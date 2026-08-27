package dylan

import dylan.diag.FileLogSink
import dylan.diag.LogBuffer
import dylan.diag.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The week-later bug-report trail: files exist, parse cleanly, rotate, and stay bounded. */
class FileLogSinkTest {
    private lateinit var dir: String
    private val fs = FileSystem.SYSTEM
    private val sinkScope = CoroutineScope(Dispatchers.Default)

    @BeforeTest
    fun setup() {
        dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + "/dylan-flog-${System.nanoTime()}"
        fs.createDirectories(dir.toPath())
    }

    @AfterTest
    fun teardown() {
        runCatching { fs.deleteRecursively(dir.toPath()) }
    }

    private fun entry(
        msg: String,
        level: LogLevel = LogLevel.INFO,
        tag: String = "dl",
        ts: Long = 1_756_000_000_000L,
    ) = LogBuffer.Entry(ts, level, tag, msg)

    private suspend fun await(
        timeoutMs: Long = 10_000,
        cond: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !cond()) delay(25)
    }

    private fun read(name: String): String? =
        (dir.toPath() / name).let {
            runCatching {
                if (fs.exists(it)) fs.source(it).buffer().use { s -> s.readUtf8() } else null
            }.getOrNull()
        }

    private fun exists(name: String) = fs.exists(dir.toPath() / name)

    @Test
    fun writesIsoTimestampedParseableLines() =
        kotlinx.coroutines.runBlocking {
            FileLogSink(fs, dir.toPath(), sinkScope).accept(entry("enqueue saavn:s1 bits=128"))
            await { read("dylan.log.0") != null }
            val text = awaitText("dylan.log.0") { it.contains("enqueue saavn:s1") }
            val line =
                text
                    .lineSequence()
                    .first { "enqueue" in it }
            assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z I/dl: ").containsMatchIn(line), line)
            assertEquals(
                "2025-08-24T01:46:40.000Z",
                FileLogSink.isoUtc(1_756_000_000_000L),
                "epoch → UTC wall clock must match known instant",
            )
        }

    @Test
    fun rotatesAndStaysBoundedUnderFlood() =
        kotlinx.coroutines.runBlocking {
            val sink = FileLogSink(fs, dir.toPath(), sinkScope, maxBytesPerFile = 400L, filesToKeep = 2)
            repeat(120) { i -> sink.accept(entry("victim line $i payload-padding-aaaaaaaaaaaaaaaaaaaa")) }
            // Rotation done when at least one archive exists and live file is under budget.
            await { exists("dylan.log.1") && (read("dylan.log.0")?.length ?: 0) < 400 }
            await { !exists("dylan.log.3") }
            for (i in 0..3) {
                val size = read("dylan.log.$i")?.encodeToByteArray()?.size
                if (size != null) assertTrue(size <= 500, "dylan.log.$i exceeded per-file cap: $size")
            }
            assertFalse(exists("dylan.log.3"), "retention must delete the oldest archive")
        }

    private suspend fun awaitText(
        name: String,
        pred: (String) -> Boolean,
    ): String {
        var text = ""
        val deadline = System.currentTimeMillis() + 10_000
        while (!pred(text) && System.currentTimeMillis() < deadline) {
            text = read(name) ?: ""
            if (!pred(text)) delay(25)
        }
        return text
    }
}
