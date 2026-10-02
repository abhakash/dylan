@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dylan.di

import app.cash.sqldelight.db.SqlDriver
import dylan.db.Dylan
import dylan.diag.LogBuffer
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/**
 * The SQLite handle, opened at most once and never in a constructor.
 *
 * [AppContainer.open] is the intended opener: it runs the open on the io lane so the schema
 * probe, the first-run `Schema.create` and the four PRAGMAs never sit between the platform's
 * entry point and the first frame.
 *
 * **A cold read is the caller's cost, and this class says so rather than hiding it.** [value]
 * cannot hand back a database it has not opened, and opening one is blocking I/O, so a thread
 * that reads it before the deferred open has run *performs the open itself*, on its own thread.
 * That is what the WARN below reports, and it is a UI-thread stall whenever a platform binds its
 * first composition to the graph before `open()` has completed — the fix for that is on the
 * platform side (gate the first read on [AppContainer.open]), not here.
 *
 * What this class does guarantee is that the cost is paid **once per process, by one thread**:
 * [cold] is `LazyThreadSafetyMode.SYNCHRONIZED`, the mode that runs the initialiser exactly once.
 * The previous shape CAS'd the *result*, which is strictly weaker — two threads could both be
 * inside the open at once, so two drivers ran `Schema.create` against one path concurrently and
 * the loser was closed only after the fact. A racing thread now waits for the open already in
 * flight and gets the same handle.
 *
 * The open is taken as a lambda rather than as a [dylan.db.DriverFactory] so that "exactly once"
 * is testable: the platform factory is a final `expect`/`actual` class, so there is otherwise no
 * way to observe how many times it was called.
 */
internal class LazyDatabase(
    private val createDriver: () -> SqlDriver,
    private val log: LogBuffer,
) {
    private val ref = AtomicReference<Handle?>(null)
    private val warned = AtomicBoolean(false)

    /**
     * The single-flight open. `SYNCHRONIZED` (not `PUBLICATION`, which permits the initialiser to
     * run concurrently by design) is what makes "at most once" true; [ref] is only the fast path
     * for readers who arrive after the fact.
     */
    private val cold =
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            val driver = createDriver()
            Handle(Dylan(driver), driver).also { ref.store(it) }
        }

    private class Handle(
        val db: Dylan,
        val driver: SqlDriver,
    )

    val isOpen: Boolean get() = ref.load() != null

    val value: Dylan
        get() {
            ref.load()?.let { return it.db }
            if (warned.compareAndSet(false, true)) {
                log.w("boot", "graph read before open() — the SQLite open ran on the caller's thread")
            }
            return open()
        }

    fun open(): Dylan {
        ref.load()?.let { return it.db }
        // A failed open throws out of the initialiser and `lazy` does not cache a failure, so the
        // next caller retries it rather than inheriting a poisoned graph.
        return cold.value.db
    }
}
