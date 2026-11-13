package io.skjaere.usenet.app.link

import io.skjaere.usenet.data.PairedTv
import io.skjaere.usenet.data.TvSession
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.link.FinalKind
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Final
import io.skjaere.usenet.link.LinkMessage.FinalAck
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.Playback
import io.skjaere.usenet.link.SheetKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * W5, L11, L33, L41, item 27: where a play this phone started on a TV stopped, on the phone. A Final is saved only for this
 * phone's own session on that TV and the same title; the TV is told it was (FinalAck) after the write, and for a Final
 * dropped too, never for one whose write failed (the TV sends it again). The phone says where it stopped, in the Undo
 * bar's look for a moment: "Stopped on Living room TV at 32:10", a late one "Living room TV stopped at 41:02", "Watched on
 * Living room TV", and "Cancelled on Living room TV" for a start that stopped before its player; no line while the bar
 * follows the session to its next episode. A clock of the test's own; the titles invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneFinalsTest {
    private val living = KeyId.of(Random.nextBytes(91))
    private val show = 4_101
    private val e4 = WatchKey.episode(show, 2, 4)
    private val e5 = WatchKey.episode(show, 2, 5)

    private val bedroom = KeyId.of(Random.nextBytes(91))
    private val kitchen = KeyId.of(Random.nextBytes(91))

    private fun name(key: KeyId) = when (key) {
        living -> "living"
        bedroom -> "bedroom"
        else -> "kitchen"
    }

    private inner class Fixture(private val test: TestScope, failWrites: Boolean = false) {
        val heard = MutableSharedFlow<TvHeard>(extraBufferCapacity = 64)
        val events = mutableListOf<String>()
        val sessions = MutableStateFlow(listOf(TvSession("s-1", 0, showTmdbId = show)))
        @Volatile var failing = failWrites
        val front = MutableStateFlow(false)

        /** The TVs link.json keeps: none until a test says. */
        val tvs = MutableStateFlow<List<PairedTv>>(emptyList())
        val holds = mutableListOf<String>()
        val playing = TvPlaying(
            test.backgroundScope,
            TvPlayingDeps(
                command = { _, build -> build(1).let { 1 } },
                heard = heard,
                linked = { MutableStateFlow(true) },
                hold = { AutoCloseable {} },
                inFront = MutableStateFlow(true),
                clock = { test.testScheduler.currentTime },
                timings = { PlayOnTimings() },
            ),
        )
        val finals = PhoneFinals(
            test.backgroundScope,
            PhoneFinalsDeps(
                heard = heard,
                send = { key, message ->
                    events += "sent ${if (key == living) "living" else "other"} ${(message as FinalAck).ids}"
                    true
                },
                tv = { key -> if (key == living) PairedTv(key.hex, "Living room TV", 0, sessions = sessions.value) else null },
                record = { final ->
                    if (failing) throw IllegalStateException("the test's database")
                    events += "saved ${final.id}"
                    true
                },
                applied = playing::stopped,
                inFront = front,
                tvs = tvs,
                hold = { key ->
                    holds += "hold ${name(key)}"
                    AutoCloseable { holds += "let go ${name(key)}" }
                },
                now = { NOW },
                timings = { PlayOnTimings() },
            ),
        ).also { it.start() }

        val said: String? get() = playing.said.value?.text

        /** Every line said, in order. */
        val lines = mutableListOf<String>()

        init {
            test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { playing.said.filterNotNull().collect { lines += it.text } }
        }

        suspend fun tv(message: LinkMessage) {
            heard.emit(TvHeard(living, message))
            test.runCurrent()
        }

        fun follow() {
            playing.follow(living, "Living room TV", "s-1", "Harbor Lights", "S02E04 · Low Water")
            test.runCurrent()
        }

        suspend fun status(state: Playback, key: String? = e4, continued: Boolean = false) =
            tv(Status("s-1", state, mine = true, continued = continued, title = "Harbor Lights", watchKey = key, positionMs = 1_930_000, durationMs = 3_120_000))
    }

    private fun final(id: String = "f-1", position: Long = 1_930_000, watched: Boolean = false, age: Long = 0, session: String = "s-1", key: String = e4, episode: Int = 4) = Final(
        id, session, key, FinalKind.Episode, "Harbor Lights S02E0$episode", showTmdbId = show, season = 2, episode = episode,
        positionMs = position, durationMs = 3_120_000, watched = watched, ageMs = age,
    )

    @Test
    fun `a Final of this phone's session is saved, then acked, and said`() = runTest {
        val f = Fixture(this)
        f.tv(final())
        assertEquals(listOf("saved f-1", "sent living [f-1]"), f.events, "the FinalAck after the write")
        assertEquals("Stopped on Living room TV at 32:10", f.said)
        advanceTimeBy(PlayOnTimings().noticeMillis)
        runCurrent()
        assertNull(f.said, "gone after about 4 s")
    }

    @Test
    fun `a Final of another session or another title is dropped, acked all the same, and nothing is said`() = runTest {
        val f = Fixture(this)
        f.tv(final(id = "f-2", session = "s-other"))
        f.sessions.value = listOf(TvSession("s-1", 0, movieTmdbId = 7_707))
        f.tv(final(id = "f-3"))
        assertEquals(listOf("sent living [f-2]", "sent living [f-3]"), f.events)
        assertNull(f.said)
    }

    @Test
    fun `a Final whose write failed isn't acked, so the TV sends it again, and one sent twice is saved and said once`() = runTest {
        val f = Fixture(this, failWrites = true)
        f.tv(final())
        assertEquals(emptyList(), f.events, "owed again on the next link")
        assertNull(f.said)
        f.failing = false
        f.tv(final())
        f.tv(final())
        assertEquals(listOf("saved f-1", "sent living [f-1]", "sent living [f-1]"), f.events)
    }

    @Test
    fun `a late one says the TV stopped, and a watched one that it was watched`() = runTest {
        val f = Fixture(this)
        f.tv(final(id = "f-4", position = 2_462_000, age = 600_000))
        assertEquals("Living room TV stopped at 41:02", f.said)
        f.tv(final(id = "f-5", position = 3_100_000, watched = true))
        assertEquals("Watched on Living room TV", f.said)
    }

    @Test
    fun `the line for the play the bar follows waits for its next word, and none comes as the bar follows to the next episode`() = runTest {
        val f = Fixture(this)
        f.follow()
        f.status(Playback.Playing)
        // Stop: the Final first, the TV's stopped status after its grace.
        f.tv(final(id = "f-6"))
        assertNull(f.said)
        f.status(Playback.Stopped)
        assertEquals("Stopped on Living room TV at 32:10", f.said)
        assertEquals(listOf("Stopped on Living room TV at 32:10"), f.lines)
        // Auto-advance: the episode's Final, its session going on with the next one.
        f.follow()
        f.status(Playback.Playing)
        f.tv(final(id = "f-7", position = 3_110_000, watched = true))
        f.status(Playback.Starting, key = null, continued = true)
        f.status(Playback.Playing, key = e5)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("Stopped on Living room TV at 32:10"), f.lines, "no line: the bar follows to the next episode")
        // The next one already starting as the Final comes.
        f.tv(final(id = "f-8", key = e5, episode = 5, position = 3_110_000, watched = true))
        f.tv(Status("s-1", Playback.Starting, mine = true, continued = true, title = "Harbor Lights"))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("Stopped on Living room TV at 32:10"), f.lines)
        assertEquals(listOf("saved f-6", "sent living [f-6]", "saved f-7", "sent living [f-7]", "saved f-8", "sent living [f-8]"), f.events, "each saved all the same")
    }

    @Test
    fun `the line for the play the bar follows comes alone after a while when the TV says nothing more`() = runTest {
        val f = Fixture(this)
        f.follow()
        f.status(Playback.Playing)
        f.tv(final(id = "f-9"))
        advanceTimeBy(2_999)
        runCurrent()
        assertNull(f.said)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("Stopped on Living room TV at 32:10", f.said)
    }

    @Test
    fun `a start stopped before its player says it was cancelled, one that failed says nothing more`() = runTest {
        val f = Fixture(this)
        f.follow()
        f.tv(Status("s-1", Playback.Starting, mine = true, title = "Harbor Lights"))
        f.status(Playback.Stopped)
        assertEquals("Cancelled on Living room TV", f.said)
        advanceTimeBy(PlayOnTimings().noticeMillis)
        runCurrent()
        f.follow()
        f.tv(Status("s-1", Playback.Starting, mine = true, title = "Harbor Lights", sheet = Status.Sheet(SheetKind.Failed, "Harbor Lights")))
        f.status(Playback.Stopped)
        assertEquals(listOf("Cancelled on Living room TV"), f.lines, "its failure on the sheet said why")
    }

    @Test
    fun `as the app comes to the front, the TVs this phone started plays on are linked for a while, for what they owe it`() = runTest {
        val f = Fixture(this)
        f.tvs.value = listOf(
            PairedTv(living.hex, "Living room TV", 0, sessions = listOf(TvSession("s-1", NOW - 3_600_000, showTmdbId = show))),
            // Its last session over 30 days ago: whatever it owed is gone there too (L33).
            PairedTv(bedroom.hex, "Bedroom TV", 0, sessions = listOf(TvSession("s-0", NOW - 31L * 24 * 60 * 60 * 1_000, movieTmdbId = 900_031))),
            // Never played on from here: nothing owed.
            PairedTv(kitchen.hex, "Kitchen TV", 0),
        )
        runCurrent()
        assertEquals(emptyList(), f.holds, "out of the front, no link")
        f.front.value = true
        runCurrent()
        assertEquals(listOf("hold living"), f.holds)
        advanceTimeBy(29_999)
        assertEquals(listOf("hold living"), f.holds)
        advanceTimeBy(2)
        assertEquals(listOf("hold living", "let go living"), f.holds, "then let go: the chosen TV, the sheet and a play followed hold their own")
        // Each time it comes to the front again; and out of the front, let go at once.
        f.front.value = false
        runCurrent()
        f.front.value = true
        runCurrent()
        assertEquals("hold living", f.holds.last())
        f.front.value = false
        runCurrent()
        assertEquals("let go living", f.holds.last())
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
