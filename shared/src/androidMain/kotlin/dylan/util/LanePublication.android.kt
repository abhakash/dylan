package dylan.util

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal actual fun lanePublication(
    lane: Lane,
    slot: LaneThreadLocal,
): CoroutineContext.Element = Publication(lane, slot)

/**
 * Publishes [lane] for the duration of every dispatch and restores the previous value afterwards.
 * `ThreadLocalElement` would be the ready-made implementation, but its `threadLocal()` hook is not
 * part of the common API either, so this is written out longhand.
 */
private class Publication(
    private val lane: Lane,
    private val slot: LaneThreadLocal,
) : AbstractCoroutineContextElement(Key),
    ThreadContextElement<Lane?> {
    companion object Key : CoroutineContext.Key<Publication>

    override fun updateThreadContext(context: CoroutineContext): Lane? {
        val previous = slot.get()
        slot.set(lane)
        return previous
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Lane?,
    ) {
        slot.set(oldState)
    }
}
