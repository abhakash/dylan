package dylan.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dylan.android.ui.Copy
import dylan.android.ui.LocalDylanTokens
import dylan.di.AppContainer
import dylan.model.SongKey
import kotlinx.coroutines.withContext

@Composable
fun SectionTitle(text: String) {
    val t = LocalDylanTokens.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp),
            color = t.textSecondary,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(1.dp)
                .background(t.divider),
        )
    }
}

@Composable
fun OfflineBanner() {
    val t = LocalDylanTokens.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(t.surfaceVariant)
            .padding(1.dp)
            .background(t.background)
            .padding(12.dp),
    ) {
        Text(
            Copy.OFFLINE.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
            color = t.textSecondary,
        )
    }
}

/**
 * UI-side online signal: the shared NetMonitor only tracks metered-ness, so true
 * connectivity comes from ConnectivityManager.activeNetwork. The NetMonitor flow is
 * collected as the recompose trigger (capability changes re-evaluate the check).
 */
@Composable
fun rememberIsOnline(container: AppContainer): Boolean {
    val ctx = LocalContext.current
    val netClass by container.netMonitor.changes().collectAsState(initial = container.netMonitor.current())
    return remember(netClass) {
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        cm?.activeNetwork != null
    }
}

/** UI-side cached-key set from the library downloads (cached_files rows). */
@Composable
fun rememberCachedKeys(container: AppContainer): Set<SongKey> {
    var keys by remember { mutableStateOf(emptySet<SongKey>()) }
    val progress by container.downloads.progress.collectAsState()
    // Re-query only when the in-flight key set changes (a finish/drop), not on every pct tick.
    LaunchedEffect(progress.keys) {
        keys =
            withContext(container.disp.dbLane) {
                runCatching {
                    container.db.dylanQueries
                        .selectAllCached()
                        .executeAsList()
                        .map { SongKey(it.provider, it.song_id) }
                        .toSet()
                }.getOrDefault(emptySet())
            }
    }
    return keys
}

/** Offline gate: uncached rows are disabled when there is no connectivity. */
fun canPlay(
    isOnline: Boolean,
    cachedKeys: Set<SongKey>,
    key: SongKey,
): Boolean = isOnline || key in cachedKeys
