package dylan.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import java.io.File

private const val DRIVER_LOG_CAPACITY = 64

/**
 * The DB is opened from `Application.onCreate`, i.e. before any shared LogBuffer exists, so
 * unlike every other component this one still needs a default. It is a real (if small) ring
 * with a logcat mirror, not a silently-discarding buffer: "user_version mismatch" has to be
 * visible even while the app graph is still building.
 */
private fun bootLog(): LogBuffer =
    LogBuffer(capacity = DRIVER_LOG_CAPACITY, minLevel = LogLevel.WARN).also { buf ->
        buf.bindSink { e -> Log.e("Dylan:${e.tag}", e.msg) }
    }

/**
 * [AndroidSqliteDriver] runs Schema.create/Schema.migrate itself, keyed on `PRAGMA user_version`,
 * so the migration chain is all that is needed here. What is NOT needed any more is the
 * `catch (e: Exception) { wipe(); open() }` fallback: it turned a schema mismatch into the silent
 * destruction of a user's favourites, history and cache, from inside Application.onCreate,
 * before the first frame had been drawn.
 *
 * A wipe now requires positive evidence of corruption AND an explicit [allowWipeOnCorruption]
 * opt-in. Every other failure propagates to AppContainer, which logs it, leaving the file alone.
 */
actual class DriverFactory(
    private val ctx: Context,
    private val log: LogBuffer = bootLog(),
    private val allowWipeOnCorruption: Boolean = false,
) {
    actual fun createDriver(): SqlDriver {
        try {
            return open()
        } catch (e: Exception) {
            if (!allowWipeOnCorruption || !isCorrupt()) throw e
            log.e("db", "integrity_check FAILED - wiping dylan.db. This deletes all user data: ${e.message}")
            wipe()
            return open()
        }
    }

    private fun open(): SqlDriver {
        val driver = AndroidSqliteDriver(Dylan.Schema, ctx, DB_NAME)
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

    /**
     * Positive evidence of corruption, or nothing.
     *
     * A probe that *could not run* is not evidence: a locked file, an unavailable mount or a full
     * disk says nothing about the bytes. Returning `true` here inverted the policy the class was
     * rewritten to enforce — with the opt-in set, every transient I/O condition became "delete the
     * user's favourites, history and cache". Returning `false` propagates the original open
     * failure with the library intact, which is the safe direction to fail in.
     */
    private fun isCorrupt(): Boolean {
        val file = ctx.getDatabasePath(DB_NAME)
        if (!file.exists()) return false
        val verdict =
            try {
                readVerdict(file.path)
            } catch (e: Exception) {
                log.e("db", "integrity_check could not run: ${e.message}")
                return false
            }
        if (verdict == "ok") return false
        log.e("db", "integrity_check: $verdict")
        return true
    }

    private fun readVerdict(path: String): String =
        SQLiteDatabase
            .openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
            .use { db ->
                db
                    .rawQuery("PRAGMA integrity_check", null)
                    .use { c -> if (c.moveToFirst()) c.getString(0) else "" }
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

    private fun wipe() {
        runCatching { ctx.deleteDatabase(DB_NAME) }
        val base = ctx.getDatabasePath(DB_NAME)
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            runCatching { File(base.path + suffix).delete() }
        }
    }

    private companion object {
        const val DB_NAME = "dylan.db"
    }
}
