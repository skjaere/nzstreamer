package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.RemotePlay
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.data.Clock
import io.skjaere.usenet.data.LinkFile
import io.skjaere.usenet.data.LinkStore
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.link.FinalKind
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Final
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W5, L33: where a play a phone started stopped on the TV, for the phone's progress. Each end of a phone's play's player
 * (its save as it closes, or at the end) makes one Final, in the phone's session, watched by the 90 % rule, owed to that
 * phone in link.json: sent at once on its link, else on its next one with its age by then, and let go on its FinalAck.
 * The TV's own plays make none, nor does a phone no longer paired get any. The titles and phones are invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvFinalTest {
    private var time = 1_800_000_000_000L
    private val store = LinkStore(MemoryStore(LinkFile()), Clock { time })
    private val galaxy = KeyId.of(Random.nextBytes(91))
    private val tablet = KeyId.of(Random.nextBytes(91))
    private var ids = 0

    /** A paired phone's link: what it heard. */
    private class Link(override val phone: KeyId) : PhoneLink {
        val heard = mutableListOf<LinkMessage>()

        override fun send(message: LinkMessage) {
            heard += message
        }
    }

    private suspend fun TestScope.finals(): TvFinals {
        store.addPhone(galaxy, "Galaxy phone", "192.168.1.57")
        store.addPhone(tablet, "Invented tablet", "192.168.1.58")
        return TvFinals(backgroundScope, store, newId = { "f-${++ids}" }, context = StandardTestDispatcher(testScheduler))
    }

    private val release = SavedRelease("indexer-7", "guid-1", "Harbor.Lights.S02E04.1080p.WEB-DL-GRP", null, 0, "nzb-1", "Harbor.Lights.S02E04.mkv", 1_400_000_000)

    private fun episode(remote: RemotePlay?) = PlayerLaunch(
        uri = "usenet://nzb-1/0", title = "Harbor Lights", subtitle = "S02E04 · Low Water", watchKey = WatchKey.episode(900002, 2, 4),
        watch = WatchTarget(WatchKind.Episode, "Harbor Lights S02E04", showTmdbId = 900002, season = 2, episode = 4), release = release, remote = remote,
    )

    private fun movie(remote: RemotePlay?) = PlayerLaunch(
        uri = "usenet://nzb-2/0", title = "Harbor Bell", subtitle = null, watchKey = WatchKey.movie(900031),
        watch = WatchTarget(WatchKind.Movie, "Harbor Bell", movieTmdbId = 900031), release = release.copy(nzbId = "nzb-2"), remote = remote,
    )

    private fun session(id: String, phone: KeyId = galaxy) = RemotePlay(id, phone.hex, "Galaxy phone")

    private val asEpisode = Final(
        "f-1", "s-1", WatchKey.episode(900002, 2, 4), FinalKind.Episode, "Harbor Lights S02E04", showTmdbId = 900002, season = 2, episode = 4,
        positionMs = 1_930_000, durationMs = 3_120_000, watched = false, ageMs = 0,
    )

    @Test
    fun `a phone's play ending makes one Final in its session, owed to it, and sent at once on its link`() = runTest {
        val finals = finals()
        val link = Link(galaxy)
        val other = Link(tablet)
        finals.linked(link)
        finals.linked(other)
        finals.ended(episode(session("s-1")), 1_930_000, 3_120_000)
        runCurrent()
        assertEquals(listOf(asEpisode), store.owed(galaxy))
        assertEquals(listOf<LinkMessage>(asEpisode), link.heard)
        assertEquals(emptyList(), other.heard, "another phone hears none of it")
        assertEquals(emptyList(), store.owed(tablet))
    }

    @Test
    fun `a movie's Final names the movie`() = runTest {
        val finals = finals()
        finals.ended(movie(session("s-2")), 600_000, 6_240_000)
        runCurrent()
        assertEquals(
            listOf(Final("f-1", "s-2", WatchKey.movie(900031), FinalKind.Movie, "Harbor Bell", movieTmdbId = 900031, positionMs = 600_000, durationMs = 6_240_000, watched = false, ageMs = 0)),
            store.owed(galaxy),
        )
    }

    @Test
    fun `the TV's own plays make none, nor a play with no length`() = runTest {
        val finals = finals()
        val link = Link(galaxy)
        finals.linked(link)
        finals.ended(episode(null), 1_930_000, 3_120_000)
        finals.ended(episode(session("s-1")), 1_930_000, 0)
        runCurrent()
        assertEquals(emptyList(), store.owed(galaxy))
        assertEquals(emptyList(), link.heard)
    }

    @Test
    fun `watched by the 90 % rule`() = runTest {
        val finals = finals()
        finals.ended(episode(session("s-1")), 2_808_000, 3_120_000)
        finals.ended(episode(session("s-1")), 2_807_999, 3_120_000)
        finals.ended(episode(session("s-1")), 3_120_000, 3_120_000)
        runCurrent()
        assertEquals(listOf(true, false, true), store.owed(galaxy).map { it.watched })
    }

    @Test
    fun `owed on the phone's next link with its age by then, and let go on its FinalAck`() = runTest {
        val finals = finals()
        finals.ended(episode(session("s-1")), 1_930_000, 3_120_000)
        runCurrent()
        time += 5_000
        val link = Link(galaxy)
        finals.linked(link)
        runCurrent()
        assertEquals(listOf<LinkMessage>(asEpisode.copy(ageMs = 5_000)), link.heard)
        finals.acked(galaxy, listOf("f-1"))
        runCurrent()
        assertEquals(emptyList(), store.owed(galaxy))
        finals.closed(link)
        val again = Link(galaxy)
        finals.linked(again)
        runCurrent()
        assertEquals(emptyList(), again.heard, "nothing owed any more")
    }

    @Test
    fun `a closed link is sent nothing, and its phone's Final waits for the next`() = runTest {
        val finals = finals()
        val link = Link(galaxy)
        finals.linked(link)
        finals.closed(link)
        finals.ended(episode(session("s-1")), 1_930_000, 3_120_000)
        runCurrent()
        assertEquals(emptyList(), link.heard)
        assertEquals(listOf("f-1"), store.owed(galaxy).map { it.id })
    }

    @Test
    fun `a phone no longer paired is owed nothing, and another phone's ack lets none go`() = runTest {
        val finals = finals()
        store.removePhone(galaxy)
        finals.ended(episode(session("s-1")), 1_930_000, 3_120_000)
        finals.ended(episode(session("s-2", tablet)), 1_930_000, 3_120_000)
        runCurrent()
        assertEquals(emptyList(), store.owed(galaxy))
        finals.acked(galaxy, listOf("f-1", "f-2"))
        runCurrent()
        assertEquals(listOf("f-2"), store.owed(tablet).map { it.id })
        assertTrue(store.owed(tablet).single().session == "s-2")
    }

    /** A paired phone's link that notes when each frame came, as a phone's link counts them (L24: 20 a second, the rest dropped). */
    private class Timed(override val phone: KeyId, private val clock: () -> Long) : PhoneLink {
        val heard = mutableListOf<Pair<Long, LinkMessage>>()

        override fun send(message: LinkMessage) {
            heard += clock() to message
        }

        /** The most frames that came within any one second. */
        val busiest: Int get() = heard.maxOfOrNull { (at, _) -> heard.count { (other, _) -> other in at until at + 1_000 } } ?: 0
    }

    @Test
    fun `what a phone is owed goes out a few at a time, at a pace its link takes, all of it, oldest first`() = runTest {
        val finals = finals()
        repeat(30) { finals.ended(episode(session("s-1")), 1_930_000L + it, 3_120_000) }
        runCurrent()
        val link = Timed(galaxy) { testScheduler.currentTime }
        finals.linked(link)
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals((1..30).map { "f-$it" }, link.heard.map { (it.second as Final).id })
        assertTrue(link.busiest <= 10, "at most 10 in any second, so the TV's statuses and Pongs still fit in the phone's 20: ${link.busiest}")

        // A link that closes is sent no more of it.
        repeat(20) { finals.ended(episode(session("s-1")), 1_000L + it, 3_120_000) }
        runCurrent()
        val short = Timed(galaxy) { testScheduler.currentTime }
        finals.linked(short)
        runCurrent()
        finals.closed(short)
        advanceTimeBy(10_000)
        assertTrue(short.heard.size < 50, "stopped as it closed: ${short.heard.size}")
    }
}
