package io.skjaere.usenet.app.play

import androidx.media3.common.C
import io.skjaere.usenet.player.ModeSize
import io.skjaere.usenet.player.Passthrough
import io.skjaere.usenet.player.tv.ScreenMode
import io.skjaere.usenet.player.tv.ScreenModes
import io.skjaere.usenet.releases.Hdr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wait for a frame-rate switch (TV-7's L7) over a display that does what a test says, on the Shield's 2160p modes
 * and ids (B0's dumpsys): asked after the moment's line has shown, listening first; played once the TV runs the mode
 * asked for and has settled 2 s, the HDMI audio read before the request is back and, for a Dolby Vision file on a
 * Dolby Vision TV, Dolby Vision is back; at the latest at the caps; at once on OK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DisplayModeSwitcherTest {
    private val uhd = ModeSize(3840, 2160)
    private val film = ScreenMode(23, uhd, 23.976025f)
    private val tv = ScreenMode(41, uhd, 59.94006f)
    private val modes = listOf(ScreenMode(22, uhd, 29.97003f), film, ScreenMode(31, uhd, 24f), ScreenMode(30, uhd, 25f), ScreenMode(38, uhd, 50f), ScreenMode(40, uhd, 60f), tv)
    private val dd = Passthrough(setOf(C.ENCODING_AC3, C.ENCODING_E_AC3))

    /** The window and the display: the window's asks, the display's changes as the test makes them. */
    private inner class Port(private val scope: TestScope) : DisplayPort {
        var active = tv
        val asked = mutableListOf<Int>()
        /** How many listened as each ask was made. */
        val listeningAtAsk = mutableListOf<Int>()
        val listeners = mutableListOf<(Int) -> Unit>()
        var hdr: Set<Hdr>? = setOf(Hdr.Hdr10)
        var route: Passthrough? = Passthrough.NONE
        /** What asking does to the rest: an HDMI re-sync drops the audio route or Dolby Vision for a while. */
        var onAsk: () -> Unit = {}

        override fun read() = ScreenModes(30, active, modes, null, uhd, outputFromTv = true)

        override var preferredModeId: Int = 0
            set(value) {
                field = value
                asked += value
                listeningAtAsk += listeners.size
                onAsk()
            }

        override fun listen(changed: (Int) -> Unit): AutoCloseable {
            listeners += changed
            return AutoCloseable { listeners -= changed }
        }

        override fun hdr() = hdr

        override fun passthrough() = route

        override fun now() = scope.testScheduler.currentTime

        /** The display runs [mode] now; or, with the same mode, its HDR changed. */
        fun changed(mode: ScreenMode = active) {
            active = mode
            listeners.toList().forEach { it(mode.id) }
        }

        /** After [ms] from now, [then]. */
        fun after(ms: Long, then: () -> Unit) = scope.launch {
            delay(ms)
            then()
        }
    }

    private val timings = DisplayModeSwitcher.Timings()

    /** Switches [port] to the film's mode, from now; [result] waits for the line it logs. */
    private fun TestScope.switch(port: Port, dolbyVision: Boolean = false, playNow: CompletableDeferred<Unit> = CompletableDeferred()) =
        async { DisplayModeSwitcher(port, timings).switchTo(film, dolbyVision, playNow) }

    @Test
    fun `it asks once the moment has shown, listening first, and plays once the TV has switched and settled`() = runTest {
        val port = Port(this)
        val done = switch(port)
        advanceTimeBy(399)
        runCurrent()
        assertEquals(emptyList(), port.asked, "asked before the moment's line was seen")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(23), port.asked)
        assertEquals(listOf(1), port.listeningAtAsk, "the display listened to only after the ask")
        port.after(1_400) { port.changed(film) }
        advanceTimeBy(1_400 + 1_999)
        runCurrent()
        assertFalse(done.isCompleted, "played before the TV settled")
        advanceTimeBy(1)
        runCurrent()
        assertTrue(done.isCompleted)
        assertEquals("Display switched in 1.4 s, settled", done.await())
        assertEquals(400 + 1_400 + 2_000, currentTime)
        assertEquals(emptyList(), port.listeners, "still listening")
    }

    /** onDisplayChanged comes for HDR's changes and a TV waking too: only the mode asked for ends the wait. */
    @Test
    fun `a change that isn't to the mode asked for doesn't end the wait`() = runTest {
        val port = Port(this)
        val done = switch(port)
        port.after(400 + 1_000) { port.changed() }
        port.after(400 + 2_000) { port.changed(film) }
        advanceUntilIdle()
        assertEquals("Display switched in 2 s, settled", done.await())
        assertEquals(400 + 2_000 + 2_000, currentTime)
    }

    @Test
    fun `a display that never switches is played on after 5 s, at its own rate`() = runTest {
        val port = Port(this)
        val done = switch(port)
        advanceUntilIdle()
        assertEquals("Display didn't switch within 5 s; playing at 59.94 Hz", done.await())
        assertEquals(400 + 5_000, currentTime)
        assertEquals(listOf(23), port.asked)
        assertEquals(emptyList(), port.listeners)
    }

    /** The Shield re-syncs its HDMI as it switches: what passed through before the request is waited for, up to 7 s. */
    @Test
    fun `the HDMI audio passed through before the request is waited for, up to the cap`() = runTest {
        val port = Port(this).apply { route = dd }
        port.onAsk = {
            port.route = Passthrough.NONE
            port.after(300) { port.changed(film) }
            port.after(3_000) { port.route = dd }
        }
        val back = switch(port)
        advanceUntilIdle()
        assertEquals("Display switched in 0.3 s, settled; passthrough back", back.await())
        // Past the settle's end (2.3 s), as soon as the route is back, within a poll.
        assertTrue(currentTime - 400 in 3_000..3_000 + timings.pollMs, "${currentTime - 400}")

        val lost = Port(this).apply { route = dd }
        lost.onAsk = {
            lost.route = Passthrough.NONE
            lost.after(300) { lost.changed(film) }
        }
        val start = currentTime
        val never = switch(lost)
        advanceUntilIdle()
        assertEquals("Display switched in 0.3 s, settled; passthrough not back within 7 s", never.await())
        assertEquals(400 + 7_000, currentTime - start)
    }

    /** A device that passes nothing through (a phone, the emulators) has nothing to wait for. */
    @Test
    fun `nothing passed through before, nothing waited for`() = runTest {
        val port = Port(this).apply { route = Passthrough.NONE }
        port.onAsk = { port.after(300) { port.changed(film) } }
        val done = switch(port)
        advanceUntilIdle()
        assertEquals("Display switched in 0.3 s, settled", done.await())
        assertEquals(400 + 300 + 2_000, currentTime)
    }

    /**
     * A Dolby Vision file on a TV that showed Dolby Vision before the request: played once Dolby Vision is back (Media3
     * picks the decoder by it as the player is made), up to 10 s from the request.
     */
    @Test
    fun `a Dolby Vision file waits for Dolby Vision, up to 10 s`() = runTest {
        val port = Port(this).apply { hdr = setOf(Hdr.DolbyVision, Hdr.Hdr10) }
        port.onAsk = {
            port.hdr = setOf(Hdr.Hdr10)
            port.after(300) { port.changed(film) }
            port.after(8_000) { port.hdr = setOf(Hdr.DolbyVision, Hdr.Hdr10) }
        }
        val back = switch(port, dolbyVision = true)
        advanceUntilIdle()
        assertEquals("Display switched in 0.3 s, settled; HDR back", back.await())
        assertTrue(currentTime - 400 in 8_000..8_000 + timings.pollMs, "${currentTime - 400}")

        val lost = Port(this).apply { hdr = setOf(Hdr.DolbyVision, Hdr.Hdr10) }
        lost.onAsk = {
            lost.hdr = setOf(Hdr.Hdr10)
            lost.after(300) { lost.changed(film) }
        }
        val start = currentTime
        val never = switch(lost, dolbyVision = true)
        advanceUntilIdle()
        assertEquals("Display switched in 0.3 s, settled; Dolby Vision not back within 10 s", never.await())
        assertEquals(400 + 10_000, currentTime - start)
    }

    /** Only a Dolby Vision file on a TV that showed it waits for it: a TV without it, or another file, doesn't. */
    @Test
    fun `no Dolby Vision wait for another file, nor on a TV without it`() = runTest {
        listOf(false to setOf(Hdr.DolbyVision, Hdr.Hdr10), true to setOf(Hdr.Hdr10)).forEach { (dolbyVision, before) ->
            val port = Port(this).apply { hdr = before }
            port.onAsk = {
                port.hdr = setOf(Hdr.Hdr10)
                port.after(300) { port.changed(film) }
            }
            val start = currentTime
            val done = switch(port, dolbyVision)
            advanceUntilIdle()
            assertEquals("Display switched in 0.3 s, settled", done.await())
            assertEquals(400 + 300 + 2_000, currentTime - start, "a Dolby Vision file: $dolbyVision")
        }
    }

    /** OK during the moment: whatever wait is left is skipped; the mode is still asked for. */
    @Test
    fun `OK plays at once, whatever is left of the wait`() = runTest {
        // While it settles.
        val settling = Port(this)
        settling.onAsk = { settling.after(1_400) { settling.changed(film) } }
        val ok = CompletableDeferred<Unit>()
        val done = switch(settling, playNow = ok)
        advanceTimeBy(400 + 1_400 + 500)
        runCurrent()
        ok.complete(Unit)
        advanceUntilIdle()
        assertEquals("Display switched in 1.4 s; OK pressed: playing at once", done.await())
        assertEquals(400 + 1_400 + 500, currentTime)

        // Before the TV switched.
        val waiting = Port(this)
        val early = CompletableDeferred<Unit>()
        var start = currentTime
        val before = switch(waiting, playNow = early)
        advanceTimeBy(400 + 800)
        runCurrent()
        early.complete(Unit)
        advanceUntilIdle()
        assertEquals("Display not switched after 0.8 s; OK pressed: playing at once", before.await())
        assertEquals(400 + 800, currentTime - start)

        // Before it was asked: asked all the same, played at once.
        val asking = Port(this)
        val first = CompletableDeferred<Unit>()
        start = currentTime
        val atOnce = switch(asking, playNow = first)
        advanceTimeBy(100)
        runCurrent()
        first.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(23), asking.asked)
        assertEquals("Display not switched after 0 s; OK pressed: playing at once", atOnce.await())
        assertEquals(100, currentTime - start)
        assertEquals(emptyList(), asking.listeners)
    }

    /** Back or Home during the wait cancels the player's setup: the display isn't listened to any more. */
    @Test
    fun `cancelled during the wait, it stops listening`() = runTest {
        val port = Port(this)
        val done = switch(port)
        advanceTimeBy(400 + 1_000)
        runCurrent()
        assertEquals(1, port.listeners.size)
        done.cancel()
        advanceUntilIdle()
        assertEquals(emptyList(), port.listeners)
        assertTrue(done.isCancelled)
    }
}
