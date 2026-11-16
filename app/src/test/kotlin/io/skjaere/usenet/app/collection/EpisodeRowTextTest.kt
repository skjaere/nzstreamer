package io.skjaere.usenet.app.collection

import io.skjaere.usenet.app.download.DownloadEntry
import io.skjaere.usenet.app.download.DownloadHostRules
import io.skjaere.usenet.app.download.DownloadMark
import io.skjaere.usenet.app.download.PageLine
import io.skjaere.usenet.app.download.TitleDownload
import io.skjaere.usenet.app.download.TitleDownloads
import io.skjaere.usenet.app.download.Tone
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.hasAired
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An episode TMDB lists as not aired yet still plays (releases often come early); its row only says so. On a phone the row
 * also says how its download is (L384, DlPhoneEpisodeRow): a line under the date, its mark, what TalkBack hears, and the
 * download's items in its ⋮ by state (L380).
 */
class EpisodeRowTextTest {
    private val saved = Locale.getDefault()

    // Sizes are said with the device's decimal mark: these read as written here.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    /** In this year, so shortDate leaves the year out. */
    private val airs = LocalDate.now().withMonth(10).withDayOfMonth(20)
    private val today = airs.minusDays(5)

    private fun episode(season: Int, number: Int, airDate: LocalDate?) = Episode(season, number, "Episode $number", null, airDate, 45, null)

    /** Glasswater Bay: S01E01 aired, S01E02 airs on the 20th, S01E03 has no date yet; one special, undated. */
    private val show = ShowDetails(
        tmdbId = 900041, name = "Glasswater Bay", originalName = null, year = airs.year, overview = null, posterPath = null, backdropPath = null,
        status = "Returning Series", inProduction = true, episodeRuntimeMinutes = 45,
        seasons = listOf(SeasonSummary(1, "Season 1", 3, null, null), SeasonSummary(0, "Specials", 1, null, null)),
        lastEpisode = EpisodeSummary(1, 1, null, today.minusDays(7)), nextEpisode = EpisodeSummary(1, 2, null, airs),
        ids = ExternalIds(),
    )

    private fun row(e: Episode) = episodeRowText(e, show.hasAired(e, today))

    @Test
    fun `an aired episode gives its date and runtime, and nothing more for TalkBack`() {
        val text = row(episode(1, 1, today.minusDays(7)))
        assertEquals("${shortDate(today.minusDays(7))} · 45 min", text.meta)
        assertNull(text.stateDescription)
    }

    @Test
    fun `an episode TMDB lists for a later date says when it airs, and TalkBack that it hasn't aired by TMDB`() {
        val text = row(episode(1, 2, airs))
        assertEquals("Airs 20 Oct · 45 min", text.meta)
        assertEquals("Hasn't aired yet according to TMDB", text.stateDescription)
    }

    @Test
    fun `one with no date after the last aired episode says it hasn't aired, as does an undated special`() {
        listOf(episode(1, 3, null), episode(0, 1, null)).forEach { e ->
            val text = row(e)
            assertEquals("Not aired yet · 45 min", text.meta, "S${e.season}E${e.episode}")
            assertEquals("Hasn't aired yet according to TMDB", text.stateDescription, "S${e.season}E${e.episode}")
        }
    }

    @Test
    fun `one with no date up to the last aired episode counts as aired`() {
        val text = row(episode(1, 1, null))
        assertEquals("45 min", text.meta)
        assertNull(text.stateDescription)
    }

    /** A download of the row's episode, as its page reads it from the runner. */
    private fun dl(
        state: DownloadState,
        reason: String? = null,
        percent: Int? = null,
        size: Long = 1_300_000_000,
        missing: Int = 0,
        tried: Int = 0,
    ) = TitleDownload("dl-row", state, reason, percent, size, missing, tried)

    private val aired = episode(1, 1, today.minusDays(7))

    private fun line(d: TitleDownload) = episodeRowText(aired, aired = true, download = d).download

    @Test
    fun `each download state says its line under the date, in the words and colours drawn`() {
        assertEquals(listOf(PageLine("Downloaded · 1.3 GB", Tone.Muted)), line(dl(DownloadState.Done)))
        assertEquals(listOf(PageLine("Downloaded · ", Tone.Muted), PageLine("2 parts missing", Tone.Warn)), line(dl(DownloadState.Done, missing = 2)))
        assertEquals(listOf(PageLine("Downloading · 42%", Tone.Accent)), line(dl(DownloadState.Running, percent = 42)))
        assertEquals(listOf(PageLine("Queued for download", Tone.Muted)), line(dl(DownloadState.Queued)))
        assertEquals(listOf(PageLine("Paused · 18%", Tone.Muted)), line(dl(DownloadState.Paused, percent = 18)))
        assertEquals(listOf(PageLine("Download failed", Tone.Warn)), line(dl(DownloadState.Failed, tried = 3)))
        assertEquals(listOf(PageLine("Finding releases…", Tone.Muted)), line(dl(DownloadState.Resolving, DownloadHostRules.FINDING_RELEASES)))
        // Resolve's later steps on its first release are still its finding; from the second release on, which one it tries.
        assertEquals(listOf(PageLine("Finding releases…", Tone.Muted)), line(dl(DownloadState.Resolving, "Checking availability")))
        assertEquals(listOf(PageLine("Trying release 2 of 3", Tone.Accent)), line(dl(DownloadState.Resolving, "Trying release 2 of 3 · Checking availability")))
        assertEquals(listOf(PageLine("Waiting for Wi-Fi", Tone.Muted)), line(dl(DownloadState.Waiting, DownloadHostRules.WAITING_FOR_WIFI)))
        // Not drawn: the waits only a Resume or a saved provider ends, a stop by Android, the provider out of reach as it
        // writes, and a pause before its file is made.
        assertEquals(listOf(PageLine("Check your Usenet provider", Tone.Warn)), line(dl(DownloadState.Waiting, DownloadHostRules.CHECK_PROVIDER)))
        assertEquals(listOf(PageLine("Stopped by Android · 18%", Tone.WarnStrong)), line(dl(DownloadState.Stopped, percent = 18)))
        assertEquals(
            listOf(PageLine("Can't reach your Usenet provider · 42%", Tone.Warn)),
            line(dl(DownloadState.Running, DownloadHostRules.CANT_REACH_PROVIDER, percent = 42)),
        )
        assertEquals(listOf(PageLine("Paused", Tone.Muted)), line(dl(DownloadState.Paused)))
        // No download: the row as it was.
        assertNull(episodeRowText(aired, aired = true).download)
    }

    @Test
    fun `TalkBack hears the download's line as the row's state, after an unaired episode's`() {
        fun said(d: TitleDownload, isAired: Boolean = true) = episodeRowText(aired, isAired, d).stateDescription
        assertEquals("Downloading, 42 percent", said(dl(DownloadState.Running, percent = 42)))
        assertEquals("Queued for download", said(dl(DownloadState.Queued)))
        assertEquals("Download paused, 18 percent", said(dl(DownloadState.Paused, percent = 18)))
        assertEquals("Downloaded", said(dl(DownloadState.Done)))
        assertEquals("Downloaded, 2 parts missing", said(dl(DownloadState.Done, missing = 2)))
        assertEquals("Download failed", said(dl(DownloadState.Failed)))
        assertEquals("Finding releases", said(dl(DownloadState.Resolving, DownloadHostRules.FINDING_RELEASES)))
        assertEquals("Trying release 2 of 3", said(dl(DownloadState.Resolving, "Trying release 2 of 3 · Fetching NZB")))
        assertEquals("Waiting for Wi-Fi", said(dl(DownloadState.Waiting, DownloadHostRules.WAITING_FOR_WIFI)))
        assertEquals("Hasn't aired yet according to TMDB, Downloaded", said(dl(DownloadState.Done), isAired = false))
    }

    @Test
    fun `the mark beside the tick follows the state, never in the watched tick's colour`() {
        assertEquals(DownloadMark.Queued, TitleDownloads.mark(dl(DownloadState.Queued)))
        assertEquals(DownloadMark.Queued, TitleDownloads.mark(dl(DownloadState.Waiting, DownloadHostRules.WAITING_FOR_WIFI)))
        assertEquals(DownloadMark.Finding, TitleDownloads.mark(dl(DownloadState.Resolving)))
        assertEquals(DownloadMark.Progress(0.42f, held = false), TitleDownloads.mark(dl(DownloadState.Running, percent = 42)))
        assertEquals(DownloadMark.Progress(0.18f, held = true), TitleDownloads.mark(dl(DownloadState.Paused, percent = 18)))
        assertEquals(DownloadMark.Progress(0f, held = true), TitleDownloads.mark(dl(DownloadState.Stopped)))
        assertEquals(DownloadMark.Done, TitleDownloads.mark(dl(DownloadState.Done, missing = 2)))
        assertEquals(DownloadMark.Failed, TitleDownloads.mark(dl(DownloadState.Failed)))
    }

    @Test
    fun `the menu keeps Choose release and Mark watched, then the download's items by state`() {
        fun menu(d: TitleDownload?, watched: Boolean = false, usenet: Boolean = true) =
            episodeMenu(watched, TitleDownloads.entries(d, usenet)).map { it.label }
        val base = listOf("Choose release", "Mark watched")
        assertEquals(base + "Download", menu(null))
        // No Usenet provider saved and on: no Download (L370).
        assertEquals(base, menu(null, usenet = false))
        // The running item: the queue's pause (L380).
        listOf(dl(DownloadState.Running), dl(DownloadState.Resolving), dl(DownloadState.Waiting, DownloadHostRules.WAITING_FOR_WIFI)).forEach {
            assertEquals(base + listOf("Pause download", "Remove download"), menu(it), it.toString())
        }
        assertEquals(base + "Remove download", menu(dl(DownloadState.Queued)))
        listOf(dl(DownloadState.Paused), dl(DownloadState.Stopped), dl(DownloadState.Waiting, DownloadHostRules.CHECK_PROVIDER)).forEach {
            assertEquals(base + listOf("Resume download", "Remove download"), menu(it), it.toString())
        }
        assertEquals(listOf("Choose release", "Mark unwatched", "Remove download"), menu(dl(DownloadState.Done, missing = 2), watched = true))
        assertEquals(base + listOf("Download again", "Remove download"), menu(dl(DownloadState.Failed), usenet = false))
        // Nothing of downloads where they don't show: the TV's row, which is given none.
        assertEquals(base, episodeMenu(false, emptyList()).map { it.label })
        assertEquals(DownloadEntry.Pause, (episodeMenu(false, listOf(DownloadEntry.Pause)).last() as EpisodeMenuItem.Download).entry)
    }
}
