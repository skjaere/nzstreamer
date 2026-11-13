package io.skjaere.usenet.link

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The link's TLS over real sockets on loopback, with software keys: each rule of L2, and the in-memory self-test of L20. */
class LinkTlsTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val open = mutableListOf<Closeable>()

    @AfterEach
    fun closeAll() {
        open.forEach {
            try {
                it.close()
            } catch (_: IOException) {
                // Closed already.
            }
        }
    }

    private fun ecPair(curve: String = "secp256r1"): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    private fun linkKey(pair: KeyPair = ecPair()) = LinkKey(pair.private, SelfSigned.certificate(pair))

    private fun parse(der: ByteArray) =
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

    /** A certificate for [subject] signed by [signer]: well-formed DER, but not a key's own. */
    private fun signedBy(subject: KeyPair, signer: KeyPair): X509Certificate =
        parse(SelfSigned.der(subject.public, signer.private, "SHA256withECDSA", SelfSigned.ECDSA_SHA256, BigInteger.TEN))

    /** A self-signed RSA certificate, as a peer that isn't NZStreamer might present. */
    private fun rsaCertificate(pair: KeyPair): X509Certificate {
        // sha256WithRSAEncryption, with the NULL parameters RFC 4055 asks for.
        val algorithm = Der.sequence(Der.oid("1.2.840.113549.1.1.11"), Der.NULL)
        return parse(SelfSigned.der(pair.public, pair.private, "SHA256withRSA", algorithm, BigInteger.TEN))
    }

    /** A server on loopback whose first connection [serve] gets on its own thread, with a 5 s read timeout. */
    private fun <T> listen(serve: (Socket) -> T): Pair<Int, CompletableFuture<T>> {
        val server = ServerSocket(0, 8, loopback).also { open += it }
        val result = CompletableFuture<T>()
        thread(isDaemon = true) {
            try {
                val socket = server.accept().also { open += it }
                socket.soTimeout = 5_000
                result.complete(serve(socket))
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return server.localPort to result
    }

    private fun connect(port: Int): Socket = Socket().also { socket ->
        open += socket
        socket.connect(InetSocketAddress(loopback, port), 2_000)
        socket.soTimeout = 5_000
    }

    /** After an accepted handshake, the server answers one byte with the next one up, so data crosses both ways. */
    private fun echoOnce(handshake: Handshake): Handshake {
        if (handshake is Handshake.Accepted) {
            val socket = handshake.socket
            val b = socket.inputStream.read()
            socket.outputStream.apply { write(b + 1); flush() }
        }
        return handshake
    }

    private fun <T> CompletableFuture<T>.done(): T = get(10, TimeUnit.SECONDS)

    @Test
    fun `two link keys pinned to each other handshake both ways, with TLS 1_3 and a suite of the list`() {
        val tv = linkKey()
        val phone = linkKey()
        val (port, served) = listen { echoOnce(LinkTls.server(it, tv, PeerPolicy.Pinned(phone.id))) }
        val client = LinkTls.client(connect(port), phone, PeerPolicy.Pinned(tv.id))
        assertIs<Handshake.Accepted>(client)
        client.socket.outputStream.apply { write(41); flush() }
        assertEquals(42, client.socket.inputStream.read())
        val server = assertIs<Handshake.Accepted>(served.done())
        assertEquals(tv.id, client.session.peer)
        assertEquals(phone.id, server.session.peer)
        assertEquals("TLSv1.3", client.session.protocol)
        assertEquals(client.session.protocol, server.session.protocol)
        assertTrue(client.session.cipherSuite in LinkTls.CIPHER_SUITES, client.session.cipherSuite)
        assertEquals(client.session.cipherSuite, server.session.cipherSuite)
    }

    @Test
    fun `a pinned key is accepted and another refused, which reports the key presented`() {
        val tv = linkKey()
        val phone = linkKey()
        val someoneElse = linkKey()

        // The phone pins another TV: TLS is up, and the phone is told whose key it met instead.
        val (port, served) = listen { LinkTls.server(it, tv, PeerPolicy.Pinned(phone.id)) }
        val refused = assertIs<Handshake.Refused>(LinkTls.client(connect(port), phone, PeerPolicy.Pinned(someoneElse.id)))
        assertEquals(tv.id, refused.presented)
        assertIs<Handshake.Accepted>(served.done())

        // The TV holds other phones: it is told the key that came.
        val (port2, served2) = listen { LinkTls.server(it, tv, PeerPolicy.Pinned(setOf(someoneElse.id))) }
        assertIs<Handshake.Accepted>(LinkTls.client(connect(port2), phone, PeerPolicy.Pinned(tv.id)))
        assertEquals(phone.id, assertIs<Handshake.Refused>(served2.done()).presented)
    }

    @Test
    fun `pairing takes any well-formed key and reports both ends' keys`() {
        val tv = linkKey()
        val phone = linkKey()
        val (port, served) = listen { LinkTls.server(it, tv, PeerPolicy.Pairing) }
        val client = assertIs<Handshake.Accepted>(LinkTls.client(connect(port), phone, PeerPolicy.Pairing)).session
        val server = assertIs<Handshake.Accepted>(served.done()).session
        assertEquals(phone.id, client.own)
        assertEquals(tv.id, client.peer)
        assertContentEquals(phone.certificate.publicKey.encoded, client.ownKey)
        assertContentEquals(tv.certificate.publicKey.encoded, client.peerKey)
        assertEquals(tv.id, server.own)
        assertEquals(phone.id, server.peer)
        assertContentEquals(tv.certificate.publicKey.encoded, server.ownKey)
        assertContentEquals(phone.certificate.publicKey.encoded, server.peerKey)
    }

    @Test
    fun `a link key is one EC P-256 certificate that verifies with its own key, and nothing else`() {
        val a = ecPair()
        val b = ecPair()
        val own = SelfSigned.certificate(a)
        assertTrue(LinkTls.wellFormed(arrayOf(own)))
        assertFalse(LinkTls.wellFormed(arrayOf(own, SelfSigned.certificate(b))), "two certificates")
        assertFalse(LinkTls.wellFormed(arrayOf(signedBy(a, b))), "signed by another key")
        assertFalse(LinkTls.wellFormed(arrayOf(rsaCertificate(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()))), "RSA")
        val p384 = ecPair("secp384r1")
        assertFalse(LinkTls.wellFormed(arrayOf(parse(SelfSigned.der(p384.public, p384.private, "SHA256withECDSA", SelfSigned.ECDSA_SHA256, BigInteger.ONE)))), "P-384")
        assertFalse(LinkTls.wellFormed(emptyArray()), "no certificate")
        assertFalse(LinkTls.wellFormed(null), "no chain")
    }

    @Test
    fun `pairing refuses a two-certificate chain, an RSA key and a certificate signed by another key`() {
        val tv = linkKey()
        val a = ecPair()
        val b = ecPair()

        // Phones that aren't link keys, at a TV in pairing mode.
        val twoCertificates = arrayOf(SelfSigned.certificate(a), SelfSigned.certificate(b))
        val (port, served) = listen { LinkTls.server(it, tv, PeerPolicy.Pairing) }
        foreignClient(port, twoCertificates, a.private)
        assertEquals(Handshake.Failed(TlsFailure.PeerKey), served.done())

        val (port2, served2) = listen { LinkTls.server(it, tv, PeerPolicy.Pairing) }
        foreignClient(port2, arrayOf(signedBy(a, b)), a.private)
        assertEquals(Handshake.Failed(TlsFailure.PeerKey), served2.done())

        // A TV that isn't one, at a phone pairing: an RSA key, which TLS 1.3 would otherwise take.
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val port3 = foreignServer(arrayOf(rsaCertificate(rsa)), rsa.private)
        assertEquals(Handshake.Failed(TlsFailure.PeerKey), LinkTls.client(connect(port3), linkKey(), PeerPolicy.Pairing))
    }

    @Test
    fun `a client with no certificate is closed`() {
        val tv = linkKey()
        val (port, served) = listen { LinkTls.server(it, tv, PeerPolicy.Pairing) }
        val client = foreignContext(null, null).socketFactory.createSocket(loopback, port) as SSLSocket
        open += client
        client.soTimeout = 5_000
        val read = try {
            client.startHandshake()
            client.inputStream.read()
        } catch (_: IOException) {
            -1
        }
        assertEquals(-1, read, "the client read data")
        assertIs<Handshake.Failed>(served.done())
    }

    @Test
    fun `TLS 1_2 forced on either end also works, with an ECDHE-ECDSA suite`() {
        val tv = linkKey()
        val phone = linkKey()
        for (forced in listOf("client", "server")) {
            val tls12 = listOf("TLSv1.2")
            val (port, served) = listen {
                LinkTls.server(it, tv, PeerPolicy.Pinned(phone.id), if (forced == "server") tls12 else LinkTls.PROTOCOLS)
            }
            val client = LinkTls.client(connect(port), phone, PeerPolicy.Pinned(tv.id), if (forced == "client") tls12 else LinkTls.PROTOCOLS)
            val session = assertIs<Handshake.Accepted>(client, forced).session
            assertIs<Handshake.Accepted>(served.done(), forced)
            assertEquals("TLSv1.2", session.protocol, forced)
            assertTrue(session.cipherSuite.startsWith("TLS_ECDHE_ECDSA_WITH_") && session.cipherSuite in LinkTls.CIPHER_SUITES, session.cipherSuite)
        }
    }

    @Test
    fun `the self-test handshakes a key with itself over two engines in memory`() {
        val key = linkKey()
        val passed = assertIs<SelfTest.Passed>(LinkTls.selfTest(key))
        assertEquals("TLSv1.3", passed.protocol)
        assertTrue(passed.cipherSuite in LinkTls.CIPHER_SUITES, passed.cipherSuite)
        assertEquals("TLSv1.2", assertIs<SelfTest.Passed>(LinkTls.selfTest(key, listOf("TLSv1.2"))).protocol)
    }

    @Test
    fun `the self-test fails a key that can't do TLS`() {
        val a = ecPair()
        val b = ecPair()
        // The certificate is a's, the private key b's: every signature fails to verify.
        assertEquals(SelfTest.Failed, LinkTls.selfTest(LinkKey(b.private, SelfSigned.certificate(a))))
        // A certificate its own key doesn't verify: no link key.
        assertEquals(SelfTest.Failed, LinkTls.selfTest(LinkKey(a.private, signedBy(a, b))))
    }

    /** An SSLContext that presents [chain] (none when null) and trusts anything: a peer that isn't NZStreamer. */
    private fun foreignContext(chain: Array<X509Certificate>?, key: PrivateKey?): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(if (chain != null && key != null) arrayOf(FixedKeyManager(chain, key)) else null, arrayOf(TrustAll), null)
        }

    /** A foreign client's handshake with the server at [port]; whatever comes of it, the server's side is what's checked. */
    private fun foreignClient(port: Int, chain: Array<X509Certificate>, key: PrivateKey) {
        val socket = foreignContext(chain, key).socketFactory.createSocket(loopback, port) as SSLSocket
        open += socket
        socket.soTimeout = 5_000
        try {
            socket.startHandshake()
            socket.inputStream.read()
        } catch (_: IOException) {
            // Refused, as it should be.
        }
    }

    /** A foreign server on loopback presenting [chain]; returns its port. */
    private fun foreignServer(chain: Array<X509Certificate>, key: PrivateKey): Int {
        val server = foreignContext(chain, key).serverSocketFactory.createServerSocket(0, 8, loopback) as SSLServerSocket
        open += server
        thread(isDaemon = true) {
            try {
                val socket = server.accept() as SSLSocket
                open += socket
                socket.soTimeout = 5_000
                socket.startHandshake()
            } catch (_: IOException) {
                // The link client refused it.
            }
        }
        return server.localPort
    }

    private class FixedKeyManager(private val chain: Array<X509Certificate>, private val key: PrivateKey) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("k")
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "k"
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("k")
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = "k"
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = "k"
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = "k"
        override fun getCertificateChain(alias: String?) = chain
        override fun getPrivateKey(alias: String?) = key
    }

    private object TrustAll : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    }
}
