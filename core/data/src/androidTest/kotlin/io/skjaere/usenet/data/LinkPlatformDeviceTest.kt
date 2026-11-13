package io.skjaere.usenet.data

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyInfo
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.link.Handshake
import io.skjaere.usenet.link.LinkKey
import io.skjaere.usenet.link.LinkTls
import io.skjaere.usenet.link.PeerPolicy
import io.skjaere.usenet.link.SelfTest
import io.skjaere.usenet.link.TlsSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Play on TV's spike on a real device (S0, items 1 and 2): what the link needs of the platform, measured before anything
 * is built on it. TLS with AndroidKeyStore keys and the fallback, then mDNS through the platform's NsdManager itself. A
 * library test package, so it may run on the Shield. What it measures goes to the log (tag LinkSpike), times as numbers
 * only, and to the instrumentation's status on a peer run, where no log is read.
 *
 * The cases with a peer on another device are opt-in (L14): they run only with the instrumentation argument `linkPeer`
 * (`workstation`, `tv` or `phone`), and are skipped without it.
 */
@RunWith(AndroidJUnit4::class)
class LinkPlatformDeviceTest {
    private val linkPeer: String? = InstrumentationRegistry.getArguments().getString("linkPeer")
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val open = mutableListOf<Closeable>()

    @After
    fun cleanUp() {
        open.forEach {
            try {
                it.close()
            } catch (_: IOException) {
                // Closed already.
            }
        }
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ALIASES + SEAL_ALIAS).forEach(keyStore::deleteEntry)
    }

    /** The first case on purpose: it opens no socket, so a missing permission can't pass for what the keystore did. */
    @Test
    fun theKeystoreMakesAKey() {
        val key = LinkIdentity.keystore(ALIAS_A).key
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue("no entry under the alias", keyStore.containsAlias(ALIAS_A))
        assertNull("the private key can be exported", key.privateKey.encoded)
        val certificate = key.certificate
        assertEquals("CN=NZStreamer", certificate.subjectX500Principal.name)
        assertTrue("not P-256 (named curve, uncompressed point)", certificate.publicKey.encoded.toHex().startsWith(P256_SPKI_PREFIX))
        // Signed by its own key, as the link's trust rule asks: a keystore that couldn't sign would have used a fake one.
        certificate.verify(certificate.publicKey)
        val info = KeyFactory.getInstance(key.privateKey.algorithm, "AndroidKeyStore").getKeySpec(key.privateKey, KeyInfo::class.java)
        val where = if (Build.VERSION.SDK_INT >= 31) "securityLevel=${info.securityLevel}" else "insideSecureHardware=${insideSecureHardware(info)}"
        report("keystore key: made, API ${Build.VERSION.SDK_INT}, $where, certificate ${certificate.sigAlgName}")
    }

    @Test
    fun theSelfTestPassesWithAKeystoreIdentity() {
        val result = LinkTls.selfTest(LinkIdentity.keystore(ALIAS_A).key)
        report("self-test (keystore): $result")
        assertTrue("self-test: $result", result is SelfTest.Passed)
    }

    @Test
    fun twoKeystoreIdentitiesHandshakeOnLoopback() {
        val (session, millis) = loopback(LinkIdentity.keystore(ALIAS_A).key, LinkIdentity.keystore(ALIAS_B).key)
        report("loopback (keystore, keystore): ${session.protocol} ${session.cipherSuite} $millis ms")
    }

    @Test
    fun twoFallbacksHandshakeOnLoopback() {
        val cipher = KeystoreCipher(SEAL_ALIAS)
        val tv = LinkIdentity.fallback(cipher)
        // The TV's as it would be read back: sealed and opened again.
        val reopened = LinkIdentity.open(tv.sealed!!, cipher)
        val (session, millis) = loopback(reopened.key, LinkIdentity.fallback(cipher).key)
        report("loopback (fallback, fallback): ${session.protocol} ${session.cipherSuite} $millis ms")
    }

    @Test
    fun theProtocolAndSuiteChosen() {
        val (session, _) = loopback(LinkIdentity.keystore(ALIAS_A).key, LinkIdentity.keystore(ALIAS_B).key)
        report("chosen: API ${Build.VERSION.SDK_INT}, ${session.protocol}, ${session.cipherSuite}")
        // TLS 1.3 from API 29; TLS 1.2 with ECDHE-ECDSA before it.
        assertEquals(if (Build.VERSION.SDK_INT >= 29) "TLSv1.3" else "TLSv1.2", session.protocol)
        assertTrue(session.cipherSuite, session.cipherSuite in LinkTls.CIPHER_SUITES)
    }

    @Test
    fun theMedianHandshakeOfTen() {
        val tv = LinkIdentity.keystore(ALIAS_A).key
        val phone = LinkIdentity.keystore(ALIAS_B).key
        val times = List(ROUNDS) { loopback(tv, phone).second }
        report("handshake ms (keystore, loopback): median ${times.sorted()[times.size / 2]}, all $times")
        // Within the 10 s TLS and the hello have (L24), or the link could never come up here.
        assertTrue("a handshake took 10 s or more: $times", times.all { it < 10_000 })
    }

    /**
     * Opt-in (`-e linkPeer workstation`): 60 s on port 28462, on every address, for core:link's LinkPeerClientTest on the
     * workstation. Each connection from a private IPv4 address gets TLS in pairing mode (neither end knows the other's
     * key) and a line answered with a line; it ends after [ROUNDS] of them. Passes when at least one came through.
     */
    @Test
    fun listensForAPeer() {
        assumeTrue("opt-in: -e linkPeer workstation", linkPeer == "workstation")
        val identity = LinkIdentity.create(alias = ALIAS_PEER, seal = KeystoreCipher(SEAL_ALIAS))
        report("listening: identity ${identity.kind}, port $PORT, addresses ${ipv4Addresses()}")
        val server = ServerSocket().also { open += it }
        server.reuseAddress = true
        server.bind(InetSocketAddress(PORT), 8)
        val until = System.nanoTime() + 60_000_000_000
        val times = mutableListOf<Long>()
        while (times.size < ROUNDS) {
            val left = (until - System.nanoTime()) / 1_000_000
            if (left <= 0) break
            server.soTimeout = left.toInt()
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                break
            }
            open += socket
            // As the TV's listener will (L23): private IPv4 peers only.
            if (!socket.inetAddress.let { it is Inet4Address && it.isSiteLocalAddress }) {
                report("refused a peer that isn't a private IPv4 address")
                socket.close()
                continue
            }
            socket.soTimeout = 10_000
            val started = System.nanoTime()
            val handshake = LinkTls.server(socket, identity.key, PeerPolicy.Pairing)
            val millis = (System.nanoTime() - started) / 1_000_000
            if (handshake !is Handshake.Accepted) {
                report("a peer's handshake: $handshake")
                continue
            }
            if (answer(handshake.socket)) {
                times += millis
                report("peer ${times.size}: ${handshake.session.protocol} ${handshake.session.cipherSuite} $millis ms")
            }
        }
        if (times.isNotEmpty()) report("handshake ms (${identity.kind}, workstation): median ${times.sorted()[times.size / 2]}, all $times")
        assertTrue("no workstation came through in 60 s", times.isNotEmpty())
    }

    /**
     * Register, then discover and resolve on this one device (L27), with a random `id` so no other run's record counts
     * (L14). It reports the networks the device is on, Wi-Fi or not, which decide whether two emulators can meet.
     */
    @Test
    fun findsItsOwnService() {
        val id = randomId()
        val listener = ServerSocket(0).also { open += it }
        NsdProbe(context).use { probe ->
            val registration = probe.register("NZStreamer test $id", listener.localPort, mapOf("pv" to "1", "id" to id))
            assertTrue("register: failed with ${registration.error}", registration.name != null)
            val search = probe.find(30_000) { it.txt["id"] == id }
            report("own service: multicast lock ${probe.multicastLock}, registered as \"${registration.name}\", ${search.describe()}; ${probe.networks()}")
            val record = search.record ?: throw AssertionError("own service: ${search.describe()}")
            assertEquals(listener.localPort, record.port)
            assertEquals("1", record.txt["pv"])
            assertTrue("resolve: no address", record.addresses.isNotEmpty())
        }
    }

    /**
     * Opt-in (`-e linkPeer workstation`): the record a TV advertises (L27), held 60 s for an mDNS query from the
     * workstation, which must see `_nzstreamer._tcp` with its TXT. Its random `id` goes to the log to look for.
     */
    @Test
    fun advertisesForAPeer() {
        assumeTrue("opt-in: -e linkPeer workstation", linkPeer == "workstation")
        val id = randomId()
        NsdProbe(context).use { probe ->
            val v4 = probe.lanIpv4()
            val txt = mapOf("pv" to "1", "id" to id, "pair" to "1") + listOfNotNull(v4?.let { "v4" to it.hostAddress!! })
            val registration = probe.register("NZStreamer spike $id", PORT, txt)
            assertTrue("register: failed with ${registration.error}", registration.name != null)
            report("advertising for 60 s: \"${registration.name}\" $TYPE port $PORT, TXT $txt, multicast lock ${probe.multicastLock}; ${probe.networks()}")
            Thread.sleep(60_000)
        }
    }

    /**
     * Opt-in (`-e linkPeer tv`, tv36 in emu-link-pair.sh): the TV's half of the two-emulator run. It listens on 28462 on
     * every address, advertises the pair TV's key id, and waits up to 120 s for the pair phone to come through a
     * handshake pinned both ways and a line each way.
     */
    @Test
    fun advertisesAndServes() {
        assumeTrue("opt-in: -e linkPeer tv", linkPeer == "tv")
        val tv = PairTestKeys.tv()
        val phone = PairTestKeys.phone().id
        val wifi = holdWifi().also { open += it }
        val server = ServerSocket().also { open += it }
        server.reuseAddress = true
        server.bind(InetSocketAddress(PORT), 8)
        NsdProbe(context).use { probe ->
            val v4 = probe.lanIpv4()
            report("pair TV: Wi-Fi asked for: ${if (wifi.joined) "joined" else "not joined in 30 s"}")
            val txt = mapOf("pv" to "1", "id" to tv.id.short) + listOfNotNull(v4?.let { "v4" to it.hostAddress!! })
            val registration = probe.register(PAIR_NAME, PORT, txt)
            assertTrue("register: failed with ${registration.error}", registration.name != null)
            report("pair TV: registered \"${registration.name}\", TXT $txt, addresses ${ipv4Addresses()}, multicast lock ${probe.multicastLock}; ${probe.networks()}")
            val until = System.nanoTime() + 120_000_000_000
            val others = mutableListOf<String>()
            var served: String? = null
            while (served == null) {
                val left = (until - System.nanoTime()) / 1_000_000
                if (left <= 0) break
                server.soTimeout = left.toInt()
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    break
                }
                open += socket
                socket.soTimeout = 10_000
                val started = System.nanoTime()
                val handshake = LinkTls.server(socket, tv, PeerPolicy.Pinned(phone))
                val millis = (System.nanoTime() - started) / 1_000_000
                if (handshake is Handshake.Accepted && answer(handshake.socket)) {
                    val from = socket.inetAddress
                    served = "from ${from.hostAddress} (private ${from is Inet4Address && from.isSiteLocalAddress}) to " +
                        "${socket.localAddress.hostAddress}: ${handshake.session.protocol} ${handshake.session.cipherSuite} $millis ms"
                } else {
                    others += "$handshake"
                }
            }
            report("pair TV: ${served ?: "nobody came through in 120 s"}; other connections: $others")
            assertTrue("no phone came through in 120 s; other connections: $others", served != null)
        }
    }

    /**
     * The emulators share only their virtual Wi-Fi (each one's eth0 is its own), and an Android TV on Ethernet joins
     * Wi-Fi only while some app asks for a Wi-Fi network: without a request, `cmd wifi connect-network` on tv36 logs
     * "CMD_START_CONNECT but no requests and not connected, bailing". So the TV half of the pair asks for one and holds
     * it for the run. Test only: a real TV is on the home network by Ethernet or Wi-Fi already.
     */
    private fun holdWifi(): HeldWifi {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val joined = CompletableFuture<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                joined.complete(network)
            }
        }
        connectivity.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
        val ok = runCatching { joined.get(30, TimeUnit.SECONDS) }.isSuccess
        return HeldWifi(ok) { connectivity.unregisterNetworkCallback(callback) }
    }

    private class HeldWifi(val joined: Boolean, private val release: () -> Unit) : Closeable {
        override fun close() = release()
    }

    /**
     * Opt-in (`-e linkPeer phone`, phone34 in emu-link-pair.sh): the phone's half. It finds the pair TV's record by its
     * `id`, resolves an IPv4 address, connects in 2 s and completes the handshake pinned both ways (L12). A failure
     * says which step failed: discovery, resolve, connect or the handshake.
     */
    @Test
    fun findsAndConnects() {
        assumeTrue("opt-in: -e linkPeer phone", linkPeer == "phone")
        val phone = PairTestKeys.phone()
        val tv = PairTestKeys.tv().id
        NsdProbe(context).use { probe ->
            val search = probe.find(90_000) { it.txt["id"] == tv.short }
            report("pair phone: multicast lock ${probe.multicastLock}, ${search.describe()}; ${probe.networks()}")
            val record = search.record ?: throw AssertionError("pair phone: ${search.describe()}")
            // RFC 1918 first, as the phone will connect to no other (L12); then any IPv4, then TXT's v4.
            val candidates = (record.addresses.filter { it is Inet4Address && it.isSiteLocalAddress } +
                record.addresses.filter { it is Inet4Address } + listOfNotNull(record.txt["v4"]?.let { InetAddress.getByName(it) })).distinct()
            if (candidates.isEmpty()) throw AssertionError("resolve: no IPv4 address in ${record.addresses} and no v4 in TXT")
            val tried = mutableListOf<String>()
            var socket: Socket? = null
            var connectMillis = 0L
            for (address in candidates) {
                val attempt = Socket().also { open += it }
                val started = System.nanoTime()
                try {
                    attempt.connect(InetSocketAddress(address, record.port), 2_000)
                    connectMillis = (System.nanoTime() - started) / 1_000_000
                    socket = attempt
                    break
                } catch (e: IOException) {
                    tried += "${address.hostAddress}: ${e.javaClass.simpleName}"
                }
            }
            if (socket == null) throw AssertionError("connect: $tried")
            socket.soTimeout = 10_000
            val started = System.nanoTime()
            val handshake = LinkTls.client(socket, phone, PeerPolicy.Pinned(tv))
            val millis = (System.nanoTime() - started) / 1_000_000
            if (handshake !is Handshake.Accepted) throw AssertionError("handshake: $handshake (connected to ${socket.inetAddress.hostAddress})")
            assertTrue("after the handshake: no answer", ask(handshake.socket))
            report(
                "pair phone: connected to ${socket.inetAddress.hostAddress}:${record.port} in $connectMillis ms (before it: $tried), " +
                    "${handshake.session.protocol} ${handshake.session.cipherSuite} $millis ms",
            )
        }
    }

    /**
     * A mutual handshake on 127.0.0.1 between [tv] (the server) and [phone], each pinning the other, then a byte each way.
     * The client's session, and its handshake's time in ms.
     */
    private fun loopback(tv: LinkKey, phone: LinkKey): Pair<TlsSession, Long> {
        val local = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket(0, 8, local).also { open += it }
        val served = CompletableFuture<Handshake>()
        thread(isDaemon = true) {
            try {
                val socket = server.accept().also { open += it }
                socket.soTimeout = 10_000
                val handshake = LinkTls.server(socket, tv, PeerPolicy.Pinned(phone.id))
                if (handshake is Handshake.Accepted) {
                    handshake.socket.outputStream.apply {
                        write(handshake.socket.inputStream.read() + 1)
                        flush()
                    }
                }
                served.complete(handshake)
            } catch (e: Throwable) {
                served.completeExceptionally(e)
            }
        }
        val socket = Socket().also { open += it }
        socket.connect(InetSocketAddress(local, server.localPort), 2_000)
        socket.soTimeout = 10_000
        val started = System.nanoTime()
        val client = LinkTls.client(socket, phone, PeerPolicy.Pinned(tv.id))
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("the client's handshake: $client", client is Handshake.Accepted)
        client as Handshake.Accepted
        client.socket.outputStream.apply {
            write(41)
            flush()
        }
        assertEquals(42, client.socket.inputStream.read())
        val accepted = served.get(15, TimeUnit.SECONDS)
        assertTrue("the server's handshake: $accepted", accepted is Handshake.Accepted)
        assertEquals(tv.id, client.session.peer)
        assertEquals(phone.id, (accepted as Handshake.Accepted).session.peer)
        client.socket.close()
        accepted.socket.close()
        return client.session to millis
    }

    /** The client's side of the line each way after a handshake: a ping, and whether the pong came. */
    private fun ask(socket: SSLSocket): Boolean = socket.use {
        it.outputStream.apply {
            write("$PING\n".toByteArray())
            flush()
        }
        it.inputStream.bufferedReader().readLine() == PONG
    }

    /** The server's side: whether a ping came, answered with the pong. */
    private fun answer(socket: SSLSocket): Boolean = socket.use {
        val pinged = it.inputStream.bufferedReader().readLine() == PING
        if (pinged) {
            it.outputStream.apply {
                write("$PONG\n".toByteArray())
                flush()
            }
        }
        pinged
    }

    /** 16 random hex digits, the shape of a key's short id (L27). */
    private fun randomId() = ByteArray(8).also(SecureRandom()::nextBytes).toHex()

    /** This device's IPv4 addresses but loopback, by interface: where a peer may reach it. */
    private fun ipv4Addresses(): String = NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { nif -> nif.inetAddresses.toList().filter { it is Inet4Address && !it.isLoopbackAddress }.map { "${nif.name}=${it.hostAddress}" } }
        .joinToString(", ").ifEmpty { "none" }

    @Suppress("DEPRECATION")
    private fun insideSecureHardware(info: KeyInfo) = info.isInsideSecureHardware

    /** A number for the gate: to the log, and to the instrumentation's status (as androidx.benchmark sends its own) on a peer run. */
    private fun report(line: String) {
        Log.i(TAG, line)
        if (linkPeer != null) InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(TAG, line) })
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "LinkSpike"
        const val ALIAS_A = "nzstreamer-link-test-a"
        const val ALIAS_B = "nzstreamer-link-test-b"
        const val ALIAS_PEER = "nzstreamer-link-test-peer"
        const val SEAL_ALIAS = "nzstreamer-link-test-seal"
        val ALIASES = listOf(ALIAS_A, ALIAS_B, ALIAS_PEER)
        const val PORT = 28462
        const val ROUNDS = 10
        const val PING = "nzstreamer link spike: ping"
        const val PONG = "nzstreamer link spike: pong"
        const val TYPE = "_nzstreamer._tcp"
        const val PAIR_NAME = "NZStreamer pair test TV"

        /** SubjectPublicKeyInfo's DER up to the point: id-ecPublicKey, prime256v1, then an uncompressed point (04). */
        const val P256_SPKI_PREFIX = "3059301306072a8648ce3d020106082a8648ce3d03010703420004"
    }
}
