package dylan.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dylan.android.ui.screens.AlbumScreen
import dylan.android.ui.screens.ArtistScreen
import dylan.android.ui.screens.DownloadsScreen
import dylan.android.ui.screens.HomeScreen
import dylan.android.ui.screens.LibraryScreen
import dylan.android.ui.screens.SearchScreen
import dylan.android.ui.screens.SettingsScreen
import dylan.di.AppContainer
import dylan.playback.Intent

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

/** Single sheet machine: only one of NP / Queue / Settings is ever presented. */
internal enum class Sheet { Closed, Np, Queue, Settings }

/**
 * The nav stack and the open sheet survive rotation and process death.
 *
 * The manifest declares no `configChanges`, so a rotation destroys and recreates the Activity —
 * a plain `remember` would drop the user back to the Home tab (and, in Search, discard a
 * submitted query) on every rotation. `Screen` is a sealed interface over data classes that
 * Bundle cannot store, so it is encoded as length-prefixed strings; a length prefix keeps an
 * album/artist id containing the separator round-tripping.
 */
private val backStackSaver =
    listSaver<SnapshotStateList<Screen>, String>(
        save = { stack -> stack.map(Screen::encode) },
        restore = { encoded ->
            val restored = encoded.mapNotNull(::decodeScreen).toMutableStateList()
            if (restored.isEmpty()) listOf(Screen.Tab(0)).toMutableStateList() else restored
        },
    )

private val sheetSaver =
    Saver<Sheet, Int>(
        save = { it.ordinal },
        restore = { Sheet.entries.getOrNull(it) },
    )

private fun Screen.encode(): String =
    when (this) {
        is Screen.Tab -> "t$index"
        is Screen.Album -> "a${id.length}:$id"
        is Screen.Artist -> "r${name.length}:$name$token"
        Screen.Downloads -> "d"
    }

private fun decodeScreen(encoded: String): Screen? {
    val body = encoded.substring(1)
    return when (encoded.firstOrNull()) {
        't' -> body.toIntOrNull()?.let { Screen.Tab(it) }
        'd' -> Screen.Downloads
        'a' -> splitHead(body)?.let { Screen.Album(it.first) }
        'r' -> splitHead(body)?.let { Screen.Artist(it.first, it.second) }
        else -> null
    }
}

/** `len:head…tail` → (head, tail); null when the encoding is malformed. */
private fun splitHead(body: String): Pair<String, String>? {
    val colon = body.indexOf(':')
    if (colon <= 0) return null
    val len = body.substring(0, colon).toIntOrNull() ?: return null
    val rest = body.substring(colon + 1)
    if (len > rest.length) return null
    return rest.substring(0, len) to rest.substring(len)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    container: AppContainer,
    onFirstPlay: () -> Unit,
    onReportDrawn: () -> Unit,
) {
    val state by container.orchestrator.state.collectAsStateWithLifecycle()
    val backStack = rememberSaveable(saver = backStackSaver) { mutableStateListOf<Screen>(Screen.Tab(0)) }
    var sheet by rememberSaveable(stateSaver = sheetSaver) { mutableStateOf(Sheet.Closed) }
    // Height of the navigation bar, measured once the first frame has laid it out. It is 0 until
    // then, which is why the player surface is not mounted before it is known — see below.
    var navBarHeightPx by remember { mutableFloatStateOf(0f) }

    // Stable across recompositions: a fresh lambda instance here is a changed parameter for all
    // six screens, so none of them could skip.
    val playNow =
        remember(container, onFirstPlay) {
            { songs: List<dylan.model.Song>, idx: Int ->
                onFirstPlay()
                container.orchestrator.submit(Intent.PlayNow(songs, idx))
            }
        }
    val openArtist =
        remember(backStack) {
            { m: dylan.model.MiniEntity ->
                val token = m.artistId.orEmpty()
                if (token.isNotBlank()) backStack.add(Screen.Artist(m.title, token))
            }
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

    BackHandler(enabled = sheet == Sheet.Queue) {
        sheet = Sheet.Np
    }
    BackHandler(enabled = sheet == Sheet.Closed && backStack.size > 1) {
        pop()
    }

    // Empty current ⇒ close everything (NP/queue/settings never outlive the session).
    LaunchedEffect(state.current) {
        if (state.current == null) sheet = Sheet.Closed
    }

    // reportFullyDrawn is a side effect on the Activity, not on the composition: calling it here
    // ran it on every state emission (AppRoot reads `state`), and a side effect belongs after a
    // frame rather than inside one. One shot, after the first frame is composed.
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        onReportDrawn()
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
                                    onOpenSettings = { sheet = Sheet.Settings },
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

            // Room for the collapsed player surface. The bar itself is drawn by the surface (see
            // NpPlayerSurface) so that it and the full-screen player are one object; this spacer is
            // only here so screen content is not laid out underneath it. It is never visible once
            // the surface is up — the surface covers exactly this band — but it carries the surface
            // colour, so the frames before the surface mounts show the bar's own background instead
            // of a hole.
            if (state.current != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(MINI_BAR_HEIGHT)
                        .background(LocalDylanTokens.current.surface),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LocalDylanTokens.current.divider),
            )
            NavigationBar(
                // The player surface rests its collapsed bar directly on top of this, so it needs
                // to know exactly how tall the navigation bar is — including the navigation-bar
                // inset the component applies itself, which is not the inset on its own.
                modifier = Modifier.onSizeChanged { navBarHeightPx = it.height.toFloat() },
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

        // One player surface for the whole session: it is the collapsed bar when `sheet` is Closed
        // and the full-screen player (or the queue, which rides inside it) when it is not. It is
        // mounted whenever a track exists, so opening the player is a change of anchor rather than
        // a composable appearing — that is what removes the entrance animation and the flash. It
        // waits for the navigation bar to be measured because that measurement is what puts its
        // collapsed bar on top of the navigation bar rather than a bar's height off it; the space
        // it reserves above the navigation bar is already laid out and already the right colour, so
        // the wait costs the artwork one frame and nothing else.
        if (state.current != null && navBarHeightPx > 0f) {
            NpPlayerSurface(
                container = container,
                navBarHeightPx = navBarHeightPx,
                expanded = sheet == Sheet.Np || sheet == Sheet.Queue,
                showQueue = sheet == Sheet.Queue,
                onExpand = { sheet = Sheet.Np },
                onCollapse = { sheet = Sheet.Closed },
                onOpenQueue = { sheet = Sheet.Queue },
                onCloseQueue = { sheet = Sheet.Np },
                onOpenArtist = { name, token -> if (token.isNotBlank()) backStack.add(Screen.Artist(name, token)) },
                onEnsureService = onFirstPlay,
            )
        }
        if (sheet == Sheet.Settings) {
            ModalBottomSheet(
                onDismissRequest = { sheet = Sheet.Closed },
                containerColor = MaterialTheme.colorScheme.surface,
                sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                SettingsScreen(container)
            }
        }
    }
}

@Composable
internal fun PlayPauseIcon(
    container: AppContainer,
    size: Int,
    playing: Boolean,
    onEnsureService: () -> Unit = {},
) {
    IconButton(
        onClick = {
            // After process death the snapshot restores PAUSED with no service running — a bare
            // TogglePlayPause would buffer forever waiting for an engine that never attaches.
            onEnsureService()
            container.orchestrator.submit(Intent.TogglePlayPause)
        },
        modifier = Modifier.height(size.dp),
    ) {
        // Pause glyph only while actually Playing — Ready is pre-audible, not playing.
        androidx.compose.material3.Icon(
            imageVector = if (playing) Dyl.Pause else Dyl.Play,
            contentDescription = if (playing) "Pause" else "Play",
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}
