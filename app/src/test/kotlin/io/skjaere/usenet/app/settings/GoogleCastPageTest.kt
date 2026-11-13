package io.skjaere.usenet.app.settings

import io.skjaere.usenet.app.casting.CAST_LOOK_FOR
import io.skjaere.usenet.data.CastPrefs
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W46, L112 (CsPhoneSettingsCast frames 1, 2 and 4): Settings › Playback › Google Cast, on phones and tablets alone. The
 * hub's row says whether this phone looks for Cast devices; the page's switch is the list's "Look for Cast devices", with
 * the approved words (the design's typography); turning it on counts as the list's tap and off asks again in the list, and
 * either writes the opt-in alone. A TV has neither the row nor the page (W28, L110).
 */
class GoogleCastPageTest {
    @Test
    fun `the hub's row says whether this phone looks for Cast devices`() {
        assertEquals("Google Cast", GoogleCastPage.TITLE)
        assertEquals("On: looks for Cast devices", GoogleCastPage.rowLine(look = true))
        assertEquals("Off", GoogleCastPage.rowLine(look = false))
    }

    @Test
    fun `the page's words are the design's`() {
        assertEquals(CAST_LOOK_FOR, GoogleCastPage.SWITCH, "the switch is the list's row, by the same words")
        assertEquals("Finds Chromecasts and TVs with Google Cast on this Wi-Fi, with Google’s Cast software from Google Play services.", GoogleCastPage.DETAIL)
        assertEquals(
            listOf(
                "Google’s Cast software starts when you open the list of devices to play on, and runs until NZStreamer closes. It sends " +
                    "Google data about finding and using Cast devices, this phone and this app, also while NZStreamer is closed.",
                "Turned off, it no longer starts, from the next time NZStreamer opens, and the list asks again before it looks. " +
                    "Your TVs with NZStreamer work either way.",
            ),
            GoogleCastPage.FOOTNOTE,
        )
    }

    @Test
    fun `on writes look for Cast devices, off writes it off, and nothing else changes`() {
        val prefs = CastPrefs(look = false, introShown = true, updatedAt = 42)
        assertEquals(CastPrefs(look = true, introShown = true, updatedAt = 42), GoogleCastPage.turned(prefs, on = true))
        assertEquals(CastPrefs(look = false, introShown = true, updatedAt = 42), GoogleCastPage.turned(prefs.copy(look = true), on = false))
    }

    @Test
    fun `a TV has no page and no row`() {
        assertFalse(GoogleCastPage.shows(television = true))
        assertTrue(GoogleCastPage.shows(television = false))
    }
}
