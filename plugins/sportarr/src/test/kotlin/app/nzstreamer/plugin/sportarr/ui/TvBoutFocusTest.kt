package app.nzstreamer.plugin.sportarr.ui

import android.view.KeyEvent
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A TV bout's focus as its part starts (L-B10): the minute's tick that starts the part, while the user reads the card,
 * changes what OK does on a bout and never the element that holds the focus. Compose keeps a node only for an element of
 * the same class in its place, so a bout whose focus came from another element once its part started would lose the
 * focus right there, and the TV's shell would settle it elsewhere on the page. OK on a started bout is pressed on its down
 * and plays on its up, and a pointer's press shows, as a clickable's.
 */
class TvBoutFocusTest {
    private val interaction = RecordingInteractions()
    private var played = 0

    /** The bout's place in its pane's memory, as its row gives it. */
    private val element = Modifier.focusRequester(FocusRequester())

    /** The classes of the bout's focus and OK, the outermost first: before its part starts ([play] none), or after. */
    private fun classes(play: String?): List<String> =
        Modifier.boutFocus(element, interaction, null, BoutPress(interaction), play) { played++ }
            .foldIn(emptyList()) { all, element -> all + element.javaClass.simpleName }

    @Test
    fun `a bout's part starting changes none of its focus and OK's elements, class for class, so its focus target stays`() {
        val before = classes(play = null)
        val after = classes(play = "Play Prelims")
        assertTrue("FocusableElement" in before, "the bout takes the focus before its start: $before")
        assertEquals(before, after, "the elements before the start and after it")
    }

    @Test
    fun `OK on a started bout is pressed on its down, held through its repeats, and plays once on its up`() {
        val press = BoutPress(interaction)
        assertTrue(press.key(KeyEvent.KEYCODE_DPAD_CENTER, down = true, playable = true) { played++ })
        assertTrue(press.key(KeyEvent.KEYCODE_DPAD_CENTER, down = true, playable = true) { played++ }, "a repeat taken")
        assertEquals(0, played, "nothing played while it is held")
        assertTrue(press.key(KeyEvent.KEYCODE_DPAD_CENTER, down = false, playable = true) { played++ })
        assertEquals(1, played)
        val (pressed, released) = interaction.emitted
        assertEquals(listOf(PressInteraction.Press::class, PressInteraction.Release::class), interaction.emitted.map { it::class })
        assertEquals(pressed, (released as PressInteraction.Release).press)
        listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A).forEach { ok ->
            assertTrue(press.key(ok, down = true, playable = true) { played++ } && press.key(ok, down = false, playable = true) { played++ }, "$ok")
        }
        assertEquals(4, played, "each of a remote's OK keys")
    }

    @Test
    fun `an up whose down it never saw, another key, and OK before the start are not taken and play nothing`() {
        val press = BoutPress(interaction)
        assertFalse(press.key(KeyEvent.KEYCODE_DPAD_CENTER, down = false, playable = true) { played++ }, "an up with no down")
        assertFalse(press.key(KeyEvent.KEYCODE_DPAD_DOWN, down = true, playable = true) { played++ }, "Down")
        assertFalse(press.key(KeyEvent.KEYCODE_DPAD_CENTER, down = true, playable = false) { played++ }, "OK before the start")
        assertFalse(press.key(KeyEvent.KEYCODE_DPAD_CENTER, down = false, playable = false) { played++ })
        assertEquals(0, played)
        assertEquals(emptyList(), interaction.emitted)
    }

    @Test
    fun `a press under way is let go as the bout loses the focus, and its up then plays nothing`() {
        val press = BoutPress(interaction)
        press.key(KeyEvent.KEYCODE_DPAD_CENTER, down = true, playable = true) { played++ }
        press.letGo()
        assertFalse(press.key(KeyEvent.KEYCODE_DPAD_CENTER, down = false, playable = true) { played++ })
        assertEquals(0, played)
        assertEquals(listOf(PressInteraction.Press::class, PressInteraction.Cancel::class), interaction.emitted.map { it::class })
    }

    @Test
    fun `a pointer's press on a started bout shows from where it went down, let go as it is released or cancelled`() = runBlocking<Unit> {
        val press = BoutPress(interaction)
        press.pointer(Offset(12f, 30f)) { true }
        press.pointer(Offset(40f, 8f)) { false }
        assertEquals(
            listOf(PressInteraction.Press::class, PressInteraction.Release::class, PressInteraction.Press::class, PressInteraction.Cancel::class),
            interaction.emitted.map { it::class },
        )
        assertEquals(listOf(Offset(12f, 30f), Offset(40f, 8f)), interaction.emitted.filterIsInstance<PressInteraction.Press>().map { it.pressPosition })
    }

    /** An interaction source keeping what was emitted to it, in order. */
    private class RecordingInteractions : MutableInteractionSource {
        val emitted = mutableListOf<Interaction>()
        override val interactions: Flow<Interaction> = emptyFlow()

        override suspend fun emit(interaction: Interaction) {
            emitted += interaction
        }

        override fun tryEmit(interaction: Interaction): Boolean {
            emitted += interaction
            return true
        }
    }
}
