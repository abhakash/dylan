package dylan.provider.saavn

import dylan.provider.Drift
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * The envelope, before any card in it is looked at.
 *
 * Split out of `Mapper.kt` deliberately: these three answers — *is this body JSON at all, does it
 * carry cards, is it an error instead of a page* — are properties of the response envelope, and
 * they are the same answer for every endpoint that shares the `{total,start,results}` shape. What
 * each card *means* is the mapper's business; whether there are cards to mean anything about is
 * this file's.
 */

/**
 * The drift for an error envelope, or null when this body is a page.
 *
 * A body carrying **both** `results` and `error` is treated as a page: the cards are real, and a page
 * that also complains is not an empty section.
 */
internal fun JsonObject.originErrorDrift(endpoint: String): Drift? {
    if (this["results"] is JsonArray) return null
    val err = this["error"] as? JsonObject ?: return null
    val code = err["code"].str()?.trim()?.takeIf { it.isNotEmpty() } ?: "?"
    val msg =
        err["msg"]
            .str()
            ?.trim()
            ?.take(DRIFT_DETAIL_CHARS)
            .orEmpty()
    return Drift(endpoint, ORIGIN_ERROR, "code=$code msg=$msg")
}

/** The `results` array, or nothing: a body that ships a different shape carries no cards. */
internal fun JsonObject.resultsArray(): List<JsonElement> = (this["results"] as? JsonArray).orEmpty()

/** A body that is not a JSON object: a drift, and an empty page — never a bare empty list. */
internal fun bodyNotJson(
    endpoint: String,
    text: String,
): List<Drift> = listOf(Drift(endpoint, BODY_NOT_JSON, text.take(DRIFT_DETAIL_CHARS)))

/**
 * The origin answered `{"error":…}` instead of a page.
 *
 * Added for issue #9: this body is valid JSON, so before it existed an empty section and a failed
 * request were the same value, and there was nothing to log. It is drift rather than an `Err`
 * because the decode layer has no error channel — `Rows.drift` is the one way a page can say that
 * it is not what it looks like.
 */
internal const val ORIGIN_ERROR = "ORIGIN_ERROR"
