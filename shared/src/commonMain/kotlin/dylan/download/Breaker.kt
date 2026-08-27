@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dylan.download

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference

class Breaker {
    private val until = AtomicLong(0L)
    val pausedUntilMs: Long get() = until.load()

    fun paused(nowMs: Long) = nowMs < until.load()

    fun pauseUntil(untilMs: Long) {
        while (true) {
            val cur = until.load()
            if (untilMs <= cur || until.compareAndSet(cur, untilMs)) return
        }
    }
}

class Breakers {
    private val map = AtomicReference<Map<String, Breaker>>(emptyMap())

    fun forHost(host: String): Breaker {
        map.load()[host]?.let { return it }
        while (true) {
            val cur = map.load()
            cur[host]?.let { return it }
            val next = cur + (host to Breaker())
            if (map.compareAndSet(cur, next)) return next.getValue(host)
        }
    }
}
