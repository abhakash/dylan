package dylan.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(
    private val ctx: Context,
) {
    actual fun createDriver(): SqlDriver {
        val driver = AndroidSqliteDriver(Dylan.Schema, ctx, "dylan.db")
        // F1 pragmas deferred for Android — AndroidSqliteDriver treats PRAGMA as query
        // and execute() throws; keep original behaviour until a safe exec path is verified.
        return driver
    }
}
