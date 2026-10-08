package dylan.common

import dylan.model.Phase
import dylan.model.PlayerState
import dylan.model.Repeat
import dylan.model.Song
import dylan.model.SongKey
import dylan.playback.ItemRef
import dylan.playback.ResumeSnapshot
import dylan.playback.decodeSnapshot
import dylan.playback.encodeSnapshot
import dylan.playback.sanitizeSnapshot
import kotlinx.collections.immutable.toPersistentList
import kotlin.random.Random

/**
 * Deterministic state generator + pure invariant functions for the queue algebra.
 *
 * This is a **generator and a set of predicates, not a test**: the assertions live in
 * `dylan.QueueStatePropertyTest` (jvmTest) so they can be run in bulk there, while everything
 * here stays in `commonTest` and therefore executes on every target.
 *
 * Why it exists: `PlayerState.nextUp` (`Models.kt:172-184`) and
 * `QueueStateMachine.resolveAdvance` (`QueueStateMachine.kt:17-27`) are two implementations of
 * "what plays next", and they already disagree. Two existing tests each covered one axis of the
 * 2×3 (shuffle × repeat) matrix, so neither could see the divergence. The audit's prescription
 * is one function, one invariant set, one property test — this is the property test half, written
 * against *both* implementations so the report names the failing one exactly.
 *
 * Every state the generator emits satisfies, by construction:
 *  - `queue.isEmpty() implies index == -1 and current == null`
 *  - `queue.isNotEmpty() implies queue[index] == current`
 *  - `shuffleOn implies shuffleOrder != null`
 *  - a non-null `shuffleOrder` is a permutation of `queue.indices`
 */
class QueueStateGenerator(
    seed: Long = DEFAULT_SEED,
    private val maxQueueSize: Int = 8,
) {
    private val random = Random(seed)

    private fun songAt(i: Int): Song =
        Song(
            key = SongKey("saavn", "s$i"),
            title = "s$i",
            subtitle = "",
            albumId = null,
            albumName = null,
            artUrl150 = "",
            artUrl500 = "",
            durationS = 100L + i,
            has320 = i % 2 == 0,
            resolveRef = "ref-s$i",
            permaToken = null,
        )

    /** A deterministic permutation of `0 until size`, current first (as `buildShuffleOrder` does). */
    fun permutation(
        size: Int,
        first: Int,
    ): List<Int> {
        val rest = (0 until size).filter { it != first }.toMutableList()
        for (i in rest.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val t = rest[i]
            rest[i] = rest[j]
            rest[j] = t
        }
        return listOf(first) + rest
    }

    fun next(): PlayerState {
        val size = random.nextInt(maxQueueSize + 1)
        if (size == 0) {
            return PlayerState(
                phase = Phase.Idle,
                current = null,
                queue = emptyList<Song>().toPersistentList(),
                index = -1,
            )
        }
        val queue = (0 until size).map(::songAt).toPersistentList()
        val index = random.nextInt(size)
        val shuffleOn = random.nextBoolean()
        val order = if (shuffleOn) permutation(size, index).toPersistentList() else null
        return PlayerState(
            phase = Phase.Playing(queue[index].key),
            current = queue[index],
            queue = queue,
            index = index,
            shuffleOn = shuffleOn,
            shuffleOrder = order,
            repeat = Repeat.entries[random.nextInt(Repeat.entries.size)],
        )
    }

    fun take(n: Int): List<PlayerState> = List(n) { next() }

    /** Every state with a fixed queue/order shape, so a failure is reproducible by seed + index. */
    companion object {
        const val DEFAULT_SEED: Long = 0x0D71A11
    }
}

/**
 * The reference semantics the audit prescribes: one pure, total function for "what plays next".
 *
 * [resolveAdvance] is `QueueStateMachine`'s existing implementation (the one the orchestrator
 * actually navigates with). [nextUpIndex] is the same question asked of `PlayerState.nextUp`, the
 * one the UI, the engine window and the prefetcher read. Where they disagree, the two callers
 * disagree with each other — which is the defect, not a detail of either function.
 */
object QueueAlgebra {
    fun resolveAdvance(
        state: PlayerState,
        dir: Int,
    ): Int? = dylan.playback.QueueStateMachine.resolveAdvance(state, dir)

    fun nextUpIndex(state: PlayerState): Int? {
        val song = state.nextUp ?: return null
        val at = state.queue.indexOf(song)
        return if (at < 0) null else at
    }

    fun isPermutationOfIndices(
        order: List<Int>?,
        size: Int,
    ): Boolean = order != null && order.size == size && order.toSet().size == size && order.all { it in 0 until size }

    /**
     * Verbatim transcription of `Orchestrator.handleIntent`'s `MoveWithinQueue` branch. Kept here
     * so the involution property can be checked over thousands of states without building a graph;
     * `IntentMatrixTest.moveWithinQueueRoundTripsAgainstTheRealOrchestrator` checks the same
     * property against the real thing, so a divergence between the two is itself a finding.
     */
    fun moveWithinQueue(
        queue: List<Song>,
        index: Int,
        from: Int,
        to: Int,
    ): Pair<List<Song>, Int> {
        if (from !in queue.indices || to !in queue.indices || from == to) return queue to index
        val q = queue.toMutableList()
        val item = q.removeAt(from)
        q.add(to, item)
        val idx =
            when {
                index == from -> to
                from < to && index in (from + 1)..to -> index - 1
                to < from && index in to until from -> index + 1
                else -> index
            }
        return q to idx
    }

    fun snapshotOf(state: PlayerState): ResumeSnapshot =
        ResumeSnapshot(
            items = state.queue.map { ItemRef(it.key.provider, it.key.songId) },
            index = state.index,
            posMs = 1_000L,
            shuffleOn = state.shuffleOn,
            order = state.shuffleOrder?.toList() ?: emptyList(),
        )
}

/** A failing invariant, with everything needed to reproduce it. */
data class Violation(
    val invariant: String,
    val detail: String,
    val state: PlayerState,
)

/**
 * The invariant set. Each predicate is pure and total so a failure names the invariant *and* the
 * counterexample, and so wave 3 can call them from the one place that assigns
 * queue/index/shuffleOrder/repeat.
 */
object QueueInvariants {
    const val NEXT_INDEX_MATCHES_NEXT_UP = "nextIndex(state, +1) == state.nextUp"
    const val TOTAL = "nextIndex returns t ⇒ t in queue.indices"
    const val REPEAT_ALL_IS_BIJECTION = "repeat == ALL ⇒ index → nextIndex(+1) is a permutation of order"
    const val SHUFFLE_IMPLIES_ORDER = "shuffleOn implies shuffleOrder != null"
    const val INDEX_TRACKS_CURRENT = "queue[index] == current"
    const val MOVE_IS_INVOLUTION = "MoveWithinQueue(from,to) then (to,from) restores queue and index"
    const val SNAPSHOT_ROUND_TRIP =
        "encode -> decode -> sanitize yields a permutation (or null) with index in refs.indices"
    const val RESTORED_SHUFFLE_IS_NAVIGABLE = "a restored shuffleOn queue carries a permutation"

    /** The headline property: the two implementations of "what plays next" must agree. */
    fun nextIndexMatchesNextUp(state: PlayerState): Violation? {
        val a = QueueAlgebra.resolveAdvance(state, +1)
        val b = QueueAlgebra.nextUpIndex(state)
        if (a == b) return null
        return Violation(
            NEXT_INDEX_MATCHES_NEXT_UP,
            "QueueStateMachine.resolveAdvance=${a?.let { "queue[$it]=${state.queue[it].key.songId}" } ?: "null"} " +
                "but PlayerState.nextUp=${b?.let { "queue[$it]=${state.queue[it].key.songId}" } ?: "null"} " +
                "(shuffleOn=${state.shuffleOn} order=${state.shuffleOrder} repeat=${state.repeat})",
            state,
        )
    }

    fun total(state: PlayerState): Violation? {
        for (dir in listOf(+1, -1)) {
            val t = QueueAlgebra.resolveAdvance(state, dir) ?: continue
            if (t !in state.queue.indices) {
                return Violation(
                    TOTAL,
                    "resolveAdvance(dir=$dir) returned $t for a queue of ${state.queue.size}",
                    state,
                )
            }
            val n = QueueAlgebra.nextUpIndex(state)
            if (n != null && n !in state.queue.indices) {
                return Violation(TOTAL, "nextUp resolved to $n for a queue of ${state.queue.size}", state)
            }
        }
        return null
    }

    fun shuffleImpliesOrder(state: PlayerState): Violation? =
        if (state.shuffleOn && state.shuffleOrder == null) {
            Violation(
                SHUFFLE_IMPLIES_ORDER,
                "shuffleOn with no permutation: the queue is permanently unnavigable",
                state,
            )
        } else {
            null
        }

    fun indexTracksCurrent(state: PlayerState): Violation? {
        if (state.queue.isEmpty()) {
            return if (state.index == -1) {
                null
            } else {
                Violation(
                    INDEX_TRACKS_CURRENT,
                    "empty queue with index=${state.index}",
                    state,
                )
            }
        }
        if (state.index !in state.queue.indices) {
            return Violation(INDEX_TRACKS_CURRENT, "index=${state.index} outside a queue of ${state.queue.size}", state)
        }
        val at = state.queue[state.index]
        return if (at == state.current) {
            null
        } else {
            Violation(
                INDEX_TRACKS_CURRENT,
                "queue[index]=${at.key.songId} but current=${state.current?.key?.songId}: " +
                    "nextUp points at the wrong song",
                state,
            )
        }
    }

    /**
     * The wrap property. Hold `order` and `repeat` fixed, vary only `index` over every element of
     * `order`, and the map `index → nextIndex(+1)` must be a permutation of `order` — every track
     * reached exactly once, including the wrap from the last element back to the first.
     *
     * Run against both implementations: the audit's claim is that the UI-facing one loses the
     * shuffle wrap, so this is where the difference is *provable* rather than inferred from one
     * hand-picked state.
     */
    fun repeatAllIsBijection(
        base: PlayerState,
        implementation: (PlayerState, Int) -> Int?,
    ): Violation? {
        if (base.queue.isEmpty()) return null
        val order = base.shuffleOrder?.toList() ?: base.queue.indices.toList()
        if (!QueueAlgebra.isPermutationOfIndices(order, base.queue.size)) return null
        val family = order.map { i -> base.copy(index = i, current = base.queue[i], repeat = Repeat.ALL) }
        val images = family.map { implementation(it, +1) }
        val missing = images.filter { it == null }
        val got = images.filterNotNull()
        val distinct = got.toSet()
        val repeated =
            got
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        if (distinct.size == order.size && missing.isEmpty() && repeated.isEmpty()) return null
        return Violation(
            REPEAT_ALL_IS_BIJECTION,
            "repeat=ALL order=$order walked to $images (nulls=$missing, repeated=$repeated): a repeat-ALL " +
                "queue with no successor shows no next track, shrinks the engine window to one item, stops " +
                "the prefetcher and leaves the wrap target evictable",
            base,
        )
    }

    fun moveIsInvolution(state: PlayerState): Violation? {
        val n = state.queue.size
        if (n < 2) return null
        for (from in 0 until n) {
            for (to in 0 until n) {
                val failure = moveRoundTripFailure(state, from, to) ?: continue
                return failure
            }
        }
        return null
    }

    private fun moveRoundTripFailure(
        state: PlayerState,
        from: Int,
        to: Int,
    ): Violation? {
        if (from == to) return null
        val (q1, i1) = QueueAlgebra.moveWithinQueue(state.queue, state.index, from, to)
        val (q2, i2) = QueueAlgebra.moveWithinQueue(q1, i1, to, from)
        if (q2 == state.queue && i2 == state.index) return null
        return Violation(
            MOVE_IS_INVOLUTION,
            "MoveWithinQueue($from,$to) then ($to,$from) left " +
                "queue=${q2.map { it.key.songId }} index=$i2, " +
                "expected queue=${state.queue.map { it.key.songId }} index=${state.index}",
            state,
        )
    }

    /**
     * `ResumeSnapshot.encode → decode → sanitize` must never hand the restorer a state it cannot
     * navigate: `order` is a permutation of `refs.indices` (or null) and `index` is in range.
     */
    fun snapshotRoundTrip(
        snapshot: ResumeSnapshot,
        resolvable: (ItemRef) -> Boolean,
    ): Violation? {
        val decoded = decodeSnapshot(encodeSnapshot(snapshot)) ?: return null
        if (decoded != snapshot) {
            return Violation(SNAPSHOT_ROUND_TRIP, "encode/decode lost data: $decoded != $snapshot", emptyState())
        }
        val out = sanitizeSnapshot(decoded, resolvable) ?: return null
        val n = out.refs.size
        if (out.index !in 0 until n) {
            return Violation(SNAPSHOT_ROUND_TRIP, "sanitize returned index=${out.index} for $n refs", emptyState())
        }
        if (out.order != null && !QueueAlgebra.isPermutationOfIndices(out.order, n)) {
            return Violation(
                SNAPSHOT_ROUND_TRIP,
                "sanitize returned order=${out.order} for $n refs, which is not a permutation of 0..${n - 1}",
                emptyState(),
            )
        }
        return null
    }

    /**
     * The `restore()` defect from the audit: `shuffleOn` survives a sanitize that had to drop a
     * stale permutation, leaving a permanently unnavigable queue with "End of queue" on every press.
     */
    fun restoredShuffleIsNavigable(
        snapshot: ResumeSnapshot,
        resolvable: (ItemRef) -> Boolean,
    ): Violation? {
        if (!snapshot.shuffleOn) return null
        val out = sanitizeSnapshot(snapshot, resolvable) ?: return null
        if (out.order != null) return null
        return Violation(
            RESTORED_SHUFFLE_IS_NAVIGABLE,
            "shuffleOn=true survived a sanitize that dropped the permutation " +
                "(items=${snapshot.items.size} order=${snapshot.order}); the restored queue is unnavigable",
            emptyState(),
        )
    }

    fun emptyState(): PlayerState =
        PlayerState(
            phase = Phase.Idle,
            current = null,
            queue = emptyList<Song>().toPersistentList(),
            index = -1,
        )

    /** Human-readable one-liner, used as the `@Ignore` reason for a currently-failing invariant. */
    fun render(v: Violation): String =
        "${v.invariant}: ${v.detail} | queue=${v.state.queue.map { it.key.songId }} index=${v.state.index} " +
            "shuffleOn=${v.state.shuffleOn} shuffleOrder=${v.state.shuffleOrder} repeat=${v.state.repeat}"
}
