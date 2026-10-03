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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import dylan.android.ui.components.rememberFavoriteKeys
import dylan.android.ui.components.rememberIsOnline
import dylan.android.ui.components.rememberSongActions
import dylan.android.ui.components.toArtistEntry
import dylan.di.AppContainer
import dylan.model.MiniEntity
import dylan.model.Song
import dylan.provider.saavn.MiniKind
import dylan.provider.saavn.kind
import dylan.provider.saavn.navigable
import dylan.provider.saavn.rowKey
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
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
    // Saveable: a rotation destroys this Activity (no configChanges in the manifest), and losing
    // the typed query — or the submitted one, whose `LaunchedEffect(submitted)` would then never
    // re-run — throws away work the user cannot get back.
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    val demand = remember { MutableStateFlow("") }
    val actions = rememberSongActions(container)
    val favKeys = rememberFavoriteKeys(container)

    var suggestions by remember { mutableStateOf<List<MiniEntity>>(emptyList()) }
    var recent by remember { mutableStateOf<List<String>>(emptyList()) }
    var topSearches by remember { mutableStateOf<List<MiniEntity>>(emptyList()) }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var total by remember { mutableLongStateOf(0L) }
    var songPage by remember { mutableStateOf(1) }
    var albums by remember { mutableStateOf<List<MiniEntity>>(emptyList()) }
    var albumTotal by remember { mutableLongStateOf(0L) }
    var albumPage by remember { mutableStateOf(1) }
    var artists by remember { mutableStateOf<List<MiniEntity>>(emptyList()) }
    var artistTotal by remember { mutableLongStateOf(0L) }
    var artistPage by remember { mutableStateOf(1) }
    var loadingMore by remember { mutableStateOf(false) }
    // Per-section exhaustion, replacing a single `hasMore` derived from the origin's totals.
    //
    // The totals could not answer this. `deliveredTotal` decides "there may be more" by comparing
    // the *kept* rows against the requested page size, which is only valid when the page contained
    // nothing but the wanted type. The album and artist endpoints return a **mixed** envelope, so a
    // 20-row page might hold 3 albums and 17 songs; `kept (3) >= pageSize (20)` is false, the
    // section declared itself exhausted after one page, and paging silently stopped. Meanwhile songs
    // kept paging and kept growing, because cross-rendition duplicates (`Mockingbird` from three
    // albums) are distinct `songId`s and so count as distinct rows against the budget.
    //
    // Exhaustion is therefore observed rather than predicted: a section is done when a page brings
    // back no rows it did not already have. That needs no assumption about the origin's `total`.
    var songsExhausted by remember { mutableStateOf(false) }
    var albumsExhausted by remember { mutableStateOf(false) }
    var artistsExhausted by remember { mutableStateOf(false) }
    // One mixed, cross-type ranked list: songs + albums + artists share relevance
    // bands (exact → prefix → contains), so an album never hides below its songs.
    val mergedHits =
        remember(results, albums, artists, submitted) {
            val q = submitted ?: return@remember emptyList<Hit>()
            val all = ArrayList<Triple<Int, Int, Hit>>()
            var order = 0
            results.forEach { all += Triple(dylan.search.relevanceBand(q, it.title), order++, Hit.SongHit(it)) }
            albums.forEach { all += Triple(dylan.search.relevanceBand(q, it.title), order++, Hit.AlbumHit(it)) }
            artists.forEach { all += Triple(dylan.search.relevanceBand(q, it.title), order++, Hit.ArtistHit(it)) }
            all.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
        }
    val canLoadMore = !loadingMore && (!songsExhausted || !albumsExhausted || !artistsExhausted)
    val hitsListState =
        androidx.compose.foundation.lazy
            .rememberLazyListState()

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
        demand
            .debounce(container.cfg.wsTypingDebounceMs.toLong())
            .distinctUntilChanged()
            .collect { q ->
                if (submitted != null) return@collect
                // Hand *every* keystroke to the channel, including a cleared or one-char one: the
                // channel owns the socket, and a demand it never hears about is a socket it keeps
                // reading on until its deadline. The channel normalises and releases it.
                container.searchChannel.request(q)
                if (q.trim().length < MIN_SUGGEST_QUERY) suggestions = emptyList()
            }
    }
    LaunchedEffect(Unit) {
        container.searchChannel.suggestions.collect { a ->
            // The channel is the single render gate: it never publishes an answer whose epoch is
            // not the current demand, so the UI no longer re-checks the query against `demand` —
            // it only applies its own "a submit is showing" policy and the minimum-length rule.
            if (a == null || submitted != null || a.query.length < MIN_SUGGEST_QUERY) return@collect
            suggestions = dylan.search.rankMinisDistinct(a.query, a.items)
        }
    }
    LaunchedEffect(submitted) {
        val q = submitted ?: return@LaunchedEffect
        songPage = 1
        albumPage = 1
        artistPage = 1
        loadingMore = false
        // A new query is a new set of budgets: nothing is known exhausted until a page says so.
        songsExhausted = false
        albumsExhausted = false
        artistsExhausted = false
        // Clear first so every submit path (keyboard, chips, suggestions) drops stale hits.
        results = emptyList()
        albums = emptyList()
        artists = emptyList()
        total = 0
        albumTotal = 0
        artistTotal = 0
        // LaunchedEffect is already a CoroutineScope: fetch sections concurrently.
        val songsDef = async { runCatching { container.provider.search(q, 1) }.getOrNull() }
        val albumsDef = async { runCatching { container.provider.searchAlbums(q, 1) }.getOrNull() }
        val artistsDef = async { runCatching { container.provider.searchArtists(q, 1) }.getOrNull() }
        val paged = songsDef.await()
        val seen = HashSet<dylan.model.SongKey>()
        results = paged?.items.orEmpty().filter { seen.add(it.key) }
        total = paged?.total ?: 0L
        val ap = albumsDef.await()
        albums = ap?.items.orEmpty()
        albumTotal = ap?.total ?: 0L
        val rp = artistsDef.await()
        artists = rp?.items.orEmpty()
        artistTotal = rp?.total ?: 0L
    }

    // Extends EVERY section that still has something to give, then the merged list re-ranks so
    // newcomers slot into their relevance band. Driven by scrolling rather than a button, so this is
    // called from a scroll listener and must be cheap and idempotent when re-entered.
    fun loadMore() {
        val q = submitted ?: return
        if (loadingMore || !canLoadMore) return
        loadingMore = true
        scope.launch {
            val nextSong = songPage + 1
            val nextAlbum = albumPage + 1
            val nextArtist = artistPage + 1
            val songsDef = async { if (!songsExhausted) runCatching { container.provider.search(q, nextSong) }.getOrNull() else null }
            val albumsDef = async { if (!albumsExhausted) runCatching { container.provider.searchAlbums(q, nextAlbum) }.getOrNull() else null }
            val artistsDef = async { if (!artistsExhausted) runCatching { container.provider.searchArtists(q, nextArtist) }.getOrNull() else null }
            songsDef.await()?.let { paged ->
                val seen = results.map { it.key }.toHashSet()
                val fresh = paged.items.filter { seen.add(it.key) }
                results = results + fresh
                total = paged.total
                songPage = nextSong
                // A page that adds nothing new is the origin's way of saying it has no more of this
                // type, whatever its `total` claimed.
                if (fresh.isEmpty()) songsExhausted = true
            }
            albumsDef.await()?.let { paged ->
                val seen = albums.map { it.rowKey }.toHashSet()
                val fresh = paged.items.filter { seen.add(it.rowKey) }
                albums = albums + fresh
                albumTotal = paged.total
                albumPage = nextAlbum
                if (fresh.isEmpty()) albumsExhausted = true
            }
            artistsDef.await()?.let { paged ->
                val seen = artists.map { it.rowKey }.toHashSet()
                val fresh = paged.items.filter { seen.add(it.rowKey) }
                artists = artists + fresh
                artistTotal = paged.total
                artistPage = nextArtist
                if (fresh.isEmpty()) artistsExhausted = true
            }
            loadingMore = false
        }
    }

    // Infinite scroll. Reads the *live* layout, not `mergedHits`, so it cannot latch onto a stale
    // item count captured when the effect started; and it keys on `submitted` so a new query
    // re-arms it instead of continuing the previous one's paging.
    LaunchedEffect(hitsListState, submitted) {
        androidx.compose.runtime
            .snapshotFlow {
                val info = hitsListState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                last to info.totalItemsCount
            }.collect { (last, count) ->
                if (count > 0 && last >= count - PREFETCH_ROWS) loadMore()
            }
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
                LazyColumn(state = hitsListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(mergedHits, key = { hitKey(it) }) { hit ->
                        when (hit) {
                            is Hit.SongHit -> {
                                val song = hit.song
                                val can = canPlay(isOnline, cachedKeys, song.key)
                                SongRow(
                                    song = song,
                                    isFavorite = song.key in favKeys,
                                    enabled = can,
                                    onTap = {
                                        if (!can) {
                                            android.widget.Toast
                                                .makeText(ctx, Copy.OFFLINE, android.widget.Toast.LENGTH_SHORT)
                                                .show()
                                        } else {
                                            // Play within the song-only order of the merged list.
                                            val songsOnly = mergedHits.filterIsInstance<Hit.SongHit>().map { it.song }
                                            onPlaySongs(songsOnly, songsOnly.indexOf(song))
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
                            is Hit.AlbumHit -> {
                                MiniRow(hit.mini, badge = "Album") {
                                    hit.mini.albumId?.let { onOpenAlbum(it) }
                                }
                            }
                            is Hit.ArtistHit -> {
                                MiniRow(hit.mini, badge = "Artist") { onOpenArtist(hit.mini) }
                            }
                        }
                    }
                    item {
                        Text(
                            "Songs ${results.size} of $total" +
                                (if (albumTotal > 0) " · Albums ${albums.size} of $albumTotal" else "") +
                                (if (artistTotal > 0) " · Artists ${artists.size} of $artistTotal" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = t.textSecondary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    if (loadingMore || canLoadMore) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = t.primary,
                                )
                            }
                        }
                    }
                }
            suggestions.isNotEmpty() ->
                LazyColumn(Modifier.fillMaxSize()) {
                    // Keyed on the shared, namespaced dedupKey: the old key was
                    // `title + (songId ?: albumId ?: "")`, which collided across the three id
                    // namespaces and degenerated to `title + ""` for playlist/show rows — two
                    // same-titled playlists then crashed Compose with a duplicate key.
                    items(suggestions, key = { m -> "sg-" + m.rowKey }) { m ->
                        MiniRow(m, greyed = !m.navigable) {
                            // Non-navigable rows (playlist / show / episode) have no screen: the
                            // `when` used to fall through and swallow the tap silently.
                            when (val k = m.kind) {
                                is MiniKind.ArtistCard -> onOpenArtist(m)
                                is MiniKind.AlbumCard -> onOpenAlbum(k.id)
                                is MiniKind.SongCard -> {
                                    val q = query.ifBlank { m.title }
                                    query = q
                                    submitted = q
                                }
                                is MiniKind.OtherCard -> Unit
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

/** One row of the mixed submit list: songs and albums/artists ranked together. */
private sealed interface Hit {
    data class SongHit(
        val song: Song,
    ) : Hit

    data class AlbumHit(
        val mini: MiniEntity,
    ) : Hit

    data class ArtistHit(
        val mini: MiniEntity,
    ) : Hit
}

/** Full id tuple, never a title and never "". */
private fun hitKey(hit: Hit): String =
    when (hit) {
        is Hit.SongHit -> "so-" + hit.song.key.provider + ":" + hit.song.key.songId
        is Hit.AlbumHit -> "al-" + hit.mini.rowKey
        is Hit.ArtistHit -> "ar-" + hit.mini.rowKey
    }

/** Below this the origin has nothing useful to suggest and every keystroke is a wasted request. */
private const val MIN_SUGGEST_QUERY = 2

/**
 * How close to the end of the results the user must scroll before the next page is requested.
 *
 * A screenful of runway, roughly: enough that the next page is usually already in flight by the
 * time the user reaches the bottom, without spending a request on a flick that never got there.
 */
private const val PREFETCH_ROWS = 12
