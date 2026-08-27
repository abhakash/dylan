package dylan.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dylan.android.ui.Copy
import dylan.android.ui.LocalDylanTokens
import dylan.android.ui.components.MiniRow
import dylan.android.ui.components.SectionTitle
import dylan.android.ui.components.SongRow
import dylan.android.ui.components.canPlay
import dylan.android.ui.components.rememberCachedKeys
import dylan.android.ui.components.rememberIsOnline
import dylan.android.ui.components.rememberFavoriteKeys
import dylan.android.ui.components.rememberSongActions
import dylan.android.ui.components.toArtistEntry
import dylan.di.AppContainer
import dylan.model.MiniEntity
import dylan.model.Song
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@Composable
fun SearchScreen(
    container: AppContainer,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (MiniEntity) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
) {
    val t = LocalDylanTokens.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf<String?>(null) }
    val demand = remember { MutableStateFlow("") }
    val actions = rememberSongActions(container)
    val favKeys = rememberFavoriteKeys(container)

    var suggestions by remember { mutableStateOf<List<MiniEntity>>(emptyList()) }
    var recent by remember { mutableStateOf<List<String>>(emptyList()) }
    var topSearches by remember { mutableStateOf<List<MiniEntity>>(emptyList()) }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var total by remember { mutableLongStateOf(0L) }
    val ctx = LocalContext.current
    val isOnline = rememberIsOnline(container)
    val cachedKeys = rememberCachedKeys(container)

    LaunchedEffect(Unit) {
        recent = runCatching { container.searchHistory.recent() }.getOrDefault(emptyList())
        topSearches = runCatching { container.provider.topSearches() }.getOrDefault(emptyList())
    }
    // §6.4 connect trigger: tab entry, not first keystroke.
    LaunchedEffect(Unit) { container.searchChannel.warmUp() }
    // Fire-and-forget demand; answers render on arrival (never blocks typing).
    LaunchedEffect(Unit) {
        demand.debounce(120).distinctUntilChanged().collect { q ->
            when {
                submitted != null -> {}
                q.length >= 2 -> container.searchChannel.request(q)
                else -> suggestions = emptyList()
            }
        }
    }
    LaunchedEffect(Unit) {
        container.searchChannel.suggestions.collect { ans ->
            val (q, list) = ans ?: return@collect
            if (submitted != null || q != demand.value || q.length < 2) return@collect
            // Server repeats entries across buckets/keystrokes [verified: 7.har] — dedupe or LazyColumn keys collide.
            suggestions =
                list.distinctBy { it.title to (it.songKey?.songId ?: it.albumId.orEmpty()) }
        }
    }
    LaunchedEffect(submitted) {
        val q = submitted ?: return@LaunchedEffect
        val paged = runCatching { container.provider.search(q, 1) }.getOrNull()
        results = paged?.items.orEmpty().distinctBy { it.key }
        total = paged?.total ?: 0L
    }

    Column(Modifier.fillMaxSize()) {
        TextField(
            value = query,
            onValueChange = { q ->
                query = q
                if (submitted != null && q != submitted) submitted = null
                demand.value = q
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
            placeholder = { Text("Search songs, albums…") },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = t.surfaceVariant,
                    unfocusedContainerColor = t.surfaceVariant,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            keyboardActions =
                androidx.compose.foundation.text.KeyboardActions(onSearch = {
                    if (query.isNotBlank()) {
                        submitted = query
                        results = emptyList()
                        scope.launch { runCatching { container.searchHistory.record(query) } }
                    }
                }),
            keyboardOptions =
                androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                ),
        )

        when {
            submitted != null ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(results, key = { it.key.songId }) { song ->
                        val can = canPlay(isOnline, cachedKeys, song.key)
                        SongRow(
                            song = song,
                            isFavorite = song.key in favKeys,
                            enabled = can,
                            onTap = {
                                if (!can) {
                                    android.widget.Toast.makeText(ctx, Copy.OFFLINE, android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    onPlaySongs(results, results.indexOf(song))
                                }
                            },
                            onPlayNext = { actions.playNext(song) },
                            onAddLast = { actions.addToQueue(song) },
                            onFavorite = { actions.toggleFavorite(song) },
                            onGoToArtist =
                                if (song.artistToken != null) {
                                    { onOpenArtist(song.toArtistEntry()) }
                                } else {
                                    null
                                },
                        )
                    }
                    item {
                        Text(
                            "${results.size} of $total",
                            style = MaterialTheme.typography.labelSmall,
                            color = t.textSecondary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            suggestions.isNotEmpty() ->
                LazyColumn(Modifier.fillMaxSize()) {
                    items(suggestions, key = { it.title + (it.songKey?.songId ?: it.albumId ?: it.artistId ?: "") }) { m ->
                        MiniRow(m, greyed = false) {
                            val albumTarget = m.albumId
                            when {
                                m.artistId != null -> onOpenArtist(m)
                                albumTarget != null -> onOpenAlbum(albumTarget)
                                m.songKey != null -> {
                                    val q = query.ifBlank { m.title }
                                    query = q
                                    submitted = q
                                }
                            }
                        }
                    }
                }
            else ->
                Column(Modifier.fillMaxSize()) {
                    if (recent.isNotEmpty()) {
                        SectionTitle("Recent searches")
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            recent.forEach { chipText ->
                                Box(
                                    Modifier
                                        .background(t.surfaceVariant, MaterialTheme.shapes.large)
                                        .clickable {
                                            query = chipText
                                            submitted = chipText
                                            demand.value = chipText
                                        }.padding(horizontal = 14.dp, vertical = 8.dp),
                                ) { Text(chipText, style = MaterialTheme.typography.bodyMedium, color = t.textPrimary) }
                            }
                        }
                    }
                    if (topSearches.isNotEmpty()) {
                        SectionTitle("Top searches")
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            topSearches.take(10).forEach { m ->
                                Box(
                                    Modifier
                                        .background(t.surfaceVariant, MaterialTheme.shapes.large)
                                        .clickable {
                                            query = m.title
                                            submitted = m.title
                                            demand.value = m.title
                                        }.padding(horizontal = 14.dp, vertical = 8.dp),
                                ) { Text(m.title, style = MaterialTheme.typography.bodyMedium, color = t.textPrimary) }
                            }
                        }
                    }
                }
        }
    }
}
