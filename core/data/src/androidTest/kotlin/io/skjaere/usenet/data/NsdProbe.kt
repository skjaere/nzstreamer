package io.skjaere.usenet.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A service NsdManager resolved: its instance name, addresses, port and TXT (as UTF-8). */
data class NsdRecord(val name: String, val addresses: List<InetAddress>, val port: Int, val txt: Map<String, String>)

/** A registration: the name NSD gave it (renamed on a conflict, "Name (2)"), or NSD's error code. */
data class NsdRegistration(val name: String?, val error: Int?)

/**
 * What a search found, and how far it got, for a failure to say which step failed: discovery (it didn't start, or
 * found nothing), the resolve, or nothing that matched. Times are ms from the search's start.
 */
data class NsdSearch(
    val startError: Int?,
    val seen: Int,
    val resolveErrors: List<Int>,
    val record: NsdRecord?,
    val foundMillis: Long?,
    val resolvedMillis: Long?,
) {
    fun describe(): String = when {
        startError != null -> "discovery: didn't start (error $startError)"
        record != null ->
            "found in $foundMillis ms, resolved in $resolvedMillis ms ($seen seen), addresses ${record.addresses.map { it.hostAddress }}, port ${record.port}"
        seen == 0 -> "discovery: found nothing"
        resolveErrors.isNotEmpty() -> "resolve: $seen seen, resolve errors $resolveErrors, none matched"
        else -> "discovery: $seen seen, none matched"
    }
}

/**
 * The platform's NsdManager as the link will use it (L12, L15, L27), for the spike's device tests alone: register,
 * discover and resolve `_nzstreamer._tcp`, each waited on. It resolves one service at a time, since NsdService allows a
 * client one resolve at a time below API 34, and from API 34 through registerServiceInfoCallback, which gives every
 * address. It holds the multicast lock while open when L15 says one is needed, and [close] undoes all of it: as the
 * app's advertiser does (L47), it unregisters a record only once NSD has said how its registration went, since on
 * Android 14 an unregister while the record's probe was finishing wedged the emulators' mDNS advertiser.
 */
class NsdProbe(context: Context) : Closeable {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val registrations = mutableListOf<Registered>()
    private val discoveries = mutableListOf<NsdManager.DiscoveryListener>()
    private val wifiHardware = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)

    // Below T extensions 7 an app holds the lock to receive mDNS, the TV to hear the queries it answers among them; a
    // device with no Wi-Fi (an Ethernet-only TV) takes none (L15).
    private val lock: WifiManager.MulticastLock? =
        if (needsMulticastLock()) {
            context.applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("nzstreamer-link-test")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } else {
            null
        }

    /** Whether a multicast lock is held: "taken", "not needed", or "no Wi-Fi" where one was needed. */
    val multicastLock: String = when {
        lock != null -> "taken"
        needsMulticastLock() -> "no Wi-Fi"
        else -> "not needed"
    }

    /** This device's private IPv4 address on its default network (as a TV's `v4`, L27), or null. */
    fun lanIpv4(): Inet4Address? = connectivity.getLinkProperties(connectivity.activeNetwork)?.linkAddresses
        ?.map { it.address }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress } as Inet4Address?

    /**
     * This device's networks, for the gate (L14, L15): whether it has Wi-Fi at all, then each network it is on, by
     * transport, with its interface and IPv4 addresses. Wi-Fi hardware with no Wi-Fi network means Wi-Fi is off or not
     * joined, which is not the same as having none: the two-emulator run needs both on the emulators' shared Wi-Fi.
     */
    @Suppress("DEPRECATION") // allNetworks: what API 26-30 have
    fun networks(): String {
        val networks = connectivity.allNetworks.map { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            val transport = TRANSPORTS.entries.firstOrNull { capabilities?.hasTransport(it.key) == true }?.value ?: "other"
            val link = connectivity.getLinkProperties(network)
            val v4 = link?.linkAddresses.orEmpty().map { it.address }.filterIsInstance<Inet4Address>().map { it.hostAddress }
            "$transport ${link?.interfaceName ?: "?"} $v4"
        }
        return "Wi-Fi hardware ${if (wifiHardware) "yes" else "no"}, networks: ${networks.joinToString("; ").ifEmpty { "none" }}"
    }

    /** A registration made here: how it went once NSD says ([outcome]), and its end once it is taken down ([down]). */
    private class Registered(val listener: NsdManager.RegistrationListener, val outcome: CompletableFuture<NsdRegistration>, val down: CountDownLatch)

    fun register(name: String, port: Int, txt: Map<String, String>, timeoutMillis: Long = 15_000): NsdRegistration {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = TYPE
            this.port = port
            txt.forEach { (key, value) -> setAttribute(key, value) }
        }
        val result = CompletableFuture<NsdRegistration>()
        val down = CountDownLatch(1)
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) {
                result.complete(NsdRegistration(registered.serviceName, null))
            }

            override fun onRegistrationFailed(failed: NsdServiceInfo, errorCode: Int) {
                result.complete(NsdRegistration(null, errorCode))
            }

            override fun onServiceUnregistered(unregistered: NsdServiceInfo) = down.countDown()

            override fun onUnregistrationFailed(failed: NsdServiceInfo, errorCode: Int) = down.countDown()
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        registrations += Registered(listener, result, down)
        return try {
            result.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            NsdRegistration(null, TIMED_OUT)
        }
    }

    /**
     * Discovers `_nzstreamer._tcp` and resolves each service found, one at a time, until [wanted] takes one or
     * [timeoutMillis] passes. A service is resolved again when it is found again after a failed resolve.
     */
    fun find(timeoutMillis: Long, wanted: (NsdRecord) -> Boolean): NsdSearch {
        val started = System.nanoTime()
        val until = started + timeoutMillis * 1_000_000
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        fun left() = (until - System.nanoTime()) / 1_000_000
        val found = LinkedBlockingQueue<NsdServiceInfo>()
        val running = CountDownLatch(1)
        var startError: Int? = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = running.countDown()

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                startError = errorCode
                running.countDown()
            }

            override fun onServiceFound(service: NsdServiceInfo) = found.put(service)

            override fun onServiceLost(service: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        discoveries += listener
        if (!running.await(10, TimeUnit.SECONDS)) startError = TIMED_OUT
        startError?.let { return NsdSearch(it, 0, emptyList(), null, null, null) }
        val seen = mutableSetOf<String>()
        val errors = mutableListOf<Int>()
        while (left() > 0) {
            val service = found.poll(left(), TimeUnit.MILLISECONDS) ?: break
            seen += service.serviceName
            val foundAt = elapsed()
            val (record, error) = resolve(service, minOf(left(), RESOLVE_MILLIS))
            if (record == null) {
                errors += error ?: TIMED_OUT
                continue
            }
            if (wanted(record)) return NsdSearch(null, seen.size, errors, record, foundAt, elapsed())
        }
        return NsdSearch(null, seen.size, errors, null, null, null)
    }

    private fun resolve(service: NsdServiceInfo, timeoutMillis: Long): Pair<NsdRecord?, Int?> =
        if (Build.VERSION.SDK_INT >= 34) resolveByCallback(service, timeoutMillis) else resolveOnce(service, timeoutMillis)

    @Suppress("DEPRECATION") // resolveService and host: what API 26-33 have
    private fun resolveOnce(service: NsdServiceInfo, timeoutMillis: Long): Pair<NsdRecord?, Int?> {
        val result = CompletableFuture<Pair<NsdRecord?, Int?>>()
        nsd.resolveService(
            service,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    result.complete(null to errorCode)
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    result.complete(record(resolved, listOfNotNull(resolved.host)) to null)
                }
            },
        )
        return try {
            result.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null to TIMED_OUT
        }
    }

    @RequiresApi(34)
    private fun resolveByCallback(service: NsdServiceInfo, timeoutMillis: Long): Pair<NsdRecord?, Int?> {
        val result = CompletableFuture<Pair<NsdRecord?, Int?>>()
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                result.complete(null to errorCode)
            }

            override fun onServiceUpdated(updated: NsdServiceInfo) {
                if (updated.hostAddresses.isNotEmpty()) result.complete(record(updated, updated.hostAddresses) to null)
            }

            override fun onServiceLost() = Unit

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        nsd.registerServiceInfoCallback(service, DIRECT, callback)
        return try {
            result.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null to TIMED_OUT
        } finally {
            try {
                nsd.unregisterServiceInfoCallback(callback)
            } catch (_: IllegalArgumentException) {
                // Its registration failed: nothing to undo.
            }
        }
    }

    private fun record(info: NsdServiceInfo, addresses: List<InetAddress>) = NsdRecord(
        info.serviceName,
        addresses,
        info.port,
        info.attributes.mapValues { (_, value) -> value?.toString(Charsets.UTF_8).orEmpty() },
    )

    override fun close() {
        discoveries.forEach {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (_: IllegalArgumentException) {
                // It never started.
            }
        }
        registrations.forEach { registration ->
            // Its answer first (L47); one that never comes is given up after a while, as the app's advertiser does.
            val outcome = try {
                registration.outcome.get(ANSWER_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                null
            }
            // A failed registration holds nothing to take down.
            if (outcome?.error != null) return@forEach
            try {
                nsd.unregisterService(registration.listener)
                registration.down.await(ANSWER_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: IllegalArgumentException) {
                // It never registered.
            }
        }
        lock?.release()
    }

    companion object {
        const val TYPE = "_nzstreamer._tcp"

        /** Not one of NsdManager's codes: what a wait that ran out records. */
        const val TIMED_OUT = -1
        private const val RESOLVE_MILLIS = 10_000L

        /** How long an answer from NSD about a registration is waited for before it is given up (L47). */
        private const val ANSWER_MILLIS = 10_000L
        private val DIRECT = Executor { it.run() }

        // A VPN first: it rides on another transport, which it would otherwise be named by.
        private val TRANSPORTS = linkedMapOf(
            NetworkCapabilities.TRANSPORT_VPN to "VPN",
            NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",
            NetworkCapabilities.TRANSPORT_ETHERNET to "Ethernet",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
        )

        /**
         * L15: below T extensions 7 (Android 12 and older, and 13 without that update) an app must hold a multicast lock
         * to receive mDNS; from them, the system takes care of an app in front. Google says to check the extension, not
         * the SDK. Android 14 has extensions 7 and later in all its builds.
         */
        fun needsMulticastLock(): Boolean {
            if (Build.VERSION.SDK_INT < 33) return true
            if (Build.VERSION.SDK_INT > 33) return false
            return SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) < 7
        }
    }
}
