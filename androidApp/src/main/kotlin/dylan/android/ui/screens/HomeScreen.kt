package dylan.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dylan.android.ui.LocalDylanTokens
import dylan.android.ui.components.OfflineBanner
import dylan.android.ui.components.SectionTitle
import dylan.android.ui.components.SongRow
import dylan.android.ui.components.containsRecording
import dylan.android.ui.components.distinctRecordings
import dylan.android.ui.components.rememberFavoriteKeys
import dylan.android.ui.components.rememberSongActions
import dylan.android.ui.components.toArtistEntry
import dylan.di.AppContainer
import dylan.model.MiniEntity
import dylan.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenAlbum: (String) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenArtist: (MiniEntity) -> Unit = {},
) {
    val t = LocalDylanTokens.current
    var trending by remember { mutableStateOf<List<dylan.model.MiniEntity>>(emptyList()) }
    var topSearches by remember { mutableStateOf<List<dylan.model.MiniEntity>>(emptyList()) }
    var jumpBack by remember { mutableStateOf<List<Song>>(emptyList()) }
    var albumHistory by remember { mutableStateOf<List<dylan.db.RecentAlbums>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<Song>>(emptyList()) }
    var offline by remember { mutableStateOf(false) }
    val actions = rememberSongActions(container)
    val favKeys = rememberFavoriteKeys(container)

    LaunchedEffect(Unit) {
        // Fast local data first — pure SQLite, no network.
        // Catalog-level dedupe: JioSaavn serves one recording under multiple song ids —
        // dedupe by recordingKey and across sections by section precedence.
        jumpBack =
            runCatching { container.history.recent(20) }
                .getOrDefault(emptyList())
                .distinctRecordings()
                .take(5)
        favorites = runCatching { container.favorites.all() }.getOrDefault(emptyList()).distinctRecordings()
        albumHistory =
            runCatching { container.history.recentAlbums(12) }.getOrDefault(emptyList())
        coroutineScope {
            val feedDeferred = async { runCatching { container.provider.home() }.getOrNull() }
            val topSearchesDeferred = async { runCatching { container.provider.topSearches() }.getOrDefault(emptyList()) }
            val feed = feedDeferred.await()
            trending =
                feed
                    ?.sections
                    ?.firstOrNull()
                    ?.items
                    .orEmpty()
            offline = feed == null
            topSearches = topSearchesDeferred.await()
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                bottom = 16.dp,
            ),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(t.background)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "DYLAN",
                    style = MaterialTheme.typography.displayLarge.copy(letterSpacing = 1.2.sp),
                    color = t.textPrimary,
                )
                Box(
                    Modifier
                        .background(t.surfaceVariant)
                        .padding(1.dp)
                        .background(t.background)
                        .clickable(onClick = onOpenSettings)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        "[ SETTINGS ]",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                        color = t.textSecondary,
                    )
                }
            }
        }
        if (offline) {
            item { OfflineBanner() }
        }
        if (jumpBack.isNotEmpty()) {
            item { SectionTitle("Jump back in") }
            items(jumpBack, key = { "jb" + it.key.songId }) { song ->
                SongRow(
                    song = song,
                    isFavorite = song.key in favKeys,
                    onTap = { onPlaySongs(listOf(song), 0) },
                    onPlayNext = { actions.playNext(song) },
                    onAddLast = { actions.addToQueue(song) },
                    onFavorite = { actions.toggleFavorite(song) },
                    onGoToArtist =
                        if (song.artistToken != null) {
                            { onOpenArtist(song.toArtistEntry()) }
                        } else {
                            null
                        },
                )
            }
        }
        // Personalized from SQLite play history — albums the user has actually listened to.
        if (albumHistory.isNotEmpty()) {
            item { SectionTitle("Recently played albums") }
            item {
                AlbumCarousel(albumHistory, onOpenAlbum)
            }
        }
        // Cross-section dedupe: a favorite already visible in Jump back in is hidden here.
        val favoritesShown = favorites.filter { f -> !jumpBack.containsRecording(f) }
        if (favoritesShown.isNotEmpty()) {
            item { SectionTitle("Your favorites") }
            val topFive = favoritesShown.take(5)
            items(topFive, key = { "fv" + it.key.songId }) { song ->
                SongRow(
                    song = song,
                    isFavorite = true,
                    onTap = { onPlaySongs(topFive, topFive.indexOfFirst { it.key == song.key }.coerceAtLeast(0)) },
                    onPlayNext = { actions.playNext(song) },
                    onAddLast = { actions.addToQueue(song) },
                    onFavorite = { actions.toggleFavorite(song) },
                )
            }
        }
        if (trending.isNotEmpty()) {
            item { SectionTitle("Trending albums") }
            item {
                // LazyRow, not Row+horizontalScroll: tiles compose as they scroll into view —
                // an eager Row composed every cover at once and janked the scroll.
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(trending, key = { it.songKey?.toString() ?: ("t" + (it.albumId ?: it.title)) }) { m ->
                        AlbumTile(title = m.title, image = m.image, onClick = { m.albumId?.let(onOpenAlbum) })
                    }
                }
            }
        }
        if (topSearches.isNotEmpty()) {
            item { SectionTitle("Top searches") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val chips = topSearches.filter { it.albumId != null }.take(12)
                    items(chips, key = { "ts" + (it.albumId ?: it.title) }) { m ->
                        Box(
                            Modifier
                                .background(t.divider)
                                .padding(1.dp)
                                .background(t.background)
                                .clickable { m.albumId?.let(onOpenAlbum) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                m.title.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                                color = t.textPrimary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One square album tile shared by both carousels. */
@Composable
private fun AlbumTile(
    title: String,
    image: String?,
    onClick: (() -> Unit)?,
) {
    val t = LocalDylanTokens.current
    Column(
        Modifier
            .width(118.dp)
            .background(t.divider)
            .padding(1.dp)
            .background(t.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(6.dp),
    ) {
        coil3.compose.AsyncImage(
            model = image,
            contentDescription = title,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(118.dp)
                    .background(t.background),
        )
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
            color = t.textPrimary,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun AlbumCarousel(
    albums: List<dylan.db.RecentAlbums>,
    onOpen: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(albums, key = { it.albumId.orEmpty() }) { a ->
            AlbumTile(title = a.albumName ?: "", image = a.artUrl, onClick = { a.albumId?.let(onOpen) })
        }
    }
}
