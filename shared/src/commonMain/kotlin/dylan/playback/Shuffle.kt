package dylan.playback

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlin.random.Random

fun buildShuffleOrder(
    queueSize: Int,
    currentIndex: Int,
    random: Random = Random.Default,
): PersistentList<Int> {
    if (queueSize <= 0) return emptyList<Int>().toPersistentList()
    val rest = (0 until queueSize).filter { it != currentIndex }.toMutableList()
    for (i in rest.size - 1 downTo 1) {
        val j = random.nextInt(i + 1)
        val t = rest[i]
        rest[i] = rest[j]
        rest[j] = t
    }
    return (listOf(currentIndex.coerceIn(0, queueSize - 1)) + rest).toPersistentList()
}
