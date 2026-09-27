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
 * Test lanes with the *production* contract.
 *
 * Production builds `state` and `dbLane` as `Dispatchers.Default.limitedParallelism(1, …)`: at
 * most one task in flight per lane, with the two lanes independent of each other. The pre-wave
 * harness passed `Dispatchers.Default` for all four, so the serialization the whole architecture
 * rests on was not merely untested — the suite was a strictly *more permissive* system than the
 * app, which is why missing-hop bugs were masked by scheduling luck and only surfaced on device.
 *
 * These lanes are the production ones. That is the whole point: there is no longer a "test lane
 * configuration" to drift away from production, and [laneAdmitsOverlap] (asserted in
 * `TestLanesContractTest`, together with a `Dispatchers.Default` control) fails the moment a graph
 * is built with a multi-permit lane.
 *
 * [io] stays a real multi-permit dispatcher because `withContext(disp.io)` exists precisely to
 * overlap filesystem/network work; single-permitting it would remove the parallelism under test.
 *
 * **Why not a `StandardTestDispatcher`?** Two reasons, both established by
 * `TestLanesContractTest.laneDelayIsRealTimeWhileOnLaneIsVirtual`:
 *
 *  1. `AppDispatchers.LaneDispatcher` deliberately does not implement `Delay`, so `delay()`
 *     reached through `disp.state` (or `disp.on(Lane.STATE)`, which returns the same wrapper) is
 *     scheduled by the global `DefaultDelay`. Virtual time is therefore **unreachable through
 *     `AppDispatchers` in any profile** — production or test — and a virtual-lane harness would
 *     buy no determinism the real lanes do not already give.
 *  2. A `StandardTestDispatcher` only runs when something drives the `TestCoroutineScheduler`
 *     (in a test, `runTest`'s work runner), and all dispatchers sharing one scheduler execute on
 *     that single driver thread. Two such "lanes" can never be in flight together, which is
 *     *stricter* than production's two independent `limitedParallelism(1)` lanes and would
 *     misreport a cross-lane interleaving as a hang.
 *
 * Virtual time is still fully available where it belongs: to *test-owned* dispatchers such as
 * [FakePlayerEngine]'s event/position loop, which is where the 10 Hz position poll and the 30 s
 * "actually listened" heuristic live.
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
