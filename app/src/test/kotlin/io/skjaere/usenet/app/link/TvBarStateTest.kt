package io.skjaere.usenet.app.link

import io.skjaere.usenet.data.PairedTv
import io.skjaere.usenet.data.TvSession
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.LinkMessage.Pause
import io.skjaere.usenet.link.LinkMessage.Resume
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.LinkMessage.Stop
import io.skjaere.usenet.link.Playback
import io.skjaere.usenet.link.SheetKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * L38, W14, W15, item 25: the Playing on bar, as the phone follows a play it started on a TV from the moment the TV
 * took it. It shows once the TV's player has the play (the start's sheet has it before), says what the TV says (playing,
 * paused, buffering, the next episode starting, Playback stopped), moves its position on between the TV's updates, and
 * sends Pause, Resume and Stop for its session. A lost link reads lost, and after 2 minutes in front "Can't reach",
 * which × puts away; a new link brings it back. The TV's stopped status ends it, as does another play in its place. A
 * clock of the test's own; the titles invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvBarStateTest {
    private val living = KeyId.of(Random.nextBytes(91))
    private val bedroom = KeyId.of(Random.nextBytes(91))
    private val e4 = WatchKey.episode(4_101, 2, 4)
    private val e5 = WatchKey.episode(4_101, 2, 5)

    private inner class Fixture(private val test: TestScope) {
        val sent = mutableListOf<Pair<KeyId, LinkMessage>>()
        private var seq = 0
        val heard = MutableSharedFlow<TvHeard>(extraBufferCapacity = 64)
        val links = mapOf(living to MutableStateFlow(true), bedroom to MutableStateFlow(true))
        val gone = mapOf(living to MutableStateFlow(false), bedroom to MutableStateFlow(false))

        /** The sessions this phone started on the living room TV, as link.json keeps them. */
        val sessions = mutableListOf(TvSession("s-1", 0, showTmdbId = 4_101))
        val front = MutableStateFlow(true)
        val holds = mutableListOf<String>()
        val looked = mutableListOf<String>()
        val playing = TvPlaying(
            test.backgroundScope,
            TvPlayingDeps(
                command = { key, build -> (++seq).also { sent += key to build(it) } },
                heard = heard,
                linked = { links.getValue(it) },
                hold = { key ->
                    holds += "hold ${key.short}"
                    AutoCloseable { holds += "let go ${key.short}" }
                },
                inFront = front,
                clock = { test.testScheduler.currentTime },
                timings = { PlayOnTimings() },
                art = { key ->
                    looked += key
                    TvArt("/${key.substringAfterLast(':')}.jpg", poster = key.startsWith("movie:"))
                },
                gone = { gone.getValue(it) },
                tv = { key -> if (key == living) PairedTv(key.hex, "Living room TV", 0, sessions = sessions.toList()) else null },
            ),
        )

        val now: TvPlayNow? get() = playing.now.value
        val bar: RemoteBar? get() = now?.bar

        fun follow(tv: KeyId = living, session: String = "s-1") {
            playing.follow(tv, if (tv == living) "Living room TV" else "Bedroom TV", session, "Harbor Lights", "S02E04 · Low Water")
            test.runCurrent()
        }

        suspend fun tv(message: LinkMessage, from: KeyId = living) {
            heard.emit(TvHeard(from, message))
            test.runCurrent()
        }

        suspend fun status(state: Playback, positionMs: Long? = 1_930_000, session: String = "s-1", watchKey: String? = e4, subtitle: String = "S02E04 · Low Water", from: KeyId = living) =
            tv(Status(session, state, mine = true, title = "Harbor Lights", subtitle = subtitle, watchKey = watchKey, positionMs = positionMs, durationMs = 3_120_000), from)

        fun link(up: Boolean, tv: KeyId = living) {
            links.getValue(tv).value = up
            test.runCurrent()
        }
    }

    @Test
    fun `the bar shows once the TV's player has the play, and says what the TV says`() = runTest {
        val f = Fixture(this)
        f.follow()
        assertEquals(listOf("hold ${living.short}"), f.holds, "the TV's link held while the phone follows its play (L36)")
        assertNull(f.bar, "not before its player: the start's sheet has it")
        f.tv(Status("s-1", Playback.Starting, mine = true, title = "Harbor Lights"))
        f.tv(Status("s-1", Playback.Buffering, mine = true, title = "Harbor Lights"))
        assertNull(f.bar, "a launch on its way is still the start's")
        f.status(Playback.Playing)
        assertEquals(RemoteBar.Playing, f.bar)
        val now = assertNotNull(f.now)
        assertEquals("Harbor Lights · S02E04", now.label)
        assertEquals(e4, now.watchKey)
        assertEquals(1_930_000L to 3_120_000L, now.positionMs to now.durationMs)
        f.status(Playback.Paused)
        assertEquals(RemoteBar.Paused, f.bar)
        f.status(Playback.Buffering)
        assertEquals(RemoteBar.Buffering, f.bar)
        f.status(Playback.Failed)
        assertEquals(RemoteBar.Stopped, f.bar, "the player's Playback stopped")
        f.tv(Status("s-1", Playback.Unknown, mine = true))
        assertEquals(RemoteBar.Stopped, f.bar, "a state this version doesn't know reads as the last one known (L26)")
        // Another session's word, or another TV's, is none of its business.
        f.status(Playback.Playing, session = "s-other")
        f.status(Playback.Playing, from = bedroom)
        assertEquals(RemoteBar.Stopped, f.bar)
    }

    @Test
    fun `the position moves on between the TV's updates while it plays, and stands while paused or lost`() = runTest {
        val f = Fixture(this)
        f.follow()
        f.status(Playback.Playing, positionMs = 60_000)
        advanceTimeBy(700)
        assertEquals(60_700L, f.playing.position(assertNotNull(f.now)))
        f.status(Playback.Playing, positionMs = 61_000)
        advanceTimeBy(400)
        assertEquals(61_400L, f.playing.position(assertNotNull(f.now)))
        f.status(Playback.Paused, positionMs = 61_500)
        advanceTimeBy(5_000)
        assertEquals(61_500L, f.playing.position(assertNotNull(f.now)))
        f.status(Playback.Playing, positionMs = 3_119_000)
        advanceTimeBy(10_000)
        assertEquals(3_120_000L, f.playing.position(assertNotNull(f.now)), "never past the end")
        f.status(Playback.Playing, positionMs = 100_000)
        f.link(up = false)
        advanceTimeBy(30_000)
        assertEquals(100_000L, f.playing.position(assertNotNull(f.now)), "the line stands still at the last position heard (PtPhoneLost 2)")
    }

    @Test
    fun `lost becomes can't reach at 120 s, and × puts it away`() = runTest {
        val f = Fixture(this)
        f.follow()
        f.status(Playback.Playing)
        f.link(up = false)
        assertEquals(RemoteBar.Lost, f.bar)
        advanceTimeBy(119_999)
        runCurrent()
        assertEquals(RemoteBar.Lost, f.bar)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(RemoteBar.CantReach, f.bar)
        f.playing.dismiss()
        assertNull(f.bar, "× puts it away")
        assertEquals(listOf("hold ${living.short}"), f.holds, "still trying to reach the TV")
    }

    @Test
    fun `a new link brings back playing`() = runTest {
        val f = Fixture(this)
        f.follow()
        f.status(Playback.Playing)
        f.link(up = false)
        advanceTimeBy(120_000)
        runCurrent()
        f.playing.dismiss()
        assertNull(f.bar)
        f.link(up = true)
        // The TV says the play now as the link comes up.
        f.tv(Hello(1, 1, io.skjaere.usenet.link.Role.Tv, "Living room TV", session = Hello.Session("s-1", mine = true)))
        f.status(Playback.Playing, positionMs = 2_110_000)
        assertEquals(RemoteBar.Playing, f.bar)
        assertEquals(2_110_000L, f.now?.positionMs)
        // Lost again: two minutes from then.
        f.link(up = false)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(RemoteBar.Lost, f.bar)
    }

    @Test
    fun `out of the front the two minutes wait, and start again as the app comes back`() = runTest {
        val f = Fixture(this)
        f.follow()
        f.status(Playback.Playing)
        f.front.value = false
        runCurrent()
        // The app's links close a while after it leaves the front: no one sees the bar meanwhile.
        f.link(up = false)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(RemoteBar.Lost, f.bar, "not can't reach: the phone wasn't trying while out of the front")
        f.front.value = true
        runCurrent()
        advanceTimeBy(119_000)
        runCurrent()
        assertEquals(RemoteBar.Lost, f.bar)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(RemoteBar.CantReach, f.bar)
    }

    @Test
    fun `the next episode starting in the session reads so, and its player takes the bar on`() = runTest {
        val f = Fixture(this)
        f.follow()
        f.status(Playback.Playing, positionMs = 3_100_000)
        f.tv(Status("s-1", Playback.Starting, mine = true, continued = true, title = "Harbor Lights", subtitle = "S02E05 · The Long Tide"))
        assertEquals(RemoteBar.NextEpisode, f.bar)
        assertEquals("Harbor Lights · S02E05", f.now?.label)
        assertNull(f.now?.watchKey, "not the episode before's any more")
        assertEquals(0L, f.now?.positionMs)
        // Its start failing on the TV: the spinner stops.
        f.tv(Status("s-1", Playback.Starting, mine = true, continued = true, title = "Harbor Lights", sheet = Status.Sheet(SheetKind.NoRelease, "Harbor Lights")))
        assertEquals(RemoteBar.Stopped, f.bar)
        f.tv(Status("s-1", Playback.Starting, mine = true, continued = true, title = "Harbor Lights", subtitle = "S02E05 · The Long Tide"))
        f.status(Playback.Playing, positionMs = 12_000, watchKey = e5, subtitle = "S02E05 · The Long Tide")
        assertEquals(RemoteBar.Playing, f.bar)
        assertEquals(e5, f.now?.watchKey)
    }

    @Test
    fun `its buttons send Pause, Resume, Stop and the seeks for its session, once each`() = runTest {
        val f = Fixture(this)
        f.follow()
        f.status(Playback.Playing)
        f.playing.pause()
        f.status(Playback.Paused)
        f.playing.resume()
        f.playing.seekTo(600_000)
        assertEquals(600_000L, f.now?.positionMs, "the time bar shows where it sent the TV until the TV says")
        f.playing.seekBy(-30_000)
        f.playing.stop()
        assertEquals(
            listOf<Pair<KeyId, LinkMessage>>(
                living to Pause(1, "s-1"), living to Resume(2, "s-1"), living to LinkMessage.SeekTo(3, "s-1", 600_000),
                living to LinkMessage.SeekBy(4, "s-1", -30_000), living to Stop(5, "s-1"),
            ),
            f.sent,
        )
        assertEquals(RemoteBar.Paused, f.bar, "the bar goes on the TV's stopped status, not on the press")
        // While the next episode starts there is no player to leave: Stop cancels the start.
        f.tv(Status("s-1", Playback.Starting, mine = true, continued = true, title = "Harbor Lights", subtitle = "S02E05 · The Long Tide"))
        f.playing.stop()
        assertEquals(living to LinkMessage.Cancel(6, "s-1"), f.sent.last())
    }

    @Test
    fun `it ends on the TV's stopped status, another play in its place, or a TV that names another play as it links again`() = runTest {
        val f = Fixture(this)
        f.follow()
        f.status(Playback.Playing)
        f.status(Playback.Stopped)
        assertNull(f.now)
        assertEquals(listOf("hold ${living.short}", "let go ${living.short}"), f.holds)
        f.follow(session = "s-2")
        f.status(Playback.Playing, session = "s-2")
        f.status(Playback.Replaced, session = "s-2")
        assertNull(f.now)
        f.follow(session = "s-3")
        f.status(Playback.Playing, session = "s-3")
        f.link(up = false)
        f.link(up = true)
        f.tv(Hello(1, 1, io.skjaere.usenet.link.Role.Tv, "Living room TV", session = null))
        assertNull(f.now, "NZStreamer on the TV closed meanwhile: its play is over")
        f.follow(session = "s-4")
        f.status(Playback.Playing, session = "s-4")
        f.tv(Hello(1, 1, io.skjaere.usenet.link.Role.Tv, "Living room TV", session = Hello.Session("s-other")))
        assertNull(f.now, "another play took its place meanwhile")
    }

    @Test
    fun `one session a phone, so a play followed on another TV moves the bar there`() = runTest {
        val f = Fixture(this)
        f.follow()
        f.status(Playback.Playing)
        f.follow(bedroom, "s-9")
        // The new one held before the old is let go: the same TV followed again keeps its link up throughout.
        assertEquals(listOf("hold ${living.short}", "hold ${bedroom.short}", "let go ${living.short}"), f.holds)
        assertNull(f.bar)
        f.status(Playback.Playing, session = "s-9", from = bedroom)
        assertEquals(RemoteBar.Playing, f.bar)
        assertEquals("Bedroom TV", f.now?.tvName)
        f.status(Playback.Paused)
        assertEquals(RemoteBar.Playing, f.bar, "the first TV plays on unfollowed")
    }

    @Test
    fun `the remote's picture is the phone's own, looked up once for each episode the TV plays`() = runTest {
        val f = Fixture(this)
        f.follow()
        assertNull(f.now?.art)
        f.status(Playback.Playing)
        assertEquals(TvArt("/e4.jpg", poster = false), f.now?.art, "the episode's still, from this phone's TMDB (L34)")
        f.status(Playback.Paused)
        assertEquals(listOf(e4), f.looked)
        f.tv(Status("s-1", Playback.Starting, mine = true, continued = true, title = "Harbor Lights", subtitle = "S02E05 · The Long Tide"))
        assertNull(f.now?.art, "not the episode before's")
        f.status(Playback.Playing, watchKey = e5)
        assertEquals(TvArt("/e5.jpg", poster = false), f.now?.art)
        assertEquals(listOf(e4, e5), f.looked)
    }

    @Test
    fun `the bar's words, and what TalkBack reads of it`() = runTest {
        val name = "Living room TV"
        val f = Fixture(this)
        f.follow()
        f.status(Playback.Playing, positionMs = 1_930_000)
        val now = assertNotNull(f.now)
        assertEquals("Playing on Living room TV", barLine(now))
        assertEquals("Harbor Lights, season 2, episode 4. Playing on Living room TV, 32 minutes 10 seconds of 52 minutes", barSpoken(now, 1_930_000))
        assertEquals("Paused on Living room TV", barLine(now.copy(state = Playback.Paused)))
        assertEquals("Buffering on the TV", barLine(now.copy(state = Playback.Buffering)))
        assertEquals("Next episode starting on Living room TV", barLine(now.copy(state = Playback.Starting)))
        assertEquals("Playback stopped on Living room TV", barLine(now.copy(state = Playback.Failed)))
        assertEquals("Lost the connection · reconnecting…", barLine(now.copy(linked = false)))
        assertEquals("Can't reach Living room TV", barLine(now.copy(linked = false, cantReach = true)))
        assertEquals("Pause on $name" to "Play on $name", pauseOn(name) to playOnTv(name))
        assertEquals("Stop playing on Living room TV", stopPlayingOn(name))
        assertEquals("Playing S02E04 · 32:10", playingEpisode(now, 1_930_000))
        assertEquals("On Living room TV · 32:10", onTvAt(name, 1_930_000))
        assertEquals("1 hour 2 minutes 5 seconds", spokenTime(3_725_000))
        assertEquals("0 seconds", spokenTime(400))
        val movie = now.copy(title = "North Quay", subtitle = null, watchKey = WatchKey.movie(7_707))
        assertEquals("North Quay", movie.label)
        assertEquals("North Quay. Playing on Living room TV, 1 minute of 52 minutes", barSpoken(movie, 60_000))
        assertEquals(4_101 to (2 to 4), now.episode?.let { it.first to (it.second to it.third) })
        assertNull(movie.episode)
    }

    @Test
    fun `a Final saved just as the lost link's two minutes run out is still said`() = runTest {
        val f = Fixture(this)
        f.follow()
        f.status(Playback.Playing)
        f.link(up = false)
        advanceTimeBy(PlayOnTimings().cantReachMillis)
        // Handed to the loop just before its wait runs out.
        val final = LinkMessage.Final(
            "f-1", "s-1", e4, io.skjaere.usenet.link.FinalKind.Episode, "Harbor Lights S02E04", showTmdbId = 4_101, season = 2, episode = 4,
            positionMs = 1_930_000, durationMs = 3_120_000, watched = false, ageMs = 0,
        )
        f.playing.stopped(living, "Living room TV", final)
        runCurrent()
        assertEquals(RemoteBar.CantReach, f.bar)
        advanceTimeBy(5_000)
        assertEquals(stoppedOnAt("Living room TV", 1_930_000), f.playing.said.value?.text)
    }

    @Test
    fun `forgetting the TV it follows, or that TV removing this phone, ends it at once`() = runTest {
        val f = Fixture(this)
        f.follow()
        f.status(Playback.Playing)
        // Forget: the TV goes from the kept ones, its link with it.
        f.link(up = false)
        f.gone.getValue(living).value = true
        runCurrent()
        assertNull(f.now, "no bar, remote or Show page line for a TV forgotten")
        assertEquals("let go ${living.short}", f.holds.last())

        // Removed on the TV (or set up again there): its refusals say so, and the follow ends too.
        f.gone.getValue(living).value = false
        f.follow(bedroom, session = "s-2")
        f.status(Playback.Paused, session = "s-2", from = bedroom)
        assertEquals(RemoteBar.Paused, f.bar)
        f.gone.getValue(bedroom).value = true
        runCurrent()
        assertNull(f.now)
    }

    @Test
    fun `a play of this phone's that a TV says it plays is followed again, after a restart or a lost Ack`() = runTest {
        val f = Fixture(this)
        f.playing.start()
        runCurrent()
        // Nothing followed (the process started afresh): the TV's link comes up, its hello names this phone's session,
        // and its status for it, titled, says it plays.
        f.tv(Hello(1, 1, io.skjaere.usenet.link.Role.Tv, "Living room TV", session = Hello.Session("s-1", mine = true)))
        f.status(Playback.Paused)
        assertEquals(RemoteBar.Paused, f.bar, "the bar, the remote and the Show page line are back")
        val now = assertNotNull(f.now)
        assertEquals("Living room TV" to "s-1", now.tvName to now.session)
        assertEquals(e4, now.watchKey)
        assertEquals(listOf("hold ${living.short}"), f.holds, "its link held while followed")
        f.playing.pause()
        assertEquals(living to Pause(1, "s-1"), f.sent.last())
    }

    @Test
    fun `a play not of this phone's sessions there, or not playing, is not taken up`() = runTest {
        val f = Fixture(this)
        f.playing.start()
        runCurrent()
        f.status(Playback.Playing, session = "s-other")
        f.status(Playback.Playing, session = "s-1", from = bedroom)
        f.tv(Status("s-1", Playback.Stopped, mine = true, title = "Harbor Lights"))
        f.tv(Status("s-1", Playback.Playing))
        assertNull(f.now, "another session, another TV, a session over, an untitled status")
        // While another is followed, it stays that one.
        f.follow(bedroom, session = "s-2")
        f.status(Playback.Playing, session = "s-1")
        assertEquals("s-2", f.now?.session)
    }
}
