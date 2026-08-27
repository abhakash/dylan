package dylan.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dylan.android.ui.LocalDylanTokens
import dylan.di.AppContainer
import dylan.model.Quality
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer) {
    val t = LocalDylanTokens.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var quality by remember { mutableStateOf(Quality.BITRATE_320) }
    var songCount by remember { mutableStateOf(0L) }
    var usedBytes by remember { mutableStateOf(0L) }
    var confirmClear by remember { mutableStateOf(false) }
    var notifGranted by remember { mutableStateOf(true) }

    val ctx = LocalContext.current
    val version =
        remember {
            runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "1.0"
        }

    // Re-check on every resume — returning from the system notification settings page must
    // refresh the row without reopening this sheet.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        val granted =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        notifGranted = Build.VERSION.SDK_INT < 33 || granted
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        quality = runCatching { container.settings.qualityPref() }.getOrDefault(Quality.BITRATE_320)
        runCatching {
            val row =
                kotlinx.coroutines.withContext(container.disp.dbLane) {
                    container.db.dylanQueries
                        .cachedCountAndBytes()
                        .executeAsOne()
                }
            songCount = row.song_count
            usedBytes = row.total_bytes
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 32.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, color = t.textPrimary)
        Spacer(Modifier.height(18.dp))

        SectionHeader("AUDIO")
        GroupCard {
            QualityRow("128 kbps", "Data saver", quality == Quality.BITRATE_128) {
                quality = Quality.BITRATE_128
                scope.launch { runCatching { container.settings.setQualityPref(Quality.BITRATE_128) } }
            }
            ThinDivider()
            QualityRow("320 kbps", "High quality", quality == Quality.BITRATE_320) {
                quality = Quality.BITRATE_320
                scope.launch { runCatching { container.settings.setQualityPref(Quality.BITRATE_320) } }
            }
        }
        Text(
            "Metered networks always stream at 128 kbps.",
            fontSize = 11.sp,
            color = t.textSecondary,
            modifier = Modifier.padding(start = 6.dp, top = 8.dp),
        )
        Spacer(Modifier.height(22.dp))

        SectionHeader("STORAGE")
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Offline audio", style = rowTitle(), color = t.textPrimary, modifier = Modifier.weight(1f))
                    Text(formatBytes(usedBytes), style = rowSub(), color = t.textSecondary)
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (usedBytes.toFloat() / container.cfg.cacheMaxBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = t.primary,
                    trackColor = t.divider,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "$songCount of ${container.cfg.cacheMaxFiles} songs · ${formatBytes(container.cfg.cacheMaxBytes)} budget",
                    style = rowSub(),
                    color = t.textSecondary,
                )
            }
            ThinDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { confirmClear = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Clear cache", style = rowTitle(), color = t.primary)
            }
        }
        Spacer(Modifier.height(22.dp))

        if (!notifGranted) {
            SectionHeader("NOTIFICATIONS")
            GroupCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            runCatching { ctx.startActivity(intent) }
                        }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Lock-screen controls unavailable", style = rowTitle(), color = t.textPrimary)
                        Text(
                            "Allow notifications to see playback controls on the lock screen.",
                            style = rowSub(),
                            color = t.textSecondary,
                        )
                    }
                    Text("Turn on", style = rowTitle(), color = t.primary)
                }
            }
            Spacer(Modifier.height(22.dp))
        }

        SectionHeader("ABOUT")
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text("Dylan", style = rowTitle(), color = t.textPrimary)
                Text("Version $version", style = rowSub(), color = t.textSecondary)
            }
        }

        Spacer(Modifier.height(28.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear cache?", style = MaterialTheme.typography.titleMedium, color = t.textPrimary) },
            text = {
                Text(
                    "Downloaded tracks will be removed from storage.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = t.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        runCatching { container.cacheManager.clearCacheExcludingProtected() }
                        val row =
                            runCatching {
                                kotlinx.coroutines.withContext(container.disp.dbLane) {
                                    container.db.dylanQueries
                                        .cachedCountAndBytes()
                                        .executeAsOne()
                                }
                            }.getOrNull()
                        songCount = row?.song_count ?: 0L
                        usedBytes = row?.total_bytes ?: 0L
                    }
                }) { Text("Clear", color = t.primary) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = t.textSecondary) } },
            containerColor = t.surface,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
        color = LocalDylanTokens.current.textSecondary,
        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
    )
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(LocalDylanTokens.current.surfaceVariant)
            .padding(vertical = 6.dp),
    ) { Column { content() } }
}

@Composable
private fun ThinDivider() {
    HorizontalDivider(color = LocalDylanTokens.current.divider.copy(alpha = 0.45f), modifier = Modifier.padding(start = 16.dp))
}

private fun rowTitle(): TextStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)

private fun rowSub(): TextStyle = TextStyle(fontSize = 12.sp)

@Composable
private fun QualityRow(
    label: String,
    sub: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val t = LocalDylanTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(label, style = rowTitle(), color = t.textPrimary)
            Text(sub, style = rowSub(), color = t.textSecondary)
        }
    }
}

// formatBytes lives in LibraryScreen.kt (same package) — reused here.
