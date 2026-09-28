package dylan.playback

import dylan.model.Phase
import dylan.model.PlayerState
import dylan.model.Repeat

internal object QueueStateMachine {
    fun transportable(phase: Phase): Boolean = phase is Phase.Playing || phase is Phase.Paused || phase is Phase.Ready

    /**
     * The single owner of "what plays next". Pure and total: it reads only the queue's four algebra
     * inputs, so it answers identically for a `PlayerState` built by hand and for one produced by
     * `PlayerState.withQueueMutation`, and it always returns a slot in `0 until queue.size`.
     */
    fun nextIndex(
        state: PlayerState,
        dir: Int,
    ): Int? = PlayerState.nextIndexIn(state.queue, state.index, state.shuffleOrder, state.shuffleOn, state.repeat, dir)

    /** Repeat-ONE pins the transport: both directions stay on the current index. */
    fun resolveAdvance(
        state: PlayerState,
        dir: Int,
    ): Int? = nextIndex(state, dir)
}
