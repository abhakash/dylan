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
    private var lastSubmittedSongId: String?

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
            self.syncInfo()
            let song = st.current
            if song?.key.songId != self.lastSubmittedSongId {
                self.lastSubmittedSongId = song?.key.songId
                self.loadArtwork(song)
            }
        }
        posHandle = graph.subscribePosition { [weak self] _ in
            // Throttled position mirror (~2 Hz max into the info center).
            guard let self else { return }
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            if now - self.lastInfoWriteMs > 500 { self.syncInfo() }
        }
    }

    // ---- MPNowPlayingInfoCenter ----------------------------------------------------------

    private func syncInfo() {
        let st = store.state
        guard let song = st.current else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        let durS = Double(song.durationS)
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: song.title,
            MPMediaItemPropertyArtist: song.subtitle.isEmpty ? (song.albumName ?? "") : song.subtitle,
            MPMediaItemPropertyPlaybackDuration: durS,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(store.positionMs) / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate: graph.playPauseShowsPause(state: st) ? 1.0 : 0.0,
        ]
        if let album = song.albumName { info[MPMediaItemPropertyAlbumTitle] = album }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        lastInfoWriteMs = Int64(Date().timeIntervalSince1970 * 1000)
    }

    private func loadArtwork(_ song: KSong?) {
        guard let song else { return }
        let token = song.key.songId
        Task { [weak self] in
            let img = await self?.thumbs.image(for: song.artUrl500, maxPixel: Thumbnailer.nowPlayingPixel)
            guard let self, let img, token == self.lastSubmittedSongId else { return }
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
        guard store.current != nil else { return }
        graph.submit(intent)
    }
}
