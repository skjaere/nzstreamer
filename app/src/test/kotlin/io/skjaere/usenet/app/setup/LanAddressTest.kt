package io.skjaere.usenet.app.setup

import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where the phone setup's page listens: a private IPv4 address (RFC 1918 alone) of the TV's Wi-Fi or Ethernet network,
 * the default one first, then Ethernet, then Wi-Fi. Nothing else a phone at home could reach for sure.
 */
class LanAddressTest {
    private fun v4(text: String): Inet4Address = InetAddress.getByAddress(text.split('.').map { it.toInt().toByte() }.toByteArray()) as Inet4Address

    private val ula: Inet6Address = InetAddress.getByAddress(ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }) as Inet6Address

    private fun wifi(vararg addresses: InetAddress, default: Boolean = false) = LanNetwork(LanTransport.Wifi, default, addresses.toList())

    private fun ethernet(vararg addresses: InetAddress, default: Boolean = false) = LanNetwork(LanTransport.Ethernet, default, addresses.toList())

    @Test
    fun `each private range is taken`() {
        listOf("10.0.2.15", "172.16.0.9", "172.31.255.1", "192.168.1.40").forEach {
            assertEquals(v4(it), LanAddress.pick(listOf(wifi(v4(it)))), it)
        }
    }

    @Test
    fun `addresses a phone at home can't be sure to reach are not`() {
        // Link-local, CGNAT (Tailscale's), loopback, CLAT's, public, just outside 172.16/12, and IPv6 (a ULA).
        listOf("169.254.10.1", "100.64.0.7", "100.127.1.1", "127.0.0.1", "192.0.0.4", "8.8.8.8", "172.32.0.1").forEach {
            assertNull(LanAddress.pick(listOf(wifi(v4(it)))), it)
        }
        assertNull(LanAddress.pick(listOf(wifi(ula))))
        assertNull(LanAddress.pick(emptyList()))
    }

    @Test
    fun `a network's first private IPv4 address, past its others`() {
        assertEquals(v4("192.168.1.40"), LanAddress.pick(listOf(wifi(ula, v4("169.254.3.3"), v4("192.168.1.40"), v4("10.1.1.1")))))
    }

    @Test
    fun `the default network first, when it is Wi-Fi or Ethernet`() {
        val picked = LanAddress.pick(listOf(ethernet(v4("192.168.1.10")), wifi(v4("192.168.2.20"), default = true)))
        assertEquals(v4("192.168.2.20"), picked)
    }

    @Test
    fun `else Ethernet before Wi-Fi`() {
        assertEquals(v4("192.168.1.10"), LanAddress.pick(listOf(wifi(v4("192.168.2.20")), ethernet(v4("192.168.1.10")))))
    }

    @Test
    fun `a default network with no private address gives way to the next`() {
        assertEquals(v4("192.168.2.20"), LanAddress.pick(listOf(ethernet(v4("100.64.0.7"), default = true), wifi(v4("192.168.2.20")))))
    }

    @Test
    fun `the system's reports make the networks, until they go`() {
        val networks = LanNetworks<String>()
        assertNull(networks.pick())
        // Addresses before the network's kind is known count for nothing yet.
        networks.links("wlan", listOf(v4("192.168.2.20")))
        assertNull(networks.pick())
        networks.capabilities("wlan", LanTransport.Wifi)
        assertEquals(v4("192.168.2.20"), networks.pick())
        networks.capabilities("eth", LanTransport.Ethernet)
        networks.links("eth", listOf(v4("192.168.1.10")))
        assertEquals(v4("192.168.1.10"), networks.pick())
        // The default is a VPN, which the request leaves out: it counts for nothing.
        networks.default("vpn")
        assertEquals(v4("192.168.1.10"), networks.pick())
        networks.default("wlan")
        assertEquals(v4("192.168.2.20"), networks.pick())
        networks.lost("wlan")
        assertEquals(v4("192.168.1.10"), networks.pick())
        networks.blocked("eth", true)
        assertNull(networks.pick())
        networks.blocked("eth", false)
        assertEquals(v4("192.168.1.10"), networks.pick())
        networks.links("eth", listOf(v4("192.168.1.11")))
        assertEquals(v4("192.168.1.11"), networks.pick())
    }
}
