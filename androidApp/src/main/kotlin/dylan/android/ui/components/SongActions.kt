package dylan.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dylan.di.AppContainer
import dylan.model.MiniEntity
import dylan.model.Song
import dylan.model.SongKey
import dylan.playback.Intent
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class SongActions internal constructor(
    private val container: AppContainer,
    private val launchUi: (suspend () -> Unit) -> Unit,
) {
    fun playNext(song: Song) {
        container.orchestrator.submit(Intent.PlayNext(song))
    }

    fun addToQueue(song: Song) {
        container.orchestrator.submit(Intent.AddLast(song))
    }

    fun toggleFavorite(song: Song) {
        launchUi {
            try {
                if (container.favorites.isFavorite(song.key)) {
                    container.favorites.remove(song.key)
                } else {
                    container.favorites.add(song)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
}

@Composable
fun rememberSongActions(container: AppContainer): SongActions {
    val scope = rememberCoroutineScope()
    return remember(container, scope) {
        SongActions(container) { block ->
            scope.launch { block() }
        }
    }
}

@Composable
fun rememberFavoriteKeys(container: AppContainer): PersistentSet<SongKey> {
    val version by container.favorites.version.collectAsState()
    var keys by remember { mutableStateOf(persistentSetOf<SongKey>()) }
    LaunchedEffect(version) {
        keys =
            try {
                container.favorites
                    .all()
                    .map { it.key }
                    .toPersistentSet()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                persistentSetOf()
            }
    }
    return keys
}

internal fun Song.toArtistEntry(): MiniEntity =
    MiniEntity(
        songKey = key,
        albumId = albumId,
        title = artistName ?: subtitle,
        subtitle = "",
        type = "artist",
        image = artUrl150,
        permaToken = permaToken,
        artistId = artistToken,
    )

internal suspend fun <T> quietLoad(
    default: T,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        default
    }
