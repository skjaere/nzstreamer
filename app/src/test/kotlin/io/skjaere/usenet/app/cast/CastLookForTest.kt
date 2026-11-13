package io.skjaere.usenet.app.cast

import io.skjaere.usenet.app.casting.CAST_DEVICES
import io.skjaere.usenet.app.casting.CAST_LOOKING_SAID
import io.skjaere.usenet.app.casting.CAST_LOOK_FOR
import io.skjaere.usenet.app.casting.CAST_LOOK_LINE
import io.skjaere.usenet.app.casting.CAST_PRIVACY
import io.skjaere.usenet.app.casting.CAST_SIMPLE
import io.skjaere.usenet.app.casting.CAST_UNAVAILABLE
import io.skjaere.usenet.app.casting.CAST_UNAVAILABLE_WHY
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W46, W47, L111, L112 (CsPhoneLookFor; CsPhoneTalkBackC2 2-4): the Play on list's Cast group, headed "Cast to", by what
 * the gate said at its opening and the opt-in. While nothing is asked yet and the flag is off, one row, "Look for Cast
 * devices", its line saying what Google's software sends, and Privacy; no line about simple files, as nothing looks yet.
 * Not read yet, the heading alone, so the row doesn't flash for someone who opted in. Once it looks, C1's group: the simple
 * line, then the devices. Without Play services, C1's one greyed row; on a TV, nothing. TalkBack reads "Cast to, heading",
 * then "Look for Cast devices, button" and the line as written, then "Privacy, link".
 */
class CastLookForTest {
    @Test
    fun `the flag off, the group asks first, with the row, its line and Privacy`() {
        val look = castGroupLook(CastGateState.NotAsked, looking = false)
        assertEquals(CastGroupLook.LookFor, look)
        assertEquals(listOf("Cast to, heading", "Look for Cast devices, button", CAST_LOOK_LINE, "Privacy, link"), look.spoken)
        assertFalse(CAST_SIMPLE in look.spoken, "nothing looks yet: no line about simple files")
    }

    @Test
    fun `the flag not read yet, the heading alone`() {
        val look = castGroupLook(CastGateState.NotAsked, looking = null)
        assertEquals(CastGroupLook.Heading, look)
        assertEquals(listOf("Cast to, heading"), look.spoken)
    }

    @Test
    fun `the line about simple files only once it looks`() {
        val look = castGroupLook(CastGateState.Started, looking = true)
        assertEquals(CastGroupLook.Devices, look)
        assertEquals(listOf("Cast to, heading", CAST_SIMPLE), look.spoken)
        for (asking in listOf(CastGroupLook.Heading, CastGroupLook.LookFor, CastGroupLook.Unavailable)) assertFalse(CAST_SIMPLE in asking.spoken, "$asking")
    }

    @Test
    fun `without Play services the group says Cast isn't available, never the row to look for`() {
        for (looking in listOf(true, false, null)) assertEquals(CastGroupLook.Unavailable, castGroupLook(CastGateState.Unavailable, looking), "$looking")
        assertEquals(listOf("Cast to, heading", CAST_UNAVAILABLE, CAST_UNAVAILABLE_WHY), CastGroupLook.Unavailable.spoken)
        assertFalse(CastGroupLook.Unavailable.spoken.any { CAST_LOOK_FOR in it })
    }

    @Test
    fun `a TV shows nothing of it`() {
        for (looking in listOf(true, false, null)) assertEquals(CastGroupLook.None, castGroupLook(CastGateState.Television, looking))
        assertEquals(emptyList(), CastGroupLook.None.spoken)
    }

    /** The approved words (CsPhoneLookFor, with its typography), and what TalkBack hears once after the tap (CsPhoneTalkBackC2 3). */
    @Test
    fun `the words`() {
        assertEquals("Cast to", CAST_DEVICES)
        assertEquals("Look for Cast devices", CAST_LOOK_FOR)
        assertEquals(
            "Uses Google’s Cast software, which sends Google data about finding and using Cast devices, this phone and this app, also while NZStreamer is closed.",
            CAST_LOOK_LINE,
        )
        assertEquals("Privacy", CAST_PRIVACY)
        assertEquals("Looking for Cast devices", CAST_LOOKING_SAID)
        assertTrue(CAST_LOOK_LINE.startsWith("Uses Google’s Cast software"))
    }
}
