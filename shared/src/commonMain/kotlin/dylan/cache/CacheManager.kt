package dylan.cache

import dylan.config.AppConfig
import dylan.db.Cached_files
import dylan.db.Dylan
import dylan.model.SongKey
import dylan.util.AppDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okio.FileSystem

class CacheManager(
    private val db: Dylan,
    private val fs: FileSystem,
    private val paths: Paths,
    // READ-ONLY view: AppContainer's combine block is the SINGLE WRITER of protectedKeys
    // (current + next-up + in-flight + upgrade sources). CacheManager never emits here —
    // it only snapshots .value for victim exclusion (see playingKeyNeverVictim below).
    // Do not add another writer; cross-agent writes would race the evict guard.
    val protectedKeys: StateFlow<Set<SongKey>>,
    private val cfg: AppConfig,
    private val disp: AppDispatchers,
    private val log: dylan.diag.LogBuffer = dylan.diag.LogBuffer.SILENT,
) {
    val inFlightJobKeys = MutableStateFlow<Set<SongKey>>(emptySet())
    val upgradeSourceKeys = MutableStateFlow<Set<SongKey>>(emptySet())
    var notifyOnce: (String) -> Unit = {}

    private sealed interface Victim {
        data class Delete(
            val row: Cached_files,
        ) : Victim

        data class Demote(
            val row: Cached_files,
        ) : Victim
    }

    private fun partBytes(): Long =
        runCatching {
            fs
                .list(paths.audioDir)
                .filter { it.name.endsWith(".part") }
                .sumOf { fs.metadataOrNull(it)?.size ?: 0L }
        }.getOrDefault(0L)

    private fun protectTokens(exempt: Set<SongKey>): List<String> {
        val keys = protectedKeys.value + inFlightJobKeys.value + upgradeSourceKeys.value + exempt
        return keys.map { "${it.provider}:${it.songId}" }.ifEmpty { listOf("::") }
    }

    suspend fun enforceBudget(
        netNewBytes: Long = 0,
        exemptKeys: Set<SongKey> = emptySet(),
    ) = withContext(disp.io) {
        val victims = mutableListOf<Victim>()
        var usage = 0L
        var count = 0L
        // partBytes does fs I/O — must stay on io, not dbLane (R7-M3)
        val part = partBytes()
        withContext(disp.dbLane) {
            val (c, b) = db.dylanQueries.cachedCountAndBytes().executeAsOne()
            count = c
            usage = b + part + netNewBytes
        }
        val pinnedCap = (cfg.cacheMaxBytes * cfg.pinnedMaxFraction).toLong()

        var pinnedUsage = withContext(disp.dbLane) { db.dylanQueries.pinnedBytes().executeAsOne() }
        val chosenPins = mutableListOf<String>()
        while (pinnedUsage > pinnedCap) {
            val v =
                withContext(disp.dbLane) {
                    db.dylanQueries.oldestPinnedVictim(protectTokens(exemptKeys) + chosenPins).executeAsOneOrNull()
                } ?: break
            victims += Victim.Demote(v)
            chosenPins += "${v.provider}:${v.song_id}"
            pinnedUsage -= v.bytes
        }

        val chosen = mutableListOf<String>()
        while (usage > cfg.cacheMaxBytes || count + 1 > cfg.cacheMaxFiles) {
            val v =
                withContext(disp.dbLane) {
                    db.dylanQueries.lruVictim(protectTokens(exemptKeys) + chosen).executeAsOneOrNull()
                } ?: break
            victims += Victim.Delete(v)
            chosen += "${v.provider}:${v.song_id}"
            usage -= v.bytes
            count--
        }

        if (victims.isEmpty()) return@withContext

        val deletes = victims.filterIsInstance<Victim.Delete>()
        val demotes = victims.filterIsInstance<Victim.Demote>()
        if (deletes.isNotEmpty()) {
            log.i("cache", "evicting ${deletes.size} file(s) ${deletes.sumOf { it.row.bytes }}B (demoted ${demotes.size} pins)")
            deletes.forEach { log.d("cache", "victim ${it.row.provider}:${it.row.song_id} bytes=${it.row.bytes} lastUsed=${it.row.last_used_ms}") }
        }

        withContext(disp.dbLane) {
            val deleted = mutableListOf<Victim.Delete>()
            db.transaction {
                victims.forEach { v ->
                    when (v) {
                        is Victim.Delete -> {
                            // Atomic victim-select+delete: re-read the row inside the txn
                            // so a concurrently-removed victim is skipped, not double-freed.
                            val fresh =
                                db.dylanQueries
                                    .selectCached(v.row.provider, v.row.song_id)
                                    .executeAsOneOrNull() ?: return@forEach
                            val key = SongKey(fresh.provider, fresh.song_id)
                            // playingKeyNeverVictim: re-check AFTER select, BEFORE delete —
                            // a track that started playing (or enqueued/upgraded) mid-pass
                            // must survive eviction even though it was unprotected at
                            // select time. Row is left untouched; no restore needed.
                            if (key in protectedKeys.value || key in inFlightJobKeys.value || key in upgradeSourceKeys.value || key in exemptKeys) {
                                log.w("cache", "victim spared (protected mid-pass) ${key.provider}:${key.songId}")
                            } else {
                                db.dylanQueries.deleteCached(fresh.provider, fresh.song_id)
                                deleted += v
                            }
                        }
                        is Victim.Demote -> db.dylanQueries.demotePin(v.row.provider, v.row.song_id)
                    }
                }
            }
            // Files die only for rows actually deleted above — never for spared victims.
            deleted.forEach { v ->
                val key = SongKey(v.row.provider, v.row.song_id)
                runCatching { fs.delete(paths.final(key, v.row.bitrate.toInt(), v.row.ext)) }
            }
        }

        if (victims.any { it is Victim.Demote }) {
            notifyOnce("Favorites exceed the offline budget - oldest moved out of guaranteed storage.")
        }
    }

    suspend fun touch(
        key: SongKey,
        nowMs: Long,
    ) = withContext(disp.dbLane) {
        db.dylanQueries.touchUsed(nowMs, key.provider, key.songId)
    }

    suspend fun clearCacheExcludingProtected(): Long =
        withContext(disp.io) {
            val keep =
                (protectedKeys.value + inFlightJobKeys.value + upgradeSourceKeys.value)
                    .map { "${it.provider}:${it.songId}" }
                    .ifEmpty { listOf("::") }
            val doomed =
                withContext(disp.dbLane) {
                    db.dylanQueries
                        .selectAllCached()
                        .executeAsList()
                        .filter { "${it.provider}:${it.song_id}" !in keep }
                }
            withContext(disp.dbLane) {
                db.transaction {
                    doomed.forEach { db.dylanQueries.deleteCached(it.provider, it.song_id) }
                }
            }
            doomed.forEach { runCatching { fs.delete(paths.final(SongKey(it.provider, it.song_id), it.bitrate.toInt(), it.ext)) } }
            if (doomed.isNotEmpty()) log.i("cache", "clear-cache removed ${doomed.size} file(s) ${doomed.sumOf { it.bytes }}B kept=${keep.size - 1}")
            doomed.sumOf { it.bytes }
        }

    // Centralized per-key eviction — replaces app-side deleteCached+fs.delete workarounds
    suspend fun evictOne(key: SongKey): Boolean =
        withContext(disp.io) {
            if (key in protectedKeys.value || key in inFlightJobKeys.value || key in upgradeSourceKeys.value) {
                log.w("cache", "evictOne refused (protected) ${key.provider}:${key.songId}")
                return@withContext false
            }
            val row =
                withContext(disp.dbLane) {
                    db.dylanQueries.selectCached(key.provider, key.songId).executeAsOneOrNull()
                } ?: return@withContext false
            withContext(disp.dbLane) { db.dylanQueries.deleteCached(key.provider, key.songId) }
            runCatching { fs.delete(paths.final(key, row.bitrate.toInt(), row.ext)) }
            log.i("cache", "evictOne ${key.provider}:${key.songId} freed=${row.bytes}B")
            true
        }
}
