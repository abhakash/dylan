import Foundation
import os
import shared

// =============================================================================
// DylanBridge — THE ONLY file naming Kotlin symbols directly.
//
// ═══════════════════════════════════════════════════════════════════════════
// DYLAN ↔ KOTLIN BRIDGE ASSUMPTION BLOCK  (single-file fix point on Xcode day)
// Every typealias below states the exact symbol to check in
// shared/build/bin/<sdk>/<config>Framework/shared.framework/Headers/shared-Swift.h
// (or via Swift jump-to-definition after one successful build).
// If any disagrees, fix IT HERE ONLY (+ KNativeAudioOutput/KEngineEventSink
// conformances in NativeAudioOutputImpl.swift) — nothing else in the app
// names a Kotlin symbol. Full inventory with verification steps:
// iosApp/BUILD-NOTES.md §3 "Bridge assumption inventory".
// ═══════════════════════════════════════════════════════════════════════════
//
// Global rules (each verified against the header once):
//   A1  ObjC prefix = capitalized framework baseName: ALL exported Kotlin
//       declarations arrive as "Shared<Name>" — module-qualified UNPREFIXED
//       spellings (shared.Foo) do NOT exist and will not compile.
//   A2  Kotlin interface → ObjC @protocol; importer appends "-Protocol" where
//       a same-named entity exists (applies to every *-Protocol alias below).
//   A3  Nested classes flatten: Intent.PlayNow → SharedIntentPlayNow;
//       EngineEvent.Prepared → SharedEngineEventPrepared.
//   A4  Kotlin object/data object → class + `.shared` singleton accessor.
//   A5  Kotlin Int → Int32, Long → Int64, Boolean → Bool; enum entries
//       lowerCamelCase (SESSION_ACTIVATION → .sessionActivation).
//   A6  Kotlin List<T> bridges as untyped NSArray → cast defensively
//       (`as? [KSong]`) at every crossing — never trust the element type.
//   A7  suspend fun → completion-handler method + auto `async` variant
//       (`try await …`); nullable returns bridge as optionals.
//   A8  (String) -> Unit properties/closures bridge as blocks; assign plain
//       Swift closures.
// =============================================================================

// ---- core graph & models ----------------------------------------------------
//   check: @interface SharedIosGraph (class dylan.di.IosGraph)
typealias KGraph = SharedIosGraph
//   check: @interface SharedKotlinSubscription (dylan.bridge.KotlinSubscription);
//          NOTE: was `shared.KotlinSubscription` — unprefixed spelling is wrong.
typealias KSubscription = SharedKotlinSubscription
typealias KSong = SharedSong                    // data class Song
typealias KSongKey = SharedSongKey              // data class SongKey
typealias KMiniEntity = SharedMiniEntity        // data class MiniEntity
typealias KAlbum = SharedAlbum                  // data class Album
typealias KHomeSection = SharedHomeSection      // data class HomeSection
typealias KPlayerState = SharedPlayerState      // data class PlayerState
typealias KDylanFailure = SharedDylanFailure    // data class DylanFailure
typealias KCachedSongInfo = SharedCachedSongInfo // data class CachedSongInfo (iosMain)
typealias KCacheStats = SharedCacheStats        // data class CacheStats (iosMain)
//   check: generic erasure — Paged<T> exports WITHOUT its type parameter;
//   `.items` arrives as [Any] (A6), `.total` as Int64 (A5).
typealias KPaged = SharedPaged                  // data class Paged<T>
typealias KLocalTrack = SharedLocalTrack        // data class LocalTrack

// ---- engine seam -------------------------------------------------------------
//   check: @protocol SharedNativeAudioOutput ("NativeAudioOutput" + -Protocol, A2)
typealias KNativeAudioOutput = SharedNativeAudioOutputProtocol
//   check: @protocol SharedEngineEventSink ("EngineEventSink" + -Protocol, A2)
typealias KEngineEventSink = SharedEngineEventSinkProtocol

// ---- intents -------------------------------------------------------------------
//   check: @protocol SharedIntent ("Intent" sealed interface + -Protocol, A2)
typealias KIntent = SharedIntentProtocol
//   check: flattened nested data classes (A3); initializers take
//   songs:[Any]/startIndex:Int32 and ms:Int64 respectively (A5/A6).
typealias KIntentPlayNow = SharedIntentPlayNow  // Intent.PlayNow
typealias KIntentSeek = SharedIntentSeek        // Intent.Seek

// ---- events ---------------------------------------------------------------------
//   check: enum TransitionReason { AUTO, SEEK, EXPLICIT } → .auto/.seek/.explicit
typealias KTransitionReason = SharedTransitionReason
//   check: enum EngineErr { DECODE, SOURCE, SESSION_ACTIVATION } →
//   .decode/.source/.sessionActivation (lowerCamelCase, A5)
typealias KEngineErr = SharedEngineErr
//   check: @protocol SharedEngineEvent ("EngineEvent" sealed interface, A2 rule).
//   NOTE: was spelled `shared.EngineEvent` — unprefixed spelling is wrong.
typealias KEngineEvent = SharedEngineEventProtocol

/// Engine event constructors (flattened classes per A3; singletons per A4).
enum Events {
    static func prepared(_ itemId: String) -> SharedEngineEventPrepared {
        SharedEngineEventPrepared(itemId: itemId)
    }

    static func trackChanged(_ itemId: String, _ reason: KTransitionReason) -> SharedEngineEventTrackChanged {
        SharedEngineEventTrackChanged(itemId: itemId, reason: reason)
    }

    static func itemEnded(_ itemId: String) -> SharedEngineEventItemEnded {
        SharedEngineEventItemEnded(itemId: itemId)
    }

    static func queueExhausted() -> SharedEngineEventQueueExhausted {
        SharedEngineEventQueueExhausted.shared
    }

    static func error(_ itemId: String?, _ kind: KEngineErr) -> SharedEngineEventError {
        SharedEngineEventError(itemId: itemId, kind: kind)
    }

    static func routeLost() -> SharedEngineEventRouteLost {
        SharedEngineEventRouteLost.shared
    }

    static func interrupted(_ shouldResume: Bool) -> SharedEngineEventInterrupted {
        SharedEngineEventInterrupted(shouldResume: shouldResume)
    }
}

// ---- intents ---------------------------------------------------------------------

/// Intent constructors (flattened classes A3; data objects surface `.shared` A4;
/// Int params narrowed to Int32 per A5).
enum Intents {
    static func playNow(_ songs: [KSong], at index: Int) -> KIntent {
        KIntentPlayNow(songs: songs, startIndex: Int32(index))
    }

    static var toggle: KIntent { SharedIntentTogglePlayPause.shared }
    static var next: KIntent { SharedIntentNext.shared }
    static var previous: KIntent { SharedIntentPrevious.shared }
    static var toggleShuffle: KIntent { SharedIntentToggleShuffle.shared }
    static var cycleRepeat: KIntent { SharedIntentCycleRepeat.shared }
    static var clearUpNext: KIntent { SharedIntentClearUpNext.shared }

    static func playNext(_ song: KSong) -> KIntent { SharedIntentPlayNext(song: song) }
    static func addLast(_ song: KSong) -> KIntent { SharedIntentAddLast(song: song) }
    static func remove(at queuePos: Int) -> KIntent { SharedIntentRemoveAt(queuePos: Int32(queuePos)) }
    static func move(from: Int, to: Int) -> KIntent {
        SharedIntentMoveWithinQueue(from: Int32(from), to: Int32(to))
    }

    static func seek(ms: Int64) -> KIntent { KIntentSeek(ms: ms) }
}

// ---- model helpers -----------------------------------------------------------------

/// Graph bootstrap — keeps the `companion` spelling assumption inside this file.
enum DylanGraph {
    /// check: SharedIosGraph.companion.create(baseDir:) — Kotlin companion object
    /// surfaces as a static `.companion` property on the class.
    static func create(baseDir: String) -> KGraph {
        SharedIosGraph.companion.create(baseDir: baseDir)
    }
}

extension KSongKey {
    /// Stable dedupe/identity token across catalogs ("provider:songId").
    var token: String { "\(provider):\(songId)" }
}

// =============================================================================
// Async wrappers over suspend functions. Auto-generated async variants of the
// bridged completion handlers are used inside; every failure degrades to a safe
// default and logs via os_log instead of throwing into SwiftUI tasks.
// =============================================================================

private let bridgeLog = Logger(subsystem: "app.dylan.player", category: "bridge")

extension KGraph {
    // -- provider ------------------------------------------------------------

    func searchSongs(q: String, page: Int) async -> [KSong] {
        await searchSongsPaged(q: q, page: page).0
    }

    /// Full-results with the server total for the "N of M" footer (§11.4).
    func searchSongsPaged(
        q: String,
        page: Int,
    ) async -> ([KSong], Int64) {
        do {
            let paged = try await container.provider.search(query: q, page: Int32(page))
            return ((paged.items.compactMap { $0 as? KSong }), paged.total)
        } catch {
            bridgeLog.error("search failed: \(error.localizedDescription)")
            return ([], 0)
        }
    }

    func albumDetail(id: String) async -> KAlbum? {
        do {
            return try await container.provider.album(id: id)
        } catch {
            bridgeLog.error("album failed: \(error.localizedDescription)")
            return nil
        }
    }

    func homeSections() async -> [KHomeSection] {
        do {
            return try await container.provider.home().sections.compactMap { $0 as? KHomeSection }
        } catch {
            bridgeLog.error("home failed: \(error.localizedDescription)")
            return []
        }
    }

    func topSearches() async -> [KMiniEntity] {
        do {
            return try await container.provider.topSearches().compactMap { $0 as? KMiniEntity }
        } catch {
            bridgeLog.error("topSearches failed: \(error.localizedDescription)")
            return []
        }
    }

    // -- repos ------------------------------------------------------------------

    func favoritesAll() async -> [KSong] {
        do {
            return try await container.favorites.all().compactMap { $0 as? KSong }
        } catch {
            bridgeLog.error("favorites.all failed: \(error.localizedDescription)")
            return []
        }
    }

    func addFavorite(_ song: KSong) async {
        do {
            try await container.favorites.add(song: song)
        } catch {
            bridgeLog.error("favorite.add failed: \(error.localizedDescription)")
        }
    }

    func removeFavorite(_ song: KSong) async {
        do {
            try await container.favorites.remove(key: song.key)
        } catch {
            bridgeLog.error("favorite.remove failed: \(error.localizedDescription)")
        }
    }

    func isFavorite(_ song: KSong) async -> Bool {
        do {
            return try await container.favorites.isFavorite(key: song.key)
        } catch {
            bridgeLog.error("favorite.isFavorite failed: \(error.localizedDescription)")
            return false
        }
    }

    func historyRecent(_ limit: Int) async -> [KSong] {
        do {
            return try await container.history.recent(limit: Int32(limit)).compactMap { $0 as? KSong }
        } catch {
            bridgeLog.error("history.recent failed: \(error.localizedDescription)")
            return []
        }
    }

    func recentSearchChips() async -> [String] {
        do {
            return try await container.searchHistory.recent()
        } catch {
            bridgeLog.error("searchHistory.recent failed: \(error.localizedDescription)")
            return []
        }
    }

    func recordSearch(_ text: String) async {
        do {
            try await container.searchHistory.record(display: text)
        } catch {
            bridgeLog.error("searchHistory.record failed: \(error.localizedDescription)")
        }
    }

    func clearSearchHistory() async {
        do {
            try await container.searchHistory.clear()
        } catch {
            bridgeLog.error("searchHistory.clear failed: \(error.localizedDescription)")
        }
    }

    // -- settings / storage / library ---------------------------------------------

    func loadHighQualityPref() async -> Bool {
        do {
            return try await isHighQualityPref()
        } catch {
            bridgeLog.error("qualityPref failed: \(error.localizedDescription)")
            return true
        }
    }

    func saveHighQualityPref(_ high: Bool) async {
        do {
            try await setHighQualityPref(high)
        } catch {
            bridgeLog.error("setQualityPref failed: \(error.localizedDescription)")
        }
    }

    func storageStats() async -> KCacheStats? {
        do {
            return try await cachedStats()
        } catch {
            bridgeLog.error("cachedStats failed: \(error.localizedDescription)")
            return nil
        }
    }

    /// Real cached bitrate for the NP quality chip — 0 when uncached.
    func cachedBitrate(for song: KSong) async -> Int32 {
        do {
            return try await cachedBitrateOf(key: song.key)
        } catch {
            bridgeLog.error("cachedBitrate failed: \(error.localizedDescription)")
            return 0
        }
    }

    func clearCacheNow() async -> Int64 {
        do {
            return try await clearCacheExcludingProtected()
        } catch {
            bridgeLog.error("clearCache failed: \(error.localizedDescription)")
            return 0
        }
    }

    func downloadsLibrary() async -> [KCachedSongInfo] {
        do {
            return try await libraryDownloads().compactMap { $0 as? KCachedSongInfo }
        } catch {
            bridgeLog.error("libraryDownloads failed: \(error.localizedDescription)")
            return []
        }
    }

    func bulkDownload(songs: [KSong]) async {
        do {
            try await enqueueBulkDownloads(songs: songs)
        } catch {
            bridgeLog.error("bulkDownload failed: \(error.localizedDescription)")
        }
    }

    func downloadNow(_ song: KSong) async {
        do {
            try await enqueueDownloadNow(song: song)
        } catch {
            bridgeLog.error("downloadNow failed: \(error.localizedDescription)")
        }
    }

    func removeDownloaded(_ info: KCachedSongInfo) async {
        do {
            try await removeDownload(key: info.song.key)
        } catch {
            bridgeLog.error("removeDownload failed: \(error.localizedDescription)")
        }
    }

    // -- intents --------------------------------------------------------------------

    func submit(_ intent: KIntent) {
        container.orchestrator.submit(intent: intent)
    }
}
