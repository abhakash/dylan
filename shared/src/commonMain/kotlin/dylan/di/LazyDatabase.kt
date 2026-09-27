@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dylan.di

import app.cash.sqldelight.db.SqlDriver
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.diag.LogBuffer
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/**
 * The SQLite handle, opened at most once and never in a constructor.
 *
 * [AppContainer.open] is the intended opener: it runs the open on the io lane so the schema
 * probe, the first-run `Schema.create` and the four PRAGMAs never sit between the platform's
 * entry point and the first frame. A consumer that reaches [value] first — a platform graph
 * whose UI binds before the open finishes — still gets a working database, with the open on
 * *its* thread and a WARN to say so; a second racing thread's driver is closed rather than
 * leaked.
 */
internal class LazyDatabase(
    private val driverFactory: DriverFactory,
    private val log: LogBuffer,
) {
    private val ref = AtomicReference<Handle?>(null)
    private val warned = AtomicBoolean(false)

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
        val driver = driverFactory.createDriver()
        val created = Handle(Dylan(driver), driver)
        return if (ref.compareAndSet(null, created)) {
            created.db
        } else {
            driver.close()
            ref.load()?.db ?: created.db
        }
    }
}
