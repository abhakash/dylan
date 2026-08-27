package dylan.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dylan.android.ui.Dyl
import dylan.android.ui.LocalDylanTokens
import dylan.model.MiniEntity
import dylan.model.Song

@Composable
fun SongRow(
    song: Song,
    index: Int? = null,
    isPlaying: Boolean = false,
    isCached: Boolean = false,
    isFavorite: Boolean = false,
    progressPct: Int? = null,
    enabled: Boolean = true,
    sizeLabel: String? = null,
    onTap: () -> Unit,
    onPlayNext: (() -> Unit)? = null,
    onAddLast: (() -> Unit)? = null,
    onFavorite: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onRemoveDownload: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val t = LocalDylanTokens.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onTap,
                    onLongClick = { menuOpen = true },
                    enabled = enabled,
                ).alpha(if (enabled) 1f else 0.45f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        index?.let {
            Text(
                "$it",
                style = MaterialTheme.typography.labelSmall,
                color = t.textSecondary,
                modifier = Modifier.size(20.dp).align(Alignment.CenterVertically),
            )
        }
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .background(t.divider)
                    .padding(1.dp)
                    .background(t.background),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = song.artUrl150,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isPlaying) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            androidx.compose.ui.graphics.Color.Black
                                .copy(alpha = 0.55f),
                        ),
                    contentAlignment = Alignment.Center,
                ) { EqBars() }
            } else if (progressPct != null) {
                DownloadRing(progressPct)
            } else if (isCached) {
                Box(Modifier.align(Alignment.TopEnd).padding(3.dp)) { CachedGlyph() }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title.uppercase(),
                style = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 0.2.sp),
                color = if (isPlaying) t.primary else t.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (song.subtitle.ifBlank { song.albumName.orEmpty() }).uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                color = t.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (sizeLabel != null) {
            Text(sizeLabel.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp), color = t.textSecondary)
        }
        IconButton(onClick = { menuOpen = true }) {
            Icon(Dyl.MoreVert, contentDescription = "More options", tint = t.textSecondary)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            onPlayNext?.let {
                DropdownMenuItem(text = { Text("Play next") }, onClick = {
                    menuOpen = false
                    it()
                })
            }
            onAddLast?.let {
                DropdownMenuItem(text = { Text("Add to queue") }, onClick = {
                    menuOpen = false
                    it()
                })
            }
            onFavorite?.let {
                DropdownMenuItem(
                    text = { Text(if (isFavorite) "Unfavorite" else "Favorite") },
                    onClick = {
                        menuOpen = false
                        it()
                    },
                )
            }
            onGoToArtist?.let {
                DropdownMenuItem(text = { Text("Go to artist") }, onClick = {
                    menuOpen = false
                    it()
                })
            }
            onDownload?.let {
                DropdownMenuItem(text = { Text("Download now") }, onClick = {
                    menuOpen = false
                    it()
                })
            }
            onRemoveDownload?.let {
                DropdownMenuItem(text = { Text("Remove download") }, onClick = {
                    menuOpen = false
                    it()
                })
            }
        }
    }
}

@Composable
fun MiniRow(
    mini: MiniEntity,
    greyed: Boolean = false,
    onTap: () -> Unit,
) {
    val t = LocalDylanTokens.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !greyed, onClick = onTap)
                .alpha(if (greyed) 0.5f else 1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .background(t.divider)
                .padding(1.dp)
                .background(t.background),
        ) {
            AsyncImage(model = mini.image, contentDescription = null, modifier = Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(1f)) {
            Text(
                mini.title.uppercase(),
                style = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 0.2.sp),
                color = t.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                mini.subtitle.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                color = t.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EqBars() {
    val t = LocalDylanTokens.current
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(width = 4.dp, height = (6 + i * 4).dp)
                    .background(t.primary),
            )
        }
    }
}

@Composable
private fun CachedGlyph() {
    val t = LocalDylanTokens.current
    Box(
        Modifier
            .size(7.dp)
            .background(t.primary),
    )
}

@Composable
fun DownloadRing(pct: Int) {
    val t = LocalDylanTokens.current
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.55f))) {
        Text(
            "${pct.coerceIn(0, 99)}%",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
            color = t.textPrimary,
        )
    }
}
