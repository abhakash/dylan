package dylan.fuzz.probe

import dylan.fuzz.OriginScript
import dylan.fuzz.OriginStep
import dylan.fuzz.mp4Body
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.write
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * Establishes two facts about the channel `Transfer.copyBody` is actually handed, both measured
 * rather than assumed.
 *
 * 1. **What object is it.** `HttpResponse.bodyAsChannel()` does *not* return the
 *    `SourceByteReadChannel` the engine built. ktor's `defaultTransformers`
 *    (`ktor-client-core`, `commonMain/io/ktor/client/plugins/DefaultTransform.kt:100-119`) launches
 *    `writer { body.copyTo(channel, Long.MAX_VALUE) }` and hands the caller that writer's
 *    `io.ktor.utils.io.ByteChannel`. So the object under test is a `ByteChannel` with a *producer
 *    coroutine* draining the engine channel into it — a second actor, and a different buffered
 *    object from the one `awaitContent` and `readAvailable` will later disagree about.
 * 2. **Whether the real path loses bytes.** The read loop here is `Transfer.readChunk` verbatim —
 *    `awaitContent()` then `readAvailable(buf, 0, buf.size)`, `-1` meaning end of body — run once per
 *    request against a scripted `Reset` origin, over [RUNS] requests.
 */
@OptIn(io.ktor.utils.io.InternalAPI::class)
class EngineReadPathProbe {
    /** Counts, over [RUNS] engine requests, each way `awaitContent` / `readAvailable` can resolve. */
    private class Tally {
        val ran = AtomicInteger()
        val awaitTrueThenEof = AtomicInteger()
        val awaitTrueThenBytes = AtomicInteger()
        val awaitFalseThenEof = AtomicInteger()
        val awaitThrew = AtomicInteger()
        val readThrew = AtomicInteger()
        val bytesDeliveredByOrigin = AtomicInteger()
        val bytesReadByConsumer = AtomicInteger()

        fun render(): String =
            "ran=${ran.get()} bytesFromOrigin=${bytesDeliveredByOrigin.get()} " +
                "bytesToConsumer=${bytesReadByConsumer.get()} " +
                "awaitTrueThenEof=${awaitTrueThenEof.get()} awaitTrueThenBytes=${awaitTrueThenBytes.get()} " +
                "awaitFalseThenEof=${awaitFalseThenEof.get()} awaitThrew=${awaitThrew.get()} " +
                "readThrew=${readThrew.get()}"
    }

    /** A `RawSource` that counts the bytes the *origin* handed over, independent of what is read. */
    private class CountingSource(
        private val total: Int,
    ) : kotlinx.io.RawSource {
        var produced = 0
            private set
        val served = AtomicInteger()

        override fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            served.incrementAndGet()
            if (produced >= total) throw OneShotResetSource.RESET
            val n = minOf(CHUNK_BYTES, total - produced)
            sink.write(ByteArray(n) { (it % 251).toByte() })
            produced += n
            return n.toLong()
        }

        override fun close() = Unit
    }

    /** Engine path, scripted reset: measures how the production two-call read loop resolves. */
    @Test
    fun `engine path, scripted reset, Transfer readChunk loop`() {
        val tally = Tally()
        val classes = mutableSetOf<String>()
        repeat(RUNS) { i ->
            val upstream = CountingSource(DELIVERED_BEFORE_RESET)
            val mock =
                io.ktor.client.engine.mock.MockEngine {
                    respond(
                        content = ByteReadChannel(upstream.buffered()),
                        headers =
                            io.ktor.http.headersOf(
                                HttpHeaders.ContentLength,
                                DECLARED.toString(),
                            ),
                    )
                }
            val client = HttpClient(mock)
            try {
                runBlocking {
                    client
                        .prepareGet("http://mock/audio") {
                            header(HttpHeaders.AcceptEncoding, "identity")
                        }.execute { r ->
                            val ch = r.bodyAsChannel()
                            classes += ch::class.qualifiedName.orEmpty()
                            tally.ran.incrementAndGet()
                            val scratch = ByteArray(64 * 1024)
                            while (true) {
                                val ac =
                                    try {
                                        ch.awaitContent()
                                    } catch (t: Throwable) {
                                        tally.awaitThrew.incrementAndGet()
                                        return@execute
                                    }
                                val n =
                                    try {
                                        ch.readAvailable(scratch, 0, scratch.size)
                                    } catch (t: Throwable) {
                                        tally.readThrew.incrementAndGet()
                                        return@execute
                                    }
                                when {
                                    n == -1 && ac -> tally.awaitTrueThenEof.incrementAndGet()
                                    n == -1 -> tally.awaitFalseThenEof.incrementAndGet()
                                    n > 0 && ac -> tally.awaitTrueThenBytes.incrementAndGet()
                                }
                                // The read loop has just ended. If it ended because
                                // `readAvailable` reported EOF while `_closedCause` was set, the
                                // producer has already hit the reset, so `produced` is final here.
                                tally.bytesDeliveredByOrigin.set(upstream.produced)
                                if (n <= 0) return@execute
                                tally.bytesReadByConsumer.addAndGet(n)
                            }
                        }
                }
            } finally {
                client.close()
            }
            if (i == 0) println("PROBE engine bodyAsChannel class=${classes.joinToString()}")
        }
        println("PROBE engine reset tally ${tally.render()} classes=$classes")
    }

    /**
     * The identical read loop, but on the `SourceByteReadChannel` the engine actually produced —
     * i.e. the engine's channel read *directly*, with the `ByteChannel` hop removed. This is the
     * control: if bytes are lost only through the `ByteChannel` hop, this must never lose any.
     */
    @Test
    fun `engine path without the ByteChannel hop, same read loop`() {
        val tally = Tally()
        val classes = mutableSetOf<String>()
        repeat(RUNS) {
            val upstream = CountingSource(DELIVERED_BEFORE_RESET)
            val engineChannel = ByteReadChannel(upstream.buffered())
            val mock =
                io.ktor.client.engine.mock.MockEngine {
                    respond(content = engineChannel)
                }
            val client = HttpClient(mock)
            try {
                runBlocking {
                    client.prepareGet("http://mock/audio").execute { r ->
                        classes += r.rawContent::class.qualifiedName.orEmpty()
                        tally.ran.incrementAndGet()
                        val scratch = ByteArray(64 * 1024)
                        val ch = r.rawContent
                        while (true) {
                            val ac =
                                try {
                                    ch.awaitContent()
                                } catch (t: Throwable) {
                                    tally.awaitThrew.incrementAndGet()
                                    return@execute
                                }
                            val n =
                                try {
                                    ch.readAvailable(scratch, 0, scratch.size)
                                } catch (t: Throwable) {
                                    tally.readThrew.incrementAndGet()
                                    return@execute
                                }
                            when {
                                n == -1 && ac -> tally.awaitTrueThenEof.incrementAndGet()
                                n == -1 -> tally.awaitFalseThenEof.incrementAndGet()
                                n > 0 && ac -> tally.awaitTrueThenBytes.incrementAndGet()
                            }
                            if (n <= 0) {
                                tally.bytesDeliveredByOrigin.set(upstream.produced)
                                return@execute
                            }
                            tally.bytesReadByConsumer.addAndGet(n)
                        }
                    }
                }
            } finally {
                client.close()
            }
        }
        println("PROBE engine direct tally ${tally.render()} classes=$classes")
    }

    /**
     * The application harness end to end: `OriginScript`'s mid-stream reset, read exactly the way
     * `Transfer.copyChunks` reads, and the recorded request list afterwards. The control is
     * `origin.requests[0].range`, which must be `null` on a cold start — a script that silently
     * delivered the whole body would show a resume here and prove nothing.
     */
    @Test
    fun `OriginScript reset, recorded Range headers`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val origin = OriginScript(scope, mp4Body(DECLARED))
        val client = HttpClient(origin.mock)
        try {
            origin.plan = listOf(OriginStep.Reset(stopAfterBytes = DELIVERED_BEFORE_RESET, declared = DECLARED))
            var lost = 0
            var requests = 0
            repeat(RUNS) {
                runBlocking {
                    client.prepareGet("http://mock/audio").execute { r ->
                        val ch = r.bodyAsChannel()
                        val scratch = ByteArray(64 * 1024)
                        var local = 0
                        while (true) {
                            val ac =
                                try {
                                    ch.awaitContent()
                                } catch (t: Throwable) {
                                    return@execute
                                }
                            val n =
                                try {
                                    ch.readAvailable(scratch, 0, scratch.size)
                                } catch (t: Throwable) {
                                    return@execute
                                }
                            if (n <= 0) {
                                if (ac && local == 0) lost++
                                return@execute
                            }
                            local += n
                        }
                    }
                }
                requests = origin.requests.size
            }
            println(
                "PROBE originscript runs=$RUNS requests=$requests " +
                    "awaitTrueThenEofWithNoBytes=$lost recordedRanges=" +
                    origin.requests
                        .map { it.range }
                        .distinct()
                        .take(5),
            )
        } finally {
            client.close()
            origin.close()
            scope.cancel()
        }
    }

    private companion object {
        const val CHUNK_BYTES = 65_536
        const val DECLARED = 4_000_000
        const val DELIVERED_BEFORE_RESET = 65_536
        const val RUNS = 150
    }
}
