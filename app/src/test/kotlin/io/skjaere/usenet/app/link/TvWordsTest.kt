package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.data.KnownMissing
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.Retry
import io.skjaere.usenet.app.play.SearchProgress
import io.skjaere.usenet.app.play.checkingProvidersLine
import io.skjaere.usenet.app.play.noRelease
import io.skjaere.usenet.app.play.skippedLine
import io.skjaere.usenet.app.search.IndexerStatus
import io.skjaere.usenet.app.search.MovieFind
import io.skjaere.usenet.app.settings.NoUsableProvider
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.SheetKind
import io.skjaere.usenet.link.SheetStep
import io.skjaere.usenet.link.StepState
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.FindResult
import io.skjaere.usenet.search.IndexerReport
import io.skjaere.usenet.search.SearchStage
import io.skjaere.usenet.search.StageReport
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L37: the TV's lines as the phone shows them. The TV's sheet says them in its own words, of its own indexers and
 * providers, "your" and "this device"; the phone says them of the TV: "the TV's indexers", "the TV's providers". Every
 * line builder whose words reach a mirrored sheet is rewritten, and nothing else is: titles, releases and chips stay as
 * the TV sent them, whatever they hold. The titles are invented.
 */
class TvWordsTest {
    private val indexer = IndexerConfig("indexer-7", "Invented indexer", "https://indexer.invalid", "apikey")
    private val other = IndexerConfig("indexer-8", "Another indexer", "https://indexer.invalid/2", "apikey")

    private val episode = EpisodePlay(
        showTmdbId = 4_101, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tvdb = 5_202, tmdb = 4_101),
        season = 2, episode = 4, episodeName = "Low Water", airDate = LocalDate.of(2025, 9, 25), runtimeMinutes = 52,
    )

    /** What a phone may never show of a TV's line: this device's words. */
    private fun assertTheTvs(line: String) {
        for (word in listOf("your ", "Your ", "this device", "this screen", "read here")) assertFalse(word in line, "\"$word\" in: $line")
    }

    @Test
    fun `every search line names the TV's indexers`() {
        val progress = SearchProgress(indexers = 2)
        val lines = listOfNotNull(
            progress.after(FinderEvent.StageStarted(SearchStage.Episode, 2)),
            progress.after(FinderEvent.IndexerAnswered(SearchStage.Episode, IndexerReport(indexer, "TVDB id", found = 4, accepted = 4), listOf("indexer-7/a"))),
            progress.after(FinderEvent.StageStarted(SearchStage.AirDate, 2)),
            progress.after(FinderEvent.StageStarted(SearchStage.ByName, 2)),
        )
        assertEquals(
            listOf(
                "Searching the TV's 2 indexers for the episode and season packs",
                "Searching the TV's 2 indexers for the episode and season packs · 1 found · 0 of 2 answered",
                "Trying a search by air date",
                "Trying a search by name",
            ),
            lines.map(TvWords::line),
        )
        // The coordinator's own lines, as it writes them (PlaybackCoordinator, MoviePlay).
        assertEquals("Searching the TV's indexers…", TvWords.line("Searching your indexers…"))
        assertEquals("Searched the TV's 2 indexers by TVDB id · 6 found", TvWords.line("Searched 2 indexers by TVDB id · 6 found"))
        assertEquals("Searched the TV's 1 indexer by IMDb id · 1 found", TvWords.line("Searched 1 indexer by IMDb id · 1 found"))
        val movie = MovieFind(null, listOf(IndexerStatus("indexer-7", "Invented indexer", IndexerStatus.State.Answered(9)), IndexerStatus("indexer-8", "Another indexer", IndexerStatus.State.Answered(0))))
        assertEquals("Searched the TV's 2 indexers · 0 found", TvWords.line(movie.searchLine))
        assertEquals(listOf("Searched the TV's 2 indexers" to "9 results, none playable"), movie.remoteStages.map { (what, why) -> TvWords.line(what) to TvWords.line(why) })
    }

    @Test
    fun `the providers' lines name the TV's providers`() {
        assertEquals("Checking the TV's providers…", TvWords.line(checkingProvidersLine(null)))
        val retried = TvWords.line(checkingProvidersLine(Retry(setOf("a/1"), startPositionMs = null, midStream = false)))
        assertEquals("The release that played it failed · Checking the TV's providers…", retried)
        val checked = 1_800_000_000_000L
        assertEquals("Skipped: missing on the TV's providers (checked 3 days ago)", TvWords.line(skippedLine(KnownMissing(checked, false), checked + 3 * 86_400_000L, ZoneOffset.UTC)))
        assertEquals("Incomplete on the TV's providers", TvWords.line("Incomplete on your providers"))
    }

    @Test
    fun `no provider's words are the TV's`() {
        val lines = listOf(NoUsableProvider.NoneSaved, NoUsableProvider.AllOff, NoUsableProvider.Unreadable(1), NoUsableProvider.Unreadable(2)).map { TvWords.line(it.message) }
        assertEquals(
            listOf(
                "No Usenet provider is set up yet.",
                "Every Usenet provider is switched off.",
                "The TV's Usenet provider's password can't be read on the TV: enter it again.",
                "The TV's Usenet providers' passwords can't be read on the TV: enter them again.",
            ),
            lines,
        )
        lines.forEach(::assertTheTvs)
    }

    @Test
    fun `no release's stages, and the reasons releases were hidden, are the TV's`() {
        val found = FindResult(
            episode.request, emptyList(), emptyList(),
            listOf(
                StageReport(SearchStage.Episode, listOf(IndexerReport(indexer, "TVDB id", found = 4, accepted = 0), IndexerReport(other, "TVDB id"))),
                StageReport(SearchStage.SeasonPack, listOf(IndexerReport(indexer, "TVDB id"), IndexerReport(other, "TVDB id"))),
            ),
        )
        val shown = noRelease(episode, found, LocalDate.of(2026, 11, 2))
        val stages = shown.remoteStages.map { (what, why) -> TvWords.line(what) to TvWords.line(why) }
        assertEquals(listOf("Searched the TV's 2 indexers by TVDB id" to "4 results, none for S02E04", "Looked for season 2 packs" to "None containing E04"), stages)
        val reasons = listOf(
            ReleaseReason.AboveMaxBitrate(40), ReleaseReason.AboveScreen(2160), ReleaseReason.PlaysOnDevice, ReleaseReason.MayBuffer(30),
            ReleaseReason.DolbyVisionOnly, ReleaseReason.HdrOnSdrScreen(), ReleaseReason.BeyondRetention,
        ).map { TvWords.line(it.text) }
        assertEquals(
            listOf(
                "Above the TV's 40 Mbps", "2160p is above the TV's screen", "Plays on the TV", "May buffer: ~30 Mbps", "Dolby Vision only: no decoder",
                "HDR on a screen without HDR", "Older than provider retention",
            ),
            reasons,
        )
        reasons.forEach(::assertTheTvs)
        // As the TV's chips say them, marked.
        assertEquals("⚠ Above the TV's 40 Mbps", TvWords.line("⚠ Above your 40 Mbps"))
        assertEquals("✓ Plays on the TV", TvWords.line("✓ Plays on this device"))
    }

    /**
     * L226: the TV's own debrid words, as they reach the phone (a hidden reason, the no-provider sheet's, a skipped
     * release's line, the running notice), said of the TV's debrid service; the curly apostrophe kept where the TV wrote one.
     */
    @Test
    fun `the TV's debrid lines name the TV's debrid service`() {
        val lines = mapOf(
            "Not used with your debrid service" to "Not used with the TV's debrid service",
            "⚠ Not used with your debrid service" to "⚠ Not used with the TV's debrid service",
            NoUsableProvider.ServiceOff.message to "The TV's debrid service is off, and no Usenet provider is set up (Settings › Debrid services).",
            "Too large for your debrid plan" to "Too large for the TV's debrid plan",
            "The debrid service won’t play this release" to "The TV's debrid service won’t play this release",
            "No longer available from the service" to "No longer available from the TV's debrid service",
            "Removed by the debrid service" to "Removed by the TV's debrid service",
            "The debrid service’s limit was reached · trying release 2 of 3" to "The TV's debrid service’s limit was reached · trying release 2 of 3",
            "The debrid service stopped answering" to "The TV's debrid service stopped answering",
            "Skipped 2 releases the debrid service failed before" to "Skipped 2 releases the TV's debrid service failed before",
            "Skipped 3 releases found incomplete or that the debrid service failed before" to "Skipped 3 releases found incomplete or that the TV's debrid service failed before",
        )
        for ((line, said) in lines) {
            assertEquals(said, TvWords.line(line), line)
            assertTheTvs(said)
            // Said of the TV once, said again: nothing doubles.
            assertEquals(said, TvWords.line(said))
        }
        // The rules' words as the app writes them, so a rewording there shows here.
        assertEquals("Not used with your debrid service", ReleaseReason.NotForService.text)
        assertEquals("No longer available from the service", io.skjaere.usenet.core.AttemptFailure.NoWorkingLink.text)
        assertEquals("The debrid service won’t play this release", io.skjaere.usenet.core.AttemptFailure.ServiceRefused(tooLarge = false).text)
        // "No longer cached" says nothing of whose: it stays.
        assertEquals("Skipped 2 releases no longer cached", TvWords.line("Skipped 2 releases no longer cached"))
    }

    @Test
    fun `a line with nothing of this device's stays as it is`() {
        for (line in listOf("Release incomplete · trying release 3 of 3", "The release before stopped playing · trying another", "Looking up the episode on TMDB…", "No results", "⚠ 1 indexer didn't answer", "")) {
            assertEquals(line, TvWords.line(line))
        }
    }

    @Test
    fun `a sheet's lines are rewritten, and nothing else, titles, releases and chips staying as sent`() {
        val sheet = Status.Sheet(
            SheetKind.Running, "Your Lighthouse", "S01E02 · Your Turn", line = "Searched 2 indexers by TVDB id · 6 found", notice = "Skipped 2 releases found incomplete before",
            steps = listOf(Status.Step(SheetStep.FetchNzb, StepState.Done, millis = 600, detail = "2.9 MB"), Status.Step(SheetStep.CheckAvailability, StepState.Failed, millis = 1_800, failure = "Incomplete on the TV's providers")),
            attempt = 1, of = 3, release = "Your.Lighthouse.S01E02.Your.Turn.1080p.WEB-DL-GRP", chips = listOf("1080p", "Your choice"), playsHere = true,
            stages = listOf(Status.Line("Searched 2 indexers", "⚠ 1 indexer didn't answer")), note = "Indexers can also number some shows differently from TMDB.",
            hidden = listOf(Status.Hidden("Your.Lighthouse.S01E02.2160p-GRP", listOf("⚠ Above your 40 Mbps", "2160p is above this screen"))), hiddenCount = 1,
            failures = listOf(Status.Line("Your.Lighthouse.S01E02.720p-GRP", "Couldn't get the NZB on the TV")),
            skippedBefore = listOf(Status.Line("Your.Lighthouse.S01E02.480p-GRP", "Skipped: missing on your providers (checked today)")),
            noProvider = "Your Usenet provider's password can't be read here: enter it again.",
        )
        val shown = TvWords.sheet(sheet)
        assertEquals(
            sheet.copy(
                line = "Searched the TV's 2 indexers by TVDB id · 6 found",
                stages = listOf(Status.Line("Searched the TV's 2 indexers", "⚠ 1 indexer didn't answer")),
                hidden = listOf(Status.Hidden("Your.Lighthouse.S01E02.2160p-GRP", listOf("⚠ Above the TV's 40 Mbps", "2160p is above the TV's screen"))),
                skippedBefore = listOf(Status.Line("Your.Lighthouse.S01E02.480p-GRP", "Skipped: missing on the TV's providers (checked today)")),
                noProvider = "The TV's Usenet provider's password can't be read on the TV: enter it again.",
            ),
            shown,
        )
        assertTrue(shown.title == "Your Lighthouse" && shown.release == sheet.release && shown.chips == sheet.chips)
    }

    @Test
    fun `a running start's notice and the no-release state's own words`() {
        val running = PlayState.Running("Harbor Lights", "S02E04", "Searched 2 indexers by TVDB id · 6 found", 1, 1, "Harbor.Lights.S02E04.1080p-GRP", emptyList(), emptyMap(), "The release that played it failed · Checking your providers…")
        val sheet = TvWords.sheet(SheetMirror.of(running)!!)
        assertEquals("The release that played it failed · Checking the TV's providers…", sheet.notice)
        assertEquals("Searched the TV's 2 indexers by TVDB id · 6 found", sheet.line)
    }
}
