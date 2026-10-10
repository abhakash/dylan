package dylan.util

import dylan.model.Song
import dylan.model.SongKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The recording identity contract.
 *
 * This is the file that pins the normalisation, because `androidApp` — where
 * `dylan.android.ui.components.recordingKey` lives — has no test source set and cannot get one
 * without a dependency change to its build script. The Android file delegates to these same
 * functions, so what is asserted here is asserted of the callsite too.
 *
 * The two halves of the contract are deliberately asserted against each other:
 *
 *  * it must **still collide** for the case it exists for — one recording served under two song ids
 *    (identical MD5 across the album and the search entry), which is why lists dedupe on this
 *    triple instead of on `SongKey` alone; and
 *  * it must **stop colliding** for every script it used to destroy. The old fold kept only
 *    `[a-z0-9 ]` and ran after `lowercase()`, so a Devanagari title and a Devanagari artist both
 *    became `""` and the key degenerated to `"|" + durationS`; two unrelated Hindi songs of equal
 *    duration were one identity, and HomeScreen filtered the favourited one out of *Your
 *    favourites* to obey that.
 */
class RecordingKeyTest {
    private fun song(
        title: String,
        artist: String,
        durationS: Long,
        songId: String = "1",
        provider: String = "saavn",
        subtitle: String = artist,
    ): Song =
        Song(
            key = SongKey(provider, songId),
            title = title,
            subtitle = subtitle,
            albumId = null,
            albumName = null,
            artUrl150 = "",
            artUrl500 = "",
            durationS = durationS,
            has320 = false,
            resolveRef = null,
            permaToken = null,
            artistName = artist,
        )

    // ── the case the identity exists for ────────────────────────────────────────────────────

    /** The one collision the whole mechanism is *for*: one recording, two song ids, one key. */
    @Test
    fun theSameRecordingUnderTwoDifferentSongIdsKeysToTheSameIdentity() {
        val albumEntry = song(title = "Tum Hi Ho", artist = "Arijit Singh", durationS = 269, songId = "11111")
        val searchEntry = song(title = "Tum Hi Ho", artist = "Arijit Singh", durationS = 269, songId = "99GkX2Y")

        assertEquals(
            searchEntry.recordingKey(),
            albumEntry.recordingKey(),
            "the album and the search entry for one recording must be one identity",
        )
    }

    /** Case, punctuation, entity markup and space runs are all folds, not identity. */
    @Test
    fun latinFoldingIsUnchangedForAsciiTitles() {
        val plain = song(title = "Zara Sa", artist = "Ankit Tiwari", durationS = 250)
        // Case, padding and punctuation only — no different words. The point is that the *decoration*
        // the origin adds around a title does not create a second identity, so anything that folds to
        // the same text must land on the same key.
        val folded = song(title = "  ZARA   SA:  ", artist = " Ankit  Tiwari ", durationS = 250)

        assertEquals(
            "zara sa|ankit tiwari",
            recordingField(plain.title).let { "$it|${recordingField(plain.recordingArtistColumn())}" },
        )
        assertEquals(
            "zara sa|ankit tiwari",
            "${recordingField(folded.title)}|${recordingField(folded.recordingArtistColumn())}",
        )
        assertEquals(plain.recordingKey(), folded.recordingKey())
    }

    /** Two different Latin-script songs are two identities — the old ASCII fold also got this right. */
    @Test
    fun twoDifferentLatinScriptTitlesStillProduceDifferentKeys() {
        val tumHiHo = song(title = "Tum Hi Ho", artist = "Arijit Singh", durationS = 269)
        val sunnRahaHai = song(title = "Sunn Raha Hai", artist = "Ankit Tiwari", durationS = 269)

        assertNotEquals(tumHiHo.recordingKey(), sunnRahaHai.recordingKey())
    }

    /** Titles that differ only in their diacritics were one key under the ASCII-only fold. */
    @Test
    fun twoLatinTitlesThatDifferOnlyInTheirDiacriticsNoLongerCollide() {
        val withMacron = song(title = "Dil Ibādat", artist = "KK", durationS = 294)
        val without = song(title = "Dil Ibadat", artist = "KK", durationS = 294)

        assertNotEquals(withMacron.recordingKey(), without.recordingKey())
    }

    // ── the case it used to destroy ────────────────────────────────────────────────────────

    /**
     * The live defect. A Devanagari title used to fold to `""` along with its artist, so the key
     * was `"|242"` — and any other Hindi song of the same length was the same key.
     */
    @Test
    fun aDevanagariTitleKeysToSomethingThatCarriesTheTitleItself() {
        val zaraSa = song(title = "ज़रा सा", artist = "अलका याग्निक", durationS = 242)

        val key = zaraSa.recordingKey()

        assertTrue(key.isNotBlank(), "a Devanagari song must not key to \"\"")
        assertTrue(key != "|242", "the title and artist must not both fold away: \"$key\"")
    }

    /** Same length, same script, different song: two identities. */
    @Test
    fun twoDevanagariSongsOfTheSameDurationDoNotCollide() {
        val zaraSa = song(title = "ज़रा सा", artist = "अलका याग्निक", durationS = 242)
        val shriRam = song(title = "श्री राम जानकी", artist = "सुजाता दास", durationS = 242)

        assertNotEquals(zaraSa.recordingKey(), shriRam.recordingKey())
    }

    /** The same collapse for a right-to-left script, and for an artist, not only a title. */
    @Test
    fun twoArabicTitlesOfTheSameDurationDoNotCollide() {
        val first = song(title = "عبد الرحمن", artist = "طلال مداح", durationS = 300)
        val second = song(title = "العندليب", artist = "طلال مداح", durationS = 300)

        assertNotEquals(first.recordingKey(), second.recordingKey())
    }

    /** Marks survive, so a Hindi title differing only in its matra is still distinguishable. */
    @Test
    fun aDevanagariTitleThatDiffersOnlyInItsMatraIsStillDistinguishable() {
        val shortAa = song(title = "सा", artist = "अलका याग्निक", durationS = 242)
        val longEe = song(title = "सी", artist = "अलका याग्निक", durationS = 242)

        assertNotEquals(shortAa.recordingKey(), longEe.recordingKey())
    }

    /** CJK: no combining forms, but the same fold must keep the characters. */
    @Test
    fun twoCJKTitlesOfTheSameDurationDoNotCollide() {
        val first = song(title = "月亮代表我的心", artist = "邓丽君", durationS = 200)
        val second = song(title = "甜蜜蜜", artist = "邓丽君", durationS = 200)

        assertNotEquals(first.recordingKey(), second.recordingKey())
    }

    // ── the list helpers that HomeScreen actually calls ───────────────────────────────────

    /** `distinctRecordings` must not drop one of two equal-length Hindi songs. */
    @Test
    fun distinctRecordingsKeepsBothOfTwoNonLatinSongsOfEqualDuration() {
        val catalog = listOf(song(title = "ज़रा सा", artist = "अलका याग्निक", durationS = 242))

        assertTrue(catalog.distinctRecordings().all { it.title == "ज़रा सा" })
    }

    /**
     * The favourites path. `favoritesShown = favorites.filter { !jumpBack.containsRecording(it) }`:
     * under the old fold a favourite collided with an unrelated *Jump back in* row of equal
     * duration and disappeared from *Your favourites*.
     */
    @Test
    fun containsRecordingDoesNotReportAMatchBetweenTwoUnrelatedNonLatinSongs() {
        val favourite = song(title = "ज़रा सा", artist = "अलका याग्निक", durationS = 242)
        val jumpBack = listOf(song(title = "श्री राम जानकी", artist = "सुजाता दास", durationS = 242))

        assertFalse(jumpBack.containsRecording(favourite))
    }

    /** The direct analogue for Latin rows, which is what the path has to keep doing. */
    @Test
    fun containsRecordingStillReportsAGenuineMatchForTheSameRecording() {
        val favourite = song(title = "Tum Hi Ho", artist = "Arijit Singh", durationS = 269, songId = "99GkX2Y")
        val jumpBack = listOf(song(title = "Tum Hi Ho", artist = "Arijit Singh", durationS = 269, songId = "11111"))

        assertTrue(jumpBack.containsRecording(favourite))
    }

    /** A mixed list: both the Latin and the non-Latin rule hold across one list. */
    @Test
    fun aMixedLatinAndNonLatinListDedupesOnlyTheSameRecordings() {
        val catalogue =
            listOf(
                song(title = "Tum Hi Ho", artist = "Arijit Singh", durationS = 269, songId = "1"),
                song(title = "Sunn Raha Hai", artist = "Ankit Tiwari", durationS = 269, songId = "2"),
                song(title = "Tum Hi Ho", artist = "Arijit Singh", durationS = 269, songId = "3"),
                song(title = "ज़रा सा", artist = "अलका याग्निक", durationS = 242, songId = "4"),
                song(title = "श्री राम जानकी", artist = "सुजाता दास", durationS = 242, songId = "5"),
            )

        val distinct = catalogue.distinctRecordings()

        assertTrue(distinct.all { it.key.songId != "3" }, "the duplicate album/search pair must collapse")
        assertTrue(distinct.all { it.key.songId != "3" })
        assertTrue(distinct.map { it.title }.contains("ज़रा सा"), "the Hindi rows must survive")
        assertTrue(distinct.map { it.title }.contains("श्री राम जानकी"), "both Hindi rows must survive")
    }

    /** The separator cannot be reached by either folded field, so the three columns cannot merge. */
    @Test
    fun theSeparatorCannotBeReachedFromEitherFoldedField() {
        val title = "Tum Hi Ho | Extra"
        val artist = "Arijit Singh"

        assertFalse(recordingField(title).contains("|"))
    }
}

/** `recordingKey` reads `artistName ?: subtitle`; spell the fallback once. */
private fun Song.recordingArtistColumn(): String = artistName ?: subtitle
