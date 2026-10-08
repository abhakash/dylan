package dylan.fuzz

import dylan.cache.Paths
import dylan.config.AppConfig
import dylan.util.Clock
import okio.FileSystem
import okio.Path

/** One failed post-condition, with the counterexample rendered rather than merely counted. */
data class Violation(
    /** `INV-04`, `S-MIG-06`, … */
    val id: String,
    /** The invariant family, so a failure names a family rather than a line. */
    val group: String,
    val title: String,
    /** The counterexample: the offending rows, the plan, the file list. */
    val detail: String,
    /** The SQL that produced it, when it was SQL. */
    val sql: String? = null,
) {
    override fun toString(): String =
        buildString {
            appendLine("[$id] $title  (group: $group)")
            appendLine("    $detail")
            sql?.let { appendLine("    sql: $it") }
        }
}

/** One entry of the audio directory, read once per check so 30 invariants cost one `list`. */
data class AudioEntry(
    val path: Path,
    val name: String,
    val size: Long,
    val mtimeMs: Long,
)

/**
 * The audio directory as the oracle sees it: one `fs.list` plus one `metadataOrNull` per entry,
 * taken once per check and shared by every condition.
 *
 * A snapshot and not live reads is not an optimisation, it is a consistency property: a check that
 * re-listed the directory per invariant could observe three different worlds inside one report, and
 * a violation it reported would have no single counterexample to reproduce.
 */
data class FsSnapshot(
    val entries: List<AudioEntry>,
) {
    val byName: Map<String, AudioEntry> = entries.associateBy { it.name }

    val parts: List<AudioEntry> = entries.filter { it.name.endsWith(".part") }

    val sidecars: List<AudioEntry> = entries.filter { it.name.endsWith(".part.meta") }

    companion object {
        /** Best-effort: a file the harness was told is unreadable is reported as absent, not fatal. */
        fun read(
            fs: FileSystem,
            dir: Path,
        ): FsSnapshot {
            val listed = runCatching { fs.list(dir) }.getOrDefault(emptyList())
            val entries =
                listed.mapNotNull { p ->
                    val meta = runCatching { fs.metadataOrNull(p) }.getOrNull() ?: return@mapNotNull null
                    AudioEntry(p, p.name, meta.size ?: 0L, meta.lastModifiedAtMillis ?: 0L)
                }
            return FsSnapshot(entries)
        }
    }
}

/**
 * What a check is allowed to know.
 *
 * [quiesced] is the important field: some post-conditions are only meaningful when no claim is in
 * progress and no job is in flight (INV-17, INV-20, INV-31), because the design deliberately leaves
 * an `EVICTING` row outliving its library row by one pass. A check that ran mid-flight would report
 * that design as a violation, so those conditions are **skipped, not passed**, when it is false —
 * [DbOracle.skipped] reports them so a caller can see the difference between "held" and "not asked".
 */
data class CheckContext(
    val step: String,
    val quiesced: Boolean,
    val sql: Sql,
    val fs: FileSystem,
    val paths: Paths,
    val cfg: AppConfig,
    val clock: Clock,
    val fsSnapshot: FsSnapshot,
    /** What the test broke on purpose. A condition suspended here is *not* checked. */
    val ledger: EnvironmentLedger,
)

/**
 * A post-condition. Returns `null` when it holds, and the violation it found when it does not.
 *
 * Implementations must be **side-effect free**, with two named exceptions and no others:
 * the CONSTRAINT group writes inside a transaction it rolls back ([Sql.expectRejected]), and the
 * PLAN group runs `EXPLAIN QUERY PLAN`. The catalogue runs every condition on every check, so a
 * condition that wrote for real would make the oracle's verdict depend on how many times it had run.
 */
interface PostCondition {
    val id: String
    val group: Group
    val title: String

    /** Only meaningful at a quiesced point; skipped — visibly — otherwise. */
    val quiescedOnly: Boolean get() = false

    fun check(ctx: CheckContext): Violation?
}

/** The invariant families, so a failure names a family rather than a line. */
enum class Group(
    val label: String,
) {
    SCHEMA("open + schema identity"),
    TOTALS("cache_totals"),
    REFERENTIAL("referential integrity"),
    PINS("pins"),
    DISK("disk <-> rows"),
    PARTS(".part + resume records"),
    INTENTS("intents + protection"),
    HISTORY("engagement + history"),
    SHAPE("query plan + constraint shape"),
}

/**
 * An invariant whose whole body is "this scalar must be zero", or "this query must return no rows".
 *
 * The two forms are the same statement and the same failure: a counterexample is the rows the query
 * returned, so a violation report carries *which* rows, never only how many.
 */
class ScalarCondition(
    override val id: String,
    override val group: Group,
    override val title: String,
    private val sql: String,
    override val quiescedOnly: Boolean = false,
    /** How many offending rows to render. Enough to diagnose, not enough to flood a log. */
    private val sample: Int = 5,
) : PostCondition {
    override fun check(ctx: CheckContext): Violation? {
        val value = ctx.sql.scalarLong(sql)
        if (value == 0L) return null
        return Violation(
            id = id,
            group = group.label,
            title = title,
            detail = "count=$value; rows: ${ctx.sql.rows(sql).take(sample)}",
            sql = sql,
        )
    }
}

/**
 * An invariant that is a statement about the filesystem and the rows together, so it cannot be one
 * SQL statement.
 */
class ProceduralCondition(
    override val id: String,
    override val group: Group,
    override val title: String,
    override val quiescedOnly: Boolean = false,
    private val body: (CheckContext) -> String?,
) : PostCondition {
    override fun check(ctx: CheckContext): Violation? = body(ctx)?.let { Violation(id, group.label, title, it) }
}

/**
 * A constraint the schema is supposed to refuse. The only honest form is write-and-expect-throw
 * inside a rolled-back transaction, so each of these states the write that must be *rejected*.
 *
 * [probe] is not decoration: it is the read that proves the rollback landed, so a "rejected" verdict
 * cannot be produced by a statement that failed for an unrelated reason — a typo in the column name
 * throws exactly like a CHECK does.
 */
class RejectionCondition(
    override val id: String,
    override val group: Group,
    override val title: String,
    private val write: String,
    private val probe: String,
) : PostCondition {
    override fun check(ctx: CheckContext): Violation? {
        val before = ctx.sql.text(probe)
        val threw = ctx.sql.expectRejected(write)
        val after = ctx.sql.text(probe)
        if (threw == null) {
            return Violation(
                id = id,
                group = group.label,
                title = title,
                detail = "the write was ACCEPTED: $write  (probe: $after)",
                sql = write,
            )
        }
        if (before != after) {
            return Violation(
                id = id,
                group = group.label,
                title = title,
                detail = "the write threw but its effect survived the rollback: probe '$before' -> '$after'",
                sql = write,
            )
        }
        return null
    }
}

/**
 * A plan guard: "this query must not sort into a temp b-tree".
 *
 * [controlQuery] is not optional. `EXPLAIN QUERY PLAN` is a four-column result whose *text* is the
 * last column, so a check that reads the wrong one — or a query SQLite happens to satisfy from an
 * index for unrelated reasons — is vacuously true forever. The control is a query that is *required*
 * to report a sort; if it does not, the fixture is wrong and the guard proves nothing.
 */
class PlanCondition(
    override val id: String,
    override val group: Group,
    override val title: String,
    private val query: String,
    private val controlQuery: String,
) : PostCondition {
    override fun check(ctx: CheckContext): Violation? {
        val control = ctx.sql.plan(controlQuery)
        if (control.none { it.contains(SORT_MARKER) }) {
            return Violation(
                id = id,
                group = group.label,
                title = title,
                detail = "the control query did not sort either, so \"no sort\" proves nothing: $control",
                sql = controlQuery,
            )
        }
        val plan = ctx.sql.plan(query)
        if (plan.none { it.contains(SORT_MARKER) }) return null
        return Violation(
            id = id,
            group = group.label,
            title = title,
            detail = "plan sorts: $plan (control: $control)",
            sql = query,
        )
    }
}

/** SQLite's marker for "materialised a sorter", in either spelling it has used. */
private const val SORT_MARKER = "TEMP B-TREE"
