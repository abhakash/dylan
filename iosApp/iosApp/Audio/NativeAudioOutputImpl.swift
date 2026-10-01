import AVFoundation
import Foundation
import MediaPlayer
import shared

/// Swift half of the engine seam (plan §9.4, R1-B.2): imperative only — no flows,
/// no suspend. Kotlin owns events/position via IosPlayerEngine; this class pushes
/// AVFoundation observations through the EngineEventSink it receives in bindEvents.
///
/// Threading. The Kotlin engine seam calls these methods from the shared *state lane*
/// (Dispatchers.Default.limitedParallelism(1)), not the main thread, while AVFoundation
/// delivers `AVPlayerItem.status` KVO on the thread that changed the value and posts
/// `AVPlayerItemDidPlayToEndTime` on its own queue. So every entry point below hops to the
/// main queue and every stored property is main-thread-only — the dictionaries are otherwise a
/// data race, not a style question. The hop is FIFO from the state lane, so a
/// `prepare`-then-`play` pair from one inbox turn still runs in that order.
///
/// Window contract:
///   * prepare(items) rebuilds the 1–2 item window; Prepared(first) is emitted when the
///     CURRENT item reaches .readyToPlay — mirroring the Android D8 fix (ExoPlayer
///     emits Prepared on STATE_READY, never before). A failed item maps to Error(.source).
///     A same-head re-prepare re-answers immediately rather than waiting for a status
///     transition that will not come again.
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

        // Every observer below is registered with `queue: nil`, so the block runs on whichever
        // thread posted — for end-of-item that is an AVFoundation queue, not main. Each one hops
        // through `onMain` so the player and the two dictionaries stay on one thread.
        kvoToken = player.observe(\AVQueuePlayer.currentItem, options: [.old, .new]) { [weak self] _, change in
            guard let self else { return }
            let item = change.newValue ?? nil
            self.onMain { self.currentItemChanged(item) }
        }

        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: nil,
            queue: nil
        ) { [weak self] note in
            guard let self else { return }
            let item = note.object as? AVPlayerItem
            self.onMain { self.itemDidEnd(item) }
        }

        routeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: nil
        ) { [weak self] note in
            let reason = UInt(note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt ?? 0)
            guard reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue else { return }
            self?.onMain {
                guard let self, !self.released else { return }
                self.player.pause()
                self.emit(Events.routeLost())
            }
        }

        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: nil
        ) { [weak self] note in
            let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt ?? 0
            let type = AVAudioSession.InterruptionType(rawValue: raw) ?? .began
            let optRaw = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let shouldResume = AVAudioSession.InterruptionOptions(rawValue: optRaw).contains(.shouldResume)
            self?.onMain {
                guard let self, !self.released else { return }
                switch type {
                case .began: self.emit(Events.interrupted(false))
                case .ended: self.emit(Events.interrupted(shouldResume))
                default: break
                }
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
        onMain { prepareOnMain(items) }
    }

    private func prepareOnMain(_ items: [KLocalTrack]) {
        guard !released else { return }
        suppressCurrentItemEvents = true
        // Window diff: if the new head is already the audible item, keep it —
        // removeAllItems mid-play killed audio before Prepared and swallowed the
        // TrackChanged needed for resync. Only rebuild when the head changed.
        if let wantHead = items.first,
           let curHead = player.items().first,
           itemIds[ObjectIdentifier(curHead)] == wantHead.itemId {
            replaceTail(with: Array(items.dropFirst().prefix(1)))
            rearmPreparedForHead()
        } else {
            dropStatusObservers()
            player.removeAllItems()
            itemIds.removeAll()
            preparedEmittedForWindow = false
            queueExhaustedEmitted = false
            for t in items.prefix(2) {
                insert(t, after: nil)
            }
        }
        suppressCurrentItemEvents = false
    }

    /// `prepare` is a request for the window to be ready, so a same-head re-prepare has to answer
    /// it. An `AVPlayerItem` has no second `.readyToPlay` transition to wait for and the diff path
    /// leaves the old status observer attached, so without this the orchestrator's resync path
    /// re-prepares and then waits for an event that can never arrive.
    private func rearmPreparedForHead() {
        guard let head = player.items().first, let id = itemIds[ObjectIdentifier(head)] else { return }
        preparedEmittedForWindow = false
        switch head.status {
        case .readyToPlay:
            preparedEmittedForWindow = true
            emit(Events.prepared(id))
        case .failed:
            emit(Events.error(id, KEngineErr.source))
        default:
            break // the observer is still attached and will fire.
        }
    }

    /// Rebuilds everything after index 0 (shared by prepare-diff and replaceUpNext).
    private func replaceTail(with tails: [KLocalTrack]) {
        while player.items().count > 1, let last = player.items().last {
            statusObservers.removeValue(forKey: ObjectIdentifier(last))?.invalidate()
            itemIds.removeValue(forKey: ObjectIdentifier(last))
            player.remove(last)
        }
        if let item = tails.first {
            _ = insert(item, after: player.items().first)
        }
        queueExhaustedEmitted = false
    }

    @objc(replaceUpNextItem:)
    func replaceUpNext(item: KLocalTrack?) {
        onMain {
            guard !released else { return }
            // Remove ONLY queued items beyond index 0 (§9.4 iOS mapping).
            replaceTail(with: item.map { [$0] } ?? [])
        }
    }

    @objc(play)
    func play() {
        onMain {
            guard !released else { return }
            do {
                try AVAudioSession.sharedInstance().setActive(true)
                player.play()
            } catch {
                emit(Events.error(nil, KEngineErr.sessionActivation))
            }
        }
    }

    @objc(pause)
    func pause() {
        onMain {
            guard !released else { return }
            player.pause()
        }
    }

    @objc(seekToMs:)
    func seekTo(ms: Int64) {
        onMain {
            guard !released else { return }
            let time = CMTime(value: CMTimeValue(ms), timescale: 1000)
            player.currentItem?.seek(
                to: time,
                toleranceBefore: .zero,
                toleranceAfter: .zero
            )
        }
    }

    // ---- rate + relative seek --------------------------------------------------------
    // `MIN_PLAYBACK_RATE` / `MAX_PLAYBACK_RATE` from shared/src/commonMain/.../playback/Engine.kt.
    // Duplicated rather than imported because this is the side that actually applies the rate, so
    // a divergence from `clampPlaybackRate` would be silent on the Kotlin side and audible here.
    private static let minRate: Float = 0.5
    private static let maxRate: Float = 2.0

    /// Speed only, clamped into the seam's range, with non-finite input dropped — `coerceIn` alone
    /// would hand NaN through, and a player whose rate is NaN never advances again.
    ///
    /// The trap this method exists to avoid: **assigning a positive `AVPlayer.rate` to a paused
    /// player BEGINS PLAYBACK.** So `rate` is assigned only while the player is already running,
    /// and `defaultRate` carries the request across a pause. `play()` is documented to start at
    /// `defaultRate`, so a rate set while paused applies on the next play and `play()` itself needs
    /// no change — which keeps "a rate is a transport property, not a play request" true on both
    /// sides of the bridge.
    @objc(setRateRate:)
    func setRate(rate: Float) {
        onMain {
            guard !released, rate.isFinite else { return }
            let clamped = min(max(rate, Self.minRate), Self.maxRate)
            player.defaultRate = clamped
            guard player.rate > 0 else { return }
            if player.rate != clamped {
                player.rate = clamped
            }
        }
    }

    /// Relative seek, from the output's own `currentTime()` — never from a caller-supplied
    /// position, which is a 10 Hz sample of this same clock and therefore already stale.
    @objc(skipForwardMs:)
    func skipForward(ms: Int64) {
        skipOnMain(byMs: ms)
    }

    /// A negative amount is not a direction change, it is a no-op — the same
    /// `ms.coerceAtLeast(0L)` the Kotlin `PlayerEngine.skipBackward` default applies.
    @objc(skipBackwardMs:)
    func skipBackward(ms: Int64) {
        skipOnMain(byMs: ms > 0 ? -ms : 0)
    }

    /// Clamped against the item's duration **only when that duration is known**. An `AVPlayerItem`
    /// that has not finished loading reports `.indefinite`, and clamping a skip against that
    /// would pin it to 0 forever — the exact bug class this codebase already paid for on the
    /// Kotlin side. Unknown duration ⇒ seek anyway and let the decoder decide.
    private func skipOnMain(byMs deltaMs: Int64) {
        guard !released, let item = player.currentItem else { return }
        let baseSeconds = player.currentTime().seconds
        guard baseSeconds.isFinite, baseSeconds >= 0 else { return }
        var targetSeconds = baseSeconds + Double(deltaMs) / 1000.0
        let durationSeconds = item.duration.seconds
        if item.duration.isNumeric, durationSeconds.isFinite, durationSeconds > 0 {
            targetSeconds = min(max(targetSeconds, 0), durationSeconds)
        }
        // Tolerant tolerances, unlike `seekTo`: a skip only has to land in the right place, and
        // demanding sample accuracy makes AVFoundation decode from the preceding keyframe.
        item.seek(
            to: CMTime(seconds: targetSeconds, preferredTimescale: 1000),
            toleranceBefore: .positiveInfinity,
            toleranceAfter: .positiveInfinity
        )
    }

    @objc(currentTimeMs)
    func currentTimeMs() -> Int64 {
        onMain { currentTimeOnMain() }
    }

    private func currentTimeOnMain() -> Int64 {
        guard !released else { return 0 }
        // CMTimeGetSeconds returns NaN/∞ for invalid times; converting non-finite
        // doubles to Int64 is UB and would poison the position lane.
        let seconds = CMTimeGetSeconds(player.currentTime())
        guard seconds.isFinite, seconds > 0 else { return 0 }
        return Int64(seconds * 1000.0)
    }

    @objc(bindEventsSink:)
    func bindEvents(sink: KEngineEventSink) {
        onMain { self.sink = sink }
    }

    func dispose() {
        onMain {
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
    }

    /// Safety net: block-based NotificationCenter tokens are NOT auto-removed on
    /// dealloc (unlike NSKeyValueObservation), so a dealloc without dispose() would leak three
    /// observer blocks. `dispose()` is idempotent via the `released` flag, so these are no-ops
    /// after it has run.
    ///
    /// Written without `onMain` on purpose: a `deinit` must not hand `self` to a closure that
    /// could outlive it, and the object is already being torn down, so there is nothing left to
    /// keep on a queue.
    deinit {
        kvoToken?.invalidate()
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        if let routeObserver { NotificationCenter.default.removeObserver(routeObserver) }
        if let interruptionObserver { NotificationCenter.default.removeObserver(interruptionObserver) }
    }

    // ---- internals -----------------------------------------------------------------------

    /// Main-queue gate. Inline on main, so the AVFoundation callbacks that re-enter this object
    /// (KVO on `removeAllItems`, the end-of-item notification) stay on one thread instead of
    /// deadlocking or bouncing through a second hop.
    private func onMain(_ body: @escaping @MainActor () -> Void) {
        if Thread.isMainThread {
            MainActor.assumeIsolated(body)
        } else {
            DispatchQueue.main.async { MainActor.assumeIsolated(body) }
        }
    }

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
            self?.onMain { self?.statusChanged(item) }
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
            emit(Events.trackChanged(id, KTransitionReason.auto_))
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
