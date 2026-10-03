package dylan.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dylan.diag.LogBuffer
import java.io.File
import java.util.Properties

/**
 * The JVM driver is the one platform where this class owns the whole open path, so it is also
 * where the schema-version policy lives and is testable.
 *
 * What it does NOT do any more: catch every exception and delete the database. That fallback is
 * what turned a schema mismatch into the silent destruction of a user's favourites, history and
 * cache — on Android from inside Application.onCreate, before the first frame. A wipe now
 * requires positive evidence of corruption (`PRAGMA integrity_check`) *and* an explicit
 * [allowWipeOnCorruption] opt-in; every other failure propagates with the file intact.
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
            log.e("db", "integrity_check FAILED - wiping $dbPath. This deletes all user data: ${e.message}")
            wipe()
            return open()
        }
    }

    /**
     * Connection-local pragmas must be supplied as driver *properties*, not executed as
     * statements. Two reasons, both load-bearing:
     *
     * 1. `PRAGMA foreign_keys` is a no-op inside a transaction (SQLite docs), and SQLDelight
     *    runs `Schema.create`/`migrate` in one.
     * 2. `JdbcSqliteDriver` **connection-pools**. A statement executes on whichever pooled
     *    connection the pool hands out and is then released, so the setting applies to that one
     *    connection only. Every other connection in the pool — and every connection opened after
     *    a `close()` — silently has foreign keys OFF, which turns every `ON DELETE CASCADE` in
     *    the schema into a no-op and leaves orphan rows.
     *
     * xerial's `SQLiteConfig` applies known pragmas from the connection Properties at connect
     * time, so this is the one form that reaches every connection. This is also the form the
     * SQLDelight docs prescribe for the JVM driver.
     */
    private fun connectionProperties(): Properties =
        Properties().apply {
            setProperty("foreign_keys", "true")
            setProperty("busy_timeout", "5000")
        }

    private fun open(): SqlDriver {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath", connectionProperties())
        try {
            applyMigrations(driver)
            // Durable, file-scoped: safe to set by statement.
            pragma(driver, "PRAGMA journal_mode=WAL")
            pragma(driver, "PRAGMA synchronous=NORMAL")
            verify(driver)
        } catch (e: Exception) {
            driver.close()
            throw e
        }
        return driver
    }

    private fun applyMigrations(driver: SqlDriver) {
        val target = Dylan.Schema.version
        val from = userVersion(driver)
        when {
            from == 0L && tableCount(driver) == 0L -> {
                Dylan.Schema.create(driver)
                setUserVersion(driver, target)
                log.i("db", "created dylan.db at schema v$target")
            }
            // A pre-versioning store (the old JVM factory never set user_version) and a v1 store
            // (the Android/iOS drivers set it) both land here and run the same 1.sqm chain.
            from < target -> {
                Dylan.Schema.migrate(driver, from, target)
                setUserVersion(driver, target)
                log.i("db", "migrated dylan.db v$from -> v$target")
            }
            else -> log.i("db", "dylan.db already at v$target")
        }
    }

    /** Fail loudly rather than run with every ON DELETE CASCADE silently turned into a no-op. */
    private fun verify(driver: SqlDriver) {
        val v = userVersion(driver)
        check(v == Dylan.Schema.version) {
            "user_version=$v, expected ${Dylan.Schema.version} after migrate"
        }
        val fk = pragmaInt(driver, "PRAGMA foreign_keys")
        if (fk != 1L) {
            error("PRAGMA foreign_keys did not take effect (got $fk): every ON DELETE CASCADE is a no-op")
        }
    }

    /**
     * Positive evidence of corruption, or nothing.
     *
     * Two things make this more than `verdict != "ok"`:
     *
     * 1. A probe that could not *run* used to return `true`. It cannot open the file, so it has
     *    learned nothing about the bytes — and returning `true` inverted the policy this method
     *    exists to enforce. With the opt-in set, every transient condition (a locked file, a full
     *    disk, a briefly unavailable mount) became "delete the user's favourites, history and
     *    cache". It now returns `false` and the original open failure propagates with the library
     *    intact, which is the direction this class was rewritten to fail in.
     * 2. But *some* failures ARE evidence: a file whose header is not a database reports
     *    `SQLITE_NOTADB` and a structurally broken one reports `SQLITE_CORRUPT`, and neither can be
     *    produced by a healthy file. Those still wipe. The line is between "the bytes are wrong"
     *    and "the bytes could not be reached", and only the former justifies deleting them.
     *
     * A schema mismatch deliberately falls on the *keep* side: `PRAGMA integrity_check` is a
     * content check, and a database whose `user_version` is simply wrong is a perfectly healthy
     * file. Measured, not assumed — see `DriverFactoryWipeGateTest`.
     */
    private fun isCorrupt(): Boolean {
        val driver =
            runCatching { JdbcSqliteDriver("jdbc:sqlite:$dbPath", connectionProperties()) }
                .getOrElse {
                    log.e("db", "integrity_check could not open $dbPath: ${it.message}")
                    return false
                }
        return try {
            val verdict =
                driver
                    .executeQuery(
                        null,
                        "PRAGMA integrity_check",
                        { c -> QueryResult.Value(if (c.next().value) c.getString(0) else "") },
                        0,
                        null,
                    ).value
            if (verdict == "ok") {
                false
            } else {
                log.e("db", "integrity_check: $verdict")
                true
            }
        } catch (e: Exception) {
            if (isContentCorruption(e)) {
                log.e("db", "integrity_check reports a corrupt file: ${e.message}")
                true
            } else {
                log.e("db", "integrity_check could not run, treating the file as intact: ${e.message}")
                false
            }
        } finally {
            runCatching { driver.close() }
        }
    }

    /**
     * True only when the failure says the *file* is wrong, not when it says the file could not be
     * reached. A bad header or a broken b-tree is evidence; `BUSY`, `LOCKED`, `CANTOPEN`,
     * `READONLY`, `PERM`, `IOERR`, `FULL`, `NOMEM` are statements about the environment, and none
     * of them justifies deleting a library.
     *
     * Matched on the driver's message rather than on `org.sqlite.SQLiteException.resultCode`:
     * `sqlite-jdbc` is a transitive dependency (reached through `sqldelight`'s JDBC driver), so its
     * types are not on this module's compile classpath. The two codes below are the only ones whose
     * verdict is a fact about the file's contents, and SQLite's messages are stable and specific.
     */
    private fun isContentCorruption(e: Exception): Boolean {
        val msg = e.message.orEmpty()
        return "SQLITE_NOTADB" in msg ||
            ("SQLITE_CORRUPT" in msg) ||
            ("file is not a database" in msg) ||
            ("database disk image is malformed" in msg)
    }

    private fun userVersion(driver: SqlDriver): Long = pragmaInt(driver, "PRAGMA user_version")

    private fun setUserVersion(
        driver: SqlDriver,
        v: Long,
    ) {
        driver.execute(null, "PRAGMA user_version = $v", 0)
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

    private fun tableCount(driver: SqlDriver): Long =
        driver
            .executeQuery(
                null,
                "SELECT COUNT(*) FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%'",
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
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            runCatching { File(dbPath + suffix).delete() }
        }
    }
}
