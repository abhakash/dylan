package dylan.playback

import dylan.model.PlayerState
import kotlinx.collections.immutable.PersistentList
import kotlin.random.Random

/**
 * The permutation builder lives in [PlayerState.newShuffleOrder] because the queue algebra needs it
 * to keep `shuffleOn ⇒ shuffleOrder != null` true at the one place the state is assigned. This is
 * the name the rest of the app uses.
 */
fun buildShuffleOrder(
    queueSize: Int,
    currentIndex: Int,
    random: Random = Random.Default,
): PersistentList<Int> = PlayerState.newShuffleOrder(queueSize, currentIndex, random)
