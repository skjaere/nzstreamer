package io.skjaere.usenet.app.casting

import androidx.media3.common.Player
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L118, M1-A (Sender volume A-E; CsPhoneSheetConnected, CsPhoneRemoteC2): the Cast device's volume as its slider shows and
 * moves it. Google's 21 steps read as 0 to 100 % in 5 % steps, muted as 0 %; the slider follows the device; only the
 * user's drags go out, a step at a time, never a level on connect; 1 s after a drag the session's own level is read
 * again, never the player's, and one back where it was though the drag asked for another is a refusal, said for the rest
 * of the session while the slider stays usable. The player is a stand-in with a device volume, as the session's is.
 */
class CastVolumeTest {
    /** What the player was asked to set, in order. */
    private val sent = mutableListOf<Int>()

    /** The player's cached volume, as Media3's keeps it: what it last set or heard. */
    private var level = 8
    private var muted = false
    private var settable = true
    private val listeners = mutableListOf<Player.Listener>()

    /** What the session itself says, read again after a drag: null with no session. */
    private var session: CastVolumeNow? = CastVolumeNow(8, false)

    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
        when (method.name) {
            "getDeviceVolume" -> level
            "isDeviceMuted" -> muted
            "isCommandAvailable" -> settable && args[0] == Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS
            "setDeviceVolume" -> {
                // Media3 takes the level at once and tells its listeners, whatever the device does with it.
                sent += args[0] as Int
                level = args[0] as Int
                listeners.toList().forEach { it.onDeviceVolumeChanged(level, muted) }
            }
            "addListener" -> listeners += args[0] as Player.Listener
            "removeListener" -> listeners -= args[0] as Player.Listener
            else -> when (method.returnType) {
                java.lang.Long.TYPE -> 0L
                Integer.TYPE -> 0
                java.lang.Boolean.TYPE -> false
                else -> null
            }
        }
    } as Player

    private fun volume() = CastVolume(player) { session }

    /** The device changed its own volume (its remote, another phone, the keys): Media3 hears it and says so. */
    private fun device(to: Int, mute: Boolean = false) {
        level = to
        muted = mute
        session = CastVolumeNow(to, mute)
        listeners.toList().forEach { it.onDeviceVolumeChanged(to, mute) }
    }

    @Test
    fun `Google's 21 steps read as 0 to 100 percent in 5 percent steps, and muted reads 0`() {
        assertEquals(40, volume().percent.value)
        session = CastVolumeNow(0, false)
        assertEquals(0, volume().percent.value)
        session = CastVolumeNow(20, false)
        assertEquals(100, volume().percent.value)
        session = CastVolumeNow(9, true)
        assertEquals(0, volume().percent.value)
        // No session's own to read: the player's.
        session = null
        level = 13
        muted = false
        assertEquals(65, volume().percent.value)
        assertEquals("45 percent", volumePercent(45))
        assertEquals("Volume on Kitchen display", volumeOn("Kitchen display"))
    }

    @Test
    fun `nothing goes to the device as it connects or as the slider shows`() {
        val volume = volume()
        val heard = volume.listen()
        volume.reread()
        heard.close()
        assertEquals(emptyList(), sent)
        assertFalse(volume.refused.value)
    }

    @Test
    fun `a drag sends each step it reaches, once, and nothing else`() {
        val volume = volume()
        val heard = volume.listen()
        // The finger down where the slider is, then on: 42 % is still 40 %; 44 % is the next step.
        volume.drag(40f)
        volume.drag(42f)
        volume.drag(44f)
        volume.drag(46f)
        volume.drag(51f)
        volume.drag(61f)
        volume.drag(59f)
        volume.released()
        assertEquals(listOf(9, 10, 12), sent)
        assertEquals(60, volume.percent.value)
        // The device took it: the session says so a second later, and nothing more is sent.
        session = CastVolumeNow(12, false)
        volume.reread()
        assertEquals(listOf(9, 10, 12), sent)
        assertEquals(60, volume.percent.value)
        assertFalse(volume.refused.value)
        heard.close()
    }

    @Test
    fun `the slider follows the device's own changes, but not under a finger`() {
        val volume = volume()
        val heard = volume.listen()
        device(13)
        assertEquals(65, volume.percent.value)
        device(13, mute = true)
        assertEquals(0, volume.percent.value)
        device(4)
        assertEquals(20, volume.percent.value)
        volume.drag(20f)
        volume.drag(30f)
        // Another phone's change while the finger is down: the finger's level stays shown.
        listeners.toList().forEach { it.onDeviceVolumeChanged(2, false) }
        assertEquals(30, volume.percent.value)
        heard.close()
        // Not listened to any more: nothing follows.
        listeners.toList().forEach { it.onDeviceVolumeChanged(17, false) }
        assertTrue(listeners.isEmpty())
    }

    /**
     * The session's own level is read 1 s after the drag, never the player's cached one, which Media3 never corrects
     * for a device that ignores the change: back where it was before the drag, the device refused it.
     */
    @Test
    fun `a refused change snaps back on the re-read, and is said for the rest of the session`() {
        val volume = volume()
        val heard = volume.listen()
        volume.drag(40f)
        volume.drag(60f)
        volume.released()
        assertEquals(60, volume.percent.value)
        assertEquals(12, player.deviceVolume, "the player's cache took the level at once")
        // The device kept 8.
        volume.reread()
        assertEquals(40, volume.percent.value, "the session's level, not the player's")
        assertTrue(volume.refused.value)
        // Still usable: the next drag goes out, and the line stays.
        volume.drag(40f)
        volume.drag(70f)
        volume.released()
        assertEquals(listOf(12, 14), sent)
        volume.reread()
        assertTrue(volume.refused.value, "for the rest of the session")
        heard.close()
        // Shown again later, as the list opens again: the session's level, not the one the player still holds.
        assertEquals(14, player.deviceVolume)
        volume.listen().close()
        assertEquals(40, volume.percent.value)
    }

    @Test
    fun `a re-read that finds another level than before takes it, and says nothing of a refusal`() {
        val volume = volume()
        volume.drag(40f)
        volume.drag(70f)
        volume.released()
        // The TV's own remote moved it meanwhile.
        session = CastVolumeNow(5, false)
        volume.reread()
        assertEquals(25, volume.percent.value)
        assertFalse(volume.refused.value)
        // A drag that came back to where it began asked nothing new: no refusal either.
        volume.drag(25f)
        volume.drag(45f)
        volume.drag(25f)
        volume.released()
        session = CastVolumeNow(5, false)
        volume.reread()
        assertFalse(volume.refused.value)
    }

    /** A slider closed within the second after a drag never read the session again: the next one shown waits for it. */
    @Test
    fun `a re-read is due from a drag's end until it is made`() {
        val volume = volume()
        assertFalse(volume.waiting, "no drag")
        volume.drag(40f)
        volume.drag(60f)
        assertFalse(volume.waiting, "a finger down")
        volume.released()
        assertTrue(volume.waiting)
        volume.reread()
        assertFalse(volume.waiting)
        // Nothing asked: nothing to wait for once read either.
        volume.drag(60f)
        volume.released()
        assertTrue(volume.waiting)
        session = null
        volume.reread()
        assertFalse(volume.waiting, "no session to read: given up")
    }

    /**
     * Every drag's end is told to each slider showing, the list's block and the remote alike, which share the session's
     * volume: one still showing waits its second for the re-read, whichever slider was dragged.
     */
    @Test
    fun `each drag's end is told to every slider that shows`() {
        val volume = volume()
        assertEquals(0, volume.ends.value)
        volume.drag(60f)
        assertEquals(0, volume.ends.value, "not while the finger is down")
        volume.released()
        assertEquals(1, volume.ends.value)
        volume.drag(65f)
        volume.released()
        assertEquals(2, volume.ends.value)
    }

    /** A keyboard's or a D-pad's Right and Left: one step each from what the slider shows, sent once, none past the ends. */
    @Test
    fun `a key moves it one step, sent as a drag to it is`() {
        val volume = volume()
        volume.step(1)
        assertEquals(45, volume.percent.value)
        volume.released()
        volume.step(-1)
        volume.step(-1)
        volume.released()
        assertEquals(35, volume.percent.value)
        assertEquals(listOf(9, 8, 7), sent)
        session = CastVolumeNow(20, muted = false)
        val full = volume()
        full.step(1)
        full.released()
        assertEquals(100, full.percent.value)
        assertEquals(listOf(9, 8, 7), sent, "nothing past the top")
    }

    @Test
    fun `a re-read while a finger is down again, or with no session, changes nothing`() {
        val volume = volume()
        volume.drag(40f)
        volume.drag(60f)
        volume.released()
        volume.drag(65f)
        volume.reread()
        assertEquals(65, volume.percent.value)
        assertFalse(volume.refused.value)
        volume.released()
        session = null
        volume.reread()
        assertEquals(65, volume.percent.value)
        assertFalse(volume.refused.value)
    }

    /** M1-A: the slider takes drags wherever the session's player can set the device's volume, the route's flag aside. */
    @Test
    fun `it takes drags where the player can set the device's volume`() {
        assertTrue(volume().settable)
        settable = false
        assertFalse(volume().settable)
    }

    @Test
    fun `the device's own volume words`() {
        assertEquals("Den TV sets its own volume: use its own remote.", ownVolume("Den TV"))
        assertEquals(1_000L, CastVolume.REREAD_MS)
    }
}
