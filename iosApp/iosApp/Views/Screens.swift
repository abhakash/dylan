import SwiftUI
import shared

// =============================================================================
// Screens — SwiftUI mirrors of the Android Compose screens (plan §11.4):
// Home · Search · Library · Album · Settings. Structure and copy track the
// Android implementation; layout idioms are SwiftUI-native.
// =============================================================================

private func greeting() -> String {
    switch Calendar.current.component(.hour, from: Date()) {
    case 5 ..< 12: "Good morning"
    case 12 ..< 18: "Good afternoon"
    default: "Good evening"
    }
}

// ---- Home ------------------------------------------------------------------

struct HomeScreen: View {
    let onOpenAlbum: (String) -> Void
    let onOpenSettings: () -> Void

    @Environment(AppEnvironment.self) private var env

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(greeting())
                        .font(.dylTitleLarge)
                        .foregroundStyle(DylanTokens.textPrimary)
                    Spacer()
                    Button(action: onOpenSettings) {
                        Image(systemName: "gearshape")
                            .font(.dylTitleMedium)
                            .foregroundStyle(DylanTokens.textSecondary)
                    }
                    .accessibilityLabel("Settings")
                }
                .padding(.horizontal, DylanTokens.s16)
                .padding(.vertical, DylanTokens.s12)

                if env.home.offline { OfflineBanner() }

                if !env.home.jumpBack.isEmpty {
                    SectionTitle("Jump back in")
                    ForEach(env.home.jumpBack, id: \.key.token) { song in
                        SongRowView(
                            song: song,
                            index: nil,
                            isPlaying: env.isPlaying(song),
                            onTap: { env.play([song], at: 0) },
                        )
                    }
                }

                if !env.home.trending.isEmpty {
                    SectionTitle("Trending albums")
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: DylanTokens.s12) {
                            ForEach(env.home.trending, id: \.title) { m in
                                Button {
                                    if let id = m.albumId { onOpenAlbum(id) }
                                } label: {
                                    VStack(alignment: .leading, spacing: DylanTokens.s4) {
                                        ThumbImage(url: m.image)
                                            .frame(width: 120, height: 120)
                                        Text(m.title)
                                            .font(.dylBodyMedium)
                                            .foregroundStyle(DylanTokens.textPrimary)
                                            .lineLimit(1)
                                            .frame(width: 120, alignment: .leading)
                                    }
                                }
                                .buttonStyle(.plain)
                                .disabled(m.albumId == nil)
                            }
                        }
                        .padding(.horizontal, DylanTokens.s16)
                    }
                }

                if !env.home.topSearches.isEmpty {
                    SectionTitle("Top searches")
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: DylanTokens.s8) {
                            ForEach(env.home.topSearches.filter { $0.albumId != nil }.prefix(12), id: \.title) { m in
                                Chip(text: m.title) {
                                    if let id = m.albumId { onOpenAlbum(id) }
                                }
                            }
                        }
                        .padding(.horizontal, DylanTokens.s16)
                    }
                }
            }
            .padding(.bottom, DylanTokens.s16)
        }
        .background(DylanTokens.background)
        .refreshable { await env.home.refresh(env.graph) }
        .task { await env.home.refresh(env.graph) }
    }
}

// ---- Search ------------------------------------------------------------------

struct SearchScreen: View {
    let onOpenAlbum: (String) -> Void

    @Environment(AppEnvironment.self) private var env
    @FocusState private var fieldFocused: Bool

    private var store: SearchStore { env.search }

    var body: some View {
        VStack(spacing: 0) {
            TextField("Search songs, albums…", text: Binding(
                get: { store.query },
                set: { store.query = $0 },
            ))
            .font(.dylBodyLarge)
            .padding(DylanTokens.s12)
            .background(Capsule().fill(DylanTokens.surfaceVariant))
            .focused($fieldFocused)
            .submitLabel(.search)
            .onSubmit { Task { await store.submit(store.query) } }
            .onChange(of: store.query) { _ in store.editingChanged() }
            .padding(.horizontal, DylanTokens.s16)
            .padding(.vertical, DylanTokens.s8)

            if store.submitted != nil {
                submittedList
            } else if !store.suggestions.isEmpty {
                suggestionsList
            } else {
                emptyState
            }
        }
        .background(DylanTokens.background)
        .task { await store.onAppear(env.graph) }
    }

    /// Submitted: paginated songs list, id-deduped accumulator (§11.4 Search).
    private var submittedList: some View {
        List {
            ForEach(Array(store.results.enumerated()), id: \.element.key.token) { idx, song in
                SongRowView(
                    song: song,
                    index: nil,
                    isPlaying: env.isPlaying(song),
                    onTap: { env.play(store.results, at: idx) },
                    onPlayNext: { env.graph.submit(Intents.playNext(song)) },
                    onAddLast: { env.graph.submit(Intents.addLast(song)) },
                    onDownload: { Task { await env.downloadNow(song) } },
                )
                .listRowInsets(EdgeInsets())
                .listRowSeparator(.hidden)
            }
            Text("\(store.results.count) of \(store.total)")
                .font(.dylLabelSmall)
                .foregroundStyle(DylanTokens.textSecondary)
                .listRowSeparator(.hidden)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private var suggestionsList: some View {
        List(store.suggestions, id: \.title) { m in
            MiniRowView(mini: m) {
                if let albumId = m.albumId {
                    onOpenAlbum(albumId)
                } else if m.songKey != nil {
                    Task { await store.jumpThroughFullResults() }
                }
            }
            .listRowInsets(EdgeInsets())
            .listRowSeparator(.hidden)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private var emptyState: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if !store.recent.isEmpty {
                    SectionTitle("Recent searches")
                    chipRail(store.recent) { text in
                        store.query = text
                        Task { await store.submit(text) }
                    }
                }
                if !store.topSearches.isEmpty {
                    SectionTitle("Top searches")
                    chipRail(store.topSearches.prefix(10).map(\.title)) { text in
                        store.query = text
                        Task { await store.submit(text) }
                    }
                }
            }
        }
    }

    private func chipRail(
        _ texts: [String],
        onTap: @escaping (String) -> Void,
    ) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: DylanTokens.s8) {
                ForEach(texts, id: \.self) { text in
                    Chip(text: text) { onTap(text) }
                }
            }
            .padding(.horizontal, DylanTokens.s16)
        }
    }
}

// ---- Library -----------------------------------------------------------------

struct LibraryScreen: View {
    @Environment(AppEnvironment.self) private var env
    @State private var segment = 0

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $segment) {
                Text("Downloads").tag(0)
                Text("Favorites").tag(1)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, DylanTokens.s16)
            .padding(.vertical, DylanTokens.s12)

            if segment == 0 { downloadsTab } else { favoritesTab }
        }
        .background(DylanTokens.background)
        .task { await env.library.refresh(env.graph) }
    }

    private var downloadsTab: some View {
        Group {
            if env.library.downloads.isEmpty {
                VStack(alignment: .leading, spacing: DylanTokens.s8) {
                    SectionTitle("Downloads")
                    Text("Nothing saved yet. Play something and it lands here.")
                        .font(.dylBodyMedium)
                        .foregroundStyle(DylanTokens.textSecondary)
                        .padding(.horizontal, DylanTokens.s16)
                }
                Spacer()
            } else {
                List {
                    ForEach(env.library.downloads, id: \.song.key.token) { info in
                        SongRowView(
                            song: info.song,
                            index: nil,
                            isPlaying: env.isPlaying(info.song),
                            isCached: true,
                            onTap: {
                                let songs = env.library.downloads.map(\.song)
                                let idx = max(0, env.library.downloads.firstIndex { $0.song.key.token == info.song.key.token } ?? 0)
                                env.play(songs, at: idx)
                            },
                        )
                        .listRowInsets(EdgeInsets())
                        .listRowSeparator(.hidden)
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) {
                                Task { await env.library.removeDownload(info, env.graph) }
                            } label: { Label("Remove", systemImage: "trash") }
                        }
                    }
                    Text("Cached audio \(formatBytes(env.library.totalBytes)) of 2 GB · \(env.library.downloads.count) songs")
                        .font(.dylLabelSmall)
                        .foregroundStyle(DylanTokens.textSecondary)
                        .listRowSeparator(.hidden)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
    }

    private var favoritesTab: some View {
        Group {
            if env.library.favorites.isEmpty {
                VStack(alignment: .leading, spacing: DylanTokens.s8) {
                    SectionTitle("Favorites")
                    Text("Songs you favorite appear here.")
                        .font(.dylBodyMedium)
                        .foregroundStyle(DylanTokens.textSecondary)
                        .padding(.horizontal, DylanTokens.s16)
                }
                Spacer()
            } else {
                List {
                    ForEach(Array(env.library.favorites.enumerated()), id: \.element.key.token) { idx, song in
                        SongRowView(
                            song: song,
                            index: nil,
                            isPlaying: env.isPlaying(song),
                            isCached: env.isCached(song),
                            onTap: { env.play(env.library.favorites, at: idx) },
                            onPlayNext: { env.graph.submit(Intents.playNext(song)) },
                            onAddLast: { env.graph.submit(Intents.addLast(song)) },
                            onFavorite: { Task { await env.toggleFavorite(song) } },
                        )
                        .listRowInsets(EdgeInsets())
                        .listRowSeparator(.hidden)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
    }
}

// ---- Album -------------------------------------------------------------------

struct AlbumScreen: View {
    let albumId: String
    let onBack: () -> Void

    @Environment(AppEnvironment.self) private var env
    @State private var album: KAlbum?
    @State private var failed = false
    @State private var songs: [KSong] = []

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .bottomLeading) {
                    ThumbImage(url: album?.artUrl500, maxPixel: Thumbnailer.nowPlayingPixel, corner: 0)
                        .frame(height: 260)
                        .frame(maxWidth: .infinity)
                        .clipped()
                    LinearGradient(
                        colors: [.clear, DylanTokens.background],
                        startPoint: .center,
                        endPoint: .bottom,
                    )
                    VStack(alignment: .leading, spacing: DylanTokens.s8) {
                        Text(album?.title ?? "")
                            .font(.dylDisplayMedium)
                            .foregroundStyle(DylanTokens.textPrimary)
                        Text([album?.subtitle ?? "", album?.year ?? ""].filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.dylBodyMedium)
                            .foregroundStyle(DylanTokens.textSecondary)
                        HStack(spacing: DylanTokens.s12) {
                            Button {
                                if !songs.isEmpty { env.play(songs, at: 0) }
                            } label: {
                                Text("Play")
                                    .font(.dylTitleMedium)
                                    .foregroundStyle(Color(hex: 0xFFFFFF))
                                    .padding(.horizontal, DylanTokens.s24)
                                    .padding(.vertical, DylanTokens.s8)
                                    .background(Capsule().fill(DylanTokens.primary))
                            }
                            Button {
                                // E2 parity (Android AlbumScreen): when a song of THIS
                                // album is current, shuffle only reshuffles upcoming items
                                // around the anchor — never jumps/restarts. Otherwise
                                // ensure shuffle ON and start from a random track.
                                if !songs.isEmpty {
                                    let playingHere = env.player.current.map { cur in
                                        songs.contains { $0.key.token == cur.key.token }
                                    } ?? false
                                    if playingHere {
                                        if !env.player.shuffleOn { env.graph.submit(Intents.toggleShuffle) }
                                    } else {
                                        if !env.player.shuffleOn { env.graph.submit(Intents.toggleShuffle) }
                                        env.play(songs, at: Int.random(in: 0 ..< songs.count))
                                    }
                                }
                            } label: {
                                Text("Shuffle")
                                    .font(.dylTitleMedium)
                                    .foregroundStyle(DylanTokens.primary)
                                    .padding(.horizontal, DylanTokens.s24)
                                    .padding(.vertical, DylanTokens.s8)
                                    .overlay(Capsule().stroke(DylanTokens.primary))
                            }
                        }
                    }
                    .padding(DylanTokens.s16)
                }

                ForEach(Array(songs.enumerated()), id: \.element.key.token) { i, song in
                    SongRowView(
                        song: song,
                        index: i + 1,
                        isPlaying: env.isPlaying(song),
                        enabled: song.cacheable,
                        onTap: { env.play(songs, at: i) },
                        onPlayNext: { env.graph.submit(Intents.playNext(song)) },
                        onAddLast: { env.graph.submit(Intents.addLast(song)) },
                    )
                }

                if failed {
                    Text("Check your connection and try again.")
                        .font(.dylBodyMedium)
                        .foregroundStyle(DylanTokens.error)
                        .padding(DylanTokens.s16)
                }
            }
        }
        .background(DylanTokens.background)
        .navigationBarBackButtonHidden(true)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button(action: onBack) {
                    Image(systemName: "chevron.left")
                        .foregroundStyle(DylanTokens.textPrimary)
                }
                .accessibilityLabel("Back")
            }
        }
        .task(id: albumId) {
            failed = false
            album = await env.graph.albumDetail(id: albumId)
            // Kotlin List<Song> crosses as untyped NSArray — cast defensively (F14 family).
            songs = ((album?.songs) as? [KSong]) ?? []
            failed = album == nil
        }
    }
}

// ---- Settings ----------------------------------------------------------------

struct SettingsPanel: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(\.dismiss) private var dismiss
    @State private var confirmClear = false
    @State private var loaded = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DylanTokens.s24 - DylanTokens.s4) {
                HStack {
                    Text("Settings")
                        .font(.dylTitleLarge)
                        .foregroundStyle(DylanTokens.textPrimary)
                    Spacer()
                    Button(action: { dismiss() }) {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(DylanTokens.textSecondary)
                    }
                    .accessibilityLabel("Close")
                }

                sectionHeader("AUDIO")
                groupCard {
                    qualityRow(label: "128 kbps", sub: "Data saver", selected: !env.prefs.highQuality) {
                        Task { await env.prefs.setHighQuality(false) }
                    }
                    thinDivider
                    qualityRow(label: "320 kbps", sub: "High quality", selected: env.prefs.highQuality) {
                        Task { await env.prefs.setHighQuality(true) }
                    }
                    thinDivider
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Prefetch next track").font(.system(size: 15, weight: .medium))
                            Text("One track ahead · ~3 MB on metered").font(.dylLabelSmall)
                        }
                        .foregroundStyle(DylanTokens.textPrimary)
                        Spacer()
                        Image(systemName: env.prefetchEnabled ? "checkmark" : "minus")
                            .foregroundStyle(DylanTokens.primary)
                    }
                    .padding(.horizontal, DylanTokens.s16)
                    .padding(.vertical, DylanTokens.s6 + 2)
                }
                Text("Metered networks always use 128 kbps.")
                    .font(.dylLabelSmall)
                    .foregroundStyle(DylanTokens.textSecondary)
                    .padding(.leading, DylanTokens.s6)

                sectionHeader("STORAGE")
                groupCard {
                    VStack(spacing: DylanTokens.s8) {
                        HStack {
                            Text("Offline audio").font(.system(size: 15, weight: .medium))
                            Spacer()
                            Text(formatBytes(env.prefs.usedBytes)).font(.dylLabelSmall)
                        }
                        .foregroundStyle(DylanTokens.textPrimary)
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                Capsule().fill(DylanTokens.divider)
                                Capsule()
                                    .fill(DylanTokens.primary)
                                    .frame(width: geo.size.width * usageFraction)
                            }
                        }
                        .frame(height: 5)
                        Text("\(env.prefs.songCount) of \(env.graph.cfg.cacheMaxFiles) songs · \(formatBytes(env.graph.cfg.cacheMaxBytes)) budget")
                            .font(.dylLabelSmall)
                            .foregroundStyle(DylanTokens.textSecondary)
                    }
                    .padding(.horizontal, DylanTokens.s16)
                    .padding(.vertical, 14)
                    thinDivider
                    Button {
                        confirmClear = true
                    } label: {
                        HStack {
                            Text("Clear cache").font(.system(size: 15, weight: .medium))
                            Spacer()
                            Text("Keeps what's playing").font(.dylLabelSmall)
                        }
                        .padding(.horizontal, DylanTokens.s16)
                        .padding(.vertical, 14)
                    }
                }

                sectionHeader("ABOUT")
                groupCard {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Dylan").font(.system(size: 15, weight: .medium))
                        Text("Version \(env.appVersion)").font(.dylLabelSmall)
                    }
                    .foregroundStyle(DylanTokens.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, DylanTokens.s16)
                    .padding(.vertical, 14)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, DylanTokens.s12)
            .padding(.bottom, DylanTokens.s24 + DylanTokens.s8)
        }
        .background(DylanTokens.background)
        .alert("Clear cache?", isPresented: $confirmClear) {
            Button("Clear", role: .destructive) {
                Task {
                    await env.prefs.clearCache(env.graph)
                    await env.library.loadDownloads(env.graph)
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Downloaded tracks will be removed from storage. Keeps the song you're playing.")
        }
        .task {
            guard !loaded else { return }
            loaded = true
            await env.prefs.load(env.graph)
        }
    }

    private var usageFraction: CGFloat {
        CGFloat(env.prefs.usedBytes) / CGFloat(env.graph.cfg.cacheMaxBytes)
    }

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(.dylLabelSmall.weight(.semibold))
            .kerning(1.4)
            .foregroundStyle(DylanTokens.textSecondary)
            .padding(.leading, DylanTokens.s6)
            .padding(.bottom, DylanTokens.s8)
    }

    private func groupCard<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(spacing: 0) { content() }
            .background(RoundedRectangle(cornerRadius: DylanTokens.radiusLg).fill(DylanTokens.surfaceVariant))
    }

    private var thinDivider: some View {
        Rectangle()
            .fill(DylanTokens.divider.opacity(0.45))
            .frame(height: 0.7)
            .padding(.leading, DylanTokens.s16)
    }

    private func qualityRow(
        label: String,
        sub: String,
        selected: Bool,
        onSelect: @escaping () -> Void,
    ) -> some View {
        Button(action: onSelect) {
            HStack(spacing: DylanTokens.s6) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(selected ? DylanTokens.primary : DylanTokens.textSecondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.system(size: 15, weight: .medium))
                    Text(sub).font(.dylLabelSmall)
                }
                .foregroundStyle(DylanTokens.textPrimary)
                Spacer()
            }
            .padding(.horizontal, DylanTokens.s16)
            .padding(.vertical, DylanTokens.s6 + 2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
