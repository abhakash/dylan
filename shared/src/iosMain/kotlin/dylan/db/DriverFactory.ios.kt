package dylan.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dylan.diag.LogBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

actual class DriverFactory(
    private val dbPath: String,
    private val log: LogBuffer = LogBuffer.SILENT,
) {
    actual fun createDriver(): SqlDriver {
        try {
            return open()
        } catch (e: Exception) {
            // Dev destructive strategy (see dylan.sq header): pre-cacheable-removal DBs
            // (14-col songs) fail Schema.create/migrate — wipe dylan.db* and recreate.
            log.e("db", "open failed, wiping dev DB: ${e.message}")
            wipe()
            return open()
        }
    }

    private fun open(): SqlDriver {
        // §5.6 Native sqlite driver on iOS forbids path separators in name (checkFilename).
        // Caller (DylanApp.swift:145) ensures <baseDir> (= Application Support/dylan) exists,
        // but DB itself lives at Application Support/dylan.db (sibling) — crash in E2E was
        // "$dbPath/dylan.db" containing '/' . Keep name flat; directory is fixed to
        // NSApplicationSupportDirectory by NativeSqliteDriver.
        val name = "dylan.db"
        val driver = NativeSqliteDriver(Dylan.Schema, name)
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

    @OptIn(ExperimentalForeignApi::class)
    private fun wipe() {
        runCatching {
            val dir =
                NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
                    .first() as String
            val fm = NSFileManager.defaultManager
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                runCatching { fm.removeItemAtPath("$dir/dylan.db$suffix", null) }
            }
        }
    }
}
