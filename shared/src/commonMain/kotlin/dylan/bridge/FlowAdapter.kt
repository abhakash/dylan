package dylan.bridge

import dylan.diag.LogBuffer
import dylan.model.PlayerState
import dylan.util.AppDispatchers
import dylan.util.Lane
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * A bridge callback, invoked on the **main lane**.
 *
 * The whole Swift store layer (`Stores.swift`, `NowPlayingController.swift`) mutates
 * `@MainActor`/`@Observable`/`@State` from these closures, and it does so from callbacks with no
 * isolation marker of their own. The hop is therefore part of the signature rather than a
 * consequence of which dispatcher [FlowAdapter] happened to hardcode, and
 * [FlowAdapter.subscribe] asserts the lane at every delivery. A later `flowOn`, or dropping
 * `.immediate`, can no longer silently turn main-actor writes into off-main writes.
 */
typealias MainThread<T> = (T) -> Unit

/**
 * Whether a subscription may skip intermediate values. Not a `Boolean`: `conflate = true` at a
 * call site says nothing about *which* stream, and the two streams here want opposite answers.
 */
enum class Conflation {
    /** Deliver every emission, in order. For a [kotlinx.coroutines.flow.StateFlow] of an
     *  `equals`-comparable value this is already deduplicated upstream — [PlayerState] is a data
     *  class and the state lane's `MutableStateFlow` conflates by equality — so a
     *  `conflate()` here would only add a buffer and a dispatch per emission. */
    KEEP_EVERY,

    /** Collapse a burst to its newest value. For high-rate flows whose intermediate values are
     *  worthless by the time they are delivered ([PositionAdapter]'s 10 Hz ticker). */
    LATEST_ONLY,
}

/**
 * The graph's view for the Swift bridge, and nothing else: which lane a callback lands on, and
 * which trail a failure is written to.
 *
 * Before this existed the adapter hardcoded `Dispatchers.Main.immediate` and `Dispatchers.Default`
 * and reported failures to stderr — so on iOS *nothing* the bridge failed on ever reached the
 * log file, which is the whole point of the trail.
 */
class BridgeLanes(
    val disp: AppDispatchers,
    val log: LogBuffer,
    val tag: String = "bridge",
)

class KotlinSubscription internal constructor(
    private val job: Job,
) {
    fun cancel() {
        job.cancel()
    }

    val isActive: Boolean get() = job.isActive
}

class FlowAdapter<T : Any>(
    private val flow: Flow<T>,
    private val scope: CoroutineScope,
    private val lanes: BridgeLanes,
    private val conflate: Conflation = Conflation.LATEST_ONLY,
) {
    /**
     * Collects [flow] and delivers every value to [onEach] on the main lane.
     *
     * There is deliberately no `flowOn`. Operators upstream of `collect` run in the *collector's*
     * context, so `flowOn(Dispatchers.Default)` put a second dispatch and a second thread hop in
     * front of every emission, onto a pool that on iOS is the same one backing `state`, `dbLane`
     * and `io` — one pool, charged per SwiftUI re-subscription, for no decoupling: the bridge's
     * upstream flows are all `StateFlow`s, which are already conflated and never lane-affine.
     * Removing it halves the dispatches per emission and takes the load off the shared pool.
     *
     * The cost of dropping it is that [flow] must be hot: a cold flow would run its producer on
     * the main lane. Every flow wired to this adapter is a `StateFlow`.
     */
    fun subscribe(
        onEach: MainThread<T>,
        onError: (Throwable) -> Unit = { t -> lanes.log.e(lanes.tag, "collect failed: ${t.message}") },
        onComplete: () -> Unit = {},
    ): KotlinSubscription =
        KotlinSubscription(
            scope.launch(lanes.disp.on(Lane.MAIN)) {
                try {
                    val piped = if (conflate == Conflation.LATEST_ONLY) flow.conflate() else flow
                    piped.collect { v ->
                        // One coroutine-context element lookup, compiled out in release: the
                        // invariant the whole iOS store layer rests on, checked on every delivery
                        // rather than trusted.
                        lanes.disp.assertInContext(Lane.MAIN)
                        onEach(v)
                    }
                    onComplete()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    onError(t)
                }
            },
        )
}

class PlayerStateAdapter(
    scope: CoroutineScope,
    flow: Flow<PlayerState>,
    lanes: BridgeLanes,
) {
    private val inner = FlowAdapter(flow, scope, lanes, Conflation.KEEP_EVERY)

    fun subscribe(onEach: MainThread<PlayerState>): KotlinSubscription = inner.subscribe(onEach)
}

class PositionAdapter(
    scope: CoroutineScope,
    flow: Flow<Long>,
    lanes: BridgeLanes,
) {
    private val inner = FlowAdapter(flow, scope, lanes, Conflation.LATEST_ONLY)

    fun subscribe(onEach: MainThread<Long>): KotlinSubscription = inner.subscribe(onEach)
}
