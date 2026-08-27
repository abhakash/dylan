package dylan.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(
    private val ctx: Context,
) {
    actual fun createDriver(): SqlDriver {
        val driver = AndroidSqliteDriver(Dylan.Schema, ctx, "dylan.db")
        runCatching { driver.execute(null, "PRAGMA journal_mode=WAL", 0) }
        runCatching { driver.execute(null, "PRAGMA synchronous=NORMAL", 0) }
        runCatching { driver.execute(null, "PRAGMA foreign_keys=ON", 0) }
        runCatching { driver.execute(null, "PRAGMA busy_timeout=5000", 0) }
        return driver
    }
}
