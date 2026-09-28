package dylan.provider.saavn

import dylan.model.MiniEntity
import dylan.model.SongKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Identity and coercion — everything the mapper needs to decide *what a field means* before it
 * decides *what a card is*.
 *
 * Split out of `Mapper.kt` deliberately: these are total functions on single fields with no
 * knowledge of envelopes, and the rules they encode (perma tokens are not numeric ids, a title
 * needs normalising, an artist name and an artist token are independent) are the ones a fixture
 * regression is most likely to be about.
 */

/**
 * Coerces the live "320kbps" field across every shape the API has shipped: Boolean literal (true),
 * numeric (1/0) and strings ("true"/"false"/"1"/"0"). Anything unrecognized (null, absent, garbage)
 * is false — never throw on catalog data.
 */
internal fun coerceHas320(el: JsonElement?): Boolean {
    val p = el as? JsonPrimitive ?: return false
    p.booleanOrNull?.let { return it }
    p.longOrNull?.let { return it == 1L }
    p.doubleOrNull?.let { return it == 1.0 }
    return when (p.content.trim().lowercase()) {
        "true", "1" -> true
        else -> false
    }
}

// webapi.get&token= wants the PERMA TOKEN (perma_url last segment), not the numeric id
// [verified: HAR-2 e9NTAB1tQ9M_ / live 8Ps4qqBA6,Y_]; numeric ids yield a 200-empty shell.
// Stored normalized (last path segment) at map time so DB rows already carry the usable token;
// bare tokens (no '/') pass through as-is.
internal fun normalizePermaToken(permaUrl: String?): String? {
    val u = permaUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if ('/' !in u) return u
    return u
        .substringAfterLast('/')
        .substringBefore('?')
        .substringBefore('#')
        .takeIf { it.isNotBlank() } ?: u
}

internal fun permaAlbumToken(permaUrl: String?): String? {
    val u = permaUrl ?: return null
    val i = u.indexOf("/album/")
    if (i < 0) return null
    val seg = u.substring(i + "/album/".length).substringAfter('/').substringBefore('?')
    return seg.takeIf { it.isNotBlank() }
}

// Same rule for artists [verified live: token -f6Su9-0agk_ → full payload, numeric 610240 → empty shell]
internal fun permaArtistToken(permaUrl: String?): String? {
    val u = permaUrl ?: return null
    val i = u.indexOf("/artist/")
    if (i < 0) return null
    val seg = u.substring(i + "/artist/".length).substringAfter('/').substringBefore('?')
    return seg.takeIf { it.isNotBlank() }
}

// ── title normalisation ──────────────────────────────────────────────────────────────────────

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * Display/sort normalisation for a catalog title. The live payload ships titles with **trailing
 * spaces** (`fixtures/top_searches.json` → `"Toh Phir Aao Tera Mera Rishta "`), which survived into
 * row identity and made a cross-bucket duplicate look like two entities.
 */
internal fun normTitle(title: String): String = title.trim().replace(WHITESPACE_RUN, " ").lowercase()

/**
 * One dedupe key for every surface that lists minis — the submit sections, merged hits, the
 * suggestion list and the LazyColumn key.
 *
 * It is *namespaced*: `"79121261"` is a song id in one bucket and an album id in another, and the
 * old key mixed the three namespaces into one string slot, so a song and an album that share a
 * numeric id collided and a non-navigable row (`id == ""`) collided with every other such row.
 * Titles are normalised, so `"Awaara Bhanwara"` and `"Awaara Bhanwara "` are one row.
 */
fun dedupKey(m: MiniEntity): String {
    val (ns, ident) =
        when {
            m.songKey != null -> "s" to m.songKey.songId
            m.artistId != null -> "a" to m.artistId
            m.albumId != null -> "l" to m.albumId
            m.permaToken != null -> "p" to m.permaToken
            else -> "t" to m.title
        }
    return "$ns:${ident.trim()}:${normTitle(m.title)}"
}

/**
 * What a [MiniEntity] row actually *is*, and therefore whether tapping it can navigate.
 *
 * It is a sealed type computed from the row rather than a new field on [MiniEntity], because
 * `model/Models.kt` belongs to another wave. Previously there was no such type: `playlist`,
 * `show` and `episode` cards were flattened into rows with **no id at all**, so (a) they hit no
 * `when` branch on tap — a dead tap — and (b) the LazyColumn key degenerated to `title + ""`, and
 * two same-titled playlists crashed Compose with a duplicate key.
 */
sealed interface MiniKind {
    data class SongCard(
        val key: SongKey,
    ) : MiniKind

    data class AlbumCard(
        val id: String,
    ) : MiniKind

    data class ArtistCard(
        val id: String,
    ) : MiniKind

    /**
     * A catalog entity this app has no screen for (playlist, show, episode, …). It carries its raw
     * id so it is still *keyable* and still dedupes — it is just not navigable, and
     * [navigable] says so.
     */
    data class OtherCard(
        val type: String,
        val rawId: String,
    ) : MiniKind
}

val MiniEntity.kind: MiniKind
    get() =
        when {
            songKey != null -> MiniKind.SongCard(songKey)
            albumId != null -> MiniKind.AlbumCard(albumId)
            artistId != null -> MiniKind.ArtistCard(artistId)
            else -> MiniKind.OtherCard(type, permaToken ?: title)
        }

/** False for playlist/show/episode rows: they can be keyed and shown, but not opened. */
val MiniEntity.navigable: Boolean
    get() = songKey != null || albumId != null || artistId != null

/** Row key for a LazyColumn/SwiftUI list. Never empty, never shared across id namespaces. */
val MiniEntity.rowKey: String
    get() = dedupKey(this)

// ── artist identity ───────────────────────────────────────────────────────────────────────────

/**
 * The first primary artist of a card, with **name and navigation token resolved independently**.
 *
 * They used to be required together (`if (!name.isNullOrBlank() && !token.isNullOrBlank()) …`),
 * so a card whose `artistMap` entry carries no `perma_url` lost the artist *name* too and rendered
 * with a blank artist line and a disabled "go to artist". The numeric `id` in the same object is a
 * usable fallback identity, which is why it is read here.
 */
internal data class PrimaryArtist(
    val name: String?,
    /** perma token when present, else the numeric artist id — both are valid `artist(id)` inputs. */
    val token: String?,
)

internal fun primaryArtist(map: JsonElement?): PrimaryArtist? {
    val first =
        ((map as? JsonObject)?.get("primary_artists") as? JsonArray)
            ?.firstOrNull() as? JsonObject
            ?: return null
    val name = (first["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val token =
        permaArtistToken((first["perma_url"] as? JsonPrimitive)?.contentOrNull)
            ?: (first["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    return if (name == null && token == null) null else PrimaryArtist(name, token)
}

// ── duration ──────────────────────────────────────────────────────────────────────────────────

/**
 * `duration` in seconds, or **null when it is absent or unparseable**.
 *
 * It used to be `?: 0L`, silently. That 0 is not cosmetic: it is the multiplier for the download
 * engine's byte estimate and therefore for the whole wall-clock cap, so an unparsed duration
 * produces a `CORRUPT_SIZE` failure attributed to *storage* on a perfectly good track. Null is the
 * only honest representation, and [dylan.provider.durationKnown] is what callers should branch on.
 */
internal fun parseDurationS(el: JsonElement?): Long? {
    val raw = el.str()?.trim() ?: return null
    val s = raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() ?: return null
    return if (s > 0) s else null
}
