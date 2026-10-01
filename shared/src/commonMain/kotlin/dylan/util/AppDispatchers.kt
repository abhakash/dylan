package dylan.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.experimental.ExperimentalNativeApi

/** The four lanes of `docs/architecture.md`. [DB] is the single-threaded SQLDelight lane. */
enum class Lane { MAIN, IO, DB, STATE }

/**
 * A lane-confined operation ran on the wrong lane. Thrown, not logged-and-continued: lane
 * confinement is what makes the shared mutable state in this app safe (the state lane owns the
 * queue, the DB lane owns the driver), so a violation is a correctness bug that must stop the
 * line that caused it rather than corrupt something further downstream.
 */
class LaneViolation(
    message: String,
) : IllegalStateException(message)

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
 * The element [AppDispatchers.on] folds in beside [LaneTag]: it republishes the lane into [slot] on
 * every dispatch, which is what makes [AppDispatchers.assert] and [AppDispatchers.current]
 * answerable from ordinary non-suspending code.
 *
 * This is an `expect` because common code has no portable hook for "a coroutine is resuming on this
 * thread". `kotlinx.coroutines.ThreadContextElement` is declared in coroutines' **JVM** source set —
 * "ThreadContextElement for common" (kotlinx-coroutines#4208) is still an open PR — so common code
 * that names it compiles for `jvm()`/`androidTarget()` and fails for the iOS targets. The jvm and
 * android actuals implement it over the real thread-local; the iOS actual is an element that
 * publishes nothing, because there is nothing it *could* publish into: Kotlin/Native's
 * `kotlin.native.concurrent.ThreadLocal` gives a per-thread value but nothing calls `set` on it
 * without that hook.
 *
 * The consequence is stated in [AppDispatchers.assert]: the thread-scoped view answers "no lane" on
 * iOS, which is why production code asserts with [AppDispatchers.assertInContext].
 */
internal expect fun lanePublication(
    lane: Lane,
    slot: LaneThreadLocal,
): CoroutineContext.Element

/**
 * Four lanes, reachable as `disp.main` / `disp.io` / `disp.dbLane` / `disp.state`.
 *
 * Those four properties are the **underlying dispatchers, unwrapped**. That is deliberate.
 * An earlier version wrapped each one in a `CoroutineDispatcher` subclass that published the
 * lane on every dispatch, which broke two things:
 *
 *  * `delay()` resolves its scheduler by casting the coroutine's `ContinuationInterceptor` to
 *    `Delay`. `Delay` cannot be implemented outside kotlinx.coroutines (its one abstract member,
 *    `scheduleResumeAfterDelay`, is `@InternalCoroutinesApi`), so a wrapper that is not a `Delay`
 *    silently falls back to the global `DefaultDelay` — a real background timer. Every lane
 *    `delay()` became wall-clock, which made virtual time impossible and forced the test suite to
 *    sleep through its own timeouts, and it cost an extra dispatch hop per delay in production.
 *  * a wrapper is invisible to `TestDispatcher`, so a test could not supply its own scheduler.
 *
 * Instead, the lane rides in the coroutine *context* via [LaneTag] (coroutine-scoped) and
 * [lanePublication] (thread-scoped), both installed by [on]. That is the mechanism coroutines
 * provides for exactly this purpose, and it composes with any dispatcher — a
 * `limitedParallelism(1)` view, `Dispatchers.IO`, or a `TestDispatcher`, which the wrapper
 * swallowed.
 *
 * **The contract.**
 *
 *  * [on] is required at every *entry point* into lane work that asserts, and nowhere else. A bare
 *    `scope.launch(disp.io)` schedules on the io dispatcher and publishes no lane, so
 *    [assertInContext] there honestly reports "no lane". A component whose own `CoroutineScope`
 *    carries `disp.on(Lane.X)` — see `DownloadEngine` — satisfies the contract for every coroutine
 *    it starts, by construction and at no per-launch cost.
 *  * A bare `launch(disp.x)` / `withContext(disp.x)` is the right thing for plain scheduling: work
 *    that never asserts. The 10 Hz position collector is the case that matters — it is entered from
 *    the state lane, does no IO and asserts nothing, so a publication there would cost a save and a
 *    restore on every tick to answer a question nobody asks.
 *  * Production code asserts with [assertInContext], never with [assert]. [assert] needs the
 *    dispatch hook that only exists on the JVM and Android, and a `limitedParallelism(1)` lane may
 *    be served by more than one pool thread over a coroutine's life, so the coroutine view is both
 *    the portable one and the stronger statement of the two.
 *  * One `on` per lane is the shape, and nesting lanes is fine: a hop into a *different* lane is
 *    the only way that lane gets published at all, and one extra save/restore is noise beside the
 *    blocking SQLite or file work inside it. Nesting two publications for the *same* lane buys
 *    nothing and costs two.
 */
class AppDispatchers(
    main: CoroutineDispatcher,
    io: CoroutineDispatcher,
    dbLane: CoroutineDispatcher,
    state: CoroutineDispatcher,
) {
    private val slot = LaneThreadLocal()

    val main: CoroutineDispatcher = main
    val io: CoroutineDispatcher = io
    val dbLane: CoroutineDispatcher = dbLane
    val state: CoroutineDispatcher = state

    /**
     * Context for entering [lane]: the dispatcher, the coroutine-scoped [LaneTag] that
     * [assertInContext] reads, and the thread-scoped publication that [assert] and [current] read.
     * Use with `launch(disp.on(Lane.STATE))` or `withContext(disp.on(Lane.IO))`.
     */
    fun on(lane: Lane): CoroutineContext = dispatcherOf(lane) + LaneTag(lane) + lanePublication(lane, slot)

    /** Dispatch onto [lane] and run [block] there with the lane published. */
    suspend fun <T> on(
        lane: Lane,
        block: suspend CoroutineScope.() -> T,
    ): T = withContext(on(lane)) { block() }

    /** Lane the calling thread is currently running lane work on; null off-lane. Debug only. */
    fun current(): Lane? = slot.get()

    /** Lane of the calling *coroutine* — survives suspension and thread hops. Debug only. */
    suspend fun currentLane(): Lane? = coroutineContext[LaneTag]?.lane

    /**
     * Coroutine-scoped [assert], and the one production code uses: correct after a suspension moved
     * the work to another thread, and available on every target. Claims a lane only when the
     * coroutine entered through [on]; otherwise it reports no lane, which is the honest answer for
     * code that was scheduled without one.
     */
    @OptIn(ExperimentalNativeApi::class)
    suspend fun assertInContext(lane: Lane) {
        val actual = coroutineContext[LaneTag]?.lane
        if (actual != lane) throw LaneViolation("expected $lane, on ${actual ?: "no lane"}")
    }

    /**
     * Thread-scoped check, for non-suspending code running inside an [on] block. One thread-local
     * read.
     *
     * This used to use `kotlin.assert`, which was wrong twice over: it is JVM-only, so it did not
     * even resolve in commonMain metadata (the iOS klib compile failed on it), and it compiles out
     * entirely when assertions are disabled — meaning the invariant this class exists to enforce was
     * silently absent from every release build. Hence [LaneViolation].
     *
     * Answers "no lane" on iOS, where [lanePublication] has no hook to publish with, and it is
     * weaker than [assertInContext] everywhere, because a `limitedParallelism(1)` lane can be
     * served by more than one pool thread over a coroutine's life. Diagnostics and test
     * scaffolding, not an invariant.
     */
    @OptIn(ExperimentalNativeApi::class)
    fun assert(lane: Lane) {
        val actual = slot.get()
        if (actual != lane) throw LaneViolation("expected $lane, on ${actual ?: "no lane"}")
    }

    private fun dispatcherOf(lane: Lane): CoroutineDispatcher =
        when (lane) {
            Lane.MAIN -> main
            Lane.IO -> io
            Lane.DB -> dbLane
            Lane.STATE -> state
        }
}
