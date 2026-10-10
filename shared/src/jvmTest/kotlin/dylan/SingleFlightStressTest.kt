package dylan

import dylan.config.AppConfig
import dylan.diag.LogBuffer
import dylan.provider.CatalogResult
import dylan.provider.ResilientClient
import dylan.provider.valueOrNull
import dylan.support.MutableClock
import dylan.support.TestLanes
import dylan.util.Clock
import dylan.util.Connectivity
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    private fun client(
        scope: CoroutineScope,
        clock: Clock = MutableClock(),
    ) = ResilientClient(
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
                private val state = MutableStateFlow(Connectivity(true, NetClass.UNMETERED))

                override fun connectivity(): Flow<Connectivity> = state

                override fun current(): NetClass = NetClass.UNMETERED

                override fun isOnline(): Boolean = true
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

    /**
     * A clock whose reads can be made to **block while `ResilientClient`'s mutex is held**.
     *
     * `storeLocked` calls `cfg.clock.nowMs()` *inside* `lock.withLock`, and so does `planLocked` —
     * so parking `nowMs()` from a latch is the only handle a test has on the client's private mutex,
     * and it is what turns "the mutex happens to be contended at the wrong instant" from a race into
     * a schedule. Nothing in production can be written this way; only the test needs the seam.
     */
    private class LatchingClock : Clock {
        private val release = java.util.concurrent.CountDownLatch(1)
        private val inside = java.util.concurrent.CountDownLatch(1)

        @Volatile
        private var armed = false

        @Volatile
        private var t = 1_756_000_000_000L

        /** Every later read blocks, reporting that it got in. */
        fun arm() {
            armed = true
        }

        fun heldMutex(): Boolean = inside.await(5, java.util.concurrent.TimeUnit.SECONDS)

        fun releaseMutex() = release.countDown()

        fun advanceMs(ms: Long) {
            t += ms
        }

        override fun nowMs(): Long {
            if (armed) {
                inside.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            return t
        }
    }

    /**
     * The stale-entry half of the single-flight contract: a key must never be left pinned to a
     * finished deferred, because a pinned key is pinned forever.
     *
     * The schedule, on real threads (the whole point — virtual time closes this interleaving):
     *
     *  1. `k` is led by a caller whose load is parked on a gate.
     *  2. Another key's load completes and its leader enters `storeLocked`, which parks inside
     *     `clock.nowMs()` — **holding the client's mutex**.
     *  3. The `k` leader is cancelled. It leaves `plan.fresh.await()` through
     *     `CancellationException` and reaches its cleanup, which must take the mutex that step 2
     *     holds.
     *  4. The mutex is released and `k`'s load is allowed to finish.
     *
     * Step 3 is the bug. `Mutex.withLock` from an already-cancelled coroutine does not wait politely:
     * `lock()` falls through to `suspendCancellableCoroutine` when the fast `tryLock` misses, and that
     * continuation's cancellability is decided at construction, so the call throws
     * `CancellationException` *without acquiring*. The old `catch (t: Throwable) { lock.withLock {
     * inflight.remove(key) } }` therefore removed nothing, `inflight["k"]` kept pointing at the now
     * completed deferred, and from then on **every** caller for `k` took the `Decision.Join` branch
     * against a zombie: the TTL never applied, the LRU never saw the value, and a load that had
     * *failed* would have been replayed as a permanent exception. Only the leader can clear its own
     * entry, and the leader was gone.
     */
    @Test
    fun aCancelledLeaderStillReleasesTheKeySoTheNextCallerReloads() {
        val clock = LatchingClock()
        val scope = CoroutineScope(lanes.disp.io + SupervisorJob())
        val ttl = 60_000L
        try {
            val c = client(scope, clock)
            val kGate = CompletableDeferred<Unit>()
            val loadStarted = java.util.concurrent.CountDownLatch(1)
            val reloads = AtomicInteger(0)

            runBlocking {
                val victim =
                    async(lanes.disp.io) {
                        c.cached<String>("k", ttl) {
                            loadStarted.countDown()
                            kGate.await()
                            CatalogResult.Ok<String>("first")
                        }
                    }
                // The victim must be parked *inside the load* before anything else happens, or the
                // cancellation below would land on a caller that has not yet registered.
                assertTrue(
                    loadStarted.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "the victim never reached the load",
                )

                // A second key's leader arms the clock from *inside its load* (which runs outside the
                // mutex), so the next `nowMs()` it makes is inside `storeLocked` — mutex held.
                val holder =
                    async(lanes.disp.io) {
                        c.cached<String>("holder", ttl) {
                            clock.arm()
                            CatalogResult.Ok<String>("held")
                        }
                    }
                assertTrue(clock.heldMutex(), "the holder never got inside the mutex")

                victim.cancel()
                // Real time, real threads: give the cancelled leader time to reach its cleanup while
                // the mutex is still held. This is synchronisation, not an assertion about timing —
                // the assertions below read state, not elapsed time.
                Thread.sleep(250)
                clock.releaseMutex()
                holder.await()

                // Now let the victim's load finish; its deferred completes with nobody left to store it.
                kGate.complete(Unit)

                // A joining caller is legitimate while the load is genuinely still running, so this
                // polls. It cannot paper over the bug: a pinned key *never* recovers, so a caller that
                // keeps getting "first" forever is the failure this asserts on.
                var seen = ""
                val deadline = System.nanoTime() + 5_000_000_000L
                while (System.nanoTime() < deadline && seen != "second") {
                    val attempt =
                        c.cached<String>("k", ttl) {
                            reloads.incrementAndGet()
                            CatalogResult.Ok<String>("second")
                        }
                    seen = attempt.valueOrNull().orEmpty()
                    Thread.sleep(20)
                }

                assertEquals(
                    "second",
                    seen,
                    "a cancelled leader left inflight['k'] pinned to a finished deferred, so every " +
                        "later caller joined a zombie instead of loading",
                )
                assertEquals(1, reloads.get(), "the pinned key also cost the load entirely")

                // And the LRU is live again: past the TTL the key reloads rather than replaying.
                clock.advanceMs(ttl + 1)
                c.cached<String>("k", ttl) {
                    reloads.incrementAndGet()
                    CatalogResult.Ok<String>("third")
                }
                assertEquals(2, reloads.get(), "the TTL must apply again once the key is released")
            }
        } finally {
            scope.cancel()
        }
    }
}
