package dylan.repo

import dylan.config.AppConfig
import dylan.db.Dylan
import dylan.model.Quality
import dylan.util.AppDispatchers
import kotlinx.coroutines.withContext

class SettingsStore(
    private val db: Dylan,
    private val disp: AppDispatchers,
    private val cfg: AppConfig,
) {
    suspend fun get(key: String): String? =
        withContext(disp.dbLane) {
            db.dylanQueries.getSetting(key).executeAsOneOrNull()
        }

    suspend fun put(
        key: String,
        value: String,
    ) = withContext(disp.dbLane) {
        db.dylanQueries.putSetting(key, value)
    }

    suspend fun qualityPref(): Quality = get("quality")?.let { runCatching { Quality.valueOf(it) }.getOrNull() } ?: cfg.defaultQuality

    suspend fun setQualityPref(q: Quality) = put("quality", q.name)
}
