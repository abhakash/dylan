package dylan.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dylan.android.ui.screens.AlbumScreen
import dylan.android.ui.screens.ArtistScreen
import dylan.android.ui.screens.DownloadsScreen
import dylan.android.ui.screens.HomeScreen
import dylan.android.ui.screens.LibraryScreen
import dylan.android.ui.screens.NowPlayingSheet
import dylan.android.ui.screens.QueueSheet
import dylan.android.ui.screens.SearchScreen
import dylan.di.AppContainer
import dylan.playback.Intent

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    container: AppContainer,
    onFirstPlay: () -> Unit,
    onReportDrawn: () -> Unit,
) {
    val state by container.orchestrator.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var albumId by remember { mutableStateOf<String?>(null) }
    var artistTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var downloadsOpen by remember { mutableStateOf(false) }
    var npOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    val playNow: (List<dylan.model.Song>, Int) -> Unit = { songs, idx ->
        onFirstPlay()
        container.orchestrator.submit(Intent.PlayNow(songs, idx))
    }
    val openArtist: (dylan.model.MiniEntity) -> Unit = { m ->
        artistTarget = (m.artistId.orEmpty() to m.title).takeIf { it.first.isNotBlank() }
    }

    BackHandler(enabled = artistTarget != null || albumId != null || downloadsOpen) {
        when {
            artistTarget != null -> artistTarget = null
            albumId != null -> albumId = null
            else -> downloadsOpen = false
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when {
                    artistTarget != null ->
                        ArtistScreen(
                            container,
                            artistTarget!!.first,
                            artistTarget!!.second,
                            onPlaySongs = playNow,
                        )
                    albumId != null -> AlbumScreen(container, albumId!!, onBack = { albumId = null }, onPlaySongs = playNow)
                    downloadsOpen -> DownloadsScreen(container, onPlaySongs = playNow, onBack = { downloadsOpen = false })
                    else ->
                        when (tab) {
                            0 ->
                                HomeScreen(
                                    container,
                                    onOpenAlbum = { albumId = it },
                                    onPlaySongs = playNow,
                                    onOpenSettings = { settingsOpen = true },
                                    onOpenArtist = openArtist,
                                )
                            1 ->
                                SearchScreen(
                                    container,
                                    onOpenAlbum = { albumId = it },
                                    onOpenArtist = openArtist,
                                    onPlaySongs = playNow,
                                )
                            else -> LibraryScreen(container, onPlaySongs = playNow, onOpenDownloads = { downloadsOpen = true })
                        }
                }
            }

            if (state.current != null && !npOpen) {
                MiniPlayer(container, onExpand = { npOpen = true }, onLongPressClear = {}, onEnsureService = onFirstPlay)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LocalDylanTokens.current.divider),
            )
            NavigationBar(
                containerColor = LocalDylanTokens.current.background,
                contentColor = LocalDylanTokens.current.textPrimary,
            ) {
                listOf(
                    "HOME" to Dyl.Home,
                    "SEARCH" to Dyl.Search,
                    "LIBRARY" to Dyl.Library,
                ).forEachIndexed { i, (label, glyph) ->
                    val selected = tab == i && albumId == null && artistTarget == null && !downloadsOpen
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            tab = i
                            albumId = null
                            artistTarget = null
                            downloadsOpen = false
                        },
                        icon = {
                            Icon(
                                glyph,
                                contentDescription = label,
                                tint = if (selected) LocalDylanTokens.current.primary else LocalDylanTokens.current.textSecondary,
                            )
                        },
                        label = {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                                color = if (selected) LocalDylanTokens.current.primary else LocalDylanTokens.current.textSecondary,
                            )
                        },
                        colors =
                            androidx.compose.material3.NavigationBarItemDefaults.colors(
                                selectedIconColor = LocalDylanTokens.current.primary,
                                unselectedIconColor = LocalDylanTokens.current.textSecondary,
                                selectedTextColor = LocalDylanTokens.current.primary,
                                unselectedTextColor = LocalDylanTokens.current.textSecondary,
                                indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                    )
                }
            }
        }

        if (npOpen && state.current != null) {
            val npSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { npOpen = false },
                containerColor = MaterialTheme.colorScheme.surface,
                sheetState = npSheetState,
            ) {
                Box(Modifier.fillMaxHeight(0.94f)) {
                    NowPlayingSheet(
                        container,
                        onQueue = { queueOpen = true },
                        onOpenArtist = { name, token -> artistTarget = token to name },
                        onEnsureService = onFirstPlay,
                    )
                }
            }
        }
        if (queueOpen) {
            ModalBottomSheet(
                onDismissRequest = { queueOpen = false },
                sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                QueueSheet(container)
            }
        }
        if (settingsOpen) {
            ModalBottomSheet(
                onDismissRequest = { settingsOpen = false },
                containerColor = MaterialTheme.colorScheme.surface,
                sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                dylan.android.ui.screens
                    .SettingsScreen(container)
            }
        }
    }
    onReportDrawn()
}

@Composable
fun MiniPlayer(
    container: AppContainer,
    onExpand: () -> Unit,
    onLongPressClear: () -> Unit,
    onEnsureService: () -> Unit = {},
) {
    val state by container.orchestrator.state.collectAsState()
    val pos by container.orchestrator.positionMs.collectAsState(0L)
    val t = LocalDylanTokens.current
    val durMs = ((state.current?.durationS ?: 0L) * 1000L).coerceAtLeast(1)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(t.divider)
                .padding(top = 1.dp)
                .background(t.surface)
                // Pull up anywhere on the bar expands the Now Playing sheet — tap still works
                // (drag detector only claims events once a vertical drag is detected).
                .pointerInput(Unit) {
                    var accum = 0f
                    detectVerticalDragGestures(
                        onDragStart = { accum = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            accum += dragAmount
                            if (accum < -64.dp.toPx()) {
                                accum = 0f
                                onExpand()
                            }
                        },
                    )
                }.clickable(onClick = onExpand)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .height(44.dp)
                .background(t.divider)
                .padding(1.dp)
                .background(t.background),
        ) {
            AsyncImage(
                model = state.current?.artUrl150,
                contentDescription = null,
                modifier = Modifier.height(42.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                (state.current?.title.orEmpty()).uppercase(),
                style = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 0.3.sp),
                color = t.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (state.current?.subtitle.orEmpty()).uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                color = t.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .height(1.dp)
                    .fillMaxWidth((pos.toFloat() / durMs).coerceIn(0f, 1f))
                    .background(t.textPrimary),
            )
        }
        PlayPauseIcon(container, size = 32, onEnsureService = onEnsureService)
    }
}

@Composable
internal fun PlayPauseIcon(
    container: AppContainer,
    size: Int,
    onEnsureService: () -> Unit = {},
) {
    val state by container.orchestrator.state.collectAsState()
    val playing = state.phase is dylan.model.Phase.Playing || state.phase is dylan.model.Phase.Ready
    IconButton(
        onClick = {
            // After process death the snapshot restores PAUSED with no service running — a bare
            // TogglePlayPause would buffer forever waiting for an engine that never attaches.
            onEnsureService()
            container.orchestrator.submit(Intent.TogglePlayPause)
        },
        modifier = Modifier.height(size.dp),
    ) {
        androidx.compose.material3.Icon(
            imageVector = if (playing) Dyl.Pause else Dyl.Play,
            contentDescription = if (playing) "Pause" else "Play",
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}
