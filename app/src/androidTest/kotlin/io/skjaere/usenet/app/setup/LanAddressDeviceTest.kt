package io.skjaere.usenet.app.setup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The phone setup's address on a device: the system's network callbacks (which need ACCESS_NETWORK_STATE) report a
 * Wi-Fi or Ethernet network, and the address picked is one of this device's own private IPv4 addresses.
 */
@RunWith(AndroidJUnit4::class)
class LanAddressDeviceTest {
    @Test
    fun theAddressIsOneOfThisDevicesPrivateOnes() = runBlocking {
        val own = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filter { it is Inet4Address && it.isSiteLocalAddress }
        assumeTrue("this device has no private IPv4 address", own.isNotEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val picked = withTimeout(5_000) { LanAddress.watch(context).first { it != null } }
        assertTrue("picked $picked, not one of this device's ($own)", picked in own)
    }
}
