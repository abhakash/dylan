package dylan

import dylan.util.WorkOutcome
import dylan.util.launchSettled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `launchSettled`'s contract: [settle] is called once for **every** way the coroutine can end,
 * including a scope that was already cancelled before the body was dispatched.
 *
 * The last case is the one the Android `ArtworkBitmapLoader` hit. It used
 * `scope.launch { runCatching { fetch(uri) }.onSuccess { out.set(it) } … }` and its KDoc claimed
 * that cancelling the engine "cancels every in-flight future with it". Cancelling a scope
 * cancels the *coroutine*; the `SettableFuture` was never completed and never cancelled, so a
 * `loadBitmap` that raced `ExoPlayerEngine.release()` left Media3's `CacheBitmapLoader` holding a
 * future that never settles, and the notification's artwork update is pending for the life of the
 * process. `androidApp` has no test source set, so the guarantee is pinned here — on the same
 * helper the Android file calls — rather than by assertion.
 */
class LaunchSettledTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun teardown() {
        runCatching { scope.cancel() }
    }

    @Test
    fun theValueReachesTheConsumer() =
        runBlocking {
            val seen = CopyOnWriteArrayList<WorkOutcome<String>>()
            scope.launchSettled({ seen += it }) { "artwork" }
            withTimeoutOrNull(AWAIT_MS) { while (seen.isEmpty()) delay(POLL_MS) }
            assertEquals(listOf<WorkOutcome<String>>(WorkOutcome.Value("artwork")), seen.toList())
        }

    @Test
    fun aThrowingBodyIsReportedAsAFailureNotAsSilence() =
        runBlocking {
            val seen = CopyOnWriteArrayList<WorkOutcome<String>>()
            scope.launchSettled({ seen += it }) { throw IllegalStateException("not an image") }
            withTimeoutOrNull(AWAIT_MS) { while (seen.isEmpty()) delay(POLL_MS) }
            val outcome = seen.single()
            assertIs<WorkOutcome.Failure>(outcome)
            assertEquals("not an image", outcome.error.message)
        }

    /**
     * THE case. A scope cancelled *before* `launch` even reaches dispatch: the body never runs, so
     * a `runCatching { … }.onSuccess { settle }` reports nothing at all, and Media3's cached future
     * is never completed or cancelled. `invokeOnCompletion` on the job is the only observer that
     * fires on this path, which is why `launchSettled` registers one.
     */
    @Test
    fun aScopeCancelledBeforeDispatchStillSettlesTheConsumer() =
        runBlocking {
            val dead = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            dead.cancel()
            val seen = CopyOnWriteArrayList<WorkOutcome<String>>()
            var bodyRan = false

            dead.launchSettled({ seen += it }) {
                bodyRan = true
                "artwork"
            }

            // Bounded because the property is "settles", not "settles synchronously": the child of
            // a cancelled parent is completed on the dispatcher's turn, so the handler is not
            // necessarily run before `launchSettled` returns. What must hold is that it *runs* —
            // with the pre-fix `launch`, `seen` stays empty forever.
            val settled =
                withTimeoutOrNull(AWAIT_MS) {
                    while (seen.isEmpty()) delay(POLL_MS)
                    true
                } == true
            assertTrue(settled, "a never-dispatched body must still report; seen=$seen")
            assertEquals(listOf(WorkOutcome.Cancelled), seen.toList())
            assertEquals(false, bodyRan, "precondition: the body really was never dispatched")
        }

    /**
     * A body cancelled *mid-flight* — the ordinary `scope.cancel()` while `fetch` is blocked — takes
     * the same reporting path, and must not be reported as a failure: cancellation is not an error.
     */
    @Test
    fun aBodyCancelledMidFlightReportsCancellation() =
        runBlocking {
            val seen = CopyOnWriteArrayList<WorkOutcome<String>>()
            val started = CompletableDeferred<Unit>()
            val job =
                scope.launchSettled({ seen += it }) {
                    started.complete(Unit)
                    delay(Long.MAX_VALUE)
                    "never"
                }
            withTimeoutOrNull(AWAIT_MS) { started.await() }
            job.cancel(CancellationException("engine released"))

            withTimeoutOrNull(AWAIT_MS) { while (seen.isEmpty()) delay(POLL_MS) }
            assertEquals(listOf(WorkOutcome.Cancelled), seen.toList(), "cancellation is not a failure")
        }

    private companion object {
        const val AWAIT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
