package dylan.download

import dylan.cache.Paths
import okio.FileSystem

/**
 * Wave 3 extract: verify + commit (was DownloadEngine:485-553).
 * Magic sniff (ftyp/ID3), size check, fsRename + DB transaction.
 * Currently a facade — DownloadEngine delegates after queue split lands.
 */
internal class Verifier(
    private val fs: FileSystem,
    private val paths: Paths,
)
