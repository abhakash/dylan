package dylan.repo

import dylan.cache.CacheManager
import dylan.config.AppConfig
import dylan.db.Dylan
import dylan.db.RecentAlbums
import dylan.db.Songs
import dylan.model.Song
import dylan.model.SongKey
import dylan.playback.decodeSnapshot
import dylan.util.AppDispatchers
import dylan.util.nowMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

internal fun Songs.toSong(): Song =
    Song(
        key = SongKey(provider, song_id),
        title = title,
        subtitle = subtitle,
        albumId = album_id,
        albumName = album_name,
        artUrl150 = art_url_150,
        artUrl500 = art_url_500,
        durationS = duration_s,
        has320 = has_320 == 1L,
        cacheable = cacheable == 1L,
        resolveRef = resolve_ref,
        permaToken = perma_token,
    )

class Favorites(
    private val db: Dylan,
    private val disp: AppDispatchers,
    private val cacheManager: CacheManager,
) {
    val version = MutableStateFlow(0)

    suspend fun add(song: Song) {
        val now = nowMs()
        withContext(disp.dbLane) {
            db.transaction {
                // Guard like admitSong — with FK=ON a plain INSERT OR REPLACE on songs would
                // cascade-delete cached_files/favorites for that key (F2). Only insert if absent.
                val exists = db.dylanQueries.selectSong(song.key.provider, song.key.songId).executeAsOneOrNull()
                if (exists == null) {
                    db.dylanQueries.insertSong(
                        song.key.provider,
                        song.key.songId,
                        song.title,
                        song.subtitle,
                        song.albumId,
                        song.albumName,
                        song.artUrl150,
                        song.artUrl500,
                        song.durationS,
                        if (song.has320) 1L else 0L,
                        if (song.cacheable) 1L else 0L,
                        song.resolveRef,
                        song.permaToken,
                        now,
                    )
                }
                db.dylanQueries.addFavorite(song.key.provider, song.key.songId, now)
                db.dylanQueries.setPin(1L, now, song.key.provider, song.key.songId)
            }
        }
        cacheManager.enforceBudget(netNewBytes = 0)
        version.value += 1
    }

    suspend fun remove(key: SongKey) =
        withContext(disp.dbLane) {
            db.transaction {
                db.dylanQueries.removeFavorite(key.provider, key.songId)
                db.dylanQueries.setPin(0L, null, key.provider, key.songId)
            }
        }.also { version.value += 1 }

    suspend fun isFavorite(key: SongKey): Boolean =
        withContext(disp.dbLane) {
            db.dylanQueries.isFavorite(key.provider, key.songId).executeAsOne()
        }

    suspend fun all(): List<Song> =
        withContext(disp.dbLane) {
            db.dylanQueries
                .allFavorites()
                .executeAsList()
                .map { it.toSong() }
        }
}

class History(
    private val db: Dylan,
    private val disp: AppDispatchers,
) {
    suspend fun recent(limit: Int): List<Song> =
        withContext(disp.dbLane) {
            db.dylanQueries
                .recentHistory(limit.toLong())
                .executeAsList()
                .map { it.toSong() }
        }

    /** Album carousel personalization — pure SQLite: last-played album per (provider, album_id). */
    suspend fun recentAlbums(limit: Int): List<RecentAlbums> =
        withContext(disp.dbLane) {
            db.dylanQueries
                .recentAlbums(limit.toLong())
                .executeAsList()
        }
}

class SearchHistoryRepo(
    private val db: Dylan,
    private val disp: AppDispatchers,
    private val cfg: AppConfig,
) {
    suspend fun record(display: String) =
        withContext(disp.dbLane) {
            val key = display.trim().lowercase()
            if (key.isNotEmpty()) {
                db.transaction {
                    db.dylanQueries.upsertSearchHistory(key, display.trim(), nowMs())
                    db.dylanQueries.trimSearchHistory(cfg.searchHistoryLimit.toLong())
                }
            }
        }

    suspend fun recent(): List<String> =
        withContext(disp.dbLane) {
            db.dylanQueries
                .listSearchHistory(cfg.searchHistoryLimit.toLong())
                .executeAsList()
                .map { it.display }
        }

    suspend fun clear() =
        withContext(disp.dbLane) {
            db.dylanQueries.clearSearchHistory()
        }
}

class HomeCacheRepo(
    private val db: Dylan,
    private val disp: AppDispatchers,
    private val cfg: AppConfig,
) {
    suspend fun getJson(key: String): String? =
        withContext(disp.dbLane) {
            val row = db.dylanQueries.getHomeCache(key).executeAsOneOrNull() ?: return@withContext null
            if (nowMs() - row.fetched_at_ms > cfg.homeCacheTtlMs) null else row.json
        }

    suspend fun putJson(
        key: String,
        json: String,
    ) = withContext(disp.dbLane) {
        db.dylanQueries.putHomeCache(key, json, nowMs())
    }

    suspend fun evictWeekly() =
        withContext(disp.dbLane) {
            db.dylanQueries.evictStaleHomeCache(nowMs() - cfg.homeCacheTtlMs)
            val keepKeys = db.dylanQueries.newestHomeKeys(cfg.homeCacheRowCap.toLong()).executeAsList()
            if (keepKeys.isNotEmpty()) db.dylanQueries.evictHomeCacheNotIn(keepKeys)
        }
}

suspend fun Dylan.weeklyGc(
    disp: AppDispatchers,
    cfg: AppConfig,
) = withContext(disp.dbLane) {
    val cutoff = nowMs() - cfg.songsGcAgeDays * 24L * 60 * 60 * 1000
    val rawResume = dylanQueries.getSetting("resume").executeAsOneOrNull()
    val protect = rawResume?.let { decodeSnapshot(it)?.items?.map { r -> "${r.provider}:${r.songId}" } } ?: emptyList<String>()
    dylanQueries.gcSongs(cutoff, protect.ifEmpty { listOf("::") })
}
