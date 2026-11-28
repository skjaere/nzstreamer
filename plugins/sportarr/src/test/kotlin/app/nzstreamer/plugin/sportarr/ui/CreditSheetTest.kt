package app.nzstreamer.plugin.sportarr.ui

import android.content.ActivityNotFoundException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The credit sheet's links (L-B7): the licence and View on Wikipedia open through one function, which never throws where
 * no app opens web links (a throw from a plugin's click would end NZStreamer and turn the plugin off at its next start):
 * it gives the address back instead, for the sheet to show as a line under the link.
 */
class CreditSheetTest {
    @Test
    fun `an address that opens leaves no line`() {
        val opened = mutableListOf<String>()
        assertNull(open(ADDRESS) { opened += it })
        assertEquals(listOf(ADDRESS), opened, "the address handed over as it is")
    }

    @Test
    fun `where nothing opens web links, as Compose's opener says it, the address shows under the link`() {
        assertEquals(ADDRESS, open(ADDRESS) { throw IllegalArgumentException("Can't open $it.") })
    }

    @Test
    fun `where no activity takes the link, the address shows under the link`() {
        assertEquals(ADDRESS, open(ADDRESS) { throw ActivityNotFoundException("No Activity found to handle Intent") })
    }

    @Test
    fun `the licence's link is its own page at Creative Commons`() {
        assertEquals("https://creativecommons.org/licenses/by-sa/4.0/", LICENCE_ADDRESS)
    }

    private companion object {
        const val ADDRESS = "http://127.0.0.1:1868/wiki/UFC_999"
    }
}
