package io.skjaere.usenet.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory

/**
 * What the device's default network offers the app now: whether it is [usable] (the internet, validated, and not
 * blocked for the app), [unmetered] (Wi-Fi or Ethernet, as JobScheduler's unmetered network is), and whether it is
 * [wifi].
 */
data class NetworkNow(val usable: Boolean, val unmetered: Boolean = false, val wifi: Boolean = false) {
    companion object {
        /** No default network. */
        val NONE = NetworkNow(usable = false)
    }
}

/**
 * The device's default network as the app follows it (L381), one per process ([AppContainer.defaultNetwork]): the
 * system's default network callback, registered once and kept for the process's life, read as [DefaultNetwork] says.
 * Its two answers come from the same words, so they never disagree:
 * - [validated], whether the default is usable: what the downloads' runner and writer read at each failure (a provider
 *   out of reach on a validated network counts toward the hour, on any other toward nothing, L382), and whose return
 *   ends their waits before a reopen or Resolve's next try;
 * - [now], what it offers: the downloads' service walks only while it is the network their needs ask for
 *   ([io.skjaere.usenet.app.download.ServicePlan.networkMet]).
 *
 * The default, because the app's connections go out on it: a network that would match the needs but isn't the default
 * carries none of them. JobScheduler judges the job's network the same way. A TV builds it too, though nothing on a TV
 * runs downloads.
 */
class DeviceNetwork private constructor(first: NetworkNow) {
    private val default = DefaultNetwork<Any>(first)
    private val nows = MutableStateFlow(first)
    private val validates = MutableStateFlow(first.usable)

    /** What the default network offers the app now. */
    val now: StateFlow<NetworkNow> = nows.asStateFlow()

    /** Whether the default network has the internet, is validated and isn't blocked for the app. */
    val validated: StateFlow<Boolean> = validates.asStateFlow()

    /** A word of the callback's, [word]: both answers move with it. The callbacks come on the system's own thread, in turn. */
    @VisibleForTesting
    internal fun heard(word: DefaultNetwork<Any>.() -> Unit) = synchronized(default) {
        default.word()
        nows.value = default.now
        validates.value = default.usable
    }

    companion object {
        /** The device's default network, followed from now on. */
        fun of(context: Context): DeviceNetwork {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            return follow(first = { connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.let(::offers) ?: NetworkNow.NONE }) { network ->
                // Only what the callbacks are given: the docs say not to ask the manager for a network's details inside one.
                connectivity.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(net: Network) = network.heard { available(net) }

                        override fun onCapabilitiesChanged(net: Network, caps: NetworkCapabilities) = network.heard { capabilities(net, offers(caps)) }

                        // From API 29; before, a network the system blocks for the app is lost to it instead.
                        override fun onBlockedStatusChanged(net: Network, isBlocked: Boolean) = network.heard { blocked(net, isBlocked) }

                        override fun onLost(net: Network) = network.heard { lost(net) }
                    },
                )
            }
        }

        /**
         * The default network from [first], what it is until the callback's first word, then as the callback [register]
         * registers says it, each word handed to the [DeviceNetwork] it is given ([heard]). Should Android not take the
         * callback (Android 11 on caps an app's callbacks), the writer reads the network as validated, as before the app
         * followed it: a provider out of reach still ends in the downloads' wait after its hour, and Resume and a provider
         * saved still end a backoff. The service reads what [first] said, its own wait capped at an hour too.
         */
        @VisibleForTesting
        internal fun follow(first: () -> NetworkNow, register: (DeviceNetwork) -> Unit): DeviceNetwork {
            val network = DeviceNetwork(first())
            try {
                register(network)
            } catch (e: RuntimeException) {
                log.warn("Couldn't follow the device's network: {}", e::class.simpleName)
                network.validates.value = true
            }
            return network
        }

        private fun offers(caps: NetworkCapabilities) = NetworkNow(
            usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        )

        private val log = LoggerFactory.getLogger(DeviceNetwork::class.java)
    }
}

/**
 * The default network as the system's default network callback tells of it, by its handle ([K]: its Network), from a
 * first answer ([first]) read before the callback's first word: what it offers the app ([now]), usable only with the
 * internet, validated and not blocked for the app. Every network the callback names but in a loss is the default from
 * then on. A new one keeps the answer before it until its capabilities come (from Android 8 they follow its arrival at
 * once), so moving from one validated network to another reads as no loss, and one that becomes the default before it is
 * validated as a loss until it is. Not thread-safe.
 */
class DefaultNetwork<K>(first: NetworkNow) {
    private var default: K? = null

    // Whether the default's capabilities have come, what they offer, and its blocked status.
    private var heard = false
    private var offered = NetworkNow.NONE
    private var blocked = false

    /** What the default network offers the app now. */
    var now = first
        private set

    /** Whether the default network has the internet, is validated and isn't blocked for the app. */
    val usable: Boolean get() = now.usable

    /** [network] is the default now. */
    fun available(network: K) {
        if (network == default) return
        default = network
        heard = false
        offered = NetworkNow.NONE
        blocked = false
    }

    /** The default [network]'s capabilities: what it [offers], usable if it has the internet and is validated. */
    fun capabilities(network: K, offers: NetworkNow) {
        available(network)
        offered = offers
        heard = true
        settle()
    }

    /** The default [network] is [blocked] for the app, or not (Android 10 on). */
    fun blocked(network: K, blocked: Boolean) {
        available(network)
        this.blocked = blocked
        if (heard) settle()
    }

    /** [network] is gone: with no default, nothing is usable. */
    fun lost(network: K) {
        if (network != default) return
        default = null
        heard = false
        now = NetworkNow.NONE
    }

    private fun settle() {
        // Blocked, it is no use to the app, whatever it is.
        now = if (blocked) offered.copy(usable = false) else offered
    }
}
