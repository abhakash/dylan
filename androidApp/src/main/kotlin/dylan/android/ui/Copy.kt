package dylan.android.ui

import dylan.model.DylanFailure
import dylan.model.ErrorCode
import dylan.model.message

/**
 * Android-only strings that are not failure messages.
 *
 * Failure copy has one source: `DylanFailure.message()` in
 * `shared/src/commonMain/kotlin/dylan/model/Models.kt`, which the toasts and the player sheet
 * already route through. This file used to hold a second, literal copy of ten of those strings —
 * byte-identical, and nothing would have noticed them drifting apart (docs/issues.md #16). Nine of
 * the eleven constants had zero references and are gone; the two that survive resolve here instead
 * of repeating the text.
 */
object Copy {
    /** Toast on every row tap while the device is offline. Same source as every other failure. */
    val OFFLINE: String = DylanFailure(ErrorCode.OFFLINE).message()

    /**
     * Removal refused because the track is in use.
     *
     * The one string here that cannot route through [DylanFailure.message]: there is no
     * `ErrorCode.BUSY`, and this is not a failure at all — it guards the Android remove path, so it
     * has no business in the shared error taxonomy without a decision that belongs to the shared
     * model. Left as one literal in one file until that decision is made.
     */
    const val BUSY = "That track is playing or being saved — can't remove it."

    /**
     * The user-facing text for [code].
     *
     * A thin delegate rather than a table: the strings live in [DylanFailure.message], so the player
     * sheet and the toasts cannot drift apart. Kept as a function because the sheet has a bare
     * [ErrorCode] at the point of display, not a [DylanFailure].
     */
    fun forCode(code: ErrorCode): String = DylanFailure(code).message()
}
