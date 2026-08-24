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
        container =
            AppContainer(
                cfg = AppConfig(),
                disp = disp,
                scope = CoroutineScope(disp.state),
                baseDir = filesDir.absolutePath,
                driverFactory = DriverFactory(this),
                netMonitor = NetMonitor(this),
                httpEngine = OkHttp.create(),
                engineFactory = { ExoPlayerEngine(this) },
            )
        // Mirror the shared ring buffer into logcat (tag: Dylan:<tag>) so device triage sees app state.
        container.log.bindSink { e ->
            val prio =
                when (e.level) {
                    dylan.diag.LogLevel.ERROR, dylan.diag.LogLevel.CRITICAL -> android.util.Log.ERROR
                    dylan.diag.LogLevel.WARN -> android.util.Log.WARN
                    dylan.diag.LogLevel.DEBUG -> android.util.Log.DEBUG
                    else -> android.util.Log.INFO
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
