package io.skjaere.usenet.app.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.handoff.SetupServer
import io.skjaere.usenet.link.Decoded
import io.skjaere.usenet.link.Handshake
import io.skjaere.usenet.link.LinkClient
import io.skjaere.usenet.link.LinkCodec
import io.skjaere.usenet.link.LinkFrames
import io.skjaere.usenet.link.LinkKey
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkTls
import io.skjaere.usenet.link.PairAttempt
import io.skjaere.usenet.link.PairCheck
import io.skjaere.usenet.link.PairHandler
import io.skjaere.usenet.link.PeerPolicy
import io.skjaere.usenet.link.SelfSigned
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/** A phone's link key, made in software for a test: what a pairing pins. */
fun softwareKey(): LinkKey {
    val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    return LinkKey(pair.private, SelfSigned.certificate(pair))
}

/** A phone in the test's process, as the phone's app makes its links: to private IPv4 addresses alone. */
fun phoneClient(name: String, key: LinkKey = softwareKey()) = LinkClient(key, name, "0.1.0", allowPeer = SetupServer::isPrivateIpv4)

/**
 * A phone in the test's process pairing with the TV at [tv] on a thread of its own, as Add a TV will: [answer] says
 * Yes or No as soon as its number shows, or nothing (null), the test answering through [check] then.
 */
class PhonePairing(tv: InetSocketAddress, name: String, val key: LinkKey = softwareKey(), private val answer: Boolean? = true) : PairHandler, Closeable {
    private val client = phoneClient(name, key)
    private val attempt = client.pairing(tv)
    val check = CompletableFuture<PairCheck>()
    val waited = CountDownLatch(1)
    val result = CompletableFuture<PairAttempt.Result>()

    init {
        thread(isDaemon = true, name = "test-phone-pairing") { result.complete(attempt.run(this)) }
    }

    override fun waiting() = waited.countDown()

    override fun compare(check: PairCheck) {
        this.check.complete(check)
        when (answer) {
            true -> check.yes()
            false -> check.no()
            null -> Unit
        }
    }

    fun outcome(): PairAttempt.Result = result.get(20, TimeUnit.SECONDS)

    override fun close() {
        attempt.cancel()
        client.close()
    }
}

/**
 * A phone driven by hand over TLS in pairing mode, its connection made from [from]: what it says is the test's, as a
 * phone that breaks the ceremony's order would say it. Frames are read and written as the link has them.
 */
class HandPhone(tv: InetSocketAddress, from: InetAddress, key: LinkKey = softwareKey()) : Closeable {
    private val plain = Socket()
    private val tls: SSLSocket
    private val input: DataInputStream

    init {
        plain.bind(InetSocketAddress(from, 0))
        plain.connect(tv, 2_000)
        plain.soTimeout = 10_000
        tls = (LinkTls.client(plain, key, PeerPolicy.Pairing) as? Handshake.Accepted)?.socket ?: throw IOException("no TLS with the TV")
        input = DataInputStream(tls.inputStream)
    }

    fun send(message: LinkMessage) {
        tls.outputStream.write(LinkFrames.encode(LinkCodec.encode(message)!!)!!)
        tls.outputStream.flush()
    }

    /** The TV's next message; null once it closed the connection. */
    fun read(): LinkMessage? = try {
        val length = input.readInt()
        val payload = ByteArray(length).also { input.readFully(it) }
        (LinkCodec.decode(payload) as? Decoded.Read)?.value
    } catch (_: IOException) {
        null
    }

    override fun close() {
        runCatching { tls.close() }
        runCatching { plain.close() }
    }
}

/**
 * A private IPv4 address of this device's other than [besides], for a phone in the test's process to come from another
 * address than the listener's. tv36 has one on Ethernet alone, and joins its Wi-Fi only while an app asks for a Wi-Fi
 * network: asked here (with the shell's CHANGE_NETWORK_STATE, which the app doesn't hold) and held until [Held.close].
 * Null when none comes within 30 s.
 */
fun anotherAddress(context: Context, besides: Inet4Address): Held? {
    fun other() = ownAddresses().filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress && it != besides }
    other()?.let { return Held(it) {} }
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = Unit
    }
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    automation.adoptShellPermissionIdentity("android.permission.CHANGE_NETWORK_STATE")
    try {
        connectivity.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
    } catch (_: SecurityException) {
        return null
    } finally {
        automation.dropShellPermissionIdentity()
    }
    val release = { runCatching { connectivity.unregisterNetworkCallback(callback) }; Unit }
    val until = System.nanoTime() + 30_000_000_000
    while (System.nanoTime() < until) {
        other()?.let { return Held(it, release) }
        Thread.sleep(200)
    }
    release()
    return null
}

/** An address held for a test, and what lets it go. */
class Held(val address: Inet4Address, private val release: () -> Unit) : Closeable {
    override fun close() = release()
}
