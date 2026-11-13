package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.casting.CAST_CONNECTED
import io.skjaere.usenet.app.casting.CAST_FROM_ANOTHER
import io.skjaere.usenet.app.casting.CastDeviceFacts
import io.skjaere.usenet.app.casting.CastDeviceKind
import io.skjaere.usenet.app.casting.CastDeviceMedia
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastLinkNow
import io.skjaere.usenet.app.casting.CastNow
import io.skjaere.usenet.app.casting.CastPlayerState
import io.skjaere.usenet.app.casting.CastStop
import io.skjaere.usenet.app.casting.CastStopRule
import io.skjaere.usenet.app.casting.DISCONNECT
import io.skjaere.usenet.app.casting.STOP_CASTING
import io.skjaere.usenet.app.casting.disconnectFrom
import io.skjaere.usenet.app.casting.playingOn
import io.skjaere.usenet.data.WatchKey
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W47, W49, L117, L119, L154, M1-A (CsPhoneSheetConnected, CsPhoneTalkBackC2 5-9): the connected device's block at the top
 * of Play on's list, by the session, what the device has, this phone's cast play there and whether the device refused a
 * change of volume in this session. Connected with nothing playing, Stop casting; this phone's film, what plays with pause
 * and Stop casting, which stops it for everyone; another phone's, its title, no pause, a line, and Disconnect, which leaves
 * it playing; an idle device, Stop casting. The fixed line only after a refusal, never from the route's flag. The devices
 * and titles are invented.
 */
class CastBlockTest {
    private val kitchen = "route:kitchen"
    private fun session(link: CastLink = CastLink.Connected, fixed: Boolean = false) =
        CastLinkNow(kitchen, "Kitchen display", link, CastDeviceFacts("device:kitchen", "Chromecast", null, CastDeviceKind.Video, fixedVolume = fixed))

    /** This phone's cast play there, as the bar follows it: Harbor Lights S02E04, [shows]. */
    private fun ours(shows: RemoteBar = RemoteBar.Playing) = CastNow(
        "Kitchen display", kitchen, "load-1", "Harbor Lights", "S02E04 · Low Water", WatchKey.episode(991_301, 2, 4), shows, 1_930_000, 3_120_000, 0,
    )

    private val copperCrown = CastDeviceMedia(CastPlayerState.Playing, ours = false, title = "Copper Crown", line = "S03E02")

    @Test
    fun `no session, or one still connecting, has no block`() {
        assertNull(castBlockLook(null, null, null, casting = false, fixed = false))
        assertNull(castBlockLook(session(CastLink.Connecting), null, null, casting = false, fixed = false))
    }

    @Test
    fun `connected with nothing playing, Connected and Stop casting`() {
        val look = castBlockLook(session(), null, null, casting = false, fixed = false)!!
        assertEquals("Kitchen display", look.name)
        assertEquals(RowLine(CAST_CONNECTED, RowLine.Tone.Good), look.line)
        assertNull(look.toggle)
        assertTrue(look.connected)
        assertFalse(look.fromAnother)
        assertNull(look.fixedLine)
        assertEquals(CastStop.StopCasting, look.stop)
        assertEquals(STOP_CASTING, look.stopWords)
        // The device idle after a play, or with no media at all: still Stop casting, which closes its player there.
        val idle = castBlockLook(session(), CastDeviceMedia(CastPlayerState.Idle, ours = false), null, casting = false, fixed = false)!!
        assertEquals(RowLine(CAST_CONNECTED, RowLine.Tone.Good), idle.line)
        assertEquals(CastStop.StopCasting, idle.stop)
    }

    @Test
    fun `this phone's film plays there, with pause, and Stop casting`() {
        val look = castBlockLook(session(), CastDeviceMedia(CastPlayerState.Playing, ours = true), ours(), casting = true, fixed = false)!!
        assertEquals(RowLine("Playing Harbor Lights · S02E04", RowLine.Tone.Good), look.line)
        assertEquals("Playing Harbor Lights, season 2, episode 4", look.spokenLine)
        assertEquals(CastBlockLook.Toggle.Pause, look.toggle)
        assertEquals("Pause on Kitchen display", look.toggleWords)
        assertTrue(look.toggleActs)
        assertEquals(CastStop.StopCasting, look.stop)
        assertFalse(look.fromAnother)
        // Paused there: Play in its place.
        val paused = castBlockLook(session(), null, ours(RemoteBar.Paused), casting = true, fixed = false)!!
        assertEquals(CastBlockLook.Toggle.Play, paused.toggle)
        assertEquals("Play on Kitchen display", paused.toggleWords)
        // Its play ended there: the line goes back to Connected, and the button with it.
        val stopped = castBlockLook(session(), CastDeviceMedia(CastPlayerState.Idle, ours = true), ours(RemoteBar.Stopped), casting = false, fixed = false)!!
        assertEquals(RowLine(CAST_CONNECTED, RowLine.Tone.Good), stopped.line)
        assertNull(stopped.toggle)
    }

    @Test
    fun `another phone's film, its title, no pause, the line, and Disconnect`() {
        val look = castBlockLook(session(), copperCrown, null, casting = false, fixed = false)!!
        assertEquals(RowLine("Playing Copper Crown · S03E02", RowLine.Tone.Good), look.line)
        assertEquals("Playing Copper Crown, S03E02", look.spokenLine)
        assertNull(look.toggle)
        assertTrue(look.fromAnother)
        assertEquals("Cast from another phone. Disconnect leaves it playing there.", look.anotherLine)
        assertEquals(CastStop.Disconnect, look.stop)
        assertEquals(DISCONNECT, look.stopWords)
        assertEquals("Disconnect", DISCONNECT)
        assertEquals("Disconnect from Kitchen display", look.stopSpoken)
        // Paused or buffering there, still another's: Disconnect.
        for (state in listOf(CastPlayerState.Paused, CastPlayerState.Buffering, CastPlayerState.Loading)) {
            assertEquals(CastStop.Disconnect, castBlockLook(session(), copperCrown.copy(state = state), null, casting = false, fixed = false)!!.stop, "$state")
        }
        // A title alone; none at all (L149).
        assertEquals(RowLine("Playing Copper Crown", RowLine.Tone.Good), castBlockLook(session(), copperCrown.copy(line = null), null, casting = false, fixed = false)!!.line)
        val untitled = castBlockLook(session(), copperCrown.copy(title = null, line = null), null, casting = false, fixed = false)!!
        assertEquals(RowLine("Playing on Kitchen display", RowLine.Tone.Good), untitled.line)
        assertEquals(playingOn("Kitchen display"), untitled.spokenLine)
    }

    @Test
    fun `this phone's play starting there wins over what the device still shows`() {
        // The tap made a cast play, whose LOAD hasn't reached the device yet: its film is still another's.
        val look = castBlockLook(session(), copperCrown, null, casting = true, fixed = false)!!
        assertEquals(CastStop.StopCasting, look.stop)
        assertFalse(look.fromAnother)
        assertEquals(RowLine(CAST_CONNECTED, RowLine.Tone.Good), look.line)
    }

    @Test
    fun `the stop rule`() {
        assertEquals(CastStop.StopCasting, CastStopRule.of(casting = false, media = null), "nothing heard of the device")
        assertEquals(CastStop.StopCasting, CastStopRule.of(casting = false, media = CastDeviceMedia(CastPlayerState.Idle, ours = false)), "idle")
        assertEquals(CastStop.StopCasting, CastStopRule.of(casting = false, media = CastDeviceMedia(CastPlayerState.Playing, ours = true)), "our last load")
        assertEquals(CastStop.StopCasting, CastStopRule.of(casting = true, media = copperCrown), "our play there")
        assertEquals(CastStop.Disconnect, CastStopRule.of(casting = false, media = copperCrown), "another's")
        assertEquals(CastStop.Disconnect, CastStopRule.of(casting = false, media = copperCrown.copy(state = CastPlayerState.Unknown)), "another's, its state unknown")
    }

    @Test
    fun `reconnecting, the block stays with Stop casting, its pause and volume waiting`() {
        val look = castBlockLook(session(CastLink.Reconnecting), null, ours(RemoteBar.Reconnecting), casting = true, fixed = false)!!
        assertEquals(RowLine(RECONNECTING, RowLine.Tone.Muted), look.line)
        assertFalse(look.connected)
        assertFalse(look.toggleActs)
        assertEquals(CastStop.StopCasting, look.stop)
    }

    /** M1-A: the line only after the device refused a change in this session; the route's own flag shows nothing, greys nothing. */
    @Test
    fun `the device's own volume line after a refusal alone, the slider usable either way`() {
        val refused = castBlockLook(session(), null, null, casting = false, fixed = true)!!
        assertEquals("Kitchen display sets its own volume: use its own remote.", refused.fixedLine)
        assertTrue(refused.connected, "the slider still takes drags")
        val flagged = castBlockLook(session(fixed = true), null, null, casting = false, fixed = false)!!
        assertNull(flagged.fixedLine, "the route's flag alone")
        assertTrue(flagged.connected)
    }

    @Test
    fun `TalkBack's words, heading to button`() {
        assertEquals(
            listOf(
                "Kitchen display, heading", "Playing Harbor Lights, season 2, episode 4", "Pause on Kitchen display, button",
                "Volume on Kitchen display, slider", "Stop casting to Kitchen display, button",
            ),
            castBlockLook(session(), null, ours(), casting = true, fixed = false)!!.spoken,
        )
        assertEquals(
            listOf(
                "Kitchen display, heading", "Playing Copper Crown, S03E02", "Volume on Kitchen display, slider",
                "Kitchen display sets its own volume: use its own remote.", CAST_FROM_ANOTHER, "Disconnect from Kitchen display, button",
            ),
            castBlockLook(session(), copperCrown, null, casting = false, fixed = true)!!.spoken,
        )
        assertEquals("Disconnect from Den TV", disconnectFrom("Den TV"))
    }
}
