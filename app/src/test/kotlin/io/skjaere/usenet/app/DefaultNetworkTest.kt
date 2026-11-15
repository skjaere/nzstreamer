package io.skjaere.usenet.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How the app reads the system's default network callback (L381): what the default network offers the app, usable only
 * with the internet, validated and not blocked for the app, the answer the downloads' runner and writer follow, whose
 * return ends their waits, and unmetered or Wi-Fi as the downloads' service reads it for their needs. Networks are named
 * by strings here, as the callback names them by their handles.
 */
class DefaultNetworkTest {
    /** Whether the default is usable after each of [events], in turn. */
    private fun DefaultNetwork<String>.after(vararg events: DefaultNetwork<String>.() -> Unit): List<Boolean> = events.map { event ->
        event()
        usable
    }

    @Test
    fun `the answer read before the callback's first word holds until the callback says otherwise`() {
        assertTrue(DefaultNetwork<String>(WIFI_NOW).usable)
        assertFalse(DefaultNetwork<String>(NetworkNow.NONE).usable)
        // The callback names the default as it registers: its capabilities settle it.
        assertEquals(listOf(true, true), DefaultNetwork<String>(WIFI_NOW).after({ available(WIFI) }, { capabilities(WIFI, WIFI_NOW) }))
        assertEquals(listOf(false, false), DefaultNetwork<String>(NetworkNow.NONE).after({ available(WIFI) }, { capabilities(WIFI, PORTAL) }))
    }

    @Test
    fun `the default lost is a loss, and a network back and validated is its return`() {
        val network = DefaultNetwork<String>(WIFI_NOW)
        assertEquals(
            listOf(true, true, false, false, true),
            network.after(
                { available(WIFI) },
                { capabilities(WIFI, WIFI_NOW) },
                { lost(WIFI) },
                // Back, its capabilities follow at once: no answer flickers in between.
                { available(WIFI) },
                { capabilities(WIFI, WIFI_NOW) },
            ),
        )
    }

    @Test
    fun `moving from one validated network to another reads as no loss`() {
        val network = DefaultNetwork<String>(WIFI_NOW)
        assertEquals(
            listOf(true, true, true, true),
            network.after(
                { capabilities(WIFI, WIFI_NOW) },
                // The default callback names the new default without a word of the old one's loss.
                { available(CELL) },
                { capabilities(CELL, CELL_NOW) },
                // The old one's loss, after the new one took over, changes nothing.
                { lost(WIFI) },
            ),
        )
        assertEquals(CELL_NOW, network.now)
    }

    @Test
    fun `a default not yet validated is a loss until it is`() {
        val network = DefaultNetwork<String>(WIFI_NOW)
        assertEquals(
            listOf(true, false, false, true),
            network.after(
                { capabilities(CELL, CELL_NOW) },
                // A network with no internet behind it yet (a portal's sign-in, say) taken as the default.
                { capabilities(WIFI, PORTAL) },
                { capabilities(WIFI, PORTAL) },
                { capabilities(WIFI, WIFI_NOW) },
            ),
        )
    }

    @Test
    fun `a network blocked for the app is a loss, and unblocked its return`() {
        val network = DefaultNetwork<String>(NetworkNow.NONE)
        assertEquals(
            listOf(true, false, true),
            network.after(
                { capabilities(WIFI, WIFI_NOW) },
                { blocked(WIFI, blocked = true) },
                { blocked(WIFI, blocked = false) },
            ),
        )
        // A new default's blocked status after its capabilities, as Android 10 on sends it, settles it too.
        assertEquals(
            listOf(false, true, false),
            DefaultNetwork<String>(NetworkNow.NONE).after({ available(CELL) }, { capabilities(CELL, CELL_NOW) }, { blocked(CELL, blocked = true) }),
        )
        // One that comes before its capabilities waits for them.
        assertEquals(
            listOf(true, true, false),
            DefaultNetwork<String>(WIFI_NOW).after({ available(CELL) }, { blocked(CELL, blocked = true) }, { capabilities(CELL, CELL_NOW) }),
        )
    }

    @Test
    fun `what the default offers besides comes with its answer, and nothing with none`() {
        val network = DefaultNetwork<String>(NetworkNow.NONE)
        network.capabilities(WIFI, WIFI_NOW)
        assertEquals(NetworkNow(usable = true, unmetered = true, wifi = true), network.now)
        // Blocked, it is no use to the app, whatever it is.
        network.blocked(WIFI, blocked = true)
        assertEquals(NetworkNow(usable = false, unmetered = true, wifi = true), network.now)
        network.capabilities(CELL, CELL_NOW)
        assertEquals(NetworkNow(usable = true, unmetered = false, wifi = false), network.now)
        network.lost(CELL)
        assertEquals(NetworkNow.NONE, network.now)
    }

    private companion object {
        const val WIFI = "wifi-100"
        const val CELL = "cell-101"

        /** Validated Wi-Fi, unmetered. */
        val WIFI_NOW = NetworkNow(usable = true, unmetered = true, wifi = true)

        /** Validated mobile data, metered. */
        val CELL_NOW = NetworkNow(usable = true, unmetered = false, wifi = false)

        /** Wi-Fi with no internet behind it yet: a portal's sign-in. */
        val PORTAL = NetworkNow(usable = false, unmetered = true, wifi = true)
    }
}
