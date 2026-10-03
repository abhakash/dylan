package dylan

import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.db.Dylan
import dylan.diag.LogBuffer
import dylan.model.Quality
import dylan.repo.SettingsStore
import dylan.support.TestLanes
import dylan.util.Lane
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `SettingsStore`'s cache is stateful and shared: the download engine reads `qualityPref()` once per
 * job, the Orchestrator reads `resume` on every boot, the GC reads `gc_last_ms` on every tick, and
 * the reconciler writes its sweep stamp — from different coroutines, on different lanes.
 *
 * The defect this pins is that `get` read `cache[key]` *outside* the store's `Mutex` and wrote it
 * inside: an unsynchronised read of a `LinkedHashMap` concurrent with a `put`. The audit classes the
 * race as PROVEN and its practical failure as SUSPECTED, and that is the honest position — a lost
 * read is benign (it re-reads the table) and a resize observed mid-flight is not something a test
 * may rely on happening. So the assertion below is the *contract* the fix delivers — reads are
 * serialised with writes — expressed as a schedule the test builds rather than as a coin flip on
 * `HashMap` internals.
 *
 * Production lanes, not [TestLanes.virtual]: the schedule needs a task that genuinely *occupies* the
 * dbLane, and `limitedParallelism(1)` frees its permit the moment a task suspends. Only a
 * thread-blocking task holds it, which is why the blocker below waits on a [CountDownLatch] rather
 * than on a `delay`.
 */
class SettingsStoreTest {
    private lateinit var tmp: String
    private lateinit var db: Dylan
    private lateinit var store: SettingsStore

    private val disp = TestLanes.production().disp
    private val cfg = AppConfig()

    @BeforeTest
    fun setup() {
        tmp = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-settings-${System.nanoTime()}"
        FileSystem.SYSTEM.createDirectories(tmp.toPath())
        db = Dylan(DriverFactory("$tmp/dylan.db", LogBuffer()).createDriver())
        store = SettingsStore(db, disp, cfg)
    }

    @AfterTest
    fun teardown() {
        runCatching { FileSystem.SYSTEM.deleteRecursively(tmp.toPath()) }
    }

    @Test
    fun getSeedsTheCacheAndThenAnswersFromIt() =
        runBlocking {
            assertNull(store.get("absent"), "precondition: the key is not in the table")
            store.put("k", "v1")
            assertEquals("v1", store.get("k"))
        }

    @Test
    fun aPutIsVisibleToTheNextGetThroughTheSameInstance() =
        runBlocking {
            store.put(SettingsStore.KEY_QUALITY, Quality.BITRATE_128.name)
            assertEquals(Quality.BITRATE_128, store.qualityPref())
            store.setQualityPref(Quality.BITRATE_320)
            assertEquals(Quality.BITRATE_320, store.qualityPref())
            assertEquals(
                Quality.BITRATE_320.name,
                db.dylanQueries.getSetting(SettingsStore.KEY_QUALITY).executeAsOneOrNull(),
                "the table is the durable half; the cache is only ever a read-through",
            )
        }

    /**
     * The read is inside the lock, and this is how the test can tell.
     *
     * The schedule, with no sleeps anywhere in it:
     *  1. a task occupies the dbLane by *blocking a thread*, so nothing else may enter;
     *  2. a `put` is started with [CoroutineStart.UNDISPATCHED], so it runs inline up to its first
     *     suspension — which is the dbLane hop. It therefore returns to this thread **holding the
     *     store's mutex**, which is the state under test;
     *  3. a `get` for an already-cached key is started the same way. It must stop at the mutex.
     *
     * The pre-fix fast path returned from `cache` without ever asking for the lock, so step 3
     * completed and step 3's assertion failed. A value assertion could not have distinguished them:
     * the pre-fix read is *correct when it happens*, it is unsynchronised, not wrong. What must not
     * exist is a read proceeding while a write to the same map is unfinished.
     */
    @Test
    fun aGetDoesNotProceedWhileAPutToTheSameCacheIsUnfinished() =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                store.put(KEY, "old")
                store.put(KEY, "new")

                val inside = CountDownLatch(1)
                val release = CountDownLatch(1)
                val blocker =
                    launch(disp.on(Lane.DB)) {
                        inside.countDown()
                        // A blocking wait, not a suspending one: `limitedParallelism(1)` returns the
                        // permit when a task suspends, so only a parked thread keeps the lane shut.
                        release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    }
                check(inside.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "the dbLane blocker never started" }

                val pendingPut =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        store.put(KEY, "newest")
                    }
                val pendingGet =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        store.get(KEY)
                    }

                assertTrue(pendingPut.isActive, "precondition: the put is parked on the dbLane, holding the lock")
                assertTrue(
                    pendingGet.isActive,
                    "get() answered while a put() held the lock — the read is not serialised with the write",
                )

                release.countDown()
                blocker.join()
                assertEquals(Unit, pendingPut.await(), "the put must complete once the lane is free")
                assertEquals("newest", pendingGet.await(), "…and the get must observe it")
            }
        }

    /**
     * Many readers and many writers on one store, from coroutines on several lanes — the shape the
     * app actually produces. A `get` must never see a value nobody wrote, and the last `put` to a key
     * must be the value the store reports afterwards.
     */
    @Test
    fun concurrentReadersAndWritersNeverSeeAValueNobodyWrote() =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val jobs =
                    (0 until WRITERS).map { w ->
                        async {
                            repeat(WRITES_EACH) { i -> store.put("k$w", "v$w-$i") }
                        }
                    } +
                        (0 until READERS).map {
                            async {
                                repeat(WRITES_EACH) {
                                    val key = "k${it % WRITERS}"
                                    val seen = store.get(key)
                                    check(seen == null || seen.startsWith("v")) { "impossible value $seen for $key" }
                                }
                            }
                        }
                jobs.forEach { it.await() }
                (0 until WRITERS).forEach { w ->
                    assertEquals("v$w-${WRITES_EACH - 1}", store.get("k$w"), "k$w must end on its last write")
                }
            }
        }

    private companion object {
        const val KEY = "cached"
        const val WRITERS = 6
        const val READERS = 6
        const val WRITES_EACH = 25
        const val TIMEOUT_MS = 30_000L
    }
}
