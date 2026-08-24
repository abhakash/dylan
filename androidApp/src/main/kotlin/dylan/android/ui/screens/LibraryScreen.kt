package dylan.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dylan.android.ui.Dyl
import dylan.android.ui.LocalDylanTokens
import dylan.android.ui.components.SectionTitle
import dylan.android.ui.components.SongRow
import dylan.android.ui.components.quietLoad
import dylan.android.ui.components.rememberFavoriteKeys
import dylan.android.ui.components.rememberSongActions
import dylan.di.AppContainer
import dylan.model.Song
import dylan.model.SongKey
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    container: AppContainer,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val t = LocalDylanTokens.current
    var favorites by remember { mutableStateOf(emptyList<Song>()) }
    var jumpBack by remember { mutableStateOf(emptyList<Song>()) }
    var downloadCount by remember { mutableIntStateOf(0) }
    var downloadBytes by remember { mutableLongStateOf(0L) }
    val actions = rememberSongActions(container)
    val favKeys = rememberFavoriteKeys(container)
    val favVersion by container.favorites.version.collectAsState()

    LaunchedEffect(favVersion) {
        favorites = quietLoad(emptyList()) { container.favorites.all() }
        jumpBack = quietLoad(emptyList()) { container.history.recent(5) }
        val rows =
            kotlinx.coroutines.withContext(container.disp.dbLane) {
                quietLoad(emptyList<dylan.db.Cached_files>()) {
                    container.db.dylanQueries
                        .selectAllCached()
                        .executeAsList()
                }
            }
        downloadCount = rows.size
        downloadBytes = rows.sumOf { it.bytes }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
    ) {
        SectionTitle("Library")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "downloads-summary") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(t.surfaceVariant)
                        .clickable(onClick = onOpenDownloads)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Dyl.Library, contentDescription = null, tint = t.primary)
                    Column(Modifier.weight(1f)) {
                        Text("Downloads", style = MaterialTheme.typography.titleMedium, color = t.textPrimary)
                        Text(
                            if (downloadCount == 0) "Nothing saved yet" else "$downloadCount songs · ${formatBytes(downloadBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = t.textSecondary,
                        )
                    }
                    Icon(Dyl.ChevronRight, contentDescription = null, tint = t.textSecondary)
                }
            }
            if (favorites.isEmpty()) {
                item(key = "favorites-empty") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        SectionTitle("Your favorites")
                        Text(
                            "Songs you favorite appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = t.textSecondary,
                        )
                    }
                }
            } else {
                item(key = "favorites-title") { SectionTitle("Your favorites") }
                items(favorites, key = { "fv" + it.key.songId }) { song ->
                    SongRow(
                        song = song,
                        isFavorite = true,
                        onTap = { onPlaySongs(favorites, favorites.indexOfFirst { it.key == song.key }.coerceAtLeast(0)) },
                        onPlayNext = { actions.playNext(song) },
                        onAddLast = { actions.addToQueue(song) },
                        onFavorite = { actions.toggleFavorite(song) },
                    )
                }
            }
            if (jumpBack.isNotEmpty()) {
                item(key = "history-title") { SectionTitle("Jump back in") }
                items(jumpBack, key = { "jb" + it.key.songId }) { song ->
                    SongRow(
                        song = song,
                        isFavorite = song.key in favKeys,
                        onTap = { onPlaySongs(listOf(song), 0) },
                        onPlayNext = { actions.playNext(song) },
                        onAddLast = { actions.addToQueue(song) },
                        onFavorite = { actions.toggleFavorite(song) },
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadsScreen(
    container: AppContainer,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onBack: () -> Unit,
) {
    val t = LocalDylanTokens.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var songs by remember { mutableStateOf(listOf<Pair<Song, dylan.db.Cached_files>>()) }
    var pendingRemove by remember { mutableStateOf<Pair<Song, dylan.db.Cached_files>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val progress by container.downloads.progress.collectAsState()
    val actions = rememberSongActions(container)
    val favKeys = rememberFavoriteKeys(container)

    LaunchedEffect(reload) {
        songs =
            kotlinx.coroutines.withContext(container.disp.dbLane) {
                quietLoad(emptyList<Pair<Song, dylan.db.Cached_files>>()) {
                    container.db.dylanQueries.selectAllCached().executeAsList().mapNotNull { r ->
                        container.db.dylanQueries
                            .selectSong(r.provider, r.song_id)
                            .executeAsOneOrNull()
                            ?.let { s -> s.toSong() to r }
                    }
                }
            }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Dyl.ArrowBack, contentDescription = "Back", tint = t.textPrimary)
            }
            Text("Downloads", style = MaterialTheme.typography.titleLarge, color = t.textPrimary)
        }
        if (songs.isEmpty()) {
            Text(
                if (progress.isEmpty()) "Nothing saved yet. Play something and it lands here." else "Downloading…",
                style = MaterialTheme.typography.bodyMedium,
                color = t.textSecondary,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(songs, key = { it.second.provider + ":" + it.second.song_id }) { (song, row) ->
                    SongRow(
                        song = song,
                        isCached = true,
                        isFavorite = SongKey(row.provider, row.song_id) in favKeys,
                        sizeLabel = formatBytes(row.bytes),
                        onTap = { onPlaySongs(songs.map { it.first }, songs.indexOfFirst { it.second.song_id == row.song_id }.coerceAtLeast(0)) },
                        onDownload = null,
                        onRemoveDownload =
                            if (isRemovable(container, SongKey(row.provider, row.song_id))) {
                                { pendingRemove = song to row }
                            } else {
                                null
                            },
                        onPlayNext = { actions.playNext(song) },
                        onAddLast = { actions.addToQueue(song) },
                        onFavorite = { actions.toggleFavorite(song) },
                    )
                }
                item {
                    val totalBytes = songs.sumOf { it.second.bytes }
                    Text(
                        "Cached audio ${formatBytes(totalBytes)} of ${formatBytes(container.cfg.cacheMaxBytes)} · ${songs.size} songs",
                        style = MaterialTheme.typography.labelSmall,
                        color = t.textSecondary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    pendingRemove?.let { (song, row) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove download?", style = MaterialTheme.typography.titleMedium, color = t.textPrimary) },
            text = {
                Text(
                    "\"${song.title}\" (${formatBytes(row.bytes)}) will be deleted from storage.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = t.textSecondary,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingRemove = null
                    scope.launch {
                        runCatching { container.cacheManager.evictOne(SongKey(row.provider, row.song_id)) }
                        reload++
                    }
                }) { Text("Remove", color = t.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingRemove = null }) { Text("Cancel", color = t.textSecondary) }
            },
            containerColor = t.surface,
        )
    }
}

// A cached song is only removable when nothing depends on it: not playing/queued (protected),
// not mid-download, not an in-progress quality-upgrade source. Composed from existing shared
// CacheManager flows + queries because CacheManager has no per-key evict API yet.
private fun isRemovable(
    container: AppContainer,
    key: SongKey,
): Boolean {
    val cm = container.cacheManager
    return key !in cm.protectedKeys.value &&
        key !in cm.inFlightJobKeys.value &&
        key !in cm.upgradeSourceKeys.value
}

internal fun dylan.db.Songs.toSong(): Song =
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
        permaToken = null,
    )

internal fun formatBytes(b: Long): String =
    when {
        b >= 1L shl 30 -> "%.1f GB".format(b.toDouble() / (1L shl 30))
        b >= 1L shl 20 -> "%.1f MB".format(b.toDouble() / (1L shl 20))
        b >= 1L shl 10 -> "%.1f KB".format(b.toDouble() / (1L shl 10))
        else -> "$b B"
    }
