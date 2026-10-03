package dylan.di

import dylan.cache.CacheManager
import dylan.cache.Paths
import dylan.cache.Reconciler
import dylan.db.Dylan
import dylan.diag.FileLogSink
import dylan.download.Breakers
import dylan.download.DownloadEngine
import dylan.playback.Orchestrator
import dylan.provider.saavn.SaavnProvider
import dylan.repo.Favorites
import dylan.repo.History
import dylan.repo.HomeCacheRepo
import dylan.repo.SearchHistoryRepo
import dylan.repo.SettingsStore
import dylan.search.SaavnSearchChannel
import dylan.util.NetMonitor
import io.ktor.client.HttpClient
import okio.FileSystem

// The four graphs. Each one is the *complete* set of handles a consumer of that concern is
// allowed, so a component constructed from DataGraph cannot reach the network and a component
// constructed from FileGraph cannot reach the database. AppContainer is the composition root
// that builds all four; nothing else in the codebase constructs one. Splitting by *lane* rather
// than by layer is deliberate: a facade exists so the lanes a component can accidentally run on
// are the lanes it was handed.

/** Database-only. Nothing here touches the filesystem or the network. */
class DataGraph(
    val db: Dylan,
    val settings: SettingsStore,
    val favorites: Favorites,
    val history: History,
    val searchHistory: SearchHistoryRepo,
    val homeCache: HomeCacheRepo,
)

/** Filesystem-only. [Paths] and [FileLogSink] both `createDirectories` in their constructors. */
class FileGraph(
    val fs: FileSystem,
    val paths: Paths,
    val fileLog: FileLogSink,
)

/** Network-only, plus the metered/unmetered policy that gates the bulk transfer. */
class NetGraph(
    val api: HttpClient,
    val bulk: HttpClient,
    val ws: HttpClient,
    val provider: SaavnProvider,
    val searchChannel: SaavnSearchChannel,
    val netMonitor: NetMonitor,
) {
    /** Reverse construction order; the shared engine is closed by [AppContainer] after this. */
    fun close() {
        ws.close()
        bulk.close()
        api.close()
    }
}

/**
 * Playback and everything that mutates the library on its behalf. [cacheManager] is here rather
 * than in [DataGraph] because it writes rows *and* unlinks files — it is not a database-only
 * service — and [DataGraph.favorites] is its one consumer, which is the single documented
 * inversion in the split.
 */
class PlaybackGraph(
    val orchestrator: Orchestrator,
    val downloads: DownloadEngine,
    val reconciler: Reconciler,
    val breakers: Breakers,
    val cacheManager: CacheManager,
)
