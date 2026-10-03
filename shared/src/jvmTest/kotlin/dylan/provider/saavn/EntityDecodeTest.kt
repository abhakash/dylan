package dylan.provider.saavn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Titles and subtitles arrive from the origin with HTML entities still in them. Observed on device:
 * "Rainy Day Women #12 & 35" rendered as `RAINY DAY WOMEN #12 &AMP; 35` on Home, because the entity
 * was never decoded and the UI uppercases titles for display.
 *
 * The decoder's contract is deliberately narrow — a fixed set plus numeric references — so the
 * interesting cases are the boundaries: case, unknown entities, and lone/paired surrogates.
 */
class EntityDecodeTest {
    @Test
    fun theAmpersandEntityTheOriginActuallyShipsIsDecoded() {
        assertEquals("Rainy Day Women #12 & 35", displayTitle("Rainy Day Women #12 &amp; 35"))
    }

    @Test
    fun entityNamesAreCaseInsensitiveBecauseTheUiUppercasesTitles() {
        // Home/Search render titles through `.uppercase()`, so a lowercase `&amp;` reaches the
        // screen as `&AMP;`. Decoding must not depend on which case the origin or the UI produced.
        assertEquals("A & B", displayTitle("A &AMP; B"))
        assertEquals("A & B", displayTitle("A &amp; B"))
        assertEquals("A & B", displayTitle("A &Amp; B"))
    }

    @Test
    fun everySupportedNamedEntityRoundTrips() {
        assertEquals("&", decodeEntities("&amp;"))
        assertEquals("<", decodeEntities("&lt;"))
        assertEquals(">", decodeEntities("&gt;"))
        assertEquals("\"", decodeEntities("&quot;"))
        assertEquals("'", decodeEntities("&apos;"))
        assertEquals(" ", decodeEntities("&nbsp;"))
    }

    @Test
    fun numericReferencesAreDecoded() {
        assertEquals("&", decodeEntities("&#38;"))
        assertEquals("&", decodeEntities("&#x26;"))
        assertEquals("é", decodeEntities("&#233;"))
        assertEquals("é", decodeEntities("&#xE9;"))
    }

    @Test
    fun anUnknownEntityIsLeftExactlyAsItWas() {
        // A title containing the literal text "&foo;" is prose, not markup. Rewriting it would lose
        // information, so the decoder must pass it through untouched — this is what keeps the fixed
        // entity set safe rather than lossy.
        assertEquals("Tom & Jerry; &foo; & bar", decodeEntities("Tom & Jerry; &foo; & bar"))
    }

    @Test
    fun anUnterminatedOrEmptyEntityIsNotRewritten() {
        assertEquals("R&D", decodeEntities("R&D"))
        assertEquals("a & b", decodeEntities("a & b"))
        assertEquals("&#;", decodeEntities("&#;"))
        assertEquals("&#xZZ;", decodeEntities("&#xZZ;"))
        assertEquals("&amp", decodeEntities("&amp"))
    }

    @Test
    fun aLoneSurrogateIsLeftAloneRatherThanEmitted() {
        // A lone surrogate is unrenderable, so the decoder refuses to produce one — and because the
        // contract is "unrecognised markup is passed through untouched", it leaves the entity as it
        // found it rather than silently deleting text the origin sent.
        assertEquals("a&#xD800;b", decodeEntities("a&#xD800;b"))
        assertEquals("a&#55296;b", decodeEntities("a&#55296;b"))
    }

    @Test
    fun aPairedSurrogateIsEmittedAsOneCodePoint() {
        // U+1F600 is stored as a surrogate pair in UTF-16 and must survive as the single character.
        assertEquals("\uD83D\uDE00", decodeEntities("&#x1F600;"))
    }

    @Test
    fun subtitlesGetTheSameTreatmentAndNullBecomesEmpty() {
        assertEquals("Goranansson & The Odyssey", displaySubtitle("Goranansson &amp; The Odyssey"))
        assertEquals("", displaySubtitle(null))
        assertEquals("", displaySubtitle("   "))
    }

    @Test
    fun decodingIsSkippedEntirelyWhenThereIsNoAmpersand() {
        // The common case must not pay for a regex over every card in a page.
        val plain = "Ordinary Title Without Entities 123"
        assertTrue(displayTitle(plain).startsWith("Ordinary"))
        assertEquals(plain, decodeEntities(plain))
    }

    @Test
    fun displayTitleStillTrimsSoTrailingSpacesStayFixed() {
        // The pre-existing normalisation this replaced: the payload ships trailing spaces.
        assertEquals("Awaara Bhanwara", displayTitle("Awaara Bhanwara   "))
    }

    @Test
    fun normTitleSeesTheDecodedFormSoTwoSpellingsAreOneRow() {
        // Identity has to agree with display, or the same track splits into two rows the moment one
        // of them is reached through a path that has not been decoded.
        assertEquals(normTitle(displayTitle("R&D")), normTitle(displayTitle("R&amp;D")))
    }
}
