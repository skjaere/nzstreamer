package io.skjaere.usenet.app.setup

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address
import java.net.InetAddress

/** How a network the TV is on connects: the two a phone on the same home network can reach it over. */
enum class LanTransport { Ethernet, Wifi }

/** A Wi-Fi or Ethernet network as the system reports it: whether it is the default network, and its addresses. */
data class LanNetwork(val transport: LanTransport, val isDefault: Boolean, val addresses: List<InetAddress>)

/** The TV's address on its home network ([LanAddress.pick]), and the network it is on, for what is registered there. */
data class LanBinding(val address: Inet4Address, val network: Network?)

/** Where the phone setup's page listens on the home network. */
object LanAddress {
    /**
     * The address a phone at home can open the page at: on the default network when that is Wi-Fi or Ethernet, else
     * on Ethernet, else on Wi-Fi, the network's first private IPv4 address (RFC 1918 alone, as isSiteLocalAddress has
     * it for IPv4). Link-local, CGNAT (Tailscale's), loopback, public and IPv6 addresses aren't: a phone on the home
     * network can't be sure to reach them. A network without one gives way to the next; null when none has one.
     */
    fun pick(networks: List<LanNetwork>): Inet4Address? = pickKeyed(networks.map { Unit to it })?.second

    /** As [pick], with the key ([K]) of the network whose address it is. */
    fun <K> pickKeyed(networks: List<Pair<K, LanNetwork>>): Pair<K, Inet4Address>? =
        networks.sortedWith(compareBy({ !it.second.isDefault }, { it.second.transport != LanTransport.Ethernet }))
            .firstNotNullOfOrNull { (key, network) ->
                (network.addresses.firstOrNull { it is Inet4Address && it.isSiteLocalAddress } as Inet4Address?)?.let { key to it }
            }

    /**
     * The TV's address on its home network ([pick]) as it changes, while collected: null when it has none. It listens
     * for Wi-Fi and Ethernet networks only (a request's default capabilities leave VPNs out) and keeps none of them up,
     * and tells which is the default. Needs ACCESS_NETWORK_STATE.
     */
    fun watch(context: Context): Flow<Inet4Address?> = follow(context) { it.pick() }

    /**
     * As [watch], with the network the address is on: Play on TV's listener binds the address, and on Android 13 and
     * later its mDNS record is registered on that network alone, so a phone resolves the address it listens on.
     */
    fun bindings(context: Context): Flow<LanBinding?> = follow(context) { networks -> networks.pickKeyed()?.let { (network, address) -> LanBinding(address, network) } }

    /** What [read] makes of the Wi-Fi and Ethernet networks as they change, while collected. */
    private fun <T> follow(context: Context, read: (LanNetworks<Network>) -> T?): Flow<T?> = callbackFlow {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val networks = LanNetworks<Network>()
        // The callbacks come on the system's own thread, the two of them on it in turn.
        fun publish(change: LanNetworks<Network>.() -> Unit) {
            trySend(synchronized(networks) { networks.change(); read(networks) })
        }
        // Only what the callbacks are given: the docs say not to ask the manager for a network's details inside one.
        val lan = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = publish {
                capabilities(network, if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) LanTransport.Ethernet else LanTransport.Wifi)
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = publish {
                links(network, properties.linkAddresses.map { it.address })
            }

            // From API 29; before, a network the system blocks for the app is lost to it instead.
            override fun onBlockedStatusChanged(network: Network, isBlocked: Boolean) = publish { blocked(network, isBlocked) }

            override fun onLost(network: Network) = publish { lost(network) }
        }
        val defaults = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish { default(network) }

            override fun onLost(network: Network) = publish { if (isDefault(network)) default(null) }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        trySend(null)
        connectivity.registerNetworkCallback(request, lan)
        connectivity.registerDefaultNetworkCallback(defaults)
        awaitClose {
            connectivity.unregisterNetworkCallback(lan)
            connectivity.unregisterNetworkCallback(defaults)
        }
    }.distinctUntilChanged()
}

/**
 * The Wi-Fi and Ethernet networks the system has told of, by its handle for each ([K]: its Network), and which one is
 * the default. A network counts once its kind is known; one blocked for the app doesn't. Not thread-safe.
 */
internal class LanNetworks<K> {
    private class Reported(var transport: LanTransport? = null, var addresses: List<InetAddress> = emptyList(), var blocked: Boolean = false)

    private val networks = LinkedHashMap<K, Reported>()
    private var default: K? = null

    fun capabilities(key: K, transport: LanTransport) {
        networks.getOrPut(key, ::Reported).transport = transport
    }

    fun links(key: K, addresses: List<InetAddress>) {
        networks.getOrPut(key, ::Reported).addresses = addresses
    }

    fun blocked(key: K, blocked: Boolean) {
        networks.getOrPut(key, ::Reported).blocked = blocked
    }

    fun lost(key: K) {
        networks.remove(key)
    }

    /** The default network is now [key] (any network, a VPN too), or none. */
    fun default(key: K?) {
        default = key
    }

    fun isDefault(key: K): Boolean = default == key

    fun pick(): Inet4Address? = pickKeyed()?.second

    /** As [pick], with the handle of the network whose address it is. */
    fun pickKeyed(): Pair<K, Inet4Address>? = LanAddress.pickKeyed(
        networks.mapNotNull { (key, n) -> n.transport?.takeUnless { n.blocked }?.let { key to LanNetwork(it, key == default, n.addresses) } },
    )
}
