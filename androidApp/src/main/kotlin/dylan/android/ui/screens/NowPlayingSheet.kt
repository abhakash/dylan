package dylan.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dylan.android.DylanApp
import dylan.android.media.AudioRoute
import dylan.android.media.RouteKind
import dylan.android.ui.LocalDylanTokens
import dylan.android.ui.PlayPauseIcon
import dylan.di.AppContainer
import dylan.model.Repeat
import dylan.playback.Intent
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    container: AppContainer,
    onQueue: () -> Unit,
    onOpenArtist: (String, String) -> Unit = { _, _ -> },
    onEnsureService: () -> Unit = {},
) {
    val state by container.orchestrator.state.collectAsState()
    val posMs by container.orchestrator.positionMs.collectAsState(0L)
    val t = LocalDylanTokens.current
    val song = state.current ?: return
    val durMs = (song.durationS * 1000L).coerceAtLeast(1)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // E6: favorite state must survive toggles and stay fresh across screens — key the read on
    // both the song and the repo's invalidation counter instead of a one-shot load.
    val favVersion by container.favorites.version.collectAsState()
    var isFavorite by remember(song.key.songId) { mutableStateOf(false) }
    LaunchedEffect(song.key.songId, favVersion) {
        isFavorite = runCatching { container.favorites.isFavorite(song.key) }.getOrDefault(false)
    }
    // Real cached bitrate for the quality chip — never a hardcoded guess.
    var bitsLabel by remember(song.key.songId) { mutableStateOf("") }
    LaunchedEffect(song.key.songId) {
        val row =
            kotlinx.coroutines.withContext(container.disp.dbLane) {
                runCatching {
                    container.db.dylanQueries
                        .selectCached(song.key.provider, song.key.songId)
                        .executeAsOneOrNull()
                }.getOrNull()
            }
        bitsLabel = row?.bitrate?.let { "${it}kbps" } ?: ""
    }

    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    val isLoading = state.phase is dylan.model.Phase.Resolving || state.phase is dylan.model.Phase.Downloading
    val shown =
        if (isLoading) {
            0f
        } else if (dragging) {
            dragPos
        } else {
            posMs.toFloat()
        }
    val downloadProgress by container.downloads.progress.collectAsState()
    val currentDownloadPct = downloadProgress[state.current?.key]

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        AsyncImage(
            model = song.artUrl500,
            contentDescription = null,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .pointerInput(state.index) {
                        detectHorizontalDragGestures { change, amount ->
                            if (kotlin.math.abs(amount) > 60f) {
                                container.orchestrator.submit(if (amount < 0) Intent.Next else Intent.Previous)
                            }
                        }
                    },
        )
        Spacer(Modifier.height(16.dp))
        Text(
            song.title.uppercase(),
            style = MaterialTheme.typography.displayLarge,
            color = t.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.basicMarquee(),
        )
        Text(
            (song.subtitle.ifBlank { song.albumName.orEmpty() }).uppercase(),
            style = MaterialTheme.typography.bodyLarge,
            color = t.textSecondary,
            maxLines = 1,
            modifier =
                when (val artistToken = song.artistToken) {
                    null -> Modifier
                    else -> Modifier.clickable { onOpenArtist(song.artistName ?: song.subtitle, artistToken) }
                },
        )

        // Prominent download/prepare status — replaces subtle label that was easy to miss
        if (isLoading) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(t.surfaceVariant)
                    .border(1.dp, t.divider)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = t.primary,
                    strokeWidth = 2.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        when (state.phase) {
                            is dylan.model.Phase.Downloading -> "DOWNLOADING… ${currentDownloadPct ?: 0}%"
                            else -> "PREPARING…"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                        color = t.textPrimary,
                    )
                    if (currentDownloadPct != null) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { (currentDownloadPct ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth().height(2.dp).padding(top = 4.dp),
                            color = t.primary,
                            trackColor = t.divider,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Slider(
            value = shown.coerceIn(0f, durMs.toFloat()),
            onValueChange = {
                if (isLoading) return@Slider
                dragging = true
                dragPos = it
            },
            onValueChangeFinished = {
                container.orchestrator.submit(Intent.Seek(dragPos.toLong()))
                dragging = false
            },
            enabled = !isLoading,
            valueRange = 0f..durMs.toFloat(),
            colors =
                SliderDefaults.colors(
                    thumbColor = t.primary,
                    activeTrackColor = t.primary,
                    inactiveTrackColor = t.divider,
                    activeTickColor = androidx.compose.ui.graphics.Color.Transparent,
                    inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledThumbColor = t.divider,
                    disabledActiveTrackColor = t.divider,
                    disabledInactiveTrackColor = t.divider,
                ),
            thumb = {
                Box(
                    Modifier
                        .size(if (dragging) 22.dp else 18.dp)
                        .background(if (isLoading) t.divider else t.primary)
                        .padding(3.dp)
                        .background(androidx.compose.ui.graphics.Color.White),
                )
            },
            track = { sliderState ->
                val range = sliderState.valueRange
                val frac =
                    if (range.endInclusive > range.start) {
                        ((sliderState.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                Box(Modifier.fillMaxWidth().height(if (dragging) 6.dp else 4.dp).background(t.divider)) {
                    Box(
                        Modifier
                            .fillMaxWidth(frac)
                            .fillMaxHeight()
                            .background(if (isLoading) t.divider else t.primary),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().height(36.dp).padding(top = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatTime(shown.toLong()),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color =
                    if (dragging) {
                        t.primary
                    } else if (isLoading) {
                        t.textSecondary
                    } else {
                        t.textPrimary
                    },
            )
            Text(
                formatTime(durMs),
                style = MaterialTheme.typography.bodyMedium,
                color = t.textSecondary,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { container.orchestrator.submit(Intent.ToggleShuffle) }) {
                Icon(dylan.android.ui.Dyl.Shuffle, "Shuffle", tint = if (state.shuffleOn) t.primary else t.textPrimary)
            }
            IconButton(onClick = { container.orchestrator.submit(Intent.Previous) }) {
                Icon(dylan.android.ui.Dyl.Prev, "Previous", tint = t.textPrimary)
            }
            Box(
                Modifier
                    .size(64.dp)
                    .background(t.primary)
                    .clickable {
                        onEnsureService()
                        container.orchestrator.submit(Intent.TogglePlayPause)
                    },
                contentAlignment = Alignment.Center,
            ) {
                PlayPauseIcon(container, size = 48, onEnsureService = onEnsureService)
            }
            IconButton(onClick = { container.orchestrator.submit(Intent.Next) }) {
                Icon(dylan.android.ui.Dyl.Next, "Next", tint = t.textPrimary)
            }
            IconButton(onClick = { container.orchestrator.submit(Intent.CycleRepeat) }) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Icon(dylan.android.ui.Dyl.Repeat, "Repeat", tint = if (state.repeat != Repeat.OFF) t.primary else t.textPrimary)
                    if (state.repeat == Repeat.ONE) {
                        Text(
                            "1",
                            style = MaterialTheme.typography.labelSmall,
                            color = t.primary,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
        }

        val route = rememberAudioRoute()
        if (route != null && route.kind != RouteKind.SPEAKER) {
            Spacer(Modifier.height(10.dp))
            Text(
                "PLAYING ON ${route.productName?.uppercase() ?: when (route.kind) {
                    RouteKind.BLUETOOTH -> "BLUETOOTH"
                    RouteKind.WIRED -> "HEADPHONES"
                    RouteKind.SPEAKER -> ""
                }}",
                style = MaterialTheme.typography.labelSmall,
                color = t.textSecondary,
                maxLines = 1,
                modifier =
                    Modifier
                        .border(1.dp, t.divider)
                        .background(t.background)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onQueue) { Icon(dylan.android.ui.Dyl.Queue, "Queue", tint = t.textSecondary) }
            IconButton(onClick = {
                scope.launch {
                    runCatching {
                        if (container.favorites.isFavorite(song.key)) {
                            container.favorites.remove(song.key)
                            isFavorite = false
                        } else {
                            container.favorites.add(song)
                            isFavorite = true
                        }
                    }
                }
            }) {
                Icon(
                    if (isFavorite) dylan.android.ui.Dyl.Heart else dylan.android.ui.Dyl.HeartOutline,
                    "Favorite",
                    tint = if (isFavorite) t.primary else t.textSecondary,
                )
            }
            Text(
                when (val p = state.phase) {
                    is dylan.model.Phase.Downloading -> "SAVING ${p.key.songId.uppercase()}…"
                    is dylan.model.Phase.Resolving -> "PREPARING…"
                    is dylan.model.Phase.Error ->
                        dylan.android.ui.Copy
                            .forCode(p.failure.code)
                    is dylan.model.Phase.Playing, is dylan.model.Phase.Paused -> bitsLabel.uppercase()
                    else -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = t.textSecondary,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun rememberAudioRoute(): AudioRoute? {
    val app = LocalContext.current.applicationContext as? DylanApp
    val flow = remember(app) { app?.mediaHub?.audioRoute }
    return flow?.collectAsState()?.value
}
