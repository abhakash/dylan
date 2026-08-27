package dylan.playback

import dylan.model.Phase
import dylan.model.PlayerState
import dylan.model.Repeat

internal object QueueStateMachine {
    fun transportable(phase: Phase): Boolean = phase is Phase.Playing || phase is Phase.Paused || phase is Phase.Ready

    fun resolveAdvance(
        state: PlayerState,
        dir: Int,
    ): Int? {
        if (state.queue.isEmpty()) return null
        return if (state.shuffleOn) {
            val order = state.shuffleOrder ?: return null
            val pos = order.indexOf(state.index)
            if (pos < 0) return null
            val nextPos = pos + dir
            when {
                nextPos in order.indices -> order[nextPos]
                dir > 0 && state.repeat == Repeat.ALL -> order.first()
                dir < 0 && state.repeat == Repeat.ALL -> order.last()
                else -> null
            }
        } else {
            val n = state.index + dir
            when {
                n in state.queue.indices -> n
                dir > 0 && state.repeat == Repeat.ALL -> 0
                dir < 0 && state.repeat == Repeat.ALL -> state.queue.lastIndex
                else -> null
            }
        }
    }
}
