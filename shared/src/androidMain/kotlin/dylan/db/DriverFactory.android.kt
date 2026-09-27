package dylan.db

import android.content.Context
import android.util.Log
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import java.io.File

private const val DRIVER_LOG_CAPACITY = 64

/**
 * The DB is opened from `Application.onCreate`, i.e. before any shared LogBuffer exists, so
 * unlike every other component this one still needs a default. It is a real (if small) ring
 * with a logcat mirror, not a silently-discarding buffer: "open failed, wiping dev DB" has to
 * be visible even while the app graph is still building.
 */
private fun bootLog(): LogBuffer =
    LogBuffer(capacity = DRIVER_LOG_CAPACITY, minLevel = LogLevel.WARN).also { buf ->
        buf.bindSink { e -> Log.e("Dylan:${e.tag}", e.msg) }
    }

actual class DriverFactory(
    private val ctx: Context,
    private val log: LogBuffer = bootLog(),
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
