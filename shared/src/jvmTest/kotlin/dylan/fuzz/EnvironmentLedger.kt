package dylan.fuzz

/**
 * What the test broke on purpose, so the oracle can tell "the app violated an invariant" from
 * "the test broke the world and the app is now required to repair it".
 *
 * Without this the suite is a red-herring generator: journey 6 *deliberately* deletes a file that a
 * `library` row describes, and INV-21 (no row references a missing file) must then be **suspended**
 * for that step and re-asserted with `expectRepair = true` — the real contract being that the *next
 * reconcile* fixes it, not that the breakage was never observed.
 *
 * Every `expectRepair` entry is asserted twice by the scenarios that create one: once with the
 * suspend in place (the break is tolerated) and once after the repair (the suspend is cleared). An
 * entry whose suspend list is wrong therefore fails in one direction or the other.
 */
class EnvironmentLedger {
    data class Entry(
        val what: String,
        /** Invariant ids whose post-conditions are suspended while this entry is active. */
        val suspends: Set<String>,
        /** True ⇒ the app is obliged to repair this before the end of the scenario. */
        val expectRepair: Boolean,
    )

    private val entries = ArrayList<Entry>()

    fun broke(
        what: String,
        suspends: Set<String>,
        expectRepair: Boolean = true,
    ): Entry {
        val entry = Entry(what, suspends, expectRepair)
        entries += entry
        return entry
    }

    fun clear() = entries.clear()

    fun active(): List<Entry> = entries.toList()

    /** The union of every suspended id. An id is checked iff it is not in here. */
    fun suspended(): Set<String> = entries.flatMap { it.suspends }.toSet()

    /**
     * Suspended id → the `what` of the entry that suspends it, so a skipped condition's report can
     * name the deliberate break instead of only the id. Two entries may suspend the same id; the
     * first one recorded wins, because a report that lists both is a report nobody reads.
     */
    fun suspendedBy(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (e in entries) for (id in e.suspends) out.putIfAbsent(id, e.what)
        return out
    }

    fun expectRepair(): List<Entry> = entries.filter { it.expectRepair }
}
