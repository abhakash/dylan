package dylan

import dylan.download.JobState
import dylan.model.ErrorCode
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fails exactly the `truncatePart` open, and nothing else.
 *
 * Keyed on **call order**, not on an argument. `truncatePart` is `openReadWrite(p)` — one argument,
 * so `mustExist = false`, the very value `Transfer.drain` passes explicitly when it opens the body
 * writer. A decorator keyed on `mustExist = true` therefore never fired, and the test silently
 * exercised the budget-spent path instead of the branch it names. Order is what genuinely separates
 * the two callers: the transfer opens the `.part` writer exactly once per body, and `truncatePart`
 * opens the same path a second time to empty it.
 *
 * The transfer's own pre-write `truncatePart` (the `from == 0L` guard in `Transfer.serveBody`) does
 * not count as an open: `truncatePart` returns early when the part does not exist yet, so on a first
 * transfer it never touches the file. That is what makes "the second open" unambiguously
 * `onSizeMismatch`'s, and the `truncations == 1` assertion below is the control that proves the
 * fault reached the branch.
 *
 * Making the *writer* fail instead is the obvious way to fake a full disk and it is useless here: it
 * lands inside `Transfer` as `StreamErr.Storage` and the attempt settles before the size check is
 * ever reached, which is the other branch, not this one.
 */
private class TruncationFailingFileSystem(
    delegate: FileSystem,
) : ForwardingFileSystem(delegate) {
    private val intercepted = AtomicInteger(0)
    private val opened = HashSet<String>()

    val truncations: Int get() = intercepted.get()

    /**
     * Fails the *second* open of a `.part` file, which is `truncatePart`'s. See the class KDoc for
     * why the discriminator is order and not `mustExist`.
     */
    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle {
        if (file.name.endsWith(".part") && !opened.add(file.name)) {
            intercepted.incrementAndGet()
            throw IOException("EIO: injected truncate failure on $file")
        }
        return super.openReadWrite(file, mustCreate, mustExist)
    }
}

/**
 * "One attempt publishes exactly one terminal state", for the size-mismatch branch.
 *
 * Separate from the other `DownloadEngine` tests because it needs a `FileSystem` decorator and a log
 * probe rather than the shared failure taxonomy, and because the whole point is the *count* of
 * terminal publications rather than which code won.
 */
class DownloadEngineTerminalStateTest : DownloadEngineFixture() {
    /**
     * A failed `truncatePart` on an oversize verdict used to publish twice.
     *
     * `onSizeMismatch` answered a truncate it could not perform by `scope.launch`-ing
     * `fail(STORAGE)` — a *sibling* of the attempt on the engine scope, which nothing joins and no
     * cancellation reaches — and then returning `Step.VERIFY`, which is the value `onVerify` reads
     * as "budget spent" and answers with a `fail(CORRUPT_SIZE)` of its own. One attempt, two
     * `fail` calls, two `dropIntent`, two `publishTerminal`, two terminal states for the same
     * attempt id.
     *
     * The observable is the **ledger of publications for this song**, read out of the log rather than
     * off `engine.states`, and the ledger is only useful if it cannot lose a row:
     *
     *  * `fail`'s first statement is `log.e("dl", "failed ${'$'}{job.label} code=${'$'}{err.code} …")`,
     *    and `LogBuffer` appends to a ring and never conflates, so *every* `fail` this attempt makes
     *    leaves a line. `states`/`attemptStates` are `StateFlow`s: they conflate, so under the bug
     *    they hold only whichever of the two publications landed last and the other is gone for good.
     *    An assertion read off the flow is a coin toss that the bug can win.
     *  * The assertion is on the *codes*, not only on a count. Under the bug the two publications
     *    are a `CORRUPT_SIZE` and a `STORAGE`, and the `CORRUPT_SIZE` is published by the attempt's
     *    own coroutine — it cannot be reordered away or lost, because its log line is written before
     *    the terminal state the test is waiting on. So "the codes published for this song are exactly
     *    `STORAGE`" fails on the mutation whether or not the detached sibling has been scheduled yet.
     *    The count assertion is kept, and is the *second* line of defence, for the sibling's own
     *    publication — the one that is not ordered against anything and needs the settle window.
     */
    @Test
    fun aFailedTruncateOnAnOversizeBodyPublishesExactlyOneTerminalState() =
        runBlocking {
            val fs = TruncationFailingFileSystem(FileSystem.SYSTEM)
            // `dlRetries = 1` puts the branch under test in reach: `onSizeMismatch` gives up on
            // `attempts + 1 > cfg.dlRetries`, and `attempts` is 0 after a clean single transfer, so
            // with the default of 2 the truncate is reached anyway — but the arithmetic is the
            // first statement in that function and is worth pinning in the same breath.
            rebuildEngine(cfg.copy(dlRetries = 1), fs)
            // A 1 s track, so `duration × bps` is 20 400 B and the band's top edge is 26 520 B;
            // 30 000 delivered bytes are therefore `BandHigh`, i.e. an oversize verdict.
            insertSong("saavn", SONG, "enc-$SONG", durationS = 1L)
            // **No `Content-Length`.** The origin's declaration is authoritative when it exists and
            // would score this as `Exact` against itself, which is why every other size test here
            // declares one; omitting it is what forces the two-sided band to decide.
            plan.replies =
                listOf(
                    Scripted(
                        HttpStatusCode.OK,
                        mapOf("Content-Type" to "audio/mp4"),
                        mp4Body(30_000),
                    ),
                )
            plan.repeatLast = false

            val st = runJob(SONG)

            assertEquals(
                1,
                fs.truncations,
                "control: exactly one truncate open, and it is onSizeMismatch's. The transfer's own " +
                    "pre-write truncate short-circuits on a part that does not exist yet, so this " +
                    "count is 1 only if the fault really reached the branch under test. " +
                    "settled=$st truncations=${fs.truncations} origins=${origin.count()} " +
                    "parts=${parts().map { it.name }}",
            )

            // Read the publications off the log, over a fixed settle window (see `settledFailures`).
            // Each entry is one distinct log line with how many times it was ever seen, so the map's
            // values sum to the number of `fail` calls this attempt made.
            val published = settledFailures(SONG_KEY)
            val codes = published.mapNotNull { (line, _) -> CODE_RE.find(line)?.groupValues?.get(1) }
            assertEquals(
                listOf(ErrorCode.STORAGE.name),
                codes.sorted(),
                "one attempt, one terminal state, and it is the disk verdict. A `truncatePart` that " +
                    "cannot be performed used to publish its STORAGE from a detached `scope.launch` " +
                    "and then answer `Step.VERIFY`, which onVerify reads as \"budget spent\" and " +
                    "answers with a CORRUPT_SIZE of its own: one attempt id, two terminal states, " +
                    "and the code the user saw was whichever landed last. More than one entry here — " +
                    "or a single entry that is not STORAGE — IS the double publication. " +
                    "published=$published",
            )
            assertEquals(
                1,
                published.values.sum(),
                "one attempt must publish one terminal state; the second was a STORAGE from inside " +
                    "onSizeMismatch and a CORRUPT_SIZE from onVerify, racing each other and the " +
                    "worker's finally. published=$published",
            )
            // Load-bearing only as a second opinion: under the bug `states` is conflated and holds
            // whichever publication landed last, so it cannot be what the count is read from — but it
            // is the state a caller actually gets, and pinning it here is still honest.
            val failed = assertNotNull(st as? JobState.Failed, "an unperformable truncate is terminal, got $st")
            assertEquals(ErrorCode.STORAGE, failed.err.code, "the truncate is a disk failure, not a size failure")
            assertEquals(
                1,
                origin.count(),
                "an unemptyable part must not be re-requested: the next body would splice onto it",
            )
            assertTrue(parts().isNotEmpty(), "STORAGE is resumable, so the partial download must survive")
            assertNull(cachedRow(SONG), "nothing may be committed from a body that failed its own size check")
        }

    /**
     * The `failed <key> …` messages the log holds over a fixed settle window, each with the most
     * times any single poll saw it.
     *
     * The pre-fix second `fail` was a `scope.launch` off the engine scope: a sibling of the attempt
     * that nothing joins and no cancellation reaches, so "the attempt has ended, now look" is not a
     * barrier and the window is what stands in for the join. Three properties are deliberate:
     *
     *  * **No early exit.** The previous version returned the count as soon as it had "held still"
     *    for [SETTLE_MS] with a count above zero. A stability rule is satisfied by a *transient*
     *    observation: it returns `1` in the gap between the two publications of a buggy attempt and
     *    hands the assertion a green it has not earned, whenever the detached sibling is slower than
     *    the heuristic. This one always observes the whole window and only then reads.
     *  * **Counts are merged by max, per message, not deduplicated.** The lines the log is read for
     *    are cumulative — every poll re-sees the earlier ones — so they cannot simply be appended.
     *    But a `Set` would be wrong in the other direction: two publications of the *same* code
     *    produce byte-identical lines, and deduplicating them would report one attempt that
     *    published twice as having published once. Taking the max per distinct message counts a
     *    repeat and cannot double-count a poll.
     *  * **It bounds the wait, it does not create it.** [SETTLE_MS] is the whole dwell and it is a
     *    *floor* on the observation — four orders of magnitude more than a `Dispatchers.IO` dispatch
     *    plus one `log.e` needs — so it can only ever make an assertion fire sooner, never mask a
     *    publication. No assertion's verdict depends on it: the codes assertion above is ordered
     *    against the terminal state the test already awaited, so it fires with zero elapsed time. This
     *    window exists solely for the detached sibling's own line, which nothing orders against
     *    anything, and it is what replaces the old `CEILING_MS` as the single bound on the wait.
     */
    private suspend fun settledFailures(key: String): Map<String, Int> {
        fun poll(into: MutableMap<String, Int>) {
            val now = HashMap<String, Int>()
            testLog.dump().forEach { e ->
                if (e.tag == DL_TAG && e.msg.startsWith("failed $key ")) {
                    now[e.msg] = (now[e.msg] ?: 0) + 1
                }
            }
            // Max-merge: a line seen in an earlier poll is still there in this one, and a line
            // evicted out of the ring mid-window is still counted from when it was seen.
            now.forEach { (msg, n) -> if (n > (into[msg] ?: 0)) into[msg] = n }
        }
        val seen = HashMap<String, Int>()
        val startNs = System.nanoTime()
        var elapsedMs = 0L
        while (elapsedMs < SETTLE_MS) {
            delay(POLL_MS)
            elapsedMs = (System.nanoTime() - startNs) / NANOS_PER_MS
            poll(seen)
        }
        // One last read after the final poll, so a sibling dispatched on the very last tick is still
        // inside the observation period rather than one poll outside it.
        poll(seen)
        return seen
    }

    private companion object {
        const val SONG = "s2"
        const val SONG_KEY = "saavn:$SONG"
        const val DL_TAG = "dl"

        /** `fail` logs `"failed <job.label> code=<code> detail=<detail>"`; `label` has no spaces. */
        val CODE_RE = Regex(" code=([A-Z_]+)")

        const val SETTLE_MS = 500L
        const val POLL_MS = 25L
        const val NANOS_PER_MS = 1_000_000L
    }
}
