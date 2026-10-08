@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dylan.util

// The multiplatform `AtomicReference`, not `java.util.concurrent`'s: this file is `commonMain`, and
// the iOS lane compiles it (`:shared:compileCommonMainKotlinMetadata` is what CI's iOS job runs), so
// a JVM-only import here is a build break on a platform nobody here can run. Same choice as
// `JobQueue` and `DownloadEngine`.
//
// Note `load()` and not `get()`: on the JVM the multiplatform class is an `actual typealias` for
// `java.util.concurrent.atomic.AtomicReference`, so `get()` compiles there and *only* there. The
// metadata compile — the iOS lane — is what catches it.
import kotlin.concurrent.atomics.AtomicReference

/** One immutable (generation, value) pair. The CAS is only sound while these travel together. */
private class VersionedState<V : Any>(
    val generation: Long,
    val value: V?,
) {
    fun withValue(next: V?) = VersionedState(generation, next)
}

/**
 * A one-slot cache whose contents are invalidated by a monotonic generation counter.
 *
 * The property it exists to provide: **a producer that captured generation *g* and finished its I/O
 * afterwards cannot publish into generation *g+1*.** That is a compare-and-set, not a re-read, and
 * the difference is load-bearing.
 *
 * The re-read version is the bug this replaces. Two plain fields and a guard:
 *
 * ```
 * val gen = generation          // 0
 * val value = compute()         // … the state moves here …
 * if (gen == generation) slot = value    // re-read of an unsynchronised field; may still be 0
 * ```
 *
 * The check and the store are two separate operations, so an invalidation landing between them is
 * lost and the stale value is published *after* the event that was supposed to retire it. The
 * write is also unsynchronised with respect to the reader. Both halves are what
 * [publishIfCurrent] removes: it CASes against the exact immutable state it read, so an
 * [invalidate] that has already landed makes the store fail rather than interleave with it.
 *
 * There is no guarantee that a *published* value is still current when a reader takes it — only
 * that a value from a superseded generation is never published. A caller that needs the pairing
 * should read [generation] and [peek] together; this class is the invalidation barrier, not a
 * snapshot.
 */
class VersionedCell<T : Any>(
    initial: T? = null,
) {
    private val state = AtomicReference<VersionedState<T>>(VersionedState(0L, initial))

    /** The cached value for the current generation, or null. A single atomic read. */
    fun peek(): T? = state.load().value

    /** The current generation. Pass it to [publishIfCurrent] to publish relative to *this* read. */
    fun generation(): Long = state.load().generation

    /**
     * Store [value] if and only if [generation] is still the current one.
     *
     * @return false when the generation moved, i.e. the caller must discard its work. The CAS
     *   retries on a lost race against a *same-generation* writer, so a false return means
     *   "invalidated", never "someone else was quicker".
     */
    fun publishIfCurrent(
        generation: Long,
        value: T,
    ): Boolean {
        while (true) {
            val cur = state.load()
            if (cur.generation != generation) return false
            if (state.compareAndSet(cur, cur.withValue(value))) return true
        }
    }

    /**
     * The cached value, or [make] installed into the current generation — once, even under
     * concurrent callers, since the installation is a CAS rather than a get-then-put.
     *
     * A discarded [make] result is the caller's problem to release, so keep it cheap: it is called
     * at most once per losing racer.
     */
    fun getOrPut(make: () -> T): T {
        while (true) {
            val cur = state.load()
            cur.value?.let { return it }
            val made = make()
            if (state.compareAndSet(cur, cur.withValue(made))) return made
        }
    }

    /**
     * Bump the generation and clear, in one atomic step.
     *
     * Bumping from the *observed* state rather than an independent counter means two concurrent
     * invalidations cannot collapse into one: each CAS retries on the state it did not win, and
     * each retry reads the higher generation and increments again.
     */
    fun invalidate() {
        while (true) {
            val cur = state.load()
            if (state.compareAndSet(cur, VersionedState(cur.generation + 1, null))) return
        }
    }

    /**
     * Clear [expected] from generation [generation], leaving newer generations alone.
     *
     * For a producer that installed a placeholder and wants to retire it *without* invalidating:
     * bumping the generation would make every other in-flight producer's value stale for a reason
     * that never happened. @return true when this call removed it.
     */
    fun clearIf(
        generation: Long,
        expected: T,
    ): Boolean {
        while (true) {
            val cur = state.load()
            if (cur.generation != generation || cur.value !== expected) return false
            if (state.compareAndSet(cur, cur.withValue(null))) return true
        }
    }
}
