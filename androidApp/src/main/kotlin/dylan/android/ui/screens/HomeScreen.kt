package dylan.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dylan.android.ui.LocalDylanTokens
import dylan.android.ui.components.OfflineBanner
import dylan.android.ui.components.SectionTitle
import dylan.android.ui.components.SongRow
import dylan.android.ui.components.rememberFavoriteKeys
import dylan.android.ui.components.rememberSongActions
import dylan.android.ui.components.toArtistEntry
import dylan.di.AppContainer
import dylan.model.MiniEntity
import dylan.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    var searchPicks by remember { mutableStateOf<List<Song>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<Song>>(emptyList()) }
    var offline by remember { mutableStateOf(false) }
    val actions = rememberSongActions(container)
    val favKeys = rememberFavoriteKeys(container)

    LaunchedEffect(Unit) {
        // Load fast local data first — no network
        jumpBack = runCatching { container.history.recent(5) }.getOrDefault(emptyList())
        favorites = runCatching { container.favorites.all() }.getOrDefault(emptyList())
        coroutineScope {
            val feedDeferred = async { runCatching { container.provider.home() }.getOrNull() }
            val topSearchesDeferred = async { runCatching { container.provider.topSearches() }.getOrDefault(emptyList()) }
            // Based on searches: 3 queries × 4 songs — run concurrently, not sequentially (was 600-1200ms lag)
            val queries =
                runCatching { container.searchHistory.recent() }
                    .getOrDefault(emptyList())
                    .take(3)
            searchPicks =
                if (queries.isEmpty()) {
                    emptyList()
                } else {
                    queries
                        .map { q ->
                            async {
                                runCatching {
                                    container.provider
                                        .search(q, 1)
                                        .items
                                        .take(4)
                                }.getOrDefault(emptyList())
                            }
                        }.awaitAll()
                        .flatten()
                        .distinctBy { it.key }
                        .filter { s -> jumpBack.none { it.key == s.key } && favorites.none { it.key == s.key } }
                        .take(8)
                }
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
            androidx.compose.foundation.layout.PaddingValues(
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
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "DYLAN",
                        style = MaterialTheme.typography.displayLarge.copy(letterSpacing = 1.2.sp),
                        color = t.textPrimary,
                    )
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(t.primary, androidx.compose.foundation.shape.CircleShape),
                    )
                    Text(
                        "( 1 )",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                        color = t.textSecondary,
                    )
                }
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
        if (searchPicks.isNotEmpty()) {
            item { SectionTitle("Based on your searches") }
            items(searchPicks, key = { "sp" + it.key.songId }) { song ->
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
        if (favorites.isNotEmpty()) {
            item { SectionTitle("Your favorites") }
            items(favorites.take(5), key = { "fv" + it.key.songId }) { song ->
                val topFive = favorites.take(5)
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
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    trending.forEach { m ->
                        Column(
                            Modifier
                                .width(118.dp)
                                .background(t.divider)
                                .padding(1.dp)
                                .background(t.surfaceVariant)
                                .clickable(enabled = m.albumId != null) { m.albumId?.let(onOpenAlbum) }
                                .padding(6.dp),
                        ) {
                            coil3.compose.AsyncImage(
                                model = m.image,
                                contentDescription = m.title,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(118.dp)
                                        .background(t.background),
                            )
                            Text(
                                m.title.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                                color = t.textPrimary,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        if (topSearches.isNotEmpty()) {
            item { SectionTitle("Top searches") }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    topSearches
                        .filter { it.albumId != null }
                        .take(12)
                        .forEach { m ->
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
