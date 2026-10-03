package dylan.fuzz.probe

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins *why* [dylan.download.Transfer] reads `rawContent` instead of `bodyAsChannel()`.
 *
 * The two look interchangeable and are not. `bodyAsChannel()` is literally `body<ByteReadChannel>()`,
 * which ktor serves through `defaultTransformers` as `writer { body.copyTo(channel, MAX_VALUE) }` —
 * so the caller gets a fresh `ByteChannel` rather than the engine's channel, with a whole extra
 * buffer in between. That buffer drops bytes when the connection resets mid-stream, which is what
 * made every affected download resume from offset 0 instead of from what it had already received.
 *
 * This asserts the *identity* of the channel rather than re-measuring the byte loss (the loss
 * measurement lives in [ReadAfterAwaitContentContractTest]). It is the cheap guard: reverting to
 * `bodyAsChannel()` fails here immediately and obviously, instead of silently reintroducing a
 * bandwidth-losing regression that only shows up as a rare CI flake.
 */
@OptIn(InternalAPI::class)
class DownloadBodyChannelIdentityTest {
    private val client =
        HttpClient(
            MockEngine {
                respond("x", headers = io.ktor.http.headersOf("Content-Type", "audio/mp4"))
            },
        )

    @Test
    fun rawContentIsTheEngineChannelAndBodyAsChannelIsACopy() =
        runBlocking {
            var rawClass: String? = null
            var copiedClass: String? = null

            client.prepareGet("http://mock/audio").execute { response ->
                rawClass = response.rawContent::class.java.name
                copiedClass = response.bodyAsChannel()::class.java.name
            }

            // `bodyAsChannel()` hands back a ByteChannel — ktor's own writer buffer.
            assertEquals(
                "io.ktor.utils.io.ByteChannel",
                copiedClass,
                "expected bodyAsChannel() to be the copied ByteChannel; if ktor changed this, the " +
                    "comment justifying rawContent is stale and this test is asserting the wrong thing",
            )
            // `rawContent` is the engine's own channel, which is the whole point.
            assertFalse(
                rawClass == "io.ktor.utils.io.ByteChannel",
                "rawContent resolved to the same copied ByteChannel as bodyAsChannel(), so reading it " +
                    "buys nothing and the opt-in should be removed",
            )
            assertTrue(
                rawClass != null && copiedClass != null && rawClass != copiedClass,
                "expected two distinct channel types, got raw=$rawClass copied=$copiedClass",
            )
        }

    @Test
    fun bothAccessorsHandBackAByteReadChannelSoTheCallSiteStillTypechecks() =
        runBlocking {
            // Guards the reason the swap is safe at all: `Transfer` declares the local as
            // `ByteReadChannel`, so whichever accessor is used must satisfy that type.
            client.prepareGet("http://mock/audio").execute { response ->
                val ch: ByteReadChannel = response.rawContent
                assertTrue(ch::class.java.name.isNotBlank())
                assertEquals(false, ch.isClosedForRead)
            }
        }
}
