package dylan.android.ui.screens

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dylan.android.ui.Copy
import dylan.android.ui.LocalDylanTokens
import dylan.android.ui.components.OfflineBanner
import dylan.android.ui.components.SectionTitle
import dylan.android.ui.components.SongActions
import dylan.android.ui.components.SongRow
import dylan.android.ui.components.canPlay
import dylan.android.ui.components.rememberCachedKeys
import dylan.android.ui.components.rememberFavoriteKeys
import dylan.android.ui.components.rememberIsOnline
import dylan.android.ui.components.rememberSongActions
import dylan.android.ui.components.toArtistEntry
import dylan.di.AppContainer
import dylan.model.MiniEntity
import dylan.model.Song
import dylan.model.SongKey
import dylan.util.containsRecording
import dylan.util.distinctRecordings
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
    val snapshot by container.homeSnapshot.collectAsStateWithLifecycle()
    var trending by remember { mutableStateOf<List<dylan.model.MiniEntity>>(emptyList()) }
    var topSearches by remember { mutableStateOf<List<dylan.model.MiniEntity>>(emptyList()) }
    var jumpBack by remember { mutableStateOf(snapshot.jumpBack) }
    var albumHistory by remember { mutableStateOf(snapshot.albums) }
    var favorites by remember { mutableStateOf(snapshot.favorites) }
    var offline by remember { mutableStateOf(false) }
    val actions = rememberSongActions(container)
    val favKeys = rememberFavoriteKeys(container)
    val ctx = LocalContext.current
    val isOnline = rememberIsOnline(container)
    val cachedKeys = rememberCachedKeys(container)

    LaunchedEffect(Unit) {
        seedHome(container, snapshot) { local ->
            jumpBack = local.jumpBack
            favorites = local.favorites
            albumHistory = local.albums
        }
        val remote = remoteHome(container)
        trending = remote.trending
        topSearches = remote.topSearches
        offline = remote.offline
    }
    val favoritesShown = remember(favorites, jumpBack) { favorites.filter { f -> !jumpBack.containsRecording(f) } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                bottom = 16.dp,
            ),
    ) {
        item { HomeHeader(onOpenSettings) }
        if (offline) {
            item { OfflineBanner() }
        }
        if (jumpBack.isNotEmpty()) {
            item { SectionTitle("Jump back in") }
            jumpBackRows(jumpBack, ctx, actions, onPlaySongs, onOpenArtist, isOnline, cachedKeys, favKeys)
        }
        // Personalized from SQLite play history — albums the user has actually listened to.
        // Skeleton tiles hold the slot on cold start so nothing below shifts when data lands.
        if (albumHistory.isNotEmpty() || !snapshot.loaded) {
            recentlyPlayedAlbums(albumHistory, showSkeleton = !snapshot.loaded, onOpenAlbum = onOpenAlbum)
        }
        if (favoritesShown.isNotEmpty()) {
            item { SectionTitle("Your favorites") }
            favoriteRows(favoritesShown, ctx, actions, onPlaySongs, isOnline, cachedKeys)
        }
        if (trending.isNotEmpty()) {
            item { SectionTitle("Trending albums") }
            item {
                // LazyRow, not Row+horizontalScroll: tiles compose as they scroll into view —
                // an eager Row composed every cover at once and janked the scroll.
                TrendingCarousel(trending, onOpenAlbum)
            }
        }
        if (topSearches.isNotEmpty()) {
            item { SectionTitle("Top searches") }
            item { TopSearchChips(topSearches, onOpenAlbum) }
        }
    }
}

/** The three local (SQLite-only) lists Home seeds itself with before the network answers. */
private data class LocalHome(
    val jumpBack: List<Song>,
    val favorites: List<Song>,
    val albums: List<dylan.db.RecentAlbums>,
)

/** The two provider calls that feed Home's remote sections, plus the offline verdict. */
private data class RemoteHome(
    val trending: List<MiniEntity>,
    val topSearches: List<MiniEntity>,
    val offline: Boolean,
)

/**
 * Fast local data first — pure SQLite, no network. Snapshot survives tab switches
 * via AppContainer StateFlow (no object singleton, unidirectional).
 */
private suspend fun seedHome(
    container: AppContainer,
    snapshot: AppContainer.HomeSnapshot,
    onSeeded: (LocalHome) -> Unit,
) {
    if (!container.homeSnapshot.value.loaded) {
        val local = readLocalHome(container)
        onSeeded(local)
        container.homeSnapshot.value =
            AppContainer.HomeSnapshot(
                loaded = true,
                jumpBack = local.jumpBack,
                favorites = local.favorites,
                albums = local.albums,
            )
    } else {
        onSeeded(LocalHome(snapshot.jumpBack, snapshot.favorites, snapshot.albums))
    }
}

private suspend fun readLocalHome(container: AppContainer): LocalHome =
    LocalHome(
        jumpBack =
            runCatching { container.history.recent(JUMP_BACK_QUERY_LIMIT) }
                .getOrDefault(emptyList())
                .distinctRecordings()
                .take(JUMP_BACK_ROW_LIMIT),
        favorites = runCatching { container.favorites.all() }.getOrDefault(emptyList()).distinctRecordings(),
        albums = runCatching { container.history.recentAlbums(ALBUM_HISTORY_LIMIT) }.getOrDefault(emptyList()),
    )

private suspend fun remoteHome(container: AppContainer): RemoteHome =
    coroutineScope {
        val feedDeferred = async { runCatching { container.provider.home() }.getOrNull() }
        val topSearchesDeferred =
            async { runCatching { container.provider.topSearches() }.getOrDefault(emptyList()) }
        val feed = feedDeferred.await()
        RemoteHome(
            trending =
                feed
                    ?.sections
                    ?.firstOrNull()
                    ?.items
                    .orEmpty(),
            offline = feed == null,
            topSearches = topSearchesDeferred.await(),
        )
    }

@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    val t = LocalDylanTokens.current
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

private fun LazyListScope.jumpBackRows(
    jumpBack: List<Song>,
    ctx: Context,
    actions: SongActions,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onOpenArtist: (MiniEntity) -> Unit,
    isOnline: Boolean,
    cachedKeys: Set<SongKey>,
    favKeys: Set<SongKey>,
) {
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
            onGoToArtist =
                if (song.artistToken != null) {
                    { onOpenArtist(song.toArtistEntry()) }
                } else {
                    null
                },
        )
    }
}

private fun LazyListScope.favoriteRows(
    favorites: List<Song>,
    ctx: Context,
    actions: SongActions,
    onPlaySongs: (List<Song>, Int) -> Unit,
    isOnline: Boolean,
    cachedKeys: Set<SongKey>,
) {
    val topFive = favorites.take(FAVORITES_ROW_LIMIT)
    items(topFive, key = { "fv" + it.key.songId }) { song ->
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
                    onPlaySongs(topFive, topFive.indexOfFirst { it.key == song.key }.coerceAtLeast(0))
                }
            },
            onPlayNext = { actions.playNext(song) },
            onAddLast = { actions.addToQueue(song) },
            onFavorite = { actions.toggleFavorite(song) },
        )
    }
}

/**
 * Recently-played albums from local play history, or the skeleton that holds the slot until
 * they land, so nothing below shifts when the data arrives.
 */
private fun LazyListScope.recentlyPlayedAlbums(
    albumHistory: List<dylan.db.RecentAlbums>,
    showSkeleton: Boolean,
    onOpenAlbum: (String) -> Unit,
) {
    item { SectionTitle("Recently played albums") }
    if (albumHistory.isNotEmpty()) {
        item { AlbumCarousel(albumHistory, onOpenAlbum) }
    } else if (showSkeleton) {
        item { AlbumSkeleton() }
    }
}

@Composable
private fun TrendingCarousel(
    trending: List<MiniEntity>,
    onOpenAlbum: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(trending, key = { it.songKey?.toString() ?: ("t" + (it.albumId ?: it.title)) }) { m ->
            AlbumTile(title = m.title, image = m.image, onClick = { m.albumId?.let(onOpenAlbum) })
        }
    }
}

@Composable
private fun TopSearchChips(
    topSearches: List<MiniEntity>,
    onOpenAlbum: (String) -> Unit,
) {
    val t = LocalDylanTokens.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val chips = topSearches.filter { it.albumId != null }.take(TOP_SEARCH_CHIP_LIMIT)
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

/** Cold-start placeholder — same geometry as [AlbumTile], keeps the first frame stable. */
@Composable
private fun AlbumSkeleton() {
    val t = LocalDylanTokens.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(SKELETON_TILE_COUNT, key = { "sk$it" }) {
            Column(
                Modifier
                    .width(118.dp)
                    .background(t.divider)
                    .padding(1.dp)
                    .background(t.surfaceVariant)
                    .padding(6.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(118.dp)
                        .background(t.background),
                )
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .width(72.dp)
                        .height(10.dp)
                        .background(t.background),
                )
            }
        }
    }
}

/** How much recent play history to scan before de-duplicating down to the rows actually shown. */
private const val JUMP_BACK_QUERY_LIMIT = 20

/** Jump-back rows shown inline; five keeps the section above the fold. */
private const val JUMP_BACK_ROW_LIMIT = 5

/** Albums pulled from local play history for the "recently played" carousel. */
private const val ALBUM_HISTORY_LIMIT = 12

/** Favorites shown inline — the full list lives in the library tab. */
private const val FAVORITES_ROW_LIMIT = 5

/** Top-search chips: one carousel's worth, past that it reads as a wall. */
private const val TOP_SEARCH_CHIP_LIMIT = 12

/** Placeholder tiles composing the cold-start frame. */
private const val SKELETON_TILE_COUNT = 3

/** HomeSnapshot now lives in AppContainer.homeSnapshot (StateFlow, unidirectional). */
