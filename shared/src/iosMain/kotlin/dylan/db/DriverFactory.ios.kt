package dylan.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DriverFactory(
    private val dbPath: String,
) {
    actual fun createDriver(): SqlDriver {
        // §5.6 explicit DB path — honor baseDir (Application Support) instead of default.
        // NativeSqliteDriver stores at <dbPath>/dylan.db; ensure directory exists via caller.
        val name = if (dbPath.isBlank()) "dylan.db" else "$dbPath/dylan.db"
        val driver = NativeSqliteDriver(Dylan.Schema, name)
        driver.execute(null, "PRAGMA journal_mode=WAL", 0)
        driver.execute(null, "PRAGMA synchronous=NORMAL", 0)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        driver.execute(null, "PRAGMA busy_timeout=5000", 0)
        return driver
    }
}
