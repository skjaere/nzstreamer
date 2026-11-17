package io.skjaere.usenet.app.collection

import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.download.DownloadEntry
import io.skjaere.usenet.app.download.DownloadRunner
import io.skjaere.usenet.app.download.ShowNext
import io.skjaere.usenet.app.download.TitleDownload
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.TvState
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeRef
import io.skjaere.usenet.metadata.NextUp
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L439: the Show page offline. An episode is greyed where it can't play: not downloaded (a finished
 * download its file plays from, as the play hook takes it), and played here (a TV or a Cast device chosen plays it
 * there); its tap says why in a bar. The header's Play greys as the rows do; the season's Download greys, as its sheet
 * searches the indexers first; an episode's ⋮ keeps Mark watched and its download's own entries, and greys what searches
 * (Choose release, Download). With no page from TMDB, the show's finished downloads are a row of their own, as the
 * Downloads page draws it. Online, nothing of it. Invented titles.
 */
class ShowOfflineTest {
    private val phone = ShowOffline(offline = true, playOn = PlayTarget.Phone)
    private val online = ShowOffline(offline = false, playOn = PlayTarget.Phone)
    private val tv = PlayTarget.Tv(KeyId.of(Random.nextBytes(91)), "Living room TV", TvState.Ready)
    private val cast = PlayTarget.Cast("route-1", "Kitchen display", CastLink.Connected)

    private val plays = TitleDownload("hl-4", DownloadState.Done, size = 1_400_000_000, plays = true)

    /** Finished, but with no release kept: the hook has no launch for it, so it would stream. */
    private val keptNoRelease = TitleDownload("hl-3", DownloadState.Done, size = 1_400_000_000)
    private val queued = TitleDownload("hl-5", DownloadState.Queued)

    @Test
    fun `an episode is greyed offline unless its file plays, and only where its play would go here`() {
        assertTrue(phone.greyed(null), "nothing downloaded")
        assertTrue(phone.greyed(queued), "only queued")
        assertTrue(phone.greyed(keptNoRelease), "finished, but not a file the hook plays")
        assertFalse(phone.greyed(plays), "its file plays")
        // A TV searches for it itself, and a Cast device plays what the phone serves: neither is here.
        assertFalse(ShowOffline(offline = true, playOn = tv).greyed(null))
        assertFalse(ShowOffline(offline = true, playOn = cast).greyed(null))
        assertFalse(online.greyed(null), "online")
    }

    @Test
    fun `a greyed episode's tap says which isn't downloaded and that it plays once online`() {
        val e = Episode(2, 5, "The Long Tide", null, LocalDate.of(2025, 4, 1), 50, null)
        assertEquals("Harbor Lights S02E05 isn't downloaded. It plays once you're online.", ShowOffline.bar("Harbor Lights", e))
    }

    @Test
    fun `the header's Play greys as its episode's row does`() {
        val downloads = mapOf((2 to 4) to plays, (2 to 5) to queued)
        assertFalse(phone.playGreyed(NextUp.Play(EpisodeRef(2, 4), firstEpisode = false), downloads), "the next one downloaded")
        assertTrue(phone.playGreyed(NextUp.Play(EpisodeRef(2, 5), firstEpisode = false), downloads), "the next one only queued")
        assertTrue(phone.playGreyed(NextUp.Resume(EpisodeRef(2, 6), 600_000), downloads), "the one begun not downloaded")
        assertFalse(phone.playGreyed(NextUp.Resume(EpisodeRef(2, 4), 600_000), downloads), "the one begun downloaded")
        // Up to date has no Play to grey.
        assertFalse(phone.playGreyed(NextUp.UpToDate(null, null), downloads))
        assertFalse(online.playGreyed(NextUp.Play(EpisodeRef(2, 5), firstEpisode = false), downloads))
        assertFalse(ShowOffline(offline = true, playOn = tv).playGreyed(NextUp.Play(EpisodeRef(2, 5), firstEpisode = false), downloads))
    }

    @Test
    fun `the season's Download greys offline`() {
        assertFalse(phone.seasonDownloadEnabled)
        // Wherever the play would go: its sheet searches this phone's indexers.
        assertFalse(ShowOffline(offline = true, playOn = tv).seasonDownloadEnabled)
        assertTrue(online.seasonDownloadEnabled)
    }

    @Test
    fun `an episode's menu offline keeps Mark watched and greys Choose release and Download`() {
        val notDownloaded = episodeMenu(watched = false, listOf(DownloadEntry.Download))
        assertEquals(listOf(false, true, false), notDownloaded.map(phone::enabled))
        // A finished one's Remove download, a failed one's Download again and Remove: its download's own, kept.
        val watched = episodeMenu(watched = true, listOf(DownloadEntry.DownloadAgain, DownloadEntry.Remove))
        assertEquals(listOf(false, true, true, true), watched.map(phone::enabled))
        assertEquals(listOf(true, true, true), notDownloaded.map(online::enabled))
    }

    private fun episode(show: Int, season: Int, number: Int, state: DownloadState, at: Long = number.toLong()) = Download(
        id = "e-$show-$season-$number", watchKey = WatchKey.episode(show, season, number), kind = DownloadKind.Episode, title = "Harbor Lights",
        showTmdbId = show, season = season, episode = number, state = state, position = at, size = 1_400_000_000L.takeIf { state == DownloadState.Done },
        indexerSize = 1_400_000_000, safeOffset = if (state == DownloadState.Done) 1_400_000_000 else 0,
        relativePath = if (state == DownloadState.Done) "downloads/e-$show-$season-$number/v.mkv" else null, posterPath = "/hl.jpg",
        createdAt = at, completedAt = at.takeIf { state == DownloadState.Done }, updatedAt = at,
    )

    private fun item(row: Download) = DownloadRunner.Item(row, row.safeOffset, null)

    @Test
    fun `with no page, the show's finished downloads are a row as the Downloads page draws it, and none without one`() {
        val watched = WatchState(
            WatchKey.episode(HL, 2, 1), WatchKind.Episode, showTmdbId = HL, season = 2, episode = 1, title = WatchTitle.episode("Harbor Lights", 2, 1),
            watched = true, updatedAt = 0,
        )
        assertNull(ShowOffline.downloaded(HL, emptyList(), emptyMap()), "no download")
        assertNull(ShowOffline.downloaded(HL, listOf(item(episode(HL, 2, 5, DownloadState.Queued))), emptyMap()), "only queued")
        val rows = listOf(2 to 1, 2 to 2, 2 to 4).map { (s, e) -> item(episode(HL, s, e, DownloadState.Done)) } + item(episode(HL, 2, 5, DownloadState.Queued))
        val row = assertNotNull(ShowOffline.downloaded(HL, rows, mapOf((2 to 1) to watched)))
        assertEquals(HL, row.showTmdbId)
        assertEquals("Harbor Lights", row.title)
        assertEquals("/hl.jpg", row.posterPath)
        assertEquals(listOf(1, 2, 4), row.episodes.map { it.episode })
        // What plays next, as the page says it: the first not watched after the one watched.
        assertEquals(2, assertIs<ShowNext.Play>(row.next).row.episode)
        // Another show's downloads are not this one's.
        assertNull(ShowOffline.downloaded(QM, rows, emptyMap()))
    }

    private companion object {
        /** Invented titles; nothing real has these ids. */
        const val HL = 990_821
        const val QM = 990_822
    }
}
