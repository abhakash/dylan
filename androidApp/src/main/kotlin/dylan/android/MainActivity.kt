package dylan.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
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
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
