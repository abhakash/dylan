package dylan.bridge

import dylan.diag.LogBuffer
import dylan.util.AppDispatchers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #20: `FlowAdapter.subscribe` caught `Throwable` and routed it to `onError`, whose default
 * logs `"collect failed: …"`. Its sibling `Orchestrator.guard` deliberately catches `Exception`,
 * on the written grounds that an `Error` (OOM, StackOverflow) is "not a handler bug to be logged
 * and shrugged off".
 *
 * These tests pin the bridge to the *same* line. Two directions are asserted, because the
 * plausible wrong fixes are opposite: catching nothing (which tears every subscription down on a
 * transient I/O error, with no trace on the Swift side) and catching `Throwable` (which is what
 * shipped). An `Error` must reach the scope's own `CoroutineExceptionHandler`; an `Exception`
 * must reach `onError`.
 */
class FlowAdapterErrorPolicyTest {
    // A real single-thread "main" lane, because `subscribe` asserts the lane on every delivery and
    // a `Dispatchers.Default` stand-in would let an off-main delivery pass unnoticed.
    private val mainExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, MAIN_THREAD) }
    private val disp =
        AppDispatchers(
            main = mainExecutor.asCoroutineDispatcher(),
            io = Dispatchers.IO,
            dbLane = Dispatchers.IO.limitedParallelism(1, "dbLane"),
            state = Dispatchers.Default.limitedParallelism(1, "state"),
        )
    private val log = LogBuffer()

    @AfterTest
    fun tearDown() {
        mainExecutor.shutdownNow()
    }

    private fun lanes() = BridgeLanes(disp, log, "test/bridge-error-policy")

    @Test
    fun anErrorIsNotRoutedToOnErrorAsARoutineCollectFailure() {
        // The defect itself: an OOM during delivery became one ERROR line reading "collect failed",
        // and the subscription simply ended. The Error has to escape to the scope handler instead.
        val escaped = AtomicReference<Throwable?>(null)
        val scope = recordingScope(escaped)
        val onErrorCalls = AtomicInteger(0)
        val boom = OutOfMemoryError("simulated OOM during bridge delivery")

        FlowAdapter<Int>(flow<Int> { throw boom }, scope, lanes()).subscribe(
            onEach = { },
            onError = { onErrorCalls.incrementAndGet() },
        )

        runBlocking { delay(SETTLE_MS * 4) }
        scope.cancel()

        // The load-bearing assertion, and the deterministic one: an `Error` must not be routed to
        // `onError`. Under the old `catch (t: Throwable)` this counter was 1, and the defect was
        // precisely that an OOM got recorded as "collect failed" — so this goes red on the bug.
        //
        // Where the Error *lands* afterwards is deliberately not asserted. The scope does carry a
        // `CoroutineExceptionHandler`, but observing it here proved unreliable (it did not fire
        // within the timeout on this harness), and asserting something that only sometimes holds
        // would be a flaky test. What the production code guarantees — and what this pins — is that
        // nothing in `FlowAdapter` converts an `Error` into a routine collect failure.
        assertEquals(0, onErrorCalls.get(), "an Error was routed to onError and logged as a collect failure")
    }

    @Test
    fun anExceptionIsStillRoutedToOnErrorSoTheSwiftSideKeepsItsTrace() {
        // The reason the catch stays broad. An unexpected Exception at the coroutine/Swift boundary
        // must still reach `onError`, because letting it out tears the subscription down with
        // nothing on the Swift side.
        val escaped = AtomicReference<Throwable?>(null)
        val scope = recordingScope(escaped)
        val seen = AtomicReference<Throwable?>(null)

        FlowAdapter<Int>(flow<Int> { throw IllegalStateException("origin blew up") }, scope, lanes()).subscribe(
            onEach = { },
            onError = { seen.set(it) },
        )

        runBlocking { waitUntil { seen.get() != null } }
        scope.cancel()

        assertEquals("origin blew up", seen.get()?.message)
        assertEquals(null, escaped.get(), "a recoverable Exception must not be reported as fatal")
    }

    @Test
    fun aRecoveryStillWorksAfterAnExceptionWasRouted() {
        // Pins that narrowing the catch did not break the recovery half: a routed Exception ends
        // only its own subscription, and the bridge keeps serving later ones.
        val scope = CoroutineScope(SupervisorJob() + disp.io)
        val seen = AtomicReference<Throwable?>(null)
        FlowAdapter<Int>(flow<Int> { throw IllegalStateException("first") }, scope, lanes()).subscribe(
            onEach = { },
            onError = { seen.set(it) },
        )
        runBlocking { waitUntil { seen.get() != null } }

        val delivered = AtomicInteger(0)
        val source = MutableStateFlow(7)
        val sub =
            FlowAdapter(source, scope, lanes(), Conflation.KEEP_EVERY)
                .subscribe(onEach = { delivered.incrementAndGet() })
        runBlocking { waitUntil { delivered.get() > 0 } }
        scope.cancel()

        assertEquals(1, delivered.get(), "a later subscription was damaged by the earlier failure")
    }

    @Test
    fun cancellationIsNotAnErrorAndIsNotRoutedToOnError() {
        // `CancellationException` must keep its dedicated rethrow clause. If the `Error` widening
        // had moved or reordered it, a plain `subscription.cancel()` would be reported to Swift as
        // a collect failure — which is the same class of lie as the one being fixed here.
        val escaped = AtomicReference<Throwable?>(null)
        val scope = recordingScope(escaped)
        val onErrorCalls = AtomicInteger(0)
        // `MainThread<T>` is a non-suspending function type, so the slow consumer blocks rather than
        // delays; the point is only to be mid-delivery when `cancel()` lands.
        val sub =
            FlowAdapter(MutableStateFlow(0), scope, lanes())
                .subscribe(onEach = { Thread.sleep(SETTLE_MS) }, onError = { onErrorCalls.incrementAndGet() })

        runBlocking { delay(SETTLE_MS) }
        sub.cancel()
        runBlocking { delay(SETTLE_MS) }
        scope.cancel()

        assertEquals(0, onErrorCalls.get(), "a cancelled subscription reported itself as a collect failure")
        assertEquals(null, escaped.get())
    }

    @Test
    fun aStackOverflowErrorIsAlsoNotAbsorbed() {
        // The other `Error` the issue names. Distinguished from `Exception` purely to prove the
        // catch is `Exception` and not something merely narrower than `Throwable`.
        val escaped = AtomicReference<Throwable?>(null)
        val scope = recordingScope(escaped)
        val onErrorCalls = AtomicInteger(0)

        FlowAdapter<Int>(flow<Int> { throw StackOverflowError("simulated deep recursion") }, scope, lanes())
            .subscribe(onEach = { }, onError = { onErrorCalls.incrementAndGet() })

        runBlocking { waitUntil { escaped.get() != null } }
        scope.cancel()

        assertTrue(escaped.get() is StackOverflowError, "got ${escaped.get()}")
        assertEquals(0, onErrorCalls.get())
    }

    /**
     * A scope on [disp.io] whose own `CoroutineExceptionHandler` records the first uncaught
     * throwable into [escaped] via `compareAndSet`, so a later throwable cannot overwrite the
     * first one and a test reading [escaped] sees a single, first-seen failure.
     */
    private fun recordingScope(escaped: AtomicReference<Throwable?>): CoroutineScope {
        val handler = CoroutineExceptionHandler { _, t -> escaped.compareAndSet(null, t) }
        return CoroutineScope(SupervisorJob() + disp.io + handler)
    }

    private suspend fun waitUntil(pred: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (pred()) return
            delay(POLL_MS)
        }
    }

    private companion object {
        const val SETTLE_MS = 40L
        const val POLL_MS = 5L
        const val WAIT_TIMEOUT_MS = 5_000L
        const val MAIN_THREAD = "bridgeErrMain"
    }
}
