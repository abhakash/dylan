@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dylan.download

import dylan.cache.Paths
import dylan.config.AppConfig
import dylan.db.Dylan
import dylan.diag.LogBuffer
import dylan.model.Quality
import dylan.model.SongKey
import dylan.util.AppDispatchers
import dylan.util.Clock
import dylan.util.Lane
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference

/** A `.part` and everything the engine knows about it without touching the filesystem. */
internal data class PartRef(
    val key: SongKey,
    val path: Path,
    val reason: Priority,
    val lastModifiedMs: Long,
    val breakpoint: Breakpoint,
)

/**
 * The `.part` file collaborator: the incremental inventory, the `.part.meta` sidecar, and the
 * capped sweep.
 *
 * This is the Fetcher/Verifier split the audit asked for, done as behaviour rather than as a
 * facade. It exists for two reasons that are both performance or correctness, not tidiness:
 *
 *  - the inventory is maintained, so an enqueue costs one map read instead of `fs.list` of the
 *    audio directory plus a `metadataOrNull` per part plus a full `allIntents()` scan — all of it
 *    on the io lane that also serves image caching and log writes;
 *  - the sweep counts *every* part against the cap, including the one being written. The previous
 *    arithmetic excluded the executing part from the candidate pool and then compared the rest
 *    against the cap, so the directory held `maxConcurrentParts + 1` exactly when a download was
 *    running (DL-8).
 */
internal class PartStore(
    private val db: Dylan,
    private val fs: FileSystem,
    private val paths: Paths,
    private val cfg: AppConfig,
    private val disp: AppDispatchers,
    private val log: LogBuffer,
) {
    private val clock: Clock = cfg.clock
    private val index = AtomicReference<Map<SongKey, PartRef>>(emptyMap())
    private val swept = AtomicBoolean(false)
    private val lastSweepMs = AtomicLong(0L)

    val size: Int get() = index.load().size

    fun partOf(
        key: SongKey,
        bits: Int,
    ): Path = paths.part(key, bits)

    /**
     * Seed the inventory from disk. A `.part` left by a previous process is the *normal* resume
     * path — the reconciler re-enqueues it — and this is where its persisted validator comes back,
     * so the first request can carry `If-Range` (DL-3).
     */
    fun loadFromDisk() {
        val listed = listDir()
        val metas = listed.filter { it.name.endsWith(META_SUFFIX) }
        if (metas.isEmpty()) return
        val now = clock.nowMs()
        val loaded =
            metas
                .mapNotNull { sidecar ->
                    val key = partKeyOf(sidecar.name.removeSuffix(META_SUFFIX)) ?: return@mapNotNull null
                    PartMeta.read(fs, sidecar)?.let { key to it }
                }.toMap()
        if (loaded.isEmpty()) return
        index.mutate { current ->
            val seeded = loaded.mapValues { (k, bp) -> resumed(k, bp, now) }
            current + seeded
        }
        log.i("dl", "resumable parts: ${loaded.size} ${loaded.keys.joinToString { "${it.provider}:${it.songId}" }}")
    }

    /**
     * The breakpoint for a part about to be written. The on-disk size is the truth — a sidecar torn
     * by a crash is corrected, not trusted — and everything else comes from the persisted record.
     */
    fun note(
        key: SongKey,
        quality: Quality,
        onDisk: Long,
        reason: Priority,
    ): Breakpoint {
        val known = index.load()[key]?.breakpoint
        val base = known?.takeIf { it.quality == quality } ?: Breakpoint.fresh(quality, clock.nowMs())
        val bp = base.wrote(onDisk)
        index.mutate { it + (key to ref(key, paths.part(key, quality.bits), reason, clock.nowMs(), bp)) }
        return bp
    }

    /** Fold the writer's last word into the index and the sidecar. Never suspends, never throws. */
    fun persist(
        key: SongKey,
        bp: Breakpoint,
    ) {
        val part = paths.part(key, bp.quality.bits)
        index.mutate { current ->
            val prev = current[key] ?: ref(key, part, Priority.USER_BULK, clock.nowMs(), bp)
            current + (key to prev.copy(breakpoint = bp))
        }
        if (!PartMeta.write(fs, PartMeta.sidecarOf(part), bp)) {
            log.w("dl", "could not persist ${key.label} breakpoint; part stays resumable, validator lost")
        }
    }

    fun forget(key: SongKey) {
        index.mutate { it - key }
    }

    fun deleteParts(key: SongKey) {
        runCatching {
            val prefix = "${key.provider}_${paths.sanitize(key.songId)}_"
            listDir()
                .filter { it.name.startsWith(prefix) && it.name.endsWith(PART_SUFFIX) }
                .forEach { p ->
                    fs.delete(p, false)
                    PartMeta.delete(fs, PartMeta.sidecarOf(p))
                }
        }
        forget(key)
    }

    /**
     * Capped sweep. Gated: the first call after construction (which is what the Reconciler's boot
     * pass relies on), then only after [PartStore] has not swept for a while or the tracked count
     * is over budget — so the common enqueue never walks the directory.
     */
    suspend fun enforce(
        budget: Int,
        force: Boolean,
        inFlight: Set<SongKey>,
        onDropIntent: suspend (SongKey) -> Unit,
    ) {
        val now = clock.nowMs()
        if (!force && withinBudget(now, budget)) return
        lastSweepMs.store(now)
        swept.store(true)
        val parts = listDir().filter { it.name.endsWith(PART_SUFFIX) }
        val reasons =
            withContext(disp.on(Lane.DB)) {
                db.dylanQueries
                    .allIntents()
                    .executeAsList()
                    .mapNotNull { row ->
                        Priority.fromWire(row.reason)?.let { SongKey(row.provider, row.song_id) to it }
                    }.toMap()
            }
        val refs =
            parts.mapNotNull { path ->
                val key = partKeyOf(path.name.removeSuffix(PART_SUFFIX)) ?: return@mapNotNull null
                val mtime = runCatching { fs.metadataOrNull(path)?.lastModifiedAtMillis }.getOrNull() ?: now
                val bp = breakpointOf(key, path, now)
                key to ref(key, path, reasons[key] ?: Priority.PREFETCH_NEXT, mtime, bp)
            }
        index.store(refs.toMap())
        val excess = (refs.size - budget).coerceAtLeast(0)
        if (excess == 0) return
        // Victim order: PREFETCH parts first, then oldest — a just-displaced USER_NOW part, the one
        // most likely to be resumed, must not be the first thing sacrificed. Parts being written
        // are never eligible, and they do count against the budget.
        val victims =
            refs
                .filter { it.first !in inFlight }
                .sortedWith(compareBy({ it.second.reason != Priority.PREFETCH_NEXT }, { it.second.lastModifiedMs }))
                .take(excess)
        for ((victimKey, victim) in victims) {
            val held = victim.breakpoint.partBytes
            log.i("dl", "part-cap victim ${victimKey.label} prio=${victim.reason} bytes=$held")
            onDropIntent(victimKey)
            fs.delete(victim.path, false)
            PartMeta.delete(fs, PartMeta.sidecarOf(victim.path))
            forget(victimKey)
        }
    }

    private fun withinBudget(
        now: Long,
        budget: Int,
    ): Boolean = swept.load() && now - lastSweepMs.load() < SWEEP_INTERVAL_MS && size <= budget

    private fun breakpointOf(
        key: SongKey,
        path: Path,
        now: Long,
    ): Breakpoint =
        index.load()[key]?.breakpoint
            ?: PartMeta.read(fs, PartMeta.sidecarOf(path))
            ?: Breakpoint.fresh(Quality.of(bitsOf(path.name)), now)

    private fun listDir(): List<Path> = runCatching { fs.list(paths.audioDir) }.getOrDefault(emptyList())

    private fun ref(
        key: SongKey,
        path: Path,
        reason: Priority,
        mtime: Long,
        bp: Breakpoint,
    ) = PartRef(key, path, reason, mtime, bp)

    private fun bitsOf(name: String): Int {
        val tail = name.substringAfterLast('_').substringBefore('.')
        return tail.toIntOrNull() ?: DEFAULT_BITS
    }

    private fun resumed(
        key: SongKey,
        bp: Breakpoint,
        now: Long,
    ): PartRef = ref(key, paths.part(key, bp.quality.bits), Priority.USER_BULK, now, bp)

    /** `saavn_s1_128.part` → `saavn`/`s1`. A sanitized song id cannot contain an underscore. */
    private fun partKeyOf(base: String): SongKey? {
        val first = base.indexOf('_')
        val last = base.lastIndexOf('_')
        if (first <= 0 || last <= first) return null
        return SongKey(base.substring(0, first), base.substring(first + 1, last))
    }

    private companion object {
        const val PART_SUFFIX = ".part"
        const val META_SUFFIX = PART_SUFFIX + PartMeta.SUFFIX
        const val SWEEP_INTERVAL_MS = 30_000L
        const val DEFAULT_BITS = 128
    }
}
