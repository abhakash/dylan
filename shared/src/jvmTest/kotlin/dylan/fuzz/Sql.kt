package dylan.fuzz

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dylan.db.Dylan

/**
 * Raw-SQL escape hatch over the *real* driver the app uses.
 *
 * Extracted from `CacheMigrationTest`'s private helpers with no behaviour change. It exists because
 * the invariant catalogue needs three primitives that generated queries cannot express: a
 * constraint check that must be *rejected* (no query can ask "did this throw?"), a scalar that
 * distinguishes "no row" from a real zero (the totals assertions depend on telling those apart),
 * and an `EXPLAIN QUERY PLAN` read that returns the plan **text**.
 *
 * **Trap 1 — `scalarLong` returns -1 for "no row".** `cache_totals` reads are
 * `COALESCE((SELECT … WHERE id=0), 0)`, so a missing singleton row and a genuine zero are the same
 * number to the app. They are not the same number to an invariant: INV-04/05 must be able to see a
 * missing row.
 *
 * **Trap 2 — the plan text is the LAST column.** `EXPLAIN QUERY PLAN` returns
 * `(id, parent, notused, detail)`. Reading column 0 yields small integers, so
 * "the plan must not report TEMP B-TREE" would be *vacuously* true forever. [plan] reads the last
 * column, and the PLAN invariant carries a control query that must produce one.
 */
class Sql(
    private val driver: SqlDriver,
) {
    fun exec(sql: String) {
        driver.execute(null, sql, 0)
    }

    /** -1 for "no row", 0 for a real zero. See Trap 1. */
    fun scalarLong(sql: String): Long =
        driver
            .executeQuery(
                null,
                sql,
                { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else NO_ROW) },
                0,
                null,
            ).value

    /** Every row's first column, in order. NULL renders as the empty string. */
    fun rows(sql: String): List<String> =
        driver
            .executeQuery(
                null,
                sql,
                { c ->
                    val out = ArrayList<String>()
                    while (c.next().value) {
                        out += c.getString(0) ?: ""
                    }
                    QueryResult.Value(out)
                },
                0,
                null,
            ).value

    fun text(sql: String): String = rows(sql).singleOrNull() ?: ""

    /**
     * The **detail** column of each plan row. See Trap 2 — column 0 is an integer row id and reading
     * it would make every plan assertion a tautology.
     */
    fun plan(sql: String): List<String> =
        driver
            .executeQuery(
                null,
                "EXPLAIN QUERY PLAN $sql",
                { c ->
                    val out = ArrayList<String>()
                    while (c.next().value) {
                        out += (0..PLAN_COLUMNS).mapNotNull { c.getString(it) }.joinToString("|")
                    }
                    QueryResult.Value(out)
                },
                0,
                null,
            ).value

    fun userVersion(): Long = scalarLong("PRAGMA user_version")

    fun pragmaInt(sql: String): Long = scalarLong(sql)

    fun names(
        type: String,
        like: String? = null,
    ): List<String> {
        val filter = if (like == null) "" else " AND name LIKE '$like'"
        return rows("SELECT name FROM sqlite_master WHERE type = '$type'$filter ORDER BY name")
    }

    fun tables(): List<String> = names("table")

    fun views(): List<String> = names("view")

    fun indexes(): List<String> = names("index")

    fun triggers(): List<String> = names("trigger")

    fun objectExists(
        name: String,
        type: String,
    ): Boolean = scalarLong("SELECT COUNT(*) FROM sqlite_master WHERE name = '$name' AND type = '$type'") == 1L

    /**
     * Runs [body] inside a **driver** transaction, committed if it returns and rolled back if it
     * throws.
     *
     * Not `BEGIN` / `ROLLBACK` as statements: SQLDelight's JDBC driver uses a `ThreadedConnectionManager`
     * for a file URL (verified: `JdbcSqliteDriverKt.connectionManager` picks `ThreadedConnectionManager`
     * for anything that is not `:memory:`), so a statement-level `BEGIN` and a statement-level
     * `ROLLBACK` are two independent hand-outs of the connection and do not have to be the same one —
     * which fails as `cannot rollback - no transaction is active`, or, worse, succeeds in committing a
     * write the caller believed it had discarded. The driver's own transaction object is the only
     * form that guarantees the pairing.
     */
    fun inTransaction(body: () -> Unit) {
        Dylan(driver).transaction { body() }
    }

    /** Runs [body] in a transaction that is rolled back **whatever** [body] does. */
    fun rolledBack(body: () -> Unit) {
        try {
            inTransaction {
                body()
                throw Rollback()
            }
        } catch (_: Rollback) {
            // Absorbing the marker is the whole point of this helper. The body's own exceptions are
            // *not* `Rollback`, so they are never rethrown-to-nothing here: they propagate, and the
            // driver's transaction rolls back on the way.
        }
    }

    /**
     * Write-and-expect-throw, rolled back.
     *
     * The CONSTRAINT group of the catalogue is a set of writes that must be *refused*. There is no
     * non-destructive way to ask "would SQLite have refused this?" — `PRAGMA ignore_check_constraints`
     * answers a different question — so the only honest form is to attempt the write inside a
     * transaction that is then rolled back, and assert the exception. Wrapped here so every NEG
     * check reads the same and cannot forget the rollback.
     *
     * Returns the exception that was thrown, or `null` if the write was **accepted** — which is
     * always a failure, so the callers assert non-null. The rejection is *not* swallowed inside the
     * transaction (an exception there rolls the whole thing back), it is caught here, after the
     * driver has already rolled it back.
     */
    fun expectRejected(sql: String): Throwable? =
        try {
            rolledBack { exec(sql) }
            null
        } catch (t: Throwable) {
            t
        }

    private class Rollback : RuntimeException(null, null, false, false)

    /** True when the CHECK/FK actually refused [sql], rather than the write landing. */
    fun rejects(sql: String): Boolean = expectRejected(sql) != null

    companion object {
        /**
         * What [scalarLong] returns for "no row". Public because the invariant catalogue has to be
         * able to tell "the query returned no rows" from "the value is zero" — `INV-38`'s trim
         * check reads an empty `play_history` through exactly this value.
         */
        const val NO_ROW = -1L

        /** `EXPLAIN QUERY PLAN` is `(id, parent, notused, detail)` — four columns. */
        private const val PLAN_COLUMNS = 3
    }
}
