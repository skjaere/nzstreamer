package io.skjaere.usenet.app

import io.skjaere.usenet.app.download.ServicePlan
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The device's default network as the app follows it (L381): one callback a process, whose words give both answers, what
 * the network offers the downloads' service and whether the writer reads it as validated, so the two never disagree. The
 * callback is a test's here: each word handed to the [DeviceNetwork] it registers with, as Android's thread hands them.
 */
class DeviceNetworkTest {
    @Test
    fun `both answers come from the one callback, and the service's network is never met while the writer reads no network`() {
        var registered: DeviceNetwork? = null
        val network = DeviceNetwork.follow(first = { WIFI_NOW }) { registered = it }
        val callback = registered ?: throw AssertionError("nothing registered")
        assertEquals(WIFI_NOW, network.now.value)
        assertTrue(network.validated.value)

        val seen = mutableListOf<Pair<NetworkNow, Boolean>>()
        fun heard(word: DefaultNetwork<Any>.() -> Unit) {
            callback.heard(word)
            seen += network.now.value to network.validated.value
        }
        heard { capabilities(WIFI, WIFI_NOW) }
        // Stay connected on a Wi-Fi with no internet: it stays the default, mobile data validated behind it unheard of.
        heard { capabilities(WIFI, NetworkNow(usable = false, unmetered = true, wifi = true)) }
        heard { available(CELL) }
        heard { capabilities(CELL, CELL_NOW) }
        heard { blocked(CELL, blocked = true) }
        heard { lost(CELL) }
        heard { capabilities(WIFI, WIFI_NOW) }
        assertEquals(listOf(true, false, false, true, false, false, true), seen.map { it.second })
        seen.forEach { (now, validated) ->
            assertEquals(now.usable, validated, "the service's network and the writer's answer from one word: $now")
            listOf(false, true).forEach { wifiOnly ->
                if (ServicePlan.networkMet(now, wifiOnly)) assertTrue(validated, "met with the writer reading no network: $now, Wi-Fi only $wifiOnly")
            }
        }
    }

    @Test
    fun `a callback Android won't take leaves the writer reading a validated network, and the service the network as first read`() {
        // Android 11 on caps an app's callbacks: the writer's failures then count toward the hour, as before it followed one.
        val network = DeviceNetwork.follow(first = { CELL_NOW }) { throw IllegalStateException("Too many NetworkRequests filed") }
        assertTrue(network.validated.value)
        assertEquals(CELL_NOW, network.now.value)
    }

    private companion object {
        const val WIFI = "wifi-100"
        const val CELL = "cell-101"
        val WIFI_NOW = NetworkNow(usable = true, unmetered = true, wifi = true)
        val CELL_NOW = NetworkNow(usable = true, unmetered = false, wifi = false)
    }
}
