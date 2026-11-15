package io.skjaere.usenet.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How the app reads the system's default network callback (L381): whether the default network has the internet, is
 * validated and isn't blocked for the app, the answer the downloads' runner and writer follow, whose return ends their
 * waits. Networks are named by strings here, as the callback names them by their handles.
 */
class DefaultNetworkTest {
    /** The answer after each of [events], in turn. */
    private fun DefaultNetwork<String>.after(vararg events: DefaultNetwork<String>.() -> Unit): List<Boolean> = events.map { event ->
        event()
        usable
    }

    @Test
    fun `the answer read before the callback's first word holds until the callback says otherwise`() {
        assertTrue(DefaultNetwork<String>(usable = true).usable)
        assertFalse(DefaultNetwork<String>(usable = false).usable)
        // The callback names the default as it registers: its capabilities settle it.
        assertEquals(listOf(true, true), DefaultNetwork<String>(usable = true).after({ available(WIFI) }, { capabilities(WIFI, true) }))
        assertEquals(listOf(false, false), DefaultNetwork<String>(usable = false).after({ available(WIFI) }, { capabilities(WIFI, false) }))
    }

    @Test
    fun `the default lost is a loss, and a network back and validated is its return`() {
        val network = DefaultNetwork<String>(usable = true)
        assertEquals(
            listOf(true, true, false, false, true),
            network.after(
                { available(WIFI) },
                { capabilities(WIFI, validated = true) },
                { lost(WIFI) },
                // Back, its capabilities follow at once: no answer flickers in between.
                { available(WIFI) },
                { capabilities(WIFI, validated = true) },
            ),
        )
    }

    @Test
    fun `moving from one validated network to another reads as no loss`() {
        val network = DefaultNetwork<String>(usable = true)
        assertEquals(
            listOf(true, true, true, true),
            network.after(
                { capabilities(WIFI, validated = true) },
                // The default callback names the new default without a word of the old one's loss.
                { available(CELL) },
                { capabilities(CELL, validated = true) },
                // The old one's loss, after the new one took over, changes nothing.
                { lost(WIFI) },
            ),
        )
    }

    @Test
    fun `a default not yet validated is a loss until it is`() {
        val network = DefaultNetwork<String>(usable = true)
        assertEquals(
            listOf(true, false, false, true),
            network.after(
                { capabilities(CELL, validated = true) },
                // A network with no internet behind it yet (a portal's sign-in, say) taken as the default.
                { capabilities(WIFI, validated = false) },
                { capabilities(WIFI, validated = false) },
                { capabilities(WIFI, validated = true) },
            ),
        )
    }

    @Test
    fun `a network blocked for the app is a loss, and unblocked its return`() {
        val network = DefaultNetwork<String>(usable = false)
        assertEquals(
            listOf(true, false, true),
            network.after(
                { capabilities(WIFI, validated = true) },
                { blocked(WIFI, blocked = true) },
                { blocked(WIFI, blocked = false) },
            ),
        )
        // A new default's blocked status after its capabilities, as Android 10 on sends it, settles it too.
        assertEquals(
            listOf(false, true, false),
            DefaultNetwork<String>(usable = false).after({ available(CELL) }, { capabilities(CELL, validated = true) }, { blocked(CELL, blocked = true) }),
        )
        // One that comes before its capabilities waits for them.
        assertEquals(
            listOf(true, true, false),
            DefaultNetwork<String>(usable = true).after({ available(CELL) }, { blocked(CELL, blocked = true) }, { capabilities(CELL, validated = true) }),
        )
    }

    private companion object {
        const val WIFI = "wifi-100"
        const val CELL = "cell-101"
    }
}
