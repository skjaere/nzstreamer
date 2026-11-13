package io.skjaere.usenet.app.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A record discovery found and resolved: its instance name, addresses, port and TXT as it came. */
class FoundRecord(val name: String, val addresses: List<InetAddress>, val port: Int, val txt: Map<String, ByteArray?>)

/**
 * The device tests' own look for `_nzstreamer._tcp` records with NsdManager, as a phone on the network would make it,
 * from inside the app's process: each service found is resolved in turn (one at a time, as NsdService allows below API
 * 34), until [find]'s test takes one or its time runs out. No multicast lock: the TVs these run on are on Android 14 and
 * later, whose system receives mDNS for an app in front.
 */
class NsdLookup(context: Context) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    /** The first record [wanted] takes within [timeoutMillis]; null when none came. */
    fun find(timeoutMillis: Long, wanted: (FoundRecord) -> Boolean): FoundRecord? {
        val until = System.nanoTime() + timeoutMillis * 1_000_000
        fun left() = (until - System.nanoTime()) / 1_000_000
        val found = LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(service: NsdServiceInfo) = found.put(service)

            override fun onServiceLost(service: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(ServiceRecord.TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            while (left() > 0) {
                val service = found.poll(left(), TimeUnit.MILLISECONDS) ?: break
                val record = resolve(service, minOf(left(), 10_000)) ?: continue
                if (wanted(record)) return record
            }
            return null
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: IllegalArgumentException) {
                // It never started.
            }
        }
    }

    private fun resolve(service: NsdServiceInfo, timeoutMillis: Long): FoundRecord? {
        if (timeoutMillis <= 0) return null
        return if (Build.VERSION.SDK_INT >= 34) resolveByCallback(service, timeoutMillis) else resolveOnce(service, timeoutMillis)
    }

    @Suppress("DEPRECATION") // resolveService and host: what API 26-33 have
    private fun resolveOnce(service: NsdServiceInfo, timeoutMillis: Long): FoundRecord? {
        val result = CompletableFuture<FoundRecord?>()
        nsd.resolveService(
            service,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    result.complete(null)
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    result.complete(FoundRecord(resolved.serviceName, listOfNotNull(resolved.host), resolved.port, resolved.attributes))
                }
            },
        )
        return try {
            result.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        }
    }

    @RequiresApi(34)
    private fun resolveByCallback(service: NsdServiceInfo, timeoutMillis: Long): FoundRecord? {
        val result = CompletableFuture<FoundRecord?>()
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                result.complete(null)
            }

            override fun onServiceUpdated(updated: NsdServiceInfo) {
                if (updated.hostAddresses.isNotEmpty()) result.complete(FoundRecord(updated.serviceName, updated.hostAddresses, updated.port, updated.attributes))
            }

            override fun onServiceLost() = Unit

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        nsd.registerServiceInfoCallback(service, DIRECT, callback)
        return try {
            result.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } finally {
            try {
                nsd.unregisterServiceInfoCallback(callback)
            } catch (_: IllegalArgumentException) {
                // Its registration failed: nothing to undo.
            }
        }
    }

    private companion object {
        val DIRECT = Executor { it.run() }
    }
}
