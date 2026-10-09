package dylan.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dylan.android.DylanApp
import dylan.android.media.AudioRoute
import dylan.android.media.RouteKind
import dylan.android.ui.LocalDylanTokens
import dylan.android.ui.PlayPauseIcon
import dylan.android.ui.components.rememberDownloadPct
import dylan.di.AppContainer
import dylan.model.Phase
import dylan.model.PlayerState
import dylan.model.Repeat
import dylan.model.SongKey
import dylan.playback.Intent
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    container: AppContainer,
    onQueue: () -> Unit,
    onOpenArtist: (String, String) -> Unit = { _, _ -> },
    onEnsureService: () -> Unit = {},
    swipeEnabled: Boolean = true,
) {
    val state by container.orchestrator.state.collectAsStateWithLifecycle()
    // A guard rather than a path: the surface only mounts this while a track is loaded, but `current`
    // can go null a frame before the surface is torn down, and every field below is the song itself.
    val song = state.current ?: return

    // Real cached bitrate for the quality chip — never a hardcoded guess.
    var bitsLabel by remember(song.key.songId) { mutableStateOf("") }
    LaunchedEffect(song.key.songId) {
        val row =
            kotlinx.coroutines.withContext(container.disp.dbLane) {
                runCatching {
                    container.db.dylanQueries
                        .selectCached(song.key.provider, song.key.songId)
                        .executeAsOneOrNull()
                }.getOrNull()
            }
        bitsLabel = row?.bitrate?.let { "${it}kbps" } ?: ""
    }

    val currentDownloadPct = rememberDownloadPct(container, song.key)

    NpFace(
        container = container,
        song = song,
        state = state,
        downloadPct = currentDownloadPct.value,
        bitsLabel = bitsLabel,
        swipeEnabled = swipeEnabled,
        onQueue = onQueue,
        onOpenArtist = onOpenArtist,
        onEnsureService = onEnsureService,
    )
}

/**
 * The face: artwork and metadata under the status bar, transport and queue controls above the bottom
 * edge, and a flexible gap between them.
 *
 * Any height the device has to spare lands in that gap rather than as a band of nothing above the
 * artwork and another below the last control, which is what a single centred stack produced.
 */
@Composable
private fun NpFace(
    container: AppContainer,
    song: dylan.model.Song,
    state: dylan.model.PlayerState,
    downloadPct: Int?,
    bitsLabel: String,
    swipeEnabled: Boolean,
    onQueue: () -> Unit,
    onOpenArtist: (String, String) -> Unit,
    onEnsureService: () -> Unit,
) {
    val durMs = (song.durationS * MS_PER_SECOND).coerceAtLeast(1)
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        // The surface already stops above the navigation bar, so only the status bar is added here.
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val contentHeight = viewport - topInset
        // Square artwork, as wide as the content column allows and never taller than a fraction of
        // the space above the controls, which keeps it from becoming a poster in landscape.
        val artSide =
            minOf(maxWidth - CONTENT_H_PAD * 2, contentHeight * ART_HEIGHT_FRACTION)
                .coerceIn(MIN_ART_SIDE, MAX_ART_SIDE)
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Column(
                // `heightIn(min =)` is what lets the gap below be a weight: a scrolling column is
                // measured with unbounded height, so without a floor there is no leftover to share.
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewport)
                    .padding(
                        top = topInset + ART_TOP_GAP,
                        start = CONTENT_H_PAD,
                        end = CONTENT_H_PAD,
                        bottom = CONTENT_BOTTOM_GAP,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ArtworkPanel(container, song, state, artSide, downloadPct, swipeEnabled)
                Spacer(Modifier.height(16.dp))
                TrackHeading(song, onOpenArtist)
                SeekSlot(container, song, state, durMs, downloadPct)
                // Everything above stays under the artwork; everything below is pinned to the
                // bottom of the surface. The weight takes whatever height is left over, so a short
                // window — or a large font scale, where the content overflows and this collapses to
                // [MIN_FLEX_GAP] — needs no second layout.
                Spacer(Modifier.weight(1f).heightIn(min = MIN_FLEX_GAP))
                TransportRow(container, state, onEnsureService)
                AudioRouteChip(state)
                Spacer(Modifier.height(8.dp))
                BottomRow(container, song, state, bitsLabel, onQueue)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * The artwork, which doubles as the loading surface — status rides ON the art (a fixed slot, nothing
 * below ever shifts) instead of a conditional row that pushed the seekbar.
 *
 * The artwork is the only part of the face with a gesture detector of its own, and that detector
 * competes with the face's `verticalScroll` for the finger: see [awaitSkipDrag] for why it has to
 * decide the axis rather than take any horizontal travel.
 */
@Composable
private fun ArtworkPanel(
    container: AppContainer,
    song: dylan.model.Song,
    state: dylan.model.PlayerState,
    side: Dp,
    downloadPct: Int?,
    swipeEnabled: Boolean,
) {
    val t = LocalDylanTokens.current
    val loading = isLoading(state)
    Box(
        Modifier
            .size(side)
            // Keyed on `swipeEnabled`, not just the track: the artwork sits behind the collapsed bar,
            // and without this a sideways swipe on the bar would skip tracks while the player was
            // shut.
            .pointerInput(state.index, swipeEnabled) {
                if (!swipeEnabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    awaitSkipDrag(down, viewConfiguration.touchSlop) { leftward ->
                        container.orchestrator.submit(
                            if (leftward) Intent.Next else Intent.Previous,
                        )
                    }
                }
            },
    ) {
        AsyncImage(
            model = song.artUrl500,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        if (loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Color.Black
                            .copy(alpha = 0.55f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = t.primary,
                        strokeWidth = 2.dp,
                    )
                    Text(
                        when (state.phase) {
                            is dylan.model.Phase.Downloading -> "DOWNLOADING ${downloadPct ?: 0}%"
                            else -> "PREPARING"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                        color = t.textSecondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * The artwork's swipe: one gesture, one track change, and an axis.
 *
 * `detectHorizontalDragGestures` — which this used to be — is wrong here twice, and both failures
 * come from how it decides when a gesture is horizontal.
 *
 * **It claims the gesture on horizontal travel alone.** `awaitHorizontalPointerSlopOrCancellation`
 * builds its threshold out of `TouchSlopDetector(orientation = Horizontal)`, whose
 * `getPostSlopOffset` compares the accumulated `mainAxis()` (x) with the touch slop and never looks
 * at `crossAxis()` (y). A finger being dragged down is never perfectly vertical, so it crosses
 * horizontal slop too; this node then consumes the change, and the face's `verticalScroll` never
 * gets the gesture. Its own slop detection runs in the same Main pass but *later* — hit paths are
 * walked children-first for the Main pass (`Node.dispatchMainEventPass` dispatches Initial, then
 * children, then Main) — so by the time the scroll looks, the change is consumed and it drops the
 * gesture. Nothing scrolls, nothing dispatches nested scroll, and collapsing the surface depends
 * entirely on `NpPlayerSurface`'s `SurfaceNestedScroll.onPostScroll`. That is why a downward swipe
 * starting on the artwork is the one place the player refuses to close.
 *
 * **Its `onDrag` reports the per-event delta** — `onHorizontalDrag(it, it.positionChange().x)` — so
 * a slow swipe that never emits one delta above [SWIPE_SKIP_PX] skips nothing, and a fast flick that
 * emits several skips several tracks in a single gesture.
 *
 * So the axis is decided here, the way Compose decides it between two drag nodes: horizontal while
 * the accumulated travel stays within `HorizontalAngleUpperBounds` (30°) of the horizontal axis,
 * which is [SWIPE_MAX_SLOPE]. A steeper drag is left *unconsumed*, so the scroll takes it in the
 * very same event and drags the player away instead. Travel is accumulated from the down point and
 * the skip fires once, when the accumulated distance first passes [SWIPE_SKIP_PX], whatever the
 * speed. Once this node has taken the gesture it keeps it, which is the same orientation lock
 * `detectHorizontalDragGestures` documents.
 *
 * @param onSkip true when the finger travelled left, the direction of `Intent.Next`.
 */
private suspend fun AwaitPointerEventScope.awaitSkipDrag(
    down: PointerInputChange,
    touchSlop: Float,
    onSkip: (Boolean) -> Unit,
) {
    var travelX = 0f
    var travelY = 0f
    var horizontal = false
    var skipped = false
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == down.id }
        val pointerGone = change == null || !change.pressed
        val takenOver = !horizontal && change?.isConsumed == true
        // The pointer left, or the face's scroll claimed this gesture while the axis was still
        // undecided. Either way: stop, and leave the change alone so whoever has it keeps it.
        if (pointerGone || takenOver) break
        val delta = change.positionChange()
        travelX += delta.x
        travelY += delta.y
        val pastSlop = abs(travelX) >= touchSlop
        val withinAxis = abs(travelY) <= abs(travelX) * SWIPE_MAX_SLOPE
        horizontal = horizontal || (pastSlop && withinAxis)
        if (horizontal) {
            // Consume before anything else can look at this event: the scroll never gets the
            // chance to take a gesture this node has already locked onto.
            change.consume()
            if (!skipped && abs(travelX) >= SWIPE_SKIP_PX) {
                skipped = true
                onSkip(travelX < 0f)
            }
        }
    }
}

/** Title and subtitle. The subtitle line is also the way to the track's artist. */
@Composable
private fun TrackHeading(
    song: dylan.model.Song,
    onOpenArtist: (String, String) -> Unit,
) {
    val t = LocalDylanTokens.current
    Text(
        song.title.uppercase(),
        style = MaterialTheme.typography.displayLarge,
        color = t.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.basicMarquee(),
    )
    Text(
        (song.subtitle.ifBlank { song.albumName.orEmpty() }).uppercase(),
        style = MaterialTheme.typography.bodyLarge,
        color = t.textSecondary,
        maxLines = 1,
        modifier =
            when (val artistToken = song.artistToken) {
                null -> Modifier
                else -> Modifier.clickable { onOpenArtist(song.artistName ?: song.subtitle, artistToken) }
            },
    )
    // Jump to the album this track belongs to (D8): provider resolves numeric
    // album_id and perma tokens alike, so Song.albumId works directly.
    // (Album jump removed: the subtitle line already carries the album info.)
}

/**
 * The seekbar slot: buffering lives INSIDE fixed-height slots (artwork overlay + slider slot) — a
 * conditional status row here used to shift the whole layout on every track change.
 */
@Composable
private fun SeekSlot(
    container: AppContainer,
    song: dylan.model.Song,
    state: dylan.model.PlayerState,
    durMs: Long,
    downloadPct: Int?,
) {
    val t = LocalDylanTokens.current
    // Scrubbable only once transportable (Playing/Paused/Ready) and not mid-resolve.
    val transportable = isTransportable(state)
    if (isLoading(state)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(top = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (downloadPct != null) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { downloadPct / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = t.primary,
                    trackColor = t.divider,
                )
            } else {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = t.primary,
                    trackColor = t.divider,
                )
            }
        }
    } else {
        SeekBar(
            container = container,
            songKey = song.key,
            durMs = durMs,
            enabled = transportable,
            dim = t.divider,
            accent = t.primary,
        )
    }
}

/** Shuffle, previous, play/pause, next and repeat, pinned above the bottom edge. */
@Composable
private fun TransportRow(
    container: AppContainer,
    state: dylan.model.PlayerState,
    onEnsureService: () -> Unit,
) {
    val t = LocalDylanTokens.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { container.orchestrator.submit(Intent.ToggleShuffle) }) {
            Icon(
                dylan.android.ui.Dyl.Shuffle,
                "Shuffle",
                tint = if (state.shuffleOn) t.primary else t.textPrimary,
            )
        }
        IconButton(onClick = { container.orchestrator.submit(Intent.Previous) }) {
            Icon(dylan.android.ui.Dyl.Prev, "Previous", tint = t.textPrimary)
        }
        Box(
            Modifier
                .size(64.dp)
                .background(t.primary),
            contentAlignment = Alignment.Center,
        ) {
            PlayPauseIcon(
                container = container,
                size = 48,
                playing = state.phase is dylan.model.Phase.Playing,
                onEnsureService = onEnsureService,
            )
        }
        IconButton(onClick = { container.orchestrator.submit(Intent.Next) }) {
            Icon(dylan.android.ui.Dyl.Next, "Next", tint = t.textPrimary)
        }
        IconButton(onClick = { container.orchestrator.submit(Intent.CycleRepeat) }) {
            Box(contentAlignment = Alignment.TopEnd) {
                Icon(
                    dylan.android.ui.Dyl.Repeat,
                    "Repeat",
                    tint = if (state.repeat != Repeat.OFF) t.primary else t.textPrimary,
                )
                if (state.repeat == Repeat.ONE) {
                    Text(
                        "1",
                        style = MaterialTheme.typography.labelSmall,
                        color = t.primary,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * "PLAYING ON <device>".
 *
 * Phase matters: AudioRouteMonitor seeds its flow at construction by replaying current outputs, so
 * with BT headphones paired and nothing playing this chip claimed "PLAYING ON <headphones>" over an
 * idle player. Only while a track is actually playing.
 */
@Composable
private fun AudioRouteChip(state: dylan.model.PlayerState) {
    val t = LocalDylanTokens.current
    val route = rememberAudioRoute()
    if (state.phase is dylan.model.Phase.Playing && route != null && route.kind != RouteKind.SPEAKER) {
        Spacer(Modifier.height(10.dp))
        Text(
            "PLAYING ON ${route.productName?.uppercase() ?: when (route.kind) {
                RouteKind.BLUETOOTH -> "BLUETOOTH"
                RouteKind.WIRED -> "HEADPHONES"
                RouteKind.SPEAKER -> ""
            }}",
            style = MaterialTheme.typography.labelSmall,
            color = t.textSecondary,
            maxLines = 1,
            modifier =
                Modifier
                    .border(1.dp, t.divider)
                    .background(t.background)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** Queue, favourite and the status line, along the bottom edge. */
@Composable
private fun BottomRow(
    container: AppContainer,
    song: dylan.model.Song,
    state: dylan.model.PlayerState,
    bitsLabel: String,
    onQueue: () -> Unit,
) {
    val t = LocalDylanTokens.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onQueue) { Icon(dylan.android.ui.Dyl.Queue, "Queue", tint = t.textSecondary) }
        FavoriteButton(container, song)
        Text(
            when (val p = state.phase) {
                // `Downloading` deliberately renders nothing. It used to print the internal song id
                // ("SAVING R3312…"), which is a provider token, not something a listener can act on or
                // read; the offline fetch is already visible as the row's own progress. `Resolving`
                // below carries the user-facing "preparing" state, and `Phase.Downloading` falls
                // through to the empty branch with the rest.
                is dylan.model.Phase.Resolving -> "PREPARING…"
                is dylan.model.Phase.Error ->
                    dylan.android.ui.Copy
                        .forCode(p.failure.code)
                is dylan.model.Phase.Playing, is dylan.model.Phase.Paused -> bitsLabel.uppercase()
                else -> ""
            },
            style = MaterialTheme.typography.labelSmall,
            color = t.textSecondary,
        )
    }
}

/**
 * The heart toggle.
 *
 * E6: favorite state must survive toggles and stay fresh across screens — the read is keyed on both
 * the song and the repo's invalidation counter instead of a one-shot load. It lives here rather than
 * in [NowPlayingSheet] so that a recomposition of the face cannot be the thing that decides whether
 * the heart is stale.
 */
@Composable
private fun FavoriteButton(
    container: AppContainer,
    song: dylan.model.Song,
) {
    val t = LocalDylanTokens.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val favVersion by container.favorites.version.collectAsStateWithLifecycle()
    var isFavorite by remember(song.key.songId) { mutableStateOf(false) }
    LaunchedEffect(song.key.songId, favVersion) {
        isFavorite = runCatching { container.favorites.isFavorite(song.key) }.getOrDefault(false)
    }
    IconButton(onClick = {
        scope.launch {
            runCatching {
                if (container.favorites.isFavorite(song.key)) {
                    container.favorites.remove(song.key)
                    isFavorite = false
                } else {
                    container.favorites.add(song)
                    isFavorite = true
                }
            }
        }
    }) {
        Icon(
            if (isFavorite) dylan.android.ui.Dyl.Heart else dylan.android.ui.Dyl.HeartOutline,
            "Favorite",
            tint = if (isFavorite) t.primary else t.textSecondary,
        )
    }
}

/** Resolving or downloading: the face shows progress over the artwork and in the seekbar slot. */
private fun isLoading(state: PlayerState): Boolean = state.phase is Phase.Resolving || state.phase is Phase.Downloading

/** Playing, paused or ready — the states in which a seek is meaningful. */
private fun isTransportable(state: dylan.model.PlayerState): Boolean =
    state.phase is dylan.model.Phase.Playing ||
        state.phase is dylan.model.Phase.Paused ||
        state.phase is dylan.model.Phase.Ready

/** Milliseconds in a second: the engine counts in ms, every label on the face counts in seconds. */
private const val MS_PER_SECOND = 1000L

/** Seconds in a minute, for the minutes field of the elapsed/duration labels. */
private const val SECONDS_PER_MINUTE = 60L

private fun formatTime(ms: Long): String {
    val s = ms / MS_PER_SECOND
    return "%d:%02d".format(s / SECONDS_PER_MINUTE, s % SECONDS_PER_MINUTE)
}

/**
 * Slider plus elapsed/duration, isolated so the 10 Hz position invalidates this subtree only — the
 * artwork, marquee and transport row above it do not recompose while a track plays. Drag state is
 * keyed on the song so a track change cannot leave a stale thumb position behind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(
    container: AppContainer,
    songKey: SongKey,
    durMs: Long,
    enabled: Boolean,
    dim: androidx.compose.ui.graphics.Color,
    accent: androidx.compose.ui.graphics.Color,
) {
    val t = LocalDylanTokens.current
    val posMs by container.orchestrator.positionMs.collectAsStateWithLifecycle(0L)
    var dragging by remember(songKey) { mutableStateOf(false) }
    var dragPos by remember(songKey) { mutableFloatStateOf(0f) }
    // Resume position is preserved: while loading we keep showing posMs (never snap to 0).
    val shown = if (dragging) dragPos else posMs.toFloat()
    Column {
        Slider(
            value = shown.coerceIn(0f, durMs.toFloat()),
            onValueChange = {
                dragging = true
                dragPos = it
            },
            onValueChangeFinished = {
                container.orchestrator.submit(Intent.Seek(dragPos.toLong()))
                dragging = false
            },
            enabled = enabled,
            valueRange = 0f..durMs.toFloat(),
            colors =
                SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = dim,
                    activeTickColor = androidx.compose.ui.graphics.Color.Transparent,
                    inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledThumbColor = dim,
                    disabledActiveTrackColor = dim,
                    disabledInactiveTrackColor = dim,
                ),
            thumb = {
                Box(
                    Modifier
                        .size(if (dragging) 22.dp else 18.dp)
                        .background(dim)
                        .padding(3.dp)
                        .background(androidx.compose.ui.graphics.Color.White),
                )
            },
            track = { sliderState ->
                val range = sliderState.valueRange
                val frac =
                    if (range.endInclusive > range.start) {
                        ((sliderState.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                Box(Modifier.fillMaxWidth().height(if (dragging) 6.dp else 4.dp).background(dim)) {
                    Box(
                        Modifier
                            .fillMaxWidth(frac)
                            .fillMaxHeight()
                            .background(accent),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().height(36.dp).padding(top = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatTime(shown.toLong()),
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    ),
                color = if (dragging) accent else t.textPrimary,
            )
            Text(
                formatTime(durMs),
                style = MaterialTheme.typography.bodyMedium,
                color = t.textSecondary,
            )
        }
    }
}

@Composable
private fun rememberAudioRoute(): AudioRoute? {
    val app = LocalContext.current.applicationContext as? DylanApp
    val flow = remember(app) { app?.mediaHub?.audioRoute }
    return flow?.collectAsStateWithLifecycle()?.value
}

/**
 * Artwork sizing and the spacing around the content block.
 *
 * The artwork is square and its side is the smaller of the width the content column leaves and a
 * fraction of the height above the controls. The width almost always wins on a phone — a square
 * cannot be wider than the screen — so the fraction is really a ceiling that stops a short or
 * landscape window from pushing everything else off, and the leftover height is absorbed by
 * [MIN_FLEX_GAP]'s weighted sibling rather than by margins around the whole stack.
 */
private const val ART_HEIGHT_FRACTION = 0.45f
private val MIN_ART_SIDE = 180.dp
private val MAX_ART_SIDE = 420.dp

/** Horizontal inset of the whole face, including the artwork. */
private val CONTENT_H_PAD = 16.dp

/** Breathing room between the status bar and the artwork, and between the last row and the edge. */
private val ART_TOP_GAP = 8.dp
private val CONTENT_BOTTOM_GAP = 16.dp

/**
 * Horizontal travel, in pixels, that one artwork swipe has to cover before it counts as a skip.
 *
 * Deliberately well under the width of the artwork: the swipe starts anywhere on the art, including
 * its edges, so a short drag still has to be enough to change track. Measured as the *accumulated*
 * travel of the gesture — see [awaitSkipDrag] for why a per-event delta cannot be used here.
 */
private const val SWIPE_SKIP_PX = 60f

/**
 * Tangent of the 30° horizontal-angle bound Compose itself uses to arbitrate a gesture between two
 * drag nodes: the artwork here, the face's `verticalScroll` on that side. A swipe no steeper than
 * this counts as horizontal and skips; anything steeper is left unconsumed so the scroll takes it
 * and the player can be dragged away.
 */
private const val SWIPE_MAX_SLOPE = 0.5773503f

/** Floor on the flexible gap, so metadata and transport never touch on a very short window. */
private val MIN_FLEX_GAP = 24.dp
