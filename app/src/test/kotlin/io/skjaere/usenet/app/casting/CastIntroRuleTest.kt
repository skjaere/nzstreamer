package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.casting.CastIntroRule.Over
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the introduction to the Cast button shows (W24, W48, L110, L123; CsPhoneIntro), and its words: once, on a phone
 * with Google Play services, on Wi-Fi or Ethernet, after setup, over a screen whose button is placed, with nothing over
 * it, and never on a TV.
 */
class CastIntroRuleTest {
    /** Every condition met: a phone, Play services, the home network, set up, the button placed, nothing over it, never shown. */
    private fun show(
        television: Boolean = false,
        playServices: Boolean? = true,
        onLan: Boolean = true,
        setupDone: Boolean = true,
        placed: Boolean = true,
        over: Set<Over> = emptySet(),
        shown: Boolean? = false,
    ) = CastIntroRule.show(television, playServices, onLan, setupDone, placed, over, shown)

    @Test
    fun `it shows with every condition met`() {
        assertTrue(show())
    }

    @Test
    fun `never on a TV`() {
        assertFalse(show(television = true))
    }

    /** No Play services, no Cast: the button is the TV icon there; and not before the check is read (L146). */
    @Test
    fun `not without Play services, nor before they are known`() {
        assertFalse(show(playServices = false))
        assertFalse(show(playServices = null))
    }

    /** Cast devices show up on the home network alone: off Wi-Fi and Ethernet there is nothing to point at. */
    @Test
    fun `not off Wi-Fi and Ethernet`() {
        assertFalse(show(onLan = false))
    }

    /** New installs see it once setup is done: never over the first-run screen. */
    @Test
    fun `not before setup is done`() {
        assertFalse(show(setupDone = false))
    }

    /** Never over a list, a question, the play sheet, a prompt, the banner, a said line or the player (W48). */
    @Test
    fun `not while anything is over the screen`() {
        Over.entries.forEach { over -> assertFalse(show(over = setOf(over)), "under $over") }
        assertFalse(show(over = setOf(Over.List, Over.PlaySheet)))
        assertEquals(setOf(Over.List, Over.Question, Over.PlaySheet, Over.CastPrompt, Over.Banner, Over.Said, Over.Player), Over.entries.toSet())
    }

    @Test
    fun `not with the player in front`() {
        assertFalse(show(over = setOf(Over.Player)))
    }

    /** Shown once, kept in the app's own settings: never again, and not before the flag is read. */
    @Test
    fun `not once shown, nor before the flag is read`() {
        assertFalse(show(shown = true))
        assertFalse(show(shown = null))
    }

    /** On the first screen with the button: it waits for one to be placed (a screen without one, Settings say, shows none). */
    @Test
    fun `it waits for the button's spot`() {
        assertFalse(show(placed = false))
    }

    /** CsPhoneIntro's words, with the design's apostrophe. */
    @Test
    fun `the words`() {
        assertEquals("Play on your TV from here", CAST_INTRO_TITLE)
        assertEquals(
            "Tap the Cast button to play on Cast devices, such as a Chromecast or a TV with Google Cast, and on your TVs with NZStreamer. " +
                "They show up when they’re on this Wi-Fi.",
            CAST_INTRO_TEXT,
        )
        assertEquals("Got it", CAST_INTRO_GOT_IT)
    }

    /**
     * TalkBack reads it once, as a pane (L123, CsPhoneTalkBackC2): where the button is, which the circle shows the eye,
     * beside Settings on Home and at the top right elsewhere.
     */
    @Test
    fun `TalkBack hears where the button is`() {
        assertEquals(
            "Play on your TV from here. Tap the Cast button, at the top beside Settings, to play on Cast devices, such as a Chromecast or a TV with " +
                "Google Cast, and on your TVs with NZStreamer. They show up when they’re on this Wi-Fi.",
            CastIntroRule.spoken(home = true),
        )
        assertEquals(
            "Play on your TV from here. Tap the Cast button, at the top right, to play on Cast devices, such as a Chromecast or a TV with " +
                "Google Cast, and on your TVs with NZStreamer. They show up when they’re on this Wi-Fi.",
            CastIntroRule.spoken(home = false),
        )
    }
}
