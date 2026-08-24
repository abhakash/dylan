package dylan.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dylan.android.ui.Dyl
import dylan.android.ui.LocalDylanTokens
import dylan.di.AppContainer
import dylan.model.Song
import dylan.playback.Intent

@Composable
fun QueueSheet(container: AppContainer) {
    val state by container.orchestrator.state.collectAsState()
    val t = LocalDylanTokens.current

    // A live drag renders a local snapshot so rows track the finger instantly; every crossing
    // also submits MoveWithinQueue, so the shared queue converges by drag end.
    var dragging by remember { mutableStateOf(false) }
    var dragQueue by remember { mutableStateOf<List<Song>>(emptyList()) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeights = remember { mutableMapOf<String, Int>() }

    val queue = if (dragging) dragQueue else state.queue
    val keys = remember(queue) { uniqueKeys(queue) }
    val currentIdx = queue.indexOfFirst { it.key == state.current?.key }
    val resistPx = with(LocalDensity.current) { 48.dp.toPx() }
    val endDrag: () -> Unit = {
        dragging = false
        draggedKey = null
        dragFrom = -1
        dragOffset = 0f
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Up Next", style = MaterialTheme.typography.titleLarge, color = t.textPrimary)
            if (state.index + 1 < state.queue.size) {
                Text(
                    "Clear",
                    style = MaterialTheme.typography.bodyMedium,
                    color = t.primary,
                    modifier = Modifier.clickable { container.orchestrator.submit(Intent.ClearUpNext) },
                )
            }
        }
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            itemsIndexed(queue, key = { i, _ -> keys[i] }) { i, song ->
                val key = keys[i]
                val isCurrent = i == currentIdx
                val isDragged = key == draggedKey
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .then(if (isDragged) Modifier.zIndex(1f) else Modifier)
                        .graphicsLayer { if (isDragged) translationY = dragOffset }
                        .onSizeChanged { rowHeights[key] = it.height },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isCurrent || isDragged) t.primary else t.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!isCurrent) {
                        Icon(
                            Dyl.MoreVert,
                            contentDescription = "Reorder",
                            tint = t.textSecondary,
                            modifier =
                                Modifier
                                    .padding(4.dp)
                                    .pointerInput(key) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                if (draggedKey != null) return@detectDragGesturesAfterLongPress
                                                val q = state.queue
                                                val idx = uniqueKeys(q).indexOf(key)
                                                if (idx <= q.indexOfFirst { it.key == state.current?.key }) {
                                                    return@detectDragGesturesAfterLongPress
                                                }
                                                dragging = true
                                                dragQueue = q
                                                draggedKey = key
                                                dragFrom = idx
                                                dragOffset = 0f
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                if (draggedKey != key) return@detectDragGesturesAfterLongPress
                                                val moved =
                                                    stepOverNeighbours(
                                                        container = container,
                                                        from = dragFrom,
                                                        offsetIn = dragOffset + amount.y,
                                                        queue = dragQueue,
                                                        rowHeights = rowHeights,
                                                        currentKey = state.current?.key,
                                                        resistPx = resistPx,
                                                    )
                                                dragQueue = moved.list
                                                dragFrom = moved.index
                                                dragOffset = moved.offset
                                            },
                                            onDragEnd = {
                                                if (draggedKey == key) endDrag()
                                            },
                                            onDragCancel = {
                                                if (draggedKey == key) endDrag()
                                            },
                                        )
                                    },
                        )
                        IconButton(onClick = { container.orchestrator.submit(Intent.RemoveAt(i)) }) {
                            Icon(Dyl.Close, "Remove", tint = t.textSecondary)
                        }
                    } else {
                        Text(
                            "Now playing",
                            style = MaterialTheme.typography.labelSmall,
                            color = t.primary,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

private data class DragStep(
    val list: List<Song>,
    val index: Int,
    val offset: Float,
)

/** Steps the dragged row across neighbours one adjacent MoveWithinQueue at a time. */
private fun stepOverNeighbours(
    container: AppContainer,
    from: Int,
    offsetIn: Float,
    queue: List<Song>,
    rowHeights: Map<String, Int>,
    currentKey: dylan.model.SongKey?,
    resistPx: Float,
): DragStep {
    var q = queue
    var cur = from
    var offset = offsetIn
    while (cur + 1 < q.size) {
        val h = rowHeights[uniqueKeys(q)[cur + 1]] ?: break
        if (offset <= h / 2f) break
        container.orchestrator.submit(Intent.MoveWithinQueue(cur, cur + 1))
        q = q.toMutableList().also { it.add(cur + 1, it.removeAt(cur)) }
        cur++
        offset -= h
    }
    // The playing row is anchored — nothing may rise above next-of-current.
    val floor = (q.indexOfFirst { it.key == currentKey } + 1).coerceAtLeast(0)
    while (cur - 1 >= floor) {
        val h = rowHeights[uniqueKeys(q)[cur - 1]] ?: break
        if (-offset <= h / 2f) break
        container.orchestrator.submit(Intent.MoveWithinQueue(cur, cur - 1))
        q = q.toMutableList().also { it.add(cur - 1, it.removeAt(cur)) }
        cur--
        offset += h
    }
    if (cur >= q.size - 1) offset = minOf(offset, resistPx)
    if (cur <= floor) offset = maxOf(offset, -resistPx)
    return DragStep(q, cur, offset)
}

private fun uniqueKeys(queue: List<Song>): List<String> {
    val seen = HashMap<String, Int>(queue.size)
    return queue.map { s ->
        val base = s.key.provider + ":" + s.key.songId
        val n = (seen[base] ?: 0) + 1
        seen[base] = n
        if (n == 1) base else "$base#$n"
    }
}
