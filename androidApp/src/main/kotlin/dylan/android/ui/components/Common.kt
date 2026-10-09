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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dylan.android.ui.Copy
import dylan.android.ui.LocalDylanTokens
import dylan.di.AppContainer
import dylan.model.SongKey
import kotlinx.coroutines.flow.map
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
 * UI-side online signal: the one predicate, read as a value.
 *
 * This used to answer the question itself, in the UI layer:
 *
 * ```
 * val changes = container.netMonitor.changes()
 * val netClass by changes.collectAsStateWithLifecycle(initialValue = container.netMonitor.current())
 * return remember(netClass) { cm?.activeNetwork != null }
 * ```
 *
 * Two defects in one line, and together they are F-23.
 *
 *  1. It re-implemented a predicate the [NetMonitor] seam already owns, using a *different* test:
 *     `ConnectivityManager.activeNetwork != null` instead of
 *     `NET_CAPABILITY_INTERNET` on the active path. The request path gates on the second
 *     (`ResilientClient`), so the two answered differently for the same device at the same moment.
 *  2. It keyed the answer on `changes()`, which reports only the **metered-ness** of the path — a
 *     two-valued ladder. An outage and a returning mobile-data connection are both `METERED`
 *     (unknown fails closed), so `distinctUntilChanged` swallowed the transition, `remember` never
 *     re-ran, and the verdict latched `false` for the rest of the session. Every request kept
 *     succeeding; every song row in Search rendered dimmed as unplayable.
 *
 * The verdict now comes from [NetMonitor.online], which is emitted on every network event, so
 * there is nothing left for a composition to remember: the value is the state, and the state is
 * updated by the flow.
 */
@Composable
fun rememberIsOnline(container: AppContainer): Boolean {
    val monitor = container.netMonitor
    // One flow instance across recompositions: a new one per recomposition would re-register the
    // network callback on every state change.
    val online = remember(monitor) { monitor.online() }
    val isOnlineNow by online.collectAsStateWithLifecycle(initialValue = monitor.isOnline())
    return isOnlineNow
}

/** UI-side cached-key set from the library downloads (cached_files rows). */
@Composable
fun rememberCachedKeys(container: AppContainer): Set<SongKey> {
    var keys by remember { mutableStateOf(emptySet<SongKey>()) }
    // Keyed on the in-flight key SET, not the progress map: a percentage tick for one song must
    // not invalidate every screen that shows an offline gate, and it must not re-run the query.
    val inflight = rememberDownloadingKeys(container)
    LaunchedEffect(inflight) {
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

/** The set of keys with a transfer in flight; changes only when a download starts or ends. */
@Composable
private fun rememberDownloadingKeys(container: AppContainer): Set<SongKey> {
    val flow = remember(container) { container.downloads.progress.map { it.keys } }
    val keys by flow.collectAsStateWithLifecycle(initialValue = emptySet())
    return keys
}

/**
 * One row's download percentage. Keyed on [key] so another song's ticks cannot invalidate this
 * row: the shared map is a single value, read per row, it made every visible row of every screen
 * recompose four times a second per active download.
 */
@Composable
fun rememberDownloadPct(
    container: AppContainer,
    key: SongKey,
): State<Int?> {
    val flow = remember(container, key) { container.downloads.progress.map { it[key] } }
    return flow.collectAsStateWithLifecycle(initialValue = null)
}

/** Whether any transfer is in flight, as a distinct boolean so a tick is not a recomposition. */
@Composable
fun rememberAnyDownloading(container: AppContainer): State<Boolean> {
    val flow = remember(container) { container.downloads.progress.map { it.isNotEmpty() } }
    return flow.collectAsStateWithLifecycle(initialValue = false)
}

/** Offline gate: uncached rows are disabled when there is no connectivity. */
fun canPlay(
    isOnline: Boolean,
    cachedKeys: Set<SongKey>,
    key: SongKey,
): Boolean = isOnline || key in cachedKeys
