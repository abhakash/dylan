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
import androidx.compose.runtime.collectAsState
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
import coil3.compose.AsyncImage
import dylan.android.ui.Copy
import dylan.android.ui.LocalDylanTokens
import dylan.android.ui.components.SongRow
import dylan.android.ui.components.canPlay
import dylan.android.ui.components.rememberCachedKeys
import dylan.android.ui.components.rememberIsOnline
import dylan.di.AppContainer
import dylan.model.Album
import dylan.playback.Intent
import kotlin.random.Random

private val AlbumHeroHeight = 340.dp

@Composable
fun AlbumScreen(
    container: AppContainer,
    albumId: String,
    onBack: () -> Unit,
    onPlaySongs: (List<dylan.model.Song>, Int) -> Unit,
) {
    val t = LocalDylanTokens.current
    var album by remember { mutableStateOf<Album?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(albumId) {
        try {
            album = container.provider.album(albumId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            album = null
        }
        failed = album == null
    }

    val songs = album?.songs.orEmpty()
    val st by container.orchestrator.state.collectAsState()
    val playing = st.phase is dylan.model.Phase.Playing
    val ctx = LocalContext.current
    val isOnline = rememberIsOnline(container)
    val cachedKeys = rememberCachedKeys(container)
    val progress by container.downloads.progress.collectAsState()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val heroPx = remember(density) { with(density) { AlbumHeroHeight.toPx() } }
    val pinnedReveal =
        remember(heroPx) {
            derivedStateOf {
                if (listState.firstVisibleItemIndex > 0) {
                    1f
                } else {
                    (listState.firstVisibleItemScrollOffset / heroPx).coerceIn(0f, 1f)
                }
            }
        }
    val pinnedVisible by remember { derivedStateOf { pinnedReveal.value > 0f } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
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
                                0.5f to androidx.compose.ui.graphics.Color.Transparent,
                                1f to t.background,
                            ),
                        ),
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        Text(album?.title.orEmpty().uppercase(), style = MaterialTheme.typography.displayMedium, color = t.textPrimary)
                        Text(
                            listOfNotNull(album?.subtitle, album?.year).joinToString(" · ").uppercase(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = t.textSecondary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { if (songs.isNotEmpty()) onPlaySongs(songs, 0) },
                                colors = ButtonDefaults.buttonColors(containerColor = t.primary, contentColor = t.onPrimary),
                                shape = MaterialTheme.shapes.large,
                            ) {
                                Text("PLAY", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp))
                            }
                            OutlinedButton(onClick = {
                                if (songs.isEmpty()) return@OutlinedButton
                                val playingHere = songs.any { it.key == st.current?.key }
                                if (playingHere) {
                                    // E2: a song from THIS album is playing — shuffle must only reshuffle
                                    // the upcoming items around the current anchor, never jump/restart.
                                    if (!st.shuffleOn) container.orchestrator.submit(Intent.ToggleShuffle)
                                } else {
                                    if (!st.shuffleOn) container.orchestrator.submit(Intent.ToggleShuffle)
                                    onPlaySongs(songs, Random.nextInt(songs.size))
                                }
                            }, shape = MaterialTheme.shapes.large, colors = ButtonDefaults.outlinedButtonColors(contentColor = t.textPrimary)) {
                                Text("SHUFFLE", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp))
                            }
                        }
                    }
                }
            }
            itemsIndexed(songs, key = { _, s -> s.key.songId }) { i, song ->
                val can = canPlay(isOnline, cachedKeys, song.key)
                SongRow(
                    song = song,
                    index = i + 1,
                    isPlaying = song.key == st.current?.key && playing,
                    isCached = song.key in cachedKeys,
                    progressPct = progress[song.key],
                    enabled = can,
                    onTap = {
                        if (!can) {
                            android.widget.Toast
                                .makeText(ctx, Copy.OFFLINE, android.widget.Toast.LENGTH_SHORT)
                                .show()
                        } else {
                            onPlaySongs(songs, i)
                        }
                    },
                )
            }
            if (failed) {
                item {
                    Text(
                        dylan.android.ui.Copy.NETWORK,
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
