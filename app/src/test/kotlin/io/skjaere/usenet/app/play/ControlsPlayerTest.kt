package io.skjaere.usenet.app.play

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ControlsPlayerTest {
    private var clock = 1_000_000L
    /** What the player was asked to do, in order. */
    private val calls = mutableListOf<String>()
    /** Where the player says it is, and its state: a seek takes it out of the end, as ExoPlayer's do. */
    private var position = 60_000L
    private var state = Player.STATE_READY
    /** The listeners added to the player itself. */
    private val heardByPlayer = mutableListOf<Player.Listener>()
    /** The commands the player itself can't do now. */
    private val unavailable = mutableSetOf<Int>()

    /**
     * A player that can do everything (but [unavailable]), a minute into a ten-minute file with
     * 20 s buffered, and media3's own increments (5 s back, 15 s forward), so a step can't pass for
     * the other's; Previous goes to the item before within its first 3 s, as ExoPlayer's does.
     */
    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
        when (method.name) {
            "isCommandAvailable" -> args[0] !in unavailable
            "getAvailableCommands" -> Player.Commands.EMPTY
            "getMaxSeekToPreviousPosition" -> C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS
            "getCurrentPosition", "getContentPosition" -> position
            "getBufferedPosition", "getContentBufferedPosition" -> 80_000L
            "getDuration" -> 600_000L
            "getPlaybackState" -> state
            "getSeekBackIncrement" -> 5_000L
            "getSeekForwardIncrement" -> 15_000L
            "seekTo" -> {
                calls += "seekTo ${args.last()}"
                if (state == Player.STATE_ENDED) state = Player.STATE_BUFFERING
            }
            "seekToDefaultPosition", "seekToPrevious", "seekToNext" -> calls += method.name
            "addListener" -> heardByPlayer += args[0] as Player.Listener
            "play", "pause" -> calls += method.name
            "setPlayWhenReady" -> calls += "setPlayWhenReady ${args[0]}"
            else -> when (method.returnType) {
                java.lang.Long.TYPE -> 0L
                Integer.TYPE -> 0
                java.lang.Boolean.TYPE -> false
                else -> null
            }
        }
    } as Player

    /** What the label says of each tap. */
    private fun shown(said: MutableList<String> = mutableListOf()) = ControlsPlayer(player, now = { clock }, onBurst = { said += it.text })

    /** [ms] on by the clock, and the burst's seek run if it is due, as the player's looper would. */
    private fun ControlsPlayer.wait(ms: Long) {
        clock += ms
        update()
    }

    // Player.Commands sits on android.util.SparseBooleanArray, a stub here: TrackSheetDeviceTest checks availableCommands.
    @Test
    fun `media3's own controls see no track selection and no playback speed`() {
        val shown = shown()
        assertFalse(shown.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS))
        assertFalse(shown.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH))
        assertTrue(shown.isCommandAvailable(Player.COMMAND_GET_TRACKS))
    }

    @Test
    fun `rewind and fast-forward go by the player's own steps and show where they go at once, without seeking yet`() {
        val said = mutableListOf<String>()
        val shown = shown(said)
        assertEquals(5_000, shown.seekBackIncrement)
        assertEquals(15_000, shown.seekForwardIncrement)
        shown.seekForward()
        shown.seekForward()
        shown.seekBack()
        assertEquals(85_000, shown.currentPosition)
        assertEquals(85_000, shown.contentPosition)
        assertEquals(listOf("+15 s", "+30 s", "+25 s"), said)
        assertEquals(emptyList(), calls)
        shown.flush()
        assertEquals(listOf("seekTo 85000"), calls)
        assertEquals(60_000, shown.currentPosition)
    }

    /** A remote's Left or Right held with the controls hidden: steps of any size join the burst, said where the caller asks. */
    @Test
    fun `steps of any size join the burst, and say themselves where asked`() {
        val said = mutableListOf<String>()
        val quiet = mutableListOf<String>()
        val shown = shown(said)
        shown.step(10_000) { quiet += it.text }
        shown.step(20_000) { quiet += it.text }
        shown.seekBack()
        shown.step(-45_000) { quiet += it.text }
        assertEquals(40_000, shown.currentPosition)
        assertEquals(listOf("+10 s", "+30 s", "\u221220 s"), quiet)
        assertEquals(listOf("+25 s"), said)
        assertEquals(emptyList(), calls)
        shown.flush()
        assertEquals(listOf("seekTo 40000"), calls)
        // As the player goes, a step seeks nowhere.
        shown.hold()
        shown.step(10_000) { quiet += it.text }
        shown.flush()
        assertEquals(listOf("seekTo 40000"), calls)
        assertEquals(3, quiet.size)
    }

    /** A step waits as long as it asks before it seeks: touch can wait longer for the next tap. */
    @Test
    fun `a step's seek waits as long as it asks`() {
        val shown = shown()
        shown.step(10_000, waitMs = 800) {}
        assertEquals(800, shown.dueInMs)
        shown.wait(799)
        assertEquals(emptyList(), calls)
        shown.wait(1)
        assertEquals(listOf("seekTo 70000"), calls)
        assertEquals(null, shown.dueInMs)
        // By default, the window.
        shown.step(10_000) {}
        assertEquals(SeekBurst.WINDOW_MS, shown.dueInMs)
    }

    /** A remote's key held: its steps join one burst that waits for the key's up, then seeks once, the up's wait later. */
    @Test
    fun `held open, a key's steps seek once, after its up`() {
        val said = mutableListOf<String>()
        val shown = shown()
        shown.step(10_000) { said += it.text }
        shown.holdOpen()
        shown.wait(600)
        shown.step(20_000) { said += it.text }
        shown.holdOpen()
        shown.wait(600)
        assertEquals(emptyList(), calls)
        assertEquals(90_000, shown.currentPosition)
        shown.settle(1_000)
        shown.wait(999)
        assertEquals(emptyList(), calls)
        shown.wait(1)
        assertEquals(listOf("seekTo 90000"), calls)
        assertEquals(listOf("+10 s", "+30 s"), said)
        // A key's up lost: it seeks all the same, a while after the key's last event.
        shown.step(10_000) {}
        shown.holdOpen()
        shown.wait(SeekBurst.HOLD_MS)
        assertEquals(listOf("seekTo 90000", "seekTo 70000"), calls)
    }

    /** Play or pause while a key is down (OK pressed meanwhile): the seek first, as for any burst; the up after seeks nothing more. */
    @Test
    fun `held open, play and pause still seek first`() {
        val shown = shown()
        shown.step(10_000) {}
        shown.holdOpen()
        shown.pause()
        assertEquals(listOf("seekTo 70000", "pause"), calls)
        shown.settle(1_000)
        shown.wait(1_000)
        assertEquals(listOf("seekTo 70000", "pause"), calls)
        // As the player goes, a key held or let go seeks nowhere.
        shown.step(10_000) {}
        shown.hold()
        shown.holdOpen()
        shown.settle(500)
        shown.wait(SeekBurst.HOLD_MS)
        assertEquals(listOf("seekTo 70000", "pause"), calls)
    }

    /** Meanwhile the skip button and the next-episode card leave a remote's focus where it is. */
    @Test
    fun `it says while a burst's seek waits`() {
        val shown = shown()
        assertFalse(shown.seeking)
        shown.step(10_000) {}
        shown.seekForward()
        assertTrue(shown.seeking)
        shown.flush()
        assertFalse(shown.seeking)
        shown.seekBack()
        assertTrue(shown.seeking)
        // Replaced by the time bar's seek.
        shown.seekTo(30_000)
        assertFalse(shown.seeking)
    }

    @Test
    fun `while a seek waits the time bar shows nothing buffered past a target outside the buffer`() {
        val shown = shown()
        shown.seekForward()
        // 75 s is inside what the player has buffered, up to 80 s.
        assertEquals(80_000, shown.bufferedPosition)
        shown.seekForward()
        assertEquals(90_000, shown.bufferedPosition)
        assertEquals(90_000, shown.contentBufferedPosition)
        repeat(4) { shown.seekBack() }
        assertEquals(70_000, shown.currentPosition)
        assertEquals(80_000, shown.bufferedPosition)
        // Behind the position, where the player keeps nothing.
        repeat(3) { shown.seekBack() }
        assertEquals(55_000, shown.bufferedPosition)
    }

    @Test
    fun `play and pause seek first`() {
        val shown = shown()
        shown.seekForward()
        shown.pause()
        shown.seekBack()
        shown.play()
        shown.seekForward()
        shown.playWhenReady = false
        assertEquals(listOf("seekTo 75000", "pause", "seekTo 55000", "play", "seekTo 75000", "setPlayWhenReady false"), calls)
    }

    /**
     * Ended, media3's controls take Play for a replay from the start (a seek to the default
     * position, then play) and drop fast-forward taps: a tap there seeks at once, so the player
     * plays on from it, and the burst goes on.
     */
    @Test
    fun `after the end a tap seeks at once, and Play goes on from where the burst goes`() {
        position = 600_000
        state = Player.STATE_ENDED
        val said = mutableListOf<String>()
        val shown = shown(said)
        shown.seekBack()
        assertEquals(listOf("seekTo 595000"), calls)
        assertEquals(Player.STATE_BUFFERING, shown.playbackState)
        shown.seekBack()
        assertEquals(590_000, shown.currentPosition)
        assertEquals(listOf("\u22125 s", "\u221210 s"), said)
        Util.handlePlayButtonAction(shown)
        assertEquals(listOf("seekTo 595000", "seekTo 590000", "play"), calls)
    }

    @Test
    fun `after the end a burst back where its first tap went seeks no more`() {
        position = 600_000
        state = Player.STATE_ENDED
        val shown = shown()
        repeat(4) { shown.seekBack() }
        shown.seekForward()
        Util.handlePlayButtonAction(shown)
        assertEquals(listOf("seekTo 595000", "play"), calls)
    }

    @Test
    fun `the file ending while a burst waits has its seek run at once`() {
        val shown = shown()
        shown.seekBack()
        state = Player.STATE_ENDED
        heardByPlayer.toList().forEach { it.onPlaybackStateChanged(Player.STATE_ENDED) }
        assertEquals(listOf("seekTo 55000"), calls)
        assertEquals(Player.STATE_BUFFERING, shown.playbackState)
        shown.flush()
        assertEquals(listOf("seekTo 55000"), calls)
    }

    /**
     * The player waits at its end only for a burst's seek still to come (EndOfPlay): there is none for fast-forward tapped
     * in the file's last second or at its end, which goes nowhere, nor for a key held over the end once the end has sent
     * its seek to the last second and that has run out too, the key still down. Nor once the player goes.
     */
    @Test
    fun `ended, a burst that goes nowhere has no seek to come`() {
        position = 599_500
        val shown = shown()
        shown.seekForward()
        assertTrue(shown.seeking)
        assertFalse(shown.seekToCome, "fast-forward from the file's last second")
        ended()
        shown.wait(SeekBurst.WINDOW_MS)
        assertEquals(emptyList(), calls)
        assertFalse(shown.seeking)
        // From the end itself (the window's +10 s while the next episode is looked up): nowhere either.
        position = 600_000
        shown.seekForward()
        assertEquals(emptyList(), calls)
        assertFalse(shown.seekToCome, "fast-forward from the end")
        shown.wait(SeekBurst.WINDOW_MS)
        // Held from 3 s before the end: as the file ends the seek runs, to its last second; that runs out, the key still down.
        position = 597_000
        state = Player.STATE_READY
        shown.step(10_000) {}
        shown.holdOpen()
        assertTrue(shown.seekToCome)
        position = 600_000
        ended()
        assertEquals(listOf("seekTo 599000"), calls)
        ended()
        shown.step(5_000) {}
        shown.holdOpen()
        assertEquals(listOf("seekTo 599000"), calls)
        assertTrue(shown.seeking)
        assertFalse(shown.seekToCome, "ended again in the file's last second, the key still down")
        // A minute in, a rewind is one to come, until the player goes: then nothing seeks any more.
        position = 60_000
        state = Player.STATE_READY
        val going = shown()
        going.seekBack()
        assertTrue(going.seekToCome)
        going.hold()
        assertFalse(going.seekToCome, "held as the player goes")
    }

    /** The file ends: the player says so to its own listeners, ControlsPlayer's among them. */
    private fun ended() {
        state = Player.STATE_ENDED
        heardByPlayer.toList().forEach { it.onPlaybackStateChanged(Player.STATE_ENDED) }
    }

    @Test
    fun `a seek from elsewhere, the time bar's, replaces the waiting one`() {
        val shown = shown()
        shown.seekForward()
        shown.seekForward()
        shown.seekTo(5_000)
        shown.flush()
        assertEquals(listOf("seekTo 5000"), calls)
    }

    @Test
    fun `held as the player goes, nothing seeks and the target stays where the play is`() {
        val shown = shown()
        shown.seekForward()
        shown.hold()
        shown.flush()
        shown.pause()
        assertEquals(listOf("pause"), calls)
        assertEquals(75_000, shown.currentPosition)
    }

    // The episodes either side of the one playing, which the player looks up and sets.

    @Test
    fun `next is offered while there is a next episode, and plays it at once`() {
        // As ExoPlayer has it with one file: nothing after it.
        unavailable += Player.COMMAND_SEEK_TO_NEXT
        val shown = shown()
        assertFalse(shown.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        var played = 0
        shown.playNext = { played++ }
        assertTrue(shown.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        // Mid-burst too: nothing seeks first, and where the burst goes is the position the player saves as it goes.
        shown.seekForward()
        shown.seekToNext()
        assertEquals(1, played)
        assertEquals(emptyList(), calls)
        assertEquals(75_000, shown.currentPosition)
        // The lookup gone (a new play): none any more.
        shown.playNext = null
        assertFalse(shown.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
    }

    @Test
    fun `previous starts the episode again after its first 3 s, and within them plays the one before`() {
        val shown = shown()
        var before = 0
        shown.playPrevious = { before++ }
        // A minute in: from the start again.
        shown.seekToPrevious()
        assertEquals(listOf("seekTo 0"), calls)
        assertEquals(0, before)
        // Just started again: a second press plays the episode before, and seeks nowhere.
        position = 400
        shown.seekToPrevious()
        assertEquals(1, before)
        position = 3_000
        shown.seekToPrevious()
        assertEquals(2, before)
        position = 3_001
        shown.seekToPrevious()
        assertEquals(listOf("seekTo 0", "seekTo 0"), calls)
        assertEquals(2, before)
    }

    @Test
    fun `previous goes by the position the controls show, where a burst of taps is going`() {
        val shown = shown()
        var before = 0
        shown.playPrevious = { before++ }
        // A minute in, rewound to 2 s: the episode before.
        shown.step(-58_000) {}
        shown.seekToPrevious()
        assertEquals(1, before)
        assertEquals(emptyList(), calls)
        // Two seconds in, fast-forwarded to 12 s: from the start again, the burst dropped.
        val again = shown()
        position = 2_000
        again.playPrevious = { before++ }
        again.step(10_000) {}
        again.seekToPrevious()
        again.flush()
        assertEquals(listOf("seekTo 0"), calls)
        assertEquals(1, before)
    }

    @Test
    fun `without an episode before, previous always starts again, and is offered as the player offers it`() {
        val shown = shown()
        assertTrue(shown.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        shown.seekToPrevious()
        position = 1_000
        shown.seekToPrevious()
        assertEquals(listOf("seekTo 0", "seekTo 0"), calls)
        // Before the file is open the player has nothing to go back in; an episode before is there all the same.
        unavailable += Player.COMMAND_SEEK_TO_PREVIOUS
        assertFalse(shown.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        shown.playPrevious = {}
        assertTrue(shown.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    /** The controls enable their buttons as they hear: Player.Events itself is a stub here (SparseBooleanArray), so only that they heard. */
    @Test
    fun `the controls hear as the episodes either side become known`() {
        val heard = mutableListOf<Player>()
        val shown = shown()
        shown.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                heard += player
            }
        })
        shown.playNext = {}
        shown.playPrevious = {}
        assertEquals(listOf<Player>(shown, shown), heard)
    }

    @Test
    fun `the controls hear of each tap, as of a jump in the position`() {
        val heard = mutableListOf<Player>()
        val shown = shown()
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                heard += player
            }
        }
        shown.addListener(listener)
        shown.seekForward()
        shown.seekBack()
        assertEquals(2, heard.size)
        heard.forEach { assertSame(shown, it) }
        shown.removeListener(listener)
        shown.seekForward()
        assertEquals(2, heard.size)
    }
}
