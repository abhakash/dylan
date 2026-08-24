package dylan.android

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
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

class DylanApp : Application() {
    lateinit var container: AppContainer
        private set

    val mediaHub = MediaHub()

    override fun onCreate() {
        super.onCreate()
        val disp =
            AppDispatchers(
                Dispatchers.Main,
                Dispatchers.IO,
                Dispatchers.IO.limitedParallelism(1),
                Dispatchers.Default.limitedParallelism(1),
            )
        // D23/B5a: appScope = SupervisorJob + state lane + logging exception handler — a crashed
        // prefetch/scan coroutine must never cancel siblings or kill the process.
        val appScope =
            CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + disp.state +
                    kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
                        android.util.Log.e("Dylan:scope", "UNCAUGHT in appScope", t)
                    },
            )
        container =
            AppContainer(
                cfg = AppConfig(),
                disp = disp,
                scope = appScope,
                baseDir = filesDir.absolutePath,
                driverFactory = DriverFactory(this),
                netMonitor = NetMonitor(this),
                httpEngine = OkHttp.create(),
                engineFactory = { ExoPlayerEngine(this) },
                logMinLevel =
                    if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                        dylan.diag.LogLevel.DEBUG
                    } else {
                        dylan.diag.LogLevel.INFO
                    },
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
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader
                .Builder(ctx)
                .memoryCache { MemoryCache.Builder().maxSizeBytes(48L * 1024 * 1024).build() }
                .build()
        }
    }

    companion object {
        fun of(ctx: Context): DylanApp = ctx.applicationContext as DylanApp
    }
}
