@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dylan.download

import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.config.AppConfig
import dylan.db.Dylan
import dylan.db.Songs
import dylan.model.DylanFailure
import dylan.model.ErrorCode
import dylan.model.Quality
import dylan.model.SongKey
import dylan.provider.MusicProvider
import dylan.provider.SignedStream
import dylan.util.AppDispatchers
import dylan.util.NetClass
import dylan.util.freeDiskBytes
import dylan.util.nowMs
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.TimeSource

private class StallSignal : Exception()

private sealed interface HttpOutcome {
    data class RateLimited(
        val retryAfterMs: Long?,
    ) : HttpOutcome

    data object Forbidden : HttpOutcome

    data object NotFound : HttpOutcome

    data object RangeNotSatisfiable : HttpOutcome

    data class Full(
        val cl: Long?,
        val etag: String?,
        val ct: String?,
    ) : HttpOutcome

    data class Partial(
        val cl: Long?,
        val crTotal: Long?,
        val etag: String?,
        val ct: String?,
    ) : HttpOutcome

    data class Other(
        val status: Int,
    ) : HttpOutcome
}

private enum class StreamErr { Ok, Stall, Storage, Cancelled }

private enum class Step { HYDRATE, QUALITY, DEDUPE, SIZE, RESOLVE, REQUEST, VERIFY, COMMIT }

class DownloadEngine(
    private val db: Dylan,
    private val fs: FileSystem,
    private val paths: Paths,
    private val cfg: AppConfig,
    private val disp: AppDispatchers,
    private val provider: MusicProvider,
    private val bulk: HttpClient,
    private val breakers: Breakers,
    private val cacheManager: CacheManager,
    private val netClass: () -> NetClass,
    private val qualityPref: suspend () -> Quality,
    private val otherEndpointsHealthy: () -> Boolean = { true },
    private val log: dylan.diag.LogBuffer = dylan.diag.LogBuffer.SILENT,
) {
    private val supervisor = SupervisorJob()
    private val scope =
        CoroutineScope(
            supervisor + disp.io +
                kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
                    dylan.util.logErr("dylan-engine: ${t.message ?: t::class.simpleName}")
                },
        )
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val queue = mutableListOf<DownloadJob>()
    private var executing: Job? = null
    private var executingKey: SongKey? = null

    val states = MutableStateFlow<Map<SongKey, JobState>>(emptyMap())
    val progress = MutableStateFlow<Map<SongKey, Int>>(emptyMap())
    private var lastProgressEmit = 0L

    fun stop() {
        supervisor.cancel()
    }

    fun start() {
        scope.launch { loop() }
    }

    fun enqueue(job: DownloadJob) {
        log.i("dl", "enqueue ${job.key.provider}:${job.key.songId} prio=${job.reason} bits=${job.bitrate}")
        scope.launch {
            states.update { it - job.key }
            mutex.withLock {
                val existing = queue.firstOrNull { it.key == job.key }
                if (existing != null && existing.reason.ordinal < job.reason.ordinal) {
                    log.d("dl", "enqueue skipped (higher prio queued) ${job.key.provider}:${job.key.songId}")
                    return@launch
                }
                queue.removeAll { it.key == job.key }
                queue += job
                queue.sortWith(compareBy({ it.reason.ordinal }, { it.enqueuedAtMs }))
            }
            runCatching {
                db.dylanQueries.upsertIntent(
                    job.key.provider,
                    job.key.songId,
                    job.reason.name,
                    job.bitrate.toLong(),
                    job.enqueuedAtMs,
                )
            }
            enforcePartCap()
            wake.trySend(Unit)
        }
    }

    fun cancel(
        key: SongKey,
        keepPart: Boolean,
    ) {
        scope.launch {
            mutex.withLock { queue.removeAll { it.key == key } }
            if (executingKey == key) executing?.cancel(CancellationException("preempted"))
            states.update { it + (key to JobState.Cancelled) }
            progress.update { it - key }
            if (!keepPart) deleteParts(key)
        }
    }

    fun dropIntent(key: SongKey) {
        runCatching { db.dylanQueries.deleteIntent(key.provider, key.songId) }
    }

    suspend fun enforcePartCap() {
        val parts =
            runCatching { fs.list(paths.audioDir) }
                .getOrDefault(emptyList())
                .filter { it.name.endsWith(".part") }
        if (parts.size <= cfg.maxConcurrentParts) return
        val executingPrefix = executingKey?.let { "${it.provider}_${paths.sanitize(it.songId)}_" }
        val eligible = parts.filter { executingPrefix == null || !it.name.startsWith(executingPrefix) }
        if (eligible.size <= cfg.maxConcurrentParts) return
        val intentMap =
            withContext(disp.dbLane) {
                db.dylanQueries
                    .allIntents()
                    .executeAsList()
                    .associateBy { "${it.provider}:${it.song_id}" }
            }
        val reasonOf = mutableMapOf<Path, Priority>()
        for (p in eligible) {
            val parsed = parsePartName(p.name) ?: continue
            val key = "${parsed.first}:${parsed.second}"
            reasonOf[p] = intentMap[key]?.let { runCatching { Priority.valueOf(it.reason) }.getOrNull() } ?: Priority.PREFETCH_NEXT
        }
        // Victim order: PREFETCH parts first, then oldest (§7.1) — a just-preempted USER_NOW part
        // you are most likely to resume must not be the first thing sacrificed.
        val victims =
            eligible
                .sortedWith(
                    compareBy(
                        { reasonOf[it] != Priority.PREFETCH_NEXT },
                        { runCatching { fs.metadataOrNull(it)?.lastModifiedAtMillis ?: 0L }.getOrDefault(0L) },
                    ),
                ).take(eligible.size - cfg.maxConcurrentParts)
        victims.forEach { p ->
            parsePartName(p.name)?.let { (prov, sid) -> dropIntent(SongKey(prov, sid)) }
            runCatching { fs.delete(p) }
        }
    }

    private fun parsePartName(name: String): Pair<String, String>? {
        val base = name.removeSuffix(".part")
        val firstUnderscore = base.indexOf('_')
        val lastUnderscore = base.lastIndexOf('_')
        if (firstUnderscore <= 0 || lastUnderscore <= firstUnderscore) return null
        return base.substring(0, firstUnderscore) to base.substring(firstUnderscore + 1, lastUnderscore)
    }

    private suspend fun loop() {
        while (true) {
            val job =
                mutex.withLock { queue.removeFirstOrNull() } ?: run {
                    wake.receive()
                    continue
                }
            // Single-flight per key: a duplicate enqueued while the same key executes would run a
            // second worker against the same .part file — the executing job owns the outcome.
            if (job.key == executingKey) continue
            val j = scope.launch { runJob(job) }
            executing = j
            executingKey = job.key
            j.join()
            executing = null
            executingKey = null
            wake.trySend(Unit)
        }
    }

    private suspend fun runJob(job: DownloadJob) {
        val key = job.key
        cacheManager.inFlightJobKeys.update { it + key }
        var resolveCount = 0
        var attempts = 0
        var rangeRestarts = 0
        var etag: String? = null
        var partB = 0L
        var segStart = 0L
        var segLen = 0L
        var total: Long? = null
        var expectedB = 0L
        var q = Quality.BITRATE_128
        var ext = "m4a"
        var signed: SignedStream? = null
        var songRow: Songs? = null
        var contentType: String? = null
        var step = Step.HYDRATE

        try {
            while (true) {
                when (step) {
                    Step.HYDRATE -> {
                        states.update { it + (key to JobState.Queued) }
                        songRow = withContext(disp.dbLane) {
                            db.dylanQueries.selectSong(key.provider, key.songId).executeAsOneOrNull()
                        } ?: run {
                            dropIntent(key)
                            return
                        }
                        if (songRow.cacheable != 1L) return fail(key, DylanFailure(ErrorCode.NOT_CACHEABLE, key))
                        if (songRow.resolve_ref.isNullOrBlank()) return fail(key, DylanFailure(ErrorCode.NO_SOURCE, key))
                        step = Step.QUALITY
                    }

                    Step.QUALITY -> {
                        val metered = netClass() == NetClass.METERED
                        val wanted = if (metered) cfg.meteredQuality else qualityPref()
                        q = if (songRow!!.has_320 != 1L) Quality.BITRATE_128 else wanted
                        step = Step.DEDUPE
                    }

                    Step.DEDUPE -> {
                        // §7.3 step 5 sufficiency: a cached entry at/above the wanted bitrate IS the
                        // deliverable — re-fetching would burn bandwidth (and on metered, violate
                        // "never spend cellular upgrading"). Below-wanted + metered ⇒ serve as-is.
                        val entry =
                            withContext(disp.dbLane) {
                                db.dylanQueries.selectCached(key.provider, key.songId).executeAsOneOrNull()
                            }
                        if (entry != null && (entry.bitrate >= q.bits.toLong() || netClass() == NetClass.METERED)) {
                            cacheManager.touch(key, nowMs())
                            states.update { it + (key to JobState.Done(entry.bytes, entry.bitrate.toInt())) }
                            progress.update { it - key }
                            dropIntent(key)
                            return
                        }
                        step = Step.SIZE
                    }

                    Step.SIZE -> {
                        expectedB = ceil(songRow!!.duration_s * q.bps.toDouble()).toLong()
                        partB = sizeOf(paths.part(key, q.bits))
                        val netNew = max(0L, (expectedB * cfg.estimatePadding).toLong() - partB)
                        cacheManager.enforceBudget(netNewBytes = netNew)
                        val free = freeDiskBytes(paths.audioDir.toString())
                        if (free >= 0 && free < max(cfg.diskFloorBytes, 2 * netNew)) {
                            return fail(key, DylanFailure(ErrorCode.STORAGE, key))
                        }
                        step = Step.RESOLVE
                    }

                    Step.RESOLVE -> {
                        states.update { it + (key to JobState.Resolving) }
                        resolveCount++
                        if (resolveCount > cfg.resolveCapPerJob) {
                            return fail(key, DylanFailure(ErrorCode.RESOLVE_LIMIT, key))
                        }
                        signed = provider.resolveStream(songRow!!.resolve_ref!!, q)
                        if (signed == null) {
                            if (++attempts <= cfg.dlRetries) {
                                delay(cfg.dlBackoffBaseMs * attempts)
                            } else {
                                return fail(key, DylanFailure(ErrorCode.NETWORK, key))
                            }
                        } else {
                            step = Step.REQUEST
                        }
                    }

                    Step.REQUEST -> {
                        val s = signed!!
                        val breaker = breakers.forHost(Url(s.url).host)
                        val now = nowMs()
                        if (breaker.paused(now)) {
                            delay(min(breaker.pausedUntilMs - now, 5_000))
                            continue
                        }
                        states.update { it + (key to JobState.Downloading(partB, total ?: expectedB.takeIf { e -> e > 0 })) }
                        val hadRange = partB > 0
                        var outcome: HttpOutcome? = null
                        var streamResult: StreamErr? = null
                        val startedAt = TimeSource.Monotonic.markNow()
                        val lastMark = AtomicReference(TimeSource.Monotonic.markNow())
                        try {
                            bulk
                                .prepareGet(s.url) {
                                    header(HttpHeaders.AcceptEncoding, "identity")
                                    if (hadRange) header(HttpHeaders.Range, "bytes=$partB-")
                                    etag?.let { header(HttpHeaders.IfRange, it) }
                                    header(HttpHeaders.UserAgent, cfg.userAgent)
                                    header(HttpHeaders.Referrer, cfg.apiBaseUrl.substringBefore("/api.php") + "/")
                                }.execute { r ->
                                    when (val o = classify(r)) {
                                        is HttpOutcome.Full -> {
                                            contentType = o.ct
                                            if (hadRange) {
                                                rangeRestarts++
                                                truncate(paths.part(key, q.bits))
                                                partB = 0
                                                if (rangeRestarts <= cfg.rangeRestartsCap) {
                                                    outcome = o
                                                    return@execute
                                                }
                                            }
                                            segStart = 0
                                            segLen = o.cl ?: 0L
                                            total = o.cl
                                            o.etag?.let { etag = it }
                                            streamResult =
                                                streamInto(r, paths.part(key, q.bits), segStart, total, expectedB, startedAt, lastMark, key)
                                        }
                                        is HttpOutcome.Partial -> {
                                            contentType = o.ct
                                            segStart = partB
                                            segLen = o.cl ?: 0L
                                            total = o.crTotal
                                            o.etag?.let { etag = it }
                                            streamResult =
                                                streamInto(r, paths.part(key, q.bits), segStart, total, expectedB, startedAt, lastMark, key)
                                        }
                                        else -> outcome = o
                                    }
                                }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            outcome = HttpOutcome.Other(-1)
                        }
                        when {
                            streamResult == StreamErr.Storage ->
                                return fail(key, DylanFailure(ErrorCode.STORAGE, key))
                            streamResult == StreamErr.Stall ->
                                if (++attempts <= cfg.dlRetries) {
                                    delay(backoff(attempts))
                                    step = Step.REQUEST
                                } else {
                                    return fail(key, DylanFailure(ErrorCode.NETWORK_TIMEOUT, key))
                                }
                            streamResult == StreamErr.Cancelled -> return
                            streamResult == StreamErr.Ok -> step = Step.VERIFY
                            else ->
                                when (val o = outcome) {
                                    is HttpOutcome.RateLimited -> {
                                        breaker.pauseUntil(nowMs() + (o.retryAfterMs ?: 5_000))
                                        if (job.reason == Priority.USER_NOW) return fail(key, DylanFailure(ErrorCode.RATE_LIMITED, key))
                                        requeueLater(job, o.retryAfterMs ?: 5_000)
                                        return
                                    }
                                    is HttpOutcome.Forbidden ->
                                        if (resolveCount < cfg.resolveCapPerJob) {
                                            step = Step.RESOLVE
                                        } else {
                                            return fail(
                                                key,
                                                DylanFailure(
                                                    if (otherEndpointsHealthy()) ErrorCode.FORBIDDEN_REGION else ErrorCode.EXPIRED,
                                                    key,
                                                ),
                                            )
                                        }
                                    is HttpOutcome.NotFound -> return fail(key, DylanFailure(ErrorCode.NOT_FOUND, key))
                                    is HttpOutcome.RangeNotSatisfiable -> {
                                        rangeRestarts++
                                        truncate(paths.part(key, q.bits))
                                        partB = 0
                                        etag = null
                                        step = Step.REQUEST
                                    }
                                    is HttpOutcome.Full -> step = Step.REQUEST
                                    is HttpOutcome.Partial -> {}
                                    is HttpOutcome.Other ->
                                        if (++attempts <= cfg.dlRetries) {
                                            delay(backoff(attempts))
                                            step = Step.REQUEST
                                        } else {
                                            return fail(key, DylanFailure(ErrorCode.NETWORK, key))
                                        }
                                    null ->
                                        if (++attempts <= cfg.dlRetries) {
                                            delay(backoff(attempts))
                                            step = Step.REQUEST
                                        } else {
                                            return fail(key, DylanFailure(ErrorCode.NETWORK, key))
                                        }
                                }
                        }
                    }

                    Step.VERIFY -> {
                        states.update { it + (key to JobState.Verifying) }
                        val tmp = paths.part(key, q.bits)
                        val finalSize = sizeOf(tmp)
                        val floor = ceil(songRow!!.duration_s * q.bps.toDouble() * 0.90).toLong()
                        val expectedTotal = total ?: (if (segStart > 0) segStart + segLen else floor)
                        if (total != null && finalSize != expectedTotal) {
                            deleteQuietly(tmp)
                            return fail(key, DylanFailure(ErrorCode.CORRUPT_SIZE, key))
                        }
                        if (total == null && finalSize < expectedTotal) {
                            deleteQuietly(tmp)
                            return fail(key, DylanFailure(ErrorCode.CORRUPT_SIZE, key))
                        }
                        val derived = extFor(contentType, signed!!.type)
                        if (derived == null) {
                            deleteQuietly(tmp)
                            return fail(key, DylanFailure(ErrorCode.UNSUPPORTED, key))
                        }
                        ext = derived
                        if (ext == "m4a" && !sniffFtyp(tmp)) {
                            deleteQuietly(tmp)
                            return fail(key, DylanFailure(ErrorCode.CORRUPT_CONTAINER, key))
                        }
                        step = Step.COMMIT
                    }

                    Step.COMMIT -> {
                        val tmp = paths.part(key, q.bits)
                        val finalPath = paths.final(key, q.bits, ext)
                        val finalSize = sizeOf(tmp)
                        val now = nowMs()
                        val favorited =
                            withContext(disp.dbLane) {
                                db.dylanQueries.isFavorite(key.provider, key.songId).executeAsOne()
                            }
                        val prev =
                            withContext(disp.dbLane) {
                                db.dylanQueries.selectCached(key.provider, key.songId).executeAsOneOrNull()
                            }
                        if (prev != null && prev.bitrate == q.bits.toLong() && prev.ext == ext && prev.bytes == finalSize) {
                            dylan.util.fsRename(tmp.toString(), finalPath.toString())
                            dropIntent(key)
                            states.update { it + (key to JobState.Done(finalSize, q.bits)) }
                            progress.update { it - key }
                            return
                        }
                        dylan.util.fsRename(tmp.toString(), finalPath.toString())
                        val ok =
                            runCatching {
                                withContext(disp.dbLane) {
                                    db.transaction {
                                        db.dylanQueries.deleteCached(key.provider, key.songId)
                                        db.dylanQueries.insertCached(
                                            key.provider,
                                            key.songId,
                                            q.bits.toLong(),
                                            ext,
                                            finalSize,
                                            now,
                                            prev?.last_used_ms,
                                            prev?.play_count ?: 0L,
                                            if (favorited || prev?.pinned == 1L) 1L else 0L,
                                            prev?.pinned_at_ms ?: if (favorited) now else null,
                                        )
                                    }
                                }
                            }.isSuccess
                        if (!ok) {
                            deleteQuietly(finalPath)
                            return fail(key, DylanFailure(ErrorCode.STORAGE, key))
                        }
                        if (prev != null && (prev.bitrate != q.bits.toLong() || prev.ext != ext)) {
                            deleteQuietly(paths.final(key, prev.bitrate.toInt(), prev.ext))
                        }
                        cacheManager.enforceBudget(netNewBytes = 0, exemptKeys = setOf(key))
                        dropIntent(key)
                        states.update { it + (key to JobState.Done(finalSize, q.bits)) }
                        progress.update { it - key }
                        return
                    }
                }
            }
        } catch (e: CancellationException) {
            states.update { it + (key to JobState.Cancelled) }
            throw e
        } finally {
            cacheManager.inFlightJobKeys.update { it - key }
        }
    }

    private suspend fun streamInto(
        r: HttpResponse,
        partPath: okio.Path,
        startOffset: Long,
        totalLen: Long?,
        expectedB: Long,
        startedAt: TimeSource.Monotonic.ValueTimeMark,
        lastMark: AtomicReference<TimeSource.Monotonic.ValueTimeMark>,
        key: SongKey,
    ): StreamErr =
        coroutineScope {
            val ch: ByteReadChannel = r.bodyAsChannel()
            var result = StreamErr.Ok
            val stalled = AtomicInt(0)
            val copy =
                launch {
                    try {
                        val h = fs.openReadWrite(partPath, mustCreate = false, mustExist = false)
                        try {
                            var pos = startOffset
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = ch.readAvailable(buf, 0, buf.size)
                                if (n == -1) break
                                if (n == 0) continue
                                h.write(pos, buf, 0, n)
                                pos += n
                                lastMark.store(TimeSource.Monotonic.markNow())
                                emitProgress(key, pos, totalLen ?: expectedB)
                            }
                        } finally {
                            runCatching { h.close() }
                        }
                    } catch (e: CancellationException) {
                        result = if (stalled.load() == 1) StreamErr.Stall else StreamErr.Cancelled
                    } catch (e: Exception) {
                        result = StreamErr.Storage
                    }
                }
            val watchdog =
                launch {
                    while (isActive && copy.isActive) {
                        delay(cfg.stallWatchdogTickMs)
                        if (!copy.isActive) break
                        val sinceChunk = lastMark.load().elapsedNow().inWholeMilliseconds
                        val totalElapsed = startedAt.elapsedNow().inWholeMilliseconds
                        if (sinceChunk > cfg.stallTimeoutMs || totalElapsed > max(60_000, expectedB / 20)) {
                            stalled.store(1)
                            copy.cancel(CancellationException("stall"))
                            break
                        }
                    }
                }
            copy.join()
            watchdog.cancel()
            result
        }

    private fun classify(r: HttpResponse): HttpOutcome =
        when (r.status.value) {
            429, 503 -> HttpOutcome.RateLimited(r.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.times(1000))
            401, 403 -> HttpOutcome.Forbidden
            404 -> HttpOutcome.NotFound
            416 -> HttpOutcome.RangeNotSatisfiable
            200 ->
                HttpOutcome.Full(
                    cl = r.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
                    etag = r.headers[HttpHeaders.ETag],
                    ct = r.headers[HttpHeaders.ContentType],
                )
            206 -> {
                val cr = r.headers[HttpHeaders.ContentRange]
                HttpOutcome.Partial(
                    cl = r.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
                    crTotal =
                        cr?.let {
                            Regex("bytes\\s+\\d+-\\d+/(\\d+)")
                                .find(it)
                                ?.groupValues
                                ?.get(1)
                                ?.toLongOrNull()
                        },
                    etag = r.headers[HttpHeaders.ETag],
                    ct = r.headers[HttpHeaders.ContentType],
                )
            }
            else -> HttpOutcome.Other(r.status.value)
        }

    private fun emitProgress(
        key: SongKey,
        loaded: Long,
        denom: Long,
    ) {
        val n = nowMs()
        if (n - lastProgressEmit < 250 && loaded < denom) return
        lastProgressEmit = n
        val pct = (((loaded * 100) / denom.coerceAtLeast(1)).coerceIn(0L, 99L)).toInt()
        progress.update { it + (key to pct) }
    }

    private suspend fun fail(
        key: SongKey,
        err: DylanFailure,
    ) {
        states.update { it + (key to JobState.Failed(err, willRetry = false)) }
        progress.update { it - key }
        dropIntent(key)
    }

    private fun requeueLater(
        job: DownloadJob,
        delayMs: Long,
    ) {
        scope.launch {
            delay(delayMs)
            mutex.withLock {
                queue.removeAll { it.key == job.key }
                queue += job
                queue.sortWith(compareBy({ it.reason.ordinal }, { it.enqueuedAtMs }))
            }
            wake.trySend(Unit)
        }
    }

    private fun backoff(attempt: Int): Long = cfg.dlBackoffBaseMs * attempt + Random.nextLong(cfg.dlBackoffBaseMs / 2)

    private fun sizeOf(p: okio.Path): Long = runCatching { fs.metadataOrNull(p)?.size ?: 0L }.getOrDefault(0L)

    private fun deleteQuietly(p: okio.Path) = runCatching { fs.delete(p) }

    private fun truncate(p: okio.Path) {
        runCatching {
            if (fs.exists(p)) {
                val h = fs.openReadWrite(p)
                try {
                    h.resize(0)
                } finally {
                    runCatching { h.close() }
                }
            }
        }
    }

    private fun deleteParts(key: SongKey) {
        runCatching {
            val prefix = "${key.provider}_${paths.sanitize(key.songId)}_"
            fs
                .list(paths.audioDir)
                .filter { it.name.startsWith(prefix) && it.name.endsWith(".part") }
                .forEach { fs.delete(it) }
        }
    }

    private fun sniffFtyp(p: okio.Path): Boolean =
        runCatching {
            val h = fs.openReadOnly(p)
            try {
                val buf = ByteArray(4)
                var read = 0
                while (read < 4) {
                    val n = h.read(4L + read, buf, read, 4 - read)
                    if (n <= 0) break
                    read += n
                }
                read == 4 && buf.decodeToString() == "ftyp"
            } finally {
                runCatching { h.close() }
            }
        }.getOrDefault(false)

    private fun extFor(
        contentType: String?,
        signedType: String?,
    ): String? {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase()
        return when {
            ct?.contains("mpeg") == true || ct?.contains("mp3") == true -> "mp3"
            ct?.contains("mp4") == true || ct?.contains("m4a") == true -> "m4a"
            signedType == "mp4" -> "m4a"
            signedType == "mp3" -> "mp3"
            else -> null
        }
    }
}
