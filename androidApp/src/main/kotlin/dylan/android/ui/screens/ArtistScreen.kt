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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dylan.android.ui.LocalDylanTokens
import dylan.android.ui.components.SongRow
import dylan.di.AppContainer
import dylan.model.Artist
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
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(artistToken) {
        artist = runCatching { container.provider.artist(artistToken) }.getOrNull()
        failed = artist == null
    }

    val songs = artist?.songs.orEmpty()
    val st by container.orchestrator.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
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
                            0.45f to Color.Transparent,
                            1f to t.background,
                        ),
                    ),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text((artist?.name ?: fallbackName).uppercase(), style = MaterialTheme.typography.displayMedium, color = t.textPrimary)
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
        itemsIndexed(songs) { i, song ->
            SongRow(
                song = song,
                index = i + 1,
                isPlaying = song.key == st.current?.key,
                onTap = { onPlaySongs(songs, i) },
                enabled = song.cacheable,
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
}
