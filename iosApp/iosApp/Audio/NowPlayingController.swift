import AVFoundation
import Foundation
import MediaPlayer
import shared

// =============================================================================
// NowPlayingController — lock-screen surface (§9.10): MPRemoteCommandCenter routes
// through the SAME intent bus as UI (Law 4); MPNowPlayingInfoCenter mirrors
// PlayerState + a throttled position; artwork decodes at 500 px via Thumbnailer.
// =============================================================================

@MainActor
final class NowPlayingController {
    private let graph: KGraph
    private let thumbs: Thumbnailer
    private let store: PlayerStore

    private var stateHandle: KSubscription?
    private var posHandle: KSubscription?
    private var commandTokens: [Any] = []
    private var lastInfoWriteMs: Int64 = 0
    private var lastSubmittedToken: String?
    private var lastPlayingFlag = false

    init(
        graph: KGraph,
        store: PlayerStore,
        thumbs: Thumbnailer
    ) {
        self.graph = graph
        self.store = store
        self.thumbs = thumbs
        registerCommands()
    }

    deinit {
        stateHandle?.cancel()
        posHandle?.cancel()
    }

    func start() {
        guard stateHandle == nil else { return }
        stateHandle = graph.subscribePlayerState { [weak self] st in
            guard let self else { return }
            self.syncInfo(st)
            let token = st.current?.key.token
            if token != self.lastSubmittedToken {
                self.lastSubmittedToken = token
                self.loadArtwork(st.current)
            }
        }
        posHandle = graph.subscribePosition { [weak self] _ in
            // Throttled position mirror (~2 Hz max into the info center).
            guard let self else { return }
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            if now - self.lastInfoWriteMs > 500, let st = self.store.state { self.syncInfo(st) }
        }
    }

    // ---- MPNowPlayingInfoCenter ----------------------------------------------------------

    /// Takes the callback's state param — never the store's (which can lag a hop behind).
    private func syncInfo(_ st: KPlayerState) {
        guard let song = st.current else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        // Preserve artwork across throttled position mirrors — otherwise every
        // 500 ms position update wipes the image that loadArtwork just wrote.
        let previousArtwork = MPNowPlayingInfoCenter.default().nowPlayingInfo?[MPMediaItemPropertyArtwork]
        let durS = Double(song.durationS)
        let isPlaying = graph.playPauseShowsPause(state: st)
        // Activate the session once per play/pause transition — not on every throttled sync.
        if isPlaying != lastPlayingFlag {
            lastPlayingFlag = isPlaying
            if isPlaying {
                try? AVAudioSession.sharedInstance().setActive(true)
            }
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: song.title,
            MPMediaItemPropertyArtist: song.subtitle.isEmpty ? (song.albumName ?? "") : song.subtitle,
            MPMediaItemPropertyPlaybackDuration: durS,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(store.positionMs) / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
        ]
        if let album = song.albumName { info[MPMediaItemPropertyAlbumTitle] = album }
        // Carry artwork forward when still showing the same song.
        if let previousArtwork, song.key.token == lastSubmittedToken {
            info[MPMediaItemPropertyArtwork] = previousArtwork
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        if #available(iOS 17.0, *) {
            MPNowPlayingInfoCenter.default().playbackState = isPlaying ? .playing : .paused
        }
        lastInfoWriteMs = Int64(Date().timeIntervalSince1970 * 1000)
    }

    private func loadArtwork(_ song: KSong?) {
        guard let song else { return }
        let token = song.key.token
        Task { [weak self] in
            let img = await self?.thumbs.image(for: song.artUrl500, maxPixel: Thumbnailer.nowPlayingPixel)
            guard let self, let img, token == self.lastSubmittedToken else { return }
            let artwork = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
            var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
            info[MPMediaItemPropertyArtwork] = artwork
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
    }

    // ---- MPRemoteCommandCenter -------------------------------------------------------------

    private func registerCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.isEnabled = true
        center.pauseCommand.isEnabled = true
        center.togglePlayPauseCommand.isEnabled = true
        center.nextTrackCommand.isEnabled = true
        center.previousTrackCommand.isEnabled = true
        center.changePlaybackPositionCommand.isEnabled = true

        // The shared bus has no bare "play" — TogglePlayPause covers resume-from-paused.
        // All intents come from Bridge.Intents — this file names no raw Kotlin symbols.
        commandTokens.append(center.playCommand.addTarget { [weak self] event in
            self?.submit(Intents.toggle)
            return .success
        })
        commandTokens.append(center.pauseCommand.addTarget { [weak self] event in
            self?.submit(Intents.toggle)
            return .success
        })
        commandTokens.append(center.togglePlayPauseCommand.addTarget { [weak self] event in
            self?.submit(Intents.toggle)
            return .success
        })
        commandTokens.append(center.nextTrackCommand.addTarget { [weak self] event in
            self?.submit(Intents.next)
            return .success
        })
        commandTokens.append(center.previousTrackCommand.addTarget { [weak self] event in
            self?.submit(Intents.previous)
            return .success
        })
        commandTokens.append(center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let posEvent = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let ms = Int64(max(0, posEvent.positionTime) * 1000.0)
            self?.submit(Intents.seek(ms: ms))
            return .success
        })
    }

    private func submit(_ intent: KIntent) {
        // No bare-current guard: intents are legal whenever the queue is non-empty
        // (e.g. next/previous/seek right after restore, before current lands).
        guard store.current != nil || !store.queueSongs.isEmpty else { return }
        graph.submit(intent)
    }
}
