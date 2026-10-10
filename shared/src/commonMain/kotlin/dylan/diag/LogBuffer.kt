package dylan.diag

import dylan.util.Clock
import dylan.util.SystemClock
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlin.concurrent.atomics.AtomicReference

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    CRITICAL,
}

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
class LogBuffer(
    private val capacity: Int = 512,
    private val minLevel: LogLevel = LogLevel.INFO,
    private val clock: Clock = SystemClock,
) {
    data class Entry(
        val ts: Long,
        val level: LogLevel,
        val tag: String,
        val msg: String,
        val metaJson: String? = null,
    )

    // Copy-on-write ring: lock-free on every platform (no `synchronized` — JVM-only).
    private val ring = AtomicReference<PersistentList<Entry>>(emptyList<Entry>().toPersistentList())

    // Sinks (platform mirrors: file appender, Android logcat, iOS NSLog) — additive, so core can
    // own the persistent file sink while each platform adds its console mirror.
    private val sinks = AtomicReference<List<Sink>>(emptyList())

    private data class Sink(
        val key: String,
        val emit: (Entry) -> Unit,
    )

    /**
     * [key] identifies the sink across re-binds. Binding a key that is already present is a
     * no-op, so re-binding can never double-write a line: the previous identity-based dedupe
     * could never fire, because every `sink::accept` reference is a fresh object.
     */
    fun bindSink(
        key: String,
        f: (Entry) -> Unit,
    ) {
        while (true) {
            val cur = sinks.load()
            if (cur.any { it.key == key }) return
            if (sinks.compareAndSet(cur, cur + Sink(key, f))) return
        }
    }

    /**
     * One console mirror per buffer. A buffer is bound to at most one platform console, so the
     * keyless form is idempotent rather than additive.
     */
    fun bindSink(f: (Entry) -> Unit) = bindSink(PLATFORM_MIRROR_KEY, f)

    private fun emit(e: Entry) {
        val cur = sinks.load()
        if (cur.isEmpty()) return
        for (i in cur.indices) {
            runCatching { cur[i].emit(e) }
        }
    }

    fun log(
        level: LogLevel,
        tag: String,
        msg: String,
        metaJson: String? = null,
    ) {
        if (level < minLevel) return
        val e = Entry(clock.nowMs(), level, tag, redact(msg), metaJson?.let(::redact))
        while (true) {
            val cur = ring.load()
            var next = cur.add(e)
            if (next.size > capacity) next = next.removeAt(0)
            if (ring.compareAndSet(cur, next)) break
        }
        emit(e)
    }

    fun dump(): List<Entry> = ring.load().toList()

    fun d(
        tag: String,
        msg: String,
        metaJson: String? = null,
    ) = log(LogLevel.DEBUG, tag, msg, metaJson)

    fun i(
        tag: String,
        msg: String,
        metaJson: String? = null,
    ) = log(LogLevel.INFO, tag, msg, metaJson)

    fun w(
        tag: String,
        msg: String,
        metaJson: String? = null,
    ) = log(LogLevel.WARN, tag, msg, metaJson)

    fun e(
        tag: String,
        msg: String,
        metaJson: String? = null,
    ) = log(LogLevel.ERROR, tag, msg, metaJson)

    fun c(
        tag: String,
        msg: String,
        metaJson: String? = null,
    ) = log(LogLevel.CRITICAL, tag, msg, metaJson)

    companion object {
        private const val PLATFORM_MIRROR_KEY = "platform-console"

        /**
         * The literal the key takes in a log line — `encrypted_media_url=…` — and exactly the
         * prefix [ENCRYPTED_MEDIA_URL_RE] is anchored on. Not just "the word appears": the regex
         * requires the `=`, so the pre-filter must require it too or a line carrying the word
         * pays a full regex scan to produce a byte-identical result.
         */
        private const val ENCRYPTED_MEDIA_URL_EQ = "encrypted_media_url="

        private const val URL_SCHEME = "://"

        /** [URL_QUERY_VALUE_RE] cannot match without one of these, so the pre-filter must reject too. */
        private const val URL_QUERY_SEP_FIRST = '?'

        private const val URL_QUERY_SEP_ANY = '&'

        // Substitution, never truncation: a redacted line keeps the quality, the CDN host and
        // the timing that follow the secret. The old `substring(0, q) + "?…"` /
        // `substring(0, k) + "resolve_ref=…"` forms threw the whole tail of the diagnostic away.
        private val ENCRYPTED_MEDIA_URL_RE = Regex("(encrypted_media_url=)[^\\s&#\"']*")

        // Query values are redacted but the keys are kept, so `?encrypted_media_url=…&bitrate=…`
        // stays greppable. Anchored on ?/& so ordinary `k=v` log fields are untouched, and
        // terminated at a quote so a JSON metaJson blob keeps everything after its URL.
        private val URL_QUERY_VALUE_RE = Regex("([?&][A-Za-z0-9_.\\[\\]-]+=)[^\\s&#\"']*")

        /**
         * `contains` pre-filters keep the common case (no URL, no token) to two fast scans and
         * zero allocations; the regexes only run on lines that actually carry a secret.
         *
         * Each pre-filter is the *strongest* form of the pattern it guards, not merely a prefix of
         * it. `URL_QUERY_VALUE_RE` is anchored on `?` or `&` and `ENCRYPTED_MEDIA_URL_RE` on `=`,
         * so a line that has neither cannot match either — and the pre-filters must reject it too,
         * or a line like `url=http://127.0.0.1:8080/a.m4a` pays a full regex scan and the
         * allocation of a `Matcher` and its output `String` to produce a byte-identical result.
         */
        internal fun redact(s: String): String {
            val hasToken = ENCRYPTED_MEDIA_URL_EQ in s
            val hasQuery = URL_SCHEME in s && (URL_QUERY_SEP_FIRST in s || URL_QUERY_SEP_ANY in s)
            if (!hasToken && !hasQuery) return s
            var out = s
            if (hasToken) out = ENCRYPTED_MEDIA_URL_RE.replace(out, "$1<redacted>")
            if (hasQuery) out = URL_QUERY_VALUE_RE.replace(out, "$1<redacted>")
            return out
        }
    }
}
