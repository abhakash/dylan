package dylan.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dylan.diag.LogBuffer

actual class DriverFactory(
    private val dbPath: String,
    private val log: LogBuffer = LogBuffer.SILENT,
) {
    actual fun createDriver(): SqlDriver {
        try {
            return open()
        } catch (e: Exception) {
            // Dev destructive strategy (see dylan.sq header) — wipe dylan.db* and recreate.
            log.e("db", "open failed, wiping dev DB: ${e.message}")
            wipe()
            return open()
        }
    }

    private fun open(): SqlDriver {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        // NB: in SQLDelight 2.x the executeQuery mapper itself returns QueryResult<R>.
        val ddl: String? =
            driver
                .executeQuery(
                    null,
                    "SELECT sql FROM sqlite_master WHERE type='table' AND name='songs'",
                    { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
                    0,
                    null,
                ).value
        if (ddl == null) {
            Dylan.Schema.create(driver)
        } else if ("cacheable" in ddl) {
            // Stale pre-removal shape (14-col songs): SELECT * would shift resolve_ref and
            // friends by one slot. Throw into the wipe path rather than running corrupt.
            throw IllegalStateException("stale songs schema (cacheable col present)")
        }
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
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            runCatching { java.io.File(dbPath + suffix).delete() }
        }
    }
}
