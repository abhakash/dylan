package dylan.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import dylan.model.Artist
import dylan.model.message
import dylan.playback.Intent
import kotlin.random.Random

@Composable
fun ArtistScreen(
    container: AppContainer,
    artistToken: String,
    fallbackName: String,
    onPlaySongs: (List<dylan.model.Song>, Int) -> Unit,
) {
    val t = LocalDylanTokens.current
    var artist by remember { mutableStateOf<Artist?>(null) }
    var errorCode by remember { mutableStateOf<dylan.model.ErrorCode?>(null) }

    // Typed: a numeric id has no route on this API and is refused with NOT_FOUND, so a stale
    // numeric token says "unavailable" instead of the old blanket "Check your connection".
    LaunchedEffect(artistToken) {
        when (val r = container.provider.artistDetail(artistToken)) {
            is dylan.provider.CatalogResult.Ok -> {
                artist = r.value
                errorCode = null
            }
            is dylan.provider.CatalogResult.Err -> {
                artist = null
                errorCode = r.code
            }
        }
    }

    val songs = artist?.songs.orEmpty()
    val st by container.orchestrator.state.collectAsStateWithLifecycle()
    val playing = st.phase is dylan.model.Phase.Playing
    val ctx = LocalContext.current
    val isOnline = rememberIsOnline(container)
    val cachedKeys = rememberCachedKeys(container)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ArtistHero(container, artist, fallbackName, songs, st, onPlaySongs)
        }
        itemsIndexed(songs) { i, song ->
            val can = canPlay(isOnline, cachedKeys, song.key)
            val pct = rememberDownloadPct(container, song.key)
            SongRow(
                song = song,
                index = i + 1,
                isPlaying = song.key == st.current?.key && playing,
                isCached = song.key in cachedKeys,
                progressPct = pct.value,
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
}

/**
 * Hero block: artist art under a scrim, the name, and PLAY / SHUFFLE.
 *
 * Its own composable so the list body stays a list body — the hero is one item, not the shape the
 * whole screen is written around.
 */
@Composable
private fun ArtistHero(
    container: AppContainer,
    artist: Artist?,
    fallbackName: String,
    songs: List<dylan.model.Song>,
    st: dylan.model.PlayerState,
    onPlaySongs: (List<dylan.model.Song>, Int) -> Unit,
) {
    val t = LocalDylanTokens.current
    Box(Modifier.fillMaxWidth()) {
        AsyncImage(
            model = artist?.artUrl500,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(300.dp),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    ARTIST_HERO_FADE_START to Color.Transparent,
                    1f to t.background,
                ),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(
                (artist?.name ?: fallbackName).uppercase(),
                style = MaterialTheme.typography.displayMedium,
                color = t.textPrimary,
            )
            when (val subtitle = artist?.subtitle) {
                null -> {}
                else ->
                    Text(
                        subtitle.uppercase(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = t.textSecondary,
                    )
            }
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
 * A song from THIS artist is already playing, so shuffle must only reshuffle the upcoming items
 * around the current anchor — never jump or restart it.
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

/**
 * Where the hero scrim starts fading: the art is untouched until here and reaches the background
 * colour at the bottom, which puts the fade under the name and the buttons rather than over the
 * middle of the artwork.
 */
private const val ARTIST_HERO_FADE_START = 0.45f
