package dylan.cache

import dylan.config.AppConfig
import dylan.db.Dylan
import dylan.download.DownloadEngine
import dylan.download.DownloadJob
import dylan.download.Priority
import dylan.model.SongKey
import dylan.util.AppDispatchers
import dylan.util.nowMs
import kotlinx.coroutines.withContext
import okio.FileSystem

class Reconciler(
    private val db: Dylan,
    private val fs: FileSystem,
    private val paths: Paths,
    private val cfg: AppConfig,
    private val disp: AppDispatchers,
    private val engine: DownloadEngine,
    private val cacheManager: CacheManager,
) {
    suspend fun run() =
        withContext(disp.io) {
            val now = nowMs()
            val rows = withContext(disp.dbLane) { db.dylanQueries.selectAllCached().executeAsList() }
            val knownFinals = rows.map { paths.final(SongKey(it.provider, it.song_id), it.bitrate.toInt(), it.ext) }.toSet()

            val files = runCatching { fs.list(paths.audioDir) }.getOrDefault(emptyList())
            val parts = files.filter { it.name.endsWith(".part") }
            val finals = files.filter { it !in parts }

            parts
                .filter { p ->
                    val age = fs.metadataOrNull(p)?.lastModifiedAtMillis ?: 0L
                    age in 1 until now - cfg.partGraceHours * 3_600_000L
                }.forEach { runCatching { fs.delete(it) } }

            finals
                .filter { f ->
                    if (f in knownFinals) return@filter false
                    val age = fs.metadataOrNull(f)?.lastModifiedAtMillis ?: 0L
                    age in 1 until now - 60_000L
                }.forEach { runCatching { fs.delete(it) } }

            val survivingFinals = runCatching { fs.list(paths.audioDir) }.getOrDefault(emptyList()).filter { !it.name.endsWith(".part") }
            rows.forEach { row ->
                val f = paths.final(SongKey(row.provider, row.song_id), row.bitrate.toInt(), row.ext)
                when {
                    !fs.exists(f) || (fs.metadataOrNull(f)?.size ?: -1L) != row.bytes -> {
                        runCatching { fs.delete(f) }
                        withContext(disp.dbLane) { db.dylanQueries.deleteCached(row.provider, row.song_id) }
                    }
                    else -> {}
                }
            }

            engine.enforcePartCap()

            val intents = withContext(disp.dbLane) { db.dylanQueries.allIntents().executeAsList() }
            val cachedKeys =
                withContext(disp.dbLane) {
                    db.dylanQueries
                        .selectAllCached()
                        .executeAsList()
                        .map { SongKey(it.provider, it.song_id) }
                        .toSet()
                }
            intents.forEach { intent ->
                val key = SongKey(intent.provider, intent.song_id)
                if (key in cachedKeys) {
                    engine.dropIntent(key)
                } else {
                    engine.enqueue(
                        DownloadJob(
                            key = key,
                            reason = runCatching { Priority.valueOf(intent.reason) }.getOrDefault(Priority.USER_BULK),
                            bitrate = intent.bitrate.toInt(),
                            enqueuedAtMs = intent.enqueued_at_ms,
                        ),
                    )
                }
            }

            cacheManager.enforceBudget(netNewBytes = 0)
        }
}
