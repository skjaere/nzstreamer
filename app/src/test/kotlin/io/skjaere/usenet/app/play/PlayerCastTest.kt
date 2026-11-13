package io.skjaere.usenet.app.play

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The player's Cast button (W44, L110, L115, L124; CsPhonePlayer): in its top bar right of ⓘ on phones and tablets, never
 * on a TV; the title's room is what the buttons leave, 48 dp less with it, as the board has it on an 844 dp wide phone in
 * landscape: 572 dp without, 524 dp with.
 */
class PlayerCastTest {
    @Test
    fun `the button shows on a phone or a tablet, never on a TV`() {
        assertTrue(PlayerCast.shows(television = false))
        assertFalse(PlayerCast.shows(television = true))
    }

    @Test
    fun `the title's room shrinks by the button's 48 dp, 572 to 524 dp at 844 wide`() {
        assertEquals(572, PlayerCast.titleRoom(844, pictureInPicture = true, cast = false))
        assertEquals(524, PlayerCast.titleRoom(844, pictureInPicture = true, cast = true))
        // Where the player offers no window there is no picture-in-picture button to take its room.
        assertEquals(572, PlayerCast.titleRoom(844, pictureInPicture = false, cast = true))
        // The system bars' and the cutout's insets at the sides are no room for it either.
        assertEquals(524 - 48 - 24, PlayerCast.titleRoom(844, pictureInPicture = true, cast = true, safeStart = 48, safeEnd = 24))
    }
}
