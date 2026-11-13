package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.debrid.ServiceSource
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.FileChoice
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackFailure
import io.skjaere.usenet.app.play.noRelease
import io.skjaere.usenet.app.search.IndexerStatus
import io.skjaere.usenet.app.search.MovieFind
import io.skjaere.usenet.app.settings.NoUsableProvider
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.DiscKind
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.core.UnreadableFile
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.UsenetSource
import io.skjaere.usenet.link.Decoded
import io.skjaere.usenet.link.LinkCodec
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.Playback
import io.skjaere.usenet.link.SheetKind
import io.skjaere.usenet.link.SheetStep
import io.skjaere.usenet.link.StepState
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.search.FindResult
import io.skjaere.usenet.search.FoundRelease
import io.skjaere.usenet.search.IndexerReport
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.SearchStage
import io.skjaere.usenet.search.StageReport
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L37: the TV's play sheet as a phone's mirror of it, the wire's `sheet`: each kind of sheet as it shows, through the
 * codec and back, carrying only what shows. Every failure is told in the TV's words, from its kind and counts: no URL,
 * indexer id or name, provider host or server reply crosses, whatever the libraries' and the indexers' own words held.
 * The titles and names are invented; the hosts are the kind an indexer or a provider would name.
 */
class SheetMirrorTest {
    private val host = "news.example-provider.net"
    private val indexerHost = "api.example-indexer.org"

    /** The sheet [state] mirrors, through the codec and back as a phone reads it. */
    private fun mirrored(state: PlayState): Status.Sheet {
        val sheet = assertNotNull(SheetMirror.of(state), "$state")
        val read = assertIs<Decoded.Read>(LinkCodec.decode(LinkCodec.encode(Status("s-1", Playback.Starting, mine = true, sheet = sheet))!!)).value
        assertEquals(sheet, assertIs<Status>(read).sheet)
        return sheet
    }

    /** Whatever [sheet] holds, as one text: no host, indexer, id or reply may be in it. */
    private fun words(sheet: Status.Sheet): String = String(LinkCodec.encode(Status("s-1", Playback.Starting, sheet = sheet))!!)

    private fun assertNothingPrivate(sheet: Status.Sheet) {
        val text = words(sheet)
        for (secret in listOf(host, indexerHost, "example", "http", "apikey", "indexer-7", "503", "Service Unavailable", "481", "430 No Such Article")) {
            assertTrue(secret !in text, "\"$secret\" crossed: $text")
        }
    }

    @Test
    fun `nothing shows for no play`() {
        assertNull(SheetMirror.of(PlayState.Idle))
    }

    @Test
    fun `a search shows its line`() {
        val sheet = mirrored(PlayState.Searching("Harbor Lights", "S02E04 · Low Water", "Searching 2 indexers for the episode and season packs · 4 found · 1 of 2 answered"))
        assertEquals(
            Status.Sheet(
                SheetKind.Searching, "Harbor Lights", "S02E04 · Low Water", line = "Searching 2 indexers for the episode and season packs · 4 found · 1 of 2 answered",
                rows = listOf(SheetStep.FetchNzb, SheetStep.CheckAvailability, SheetStep.Open, SheetStep.ProbeTracks),
            ),
            sheet,
        )
    }

    /**
     * D2PhoneTvStarting (L223): a search sends the rows the TV's own sheet lists while it searches: a debrid service's
     * three on a TV that plays from its service alone, Usenet's four on one with a usable provider.
     */
    @Test
    fun `a search sends the rows the TV's sheet lists while it searches`() {
        val searching = PlayState.Searching("Harbor Lights", "S02E04 · Low Water", "Searching your indexers…")
        assertEquals(listOf(SheetStep.Prepare, SheetStep.Open, SheetStep.ProbeTracks), SheetMirror.of(searching, ServiceSource.STEPS)!!.rows)
        assertEquals(listOf(SheetStep.FetchNzb, SheetStep.CheckAvailability, SheetStep.Open, SheetStep.ProbeTracks), SheetMirror.of(searching, UsenetSource.STEPS)!!.rows)
        // Only a search's sheet: a running start's rows are its steps'.
        assertEquals(emptyList(), SheetMirror.of(running, ServiceSource.STEPS)!!.rows)
    }

    private val running = PlayState.Running(
        "Harbor Lights", "S02E04 · Low Water", "Searched 2 indexers by TVDB id · 6 found", attempt = 1, of = 3,
        release = "Harbor.Lights.S02E04.Low.Water.2160p.WEB-DL.DV.HDR10.DDP5.1.H.265-GRP", chips = listOf("2160p", "WEB-DL", "HEVC 10-bit", "7.4 GB", "DD+ 5.1"),
        steps = mapOf(
            PipelineStep.FetchNzb to StepStatus.Done(600, "2.9 MB"),
            PipelineStep.CheckAvailability to StepStatus.Running(112, 256),
        ),
        notice = null,
    )

    @Test
    fun `a running start shows its steps, the release and its chips, and that it plays on the TV`() {
        val sheet = mirrored(running)
        assertEquals(
            Status.Sheet(
                SheetKind.Running, "Harbor Lights", "S02E04 · Low Water", line = "Searched 2 indexers by TVDB id · 6 found",
                steps = listOf(
                    Status.Step(SheetStep.FetchNzb, StepState.Done, millis = 600, detail = "2.9 MB"),
                    Status.Step(SheetStep.CheckAvailability, StepState.Running, done = 112, total = 256),
                ),
                attempt = 1, of = 3, release = "Harbor.Lights.S02E04.Low.Water.2160p.WEB-DL.DV.HDR10.DDP5.1.H.265-GRP",
                chips = listOf("2160p", "WEB-DL", "HEVC 10-bit", "7.4 GB", "DD+ 5.1"), playsHere = true,
            ),
            sheet,
        )
    }

    @Test
    fun `a step's detail too long to show stays behind, as the sheet leaves it out`() {
        val opened = running.copy(steps = running.steps + (PipelineStep.Open to StepStatus.Done(900, "Harbor.Lights.S02E04.Low.Water.2160p.mkv")))
        assertEquals(Status.Step(SheetStep.Open, StepState.Done, millis = 900), mirrored(opened).steps.last())
    }

    /**
     * L206: a TV's torrent start is mirrored with its own steps, Preparing first and done with its time, then Opening, as
     * the TV's sheet lists them; a failed Preparing is told in the TV's words, naming no service, not even the one whose
     * key it refused (L204).
     */
    @Test
    fun `a TV's torrent play mirrors Preparing`() {
        val torrent = running.copy(
            release = "Harbor.Lights.S02E04.2160p.WEB.H265-KESTREL",
            steps = mapOf(PipelineStep.Prepare to StepStatus.Done(900), PipelineStep.Open to StepStatus.Running()),
            sourceSteps = ServiceSource.STEPS,
        )
        val sheet = mirrored(torrent)
        assertEquals(listOf("prepare" to StepState.Done, "open" to StepState.Running), sheet.steps.map { it.step.wire to it.state })
        assertEquals(900L, sheet.steps.first().millis)
        val refused = mirrored(torrent.copy(steps = mapOf(PipelineStep.Prepare to StepStatus.Failed(400, AttemptFailure.KeyRefused("TorBox", "service:a")))))
        assertEquals(listOf("prepare" to "The TV's debrid service refused its API key"), refused.steps.map { it.step.wire to it.failure })
        assertTrue("TorBox" !in words(refused), words(refused))
    }

    @Test
    fun `the TV's own check says it doesn't play there once the probe failed`() {
        val probed = running.copy(
            steps = mapOf(
                PipelineStep.FetchNzb to StepStatus.Done(600),
                PipelineStep.CheckAvailability to StepStatus.Done(1_800),
                PipelineStep.Open to StepStatus.Done(900),
                PipelineStep.ProbeTracks to StepStatus.Failed(2_100, AttemptFailure.NoPlayableVideo(listOf("2160p Dolby Vision profile 7"))),
            ),
        )
        val sheet = mirrored(probed)
        assertEquals(false, sheet.playsHere)
        assertEquals(Status.Step(SheetStep.ProbeTracks, StepState.Failed, millis = 2_100, failure = "No video the TV can play: 2160p Dolby Vision profile 7"), sheet.steps.last())
    }

    @Test
    fun `a failed step is told in the TV's words, from its kind and counts`() {
        val cases = mapOf(
            AttemptFailure.FetchFailed("HTTP 503 Service Unavailable from https://$indexerHost/api?apikey=indexer-7") to "Couldn't get the NZB on the TV",
            AttemptFailure.MissingArticles("430 No Such Article from $host", missing = 41, checked = 256) to "Incomplete on the TV's providers · 41 of 256 checked articles missing",
            AttemptFailure.MissingArticles("430 No Such Article", missing = 1, checked = 1, providersLeftOut = true) to "Incomplete on the TV's providers in use · 1 of 1 checked article missing",
            AttemptFailure.MissingArticles("gap", unanswered = UsenetError.AuthFailed("481 rejected by $host")) to "Incomplete on the TV's providers that answered",
            AttemptFailure.MissingArticles("gap") to "Incomplete on the TV's providers",
            AttemptFailure.Passworded to "Passworded",
            AttemptFailure.Unsupported("RAR5 solid archive from $host") to "Can't be streamed on the TV",
            AttemptFailure.NoVideo to "No video file",
            AttemptFailure.NoMatchingEpisode(2, 4) to "No file for S02E04",
            AttemptFailure.NoRememberedFile("Harbor.Lights.S02E04.mkv") to "The file that played before isn't in it",
            AttemptFailure.NoPlayableAudio(listOf("TrueHD Atmos")) to "No audio the TV can play (TrueHD Atmos)",
            AttemptFailure.NoPlayableVideo(emptyList()) to "No video the TV can play",
            AttemptFailure.Provider(UsenetError.AuthFailed("481 Authentication rejected by $host")) to "A provider on the TV rejected the login",
            AttemptFailure.Provider(UsenetError.ConnectionFailed("connect to $host:563 timed out")) to "The TV couldn't reach its provider",
            AttemptFailure.Provider(UsenetError.Network("reset by $host")) to "The TV lost the connection to its provider",
            AttemptFailure.Provider(UsenetError.Timeout(host)) to "A provider on the TV stopped answering",
            AttemptFailure.Provider(UsenetError.Protocol("500 from $host")) to "A provider on the TV sent an unexpected answer",
            AttemptFailure.Provider(UsenetError.NotConfigured) to "The TV has no Usenet provider set up",
            AttemptFailure.Failed(UsenetError.MissingArticle("430 from $host")) to "Incomplete on the TV's providers",
            AttemptFailure.Failed(UsenetError.Corrupt("CRC from $host")) to "Damaged on the TV's providers",
            AttemptFailure.Failed(UsenetError.Unknown("java.io.IOException: $host")) to "Reading the release failed on the TV",
            // A file the TV's player can't read (L318): the TV's words, of its player, never a provider's.
            AttemptFailure.Unreadable(UnreadableFile.UnknownFormat, "M2TS") to "The TV's player can't read this M2TS file",
            AttemptFailure.Unreadable(UnreadableFile.UnknownFormat, null) to "The TV's player can't read this file's format",
            AttemptFailure.Unreadable(UnreadableFile.Broken, "MKV") to "The TV's player can't read this MKV file: it may be damaged",
            AttemptFailure.Unreadable(UnreadableFile.Broken, null) to "The TV's player can't read this file: it may be damaged",
            AttemptFailure.Unreadable(UnreadableFile.ReaderCrashed, "MKV") to "Reading the release failed on the TV",
            // A disc copy (L313): the release's own words, the same on the TV as here.
            AttemptFailure.DiscCopy(DiscKind.BluRay) to "A copy of a Blu-ray disc: NZStreamer plays single video files",
            AttemptFailure.DiscCopy(DiscKind.Dvd) to "A copy of a DVD: NZStreamer plays single video files",
            AttemptFailure.DiscCopy(DiscKind.Image) to "A disc image (ISO): NZStreamer plays single video files",
        )
        for ((failure, words) in cases) {
            assertEquals(words, SheetMirror.failure(failure), "$failure")
            val sheet = mirrored(running.copy(steps = mapOf(PipelineStep.FetchNzb to StepStatus.Failed(400, failure))))
            assertEquals(words, sheet.steps.single().failure)
            assertNothingPrivate(sheet)
        }
    }

    /** A failure from another device's words (a phone's mirror of its TV, L37) is never told on: plain words alone. */
    @Test
    fun `a failure in another device's words is told as the plain line`() {
        assertEquals(SheetMirror.COULDNT, SheetMirror.failure(AttemptFailure.Elsewhere("Incomplete on the TV's providers · reply from $host")))
    }

    /** A TV never casts (L67), so a file refused for a Cast device is never its own failure: the plain line, should one come. */
    @Test
    fun `a file refused for a Cast device is told as the plain line`() {
        assertEquals(SheetMirror.COULDNT, SheetMirror.failure(AttemptFailure.NotForCast("avi")))
        assertEquals(SheetMirror.COULDNT, SheetMirror.failure(AttemptFailure.NoCastAudio(listOf("DTS-HD"))))
    }

    @Test
    fun `the notice names the release that failed in the TV's words, and the app's own notices as they are`() {
        val fetch = AttemptFailure.FetchFailed("HTTP 503 Service Unavailable from https://$indexerHost/api")
        val after = running.copy(attempt = 2, of = 3, notice = "Couldn't get the NZB: HTTP 503 Service Unavailable · trying release 2 of 3", noticeFailure = fetch)
        assertEquals("Couldn't get the NZB on the TV · trying release 2 of 3", mirrored(after).notice)
        assertNothingPrivate(mirrored(after))
        val missing = running.copy(attempt = 3, of = 3, notice = "Release incomplete · trying release 3 of 3", noticeFailure = AttemptFailure.MissingArticles("430", missing = 41, checked = 256))
        assertEquals("Release incomplete · trying release 3 of 3", mirrored(missing).notice)
        val skipped = running.copy(notice = "Skipped 2 releases found incomplete before")
        assertEquals("Skipped 2 releases found incomplete before", mirrored(skipped).notice)
        // A file the TV's player can't read: the TV's notice words, said of the TV (L329).
        val unreadable = listOf(
            AttemptFailure.Unreadable(UnreadableFile.UnknownFormat, "M2TS"), AttemptFailure.Unreadable(UnreadableFile.Broken, null),
            AttemptFailure.Unreadable(UnreadableFile.ReaderCrashed, "MKV"),
        ).map { f -> mirrored(running.copy(attempt = 2, of = 3, notice = io.skjaere.usenet.app.play.retryNotice(f, 2, 3, cast = null), noticeFailure = f)).notice }
        assertEquals(
            listOf(
                "M2TS file can't be read on the TV · trying release 2 of 3", "File can't be read on the TV · trying release 2 of 3",
                "Reading the release failed on the TV · trying release 2 of 3",
            ),
            unreadable,
        )
        // A disc copy: the TV's notice words, which name no device (L329).
        val discs = listOf(DiscKind.BluRay, DiscKind.Dvd, DiscKind.Image).map(AttemptFailure::DiscCopy)
            .map { f -> mirrored(running.copy(attempt = 2, of = 3, notice = io.skjaere.usenet.app.play.retryNotice(f, 2, 3, cast = null), noticeFailure = f)).notice }
        assertEquals(
            listOf("Blu-ray disc copy · trying release 2 of 3", "DVD copy · trying release 2 of 3", "Disc image · trying release 2 of 3"),
            discs,
        )
    }

    @Test
    fun `a season pack's question shows what it looks for and the release, not its files`() {
        val choosing = PlayState.ChoosingFile(
            "Harbor Lights", "S02E04 · Low Water", "Looking for S02E04 'Low Water'", "Harbor.Lights.S02.1080p.BluRay.DDP5.1.x264-GRP",
            listOf(FileChoice(0, "Harbor Lights - Brine.mkv", 4_400_000_000, sample = false), FileChoice(1, "sample.mkv", 48_000_000, sample = true)),
        )
        val sheet = mirrored(choosing)
        assertEquals(
            Status.Sheet(
                SheetKind.Choosing, "Harbor Lights", "S02E04 · Low Water", lookingFor = "Looking for S02E04 'Low Water'",
                release = "Harbor.Lights.S02.1080p.BluRay.DDP5.1.x264-GRP",
            ),
            sheet,
        )
        assertTrue("Brine" !in words(sheet))
    }

    private fun item(title: String, indexer: String = "indexer-7") = NewznabItem(
        indexerId = indexer, guid = title, title = title, sizeBytes = 1_400_000_000, publishedAt = null, postedAt = null,
        categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
        tvmazeId = null, season = null, episode = null, coverUrl = "https://$indexerHost/covers/1.jpg", mediaInfo = null,
        downloadTemplate = "https://$indexerHost/api?t=get&id=1&apikey=indexer-7", attributes = emptyMap(),
    )

    private fun hidden(title: String, vararg reasons: ReleaseReason) = RankedItem(
        item(title), RankedRelease(ReleaseFacts(title, title, 1_400_000_000), ReleaseParser.parse(title), playable = false, score = 0.0, reasons = reasons.toList(), estimatedBitrate = null),
    )

    private val episode = EpisodePlay(
        900002, "Harbor Lights", 2024, emptyList(), TvIds(tvdb = 9000021, tmdb = 900002), 2, 4, "Low Water", LocalDate.of(2025, 3, 4), 52, null,
        aired = true, originalLanguage = "en",
    )

    @Test
    fun `no release shows what each search did, an indexer's error as a count`() {
        val indexer = IndexerConfig("indexer-7", indexerHost, "https://$indexerHost", "apikey")
        val other = IndexerConfig("indexer-8", "Invented indexer", "https://$indexerHost/2", "apikey")
        val dvOnly = hidden("Harbor.Lights.S02E04.2160p.WEB-DL.DV.H.265-GRP", ReleaseReason.DolbyVisionOnly, ReleaseReason.Language("English"))
        val found = FindResult(
            episode.request, emptyList(), listOf(FoundRelease(dvOnly.item, dvOnly.ranked, SearchStage.Episode)),
            listOf(
                StageReport(
                    SearchStage.Episode,
                    listOf(
                        IndexerReport(indexer, "TVDB id", found = 4, accepted = 0),
                        IndexerReport(other, "TVDB id", error = NewznabError.Server("503 Service Unavailable from $indexerHost")),
                    ),
                ),
                StageReport(SearchStage.SeasonPack, listOf(IndexerReport(indexer, "TVDB id"), IndexerReport(other, "TVDB id"))),
                StageReport(SearchStage.ByName, listOf(IndexerReport(indexer, "name", error = NewznabError.Timeout("$indexerHost timed out")))),
            ),
        )
        val shown = noRelease(episode, found, LocalDate.of(2026, 11, 2))
        // The TV's own sheet names the indexer's error; the phone's says how many didn't answer.
        assertTrue(shown.stages.any { indexerHost in it.second })
        val sheet = mirrored(shown)
        assertEquals(SheetKind.NoRelease, sheet.kind)
        assertEquals(
            listOf(
                // As the TV's sheet has it: what was found comes before an error.
                Status.Line("Searched 2 indexers by TVDB id", "4 results, none for S02E04"),
                Status.Line("Looked for season 2 packs", "None containing E04"),
                Status.Line("Searched by name", "⚠ 1 indexer didn't answer"),
            ),
            sheet.stages,
        )
        assertEquals("Indexers can also number some shows differently from TMDB.", sheet.note)
        assertEquals(listOf(Status.Hidden("Harbor.Lights.S02E04.2160p.WEB-DL.DV.H.265-GRP", listOf("⚠ Dolby Vision only: no decoder", "English"))), sheet.hidden)
        assertEquals(1, sheet.hiddenCount)
        assertNothingPrivate(sheet)
    }

    @Test
    fun `a movie's no release says the TV's indexers as counts, never their names`() {
        val statuses = listOf(
            IndexerStatus("indexer-7", indexerHost, IndexerStatus.State.Answered(3)),
            IndexerStatus("indexer-8", "Invented indexer", IndexerStatus.State.Failed("HTTP 503 Service Unavailable from $indexerHost")),
            IndexerStatus("indexer-9", "Another indexer", IndexerStatus.State.Answered(0)),
        )
        val find = MovieFind(null, statuses)
        val shown = PlayState.NoRelease("Harbor Bell", "2026", find.stages, null, "Harbor Bell 2026", emptyList(), remoteStages = find.remoteStages)
        val sheet = mirrored(shown)
        assertEquals(listOf(Status.Line("Searched 3 indexers", "3 results, none playable · ⚠ 1 indexer didn't answer")), sheet.stages)
        assertTrue("Invented indexer" !in words(sheet))
        assertNothingPrivate(sheet)
        assertEquals(listOf(Status.Line("Searched 1 indexer", "No results")), mirrored(shown.copy(remoteStages = MovieFind(null, statuses.takeLast(1)).remoteStages)).stages)
    }

    @Test
    fun `hidden releases past what fits are counted, not listed`() {
        val many = List(SheetMirror.MAX_HIDDEN + 5) { hidden("Harbor.Lights.S02E04.$it.2160p-GRP", ReleaseReason.DiscImage) }
        val sheet = mirrored(PlayState.NoRelease("Harbor Lights", "S02E04", emptyList(), null, "Harbor Lights", many))
        assertEquals(SheetMirror.MAX_HIDDEN, sheet.hidden.size)
        assertEquals(SheetMirror.MAX_HIDDEN + 5, sheet.hiddenCount)
    }

    @Test
    fun `a failure shows each release tried in the TV's words, those skipped, and those left`() {
        val failed = PlayState.Failed(
            "Harbor Lights", "S02E04 · Low Water",
            listOf(
                "Harbor.Lights.S02E04.2160p-GRP" to "Incomplete on your providers · 41 of 256 checked articles missing",
                "Harbor.Lights.S02E04.1080p-GRP" to "Couldn't get the NZB: HTTP 503 from https://$indexerHost/api?apikey=indexer-7",
                "Harbor.Lights.S02E04.720p-GRP" to "Something went wrong: java.net.SocketException: $host",
            ),
            notTried = 2, skipped = 1,
            missing = listOf("Harbor.Lights.S02E04.480p-GRP" to "Skipped: missing on your providers (checked 3 days ago)"),
            remote = listOf("Incomplete on the TV's providers · 41 of 256 checked articles missing", "Couldn't get the NZB on the TV"),
        )
        val sheet = mirrored(failed)
        assertEquals(
            Status.Sheet(
                SheetKind.Failed, "Harbor Lights", "S02E04 · Low Water",
                failures = listOf(
                    Status.Line("Harbor.Lights.S02E04.2160p-GRP", "Incomplete on the TV's providers · 41 of 256 checked articles missing"),
                    Status.Line("Harbor.Lights.S02E04.1080p-GRP", "Couldn't get the NZB on the TV"),
                    // Its words weren't written for a phone: the plain line, never the TV's own.
                    Status.Line("Harbor.Lights.S02E04.720p-GRP", SheetMirror.COULDNT),
                ),
                skippedBefore = listOf(Status.Line("Harbor.Lights.S02E04.480p-GRP", "Skipped: missing on your providers (checked 3 days ago)")),
                notTried = 2, skipped = 1,
            ),
            sheet,
        )
        assertNothingPrivate(sheet)
    }

    /** #316 (L223): a failure says how many of the releases left were left as what they play from failed on the TV. */
    @Test
    fun `a failure says how many releases were left as what they play from failed`() {
        val failed = PlayState.Failed("Harbor Lights", "S02E04", listOf("Harbor.Lights.S02E04.2160p-GRP" to "No longer cached"), notTried = 3, remote = listOf("No longer cached on the TV's debrid service"), noRoute = 2)
        val sheet = mirrored(failed)
        assertEquals(3, sheet.notTried)
        assertEquals(2, sheet.noRoute)
        assertEquals(0, mirrored(failed.copy(noRoute = 0)).noRoute)
    }

    /**
     * L223, L226: nothing of the TV's debrid service or account crosses, in any of a service's failures (a step's, the
     * notice's, a failure line's), a release hidden as not used with the service, or a skipped release's line: no
     * service's name, hash, magnet, address or account. As the phone shows them (TvWords), each says "the TV's". The
     * releases are torrents, as a Torznab feed gives them (CGX-3): the one running is tagged with its service, and the one
     * hidden is known by its hash, its account's check found it cached, and its magnet names trackers, so each check could
     * fail.
     */
    @Test
    fun `nothing names the TV's service or account, in any debrid failure, hidden reason or skipped line`() {
        val account = "acct-3f9a2c"
        val route = io.skjaere.usenet.app.play.UsableRoutes.serviceRoute(account)
        val hash = "4c1e7a0b9d2f36e85a7c1b0d9e3f2a6b8c4d1e7f"
        val magnet = "magnet:?xt=urn:btih:$hash&dn=Harbor.Lights.S02E04&tr=udp%3A%2F%2Ftracker.example.org%3A1337%2Fquay.viewer"
        val tag = io.skjaere.usenet.app.search.SourceTag("TorBox", "From TorBox, also cached on 1 more service", dim = false, more = 1)
        val failures = listOf(
            AttemptFailure.NoLongerCached, AttemptFailure.RemovedByService, AttemptFailure.ServiceLimit(kotlin.time.Duration.parse("20m"), route),
            AttemptFailure.ServiceLimit(null, route), AttemptFailure.ServiceRefused(tooLarge = true), AttemptFailure.ServiceRefused(tooLarge = false),
            AttemptFailure.KeyRefused("TorBox", route), AttemptFailure.KeyRefused("Premiumize", route), AttemptFailure.ServiceDown(route), AttemptFailure.NoWorkingLink,
        )
        // What a play remembers and skips by (ServiceMisses): the failure's own words.
        val remembered = listOf(AttemptFailure.NoLongerCached, AttemptFailure.RemovedByService, AttemptFailure.ServiceRefused(true), AttemptFailure.ServiceRefused(false), AttemptFailure.NoWorkingLink)
        val release = "Harbor.Lights.S02E04.2160p.WEB.H265-KESTREL"
        // As a Torznab feed's item reads (NewznabXml): known by its hash, no link kept; its magnet kept too, as a later
        // read might, so the mirror is seen to leave it out.
        val torrent = item(release, indexer = "torznab-3").copy(
            guid = "btih:$hash", downloadTemplate = null, infoHash = hash, enclosureType = "application/x-bittorrent",
            attributes = mapOf("infohash" to listOf(hash), "magneturl" to listOf(magnet)),
            availability = io.skjaere.usenet.releases.Availability.Cached(listOf(account)),
        )
        val sheets = failures.flatMap { f ->
            listOf(
                running.copy(
                    release = release, chips = listOf("2160p", "WEB", "HEVC"), tag = tag,
                    steps = mapOf(PipelineStep.Prepare to StepStatus.Done(900), PipelineStep.Open to StepStatus.Failed(400, f)), sourceSteps = ServiceSource.STEPS,
                    notice = "${f.text} · trying release 2 of 3", noticeFailure = f,
                ),
                PlayState.Failed("Harbor Lights", "S02E04", listOf(release to f.text), notTried = 1, remote = listOf(SheetMirror.failure(f)), noRoute = 1),
            )
        } + PlayState.Failed("Harbor Lights", "S02E04", emptyList(), notTried = 0, missing = remembered.map { release to it.text }) +
            PlayState.NoRelease("Harbor Lights", "S02E04", emptyList(), null, "Harbor Lights", listOf(hidden(release, ReleaseReason.NotForService).copy(item = torrent))) +
            PlayState.Failed("Harbor Lights", "S02E04", emptyList(), notTried = 0, noProvider = NoUsableProvider.ServiceOff)
        for (state in sheets) {
            val sheet = mirrored(state)
            val text = words(sheet)
            for (secret in listOf("TorBox", "Premiumize", "btih", hash, "magnet:", "tracker", "http", account, "service:", "quay.viewer")) {
                assertTrue(secret !in text, "\"$secret\" crossed: $text")
            }
            val shown = words(TvWords.sheet(sheet))
            for (mine in listOf("your debrid", "Your debrid", "the debrid service", "The debrid service", "from the service")) {
                assertTrue(mine !in shown, "\"$mine\" shown on the phone: $shown")
            }
        }
    }

    /**
     * DEP-6 (L226, L231): the TV trying the same release on its next debrid service is mirrored as its sheet shows it, the
     * steps after Preparing waiting again, and its notice in the mirror's own words: each of the service's failures as a
     * phone is told it, then "trying the TV's next debrid service"; never "trying release N of M", as no other release is
     * tried, and never a service's name or an account's id, not even a refused key's, which the TV's own words name. The
     * phone's rewrite (TvWords) leaves it as it is.
     */
    @Test
    fun `the TV's try of its next debrid service is mirrored in the mirror's own words, naming no service`() {
        val account = "acct-3f9a2c"
        val route = io.skjaere.usenet.app.play.UsableRoutes.serviceRoute(account)
        val failures = listOf(
            AttemptFailure.NoWorkingLink, AttemptFailure.NoLongerCached, AttemptFailure.RemovedByService, AttemptFailure.ServiceLimit(kotlin.time.Duration.parse("20m"), route),
            AttemptFailure.ServiceLimit(null, route), AttemptFailure.ServiceRefused(tooLarge = true), AttemptFailure.ServiceRefused(tooLarge = false),
            AttemptFailure.KeyRefused("TorBox", route), AttemptFailure.KeyRefused("Premiumize", route), AttemptFailure.ServiceDown(route),
        )
        val tag = io.skjaere.usenet.app.search.SourceTag("Premiumize", "From Premiumize", dim = false)
        val torrent = running.copy(release = "Harbor.Lights.S02E04.2160p.WEB.H265-KESTREL", chips = listOf("2160p", "WEB", "HEVC"), tag = tag, sourceSteps = ServiceSource.STEPS)
        // Frame 1 on the TV: Opening failed on its first service.
        val failedHere = mirrored(torrent.copy(steps = mapOf(PipelineStep.Prepare to StepStatus.Done(800), PipelineStep.Open to StepStatus.Failed(400, AttemptFailure.ServiceDown(route)))))
        assertEquals(listOf("prepare" to StepState.Done, "open" to StepState.Failed), failedHere.steps.map { it.step.wire to it.state })
        val notices = failures.map { f ->
            val retried = torrent.copy(
                steps = io.skjaere.usenet.app.play.retriedSteps(mapOf(PipelineStep.Prepare to StepStatus.Done(800), PipelineStep.Open to StepStatus.Failed(400, f)), ServiceSource.STEPS) +
                    (PipelineStep.Prepare to StepStatus.Running()),
                notice = io.skjaere.usenet.app.play.nextServiceNotice(f, cast = null), noticeFailure = f, noticeNextService = true,
            )
            val sheet = mirrored(retried)
            // The mirror follows: Preparing again, the steps after it waiting.
            assertEquals(listOf(Status.Step(SheetStep.Prepare, StepState.Running)), sheet.steps)
            val text = words(sheet)
            for (secret in listOf("TorBox", "Premiumize", account, "service:", "trying release", "your next")) {
                assertTrue(secret !in text, "\"$secret\" crossed: $text")
            }
            assertEquals(sheet.notice, TvWords.sheet(sheet).notice)
            sheet.notice
        }
        val next = "trying the TV's next debrid service"
        assertEquals(
            listOf(
                "No longer available from the TV's debrid service · $next", "No longer cached on the TV's debrid service · $next", "Removed by the TV's debrid service · $next",
                "The TV's debrid service's limit was reached · $next", "The TV's debrid service's limit was reached · $next", "Too large for the TV's debrid plan · $next",
                "The TV's debrid service won't play this release · $next", "The TV's debrid service refused its API key · $next", "The TV's debrid service refused its API key · $next",
                "The TV's debrid service stopped answering · $next",
            ),
            notices,
        )
        // Moving on to the next release, the notice is today's again.
        val moved = torrent.copy(attempt = 2, notice = "The debrid service stopped answering · trying release 2 of 3", noticeFailure = AttemptFailure.ServiceDown(route))
        assertEquals("The TV's debrid service stopped answering · trying release 2 of 3", mirrored(moved).notice)
    }

    @Test
    fun `no usable provider shows why, as the TV's sheet does`() {
        val sheet = mirrored(PlayState.Failed("Harbor Lights", "S02E04", emptyList(), notTried = 0, noProvider = NoUsableProvider.NoneSaved))
        assertEquals(Status.Sheet(SheetKind.NoProvider, "Harbor Lights", "S02E04", noProvider = "No Usenet provider is set up yet."), sheet)
    }

    @Test
    fun `the player's "Playback stopped" is told in the TV's words`() {
        val io = androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        assertEquals("Part of this release is missing from every Usenet provider on the TV.", PlaybackFailure.of(UsenetError.MissingArticle("430 from $host"), io).remote)
        assertEquals("A Usenet provider on the TV rejected the login.", PlaybackFailure.of(UsenetError.AuthFailed("481 from $host"), io).remote)
        assertEquals("The TV can't play this release's video (2160p Dolby Vision profile 7).", PlaybackFailure.noVideo(listOf("2160p Dolby Vision profile 7")).remote)
    }
}
