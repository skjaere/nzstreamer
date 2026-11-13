package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.VideoTiming
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Missing parts during a cast (W37, L94, L104; CsPhoneMissingParts), as the cast play the bar and the remote follow says
 * them: the stretches of zeros the phone's server sent the device count as the player counts its own (three within ten
 * minutes of film, then six and twelve), each placed by its share of the file; the prompt waits while the app isn't in
 * front; the one-part notice comes once, as the play gets to each; and a device stuck at one is sent past it, then asked
 * about. On the test's clock; the film is invented: 52 minutes in a file of a thousand bytes a millisecond.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastMissingPartsTest {
    private val kitchen = CastTo("route:kitchen", "Kitchen display")
    private val length = 3_120_000L
    private val size = 3_120_000_000L
    private val release = SavedRelease("fake", "harbor-lights", "Harbor.Lights.S02E04.1080p.WEB-DL.H.264-CAST", null, 0, "nzb-hl", "hl.mkv", size)
    private val episode = WatchTarget(WatchKind.Episode, WatchTitle.episode("Harbor Lights", 2, 4), showTmdbId = 4_101, season = 2, episode = 4)
    private val launch = PlayerLaunch(
        UsenetUris.build("nzb-hl", 0, release.fileName, release.fileSize), "Harbor Lights", "S02E04 · Low Water", WatchKey.episode(4_101, 2, 4),
        episode, release, video = VideoTiming(fps = 24.0, durationMs = length), cast = kitchen,
    )

    /** A second of missing articles [atMs] into the film. */
    private fun gapAt(atMs: Long) = atMs * 1_000 until (atMs + 1_000) * 1_000

    private inner class Fixture(private val test: TestScope) {
        val feed = MutableStateFlow<CastFeed?>(null)
        val link = MutableStateFlow<CastLinkNow?>(CastLinkNow(kitchen.route, kitchen.name, CastLink.Connected))
        val front = MutableStateFlow(true)
        val sent = mutableListOf<String>()
        val tried = mutableListOf<Pair<PlayerLaunch, Long>>()
        var another = true

        /** How often the coordinator was asked whether there is another release: once a tick. */
        var asked = 0
        val playing = CastPlaying(
            test.backgroundScope,
            CastPlayingDeps(
                feed = feed, link = link, pause = { sent += "pause" }, resume = { sent += "play" }, seekTo = { sent += "seek to $it" },
                stop = { sent += "stop casting" }, clock = { test.testScheduler.currentTime }, inFront = front,
                tryAnother = { launch, at -> tried += launch to at }, canTryAnother = { asked++; another },
            ),
        )

        val prompt: CastPrompt? get() = playing.missing.value

        /** The device's player says [state] at [positionMs] now, the server having sent [gaps] and gone on past [passed] of them. */
        fun device(state: CastPlayerState, positionMs: Long, gaps: List<LongRange> = emptyList(), passed: List<LongRange> = gaps, reported: Long = positionMs) {
            val now = test.testScheduler.currentTime
            feed.value = CastFeed("load-1", launch, state, positionMs, length, reported = reported, reportedAt = now, size = size, gaps = gaps, passed = passed)
            test.runCurrent()
        }

        /** [ms] go by. */
        fun pass(ms: Long) {
            test.advanceTimeBy(ms)
            test.runCurrent()
        }
    }

    @Test
    fun `three gaps sent within ten minutes of film ask, by the player's words with the cast's line`() = runTest {
        val f = Fixture(this)
        f.device(CastPlayerState.Playing, 1_900_000, gaps = listOf(gapAt(1_950_000), gapAt(2_000_000)))
        f.pass(1_000)
        assertNull(f.prompt, "two so far")
        f.device(CastPlayerState.Playing, 1_901_000, gaps = listOf(gapAt(1_950_000), gapAt(2_000_000), gapAt(2_400_000)))
        f.pass(1_000)
        val prompt = assertNotNull(f.prompt)
        assertEquals(CastPrompt(3, tryAnother = true, stuck = false, device = "Kitchen display"), prompt)
        assertEquals("Kitchen display may show glitches.", prompt.line)
        // Keep watching: it asks again at six.
        f.playing.keepWatching()
        assertNull(f.prompt)
        val six = listOf(1_950_000L, 2_000_000L, 2_400_000L, 2_500_000L, 2_600_000L, 2_700_000L).map(::gapAt)
        f.device(CastPlayerState.Playing, 1_903_000, gaps = six)
        f.pass(1_000)
        assertEquals(6, f.prompt?.count)
        // Try another release: from where it plays, with the device's play's launch; the prompt goes.
        f.playing.tryAnother()
        assertEquals(listOf(launch to 1_904_000L), f.tried)
        assertNull(f.prompt)
    }

    @Test
    fun `with no other release it is the count alone, gone after 5 s of playing`() = runTest {
        val f = Fixture(this)
        f.another = false
        f.device(CastPlayerState.Playing, 1_900_000, gaps = listOf(gapAt(1_950_000), gapAt(2_000_000), gapAt(2_050_000)))
        f.pass(500)
        assertEquals(CastPrompt(3, tryAnother = false, stuck = false, device = "Kitchen display"), f.prompt)
        f.pass(4_000)
        assertNotNull(f.prompt)
        f.pass(1_500)
        assertNull(f.prompt, "5 s of playing")
    }

    /** CsPhoneMissingParts: the phone locked, the prompt waits until NZStreamer is opened during the cast; its time doesn't run. */
    @Test
    fun `while the app isn't in front the prompt waits, its time not running`() = runTest {
        val f = Fixture(this)
        f.front.value = false
        f.device(CastPlayerState.Playing, 1_900_000, gaps = listOf(gapAt(1_950_000), gapAt(2_000_000), gapAt(2_050_000)))
        f.pass(60_000)
        assertEquals(3, f.prompt?.count, "it waits, a minute of playing on")
        f.front.value = true
        f.pass(19_000)
        assertNotNull(f.prompt)
        f.pass(1_500)
        assertNull(f.prompt, "unanswered, it hides after 20 s of playing in front, as Keep watching")
    }

    /** The player's "A missing part was skipped", for the cast in the remote's state line, once as the play gets to each. */
    @Test
    fun `the notice comes once as the play gets to each gap, never while the prompt shows`() = runTest {
        val f = Fixture(this)
        f.device(CastPlayerState.Playing, 1_995_000, gaps = listOf(gapAt(2_000_000)))
        f.pass(4_000)
        assertNull(f.playing.notice.value, "not there yet")
        f.pass(1_000)
        assertEquals("A missing part: Kitchen display may glitch", f.playing.notice.value)
        f.pass(3_500)
        assertNull(f.playing.notice.value, "for a moment")
        // Back before it and over it again: said once.
        f.device(CastPlayerState.Playing, 1_990_000, gaps = listOf(gapAt(2_000_000)))
        f.pass(15_000)
        assertNull(f.playing.notice.value)
        // While the prompt shows, which says more, none.
        val asked = Fixture(this)
        asked.device(CastPlayerState.Playing, 2_045_000, gaps = listOf(gapAt(1_950_000), gapAt(2_000_000), gapAt(2_050_000)))
        asked.pass(6_000)
        assertNotNull(asked.prompt)
        assertNull(asked.playing.notice.value)
    }

    /** L104: stuck at a served gap, sent past it twice (2 s, then 10 s), then asked at once, saying the device is stuck there. */
    @Test
    fun `stuck at a gap it sends the device past it, twice, then asks at once`() = runTest {
        val f = Fixture(this)
        f.another = false
        val gap = gapAt(2_000_000)
        f.device(CastPlayerState.Playing, 1_990_000, gaps = listOf(gap))
        f.pass(1_000)
        f.device(CastPlayerState.Buffering, 1_999_600, gaps = listOf(gap))
        f.pass(7_500)
        assertEquals(emptyList(), f.sent)
        f.pass(1_000)
        assertEquals(listOf("seek to 2003000"), f.sent)
        f.device(CastPlayerState.Buffering, 2_003_000, gaps = listOf(gap))
        f.pass(8_500)
        assertEquals(listOf("seek to 2003000", "seek to 2013000"), f.sent)
        assertNull(f.prompt)
        // Stuck where it was sent, past the gap's window: the same gap's.
        f.device(CastPlayerState.Buffering, 2_013_000, gaps = listOf(gap))
        f.pass(8_500)
        val prompt = assertNotNull(f.prompt, "asked at once, with one gap so far")
        assertEquals(CastPrompt(1, tryAnother = false, stuck = true, device = "Kitchen display"), prompt)
        assertEquals("Kitchen display is stuck at a missing part.", prompt.line)
        assertEquals(2, f.sent.size, "no third seek")
        // With another release to try, it offers it.
        val g = Fixture(this)
        g.device(CastPlayerState.Playing, 1_990_000, gaps = listOf(gap))
        g.pass(1_000)
        for (at in listOf(1_999_600L, 2_003_000L, 2_013_000L)) {
            g.device(CastPlayerState.Buffering, at, gaps = listOf(gap))
            g.pass(8_500)
        }
        assertEquals(CastPrompt(1, tryAnother = true, stuck = true, device = "Kitchen display"), g.prompt)
    }

    /**
     * A play whose session went is over here, though its feed never said it ended (a load given up as it started): its
     * missing parts are watched no more, the coordinator no longer asked each tick, also once the device is connected again.
     */
    @Test
    fun `a play whose session went is watched no more`() = runTest {
        val f = Fixture(this)
        f.device(CastPlayerState.Loading, 1_930_000)
        f.pass(1_000)
        assertTrue(f.asked > 0, "watched while it lives there")
        f.link.value = null
        f.pass(500)
        val asked = f.asked
        f.pass(10_000)
        assertEquals(asked, f.asked, "no more ticks")
        // The device chosen again: a new session, which this play isn't in.
        f.link.value = CastLinkNow(kitchen.route, kitchen.name, CastLink.Connected)
        f.pass(10_000)
        assertEquals(asked, f.asked, "nor in the next session")
        assertNull(f.playing.now.value)
    }

    /**
     * L104: the device waits at a gap the stream through it hasn't gone past (its provider slow with what follows), though
     * the index at the end was read to the file's end on the Resume (L96), which Served.passed doesn't count: it isn't sent
     * on, and nothing asks.
     */
    @Test
    fun `waiting at a gap the stream through it hasn't gone past it isn't sent on, the index read or not`() = runTest {
        val f = Fixture(this)
        val gap = gapAt(2_000_000)
        f.device(CastPlayerState.Playing, 1_990_000, gaps = listOf(gap), passed = emptyList())
        f.pass(1_000)
        f.device(CastPlayerState.Buffering, 2_000_200, gaps = listOf(gap), passed = emptyList())
        f.pass(30_000)
        assertEquals(emptyList(), f.sent)
        assertNull(f.prompt)
    }

    /**
     * Reconnecting, the device's statuses don't come: nothing is read as stuck meanwhile, and the time away doesn't count
     * once the session is back. Only the device's words from then on: 8 s of buffering after its first send it on.
     */
    @Test
    fun `while the session reconnects nothing counts as stuck, nor does the time away after`() = runTest {
        val f = Fixture(this)
        val gap = gapAt(2_000_000)
        f.device(CastPlayerState.Playing, 1_990_000, gaps = listOf(gap))
        f.pass(1_000)
        f.device(CastPlayerState.Buffering, 1_999_600, gaps = listOf(gap))
        f.pass(3_000)
        f.link.value = CastLinkNow(kitchen.route, kitchen.name, CastLink.Reconnecting)
        f.pass(30_000)
        assertEquals(emptyList(), f.sent)
        // Back, its last word from before the 30 s away all there is for a moment.
        f.link.value = CastLinkNow(kitchen.route, kitchen.name, CastLink.Connected)
        f.pass(1_500)
        assertEquals(emptyList(), f.sent, "the time away counted")
        // The device says where it is again, buffering there still.
        f.device(CastPlayerState.Buffering, 1_999_600, gaps = listOf(gap))
        f.pass(7_500)
        assertEquals(emptyList(), f.sent)
        f.pass(1_000)
        assertEquals(listOf("seek to 2003000"), f.sent)
    }
}
