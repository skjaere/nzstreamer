package io.skjaere.usenet.app.play

import io.skjaere.nntp.YencHeaders
import io.skjaere.nzbstreamer.enrichment.VerificationResult
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.nzb.NzbFile
import io.skjaere.nzbstreamer.nzb.NzbSegment
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.core.AvailabilityBackend
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.FileChooser
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.PipelineEvent
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.PlayPipeline
import io.skjaere.usenet.core.PrepareBackend
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.core.UsenetSource
import io.skjaere.usenet.app.debrid.ServiceFiles
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceListing
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseRanker
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.EpisodeRequest
import io.skjaere.usenet.search.FindResult
import io.skjaere.usenet.search.FoundRelease
import io.skjaere.usenet.search.SearchStage
import io.skjaere.usenet.search.ShowRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The episode either side of one playing from a season pack, played from that pack (auto-advance's card, the
 * controls' Next and Previous): its file picked as an episode's is, with no search, no grab and no prepare, and
 * checked and probed as any play's file is. Not from a release of one video, nor for an episode the pack lacks.
 */
class PlayingPackTest {
    /** Where a play keeps what plays: the coordinator does once it plays, which these plays don't reach. */
    @TempDir
    lateinit var nzbDir: File

    /** A release named [title] of [files], each in a volume of its own: its NZB's [bytes], and the NZB as prepared ([nzb]). */
    private class Pack(title: String, val files: List<StreamableFile>) {
        private val volumes = files.map { file ->
            NzbFile("p", 0, "\"${file.path}\" yEnc (1/1)", listOf("alt.binaries.fake"), listOf(NzbSegment(10, 1, "${file.path}@fake")), YencHeaders(128, file.totalSize, file.path))
        }
        val bytes = volumes.joinToString("\n", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<nzb xmlns=\"http://www.newzbin.com/DTD/2003/nzb\">\n", "\n</nzb>") { volume ->
            """<file poster="p" date="0" subject="${volume.subject.replace("\"", "&quot;")}"><groups><group>alt.binaries.fake</group></groups>""" +
                """<segments><segment bytes="10" number="1">${volume.segments.single().articleId}</segment></segments></file>"""
        }.encodeToByteArray()
        val metadata = ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(volumes))
        val nzb = PreparedNzb(PreparedNzb.idFor(bytes), title, metadata, files.mapIndexed(::FileCandidate))
    }

    /** Harbor Lights season 2 in one release: E01 to E06, each in a volume of its own. */
    private val namedPack = Pack(
        "Harbor.Lights.S02.1080p.WEB.h264-GRP",
        (1..6).map { e -> StreamableFile("Harbor.Lights.S02E%02d.1080p.WEB.h264-GRP.mkv".format(e), 1_000_000_000L + e * 1_000, e - 1, 0, 0, 0) },
    )
    private val packFiles = namedPack.files
    private val packBytes = namedPack.bytes
    private val packMetadata = namedPack.metadata
    private val pack = namedPack.nzb

    /** Season 2 again, its episodes named for no episode the parser reads: episode n of 6 as "n_6" (one "2-6"), and a sample. */
    private val unnamedPack = Pack(
        "Harbor.Lights.S02.720p.WEB.h264-GRP",
        (1..6).map { e -> StreamableFile(if (e == 2) "Harbor.Lights.2-6.mp4" else "Harbor.Lights.${e}_6.mp4", 900_000_000L + e * 1_000, e - 1, 0, 0, 0) } +
            StreamableFile("Harbor.Lights.sample.mp4", 50_000_000L, 6, 0, 0, 0),
    )
    private val unnamed = unnamedPack.nzb

    /** E01 playing from the unnamed pack. */
    private val playingUnnamed = SavedRelease("fake", "hl-s02-unnamed", unnamed.title, null, 0, unnamed.id, unnamedPack.files[0].path, unnamedPack.files[0].totalSize)

    /** E05 playing from the pack. */
    private val playing = SavedRelease("fake", "hl-s02-pack", pack.title, "http://10.0.2.2:8089/api?t=get&id=hl-s02-pack&apikey={apikey}", 4, pack.id, packFiles[4].path, packFiles[4].totalSize)

    private fun episode(n: Int) = EpisodePlay(
        showTmdbId = 900002, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tmdb = 900002),
        season = 2, episode = n, episodeName = "Episode $n", airDate = null, runtimeMinutes = 45,
    )

    @Test
    fun `the episodes either side of one playing from a season pack are its files named for them`() {
        assertEquals(packFiles[5].path, PlayingPack.fileFor(pack, playing, 2, 6)?.name)
        assertEquals(packFiles[3].path, PlayingPack.fileFor(pack, playing, 2, 4)?.name)
        // One the pack doesn't hold, or another season's, is searched for as before.
        assertNull(PlayingPack.fileFor(pack, playing, 2, 7))
        assertNull(PlayingPack.fileFor(pack, playing, 3, 1))
    }

    /** A release of one video is one episode, whatever its file is named; nor is the file playing another episode (a file of two). */
    @Test
    fun `a release of one video, or the file playing, holds no other episode`() {
        val single = PreparedNzb("single", "Harbor.Lights.S02E05.1080p.WEB.h264-GRP", packMetadata, listOf(FileCandidate(0, StreamableFile("a8f3c1.mkv", 1_000_000_000L, 0, 0, 0, 0))))
        assertNull(PlayingPack.fileFor(single, playing.copy(nzbId = "single", fileIndex = 0, fileName = "a8f3c1.mkv"), 2, 6))
        val double = PreparedNzb(
            "double", "Harbor.Lights.S02E05E06.1080p.WEB.h264-GRP", packMetadata,
            listOf("Harbor.Lights.S02E05E06.1080p.WEB.h264-GRP.mkv", "Harbor.Lights.S02E07.1080p.WEB.h264-GRP.mkv").mapIndexed { i, name ->
                FileCandidate(i, StreamableFile(name, 1_000_000_000L + i, i, 0, 0, 0))
            },
        )
        val playingDouble = playing.copy(nzbId = "double", fileIndex = 0, fileName = double.candidates[0].name, fileSize = double.candidates[0].size)
        assertNull(PlayingPack.fileFor(double, playingDouble, 2, 6))
        assertEquals(double.candidates[1].name, PlayingPack.fileFor(double, playingDouble, 2, 7)?.name)
    }

    /** The file the user played for the episode from this NZB before (#179) is the one it plays again. */
    @Test
    fun `the file played for the episode from the pack before is played again`() {
        val picked = RememberedRelease("fake", "hl-s02-pack", pack.title, null, 2, pack.id, packFiles[2].path, packFiles[2].totalSize)
        assertEquals(packFiles[2].path, PlayingPack.fileFor(pack, playing, 2, 6, picked)?.name)
        // From another NZB of it (grabbed again), or a file no longer there: the episode's own.
        assertEquals(packFiles[5].path, PlayingPack.fileFor(pack, playing, 2, 6, picked.copy(nzbId = "other"))?.name)
        assertEquals(packFiles[5].path, PlayingPack.fileFor(pack, playing, 2, 6, picked.copy(fileName = "Gone.mkv", fileIndex = 9))?.name)
    }

    @Test
    fun `the request plays the pack's file alone as the episode, from its stored NZB`() {
        val file = assertNotNull(PlayingPack.fileFor(pack, playing, 2, 6))
        val play = PlayingPack.play(episode(6), playing, pack, file)
        val request = play.request
        assertEquals(listOf("fake/hl-s02-pack"), request.candidates.map { it.key })
        assertEquals("fake/hl-s02-pack", play.key)
        assertEquals(RememberedRelease("fake", "hl-s02-pack", pack.title, playing.downloadTemplate, 5, pack.id, packFiles[5].path, packFiles[5].totalSize), request.remembered)
        assertEquals(WatchTarget(WatchKind.Episode, "Harbor Lights S02E06", showTmdbId = 900002, season = 2, episode = 6), request.watch)
        assertEquals(FilePicker.Episode(2, 6), request.picker)
        assertEquals("Harbor Lights", request.title)
        assertEquals("S02E06 · Episode 6", request.subtitle)
        // The pack's file is checked once it is picked, on its volumes, not the whole pack before it is opened.
        val picker = Replay.picker(request.picker, request.remembered, request.watch)
        assertEquals(false, picker.takesWhole(pack.title))
        assertEquals(packFiles[5].path, picker.pick(pack)?.name)
    }

    /**
     * A season pack played from a debrid service (L181): the episode either side is found in the listing the service gave,
     * kept, with no search, and played from that torrent alone, remembered as a torrent, by its content id; one the pack's
     * names say it lacks is searched for without it.
     */
    @Test
    fun `the next episode of a torrent pack plays from its kept listing, with no search`() = runBlocking<Unit> {
        val hash = "b8e5ad63c94db5348740caabfbdca0083423ad21"
        val folder = "Harbor.Lights.S02.1080p.WEB-DL.H.264-TIDE"
        val listing = ServiceFiles(
            InfoHash.of(hash), folder,
            ServiceListing((1..6).map { ServiceFile(it - 1, "$folder/Harbor.Lights.S02E%02d.1080p.WEB-DL.H.264-TIDE.mkv".format(it), 900_000_000L + it, "f$it") }),
        )
        val playingTorrent = SavedRelease(
            "prowlarr", "btih:$hash", folder, null, 4, "btih:$hash", listing.files[4].name, listing.files[4].size, source = ReleaseFacts.TORRENT,
        )
        val found = assertNotNull(PlayingPack.find(listing, playingTorrent, 2, 6)?.file)
        assertEquals("Harbor.Lights.S02E06.1080p.WEB-DL.H.264-TIDE.mkv", found.name)
        val play = PlayingPack.play(episode(6), playingTorrent, listing, found)
        assertNull(play.prepared)
        assertEquals("btih:$hash", play.key)
        val remembered = assertNotNull(play.request.remembered)
        assertEquals(ReleaseFacts.TORRENT, remembered.source)
        assertEquals("btih:$hash", remembered.nzbId)
        assertEquals(found.name, remembered.fileName)
        assertEquals(ReleaseFacts.TORRENT, play.request.candidates.single().playCandidate.source)
        // The file is the one found, in the listing the service gives again.
        assertEquals(found.name, Replay.picker(play.request.picker, remembered, play.request.watch).pick(listing)?.name)

        assertTrue(PlayingPack.lacks(listing, playingTorrent, 2, 7))
        val searched = mutableListOf<Retry?>()
        PlayingPack.run(null, start = { error("nothing to play from") }, search = { searched += it }, lacking = playingTorrent.key)
        assertEquals(setOf("btih:$hash"), assertNotNull(searched.single()).failed)
    }

    /** The pack as prepared for the play is handed back for its own NZB, not prepared again; any other NZB is prepared. */
    @Test
    fun `the pack prepared for the play isn't prepared again`() = runBlocking<Unit> {
        val prepared = mutableListOf<String>()
        val backend = object : PrepareBackend {
            override suspend fun prepare(nzbBytes: ByteArray, password: String?): PrepareResult {
                prepared += nzbBytes.decodeToString()
                return PrepareResult.Success(packMetadata.copy())
            }

            override suspend fun streamableFiles(metadata: ExtractedMetadata) = packFiles.take(1)
        }
        val preparer = PlayingPack.preparer(pack, backend, Dispatchers.Unconfined)
        val held = assertIs<PrepareOutcome.Ready>(preparer.prepare(packBytes, pack.title)).nzb
        assertEquals(pack.id, held.id)
        assertEquals(pack.candidates, held.candidates)
        assertEquals(emptyList(), prepared)
        val other = assertIs<PrepareOutcome.Ready>(preparer.prepare("<nzb/>".encodeToByteArray(), "Other")).nzb
        assertEquals(listOf("<nzb/>"), prepared)
        assertEquals(packFiles.take(1), other.candidates.map { it.file })
    }

    /** Through the pipeline: no grab, no prepare, and only the file's own volume is checked. */
    @Test
    fun `the next episode plays from the stored pack without a grab or a prepare, checked on its own volume`() = runBlocking<Unit> {
        val play = PlayingPack.play(episode(6), playing, pack, assertNotNull(PlayingPack.fileFor(pack, playing, 2, 6)))
        val request = play.request
        val remembered = assertNotNull(request.remembered)
        val grabbed = mutableListOf<String>()
        val grab = NzbSource { candidate -> grabbed += candidate.id; error("grabbed ${candidate.id}") }
        val backend = object : PrepareBackend {
            override suspend fun prepare(nzbBytes: ByteArray, password: String?): PrepareResult = error("prepared again")
            override suspend fun streamableFiles(metadata: ExtractedMetadata): List<StreamableFile> = error("listed again")
        }
        val checked = mutableListOf<String>()
        val availability = AvailabilityBackend { nzb, _ -> checked += nzb.files.flatMap { f -> f.segments.map { it.articleId } }; VerificationResult.Success }
        val usenet = UsenetSource(ReplaySource(remembered, packBytes, grab), availability, PlayingPack.preparer(pack, backend, Dispatchers.Unconfined), PreparedNzbStore(nzbDir))
        val pipeline = PlayPipeline({ usenet }, dispatcher = Dispatchers.Unconfined)
        val end = pipeline.play(request.candidates.map { PlayCandidate(it.key, it.item.title, it.item.indexerId) }, Replay.picker(request.picker, remembered, request.watch)).last()
        val target = assertIs<PipelineEvent.Succeeded>(end, "$end").target
        assertEquals(packFiles[5].path, target.file.name)
        assertEquals(pack.id, target.listing.id)
        assertEquals(emptyList(), grabbed)
        assertEquals(listOf("${packFiles[5].path}@fake"), checked)
    }

    /**
     * An episode the pack playing names no file for (episode n of 6 named "n_6") is asked for there, with no search: in
     * that pack only while its name holds the episode's season. The file chosen for it from this NZB before plays again
     * without asking; a pack that names its files plays the episode's, and has none for one it lacks.
     */
    @Test
    fun `an episode the pack playing names no file for is asked for there`() {
        assertEquals(PlayingPack.InPack(null), PlayingPack.find(unnamed, playingUnnamed, 2, 2))
        assertNull(PlayingPack.find(unnamed, playingUnnamed, 3, 1))
        val picked = RememberedRelease("fake", "hl-s02-unnamed", unnamed.title, null, 1, unnamed.id, unnamedPack.files[1].path, unnamedPack.files[1].totalSize)
        assertEquals(PlayingPack.InPack(unnamed.candidates[1]), PlayingPack.find(unnamed, playingUnnamed, 2, 2, picked))
        assertEquals(PlayingPack.InPack(pack.candidates[5]), PlayingPack.find(pack, playing, 2, 6))
        assertNull(PlayingPack.find(pack, playing, 2, 7))
    }

    /** Through the pipeline: which file is the episode is asked of the stored pack's videos, with no grab and no prepare. */
    @Test
    fun `the episode's file is asked for in the stored pack, with no grab and no prepare, and the one chosen plays`() = runBlocking<Unit> {
        val request = PlayingPack.play(episode(2), playingUnnamed, unnamed, file = null).request
        assertEquals("Episode 2", request.episodeName)
        val remembered = assertNotNull(request.remembered)
        val grabbed = mutableListOf<String>()
        val grab = NzbSource { candidate -> grabbed += candidate.id; error("grabbed ${candidate.id}") }
        val backend = object : PrepareBackend {
            override suspend fun prepare(nzbBytes: ByteArray, password: String?): PrepareResult = error("prepared again")
            override suspend fun streamableFiles(metadata: ExtractedMetadata): List<StreamableFile> = error("listed again")
        }
        val checked = mutableListOf<String>()
        val availability = AvailabilityBackend { nzb, _ -> checked += nzb.files.flatMap { f -> f.segments.map { it.articleId } }; VerificationResult.Success }
        val asked = mutableListOf<List<String>>()
        val chooser = FileChooser { _, nzb, choices ->
            assertEquals(unnamed.id, nzb.id)
            asked += choices.map { it.name }
            choices.single { it.name == "Harbor.Lights.2-6.mp4" }
        }
        val usenet = UsenetSource(
            ReplaySource(remembered, unnamedPack.bytes, grab), availability, PlayingPack.preparer(unnamed, backend, Dispatchers.Unconfined), PreparedNzbStore(nzbDir),
            chooser = chooser,
        )
        val pipeline = PlayPipeline({ usenet }, dispatcher = Dispatchers.Unconfined)
        val end = pipeline.play(request.candidates.map { PlayCandidate(it.key, it.item.title, it.item.indexerId) }, playPicker(request, remembered)).last()
        val target = assertIs<PipelineEvent.Succeeded>(end, "$end").target
        assertEquals("Harbor.Lights.2-6.mp4", target.file.name)
        assertEquals(listOf(unnamed.candidates.map { it.name }), asked)
        assertEquals(emptyList(), grabbed)
        assertEquals(listOf("Harbor.Lights.2-6.mp4@fake"), checked)
    }

    @Test
    fun `an episode the pack plays isn't searched for, one it fails is, without the pack`() = runBlocking<Unit> {
        val play = PlayingPack.play(episode(6), playing, pack, assertNotNull(PlayingPack.fileFor(pack, playing, 2, 6)))
        val searched = mutableListOf<Retry?>()
        PlayingPack.run(play, start = { null }, search = { searched += it })
        assertEquals(emptyList(), searched)

        val failed = PlayState.Failed("Harbor Lights", "S02E06", listOf(pack.title to "Release incomplete"), notTried = 0)
        PlayingPack.run(play, start = { failed }, search = { searched += it })
        assertEquals(listOf<Retry?>(Retry(setOf("fake/hl-s02-pack"), startPositionMs = null, midStream = false, fromPlaying = true)), searched)

        searched.clear()
        PlayingPack.run(null, start = { error("nothing to play from") }, search = { searched += it })
        assertEquals(listOf<Retry?>(null), searched)
    }

    /**
     * A pack whose videos are named for their episodes, none the one wanted, plainly lacks it: the search for it leaves the
     * pack out, as nothing failed, saying nothing of it. Not a pack that may hold it (its videos named for no episode),
     * nor a release of one video, which a search for another episode finds only if it holds that one too.
     */
    @Test
    fun `an episode a named pack lacks is searched for without the pack`() = runBlocking<Unit> {
        assertTrue(PlayingPack.lacks(pack, playing, 2, 7))
        assertFalse(PlayingPack.lacks(pack, playing, 2, 6))
        assertFalse(PlayingPack.lacks(unnamed, playingUnnamed, 2, 2))
        val single = PreparedNzb("single", "Harbor.Lights.S02E05.1080p.WEB.h264-GRP", packMetadata, listOf(FileCandidate(0, StreamableFile("a8f3c1.mkv", 1_000_000_000L, 0, 0, 0, 0))))
        assertFalse(PlayingPack.lacks(single, playing.copy(nzbId = "single", fileIndex = 0, fileName = "a8f3c1.mkv"), 2, 6))

        val searched = mutableListOf<Retry?>()
        PlayingPack.run(null, start = { error("nothing to play from") }, search = { searched += it }, lacking = "fake/hl-s02-pack")
        val retry = assertNotNull(searched.single())
        assertEquals(Retry(setOf("fake/hl-s02-pack"), startPositionMs = null, midStream = false, fromPlaying = true, playingLacksIt = true), retry)
        assertNull(retry.notice)
        assertEquals("Checking your providers…", checkingProvidersLine(retry))
    }

    /** The card's search for an episode the pack playing lacks says what it found besides the pack. */
    @Test
    fun `the card's search for an episode a named pack lacks finds another release`() {
        fun found(vararg releases: Pair<String, String>) = FindResult(
            EpisodeRequest(ShowRef("Harbor Lights"), 2, 7),
            releases.map { (guid, title) ->
                val item = NewznabItem(
                    indexerId = "fake", guid = guid, title = title, sizeBytes = 1_000L + guid.length, publishedAt = null, postedAt = null,
                    categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
                    tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
                )
                FoundRelease(item, ReleaseRanker().rank(listOf(ReleaseFacts("fake/$guid", title, item.sizeBytes)), RankingContext(StaticDeviceCapabilities())).single(), SearchStage.Episode)
            },
            hidden = emptyList(), stages = emptyList(),
        )
        val r = found("hl-s02-pack" to pack.title, "hl-s02e07" to "Harbor.Lights.S02E07.1080p.WEB.h264-GRP")
        assertEquals(Prefetch.Ready(pack.title, 1_011L), prefetchOf(r))
        assertEquals(Prefetch.Ready("Harbor.Lights.S02E07.1080p.WEB.h264-GRP", 1_009L), prefetchOf(r, leftOut = setOf("fake/hl-s02-pack")))
        assertEquals(Prefetch.NothingFound, prefetchOf(found("hl-s02-pack" to pack.title), leftOut = setOf("fake/hl-s02-pack")))
        assertEquals(Prefetch.NothingFound, prefetchOf(null))
    }

    /** The sheet says why another release is looked for: the release playing couldn't play the episode. */
    @Test
    fun `the sheet says the release playing couldn't play the episode`() {
        val retry = Retry(setOf("fake/hl-s02-pack"), startPositionMs = null, midStream = false, fromPlaying = true)
        assertEquals("The release that was playing couldn't play it · trying another", retry.notice)
        assertEquals("The release that was playing couldn't play it · trying another · Checking your providers…", checkingProvidersLine(retry))
    }
}
