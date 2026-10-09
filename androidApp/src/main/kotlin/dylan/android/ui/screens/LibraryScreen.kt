package dylan.android.ui.screens

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dylan.android.ui.Copy
import dylan.android.ui.Dyl
import dylan.android.ui.LocalDylanTokens
import dylan.android.ui.components.SectionTitle
import dylan.android.ui.components.SongActions
import dylan.android.ui.components.SongRow
import dylan.android.ui.components.canPlay
import dylan.android.ui.components.quietLoad
import dylan.android.ui.components.rememberAnyDownloading
import dylan.android.ui.components.rememberCachedKeys
import dylan.android.ui.components.rememberFavoriteKeys
import dylan.android.ui.components.rememberIsOnline
import dylan.android.ui.components.rememberSongActions
import dylan.cache.DownloadEntry
import dylan.di.AppContainer
import dylan.model.Song
import dylan.model.SongKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    container: AppContainer,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onOpenDownloads: () -> Unit,
) {
    var favorites by remember { mutableStateOf(emptyList<Song>()) }
    var jumpBack by remember { mutableStateOf(emptyList<Song>()) }
    val actions = rememberSongActions(container)
    val favKeys = rememberFavoriteKeys(container)
    val ctx = LocalContext.current
    val isOnline = rememberIsOnline(container)
    val cachedKeys = rememberCachedKeys(container)
    // Same single-JOIN model the Downloads screen renders, so the summary cannot disagree with it.
    val downloads by container.cacheManager.downloads.collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(favKeys) {
        favorites = quietLoad(emptyList()) { container.favorites.all() }
        jumpBack = quietLoad(emptyList()) { container.history.recent(HISTORY_RECENT_LIMIT) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
    ) {
        SectionTitle("Library")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "downloads-summary") { DownloadsSummaryRow(downloads, onOpenDownloads) }
            favoritesSection(favorites, ctx, actions, onPlaySongs, isOnline, cachedKeys)
            jumpBackSection(jumpBack, ctx, actions, onPlaySongs, isOnline, cachedKeys, favKeys)
        }
    }
}

@Composable
private fun DownloadsSummaryRow(
    downloads: List<DownloadEntry>,
    onOpenDownloads: () -> Unit,
) {
    val t = LocalDylanTokens.current
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
                downloadsSummary(downloads),
                style = MaterialTheme.typography.bodyMedium,
                color = t.textSecondary,
            )
        }
        Icon(Dyl.ChevronRight, contentDescription = null, tint = t.textSecondary)
    }
}

private fun LazyListScope.favoritesSection(
    favorites: List<Song>,
    ctx: Context,
    actions: SongActions,
    onPlaySongs: (List<Song>, Int) -> Unit,
    isOnline: Boolean,
    cachedKeys: Set<SongKey>,
) {
    // No favourites, no section. HomeScreen already gates on `favoritesShown.isNotEmpty()`; this
    // keeps the two consistent, so an account with nothing favourited shows neither a heading nor
    // a placeholder row explaining a list that does not exist.
    if (favorites.isEmpty()) return
    item(key = "favorites-title") { SectionTitle("Your favorites") }
    items(favorites, key = { "fv" + it.key.songId }) { song ->
        val can = canPlay(isOnline, cachedKeys, song.key)
        SongRow(
            song = song,
            isFavorite = true,
            enabled = can,
            onTap = {
                if (!can) {
                    android.widget.Toast
                        .makeText(ctx, Copy.OFFLINE, android.widget.Toast.LENGTH_SHORT)
                        .show()
                } else {
                    onPlaySongs(favorites, favorites.indexOfFirst { it.key == song.key }.coerceAtLeast(0))
                }
            },
            onPlayNext = { actions.playNext(song) },
            onAddLast = { actions.addToQueue(song) },
            onFavorite = { actions.toggleFavorite(song) },
        )
    }
}

private fun LazyListScope.jumpBackSection(
    jumpBack: List<Song>,
    ctx: Context,
    actions: SongActions,
    onPlaySongs: (List<Song>, Int) -> Unit,
    isOnline: Boolean,
    cachedKeys: Set<SongKey>,
    favKeys: Set<SongKey>,
) {
    if (jumpBack.isEmpty()) return
    item(key = "history-title") { SectionTitle("Jump back in") }
    items(jumpBack, key = { "jb" + it.key.songId }) { song ->
        val can = canPlay(isOnline, cachedKeys, song.key)
        SongRow(
            song = song,
            isFavorite = song.key in favKeys,
            enabled = can,
            onTap = {
                if (!can) {
                    android.widget.Toast
                        .makeText(ctx, Copy.OFFLINE, android.widget.Toast.LENGTH_SHORT)
                        .show()
                } else {
                    onPlaySongs(listOf(song), 0)
                }
            },
            onPlayNext = { actions.playNext(song) },
            onAddLast = { actions.addToQueue(song) },
            onFavorite = { actions.toggleFavorite(song) },
        )
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
    var pendingRemove by remember { mutableStateOf<DownloadEntry?>(null) }
    // One subscription, one JOIN. `removableKeys` is a second subscription to the same cold
    // flow, so reading it alongside `downloads` would double the query on every emission.
    val rows by container.cacheManager.downloads.collectAsStateWithLifecycle(initialValue = emptyList())
    val anyDownloading = rememberAnyDownloading(container)
    val actions = rememberSongActions(container)
    val favKeys = rememberFavoriteKeys(container)
    val songs = remember(rows) { rows.map { it.song } }
    val totalBytes = remember(rows) { rows.sumOf { it.bytes } }

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
        if (rows.isEmpty()) {
            Text(
                if (anyDownloading.value) "Downloading…" else "Nothing saved yet. Play something and it lands here.",
                style = MaterialTheme.typography.bodyMedium,
                color = t.textSecondary,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                downloadRows(rows, songs, totalBytes, container, actions, favKeys, onPlaySongs) { row ->
                    pendingRemove = row
                }
            }
        }
    }

    pendingRemove?.let { entry ->
        RemoveDownloadDialog(
            entry = entry,
            container = container,
            scope = scope,
            onDismiss = { pendingRemove = null },
        )
    }
}

private fun LazyListScope.downloadRows(
    rows: List<DownloadEntry>,
    songs: List<Song>,
    totalBytes: Long,
    container: AppContainer,
    actions: SongActions,
    favKeys: Set<SongKey>,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onRequestRemove: (DownloadEntry) -> Unit,
) {
    items(rows, key = { it.key.provider + ":" + it.key.songId }) { row ->
        val at = songs.indexOfFirst { it.key == row.key }.coerceAtLeast(0)
        val offerRemove = remember(row) { { onRequestRemove(row) } }
        SongRow(
            song = row.song,
            isCached = true,
            isFavorite = row.key in favKeys,
            sizeLabel = formatBytes(row.bytes),
            onTap = { onPlaySongs(songs, at) },
            onDownload = null,
            // From the same row the protection set was evaluated against, so the menu
            // disappears the moment a download or an upgrade takes the key.
            onRemoveDownload = if (row.removable) offerRemove else null,
            onPlayNext = { actions.playNext(row.song) },
            onAddLast = { actions.addToQueue(row.song) },
            onFavorite = { actions.toggleFavorite(row.song) },
        )
    }
    item(key = "downloads-footer") {
        val t = LocalDylanTokens.current
        Text(
            downloadsFooter(totalBytes, rows.size, container),
            style = MaterialTheme.typography.labelSmall,
            color = t.textSecondary,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun RemoveDownloadDialog(
    entry: DownloadEntry,
    container: AppContainer,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val t = LocalDylanTokens.current
    val ctx = LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove download?", style = MaterialTheme.typography.titleMedium, color = t.textPrimary) },
        text = {
            Text(
                "\"${entry.song.title}\" (${formatBytes(entry.bytes)}) will be deleted from storage.",
                style = MaterialTheme.typography.bodyMedium,
                color = t.textSecondary,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                onDismiss()
                scope.launch {
                    // evictOne re-checks protection inside the claim, so a key that became
                    // protected between the tap and here is refused, not deleted.
                    val removed = container.cacheManager.evictOne(entry.key)
                    if (!removed) {
                        android.widget.Toast
                            .makeText(ctx, Copy.BUSY, android.widget.Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }) { Text("Remove", color = t.error) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = t.textSecondary) }
        },
        containerColor = t.surface,
    )
}

private fun downloadsSummary(rows: List<DownloadEntry>): String {
    if (rows.isEmpty()) return "Nothing saved yet"
    return "${rows.size} songs · ${formatBytes(rows.sumOf { it.bytes })}"
}

private fun downloadsFooter(
    bytes: Long,
    count: Int,
    container: AppContainer,
): String = "Cached audio ${formatBytes(bytes)} of ${formatBytes(container.cfg.cacheMaxBytes)} · $count songs"

internal fun formatBytes(b: Long): String =
    when {
        b >= BYTES_PER_GB -> "%.1f GB".format(b.toDouble() / BYTES_PER_GB)
        b >= BYTES_PER_MB -> "%.1f MB".format(b.toDouble() / BYTES_PER_MB)
        b >= BYTES_PER_KB -> "%.1f KB".format(b.toDouble() / BYTES_PER_KB)
        else -> "$b B"
    }

/** Recent plays loaded for the library's "Jump back in" section. */
private const val HISTORY_RECENT_LIMIT = 5

// Size thresholds for formatBytes. Binary multiples (as before), labelled with the familiar
// SI-looking suffixes the UI has always shown.
private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = 1024L * 1024L
private const val BYTES_PER_GB = 1024L * 1024L * 1024L
