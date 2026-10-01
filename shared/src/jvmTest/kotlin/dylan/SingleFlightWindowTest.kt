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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Single-flight must collapse concurrent callers for one key onto ONE request.
 *
 * `SaavnProviderTest.theSnapshotLruSurvivesConcurrentWritersThatTheLinkedHashMapDoesNot` asserts
 * this, but only by scheduling luck: it failed on CI (~1 run in 3) and passed 6/6 locally, so it
 * proves nothing on its own — a test that cannot distinguish correct from incorrect code is not a
 * gate. These tests hold the leader's `load()` body open on a `CompletableDeferred`, so the
 * interleaving is chosen by the test rather than by the scheduler, and each property is asserted
 * directly.
 *
 * These are coverage for properties the existing test only reaches by luck. They are NOT regression
 * tests for a proven defect: the sweep predicate in `planLocked` was investigated as a possible
 * race and found sound — `planLocked` runs entirely under `lock`, and the leader takes that same
 * lock in `storeLocked` with no suspension point between `await()` returning and the lock being
 * acquired, so no caller can observe the completed-but-unstored state it would need to.
 */
class SingleFlightWindowTest {
    private val sched = TestCoroutineScheduler()
    private val lanes = TestLanes.virtual(sched)
    private val log = LogBuffer()
    private val clock = MutableClock()

    private fun client(scope: CoroutineScope): ResilientClient =
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
            cfg = AppConfig(clock = clock),
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

    /**
     * The leader is parked INSIDE `load()`, so its deferred is incomplete and `entries` is
     * unwritten. A second caller must JOIN it. It can only do so by observing the leader's value —
     * which is what makes this a real assertion rather than a scheduling coincidence.
     */
    @Test
    fun aCallerArrivingWhileTheLeaderIsStillLoadingJoinsIt() =
        runTest(sched) {
            val scope = CoroutineScope(lanes.disp.io + SupervisorJob())
            try {
                val c = client(scope)
                val loadEntered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()

                val leader =
                    async(lanes.disp.io) {
                        c.cached<String>("album-join", ttlMs = 60_000) {
                            loadEntered.complete(Unit)
                            release.await()
                            CatalogResult.Ok<String>("from-leader")
                        }
                    }

                // The leader is now parked inside its load with the key registered in `inflight`.
                loadEntered.await()

                var joinerLoadRan = false
                val joiner =
                    async(lanes.disp.io) {
                        c.cached<String>("album-join", ttlMs = 60_000) {
                            joinerLoadRan = true
                            CatalogResult.Ok<String>("from-joiner")
                        }
                    }

                // Let the joiner reach its decision while the leader is still parked, so the join
                // path — not the cache-hit path — is the only way to an answer.
                testScheduler.runCurrent()

                release.complete(Unit)
                val out = joiner.await()

                assertTrue(!joinerLoadRan, "single-flight broke: the joiner issued its own request")
                assertEquals("from-leader", (out as CatalogResult.Ok).value, "the joiner must see the LEADER's result")

                leader.await()
            } finally {
                scope.cancel()
            }
        }

    /**
     * The load has now COMPLETED and the result is stored. A caller arriving here must get the
     * cached value — this is the ordinary post-completion path, which is what the leader leaves
     * behind for every later caller.
     */
    @Test
    fun aCallerArrivingAfterTheLoadCompletesGetsTheCachedResult() =
        runTest(sched) {
            val scope = CoroutineScope(lanes.disp.io + SupervisorJob())
            try {
                val c = client(scope)
                val loadEntered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()

                val leader =
                    async(lanes.disp.io) {
                        c.cached<String>("album-window", ttlMs = 60_000) {
                            loadEntered.complete(Unit)
                            release.await()
                            CatalogResult.Ok<String>("first")
                        }
                    }
                loadEntered.await()

                // Release the load and let the leader store its result.
                release.complete(Unit)
                testScheduler.runCurrent()
                leader.await()

                var reloaded = false
                val late =
                    c.cached<String>("album-window", ttlMs = 60_000) {
                        reloaded = true
                        CatalogResult.Ok<String>("second")
                    }

                assertTrue(!reloaded, "a completed load was reloaded instead of served from cache")
                assertEquals("first", (late as CatalogResult.Ok).value)
            } finally {
                scope.cancel()
            }
        }

    /**
     * The narrowed sweep must still clean up after a load that THREW — a failed load caches
     * nothing, so its key is absent from `entries` and the entry is removed. Without this the key
     * would be permanently poisoned. This is why the predicate could not simply be dropped.
     */
    @Test
    fun aFailedLoadIsSweptSoTheKeyCanBeRetried() =
        runTest(sched) {
            val scope = CoroutineScope(lanes.disp.io + SupervisorJob())
            try {
                val c = client(scope)

                val first =
                    runCatching {
                        c.cached<String>("album-boom", ttlMs = 60_000) {
                            error("simulated origin failure")
                        }
                    }
                assertTrue(first.isFailure, "the failure must reach the caller")

                var retried = false
                val second =
                    c.cached<String>("album-boom", ttlMs = 60_000) {
                        retried = true
                        CatalogResult.Ok<String>("recovered")
                    }
                assertTrue(retried, "the key was permanently poisoned: the retry never ran")
                assertEquals("recovered", (second as CatalogResult.Ok).value)
            } finally {
                scope.cancel()
            }
        }
}
