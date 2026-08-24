package dylan.diag

import dylan.util.nowMs
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

    // Optional platform mirror (Android logcat, iOS os_log) — bound once at app init.
    private var sink: ((Entry) -> Unit)? = null

    fun bindSink(f: (Entry) -> Unit) {
        sink = f
    }

    fun log(
        level: LogLevel,
        tag: String,
        msg: String,
        metaJson: String? = null,
    ) {
        if (level < minLevel) return
        val e = Entry(nowMs(), level, tag, redact(msg), metaJson?.let(::redact))
        while (true) {
            val cur = ring.load()
            var next = cur.add(e)
            if (next.size > capacity) next = next.removeAt(0)
            if (ring.compareAndSet(cur, next)) break
        }
        sink?.invoke(e)
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
        // Shared drop-all buffer for tests / call sites that were not handed the real one.
        val SILENT: LogBuffer = LogBuffer(capacity = 8, minLevel = LogLevel.CRITICAL)

        internal fun redact(s: String): String {
            var out = s
            val urlIdx = out.indexOf("://")
            if (urlIdx > 0) {
                val q = out.indexOf('?', urlIdx)
                if (q > 0) out = out.substring(0, q) + "?…"
            }
            while (true) {
                val k = out.indexOf("encrypted_media_url")
                if (k < 0) break
                out = out.substring(0, k) + "resolve_ref=<redacted>"
            }
            return out
        }
    }
}
