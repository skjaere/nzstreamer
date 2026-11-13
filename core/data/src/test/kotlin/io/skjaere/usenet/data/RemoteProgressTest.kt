package io.skjaere.usenet.data

import io.skjaere.usenet.link.FinalKind
import io.skjaere.usenet.link.LinkMessage.Final
import io.skjaere.usenet.link.Outbox
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * W5, L11, L33, item 27: where a play this phone started on a TV stopped, as the phone saves it. A Final counts only for
 * one of this phone's own sessions on that TV and the same show or movie, its watch key worked out again from its ids;
 * it writes only over a row that isn't newer, with the Final's own time (this phone's now less its age, clamped), the
 * position (none once watched) and the phone's remembered release, never the TV's. The titles are invented.
 */
class RemoteProgressTest {
    private val now = 1_800_000_000_000L
    private val show = 4_101
    private val film = 7_707
    private val e4 = WatchKey.episode(show, 2, 4)

    private fun episode(position: Long = 1_930_000, watched: Boolean = false, age: Long = 5_000, session: String = "s-1", key: String = e4, number: Int = 4) = Final(
        "f-1", session, key, FinalKind.Episode, "Harbor Lights S02E0$number", showTmdbId = show, season = 2, episode = number,
        positionMs = position, durationMs = 3_120_000, watched = watched, ageMs = age,
    )

    private fun movie(session: String = "s-2") = Final(
        "f-2", session, WatchKey.movie(film), FinalKind.Movie, "North Quay", movieTmdbId = film, positionMs = 600_000, durationMs = 7_080_000, watched = false, ageMs = 0,
    )

    private val sessions = listOf(TvSession("s-1", now - 3_600_000, showTmdbId = show), TvSession("s-2", now - 7_200_000, movieTmdbId = film))

    private val release = RememberedRelease("phone-indexer", "guid-1", "Harbor.Lights.S02E04.1080p.WEB-DL-GRP", null, 0)

    private fun row(updatedAt: Long, position: Long = 600_000, watched: Boolean = false, deleted: Boolean = false) = WatchState(
        e4, WatchKind.Episode, showTmdbId = show, season = 2, episode = 4, title = "Harbor Lights S02E04", positionMs = position, durationMs = 3_120_000,
        watched = watched, lastPlayedAt = updatedAt, release = release, updatedAt = updatedAt, deleted = deleted,
    )

    @Test
    fun `a Final of one of this phone's sessions, for the same show, is its episode's, worked out again from its ids`() {
        assertEquals(e4, RemoteProgress.key(episode(), sessions))
        // Auto-advance's next episode, in the same session: the same show counts.
        assertEquals(WatchKey.episode(show, 2, 5), RemoteProgress.key(episode(key = WatchKey.episode(show, 2, 5), number = 5), sessions))
        assertEquals(WatchKey.movie(film), RemoteProgress.key(movie(), sessions))
    }

    @Test
    fun `a Final of another session, another title, or a watch key its ids don't make is dropped`() {
        assertNull(RemoteProgress.key(episode(session = "s-other"), sessions), "not this phone's session there")
        assertNull(RemoteProgress.key(episode(session = "s-2"), sessions), "that session was a movie's")
        assertNull(RemoteProgress.key(movie(session = "s-1"), sessions), "that session was a show's")
        assertNull(RemoteProgress.key(episode(key = WatchKey.episode(show, 2, 9)), sessions), "the watch key isn't its ids'")
        assertNull(RemoteProgress.key(episode(key = WatchKey.movie(film)), sessions))
        assertNull(RemoteProgress.key(episode().copy(kind = FinalKind.Unknown), sessions), "a kind this version doesn't know")
        assertNull(RemoteProgress.key(episode().copy(season = null), sessions))
        assertNull(RemoteProgress.key(episode(), emptyList()), "no session kept for that TV")
    }

    @Test
    fun `it writes where it stopped, as of the Final's own time, keeping the phone's remembered release`() {
        val written = RemoteProgress.decide(row(updatedAt = now - 60_000), episode(age = 5_000), now)
        assertEquals(
            row(updatedAt = now - 5_000, position = 1_930_000).copy(lastPlayedAt = now - 5_000), written,
            "updatedAt and lastPlayedAt are the Final's time, the release the phone's",
        )
        val fresh = RemoteProgress.decide(null, episode(age = 0), now)
        assertEquals(
            WatchState(e4, WatchKind.Episode, showTmdbId = show, season = 2, episode = 4, title = "Harbor Lights S02E04", positionMs = 1_930_000, durationMs = 3_120_000, lastPlayedAt = now, updatedAt = now),
            fresh, "a row of its own where the phone had none, no release",
        )
        val back = RemoteProgress.decide(row(updatedAt = now - 60_000, deleted = true), episode(), now)
        assertEquals(false, back?.deleted, "a row marked unwatched before it comes back")
        assertNull(back?.release, "a tombstone's release isn't the phone's any more")
    }

    @Test
    fun `a newer row on the phone is kept`() {
        assertNull(RemoteProgress.decide(row(updatedAt = now - 4_000), episode(age = 5_000), now))
        assertNull(RemoteProgress.decide(row(updatedAt = now - 4_000, deleted = true), episode(age = 5_000), now), "a mark of unwatched since too")
        assertEquals(now - 5_000, RemoteProgress.decide(row(updatedAt = now - 5_000), episode(age = 5_000), now)?.updatedAt, "one as old isn't newer")
    }

    @Test
    fun `watched means position 0 and watched, and a play under a minute into a watched episode leaves it watched`() {
        val watched = RemoteProgress.decide(row(updatedAt = now - 60_000), episode(position = 3_000_000, watched = true), now)
        assertEquals(0L to true, watched?.positionMs to watched?.watched)
        val rewatch = RemoteProgress.decide(row(updatedAt = now - 60_000, position = 0, watched = true), episode(position = 40_000), now)
        assertEquals(40_000L to true, rewatch?.positionMs to rewatch?.watched, "as a play here: the episode stays watched")
        val further = RemoteProgress.decide(row(updatedAt = now - 60_000, position = 0, watched = true), episode(position = 300_000), now)
        assertEquals(300_000L to false, further?.positionMs to further?.watched)
    }

    @Test
    fun `its time is this phone's now less its age, clamped`() {
        assertEquals(now - 5_000, RemoteProgress.at(episode(age = 5_000), now))
        assertEquals(now, RemoteProgress.at(episode(age = -9_000), now), "an age below 0 is now")
        assertEquals(0L, RemoteProgress.at(episode(age = now + 1), now), "an age past the clock's start is its start")
    }

    @Test
    fun `a TV clock a year behind or an hour ahead gives the same time`() {
        val made = episode(age = 0)
        val behind = now - 365L * 86_400_000
        val ahead = now + 3_600_000
        // The TV keeps it 8 s by its own clock, wrong as it is, and sends it with its age (L25).
        val fromBehind = Outbox().add(made, behind).due(behind + 8_000).single()
        val fromAhead = Outbox().add(made, ahead).due(ahead + 8_000).single()
        val a = RemoteProgress.decide(row(updatedAt = now - 60_000), fromBehind, now)
        val b = RemoteProgress.decide(row(updatedAt = now - 60_000), fromAhead, now)
        assertEquals(now - 8_000, a?.updatedAt)
        assertEquals(a, b)
    }
}
