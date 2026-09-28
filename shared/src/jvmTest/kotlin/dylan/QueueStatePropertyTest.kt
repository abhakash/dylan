package dylan

import dylan.common.QueueAlgebra
import dylan.common.QueueInvariants
import dylan.common.QueueStateGenerator
import dylan.common.Violation
import dylan.model.PlayerState
import dylan.model.Repeat
import dylan.model.Song
import dylan.model.SongKey
import dylan.playback.ItemRef
import dylan.playback.ResumeSnapshot
import dylan.playback.restoreState
import dylan.support.testSong
import kotlinx.collections.immutable.toPersistentList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The queue-algebra property suite. Bodies live in `jvmTest` (bulk runs, several seeds) while the
 * generator and the predicates live in `commonTest`, so they execute on every target.
 *
 * Every state is pushed through `PlayerState.withQueueMutation` first. That is not a convenience:
 * `nextIndex` is a *maintained* field, so a hand-built state has none, and the property under test is
 * that the single algebra and the maintained field agree — for every shape a hand-built state can
 * have. The generator emits `shuffleOn == (shuffleOrder != null)` by construction, so the
 * `shuffleOn ⇒ shuffleOrder != null` invariant is asserted in its own right.
 */
class QueueStatePropertyTest {
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

    /**
     * The headline property, and the one the audit's §3.2 divergence failed: one algebra, one
     * `nextIndex`, no second implementation of "what plays next" to disagree with.
     */
    @Test
    fun nextIndexMatchesNextUpOverTheWholeMatrix() =
        assertInvariant(
            QueueInvariants.NEXT_INDEX_MATCHES_NEXT_UP,
            SEED_A,
        ) { QueueInvariants.nextIndexMatchesNextUp(maintain(it)) }

    /** The same walk, seen through the UI-facing accessor, over the whole 2×3 matrix. */
    @Test
    fun repeatAllWalkIsABijectionForNextUp() {
        val violations = mutableListOf<Violation>()
        QueueStateGenerator(SEED_F).take(ROUNDS).forEach { base ->
            val v = QueueInvariants.repeatAllIsBijection(base) { st, _ -> QueueAlgebra.nextUpIndex(maintain(st)) }
            v?.let(violations::add)
        }
        assertInvariant("repeatAllWalkIsABijection/PlayerState.nextUp", violations)
    }

    /**
     * The `restore()` defect: a shuffle that survived without a permutation is a queue that cannot
     * be navigated. Checked against the real `restoreState`, so the production decision is what is
     * being asserted — `withQueueMutation` throws on any other inconsistency.
     */
    @Test
    fun aRestoredShuffleAlwaysCarriesItsPermutation() {
        val random = Random(SEED_I)
        val generator = QueueStateGenerator(SEED_I)
        val violations = mutableListOf<Violation>()
        repeat(ROUNDS) {
            val snap = randomSnapshot(random, generator).copy(shuffleOn = true)
            val byKey = songsFor(snap).filter { it.key.songId != DROPPED_SONG }.associateBy { it.key }
            val st = restoreState(snap, byKey, 0L) ?: return@repeat
            if (st.shuffleOn && st.shuffleOrder == null) {
                violations +=
                    Violation(
                        QueueInvariants.RESTORED_SHUFFLE_IS_NAVIGABLE,
                        "shuffleOn survived a sanitize that dropped the permutation " +
                            "(items=${snap.items.size} order=${snap.order}); the restored queue is unnavigable",
                        st,
                    )
            }
        }
        assertInvariant(QueueInvariants.RESTORED_SHUFFLE_IS_NAVIGABLE, violations)
    }

    /**
     * The wrap spelled out with two tracks, so the property above has a hand-checkable input.
     * `queue=[a,b] index=1 shuffleOrder=[0,1] repeat=ALL`: the wrap target is slot 0, and the UI,
     * the engine window and the prefetcher must all see it.
     */
    @Test
    fun theShuffleRepeatAllWrapIsExplicit() {
        val s = twoTrackShuffledStateAtTheEndOfTheOrder()
        assertEquals(0, dylan.playback.QueueStateMachine.resolveAdvance(s, +1), "the algebra wraps on repeat ALL")
        assertEquals(0, QueueAlgebra.resolveAdvance(s, +1))
        assertEquals(0, s.nextIndex)
        assertEquals(0, QueueAlgebra.nextUpIndex(s), "the maintained accessor agrees with the algebra")
        assertEquals("a", s.nextUp?.key?.songId)
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
            val random = Random(seed)
            var violations = 0
            repeat(ROUNDS) {
                val s = maintain(generator.next())
                val v =
                    when (name) {
                        QueueInvariants.NEXT_INDEX_MATCHES_NEXT_UP -> QueueInvariants.nextIndexMatchesNextUp(s)
                        QueueInvariants.RESTORED_SHUFFLE_IS_NAVIGABLE -> {
                            val snap = randomSnapshot(random, generator).copy(shuffleOn = true)
                            val byKey = songsFor(snap).filter { it.key.songId != DROPPED_SONG }.associateBy { it.key }
                            val st = restoreState(snap, byKey, 0L)
                            if (st != null && st.shuffleOn && st.shuffleOrder == null) {
                                Violation(name, "restored shuffle without a permutation", st)
                            } else {
                                null
                            }
                        }
                        else ->
                            QueueInvariants.repeatAllIsBijection(s) { st, _ ->
                                QueueAlgebra.nextUpIndex(maintain(st))
                            }
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

    /** Every generated state must survive the validator and gain a `nextIndex` that agrees. */
    @Test
    fun everyGeneratedStateMaintainsItsNextIndex() {
        val mismatches = mutableListOf<Violation>()
        QueueStateGenerator(SEED_A).take(ROUNDS).forEach { raw ->
            val m = maintain(raw)
            if (m.nextIndex != QueueAlgebra.resolveAdvance(m, +1)) {
                mismatches += Violation("withQueueMutation agrees with the algebra", "nextIndex=${m.nextIndex}", m)
            }
        }
        assertInvariant("withQueueMutation agrees with the algebra", mismatches)
    }

    /** A `RemoveAt` of the playing row must not make Next skip a track. */
    @Test
    fun removingThePlayingRowDoesNotSkipTheNextOne() {
        val queue = listOf(testSong("a"), testSong("b"), testSong("c")).toPersistentList()
        val before = PlayerState().withQueueMutation(queue = queue, index = 1, current = queue[1])
        assertEquals(2, before.nextIndex)
        val after =
            before.withQueueMutation(
                queue = listOf(queue[0], queue[2]).toPersistentList(),
                index = 0,
                current = queue[1],
            )
        assertEquals(1, after.nextIndex, "next must be c, not the slot that now holds c under b's index")
        assertEquals("c", after.nextUp?.key?.songId)
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────

    /**
     * The one legitimate way a state reaches production: through the single assignment site. A
     * hand-built `PlayerState` has no `nextIndex`, so every property below runs on `this`.
     */
    private fun maintain(s: PlayerState): PlayerState = s.withQueueMutation()

    private fun twoTrackShuffledStateAtTheEndOfTheOrder(): PlayerState {
        val queue = listOf(testSong("a"), testSong("b")).toPersistentList()
        return PlayerState()
            .withQueueMutation(
                queue = queue,
                index = 1,
                current = queue[1],
                shuffleOn = true,
                shuffleOrder = kotlinx.collections.immutable.persistentListOf(0, 1),
                repeat = Repeat.ALL,
            ).copy(phase = dylan.model.Phase.Playing(queue[1].key))
    }

    private fun songsFor(snap: ResumeSnapshot): List<Song> =
        snap.items
            .map { ref -> testSong(ref.songId).copy(key = SongKey(ref.provider, ref.songId)) }

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
        QueueStateGenerator(seed).take(ROUNDS).forEach { check(maintain(it))?.let(violations::add) }
        assertInvariant(name, violations)
    }

    /**
     * Report, never swallow: the first few counterexamples are printed in full and the run fails.
     * No assertion is ever weakened to go green.
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
