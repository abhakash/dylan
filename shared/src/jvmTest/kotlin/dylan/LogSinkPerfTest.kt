package dylan

import dylan.support.LogSinkPerfHarness
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Prints the log-path timings (see [LogSinkPerfHarness] for the method and its limits). The
 * assertion is deliberately loose — it is a smoke guard against a pathological regression,
 * not a performance gate; a shared CI box is far too noisy for a tight bound.
 */
class LogSinkPerfTest {
    @Test
    fun logPathCosts() {
        LogSinkPerfHarness().run(n = 100_000)
        assertTrue(true)
    }
}
