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
     *
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
     *
     * ### What is routed and what is rethrown
     *
     * The `catch` below is `Exception`, **not** `Throwable`, and that is the same line
     * [dylan.playback.Orchestrator.guard] draws on the state lane: an `Error` — `OutOfMemoryError`,
     * `StackOverflowError` — is not a handler bug to be logged and shrugged off. Here it is
     * *more* clearly wrong than it is on the state lane, for two reasons.
     *
     * First, the diagnosis is destroyed. The default `onError` writes `"collect failed: …"` to the
     * trail, so an OOM during delivery was recorded as a routine collect failure — a line that says
     * nothing about memory, nothing about the main lane, and nothing about the fact that the
     * subscription is now dead. An OOM with no honest record of itself is the failure mode this
     * codebase's log trail exists to prevent.
     *
     * Second, and worse: this is the boundary to a `@MainActor` Swift store, so an `Exception` is
     * genuinely worth catching (letting one out tears the subscription down with no trace on the
     * Swift side) while an `Error` must not be — once the heap is gone, the correct response is to
     * unwind and let the platform report it, not to keep a Kotlin coroutine alive to format a log
     * line it may not be able to allocate. Routing it is not "safe"; it is a lie about severity.
     *
     * So [onError] keeps the `Throwable` parameter — a caller may legitimately want to observe an
     * `Error` — but nothing in this class *sends* one there. Rethrowing ends the subscription
     * exactly as [dylan.playback.Orchestrator] ends its inbox loop, and hands the `Error` to the
     * scope's own `CoroutineExceptionHandler`, which is the one place already set up to decide
     * what a fatal error means for the app. Upstream flows are graph-produced `StateFlow`s whose
     * failure types this bridge cannot enumerate, which is why the `Exception` catch stays broad
     * rather than being narrowed to a list.
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
                        // One coroutine-context element lookup, per delivery. The comment that used
                        // to sit here said "compiled out in release". That was true of the
                        // `kotlin.assert` it replaced and is FALSE of the check now in place:
                        // `assertInContext` throws `LaneViolation` unconditionally, in every
                        // build, on every target. It matters in both directions — this must not be
                        // deleted on the strength of a stale comment, and if the hop ever does go
                        // wrong it fails loudly on device instead of quietly rendering SwiftUI
                        // state off the main actor.
                        lanes.disp.assertInContext(Lane.MAIN)
                        onEach(v)
                    }
                    onComplete()
                } catch (c: CancellationException) {
                    throw c
                } catch (swallow: Exception) {
                    // Deliberately `Exception`, not `Throwable` — see the KDoc. `catchAll` here
                    // used to catch an `OutOfMemoryError` and log it as "collect failed", which
                    // both destroyed the diagnosis and kept a dead subscription alive on a heap
                    // that had already failed. The name matches detekt's
                    // `TooGenericExceptionCaught` allowlist, and the `throw c` above is why this
                    // clause must stay below the `CancellationException` one.
                    //
                    // Broad on purpose: the upstream flows are graph-produced StateFlows whose
                    // failure types this bridge cannot enumerate, and an unexpected `Exception`
                    // here still tears the subscription down with no trace on the Swift side. So it
                    // is *routed, not swallowed*: the caller's `onError` owns the decision and the
                    // default writes it to the trail.
                    onError(swallow)
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
