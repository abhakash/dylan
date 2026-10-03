package dylan.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dylan.android.ui.screens.NowPlayingSheet
import dylan.android.ui.screens.QueueSheet
import dylan.di.AppContainer
import dylan.model.Phase
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * The two resting places of the player surface.
 *
 * Anchors are **pixels**, measured from the top of the surface: [Full] is the surface flush with the
 * top of the window, [Mini] is the surface pushed down so that only its first [MINI_BAR_HEIGHT]
 * strip — the collapsed bar — is left showing above the navigation bar.
 */
internal enum class NpAnchor {
    Full,
    Mini,
}

/**
 * Height of the collapsed face, 1 dp divider + 10 dp padding + 46 dp content + 10 dp padding.
 *
 * This is a constant rather than a measurement because two different places need it at the same
 * time and must agree: AppRoot reserves exactly this much room above the navigation bar so screen
 * content is never hidden behind the bar, and the surface uses it to place [NpAnchor.Mini]. A
 * measured value would make the second one a frame late, which is the same class of bug as an
 * anchor that is out of date.
 */
internal val MINI_BAR_HEIGHT = 67.dp

/** 46 dp of content inside [MINI_BAR_HEIGHT]'s 1 dp divider plus 20 dp of padding. */
private val MINI_BAR_CONTENT = 46.dp

/**
 * Peak dimming of the app behind the surface. Only meaningful while the surface is between the two
 * anchors; at [NpAnchor.Mini] the app is fully visible and at [NpAnchor.Full] the surface covers it.
 */
private const val SCRIM_MAX_ALPHA = 0.65f

/**
 * Where the surface must travel past before a release commits to the far anchor, as a fraction of
 * the total travel and as an absolute cap.
 *
 * The Foundation default is 50% of the distance, which for a ~2100 px trip means the finger has to
 * travel more than a thousand pixels before a slow release opens the player — that is the "it jumps"
 * complaint in a different costume. 28% of the trip, never more than 200 dp, matches what a full
 * screen player feels like: nudge it open a little and it commits. A release faster than
 * Foundation's 125 dp/s minimum fling velocity commits regardless, which is unchanged.
 */
private const val COMMIT_FRACTION = 0.28f
private val COMMIT_MAX = 200.dp

/**
 * Settle spec for every path that ends a drag: the fling behaviour, back, and the nested-scroll
 * fling.
 *
 * `dampingRatio = 1f` is deliberate. Foundation's own settle allows a spring to overshoot past an
 * anchor on purpose ("we respect the user's intention and allow the overshoot"), which for a
 * full-screen player means the surface visibly travels off the top of the window and leaves a gap
 * at the bottom before coming back.
 */
private val SettleSpec: AnimationSpec<Float> =
    spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)

/**
 * The Now Playing surface: one draggable layer that is the full-screen player when it is open and
 * the collapsed bar when it is not.
 *
 * There is no second surface and no entrance animation. The bar is the surface's own top strip, so
 * a drag moves bar and player as one object in both directions and the finger and the pixels are
 * never out of step; and because the surface is mounted for as long as a track is loaded, opening
 * the player is a change of anchor on an already-visible layer rather than a composable appearing.
 *
 * @param navBarHeightPx measured height of the navigation bar *including* the navigation-bar inset
 *   the component applies to itself. It is measured rather than derived from
 *   `WindowInsets.navigationBars` because the inset is only part of it, and the collapsed anchor is
 *   the distance from the bottom of the window to the top of the navigation bar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NpPlayerSurface(
    container: AppContainer,
    navBarHeightPx: Float,
    expanded: Boolean,
    showQueue: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onOpenQueue: () -> Unit,
    onCloseQueue: () -> Unit,
    onOpenArtist: (String, String) -> Unit,
    onEnsureService: () -> Unit,
) {
    val t = LocalDylanTokens.current
    val density = LocalDensity.current
    val miniBarPx = with(density) { MINI_BAR_HEIGHT.toPx() }
    val commitCapPx = with(density) { COMMIT_MAX.toPx() }
    val scope = rememberCoroutineScope()

    // Measured height of the surface, and therefore the pixel distance between the two anchors.
    // Read inside `graphicsLayer` blocks (draw phase) so a re-measure never recomposes the tree.
    var surfacePx by remember { mutableFloatStateOf(0f) }

    val npState =
        remember {
            // No anchors here: they are pixels and the surface has not been measured yet. The
            // initial value decides which anchor the first `updateAnchors` snaps to, which is how a
            // process death while the player was open comes back open instead of flashing closed.
            AnchoredDraggableState(initialValue = if (expanded) NpAnchor.Full else NpAnchor.Mini)
        }

    val positionalThreshold =
        remember {
            val cap = commitCapPx
            val threshold = { distance: Float -> min(distance * COMMIT_FRACTION, cap) }
            threshold
        }
    val fling = AnchoredDraggableDefaults.flingBehavior(npState, positionalThreshold, SettleSpec)

    // AppRoot's `expanded` is the intent; this state is the truth. Keep both in step: intent drives
    // `animateTo`, and a drag that lands on an anchor drives the intent back out.
    val expandedNow by rememberUpdatedState(expanded)

    LaunchedEffect(expanded) {
        npState.animateTo(if (expanded) NpAnchor.Full else NpAnchor.Mini, SettleSpec)
    }
    LaunchedEffect(npState) {
        // A gesture is the only thing allowed to push the intent back out, and the test for "was
        // this a gesture" is simply whether the surface is resting somewhere the intent did not ask
        // for. Every intent-driven move (back, the queue, opening Settings from under an open
        // player) finishes with the two agreeing, so none of them can be mistaken for a dismissal.
        // Without this, collapsing the player to make room for Settings would read as the user
        // dismissing the player and close Settings as well.
        snapshotFlow { npState.currentValue }.collect { settled ->
            if (settled == if (expandedNow) NpAnchor.Full else NpAnchor.Mini) return@collect
            when (settled) {
                NpAnchor.Full -> onExpand()
                NpAnchor.Mini -> onCollapse()
            }
        }
    }
    // Back folds the player away. The queue has its own handler in AppRoot and consumes back first.
    BackHandler(enabled = expanded && !showQueue) {
        scope.launch { npState.animateTo(NpAnchor.Mini, SettleSpec) }
    }

    // The face scrolls, so a drag on the face is two gestures competing for one finger. Wiring the
    // scroll into the surface is what lets the content scroll back up while the player is open and
    // still collapse the player when the content is already at its top — without this the two
    // either fight or the content scrolls the player away. This is the same split Material's own
    // `ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection` uses: take the direction the
    // surface *cannot* move in during the pre pass, and let the leftover of the other direction
    // drive it in the post pass. Attached only while expanded, so the app's own lists underneath
    // keep their scroll behaviour untouched.
    val nestedScroll =
        remember(npState) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Upward: the surface is already at its topmost anchor whenever the face can
                    // scroll, so this normally consumes nothing — it is here so that if the surface
                    // is part-way collapsed the finger never has to fight two targets.
                    val dy = available.y
                    if (dy < 0f && source == NestedScrollSource.UserInput) {
                        val used = npState.dispatchRawDelta(dy)
                        if (used != 0f) return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Whatever the face could not take — which for a bottom-anchored surface means
                    // downward — moves the surface, 1:1 with the finger. `UserInput` covers both the
                    // drag and the fling that follows it, so a fling slides the surface as it decays
                    // instead of snapping only when it finally ends.
                    val dy = available.y
                    if (dy != 0f) {
                        val used = npState.dispatchRawDelta(dy)
                        if (used != 0f) return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    // A downward flick collapses, but only if the surface is not already there.
                    val off = npState.offset
                    if (available.y > 0f && off.isFinite() && off > npState.anchors.positionOf(NpAnchor.Full)) {
                        scope.launch { npState.animateTo(NpAnchor.Mini, SettleSpec) }
                        return available
                    }
                    return Velocity.Zero
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
                    if (available.y > 0f) {
                        scope.launch { npState.animateTo(NpAnchor.Mini, SettleSpec) }
                        return available
                    }
                    // The face took the whole fling. The surface may still be between anchors
                    // because `onPostScroll` moved it, so put it back on one.
                    scope.launch { npState.settleToNearestAnchor(SettleSpec) }
                    return Velocity.Zero
                }
            }
        }

    Box(
        Modifier
            .fillMaxSize()
            // Everything from the top of the window down to the navigation bar. The surface is
            // taller than that and is translated down off the bottom edge when collapsed, so the
            // overlay is clipped to this region — without the clip the player face, which sits
            // behind the collapsed bar, would show through the top of the navigation bar.
            .padding(bottom = with(density) { navBarHeightPx.toDp() })
            .clipToBounds(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // How far the surface has travelled out of its resting place, 0 collapsed and 1
                    // fully open. The visible height of the surface is `surfacePx - offset`, which
                    // runs from `miniBarPx` (collapsed, only the bar showing) to `surfacePx` (open).
                    // `offset` is NaN until the surface has anchors; a transparent scrim is a real
                    // state — it is what the surface shows at rest — so that is what an un-anchored
                    // frame draws. The surface itself uses `requireOffset`, because being in the
                    // wrong place is not a state it can draw.
                    val off = npState.offset
                    alpha =
                        if (off.isNaN()) {
                            0f
                        } else {
                            SCRIM_MAX_ALPHA * (surfacePx - off - miniBarPx) / (surfacePx - miniBarPx).coerceAtLeast(1f)
                        }.coerceIn(0f, SCRIM_MAX_ALPHA)
                }.background(Color.Black)
                // While collapsed the surface is only the bar, so this scrim is invisible and must
                // not eat touches belonging to the screen underneath it. Once the surface is opening
                // it covers the screen that is left uncovered above its top edge, and swallowing
                // taps there stops a second finger reaching the app mid-drag.
                .pointerInput(expanded) { if (expanded) detectTapGestures { } },
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight()
                .background(t.surface)
                // Anchors are pixels, so they can only be built once the surface has been measured.
                // `onSizeChanged` runs in this node's placement, which is before its own layer block
                // is evaluated, so `requireOffset` below is safe from the very first frame.
                .onSizeChanged { size ->
                    val height = size.height.toFloat()
                    surfacePx = height
                    npState.updateAnchors(
                        DraggableAnchors {
                            NpAnchor.Full at 0f
                            NpAnchor.Mini at height - miniBarPx
                        },
                    )
                }.graphicsLayer { translationY = npState.requireOffset() }
                .then(if (expanded) Modifier.nestedScroll(nestedScroll) else Modifier)
                .anchoredDraggable(
                    state = npState,
                    orientation = Orientation.Vertical,
                    flingBehavior = fling,
                ),
        ) {
            if (showQueue) {
                QueueSheet(container, onClose = onCloseQueue)
            } else {
                NowPlayingSheet(
                    container,
                    onQueue = onOpenQueue,
                    onOpenArtist = onOpenArtist,
                    onEnsureService = onEnsureService,
                    // The face is laid out behind the collapsed bar even while it is shut, so the
                    // artwork's swipe-to-skip is only live once the player is actually open.
                    swipeEnabled = expanded,
                )
            }
            MiniBar(
                container = container,
                expanded = expanded,
                onExpand = onExpand,
                onEnsureService = onEnsureService,
                modifier =
                    Modifier.graphicsLayer {
                        val off = npState.offset
                        // Inverse of the scrim's travel: 1 collapsed so the bar is solid, 0 open.
                        // The bar is the surface's *top* strip, so left alone it would slide up and
                        // sit across the status bar instead of leaving. Before the surface has
                        // anchors there is no travel to report, and the state to show is the one it
                        // snaps into: collapsed, bar solid.
                        val shown =
                            if (off.isNaN()) {
                                1f
                            } else {
                                1f - (surfacePx - off - miniBarPx) / (surfacePx - miniBarPx).coerceAtLeast(1f)
                            }
                        alpha = shown.coerceIn(0f, 1f)
                    },
            )
        }
    }
}

/**
 * Snap the surface onto whichever anchor it is already closest to.
 *
 * `AnchoredDraggableState.settle(velocity)` is not available here: in Compose Foundation 1.11 it
 * throws unless the state was built with positional and velocity thresholds, and `Modifier
 * .anchoredDraggable` does not accept them. The velocity is dropped deliberately — the inner
 * scroller has already consumed it, so only the resting place matters here.
 */
private suspend fun <T : Any> AnchoredDraggableState<T>.settleToNearestAnchor(spec: AnimationSpec<Float>) {
    val offset = offset
    if (offset.isNaN()) return
    val nearest = anchors.closestAnchor(offset) ?: return
    if (anchors.positionOf(nearest) == offset) return
    animateTo(nearest, spec)
}

/**
 * The collapsed face: the surface's own top strip. It is never a separate view, so a drag that
 * starts here is the same drag that continues into the full-screen player.
 */
@Composable
private fun MiniBar(
    container: AppContainer,
    expanded: Boolean,
    onExpand: () -> Unit,
    onEnsureService: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by container.orchestrator.state.collectAsStateWithLifecycle()
    val t = LocalDylanTokens.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(MINI_BAR_HEIGHT)
                .background(t.divider)
                .padding(top = 1.dp)
                .background(t.surface)
                .clickable(enabled = !expanded, onClick = onExpand)
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .height(MINI_BAR_CONTENT)
                .background(t.divider)
                .padding(1.dp)
                .background(t.background),
        ) {
            AsyncImage(
                model = state.current?.artUrl150,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
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
            MiniProgress(container, (state.current?.durationS ?: 0L) * 1000L, t.textPrimary)
        }
        PlayPauseIcon(container, size = 32, playing = state.phase is Phase.Playing, onEnsureService = onEnsureService)
    }
}

/** Vertical centre of the 1 dp track: the scale is horizontal, so it must grow from the left. */
private const val BAR_CENTRE = 0.5f

private val LeftOrigin = TransformOrigin(0f, BAR_CENTRE)

/**
 * The 10 Hz position never recomposes anything above it: the read is deferred into the layer block
 * (a draw-phase read) and the track is a full-width bar scaled horizontally, so no measure or
 * layout pass runs per tick.
 */
@Composable
private fun MiniProgress(
    container: AppContainer,
    durationMs: Long,
    color: androidx.compose.ui.graphics.Color,
) {
    val pos = container.orchestrator.positionMs.collectAsStateWithLifecycle(0L)
    val dur = durationMs.coerceAtLeast(1L)
    Box(
        Modifier
            .padding(top = 4.dp)
            .height(1.dp)
            .fillMaxWidth()
            .graphicsLayer {
                transformOrigin = LeftOrigin
                scaleX = (pos.value.toFloat() / dur).coerceIn(0f, 1f)
            }.background(color),
    )
}
