package dylan.playback

import dylan.config.AppConfig
import dylan.db.Dylan
import dylan.repo.SettingsStore
import dylan.util.AppDispatchers

/**
 * Wave 3 extract: snapshot persist/restore + history bump (was Orchestrator:608-720).
 * Owns `saveSnapshot`/`restore`/`sanitizeSnapshot` so Orchestrator stays inbox router only.
 * Currently a facade — Orchestrator delegates after queue/window split lands.
 */
internal class SnapshotStore(
    private val db: Dylan,
    private val disp: AppDispatchers,
    private val cfg: AppConfig,
    private val settings: SettingsStore,
)
