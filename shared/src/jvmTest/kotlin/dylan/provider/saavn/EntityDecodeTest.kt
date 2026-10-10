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
    fun anUppercaseHexMarkerIsDecodedBecauseUppercasingATitleUppercasesItToo() {
        // Issue #17. The `x` in `&#x…;` is a *case-insensitive marker*, exactly as HTML specifies
        // and exactly as `&amp;`/`&AMP;` is — and the same `.uppercase()` that turned a shipped
        // `&amp;` into `&AMP;` turns a shipped `&#x41;` into `&#X41;`. The pattern's hex branch used
        // to be spelled `#x`, lowercase only, so `&#X41;` matched no branch at all and was left as
        // raw markup on screen. This is that string, decoded.
        assertEquals("A", decodeEntities("&#X41;"))
        assertEquals("A", decodeEntities("&#x41;"))
        assertEquals("Rainy Day Women #12 & 35", displayTitle("Rainy Day Women #12 &#X26; 35"))
    }

    @Test
    fun anUppercaseHexMarkerDecodesToASupplementaryCodePoint() {
        // The same defect on the two-character (surrogate pair) path. This one matters most: the old
        // pattern left a raw `&#X1F600;` in a title, which is the *worst* kind of leftover, because
        // `&` and `#` and the digits are all individually valid display text and the row looks like
        // it rendered a bug rather than having failed to decode.
        assertEquals("\uD83D\uDE00", decodeEntities("&#X1F600;"))
        assertEquals("\uD83D\uDE00", decodeEntities("&#x1F600;"))
    }

    @Test
    fun wideningTheHexMarkerDidNotWidenTheNamedEntitySet() {
        // The `#17` fix only widens the *numeric* marker. The fixed named-entity set is what makes
        // the decoder safe rather than lossy, so the "unrecognised markup is passed through
        // untouched" contract must still hold for a genuinely unknown name — in any case, and next
        // to a numeric reference that IS now decoded, which is the combination that regresses
        // silently if the pattern is ever loosened further.
        assertEquals("Tom & Jerry; &foo; & bar", decodeEntities("Tom & Jerry; &foo; & bar"))
        assertEquals("&foo;", decodeEntities("&foo;"))
        assertEquals("&FOO;", decodeEntities("&FOO;"))
        assertEquals("&fo o;", decodeEntities("&fo o;"))
        // Decoded neighbour proves the assertions above are not passing merely because nothing
        // decoded: `&foo;` stays verbatim while the `&amp;` beside it does not.
        assertEquals("&foo; &", decodeEntities("&foo; &amp;"))
    }

    @Test
    fun anUppercaseHexMarkerStillRejectsNonHexDigitsAndLoneSurrogates() {
        // The widened branch must not become a hole: a non-hex digit after an uppercase `X` is not a
        // character reference and stays verbatim, and a lone surrogate is still refused rather than
        // emitted. Both are the pre-existing guarantees, re-asserted on the case that did not exist
        // before this fix.
        assertEquals("&#XZZ;", decodeEntities("&#XZZ;"))
        // Both ends of the surrogate block, in HEX (0xD800..0xDFFF). The decimal lone-surrogate cases
        // are already covered above; these are the same guarantee on the branch the fix widened.
        assertEquals("a&#XD800;b", decodeEntities("a&#XD800;b"))
        assertEquals("a&#Xdfff;b", decodeEntities("a&#Xdfff;b"))
        // An uppercase marker with no digits at all is not an entity either.
        assertEquals("&#X;", decodeEntities("&#X;"))
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
