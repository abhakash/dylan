package dylan.repo

import dylan.config.AppConfig
import dylan.db.Dylan
import dylan.model.Quality
import dylan.util.AppDispatchers
import dylan.util.Lane
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Settings are a handful of short strings read from the hottest paths in the app: `qualityPref()`
 * once per download job (a 200-track album heart is 200 extra serialised dbLane hops), `resume` on
 * every boot, `gc_last_ms` on every GC tick. Each of those was a full round trip through the
 * single-threaded DB lane, and it could not be made cheap by indexing a two-column table.
 *
 * So they are cached in memory, seeded once on first read and updated on write. Two properties make
 * that safe, and both are enforced here rather than assumed:
 *
 *  - **Every access to [cache] is under [mutex].** It is a `LinkedHashMap`, and a `put` from one
 *    coroutine concurrent with a `get` from another is an unsynchronised read of a container being
 *    restructured: a resize can leave a present key unreachable, and the read can observe a bucket
 *    array mid-swap. The old code read `cache[key]` *outside* the lock and wrote it inside, which is
 *    exactly that race, on the hottest read path in the app.
 *  - **The cache is the authority for a key this instance has written.** A `put` updates the table
 *    first and the cache second, both under the lock, so a `get` after a `put` always returns the
 *    value just written. It says nothing about *other* instances: each `SettingsStore` has its own
 *    cache, so a key written through one is only visible to another when the other misses and reads
 *    the table. There is one shared instance in the graph (`data.settings`); the reconciler holds a
 *    second one for its own sweep key — see its KDoc for why that is currently harmless.
 */
class SettingsStore(
    private val db: Dylan,
    private val disp: AppDispatchers,
    private val cfg: AppConfig,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, String>()

    suspend fun get(key: String): String? =
        mutex.withLock {
            // The fast path is inside the lock, deliberately. Hoisting it out saves a lock
            // acquisition on a hit and reintroduces the race the lock exists to prevent — and the
            // lock is a coroutine `Mutex`, so acquiring it does not block a thread.
            cache[key] ?: withContext(disp.on(Lane.DB)) { db.dylanQueries.getSetting(key).executeAsOneOrNull() }
                ?.also { cache[key] = it }
        }

    suspend fun put(
        key: String,
        value: String,
    ) {
        mutex.withLock {
            withContext(disp.on(Lane.DB)) { db.dylanQueries.putSetting(key, value) }
            cache[key] = value
        }
    }

    /** In-memory: the download engine reads this once per job, so a round trip per job is not free. */
    suspend fun qualityPref(): Quality {
        val name = get(KEY_QUALITY) ?: return cfg.defaultQuality
        return runCatching { Quality.valueOf(name) }.getOrDefault(cfg.defaultQuality)
    }

    suspend fun setQualityPref(q: Quality) = put(KEY_QUALITY, q.name)

    companion object {
        const val KEY_QUALITY = "quality"
    }
}
