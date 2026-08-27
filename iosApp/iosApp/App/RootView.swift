import SwiftUI
import shared

// =============================================================================
// RootView — SwiftUI mirror of Android AppRoot.kt: three tabs (Home · Search ·
// Library), mini player above the tab bar, full-screen Now Playing / Queue /
// Settings sheets, album detail as a pushed cover (Android swaps tab content;
// SwiftUI idiom noted in BUILD-NOTES).
// =============================================================================

// =============================================================================
// SheetKind — the single sheet machine for RootView (Closed = nil).
// =============================================================================

private enum SheetKind: String, Identifiable {
    case np, queue, settings
    var id: String { rawValue }
}

struct RootView: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(\.scenePhase) private var scenePhase

    @State private var tab = 0
    @State private var albumId: String?
    /// Single sheet machine: only one of NP / Queue / Settings is ever presented.
    @State private var sheet: SheetKind?

    var body: some View {
        ZStack(alignment: .bottom) {
            VStack(spacing: 0) {
                if let albumId {
                    AlbumScreen(albumId: albumId) { self.albumId = nil }
                } else {
                    switch tab {
                    case 0:                 HomeScreen(onOpenAlbum: { albumId = $0 }, onOpenSettings: { sheet = .settings })
                    case 1: SearchScreen(onOpenAlbum: { albumId = $0 })
                    default: LibraryScreen()
                    }
                }

                // §11.4 mini player sits above the tab bar while current ≠ null — it stays
                // mounted under the NP sheet so dismissing reveals it continuously.
                if env.player.current != nil {
                    MiniPlayerBar { sheet = .np }
                }
                tabBar
            }

            toastOverlay
        }
        .background(DylanTokens.background)
        .sheet(item: $sheet) { kind in
            switch kind {
            case .np:
                if env.player.current != nil {
                    NowPlayingSheet(
                        onQueue: { sheet = .queue },
                        onDismiss: { sheet = nil }
                    )
                    .environment(env)
                }
            case .queue:
                QueueSheet(onDone: { sheet = env.player.current != nil ? .np : nil })
                    .environment(env)
            case .settings:
                SettingsPanel().environment(env)
            }
        }
        .onChange(of: env.player.current?.key.token) { _ in
            // Empty current ⇒ close everything (sheets never outlive the session).
            if env.player.current == nil { sheet = nil }
        }
        .onChange(of: scenePhase) { phase in
            // §9.10: background ⇒ snapshot write + WS close; audio session keeps the
            // process alive during playback so prefetch continues naturally.
            if phase == .background || phase == .inactive {
                env.graph.onBackground()
            }
        }
    }

    private var tabBar: some View {
        HStack {
            tabButton(0, "Home", icon: "house.fill")
            tabButton(1, "Search", icon: "magnifyingglass")
            tabButton(2, "Library", icon: "square.stack")
        }
        .padding(.top, DylanTokens.s6)
        .padding(.bottom, DylanTokens.s4)
        .background(DylanTokens.surface)
    }

    private func tabButton(
        _ index: Int,
        _ label: String,
        icon: String
    ) -> some View {
        Button {
            withAnimation(.easeInOut(duration: DylanTokens.fastMs / 1000)) {
                tab = index
                albumId = nil
            }
        } label: {
            VStack(spacing: 2) {
                Image(systemName: icon)
                    .font(.system(size: 22))
                    .foregroundStyle(tab == index ? DylanTokens.primary : DylanTokens.textSecondary)
                Text(label)
                    .font(.dylLabelSmall)
                    .foregroundStyle(tab == index ? DylanTokens.primary : DylanTokens.textSecondary)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    @ViewBuilder private var toastOverlay: some View {
        if env.toasts.visible {
            Text(env.toasts.message)
                .font(.dylBodyMedium)
                .foregroundStyle(Color(hex: 0xFFFFFF))
                .padding(.horizontal, DylanTokens.s16)
                .padding(.vertical, DylanTokens.s12)
                .background(RoundedRectangle(cornerRadius: DylanTokens.radiusLg).fill(Color.black.opacity(0.8)))
                .padding(.bottom, 96)
                .transition(.opacity)
                .animation(.easeInOut(duration: DylanTokens.fastMs / 1000), value: env.toasts.visible)
        }
    }
}
