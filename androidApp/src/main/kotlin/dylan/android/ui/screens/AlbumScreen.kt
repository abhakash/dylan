package dylan.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dylan.android.ui.Copy
import dylan.android.ui.LocalDylanTokens
import dylan.android.ui.components.SongRow
import dylan.android.ui.components.canPlay
import dylan.android.ui.components.rememberCachedKeys
import dylan.android.ui.components.rememberDownloadPct
import dylan.android.ui.components.rememberIsOnline
import dylan.di.AppContainer
import dylan.model.Album
import dylan.model.message
import dylan.playback.Intent
import kotlin.random.Random

private val AlbumHeroHeight = 340.dp

/** Where the hero scrim starts fading, so the fade lands under the title and the buttons. */
private const val ALBUM_HERO_FADE_START = 0.5f

@Composable
fun AlbumScreen(
    container: AppContainer,
    albumId: String,
    onPlaySongs: (List<dylan.model.Song>, Int) -> Unit,
) {
    val t = LocalDylanTokens.current
    var album by remember { mutableStateOf<Album?>(null) }
    var errorCode by remember { mutableStateOf<dylan.model.ErrorCode?>(null) }

    // The provider returns a CatalogResult, so there is nothing left to catch: every failure mode
    // (offline, geo-blocked, rate limited, bot-walled, timed out, gone) is a named code with its own
    // message. The old try/catch here was dead code — the provider could only return null — and the
    // screen showed "Check your connection" for all five.
    LaunchedEffect(albumId) {
        when (val r = container.provider.albumDetail(albumId)) {
            is dylan.provider.CatalogResult.Ok -> {
                album = r.value
                errorCode = null
            }
            is dylan.provider.CatalogResult.Err -> {
                album = null
                errorCode = r.code
            }
        }
    }

    val songs = album?.songs.orEmpty()
    val st by container.orchestrator.state.collectAsStateWithLifecycle()
    val isOnline = rememberIsOnline(container)
    val cachedKeys = rememberCachedKeys(container)
    val listState = rememberLazyListState()
    val pinnedReveal = rememberHeroReveal(listState)
    val pinnedVisible by remember { derivedStateOf { pinnedReveal.value > 0f } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                AlbumHero(container, album, songs, st, onPlaySongs)
            }
            // One album can carry the same songId twice (a duplicated track in the catalogue listing), and a
            // duplicate LazyColumn key is a hard crash, not a glitch. Occurrence-indexed for the
            // same reason QueueSheet/SearchScreen namespace theirs.
            itemsIndexed(songs, key = { i, s -> "${s.key.provider}:${s.key.songId}#$i" }) { i, song ->
                AlbumSongRow(
                    container = container,
                    song = song,
                    rowIndex = i,
                    songs = songs,
                    st = st,
                    isOnline = isOnline,
                    cachedKeys = cachedKeys,
                    onPlaySongs = onPlaySongs,
                )
            }
            if (errorCode != null) {
                item {
                    Text(
                        dylan.model.DylanFailure(errorCode!!).message(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = t.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        if (pinnedVisible) {
            PinnedAlbumBar(
                title = album?.title.orEmpty(),
                artUrl = album?.artUrl150,
                reveal = pinnedReveal,
            )
        }
    }
}

/**
 * How far the pinned bar has been revealed: 0 while the hero art is still at the top, 1 once the
 * list has scrolled past it.
 *
 * Read through a [derivedStateOf] so the scroll position invalidates only this value, not the list
 * body that would otherwise recompose on every pixel of scroll.
 */
@Composable
private fun rememberHeroReveal(listState: LazyListState): State<Float> {
    val density = LocalDensity.current
    val heroPx = remember(density) { with(density) { AlbumHeroHeight.toPx() } }
    return remember(heroPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / heroPx).coerceIn(0f, 1f)
            }
        }
    }
}

/**
 * One album row.
 *
 * The offline gate is evaluated per row here rather than once for the list: it is the row's `enabled`
 * flag, and caching the percentage inside the row keeps one row's download ticks from invalidating
 * every other visible row.
 */
@Composable
private fun AlbumSongRow(
    container: AppContainer,
    song: dylan.model.Song,
    rowIndex: Int,
    songs: List<dylan.model.Song>,
    st: dylan.model.PlayerState,
    isOnline: Boolean,
    cachedKeys: Set<dylan.model.SongKey>,
    onPlaySongs: (List<dylan.model.Song>, Int) -> Unit,
) {
    val ctx = LocalContext.current
    val can = canPlay(isOnline, cachedKeys, song.key)
    val pct = rememberDownloadPct(container, song.key)
    SongRow(
        song = song,
        index = rowIndex + 1,
        isPlaying = song.key == st.current?.key && st.phase is dylan.model.Phase.Playing,
        isCached = song.key in cachedKeys,
        progressPct = pct.value,
        enabled = can,
        onTap = {
            if (!can) {
                android.widget.Toast
                    .makeText(ctx, Copy.OFFLINE, android.widget.Toast.LENGTH_SHORT)
                    .show()
            } else {
                onPlaySongs(songs, rowIndex)
            }
        },
    )
}

/**
 * Hero block: album art under a scrim, the title line, and PLAY / SHUFFLE.
 *
 * One LazyColumn item, extracted so the list body — the keyed rows, the pinned-bar bookkeeping and
 * the error item — is readable on its own.
 */
@Composable
private fun AlbumHero(
    container: AppContainer,
    album: Album?,
    songs: List<dylan.model.Song>,
    st: dylan.model.PlayerState,
    onPlaySongs: (List<dylan.model.Song>, Int) -> Unit,
) {
    val t = LocalDylanTokens.current
    Box(Modifier.fillMaxWidth()) {
        AsyncImage(
            model = album?.artUrl500,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(AlbumHeroHeight),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to androidx.compose.ui.graphics.Color.Transparent,
                    ALBUM_HERO_FADE_START to androidx.compose.ui.graphics.Color.Transparent,
                    1f to t.background,
                ),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(
                album?.title.orEmpty().uppercase(),
                style = MaterialTheme.typography.displayMedium,
                color = t.textPrimary,
            )
            Text(
                listOfNotNull(album?.subtitle, album?.year).joinToString(" · ").uppercase(),
                style = MaterialTheme.typography.bodyMedium,
                color = t.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { if (songs.isNotEmpty()) onPlaySongs(songs, 0) },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = t.primary,
                            contentColor = t.onPrimary,
                        ),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text("PLAY", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp))
                }
                OutlinedButton(
                    onClick = { shuffleFromHero(container, songs, st, onPlaySongs) },
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = t.textPrimary),
                ) {
                    Text("SHUFFLE", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp))
                }
            }
        }
    }
}

/**
 * SHUFFLE on the hero.
 *
 * E2: a song from THIS album is playing — shuffle must only reshuffle the upcoming items around the
 * current anchor, never jump/restart.
 */
private fun shuffleFromHero(
    container: AppContainer,
    songs: List<dylan.model.Song>,
    st: dylan.model.PlayerState,
    onPlaySongs: (List<dylan.model.Song>, Int) -> Unit,
) {
    if (songs.isEmpty()) return
    val playingHere = songs.any { it.key == st.current?.key }
    if (playingHere) {
        if (!st.shuffleOn) container.orchestrator.submit(Intent.ToggleShuffle)
    } else {
        if (!st.shuffleOn) container.orchestrator.submit(Intent.ToggleShuffle)
        onPlaySongs(songs, Random.nextInt(songs.size))
    }
}

@Composable
private fun PinnedAlbumBar(
    title: String,
    artUrl: String?,
    reveal: State<Float>,
) {
    val t = LocalDylanTokens.current
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = reveal.value }
            .background(t.surface)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = artUrl,
                contentDescription = null,
                modifier =
                    Modifier
                        .height(40.dp)
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.small)
                        .background(t.surfaceVariant),
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = t.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(t.divider),
        )
    }
}
