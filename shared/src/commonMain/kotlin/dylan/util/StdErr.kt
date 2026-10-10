package dylan.util

/** Platform stderr sink — JVM has System.err; Native routes through NSLog/fprintf. */
internal expect fun logErr(msg: String)

/**
 * [logErr] with [dylan.diag.LogBuffer.redact] applied.
 *
 * Every caller of this egress hands it throwable-derived text — an exception message or a
 * `Throwable.toString()` — and the whole reason [dylan.diag.LogBuffer.redact] exists is that a
 * signed CDN URL (or the token inside one) reaches a log through an exception: ktor's `Url()`
 * failure names the url it could not parse, a transport failure quotes it, and a filesystem
 * failure names the path. The ring redacts `msg` and `metaJson` on the way *in*; this is the one
 * egress that writes straight to stderr / logcat / NSLog with no ring in front of it, so without
 * this the redaction is bypassable by construction rather than by a call site that forgot.
 */
internal fun logErrRedacted(msg: String) = logErr(dylan.diag.LogBuffer.redact(msg))
