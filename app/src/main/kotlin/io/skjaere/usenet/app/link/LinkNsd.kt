package io.skjaere.usenet.app.link

import android.content.Context
import android.content.pm.PackageManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import org.slf4j.LoggerFactory
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor

/** Puts the TV's mDNS record on the home network while open. */
fun interface Advertiser {
    /**
     * [record] for the listener on [port], on [network] alone where the system can (API 33+), until the handle closes.
     * One record at a time: a newer one takes the place of the one before.
     */
    fun advertise(record: ServiceRecord, port: Int, network: Network?): AutoCloseable
}

/** A record as it is advertised: what it says, the port it names, and the network it goes out on (null: the system's choice). */
internal data class Advert(val record: ServiceRecord, val port: Int, val network: Network?)

/** How a registration went, as NsdManager tells it, on its own thread. */
internal interface NsdHeard {
    fun registered()

    fun registrationFailed(code: Int)

    fun unregistered()

    fun unregistrationFailed(code: Int)
}

/** The platform calls [LinkNsd] makes: NsdManager's and the multicast lock's in the app, a test's stand-ins on the JVM. */
internal interface NsdCalls {
    /** Starts registering [advert]; how it went comes to [heard]. Null when NsdManager refused it at once: nothing comes. */
    fun register(advert: Advert, heard: NsdHeard): Any?

    /** Starts taking [registration] down, its end told to its [NsdHeard]; false when NsdManager has no such registration. */
    fun unregister(registration: Any): Boolean

    /** Takes the multicast lock where L15 wants one ([held]), or gives it back. */
    fun multicast(held: Boolean)
}

/**
 * The TV's record (L27) through the platform's NsdManager, one call at a time ([RecordKeeper], L47), with a multicast
 * lock held meanwhile where L15 wants one ([TvLinkPolicy.multicastLock]). The record it was last given is the one kept
 * up; closing its handle takes it down, once NSD has said it is up. Registering is the system's work, and a failure is
 * logged with NsdManager's code: the listener still takes phones that remember its address, or are given it by hand.
 * Nothing of the record is logged.
 */
class LinkNsd internal constructor(
    calls: NsdCalls,
    executor: ScheduledExecutorService = nsdThread,
    giveUpMillis: Long = GIVE_UP_MS,
    info: (String) -> Unit = { log.info(it) },
) : Advertiser {
    /** [giveUpMillis]: how long an answer from NsdManager is waited for; tests shrink it. */
    constructor(context: Context, giveUpMillis: Long = GIVE_UP_MS) : this(PlatformNsd(context), giveUpMillis = giveUpMillis)

    private val keeper = RecordKeeper(calls, executor, giveUpMillis, info)

    /** Whether no record is up, wanted or waited on: for a test to wait on once it closed its record. */
    internal val settled: Boolean get() = keeper.settled

    override fun advertise(record: ServiceRecord, port: Int, network: Network?): AutoCloseable {
        val advert = Advert(record, port, network)
        keeper.want(advert)
        return AutoCloseable { keeper.drop(advert) }
    }

    internal companion object {
        val log = LoggerFactory.getLogger(LinkNsd::class.java)

        /** How long an answer from NsdManager is waited for before it is given up (L47). */
        const val GIVE_UP_MS = 10_000L

        /** The one thread every advertiser's keeper works on. */
        private val nsdThread: ScheduledExecutorService by lazy {
            ScheduledThreadPoolExecutor(1) { task -> Thread(task, "link-nsd").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
        }
    }
}

/** NsdManager and the multicast lock, as [LinkNsd] calls them on a device. */
private class PlatformNsd(context: Context) : NsdCalls {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)

    /** None on a TV without Wi-Fi (Ethernet only), which takes no multicast lock. */
    private val lock: WifiManager.MulticastLock? =
        if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) {
            app.getSystemService(WifiManager::class.java)
                ?.takeIf { TvLinkPolicy.multicastLock(Build.VERSION.SDK_INT, wifi = true, ::tExtensionVersion) }
                ?.createMulticastLock(LOCK_TAG)?.apply { setReferenceCounted(false) }
        } else {
            null
        }

    override fun register(advert: Advert, heard: NsdHeard): Any? {
        val info = NsdServiceInfo().apply {
            serviceName = advert.record.name
            serviceType = ServiceRecord.TYPE
            port = advert.port
            advert.record.txt.forEach { (key, value) -> setAttribute(key, value) }
            if (advert.network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) network = advert.network
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) {
                LinkNsd.log.info("Play on TV: the TV's record is up (port {})", advert.port)
                heard.registered()
            }

            override fun onRegistrationFailed(failed: NsdServiceInfo, errorCode: Int) {
                LinkNsd.log.warn("Play on TV: couldn't advertise the TV (NSD error {}); phones reach it by its address alone", errorCode)
                heard.registrationFailed(errorCode)
            }

            override fun onServiceUnregistered(unregistered: NsdServiceInfo) = heard.unregistered()

            override fun onUnregistrationFailed(failed: NsdServiceInfo, errorCode: Int) {
                LinkNsd.log.debug("Play on TV: the TV's record not taken down (NSD error {})", errorCode)
                heard.unregistrationFailed(errorCode)
            }
        }
        return try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            listener
        } catch (e: IllegalArgumentException) {
            // A record NsdManager won't take (a name it finds bad, say): as a failed registration.
            LinkNsd.log.warn("Play on TV: couldn't advertise the TV", e)
            null
        }
    }

    override fun unregister(registration: Any): Boolean = try {
        nsd.unregisterService(registration as NsdManager.RegistrationListener)
        true
    } catch (_: IllegalArgumentException) {
        // Gone already: its registration failed.
        false
    }

    override fun multicast(held: Boolean) {
        if (held) lock?.acquire() else lock?.release()
    }

    private companion object {
        const val LOCK_TAG = "nzstreamer-link"
    }
}

/** The T extension's version, on Android 13 alone ([TvLinkPolicy.multicastLock] asks it there). */
internal fun tExtensionVersion(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) else 0
