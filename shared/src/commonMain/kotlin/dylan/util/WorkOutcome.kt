package dylan.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * How a launched unit of work ended, from the point of view of whoever is *waiting for it*.
 *
 * [Cancelled] is a first-class member because "the work stopped" and "the work produced nothing"
 * are different facts, and a consumer that cannot tell them apart hangs or mis-reports. This file
 * exists for [WorkOutcome] and [launchSettled] together, because the second is meaningless without
 * the first.
 */
sealed interface WorkOutcome<out T> {
    data class Value<T>(
        val value: T,
    ) : WorkOutcome<T>

    data class Failure(
        val error: Throwable,
    ) : WorkOutcome<Nothing>

    data object Cancelled : WorkOutcome<Nothing>
}

/**
 * [CoroutineScope.launch] plus a guarantee the body alone cannot give: [settle] is called exactly
 * once for **every** way the coroutine can end, *including* a scope that was already cancelled
 * before the body was dispatched.
 *
 * That last case is the whole reason this exists. `launch { doThing().also(settle) }` settles
 * nothing when cancellation lands between the `launch` and the first dispatch — the body never
 * runs, so the line that would have reported the result never executes, and a consumer holding
 * the handle waits for a result that can never arrive. Tying the report to the *job's* completion
 * rather than to the body's happy path closes that hole:
 *
 * ```
 * val out = SettableFuture.create<Bitmap>()
 * scope.launchSettled({ outcome -> out.cancel(false) ... }) { fetch(uri) }
 * ```
 *
 * [settle] **must be idempotent**: the completion handler runs after a body that already settled,
 * so a consumer that can only be settled once (a future, a callback, a channel send) is the
 * expected shape. This is not a licence to settle twice — a future whose `set` and `cancel` are
 * both no-ops after the first is exactly the consumer this is for.
 *
 * A [CancellationException] from [block] is rethrown, never reported as [WorkOutcome.Failure]:
 * the job is being cancelled, not the work having failed, and reporting it as a failure would
 * publish an error for a shutdown.
 */
fun <T> CoroutineScope.launchSettled(
    settle: (WorkOutcome<T>) -> Unit,
    block: suspend () -> T,
): Job {
    val job =
        launch {
            // The outcome is resolved first and reported outside the `try`, so a `settle` that
            // itself throws is not mistaken for the work having failed.
            val outcome =
                try {
                    WorkOutcome.Value(block())
                } catch (cancelled: CancellationException) {
                    // Re-thrown, not reported: the job is being cancelled, not the work having
                    // failed, and the completion handler below is what reports cancellation.
                    throw cancelled
                } catch (expected: Throwable) {
                    // Deliberately `Throwable` and not `Exception`: a consumer of this helper has no
                    // way to report an `Error` any other way, and a launched unit of work that dies
                    // without settling its consumer is the failure mode this exists to prevent. The
                    // name is the project's detekt allowlist convention, and the contract it encodes
                    // is [WorkOutcome.Failure] — "it did not produce a value, and here is why".
                    WorkOutcome.Failure(expected)
                }
            settle(outcome)
        }
    // The child's own cancellation, observed as a *completion* rather than inferred from the body
    // having run. Two distinct paths need it and only this one observes both: a body cancelled
    // mid-flight (the `throw cancelled` above is rethrown, so the `settle` inside `launch` never
    // happens) and a body that was never dispatched at all (the whole `launch` body is skipped, so
    // there is no rethrow to observe either). The handler is idempotent from the consumer's side by
    // contract — see [launchSettled].
    job.invokeOnCompletion { cause -> if (cause is CancellationException) settle(WorkOutcome.Cancelled) }
    return job
}
