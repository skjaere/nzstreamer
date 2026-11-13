package io.skjaere.usenet.core

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Over TLS a provider must present a certificate for the host it is set up with: ktor-nntp-client
 * checks the name now (library issue K1), and the app doesn't offer to turn that off. One set up by
 * an address its certificate doesn't list fails the provider screen's Test and a play alike, in
 * words that say why.
 */
class ProviderTlsTest {
    private val clip = TestClip()
    private val server = clip.server(tls = certificate.serverContext)
    private val engine = UsenetEngine()

    /** The server by its address, which its certificate (for "localhost") doesn't list. */
    private val byAddress = server.provider().copy(useTls = true)
    private val notFor = "The provider's certificate isn't for ${byAddress.host} (it names localhost)"

    @BeforeTest
    fun trust() = certificate.trust()

    @AfterTest
    fun tearDown() {
        engine.close()
        server.close()
        certificate.distrust()
    }

    @Test
    fun `the provider test refuses a certificate that isn't for the host, and says so`() = runBlocking<Unit> {
        assertEquals(UsenetError.ConnectionFailed(notFor), UsenetEngine.testProvider(byAddress, timeoutMs = 10_000))
        // By the name it is for, the same server passes: only the name was wrong.
        assertNull(UsenetEngine.testProvider(byAddress.copy(host = "localhost"), timeoutMs = 10_000))
    }

    @Test
    fun `a play or a stream from a provider whose certificate isn't for its host stops at once, in those words`() = runBlocking<Unit> {
        engine.configure(EngineSettings(listOf(byAddress)))
        val exhausted = assertIs<PipelineEvent.Exhausted>(
            assertNotNull(
                withTimeoutOrNull(15_000) {
                    usenetPipeline({ clip.nzbBytes }, EngineAvailabilityBackend(engine), NzbPreparer(EnginePrepareBackend(engine)))
                        .play(listOf(PlayCandidate("clip1", "Clip 1"), PlayCandidate("clip2", "Clip 2"))).last()
                },
                "the play was still going after 15 s",
            ),
        )
        val failure = assertIs<AttemptFailure.Provider>(exhausted.failedAttempts.single().second)
        assertEquals(UsenetError.ConnectionFailed(notFor), failure.error)
        assertEquals(1, exhausted.notTried)

        val (_, cause) = withTimeout(15_000) { NzbStreamOpener(engine).open(clip.nzb, 0, 0L until clip.data.size.toLong()).readToEnd() }
        assertEquals(UsenetError.ConnectionFailed(notFor), cause?.toUsenetError(), "the stream ended with $cause")
    }

    companion object {
        /** For "localhost" only, as a provider's certificate is for its host name, not its address. */
        private lateinit var certificate: TestCertificate

        @JvmStatic
        @BeforeAll
        fun makeCertificate() {
            certificate = TestCertificate("dns:localhost")
        }

        @JvmStatic
        @AfterAll
        fun deleteCertificate() = certificate.close()
    }
}
