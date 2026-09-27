package dylan.diag

import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Uncaught-exception handler that writes through the [LogBuffer], so a scope crash reaches the
 * persistent file trail instead of only the platform console. [mirror] is the platform console
 * (logcat / NSLog) and is purely additive — the buffer is the record.
 */
fun logBufferExceptionHandler(
    log: LogBuffer,
    tag: String,
    mirror: (String, Throwable) -> Unit = { _, _ -> },
): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, t ->
        log.e(tag, "UNCAUGHT ${t::class.simpleName}: ${t.message ?: "-"}")
        runCatching { mirror(t.message ?: t.toString(), t) }
    }
