package io.skjaere.usenet.app.play

import android.view.KeyEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OkGuardTest {
    private val opened = 10_000L
    private val ok = listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)

    /** The second press of a double OK, the first having opened the sheet. */
    @Test
    fun `an OK just after the sheet opens is ignored, going down and coming up`() {
        ok.forEach { key ->
            val guard = OkGuard(opened)
            assertTrue(guard.blocks(key, down = true, repeatCount = 0, eventTime = opened + 120), "down of $key")
            assertTrue(guard.blocks(key, down = false, repeatCount = 0, eventTime = opened + 180), "up of $key")
        }
    }

    @Test
    fun `from 400 ms an OK goes through`() {
        val guard = OkGuard(opened)
        assertFalse(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeatCount = 0, eventTime = opened + 400))
        assertFalse(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = false, repeatCount = 0, eventTime = opened + 480))
    }

    /**
     * What arrives unprompted (the phone setup's review, its first focus on Save) takes a longer window: an OK
     * meant for something else as it appears must not save what the user hasn't read.
     */
    @Test
    fun `a longer window ignores an OK until it has passed`() {
        val guard = OkGuard(opened, windowMs = 1_000)
        assertTrue(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeatCount = 0, eventTime = opened + 900))
        assertTrue(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = false, repeatCount = 0, eventTime = opened + 950))
        assertFalse(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeatCount = 0, eventTime = opened + 1_000))
        assertFalse(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = false, repeatCount = 0, eventTime = opened + 1_050))
    }

    /** Else the button focused would see an up without its down. */
    @Test
    fun `a press ignored going down is ignored coming up, however late`() {
        val guard = OkGuard(opened)
        assertTrue(guard.blocks(KeyEvent.KEYCODE_ENTER, down = true, repeatCount = 0, eventTime = opened + 390))
        assertTrue(guard.blocks(KeyEvent.KEYCODE_ENTER, down = false, repeatCount = 0, eventTime = opened + 700))
        // The next press is its own.
        assertFalse(guard.blocks(KeyEvent.KEYCODE_ENTER, down = true, repeatCount = 0, eventTime = opened + 900))
        assertFalse(guard.blocks(KeyEvent.KEYCODE_ENTER, down = false, repeatCount = 0, eventTime = opened + 950))
    }

    /** An OK held on what opened the sheet repeats into it. */
    @Test
    fun `a held OK's repeats are ignored, also after 400 ms`() {
        val guard = OkGuard(opened)
        assertTrue(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeatCount = 1, eventTime = opened + 50))
        assertTrue(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeatCount = 7, eventTime = opened + 1_500))
    }

    /**
     * Its up is that press's, whose down went elsewhere: ignored too, however late. Let through, it would reach the button
     * without its down, which presses nothing, and go on past it (to the player, which brings its controls up for it).
     */
    @Test
    fun `a held OK's up is ignored with its repeats, and the next press is its own`() {
        val guard = OkGuard(opened)
        assertTrue(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeatCount = 3, eventTime = opened + 900))
        assertTrue(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = false, repeatCount = 0, eventTime = opened + 1_200))
        assertFalse(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeatCount = 0, eventTime = opened + 1_500))
        assertFalse(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = false, repeatCount = 0, eventTime = opened + 1_550))
    }

    /**
     * A press it let through, held past the key-repeat timeout: its repeats are ignored as any are, but its up is the
     * press's own, which the button focused clicks on (Compose's clickable answers the up of a down it saw).
     */
    @Test
    fun `a held OK whose down went through comes up through it, so the button clicks`() {
        val guard = OkGuard(opened)
        assertFalse(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeatCount = 0, eventTime = opened + 500))
        assertTrue(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeatCount = 1, eventTime = opened + 1_000))
        assertTrue(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeatCount = 2, eventTime = opened + 1_050))
        assertFalse(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = false, repeatCount = 0, eventTime = opened + 1_100), "the up")
        // A held press whose down went elsewhere is still ignored, up and all.
        assertTrue(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeatCount = 4, eventTime = opened + 2_000))
        assertTrue(guard.blocks(KeyEvent.KEYCODE_DPAD_CENTER, down = false, repeatCount = 0, eventTime = opened + 2_100))
    }

    @Test
    fun `other keys go through at once, so the remote still moves the focus`() {
        val guard = OkGuard(opened)
        listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BACK).forEach { key ->
            assertFalse(guard.blocks(key, down = true, repeatCount = 0, eventTime = opened + 10), "down of $key")
            assertFalse(guard.blocks(key, down = true, repeatCount = 3, eventTime = opened + 60), "repeat of $key")
            assertFalse(guard.blocks(key, down = false, repeatCount = 0, eventTime = opened + 80), "up of $key")
        }
    }
}
