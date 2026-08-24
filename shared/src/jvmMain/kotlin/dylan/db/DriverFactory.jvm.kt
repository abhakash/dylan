package dylan.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

actual class DriverFactory(
    private val dbPath: String,
) {
    actual fun createDriver(): SqlDriver {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        val exists =
            driver.executeQuery(
                null,
                "SELECT name FROM sqlite_master WHERE type='table' AND name='songs'",
                { cursor -> cursor.next() },
                0,
                null,
            )
        if (exists.value == false) Dylan.Schema.create(driver)
        driver.execute(null, "PRAGMA journal_mode=WAL", 0)
        driver.execute(null, "PRAGMA synchronous=NORMAL", 0)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        driver.execute(null, "PRAGMA busy_timeout=5000", 0)
        return driver
    }
}
