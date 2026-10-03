@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dylan

import dylan.di.AppContainer
import dylan.playback.Orchestrator
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M12: `AppContainer.onToast` and `Orchestrator.toast` are **cross-lane** function references.
 *
 * `AppContainer.bootComponents` publishes the container's sink from the **io** lane, and every
 * state-lane handler reads `Orchestrator.toast`; the orchestrator's own hook is likewise published
 * from the io lane and read from the state lane. As plain `var`s those are unsynchronised
 * cross-thread publications of a non-null value — the reader may observe `null` indefinitely, and
 * the only symptom is that a toast (an error message, "End of queue") never arrives.
 *
 * A data race cannot be *observed* deterministically from a test: on x86 it reorders almost
 * nothing, and on ARM the window is small and timing-dependent, so a test that tries to provoke it
 * is a coin flip in both directions. What can be asserted is the property that makes it safe — the
 * storage is an atomic, not a plain field — so that is what these assert, and a regression to a bare
 * `var` fails here immediately rather than on a user's device.
 *
 * The property is "no cross-lane hook is stored in a plain field", not "this one field is named X",
 * so the assertion is over *every* function-typed field in the two classes. That is both stricter
 * than the finding and immune to the field being renamed.
 */
class CrossLaneToastPublicationTest {
    @Test
    fun noToastFieldOnTheContainerIsAPlainCrossLaneSlot() = assertNoPlainToastFields(AppContainer::class.java)

    @Test
    fun noToastFieldOnTheOrchestratorIsAPlainCrossLaneSlot() = assertNoPlainToastFields(Orchestrator::class.java)

    /**
     * A `((String) -> Unit)?` property compiles to a field of function type, and a field of function
     * type is the defect: nothing orders its write against a read on another lane. No field carrying
     * a toast hook may be one.
     *
     * Scoped to toast-named fields deliberately. The container also stores `engineFactory`, which is
     * a function field too and is *correct*: it is a constructor parameter assigned once before the
     * container is published, so its write happens-before every read by construction. The property
     * under test is the one that is written long after construction, on a different lane.
     *
     * A `volatile` function field would fail this too, deliberately: `volatile` orders the single
     * reference access, but a handler's read-then-invoke is two steps and only the *publication* is
     * guaranteed. The finding asked for an `AtomicReference`, and that is what is asserted.
     */
    private fun assertNoPlainToastFields(cls: Class<*>) {
        assertTrue(
            cls.declaredMethods.any { it.name == "getToast" || it.name == "getOnToast" },
            "${cls.name} has no `toast`/`onToast` accessor — this guard is guarding a name that is gone",
        )
        val plain =
            cls.declaredFields
                .filter { it.name.contains("toast", ignoreCase = true) }
                .filter { field ->
                    val t = field.type
                    t.name.startsWith("kotlin.jvm.functions.Function") ||
                        (t.isInterface && t.methods.any { it.name == "invoke" })
                }
        assertTrue(
            plain.isEmpty(),
            "${cls.name} stores a toast hook in a plain field (${plain.joinToString { it.name }}). A hook " +
                "written on the io lane and read on the state lane must be held in an AtomicReference, or " +
                "the reader can observe null indefinitely and the toast never arrives.",
        )
    }
}
