package io.skjaere.usenet.app.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.net.Inet4Address
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** The TV's name in the two-device run (item 16): invented, and how the phone's half knows the TV it is to pair with. */
const val PAIR_TV_NAME = "Pair test TV"

/** The film the phone's half asks the TV's half to play (item 28): invented, the TV's playable one ([PlayableFilm]). */
const val PAIR_FILM = 900_071
const val PAIR_FILM_TITLE = "Harbor Bell"

/** The two-device run's peer role (L14): `tv` or `phone` from emu-link-pair.sh's `-e linkPeer`, else null (skipped). */
val linkPeer: String? get() = InstrumentationRegistry.getArguments().getString("linkPeer")

/** A line for the pair run's log: counts and times alone, never a name or a number shown for pairing (L13). */
fun pairReport(tag: String, line: String) {
    Log.i(tag, line)
    InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(tag, line) })
}

/** The Wi-Fi network a test holds, and its private IPv4 address there. */
class HeldWifi(val network: Network, val address: Inet4Address, private val release: () -> Unit) : Closeable {
    override fun close() = release()
}

/**
 * The emulators share only their virtual Wi-Fi, and tv36 (an Android TV on Ethernet) joins it only while some app asks
 * for a Wi-Fi network (the gate's finding; core:data's LinkPlatformDeviceTest.holdWifi). Asked here with the shell's
 * CHANGE_NETWORK_STATE, which the app doesn't hold, and held until closed: test only, a real TV is on its home network
 * already. Null when no Wi-Fi address comes within [seconds].
 */
fun holdWifi(context: Context, seconds: Long = 30): HeldWifi? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val joined = CompletableFuture<Network>()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            joined.complete(network)
        }
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
    val until = System.nanoTime() + seconds * 1_000_000_000
    val network = runCatching { joined.get(seconds, TimeUnit.SECONDS) }.getOrNull()
    while (network != null && System.nanoTime() < until) {
        val address = connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress } as Inet4Address?
        if (address != null) return HeldWifi(network, address, release)
        Thread.sleep(200)
    }
    release()
    return null
}
