package dylan.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.random.Random

data class SongKey(
    val provider: String,
    val songId: String,
) {
    /**
     * Engine itemId for one cached rendition of this key, stamped with the play generation that
     * asked for it. The generation is part of the identity, so an engine event carrying a stale
     * one is detectable by one string comparison instead of a prefix scan over the queue.
     */
    fun itemId(
        generation: Long,
        bits: Int,
    ) = "$GEN_PREFIX$generation:$provider:$songId:$bits"

    companion object {
        const val GEN_PREFIX = "g"
        const val GEN_SEP = ":"
    }
}

enum class Quality(
    val bits: Int,
    val bps: Long,
) {
    BITRATE_128(128, 20_400),
    BITRATE_320(320, 40_400),
    ;

    companion object {
        fun of(bits: Int) = if (bits >= 320) BITRATE_320 else BITRATE_128
    }
}

data class Song(
    val key: SongKey,
    val title: String,
    val subtitle: String,
    val albumId: String?,
    val albumName: String?,
    val artUrl150: String,
    val artUrl500: String,
    val durationS: Long,
    val has320: Boolean,
    val resolveRef: String?,
    val permaToken: String?,
    val artistName: String? = null,
    val artistToken: String? = null,
)

data class Artist(
    val id: String,
    val name: String,
    val subtitle: String?,
    val artUrl150: String,
    val artUrl500: String,
    val songs: List<Song>,
)

data class Album(
    val id: String,
    val title: String,
    val subtitle: String?,
    val artUrl150: String,
    val artUrl500: String,
    val year: String?,
    val songs: List<Song>,
)

data class MiniEntity(
    val songKey: SongKey?,
    val albumId: String?,
    val title: String,
    val subtitle: String,
    val type: String,
    val image: String,
    val permaToken: String?,
    val artistId: String? = null,
)

data class Paged<T>(
    val items: List<T>,
    val total: Long,
    val page: Int,
)

data class SearchSections(
    val topSearches: List<MiniEntity>,
    val trending: List<MiniEntity>,
)

data class HomeSection(
    val title: String,
    val items: List<MiniEntity>,
)

data class HomeFeed(
    val sections: List<HomeSection>,
)

enum class Repeat { OFF, ALL, ONE }

enum class ErrorCode {
    OFFLINE,
    NOT_FOUND,
    NO_SOURCE,
    NOT_CACHEABLE,
    EXPIRED,
    FORBIDDEN_REGION,
    NETWORK,
    NETWORK_TIMEOUT,
    STORAGE,
    CORRUPT_SIZE,
    CORRUPT_CONTAINER,
    UNSUPPORTED,
    RATE_LIMITED,
    RESOLVE_LIMIT,
    TOO_MANY_FAILURES,
    DRIFT,
}

data class DylanFailure(
    val code: ErrorCode,
    val songKey: SongKey? = null,
    val detail: String? = null,
)

/** Single user-facing copy source — Android toasts, NP sheet, and iOS failureMessage all route here. */
fun DylanFailure.message(): String =
    when (code) {
        ErrorCode.OFFLINE -> "You're offline — saved music still plays."
        ErrorCode.NOT_FOUND -> "This track seems unavailable."
        ErrorCode.NO_SOURCE -> "No playable source for this track."
        ErrorCode.NOT_CACHEABLE, ErrorCode.UNSUPPORTED -> "This track can't be saved for offline play."
        ErrorCode.EXPIRED, ErrorCode.RESOLVE_LIMIT -> "Couldn't refresh this track. Try again."
        ErrorCode.FORBIDDEN_REGION -> "Not available in your region."
        ErrorCode.NETWORK, ErrorCode.NETWORK_TIMEOUT, ErrorCode.DRIFT -> "Check your connection and try again."
        ErrorCode.STORAGE -> "Not enough space. Free up storage or clear cache."
        ErrorCode.CORRUPT_SIZE, ErrorCode.CORRUPT_CONTAINER -> "That file didn't download cleanly. Retrying…"
        ErrorCode.RATE_LIMITED -> "Slow down a moment…"
        ErrorCode.TOO_MANY_FAILURES -> "Several tracks failed to load. Check your connection."
    }

sealed interface Phase {
    data object Idle : Phase

    data class Resolving(
        val key: SongKey,
    ) : Phase

    data class Downloading(
        val key: SongKey,
    ) : Phase

    data class Ready(
        val key: SongKey,
    ) : Phase

    data class Playing(
        val key: SongKey,
    ) : Phase

    data class Paused(
        val key: SongKey,
    ) : Phase

    data class Error(
        val failure: DylanFailure,
    ) : Phase
}

/** Illegal state of the queue algebra. A throw, not a log line: these are programmer errors. */
class QueueInvariantViolation(
    message: String,
) : IllegalStateException(message)

/**
 * The playing position, owned by the state machine.
 *
 * It is authoritative only in the phases where a transport exists ([Phase.Ready], [Phase.Playing],
 * [Phase.Paused], [Phase.Error]) and is a *seed* elsewhere: [Phase.Resolving] and [Phase.Downloading]
 * carry the position the track will be prepared at. That is what lets a skip keep the abandoned
 * download's position, a restore carry a position across a process restart, and a rebuffer
 * ([EngineEvent.Prepared]) leave the position untouched.
 */
data class PlayerState(
    val phase: Phase = Phase.Idle,
    val current: Song? = null,
    val queue: PersistentList<Song> = persistentListOf(),
    /** Playing order position of [current] in [queue], or -1 when the playing row was removed. */
    val index: Int = -1,
    val shuffleOn: Boolean = false,
    val shuffleOrder: PersistentList<Int>? = null,
    val repeat: Repeat = Repeat.OFF,
    /** Maintained: what plays after [current], including the repeat-ALL wrap. `null` = nothing follows. */
    val nextIndex: Int? = null,
    /** Position the engine is at, or the position the next window will be prepared at. */
    val posMs: Long = 0L,
    /**
     * True when this state was produced by [withQueueMutation]. A state assembled by a bare
     * constructor has `nextIndex == null` by construction, so an un-maintained field is visible
     * rather than silently wrong.
     */
    val maintained: Boolean = false,
) {
    val nextUp: Song? get() = nextIndex?.let { queue.getOrNull(it) }

    /** Position to resume the item at [at] at, for the window being handed to the engine. */
    fun resumePosMsFor(at: Int): Long = if (at == index) posMs else 0L

    /** Keeps the order being walked across a re-anchor; `null` means the caller must rebuild it. */
    private fun stableOrder(
        order: PersistentList<Int>?,
        queue: PersistentList<Song>,
        index: Int,
    ): PersistentList<Int>? = order?.takeIf { it.size == queue.size && isPermutationOf(it, queue.size) && index in it }

    /** The only assignment site for the queue's shape and the phase that reads it. */
    fun withQueueMutation(
        queue: PersistentList<Song> = this.queue,
        index: Int = this.index,
        current: Song? = this.current,
        shuffleOn: Boolean = this.shuffleOn,
        shuffleOrder: PersistentList<Int>? = this.shuffleOrder,
        repeat: Repeat = this.repeat,
        phase: Phase = this.phase,
        posMs: Long? = null,
        remap: Boolean = false,
    ): PlayerState {
        val anchored =
            if (!shuffleOn || queue.isEmpty()) {
                null
            } else if (remap) {
                // An edit that dropped or added a row invalidates the order; a fresh one is anchored
                // so the item the user is on keeps playing next-in-order, not "slot zero".
                shuffleOrder?.takeIf { it.size == queue.size }
            } else {
                stableOrder(shuffleOrder, queue, index) ?: newShuffleOrder(queue.size, index)
            }
        val out =
            PlayerState(
                phase = phase,
                current = current,
                queue = queue,
                index = index,
                shuffleOn = shuffleOn,
                shuffleOrder = anchored,
                repeat = repeat,
                nextIndex = nextIndexIn(queue, index, anchored, shuffleOn, repeat, FORWARD),
                posMs = posMs ?: if (index == this.index) this.posMs else 0L,
                maintained = true,
            )
        validate(out)
        return out
    }

    private fun isPermutationOf(
        order: List<Int>,
        size: Int,
    ): Boolean {
        if (order.size != size || size == 0) return false
        var seen = 0L
        for (i in order) {
            if (i < 0 || i >= size) return false
            val bit = 1L shl i
            if (seen and bit != 0L) return false
            seen = seen or bit
        }
        return true
    }

    companion object {
        /** `+1` is the maintained `nextIndex` direction; `-1` is the Previous direction. */
        const val FORWARD = 1

        /**
         * Allocation ceiling for the permutation check. Above it, `1L shl i` stops being a bitset;
         * large queues fall back to a `HashSet` membership test, which the algebra treats
         * identically.
         */
        const val PERMUTATION_BITSET_LIMIT = 64

        /** A shuffled playback order over `0 until size`, anchored so [currentIndex] plays first. */
        fun newShuffleOrder(
            size: Int,
            currentIndex: Int,
            random: Random = Random.Default,
        ): PersistentList<Int> {
            if (size <= 0) return persistentListOf()
            val anchor = currentIndex.coerceIn(0, size - 1)
            val rest = ArrayList<Int>(size - 1)
            for (i in 0 until size) {
                if (i != anchor) rest.add(i)
            }
            for (i in rest.size - 1 downTo 1) {
                val j = random.nextInt(i + 1)
                val t = rest[i]
                rest[i] = rest[j]
                rest[j] = t
            }
            return (listOf(anchor) + rest).toPersistentList()
        }

        /**
         * The one implementation of "what plays next". Pure and total: it reads only its six
         * arguments, returns a slot in `0 until queue.size` or `null`, and cannot be affected by a
         * [PlayerState] that skipped [withQueueMutation]. `dir` is `+1` for "what plays next".
         */
        fun nextIndexIn(
            queue: List<Song>,
            index: Int,
            shuffleOrder: List<Int>?,
            shuffleOn: Boolean,
            repeat: Repeat,
            dir: Int,
        ): Int? {
            if (queue.isEmpty() || index !in queue.indices) return null
            if (repeat == Repeat.ONE) return index
            if (shuffleOn) {
                val order = shuffleOrder
                if (order == null || order.size != queue.size) return null
                val at = order.indexOf(index)
                if (at < 0) return null
                val target = at + dir
                return when {
                    target in order.indices -> order[target]
                    repeat == Repeat.ALL -> if (dir > 0) order[0] else order[order.size - 1]
                    else -> null
                }
            }
            val target = index + dir
            return when {
                target in queue.indices -> target
                repeat == Repeat.ALL -> if (dir > 0) 0 else queue.lastIndex
                else -> null
            }
        }

        /** Every rule a maintained [PlayerState] must satisfy. Called from [withQueueMutation]. */
        fun validate(s: PlayerState) {
            val n = s.queue.size
            if (s.index < -1 || s.index > s.queue.lastIndex) {
                throw QueueInvariantViolation("index=${s.index} outside -1..${s.queue.lastIndex} for a queue of $n")
            }
            if (s.queue.isEmpty() != (s.index == -1)) {
                throw QueueInvariantViolation("queue of $n paired with index=${s.index}")
            }
            if (s.shuffleOn != (s.shuffleOrder != null)) {
                throw QueueInvariantViolation("shuffleOn=${s.shuffleOn} but shuffleOrder=${s.shuffleOrder != null}")
            }
            s.shuffleOrder?.let { order ->
                if (order.size != n) throw QueueInvariantViolation("shuffleOrder of ${order.size} over a queue of $n")
                if (n > 0) {
                    val distinct = if (n <= PERMUTATION_BITSET_LIMIT) bitsetDistinct(order, n) else order.toSet().size == n
                    if (!distinct) throw QueueInvariantViolation("shuffleOrder=$order is not a permutation of 0..${n - 1}")
                    if (s.index >= 0 && s.index !in order) {
                        throw QueueInvariantViolation("index=${s.index} absent from shuffleOrder=$order")
                    }
                }
            }
            val next = s.nextIndex
            if (next != null && next !in 0 until n) {
                throw QueueInvariantViolation("nextIndex=$next outside 0..${n - 1}")
            }
            if (s.repeat != Repeat.ONE && next == s.index && s.index in s.queue.indices) {
                throw QueueInvariantViolation("nextIndex=$next == index under repeat=${s.repeat}")
            }
            if (s.repeat == Repeat.ONE && next != s.index) {
                throw QueueInvariantViolation("repeat ONE with nextIndex=$next != index=${s.index}")
            }
        }

        private fun bitsetDistinct(
            order: List<Int>,
            n: Int,
        ): Boolean {
            var seen = 0L
            for (i in order) {
                if (i < 0 || i >= n) return false
                val bit = 1L shl i
                if (seen and bit != 0L) return false
                seen = seen or bit
            }
            return true
        }
    }
}
