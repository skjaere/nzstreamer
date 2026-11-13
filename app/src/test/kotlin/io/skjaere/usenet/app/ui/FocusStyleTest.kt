package io.skjaere.usenet.app.ui

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How the focus shows, by form factor: the TV's as the TV design has it, seen from about 3 m. */
class FocusStyleTest {
    /** The app's own components turn over and grow a little; rings for the rest are 3 dp, where 2 dp is too faint (#148). */
    @Test
    fun `a TV's focus turns the app's components over and draws 3 dp rings`() {
        val tv = FocusStyle.Tv
        assertEquals(3.dp, tv.outline)
        assertTrue(tv.invert)
        assertEquals(1.02f, tv.rowScale)
        assertEquals(1.05f, tv.buttonScale)
        assertEquals(1.1f, tv.cardScale)
        assertEquals(1.05f, tv.cardPressedScale)
        assertEquals(0.98f, tv.pressedScale)
    }

    /** A pointer remote's hover shows as the focus does. */
    @Test
    fun `on a TV a hover looks as the focus does`() {
        assertTrue(FocusStyle.Tv.hover)
        val ripple = assertNotNull(FocusStyle.Tv.ripple, "material's controls keep their own layers on a TV")
        assertEquals(ripple.focusedAlpha, ripple.hoveredAlpha)
    }

    /** material3's controls the app doesn't wrap (icon and text buttons): the accent's layer at 0.28 over them. */
    @Test
    fun `on a TV material's controls take a stronger focus layer`() {
        assertEquals(0.28f, FocusStyle.Tv.ripple!!.focusedAlpha)
    }

    @Test
    fun `a phone's focus is the 2 dp ring it was, with nothing turned over or grown`() {
        val phone = FocusStyle.Phone
        assertEquals(2.dp, phone.outline)
        assertFalse(phone.invert)
        assertFalse(phone.hover)
        assertNull(phone.ripple)
        listOf(phone.rowScale, phone.buttonScale, phone.cardScale, phone.cardPressedScale, phone.pressedScale).forEach { assertEquals(1f, it) }
    }
}
