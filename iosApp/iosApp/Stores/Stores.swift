import Foundation
import Observation
import shared

// =============================================================================
// Stores — @Observable surfaces mapping FlowAdapter subscriptions to SwiftUI
// state (plan §9.11 / §4.1 "Screens ← @Observable Store"). Every store binds
// through the concrete-closure subscriptions on SharedIosGraph; cancellation
// flows through the Kotlin-owned Job (KotlinSubscription.cancel), never Task.
// =============================================================================

@MainActor
@Observable
final class PlayerStore {
    private(set) var state: KPlayerState?
    private(set) var positionMs: Int64 = 0
    private(set) var queueSongs: [KSong] = []
    private(set) var phaseKind: String = "idle"
    private(set) var repeatKind: String = "off"
    private(set) var showsPause: Bool = false
    /// Phase-derived status line for Now Playing (mirrors Android's label arm).
    private(set) var statusLine: String = ""

    nonisolated(unsafe) private var stateHandle: KSubscription?
    nonisolated(unsafe) private var posHandle: KSubscription?
    private weak var graph: KGraph?

    func bind(_ g: KGraph) {
        guard stateHandle == nil else { return }
        graph = g
        // FlowAdapter already collects on Dispatchers.Main.immediate, so the closure
        // runs on the main thread — apply synchronously via assumeIsolated instead of
        // an extra Task hop (which deferred a runloop turn and let position overtake state).
        stateHandle = g.subscribePlayerState { [weak self] st in
            MainActor.assumeIsolated { self?.apply(st) }
        }
        posHandle = g.subscribePosition { [weak self] ms in
            MainActor.assumeIsolated { self?.positionMs = ms.int64Value }
        }
    }

    deinit {
        // KotlinSubscription.cancel just cancels the Kotlin-owned Job (thread-safe) —
        // no MainActor hop needed.
        stateHandle?.cancel()
        posHandle?.cancel()
    }

    /// UI-side distinctUntilChanged: FlowAdapter conflates shared-side (not owned here),
    /// so skip consecutive identical emissions locally instead of re-publishing.
    private var lastSig = ""

    private func apply(_ st: KPlayerState?) {
        guard let g = graph else { return }
        let rawQueue = st.flatMap { g.queueAsList(state: $0) }
        // Bridge contract (R4-F14): PersistentList crosses as NSArray of KSong.
        // A shape change must fail loudly in debug, never as an empty queue.
        if rawQueue != nil && !((rawQueue as? [Any])?.allSatisfy { $0 is KSong } ?? true) {
            assertionFailure("bridge contract: queueAsList returned non-KSong elements")
        }
        let queue = (rawQueue as? [KSong]) ?? []
        let sig = [
            g.phaseKind(state: st),
            st?.current?.key.token ?? "-",
            "\(st?.index ?? -1)",
            queue.map(\.key.token).joined(separator: ","),
            "\(st?.shuffleOn ?? false)",
            g.repeatKind(state: st),
        ].joined(separator: "|")
        guard sig != lastSig else { return }
        lastSig = sig
        state = st
        queueSongs = queue
        phaseKind = st == nil ? "idle" : g.phaseKind(state: st)
        repeatKind = g.repeatKind(state: st)
        showsPause = g.playPauseShowsPause(state: st)
        switch g.phaseKind(state: st) {
        case "downloading":
            statusLine = "Saving…"
        case "resolving":
            statusLine = "Preparing…"
        case "error":
            if let f = g.failureOf(state: st) { statusLine = g.failureMessage(failure: f) } else { statusLine = "" }
        default:
            statusLine = ""
        }
    }

    var current: KSong? { state?.current }
    var currentIndex: Int32 { state?.index ?? -1 }
    var shuffleOn: Bool { state?.shuffleOn ?? false }
}

@MainActor
@Observable
final class SearchStore {
    var query: String = "" {
        didSet { scheduleDemand() }
    }
    private(set) var submitted: String?
    private(set) var results: [KSong] = []
    private(set) var total: Int64 = 0
    private(set) var songPage: Int = 1
    private(set) var loadingMore: Bool = false
    private(set) var albumResults: [KMiniEntity] = []
    private(set) var albumTotal: Int64 = 0
    private(set) var albumPage: Int = 1
    private(set) var artistResults: [KMiniEntity] = []
    private(set) var artistTotal: Int64 = 0
    private(set) var artistPage: Int = 1

    /// One mixed, cross-type ranked list (mirrors Android Hit): songs + albums +
    /// artists share relevance bands so an album never hides below its songs.
    var mergedHits: [SearchHit] {
        let q = (submitted ?? "").lowercased()
        var all: [(band: Int, order: Int, hit: SearchHit)] = []
        var order = 0
        for s in results { all.append((rankBand(q, s.title), order, .song(s))); order += 1 }
        for m in albumResults { all.append((rankBand(q, m.title), order, .album(m))); order += 1 }
        for m in artistResults { all.append((rankBand(q, m.title), order, .artist(m))); order += 1 }
        return all.sorted {
            if $0.band != $1.band { return $0.band < $1.band }
            return $0.order < $1.order
        }.map(\.hit)
    }

    var hasMore: Bool {
        Int64(results.count) < total || Int64(albumResults.count) < albumTotal || Int64(artistResults.count) < artistTotal
    }
    private(set) var suggestions: [KMiniEntity] = []
    private(set) var recent: [String] = []
    private(set) var topSearches: [KMiniEntity] = []

    nonisolated(unsafe) private var suggestionsHandle: KSubscription?
    private var latestAnswered: String = ""
    nonisolated(unsafe) private var demandTask: Task<Void, Never>?
    private weak var graph: KGraph?

    func bind(_ g: KGraph) {
        guard suggestionsHandle == nil else { return }
        graph = g
        // Render-on-arrival (D9): the WS answer lands here whenever it lands; typing never blocks.
        suggestionsHandle = g.search.subscribeSuggestions { [weak self] answered, items in
            guard let self else { return }
            if !(items is [KMiniEntity]) {
                assertionFailure("bridge contract: suggestions delivered non-KMiniEntity items")
            }
            let list = (items as? [KMiniEntity]) ?? []
            self.latestAnswered = answered
            guard self.submitted == nil,
                  answered == self.query.trimmingCharacters(in: .whitespaces),
                  answered.count >= 2 else { return }
            // Server repeats entries across buckets/keystrokes [verified: 7.har] — dedupe (D7).
            // Ranked like submit sections: exact/prefix title matches float above fuzzy ones.
            var seen = Set<String>()
            let q = answered.lowercased()
            self.suggestions = list.filter { entry -> Bool in
                let id = entry.title + (entry.songKey?.songId ?? entry.albumId ?? "")
                if seen.contains(id) { return false }
                seen.insert(id)
                return true
            }.enumerated().sorted { lhs, rhs in
                let bl = rankBand(q, lhs.element.title)
                let br = rankBand(q, rhs.element.title)
                if bl != br { return bl < br }
                return lhs.offset < rhs.offset // stable: server order survives within a band
            }.map(\.element)
        }
    }

    deinit {
        suggestionsHandle?.cancel()
        demandTask?.cancel()
    }

    /// §6.4 connect trigger: tab entry, not first keystroke.
    func onAppear(_ g: KGraph) async {
        bind(g)
        g.search.warmUp()
        async let r: Void = loadRecent(g)
        async let t: Void = loadTopSearches(g)
        _ = await (r, t)
    }

    func loadRecent(_ g: KGraph) async {
        recent = await g.recentSearchChips()
    }

    func loadTopSearches(_ g: KGraph) async {
        topSearches = await g.topSearches()
    }

    private func scheduleDemand() {
        demandTask?.cancel()
        guard let g = graph else { return }
        let q = query
        demandTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 120_000_000)
            guard !Task.isCancelled, let self else { return }
            if self.submitted != nil { return }
            let trimmed = q.trimmingCharacters(in: .whitespaces)
            if trimmed.count >= 2 {
                g.search.request(query: trimmed)
            } else {
                self.suggestions = []
            }
        }
    }

    func editingChanged() {
        if let s = submitted, query != s { submitted = nil }
    }

    func submit(_ text: String) async {
        guard let g = graph, !text.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        submitted = text
        suggestions = []
        songPage = 1
        albumPage = 1
        artistPage = 1
        loadingMore = false
        results = []
        albumResults = []
        artistResults = []
        total = 0
        albumTotal = 0
        artistTotal = 0
        await g.recordSearch(text)
        async let songs = g.searchSongsPaged(q: text, page: 1)
        async let albums = g.searchAlbumsPaged(q: text, page: 1)
        async let artists = g.searchArtistsPaged(q: text, page: 1)
        let (fetched, serverTotal) = await songs
        var seen = Set<String>()
        results = fetched.filter { seen.insert($0.key.token).inserted }
        total = serverTotal
        let (albumsFetched, albumServerTotal) = await albums
        albumResults = albumsFetched
        albumTotal = albumServerTotal
        let (artistsFetched, artistServerTotal) = await artists
        artistResults = artistsFetched
        artistTotal = artistServerTotal
        await loadRecent(g)
    }

    func loadMoreSongs() async {
        guard let g = graph, let q = submitted, !loadingMore, hasMore else { return }
        loadingMore = true
        defer { loadingMore = false }
        let nextSong = songPage + 1
        let nextAlbum = albumPage + 1
        let nextArtist = artistPage + 1
        async let songs = (Int64(results.count) < total) ? g.searchSongsPaged(q: q, page: nextSong) : ([], total)
        async let albums = (Int64(albumResults.count) < albumTotal) ? g.searchAlbumsPaged(q: q, page: nextAlbum) : ([], albumTotal)
        async let artists = (Int64(artistResults.count) < artistTotal) ? g.searchArtistsPaged(q: q, page: nextArtist) : ([], artistTotal)
        let (fetched, serverTotal) = await songs
        if Int64(results.count) < total {
            var seen = Set(results.map(\.key.token))
            results += fetched.filter { seen.insert($0.key.token).inserted }
            total = serverTotal
            songPage = nextSong
        }
        let (albumsFetched, albumServerTotal) = await albums
        if Int64(albumResults.count) < albumTotal {
            var seen = Set(albumResults.map { $0.title + ($0.albumId ?? "") })
            albumResults += albumsFetched.filter { seen.insert($0.title + ($0.albumId ?? "")).inserted }
            albumTotal = albumServerTotal
            albumPage = nextAlbum
        }
        let (artistsFetched, artistServerTotal) = await artists
        if Int64(artistResults.count) < artistTotal {
            var seen = Set(artistResults.map { $0.title + ($0.artistId ?? "") })
            artistResults += artistsFetched.filter { seen.insert($0.title + ($0.artistId ?? "")).inserted }
            artistTotal = artistServerTotal
            artistPage = nextArtist
        }
    }

    /// Mini-entity tap on the typing path jumps through full-results (§9.6).
    func jumpThroughFullResults() async {
        let q = query.isEmpty ? "" : query
        if !q.isEmpty { await submit(q) }
    }

    func reset() {
        submitted = nil
        results = []
        total = 0
        songPage = 1
        loadingMore = false
        albumResults = []
        albumTotal = 0
        albumPage = 1
        artistResults = []
        artistTotal = 0
        artistPage = 1
        suggestions = []
    }
}

/// One row of the mixed submit list (mirrors Android Hit).
enum SearchHit {
    case song(KSong)
    case album(KMiniEntity)
    case artist(KMiniEntity)
}

/// Mirrors shared SearchRank.band: exact (0) → prefix (1) → contains (2) → other (3).
/// Swift stdlib sort is stable in practice for this size; ties keep server order.
func rankBand(_ query: String, _ title: String) -> Int {
    let q = query.trimmingCharacters(in: .whitespaces).lowercased()
    let t = title.trimmingCharacters(in: .whitespaces).lowercased()
    if q.isEmpty || t.isEmpty { return 3 }
    if t == q { return 0 }
    if t.hasPrefix(q) { return 1 }
    if t.contains(q) { return 2 }
    return 3
}

@MainActor
@Observable
final class HomeStore {
    private(set) var jumpBack: [KSong] = []
    private(set) var trending: [KMiniEntity] = []
    private(set) var topSearches: [KMiniEntity] = []
    private(set) var offline: Bool = false
    private(set) var loading: Bool = false

    func refresh(_ g: KGraph) async {
        loading = true
        defer { loading = false }
        // E5 parity: Jump Back In on Home shows at most 20 — Android HomeScreen.kt:81 reads
        // history.recent(20). The old comment here claimed Android Home was recent(5), but that is
        // the *Library* screen (LibraryScreen.kt:69); the two were counting different screens, so
        // iOS Home silently showed a quarter of Android's.
        jumpBack = await g.historyRecent(20)
        let sections = await g.homeSections()
        // Android takes feed.sections.first — trending albums rail (plan §11.4).
        if let items = sections.first?.items, !(items is [KMiniEntity]) {
            assertionFailure("bridge contract: home section items are non-KMiniEntity")
        }
        trending = ((sections.first?.items as? [KMiniEntity]) ?? [])
        // homeSections() degrades to [] on any provider failure → same banner trigger
        // as Android's `feed == null` (empty-but-successful feeds don't occur in practice).
        offline = sections.isEmpty
        topSearches = await g.topSearches()
    }
}

@MainActor
@Observable
final class LibraryStore {
    private(set) var downloads: [KCachedSongInfo] = []
    private(set) var favorites: [KSong] = []
    private(set) var jumpBack: [KSong] = []
    private(set) var totalBytes: Int64 = 0

    func refresh(_ g: KGraph) async {
        async let d: Void = loadDownloads(g)
        async let f: Void = loadFavorites(g)
        async let j: Void = loadJumpBack(g)
        _ = await (d, f, j)
    }

    func loadDownloads(_ g: KGraph) async {
        downloads = await g.downloadsLibrary()
        totalBytes = downloads.reduce(0) { $0 + $1.bytes }
    }

    func loadFavorites(_ g: KGraph) async {
        favorites = await g.favoritesAll()
    }

    func loadJumpBack(_ g: KGraph) async {
        jumpBack = await g.historyRecent(5)
    }

    /// True when the cached row and its file are gone; false when the eviction was refused
    /// because something still depends on the key.
    @discardableResult
    func removeDownload(_ info: KCachedSongInfo, _ g: KGraph) async -> Bool {
        let removed = await g.removeDownloaded(info)
        await loadDownloads(g)
        return removed
    }
}

@MainActor
@Observable
final class PrefsStore {
    var highQuality: Bool = true
    private(set) var songCount: Int64 = 0
    private(set) var usedBytes: Int64 = 0
    private(set) var lastClearedBytes: Int64?

    private weak var graph: KGraph?

    func load(_ g: KGraph) async {
        graph = g
        highQuality = await g.loadHighQualityPref()
        await reloadStats(g)
    }

    func reloadStats(_ g: KGraph) async {
        if let s = await g.storageStats() {
            songCount = s.songCount
            usedBytes = s.totalBytes
        }
    }

    func setHighQuality(_ high: Bool) async {
        highQuality = high
        await graph?.saveHighQualityPref(high)
    }

    func clearCache(_ g: KGraph) async {
        lastClearedBytes = await g.clearCacheNow()
        await reloadStats(g)
    }
}

/// Toast surface fed from graph.onToast (every toast also mirrored to LogBuffer).
@MainActor
@Observable
final class ToastStore {
    private(set) var message: String = ""
    private(set) var visible: Bool = false
    private var dismissTask: Task<Void, Never>?

    func show(_ msg: String) {
        message = msg
        visible = true
        dismissTask?.cancel()
        dismissTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            guard !Task.isCancelled else { return }
            self?.visible = false
        }
    }
}
