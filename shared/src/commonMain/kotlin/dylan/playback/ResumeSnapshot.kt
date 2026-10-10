package dylan.playback

import dylan.model.Phase
import dylan.model.PlayerState
import dylan.model.Repeat
import dylan.model.Song
import dylan.model.SongKey
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ResumeSnapshot(
    val v: Int = 2,
    val items: List<ItemRef> = emptyList(),
    val index: Int = 0,
    val posMs: Long = 0,
    val shuffleOn: Boolean = false,
    val order: List<Int> = emptyList(),
    /**
     * When the position was captured. A sticky service restart is a pause-then-resume, so a
     * position older than this is stale by definition and the track starts at 0.
     */
    val playedAtMs: Long = 0L,
)

@Serializable
data class ItemRef(
    val provider: String,
    val songId: String,
)

private val json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

fun encodeSnapshot(s: ResumeSnapshot): String = json.encodeToString(ResumeSnapshot.serializer(), s)

fun decodeSnapshot(raw: String): ResumeSnapshot? =
    runCatching { json.decodeFromString(ResumeSnapshot.serializer(), raw) }
        .getOrNull()
        ?.takeIf { it.v == 2 }

data class RestoredQueue(
    val refs: List<ItemRef>,
    val index: Int,
    val order: List<Int>?,
)

fun sanitizeSnapshot(
    s: ResumeSnapshot,
    resolveRow: (ItemRef) -> Boolean,
): RestoredQueue? {
    if (s.items.isEmpty()) return null
    val kept = s.items.withIndex().filter { resolveRow(it.value) }
    if (kept.isEmpty()) return null
    val newIndex = s.index.coerceIn(0, kept.size - 1)
    val positionOfOriginal = HashMap<Int, Int>()
    kept.forEachIndexed { newPos, iv -> positionOfOriginal[iv.index] = newPos }
    var order: List<Int>? = null
    if (s.shuffleOn && s.order.isNotEmpty()) {
        val mapped = s.order.mapNotNull { originalIdx -> positionOfOriginal[originalIdx] }
        // A permutation must be a bijection over the filtered queue — duplicates would crown one
        // song as its own successor, exactly the stale-order fault class §9.7 bans.
        if (mapped.size == kept.size && mapped.toSet().size == kept.size) order = mapped
    }
    return RestoredQueue(kept.map { it.value }, newIndex, order)
}

/**
 * The state a snapshot restores to, or null when it cannot be navigated.
 *
 * `shuffleOn` follows the *surviving permutation*, not the artifact's flag: [sanitizeSnapshot]
 * drops a stale order, and `shuffleOn = true` over a null order is a queue with no next track, no
 * prefetch and "End of queue" on every press.
 *
 * Returns through [PlayerState.withQueueMutation], so the restored state is validated on the way in
 * and its `nextIndex` describes the queue it will actually walk.
 */
fun restoreState(
    snap: ResumeSnapshot,
    byKey: Map<SongKey, Song>,
    posMs: Long,
): PlayerState? {
    val restored = sanitizeSnapshot(snap) { ref -> byKey.containsKey(SongKey(ref.provider, ref.songId)) } ?: return null
    val ordered = restored.refs.mapNotNull { byKey[SongKey(it.provider, it.songId)] }
    if (ordered.isEmpty() || restored.index !in ordered.indices) return null
    return PlayerState().withQueueMutation(
        queue = ordered.toPersistentList(),
        index = restored.index,
        current = ordered[restored.index],
        phase = Phase.Paused(ordered[restored.index].key),
        posMs = posMs.coerceAtLeast(0L),
        shuffleOn = restored.order != null,
        shuffleOrder = restored.order?.toPersistentList(),
        repeat = Repeat.OFF,
    )
}
