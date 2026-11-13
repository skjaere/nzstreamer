package io.skjaere.usenet.app.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * L91: what the bar, the remote and the Show page follow is the cast while one lives, else the play this phone started on
 * a TV, and each button goes to the play shown; L93: the line said over the page is the last either said. The plays are
 * stand-ins; the devices invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemotePlaysTest {
    private class Shown(override val kind: RemoteKind, override val device: String) : RemoteNow {
        override val title = "Harbor Lights"
        override val subtitle = null
        override val watchKey = null
        override val shows = RemoteBar.Playing
        override val bar = RemoteBar.Playing
        override val positionMs = 0L
        override val durationMs = null
        override val heardAt = 0L
        override val art = null
        override val live = true
        override val moving = false
    }

    private class Stand(private val name: String, private val sent: MutableList<String>) : RemotePlaying {
        val shown = MutableStateFlow<RemoteNow?>(null)
        override val now: StateFlow<RemoteNow?> = shown
        val line = MutableStateFlow<RemoteSaid?>(null)
        override val said: StateFlow<RemoteSaid?> = line

        override fun position(now: RemoteNow) = 0L

        override fun pause() {
            sent += "$name pause"
        }

        override fun resume() {
            sent += "$name play"
        }

        override fun stop() {
            sent += "$name stop"
        }

        override fun seekTo(positionMs: Long) {
            sent += "$name seek to $positionMs"
        }

        override fun seekBy(deltaMs: Long) {
            sent += "$name seek by $deltaMs"
        }

        override fun dismiss() {
            sent += "$name put away"
        }

        override fun act(said: RemoteSaid) {
            sent += "$name act ${said.id}"
        }

        override fun left(said: RemoteSaid) {
            sent += "$name left ${said.id}"
        }
    }

    /** L125: a line's button, and another screen opening under it, go to the play whose line it is, by its device's mark. */
    @Test
    fun `a line's button and a screen opening go to the play that said it`() = runTest {
        val sent = mutableListOf<String>()
        val plays = RemotePlays(backgroundScope, Stand("tv", sent), Stand("cast", sent))
        val watched = RemoteSaid("Watched on Kitchen display", 7, RemoteKind.Cast)
        val stopped = RemoteSaid("Stopped on Living room TV at 32:10", 3, RemoteKind.Tv)
        plays.act(watched)
        plays.left(watched)
        plays.left(stopped)
        assertEquals(listOf("cast act 7", "cast left 7", "tv left 3"), sent)
    }

    @Test
    fun `the cast while one lives, else the TV's play, each button to the one shown`() = runTest {
        val sent = mutableListOf<String>()
        val tv = Stand("tv", sent)
        val cast = Stand("cast", sent)
        val plays = RemotePlays(backgroundScope, tv, cast)
        runCurrent()
        assertNull(plays.now.value)
        tv.shown.value = Shown(RemoteKind.Tv, "Living room TV")
        runCurrent()
        assertEquals("Living room TV", plays.now.value?.device)
        plays.pause()
        cast.shown.value = Shown(RemoteKind.Cast, "Kitchen display")
        runCurrent()
        assertEquals("Kitchen display", plays.now.value?.device, "the cast while one lives")
        plays.pause()
        plays.seekBy(10_000)
        plays.stop()
        cast.shown.value = null
        runCurrent()
        assertEquals("Living room TV", plays.now.value?.device, "the TV's again")
        plays.resume()
        assertEquals(listOf("tv pause", "cast pause", "cast seek by 10000", "cast stop", "tv play"), sent)
    }

    /**
     * Each play's own buttons, for what is held for a moment (the remote's burst of ±10 s taps): the TV's for the TV's
     * play, the cast's for the cast's, whichever is shown by then. A play followed alone is its own.
     */
    @Test
    fun `each play's buttons are its own, whichever is shown`() = runTest {
        val tv = Stand("tv", mutableListOf())
        val cast = Stand("cast", mutableListOf())
        val plays = RemotePlays(backgroundScope, tv, cast)
        val onTv = Shown(RemoteKind.Tv, "Living room TV")
        val casting = Shown(RemoteKind.Cast, "Kitchen display")
        cast.shown.value = casting
        runCurrent()
        assertEquals(tv, plays.playOf(onTv), "the TV's, though the cast is shown")
        assertEquals(cast, plays.playOf(casting))
        assertEquals(tv, RemotePlays(backgroundScope, tv, null).playOf(casting), "without Cast, the TV's alone")
        assertEquals(tv, tv.playOf(onTv))
    }

    @Test
    fun `a build or a device without Cast follows the TV's alone, and a TV follows nothing`() = runTest {
        val tv = Stand("tv", mutableListOf())
        val plays = RemotePlays(backgroundScope, tv, null)
        assertEquals(tv.now, plays.now)
        assertNull(NoRemote.now.value)
    }

    /** L93, item 27: one line over the page, the last either said, by its device's mark; it goes as its own goes. */
    @Test
    fun `the line said is the last the cast or the TV said, gone as that one goes`() = runTest {
        val tv = Stand("tv", mutableListOf())
        val cast = Stand("cast", mutableListOf())
        val plays = RemotePlays(backgroundScope, tv, cast)
        runCurrent()
        assertNull(plays.said.value)
        val stopped = RemoteSaid("Stopped on Living room TV at 32:10", 1, RemoteKind.Tv)
        tv.line.value = stopped
        runCurrent()
        assertEquals(stopped, plays.said.value)
        val watched = RemoteSaid("Watched on Kitchen display", 1, RemoteKind.Cast)
        cast.line.value = watched
        runCurrent()
        assertEquals(watched, plays.said.value, "the last said")
        tv.line.value = null
        runCurrent()
        assertEquals(watched, plays.said.value, "the TV's going takes nothing of the cast's")
        cast.line.value = null
        runCurrent()
        assertNull(plays.said.value)
        assertEquals(tv.said, RemotePlays(backgroundScope, tv, null).said, "without Cast, the TV's alone")
        assertNull(NoRemote.said.value)
    }
}
