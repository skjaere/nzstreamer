package io.skjaere.usenet.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.network.selector.SelectorManager
import io.skjaere.nntp.NntpClient
import io.skjaere.nntp.NntpClientPool
import io.skjaere.nntp.NntpConnectionException
import io.skjaere.nntp.StatResult
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.core.UsenetEngine
import io.skjaere.usenet.core.UsenetError
import io.skjaere.yenc.RapidYenc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * ktor-nntp-client's TLS is the platform's (library issue K43): Conscrypt here, as on the Shield and phones, where the
 * library's own tests run the JDK's and a desktop build of Conscrypt. On it a provider's certificate must name the
 * provider's host, and one that doesn't is refused in words and not tried again; the host is sent as the server name
 * (SNI), and no name for an address or with the check off; TLS 1.3 is used when the server offers it. Articles come
 * whole over it and the connection goes on; one cut short, with TLS's close_notify or without, fails as a closed
 * connection; one cancelled midway is read off and its connection kept. The server is the test's own, in this
 * process, with a certificate that the run trusts by its fingerprint ([StreamBenchTrust]): for localhost,
 * assets/tls-localhost.p12, or for 127.0.0.1, assets/tls-loopback.p12 (password changeit). They were made with
 * `keytool -J-Dkeystore.pkcs12.legacy -genkeypair -alias localhost -keyalg EC -groupname secp256r1
 * -dname CN=localhost -ext SAN=dns:localhost -validity 36500 -keystore tls-localhost.p12 -storetype PKCS12
 * -storepass changeit -keypass changeit`, and the same with alias loopback, CN=127.0.0.1 and SAN=ip:127.0.0.1: the
 * legacy PKCS12 format, which Android's keystore reads.
 */
@RunWith(AndroidJUnit4::class)
class PlatformTlsDeviceTest {
    private lateinit var server: LocalTlsServer
    private val callers = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Before
    fun setUp() {
        server = startServer(KEYSTORE)
    }

    @After
    fun tearDown() {
        StreamBenchTrust.uninstall()
        server.close()
        callers.cancel()
    }

    /** A server with the key and certificate in the asset [keyStore], which the run trusts; [serve] answers each connection. */
    private fun startServer(keyStore: String, serve: (LocalTlsServer.Session) -> Unit = LocalTlsServer.answering()): LocalTlsServer {
        if (::server.isInitialized) {
            StreamBenchTrust.uninstall()
            server.close()
        }
        return InstrumentationRegistry.getInstrumentation().context.assets.open(keyStore).use { LocalTlsServer(it, serve) }
            .also { StreamBenchTrust.install(it.certificateSha256) }
    }

    private fun provider(host: String) = ProviderConfig("tls", "tls", host, server.port, "", "", useTls = true, maxConnections = 1)

    @Test
    fun aCertificateForTheHostIsAcceptedOverTls13WithTheHostAsTheServerName() = runBlocking<Unit> {
        assertNull(UsenetEngine.testProvider(provider("localhost"), timeoutMs = 10_000))
        assertEquals("the protocol the handshake chose", listOf("TLSv1.3"), server.protocols)
        assertEquals("server names asked for", listOf(listOf("localhost")), server.requestedServerNames)
    }

    @Test
    fun aCertificateNotForTheHostIsRefusedInWords() = runBlocking<Unit> {
        assertEquals(
            UsenetError.ConnectionFailed("The provider's certificate isn't for 127.0.0.1 (it names localhost)"),
            UsenetEngine.testProvider(provider("127.0.0.1"), timeoutMs = 10_000),
        )
    }

    /** The pool takes a refused certificate for a failure no retry changes only when it finds why in the cause chain. */
    @Test
    fun aPoolDoesNotTryARefusedCertificateAgain() = runBlocking<Unit> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        SelectorManager(Dispatchers.IO).use { selector ->
            val pool = NntpClientPool(
                host = "127.0.0.1", port = server.port, selectorManager = selector, useTls = true,
                maxConnections = 1, scope = scope, keepaliveIntervalMs = 0, idleGracePeriodMs = 0,
            )
            try {
                val failure = runCatching { withTimeout(5_000) { pool.stat("<a@b>") } }.exceptionOrNull()
                assertTrue("the failure doesn't say why: $failure", generateSequence(failure) { it.cause }.any { it is CertificateException })
                delay(1_500) // a retry would come after 1 s
                assertEquals("connections: a refused certificate was tried again", 1, server.accepted)
            } finally {
                pool.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun anAddressGetsNoServerNameNorDoesAHostWithTheCheckOff() = runBlocking<Unit> {
        server = startServer(LOOPBACK_KEYSTORE)
        assertNull(UsenetEngine.testProvider(provider("127.0.0.1"), timeoutMs = 10_000))
        SelectorManager(Dispatchers.IO).use { selector ->
            NntpClient.connect("localhost", server.port, selector, useTls = true, verifyHostname = false).close()
        }
        assertEquals("server names asked for", listOf(emptyList<String>(), emptyList()), server.requestedServerNames)
    }

    /** A 1.3 MB article, many records, read whole as nzb-streamer reads it, and the connection in step after it. */
    @Test
    fun anArticleOverTls13ComesWholeAndTheConnectionGoesOn() = runBlocking<Unit> {
        val first = Random(41).nextBytes(1_300_000)
        val second = Random(42).nextBytes(40_000)
        server = startServer(KEYSTORE, LocalTlsServer.answering(article(first), article(second)))
        client().use { client ->
            assertArrayEquals(first, client.bodyYencBytes("<first@test>").data)
            assertArrayEquals(second, client.bodyYencBytes("<second@test>").data)
        }
        assertEquals("the protocol the handshake chose", listOf("TLSv1.3"), server.protocols)
    }

    @Test
    fun anArticleCutShortWithCloseNotifyFailsAsAClosedConnection() = runBlocking<Unit> {
        val whole = article(Random(43).nextBytes(300_000))
        server = startServer(KEYSTORE) { session ->
            session.reader.readLine() // BODY
            session.out.write(whole, 0, whole.size / 2)
            session.out.flush()
            session.closeWithNotify()
        }
        client().use { client ->
            val failure = runCatching { client.bodyYencBytes("<cut@test>") }.exceptionOrNull()
            assertTrue("expected a connection failure, got $failure", failure is NntpConnectionException)
        }
    }

    /** Without close_notify the end may be someone cutting the stream short: the connection's end all the same. */
    @Test
    fun anArticleCutShortWithoutCloseNotifyFailsAsAClosedConnection() = runBlocking<Unit> {
        val whole = article(Random(44).nextBytes(300_000))
        server = startServer(KEYSTORE) { session ->
            session.reader.readLine() // BODY
            session.out.write(whole, 0, whole.size / 2)
            session.out.flush()
            Thread.sleep(100) // the bytes out before the FIN
            session.closeWithoutNotify()
        }
        client().use { client ->
            val failure = runCatching { client.bodyYencBytes("<cut@test>") }.exceptionOrNull()
            assertTrue("expected a connection failure, got $failure", failure is NntpConnectionException)
        }
    }

    /** As a seek leaves an article: the rest is read off and the connection serves the next one. */
    @Test
    fun anArticleCancelledMidwayIsReadOffAndItsConnectionKept() = runBlocking<Unit> {
        val first = article(Random(45).nextBytes(400_000))
        val second = Random(46).nextBytes(30_000)
        val startSent = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        server = startServer(KEYSTORE) { session ->
            session.reader.readLine() // the STAT that opens the connection
            session.out.write("223 0 <a@b> exists\r\n".toByteArray())
            session.out.flush()
            session.reader.readLine() // the first BODY
            session.out.write(first, 0, 8_192)
            session.out.flush()
            startSent.countDown()
            cancelled.await(10, TimeUnit.SECONDS)
            session.out.write(first, 8_192, first.size - 8_192)
            session.out.flush()
            LocalTlsServer.answering(article(second))(session)
        }
        SelectorManager(Dispatchers.IO).use { selector ->
            val pool = NntpClientPool(
                host = "localhost", port = server.port, selectorManager = selector, useTls = true,
                maxConnections = 1, scope = callers, keepaliveIntervalMs = 0, idleGracePeriodMs = 0,
            )
            try {
                assertTrue(pool.stat("<a@b>") is StatResult.Found)
                val pending = callers.async { pool.withClient { it.bodyYencBytes("<first@test>") } }
                assertTrue("the server never sent the start of the article", startSent.await(5, TimeUnit.SECONDS))
                delay(200) // the client has the start, and waits for the rest
                pending.cancel()
                cancelled.countDown()
                withTimeout(5_000) { pending.join() }
                assertTrue("the article wasn't cancelled: ${pending.getCompletionExceptionOrNull()}", pending.isCancelled)
                assertArrayEquals(second, withTimeout(10_000) { pool.withClient { it.bodyYencBytes("<second@test>") } }.data)
                assertEquals("connections: the one read off should have served the next article", 1, server.accepted)
            } finally {
                cancelled.countDown()
                pool.close()
            }
        }
    }

    private suspend fun client(): NntpClient = SelectorManager(Dispatchers.IO).let { selector ->
        NntpClient.connect("localhost", server.port, selector, useTls = true)
    }

    /** A yEnc article as a server sends it after BODY: its 222, a part of a larger file, its =yend, the terminator. */
    private fun article(data: ByteArray): ByteArray {
        val encoded = String(RapidYenc.encode(data), Charsets.ISO_8859_1)
        return buildString {
            append("222 0 <a@test>\r\n")
            append("=ybegin part=1 total=1 line=128 size=${data.size} name=data.bin\r\n")
            append("=ypart begin=1 end=${data.size}\r\n")
            append(encoded)
            if (!encoded.endsWith("\r\n")) append("\r\n")
            append("=yend size=${data.size} part=1 pcrc32=${RapidYenc.crc32(data).toString(16).padStart(8, '0')}\r\n")
            append(".\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
    }

    private companion object {
        const val KEYSTORE = "tls-localhost.p12"
        const val LOOPBACK_KEYSTORE = "tls-loopback.p12"
    }
}

/**
 * An NNTP server over TLS on this device, with the key and certificate in [keyStore] (PKCS12, password changeit): greets
 * each connection that completes the handshake and hands it to [serve] (by default answering STAT and QUIT), noting
 * the protocol each handshake chose and the server names it asked for. TLS is layered over a plain socket, so a
 * connection can also end without close_notify.
 */
private class LocalTlsServer(keyStore: InputStream, private val serve: (Session) -> Unit) : Closeable {
    /** One connection: what the client sent ([reader]) and the way back ([out]), over TLS. */
    class Session(private val tls: SSLSocket, private val tcp: Socket) {
        val reader: BufferedReader = tls.inputStream.bufferedReader(Charsets.ISO_8859_1)
        val out: OutputStream = tls.outputStream

        /** Ends the connection as TLS means it to end: close_notify, then the TCP close. */
        fun closeWithNotify() = tls.close()

        /** Ends the TCP connection with no close_notify, as a server that crashed or a cut network does. */
        fun closeWithoutNotify() = tcp.close()
    }

    private val serverSocket = ServerSocket(0)
    private val factory: SSLSocketFactory

    val protocols = CopyOnWriteArrayList<String>()
    val requestedServerNames = CopyOnWriteArrayList<List<String>>()
    private val acceptedCount = AtomicInteger()
    val accepted get() = acceptedCount.get()
    val port get() = serverSocket.localPort

    /** The certificate's SHA-256, as [StreamBenchTrust] pins it. */
    val certificateSha256: String

    init {
        val keys = KeyStore.getInstance("PKCS12").apply { load(keyStore, PASSWORD) }
        val certificate = keys.getCertificate(keys.aliases().nextElement())
        certificateSha256 = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, PASSWORD) }.keyManagers
        factory = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }.socketFactory
        Thread {
            while (true) {
                val tcp = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    break
                }
                acceptedCount.incrementAndGet()
                Thread { handle(tcp) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handle(tcp: Socket) {
        try {
            // No host name of its own: Conscrypt reports one given here as the name the client asked for.
            val tls = (factory.createSocket(tcp, null, tcp.port, false) as SSLSocket).apply { useClientMode = false }
            tls.startHandshake()
            protocols += tls.session.protocol
            requestedServerNames += (tls.session as ExtendedSSLSession).requestedServerNames.map { (it as? SNIHostName)?.asciiName ?: it.toString() }
            val session = Session(tls, tcp)
            session.out.write("200 welcome\r\n".toByteArray())
            session.out.flush()
            serve(session)
            tls.close()
        } catch (_: IOException) {
            // The client refused the certificate, or went away.
        } finally {
            runCatching { tcp.close() }
        }
    }

    override fun close() {
        serverSocket.close()
    }

    companion object {
        private val PASSWORD = "changeit".toCharArray()

        /** Answers BODY with each of [articles] in turn, STAT with 223 and QUIT with 205, until the client goes away. */
        fun answering(vararg articles: ByteArray): (Session) -> Unit = { session ->
            val bodies = articles.iterator()
            while (true) {
                val command = session.reader.readLine() ?: break
                when {
                    command.startsWith("BODY") -> session.out.write(bodies.next())
                    command == "QUIT" -> session.out.write("205 bye\r\n".toByteArray())
                    else -> session.out.write("223 0 <a@b> exists\r\n".toByteArray())
                }
                session.out.flush()
                if (command == "QUIT") break
            }
        }
    }
}
