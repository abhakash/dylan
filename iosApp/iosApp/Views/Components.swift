import SwiftUI
import UIKit
import shared

// =============================================================================
// Components — SwiftUI mirrors of ui/components/SongRow.kt + Common.kt
// (plan §11.5 states: Default · Playing · Downloading · Cached · NonCacheable).
// =============================================================================

func formatBytes(_ b: Int64) -> String {
    switch b {
    case let x where x >= 1 << 30: String(format: "%.1f GB", Double(x) / Double(1 << 30))
    case let x where x >= 1 << 20: String(format: "%.1f MB", Double(x) / Double(1 << 20))
    case let x where x >= 1 << 10: String(format: "%.1f KB", Double(x) / Double(1 << 10))
    default: "\(b) B"
    }
}

func formatTime(ms: Int64) -> String {
    let s = Int(max(0, ms)) / 1000
    return String(format: "%d:%02d", s / 60, s % 60)
}

/// §11.9: rows decode at 150 px via ImageIO downsample — never AsyncImage native size.
struct ThumbImage: View {
    let url: String?
    var maxPixel: CGFloat = Thumbnailer.rowPixel
    var corner: CGFloat = DylanTokens.radiusSm

    @Environment(AppEnvironment.self) private var env
    @State private var image: UIImage?
    @State private var loadedUrl: String?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: corner).fill(DylanTokens.surfaceVariant)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: "music.note")
                    .foregroundStyle(DylanTokens.textSecondary.opacity(0.4))
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: corner))
        .task(id: url) {
            guard let url, url != loadedUrl else { return }
            let img = await env.thumbs.image(for: url, maxPixel: maxPixel)
            if !Task.isCancelled, url == self.url {
                image = img
                loadedUrl = url
            }
        }
    }
}

struct SectionTitle: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.dylTitleMedium)
            .foregroundStyle(DylanTokens.textPrimary)
            .padding(.horizontal, DylanTokens.s16)
            .padding(.top, DylanTokens.s12)
            .padding(.bottom, DylanTokens.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct Chip: View {
    let text: String
    var action: (() -> Void)?

    var body: some View {
        Text(text)
            .font(.dylBodyMedium)
            .lineLimit(1)
            .padding(.horizontal, 14)
            .padding(.vertical, DylanTokens.s8)
            .background(Capsule().fill(DylanTokens.surfaceVariant))
            .foregroundStyle(DylanTokens.textPrimary)
            .contentShape(Capsule())
            .onTapGesture { action?() }
    }
}

struct OfflineBanner: View {
    var body: some View {
        Text("You're offline — saved music still plays.")
            .font(.dylLabelSmall)
            .foregroundStyle(DylanTokens.error)
            .frame(maxWidth: .infinity)
            .padding(.vertical, DylanTokens.s8)
            .background(DylanTokens.surfaceVariant)
    }
}

/// §11.6: 3-bar eq, ~1 s loop — disabled under Reduce Motion.
struct EqBars: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var animating = false

    var body: some View {
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(0 ..< 3, id: \.self) { i in
                Capsule()
                    .fill(DylanTokens.primary)
                    .frame(width: 3, height: animating ? 14 : CGFloat(6 + i * 4))
                    .animation(
                        reduceMotion ? nil : .easeInOut(duration: 0.5)
                            .repeatForever(autoreverses: true)
                            .delay(Double(i) * 0.15),
                        value: animating
                    )
            }
        }
        .frame(height: 14, alignment: .bottom)
        .onAppear { animating = true }
        .accessibilityHidden(true)
    }
}

/// Per-key download ring (R7-P1): THIS row subscribes alone — one row's ring must not
/// re-render its neighbors. Subscription lifecycle rides appear/disappear.
private struct DownloadRing: View {
    let key: KSongKey

    @Environment(AppEnvironment.self) private var env
    @State private var pct: Int32 = -1
    @State private var handle: KSubscription?

    var body: some View {
        Group {
            if pct >= 0 && pct <= 99 {
                ZStack {
                    Circle().stroke(DylanTokens.surfaceVariant, lineWidth: 3)
                    Circle()
                        .trim(from: 0, to: CGFloat(pct) / 100.0)
                        .stroke(DylanTokens.primary, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                }
                .padding(12)
            }
        }
        .onAppear {
            guard handle == nil else { return }
            handle = env.graph.subscribeProgress(key: key) { p in pct = p.intValue }
        }
        .onDisappear {
            handle?.cancel()
            handle = nil
        }
    }
}

private struct CachedDot: View {
    var body: some View {
        Circle()
            .fill(DylanTokens.primary.opacity(0.9))
            .frame(width: 8, height: 8)
    }
}

/// Mirrors Android SongRow (§11.5): Default · Playing(eq) · Downloading(ring) · Cached(dot)
/// · NonCacheable(greyed + badge). Long-press menu = Android DropdownMenu items.
struct SongRowView: View {
    let song: KSong
    var index: Int?
    var isPlaying: Bool = false
    var isCached: Bool = false
    var enabled: Bool = true
    var onTap: () -> Void
    var onPlayNext: (() -> Void)?
    var onAddLast: (() -> Void)?
    var onFavorite: (() -> Void)?
    var onDownload: (() -> Void)?

    @Environment(AppEnvironment.self) private var env

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: DylanTokens.s12) {
                if let index {
                    Text("\(index)")
                        .font(.dylLabelSmall)
                        .foregroundStyle(DylanTokens.textSecondary)
                        .frame(width: 20)
                }
                ZStack {
                    ThumbImage(url: song.artUrl150)
                        .frame(width: 48, height: 48)
                    if isPlaying {
                        Color.white.opacity(0.001)
                        EqBars()
                    } else if isCached {
                        VStack { Spacer(); HStack { Spacer(); CachedDot() } }
                    }
                }
                .overlay(DownloadRing(key: song.key).frame(width: 48, height: 48))
                .frame(width: 48, height: 48)

                VStack(alignment: .leading, spacing: 2) {
                    Text(song.title)
                        .font(.dylBodyLarge)
                        .foregroundStyle(isPlaying ? DylanTokens.primary : DylanTokens.textPrimary)
                        .lineLimit(1)
                    Text(song.subtitle.isEmpty ? (song.albumName ?? "") : song.subtitle)
                        .font(.dylBodyMedium)
                        .foregroundStyle(DylanTokens.textSecondary)
                        .lineLimit(1)
                    if !song.cacheable {
                        Text("Not available offline")
                            .font(.dylLabelSmall)
                            .foregroundStyle(DylanTokens.error)
                    }
                }
                Spacer(minLength: 0)
                if song.has320 {
                    Text("320")
                        .font(.dylLabelSmall)
                        .foregroundStyle(DylanTokens.primary)
                }
            }
            .padding(.horizontal, DylanTokens.s16)
            .padding(.vertical, DylanTokens.s8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .opacity(enabled ? 1 : 0.45)
        .disabled(!enabled)
        .contextMenu { menu }
    }

    @ViewBuilder private var menu: some View {
        if let onPlayNext {
            Button(action: onPlayNext) { Label("Play next", systemImage: "text.line.first.and.arrow.forward") }
        }
        if let onAddLast {
            Button(action: onAddLast) { Label("Add to queue", systemImage: "plus") }
        }
        if let onFavorite {
            Button(action: onFavorite) { Label("Favorite", systemImage: "heart") }
        }
        if let onDownload {
            Button(action: onDownload) { Label("Download now", systemImage: "arrow.down.circle") }
        }
    }
}

/// Suggestions/home-rail entity row (Android MiniRow).
struct MiniRowView: View {
    let mini: KMiniEntity
    var greyed: Bool = false
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: DylanTokens.s12) {
                ThumbImage(url: mini.image)
                    .frame(width: 48, height: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Text(mini.title)
                        .font(.dylBodyLarge)
                        .foregroundStyle(DylanTokens.textPrimary)
                        .lineLimit(1)
                    Text(mini.subtitle)
                        .font(.dylBodyMedium)
                        .foregroundStyle(DylanTokens.textSecondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, DylanTokens.s16)
            .padding(.vertical, DylanTokens.s8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .opacity(greyed ? 0.5 : 1)
        .disabled(greyed)
    }
}

/// §11.4 Now Playing primary transport: 64 pt primary circle.
struct PlayPauseCircle: View {
    @Environment(AppEnvironment.self) private var env

    var body: some View {
        Button {
            env.graph.submit(Intents.toggle)
        } label: {
            ZStack {
                Circle().fill(DylanTokens.primary)
                Image(systemName: env.player.showsPause ? "pause.fill" : "play.fill")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundStyle(Color(hex: 0xFFFFFF))
            }
        }
        .frame(width: 64, height: 64)
        .accessibilityLabel(env.player.showsPause ? "Pause" : "Play")
    }
}
