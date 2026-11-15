package io.skjaere.usenet.app.search

import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A release list in download mode (L355, L386): its title, the line under its sorts with the free space and when it
 * starts, a release that won't fit and what it needs, and a season pack's share of the one episode. Invented releases.
 */
class ReleaseListModeTest {
    private val gb = 1_000_000_000L

    private fun item(title: String, size: Long, indexer: String = "a") = RankedItem(
        NewznabItem(
            indexerId = indexer, guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("$indexer/$title", title, size), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
    )

    private val episode = item(KESTREL, 1_200_000_000)
    private val pack = item(PACK, 11_200_000_000, indexer = "b")
    private val remux = item(FERRY, 9_800_000_000, indexer = "c")

    private fun episodeGroup(vararg items: RankedItem) = ResultGroup("harbor lights|ep:2:4", GroupKind.Episode, 2, listOf(4), null, null, items.toList(), emptyList())

    private fun download(free: Long, startsOnWifi: Boolean = false, seasonEpisodes: Int? = 8) =
        ReleaseListMode.Download(free, startsOnWifi, seasonEpisodes) {}

    @Test
    fun `the title says what is downloaded, and the subtitle names the show or the film's year with its releases`() {
        val mode = download(14 * gb)
        assertEquals("Download S02E04" to "Harbor Lights · 3 releases from 3 indexers", mode.bar(episodeGroup(episode, pack, remux), "Harbor Lights"))
        val film = ResultGroup("copper crown|2022", GroupKind.Movie, null, emptyList(), null, 2022, listOf(item(COPPER, 4_300_000_000)), emptyList())
        assertEquals("Download Copper Crown" to "2022 · 1 release from 1 indexer", mode.bar(film, "Copper Crown"))
        assertEquals("Download Copper Crown" to "1 release from 1 indexer", mode.bar(film.copy(year = null), "Copper Crown"))
        // A play's list, as it always was.
        assertEquals("Harbor Lights · 3 releases from 3 indexers", "Harbor Lights · ${releasesFrom(episodeGroup(episode, pack, remux))}")
    }

    @Test
    fun `the line says it is ranked for downloading, the space free and what a tap does, and on mobile data that it starts on Wi-Fi`() {
        val group = episodeGroup(episode, pack, remux)
        assertEquals(listOf("Ranked for downloading: smaller first", "14.0 GB free on this device. A tap downloads the release."), download(14 * gb).lines(group))
        assertEquals(
            listOf("Ranked for downloading: smaller first", "14.0 GB free on this device. A tap downloads the release.", "Starts on Wi-Fi: this phone is on mobile data."),
            download(14 * gb, startsOnWifi = true).lines(group),
        )
        // With some too big: that those are greyed.
        assertEquals(listOf("Ranked for downloading: smaller first", "3.0 GB free on this device. Releases that won't fit are greyed."), download(3 * gb).lines(group))
    }

    @Test
    fun `a release that needs more than is free won't fit, and says what it needs`() {
        val mode = download(3 * gb)
        assertTrue(mode.fits(episode))
        assertNull(mode.wontFit(episode))
        assertFalse(mode.fits(remux))
        assertEquals("Won't fit: needs about 9.8 GB", mode.wontFit(remux))
        // Exactly what is free fits.
        assertTrue(download(1_200_000_000).fits(episode))
        assertFalse(download(1_199_999_999).fits(episode))
    }

    @Test
    fun `a season pack needs this episode's share, where the season's episodes are counted, else its whole size`() {
        val mode = download(3 * gb)
        assertEquals(1_400_000_000L, mode.need(pack))
        assertTrue(mode.fits(pack))
        assertEquals("Season pack · this episode about 1.4 GB", mode.packLine(pack))
        assertNull(mode.packLine(episode))
        // Its count not known: the whole pack's size, and no line.
        val uncounted = download(3 * gb, seasonEpisodes = null)
        assertEquals(11_200_000_000L, uncounted.need(pack))
        assertEquals("Won't fit: needs about 11.2 GB", uncounted.wontFit(pack))
        assertNull(uncounted.packLine(pack))
    }

    private companion object {
        const val KESTREL = "Harbor.Lights.S02E04.1080p.WEB-DL.DDP5.1.H.265-KESTREL"
        const val PACK = "Harbor.Lights.S02.1080p.WEB-DL.DDP5.1.H.264-DRIFTWOOD"
        const val FERRY = "Harbor.Lights.S02E04.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY"
        const val COPPER = "Copper.Crown.2022.1080p.WEB-DL.DDP5.1.H.264-KESTREL"
    }
}
