package dylan.db

import app.cash.sqldelight.db.SqlDriver

expect class DriverFactory {
    /**
     * Opens the database, creating or migrating it to [Dylan.Schema.version] as needed.
     *
     * A failure is never papered over: a wipe (which deletes the user's favourites, history and
     * cache) happens only on positive evidence of corruption, and only when the platform opted in.
     */
    fun createDriver(): SqlDriver
}
