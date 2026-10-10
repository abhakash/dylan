package dylan.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dylan.android.ui.AppRoot
import dylan.android.ui.DylanTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var notifAsked = false
    private var serviceStarted = false

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun onFirstPlayTapped() {
        ensureNotificationPermission()
        if (!serviceStarted) {
            serviceStarted = true
            DylanApp
                .of(this)
                .container.log
                .i("activity", "first play → starting DylanMediaService")
            startForegroundService(Intent(this, dylan.android.media.DylanMediaService::class.java))
        }
    }

    private fun ensureNotificationPermission() {
        if (notifAsked) return
        notifAsked = true
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Both bars pinned to `dark`, explicitly. The app background is hard-coded
        // #FF000000 in every token set (see `DylanTokens`), so the clock, battery and signal must
        // always be light. Bare `enableEdgeToEdge()` took `SystemBarStyle.auto`, whose icon
        // brightness follows the *system* night flag rather than the app's, so on a light-mode
        // device it asked for dark icons on a black bar. (That is the whole of issues.md #7.)
        //
        // `TRANSPARENT` for both scrims is what the defaults already resolved to on every API this
        // app can run on: the nav bar's `DefaultLightScrim`/`DefaultDarkScrim` pair is only read
        // below API 29, and minSdk is 34. Pinning `dark` also leaves the navigation bar's platform
        // contrast scrim off — `isNavigationBarContrastEnforced` stays on only for `MODE_NIGHT_AUTO`
        // — which is right here: the app paints its own black under the bar, edge to edge.
        //
        // `enableEdgeToEdge` and `SystemBarStyle` are deprecated in androidx *after* the pinned
        // 1.12.4, where `WindowCompat.enableEdgeToEdge` plus the insets controller replace them.
        // Left alone deliberately: 1.12.4 does not warn, and that migration would rewrite the scrim
        // behaviour described above rather than keep it.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            DylanTheme {
                AppRoot(
                    container = DylanApp.of(this).container,
                    onFirstPlay = { onFirstPlayTapped() },
                    onReportDrawn = { reportFullyDrawn() },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        DylanApp.of(this).container.onBackground()
    }

    override fun onDestroy() {
        // Best-effort drain of the file log before process death — fire-and-forget
        // on the app scope (never block the UI thread); bounded by flush timeout.
        runCatching {
            DylanApp.of(this).container.let { c ->
                c.scope.launch(c.disp.io) { runCatching { c.fileLog.flush() } }
            }
        }
        super.onDestroy()
    }
}
