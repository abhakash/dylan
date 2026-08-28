import AVFoundation
import Foundation
import Network
import Observation
import SwiftUI
import UIKit
import shared

// =============================================================================
// DylanApp — composition root (§12.2): URLCache singleton exactly once (R6-3),
// audio session category at launch non-interrupting (C.8), SharedIosGraph built
// with the Application Support base dir, Swift NativeAudioOutputImpl attached
// via IosGraph.attachAudio (engine + event sink wiring lives Kotlin-side),
// NWPathMonitor pushes metered state into the shared NetMonitor (D14).
// =============================================================================

@MainActor
@Observable
final class AppEnvironment {
    static private(set) var shared: AppEnvironment!

    let graph: KGraph
    let output: NativeAudioOutputImpl
    let thumbs: Thumbnailer

    let player = PlayerStore()
    let search = SearchStore()
    let home = HomeStore()
    let library = LibraryStore()
    let prefs = PrefsStore()
    let toasts = ToastStore()

    private let remote: NowPlayingController
    private let pathMonitor = NWPathMonitor()
    private var warningToken: NSObjectProtocol?

    var prefetchEnabled: Bool { graph.cfg.prefetchEnabled }

    var appVersion: String = {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "1.0"
    }()

    private init() {
        // §12.2/R6-3: ONE URLCache for the whole app — Thumbnailer rides URLSession.shared,
        // so artwork responses land in this 150 MB disk budget automatically.
        URLCache.shared = URLCache(
            memoryCapacity: 32 * 1024 * 1024,
            diskCapacity: 150 * 1024 * 1024,
            directory: Self.artworkCacheDir()
        )

        let support = Self.applicationSupportDir()
        // Kotlin companion fun → Swift: SharedIosGraph.companion.create(baseDir:)
        // (spelling assumption lives in DylanGraph.create, Bridge/DylanBridge.swift)
        graph = DylanGraph.create(baseDir: support)

        output = NativeAudioOutputImpl()
        thumbs = Thumbnailer()

        player.bind(graph)
        search.bind(graph)

        remote = NowPlayingController(graph: graph, store: player, thumbs: thumbs)
        remote.start()

        // C.8: setCategory at launch — cheap, interrupts nothing; setActive(true) happens
        // inside NativeAudioOutputImpl.play() immediately before first play.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)

        // Toast surface: Kotlin state lane → main thread hop.
        graph.onToast = { [weak self] msg in
            DispatchQueue.main.async {
                self?.toasts.show(msg)
            }
        }

        // D14: metered ⇔ isExpensive || isConstrained — pushed, never polled.
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let metered = path.isExpensive || path.isConstrained
            DispatchQueue.main.async {
                self?.graph.pushMetered(isMetered: metered)
            }
        }
        pathMonitor.start(queue: .global(qos: .utility))

        warningToken = NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.thumbs.flushMemory()
            URLCache.shared.removeAllCachedResponses()
        }
    }

    /// Idempotent one-shot bootstrap used by the App struct.
    static func bootstrap() -> AppEnvironment {
        if shared == nil {
            shared = AppEnvironment()
            shared.graph.attachAudio(output: shared.output)
        }
        return shared
    }

    deinit {
        pathMonitor.cancel()
        if let warningToken {
            NotificationCenter.default.removeObserver(warningToken)
        }
    }

    // ---- UI action helpers ---------------------------------------------------------------

    func play(_ songs: [KSong], at index: Int) {
        guard !songs.isEmpty else { return }
        graph.submit(Intents.playNow(songs, at: index))
    }

    func isPlaying(_ song: KSong) -> Bool {
        player.current?.key.token == song.key.token
    }

    /// Cached glyph derives from actual cached_files presence (§11.4 Library), not the pin flag.
    func isCached(_ song: KSong) -> Bool {
        library.downloads.contains { $0.song.key.token == song.key.token }
    }

    func toggleFavorite(_ song: KSong) async {
        if await graph.isFavorite(song) {
            await graph.removeFavorite(song)
        } else {
            await graph.addFavorite(song)
        }
        await library.loadFavorites(graph)
    }

    func downloadNow(_ song: KSong) async {
        await graph.downloadNow(song)
        toasts.show("Downloading “\(song.title)”")
    }

    // ---- paths ---------------------------------------------------------------------------

    private static func applicationSupportDir() -> String {
        let fm = FileManager.default
        let base = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fm.temporaryDirectory
        let dir = base.appendingPathComponent("dylan", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.path
    }

    private static func artworkCacheDir() -> URL? {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
        let dir = caches?.appendingPathComponent("artwork", isDirectory: true)
        if let dir {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }
}

@main
struct DylanApp: App {
    private let env = AppEnvironment.bootstrap()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(env)
                .tint(DylanTokens.primary)
        }
    }
}
