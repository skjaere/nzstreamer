package io.skjaere.usenet.link

import java.io.IOException
import java.math.BigInteger
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.Buffer
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECPoint
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * One end of a link: this device's private key, and the certificate that carries its public half to the peer. The key
 * may be an AndroidKeyStore one, which never leaves the device: TLS signs with it there.
 */
class LinkKey(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val id: KeyId = KeyId.of(certificate.publicKey)

    override fun toString() = "LinkKey(${id.short})"
}

/** Whom a connection is for. */
sealed interface PeerPolicy {
    /** A paired peer: one of [keys] (the phone's one TV, or the TV's phones). */
    class Pinned(val keys: Set<KeyId>) : PeerPolicy {
        constructor(vararg keys: KeyId) : this(keys.toSet())
    }

    /** Pairing: any link key, which the ceremony then decides on (L21). */
    data object Pairing : PeerPolicy
}

/**
 * What TLS settled on: the version and suite, and each end's key, by id and as its SubjectPublicKeyInfo (pairing's
 * commit and number are worked out from the two, L21). Both come from the session, never from what a peer says.
 */
class TlsSession(
    val protocol: String,
    val cipherSuite: String,
    val own: KeyId,
    val ownKey: ByteArray,
    val peer: KeyId,
    val peerKey: ByteArray,
)

/** Why TLS didn't come up: all a caller learns (no exception or message leaves the module, L13). */
enum class TlsFailure {
    /** A read during the handshake waited out the socket's timeout. */
    TimedOut,

    /** The peer's certificate isn't a link key: exactly one EC P-256 certificate that verifies with its own key. */
    PeerKey,

    /** Anything else: the peer left or refused this end's key, or the two ends had no version or suite in common. */
    Other,
}

/** What came of a connection's handshake. */
sealed interface Handshake {
    /** TLS is up with a key the policy takes. */
    class Accepted(val socket: SSLSocket, val session: TlsSession) : Handshake

    /**
     * TLS is up, but the peer's key, [presented], isn't pinned. The socket is left open, since a caller may still read
     * what the peer says (a TV's name, L12), tell it that it isn't paired (L23), or pair with it from [session]'s two
     * keys (L21); it sends it nothing meant for a peer it trusts, and closes it.
     */
    class Refused(val socket: SSLSocket, val session: TlsSession) : Handshake {
        val presented: KeyId get() = session.peer
    }

    /** No TLS; the socket is closed. */
    data class Failed(val failure: TlsFailure) : Handshake
}

/** What the in-memory self-test of a key found (L20). */
sealed interface SelfTest {
    /** A key handshook with itself: the version and suite this device chose. */
    data class Passed(val protocol: String, val cipherSuite: String) : SelfTest

    /** It couldn't: this device's TLS can't use the key. */
    data object Failed : SelfTest
}

/**
 * The link's TLS (L2): mutual, between link keys alone, TLS 1.3 where both ends have it (API 29 on) and else TLS 1.2
 * with ECDHE-ECDSA and AES-128-GCM or ChaCha20-Poly1305. Nothing is checked but the key: no CA, no hostname and no
 * dates, since a TV box's clock may be wrong.
 *
 * The TrustManager takes only a link key, for pinned and pairing connections alike (Google Play flags trust managers
 * that take anything). Then, after the handshake and before any frame, the peer's key id is checked against the
 * policy's pins: the TrustManager isn't the only check. Each connection gets an SSLContext of its own, so no TLS
 * session is resumed across connections or policies (a resumed one would skip the TrustManager).
 */
object LinkTls {
    /** Wanted, in order; each end enables those its TLS has (naming TLSv1.3 where there is none throws, on API 26-28). */
    val PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")

    /** TLS 1.3's three (SunJSSE chooses 1.3 only with them enabled), then TLS 1.2's two; each end enables those it has. */
    val CIPHER_SUITES = listOf(
        "TLS_AES_128_GCM_SHA256",
        "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
    )

    /**
     * TLS on [accepted] as the server, a link key from the client required. Blocks for the handshake, each read within
     * the socket's timeout; a deadline for the whole of it is the caller's (L24), by closing the socket.
     */
    fun server(accepted: Socket, key: LinkKey, policy: PeerPolicy, protocols: List<String> = PROTOCOLS): Handshake =
        handshake(accepted, key, policy, protocols, client = false)

    /** TLS on [connected] as the client, as [server] is. */
    fun client(connected: Socket, key: LinkKey, policy: PeerPolicy, protocols: List<String> = PROTOCOLS): Handshake =
        handshake(connected, key, policy, protocols, client = true)

    private fun handshake(plain: Socket, key: LinkKey, policy: PeerPolicy, protocols: List<String>, client: Boolean): Handshake {
        val trust = PeerTrust()
        var ssl: SSLSocket? = null
        val failure = try {
            // A handshake's flights and the link's frames are small: none waits for Nagle's algorithm (40 ms a flight).
            plain.tcpNoDelay = true
            // An address as the host: no SNI goes out, and nothing checks it.
            val socket = context(key, trust).socketFactory.createSocket(plain, plain.inetAddress.hostAddress, plain.port, true) as SSLSocket
            ssl = socket
            socket.useClientMode = client
            if (!client) socket.needClientAuth = true
            val enabled = enable(protocols, socket.supportedProtocols, socket.supportedCipherSuites)
            if (enabled == null) {
                TlsFailure.Other
            } else {
                socket.enabledProtocols = enabled.first
                socket.enabledCipherSuites = enabled.second
                socket.startHandshake()
                val session = session(socket.session)
                when {
                    session == null -> TlsFailure.PeerKey
                    policy is PeerPolicy.Pinned && session.peer !in policy.keys -> return Handshake.Refused(socket, session)
                    else -> return Handshake.Accepted(socket, session)
                }
            }
        } catch (_: SocketTimeoutException) {
            TlsFailure.TimedOut
        } catch (_: IOException) {
            if (trust.refused) TlsFailure.PeerKey else TlsFailure.Other
        } catch (_: GeneralSecurityException) {
            TlsFailure.Other
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            // A provider's own (a keystore key it can't sign with, say): the connection fails, the caller goes on.
            TlsFailure.Other
        }
        // The plain socket first: nothing is owed to a peer TLS failed with, not even a close_notify.
        plain.closeQuietly()
        ssl?.closeQuietly()
        return Handshake.Failed(failure)
    }

    /**
     * Two engines with [key], one the client and one the server, handshake in memory, then send each other a few bytes
     * (L20): no socket, so no other app can take a port and fail it. It passes when TLS came up both ways with the key
     * on both ends, which a key this device's TLS can't sign with, or whose certificate isn't a link key, can't do.
     */
    fun selfTest(key: LinkKey, protocols: List<String> = PROTOCOLS): SelfTest {
        try {
            val client = End(engine(key, client = true, protocols) ?: return SelfTest.Failed, CLIENT_SAYS)
            val server = End(engine(key, client = false, protocols) ?: return SelfTest.Failed, SERVER_SAYS)
            client.engine.beginHandshake()
            server.engine.beginHandshake()
            var turns = 0
            while (!(client.heard(SERVER_SAYS.size) && server.heard(CLIENT_SAYS.size))) {
                // Both take their turn, whatever the first did.
                val moved = client.turn(server) or server.turn(client)
                if (!moved || ++turns > MAX_TURNS) return SelfTest.Failed
            }
            val ours = session(client.engine.session) ?: return SelfTest.Failed
            val theirs = session(server.engine.session) ?: return SelfTest.Failed
            val both = ours.peer == key.id && theirs.peer == key.id && client.said(SERVER_SAYS) && server.said(CLIENT_SAYS)
            return if (both) SelfTest.Passed(ours.protocol, ours.cipherSuite) else SelfTest.Failed
        } catch (_: IOException) {
            return SelfTest.Failed
        } catch (_: GeneralSecurityException) {
            return SelfTest.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            // The keystore's ProviderException among them: what the test is for.
            return SelfTest.Failed
        }
    }

    /**
     * Whether [chain] is a link key: exactly one certificate, for an EC P-256 key, that verifies with that key. No date
     * and no name is looked at.
     */
    internal fun wellFormed(chain: Array<out Certificate>?): Boolean {
        val certificate = chain?.singleOrNull() ?: return false
        if (!isP256(certificate.publicKey)) return false
        return try {
            certificate.verify(certificate.publicKey)
            true
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    private fun context(key: LinkKey, trust: PeerTrust): SSLContext =
        SSLContext.getInstance("TLS").apply { init(arrayOf(KeyManager(key)), arrayOf(trust), null) }

    private fun engine(key: LinkKey, client: Boolean, protocols: List<String>): SSLEngine? {
        val engine = context(key, PeerTrust()).createSSLEngine()
        engine.useClientMode = client
        if (!client) engine.needClientAuth = true
        val (enabledProtocols, suites) = enable(protocols, engine.supportedProtocols, engine.supportedCipherSuites) ?: return null
        engine.enabledProtocols = enabledProtocols
        engine.enabledCipherSuites = suites
        return engine
    }

    /** [protocols] and [CIPHER_SUITES] as far as this end has them; null when it has none of one. */
    private fun enable(protocols: List<String>, supportedProtocols: Array<String>, supportedSuites: Array<String>): Pair<Array<String>, Array<String>>? {
        val enabled = protocols.filter { it in supportedProtocols }
        val suites = CIPHER_SUITES.filter { it in supportedSuites }
        return if (enabled.isEmpty() || suites.isEmpty()) null else enabled.toTypedArray() to suites.toTypedArray()
    }

    /** The session's two keys, the peer's checked again as the TrustManager checked it; null when it isn't a link key. */
    private fun session(session: SSLSession): TlsSession? {
        val peer = try {
            session.peerCertificates
        } catch (_: SSLPeerUnverifiedException) {
            return null
        }
        if (!wellFormed(peer)) return null
        val own = session.localCertificates?.singleOrNull() ?: return null
        val peerKey = peer[0].publicKey.encoded
        val ownKey = own.publicKey.encoded
        return TlsSession(session.protocol, session.cipherSuite, KeyId.of(ownKey), ownKey, KeyId.of(peerKey), peerKey)
    }

    private const val MAX_TURNS = 100
    private val CLIENT_SAYS = "NZStreamer self-test: client".toByteArray(Charsets.US_ASCII)
    private val SERVER_SAYS = "NZStreamer self-test: server".toByteArray(Charsets.US_ASCII)
}

/**
 * The one trust rule for both ends, whatever the policy: the peer's chain is a link key. It remembers that it refused
 * one, so the failure can say why; the exception itself stays in TLS, which answers the peer with an alert.
 */
private class PeerTrust : X509ExtendedTrustManager() {
    @Volatile
    var refused = false
        private set

    private fun check(chain: Array<out X509Certificate>?) {
        if (!LinkTls.wellFormed(chain)) {
            refused = true
            throw CertificateException("not a link key")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)

    // None: no CA is asked for.
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Offers this end's one key wherever TLS asks for one of its kind: "EC", or the "EC_EC"-style names older TLS stacks
 * ask with. Never for another kind, which a TLS stack would refuse it for.
 */
private class KeyManager(private val key: LinkKey) : X509ExtendedKeyManager() {
    private val kind = key.certificate.publicKey.algorithm

    private fun takes(keyType: String?) = keyType != null && (keyType == kind || keyType.startsWith(kind + "_"))

    private fun alias(keyTypes: Array<out String>?) = ALIAS.takeIf { keyTypes?.any(::takes) == true }

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = if (takes(keyType)) arrayOf(ALIAS) else null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = if (takes(keyType)) arrayOf(ALIAS) else null

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias(keyType)

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS.takeIf { takes(keyType) }

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias(keyType)

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS.takeIf { takes(keyType) }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) arrayOf(key.certificate) else null

    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) key.privateKey else null

    private companion object {
        const val ALIAS = "link"
    }
}

/**
 * One engine of the self-test: what it has to say, what it heard, and the records it wrote that the other hasn't read
 * yet (in write mode between turns).
 */
private class End(val engine: SSLEngine, say: ByteArray) {
    private val outgoing = ByteBuffer.wrap(say)
    private var received: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize)
    var written: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
        private set
    private var steps = 0

    fun heard(size: Int) = received.position() >= size

    fun said(expected: ByteArray): Boolean {
        val bytes = ByteArray(received.position())
        received.duplicate().flipped().get(bytes)
        return bytes.contentEquals(expected)
    }

    /** Does what the engine asks until it waits on the peer: its handshake, what it has to say, what [peer] wrote. Whether anything moved. */
    fun turn(peer: End): Boolean {
        var moved = false
        while (steps++ < MAX_STEPS) {
            val step = when (engine.handshakeStatus) {
                HandshakeStatus.NEED_TASK -> {
                    var task = engine.delegatedTask
                    while (task != null) {
                        task.run()
                        task = engine.delegatedTask
                    }
                    true
                }
                HandshakeStatus.NEED_WRAP -> wrap()
                HandshakeStatus.NEED_UNWRAP -> unwrap(peer)
                // Handshaken (or a later handshake message to come): what it has to say, then what came.
                else -> (outgoing.hasRemaining() && wrap()) or unwrap(peer)
            }
            if (!step) return moved
            moved = true
        }
        return false
    }

    private fun wrap(): Boolean {
        val result = engine.wrap(outgoing, written)
        return when (result.status) {
            Status.BUFFER_OVERFLOW -> {
                written = written.grown(engine.session.packetBufferSize)
                true
            }
            Status.CLOSED -> false
            else -> result.bytesProduced() > 0 || result.bytesConsumed() > 0
        }
    }

    private fun unwrap(peer: End): Boolean {
        val input = peer.written.flipped()
        try {
            val result = engine.unwrap(input, received)
            return when (result.status) {
                Status.BUFFER_OVERFLOW -> {
                    received = received.grown(engine.session.applicationBufferSize)
                    true
                }
                Status.BUFFER_UNDERFLOW, Status.CLOSED -> false
                else -> result.bytesProduced() > 0 || result.bytesConsumed() > 0
            }
        } finally {
            input.compact()
        }
    }

    private companion object {
        const val MAX_STEPS = 1_000
    }
}

/** Into read mode. Through Buffer: ByteBuffer's own flip() is JDK 9's, and older Android has none. */
private fun ByteBuffer.flipped(): ByteBuffer {
    (this as Buffer).flip()
    return this
}

/** A copy with [more] bytes of room, in write mode, holding what this one holds. */
private fun ByteBuffer.grown(more: Int): ByteBuffer = ByteBuffer.allocate(capacity() + more).put(flipped())

/** Whether [key] is an EC key on P-256 (secp256r1), by the curve's field, coefficients, base point, order and cofactor. */
internal fun isP256(key: PublicKey): Boolean {
    val params = (key as? ECPublicKey)?.params ?: return false
    val field = params.curve.field as? ECFieldFp ?: return false
    return field.p == P256_P && params.curve.a == P256_A && params.curve.b == P256_B && params.generator == P256_G &&
        params.order == P256_N && params.cofactor == 1
}

// SEC 2 v2, 2.4.2.
private val P256_P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
private val P256_A = BigInteger("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc", 16)
private val P256_B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
private val P256_G = ECPoint(
    BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16),
    BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16),
)
private val P256_N = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
