package dylan.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dylan.diag.LogBuffer
import java.io.File

actual class DriverFactory(
    private val ctx: Context,
    private val log: LogBuffer = LogBuffer.SILENT,
) {
    actual fun createDriver(): SqlDriver {
        try {
            return open()
        } catch (e: Exception) {
            // Dev destructive strategy (see dylan.sq header): pre-cacheable-removal DBs
            // (14-col songs) fail Schema.create/migrate — wipe dylan.db* and recreate.
            // Broad catch is deliberate: driver wraps sqlite errors in several types.
            log.e("db", "open failed, wiping dev DB: ${e.message}")
            wipe()
            return open()
        }
    }

    private fun open(): SqlDriver {
        val driver = AndroidSqliteDriver(Dylan.Schema, ctx, "dylan.db")
        pragma(driver, "PRAGMA journal_mode=WAL")
        pragma(driver, "PRAGMA synchronous=NORMAL")
        pragma(driver, "PRAGMA foreign_keys=ON")
        pragma(driver, "PRAGMA busy_timeout=5000")
        return driver
    }

    private fun pragma(
        driver: SqlDriver,
        sql: String,
    ) {
        runCatching { driver.execute(null, sql, 0) }
            .onFailure { log.e("db", "$sql failed: ${it.message}") }
    }

    private fun wipe() {
        runCatching { ctx.deleteDatabase("dylan.db") }
        val base = ctx.getDatabasePath("dylan.db")
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            runCatching { File(base.path + suffix).delete() }
        }
    }
}
