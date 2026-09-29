import SwiftUI
import shared

// =============================================================================
// Now Playing sheet + Queue sheet — mirrors Android NowPlayingSheet.kt /
// QueueSheet.kt (plan §11.4): art xl20 · displayLarge title · scrub slider with
// committed-on-release gesture · 64 pt primary circle · queue/heart/quality row.
// =============================================================================

struct NowPlayingSheet: View {
    let onQueue: () -> Void
    var onDismiss: (() -> Void)? = nil

    @Environment(AppEnvironment.self) private var env
    @State private var dragging = false
    @State private var dragPos: Double = 0
    @State private var isFavorite = false
    @State private var bitsLabel = ""
    @State private var loadedSongToken: String?

    private var song: KSong? { env.player.current }

    var body: some View {
        Group {
            if let song {
                content(song)
            } else {
                Text("Nothing playing")
                    .font(.dylBodyMedium)
                    .foregroundStyle(DylanTokens.textSecondary)
            }
        }
        .presentationDetents([.large])
        .background(DylanTokens.surface)
    }

    @ViewBuilder private func content(_ song: KSong) -> some View {
        let durMs = max(1, song.durationS * 1000)
        // Mirrors Android NowPlayingSheet: isLoading = phase is Resolving || Downloading
        let isLoading = env.player.phaseKind == "resolving" || env.player.phaseKind == "downloading"
        // Scrubbable only once transportable (Playing/Paused/Ready), which is already false while
        // resolving or downloading — so the disabled scrubber *is* the mid-resolve behaviour.
        let transportable = ["playing", "paused", "ready"].contains(env.player.phaseKind)
        // Resume position is preserved: while loading we keep showing pos (never snap to 0).
        let shown: Double = dragging ? dragPos : Double(env.player.positionMs)
        // Centering contract (mirrors Android NowPlayingSheet): the sheet is .large
        // while the content is a fixed stack. Anchor top while it scrolls; center
        // it in the leftover space when it fits — no dead gap below.
        GeometryReader { geo in
            ScrollView(showsIndicators: false) {
                VStack(spacing: 0) {
                    Spacer(minLength: 0)
                    contentStack(song, durMs: durMs, isLoading: isLoading, transportable: transportable, shown: shown)
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, minHeight: geo.size.height)
            }
            .task(id: song.key.token) {
                // A track change re-creates the Slider (it is keyed on the token), so a drag in
                // flight never reports its release. Reset here, as Android's `remember(song.key)`
                // does, or the new track inherits the old drag value.
                dragging = false
                dragPos = 0
                loadedSongToken = song.key.token
                async let f: Void = refreshFavorite(song)
                async let b: Void = refreshBits(song)
                _ = await (f, b)
            }
        }
    }

    @ViewBuilder private func contentStack(
        _ song: KSong,
        durMs: Int64,
        isLoading: Bool,
        transportable: Bool,
        shown: Double
    ) -> some View {
        VStack(spacing: 0) {
            HStack {
                Button {
                    onDismiss?()
                } label: {
                    Image(systemName: "chevron.down")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(DylanTokens.textSecondary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("Close")
                Spacer()
                Capsule()
                    .fill(DylanTokens.divider)
                    .frame(width: 40, height: 4)
                Spacer()
                // Balance close button
                Color.clear.frame(width: 44, height: 44)
            }
            .padding(.top, DylanTokens.s12)

            ThumbImage(url: song.artUrl500, maxPixel: Thumbnailer.nowPlayingPixel, corner: DylanTokens.radiusXl)
                .frame(height: 320)
                .padding(.horizontal, DylanTokens.s24)
                .padding(.top, DylanTokens.s12)
                .gesture(
                    DragGesture(minimumDistance: 30).onEnded { g in
                        if abs(g.translation.width) > 60 {
                            env.graph.submit(g.translation.width < 0 ? Intents.next : Intents.previous)
                        }
                    }
                )

            Text(song.title)
                .font(.dylDisplayLarge)
                .foregroundStyle(DylanTokens.textPrimary)
                .lineLimit(1)
                .padding(.horizontal, DylanTokens.s24)
                .padding(.top, DylanTokens.s16)

            Text(song.subtitle.isEmpty ? (song.albumName ?? "") : song.subtitle)
                .font(.dylBodyLarge)
                .foregroundStyle(DylanTokens.textSecondary)
                .lineLimit(1)
                .padding(.horizontal, DylanTokens.s24)

            // (Album jump removed: the subtitle line already carries the album info.)

            // The scrubber is never unmounted. Swapping it for a ProgressView while the phase is
            // Resolving/Downloading destroyed the Slider mid-gesture, so `onEditingChanged(false)`
            // never arrived: `dragging` stayed true, the release-seek was dropped and the clock
            // latched at the abandoned drag value for the rest of the session. The loading bar
            // lives in the status row instead, where it costs no layout shift.
            Slider(
                value: Binding(
                    get: { min(max(shown, 0), Double(durMs)) },
                    set: { dragging = true; dragPos = $0 }
                ),
                in: 0 ... Double(durMs),
                onEditingChanged: { editing in
                    if !editing {
                        env.graph.submit(Intents.seek(ms: Int64(dragPos)))
                        dragging = false
                    }
                }
            )
            .tint(DylanTokens.primary)
            .padding(.horizontal, DylanTokens.s24)
            .padding(.top, DylanTokens.s12)
            // Keyed on the song so a track change resets any in-flight drag.
            .id(song.key.token)
            .disabled(!transportable)

            if isLoading {
                ProgressView()
                    .progressViewStyle(.linear)
                    .tint(DylanTokens.primary)
                    .frame(height: 2)
                    .padding(.horizontal, DylanTokens.s24)
                    .padding(.top, DylanTokens.s8)
                    .allowsHitTesting(false)
                    .accessibilityLabel(env.player.statusLine)
            }

            HStack {
                Text(formatTime(ms: Int64(shown)))
                Spacer()
                Text(formatTime(ms: durMs))
            }
            .font(.dylLabelSmall)
            .foregroundStyle(DylanTokens.textSecondary)
            .padding(.horizontal, DylanTokens.s24)

            HStack {
                Button { env.graph.submit(Intents.toggleShuffle) } label: {
                    Image(systemName: "shuffle")
                        .font(.system(size: 20))
                        .foregroundStyle(env.player.shuffleOn ? DylanTokens.primary : DylanTokens.textPrimary)
                }
                .accessibilityLabel("Shuffle")
                Spacer()
                Button { env.graph.submit(Intents.previous) } label: {
                    Image(systemName: "backward.fill").font(.system(size: 28))
                        .foregroundStyle(DylanTokens.textPrimary)
                }
                .accessibilityLabel("Previous")
                Spacer()
                PlayPauseCircle()
                Spacer()
                Button { env.graph.submit(Intents.next) } label: {
                    Image(systemName: "forward.fill").font(.system(size: 28))
                        .foregroundStyle(DylanTokens.textPrimary)
                }
                .accessibilityLabel("Next")
                Spacer()
                Button { env.graph.submit(Intents.cycleRepeat) } label: {
                    ZStack(alignment: .topTrailing) {
                        Image(systemName: "repeat")
                            .font(.system(size: 20))
                            .foregroundStyle(env.player.repeatKind != "off" ? DylanTokens.primary : DylanTokens.textPrimary)
                        if env.player.repeatKind == "one" {
                            Text("1")
                                .font(.dylLabelSmall)
                                .foregroundStyle(DylanTokens.primary)
                                .offset(x: 8, y: -4)
                        }
                    }
                }
                .accessibilityLabel("Repeat")
            }
            .padding(.horizontal, DylanTokens.s24 + DylanTokens.s8)
            .padding(.top, DylanTokens.s8)

            HStack {
                Button(action: onQueue) {
                    Image(systemName: "list.bullet").font(.system(size: 18))
                        .foregroundStyle(DylanTokens.textSecondary)
                }
                .accessibilityLabel("Queue")
                Spacer()
                Button {
                    Task { await env.toggleFavorite(song) }
                    isFavorite.toggle()
                } label: {
                    Image(systemName: isFavorite ? "heart.fill" : "heart").font(.system(size: 18))
                        .foregroundStyle(isFavorite ? DylanTokens.primary : DylanTokens.textSecondary)
                }
                .accessibilityLabel(isFavorite ? "Unfavorite" : "Favorite")
                Spacer()
                Text(statusText(for: song, bitsFallback: bitsLabel))
                    .font(.dylLabelSmall)
                    .foregroundStyle(DylanTokens.textSecondary)
            }
            .padding(.horizontal, DylanTokens.s24 + DylanTokens.s8)
            .padding(.top, DylanTokens.s8)
            .padding(.bottom, DylanTokens.s12)
        }
    }

    /// Mirrors the Android status-label arm; Playing/Paused shows the REAL cached bitrate.
    private func statusText(
        for _: KSong,
        bitsFallback: String
    ) -> String {
        switch env.player.phaseKind {
        case "downloading", "resolving", "error":
            return env.player.statusLine
        case "playing", "paused", "ready":
            return bitsFallback
        default:
            return ""
        }
    }

    private func refreshFavorite(_ song: KSong) async {
        guard loadedSongToken == song.key.token else { return }
        isFavorite = await env.graph.isFavorite(song)
    }

    private func refreshBits(_ song: KSong) async {
        guard loadedSongToken == song.key.token else { return }
        let bits = await env.graph.cachedBitrate(for: song)
        bitsLabel = bits > 0 ? "\(bits)kbps" : ""
    }
}

/// Queue modal (§11.4): Up Next + Clear · current highlighted with eq-bars ·
/// move up/down (MoveWithinQueue) · swipe-remove (RemoveAt).
struct QueueSheet: View {
    @Environment(AppEnvironment.self) private var env
    var onDone: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Up Next")
                    .font(.dylTitleLarge)
                    .foregroundStyle(DylanTokens.textPrimary)
                Spacer()
                if env.player.currentIndex + 1 < Int32(env.player.queueSongs.count) {
                    Button("Clear") { env.graph.submit(Intents.clearUpNext) }
                        .font(.dylBodyMedium)
                        .foregroundStyle(DylanTokens.primary)
                }
                if let onDone {
                    Button("Done") { onDone() }
                        .font(.dylBodyMedium)
                        .foregroundStyle(DylanTokens.primary)
                }
            }
            .padding(.horizontal, DylanTokens.s16)
            .padding(.top, DylanTokens.s16)
            .padding(.bottom, DylanTokens.s8)

            List {
                ForEach(Array(env.player.queueSongs.enumerated()), id: \.offset) { i, song in
                    queueRow(i, song)
                        .listRowInsets(EdgeInsets(top: 6, leading: DylanTokens.s16, bottom: 6, trailing: DylanTokens.s16))
                        .listRowSeparator(.hidden)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
        .background(DylanTokens.surface)
        .presentationDetents([.large])
    }

    @ViewBuilder private func queueRow(
        _ i: Int,
        _ song: KSong
    ) -> some View {
        let isCurrent = i == Int(env.player.currentIndex)
        HStack(spacing: DylanTokens.s8) {
            Text(song.title)
                .font(.dylBodyLarge)
                .foregroundStyle(isCurrent ? DylanTokens.primary : DylanTokens.textPrimary)
                .lineLimit(1)
            if isCurrent { EqBars() }
            Spacer(minLength: 0)
            if !isCurrent {
                Button {
                    env.graph.submit(Intents.move(from: i, to: i - 1))
                } label: {
                    Image(systemName: "arrow.up")
                        .foregroundStyle(i > Int(env.player.currentIndex) + 1 ? DylanTokens.textSecondary : DylanTokens.divider)
                }
                .disabled(!(i > Int(env.player.currentIndex) + 1))
                .accessibilityLabel("Move up")

                Button {
                    env.graph.submit(Intents.move(from: i, to: i + 1))
                } label: {
                    Image(systemName: "arrow.down")
                        .foregroundStyle(i < env.player.queueSongs.count - 1 && i > Int(env.player.currentIndex) ? DylanTokens.textSecondary : DylanTokens.divider)
                }
                .disabled(!(i < env.player.queueSongs.count - 1 && i > Int(env.player.currentIndex)))
                .accessibilityLabel("Move down")

                Button {
                    env.graph.submit(Intents.remove(at: i))
                } label: {
                    Image(systemName: "xmark")
                        .foregroundStyle(DylanTokens.textSecondary)
                }
                .accessibilityLabel("Remove")
            } else {
                Text("Now playing")
                    .font(.dylLabelSmall)
                    .foregroundStyle(DylanTokens.primary)
                    .padding(.horizontal, DylanTokens.s12)
            }
        }
        .swipeActions(edge: .trailing) {
            if !isCurrent {
                Button(role: .destructive) {
                    env.graph.submit(Intents.remove(at: i))
                } label: { Label("Remove", systemImage: "trash") }
            }
        }
    }
}

/// §11.4 mini player above tabs: 40 px art · single line · 32 pt play/pause · 2 pt progress.
struct MiniPlayerBar: View {
    let onExpand: () -> Void

    @Environment(AppEnvironment.self) private var env

    var body: some View {
        Group {
            if let song = env.player.current {
                HStack(spacing: DylanTokens.s12) {
                    // A Button nested in another Button's label is not a second control: the
                    // outer one owns the tap, so play/pause from the bar never fired. Two
                    // sibling buttons, one row.
                    Button(action: onExpand) {
                        HStack(spacing: 10) {
                            ThumbImage(url: song.artUrl150)
                                .frame(width: 40, height: 40)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(song.title)
                                    .font(.dylBodyLarge)
                                    .foregroundStyle(DylanTokens.textPrimary)
                                    .lineLimit(1)
                                progressBar
                                    .frame(height: 2)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Now playing: \(song.title)")

                    Button {
                        env.graph.submit(Intents.toggle)
                    } label: {
                        Image(systemName: env.player.showsPause ? "pause.fill" : "play.fill")
                            .font(.system(size: 22))
                            .foregroundStyle(DylanTokens.textPrimary)
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel(env.player.showsPause ? "Pause" : "Play")
                }
                .padding(.horizontal, DylanTokens.s12)
                .padding(.vertical, DylanTokens.s8)
                .background(DylanTokens.surface)
            }
        }
    }

    private var progressBar: some View {
        GeometryReader { geo in
            let dur = max(1, env.player.current?.durationS ?? 1) * 1000
            // Mini-player parity with the sheet: always show pos (never snap to 0 when loading).
            let frac: Double = min(1.0, Double(env.player.positionMs) / Double(dur))
            ZStack(alignment: .leading) {
                Rectangle().fill(DylanTokens.divider.opacity(0.6))
                Rectangle()
                    .fill(DylanTokens.primary)
                    .frame(width: geo.size.width * CGFloat(frac))
            }
        }
    }
}
