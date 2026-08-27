package dylan.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/** Navigation stack entry. Tab roots live IN the stack so back walks Search → Home → exit. */
internal sealed interface Screen {
    data class Tab(
        val index: Int,
    ) : Screen

    data class Album(
        val id: String,
    ) : Screen

    data class Artist(
        val name: String,
        val token: String,
    ) : Screen

    data object Downloads : Screen
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    container: AppContainer,
    onFirstPlay: () -> Unit,
    onReportDrawn: () -> Unit,
) {
    val state by container.orchestrator.state.collectAsState()
    val backStack = remember { mutableStateListOf<Screen>(Screen.Tab(0)) }
    var npOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    val playNow: (List<dylan.model.Song>, Int) -> Unit = { songs, idx ->
        onFirstPlay()
        container.orchestrator.submit(Intent.PlayNow(songs, idx))
    }
    val openArtist: (dylan.model.MiniEntity) -> Unit = { m ->
        val token = m.artistId.orEmpty()
        if (token.isNotBlank()) backStack.add(Screen.Artist(m.title, token))
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun switchTab(i: Int) {
        // Bottom-nav convention: return to that tab's root wherever it sits in the stack.
        val root = backStack.indexOfFirst { it is Screen.Tab && it.index == i }
        if (root >= 0) {
            while (backStack.size > root + 1) backStack.removeAt(backStack.lastIndex)
        } else {
            backStack.add(Screen.Tab(i))
        }
    }

    BackHandler(enabled = !npOpen && backStack.size > 1) {
        pop()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (val screen = backStack.last()) {
                    is Screen.Album -> AlbumScreen(container, screen.id, onBack = { pop() }, onPlaySongs = playNow)
                    is Screen.Artist -> ArtistScreen(container, screen.token, screen.name, onPlaySongs = playNow)
                    Screen.Downloads -> DownloadsScreen(container, onPlaySongs = playNow, onBack = { pop() })
                    is Screen.Tab ->
                        when (screen.index) {
                            0 ->
                                HomeScreen(
                                    container,
                                    onOpenAlbum = { backStack.add(Screen.Album(it)) },
                                    onPlaySongs = playNow,
                                    onOpenSettings = { settingsOpen = true },
                                    onOpenArtist = openArtist,
                                )
                            1 ->
                                SearchScreen(
                                    container,
                                    onOpenAlbum = { backStack.add(Screen.Album(it)) },
                                    onOpenArtist = openArtist,
                                    onPlaySongs = playNow,
                                )
                            else -> LibraryScreen(container, onPlaySongs = playNow, onOpenDownloads = { backStack.add(Screen.Downloads) })
                        }
                }
            }

            // Mini player stays MOUNTED whenever a track exists — the NP overlay slides over
            // it, so dismissing reveals it continuously instead of popping it in afterwards.
            if (state.current != null) {
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
                    val selected = backStack.last() is Screen.Tab && (backStack.last() as Screen.Tab).index == i
                    NavigationBarItem(
                        selected = selected,
                        onClick = { switchTab(i) },
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
            NpOverlay(
                container = container,
                onClosed = { npOpen = false },
                onQueue = { queueOpen = true },
                onOpenArtist = { name, token -> if (token.isNotBlank()) backStack.add(Screen.Artist(name, token)) },
                onEnsureService = onFirstPlay,
            )
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

private enum class NpAnchor { Open, Closed }

/**
 * Now Playing overlay — ground-up rebuild replacing ModalBottomSheet (whose dismissal lagged
 * and never tracked the finger). Foundation AnchoredDraggable: offset follows the finger 1:1
 * on the way down, settle() picks the anchor from velocity/position on release, and the mini
 * player underneath is revealed continuously during the slide.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NpOverlay(
    container: AppContainer,
    onClosed: () -> Unit,
    onQueue: () -> Unit,
    onOpenArtist: (String, String) -> Unit,
    onEnsureService: () -> Unit,
) {
    val t = LocalDylanTokens.current
    var heightPx by remember { mutableFloatStateOf(0f) }
    val npState =
        remember {
            AnchoredDraggableState(
                initialValue = NpAnchor.Closed,
                anchors =
                    DraggableAnchors {
                        NpAnchor.Open at 0f
                        NpAnchor.Closed at 1f
                    },
            )
        }
    val fling = AnchoredDraggableDefaults.flingBehavior(npState)
    val backScope = androidx.compose.runtime.rememberCoroutineScope()
    BackHandler { backScope.launch { npState.animateAnchor(NpAnchor.Closed) } }

    LaunchedEffect(heightPx) {
        if (heightPx > 0f) {
            npState.updateAnchors(
                DraggableAnchors {
                    NpAnchor.Open at 0f
                    NpAnchor.Closed at heightPx
                },
            )
        }
    }
    // Entrance: slide up once anchors are measurable (tap + pull-up handoff share this path).
    LaunchedEffect(Unit) {
        snapshotFlow { heightPx }.firstOrNull { it > 0f }
        npState.animateAnchor(NpAnchor.Open)
    }
    // Fully settled at the bottom anchor ⇒ unmount (mini player is already visible beneath).
    LaunchedEffect(Unit) {
        snapshotFlow { npState.currentValue }.collect { if (it == NpAnchor.Closed) onClosed() }
    }

    Box(Modifier.fillMaxSize()) {
        // Scrim — progress computed inside graphicsLayer (draw phase) so drag re-draws without recomposing.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = if (heightPx > 0f && npState.offset.isFinite()) (npState.offset / heightPx).coerceIn(0f, 1f) else 1f
                    alpha = 0.65f * (1f - p)
                }.background(androidx.compose.ui.graphics.Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures { }
                },
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .background(t.surface)
                .onSizeChanged { heightPx = it.height.toFloat() }
                .graphicsLayer { translationY = if (npState.offset.isFinite()) npState.offset else heightPx }
                .anchoredDraggable(npState, flingBehavior = fling, orientation = Orientation.Vertical),
        ) {
            NowPlayingSheet(
                container,
                onQueue = onQueue,
                onOpenArtist = onOpenArtist,
                onEnsureService = onEnsureService,
            )
        }
    }
}

/**
 * Programmatic settle to an anchor (animateTo was removed from the 1.8+ state API).
 * Drives [AnchoredDraggableState.anchoredDrag] with an [androidx.compose.animation.core.animate]
 * tween so the sheet rides the same offset pipeline as finger drags.
 */
private suspend fun <T> AnchoredDraggableState<T>.animateAnchor(
    target: T,
    spec: androidx.compose.animation.core.AnimationSpec<Float> =
        androidx.compose.animation.core
            .tween(280),
) {
    if (!anchors.hasPositionFor(target)) return
    val targetOffset = anchors.positionOf(target)
    val start = if (offset.isFinite()) offset else targetOffset
    anchoredDrag {
        androidx.compose.animation.core.animate(
            initialValue = start,
            targetValue = targetOffset,
            animationSpec = spec,
        ) { v, _ ->
            dragTo(v)
        }
    }
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
