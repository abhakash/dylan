package dylan.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dylan.diag.LogBuffer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.free

/**
 * [NativeSqliteDriver] runs Schema.create/Schema.migrate itself, keyed on `PRAGMA user_version`,
 * so the migration chain is all that is needed here. What is NOT needed any more is the
 * `catch (e: Exception) { wipe(); open() }` fallback: it turned a schema mismatch into the silent
 * destruction of a user's favourites, history and cache, on the @MainActor graph init path.
 *
 * A wipe now requires positive evidence of corruption AND an explicit [allowWipeOnCorruption]
 * opt-in. Every other failure propagates to AppContainer, which logs it, leaving the file alone.
 */
actual class DriverFactory(
    private val dbPath: String,
    private val log: LogBuffer,
    private val allowWipeOnCorruption: Boolean = false,
) {
    actual fun createDriver(): SqlDriver {
        try {
            return open()
        } catch (e: Exception) {
            if (!allowWipeOnCorruption || !isCorrupt()) throw e
            log.e("db", "integrity_check FAILED - wiping $DB_NAME. This deletes all user data: ${e.message}")
            wipe()
            return open()
        }
    }

    private fun open(): SqlDriver {
        // §5.6 Native sqlite driver on iOS forbids path separators in name (checkFilename).
        // The DB lives at Application Support/dylan.db (a sibling of the app's base dir), and the
        // driver resolves the directory itself; "dylan.db/dylan.db" crashed on E2E.
        val driver = NativeSqliteDriver(Dylan.Schema, DB_NAME)
        try {
            // Both of these are connection-local and both are silently no-ops inside a
            // transaction, so they are issued outside one and then read back.
            pragma(driver, "PRAGMA journal_mode=WAL")
            pragma(driver, "PRAGMA synchronous=NORMAL")
            pragma(driver, "PRAGMA foreign_keys=ON")
            pragma(driver, "PRAGMA busy_timeout=5000")
            verify(driver)
        } catch (e: Exception) {
            driver.close()
            throw e
        }
        return driver
    }

    /** Fail loudly rather than run with every ON DELETE CASCADE silently turned into a no-op. */
    private fun verify(driver: SqlDriver) {
        val v = pragmaInt(driver, "PRAGMA user_version")
        check(v == Dylan.Schema.version) {
            "user_version=$v, expected ${Dylan.Schema.version} after migrate"
        }
        val fk = pragmaInt(driver, "PRAGMA foreign_keys")
        if (fk != 1L) {
            log.e("db", "PRAGMA foreign_keys did not take effect (got $fk): ON DELETE CASCADE is a no-op")
        }
    }

    /** Read-only, no SQLDelight, no schema: the one question the wipe decision depends on. */
    @OptIn(ExperimentalForeignApi::class)
    private fun isCorrupt(): Boolean {
        val path = dbFilePath()
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return false
        val handle = nativeHeap<sqlite3>()
        val opened = sqlite3_open_v2(path, handle.ptr, SQLITE_OPEN_READONLY, null)
        if (opened != SQLITE_OK) {
            sqlite3_close_v2(handle.value)
            free(handle)
            return true
        }
        val verdict =
            try {
                readIntegrityVerdict(handle.value)
            } finally {
                sqlite3_close_v2(handle.value)
                free(handle)
            }
        if (verdict != "ok") log.e("db", "integrity_check: $verdict")
        return verdict != "ok"
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readIntegrityVerdict(handle: CPointer<sqlite3>): String {
        val stmt = nativeHeap<sqlite3_stmt>()
        if (sqlite3_prepare_v2(handle, "PRAGMA integrity_check", -1, stmt.ptr, null) != SQLITE_OK) {
            free(stmt)
            return "prepare-failed"
        }
        val row = sqlite3_step(stmt.value)
        val text = if (row == SQLITE_ROW) sqlite3_column_text(stmt.value, 0)?.toKString() ?: "" else "step-$row"
        sqlite3_finalize(stmt.value)
        free(stmt)
        return text
    }

    private fun pragmaInt(
        driver: SqlDriver,
        sql: String,
    ): Long =
        driver
            .executeQuery(
                null,
                sql,
                { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) },
                0,
                null,
            ).value

    private fun pragma(
        driver: SqlDriver,
        sql: String,
    ) {
        runCatching { driver.execute(null, sql, 0) }
            .onFailure { log.e("db", "$sql failed: ${it.message}") }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun dbFilePath(): String =
        (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String) +
            "/" + DB_NAME

    @OptIn(ExperimentalForeignApi::class)
    private fun wipe() {
        runCatching {
            val fm = NSFileManager.defaultManager
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                runCatching { fm.removeItemAtPath(dbFilePath() + suffix, null) }
            }
        }
    }

    private companion object {
        const val DB_NAME = "dylan.db"
        const val SQLITE_OK = 0
        const val SQLITE_ROW = 100
        const val SQLITE_OPEN_READONLY = 0x00000001
    }
}
