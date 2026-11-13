package io.skjaere.usenet.app.link

import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.link.LinkMessage.Play
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * L39: where a phone's play starts on the TV. The phone's place for the episode goes against the TV's own, by when each
 * was saved: the phone's age turned into the TV's clock (L25), so a TV clock that is wrong by a constant gives the same
 * answer. The newer wins; the phone's place is 0 when watched or under a minute in; null leaves the TV's own.
 */
class PlayFromTest {
    private val now = 1_800_000_000_000L
    private val key = WatchKey.episode(900002, 2, 4)

    /** The TV's own row for the episode, saved [savedAgoMs] before [at] (the TV's clock), at [positionMs]. */
    private fun tvRow(positionMs: Long, savedAgoMs: Long, at: Long = now) = WatchState(
        key, WatchKind.Episode, showTmdbId = 900002, season = 2, episode = 4, title = "Harbor Lights S02E04",
        positionMs = positionMs, durationMs = 3_120_000, updatedAt = at - savedAgoMs,
    )

    @Test
    fun `the phone's place wins when it was saved after the TV's`() {
        // The phone saved 32:10 five minutes ago; the TV saved 12:00 yesterday.
        val from = Play.From(positionMs = 1_930_000, watched = false, ageMs = 300_000)
        assertEquals(1_930_000, PlayFrom.start(from, tvRow(720_000, savedAgoMs = 86_400_000), now))
    }

    @Test
    fun `the TV's own place wins when it was saved after the phone's`() {
        val from = Play.From(positionMs = 1_930_000, watched = false, ageMs = 86_400_000)
        assertNull(PlayFrom.start(from, tvRow(720_000, savedAgoMs = 300_000), now), "the TV's own place")
    }

    @Test
    fun `saved at the same moment, the TV's own place is kept`() {
        val from = Play.From(positionMs = 1_930_000, watched = false, ageMs = 300_000)
        assertNull(PlayFrom.start(from, tvRow(720_000, savedAgoMs = 300_000), now))
    }

    @Test
    fun `with no row on the TV the phone's place is taken`() {
        assertEquals(1_930_000, PlayFrom.start(Play.From(1_930_000, watched = false, ageMs = 300_000), tv = null, now = now))
    }

    @Test
    fun `without the phone's place the TV's own is kept`() {
        assertNull(PlayFrom.start(from = null, tv = tvRow(720_000, savedAgoMs = 300_000), now = now))
        assertNull(PlayFrom.start(from = null, tv = null, now = now))
    }

    @Test
    fun `watched on the phone, or under a minute in, starts from the beginning`() {
        val older = tvRow(720_000, savedAgoMs = 86_400_000)
        assertEquals(0, PlayFrom.start(Play.From(positionMs = 0, watched = true, ageMs = 300_000), older, now), "watched")
        assertEquals(0, PlayFrom.start(Play.From(positionMs = 2_900_000, watched = true, ageMs = 300_000), older, now), "watched, a place kept")
        assertEquals(0, PlayFrom.start(Play.From(positionMs = 59_999, watched = false, ageMs = 300_000), older, now), "under a minute in")
        assertEquals(60_000, PlayFrom.start(Play.From(positionMs = 60_000, watched = false, ageMs = 300_000), older, now), "a minute in")
    }

    @Test
    fun `a TV clock a year behind or an hour ahead gives the same answer`() {
        val year = 365L * 86_400_000
        val hour = 3_600_000L
        val newer = Play.From(positionMs = 1_930_000, watched = false, ageMs = 300_000)
        val older = Play.From(positionMs = 1_930_000, watched = false, ageMs = 86_400_000)
        for (shift in listOf(0L, -year, hour)) {
            val tvNow = now + shift
            // The TV's row was written by the same wrong clock: 2 hours before now, as it reads it.
            val row = tvRow(720_000, savedAgoMs = 7_200_000, at = tvNow)
            assertEquals(1_930_000, PlayFrom.start(newer, row, tvNow), "the phone's newer, the TV's clock off by $shift ms")
            assertNull(PlayFrom.start(older, row, tvNow), "the TV's newer, the TV's clock off by $shift ms")
        }
    }

    @Test
    fun `an age past the TV's own clock, or below zero, is clamped`() {
        // A phone that says it saved before the TV's epoch: as old as can be, so the TV's own row wins.
        assertNull(PlayFrom.start(Play.From(1_930_000, watched = false, ageMs = now + 5), tvRow(720_000, savedAgoMs = 86_400_000), now))
        // An age below zero is now: newer than any row the TV saved before now.
        assertEquals(1_930_000, PlayFrom.start(Play.From(1_930_000, watched = false, ageMs = -60_000), tvRow(720_000, savedAgoMs = 1), now))
    }
}
