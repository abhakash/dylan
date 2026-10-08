import CoreGraphics
import Foundation
import ImageIO
import UIKit

/// Hand-rolled ImageIO thumbnail decoder (§11.9, R3-E5): rows decode at 150 px,
/// Now Playing at 500 px — never the native pixel size. Memory cache is an NSCache;
/// disk caching rides the app-wide URLCache singleton configured once in DylanApp
/// (§12.2/R6-3: views never construct a cache).
final class Thumbnailer {
    static let rowPixel: CGFloat = 150
    static let nowPlayingPixel: CGFloat = 500

    private let memory = NSCache<NSString, UIImage>()
    private var inflight: [String: Task<UIImage?, Never>] = [:]
    private let lock = NSLock()

    init(memoryLimitBytes: Int = 48 * 1024 * 1024) {
        memory.totalCostLimit = memoryLimitBytes
    }

    func image(for urlString: String?, maxPixel: CGFloat) async -> UIImage? {
        guard let urlString, !urlString.isEmpty else { return nil }
        let key = "\(Int(maxPixel))|\(urlString)"
        if let hit = memory.object(forKey: key as NSString) { return hit }

        let existing: Task<UIImage?, Never>? = withLock { inflight[key] }
        if let existing {
            return await existing.value
        }

        let task = Task<UIImage?, Never> {
            let img = await Self.fetchAndDownsample(urlString, maxPixel: maxPixel)
            if let img {
                self.memory.setObject(img, forKey: key as NSString, cost: img.pixelCost)
            }
            self.withLock { _ = self.inflight.removeValue(forKey: key) }
            return img
        }
        withLock { inflight[key] = task }
        // The task's own removal is a RACE with the insertion above: `Task.init` may hand the
        // closure to another thread immediately, so a URLSession-cache hit can finish — and remove
        // its own entry — before `inflight[key] = task` runs. The removal then finds nothing to
        // remove and the completed task is installed permanently, so `inflight` grows by one
        // dead entry per raced key for the life of the process. Clearing it here too is
        // idempotent and identity-checked, so whoever runs last wins and a live task is never
        // dropped out from under a caller that is still awaiting it.
        let img = await task.value
        withLock { if inflight[key] == task { inflight.removeValue(forKey: key) } }
        return img
    }

    func flushMemory() {
        memory.removeAllObjects()
    }

    // ---- internals -----------------------------------------------------------------------

    private func withLock<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    /// URLSession.shared uses the shared URLCache singleton → HTTP responses land in the
    /// 150 MB artwork disk budget; decode happens at request time via ImageIO.
    private static func fetchAndDownsample(
        _ urlString: String,
        maxPixel: CGFloat
    ) async -> UIImage? {
        guard let url = URL(string: urlString) else { return nil }
        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            return downsample(data, maxPixel: maxPixel)
        } catch {
            return nil
        }
    }

    static func downsample(_ data: Data, maxPixel: CGFloat) -> UIImage? {
        let srcOpts = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let src = CGImageSourceCreateWithData(data as CFData, srcOpts) else { return nil }
        let thumbOpts = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(maxPixel),
        ] as CFDictionary
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, thumbOpts) else { return nil }
        return UIImage(cgImage: cg)
    }
}

private extension UIImage {
    var pixelCost: Int {
        cgImage.map { $0.width * $0.height * 4 } ?? 1
    }
}
