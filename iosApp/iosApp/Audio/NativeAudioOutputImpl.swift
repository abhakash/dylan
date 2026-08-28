import AVFoundation
import Foundation
import MediaPlayer
import shared

/// Swift half of the engine seam (plan §9.4, R1-B.2): imperative only — no flows,
/// no suspend. Kotlin owns events/position via IosPlayerEngine; this class pushes
/// AVFoundation observations through the EngineEventSink it receives in bindEvents.
///
/// Window contract:
///   * prepare(items) rebuilds the 1–2 item window; Prepared(first) is emitted when the
///     CURRENT item reaches .readyToPlay — mirroring the Android D8 fix (ExoPlayer
///     emits Prepared on STATE_READY, never before). A failed item maps to Error(.source).
///   * replaceUpNext removes queued items BEYOND index 0 only (never removeAllItems —
///     that kills audible playback), then inserts.
///   * KVO currentItem change → TrackChanged(AUTO); last item's natural end → QueueExhausted;
///     non-last natural end → ItemEnded followed by TrackChanged(AUTO) from the advance.
final class NativeAudioOutputImpl: NSObject, KNativeAudioOutput {
    private let player = AVQueuePlayer()
    private weak var sink: KEngineEventSink?
    private var itemIds: [ObjectIdentifier: String] = [:]
    private var statusObservers: [ObjectIdentifier: NSKeyValueObservation] = [:]
    private var suppressCurrentItemEvents = false
    private var preparedEmittedForWindow = false
    private var queueExhaustedEmitted = false
    private var released = false

    private var kvoToken: NSKeyValueObservation?
    private var endObserver: NSObjectProtocol?
    private var routeObserver: NSObjectProtocol?
    private var interruptionObserver: NSObjectProtocol?

    override init() {
        super.init()
        player.actionAtItemEnd = .advance
        player.automaticallyWaitsToMinimizeStalling = true

        kvoToken = player.observe(\AVQueuePlayer.currentItem, options: [.old, .new]) { [weak self] _, change in
            self?.currentItemChanged(change.newValue ?? nil)
        }

        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: nil,
            queue: nil
        ) { [weak self] note in
            self?.itemDidEnd(note.object as? AVPlayerItem)
        }

        routeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: nil
        ) { [weak self] note in
            guard let self, !self.released else { return }
            let reason = UInt(note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt ?? 0)
            if reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue {
                self.player.pause()
                self.emit(Events.routeLost())
            }
        }

        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: nil
        ) { [weak self] note in
            guard let self, !self.released else { return }
            let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt ?? 0
            let type = AVAudioSession.InterruptionType(rawValue: raw) ?? .began
            switch type {
            case .began:
                self.emit(Events.interrupted(false))
            case .ended:
                let optRaw = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
                let shouldResume = AVAudioSession.InterruptionOptions(rawValue: optRaw).contains(.shouldResume)
                self.emit(Events.interrupted(shouldResume))
            default:
                break
            }
        }
    }

    // ---- NativeAudioOutput ------------------------------------------------------------
    // ObjC selectors are suffixed (prepareItems:, bindEventsSink:, etc.) with swift_name mapping
    // to clean Swift names (prepare(items:), bindEvents(sink:), etc.). `release` is mangled to
    // `release_` in ObjC to avoid NSObject collision but Swift name remains `release()`.
    // Explicit @objc(selector) ensures the Swift witness uses the ObjC selector expected by the
    // Kotlin header (`shared.h` @protocol SharedNativeAudioOutput, swift_name NativeAudioOutput).

    @objc(prepareItems:)
    func prepare(items: [KLocalTrack]) {
        guard !released else { return }
        suppressCurrentItemEvents = true
        dropStatusObservers()
        player.removeAllItems()
        itemIds.removeAll()
        preparedEmittedForWindow = false
        queueExhaustedEmitted = false
        for t in items.prefix(2) {
            insert(t, after: nil)
        }
        suppressCurrentItemEvents = false
    }

    @objc(replaceUpNextItem:)
    func replaceUpNext(item: KLocalTrack?) {
        guard !released else { return }
        // Remove ONLY queued items beyond index 0 (§9.4 iOS mapping).
        while player.items().count > 1, let last = player.items().last {
            statusObservers.removeValue(forKey: ObjectIdentifier(last))?.invalidate()
            itemIds.removeValue(forKey: ObjectIdentifier(last))
            player.remove(last)
        }
        if let item {
            _ = insert(item, after: player.items().first)
        }
        queueExhaustedEmitted = false
    }

    @objc(play)
    func play() {
        guard !released else { return }
        do {
            try AVAudioSession.sharedInstance().setActive(true)
            player.play()
        } catch {
            emit(Events.error(nil, KEngineErr.sessionActivation))
        }
    }

    @objc(pause)
    func pause() {
        guard !released else { return }
        player.pause()
    }

    @objc(seekToMs:)
    func seekTo(ms: Int64) {
        guard !released else { return }
        let time = CMTime(value: CMTimeValue(ms), timescale: 1000)
        player.currentItem?.seek(
            to: time,
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
    }

    @objc(currentTimeMs)
    func currentTimeMs() -> Int64 {
        guard !released else { return 0 }
        // CMTimeGetSeconds returns NaN/∞ for invalid times; converting non-finite
        // doubles to Int64 is UB and would poison the position lane.
        let seconds = CMTimeGetSeconds(player.currentTime())
        guard seconds.isFinite, seconds > 0 else { return 0 }
        return Int64(seconds * 1000.0)
    }

    @objc(bindEventsSink:)
    func bindEvents(sink: KEngineEventSink) {
        self.sink = sink
    }

    func dispose() {
        guard !released else { return }
        released = true
        player.pause()
        player.removeAllItems()
        itemIds.removeAll()
        dropStatusObservers()
        kvoToken?.invalidate()
        kvoToken = nil
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        if let routeObserver { NotificationCenter.default.removeObserver(routeObserver) }
        if let interruptionObserver { NotificationCenter.default.removeObserver(interruptionObserver) }
        endObserver = nil
        routeObserver = nil
        interruptionObserver = nil
    }

    /// Safety net: block-based NotificationCenter tokens are NOT auto-removed on
    /// dealloc (unlike NSKeyValueObservation), so a dealloc without release() would
    /// leak observer blocks. dispose() is idempotent via the released flag.
    deinit {
        dispose()
    }

    // ---- internals -----------------------------------------------------------------------

    private func dropStatusObservers() {
        statusObservers.values.forEach { $0.invalidate() }
        statusObservers.removeAll()
    }

    @discardableResult
    private func insert(
        _ t: KLocalTrack,
        after anchor: AVPlayerItem?
    ) -> AVPlayerItem {
        let av = AVPlayerItem(url: URL(fileURLWithPath: t.path))
        itemIds[ObjectIdentifier(av)] = t.itemId
        observeStatus(av)
        player.insert(av, after: anchor)
        return av
    }

    /// D8 mirror: readiness is an EVENT, not an assumption. Prepared(itemId) fires when the
    /// current-slot item reports .readyToPlay; a failed item reports Error(.source) so the
    /// orchestrator can skip instead of hanging in Ready forever.
    private func observeStatus(_ item: AVPlayerItem) {
        let token = item.observe(\AVPlayerItem.status, options: [.new]) { [weak self] item, _ in
            DispatchQueue.main.async { self?.statusChanged(item) }
        }
        statusObservers[ObjectIdentifier(item)] = token
    }

    private func statusChanged(_ item: AVPlayerItem) {
        guard !released else { return }
        switch item.status {
        case .readyToPlay:
            guard !preparedEmittedForWindow,
                  player.items().first === item,
                  let id = itemIds[ObjectIdentifier(item)] else { return }
            preparedEmittedForWindow = true
            emit(Events.prepared(id))
        case .failed:
            statusObservers.removeValue(forKey: ObjectIdentifier(item))?.invalidate()
            guard let id = itemIds[ObjectIdentifier(item)] else { return }
            if player.items().first === item { preparedEmittedForWindow = true }
            emit(Events.error(id, KEngineErr.source))
        default:
            break
        }
    }

    private func currentItemChanged(_ newItem: AVPlayerItem?) {
        guard !released, !suppressCurrentItemEvents else { return }
        if let item = newItem {
            guard let id = itemIds[ObjectIdentifier(item)] else { return } // unknown id → orchestrator resync not needed; window is ours
            queueExhaustedEmitted = false
            emit(Events.trackChanged(id, KTransitionReason.AUTO))
        } else if !queueExhaustedEmitted, player.items().isEmpty {
            queueExhaustedEmitted = true
            emit(Events.queueExhausted())
        }
    }

    private func itemDidEnd(_ item: AVPlayerItem?) {
        guard !released, let item else { return }
        guard let id = itemIds[ObjectIdentifier(item)] else { return }
        let items = player.items()
        guard let idx = items.firstIndex(of: item) else { return }
        if idx + 1 < items.count {
            // Successor existed → informational ItemEnded; the advance fires TrackChanged(AUTO).
            emit(Events.itemEnded(id))
        } else if !queueExhaustedEmitted {
            queueExhaustedEmitted = true
            emit(Events.queueExhausted())
        }
    }

    private func emit(_ e: KEngineEvent) {
        sink?.onEvent(e: e)
    }
}
