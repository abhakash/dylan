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
        stateHandle = g.subscribePlayerState { [weak self] st in self?.apply(st) }
        posHandle = g.subscribePosition { [weak self] ms in self?.positionMs = (ms as? KotlinLong)?.int64Value ?? (ms as? Int64) ?? 0 }
    }

    deinit {
        // MainActor properties - best effort
        Task { @MainActor [stateHandle, posHandle] in stateHandle?.cancel(); posHandle?.cancel() }
    }

    private func apply(_ st: KPlayerState?) {
        guard let g = graph else { return }
        state = st
        queueSongs = ((st.flatMap { g.queueAsList(state: $0) }) as? [KSong]) ?? []
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
            let list = (items as? [KMiniEntity]) ?? []
            self.latestAnswered = answered
            guard self.submitted == nil,
                  answered == self.query.trimmingCharacters(in: .whitespaces),
                  answered.count >= 2 else { return }
            // Server repeats entries across buckets/keystrokes [verified: 7.har] — dedupe (D7).
            var seen = Set<String>()
            self.suggestions = list.filter { entry -> Bool in
                let id = entry.title + (entry.songKey?.songId ?? entry.albumId ?? "")
                if seen.contains(id) { return false }
                seen.insert(id)
                return true
            }
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
        await g.recordSearch(text)
        let (songs, serverTotal) = await g.searchSongsPaged(q: text, page: 1)
        var seen = Set<String>()
        results = songs.filter { seen.insert($0.key.token).inserted }
        total = serverTotal
        await loadRecent(g)
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
        suggestions = []
    }
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
        // E5 parity: Jump Back In shows at most 5 (Android HomeScreen recent(5)).
        jumpBack = await g.historyRecent(5)
        let sections = await g.homeSections()
        // Android takes feed.sections.first — trending albums rail (plan §11.4).
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
    private(set) var totalBytes: Int64 = 0

    func refresh(_ g: KGraph) async {
        async let d: Void = loadDownloads(g)
        async let f: Void = loadFavorites(g)
        _ = await (d, f)
    }

    func loadDownloads(_ g: KGraph) async {
        downloads = await g.downloadsLibrary()
        totalBytes = downloads.reduce(0) { $0 + $1.bytes }
    }

    func loadFavorites(_ g: KGraph) async {
        favorites = await g.favoritesAll()
    }

    func removeDownload(_ info: KCachedSongInfo, _ g: KGraph) async {
        await g.removeDownloaded(info)
        await loadDownloads(g)
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
