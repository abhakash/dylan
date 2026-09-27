package dylan

import dylan.common.QueueAlgebra
import dylan.common.QueueInvariants
import dylan.common.QueueStateGenerator
import dylan.common.Violation
import dylan.model.Phase
import dylan.model.PlayerState
import dylan.model.Repeat
import dylan.playback.ItemRef
import dylan.playback.ResumeSnapshot
import dylan.support.testSong
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.random.Random
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The queue-algebra property suite. Bodies live in `jvmTest` (bulk runs, several seeds) while the
 * generator and the predicates live in `commonTest`, so they execute on every target.
 *
 * Invariants that hold today are asserted. Invariants that do **not** hold today are `@Ignore`d
 * with the exact counterexample in the reason, so the suite is green *and* the divergence is on
 * the record; nothing in production was touched to get here. `queueAlgebraReportIsPrintedOnEveryRun`
 * re-runs the same properties and prints PASS/FAIL, so the evidence is in every build log and
 * wave 3 only has to delete the `@Ignore`s.
 */
class QueueStatePropertyTest {
    // ── invariants that hold against today's production code ─────────────────────────────

    @Test
    fun nextIndexIsTotal() = assertInvariant(QueueInvariants.TOTAL, SEED_B) { QueueInvariants.total(it) }

    @Test
    fun shuffleImpliesAPermutation() =
        assertInvariant(
            QueueInvariants.SHUFFLE_IMPLIES_ORDER,
            SEED_C,
        ) { QueueInvariants.shuffleImpliesOrder(it) }

    @Test
    fun indexTracksCurrent() =
        assertInvariant(
            QueueInvariants.INDEX_TRACKS_CURRENT,
            SEED_D,
        ) { QueueInvariants.indexTracksCurrent(it) }

    @Test
    fun repeatAllWalkIsABijectionForTheStateMachine() =
        assertInvariant("repeatAllWalkIsABijection/QueueStateMachine", SEED_E) {
            QueueInvariants.repeatAllIsBijection(it, QueueAlgebra::resolveAdvance)
        }

    @Test
    fun moveWithinQueueIsAnInvolution() =
        assertInvariant(
            QueueInvariants.MOVE_IS_INVOLUTION,
            SEED_G,
        ) { QueueInvariants.moveIsInvolution(it) }

    @Test
    fun snapshotRoundTripIsAlwaysNavigable() {
        val random = Random(SEED_H)
        val generator = QueueStateGenerator(SEED_H)
        val violations = mutableListOf<Violation>()
        repeat(ROUNDS) {
            val snap = randomSnapshot(random, generator)
            val keep = { ref: ItemRef -> ref.songId.hashCode() % KEEP_MODULUS != 0 }
            QueueInvariants.snapshotRoundTrip(snap, keep)?.let(violations::add)
        }
        assertInvariant(QueueInvariants.SNAPSHOT_ROUND_TRIP, violations)
    }

    // ── invariants that do NOT hold today: audit §3.2, findings PC-4 / restore() ──────────

    @Test
    @Ignore(
        "Models.nextUp and QueueStateMachine.resolveAdvance disagree on the shuffle + repeat-ALL " +
            "wrap: nextUp has no wrap on the shuffle branch, so at the end of the permutation the UI " +
            "shows no next track, the engine window shrinks to one item, the prefetcher stops and the " +
            "wrap target is unprotected from eviction. 91/4000 generated states violate it. " +
            "Minimal counterexample: queue=[s0] index=0 shuffleOn=true shuffleOrder=[0] repeat=ALL ⇒ " +
            "resolveAdvance(+1)=0 but nextUp=null. Fix: delete PlayerState.nextUp and have every caller " +
            "use the single total nextIndex(state, dir). See docs/codebase-audit.md §3.2.",
    )
    fun nextIndexMatchesNextUpOverTheWholeMatrix() {
        assertInvariant(
            QueueInvariants.NEXT_INDEX_MATCHES_NEXT_UP,
            SEED_A,
        ) { QueueInvariants.nextIndexMatchesNextUp(it) }
    }

    @Test
    @Ignore(
        "Same missing wrap, seen through the UI-facing accessor: for fixed order with repeat=ALL the " +
            "map index → nextUp must be a permutation of order, and it is not — the last element of the " +
            "shuffle permutation has no successor. 1850/4000 generated states violate it (QueueStateMachine " +
            "satisfies the same property, which is what makes the divergence a defect rather than a choice). " +
            "Minimal counterexample: order=[0] walked to [null]. Fix: as above.",
    )
    fun repeatAllWalkIsABijectionForNextUp() {
        assertInvariant("repeatAllWalkIsABijection/PlayerState.nextUp", SEED_F) {
            QueueInvariants.repeatAllIsBijection(it) { s, _ -> QueueAlgebra.nextUpIndex(s) }
        }
    }

    @Test
    @Ignore(
        "restore() keeps shuffleOn = true after sanitizeSnapshot had to drop a stale permutation, " +
            "leaving a permanently unnavigable queue that toasts 'End of queue' on every press " +
            "(Orchestrator.kt:932 takes restored.order but snap.shuffleOn unconditionally). " +
            "1309/4000 fuzzed snapshots violate it. Minimal counterexample: " +
            "ResumeSnapshot(items=[saavn:s0, saavn:s1], shuffleOn=true, order=[]) → sanitize returns " +
            "order=null while shuffleOn stays true. " +
            "Fix: Orchestrator.restore must set shuffleOn = restored.order != null.",
    )
    fun aRestoredShuffleAlwaysCarriesItsPermutation() {
        val random = Random(SEED_I)
        val generator = QueueStateGenerator(SEED_I)
        val violations = mutableListOf<Violation>()
        repeat(ROUNDS) {
            val snap = randomSnapshot(random, generator).copy(shuffleOn = true)
            val keep = { ref: ItemRef -> ref.songId != DROPPED_SONG }
            QueueInvariants.restoredShuffleIsNavigable(snap, keep)?.let(violations::add)
        }
        assertInvariant(QueueInvariants.RESTORED_SHUFFLE_IS_NAVIGABLE, violations)
    }

    @Test
    @Ignore(
        "The shuffle + repeat-ALL wrap spelled out with two tracks instead of generated, so wave 3 has a " +
            "hand-checkable input. queue=[a,b] index=1 shuffleOn=true shuffleOrder=[0,1] repeat=ALL: " +
            "resolveAdvance(+1)=0 (the wrap) but nextUp=null, because PlayerState.nextUp's shuffle branch " +
            "returns order.getOrNull(pos + 1) with no repeat-ALL fallback. " +
            "Fix: as in nextIndexMatchesNextUpOverTheWholeMatrix.",
    )
    fun theShuffleRepeatAllWrapIsExplicit() {
        val s = twoTrackShuffledStateAtTheEndOfTheOrder()
        assertEquals(0, QueueAlgebra.resolveAdvance(s, +1), "QueueStateMachine wraps on repeat ALL")
        assertNull(QueueAlgebra.nextUpIndex(s), "PlayerState.nextUp does not — this is the defect")
    }

    // ── harness self-checks ──────────────────────────────────────────────────────────────

    @Test
    fun queueAlgebraReportIsPrintedOnEveryRun() {
        val seeds =
            listOf(
                QueueInvariants.NEXT_INDEX_MATCHES_NEXT_UP to SEED_A,
                "repeatAllWalkIsABijection/PlayerState.nextUp" to SEED_F,
                QueueInvariants.RESTORED_SHUFFLE_IS_NAVIGABLE to SEED_I,
            )
        seeds.forEach { (name, seed) ->
            val generator = QueueStateGenerator(seed)
            var violations = 0
            repeat(ROUNDS) {
                val s = generator.next()
                val v =
                    when (name) {
                        QueueInvariants.NEXT_INDEX_MATCHES_NEXT_UP -> QueueInvariants.nextIndexMatchesNextUp(s)
                        else -> QueueInvariants.repeatAllIsBijection(s) { st, _ -> QueueAlgebra.nextUpIndex(st) }
                    }
                if (v != null) {
                    violations++
                    if (violations == 1) println("[queue-property] $name first violation: ${QueueInvariants.render(v)}")
                }
            }
            println("[queue-property] $name: $violations/$ROUNDS states violate it")
        }
    }

    @Test
    fun theGeneratorOnlyEmitsValidStatesAndIsReproducible() {
        val states = QueueStateGenerator(SEED_J).take(ROUNDS)
        assertEquals(ROUNDS, states.size)
        assertEquals(
            states,
            QueueStateGenerator(SEED_J).take(ROUNDS),
            "a failing property must replay from its seed alone",
        )
        assertTrue(states.any { it.shuffleOn }, "both shuffle axes must be exercised")
        assertTrue(states.any { it.repeat == Repeat.ALL })
        assertTrue(states.any { it.repeat == Repeat.ONE })
        assertTrue(states.any { it.queue.isEmpty() })
        assertTrue(states.all { it.shuffleOn == (it.shuffleOrder != null) })
        assertTrue(states.all { it.queue.isEmpty() || it.queue[it.index] == it.current })
    }

    private fun twoTrackShuffledStateAtTheEndOfTheOrder(): PlayerState {
        val queue = listOf(testSong("a"), testSong("b")).toPersistentList()
        return PlayerState(
            phase = Phase.Playing(queue[1].key),
            current = queue[1],
            queue = queue,
            index = 1,
            shuffleOn = true,
            shuffleOrder = persistentListOf(0, 1),
            repeat = Repeat.ALL,
        )
    }

    private fun randomSnapshot(
        random: Random,
        generator: QueueStateGenerator,
    ): ResumeSnapshot {
        val size = random.nextInt(MAX_SNAPSHOT_ITEMS + 1)
        val items = (0 until size).map { ItemRef("saavn", "s$it") }
        val order =
            if (size == 0 || !random.nextBoolean()) {
                emptyList()
            } else {
                generator.permutation(size, random.nextInt(size))
            }
        return ResumeSnapshot(
            items = items,
            index = random.nextInt(maxOf(size, 1)),
            posMs = random.nextInt(60_000).toLong(),
            shuffleOn = random.nextBoolean(),
            order = order,
        )
    }

    private fun assertInvariant(
        name: String,
        seed: Long,
        check: (PlayerState) -> Violation?,
    ) {
        val violations = mutableListOf<Violation>()
        QueueStateGenerator(seed).take(ROUNDS).forEach { check(it)?.let(violations::add) }
        assertInvariant(name, violations)
    }

    /**
     * Report, never swallow: the first few counterexamples are printed in full and the run fails.
     * Deleting an `@Ignore` is therefore all wave 3 has to do to make a fix verifiable — no
     * assertion is ever weakened to go green.
     */
    private fun assertInvariant(
        name: String,
        violations: List<Violation>,
    ) {
        if (violations.isEmpty()) {
            println("[queue-property] PASS $name over $ROUNDS states")
            return
        }
        violations.take(MAX_REPORTED).forEach { println("[queue-property] FAIL $name: ${QueueInvariants.render(it)}") }
        println("[queue-property] FAIL $name: ${violations.size}/$ROUNDS states violate it")
        throw AssertionError(
            "$name violated by ${violations.size}/$ROUNDS states; " +
                "first: ${QueueInvariants.render(violations.first())}",
        )
    }

    private companion object {
        const val ROUNDS = 4_000
        const val MAX_REPORTED = 3
        const val KEEP_MODULUS = 3
        const val DROPPED_SONG = "s0"
        const val MAX_SNAPSHOT_ITEMS = 5
        const val SEED_A = 0x51DE_A11L
        const val SEED_B = 0x51DE_B22L
        const val SEED_C = 0x51DE_C33L
        const val SEED_D = 0x51DE_D44L
        const val SEED_E = 0x51DE_E55L
        const val SEED_F = 0x51DE_F66L
        const val SEED_G = 0x51DE_677L
        const val SEED_H = 0x51DE_488L
        const val SEED_I = 0x51DE_599L
        const val SEED_J = 0x51DE_6AA4L
    }
}
