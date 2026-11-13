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
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.request
import io.skjaere.usenet.app.search.IndexerConfigs
import io.skjaere.usenet.app.search.IndexerStatus
import io.skjaere.usenet.app.search.SearchRequest
import io.skjaere.usenet.app.search.TitleInfo
import io.skjaere.usenet.app.search.playRequest
import io.skjaere.usenet.app.search.recentPick
import io.skjaere.usenet.app.searchRoute
import io.skjaere.usenet.app.settings.TestCipher
import io.skjaere.usenet.app.settings.memorySettings
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.AvailabilityBackend
import io.skjaere.usenet.core.DiscKind
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.NzbPreparer
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.PipelineEvent
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.PlayPipeline
import io.skjaere.usenet.core.PrepareBackend
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.core.UnreadableFile
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.UsenetSource
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.TitleSuggestion
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGrouper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayTest {
    /** Where a play keeps what plays: the coordinator does once it plays, which these plays don't reach. */
    @TempDir
    lateinit var nzbDir: File

    private val remembered = RememberedRelease(
        "fake", "hl-s02e05", "Harbor.Lights.S02E05.1080p.WEB.h264-GRP", "http://10.0.2.2:8089/api?t=get&id=hl-s02e05&apikey={apikey}", fileIndex = 2, nzbId = "abc",
    )
    private val storedNzb = "<nzb/>".encodeToByteArray()

    private fun item(indexer: String, guid: String, title: String = "Harbor.Lights.S02E05.720p.WEB.h264-OTH") = RankedItem(
        NewznabItem(
            indexerId = indexer, guid = guid, title = title, sizeBytes = 1_000_000_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("$indexer/$guid", title, 1_000_000_000), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
    )

    /** Counts the grabs it is asked for and answers each with the candidate's id. */
    private class CountingGrab : NzbSource {
        val grabbed = mutableListOf<String>()
        override suspend fun fetch(candidate: PlayCandidate): ByteArray = candidate.id.encodeToByteArray().also { grabbed += candidate.id }
    }

    private val searched = listOf(item("a", "1"), item("fake", "hl-s02e05", remembered.title), item("b", "2"))

    @Test
    fun `the remembered release's stored NZB is used without a grab, other releases are grabbed`() = runBlocking<Unit> {
        val grab = CountingGrab()
        val source = ReplaySource(remembered, storedNzb, grab)
        assertContentEquals(storedNzb, source.fetch(PlayCandidate("fake/hl-s02e05", remembered.title, "fake")))
        assertEquals(emptyList(), grab.grabbed)
        assertContentEquals("a/1".encodeToByteArray(), source.fetch(PlayCandidate("a/1", "Other", "a")))
        assertEquals(listOf("a/1"), grab.grabbed)
    }

    /** A password from its indexer is in the store, not in the NZB: the stored NZB comes with it again. */
    @Test
    fun `the remembered release's stored NZB comes with the password stored with it`() = runBlocking<Unit> {
        val grab = CountingGrab()
        val source = ReplaySource(remembered, storedNzb, grab, storedPassword = "salt-and-signal")
        val stored = source.fetchWithPassword(PlayCandidate("fake/hl-s02e05", remembered.title, "fake"))
        assertContentEquals(storedNzb, stored.bytes)
        assertEquals("salt-and-signal", stored.password)
        assertEquals(emptyList(), grab.grabbed)
        // Another comes with what its grab gives.
        assertEquals(null, source.fetchWithPassword(PlayCandidate("a/1", "Other", "a")).password)
        assertEquals(listOf("a/1"), grab.grabbed)
    }

    @Test
    fun `a remembered release no longer stored is grabbed from its indexer`() = runBlocking<Unit> {
        val grab = CountingGrab()
        ReplaySource(remembered, stored = null, grab).fetch(PlayCandidate("fake/hl-s02e05", remembered.title, "fake"))
        assertEquals(listOf("fake/hl-s02e05"), grab.grabbed)
        // A grab's failure is the pipeline's to report, as for any release.
        val failing = ReplaySource(remembered, stored = null, grab = { throw FetchException("Indexer no longer set up") })
        assertFailsWith<FetchException> { failing.fetch(PlayCandidate("fake/hl-s02e05", remembered.title, "fake")) }
    }

    /** The pipeline tries a release by its key, name and indexer, and knows whether its indexer says it needs a password. */
    @Test
    fun `a release is tried with its indexer's password flag`() {
        val flagged = item("fake", "hl-s02e05-pw").let { it.copy(item = it.item.copy(passworded = true)) }
        assertEquals(PlayCandidate("fake/hl-s02e05-pw", flagged.item.title, "fake", passworded = true), flagged.playCandidate)
        assertEquals(PlayCandidate("other/x", "Harbor.Lights.S02E05.720p.WEB.h264-OTH", "other"), item("other", "x").playCandidate)
    }

    @Test
    fun `the remembered release goes first among a search's releases when it found it`() {
        assertEquals(listOf("fake/hl-s02e05", "a/1", "b/2"), Replay.order(searched, remembered, stored = false).map { it.key })
        assertEquals(listOf("fake/hl-s02e05", "a/1", "b/2"), Replay.order(searched, remembered, stored = true).map { it.key })
    }

    @Test
    fun `a remembered release the search didn't find is tried first when its NZB is stored`() {
        val others = listOf(item("a", "1"), item("b", "2"))
        val ordered = Replay.order(others, remembered, stored = true)
        assertEquals(listOf("fake/hl-s02e05", "a/1", "b/2"), ordered.map { it.key })
        val added = ordered.first().item
        assertEquals(remembered.title, added.title)
        assertEquals(remembered.downloadTemplate, added.downloadTemplate)
        assertEquals(true, ordered.first().ranked.playable)
    }

    @Test
    fun `a remembered release neither found nor stored is left out`() {
        val others = listOf(item("a", "1"), item("b", "2"))
        assertEquals(others, Replay.order(others, remembered, stored = false))
        assertEquals(others, Replay.order(others, remembered = null, stored = false))
    }

    @Test
    fun `a release chosen by hand is tried alone, the remembered one not added ahead of it`() {
        val chosen = listOf(item("b", "2"))
        assertEquals(chosen, Replay.order(chosen, remembered, stored = true, first = false))
        assertEquals(searched, Replay.order(searched, remembered, stored = true, first = false))
    }

    /** Season 2 of Harbor Lights in one release: E01 to E10, each in a volume of its own, the last the largest. */
    private val packFiles = (1..10).map { e ->
        StreamableFile("Harbor.Lights.S02E%02d.1080p.WEB.h264-GRP.mkv".format(e), 1_000_000_000L + e * 1_000, e - 1, 0, 0, 0)
    }
    /** The pack's volumes, one article each, named for the episode in it. */
    private val packVolumes = packFiles.map { file ->
        NzbFile("p", 0, "\"${file.path}\" yEnc (1/1)", listOf("alt.binaries.fake"), listOf(NzbSegment(10, 1, "${file.path}@fake")), YencHeaders(128, file.totalSize, file.path))
    }
    private val packBytes = packVolumes.joinToString("\n", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<nzb xmlns=\"http://www.newzbin.com/DTD/2003/nzb\">\n", "\n</nzb>") { volume ->
        """<file poster="p" date="0" subject="${volume.subject.replace("\"", "&quot;")}"><groups><group>alt.binaries.fake</group></groups>""" +
            """<segments><segment bytes="10" number="1">${volume.segments.single().articleId}</segment></segments></file>"""
    }.encodeToByteArray()
    private val packId = PreparedNzb.idFor(packBytes)
    /** The pack, remembered with E05's file: the Show screen's Play found only the pack and played E05 from it. */
    private val rememberedPack = RememberedRelease("fake", "hl-s02-pack", "Harbor.Lights.S02.1080p.WEB.h264-GRP", null, fileIndex = 4, nzbId = packId)

    /** Opens any NZB as the pack: its ten episodes over its ten volumes. */
    private val packPreparer = NzbPreparer(object : PrepareBackend {
        override suspend fun prepare(nzbBytes: ByteArray, password: String?) = PrepareResult.Success(
            ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(packVolumes)),
        )
        override suspend fun streamableFiles(metadata: ExtractedMetadata) = packFiles
    })

    /** What the pack played: Harbor Lights S02E05, by TMDB. */
    private val packWatch = WatchTarget(WatchKind.Episode, "Harbor Lights S02E05", 900002, 2, 5)

    /**
     * The pack as prepare opens it when the episodes [without] lack their first article: left out, the
     * others listed in their order (library issue N15).
     */
    private fun packOpenedWithout(vararg without: Int): NzbPreparer {
        val kept = (1..10).filter { it !in without }
        return NzbPreparer(object : PrepareBackend {
            override suspend fun prepare(nzbBytes: ByteArray, password: String?) = PrepareResult.Success(
                ExtractedMetadata.Raw(
                    NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(kept.map { packVolumes[it - 1] }),
                ),
            )
            override suspend fun streamableFiles(metadata: ExtractedMetadata) = kept.mapIndexed { i, e -> packFiles[e - 1].copy(startVolumeIndex = i) }
        })
    }

    /**
     * Play on a Search episode group (single-episode releases, picked by size) with [remembered] (the
     * pack) stored, which goes first, opened by [preparer].
     */
    private suspend fun playEpisodeGroup(
        availability: AvailabilityBackend,
        grab: NzbSource,
        remembered: RememberedRelease = rememberedPack,
        preparer: NzbPreparer = packPreparer,
    ): PipelineEvent {
        val group = listOf(item("a", "1"), item("b", "2", "Harbor.Lights.S02E05.2160p.WEB.h265-OTH"))
        val candidates = Replay.order(group, remembered, stored = true)
        assertEquals("fake/hl-s02-pack", candidates.first().key)
        val usenet = UsenetSource(ReplaySource(remembered, packBytes, grab), availability, preparer, PreparedNzbStore(nzbDir))
        val pipeline = PlayPipeline({ usenet })
        return pipeline.play(candidates.map { PlayCandidate(it.key, it.item.title, it.item.indexerId) }, Replay.picker(FilePicker.Largest, remembered, packWatch)).last()
    }

    /** Resumes [state] from Home, as the coordinator runs it, with the pack's NZB stored and opened by [preparer]. */
    private suspend fun resume(state: WatchState, preparer: NzbPreparer): PipelineEvent {
        val request = assertNotNull(ResumePlan.of(state).request)
        val remembered = assertNotNull(request.remembered)
        val usenet = UsenetSource(ReplaySource(remembered, packBytes, CountingGrab()), AvailabilityBackend { _, _ -> VerificationResult.Success }, preparer, PreparedNzbStore(nzbDir))
        val pipeline = PlayPipeline({ usenet })
        return pipeline.play(request.candidates.map { PlayCandidate(it.key, it.item.title, it.item.indexerId) }, Replay.picker(request.picker, remembered, request.watch)).last()
    }

    /** The pack played without TMDB, from [release] (a Search season-pack group's Play, its first episode), in progress. */
    private fun packRelease(release: RememberedRelease) = WatchState(
        WatchKey.release(packId, release.fileIndex), WatchKind.Release, title = release.title,
        positionMs = 900_000, durationMs = 2_700_000, lastPlayedAt = 10, release = release, updatedAt = 10,
    )

    private fun attempts(end: PipelineEvent) = when (end) {
        is PipelineEvent.Succeeded -> end.failedAttempts
        is PipelineEvent.Exhausted -> end.failedAttempts
        else -> error("$end")
    }

    /** Why the pack's attempt failed in [end]; the test fails, naming the file, if the pack played. */
    private fun packFailure(end: PipelineEvent): AttemptFailure {
        if (end is PipelineEvent.Succeeded) assertNotEquals("fake/hl-s02-pack", end.target.candidate.id, "the pack played ${end.target.file.name}")
        return attempts(end).first { it.first.id == "fake/hl-s02-pack" }.second
    }

    private fun prepared(id: String, files: List<StreamableFile>) = PreparedNzb(
        id, "title", ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(emptyList())),
        files.mapIndexed(::FileCandidate),
    )

    /**
     * Play on a Search episode group (single-episode releases, picked by size) with the pack stored:
     * the pack is tried first, from the store, and must play E05 as it did before, not its largest file.
     */
    @Test
    fun `a stored remembered season pack added ahead of an episode group plays the remembered episode, not the pack's largest file`() = runBlocking<Unit> {
        val grab = CountingGrab()
        val end = playEpisodeGroup(AvailabilityBackend { _, _ -> VerificationResult.Success }, grab)
        val played = assertIs<PipelineEvent.Succeeded>(end, "$end").target
        assertEquals(emptyList(), grab.grabbed)
        assertEquals(packId, played.listing.id)
        assertEquals("Harbor.Lights.S02E05.1080p.WEB.h264-GRP.mkv", played.file.name)
        assertEquals(4, played.file.index)
    }

    /**
     * The same pack with an article of E03 gone: it played E05 before, checked on E05's volumes once
     * it was picked, and is checked so again, not whole as the group's single-episode releases are.
     * Checked whole, the gap would fail it, and as a release at fault it would be forgotten.
     */
    @Test
    fun `a stored remembered season pack with a gap in another episode still plays the remembered one`() = runBlocking<Unit> {
        val e03 = packVolumes[2].segments.single().articleId
        val e05 = packVolumes[4].segments.single().articleId
        val checked = mutableListOf<List<String>>()
        fun gone(article: String) = AvailabilityBackend { nzb, _ ->
            val articles = nzb.files.flatMap { file -> file.segments.map { it.articleId } }.also { checked += it }
            if (article in articles) VerificationResult.MissingArticles("430 No such article", IllegalStateException(article)) else VerificationResult.Success
        }
        val grab = CountingGrab()
        val end = playEpisodeGroup(gone(e03), grab)
        val succeeded = assertIs<PipelineEvent.Succeeded>(end, "$end")
        assertEquals(emptyList(), succeeded.failedAttempts)
        assertEquals(emptyList(), grab.grabbed)
        assertEquals(packId, succeeded.target.listing.id)
        assertEquals(4, succeeded.target.file.index)
        assertEquals(listOf(listOf(e05)), checked)

        // A gap in E05 itself, what would play, still fails it.
        checked.clear()
        val broken = playEpisodeGroup(gone(e05), CountingGrab())
        val failed = attempts(broken).first()
        assertEquals("fake/hl-s02-pack", failed.first.id)
        assertIs<AttemptFailure.MissingArticles>(failed.second)
        assertEquals(listOf(e05), checked.first())
    }

    private val e05File = packFiles[4]

    /**
     * The pack remembered with E05's file by its position alone, as releases were before names were
     * kept, in a listing that no longer holds it there: nzb-streamer listed the pack's NFO first before
     * N15, and since N15 prepare leaves out an episode that lacks its first article (E03 here) while
     * the others stand in. The file now there is E06, which must not play under E05's progress (#179):
     * the episode is found as the watch wants it, on Play from an episode group and on Resume.
     */
    @Test
    fun `a season pack remembered by position before N15 shifted its files plays the remembered episode, not the one now there`() = runBlocking<Unit> {
        listOf(
            // E05 was file 5 behind the NFO; prepare lists the ten episodes alone now.
            rememberedPack.copy(fileIndex = 5) to packPreparer,
            // E05 was file 4; E03 lacks its first article, so it is left out and E06 is file 4.
            rememberedPack to packOpenedWithout(3),
        ).forEach { (remembered, preparer) ->
            val played = assertIs<PipelineEvent.Succeeded>(playEpisodeGroup(AvailabilityBackend { _, _ -> VerificationResult.Success }, CountingGrab(), remembered, preparer))
            assertEquals(packId, played.target.listing.id)
            assertEquals(e05File.path, played.target.file.name, "${remembered.fileIndex}")
            val resumed = assertIs<PipelineEvent.Succeeded>(resume(episode.copy(release = remembered), preparer))
            assertEquals(e05File.path, resumed.target.file.name, "${remembered.fileIndex}")
        }
        // A release of one video can't have moved another episode there: that video plays, as it did,
        // named otherwise as it may be (by the indexer's numbering, not TMDB's).
        val single = prepared(packId, listOf(StreamableFile("Harbor.Lights.S01E17.1080p.WEB.h264-GRP.mkv", 1_000, 0, 0, 0, 0)))
        assertEquals(0, Replay.picker(FilePicker.Largest, rememberedPack.copy(fileIndex = 0), packWatch).pick(single)?.index)
    }

    /** Remembered with its name, the file is found by that wherever the release lists it now (#179). */
    @Test
    fun `a remembered file is found by its name wherever the release lists it now`() = runBlocking<Unit> {
        val named = rememberedPack.copy(fileName = e05File.path, fileSize = e05File.totalSize)
        val played = assertIs<PipelineEvent.Succeeded>(playEpisodeGroup(AvailabilityBackend { _, _ -> VerificationResult.Success }, CountingGrab(), named, packOpenedWithout(3)))
        assertEquals(e05File.path, played.target.file.name)
        assertEquals(3, played.target.file.index)
        // Played without TMDB, nothing but its name says which file of the pack played: the first
        // episode, file 1 behind the NFO before N15.
        val first = packFiles[0]
        val release = rememberedPack.copy(fileIndex = 1, fileName = first.path, fileSize = first.totalSize)
        assertEquals(first.path, assertIs<PipelineEvent.Succeeded>(resume(packRelease(release), packPreparer)).target.file.name)
    }

    /**
     * The file that played isn't in the release any more: prepare left E05 out (it lacks its first
     * article), or lists it under another name. No other episode plays from the pack in its place. A
     * play the release lacks the file for says it is missing, as for any release; a play finds the
     * episode under its new name, as TMDB, or played without TMDB the release's name or else the
     * file's, says it; a pack played without TMDB from a file whose name says none (obfuscated),
     * which nothing else tells the episode of, says the file is gone.
     */
    @Test
    fun `a remembered file no longer there plays no other episode in its place`() = runBlocking<Unit> {
        val named = rememberedPack.copy(fileName = e05File.path, fileSize = e05File.totalSize)
        listOf(
            playEpisodeGroup(AvailabilityBackend { _, _ -> VerificationResult.Success }, CountingGrab(), named, packOpenedWithout(5)),
            resume(episode.copy(release = named), packOpenedWithout(5)),
            resume(packRelease(named), packOpenedWithout(5)),
        ).forEach { end ->
            val missing = assertIs<AttemptFailure.MissingArticles>(packFailure(end), "$end")
            assertTrue(e05File.path in missing.detail, missing.detail)
        }
        // Listed under another name now, at the position E06 has: by TMDB's episode, by the episode
        // the file's name gives for the pack played without TMDB, and by the release's name for a
        // release named for one episode.
        val renamed = named.copy(fileIndex = 5, fileName = "harbor.lights.s02e05.mkv")
        listOf(episode.copy(release = renamed), packRelease(renamed), packRelease(renamed.copy(title = "Harbor.Lights.S02E05.1080p.WEB.h264-GRP"))).forEach { state ->
            assertEquals(e05File.path, assertIs<PipelineEvent.Succeeded>(resume(state, packPreparer), "${state.kind} ${state.title}").target.file.name)
        }
        val obfuscated = named.copy(fileIndex = 5, fileName = "a91f03c7e2.mkv")
        assertEquals(AttemptFailure.NoRememberedFile("a91f03c7e2.mkv"), packFailure(resume(packRelease(obfuscated), packPreparer)))
    }

    /**
     * A release played without TMDB whose name gives no episode ("Harbor.Lights.Complete.Series", an
     * anime batch, a daily pack), remembered with its largest file, E10. Where that isn't there any
     * more, the largest file now is another episode, which must not play under E10's progress (#179):
     * the file's name says the episode, and one that says none plays only from a release of one video.
     */
    @Test
    fun `a release named for no episode plays no other file in place of its remembered one`() = runBlocking<Unit> {
        val e10 = packFiles[9]
        val complete = rememberedPack.copy(title = "Harbor.Lights.Complete.Series.1080p.WEB.h264-GRP", fileIndex = 9, fileName = e10.path, fileSize = e10.totalSize)
        // Left out (it lacks its first article): the release lacks it, as any release would.
        val missing = assertIs<AttemptFailure.MissingArticles>(packFailure(resume(packRelease(complete), packOpenedWithout(10))))
        assertTrue(e10.path in missing.detail, missing.detail)
        // Listed under another name that says the episode, at another position: that plays.
        val renamed = complete.copy(fileIndex = 0, fileName = "harbor.lights.s02e10.mkv")
        assertEquals(e10.path, assertIs<PipelineEvent.Succeeded>(resume(packRelease(renamed), packOpenedWithout(3))).target.file.name)
        // Named for no episode (obfuscated), gone from a release of nine videos: nothing tells which is it.
        val obfuscated = complete.copy(fileName = "a91f03c7e2.mkv")
        assertEquals(AttemptFailure.NoRememberedFile("a91f03c7e2.mkv"), packFailure(resume(packRelease(obfuscated), packOpenedWithout(3))))
        // A release of one video (a film played without TMDB) plays that one, named otherwise as it may be now.
        val film = assertNotNull(releaseOnly.release).copy(fileName = "Northbound.2019.1080p.BluRay.x264-GRP.mkv", fileSize = 1_000)
        val one = prepared("abc", listOf(StreamableFile("Northbound.2019.nfo", 10, 0, 0, 0, 0), StreamableFile("northbound.mkv", 2_000, 0, 0, 0, 0)))
        assertEquals(1, Replay.picker(FilePicker.Largest, film, WatchTarget(WatchKind.Release, film.title)).pick(one)?.index)
    }

    /**
     * A season pack played without TMDB, remembered by its position alone (before names were kept):
     * nothing else tells which episode played. Where that position is now past the end of the
     * listing, or holds no video, the largest file would be another episode, so nothing plays, and
     * the release is forgotten for Resume's search (#179). E09 was file 9 behind the NFO before N15;
     * E03 lacks its first article now, so the listing ends at file 8, and E10 is the largest.
     */
    @Test
    fun `a season pack played without TMDB from a position no longer there plays no other episode`() = runBlocking<Unit> {
        val e09 = rememberedPack.copy(fileIndex = 9)
        val end = resume(packRelease(e09), packOpenedWithout(3))
        val failure = packFailure(end)
        assertEquals(AttemptFailure.NoRememberedFile(null), failure)
        assertEquals("Can't tell which file played before", failure.text)
        assertTrue(Replay.broke(e09, attempts(end)))
        // Its position holding the NFO now: the same.
        val nfo = prepared(packId, packFiles.take(4) + StreamableFile("Harbor.Lights.S02.nfo", 10, 0, 0, 0, 0) + packFiles.drop(5))
        val picker = Replay.picker(FilePicker.Largest, rememberedPack, WatchTarget(WatchKind.Release, rememberedPack.title))
        assertNull(picker.pick(nfo))
        assertEquals(AttemptFailure.NoRememberedFile(null), picker.noMatchIn(nfo))
    }

    @Test
    fun `the remembered file is picked only in the remembered release's NZB, any other is picked as the request says`() {
        val picker = Replay.picker(FilePicker.Largest, rememberedPack, packWatch)
        assertEquals(4, picker.pick(prepared(packId, packFiles))?.index)
        // Another release's NZB, a pack too: the request's own picker, the largest file.
        assertEquals(9, picker.pick(prepared("other", packFiles))?.index)
        // Nothing remembered: the request's picker as it is.
        assertEquals(FilePicker.Largest, Replay.picker(FilePicker.Largest, remembered = null, packWatch))
        // A remembered position that isn't a video any more: the episode watched, here not there, not
        // the request's pick, the largest file (#179). It says the episode isn't there, not, as
        // another release's pick does, that the release has no video.
        val withoutE05 = prepared(packId, packFiles.take(4) + StreamableFile("Harbor.Lights.S02.nfo", 10, 0, 0, 0, 0) + packFiles.drop(5))
        assertNull(picker.pick(withoutE05))
        assertEquals(AttemptFailure.NoMatchingEpisode(2, 5), picker.noMatchIn(withoutE05))
        assertEquals(AttemptFailure.NoVideo, picker.noMatchIn(prepared("other", emptyList())))
        // What it looks for when the file isn't there is the request's picker's too (library issue N15).
        val episode = Replay.picker(FilePicker.Episode(2, 5), rememberedPack, packWatch)
        assertEquals(true, episode.wants("Harbor.Lights.S02E05.1080p.WEB.h264-GRP.mkv"))
        assertEquals(false, episode.wants("Harbor.Lights.S02E04.1080p.WEB.h264-GRP.mkv"))
        // The remembered release is checked on the file that plays, any other as the request's picker says.
        assertEquals(false, picker.takesWhole(rememberedPack.title))
        assertEquals(true, picker.takesWhole("Harbor.Lights.S02E05.720p.WEB.h264-OTH"))
    }

    @Test
    fun `only a TMDB episode or film looks up the release that played it, a release play is its own key`() {
        assertEquals(WatchKey.episode(900002, 2, 5), WatchTarget(WatchKind.Episode, "Harbor Lights S02E05", 900002, 2, 5).rememberedKey)
        assertEquals(WatchKey.movie(900010), WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = 900010).rememberedKey)
        assertNull(WatchTarget(WatchKind.Release, "Harbor.Lights.S02E05.1080p.WEB.h264-GRP").rememberedKey)
    }

    private val episode = WatchState(
        WatchKey.episode(900002, 2, 5), WatchKind.Episode, showTmdbId = 900002, season = 2, episode = 5, title = "Harbor Lights S02E05",
        positionMs = 900_000, durationMs = 2_700_000, lastPlayedAt = 10, release = remembered, updatedAt = 10,
    )
    private val film = WatchState(
        WatchKey.movie(900010), WatchKind.Movie, movieTmdbId = 900010, title = "Copper Crown",
        positionMs = 900_000, durationMs = 6_000_000, lastPlayedAt = 10, release = remembered.copy(title = "Copper.Crown.2019.1080p.WEB-DL.H.264-GRP"), updatedAt = 10,
    )
    private val releaseOnly = WatchState(
        WatchKey.release("abc", 0), WatchKind.Release, title = "Northbound.2019.1080p.BluRay.x264-GRP",
        positionMs = 900_000, durationMs = 6_000_000, lastPlayedAt = 10, release = remembered.copy(title = "Northbound.2019.1080p.BluRay.x264-GRP", fileIndex = 0), updatedAt = 10,
    )

    @Test
    fun `resuming an episode plays the release that played it, its file, against the TMDB episode`() {
        val plan = ResumePlan.of(episode)
        val request = assertNotNull(plan.request)
        assertEquals("Harbor Lights", request.title)
        assertEquals("S02E05", request.subtitle)
        assertEquals(listOf("fake/hl-s02e05"), request.candidates.map { it.key })
        assertEquals(remembered.downloadTemplate, request.candidates.single().item.downloadTemplate)
        assertEquals(RememberedFile(2, FilePicker.Episode(2, 5)), request.picker)
        assertEquals(WatchTarget(WatchKind.Episode, "Harbor Lights S02E05", showTmdbId = 900002, season = 2, episode = 5), request.watch)
        assertEquals(remembered, request.remembered)
        assertEquals(EpisodeTarget(900002, "Harbor Lights", 2, 5), plan.episode)
    }

    @Test
    fun `an episode whose release fails falls back to its search, without that release`() = runBlocking<Unit> {
        val searches = mutableListOf<Pair<EpisodeTarget, Set<String>?>>()
        val shown = ResumePlan.of(episode).run(
            play = { PlayState.Failed("Harbor Lights", "S02E05", listOf(remembered.title to "Incomplete on your providers"), notTried = 0) },
            searchEpisode = { target, retry -> searches += target to retry?.failed },
        )
        assertNull(shown)
        assertEquals(listOf<Pair<EpisodeTarget, Set<String>?>>(EpisodeTarget(900002, "Harbor Lights", 2, 5) to setOf("fake/hl-s02e05")), searches)
    }

    /**
     * Resume's fallback to the episode search after the remembered release failed the pipeline's
     * checks (a grab refused, articles gone, the probe failing): nothing played, so the sheet
     * doesn't say the release before stopped playing, as it does for Try another release.
     */
    @Test
    fun `an episode searched for after its release failed on Resume isn't said to have stopped playing`() = runBlocking<Unit> {
        var retry: Retry? = null
        ResumePlan.of(episode).run(
            play = { PlayState.Failed("Harbor Lights", "S02E05", listOf(remembered.title to "Incomplete on your providers"), notTried = 0) },
            searchEpisode = { _, r -> retry = r },
        )
        assertEquals(setOf("fake/hl-s02e05"), assertNotNull(retry).failed)
        assertNull(retry?.notice)
        // Try another release, after the release stopped as it played, says so.
        val request = assertNotNull(ResumePlan.of(episode).request)
        val launch = usenetLaunch(request, SavedRelease("fake", "hl-s02e05", remembered.title, null, 2, "abc"), "play-1", retry = null)
        val again = assertNotNull(PlaySessions().retry(launch, 900_000) as? RetryPlan.SearchEpisode)
        assertEquals("The release before stopped playing · trying another", again.retry.notice)
    }

    /**
     * The remembered release skipped without a grab as found incomplete before (its gap found while a provider was left
     * out, which kept it remembered) wasn't tried: the episode search after it says so, not that it failed. One that was
     * tried and failed, or failed besides others skipped, did fail.
     */
    @Test
    fun `an episode searched for after its release was skipped as found incomplete before isn't said to have failed`() = runBlocking<Unit> {
        val skipped = listOf(remembered.title to "Skipped: missing on your providers (checked 3 days ago)")
        val outcomes = listOf(
            PlayState.Failed("Harbor Lights", "S02E05", emptyList(), notTried = 0, missing = skipped) to "The release that played it was found incomplete before",
            PlayState.Failed("Harbor Lights", "S02E05", listOf(remembered.title to "Incomplete on your providers"), notTried = 0) to "The release that played it failed",
            PlayState.Failed("Harbor Lights", "S02E05", listOf("Other" to "Passworded"), notTried = 0, missing = skipped) to "The release that played it failed",
        )
        outcomes.forEach { (failed, line) ->
            var retry: Retry? = null
            ResumePlan.of(episode).run(play = { failed }, searchEpisode = { _, r -> retry = r })
            assertEquals(line, assertNotNull(retry).searchingNotice, "$failed")
            assertEquals(setOf("fake/hl-s02e05"), retry?.failed)
        }
        // Try another release, after the release stopped as it played: it failed.
        val request = assertNotNull(ResumePlan.of(episode).request)
        val launch = usenetLaunch(request, SavedRelease("fake", "hl-s02e05", remembered.title, null, 2, "abc"), "play-1", retry = null)
        val again = assertNotNull(PlaySessions().retry(launch, 900_000) as? RetryPlan.SearchEpisode)
        assertEquals("The release that played it failed", again.retry.searchingNotice)
    }

    /** A torrent played before (L181): remembered by its content id, with where it played from, and never a magnet or a tracker's link. */
    private val torrent = RememberedRelease(
        "prowlarr", "btih:$TORRENT_HASH", "Harbor.Lights.S02.1080p.WEB-DL.H.264-TIDE", null, fileIndex = 1, nzbId = "btih:$TORRENT_HASH",
        fileName = "Harbor.Lights.S02E05.1080p.WEB-DL.H.264-TIDE.mkv", fileSize = 950_000_000, source = ReleaseFacts.TORRENT,
    )

    /**
     * Resume's torrent (L181): a release of its hash and source, first by its key as any remembered release, whether the
     * search found it (with the accounts its check found) or not (added while the service's listing of it is kept); it
     * plays from the service accounts on, A to Z, as its own check would have found it there.
     */
    @Test
    fun `a remembered torrent goes first by its key, and plays from the accounts on`() {
        val candidate = Replay.candidate(torrent)
        assertEquals("btih:$TORRENT_HASH", candidate.key)
        assertEquals(TORRENT_HASH, candidate.item.infoHash)
        assertNull(candidate.item.downloadTemplate)
        val searched = RankedItem(
            item("prowlarr", "btih:$TORRENT_HASH", torrent.title).item.copy(infoHash = TORRENT_HASH, availability = Availability.Cached(listOf("tb"))),
            RankedRelease(ReleaseFacts("btih:$TORRENT_HASH", torrent.title, 1_000_000_000, source = ReleaseFacts.TORRENT), ReleaseParser.parse(torrent.title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
        )
        val other = item("fake", "hl-s02e05")
        assertEquals(listOf(searched.key, other.key), Replay.order(listOf(other, searched), torrent, stored = false).map { it.key })
        assertEquals(listOf("btih:$TORRENT_HASH", other.key), Replay.order(listOf(other), torrent, stored = true).map { it.key })
        assertEquals(listOf(other.key), Replay.order(listOf(other), torrent, stored = false).map { it.key })

        val onAccounts = Replay.onAccounts(candidate, listOf("pm", "tb")).playCandidate
        assertEquals(PlayCandidate("btih:$TORRENT_HASH", torrent.title, "prowlarr", source = ReleaseFacts.TORRENT, routes = listOf("service:pm", "service:tb")), onAccounts)
        // A search's torrent keeps the accounts its check found; an NZB has none to take.
        assertEquals(searched, Replay.onAccounts(searched, listOf("pm", "tb")))
        assertEquals(other, Replay.onAccounts(other, listOf("pm", "tb")))
        // Its file is the one that played, in any listing of the torrent.
        assertEquals("btih:$TORRENT_HASH", assertIs<RememberedFile>(Replay.picker(FilePicker.Episode(2, 5), torrent, WatchTarget(WatchKind.Episode, "Harbor Lights S02E05", showTmdbId = 900002, season = 2, episode = 5))).nzbId)
    }

    /** A Resume of a torrent the service no longer has (L181): the episode is searched for again, without it. */
    @Test
    fun `a Resume of a torrent the service no longer has searches for the episode again, without it`() = runBlocking<Unit> {
        val plan = ResumePlan.of(episode.copy(release = torrent))
        assertEquals(listOf("btih:$TORRENT_HASH"), plan.request?.candidates?.map { it.key })
        var retry: Retry? = null
        plan.run(play = { PlayState.Failed("Harbor Lights", "S02E05", listOf(torrent.title to "No longer cached"), notTried = 0) }, searchEpisode = { _, r -> retry = r })
        assertEquals(setOf("btih:$TORRENT_HASH"), assertNotNull(retry).failed)
    }

    /** DEP-8: a Resume whose remembered torrent was skipped as its service failed it before searches, saying so, not that it was found incomplete. */
    @Test
    fun `a Resume of a torrent skipped as its service failed it before says so as it searches`() = runBlocking<Unit> {
        var retry: Retry? = null
        val skipped = PlayState.Failed("Harbor Lights", "S02E05", emptyList(), notTried = 0, missing = listOf(torrent.title to "Too large for your debrid plan"))
        ResumePlan.of(episode.copy(release = torrent)).run(play = { skipped }, searchEpisode = { _, r -> retry = r })
        assertEquals("The release that played it was skipped: too large for your debrid plan", assertNotNull(retry).searchingNotice)
        assertFalse(retry!!.foundIncomplete)
    }

    /**
     * DEP-3: Try a smaller release from a torrent that played: if none of the smaller ones plays, the play goes back to the
     * torrent, from the service accounts on, as a remembered one plays; an NZB from Usenet, as before.
     */
    @Test
    fun `the way back to a torrent left for a smaller release plays from the accounts on`() {
        val playing = SavedRelease("prowlarr", "btih:$TORRENT_HASH", torrent.title, null, 1, "btih:$TORRENT_HASH", torrent.fileName, torrent.fileSize, ReleaseFacts.TORRENT)
        val back = assertNotNull(Replay.wayBack(Left(playing, "release:btih:$TORRENT_HASH:1", emptyList(), Left.Why.Smaller), listOf("tb")))
        assertEquals("btih:$TORRENT_HASH", back.key)
        assertEquals(listOf("service:tb"), back.playCandidate.routes)
        assertTrue(UsableRoutes.of(providerUsable = true, accountsOn = listOf("tb")).keeps(back.playCandidate))
        val nzb = SavedRelease("fake", "hl-s02e05", remembered.title, null, 2, "abc")
        assertEquals(listOf("usenet"), Replay.wayBack(Left(nzb, "w", emptyList(), Left.Why.Smaller), listOf("tb"))?.playCandidate?.routes)
        assertNull(Replay.wayBack(null, listOf("tb")))
    }

    /**
     * DDC-2: a remembered torrent the play's search just returned hidden (not cached, not checked, not for the service) has
     * the search's word on it: it isn't added back ahead of the others while its listing is kept, as one the search didn't
     * return is. A Usenet release keeps today's rule, its stored NZB tried.
     */
    @Test
    fun `a remembered torrent the search just hid isn't added back`() {
        val other = item("fake", "hl-s02e06")
        assertEquals(listOf(other.key), Replay.order(listOf(other), torrent, stored = true, hidden = setOf("btih:$TORRENT_HASH")).map { it.key })
        assertEquals(listOf("btih:$TORRENT_HASH", other.key), Replay.order(listOf(other), torrent, stored = true, hidden = setOf(other.key + "-x")).map { it.key })
        assertEquals(listOf(Replay.key(remembered), other.key), Replay.order(listOf(other), remembered, stored = true, hidden = setOf(Replay.key(remembered))).map { it.key })
    }

    /**
     * DDC-2, DDR-3, DEP-2: what a play leaves out, whatever list or memory it came from, whatever device it is for (L202
     * lifted): a torrent from a torrent indexer whose "Use with your debrid service" is off (L194), whose hash goes to no
     * service. An NZB never.
     */
    @Test
    fun `a play leaves out a torrent from an indexer kept from the service, whatever device it is for`() {
        val onAccounts = Replay.onAccounts(Replay.candidate(torrent), listOf("tb"))
        assertNull(Replay.leftOut(onAccounts, offService = emptySet()))
        assertEquals(ReleaseReason.NotForService, Replay.leftOut(onAccounts, offService = setOf("prowlarr")))
        assertNull(Replay.leftOut(onAccounts, offService = setOf("quarry")))
        assertNull(Replay.leftOut(Replay.candidate(remembered), offService = setOf("fake")))
    }

    /**
     * DDC-2: the torrent indexers kept from the service are read from every one saved, not from what a search asks: the
     * switch is the user's "no" (L194) whether the indexer is switched on, its key can be read, or it was removed since
     * (its last switch kept). A remembered torrent of any of them is left out as not for the service, its hash sent nowhere.
     */
    @Test
    fun `an indexer kept from the service is so whether a search asks it or not`() = runBlocking<Unit> {
        val cipher = TestCipher()
        val settings = memorySettings(cipher = cipher)
        val used = settings.saveTorrentIndexer(null, "Home Prowlarr", "http://192.168.1.20:9696/1", "KP")
        val kept = settings.saveTorrentIndexer(null, "Quarry", "http://192.168.1.20:9696/2", "KQ", useWithService = false)
        val off = settings.saveTorrentIndexer(null, "Attic", "http://192.168.1.20:9696/3", "KA", enabled = false, useWithService = false)
        val unreadable = settings.saveTorrentIndexer(null, "Cellar", "http://192.168.1.20:9696/4", "KC", useWithService = false)
        cipher.unreadable += "KC"
        val removed = settings.saveTorrentIndexer(null, "Loft", "http://192.168.1.20:9696/5", "KL", useWithService = false)
        settings.deleteTorrentIndexer(removed)
        // A search asks the first two alone.
        assertEquals(listOf(used, kept), settings.searchableTorrentIndexers().map { it.record.id })

        val offService = IndexerConfigs.offService(settings.settings.first(), shown = true)
        assertEquals(setOf(kept, off, unreadable, removed), offService)
        for (id in listOf(kept, off, unreadable, removed)) {
            val item = Replay.onAccounts(Replay.candidate(torrent.copy(indexerId = id)), listOf("tb"))
            assertEquals(ReleaseReason.NotForService, Replay.leftOut(item, offService), id)
        }
        assertNull(Replay.leftOut(Replay.onAccounts(Replay.candidate(torrent.copy(indexerId = used)), listOf("tb")), offService))
    }

    /**
     * DDC-2: what a play tries, as the coordinator's play asks it: the search's releases with the remembered one first, a
     * torrent on the accounts on, and what a play leaves out apart with its reason. A remembered torrent of an indexer kept
     * from the service is never tried; one the search just hid isn't there at all.
     */
    @Test
    fun `a play tries no remembered torrent a search would leave out`() {
        val other = item("fake", "hl-s02e06")
        fun tries(remembered: RememberedRelease, hidden: Set<String> = emptySet(), offService: Set<String> = emptySet()) =
            Replay.toTry(listOf(other), remembered, stored = true, first = true, hidden, listOf("tb"), offService)
                .let { (tried, left) -> tried.map { it.key } to left.map { it.key to Replay.leftOut(it, offService) } }

        val (tried, left) = tries(torrent)
        assertEquals(listOf("btih:$TORRENT_HASH", other.key), tried)
        assertEquals(emptyList(), left)
        assertEquals(listOf("service:tb"), Replay.toTry(listOf(other), torrent, true, true, emptySet(), listOf("tb"), emptySet()).first.first().playCandidate.routes)
        assertEquals(listOf(other.key) to listOf("btih:$TORRENT_HASH" to ReleaseReason.NotForService), tries(torrent, offService = setOf("prowlarr")))
        assertEquals(listOf(other.key) to emptyList<Pair<String, ReleaseReason?>>(), tries(torrent, hidden = setOf("btih:$TORRENT_HASH")))
        // An NZB is tried whatever the play is for.
        assertEquals(listOf(Replay.key(remembered), other.key) to emptyList<Pair<String, ReleaseReason?>>(), tries(remembered, offService = setOf("fake")))
    }

    /**
     * L202 lifted (W73, C2): a play for a Cast device asks what to try as any play does (the play's device isn't asked), so
     * it tries a remembered torrent first, from the accounts on.
     */
    @Test
    fun `a cast play tries the remembered torrent first`() {
        val other = item("fake", "hl-s02e06")
        val (tried, left) = Replay.toTry(listOf(other), torrent, stored = true, first = true, emptySet(), listOf("tb"), emptySet())
        assertEquals(listOf("btih:$TORRENT_HASH", other.key), tried.map { it.key })
        assertEquals(listOf("service:tb"), tried.first().playCandidate.routes)
        assertEquals(emptyList(), left)
    }

    /**
     * L202 lifted (W73, C2; device case 4(g)): Resume of an episode a torrent played, to a Cast device, plays the remembered
     * torrent with no search, as a phone's Resume does; the film's too. A torrent from an indexer kept from the service since
     * still has its episode searched for (DDR-3), with nothing said of a failure.
     */
    @Test
    fun `a cast Resume of a torrent episode plays the remembered torrent (the film's too)`() = runBlocking<Unit> {
        val played = mutableListOf<PlayRequest>()
        val searched = mutableListOf<Pair<EpisodeTarget, Retry?>>()
        val forCast = { item: RankedItem -> Replay.leftOut(item, offService = emptySet()) != null }
        ResumePlan.of(episode.copy(release = torrent)).run(play = { played += it; null }, searchEpisode = { target, r -> searched += target to r }, passOver = forCast)
        assertEquals(listOf("Harbor Lights"), played.map { it.title })
        assertEquals(listOf("btih:$TORRENT_HASH"), played.single().candidates.map { it.key })
        assertEquals(emptyList(), searched)

        ResumePlan.of(film.copy(release = torrent)).run(play = { played += it; null }, searchEpisode = { target, r -> searched += target to r }, passOver = forCast)
        assertEquals(listOf("Harbor Lights", "Copper Crown"), played.map { it.title })
        assertEquals(emptyList(), searched)

        // Kept from the service since: the episode is searched for, the torrent untried.
        val keptFromService = { item: RankedItem -> Replay.leftOut(item, offService = setOf("prowlarr")) != null }
        ResumePlan.of(episode.copy(release = torrent)).run(play = { played += it; null }, searchEpisode = { target, r -> searched += target to r }, passOver = keptFromService)
        assertEquals(listOf("Harbor Lights", "Copper Crown"), played.map { it.title })
        assertEquals(listOf<Pair<EpisodeTarget, Retry?>>(EpisodeTarget(900002, "Harbor Lights", 2, 5) to null), searched)
    }

    @Test
    fun `an episode whose release plays needs no search`() = runBlocking<Unit> {
        val searches = mutableListOf<EpisodeTarget>()
        val shown = ResumePlan.of(episode).run(play = { null }, searchEpisode = { target, _ -> searches += target })
        assertNull(shown)
        assertEquals(emptyList(), searches)
    }

    @Test
    fun `an episode with no release remembered goes straight to its search`() = runBlocking<Unit> {
        val plan = ResumePlan.of(episode.copy(release = null))
        assertNull(plan.request)
        val searches = mutableListOf<Pair<EpisodeTarget, Retry?>>()
        plan.run(play = { error("nothing to play") }, searchEpisode = { target, retry -> searches += target to retry })
        assertEquals(listOf<Pair<EpisodeTarget, Retry?>>(EpisodeTarget(900002, "Harbor Lights", 2, 5) to null), searches)
    }

    @Test
    fun `a film whose release fails shows the failure with a search for the film`() = runBlocking<Unit> {
        val plan = ResumePlan.of(film)
        val request = assertNotNull(plan.request)
        assertEquals("Copper Crown", request.title)
        assertEquals(RememberedFile(2, FilePicker.Largest), request.picker)
        assertEquals(WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = 900010), request.watch)
        val failed = PlayState.Failed("Copper Crown", null, listOf("Copper.Crown.2019.1080p.WEB-DL.H.264-GRP" to "Incomplete on your providers"), notTried = 0)
        val shown = plan.run(play = { failed }, searchEpisode = { _, _ -> error("a film has no episode search") })
        assertEquals(failed.copy(search = "Copper Crown", searchPick = SearchPick(TitleType.Movie, 900010, "Copper Crown")), shown)
    }

    /**
     * The sheet's Search after a film's release failed searches by the film's TMDB id, as a pick from
     * the suggestions does: what plays from it is the film, so it starts at the film's saved position
     * and saves progress to it, and the recent search keeps the pick.
     */
    @Test
    fun `the search a failed film offers is by its TMDB id, so what plays from it is the film`() = runBlocking<Unit> {
        val failed = PlayState.Failed("Copper Crown", null, listOf("Copper.Crown.2019.1080p.WEB-DL.H.264-GRP" to "Incomplete on your providers"), notTried = 0)
        val shown = assertNotNull(ResumePlan.of(film).run(play = { failed }, searchEpisode = { _, _ -> error("a film has no episode search") }))
        val request = assertNotNull(assertNotNull(shown.searchRoute()).request())
        assertEquals(SearchRequest("Copper Crown", TitleSuggestion(MediaType.Movie, 900010, "Copper Crown", null, null, null, null)), request)
        assertEquals(SearchPick(TitleType.Movie, 900010, "Copper Crown"), request.recentPick())

        val info = TitleInfo(MediaType.Movie, 900010, "Copper Crown", 2019, "Movie · 2019", null, null, null, TvIds(tmdb = 900010), 100)
        val found = item("a", "1", "Copper.Crown.2019.720p.WEB-DL.H.264-OTH")
        val results = request.results(info, listOf(IndexerStatus("a", "Indexer A", IndexerStatus.State.Answered(1))),
            ResultGrouper().group(listOf(found.item), RankingContext(StaticDeviceCapabilities(videoTenBit = emptySet())), ResultGrouper.KnownTitle("Copper Crown", 2019, isTv = false)), done = true)
        val group = results.grouped.sections.single().groups.single()
        val watch = playRequest(results, group, group.playable).watch
        assertEquals(WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = 900010), watch)
        assertEquals(WatchKey.movie(900010), watch.key("nzb", 0))
        // A release played without TMDB offers a search by its name.
        val release = assertNotNull(ResumePlan.of(releaseOnly).run(play = { failed }, searchEpisode = { _, _ -> error("no episode") }))
        assertEquals(SearchRoute("Northbound 2019"), release.searchRoute())
    }

    @Test
    fun `an episode TMDB couldn't look up offers a search by the show's TMDB id`() {
        val failed = lookUpFailed(EpisodeTarget(900002, "Harbor Lights", 2, 5), "TMDB doesn't list S02E05")
        assertEquals(PlayState.Failed("Harbor Lights", "S02E05", listOf("S02E05" to "TMDB doesn't list S02E05"), notTried = 0, search = "Harbor Lights S02E05",
            searchPick = SearchPick(TitleType.Tv, 900002, "Harbor Lights", season = 2, episode = 5), remote = listOf("TMDB doesn't list S02E05")), failed)
        assertEquals(SearchRequest("Harbor Lights S02E05", TitleSuggestion(MediaType.Tv, 900002, "Harbor Lights", null, null, null, null)), failed.searchRoute()?.request())
    }

    /** The remembered release failing before it played, through its own fault: it is forgotten, so Resume and replay don't choose it again. */
    @Test
    fun `a remembered release that broke is told from one that failed through no fault of its own`() {
        val it = PlayCandidate(Replay.key(remembered), remembered.title, "fake")
        val other = PlayCandidate("a/1", "Other", "a")
        listOf(
            AttemptFailure.MissingArticles("430"), AttemptFailure.Passworded, AttemptFailure.NoPlayableAudio(listOf("TrueHD")),
            // It never really played here, its sound over a black screen: Resume shouldn't choose it again.
            AttemptFailure.NoPlayableVideo(listOf("2160p Dolby Vision profile 7")), AttemptFailure.Unsupported("RAR"), AttemptFailure.NoVideo, AttemptFailure.NoMatchingEpisode(2, 5), AttemptFailure.Failed(UsenetError.Corrupt("CRC")),
            // A file the player can't read is the release's (L319).
            AttemptFailure.Unreadable(UnreadableFile.UnknownFormat, "MKV"), AttemptFailure.Unreadable(UnreadableFile.Broken, null),
            // A copy of a disc is the release's shape, whatever plays it (L313).
            AttemptFailure.DiscCopy(DiscKind.BluRay), AttemptFailure.DiscCopy(DiscKind.Dvd), AttemptFailure.DiscCopy(DiscKind.Image),
        ).forEach { failure ->
            assertTrue(Replay.broke(remembered, listOf(other to AttemptFailure.Passworded, it to failure)), "$failure")
            // Another release's failure says nothing of it.
            assertFalse(Replay.broke(remembered, listOf(other to failure)), "$failure")
        }
        listOf(
            AttemptFailure.FetchFailed("Download limit reached", blocksIndexer = true), AttemptFailure.Provider(UsenetError.AuthFailed("481")),
            AttemptFailure.Failed(UsenetError.Timeout(null)), AttemptFailure.Failed(UsenetError.Network("reset")),
            // Missing on the providers in use while one is left out (its login rejected): it may be whole on that one (#172).
            AttemptFailure.MissingArticles("430", providersLeftOut = true),
            // Missing on the providers that answered while another failed: it may be whole on that one (#172).
            AttemptFailure.MissingArticles("No provider could answer", unanswered = UsenetError.AuthFailed("481")),
            // A TV's failure, mirrored on a phone (L37): never this device's release, and never reaching Replay there.
            AttemptFailure.Elsewhere("Incomplete on the TV's providers"),
            // A reader that crashed may be the app's own fault (L319).
            AttemptFailure.Unreadable(UnreadableFile.ReaderCrashed, "MKV"),
        ).forEach { failure -> assertFalse(Replay.broke(remembered, listOf(it to failure)), "$failure") }
        assertFalse(Replay.broke(remembered, emptyList()))
    }

    /**
     * A debrid service removing the release is the release's own fault, and Resume no longer chooses it (L204); the rest
     * of a service's failures are the account's or the moment's: not cached any more there, its limit, its key, an outage,
     * a release it won't take, a link it couldn't give again.
     */
    @Test
    fun `a remembered release a service removed is forgotten, one it no longer has cached isn't`() {
        val it = PlayCandidate(Replay.key(remembered), remembered.title, "fake")
        assertTrue(Replay.broke(remembered, listOf(it to AttemptFailure.RemovedByService)))
        listOf(
            AttemptFailure.NoLongerCached, AttemptFailure.ServiceLimit(null, "service:a"), AttemptFailure.KeyRefused("TorBox", "service:a"),
            AttemptFailure.ServiceDown("service:a"), AttemptFailure.ServiceRefused(tooLarge = true), AttemptFailure.NoWorkingLink,
        ).forEach { failure -> assertFalse(Replay.broke(remembered, listOf(it to failure)), "$failure") }
    }

    /** A file a Cast device can't play (L57) still plays on this phone: Resume and replay may choose it here. */
    @Test
    fun `a remembered release refused for a Cast device isn't forgotten`() {
        val it = PlayCandidate(Replay.key(remembered), remembered.title, "fake")
        assertFalse(Replay.broke(remembered, listOf(it to AttemptFailure.NotForCast("avi"))))
        assertFalse(Replay.broke(remembered, listOf(it to AttemptFailure.NotForCast("m2ts")), choseFile = true, pickedFile = { remembered.fileName }))
    }

    /** What a Cast device can't play of a file (L129) is its limit, not the release's: it still plays on this phone. */
    @Test
    fun `a remembered release its Cast device can't fully play isn't forgotten`() {
        val it = PlayCandidate(Replay.key(remembered), remembered.title, "fake")
        listOf(AttemptFailure.NoCastAudio(listOf("DTS-HD")), AttemptFailure.NoCastVideo("VC-1"), AttemptFailure.TooBigForCast(2160, 1080)).forEach { failure ->
            assertFalse(Replay.broke(remembered, listOf(it to failure)), "$failure")
            assertFalse(Replay.broke(remembered, listOf(it to failure), choseFile = true, pickedFile = { remembered.fileName }), "$failure")
        }
    }

    /**
     * Where the user chose which of its files plays (Choose file), the file chosen failing says nothing of the file
     * remembered for the episode, which may play: only the remembered file failing, or the release failing before any
     * file was picked, does.
     */
    @Test
    fun `a file the user chose failing doesn't forget the release remembered with another file`() {
        val pack = remembered.copy(fileName = "chapters.mkv")
        val it = PlayCandidate(Replay.key(pack), pack.title, "fake")
        val failures = listOf(it to AttemptFailure.MissingArticles("430"))
        assertFalse(Replay.broke(pack, failures, choseFile = true, pickedFile = { "tracks.mkv" }))
        assertTrue(Replay.broke(pack, failures, choseFile = true, pickedFile = { "chapters.mkv" }))
        // Failing as it opened, before a file was picked: the release, whatever its file.
        assertTrue(Replay.broke(pack, listOf(it to AttemptFailure.Passworded), choseFile = true, pickedFile = { null }))
        // Any other play picks the remembered file in it.
        assertTrue(Replay.broke(pack, failures, pickedFile = { "tracks.mkv" }))
    }

    @Test
    fun `a broken release is forgotten under the key it is remembered under`() {
        assertEquals(WatchKey.episode(900002, 2, 5), Replay.watchKey(ResumePlan.of(episode).request!!.watch, remembered))
        assertEquals(WatchKey.movie(900010), Replay.watchKey(WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = 900010), remembered))
        assertEquals(WatchKey.release("abc", 2), Replay.watchKey(WatchTarget(WatchKind.Release, remembered.title), remembered))
        assertNull(Replay.watchKey(WatchTarget(WatchKind.Release, remembered.title), remembered.copy(nzbId = null)))
    }

    @Test
    fun `a film with no release remembered says so, with the search`() = runBlocking<Unit> {
        val shown = ResumePlan.of(film.copy(release = null)).run(play = { error("nothing to play") }, searchEpisode = { _, _ -> error("no episode") })
        assertEquals(
            PlayState.Failed("Copper Crown", null, listOf("Copper Crown" to "No release is remembered for it"), notTried = 0, search = "Copper Crown",
                searchPick = SearchPick(TitleType.Movie, 900010, "Copper Crown"), remote = listOf("No release is remembered for it")),
            shown,
        )
    }

    @Test
    fun `a release-only play resumes its file and fails with a search for its title`() = runBlocking<Unit> {
        val plan = ResumePlan.of(releaseOnly)
        val request = assertNotNull(plan.request)
        assertEquals("Northbound", request.title)
        // Named for no episode: where its file isn't there, a release of one video plays that one, else nothing (#179).
        assertEquals(RememberedFile(0, FilePicker.Largest, wanted = SoleVideo), request.picker)
        assertEquals(WatchTarget(WatchKind.Release, "Northbound.2019.1080p.BluRay.x264-GRP"), request.watch)
        assertNull(plan.episode)
        val failed = PlayState.Failed("Northbound", null, listOf(releaseOnly.title to "Passworded"), notTried = 0)
        assertEquals(failed.copy(search = "Northbound 2019"), plan.run(play = { failed }, searchEpisode = { _, _ -> error("no episode") }))
    }

    /**
     * A release played without TMDB is keyed by its NZB's hash and file. Resumed once the store has
     * let its NZB go, it is grabbed again, and the indexer's copy may not be byte for byte the one
     * that played (a generated header, a proxy rewriting it), nor list the file at the same index:
     * the play keeps the key its progress is saved under, so it starts from there and Up next keeps
     * one row for it.
     */
    @Test
    fun `a resumed release-only play keeps its watch key when its NZB grabbed again differs`() {
        val request = assertNotNull(ResumePlan.of(releaseOnly).request)
        val regrabbed = SavedRelease(remembered.indexerId, remembered.guid, releaseOnly.title, remembered.downloadTemplate, fileIndex = 1, nzbId = "def")
        assertEquals(releaseOnly.key, usenetLaunch(request, regrabbed, "play-1", retry = null).watchKey)
        assertEquals(releaseOnly.key, usenetLaunch(request, regrabbed.copy(fileIndex = 0, nzbId = "abc"), "play-2", retry = null).watchKey)
        // Only the remembered release keeps it: another release is keyed by itself, as any.
        assertEquals(WatchKey.release("ghi", 0), usenetLaunch(request, SavedRelease("b", "2", "Other", null, 0, "ghi"), "play-3", retry = null).watchKey)
        // Broken by the next Resume, it is forgotten under that key too, the one it is remembered under.
        assertEquals(releaseOnly.key, Replay.watchKey(request.watch, regrabbed.toRemembered(), request.resumeKey))
        // An episode's or film's key is the TMDB one either way.
        val film = assertNotNull(ResumePlan.of(film).request)
        assertEquals(WatchKey.movie(900010), usenetLaunch(film, regrabbed, "play-4", retry = null).watchKey)
    }

    /**
     * L176: a release Resume tries again is ranked from where it played, as the search's would be: one remembered from a
     * torrent as a torrent, one remembered before sources were (null) as Usenet's.
     */
    @Test
    fun `a remembered release is a candidate of the source it played from`() {
        assertEquals(ReleaseFacts.USENET, Replay.candidate(remembered).ranked.facts.source)
        assertEquals(ReleaseFacts.USENET, Replay.candidate(remembered.copy(source = ReleaseFacts.USENET)).ranked.facts.source)
        val torrent = remembered.copy(guid = "btih:5c1d0b4e2a7f9c3e8d6b1a0f4e2c7d9b3a5e8f10", downloadTemplate = null, source = ReleaseFacts.TORRENT)
        assertEquals(ReleaseFacts.TORRENT, Replay.candidate(torrent).ranked.facts.source)
    }

    private companion object {
        const val TORRENT_HASH = "c0ffee11c0ffee22c0ffee33c0ffee44c0ffee55"
    }
}
