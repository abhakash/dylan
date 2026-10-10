package dylan

import dylan.di.APP_VERSION
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shipped version reaches the app through two independent paths: `versionName` in the APK, and
 * [APP_VERSION] for the boot log line. They had already drifted — root `VERSION` read 1.0.0 while
 * `APP_VERSION` was a hand-maintained 0.1.0 — so every session-stamped boot line under-reported the
 * version the user actually installed, and nothing failed. A "keep in sync" comment had been asked
 * to enforce a sync it cannot enforce.
 *
 * This asserts the generated constant against the file it is generated from, so a future edit that
 * bypasses the generator fails here instead of silently shipping a wrong version to the log.
 */
class AppVersionTest {
    @Test
    fun theBootLogVersionMatchesTheFileTheBuildGeneratesItFrom() {
        val versionFile = File("../VERSION")
        assertTrue(
            versionFile.exists(),
            "root VERSION must be readable from the shared module's working dir (${versionFile.absolutePath})",
        )
        val expected = versionFile.readText().trim()
        assertTrue(
            expected.isNotEmpty(),
            "VERSION is empty, so every build would report an empty app version",
        )
        assertEquals(
            expected,
            APP_VERSION,
            "APP_VERSION ($APP_VERSION) has drifted from root VERSION ($expected): the boot log " +
                "would under-report the installed version",
        )
    }
}
