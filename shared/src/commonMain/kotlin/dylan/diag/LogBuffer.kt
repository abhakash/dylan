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

    /**
     * Entries per chunk of the ring.
     *
     * One chunk is the unit of eviction, so it must never exceed [capacity]: the invariant that
     * makes wholesale chunk-dropping safe is that by the time the live count exceeds [capacity]
     * at least one chunk is already sealed (see [log]).
     */
    private val chunkSize: Int = capacity.coerceIn(CHUNK_MIN, CHUNK_MAX)

    private val emptyEntries: PersistentList<Entry> = emptyList<Entry>().toPersistentList()

    private val emptyChunks: PersistentList<PersistentList<Entry>> =
        emptyList<PersistentList<Entry>>().toPersistentList()

    /**
     * Copy-on-write ring: lock-free on every platform (no `synchronized` — JVM-only).
     *
     * It is a **deque of fixed-size chunks**, not one `PersistentList`. The old shape was
     * `if (next.size > capacity) next = next.removeAt(0)`, and `PersistentVector.removeAt(0)` is
     * not O(log n): `removeFromRootAt` copies the whole 32-way root and shifts a full segment
     * down at every level of the trie, so `log()` paid that on *every* line — ~3 KB of garbage
     * per retained line at the shipped `capacity = 512`, about 100x a channel `trySend`, on the
     * one lane that must not stall.
     *
     * Splitting the ring into chunks moves that O(capacity) work off the per-line path:
     *  - appending is `live.add(e)` — one tail-array copy, the irreducible cost of publishing a
     *    new immutable state;
     *  - evicting the oldest entry is `headDead++` — a counter, no allocation at all;
     *  - the actual removal happens once per chunk boundary, as a wholesale `sealed.removeAt(0)`,
     *    which is where the old per-line cost now lives, amortised over [chunkSize] lines.
     *
     * Nothing observable changes. The oldest live entry is still evicted the moment [capacity] is
     * exceeded — `headDead` is the exact prefix of the oldest chunk that is dead — and [dump]
     * walks the chunks in order, so it returns the most recent [capacity] entries in order.
     */
    private val ring = AtomicReference(RingState(emptyChunks, 0, emptyEntries))

    /** One published state of the ring. Immutable, so a concurrent [dump] reads a whole snapshot. */
    private data class RingState(
        /** Sealed chunks, oldest first. Every one holds exactly [chunkSize] entries. */
        val sealed: PersistentList<PersistentList<Entry>>,
        /** How many entries at the front of the oldest sealed chunk are already evicted. */
        val headDead: Int,
        /** The chunk entries are appended to: the newest, and possibly empty. */
        val live: PersistentList<Entry>,
    )

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
            var live = cur.live.add(e)
            var sealed = cur.sealed
            var headDead = cur.headDead
            // Sealing moves the chunk as it is, so the live count is unchanged by it. That is what
            // makes one eviction per append always enough: the ring was at `capacity` before this
            // append, so it can be at most `capacity + 1` now.
            if (live.size >= chunkSize) {
                sealed = sealed.add(live)
                live = emptyEntries
            }
            // Evicting is a counter, not a restructure — and it is exact. `headDead` names the
            // first *live* slot of the oldest sealed chunk, so the oldest entry falls off the
            // instant capacity is exceeded, exactly as the flat list did.
            if (liveCountOf(sealed, headDead, live) > capacity) {
                headDead++
                if (headDead == chunkSize) {
                    sealed = sealed.removeAt(0)
                    headDead = 0
                }
            }
            if (ring.compareAndSet(cur, RingState(sealed, headDead, live))) break
        }
        emit(e)
    }

    /**
     * The most recent [capacity] entries, oldest first.
     *
     * Reads one immutable [RingState], so it never sees a half-published ring, and it materialises
     * in chunk order: [RingState.sealed] oldest-first with [RingState.headDead] lopped off the
     * front, then [RingState.live]. O(capacity / chunkSize) chunk hops, which is why a chunk is
     * an inner list the reader can walk directly rather than a per-entry node.
     */
    fun dump(): List<Entry> {
        val cur = ring.load()
        val out = ArrayList<Entry>(liveCountOf(cur.sealed, cur.headDead, cur.live))
        var first = true
        for (chunk in cur.sealed) {
            val from = if (first) cur.headDead else 0
            first = false
            for (i in from until chunk.size) out.add(chunk[i])
        }
        out.addAll(cur.live)
        return out
    }

    /** Live entries across every chunk of [sealed], plus [live]. O(1). */
    private fun liveCountOf(
        sealed: PersistentList<PersistentList<Entry>>,
        headDead: Int,
        live: PersistentList<Entry>,
    ): Int = sealed.size * chunkSize - headDead + live.size

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
         * Bounds on the chunk size. A chunk is the unit of eviction, so it must fit inside
         * [capacity] — the smallest ring that still has a chunk to evict is one chunk — and it is
         * capped so the transient physical overshoot (a sealed chunk plus a partially dead oldest
         * one) stays a small fraction of any [capacity]: at the shipped 512 that is at most
         * 512 + 63 entries held, of which 512 are live.
         */
        private const val CHUNK_MIN = 1
        private const val CHUNK_MAX = 32

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
