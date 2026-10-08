package dylan.util

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Nothing to do, and that is the whole point of the file: Kotlin/Native's
 * `kotlin.native.concurrent.ThreadLocal` gives a genuine per-thread value, but publishing into it
 * needs a "a coroutine is resuming on this thread" hook, and coroutines' multiplatform
 * `ThreadContextElement` is still an open PR (#4208) with no Native implementation in 1.11.0.
 * So the iOS `slot` stays empty and `AppDispatchers.assert` reports "no lane" — which is what
 * `AppDispatchers.assertInContext` exists to answer instead.
 *
 * The element is still returned so that the shape of `AppDispatchers.on` is identical on every
 * target and so that [LaneTag] keeps its own key.
 */
internal actual fun lanePublication(
    lane: Lane,
    slot: LaneThreadLocal,
): CoroutineContext.Element = Publication

private object Publication : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<Publication>
}
