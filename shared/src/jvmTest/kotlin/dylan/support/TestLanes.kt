package dylan.support

import dylan.util.AppDispatchers
import dylan.util.Lane
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Rendezvous width. Two is the minimum that separates "one task at a time" from "more than
 * one", and it is the only width meaningful on a serialized lane at all: a `CyclicBarrier(n)`
 * trips only if n tasks are inside the lane *simultaneously*, so on a single-permit lane every
 * barrier necessarily times out.
 */
const val RENDEZVOUS: Int = 2

/**
 * Ceiling the "must be single-permit" assertions pay, once each: the first task parks in the
 * lane body until this elapses, and every task queued behind it then short-circuits.
 */
const val RENDEZVOUS_TIMEOUT_MS: Long = 250L

/** Generous, because a multi-permit lane must reach the barrier in a single scheduling quantum. */
const val PARALLEL_RENDEZVOUS_TIMEOUT_MS: Long = 2_000L

/**
 * Test lanes, in two profiles. Both are single-permit per lane, because single-permit is the
 * production contract; they differ in *whose clock* the lanes run on.
 *
 * **Production builds `state` and `dbLane` as `Dispatchers.Default.limitedParallelism(1, …)`:**
 * at most one task in flight per lane, with the two lanes independent of each other. The pre-wave
 * harness passed `Dispatchers.Default` for all four, so the serialization the whole architecture
 * rests on was not merely untested — the suite was a strictly *more permissive* system than the
 * app, which is why missing-hop bugs were masked by scheduling luck and only surfaced on device.
 *
 * ## Why a `StandardTestDispatcher` lane is viable at all
 *
 * `AppDispatchers` used to hand back a `CoroutineDispatcher` subclass that published the lane on
 * every dispatch. `delay()` finds its scheduler by casting the coroutine's `ContinuationInterceptor`
 * to `kotlinx.coroutines.Delay`, and that interface cannot be implemented outside kotlinx.coroutines
 * (`scheduleResumeAfterDelay` is `@InternalCoroutinesApi`), so the wrapper silently forced every
 * lane onto the global real-time `DefaultDelay` and the suite had to sleep through its own
 * timeouts. `AppDispatchers` now stores the *raw* dispatchers and carries the lane in the
 * coroutine context, so nothing stands between a lane and a `TestDispatcher`.
 *
 * The non-obvious part is that `limitedParallelism(1)` does not break this. `LimitedDispatcher`
 * *is* a `Delay` and its constructor is
 * `dispatcher as? Delay ?: DefaultDelay` — so
 * `StandardTestDispatcher(scheduler).limitedParallelism(1, "state")` keeps the single-permit
 * contract **and** routes `delay()` into the shared scheduler. (Against `Dispatchers.Default` the
 * same call gets `DefaultDelay`, which is why the [production] profile is genuinely wall-clock and
 * `TestLanesContractTest` measures it that way.)
 *
 * ## The trade-off this profile accepts
 *
 * All four lanes of a [virtual] profile share one `TestCoroutineScheduler`, and that scheduler has
 * exactly one driver thread. Serialization is therefore *stricter* than production — two lanes can
 * never be occupied at the same instant — and real filesystem work serializes behind the test
 * thread instead of overlapping on `Dispatchers.IO`.
 *
 * That is acceptable for a test that is about behaviour under time (does the retry ladder stop
 * after `dlRetries`? does the ready timeout land in `Phase.Error`?), and it is **not** acceptable
 * for a test that is about cross-lane concurrency. So the overlap contract — one task per lane, two
 * lanes occupiable together, `io` genuinely multi-permit — is asserted in `TestLanesContractTest`
 * against the [production] profile, with a `Dispatchers.Default` control that proves the detector
 * can see red. Use [production] there, [virtual] everywhere else.
 */
class TestLanes(
    val state: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1, "state"),
    val dbLane: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1, "dbLane"),
    val main: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1, "main"),
    val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val disp: AppDispatchers = AppDispatchers(main, io, dbLane, state)

    /** A scope whose coroutines all land on [lane] — the shape production `scope.launch` uses. */
    fun laneScope(lane: Lane): CoroutineScope = CoroutineScope(disp.on(lane) + SupervisorJob())

    companion object {
        /**
         * The wall-clock profile: production's own `limitedParallelism(1)` lanes over
         * `Dispatchers.Default`. `delay()` inside one of these is a real timer, so a test that
         * waits on lane work must wait on the wall clock. This is what `TestLanesContractTest`
         * audits, and the default everywhere a test is about scheduling.
         */
        fun production(): TestLanes = TestLanes()

        /**
         * The virtual-time profile: the same four single-permit lanes, but every one of them a
         * `StandardTestDispatcher` on [scheduler]. Must be driven from inside a `runTest` (or an
         * equivalent) that shares [scheduler], or nothing on a lane will ever run.
         */
        fun virtual(scheduler: TestCoroutineScheduler): TestLanes =
            TestLanes(
                state = StandardTestDispatcher(scheduler, "state").limitedParallelism(1, "state"),
                dbLane = StandardTestDispatcher(scheduler, "dbLane").limitedParallelism(1, "dbLane"),
                main = StandardTestDispatcher(scheduler, "main").limitedParallelism(1, "main"),
                io = StandardTestDispatcher(scheduler, "io").limitedParallelism(1, "io"),
            )
    }
}

/**
 * Rendezvous [n] tasks inside one lane body and report whether all [n] were inside
 * *simultaneously*.
 *
 * `true` ⇒ the lane is multi-permit — the regression this suite exists to catch, since a graph
 * built with `Dispatchers.Default` for `state`/`dbLane` returns `true` here.
 * `false` ⇒ the lane is single-permit, i.e. `limitedParallelism(1)`-equivalent.
 */
suspend fun laneAdmitsOverlap(
    disp: AppDispatchers,
    lane: Lane,
    n: Int = RENDEZVOUS,
    timeoutMs: Long = RENDEZVOUS_TIMEOUT_MS,
): Boolean = lanesOverlap(disp, listOf(lane to n), timeoutMs)

/**
 * Rendezvous across [counts] worth of tasks spread over [lanes], all blocked on one barrier.
 *
 * With one task per lane this answers a sharper question than [laneAdmitsOverlap]: can two
 * *different* lanes be occupied at the same time? Production says yes — they are two independent
 * `limitedParallelism(1)` pools — and collapsing them onto one dispatcher would silently remove
 * every cross-lane interleaving the app actually has.
 */
suspend fun lanesOverlap(
    disp: AppDispatchers,
    counts: List<Pair<Lane, Int>>,
    timeoutMs: Long = PARALLEL_RENDEZVOUS_TIMEOUT_MS,
): Boolean {
    val total = counts.sumOf { it.second }
    if (total == 0) return false
    val barrier = CyclicBarrier(total)
    val gaveUp = AtomicBoolean(false)
    val scope = CoroutineScope(SupervisorJob())
    try {
        val results =
            counts.flatMap { (lane, n) ->
                val d = disp.on(lane)
                List(n) {
                    scope.async(d) {
                        if (gaveUp.get()) return@async false
                        runCatching { barrier.await(timeoutMs, TimeUnit.MILLISECONDS) }
                            .onFailure { gaveUp.set(true) }
                            .isSuccess
                    }
                }
            }
        return results.awaitAll().all { it }
    } finally {
        scope.cancel()
    }
}

/**
 * Max simultaneous entries into a lane body across [RENDEZVOUS] contending tasks. `1` is the
 * production contract; anything above it is a concurrency defect in the graph, not in the harness.
 */
suspend fun peakInLaneConcurrency(
    disp: AppDispatchers,
    lane: Lane,
    n: Int = RENDEZVOUS,
    holdMs: Long = RENDEZVOUS_TIMEOUT_MS,
): Int {
    val current = AtomicInteger(0)
    val high = AtomicInteger(0)
    val barrier = CyclicBarrier(n)
    val scope = CoroutineScope(disp.on(lane) + SupervisorJob())
    val jobs =
        List(n) {
            scope.async {
                val now = current.incrementAndGet()
                high.updateAndGet { prev -> maxOf(prev, now) }
                runCatching { barrier.await(holdMs, TimeUnit.MILLISECONDS) }
                current.decrementAndGet()
            }
        }
    jobs.awaitAll()
    scope.cancel()
    return high.get()
}

/** Blocking convenience wrapper so a plain (non-suspend) test can still assert the contract. */
fun laneAdmitsOverlapBlocking(
    disp: AppDispatchers,
    lane: Lane,
    n: Int = RENDEZVOUS,
    timeoutMs: Long = RENDEZVOUS_TIMEOUT_MS,
): Boolean = runBlocking { laneAdmitsOverlap(disp, lane, n, timeoutMs) }
