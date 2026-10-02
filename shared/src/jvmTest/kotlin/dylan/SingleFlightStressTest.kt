package dylan

import dylan.config.AppConfig
import dylan.diag.LogBuffer
import dylan.provider.CatalogResult
import dylan.provider.ResilientClient
import dylan.support.MutableClock
import dylan.support.TestLanes
import dylan.util.NetClass
import dylan.util.NetMonitor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reproduces the single-flight duplicate that failed CI in
 * `SaavnProviderTest.theSnapshotLruSurvives...` (9 requests for 8 distinct albums, ~1 run in 3).
 *
 * That test could not catch it locally because it runs on virtual time, where the leader's
 * continuation is resumed deterministically and the gap closes before anyone else is planned. This
 * one uses the production lane profile (real threads) with 256 openers over 8 keys: against the old
 * sweep it reports 8-44 duplicate loads; against the fix, none.
 */
class SingleFlightStressTest {
    private val lanes = TestLanes.production()
    private val log = LogBuffer()

    private fun client(scope: CoroutineScope) =
        ResilientClient(
            http =
                HttpClient(
                    MockEngine {
                        respond(
                            content = """{"ok":true}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                ),
            cfg = AppConfig(clock = MutableClock()),
            net =
                object : NetMonitor {
                    override fun current(): NetClass = NetClass.UNMETERED

                    override fun isOnline(): Boolean = true

                    override fun changes() = MutableStateFlow(NetClass.UNMETERED)
                },
            scope = scope,
            disp = lanes.disp,
            log = log,
        )

    @Test
    fun concurrentOpenersCollapseToOneLoadPerKey() {
        val distinct = 8
        val openers = 256
        val scope = CoroutineScope(lanes.disp.io + SupervisorJob())
        try {
            val c = client(scope)
            val loads = AtomicInteger(0)
            val gate = CompletableDeferred<Unit>()
            // Released from another thread so every opener parks before the first load starts;
            // completing it inline would let the first opener finish before the last one exists.
            Thread {
                Thread.sleep(20)
                gate.complete(Unit)
            }.apply { isDaemon = true }.start()

            val ids = (0 until distinct).map { "album-$it" }
            runBlocking {
                (0 until openers)
                    .map { i ->
                        async(lanes.disp.io) {
                            gate.await()
                            c.cached<String>(ids[i % distinct], ttlMs = 60_000) {
                                loads.incrementAndGet()
                                repeat(20) { yield() } // so the leader is not instantaneous
                                CatalogResult.Ok<String>("v")
                            }
                        }
                    }.awaitAll()
            }
            val duplicates = loads.get() - distinct
            assertEquals(
                distinct,
                loads.get(),
                "single-flight let $duplicates duplicate load(s) through for $openers openers over $distinct keys",
            )
        } finally {
            scope.cancel()
        }
    }
}
