package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.TvState
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.link.KeyId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A show's downloads (item 19, L372): its finished episodes by season, what Resume at the top plays (the episode begun
 * most lately, else the next not watched after the furthest watched), and each episode's words: size and length, what is
 * left of one begun, its missing parts. Invented show and episodes.
 */
class DownloadsShowTest {
    private val saved = Locale.getDefault()

    // formatSize follows the default locale; pin one so the decimal separator is predictable.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    @Test
    fun `Resume plays the episode begun most lately, from where it stopped`() {
        val rows = (1..3).map { episode(1, it) } + (1..3).map { episode(2, it) }
        val states = mapOf(
            (1 to 1) to watched(1, 1), (1 to 2) to watched(1, 2), (1 to 3) to watched(1, 3),
            (2 to 1) to state(2, 1, positionMs = 20 * 60_000L, durationMs = 52 * 60_000L, lastPlayedAt = 5),
        )
        val next = assertIs<ShowNext.Resume>(DownloadsShow.next(rows, states))
        assertEquals("s2e1", next.row.id)
        assertEquals("Resume S02E01 · 20:00", DownloadsShow.button(next))

        // Two begun: the one played last.
        val two = states + ((2 to 3) to state(2, 3, positionMs = 5 * 60_000L, durationMs = 51 * 60_000L, lastPlayedAt = 9))
        assertEquals("s2e3", DownloadsShow.next(rows, two)!!.row.id)
        // Under a minute in is not begun, as the Show page reads it.
        val barely = states + ((2 to 1) to state(2, 1, positionMs = 30_000L, durationMs = 52 * 60_000L))
        assertEquals(ShowNext.Play(rows[3]), DownloadsShow.next(rows, barely))
    }

    @Test
    fun `nothing begun, Resume plays the first not watched after the furthest watched, else the first not watched`() {
        val rows = listOf(episode(1, 1), episode(1, 4), episode(1, 5), episode(2, 1))
        // S01E01 skipped, S01E03 (not downloaded) watched: the next after it.
        val states = mapOf((1 to 3) to watched(1, 3))
        val next = assertIs<ShowNext.Play>(DownloadsShow.next(rows, states))
        assertEquals("s1e4", next.row.id)
        assertEquals("Play S01E04", DownloadsShow.button(next))
        assertEquals("Next: S01E04", DownloadsPage.nextLine(next))
        // Watched past every download: the first not watched.
        assertEquals("s1e1", DownloadsShow.next(rows, mapOf((3 to 1) to watched(3, 1)))!!.row.id)
        assertEquals("s1e1", DownloadsShow.next(rows, emptyMap())!!.row.id)
        // All watched: no Resume.
        assertNull(DownloadsShow.next(rows, rows.associate { (it.season!! to it.episode!!) to watched(it.season!!, it.episode!!) }))
    }

    @Test
    fun `its seasons hold its finished episodes in order, the rest left on the Downloads page`() {
        val rows = listOf(
            episode(2, 2), episode(1, 3), episode(2, 1), episode(1, 1),
            episode(2, 3, state = DownloadState.Running), episode(2, 4, state = DownloadState.Failed), episode(2, 5, state = DownloadState.Deleting),
            episode(0, 1),
        )
        val seasons = DownloadsShow.seasons(rows)
        assertEquals(listOf(0, 1, 2), seasons.map { it.number })
        assertEquals(listOf(listOf("s0e1"), listOf("s1e1", "s1e3"), listOf("s2e1", "s2e2")), seasons.map { season -> season.episodes.map { it.id } })
        assertEquals(listOf("Specials", "Season 1", "Season 2"), seasons.map { DownloadsShow.seasonTitle(it.number) })
        assertEquals("5 episodes · 8.0 GB", DownloadsPage.showSub(DownloadsShow.ordered(rows)))
        assertEquals("1 episode · 1.6 GB", DownloadsPage.showSub(listOf(episode(3, 1))))
    }

    @Test
    fun `an episode says its size and length, what is left of one begun, and its missing parts`() {
        val row = episode(2, 1, size = 2_100_000_000, runtime = 52)
        assertEquals("Brine", DownloadsShow.name(row))
        assertEquals("Episode 7", DownloadsShow.name(episode(2, 7).copy(episodeName = null)))
        assertEquals("2.1 GB · 52 min", DownloadsShow.meta(row, null))
        // No length kept: as its last play measured it.
        assertEquals("2.0 GB · 49 min", DownloadsShow.meta(episode(2, 2, size = 2 * GB), state(2, 2, positionMs = 0, durationMs = 49 * 60_000L)))
        assertEquals("1.6 GB", DownloadsShow.meta(episode(2, 3), null))
        assertEquals("1 h 5 min", DownloadsPage.duration(65))

        val begun = state(2, 1, positionMs = 20 * 60_000L, durationMs = 52 * 60_000L)
        assertEquals("32:00 left", DownloadsShow.left(begun))
        assertEquals(20f / 52f, DownloadsShow.progress(begun)!!, 0.0001f)
        assertNull(DownloadsShow.left(watched(2, 1)))
        assertNull(DownloadsShow.left(state(2, 1, positionMs = 30_000L, durationMs = 52 * 60_000L)))
        assertEquals("3 parts missing", DownloadsPage.missing(row.copy(gaps = (0..2).map { DownloadGap(it * 10L, it * 10L + 1) })))
    }

    /** C4, L415: with Delete once watched on, an episode going tomorrow says so on its own line, under its size and length. */
    /**
     * L418, L470: Resume and an episode's tap play where a download row plays: the Cast device chosen, connected or
     * connecting, its name in Resume's face and TalkBack's action; This phone with This phone or a TV chosen, as now.
     */
    @Test
    fun `Resume and an episode's tap say the Cast device chosen, and This phone with a TV chosen`() {
        val tv = PlayTarget.Tv(KeyId.of(Random.nextBytes(91)), "Living room TV", TvState.Ready)
        val kitchen = PlayTarget.Cast("route-1", "Kitchen display", CastLink.Connected)
        val connecting = kitchen.copy(link = CastLink.Connecting)
        // Resume's PlayOnPrimary is given where the row plays: a TV read as This phone.
        assertEquals(listOf(PlayTarget.Phone, PlayTarget.Phone, kitchen, connecting), listOf(PlayTarget.Phone, tv, kitchen, connecting).map(DownloadsPage::playsOn))
        assertEquals(
            listOf("Play", "Play", "Play on Kitchen display", "Play on Kitchen display"),
            listOf(PlayTarget.Phone, tv, kitchen, connecting).map(DownloadsShow::playAction),
        )
    }

    @Test
    fun `an episode going tomorrow says Deleting tomorrow`() {
        val row = episode(1, 1)
        assertEquals("Deleting tomorrow", DownloadsShow.deleting(row, setOf(row.id, "other")))
        assertNull(DownloadsShow.deleting(row, setOf("other")))
        assertNull(DownloadsShow.deleting(row, emptySet()))
    }

    private companion object {
        const val GB = 1_000_000_000L
        const val HL = 990_617

        val NAMES = mapOf(1 to "Brine", 2 to "The Breakwater", 3 to "Fog Signal")

        fun episode(season: Int, episode: Int, state: DownloadState = DownloadState.Done, size: Long = 1_600_000_000, runtime: Int? = null) = Download(
            id = "s${season}e$episode", watchKey = WatchKey.episode(HL, season, episode), kind = DownloadKind.Episode, title = "Harbor Lights",
            showTmdbId = HL, season = season, episode = episode, episodeName = NAMES[episode], runtimeMinutes = runtime, state = state, position = 0,
            size = size, indexerSize = size, relativePath = "downloads/s${season}e$episode/v.mkv", createdAt = 1, completedAt = 1, updatedAt = 1,
        )

        fun watched(season: Int, episode: Int) = WatchState(
            key = WatchKey.episode(HL, season, episode), kind = WatchKind.Episode, showTmdbId = HL, season = season, episode = episode, title = "Harbor Lights",
            watched = true, lastPlayedAt = 1, updatedAt = 1,
        )

        fun state(season: Int, episode: Int, positionMs: Long, durationMs: Long, lastPlayedAt: Long = 2) = WatchState(
            key = WatchKey.episode(HL, season, episode), kind = WatchKind.Episode, showTmdbId = HL, season = season, episode = episode, title = "Harbor Lights",
            positionMs = positionMs, durationMs = durationMs, lastPlayedAt = lastPlayedAt, updatedAt = 1,
        )
    }
}
