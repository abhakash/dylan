package dylan.playback

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
