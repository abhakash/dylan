package dylan

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * The wipe gate: a schema mismatch must NEVER reach the delete path.
 *
 * The wipe is the most destructive thing this app can do — it removes the user's favourites, history
 * and cache — so it is worth pinning the input that most plausibly reaches it by accident.
 * `verify()` deliberately *throws* when `user_version` does not equal [Dylan.Schema.version] after
 * migrate, and that throw lands in `createDriver`'s catch, which is the same catch the wipe hangs
 * off. "The schema did not match" and "the file is corrupt" are therefore the same code path,
 * separated only by `isCorrupt()`.
 *
 * The separation works because `PRAGMA integrity_check` is a *content* check: a database whose
 * `user_version` is wrong is a perfectly healthy file, and integrity_check answers `ok`. Measured
 * here rather than assumed.
 *
 * `allowWipeOnCorruption = true` is used deliberately: the point is to exercise the gate itself,
 * which production never does (every call site takes the `false` default). A default-constructed
 * factory would short-circuit the gate entirely and hide every bug in it.
 */
class DriverFactoryWipeGateTest {
    private val log = LogBuffer(minLevel = LogLevel.DEBUG)

    @Test
    fun aSchemaMismatchNeverDeletesTheLibrary() {
        val dbFile = tempFile("schema")
        // Healthy database, wrong version: the exact shape of a downgrade or a stale build.
        seed(dbFile, userVersion = Dylan.Schema.version + 7)

        val factory = DriverFactory(dbFile.path, log, allowWipeOnCorruption = true)
        assertFails { factory.createDriver() }
        assertTrue(dbFile.exists(), "the schema mismatch DELETED the database")
        assertEquals(1L, settingCount(dbFile), "the user's rows did not survive the refused wipe")
        assertTrue(
            log.dump().none { it.msg.contains("wiping") },
            "the wipe path was taken for a schema mismatch: ${log.dump().map { it.msg }}",
        )
    }

    /**
     * The complementary direction, so the fix above cannot be read as "never wipe".
     */
    @Test
    fun aFileThatIsNotADatabaseIsStillTreatedAsCorrupt() {
        val dbFile = tempFile("notadb")
        dbFile.parentFile.mkdirs()
        dbFile.writeText("this is not a sqlite file at all\n".repeat(200))

        val factory = DriverFactory(dbFile.path, log, allowWipeOnCorruption = true)
        factory.createDriver() // must succeed, by wiping and recreating
        assertTrue(dbFile.exists(), "the corrupt file was neither wiped nor replaced")
        assertTrue(
            log.dump().any { it.msg.contains("wiping") },
            "a file that is not a database must still be treated as corrupt",
        )
    }

    /**
     * The environmental half of the gate, which is the half that was actually wrong.
     *
     * `isCorrupt()` used to answer `true` whenever the probe could not *run*. A file the driver
     * cannot open is a fact about permissions or locking, not about the bytes — so with the opt-in
     * set, one unreadable file destroyed a library that was intact a moment earlier. The gate now
     * keeps the file and lets the original failure propagate.
     *
     * This is the case the schema-mismatch test above cannot reach: there `integrity_check` runs and
     * answers `ok`; here it never runs at all. Both directions have to be pinned, or the gate can be
     * "fixed" by simply never wiping.
     */
    @Test
    fun anUnreadableFileIsNotEvidenceOfCorruption() {
        val dbFile = tempFile("unreadable")
        seed(dbFile)

        assertTrue(dbFile.setReadable(false), "could not make the file unreadable")
        // Precondition: the environment really is what broke, so this cannot pass vacuously.
        val probeFails = integrityCheckFails(dbFile)
        if (!probeFails) {
            dbFile.setReadable(true)
            return // running with privileges that bypass file permissions; nothing to assert
        }

        val factory = DriverFactory(dbFile.path, log, allowWipeOnCorruption = true)
        assertFails { factory.createDriver() }

        dbFile.setReadable(true)
        assertTrue(dbFile.exists(), "an unreadable file was deleted as if it were corrupt")
        assertEquals(1L, settingCount(dbFile), "the user's rows did not survive an unreadable-file failure")
    }

    @Test
    fun theOptOutIsNotAnAccidentOfConstruction() {
        // Every production call site takes the default. If that ever changes, this is the note.
        val dbFile = tempFile("default")
        seed(dbFile)
        DriverFactory(dbFile.path, log).createDriver() // healthy: opens fine
        assertTrue(log.dump().none { it.msg.contains("wiping") })
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun tempFile(tag: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "dylan-wipegate-$tag-${System.nanoTime()}")
        dir.mkdirs()
        return File(dir, "dylan.db")
    }

    /**
     * A real database holding one user row, stamped at [userVersion].
     *
     * The version stamp matters: without it the factory takes the `from == 0 && tables exist` branch
     * and fails in `Schema.migrate` for an unrelated reason, which would make every test below pass
     * without ever reaching the gate.
     */
    private fun seed(
        dbFile: File,
        userVersion: Long = Dylan.Schema.version,
    ) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.path}", Properties())
        try {
            Dylan.Schema.create(driver)
            driver.execute(null, "INSERT INTO settings(key, value) VALUES ('resume', 'precious')", 0)
            driver.execute(null, "PRAGMA user_version = $userVersion", 0)
        } finally {
            driver.close()
        }
    }

    private fun integrityCheckFails(dbFile: File): Boolean =
        runCatching {
            query(dbFile, "PRAGMA integrity_check") { c -> if (c.next().value) c.getString(0) else "" }
        }.isFailure

    private fun settingCount(dbFile: File): Long =
        query(dbFile, "SELECT COUNT(*) FROM settings") { c ->
            if (c.next().value) c.getLong(0) ?: 0L else 0L
        }

    /** Runs [sql] against [dbFile] through the same driver the factory uses, then closes it. */
    private fun <T> query(
        dbFile: File,
        sql: String,
        read: (app.cash.sqldelight.db.SqlCursor) -> T,
    ): T {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.path}", Properties())
        return try {
            driver.executeQuery(null, sql, { c -> QueryResult.Value(read(c)) }, 0, null).value
        } finally {
            driver.close()
        }
    }
}
