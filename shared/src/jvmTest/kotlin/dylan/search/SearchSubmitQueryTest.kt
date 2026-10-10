package dylan.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * F-24: which query the submit list shows.
 *
 * The infinite scroll is keyed on the submitted query and pages with it — that much was correct and
 * stays correct: paging a query the user is not looking at is exactly the defect to avoid. What was
 * wrong is that a user who *typed* a query never got to the submitted query at all: `submitted` only
 * moved in the keyboard's submit lambda, so the merged three-section list — the only list with a
 * "load more" — did not exist while the user was typing, and the typeahead they were looking at has
 * no paging by construction.
 */
class SearchSubmitQueryTest {
    private val minChars = 3

    /** A settled typed query becomes the submitted one, so the list that is on screen is the list that pages. */
    @Test
    fun aSettledTypedQueryIsSubmitted() {
        assertEquals("arijit", settledSubmitQuery("arijit", null, minChars))
    }

    /** Below the floor there is nothing to page, and the typeahead is the better affordance anyway. */
    @Test
    fun aTwoCharacterQueryStaysOnTheTypeahead() {
        assertNull(settledSubmitQuery("ar", null, minChars))
        assertNull(settledSubmitQuery("a", null, minChars))
        assertNull(settledSubmitQuery("", null, minChars))
        assertNull(settledSubmitQuery("   ", null, minChars))
    }

    /**
     * Re-submitting the query already on screen would blank the list and refetch it — the exact
     * regression the submit *epoch* was introduced to fix, arriving from a new direction.
     */
    @Test
    fun theQueryAlreadyShowingIsNotSubmittedAgain() {
        assertNull(settledSubmitQuery("arijit", "arijit", minChars))
        assertNull(settledSubmitQuery(" arijit ", "arijit", minChars))
        assertNull(settledSubmitQuery("arijit", " arijit ", minChars))
    }

    /** A query that has changed is a new list, and must be one. */
    @Test
    fun aChangedQueryIsANewQuery() {
        assertEquals("arijit singh", settledSubmitQuery("arijit singh", "arijit", minChars))
        assertEquals("ari", settledSubmitQuery("ari", "arijit singh", minChars))
    }

    /**
     * A cleared field goes back to the landing tab. It must not submit the empty query, or the
     * results list would render empty for a box the user has deliberately emptied.
     */
    @Test
    fun aClearedFieldDoesNotSubmitTheEmptyQuery() {
        assertNull(settledSubmitQuery("", "arijit", minChars))
    }

    /** The query the caller submits is the trimmed one, so the epoch and the fetch agree. */
    @Test
    fun theSubmittedQueryIsTrimmed() {
        assertEquals("arijit", settledSubmitQuery("  arijit  ", null, minChars))
    }
}
