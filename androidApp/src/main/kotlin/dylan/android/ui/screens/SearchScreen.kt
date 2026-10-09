package dylan.android.ui.screens

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
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
import dylan.android.ui.components.SongActions
import dylan.android.ui.components.SongRow
import dylan.android.ui.components.canPlay
import dylan.android.ui.components.rememberCachedKeys
import dylan.android.ui.components.rememberFavoriteKeys
import dylan.android.ui.components.rememberIsOnline
import dylan.android.ui.components.rememberSongActions
import dylan.android.ui.components.toArtistEntry
import dylan.di.AppContainer
import dylan.model.MiniEntity
import dylan.model.Paged
import dylan.model.Song
import dylan.model.SongKey
import dylan.provider.saavn.MiniKind
import dylan.provider.saavn.kind
import dylan.provider.saavn.navigable
import dylan.provider.saavn.rowKey
import dylan.search.SearchSections
import dylan.search.settledSubmitQuery
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Saveable: a rotation destroys this Activity (no configChanges in the manifest), and losing
    // the typed query — or the submitted one, whose `LaunchedEffect(submitted, submitEpoch)` would
    // then never re-run — throws away work the user cannot get back.
    var query by rememberSaveable { mutableStateOf("") }
    // Held as a state in its own right so the suggestion feed can read the *current* submitted query
    // from inside its own long-lived collectors.
    val submittedState = rememberSaveable { mutableStateOf<String?>(null) }
    var submitted by submittedState
    // How many times a submit has happened — the thing the fetch is keyed on, deliberately **not**
    // the query. Keying it on the query string cannot tell "the user searched for something else"
    // from "the user searched for the same thing again", and only the second one used to blank the
    // list with nothing scheduled to refill it: the rows were dropped synchronously in the submit
    // lambda while the refetch effect, keyed on a string that had not changed, never re-ran. A
    // counter moves on every submit, repeats included, so every submit is a fetch. Saveable with
    // `submitted`, for the rotation reason above: after a rotation the list must still refill.
    var submitEpoch by rememberSaveable { mutableLongStateOf(0L) }
    val demand = remember { MutableStateFlow("") }

    var recent by remember { mutableStateOf<List<String>>(emptyList()) }
    var topSearches by remember { mutableStateOf<List<MiniEntity>>(emptyList()) }
    val suggestions by rememberSuggestions(container, demand, submittedState)

    // The submitted list's rows and its scroll position are owned here, not by [SearchResults]: the
    // keyboard's submit has to drop the rows synchronously — before the refetch effect has run — and
    // the scroll position has to survive the list being unmounted while the query is edited away.
    val results = remember { ResultState() }
    val hitsListState = rememberLazyListState()

    val isOnline = rememberIsOnline(container)
    val cachedKeys = rememberCachedKeys(container)
    val favKeys = rememberFavoriteKeys(container)

    // F-24: a typed query that has settled is submitted. See [settledSubmitQuery] for why, and for
    // the trade-off. Every submit path below goes through [submitQuery] so they all set the same
    // things in the same order — flag the fetch, drop the rows, move the query, move the epoch — and
    // the settle path cannot drift from the Enter path.
    fun submitQuery(
        q: String,
        record: Boolean = false,
    ) {
        results.beginSubmit()
        results.clearSongs()
        query = q
        submittedState.value = q
        submitEpoch = submitEpoch + 1
        demand.value = q
        if (record) scope.launch { runCatching { container.searchHistory.record(q) } }
    }

    // Reads the typed text and never writes it, so the settle cannot race a keystroke: `demand` is
    // what the keystrokes publish, and this collector only ever asks a question of the answer.
    LaunchedEffect(Unit) {
        demand
            .debounce(SUBMIT_SETTLE_MS)
            .distinctUntilChanged()
            .collect { q ->
                settledSubmitQuery(q, submittedState.value, MIN_SUBMIT_QUERY)?.let { submitQuery(it) }
            }
    }

    LaunchedEffect(Unit) {
        recent = runCatching { container.searchHistory.recent() }.getOrDefault(emptyList())
        topSearches = runCatching { container.provider.topSearches() }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize()) {
        SearchField(
            query = query,
            onQueryChange = { q ->
                query = q
                if (submitted != null && q != submitted) submitted = null
                demand.value = q
            },
            onSubmit = { q ->
                if (q.isNotBlank()) submitQuery(q, record = true)
            },
        )

        // A local val, because `submitted` is written from the lambdas above and so cannot be smart
        // cast inside this `when`.
        val submittedQuery = submitted
        when {
            submittedQuery != null ->
                SearchResults(
                    container = container,
                    submitted = submittedQuery,
                    submitEpoch = submitEpoch,
                    results = results,
                    listState = hitsListState,
                    isOnline = isOnline,
                    cachedKeys = cachedKeys,
                    favKeys = favKeys,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                    onPlaySongs = onPlaySongs,
                )
            suggestions.isNotEmpty() ->
                SearchSuggestions(
                    suggestions = suggestions,
                    query = query,
                    onSubmitQuery = { q ->
                        // A tap on a suggestion is a submit: same steps as the keyboard's.
                        submitQuery(q)
                    },
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                )
            else ->
                SearchLanding(
                    recent = recent,
                    topSearches = topSearches,
                    onPick = { q ->
                        submitQuery(q)
                    },
                )
        }
    }
}

/**
 * What the channel is answering for the query as it is typed.
 *
 * All three effects belong together: the socket is opened when the tab opens, every keystroke is
 * handed to it as a demand, and the answers are the suggestions. The state lives here because the
 * producers and the only consumer — the choice between the suggestions and the landing tab — are all
 * in this file; [SearchScreen] reads it as a [State] so the write invalidates the tab, not just this
 * function.
 */
@Composable
private fun rememberSuggestions(
    container: AppContainer,
    demand: MutableStateFlow<String>,
    submitted: MutableState<String?>,
): State<List<MiniEntity>> {
    val suggestions = remember { mutableStateOf<List<MiniEntity>>(emptyList()) }
    // §6.4 connect trigger: tab entry, not first keystroke.
    LaunchedEffect(Unit) { container.searchChannel.warmUp() }
    // Fire-and-forget demand; answers render on arrival (never blocks typing).
    LaunchedEffect(Unit) {
        demand
            .debounce(container.cfg.wsTypingDebounceMs.toLong())
            .distinctUntilChanged()
            .collect { q ->
                if (submitted.value != null) return@collect
                // Hand *every* keystroke to the channel, including a cleared or one-char one: the
                // channel owns the socket, and a demand it never hears about is a socket it keeps
                // reading on until its deadline. The channel normalises and releases it.
                container.searchChannel.request(q)
                if (q.trim().length < MIN_SUGGEST_QUERY) suggestions.value = emptyList()
            }
    }
    LaunchedEffect(Unit) {
        container.searchChannel.suggestions.collect { a ->
            // The channel is the single render gate: it never publishes an answer whose epoch is
            // not the current demand, so the UI no longer re-checks the query against `demand` —
            // it only applies its own "a submit is showing" policy and the minimum-length rule.
            if (a == null || submitted.value != null || a.query.length < MIN_SUGGEST_QUERY) return@collect
            suggestions.value = dylan.search.rankMinisDistinct(a.query, a.items)
        }
    }
    return suggestions
}

/** The query field: typing, submitting from the keyboard, and nothing else. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
) {
    val t = LocalDylanTokens.current
    TextField(
        value = query,
        onValueChange = onQueryChange,
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
            androidx.compose.foundation.text.KeyboardActions(
                onSearch = { if (query.isNotBlank()) onSubmit(query) },
            ),
        keyboardOptions =
            androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
            ),
    )
}

/**
 * The submitted list: the three sections merged into one cross-type ranked list, and the paging that
 * keeps it growing.
 *
 * Its state is a [ResultState] owned by [SearchScreen]; only the work — merging, paging, fetching —
 * happens here.
 */
@Composable
private fun SearchResults(
    container: AppContainer,
    submitted: String,
    submitEpoch: Long,
    results: ResultState,
    listState: LazyListState,
    isOnline: Boolean,
    cachedKeys: Set<SongKey>,
    favKeys: Set<SongKey>,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (MiniEntity) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val hits =
        remember(results.songs, results.albums, results.artists, submitted) {
            mergeHits(submitted, results.songs, results.albums, results.artists)
        }

    // Keyed on the submit **epoch**, not on the query. With `submitted` alone, a repeated submit of
    // the same text left the key unchanged and this block never re-ran — while the submit lambda had
    // already dropped the rows, so the list went blank with nothing scheduled to refill it. The
    // first submit is not a double fetch: this composable does not exist at all until `submitted` is
    // non-null, so entering it runs the block once whatever the keys are.
    //
    // `submitted` stays in the key because it is the value being fetched, and it costs nothing:
    // every path that changes it also moves the epoch.
    LaunchedEffect(submitted, submitEpoch) {
        // A new submit is a new set of budgets: nothing is known exhausted until a page says so, and
        // the stale hits are dropped first so every submit path (keyboard, chips, suggestions) starts
        // from nothing.
        results.reset()
        results.fetchFirst(container, submitted)
    }

    // Driven by scrolling rather than by a button, so this is called from a scroll listener and must
    // be cheap and idempotent when re-entered. The guard is read live rather than captured: this
    // closure belongs to one composition, and a repeated submit re-arms nothing here.
    fun loadMore() {
        if (!results.canExtend()) return
        results.loadingMore = true
        scope.launch {
            results.extend(container, submitted)
            results.loadingMore = false
        }
    }

    // Infinite scroll. Reads the *live* layout, not `hits`, so it cannot latch onto a stale item
    // count captured when the effect started; and it keys on `submitted` so a new query re-arms it
    // instead of continuing the previous one's paging. It deliberately does **not** key on
    // [submitEpoch]: there is nothing to re-arm for a repeated submit of the same query, because
    // `loadMore` asks [ResultState.canExtend] at call time and so already sees the fresh budgets.
    LaunchedEffect(listState, submitted) {
        androidx.compose.runtime
            .snapshotFlow {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                last to info.totalItemsCount
            }.collect { (last, count) ->
                if (count > 0 && last >= count - PREFETCH_ROWS) loadMore()
            }
    }

    SearchResultsList(
        container = container,
        listState = listState,
        hits = hits,
        results = results,
        showSpinner = results.loadingMore || results.growing,
        isOnline = isOnline,
        cachedKeys = cachedKeys,
        favKeys = favKeys,
        onOpenAlbum = onOpenAlbum,
        onOpenArtist = onOpenArtist,
        onPlaySongs = onPlaySongs,
    )
}

/** The merged hits as rows, plus the running count and the paging spinner. */
@Composable
private fun SearchResultsList(
    container: AppContainer,
    listState: LazyListState,
    hits: List<Hit>,
    results: ResultState,
    showSpinner: Boolean,
    isOnline: Boolean,
    cachedKeys: Set<SongKey>,
    favKeys: Set<SongKey>,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (MiniEntity) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
) {
    val t = LocalDylanTokens.current
    val ctx = LocalContext.current
    val actions = rememberSongActions(container)
    val onPlayHit = { song: Song ->
        // Play within the song-only order of the merged list.
        val songsOnly = hits.filterIsInstance<Hit.SongHit>().map { it.song }
        onPlaySongs(songsOnly, songsOnly.indexOf(song))
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(hits, key = { hitKey(it) }) { hit ->
            HitRow(
                hit = hit,
                ctx = ctx,
                actions = actions,
                favKeys = favKeys,
                isOnline = isOnline,
                cachedKeys = cachedKeys,
                onPlayHit = onPlayHit,
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist,
            )
        }
        item {
            ResultFooter(
                ResultCounts(
                    songs = results.songs.size,
                    songTotal = results.songTotal,
                    albums = results.albums.size,
                    albumTotal = results.albumTotal,
                    artists = results.artists.size,
                    artistTotal = results.artistTotal,
                ),
            )
        }
        if (showSpinner) {
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
}

/** One row of the merged list: a song, an album or an artist, each with its own affordances. */
@Composable
private fun HitRow(
    hit: Hit,
    ctx: Context,
    actions: SongActions,
    favKeys: Set<SongKey>,
    isOnline: Boolean,
    cachedKeys: Set<SongKey>,
    onPlayHit: (Song) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (MiniEntity) -> Unit,
) {
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
                        onPlayHit(song)
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

/** How much of each section has arrived, against what the origin claims there is. */
private data class ResultCounts(
    val songs: Int,
    val songTotal: Long,
    val albums: Int,
    val albumTotal: Long,
    val artists: Int,
    val artistTotal: Long,
)

@Composable
private fun ResultFooter(counts: ResultCounts) {
    val t = LocalDylanTokens.current
    Text(
        "Songs ${counts.songs} of ${counts.songTotal}" +
            (if (counts.albumTotal > 0) " · Albums ${counts.albums} of ${counts.albumTotal}" else "") +
            (if (counts.artistTotal > 0) " · Artists ${counts.artists} of ${counts.artistTotal}" else ""),
        style = MaterialTheme.typography.labelSmall,
        color = t.textSecondary,
        modifier = Modifier.padding(16.dp),
    )
}

/** The channel's suggestions, while nothing has been submitted yet. */
@Composable
private fun SearchSuggestions(
    suggestions: List<MiniEntity>,
    query: String,
    onSubmitQuery: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (MiniEntity) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        // Keyed on the shared, namespaced dedupKey: the old key was `title + (songId ?: albumId ?:
        // "")`, which collided across the three id namespaces and degenerated to `title + ""` for
        // playlist/show rows — two same-titled playlists then crashed Compose with a duplicate key.
        items(suggestions, key = { m -> "sg-" + m.rowKey }) { m ->
            MiniRow(m, greyed = !m.navigable) {
                // Non-navigable rows (playlist / show / episode) have no screen: the `when` used to
                // fall through and swallow the tap silently.
                when (val k = m.kind) {
                    is MiniKind.ArtistCard -> onOpenArtist(m)
                    is MiniKind.AlbumCard -> onOpenAlbum(k.id)
                    is MiniKind.SongCard -> onSubmitQuery(query.ifBlank { m.title })
                    is MiniKind.OtherCard -> Unit
                }
            }
        }
    }
}

/** What the tab shows before there is anything to show: recent searches and the origin's top list. */
@Composable
private fun SearchLanding(
    recent: List<String>,
    topSearches: List<MiniEntity>,
    onPick: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (recent.isNotEmpty()) {
            SectionTitle("Recent searches")
            QueryChipRow(recent) { onPick(it) }
        }
        if (topSearches.isNotEmpty()) {
            SectionTitle("Top searches")
            QueryChipRow(topSearches.take(TOP_SEARCHES_SHOWN).map { it.title }) { onPick(it) }
        }
    }
}

/** One horizontally scrolling row of query pills: a recent search or one of the top searches. */
@Composable
private fun QueryChipRow(
    queries: List<String>,
    onPick: (String) -> Unit,
) {
    val t = LocalDylanTokens.current
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        queries.forEach { text ->
            Box(
                Modifier
                    .background(t.surfaceVariant, MaterialTheme.shapes.large)
                    .clickable { onPick(text) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Text(text, style = MaterialTheme.typography.bodyMedium, color = t.textPrimary) }
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

/**
 * One mixed, cross-type ranked list: songs + albums + artists share relevance bands (exact → prefix
 * → contains), so an album never hides below its songs.
 */
private fun mergeHits(
    submitted: String,
    songs: List<Song>,
    albums: List<MiniEntity>,
    artists: List<MiniEntity>,
): List<Hit> {
    val all = ArrayList<Triple<Int, Int, Hit>>()
    var order = 0
    songs.forEach { all += Triple(dylan.search.relevanceBand(submitted, it.title), order++, Hit.SongHit(it)) }
    albums.forEach { all += Triple(dylan.search.relevanceBand(submitted, it.title), order++, Hit.AlbumHit(it)) }
    artists.forEach { all += Triple(dylan.search.relevanceBand(submitted, it.title), order++, Hit.ArtistHit(it)) }
    return all.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
}

/**
 * The submitted list's rows, its running totals and its paging budgets.
 *
 * The rows and the budgets are one [dylan.search.SearchSections] value, held as a single snapshot
 * state; the arithmetic that decides when a section is exhausted lives in that type, in `shared`, so
 * it has a JVM test that can run without a device. This class exists only for the part that cannot
 * move: the fetches (which need the container and a suspending context) and the fact that Compose
 * needs a snapshot state per recomposition.
 *
 * The object is created by [SearchScreen], so its lifetime is the screen's, not the list's.
 */
private class ResultState {
    var sections by mutableStateOf(dylan.search.SearchSections())

    var songs: List<Song>
        get() = sections.songs
        set(value) {
            sections = sections.copy(songs = value)
        }

    var albums: List<MiniEntity>
        get() = sections.albums
        set(value) {
            sections = sections.copy(albums = value)
        }

    var artists: List<MiniEntity>
        get() = sections.artists
        set(value) {
            sections = sections.copy(artists = value)
        }

    var songTotal: Long
        get() = sections.songTotal
        set(value) {
            sections = sections.copy(songTotal = value)
        }

    var albumTotal: Long
        get() = sections.albumTotal
        set(value) {
            sections = sections.copy(albumTotal = value)
        }

    var artistTotal: Long
        get() = sections.artistTotal
        set(value) {
            sections = sections.copy(artistTotal = value)
        }

    var loadingMore: Boolean
        get() = sections.loadingMore
        set(value) {
            sections = sections.withLoadingMore(value)
        }

    /** Page 1 of the current submit has been asked for and has not landed yet. */
    val loadingFirst: Boolean
        get() = sections.loadingFirst

    /**
     * "This list is still growing", for the footer spinner.
     *
     * Display only, and deliberately true *while page 1 is in flight* — that is precisely when the
     * user wants a spinner. The paging decision is [canExtend].
     */
    val growing: Boolean
        get() = sections.budgets.growing

    /** "A next page may be worth asking for", read at the moment it is asked. */
    fun canExtend(): Boolean = sections.canExtend()

    /** A new query: fresh budgets, and nothing left over from the last one. */
    fun reset() {
        sections = sections.reset()
    }

    /** Called synchronously by every submit path, before that path changes anything else. */
    fun beginSubmit() {
        sections = sections.beginSubmit()
    }

    /** The keyboard's submit drops the song rows synchronously, before the refetch effect has run. */
    fun clearSongs() {
        sections = sections.clearSongs()
    }

    /**
     * The first page of every section, fetched concurrently so a slow origin costs one round trip.
     *
     * [loadingFirst] is cleared only on the way out, never in a `finally`. A first fetch cancelled
     * by the submit that replaced it must leave the flag set: the fetch that superseded it is the one
     * that owns clearing it, and a cancelled fetch that cleared it would unblock paging against a
     * list that has not been loaded.
     */
    suspend fun fetchFirst(
        container: AppContainer,
        query: String,
    ) {
        coroutineScope {
            val songsDef = async { pageOrNull { container.provider.search(query, 1) } }
            val albumsDef = async { pageOrNull { container.provider.searchAlbums(query, 1) } }
            val artistsDef = async { pageOrNull { container.provider.searchArtists(query, 1) } }
            // A first page never exhausts a section, not even an empty one: the origin may simply have
            // nothing for this query — which is what `reset()` before the fold is for, since a fresh
            // budget starts with every section un-exhausted.
            sections = sections.reset().foldFirst(songsDef.await(), albumsDef.await(), artistsDef.await())
        }
    }

    /**
     * Extend every section that still has something to give, then the merged list re-ranks so
     * newcomers slot into their relevance band.
     *
     * Each section is asked for independently, so a section that has nothing left — or that never
     * mapped anything at all — cannot stop the other two from extending. That is the invariant F-25
     * reported as broken, and it is now asserted in `dylan.search.SearchPagingTest`.
     */
    suspend fun extend(
        container: AppContainer,
        query: String,
    ) {
        val current = sections
        val budgets = current.budgets
        val nextSong = budgets.songPage + 1
        val nextAlbum = budgets.albumPage + 1
        val nextArtist = budgets.artistPage + 1
        val songs =
            if (budgets.songsExhausted) {
                null
            } else {
                pageOrNull { container.provider.search(query, nextSong) }
            }
        val albums =
            if (budgets.albumsExhausted) {
                null
            } else {
                pageOrNull { container.provider.searchAlbums(query, nextAlbum) }
            }
        val artists =
            if (budgets.artistsExhausted) {
                null
            } else {
                pageOrNull { container.provider.searchArtists(query, nextArtist) }
            }
        sections = current.foldMore(songs, albums, artists)
    }
}

/** A page, or null when the origin could not answer. */
private suspend fun <T> pageOrNull(fetch: suspend () -> Paged<T>): Paged<T>? = runCatching { fetch() }.getOrNull()

/** Below this the origin has nothing useful to suggest and every keystroke is a wasted request. */
private const val MIN_SUGGEST_QUERY = 2

/**
 * How short a typed query has to be to stay on the typeahead instead of being submitted.
 *
 * Three, against the channel's two: at one or two characters the typeahead is the better affordance
 * and there is nothing to page yet; at three the mixed results list is worth its three requests.
 * The number is a floor, not a threshold a tuned search would rely on — it exists so that a
 * two-character prefix does not cost a round trip to report two rows.
 */
private const val MIN_SUBMIT_QUERY = 3

/**
 * How long the typed query must hold still before it is submitted (F-24).
 *
 * Five times the channel's own typing debounce, deliberately: the typeahead must be allowed to
 * answer first, or the user sees the results list arrive before the suggestions it is meant to come
 * up with. The cost of this is one three-way search (songs + albums + artists) per settled query,
 * where before there was one per explicit submit — which is the trade-off F-24 makes, stated in
 * [settledSubmitQuery].
 */
private const val SUBMIT_SETTLE_MS = 600L

/**
 * How many of the origin's top searches the landing tab offers. Ten is the list as it is worth
 * showing; past that it is a wall of pills over an empty screen.
 */
private const val TOP_SEARCHES_SHOWN = 10

/**
 * How close to the end of the results the user must scroll before the next page is requested.
 *
 * A screenful of runway, roughly: enough that the next page is usually already in flight by the
 * time the user reaches the bottom, without spending a request on a flick that never got there.
 */
private const val PREFETCH_ROWS = 12
