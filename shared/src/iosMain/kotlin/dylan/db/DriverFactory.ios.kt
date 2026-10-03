package dylan.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dylan.diag.LogBuffer

/**
 * [NativeSqliteDriver] runs Schema.create/Schema.migrate itself, keyed on `PRAGMA user_version`,
 * so the migration chain is all that is needed here. What is NOT needed any more is the
 * `catch (e: Exception) { wipe(); open() }` fallback: it turned a schema mismatch into the silent
 * destruction of a user's favourites, history and cache, on the @MainActor graph init path.
 *
 * **iOS has no corruption probe, so it never wipes.** A wipe needs positive evidence of corruption
 * — `PRAGMA integrity_check` read from the broken file — and there is no way to ask that question
 * here: the JVM factory runs it through JDBC, and Kotlin/Native ships no `sqlite3` cinterop for the
 * Apple targets at all, so the raw `sqlite3_open_v2`/`sqlite3_step` calls this file used to make
 * could not compile on any target. Without the evidence, every failure propagates to
 * [dylan.di.AppContainer], which logs it and leaves the file alone. That is the safe direction to
 * fail in: a user who hits a real corruption keeps their file, and the jvm/android factories —
 * which can check — still self-heal.
 */
actual class DriverFactory(
    private val log: LogBuffer,
) {
    actual fun createDriver(): SqlDriver {
        // §5.6: NativeSqliteDriver forbids path separators in a name and resolves the directory
        // itself, so the DB is the bare name and lands in Application Support. "dylan.db/dylan.db"
        // — passing the app's base dir as the name — crashed on E2E.
        val driver = NativeSqliteDriver(Dylan.Schema, DB_NAME)
        try {
            // Connection-local, and both are silently no-ops inside a transaction, so they are
            // issued outside one and then read back.
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

    private companion object {
        const val DB_NAME = "dylan.db"
    }
}
