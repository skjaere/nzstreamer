package io.skjaere.usenet.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory

/**
 * Whether the device's default network has the internet and Android calls it validated, for the app to use: what the
 * downloads' runner and writer read at each failure (a provider out of reach on a validated network counts toward the
 * hour, on any other toward nothing, L382), and whose return ends their waits before a reopen or Resolve's next try
 * (L381), on either host. One per process ([AppContainer.validatedNetwork]): the system's default network callback,
 * registered once and kept for the process's life, read as [DefaultNetwork] says. A TV builds it too, though nothing on
 * a TV runs downloads.
 */
object ValidatedNetwork {
    fun of(context: Context): StateFlow<Boolean> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        // Until the callback says, the device's network now.
        val default = DefaultNetwork<Network>(connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.let(::usable) == true)
        val state = MutableStateFlow(default.usable)

        // The callbacks come on the system's own thread, in turn.
        fun publish(change: DefaultNetwork<Network>.() -> Unit) = synchronized(default) {
            default.change()
            state.value = default.usable
        }
        // Only what the callbacks are given: the docs say not to ask the manager for a network's details inside one.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish { available(network) }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = publish { capabilities(network, usable(caps)) }

            // From API 29; before, a network the system blocks for the app is lost to it instead.
            override fun onBlockedStatusChanged(network: Network, isBlocked: Boolean) = publish { blocked(network, isBlocked) }

            override fun onLost(network: Network) = publish { lost(network) }
        }
        try {
            connectivity.registerDefaultNetworkCallback(callback)
        } catch (e: RuntimeException) {
            // Android 11 on caps an app's callbacks. Read as validated, as before the app followed it: a provider out of
            // reach still ends in the downloads' wait after its hour, and Resume and a provider saved still end a backoff.
            log.warn("Couldn't follow the device's network: {}", e::class.simpleName)
            state.value = true
        }
        return state.asStateFlow()
    }

    private fun usable(caps: NetworkCapabilities) =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private val log = LoggerFactory.getLogger(ValidatedNetwork::class.java)
}

/**
 * The default network as the system's default network callback tells of it, by its handle ([K]: its Network), from a
 * first answer ([usable]) read before the callback's first word: whether it is [usable], with the internet, validated and
 * not blocked for the app. Every network the callback names but in a loss is the default from then on. A new one keeps the
 * answer before it until its capabilities come (from Android 8 they follow its arrival at once), so moving from one
 * validated network to another reads as no loss, and one that becomes the default before it is validated as a loss until
 * it is. Not thread-safe.
 */
class DefaultNetwork<K>(usable: Boolean) {
    private var default: K? = null

    // Whether the default's capabilities have come, and what they and its blocked status say.
    private var heard = false
    private var validated = false
    private var blocked = false

    /** Whether the default network has the internet, is validated and isn't blocked for the app. */
    var usable = usable
        private set

    /** [network] is the default now. */
    fun available(network: K) {
        if (network == default) return
        default = network
        heard = false
        validated = false
        blocked = false
    }

    /** The default [network] has the internet and is validated, or not ([validated]). */
    fun capabilities(network: K, validated: Boolean) {
        available(network)
        this.validated = validated
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
        usable = false
    }

    private fun settle() {
        usable = validated && !blocked
    }
}
