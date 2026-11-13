package io.skjaere.usenet.app.link

import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor

/**
 * Looks for TVs' records (`_nzstreamer._tcp`, L27) through the platform's NsdManager while collected (L12, L36), with a
 * multicast lock held meanwhile where L15 wants one ([TvLinkPolicy.multicastLock]). Each service found is resolved:
 * from API 34 by a service-info callback, which follows its addresses and TXT and tells when it goes; below, one resolve
 * at a time, as NsdService allows a client, a service found while one runs waiting its turn ([ResolveQueue]). A resolve
 * there can't be stopped, so none is given up on before it answers; a stuck one is passed over after a while, and the
 * answer for a service lost meanwhile is dropped. A record without a
 * well-formed `id` isn't a TV's ([ServiceRecord.read]); a TV's addresses are its private IPv4 ones, its TXT `v4` among
 * them, since an old phone's resolve may give one IPv6 address alone. Nothing of a record is logged.
 */
class LinkNsdFinder(context: Context) : TvFinder {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)

    /** None without Wi-Fi hardware: no multicast lock is taken. */
    private val wifi: WifiManager? =
        if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) app.getSystemService(WifiManager::class.java) else null

    override fun find(): Flow<List<FoundTv>?> = callbackFlow {
        val lock = wifi?.takeIf { TvLinkPolicy.multicastLock(Build.VERSION.SDK_INT, wifi = true, ::tExtensionVersion) }
            ?.createMulticastLock(LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        val search = Search(this)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = search.publish()

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                log.warn("Play on TV: couldn't look for TVs (NSD error {}); TVs are reached at their addresses alone", errorCode)
                trySend(null)
            }

            override fun onServiceFound(service: NsdServiceInfo) = search.found(service)

            override fun onServiceLost(service: NsdServiceInfo) = search.lost(service)

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(ServiceRecord.TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: IllegalArgumentException) {
                // It never started.
            }
            search.close()
            lock?.release()
        }
    }

    /** One look's services and what each resolved to; NsdManager's callbacks come on its own thread. */
    private inner class Search(private val out: ProducerScope<List<FoundTv>?>) {
        private val lock = Any()

        /** The TVs resolved, by service name. */
        private val tvs = LinkedHashMap<String, FoundTv>()

        /** API 34 on: each service's callback, by name. */
        private val callbacks = HashMap<String, NsdManager.ServiceInfoCallback>()

        /** Below API 34: services waiting for a resolve, and the one resolving now. */
        private val queue = ResolveQueue<NsdServiceInfo> { it.serviceName }
        private var stuck: Job? = null
        private var closed = false

        fun publish() {
            synchronized(lock) { out.trySend(tvs.values.toList()) }
        }

        fun found(service: NsdServiceInfo) {
            synchronized(lock) {
                if (closed) return
                if (Build.VERSION.SDK_INT >= 34) follow(service) else queue.found(service)?.let(::resolve)
            }
        }

        fun lost(service: NsdServiceInfo) {
            synchronized(lock) {
                if (Build.VERSION.SDK_INT >= 34) callbacks.remove(service.serviceName)?.let(::unfollow) else queue.lost(service.serviceName)
                if (tvs.remove(service.serviceName) != null) out.trySend(tvs.values.toList())
            }
        }

        fun close() {
            synchronized(lock) {
                closed = true
                stuck?.cancel()
                if (Build.VERSION.SDK_INT >= 34) callbacks.values.forEach(::unfollow)
                callbacks.clear()
            }
        }

        /** [service] resolved to [addresses]: a TV's record, or nothing of ours. Under [lock]. */
        private fun resolved(service: NsdServiceInfo, addresses: List<InetAddress>) {
            val record = ServiceRecord.read(service.serviceName, service.attributes) ?: return
            val v4 = (addresses.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress } + listOfNotNull(record.v4)).distinct()
            tvs[service.serviceName] = FoundTv(record, v4, service.port)
            out.trySend(tvs.values.toList())
        }

        /** Below API 34: [service] resolved now, the queue's turn for it; passed over if it hasn't answered in time. Under [lock]. */
        @Suppress("DEPRECATION") // resolveService and host: what API 26-33 have
        private fun resolve(service: NsdServiceInfo) {
            if (closed) return
            val name = service.serviceName
            stuck?.cancel()
            stuck = out.launch {
                delay(STUCK_MS)
                synchronized(lock) { queue.passOver(name)?.let(::resolve) }
            }
            nsd.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        synchronized(lock) {
                            if (closed) return
                            if (queue.resolving == name) stuck?.cancel()
                            // A service lost while this ran is gone: its answer would bring it back with nothing to take it away.
                            val (wanted, next) = queue.answered(name)
                            if (wanted) resolved(info, listOfNotNull(info.host))
                            next?.let(::resolve)
                        }
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        synchronized(lock) {
                            if (closed) return
                            if (queue.resolving == name) stuck?.cancel()
                            // Another resolve still runs (one passed over): this one waits its turn again, a moment later.
                            val busy = errorCode == NsdManager.FAILURE_ALREADY_ACTIVE
                            queue.failed(name, busy)?.let(::resolve)
                            if (busy) {
                                out.launch {
                                    delay(RETRY_MS)
                                    synchronized(lock) { if (!closed) queue.retry(service)?.let(::resolve) }
                                }
                            }
                        }
                    }
                },
            )
        }

        /** API 34 on: [service] followed as it changes, its addresses and TXT, until it goes. Under [lock]. */
        @RequiresApi(34)
        private fun follow(service: NsdServiceInfo) {
            val name = service.serviceName
            if (name in callbacks) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    log.debug("Play on TV: couldn't follow a TV's record (NSD error {})", errorCode)
                    synchronized(lock) { callbacks.remove(name) }
                }

                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    synchronized(lock) { if (!closed && updated.hostAddresses.isNotEmpty()) resolved(updated, updated.hostAddresses) }
                }

                override fun onServiceLost() {
                    synchronized(lock) { if (tvs.remove(name) != null) out.trySend(tvs.values.toList()) }
                }

                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            try {
                nsd.registerServiceInfoCallback(service, DIRECT, callback)
                callbacks[name] = callback
            } catch (e: IllegalArgumentException) {
                log.debug("Play on TV: couldn't follow a TV's record", e)
            }
        }

        @RequiresApi(34)
        private fun unfollow(callback: NsdManager.ServiceInfoCallback) {
            try {
                nsd.unregisterServiceInfoCallback(callback)
            } catch (_: IllegalArgumentException) {
                // Its registration failed: nothing to undo.
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(LinkNsdFinder::class.java)
        const val LOCK_TAG = "nzstreamer-link-find"

        /** A resolve that hasn't answered by now is passed over. */
        const val STUCK_MS = 15_000L

        /** How long a service waits to be resolved again after "another resolve runs". */
        const val RETRY_MS = 1_000L
        val DIRECT = Executor { it.run() }
    }
}
