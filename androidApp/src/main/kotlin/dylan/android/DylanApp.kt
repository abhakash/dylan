package dylan.android

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import dylan.android.media.ExoPlayerEngine
import dylan.android.media.MediaHub
import dylan.config.AppConfig
import dylan.db.DriverFactory
import dylan.di.AppContainer
import dylan.util.AppDispatchers
import dylan.util.NetMonitor
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath

class DylanApp : Application() {
    lateinit var container: AppContainer
        private set

    val mediaHub = MediaHub()

    override fun onCreate() {
        super.onCreate()
        val cfg = AppConfig()
        val disp =
            AppDispatchers(
                Dispatchers.Main,
                Dispatchers.IO,
                Dispatchers.IO.limitedParallelism(1),
                Dispatchers.Default.limitedParallelism(1),
            )
        // One buffer for the whole process, built here so the file trail can exist before any
        // component logs and so `DriverFactory` gets the same ring the container publishes.
        //
        // It used to be two: `AppContainer` defaulted its own `LogBuffer` and
        // `DriverFactory(this)` defaulted a *second* one (capacity 64, minLevel WARN) with only a
        // logcat mirror. Nothing ever drained that ring into the file trail, so every line the DB
        // open emitted — `PRAGMA journal_mode is 'delete', not wal`,
        // `PRAGMA foreign_keys did not take effect`, `busy_timeout is 0` — was logcat-only and is
        // gone from a week-later triage. Its `minLevel = WARN` also made the one `log.i("db", …)`
        // in `DriverFactory.android.verify` unreachable by construction.
        //
        // iOS has always done this (IosGraph.create builds the buffer, then passes it to both
        // `DriverFactory(log)` and `AppContainer(log = …)`); Android was the outlier.
        val log =
            dylan.diag.LogBuffer(
                minLevel =
                    if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                        dylan.diag.LogLevel.DEBUG
                    } else {
                        dylan.diag.LogLevel.INFO
                    },
            )
        // D23/B5a: appScope = SupervisorJob + state lane + logging exception handler — a crashed
        // prefetch/scan coroutine must never cancel siblings or kill the process.
        //
        // Routed through the shared ring with `logBufferExceptionHandler`, not straight to
        // logcat. An uncaught appScope crash is the one line you cannot reconstruct from state,
        // and the old direct `Log.e` wrote it to logcat only: it reached no file, so a
        // week-later triage saw a process that died with an empty trail. This is exactly what
        // `dylan.diag.logBufferExceptionHandler` is for — the ring is the record, logcat is the
        // sink `bindSink` adds below. iOS has always done it this way (IosGraph.create).
        val appScope =
            CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + disp.state +
                    dylan.diag.logBufferExceptionHandler(log, "scope") { m, t ->
                        android.util.Log.e("Dylan:scope", m, t)
                    },
            )
        container =
            AppContainer(
                cfg = cfg,
                disp = disp,
                scope = appScope,
                baseDir = filesDir.absolutePath,
                driverFactory = DriverFactory(this, log),
                fs = okio.FileSystem.SYSTEM,
                netMonitor = NetMonitor(this),
                httpEngine = OkHttp.create(),
                engineFactory = { ExoPlayerEngine(this) },
                log = log,
            )
        // Mirror the shared ring buffer into logcat (tag: Dylan:<tag>) so device triage sees app state.
        container.log.bindSink { e ->
            val prio =
                when (e.level) {
                    dylan.diag.LogLevel.DEBUG -> android.util.Log.DEBUG
                    dylan.diag.LogLevel.INFO -> android.util.Log.INFO
                    dylan.diag.LogLevel.WARN -> android.util.Log.WARN
                    dylan.diag.LogLevel.ERROR, dylan.diag.LogLevel.CRITICAL -> android.util.Log.ERROR
                }
            android.util.Log.println(prio, "Dylan:${e.tag}", e.msg + (e.metaJson?.let { " $it" } ?: ""))
        }
        container.start()
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader
                .Builder(ctx)
                .memoryCache { MemoryCache.Builder().maxSizeBytes(cfg.imageMemoryCacheBytes).build() }
                .diskCache {
                    DiskCache
                        .Builder()
                        .directory((ctx.cacheDir.resolve("coil").absolutePath).toPath())
                        .maxSizeBytes(cfg.imageCacheBytes)
                        .build()
                }.build()
        }
    }

    companion object {
        fun of(ctx: Context): DylanApp = ctx.applicationContext as DylanApp
    }
}
