package dylan.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.experimental.ExperimentalNativeApi

/** The four lanes of `docs/architecture.md`. [DB] is the single-threaded SQLDelight lane. */
enum class Lane { MAIN, IO, DB, STATE }

/**
 * Carries the active lane across a `withContext` so a *coroutine* can be asked which lane it
 * is in ([AppDispatchers.assertInContext]). A dispatcher cannot tag an already-created
 * coroutine's context, which is why the thread-scoped [AppDispatchers.assert] exists too.
 */
class LaneTag(
    val lane: Lane,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LaneTag>
}

/** Per-thread current lane. Debug/assert path only — never on the per-message hot path. */
internal expect class LaneThreadLocal() {
    fun get(): Lane?

    fun set(lane: Lane?)
}

/**
 * Four lanes, still reachable as `disp.main` / `disp.io` / `disp.dbLane` / `disp.state`, but
 * each of those is a [LaneDispatcher]: it publishes the lane into a thread-local on every
 * dispatch, so the contract is checkable from ordinary non-suspending code.
 *
 * [on] is the only *sanctioned* way to enter a lane; the raw properties stay so the existing
 * `scope.launch(disp.state)` call sites keep compiling. [assert] is the enforcement and costs
 * one thread-local read plus a static-boolean check; both asserts compile out with the
 * platform's assertion flag, which no release build sets.
 */
class AppDispatchers(
    main: CoroutineDispatcher,
    io: CoroutineDispatcher,
    dbLane: CoroutineDispatcher,
    state: CoroutineDispatcher,
) {
    private val slot = LaneThreadLocal()

    val main: CoroutineDispatcher = LaneDispatcher(Lane.MAIN, main, slot)
    val io: CoroutineDispatcher = LaneDispatcher(Lane.IO, io, slot)
    val dbLane: CoroutineDispatcher = LaneDispatcher(Lane.DB, dbLane, slot)
    val state: CoroutineDispatcher = LaneDispatcher(Lane.STATE, state, slot)

    /** Dispatcher for [lane] with the lane tag attached, for `withContext(disp.on(Lane.IO))`. */
    fun on(lane: Lane): CoroutineContext = dispatcherOf(lane) + LaneTag(lane)

    /** Dispatch onto [lane] and run [block] there with the lane tag in context. */
    suspend fun <T> on(
        lane: Lane,
        block: suspend CoroutineScope.() -> T,
    ): T = withContext(on(lane)) { block() }

    /** Lane the calling thread is currently running lane work on; null off-lane. Debug only. */
    fun current(): Lane? = slot.get()

    /** Lane of the calling *coroutine* — survives suspension and thread hops. Debug only. */
    suspend fun currentLane(): Lane? = coroutineContext[LaneTag]?.lane

    /**
     * Lane-discipline tripwire for lane-confined entry points (`Orchestrator.process`,
     * `CacheManager.enforceBudget`, `DownloadEngine.loop`, `WindowPreparer.sniffOk`).
     * Thread-scoped: one thread-local read, no allocation, and the message is only built when
     * platform assertions are on (never in release).
     */
    @OptIn(ExperimentalNativeApi::class)
    fun assert(lane: Lane) {
        val actual = slot.get()
        kotlin.assert(actual == lane) { "expected $lane, on ${actual ?: "no lane"}" }
    }

    /**
     * Coroutine-scoped [assert]: correct after a suspension moved the work to another thread,
     * but only if the coroutine entered through [on] (or another `LaneTag`-carrying context).
     */
    @OptIn(ExperimentalNativeApi::class)
    suspend fun assertInContext(lane: Lane) {
        val actual = coroutineContext[LaneTag]?.lane
        kotlin.assert(actual == lane) { "expected $lane, on ${actual ?: "no lane"}" }
    }

    private fun dispatcherOf(lane: Lane): CoroutineDispatcher =
        when (lane) {
            Lane.MAIN -> main
            Lane.IO -> io
            Lane.DB -> dbLane
            Lane.STATE -> state
        }
}

private class LaneDispatcher(
    private val lane: Lane,
    private val delegate: CoroutineDispatcher,
    private val slot: LaneThreadLocal,
) : CoroutineDispatcher() {
    // Must delegate: without it every withContext(disp.main) would post even when already on
    // the UI thread, turning Main.immediate into a plain Main.
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = delegate.isDispatchNeeded(context)

    // The wrapper deliberately does NOT implement Delay (its members are @InternalCoroutinesApi),
    // so delay() inside a lane is scheduled by the global DefaultDelay and then dispatches back
    // here — one extra hop per delay, which is why nothing hot may busy-delay on a lane.
    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        delegate.dispatch(context) {
            val prev = slot.get()
            slot.set(lane)
            try {
                block.run()
            } finally {
                slot.set(prev)
            }
        }
    }

    // Lane name (not the delegate's) is what makes a thread dump readable; see the `name =`
    // argument on limitedParallelism at the graph construction sites.
    override fun toString(): String = "dylan.$lane"
}
