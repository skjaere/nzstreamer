package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.casting.CastLine
import io.skjaere.usenet.app.debrid.ServiceSource
import io.skjaere.usenet.app.search.NamedAccount
import io.skjaere.usenet.app.search.SourceTag
import io.skjaere.usenet.app.search.playTag
import io.skjaere.usenet.app.search.sourceTag
import io.skjaere.usenet.app.request
import io.skjaere.usenet.app.searchRoute
import io.skjaere.usenet.app.settings.NoUsableProvider
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.DiscKind
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.core.UnreadableFile
import io.skjaere.usenet.core.UsenetSource
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.data.KnownMissing
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.RankingPrefs
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.FindResult
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGrouper
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlaySheetTest {
    private val kitchen = CastTo("route-kitchen", "Kitchen display")

    @Test
    fun `the step named is the one running, also when a season pack is checked after opening`() {
        val pack = mapOf(
            PipelineStep.FetchNzb to StepStatus.Done(10),
            PipelineStep.Open to StepStatus.Done(10),
            PipelineStep.CheckAvailability to StepStatus.Running(3, 6),
        )
        assertEquals(PipelineStep.CheckAvailability, currentStep(pack))
    }

    /**
     * DEP-8: the failure sheet's line on the releases not tried names the indexer's allowance only for those left to save
     * it; those left as what they play from failed above (a debrid account's limit, a provider down) are said so.
     */
    @Test
    fun `releases not tried as what they play from failed are said so, not as saving the allowance`() {
        assertEquals(
            listOf("2 more releases not tried, to save your indexer's download allowance. Choose one from the release list."),
            notTriedLines(notTried = 2, noRoute = 0),
        )
        assertEquals(listOf("1 more release not tried: what it plays from failed above."), notTriedLines(notTried = 1, noRoute = 1))
        assertEquals(
            listOf(
                "1 more release not tried, to save your indexer's download allowance. Choose one from the release list.",
                "2 more releases not tried: what they play from failed above.",
            ),
            notTriedLines(notTried = 3, noRoute = 2),
        )
        assertEquals(emptyList<String>(), notTriedLines(notTried = 0, noRoute = 0))
    }

    @Test
    fun `between steps the furthest one reached is named`() {
        assertEquals(PipelineStep.CheckAvailability, currentStep(mapOf(PipelineStep.FetchNzb to StepStatus.Done(10), PipelineStep.CheckAvailability to StepStatus.Done(10))))
        assertEquals(null, currentStep(emptyMap()))
    }

    @Test
    fun `without a usable provider the sheet says why, with nothing tried and no search to offer`() {
        listOf(NoUsableProvider.NoneSaved, NoUsableProvider.AllOff, NoUsableProvider.Unreadable(2)).forEach { missing ->
            val failed = noProviderFailure("Lantern Field", "S01E02", missing)
            assertEquals("Lantern Field" to "S01E02", failed.title to failed.subtitle)
            assertEquals(missing, failed.noProvider)
            assertEquals(emptyList(), failed.failures)
            assertEquals(0, failed.notTried)
            assertNull(failed.searchRoute())
        }
        assertEquals("Every Usenet provider is switched off.", noProviderFailure("Lantern Field", null, NoUsableProvider.AllOff).noProvider?.message)
    }

    /**
     * PtTvNotReady, D2TvNotReady (L248, choice 6): a TV's not-ready sheet offers "Set up with your phone" first and focused,
     * then the page for what is missing, and Close last (drawn by the sheet). A service that is off puts Open Debrid
     * services first and focused, then the phone: the phone's page can't switch a service on. With debrid hidden (L192) no
     * debrid page has a way there, and none shows.
     */
    @Test
    fun `a TV's not-ready sheet offers its ways in the case's order, the first focused`() {
        val opened = mutableListOf<String>()
        val ways = NotReadyWays(
            phoneSetup = { opened += "phone setup" }, addProvider = { opened += "provider form" }, addIndexer = { opened += "indexer form" },
            addService = { opened += "Add a service" }, services = { opened += "Debrid services" }, addTorrentIndexer = { opened += "torrent indexer form" },
        )
        val cases = listOf(
            NotReady.Missing.Provider to listOf("Set up with your phone" to "phone setup", "Add a Usenet provider" to "provider form"),
            NotReady.Missing.Indexer to listOf("Set up with your phone" to "phone setup", "Add an indexer" to "indexer form"),
            NotReady.Missing.Service to listOf("Set up with your phone" to "phone setup", "Add a debrid service" to "Add a service"),
            NotReady.Missing.ServiceOff() to listOf("Open Debrid services" to "Debrid services", "Set up with your phone" to "phone setup"),
            NotReady.Missing.ServiceOff("TorBox") to listOf("Open Debrid services" to "Debrid services", "Set up with your phone" to "phone setup"),
            NotReady.Missing.TorrentIndexer to listOf("Set up with your phone" to "phone setup", "Add a torrent indexer" to "torrent indexer form"),
        )
        for ((missing, expected) in cases) {
            val buttons = notReadyButtons(NotReady("Galaxy phone", "Harbor Lights S02E04", missing), ways)
            assertEquals(expected.map { it.first }, buttons.map { it.first }, "$missing: the first is focused")
            opened.clear()
            buttons.forEach { it.second() }
            assertEquals(expected.map { it.second }, opened, "$missing")
        }
        // Hidden: no way to a debrid page, so only the phone's (a hidden TV's readiness never lacks these, L254).
        val hidden = NotReadyWays(phoneSetup = {}, addProvider = {}, addIndexer = {})
        for (missing in listOf(NotReady.Missing.Service, NotReady.Missing.ServiceOff("TorBox"), NotReady.Missing.TorrentIndexer)) {
            assertEquals(listOf("Set up with your phone"), notReadyButtons(NotReady("Galaxy phone", "Harbor Bell", missing), hidden).map { it.first }, "$missing")
        }
        assertEquals(
            listOf("Set up with your phone", "Add a Usenet provider"),
            notReadyButtons(NotReady("Galaxy phone", "Harbor Bell", NotReady.Missing.Provider), hidden).map { it.first },
        )
    }

    /** The sheet's Search searches the episode's words (its id searches just failed), ranked as the show's releases are. */
    @Test
    fun `the no-release sheet's search for an episode ranks its untagged releases in the show's language`() {
        val show = ShowDetails(
            900040, "Moonwell Village", "Dalsaem Maeul", 2021, null, null, null, "Ended", false, 60, emptyList(), null, null, ExternalIds(), originalLanguage = "ko",
        )
        val ep = EpisodePlay.of(show, Episode(1, 2, "The Well", null, LocalDate.of(2021, 3, 6), null, null), today = LocalDate.of(2026, 10, 18))
        val sheet = noRelease(ep, FindResult(ep.request, emptyList(), emptyList(), emptyList()), LocalDate.of(2026, 10, 18))
        val request = assertNotNull(sheet.searchRoute().request())
        assertEquals("Moonwell Village s01e02" to null, request.text to request.pick)

        fun item(title: String) = NewznabItem(
            indexerId = "a", guid = title, title = title, sizeBytes = 2_000_000_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        )
        val english = RankingContext(StaticDeviceCapabilities(videoTenBit = emptySet()), RankingPrefs(audioLanguages = listOf("en")))
        val grouped = ResultGrouper().group(
            listOf(
                item("Moonwell.Village.S01E02.KOREAN.SUBBED.1080p.WEB.H264-A"),
                item("Moonwell.Village.S01E02.1080p.WEB.H264-B"),
                item("Moonwell.Village.S01E02.ENGLISH.1080p.WEB.H264-C"),
            ),
            request.rankingContext(english),
        )
        // The subbed and untagged releases speak Korean: the English dub is the one in the preferred language.
        assertEquals("Moonwell.Village.S01E02.ENGLISH.1080p.WEB.H264-C", grouped.sections.single().groups.single().playable.first().item.title)
    }

    /** A release incomplete only on some providers is said to be so in the notice too, as in its failure line (#172). */
    @Test
    fun `the notice says a release is incomplete only on the providers that served`() {
        assertEquals("Release incomplete", shortFailure(AttemptFailure.MissingArticles("430")))
        assertEquals("Incomplete on the providers in use", shortFailure(AttemptFailure.MissingArticles("430", providersLeftOut = true)))
        assertEquals(
            "Incomplete on the providers that answered",
            shortFailure(AttemptFailure.MissingArticles("No provider could answer", unanswered = UsenetError.AuthFailed("481"))),
        )
        assertEquals("Release passworded", shortFailure(AttemptFailure.Passworded))
        assertEquals("No playable video", shortFailure(AttemptFailure.NoPlayableVideo(listOf("2160p Dolby Vision profile 7"))))
    }

    /** An automatic pick's notice when a file can't be cast (L97): "AVI file can't be cast · trying release 2 of 3". */
    @Test
    fun `the notice names the file type a Cast device can't play`() {
        assertEquals("AVI file can't be cast", shortFailure(AttemptFailure.NotForCast("avi")))
        assertEquals("M2TS file can't be cast", shortFailure(AttemptFailure.NotForCast("m2ts")))
        assertEquals("Unknown file type can't be cast", shortFailure(AttemptFailure.NotForCast("")))
    }

    /**
     * A file the player can't read, in the notice (L318): its type where its name gives one, as the notice names a file a
     * Cast device can't play; a reader that crashed in the words its failure line has.
     */
    @Test
    fun `the notice names the type of a file the player can't read`() {
        assertEquals("M2TS file can't be read", shortFailure(AttemptFailure.Unreadable(UnreadableFile.UnknownFormat, "M2TS")))
        assertEquals("MKV file can't be read", shortFailure(AttemptFailure.Unreadable(UnreadableFile.Broken, "MKV")))
        assertEquals("File can't be read", shortFailure(AttemptFailure.Unreadable(UnreadableFile.UnknownFormat, null)))
        assertEquals("File can't be read", shortFailure(AttemptFailure.Unreadable(UnreadableFile.Broken, null)))
        assertEquals("Reading the release failed", shortFailure(AttemptFailure.Unreadable(UnreadableFile.ReaderCrashed, "MKV")))
        assertEquals("M2TS file can't be read · trying release 2 of 3", retryNotice(AttemptFailure.Unreadable(UnreadableFile.UnknownFormat, "M2TS"), 2, 3, null))
        // TalkBack reads the step row as it shows.
        assertEquals(
            "Checking audio and video, failed: The player can't read this M2TS file",
            stepSpoken(STEP_LABELS.getValue(PipelineStep.ProbeTracks), StepStatus.Failed(900, AttemptFailure.Unreadable(UnreadableFile.UnknownFormat, "M2TS"))),
        )
    }

    /**
     * A disc copy skipped (L314): its line, said whichever source it came from; the notice for disc copies alone, and for
     * them among others of any kind, Usenet's or a debrid service's, which no single reason covers; the list's chip.
     */
    @Test
    fun `a disc copy skipped is said as one, alone or among others`() {
        val day = 24 * 60 * 60 * 1000L
        val checked = LocalDate.of(2026, 11, 7).atTime(21, 9).toInstant(ZoneOffset.UTC).toEpochMilli()
        val disc = KnownMissing(checked, incompleteNzb = false, discCopy = true)
        val line = skippedLine(disc, checked, ZoneOffset.UTC)
        assertEquals("Skipped: a disc copy (checked today)", line)
        assertEquals("Skipped: a disc copy (checked yesterday)", skippedLine(disc, checked + day, ZoneOffset.UTC))
        assertEquals("Skipped: a disc copy (checked 3 days ago)", skippedLine(disc, checked + 3 * day, ZoneOffset.UTC))
        assertEquals("Skipped 1 release that is a disc copy", skippedNotice(listOf(line)))
        assertEquals("Skipped 2 releases that are disc copies", skippedNotice(listOf(line, line)))
        assertEquals(
            "Skipped 3 releases that failed before",
            skippedNotice(listOf("Skipped: missing on your providers (checked today)", line, "Skipped: needs a password (checked today)")),
        )
        assertEquals("Skipped 2 releases that failed before", skippedNotice(listOf(line, "No longer cached")))
        assertEquals("Skipped 3 releases that failed before", skippedNotice(listOf("Removed by the debrid service", line, "Skipped: incomplete NZB (checked today)")))
        assertEquals("Disc copy · 7 Nov", missingChip(disc, LocalDate.of(2026, 11, 8), ZoneOffset.UTC))
        assertEquals("Disc copy · 7 Nov 2026", missingChip(disc, LocalDate.of(2027, 1, 2), ZoneOffset.UTC))
    }

    /** A disc copy, in the notice (L313): what it is a copy of, short; its line says what the app plays instead. */
    @Test
    fun `the notice names what a disc copy is a copy of`() {
        assertEquals(
            listOf("Blu-ray disc copy", "DVD copy", "Disc image"),
            listOf(DiscKind.BluRay, DiscKind.Dvd, DiscKind.Image).map { shortFailure(AttemptFailure.DiscCopy(it)) },
        )
        assertEquals("Blu-ray disc copy · trying release 2 of 3", retryNotice(AttemptFailure.DiscCopy(DiscKind.BluRay), 2, 3, null))
        // TalkBack reads the step row as it shows.
        assertEquals(
            "Opening, failed: A copy of a Blu-ray disc: NZStreamer plays single video files",
            stepSpoken(STEP_LABELS.getValue(PipelineStep.Open), StepStatus.Failed(900, AttemptFailure.DiscCopy(DiscKind.BluRay))),
        )
    }

    /**
     * "Plays on this device" is the ranker's word for the release: the probe finding no audio or video this device plays
     * says otherwise, so the sheet drops it beside that failed step; any other step's failure leaves it.
     */
    @Test
    fun `the sheet says the release plays on this device unless the probe found it doesn't`() {
        val opened = mapOf(PipelineStep.FetchNzb to StepStatus.Done(10), PipelineStep.CheckAvailability to StepStatus.Done(10), PipelineStep.Open to StepStatus.Done(10))
        assertEquals(true, playsHere(opened + (PipelineStep.ProbeTracks to StepStatus.Running())))
        assertEquals(true, playsHere(opened + (PipelineStep.ProbeTracks to StepStatus.Done(10))))
        assertEquals(true, playsHere(mapOf(PipelineStep.FetchNzb to StepStatus.Failed(10, AttemptFailure.Passworded))))
        assertEquals(false, playsHere(opened + (PipelineStep.ProbeTracks to StepStatus.Failed(10, AttemptFailure.NoPlayableVideo(listOf("2160p HEVC 10-bit"))))))
        assertEquals(false, playsHere(opened + (PipelineStep.ProbeTracks to StepStatus.Failed(10, AttemptFailure.NoPlayableAudio(listOf("TrueHD"))))))
    }

    /** The phone's probe isn't the Cast device's (L65): a cast's sheet never says the release plays on this device. */
    @Test
    fun `a cast's sheet doesn't say the release plays on this device`() {
        val probed = mapOf(PipelineStep.FetchNzb to StepStatus.Done(10), PipelineStep.ProbeTracks to StepStatus.Done(10))
        assertEquals(true, playsHere(probed, cast = null))
        assertEquals(false, playsHere(probed, cast = kitchen))
    }

    /**
     * W63: with nothing the device can't play, the audio and subtitles lines show on the starting sheet under "Casting to
     * Kitchen display", once the file is checked; a play here has none, nor says them.
     */
    @Test
    fun `a cast's starting sheet says its audio and subtitles lines under where it plays`() {
        val lines = listOf(
            CastLine(CastLine.Kind.Audio, "Kitchen display will play the German audio: Cast devices play a file’s first sound track."),
            CastLine(CastLine.Kind.Subtitles, "Subtitles don’t show on Cast devices"),
        )
        val starting = PlayState.Running(
            "Lantern Field", "2008", null, 1, 1, "Lantern.Field.2008.German.DL.1080p.BluRay.x264-WATTWURM", emptyList(),
            PipelineStep.entries.associateWith { StepStatus.Done(10) }, notice = null, castStep = StepStatus.Running(), castLines = lines,
        )
        assertEquals(lines, castLinesShown(starting, kitchen))
        assertEquals(emptyList(), castLinesShown(starting, cast = null))
        assertEquals(emptyList(), castLinesShown(starting.copy(castLines = emptyList()), kitchen))
    }

    /** Minimised, a cast's start says where it goes, then the step (CsPhoneStarting's note): "On Kitchen display · Starting…". */
    @Test
    fun `the mini bar says the device before the step, and the device's own step once it has the release`() {
        val fetching = PlayState.Running(
            "Harbor Lights", "S02E04 · Low Water", null, 1, 3, "Harbor.Lights.S02E04.1080p.WEB-DL.H.264-GRP", emptyList(),
            mapOf(PipelineStep.FetchNzb to StepStatus.Running()), notice = null,
        )
        assertEquals("Fetching NZB…", runningLine(fetching, cast = null))
        assertEquals("On Kitchen display · Fetching NZB…", runningLine(fetching, kitchen))
        val starting = fetching.copy(steps = PipelineStep.entries.associateWith { StepStatus.Done(10) }, castStep = StepStatus.Running())
        assertEquals("On Kitchen display · Starting…", runningLine(starting, kitchen))
        val searching = PlayState.Searching("Harbor Lights", "S02E04 · Low Water", "Searching your indexers…")
        assertEquals("Searching your indexers…", searchingLine(searching, cast = null))
        assertEquals("On Kitchen display · Searching your indexers…", searchingLine(searching, kitchen))
        val failed = PlayState.Failed("Harbor Lights", "S02E04 · Low Water", emptyList(), notTried = 0)
        assertEquals("Couldn't play Harbor Lights", failedLine(failed, cast = null))
        assertEquals("Couldn't play Harbor Lights on Kitchen display", failedLine(failed, kitchen))
    }

    /**
     * A torrent's sheet lists its own three steps, Preparing first, for whatever its service needs (L203, DbPhonePlaySheet
     * 1); a Usenet release's its four, as before. Minimised, the bar names the step; while searching, the sheet lists
     * Usenet's steps, or a torrent's for a user with a service and no usable provider.
     */
    @Test
    fun `a torrent's sheet lists Preparing, Opening and the probe, a Usenet release's its four steps`() {
        val torrent = PlayState.Running(
            "Harbor Lights", "S02E04 · Low Water", "Searched 4 indexers by TVDB id · 9 found", 1, 3, "Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.H.264-KESTREL",
            emptyList(), mapOf(PipelineStep.Prepare to StepStatus.Running()), notice = null, sourceSteps = ServiceSource.STEPS,
        )
        assertEquals(listOf("Preparing" to StepStatus.Running(), "Opening" to null, "Checking audio and video" to null), stepRows(torrent))
        assertEquals("Preparing…", runningLine(torrent, cast = null))
        val opening = torrent.copy(steps = mapOf(PipelineStep.Prepare to StepStatus.Done(800), PipelineStep.Open to StepStatus.Running()))
        assertEquals("Opening…", runningLine(opening, cast = null))
        assertEquals(PipelineStep.Open, currentStep(mapOf(PipelineStep.Prepare to StepStatus.Done(800), PipelineStep.Open to StepStatus.Done(500)), ServiceSource.STEPS))
        val usenet = torrent.copy(steps = mapOf(PipelineStep.FetchNzb to StepStatus.Done(600, "2.9 MB")), sourceSteps = UsenetSource.STEPS)
        assertEquals(listOf("Fetching NZB", "Checking availability", "Opening", "Checking audio and video"), stepRows(usenet).map { it.first })
        assertEquals(StepStatus.Done(600, "2.9 MB"), stepRows(usenet).first().second)
        // While searching: Usenet's steps while a provider is usable, a torrent's for a user with only a service on.
        assertEquals(UsenetSource.STEPS, searchingSteps(UsableRoutes.of(providerUsable = true, accountsOn = listOf("tb"))))
        assertEquals(ServiceSource.STEPS, searchingSteps(UsableRoutes.of(providerUsable = false, accountsOn = listOf("tb"))))
        assertEquals(UsenetSource.STEPS, searchingSteps(UsableRoutes.of(providerUsable = true, accountsOn = emptyList())))
    }

    /**
     * "Release 1 of 3" with where the release plays from beside it, as the lists tag it (DbPhonePlaySheet, DbTvPlaySheet),
     * only while a service account is on; and a step reads as its name and how it went, "Preparing" naming no service (L214).
     */
    @Test
    fun `the release line carries its source's tag, and a step reads as its name and how it went`() {
        val names = listOf(NamedAccount("tb", "TorBox"))
        val cached = rankedItem("Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.H.264-KESTREL", HASH, Availability.Cached(listOf("tb")))
        val nzb = rankedItem("Harbor.Lights.S02E04.Low.Water.1080p.BluRay.DD5.1.x264-ORCHID", null, null)
        val running = PlayState.Running("Harbor Lights", "S02E04 · Low Water", null, 1, 3, cached.item.title, emptyList(), emptyMap(), notice = null, tag = playTag(cached, names, from = null))
        assertEquals("Release 1 of 3", releaseLine(running))
        assertEquals("Release", releaseLine(running.copy(of = 1)))
        assertEquals(SourceTag("TorBox", "From TorBox", dim = false), running.tag)
        assertEquals(SourceTag("Usenet", "From Usenet", dim = false), sourceTag(nzb, names))
        assertNull(sourceTag(nzb, emptyList()))
        assertEquals("Preparing", stepSpoken(STEP_LABELS.getValue(PipelineStep.Prepare), null))
        assertEquals("Preparing, in progress", stepSpoken("Preparing", StepStatus.Running()))
        assertEquals("Preparing, done", stepSpoken("Preparing", StepStatus.Done(800)))
        assertEquals("Checking availability, in progress, 112 of 256", stepSpoken("Checking availability", StepStatus.Running(112, 256)))
        assertEquals("Preparing, failed: No longer cached", stepSpoken("Preparing", StepStatus.Failed(10, AttemptFailure.NoLongerCached)))
    }

    /**
     * DEP-6 (D2PhonePlaySheetRetry 1-2, choice 3): as the same release is tried on the next debrid service, the notice says
     * the failure in the sheet's short words and that the next service is tried, naming no service but a refused key's own;
     * the steps after Preparing go back to waiting, so the sheet and its minimised line say Preparing again; and the tag
     * names the account trying it now, Premiumize, then TorBox, with no "+1" though both have it.
     */
    @Test
    fun `the next service's try says so in the notice, its rows back at Preparing, its tag the account trying it now`() {
        val down = AttemptFailure.ServiceDown("service:pm")
        assertEquals("The debrid service stopped answering · trying your next debrid service", nextServiceNotice(down, cast = null))
        assertEquals("No longer available from the service · trying your next debrid service", nextServiceNotice(AttemptFailure.NoWorkingLink, cast = null))
        assertEquals("No longer cached · trying your next debrid service", nextServiceNotice(AttemptFailure.NoLongerCached, cast = null))
        // The wait is the failure sheet's, as in the notice moving on to the next release.
        assertEquals("The debrid service’s limit was reached · trying your next debrid service", nextServiceNotice(AttemptFailure.ServiceLimit(20.minutes, "service:pm"), cast = null))
        assertEquals("TorBox refused your API key · trying your next debrid service", nextServiceNotice(AttemptFailure.KeyRefused("TorBox", "service:tb"), cast = null))

        // Opening failed (frame 1): Preparing stays done until it runs again, the steps after it go back to waiting.
        val opened = mapOf(PipelineStep.Prepare to StepStatus.Done(800), PipelineStep.Open to StepStatus.Failed(400, down))
        val again = retriedSteps(opened, ServiceSource.STEPS)
        assertEquals(mapOf<PipelineStep, StepStatus>(PipelineStep.Prepare to StepStatus.Done(800)), again)
        assertEquals(PipelineStep.Prepare, currentStep(again, ServiceSource.STEPS))
        // The probe failed: the same, and "Plays on this device" back with it.
        val probed = mapOf(
            PipelineStep.Prepare to StepStatus.Done(800), PipelineStep.Open to StepStatus.Done(500),
            PipelineStep.ProbeTracks to StepStatus.Failed(900, AttemptFailure.NoWorkingLink),
        )
        assertEquals(mapOf<PipelineStep, StepStatus>(PipelineStep.Prepare to StepStatus.Done(800)), retriedSteps(probed, ServiceSource.STEPS))
        assertEquals(false, playsHere(probed))
        assertEquals(true, playsHere(retriedSteps(probed, ServiceSource.STEPS)))

        // Frame 2: Preparing again on the next service.
        val names = listOf(NamedAccount("pm", "Premiumize"), NamedAccount("tb", "TorBox"))
        val cached = rankedItem("Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.H.264-KESTREL", HASH, Availability.Cached(listOf("pm", "tb")))
        val preparing = PlayState.Running(
            "Harbor Lights", "S02E04 · Low Water", "Searched 4 indexers by TVDB id · 9 found", 1, 3, cached.item.title, emptyList(),
            again + (PipelineStep.Prepare to StepStatus.Running()), notice = nextServiceNotice(down, cast = null), noticeFailure = down,
            noticeNextService = true, sourceSteps = ServiceSource.STEPS, tag = playTag(cached, names, from = "tb"),
        )
        assertEquals(listOf("Preparing" to StepStatus.Running(), "Opening" to null, "Checking audio and video" to null), stepRows(preparing))
        assertEquals("Preparing…", runningLine(preparing, cast = null))
        assertEquals("Release 1 of 3", releaseLine(preparing))
        assertEquals(SourceTag("Premiumize", "From Premiumize", dim = false), playTag(cached, names, from = "pm"))
        assertEquals(SourceTag("TorBox", "From TorBox", dim = false), preparing.tag)
    }

    private fun rankedItem(title: String, infoHash: String?, availability: Availability?) = RankedItem(
        NewznabItem(
            indexerId = "prowlarr", guid = infoHash?.let { "btih:$it" } ?: "g1", title = title, sizeBytes = 0, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null,
            season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(), infoHash = infoHash,
            availability = availability,
        ),
        RankedRelease(ReleaseFacts(infoHash ?: "g1", title, 0), ReleaseParser.parse(title), playable = true, score = 0.0, reasons = emptyList(), estimatedBitrate = null),
    )

    /** Releases skipped as found incomplete before are listed with those tried, so the words above them say "couldn't" only. */
    @Test
    fun `the failure sheet's words fit what was tried and what was skipped`() {
        fun failed(tried: Int, skipped: Int) = PlayState.Failed(
            "Harbor Lights", "S01E01", List(tried) { "Release $it" to "Release incomplete" }, notTried = 0,
            missing = List(skipped) { "Skipped $it" to "Skipped: missing on your providers (checked 3 days ago)" },
        )
        assertEquals("Couldn't start playback. The release failed:", failedHeader(failed(tried = 1, skipped = 0)))
        assertEquals("Couldn't start playback. Each release tried failed:", failedHeader(failed(tried = 2, skipped = 0)))
        assertEquals("Couldn't start playback:", failedHeader(failed(tried = 1, skipped = 1)))
        assertEquals("Couldn't start playback:", failedHeader(failed(tried = 0, skipped = 2)))
    }

    private companion object {
        const val HASH = "c0ffee11c0ffee22c0ffee33c0ffee44c0ffee55"
    }
}
