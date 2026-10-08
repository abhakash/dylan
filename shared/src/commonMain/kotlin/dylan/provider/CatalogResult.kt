package dylan.provider

import dylan.model.ErrorCode

/**
 * One catalog card the mapper could not turn into a domain object, with the reason.
 *
 * A dropped card is a *contract* fact, not noise: `CatalogResult.Ok` carries these so a page
 * that lost 1 of 20 rows is observably different from a page that genuinely returned 0 rows.
 * Before this type existed the two were byte-identical (`null` / empty list), which is why
 * a bot-wall, a rate limit, a decode break and "no matches" all rendered as "Check your
 * connection".
 */
data class Drift(
    /** Endpoint that produced it, e.g. `search.getResults` or `autocomplete.get`. */
    val endpoint: String,
    /** Stable machine reason, e.g. `CARD_DECODE`, `NO_PERMA_TOKEN`, `BODY_NOT_JSON`. */
    val reason: String,
    val detail: String? = null,
)

/**
 * Typed catalog outcome. Every [MusicProvider] method returns one, so a failure is a *value*
 * with a code, not a `null` that five unrelated root causes share.
 *
 * `Ok` carries [Drift] because partial success is the normal case for a real payload: one
 * unusable card out of twenty is a drop, not a page.
 */
sealed interface CatalogResult<out T> {
    data class Ok<T>(
        val value: T,
        val drift: List<Drift> = emptyList(),
    ) : CatalogResult<T>

    data class Err(
        val code: ErrorCode,
        val detail: String?,
        val retryable: Boolean,
    ) : CatalogResult<Nothing>
}

/** The value, or null. Never the error — a caller that ignores the code gets null, as before. */
fun <T> CatalogResult<T>.valueOrNull(): T? = (this as? CatalogResult.Ok)?.value

fun CatalogResult<*>.errorOrNull(): CatalogResult.Err? = this as? CatalogResult.Err

fun CatalogResult<*>.errorCodeOrNull(): ErrorCode? = errorOrNull()?.code

/** The error, or null. */
fun <T> CatalogResult<T>.orDefault(fallback: T): T = valueOrNull() ?: fallback

fun CatalogResult<*>.retryable(): Boolean = (this as? CatalogResult.Err)?.retryable ?: false

fun err(
    code: ErrorCode,
    detail: String? = null,
    retryable: Boolean = true,
): CatalogResult.Err = CatalogResult.Err(code, detail, retryable)
