package dylan.repo

import dylan.cache.CacheManager
import dylan.config.AppConfig
import dylan.db.Dylan
import dylan.db.RecentAlbums
import dylan.model.Song
import dylan.model.SongKey
import dylan.playback.decodeSnapshot
import dylan.util.AppDispatchers
import dylan.util.Clock
import dylan.util.Lane
import dylan.util.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Repo-layer catalog upsert path for songs arriving from search/album/artist payloads:
 * insert-ignore (never clobbers a live row — with FK=ON a REPLACE would cascade-delete
 * cached_files/favorites for that key) + metadata refresh via updateSongMetadata, with
 * the whole admit loop in one db.transaction so a batch never half-lands.
 *
 * Distinct from Orchestrator.admitSong (playback agent, owned there): that one skips
 * re-admit for playback-window rows, this one refreshes stale metadata on re-admit.
 */
suspend fun Dylan.admitSongs(
    disp: AppDispatchers,
    songs: List<Song>,
    clock: Clock = SystemClock,
) = withContext(disp.on(Lane.DB)) {
    if (songs.isEmpty()) return@withContext
    val now = clock.nowMs()
    transaction {
        songs.forEach { song ->
            val r = song.toRow(now)
            dylanQueries.insertSong(
                r.provider,
                r.songId,
                r.title,
                r.subtitle,
                r.albumId,
                r.albumName,
                r.artUrl150,
                r.artUrl500,
                r.durationS,
                r.has320,
                r.resolveRef,
                r.permaToken,
                r.updatedAtMs,
            )
            dylanQueries.updateSongMetadata(
                r.provider,
                r.songId,
                r.title,
                r.subtitle,
                r.albumId,
                r.albumName,
                r.artUrl150,
                r.artUrl500,
                r.durationS,
                r.has320,
                r.resolveRef,
                r.permaToken,
                r.updatedAtMs,
            )
        }
    }
}

class Favorites(
    private val db: Dylan,
    private val disp: AppDispatchers,
    private val cacheManager: CacheManager,
    private val clock: Clock = SystemClock,
) {
    val version = MutableStateFlow(0)

    suspend fun add(song: Song) {
        val now = clock.nowMs()
        withContext(disp.on(Lane.DB)) {
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
                        song.resolveRef,
                        song.permaToken,
                        now,
                    )
                }
                db.dylanQueries.addFavorite(song.key.provider, song.key.songId, now)
                // Read inside this transaction, never before it: a `remove` landing between the
                // read and the write is what used to leave a permanent phantom pin (docs §3.9).
                db.dylanQueries.setPin(1L, now, song.key.provider, song.key.songId)
            }
        }
        // The pin is a write, not a new row: nothing is pending, so nothing is reserved.
        cacheManager.enforceBudget()
        version.value += 1
    }

    suspend fun remove(key: SongKey) =
        withContext(disp.on(Lane.DB)) {
            db.transaction {
                db.dylanQueries.removeFavorite(key.provider, key.songId)
                db.dylanQueries.demotePin(key.provider, key.songId)
            }
        }.also { version.value += 1 }

    suspend fun isFavorite(key: SongKey): Boolean =
        withContext(disp.on(Lane.DB)) {
            db.dylanQueries.isFavorite(key.provider, key.songId).executeAsOne()
        }

    suspend fun all(): List<Song> =
        withContext(disp.on(Lane.DB)) {
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
        withContext(disp.on(Lane.DB)) {
            db.dylanQueries
                .recentHistory(limit.toLong())
                .executeAsList()
                .map { it.toSong() }
        }

    /** Album carousel personalization — pure SQLite: last-played album per (provider, album_id). */
    suspend fun recentAlbums(limit: Int): List<RecentAlbums> =
        withContext(disp.on(Lane.DB)) {
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
        withContext(disp.on(Lane.DB)) {
            val key = display.trim().lowercase()
            if (key.isNotEmpty()) {
                db.transaction {
                    db.dylanQueries.upsertSearchHistory(key, display.trim(), cfg.clock.nowMs())
                    db.dylanQueries.trimSearchHistory(cfg.searchHistoryLimit.toLong())
                }
            }
        }

    suspend fun recent(): List<String> =
        withContext(disp.on(Lane.DB)) {
            db.dylanQueries
                .listSearchHistory(cfg.searchHistoryLimit.toLong())
                .executeAsList()
                .map { it.display }
        }

    suspend fun clear() =
        withContext(disp.on(Lane.DB)) {
            db.dylanQueries.clearSearchHistory()
        }
}

class HomeCacheRepo(
    private val db: Dylan,
    private val disp: AppDispatchers,
    private val cfg: AppConfig,
) {
    suspend fun getJson(key: String): String? =
        withContext(disp.on(Lane.DB)) {
            val row = db.dylanQueries.getHomeCache(key).executeAsOneOrNull() ?: return@withContext null
            if (cfg.clock.nowMs() - row.fetched_at_ms > cfg.homeCacheTtlMs) null else row.json
        }

    suspend fun putJson(
        key: String,
        json: String,
    ) = withContext(disp.on(Lane.DB)) {
        db.dylanQueries.putHomeCache(key, json, cfg.clock.nowMs())
    }

    suspend fun evictWeekly() =
        withContext(disp.on(Lane.DB)) {
            db.dylanQueries.evictStaleHomeCache(cfg.clock.nowMs() - cfg.homeCacheTtlMs)
            db.dylanQueries.evictHomeCacheKeepNewest(cfg.homeCacheRowCap.toLong())
        }
}

/**
 * Quality-upgrade candidates: the predicate, the `has_320` filter and the LIMIT are all in SQL.
 * The call site used to `selectAllCached()` and then `selectSong()` per row *before* `.take(n)`,
 * so the limit bounded nothing and a 300-row cache cost 301 round trips through the single
 * dbLane every 30 minutes, blocking playback DB work for the duration.
 */
suspend fun Dylan.upgradeCandidates(
    disp: AppDispatchers,
    fromBitrate: Long,
    limit: Int,
): List<SongKey> =
    withContext(disp.on(Lane.DB)) {
        dylanQueries
            .selectUpgradeCandidates(fromBitrate, limit.toLong())
            .executeAsList()
            .map { SongKey(it.provider, it.song_id) }
    }

suspend fun Dylan.weeklyGc(
    disp: AppDispatchers,
    cfg: AppConfig,
) = withContext(disp.on(Lane.DB)) {
    val cutoff = cfg.clock.nowMs() - cfg.songsGcAgeDays * 24L * 60 * 60 * 1000
    val rawResume = dylanQueries.getSetting("resume").executeAsOneOrNull()
    val protect = rawResume?.let { decodeSnapshot(it)?.items?.map { r -> "${r.provider}:${r.songId}" } } ?: emptyList<String>()
    dylanQueries.gcSongs(cutoff, protect.ifEmpty { listOf("::") })
    // Orphan sweep: history rows whose song row is gone (pre-CASCADE DBs, or rows removed
    // while FK enforcement was off). NOT EXISTS-scoped, so history for live-queue and
    // jump-back-in songs whose rows still exist is never touched — history feeds both the
    // resume snapshot above and the Home carousel, and only truly dangling rows go.
    dylanQueries.deleteOrphanHistory()
    // The same failure mode for the cache: with PRAGMA foreign_keys off, or a store written
    // before the PRAGMA existed, a deleted song can leave cached rows behind, and those rows
    // keep bytes charged against the budget forever.
    dylanQueries.deleteOrphanLibrary()
    dylanQueries.deleteOrphanObjects()
}
