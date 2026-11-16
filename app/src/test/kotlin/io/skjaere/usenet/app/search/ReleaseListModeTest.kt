package io.skjaere.usenet.app.search

import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A release list in download mode (L355, L386): its title, the line under its sorts with the free space and when it
 * starts, a release that won't fit and what it needs, and a season pack's share of the one episode. Invented releases.
 */
class ReleaseListModeTest {
    private val saved = Locale.getDefault()

    // formatSize follows the default locale; pin one so the decimal separator is predictable.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    private val gb = 1_000_000_000L

    private fun item(title: String, size: Long, indexer: String = "a", hash: String? = null) = RankedItem(
        NewznabItem(
            indexerId = indexer, guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
            infoHash = hash,
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

    /**
     * Choose file in download mode (L403): on a Usenet release that holds several episodes (a season pack, a release of two)
     * and fits, where the list offers it at all; greyed "Needs Wi-Fi" on mobile data while downloads wait for Wi-Fi or
     * Ethernet, as opening its pack reads tens of MB; never on a single episode, a release that won't fit, or a torrent.
     */
    @Test
    fun `Choose file is offered on a Usenet pack that fits, greyed on mobile data, and never on a single episode, one too big or a torrent`() {
        val chosen = mutableListOf<RankedItem>()
        fun mode(free: Long, startsOnWifi: Boolean = false) = ReleaseListMode.Download(free, startsOnWifi, seasonEpisodes = 8, onChooseFile = { chosen += it }) {}
        val two = item(TWO, 2_400_000_000)
        val torrentPack = item(PACK_TORRENT, 11_200_000_000, indexer = "t", hash = "0123456789abcdef0123456789abcdef01234567")
        assertEquals(ReleaseListMode.Download.FileChoiceOffer.Offered, mode(3 * gb).fileChoice(pack))
        assertEquals(ReleaseListMode.Download.FileChoiceOffer.Offered, mode(3 * gb).fileChoice(two))
        assertNull(mode(3 * gb).fileChoice(episode), "a single episode")
        // The pack's share of 1.4 GB doesn't fit in 1.0 GB: its card is greyed, and offers nothing.
        assertFalse(mode(gb).fits(pack))
        assertNull(mode(gb).fileChoice(pack), "a greyed card")
        assertNull(mode(14 * gb).fileChoice(torrentPack), "a torrent")
        assertEquals(ReleaseListMode.Download.FileChoiceOffer.NeedsWifi, mode(3 * gb, startsOnWifi = true).fileChoice(pack))
        assertEquals("Needs Wi-Fi", ReleaseListMode.Download.NEEDS_WIFI)
        // A list that offers it on no card.
        assertNull(download(14 * gb).fileChoice(pack))
        assertEquals(emptyList(), chosen)
    }

    /**
     * The list's cards take Choose file in download mode from the mode ([ReleaseListMode.Download.fileChoice]): offered there
     * as it says, greyed with its line; a play's list keeps its own rule (a release of several episodes, its own onChooseFile).
     * Read from ReleaseList's source, as the card is a composable: the old rule, which kept it off every download list, gone.
     */
    @Test
    fun `the list's cards offer Choose file in download mode as the mode says, greyed with its line, and a play's as before`() {
        val source = java.io.File("src/main/kotlin/io/skjaere/usenet/app/search/ReleaseList.kt").readText()
        assertFalse("download == null && item.offersFileChoice" in source, "Choose file kept off every download list")
        listOf(
            "val offer = download?.fileChoice(item)",
            "offer != null -> download.onChooseFile?.let { choose -> { choose(item) } }",
            "download == null -> onChooseFile?.takeIf { item.offersFileChoice }?.let { choose -> { choose(item) } }",
            "chooseFileNote = ReleaseListMode.Download.NEEDS_WIFI.takeIf { offer == ReleaseListMode.Download.FileChoiceOffer.NeedsWifi }",
            "enabled = chooseFileNote == null",
        ).forEach { assertTrue(it in source, "ReleaseList.kt lacks: $it") }
    }

    private companion object {
        const val TWO = "Harbor.Lights.S02E04E05.1080p.WEB-DL.DDP5.1.H.264-WREN"
        const val PACK_TORRENT = "Harbor.Lights.S02.1080p.WEB.H264-SALTMARSH"
        const val KESTREL = "Harbor.Lights.S02E04.1080p.WEB-DL.DDP5.1.H.265-KESTREL"
        const val PACK = "Harbor.Lights.S02.1080p.WEB-DL.DDP5.1.H.264-DRIFTWOOD"
        const val FERRY = "Harbor.Lights.S02E04.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY"
        const val COPPER = "Copper.Crown.2022.1080p.WEB-DL.DDP5.1.H.264-KESTREL"
    }
}
