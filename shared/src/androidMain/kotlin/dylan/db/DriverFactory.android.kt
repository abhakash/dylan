package dylan.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import java.io.File

private const val DRIVER_LOG_CAPACITY = 64

/**
 * The wait a contended write is *asked* for. Long enough to outlast the reconciler's sweep and a
 * media-service write landing together, short enough that a genuinely wedged lock surfaces instead
 * of hanging a lane. A request, not a guarantee — see the `busy_timeout` check in [verify] for why
 * only the floor is enforced.
 */
private const val BUSY_TIMEOUT_MS = 5_000L

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
        } catch (catchAllOpen: Exception) {
            // Deliberately `Exception`, not a SQLite type: this is the gate every failure of [open]
            // passes through, and narrowing it would route some of them past the corruption probe
            // entirely. Nothing is swallowed — the catch either rethrows the original or, with the
            // opt-in *and* positive evidence, wipes and retries.
            if (!allowWipeOnCorruption || !isCorrupt()) throw catchAllOpen
            log.e("db", "integrity_check FAILED - wiping dylan.db. This deletes all user data: ${catchAllOpen.message}")
            wipe()
            return open()
        }
    }

    private fun open(): SqlDriver {
        val driver =
            AndroidSqliteDriver(
                schema = Dylan.Schema,
                context = ctx,
                name = DB_NAME,
                factory = WalOpenHelperFactory,
            )
        try {
            // Connection-local, and issued outside any transaction because SQLite silently ignores
            // both of these inside one. `journal_mode` is deliberately absent: Android owns the
            // journal mode through [WalOpenHelperFactory], and [verify] reads it back rather than
            // setting it, because setting it by hand is what silently did nothing for so long.
            pragma(driver, "PRAGMA synchronous=NORMAL")
            pragma(driver, "PRAGMA busy_timeout=$BUSY_TIMEOUT_MS")
            // WAL means Android opens a *pool* of connections, and a pragma is per connection — so
            // `PRAGMA foreign_keys=ON` would arm enforcement on whichever connection happened to
            // serve this statement and leave `ON DELETE CASCADE` a no-op on the rest. Measured on
            // device: foreign_keys read back as 0. Foreign keys go through the platform API instead,
            // which is a dbconfig Android applies to every connection it opens for this database.
            WalOpenHelperFactory.helper?.writableDatabase?.setForeignKeyConstraintsEnabled(true)
            verify(driver)
        } catch (catchAllSetup: Exception) {
            // Cleanup only: the exception is rethrown untouched and never inspected, so its type is
            // irrelevant — what matters is that a half-configured driver is closed on *any* setup
            // failure rather than leaked.
            driver.close()
            throw catchAllSetup
        } finally {
            // Drop the helper reference as soon as the open sequence is done with it. It is read
            // exactly once, above; holding it for the life of the process would pin the file handle
            // and its `-wal`/`-shm` after the driver is closed, which is how `wipe()` ends up
            // unlinking a file this process still has open.
            WalOpenHelperFactory.release()
        }
        return driver
    }

    /**
     * WAL, through the platform API, before the file is ever opened.
     *
     * `AndroidSqliteDriver` has no WAL support of its own — disassembling `android-driver-2.0.2`
     * shows zero references to `enableWriteAheadLogging` or `setWriteAheadLoggingEnabled` — and a
     * bare `PRAGMA journal_mode=WAL` is not a substitute. Android rejected it outright through
     * `execSQL` (it produces a row), and SQLite treats the pragma as a silent no-op inside a
     * transaction. Measured on an API 34 device, the file sat at `journal_mode=delete`; with this
     * factory it reads `wal`, with `-wal` and `-shm` alongside it.
     *
     * Android has wanted `setWriteAheadLoggingEnabled` since API 16 precisely because it is the
     * supported way to change journal mode: it is applied as part of opening, before any statement
     * runs. This wraps only the *factory*; SQLDelight's own `SupportSQLiteOpenHelper.Callback` is
     * left in place, because that callback is what runs `Schema.create`/`Schema.migrate` and
     * replacing it would silently drop the migration chain.
     *
     * The helper is kept so foreign keys can be armed pool-wide once the file is open. See [open].
     *
     * It is a *process-lifetime* object, so holding the helper here would pin the `SQLiteDatabase`
     * — the file handle and its `-wal`/`-shm` — for the life of the process, long after the driver
     * that opened it was closed. That matters most on the wipe path: [wipe] unlinks the file while
     * this process still holds it open, so the space is not reclaimed until the process dies and the
     * stale handle can keep writing to an unlinked inode. [release] drops the reference when the
     * driver closes; it does not close the helper, because the driver owns that and does it itself.
     */
    private object WalOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
        private val delegate = FrameworkSQLiteOpenHelperFactory()

        /** The one helper this driver opened, or null before the driver has opened the file. */
        var helper: SupportSQLiteOpenHelper? = null
            private set

        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
            delegate
                .create(configuration)
                .also {
                    it.setWriteAheadLoggingEnabled(true)
                    helper = it
                }

        /**
         * Stop pinning the helper. Called once the driver owning it has been closed.
         *
         * Clearing rather than closing is deliberate: the driver's own `close()` is what closes the
         * helper, and closing it twice would throw. All this does is stop *this* object from
         * outliving the file, which is the actual leak.
         */
        fun release() {
            helper = null
        }
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
        // WAL and busy_timeout are asserted for the same reason, and because `journal_mode` is the
        // one setting that cannot be read off the file to check it later: it is persistent, so a
        // broken setting is invisible from outside the process that failed to set it.
        val mode = pragmaString(driver, "PRAGMA journal_mode")
        if (!mode.equals("wal", ignoreCase = true)) {
            log.e("db", "PRAGMA journal_mode is '$mode', not wal: readers and writers block each other")
        }
        val busy = pragmaInt(driver, "PRAGMA busy_timeout")
        // A floor, not an equality. WAL puts Android's connection pool in play and a pragma is per
        // connection, so the 5000 we ask for lands on whichever connection served the statement and
        // cannot be made uniform from here — Android supplies its own per-connection default, which
        // measured 2500ms on the device. What must hold is that a contended write *waits* at all;
        // asserting 5000 exactly would be asserting something this code cannot deliver, which is the
        // same "claim the code does not enforce" defect this method exists to catch.
        if (busy <= 0) {
            log.e("db", "PRAGMA busy_timeout is $busy: a contended write fails instantly instead of waiting")
        } else if (busy < BUSY_TIMEOUT_MS) {
            log.i("db", "PRAGMA busy_timeout is $busy here (asked $BUSY_TIMEOUT_MS, pool default applies elsewhere)")
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
            } catch (catchAllProbe: Exception) {
                // A probe that could not run is not evidence of corruption (see [isCorrupt]), and
                // `SQLiteDatabase.openDatabase` has a wide failure surface — a locked file, an
                // unmounted volume, a full disk. Its message is logged; the verdict is "keep".
                log.e("db", "integrity_check could not run: ${catchAllProbe.message}")
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

    private fun pragmaString(
        driver: SqlDriver,
        sql: String,
    ): String =
        driver
            .executeQuery(
                null,
                sql,
                { c -> QueryResult.Value(if (c.next().value) c.getString(0).orEmpty() else "") },
                0,
                null,
            ).value

    /**
     * Every PRAGMA goes through `executeQuery`, never through `execute`.
     *
     * `execute` bottoms out in Android's `execSQL`, which refuses any statement that produces a
     * row, and the two pragmas that carry this file's concurrency behaviour are exactly the two
     * that report their result: `journal_mode=WAL` returns the resulting mode and
     * `busy_timeout=5000` returns the resulting timeout. Both were failing on device with
     * *"Queries can be performed using SQLiteDatabase query or rawQuery methods only"*, so the
     * busy timeout was never applied and a contended write failed instantly instead of waiting.
     * `synchronous` and `foreign_keys` set silently and return nothing, which is why only these two
     * ever complained — and why the complaint had to be read as noise for a while.
     *
     * WAL was genuinely at risk, and the measurement said so. An earlier note here claimed
     * `AndroidSqliteDriver` enables WAL while opening, so the failing pragma was "redundant rather
     * than load-bearing". Measured on device, that was wrong: `journal_mode` read back `rollback`
     * with **no** `-wal` or `-shm` file present at all. WAL is therefore not set here at all — it
     * goes through [WalOpenHelperFactory] before the file opens, because SQLite ignores
     * `journal_mode` from inside an open transaction and ignores it mid-connection anyway.
     */
    private fun pragma(
        driver: SqlDriver,
        sql: String,
    ) {
        runCatching { driver.executeQuery(null, sql, { QueryResult.Value(null) }, 0, null) }
            .onFailure { log.e("db", "$sql failed: ${it.message}") }
    }

    /**
     * Delete the database and its sidecars, and **say whether that worked**.
     *
     * This is the only irreversible operation in the class, and the log line above it tells the user
     * their data is gone. Reporting success unconditionally would make that log a claim rather than
     * a record: a `deleteDatabase` that returns false (open handle, permissions, a read-only
     * volume) or throws would leave the file in place while the log says otherwise, and the next
     * `open()` would fail on the file we claimed to have removed.
     *
     * Best-effort by design — a missing sidecar is not a failure, only a `false` return or a
     * thrown exception is. Nothing here rethrows: the caller is already on the failure path, and a
     * failed delete should not mask the corruption that caused the wipe.
     */
    private fun wipe() {
        runCatching { ctx.deleteDatabase(DB_NAME) }
            .onSuccess { deleted ->
                if (!deleted) log.e("db", "deleteDatabase($DB_NAME) returned false: the file may still exist")
            }.onFailure { log.e("db", "deleteDatabase($DB_NAME) threw: ${it.message}") }
        val base = ctx.getDatabasePath(DB_NAME)
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val sidecar = File(base.path + suffix)
            if (!sidecar.exists()) continue
            runCatching { sidecar.delete() }
                .onSuccess { deleted ->
                    if (!deleted) log.e("db", "could not delete sidecar ${sidecar.name}")
                }.onFailure { log.e("db", "deleting sidecar ${sidecar.name} threw: ${it.message}") }
        }
    }

    private companion object {
        const val DB_NAME = "dylan.db"
    }
}
