package dylan.repo

import dylan.config.AppConfig
import dylan.db.Dylan
import dylan.model.Quality
import dylan.util.AppDispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Settings are a handful of short strings read from the hottest paths in the app: `qualityPref()`
 * once per download job (a 200-track album heart is 200 extra serialised dbLane hops), `resume` on
 * every boot, `gc_last_ms` on every GC tick. Each of those was a full round trip through the
 * single-threaded DB lane, and it could not be made cheap by indexing a two-column table.
 *
 * So they are cached in memory, seeded once on first read and updated on write. The invariant
 * that makes this safe is that [SettingsStore] is the only writer to the `settings` table for
 * every key that is read through here; the Reconciler's weekly-sweep timestamp is the exception
 * and is read back through the same instance.
 */
class SettingsStore(
    private val db: Dylan,
    private val disp: AppDispatchers,
    private val cfg: AppConfig,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, String>()

    suspend fun get(key: String): String? {
        cache[key]?.let { return it }
        return mutex.withLock {
            cache[key] ?: withContext(disp.dbLane) { db.dylanQueries.getSetting(key).executeAsOneOrNull() }
                ?.also { cache[key] = it }
        }
    }

    suspend fun put(
        key: String,
        value: String,
    ) {
        mutex.withLock {
            withContext(disp.dbLane) { db.dylanQueries.putSetting(key, value) }
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
