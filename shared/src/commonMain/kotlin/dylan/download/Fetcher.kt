package dylan.download

import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.config.AppConfig
import dylan.provider.MusicProvider
import dylan.util.AppDispatchers
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import okio.FileSystem

/**
 * Wave 3 extract: resolve → request → stream (was DownloadEngine:345-636).
 * Breaker/stall watchdog live here; queue + verify stay elsewhere.
 * Currently a facade — DownloadEngine delegates to this after queue split lands.
 */
internal class Fetcher(
    private val db: dylan.db.Dylan,
    private val fs: FileSystem,
    private val paths: Paths,
    private val cfg: AppConfig,
    private val disp: AppDispatchers,
    private val provider: MusicProvider,
    private val bulk: HttpClient,
    private val breakers: Breakers,
    private val cacheManager: CacheManager,
    private val scope: CoroutineScope,
)
