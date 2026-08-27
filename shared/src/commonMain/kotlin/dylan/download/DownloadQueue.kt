@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dylan.download

import dylan.cache.Paths
import dylan.config.AppConfig
import dylan.model.SongKey
import dylan.util.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.atomics.AtomicReference

/**
 * Wave 3 extract: queue ownership (was inline in DownloadEngine:129-267).
 * Single-flight peek, priority sort, strictly-higher-priority preempt keeping .part.
 * Fetcher/Verifier remain in DownloadEngine for now — this file owns queue state only.
 */
internal class DownloadQueue(
    private val paths: Paths,
    private val cfg: AppConfig,
    private val disp: AppDispatchers,
    private val scope: CoroutineScope,
) {
    val mutex = Mutex()
    val wake = Channel<Unit>(Channel.CONFLATED)
    val queue = mutableListOf<DownloadJob>()
    var executing: Job? = null
    var executingKey: SongKey? = null
    var executingJob: DownloadJob? = null
    val preemptedKey = AtomicReference<SongKey?>(null)
}
