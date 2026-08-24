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
    val protectedKeys: StateFlow<Set<SongKey>>,
    private val cfg: AppConfig,
    private val disp: AppDispatchers,
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

        withContext(disp.dbLane) {
            db.transaction {
                victims.forEach { v ->
                    when (v) {
                        is Victim.Delete -> db.dylanQueries.deleteCached(v.row.provider, v.row.song_id)
                        is Victim.Demote -> db.dylanQueries.demotePin(v.row.provider, v.row.song_id)
                    }
                }
            }
        }

        victims.filterIsInstance<Victim.Delete>().forEach { v ->
            val key = SongKey(v.row.provider, v.row.song_id)
            if (key in protectedKeys.value || key in inFlightJobKeys.value || key in upgradeSourceKeys.value || key in exemptKeys) {
                withContext(disp.dbLane) {
                    db.dylanQueries.replaceCachedRow(
                        v.row.provider,
                        v.row.song_id,
                        v.row.bitrate,
                        v.row.ext,
                        v.row.bytes,
                        v.row.cached_at_ms,
                        v.row.last_used_ms,
                        v.row.play_count,
                        v.row.pinned,
                        v.row.pinned_at_ms,
                    )
                }
            } else {
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
            doomed.sumOf { it.bytes }
        }

    // Centralized per-key eviction — replaces app-side deleteCached+fs.delete workarounds
    suspend fun evictOne(key: SongKey): Boolean =
        withContext(disp.io) {
            if (key in protectedKeys.value || key in inFlightJobKeys.value || key in upgradeSourceKeys.value) return@withContext false
            val row =
                withContext(disp.dbLane) {
                    db.dylanQueries.selectCached(key.provider, key.songId).executeAsOneOrNull()
                } ?: return@withContext false
            withContext(disp.dbLane) { db.dylanQueries.deleteCached(key.provider, key.songId) }
            runCatching { fs.delete(paths.final(key, row.bitrate.toInt(), row.ext)) }
            true
        }
}
