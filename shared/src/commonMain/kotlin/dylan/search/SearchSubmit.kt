package dylan.search

/**
 * Which query the submit list is showing, given what is in the field and what is already submitted.
 *
 * Two paths reach the submit list — the keyboard's Enter, and a tap on a suggestion or a recent
 * search — and until F-24 those were the *only* two. While the user typed, `submitted` stayed null,
 * so `SearchResults` was not composed at all and the merged three-section list — the only list with
 * a "load more" — did not exist. The rows on screen were the typeahead's, and the typeahead has no
 * paging by construction: it is the WebSocket channel's answer to the text as typed, not a page of a
 * query. So the infinite scroll could not arm until the query was submitted, and a user who typed
 * saw a list that never grew with no way to know that pressing Enter was the difference.
 *
 * The rule this function pins is the one that fixes it **without** paging a query the user is not
 * looking at: the query in the field is the query the submit list shows, so the paged query and the
 * visible rows are always about the same text. The typeahead is not dropped either — it still runs
 * and still renders first, while the query is still moving — it simply stops being the last thing the
 * user is left with.
 *
 * Returning `null` means "leave the current list alone"; the caller must then do nothing at all.
 *
 * @param submitted the query the submit list is showing today, or null if it is showing the
 *   typeahead or the landing tab.
 * @param minChars below this the query is too short to be worth paging, and the typeahead — which is
 *   the better affordance for two characters — keeps the screen.
 */
fun settledSubmitQuery(
    typed: String,
    submitted: String?,
    minChars: Int,
): String? {
    val q = typed.trim()
    if (q.length < minChars) return null
    if (q == submitted?.trim()) return null
    return q
}
