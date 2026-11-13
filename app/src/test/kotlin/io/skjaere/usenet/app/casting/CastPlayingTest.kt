package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.home.UpNextPlay
import io.skjaere.usenet.app.link.PlayOnTimings
import io.skjaere.usenet.app.link.RemoteBar
import io.skjaere.usenet.app.link.RemoteKind
import io.skjaere.usenet.app.link.RemoteSaidAction
import io.skjaere.usenet.app.link.RemoteSeek
import io.skjaere.usenet.app.link.TvArt
import io.skjaere.usenet.app.link.barLine
import io.skjaere.usenet.app.link.barSpoken
import io.skjaere.usenet.app.link.onTvAt
import io.skjaere.usenet.app.link.playingEpisode
import io.skjaere.usenet.app.link.playingEpisodeOf
import io.skjaere.usenet.app.link.positionOn
import io.skjaere.usenet.app.link.remoteHeading
import io.skjaere.usenet.app.link.stopOn
import io.skjaere.usenet.app.link.stopWords
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.SeekBurst
import io.skjaere.usenet.app.play.VideoTiming
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.EpisodeRef
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.EpisodeWatch
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.ShowProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L91, L92 (CsPhoneBar, CsPhoneRemote): a cast play as the bar, the remote and the Show page follow it, from the feed of
 * its play on the device and the Cast session: playing, paused, loading with its position standing still, reconnecting
 * with its buttons waiting, Playback stopped after an error there, and gone as it ends; its position moving on between
 * the feed's polls while it plays; its buttons going to the device's player once each, a seek by as one seek to a place,
 * three ±10 s taps as one seek. After it (L93, CsPhoneStopped): each end's line, said once for about 4 s, a line made
 * while the app isn't in front waiting until it is. A clock of the test's own; the device, show and release invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastPlayingTest {
    private val kitchen = CastTo("route:kitchen", "Kitchen display")
    private val length = 3_120_000L
    private val release = SavedRelease("fake", "harbor-lights", "Harbor.Lights.S02E04.1080p.WEB-DL.H.264-CAST", null, 0, "nzb-hl", "hl.mkv", 2_000_000_000)
    private val episode = WatchTarget(WatchKind.Episode, WatchTitle.episode("Harbor Lights", 2, 4), showTmdbId = 4_101, season = 2, episode = 4)
    private val launch = PlayerLaunch(
        UsenetUris.build("nzb-hl", 0, release.fileName, release.fileSize), "Harbor Lights", "S02E04 · Low Water", WatchKey.episode(4_101, 2, 4),
        episode, release, video = VideoTiming(fps = 24.0, durationMs = length - 1_000), cast = kitchen,
    )

    private inner class Fixture(private val test: TestScope) {
        var clock = 1_000_000L
        val sent = mutableListOf<String>()
        val feed = MutableStateFlow<CastFeed?>(null)
        val link = MutableStateFlow<CastLinkNow?>(CastLinkNow(kitchen.route, kitchen.name, CastLink.Connected))

        /** Whether one of the app's activities is in front. */
        val front = MutableStateFlow(true)

        /** Up next's next episode after the one played, as the collection says it; null for none. How often it was asked. */
        var next: UpNextPlay.NextEpisode? = null
        var asked = 0

        /** Holds Up next's answer until completed, as the collection may count the play's watch a moment after its end. */
        var answer: CompletableDeferred<Unit>? = null

        /** What the line's button played, in order. */
        val played = mutableListOf<UpNextPlay.NextEpisode>()

        /** TalkBack exploring by touch. */
        var exploring = false

        /** A play starting, as the coordinator tells it. */
        val starting = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val playing = CastPlaying(
            test.backgroundScope,
            CastPlayingDeps(
                feed = feed, link = link, pause = { sent += "pause" }, resume = { sent += "play" }, seekTo = { sent += "seek to $it" },
                stop = { sent += "stop casting" }, clock = { clock }, inFront = front,
                nextEpisode = {
                    asked++
                    answer?.await()
                    next
                },
                play = { played += it }, touchExploration = { exploring }, starting = starting,
            ),
        )

        /** Every line said, in order, from now. */
        val lines = mutableListOf<String>().also { lines ->
            test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { playing.said.filterNotNull().collect { lines += it.text } }
        }

        val now: CastNow? get() = playing.now.value

        /** The device's player says [state] at [positionMs], as a status or the second's poll gives it. */
        fun device(state: CastPlayerState, positionMs: Long = 1_930_000, durationMs: Long? = length, end: CastEnd? = null, load: String = "load-1") {
            feed.value = CastFeed(load, launch, state, positionMs, durationMs, end = end, art = TvArt("/e4.jpg"))
            test.runCurrent()
        }

        fun session(link: CastLink?, route: String = kitchen.route) {
            this.link.value = link?.let { CastLinkNow(route, kitchen.name, it) }
            test.runCurrent()
        }
    }

    @Test
    fun `from its LOAD the bar says what the device's player does, loading for all but playing and paused`() = runTest {
        val f = Fixture(this)
        runCurrent()
        assertNull(f.now, "nothing loaded on the device")
        f.device(CastPlayerState.Loading, positionMs = 0, durationMs = null)
        val loading = assertNotNull(f.now)
        assertEquals(RemoteBar.Loading, loading.bar)
        assertEquals(RemoteKind.Cast, loading.kind)
        assertEquals("Kitchen display", loading.device)
        assertEquals(length - 1_000, loading.durationMs, "the probe's length until the device says its own")
        f.device(CastPlayerState.Playing)
        assertEquals(RemoteBar.Playing, f.now?.bar)
        assertEquals(length, f.now?.durationMs)
        assertEquals("Harbor Lights · S02E04", f.now?.label)
        assertEquals(TvArt("/e4.jpg"), f.now?.art)
        f.device(CastPlayerState.Paused)
        assertEquals(RemoteBar.Paused, f.now?.bar)
        f.device(CastPlayerState.Buffering)
        assertEquals(RemoteBar.Loading, f.now?.bar)
        assertTrue(assertNotNull(f.now).controls, "pause and the seeks work while it loads")
        f.device(CastPlayerState.Idle)
        assertEquals(RemoteBar.Loading, f.now?.bar, "a moment idle, before the device says how it ended")
    }

    @Test
    fun `its position moves on between the feed's polls while it plays, and stands still loading or paused`() = runTest {
        val f = Fixture(this)
        f.device(CastPlayerState.Playing, positionMs = 60_000)
        f.clock += 700
        assertEquals(60_700L, f.playing.position(assertNotNull(f.now)))
        // The next second's poll: where Media3's player says the device is.
        f.device(CastPlayerState.Playing, positionMs = 61_000)
        f.clock += 400
        assertEquals(61_400L, f.playing.position(assertNotNull(f.now)))
        f.device(CastPlayerState.Loading, positionMs = 61_500)
        f.clock += 5_000
        assertEquals(61_500L, f.playing.position(assertNotNull(f.now)), "loading: the position stands still")
        f.device(CastPlayerState.Paused, positionMs = 61_500)
        f.clock += 5_000
        assertEquals(61_500L, f.playing.position(assertNotNull(f.now)))
        f.device(CastPlayerState.Playing, positionMs = length - 1_000)
        f.clock += 10_000
        assertEquals(length, f.playing.position(assertNotNull(f.now)), "never past the end")
    }

    @Test
    fun `reconnecting while the session is suspended, its buttons waiting and its position standing still`() = runTest {
        val f = Fixture(this)
        f.device(CastPlayerState.Playing, positionMs = 2_000_000)
        f.session(CastLink.Reconnecting)
        val now = assertNotNull(f.now)
        assertEquals(RemoteBar.Reconnecting, now.bar)
        assertTrue(now.waits)
        assertFalse(now.controls)
        f.clock += 30_000
        assertEquals(2_000_000L, f.playing.position(now), "the line stands still")
        f.playing.pause()
        f.playing.resume()
        f.playing.seekTo(100_000)
        f.playing.seekBy(10_000)
        f.playing.stop()
        assertEquals(emptyList(), f.sent, "the buttons wait")
        f.session(CastLink.Connected)
        assertEquals(RemoteBar.Playing, f.now?.bar)
        f.playing.pause()
        assertEquals(listOf("pause"), f.sent)
    }

    @Test
    fun `an error there after it played is Playback stopped, with Stop casting alone`() = runTest {
        val f = Fixture(this)
        f.device(CastPlayerState.Playing)
        // The service stops; the feed keeps how it ended, and the session lives on.
        f.device(CastPlayerState.Idle, end = CastEnd(CastEnd.How.Failed, 1_930_000))
        val now = assertNotNull(f.now)
        assertEquals(RemoteBar.Stopped, now.bar)
        assertEquals("Playback stopped on Kitchen display", barLine(now))
        assertFalse(now.controls)
        assertFalse(now.live)
        f.playing.pause()
        f.playing.seekBy(10_000)
        f.playing.stop()
        assertEquals(listOf("stop casting"), f.sent)
    }

    @Test
    fun `it goes as the play ends there, as its session ends or is another device's, and as the next one loads`() = runTest {
        val f = Fixture(this)
        for (how in listOf(CastEnd.How.Stopped, CastEnd.How.Watched, CastEnd.How.LongPause, CastEnd.How.Replaced)) {
            f.device(CastPlayerState.Playing)
            assertEquals(RemoteBar.Playing, f.now?.bar)
            f.device(CastPlayerState.Idle, end = CastEnd(how, 1_930_000))
            assertNull(f.now, "$how: its words are said once, after (L93)")
        }
        f.device(CastPlayerState.Playing)
        f.device(CastPlayerState.Idle, end = CastEnd(CastEnd.How.Failed, 1_930_000))
        f.session(null)
        assertNull(f.now, "the session's end takes Playback stopped with it")
        f.session(CastLink.Connected)
        assertNull(f.now, "a later session with the device doesn't bring an old play back")
        f.device(CastPlayerState.Loading, positionMs = 0, load = "load-2")
        assertEquals(RemoteBar.Loading, f.now?.bar, "the next play's LOAD")
        f.session(CastLink.Connected, route = "route:den")
        assertNull(f.now, "the session is another device's")
        f.feed.value = null
        runCurrent()
        assertNull(f.now)
    }

    @Test
    fun `its buttons go to the device's player once each, Stop as Stop casting`() = runTest {
        val f = Fixture(this)
        f.device(CastPlayerState.Playing)
        f.playing.pause()
        f.device(CastPlayerState.Paused)
        f.playing.resume()
        f.playing.seekTo(600_000)
        assertEquals(600_000L, f.now?.positionMs, "the time bar shows where it sent the device until the device says")
        f.playing.stop()
        assertEquals(listOf("pause", "play", "seek to 600000", "stop casting"), f.sent)
        assertEquals(RemoteBar.Paused, f.now?.bar, "the bar goes as the play ends there, not on the press")
    }

    @Test
    fun `a seek by is one seek to where it is plus the step, never before the start or past the end`() = runTest {
        val f = Fixture(this)
        f.device(CastPlayerState.Playing, positionMs = 600_000)
        f.clock += 500
        f.playing.seekBy(-30_000)
        f.playing.seekTo(-5_000)
        f.playing.seekTo(length + 60_000)
        assertEquals(listOf("seek to 570500", "seek to 0", "seek to $length"), f.sent)
    }

    @Test
    fun `three ±10 s taps on the remote make one seek, as on a TV's`() = runTest {
        val f = Fixture(this)
        f.device(CastPlayerState.Playing, positionMs = 1_930_000)
        val seek = RemoteSeek(now = { f.clock }, seekBy = f.playing::seekBy, seekTo = f.playing::seekTo)
        repeat(3) {
            seek.tap(10_000, f.playing.position(assertNotNull(f.now)), length)
            f.clock += 200
        }
        f.clock += SeekBurst.WINDOW_MS - 600
        seek.update()
        assertEquals(emptyList(), f.sent, "within the burst's window")
        f.clock += 400
        seek.update()
        // 30 s on from where the device is as the seek goes: the window after the last tap, 400 ms after the first.
        assertEquals(listOf("seek to ${1_930_000 + 400 + SeekBurst.WINDOW_MS + 30_000}"), f.sent)
        assertNull(seek.dueInMs())
    }

    @Test
    fun `the bar's and the remote's words for a cast, and what TalkBack reads`() = runTest {
        val f = Fixture(this)
        f.device(CastPlayerState.Playing, positionMs = 1_930_000)
        val now = assertNotNull(f.now)
        assertEquals("Playing on Kitchen display", barLine(now))
        assertEquals("Harbor Lights, season 2, episode 4. Playing on Kitchen display, 32 minutes 10 seconds of 52 minutes", barSpoken(now, 1_930_000))
        assertEquals("Paused on Kitchen display", barLine(now.copy(shows = RemoteBar.Paused)))
        assertEquals("Loading on Kitchen display…", barLine(now.copy(shows = RemoteBar.Loading)))
        assertEquals("Reconnecting to Kitchen display…", barLine(now.copy(shows = RemoteBar.Reconnecting)))
        assertEquals("Stop casting to Kitchen display", stopOn(now))
        assertEquals("Stop casting", stopWords(now))
        assertEquals("Position on Kitchen display", positionOn(now))
        assertEquals("Harbor Lights, season 2, episode 4, Low Water", remoteHeading(now))
        assertEquals("Playing S02E04 · 32:10", playingEpisode(now, 1_930_000))
        assertEquals("On Kitchen display · 32:10", onTvAt(now.device, 1_930_000))
        assertEquals(2 to 4, now.playingEpisodeOf(4_101), "the Show page's line, while it plays there")
        assertEquals(2 to 4, now.copy(shows = RemoteBar.Reconnecting).playingEpisodeOf(4_101), "it plays on there meanwhile")
        assertNull(now.copy(shows = RemoteBar.Stopped).playingEpisodeOf(4_101))
        assertNull(now.playingEpisodeOf(4_102))
        assertEquals("Playing Harbor Lights · S02E04", playingThere(now.label), "the sheet's connected row (L78)")
    }

    @Test
    fun `× puts it away until the next play's LOAD`() = runTest {
        val f = Fixture(this)
        f.device(CastPlayerState.Playing)
        f.playing.dismiss()
        assertNull(f.now)
        f.device(CastPlayerState.Paused)
        assertNull(f.now, "the same play")
        f.device(CastPlayerState.Loading, load = "load-2")
        assertEquals(RemoteBar.Loading, f.now?.bar)
    }

    /**
     * L93 (CsPhoneStopped): the end of a play that played there says its line once, in the Undo bar's look for about 4 s:
     * stopped there (Stop casting, the session's end, another sender), watched, a FINISHED long before the end as a stop,
     * the long pause. An error there, which the bar says, and a play the next one's LOAD replaced say nothing.
     */
    @Test
    fun `each end of a play there says its line once, for about 4 s`() = runTest {
        val f = Fixture(this)
        val notice = PlayOnTimings().noticeMillis
        val ends = listOf(
            CastEnd(CastEnd.How.Stopped, 1_930_000) to "Stopped on Kitchen display at 32:10",
            CastEnd(CastEnd.How.Watched, length) to "Watched on Kitchen display",
            CastEnd(CastEnd.How.Stopped, 6_600_000) to "Stopped on Kitchen display at 1:50:00",
            CastEnd(CastEnd.How.LongPause, 1_930_000) to "Kitchen display closed after a long pause. Stopped at 32:10.",
        )
        ends.forEachIndexed { i, (end, line) ->
            f.device(CastPlayerState.Playing, load = "load-$i")
            f.device(CastPlayerState.Idle, end = end, load = "load-$i")
            assertEquals(line, f.playing.said.value?.text)
            assertEquals(RemoteKind.Cast, f.playing.said.value?.kind, "said by the Cast icon")
            // The feed keeps how it ended until the next LOAD: still said once.
            f.device(CastPlayerState.Idle, positionMs = 1_000, end = end, load = "load-$i")
            advanceTimeBy(notice - 1)
            runCurrent()
            assertEquals(line, f.playing.said.value?.text)
            advanceTimeBy(2)
            runCurrent()
            assertNull(f.playing.said.value, "gone after about 4 s")
        }
        assertEquals(ends.map { it.second }, f.lines)
        f.device(CastPlayerState.Playing, load = "load-failed")
        f.device(CastPlayerState.Idle, end = CastEnd(CastEnd.How.Failed, 1_930_000), load = "load-failed")
        f.device(CastPlayerState.Idle, end = CastEnd(CastEnd.How.Replaced, 1_930_000), load = "load-replaced")
        assertNull(f.playing.said.value, "an error, which the bar says, and a play replaced say nothing")
        assertEquals(ends.size, f.lines.size)
    }

    /**
     * L93 (CsPhoneStopped 4): a line made while the app isn't in front waits until it is, and is said once then: the long
     * pause's, mostly, as the phone is locked by the time the device closes.
     */
    @Test
    fun `a line made while the app isn't in front waits until it is, and is said once`() = runTest {
        val f = Fixture(this)
        f.device(CastPlayerState.Paused)
        f.front.value = false
        f.device(CastPlayerState.Idle, end = CastEnd(CastEnd.How.LongPause, 1_930_000))
        advanceTimeBy(60_000)
        runCurrent()
        assertNull(f.playing.said.value, "not while the app isn't in front")
        f.front.value = true
        runCurrent()
        assertEquals("Kitchen display closed after a long pause. Stopped at 32:10.", f.playing.said.value?.text)
        advanceTimeBy(PlayOnTimings().noticeMillis + 1)
        runCurrent()
        assertNull(f.playing.said.value)
        f.front.value = false
        runCurrent()
        f.front.value = true
        runCurrent()
        assertNull(f.playing.said.value, "once")
        assertEquals(listOf("Kitchen display closed after a long pause. Stopped at 32:10."), f.lines)
    }

    /**
     * A device test's reset forgets what was said and what waits to be said: the tests' plays share one process, and a
     * line one left waiting for the app (its play ended with no activity in front) would be said in the next one's.
     */
    @Test
    fun `forgotten, nothing said or held for the app is said again`() = runTest {
        val f = Fixture(this)
        f.device(CastPlayerState.Playing, positionMs = length - 60_000)
        f.front.value = false
        f.device(CastPlayerState.Idle, end = CastEnd(CastEnd.How.Watched, length))
        // The reset: nothing followed, and what was said forgotten.
        f.feed.value = null
        runCurrent()
        f.playing.forget()
        f.front.value = true
        runCurrent()
        assertNull(f.playing.said.value, "held for the app")
        assertEquals(emptyList(), f.lines)
        // A line still said as it is forgotten goes too.
        val g = Fixture(this)
        g.device(CastPlayerState.Paused)
        g.device(CastPlayerState.Idle, end = CastEnd(CastEnd.How.Stopped, 1_930_000))
        assertEquals("Stopped on Kitchen display at 32:10", g.playing.said.value?.text)
        g.playing.forget()
        assertNull(g.playing.said.value, "being said")
    }

    private val harborLights = CollectedShow(CollectedShow.idFor(4_101), 4_101, name = "Harbor Lights", addedAt = 0, updatedAt = 0)
    private val s02e05 = UpNextPlay.NextEpisode(harborLights, EpisodeRef(2, 5))

    /** The Watched line with its button, as the cast's end says it. */
    private fun Fixture.watched(load: String = "load-1") {
        device(CastPlayerState.Playing, positionMs = length - 60_000, load = load)
        device(CastPlayerState.Idle, end = CastEnd(CastEnd.How.Watched, length), load = load)
    }

    /**
     * W54 (a), W65, L125 (CsPhoneWatchedNext 1): nothing plays by itself; watched there, with Up next holding the show's
     * next episode, the line gains one button for it, and stays 10 s rather than 4.
     */
    @Test
    fun `watched there with a next episode, the line has its button and stays 10 s`() = runTest {
        val f = Fixture(this)
        f.next = s02e05
        f.watched()
        val said = assertNotNull(f.playing.said.value)
        assertEquals("Watched on Kitchen display", said.text)
        assertEquals(RemoteSaidAction("Play S02E05 on Kitchen display", s02e05), said.action)
        assertEquals(10_000L, PlayOnTimings().nextMillis)
        advanceTimeBy(PlayOnTimings().noticeMillis + 1)
        runCurrent()
        assertEquals(said, f.playing.said.value, "not gone after 4 s")
        advanceTimeBy(PlayOnTimings().nextMillis - PlayOnTimings().noticeMillis - 2)
        runCurrent()
        assertEquals(said, f.playing.said.value)
        advanceTimeBy(2)
        runCurrent()
        assertNull(f.playing.said.value, "gone after 10 s")
        assertEquals(emptyList(), f.played, "nothing played by itself")
    }

    /** L125 (CsPhoneWatchedNext, the next season's first): the button names the episode Up next gives. */
    @Test
    fun `after a season's last episode the button plays the next season's first`() = runTest {
        val f = Fixture(this)
        f.next = UpNextPlay.NextEpisode(harborLights, EpisodeRef(3, 1))
        f.watched()
        assertEquals("Play S03E01 on Kitchen display", f.playing.said.value?.action?.words)
        assertEquals("Play S03E01 on Kitchen display", playNextOn(EpisodeRef(3, 1), "Kitchen display"))
    }

    /** L125 (CsPhoneWatchedNext, no next episode yet): the line alone, as in C1, for about 4 s. */
    @Test
    fun `with no next episode the line is alone, for 4 s`() = runTest {
        val f = Fixture(this)
        f.watched()
        assertEquals("Watched on Kitchen display", f.playing.said.value?.text)
        assertNull(f.playing.said.value?.action)
        assertEquals(1, f.asked)
        advanceTimeBy(PlayOnTimings().noticeMillis + 1)
        runCurrent()
        assertNull(f.playing.said.value)
    }

    /** L125: only a play watched there offers the next one: a stop, the long pause, say their line alone, Up next not asked. */
    @Test
    fun `a play stopped there has no button, and Up next isn't asked`() = runTest {
        val f = Fixture(this)
        f.next = s02e05
        f.device(CastPlayerState.Paused)
        f.device(CastPlayerState.Idle, end = CastEnd(CastEnd.How.Stopped, 1_930_000))
        assertEquals("Stopped on Kitchen display at 32:10", f.playing.said.value?.text)
        assertNull(f.playing.said.value?.action)
        f.device(CastPlayerState.Paused, load = "load-2")
        f.device(CastPlayerState.Idle, end = CastEnd(CastEnd.How.LongPause, 1_930_000), load = "load-2")
        assertNull(f.playing.said.value?.action)
        assertEquals(0, f.asked)
    }

    /**
     * L125 (CsPhoneWatchedNext, the phone locked): a line held for the app in front shows its button only if the session
     * still lives; the device closed it meanwhile (Play goes to this phone, W36), the line alone.
     */
    @Test
    fun `a line said once the session is gone has no button`() = runTest {
        val f = Fixture(this)
        f.next = s02e05
        f.front.value = false
        f.watched()
        assertNull(f.playing.said.value, "not while the app isn't in front")
        f.session(null)
        f.front.value = true
        runCurrent()
        assertEquals("Watched on Kitchen display", f.playing.said.value?.text)
        assertNull(f.playing.said.value?.action)
        // Still alive as it is said: the button.
        val g = Fixture(this)
        g.next = s02e05
        g.front.value = false
        g.watched()
        g.front.value = true
        runCurrent()
        assertEquals(s02e05, g.playing.said.value?.action?.play)
    }

    /**
     * L125 (CFT-6): Up next may take a moment to answer, its watch not yet counted; the session ending meanwhile leaves the
     * line alone when the answer comes, and nothing plays.
     */
    @Test
    fun `a session that ends while Up next answers leaves the line alone`() = runTest {
        val f = Fixture(this)
        f.next = s02e05
        f.answer = CompletableDeferred()
        f.watched()
        assertEquals(1, f.asked)
        assertNull(f.playing.said.value, "said once Up next answers")
        f.session(null)
        f.answer?.complete(Unit)
        runCurrent()
        val said = assertNotNull(f.playing.said.value)
        assertEquals("Watched on Kitchen display", said.text)
        assertNull(said.action)
        f.playing.act(said)
        assertEquals(emptyList(), f.played)
    }

    /** L125 (CsPhoneWatchedNext 3, CsPhoneTalkBackC2): the button plays Up next's next episode, once, and the line goes. */
    @Test
    fun `its button plays the next episode, once, and the line goes`() = runTest {
        val f = Fixture(this)
        f.next = s02e05
        f.watched()
        val said = assertNotNull(f.playing.said.value)
        f.playing.act(said)
        assertNull(f.playing.said.value)
        f.playing.act(said)
        assertEquals(listOf(s02e05), f.played)
    }

    /**
     * W65, L125: with TalkBack exploring by touch the line with a button has no timeout: it stays until its button is used,
     * another screen opens, or a play starts. Without TalkBack another screen leaves it, as the line shows wherever the
     * phone is.
     */
    @Test
    fun `with TalkBack on the line stays until used, until another screen opens or a play starts`() = runTest {
        val f = Fixture(this)
        f.next = s02e05
        f.exploring = true
        f.watched()
        val first = assertNotNull(f.playing.said.value)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(first, f.playing.said.value, "no timeout")
        f.playing.left(first)
        assertNull(f.playing.said.value, "another screen")

        f.watched(load = "load-2")
        assertNotNull(f.playing.said.value)
        f.starting.emit(Unit)
        runCurrent()
        assertNull(f.playing.said.value, "a play started")

        f.watched(load = "load-3")
        f.playing.act(assertNotNull(f.playing.said.value))
        assertNull(f.playing.said.value, "used")
        assertEquals(listOf(s02e05), f.played)

        // TalkBack off: another screen leaves the line as it is, gone after 10 s.
        f.exploring = false
        f.watched(load = "load-4")
        val timed = assertNotNull(f.playing.said.value)
        f.playing.left(timed)
        assertEquals(timed, f.playing.said.value)
        advanceTimeBy(PlayOnTimings().nextMillis + 1)
        runCurrent()
        assertNull(f.playing.said.value)
    }

    /**
     * L125 (CFS-4, CFU-2): the button lives while its play's session does, connected. Held for TalkBack, the session ended
     * minutes later (the device closed it, Play back on this phone): the line stays, alone, for the notice's time, and the
     * button's word, had it come first, plays nothing. Within the 10 s, the session going to another device or ending
     * drops the button the same way.
     */
    @Test
    fun `the button goes as its play's session does, and plays nothing after`() = runTest {
        val f = Fixture(this)
        f.next = s02e05
        f.exploring = true
        f.watched()
        val held = assertNotNull(f.playing.said.value)
        advanceTimeBy(300_000)
        runCurrent()
        f.session(null)
        val alone = assertNotNull(f.playing.said.value, "the line stays")
        assertEquals("Watched on Kitchen display", alone.text)
        assertNull(alone.action, "the button went with the session")
        f.playing.act(held)
        assertEquals(emptyList(), f.played, "played on this phone under a button that names the device")
        advanceTimeBy(PlayOnTimings().noticeMillis + 1)
        runCurrent()
        assertNull(f.playing.said.value, "then gone, as a line alone goes")

        // Without TalkBack, within its 10 s: another device's session, then a tap that comes too late.
        val g = Fixture(this)
        g.next = s02e05
        g.watched()
        val timed = assertNotNull(g.playing.said.value)
        g.session(CastLink.Connected, route = "route:den")
        assertNull(g.playing.said.value?.action)
        g.playing.act(timed)
        assertEquals(emptyList(), g.played)
        // Suspended a moment (the phone's Wi-Fi): no longer connected, so no button either.
        val h = Fixture(this)
        h.next = s02e05
        h.watched()
        h.session(CastLink.Reconnecting)
        assertNull(h.playing.said.value?.action)
    }

    private val show = ShowDetails(
        4_101, "Harbor Lights", null, 2021, null, null, null, "Ended", false, 52,
        listOf(SeasonSummary(2, null, 8, LocalDate.of(2022, 3, 1), null), SeasonSummary(3, null, 6, LocalDate.of(2023, 3, 1), null)),
        lastEpisode = EpisodeSummary(3, 6, "Episode 6", LocalDate.of(2023, 4, 5)), nextEpisode = null, ids = ExternalIds(tvdb = 5_202),
    )

    /** The collection's progress for Harbor Lights with [watched] episodes watched and [inProgress] started. */
    private fun progress(watched: List<EpisodeRef>, inProgress: EpisodeRef? = null) = ShowProgress.of(
        show,
        watched.map { EpisodeWatch(it.season, it.episode, watched = true) } +
            listOfNotNull(inProgress?.let { EpisodeWatch(it.season, it.episode, watched = false, positionMs = 600_000, lastPlayedAt = 1) }),
        LocalDate.of(2026, 11, 3),
    )

    private fun season2(upTo: Int) = (1..upTo).map { EpisodeRef(2, it) }

    /**
     * L125: Up next's next episode after the one watched there, from the collection's row: once the watch is counted, the
     * one after it (the next season's first after a season's last); while the row still has it to play or resume, the save
     * not in yet, wait; nothing for a show up to date, another episode in progress, or one with no facts.
     */
    @Test
    fun `Up next's next episode after the one watched, once the collection counts it`() {
        val e4 = EpisodeRef(2, 4)
        assertEquals(NextAfter.Next(s02e05), nextEpisodeAfter(harborLights, progress(season2(4)), e4))
        assertEquals(NextAfter.Waiting, nextEpisodeAfter(harborLights, progress(season2(3)), e4), "the save not in yet")
        assertEquals(NextAfter.Waiting, nextEpisodeAfter(harborLights, progress(season2(3), inProgress = e4), e4), "still in progress")
        assertEquals(
            NextAfter.Next(UpNextPlay.NextEpisode(harborLights, EpisodeRef(3, 1))),
            nextEpisodeAfter(harborLights, progress(season2(8)), EpisodeRef(2, 8)),
        )
        // Watched again, an earlier one: Up next's is still after the furthest.
        assertEquals(NextAfter.Next(s02e05), nextEpisodeAfter(harborLights, progress(season2(4)), EpisodeRef(2, 1)))
        assertNull(nextEpisodeAfter(harborLights, progress(season2(8) + (1..6).map { EpisodeRef(3, it) }), EpisodeRef(3, 6)), "up to date")
        assertNull(nextEpisodeAfter(harborLights, progress(season2(3), inProgress = EpisodeRef(2, 7)), e4), "another in progress")
        assertNull(nextEpisodeAfter(harborLights, null, e4), "no facts from TMDB")
    }

    /**
     * W36, L85 (CsPhoneStopped 3): watched there, the session lives on until the device closes it, so Play still goes
     * there: nothing from here ends it, and the line says it was watched.
     */
    @Test
    fun `watched there keeps the session, and with it the choice, while it lives`() = runTest {
        val f = Fixture(this)
        f.device(CastPlayerState.Playing, positionMs = length - 60_000)
        f.device(CastPlayerState.Idle, end = CastEnd(CastEnd.How.Watched, length))
        assertEquals("Watched on Kitchen display", f.playing.said.value?.text)
        assertNull(f.now, "the bar goes")
        assertEquals(emptyList(), f.sent, "no Stop casting: the session, and the device chosen, live on")
        assertEquals(CastLink.Connected, f.link.value?.link)
    }
}
