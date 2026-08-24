package dylan.android.ui

import dylan.model.ErrorCode

object Copy {
    val OFFLINE = "You're offline — saved music still plays."
    val NOT_FOUND = "This track seems unavailable."
    val EXPIRED = "Couldn't refresh this track. Try again."
    val NETWORK = "Check your connection and try again."
    val STORAGE = "Not enough space. Free up storage or clear cache."
    val CORRUPT = "That file didn't download cleanly. Retrying…"
    val NOT_CACHEABLE = "This track can't be saved for offline play."
    val GEO_BLOCKED = "Not available in your region."
    val RATE_LIMITED = "Slow down a moment…"
    val TOO_MANY_FAILURES = "Several tracks failed to load. Check your connection."

    fun forCode(code: ErrorCode): String =
        when (code) {
            ErrorCode.OFFLINE -> OFFLINE
            ErrorCode.NOT_FOUND -> NOT_FOUND
            ErrorCode.EXPIRED -> EXPIRED
            ErrorCode.NETWORK, ErrorCode.NETWORK_TIMEOUT, ErrorCode.DRIFT -> NETWORK
            ErrorCode.STORAGE -> STORAGE
            ErrorCode.CORRUPT_SIZE, ErrorCode.CORRUPT_CONTAINER -> CORRUPT
            ErrorCode.NOT_CACHEABLE, ErrorCode.NO_SOURCE, ErrorCode.UNSUPPORTED -> NOT_CACHEABLE
            ErrorCode.FORBIDDEN_REGION -> GEO_BLOCKED
            ErrorCode.RATE_LIMITED, ErrorCode.RESOLVE_LIMIT -> RATE_LIMITED
            ErrorCode.TOO_MANY_FAILURES -> TOO_MANY_FAILURES
        }
}
