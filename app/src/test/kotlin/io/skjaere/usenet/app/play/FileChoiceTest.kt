package io.skjaere.usenet.app.play

import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.app.collection.chooseFileRequest
import io.skjaere.usenet.app.collection.chosenRequest
import io.skjaere.usenet.app.search.offersFileChoice
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.app.casting.CastPrecheck
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.RankedItem
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A release of several videos that names none of them for the episode wanted ("Harbor.Lights.2-6.mp4", episode 2 of 6,
 * isn't read as an episode number) has the play sheet ask which it is: it says which episode it looks for and lists the
 * release's videos, largest first and samples last. Choose release's "Choose file" asks so of any release of several
 * videos, whatever the file remembered for the episode.
 */
class FileChoiceTest {
    private val metadata = ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(emptyList()))

    private fun prepared(id: String, title: String, files: List<Pair<String, Long>>) =
        PreparedNzb(id, title, metadata, files.mapIndexed { i, (name, size) -> FileCandidate(i, StreamableFile(name, size, i, 0, 0, 0)) })

    /** Harbor Lights season 2, its six episodes named "n_6" (one "2-6"), with a sample listed first. */
    private val pack = prepared(
        "unnamed", "Harbor.Lights.S02.720p.WEB.h264-GRP",
        listOf(
            "Harbor.Lights.sample.mp4" to 50_000_000L, "Harbor.Lights.1_6.mp4" to 950_000_000L, "Harbor.Lights.2-6.mp4" to 1_200_000_000L,
            "Harbor.Lights.3_6.mp4" to 1_000_000_000L, "Harbor.Lights.4_6.mp4" to 980_000_000L, "Harbor.Lights.5_6.mp4" to 1_100_000_000L,
            "Harbor.Lights.6_6.mp4" to 990_000_000L, "Harbor.Lights.nfo" to 2_000L,
        ),
    )

    private val ep = EpisodePlay(
        showTmdbId = 900002, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tvdb = 900001, tmdb = 900002),
        season = 2, episode = 1, episodeName = "Brine", airDate = null, runtimeMinutes = 45,
    )

    private fun item(title: String) = RankedItem(
        NewznabItem(
            indexerId = "fake", guid = "g-" + title.lowercase(), title = title, sizeBytes = 7_000_000_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("fake/g-$title", title, 7_000_000_000), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
    )

    @Test
    fun `the sheet says which episode it looks for and lists the videos, largest first and samples last`() {
        val request = chosenRequest(ep, item(pack.title))
        val state = fileChoice(request, pack.title, FilePicker.Episode(2, 1).choices(pack))
        assertEquals("Harbor Lights" to "S02E01 · Brine", state.title to state.subtitle)
        assertEquals("Looking for S02E01 'Brine'", state.lookingFor)
        assertEquals(pack.title, state.release)
        assertEquals(
            listOf("2-6", "5_6", "3_6", "6_6", "4_6", "1_6", "sample").map { "Harbor.Lights.$it.mp4" },
            state.files.map { it.name },
        )
        assertEquals(List(6) { false } + true, state.files.map { it.sample })
        assertEquals(FileChoice(2, "Harbor.Lights.2-6.mp4", 1_200_000_000L, sample = false), state.files.first())
    }

    /** Where nothing tells which is the episode, the sheet says so; asked to choose, it says what plays and marks the file named for it. */
    @Test
    fun `the sheet says why it asks`() {
        val unnamed = fileChoice(chosenRequest(ep, item(pack.title)), pack.title, FilePicker.Episode(2, 1).choices(pack))
        assertEquals("No file in this release is named for it. Choose the one it is:" to false, unnamed.prompt to unnamed.asked)
        assertTrue(unnamed.files.all { it.named == null })
        val named = prepared("named", pack.title, (1..3).map { "Harbor.Lights.S02E0$it.720p.WEB.h264-GRP.mkv" to 1_000_000_000L + it } + ("Harbor.Lights.S02E01.sample.mkv" to 1L))
        val asked = fileChoice(chooseFileRequest(ep, item(pack.title)), pack.title, ChooseFile(FilePicker.Episode(2, 1)).choices(named))
        assertEquals("Choose the file to play as S02E01:" to true, asked.prompt to asked.asked)
        assertEquals(listOf(null, null, "S02E01", null), asked.files.map { it.named })
        assertEquals("Harbor.Lights.S02E01.720p.WEB.h264-GRP.mkv", asked.files.single { it.named != null }.name)
    }

    /**
     * The play's steps arrive on another thread than the question: one that comes late doesn't take the question's place
     * while the play waits on it. Once it is answered (or there is none), the steps show again.
     */
    @Test
    fun `the question stays on the sheet while the play waits on it`() {
        val question = fileChoice(chosenRequest(ep, item(pack.title)), pack.title, FilePicker.Episode(2, 1).choices(pack))
        val running = PlayState.Running("Harbor Lights", "S02E01 · Brine", "Searched 1 indexer", 1, 1, pack.title, emptyList(), mapOf(PipelineStep.Open to StepStatus.Running()), null)
        assertSame(question, runningOver(question, running, asking = true))
        assertSame(running, runningOver(question, running, asking = false))
        assertSame(running, runningOver(PlayState.Searching("Harbor Lights", "S02E01 · Brine", "Searching…"), running, asking = true))
        // The pre-check's question too (L130), the play waiting on it in the probe as on the file in Open.
        val precheck = PlayState.Prechecking(
            "Harbor Lights", "S02E01 · Brine",
            CastPrecheck("No sound on Kitchen display", "DTS 5.1 is its only sound.", pack.title, emptyList(), "Checked before anything was sent to Kitchen display.", compatible = false, here = true),
        )
        assertSame(precheck, runningOver(precheck, running, asking = true))
        assertSame(running, runningOver(precheck, running, asking = false))
    }

    /** Without the episode's name it says the episode alone; from Search, a season pack's Play looks for its first episode. */
    @Test
    fun `what it looks for is the episode the play is for`() {
        assertEquals("Looking for S02E01", lookingFor(chosenRequest(ep.copy(episodeName = null), item(pack.title))))
        assertEquals("Looking for S02E01 'Brine'", lookingFor(chooseFileRequest(ep, item(pack.title))))
        val fromSearch = PlayRequest("Harbor Lights", "S02 · episode 1", listOf(item(pack.title)), FilePicker.Episode(2, 1), WatchTarget(WatchKind.Release, pack.title))
        assertEquals("Looking for S02E01", lookingFor(fromSearch))
    }

    @Test
    fun `Choose file asks which file of any release of several videos, and plays a release of one`() {
        val choose = ChooseFile(FilePicker.Episode(2, 1))
        assertNull(choose.pick(pack))
        assertEquals(pack.candidates.filter { it.isVideo }, choose.choices(pack))
        // Named for their episodes too: the user picks.
        val named = prepared("named", pack.title, (1..3).map { "Harbor.Lights.S02E0$it.720p.WEB.h264-GRP.mkv" to 1_000_000_000L + it })
        assertNull(choose.pick(named))
        assertEquals(named.candidates, choose.choices(named))
        val single = prepared("single", "Harbor.Lights.S02E01.720p.WEB.h264-GRP", listOf("a8f3c1.mkv" to 1_000_000_000L, "a8f3c1.nfo" to 1_000L))
        assertEquals("a8f3c1.mkv", choose.pick(single)?.name)
        // Checked on the file chosen, once it is: a gap in another file doesn't matter.
        assertFalse(choose.takesWhole(single.title))
        assertFalse(choose.takesWhole(pack.title))
        assertEquals(AttemptFailure.NoVideo, choose.noMatchIn(prepared("none", pack.title, listOf("Harbor.Lights.rar" to 1_000L))))
        assertEquals(AttemptFailure.NoMatchingEpisode(2, 1), choose.noMatch)
    }

    /** Choose file asks even where a file is remembered for the episode in that NZB; any other play picks that file. */
    @Test
    fun `Choose file isn't the file remembered for the episode`() {
        val remembered = RememberedRelease("fake", "g-" + pack.title.lowercase(), pack.title, null, 1, pack.id, "Harbor.Lights.1_6.mp4", 950_000_000L)
        val asking = chooseFileRequest(ep, item(pack.title))
        assertSame(asking.picker, playPicker(asking, remembered))
        val plain = chosenRequest(ep, item(pack.title))
        assertEquals(Replay.picker(plain.picker, remembered, plain.watch), playPicker(plain, remembered))
        assertEquals("Harbor.Lights.1_6.mp4", playPicker(plain, remembered).pick(pack)?.name)
    }

    /**
     * The release remembered for the episode, its file gone from its NZB: there, which of its videos is the episode is asked;
     * in any other NZB, as the request's picker asks: of its videos where none is named for an episode, of none where one is.
     */
    @Test
    fun `in the remembered release's NZB the episode is asked for where the file remembered is gone`() {
        val gone = RememberedRelease("fake", "g-pack", pack.title, null, 9, pack.id, "Harbor.Lights.Gone.mp4", 1L)
        val picker = Replay.picker(FilePicker.Episode(2, 1), gone, ep.watch)
        assertNull(picker.pick(pack))
        assertEquals(FilePicker.Episode(2, 1).choices(pack), picker.choices(pack))
        assertEquals(7, picker.choices(pack).size)
        val other = prepared("other", pack.title, listOf("Harbor.Lights.x.mp4" to 2L, "Harbor.Lights.y.mp4" to 3L))
        assertEquals(listOf("Harbor.Lights.x.mp4", "Harbor.Lights.y.mp4"), picker.choices(other).map { it.name })
        val named = prepared("named", pack.title, listOf("Harbor.Lights.S02E02.mkv" to 1L, "Harbor.Lights.x.mp4" to 2L))
        assertEquals(emptyList(), picker.choices(named))
    }

    @Test
    fun `Choose file is offered on the rows of releases named as several episodes`() {
        assertTrue(item("Harbor.Lights.S02.720p.WEB.h264-GRP").offersFileChoice)
        assertTrue(item("Harbor.Lights.S01-S02.720p.WEB.h264-GRP").offersFileChoice)
        assertTrue(item("Harbor.Lights.S02E01E02.720p.WEB.h264-GRP").offersFileChoice)
        assertFalse(item("Harbor.Lights.S02E01.720p.WEB.h264-GRP").offersFileChoice)
        assertFalse(item("Copper.Crown.2019.1080p.BluRay.x264-GRP").offersFileChoice)
    }

    @Test
    fun `the Show page's Choose file plays the release as chosen, asking which file is the episode`() {
        val release = item(pack.title)
        assertEquals(chosenRequest(ep, release).copy(picker = ChooseFile(FilePicker.Episode(2, 1))), chooseFileRequest(ep, release))
        assertEquals("Brine", chosenRequest(ep, release).episodeName)
    }
}
