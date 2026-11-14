package io.skjaere.usenet.core

import io.skjaere.compressionutils.EncryptionInfo
import io.skjaere.compressionutils.SplitInfo
import io.skjaere.nntp.ArticleNotFoundException
import io.skjaere.nntp.NntpAuthenticationException
import io.skjaere.nntp.NntpConnectionException
import io.skjaere.nntp.NntpConnectionLostException
import io.skjaere.nntp.YencHeaders
import io.skjaere.nzbstreamer.enrichment.ArchiveVolumeNotInNzbException
import io.skjaere.nzbstreamer.enrichment.UnplacedArchiveVolumeException
import io.skjaere.nzbstreamer.enrichment.VerificationResult
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.nzbstreamer.nzb.FilenameResolver
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.nzb.NzbFile
import io.skjaere.nzbstreamer.nzb.NzbParser
import io.skjaere.nzbstreamer.stream.SegmentTimeoutException
import io.skjaere.nzbstreamer.stream.StreamableFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ConnectException
import java.net.SocketException
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class PlayPipelineTest {

    /** How each fake release behaves; keyed by candidate id, which the fake NZB carries in a comment. */
    private enum class Release {
        Good, Missing, Passworded, Unfetchable, GrabLimit, BadKey, Silent, Pack, ProviderDown, CompressedOnly, OtherEpisode,
        Par2Gap, RarPack, RarPackGapElsewhere, RarPackGapInEpisode, TimesOut, GapOnOpen, ProviderDownOnOpen, PackEpisodeGone,
        VolumeNotInNzb, VolumeUnplaced, EpisodePairFirstGone, LoneVideoGap,
        /** A season pack whose videos are named for no episode in a form the parser knows: "Show.1_6.mp4" is episode 1 of 6. */
        UnnamedPack,
        /** A season pack of E01 to E03, named for them, and an extra named for no episode. */
        PackWithExtra,
        /** Encrypted: opens with [INDEXER_PASSWORD], which its indexer sends beside the NZB. */
        EncryptedByIndexer,
        /** Encrypted: opens with the password in its NZB's head, [OWN_PASSWORD]; its indexer sends a wrong one beside it. */
        EncryptedOwnPassword,
        /**
         * A RAR set whose file data is encrypted and its headers aren't (rar -p): it lists and opens without a password, and the
         * player reads its video as bytes of no format it knows.
         */
        DataEncrypted,
        /** One video, not in an archive, whose bytes are of no format the player knows. */
        Unreadable,
        /** A RAR set as [DataEncrypted]'s, whose video an extractor takes and then fails on. */
        BrokenData,
        /** A RAR set as [DataEncrypted]'s, whose video's reader crashes. */
        CrashingReader,
        /** A season pack whose videos are named for no episode the parser reads ("Show.1_6.mp4"), and a sample named for E01. */
        UnnamedPackNamedSample,
        /** One video under an obfuscated name, and a sample named for the episode. */
        ObfuscatedNamedSample,
        /** Opening it, the provider's connection is reset under a command. */
        ConnectionLostOnOpen,
        /** Opening it, the provider can't be reached: its connect is refused. */
        UnreachableOnOpen,
        /** Its video is of a kind no decoder of the device takes: Dolby Vision profile 5, on a device without its decoder. */
        NoVideoDecoder,
        /** A UHD Blu-ray disc's files in a RAR set, their folders kept ([DISC]/BDMV/…), as prepare lists them (C6). */
        BluRayDisc,
        /** A Blu-ray disc's files posted as they are, without folders: two streams beside the disc's navigation files. */
        FlatDisc,
        /** A DVD's files in a RAR set, in their VIDEO_TS folder. */
        DvdCopy,
        /** A disc image, posted as it is. */
        DiscImage,
        /** [BluRayDisc]'s files and, beside them, the film as one video, smaller than the disc's stream. */
        DiscWithMkv,
        /** A disc's stream and navigation files posted without folders, and a video of the release's own, smaller than the stream. */
        FlatDiscWithMkv,
    }

    /** The password each release's indexer sends beside its NZB (a header, the file name), when it sends one. */
    private val indexerPasswords = mapOf(Release.EncryptedByIndexer to INDEXER_PASSWORD, Release.EncryptedOwnPassword to "not-it")

    /** Grabs an indexer refuses for every release it has: a used-up download limit, a revoked API key. */
    private val refusals = mapOf(Release.GrabLimit to "Download limit reached", Release.BadKey to "Incorrect API key")

    /**
     * A file in a fake NZB of [segments] articles; those numbered in [gaps] are gone from the provider.
     * Opening reads every file's first article and leaves out a file that lacks it, as nzb-streamer does
     * one another file stands in for (an episode of a season pack, library issue N15).
     */
    private data class FakeFile(val name: String, val segments: Int = 3, val gaps: Set<Int> = emptySet())

    /** What a fake release's NZB holds. [rarEntries]: the files inside its RAR set, by the volumes they are stored in. */
    private class Content(val files: List<FakeFile>, val rarEntries: Map<String, IntRange> = emptyMap())

    private val single = listOf(FakeFile("a8f3c1.mkv", 10), FakeFile("a8f3c1.sample.mkv", 1))

    /** A season pack stored as one RAR set: E01 in volumes 0-1, E02 in 2-3, E03 in 4-5, plus a PAR2 recovery volume. */
    private fun rarPack(volumeGaps: Map<Int, Set<Int>> = emptyMap(), recoveryGaps: Set<Int> = emptySet()) = Content(
        files = (0 until 6).map { FakeFile("Show.S01.1080p.WEB.part%02d.rar".format(it + 1), gaps = volumeGaps[it].orEmpty()) } +
            FakeFile("Show.S01.1080p.WEB.vol00+01.par2", gaps = recoveryGaps),
        rarEntries = (1..3).associate { "Show.S01E%02d.1080p.WEB.mkv".format(it) to (2 * it - 2..2 * it - 1) },
    )

    private fun contentOf(release: Release): Content = when (release) {
        Release.Missing -> Content(listOf(FakeFile("a8f3c1.mkv", 10, gaps = setOf(7)), FakeFile("a8f3c1.sample.mkv", 1)))
        Release.Pack -> Content((1..3).map { FakeFile("Show.S01E%02d.1080p.WEB.mkv".format(it)) } + FakeFile("Show.S01.nfo", 1))
        Release.PackEpisodeGone ->
            Content((1..3).map { FakeFile("Show.S01E%02d.1080p.WEB.mkv".format(it), gaps = if (it == 3) setOf(1) else emptySet()) } + FakeFile("Show.S01.nfo", 1))
        Release.CompressedOnly -> Content(listOf(FakeFile("a8f3c1.rar")))
        Release.PackWithExtra -> Content((1..3).map { FakeFile("Show.S01E%02d.1080p.WEB.mkv".format(it)) } + FakeFile("Show.S01.Behind.The.Harbour.1080p.WEB.mkv"))
        Release.UnnamedPack -> Content(
            listOf(FakeFile("Show.1_6.mp4", 4), FakeFile("Show.2-6.mp4", 5), FakeFile("Show.3_6.mp4", 3), FakeFile("Show.sample.mp4", 1), FakeFile("Show.S01.nfo", 1)),
        )
        Release.OtherEpisode -> Content(listOf(FakeFile("Show.S01E01.1080p.WEB.mkv")))
        Release.UnnamedPackNamedSample -> Content(
            listOf(FakeFile("Show.1_6.mp4", 4), FakeFile("Show.2-6.mp4", 5), FakeFile("Show.3_6.mp4", 3), FakeFile("Show.S01E01.Sample.mkv", 1), FakeFile("Show.S01.nfo", 1)),
        )
        Release.ObfuscatedNamedSample -> Content(listOf(FakeFile("a8f3c1.mkv", 10), FakeFile("Show.S01E01.1080p.WEB.sample.mkv", 1)))
        Release.Par2Gap -> Content(single + FakeFile("a8f3c1.vol00+01.par2", 4, gaps = setOf(2)) + FakeFile("a8f3c1.nfo", 1))
        Release.RarPack -> rarPack()
        Release.RarPackGapElsewhere -> rarPack(volumeGaps = mapOf(1 to setOf(2)), recoveryGaps = setOf(2))
        Release.RarPackGapInEpisode -> rarPack(volumeGaps = mapOf(5 to setOf(3)))
        // A release of two episodes, the first of which lacks its first article: prepare leaves it out, and keeps the second.
        Release.EpisodePairFirstGone ->
            Content((1..2).map { FakeFile("Show.S01E%02d.1080p.WEB.mkv".format(it), gaps = if (it == 1) setOf(1) else emptySet()) })
        // One video with a gap, beside a sample and a subtitle prepare doesn't keep (nzb-streamer doesn't fetch samples).
        Release.DataEncrypted, Release.BrokenData, Release.CrashingReader -> Content(
            listOf(FakeFile("a8f3c1.part01.rar"), FakeFile("a8f3c1.part02.rar"), FakeFile("a8f3c1.vol00+01.par2")),
            rarEntries = mapOf("a8f3c1.mkv" to 0..1),
        )
        Release.BluRayDisc -> Content(rarVolumes, rarEntries = discEntries)
        Release.DiscWithMkv -> Content(rarVolumes, rarEntries = discEntries + ("$DISC/Copper.Crown.2019.2160p.BluRay.REMUX.mkv" to 1..1))
        Release.DvdCopy -> Content(rarVolumes, rarEntries = mapOf("Quiet.Orbit.1999.DVD/VIDEO_TS/VIDEO_TS.IFO" to 0..0, "Quiet.Orbit.1999.DVD/VIDEO_TS/VTS_01_1.VOB" to 0..1))
        Release.FlatDisc -> Content(listOf(FakeFile("00001.m2ts", 10), FakeFile("00002.m2ts", 4), FakeFile("00800.mpls", 1), FakeFile("00001.clpi", 1), FakeFile("index.bdmv", 1)))
        Release.FlatDiscWithMkv -> Content(listOf(FakeFile("00001.m2ts", 10), FakeFile("00800.mpls", 1), FakeFile("index.bdmv", 1), FakeFile("a8f3c1.mkv", 5)))
        Release.DiscImage -> Content(listOf(FakeFile("Quiet.Orbit.1999.iso", 10), FakeFile("Quiet.Orbit.1999.nfo", 1)))
        Release.LoneVideoGap -> Content(
            listOf(FakeFile("a8f3c1.mkv", 10, gaps = setOf(7)), FakeFile("a8f3c1.sample.mkv", 1, gaps = setOf(1)), FakeFile("a8f3c1.en.srt", 1, gaps = setOf(1))),
        )
        else -> Content(single)
    }

    /** A RAR set's two volumes and its repair volume, which hold its [Content.rarEntries]. */
    private val rarVolumes = listOf(FakeFile("a8f3c1.part01.rar"), FakeFile("a8f3c1.part02.rar"), FakeFile("a8f3c1.vol00+01.par2"))

    /** A UHD Blu-ray disc's files, the stream in both volumes, its navigation files and a backup of its index beside it. */
    private val discEntries = mapOf(
        "$DISC/BDMV/index.bdmv" to 0..0, "$DISC/BDMV/PLAYLIST/00800.mpls" to 0..0, "$DISC/BDMV/CLIPINF/00001.clpi" to 0..0,
        "$DISC/BDMV/STREAM/00001.m2ts" to 0..1, "$DISC/BDMV/BACKUP/index.bdmv" to 1..1,
    )

    /** Season packs go by a pack's name; the rest by "Release n". */
    private val packTitle = "Show.S01.1080p.WEB-GRP"
    private val packs = setOf(
        Release.Pack, Release.RarPack, Release.RarPackGapElsewhere, Release.RarPackGapInEpisode, Release.PackEpisodeGone, Release.UnnamedPack, Release.PackWithExtra,
        Release.UnnamedPackNamedSample,
    )

    private fun articleId(id: String, file: FakeFile, number: Int) = "${if (number in file.gaps) GONE else ""}$id/${file.name}/$number"

    private fun nzbOf(id: String): ByteArray = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- $id -->\n<nzb xmlns=\"http://www.newzbin.com/DTD/2003/nzb\">\n")
        if (id.startsWith("${Release.EncryptedOwnPassword}:")) append("<head><meta type=\"password\">$OWN_PASSWORD</meta></head>\n")
        contentOf(Release.valueOf(id.substringBefore(':'))).files.forEach { file ->
            append("<file poster=\"fake\" date=\"0\" subject=\"&quot;${file.name}&quot; yEnc (1/${file.segments})\">")
            append("<groups><group>alt.binaries.fake</group></groups><segments>")
            (1..file.segments).forEach { n -> append("<segment bytes=\"$SEGMENT_SIZE\" number=\"$n\">${articleId(id, file, n)}</segment>") }
            append("</segments></file>\n")
        }
        append("</nzb>\n")
    }.encodeToByteArray()

    private fun nameOf(file: NzbFile) = checkNotNull(FilenameResolver.extractSubjectFilename(file.subject))

    private fun articlesOf(nzb: NzbDocument) = nzb.files.flatMap { file -> file.segments.map { it.articleId } }

    private val fetched = mutableListOf<String>()

    private fun release(bytes: ByteArray) = Release.valueOf(bytes.decodeToString().substringAfter("<!-- ").substringBefore(':'))

    /**
     * What [target] opened, as the NZB it carries names it. That must be the NZB fetched for its own candidate
     * ("<!-- Good:1 -->"), which [UsenetTarget.keep] stores and a Resume prepares again: every probe checks it.
     */
    private fun release(target: PlaybackTarget): Release {
        val nzb = (target as UsenetTarget).nzbBytes
        assertEquals(target.candidate.id, nzb.decodeToString().substringAfter("<!-- ").substringBefore(" -->"), "the NZB ${target.candidate.id}'s target carries")
        return release(nzb)
    }

    private val source = object : NzbSource {
        override suspend fun fetch(candidate: PlayCandidate): ByteArray = fetchWithPassword(candidate).bytes

        override suspend fun fetchWithPassword(candidate: PlayCandidate): FetchedNzb {
            fetched += candidate.id
            return when (val release = Release.valueOf(candidate.id.substringBefore(':'))) {
                Release.Unfetchable -> throw FetchException("HTTP 404")
                Release.GrabLimit, Release.BadKey -> throw FetchException(refusals.getValue(release), blocksIndexer = true)
                else -> FetchedNzb(nzbOf(candidate.id), indexerPasswords[release])
            }
        }
    }

    /** Every article the fake provider was asked about. It STATs all it is given, as "check every article" does. */
    private val checked = mutableListOf<String>()

    private val availability = AvailabilityBackend { nzb, progress ->
        val articles = articlesOf(nzb)
        checked += articles
        articles.indices.forEach { progress(it + 1, articles.size) }
        val gone = articles.firstOrNull { it.startsWith(GONE) }
        when {
            articles.any { it.startsWith("${Release.ProviderDown}:") } ->
                VerificationResult.Failure("auth", NntpAuthenticationException("481 Authentication failed"))
            gone != null -> VerificationResult.MissingArticles("Missing article $gone", ArticleNotFoundException(gone))
            else -> VerificationResult.Success
        }
    }

    /** Releases the preparer opened, in order. */
    private val opened = mutableListOf<Release>()

    /** The password each release was opened with, besides its NZB's own (which nzb-streamer reads itself), in order. */
    private val passwords = mutableListOf<Pair<Release, String?>>()

    /**
     * Opens fake NZBs as nzb-streamer does a plain release: every file but the PAR2 ones is a volume,
     * sized by its yEnc headers, and each volume is a file to stream, unless the release is a RAR set.
     * streamableFiles has no access to the NZB, so it goes by the release prepared last.
     */
    private val prepareBackend = object : PrepareBackend {
        override suspend fun prepare(nzbBytes: ByteArray, password: String?): PrepareResult {
            opened += release(nzbBytes)
            passwords += opened.last() to password
            // A password given supersedes the NZB's own, as nzb-streamer's passwordOverride does.
            val usable = password ?: NzbParser.parse(nzbBytes).password
            val unlocks = mapOf(Release.EncryptedByIndexer to INDEXER_PASSWORD, Release.EncryptedOwnPassword to OWN_PASSWORD)[opened.last()]
            if (unlocks != null && usable != unlocks) return encrypted
            if (opened.last() == Release.TimesOut) return PrepareResult.Failure(TIMED_OUT, SegmentTimeoutException("seg", 5_000, TIMED_OUT))
            if (opened.last() == Release.GapOnOpen) return PrepareResult.MissingArticles("Article <x> not found", ArticleNotFoundException("<x>"))
            if (opened.last() == Release.VolumeNotInNzb) return PrepareResult.MissingArticles(NOT_IN_NZB, ArchiveVolumeNotInNzbException(NOT_IN_NZB))
            if (opened.last() == Release.VolumeUnplaced) return PrepareResult.Failure(UNPLACED, UnplacedArchiveVolumeException("x7f3k9q2.avi", UNPLACED))
            if (opened.last() == Release.ProviderDownOnOpen) return PrepareResult.Failure("auth", NntpAuthenticationException("481 Authentication failed"))
            if (opened.last() == Release.ConnectionLostOnOpen) return PrepareResult.Failure(LOST, NntpConnectionLostException(LOST, SocketException("Connection reset")))
            if (opened.last() == Release.UnreachableOnOpen) return PrepareResult.Failure(UNREACHABLE, NntpConnectionException(UNREACHABLE, ConnectException("Connection refused")))
            if (opened.last() == Release.Passworded) return encrypted
            val volumes = NzbParser.parse(nzbBytes).files.filterNot { nameOf(it).endsWith(".par2") || it.segments.first().articleId.startsWith(GONE) }
                .onEach { it.yencHeaders = YencHeaders(line = 128, size = it.segments.size * SEGMENT_SIZE, name = nameOf(it)) }
            val response = NzbMetadataResponse(volumes = volumes.map(::nameOf), obfuscated = false, entries = emptyList())
            return PrepareResult.Success(
                if (opened.last() in archivedData) ExtractedMetadata.Archive(response, NzbDocument(files = volumes), entries = emptyList())
                else ExtractedMetadata.Raw(response = response, orderedArchiveNzb = NzbDocument(files = volumes)),
            )
        }

        override suspend fun streamableFiles(metadata: ExtractedMetadata): List<StreamableFile> {
            val volumes = metadata.orderedArchiveNzb.files
            val sizes = volumes.map { it.yencHeaders!!.size }
            val rarEntries = contentOf(opened.last()).rarEntries
            return when {
                opened.last() == Release.CompressedOnly -> emptyList()
                rarEntries.isEmpty() -> volumes.mapIndexed { i, volume ->
                    StreamableFile(nameOf(volume), sizes[i], startVolumeIndex = i, startOffsetInVolume = 0, continuationHeaderSize = 0, endOfArchiveSize = 0)
                }
                else -> rarEntries.map { (name, range) ->
                    val splits = range.map { SplitInfo(volumeIndex = it, dataStartPosition = sizes.take(it).sum(), dataSize = sizes[it]) }
                    StreamableFile(
                        name, splits.sumOf { it.dataSize }, startVolumeIndex = range.first, startOffsetInVolume = 0,
                        continuationHeaderSize = 0, endOfArchiveSize = 0, preComputedSplits = splits,
                    )
                }
            }
        }
    }

    private val preparer = NzbPreparer(prepareBackend, dispatcher = Dispatchers.Unconfined)

    /** Releases whose video is in a RAR set whose headers aren't encrypted, which opens as an archive without a password. */
    private val archivedData = setOf(Release.DataEncrypted, Release.BrokenData, Release.CrashingReader, Release.BluRayDisc, Release.DiscWithMkv, Release.DvdCopy)

    private val encrypted = PrepareResult.Encrypted(
        "enc",
        EncryptionInfo(archiveName = "a.rar", volumeIndex = 0, encryptionVersion = 0, kdfIterationsLog2 = 15, salt = null, hasPasswordCheck = true),
    )

    private val probe = TrackProbe { target ->
        val release = release(target)
        // A disc's stream, its packets 192 bytes: no extractor reads it (W103).
        if (target.file.name.endsWith(".m2ts")) return@TrackProbe ProbeResult.Failed(UsenetError.Unknown(null), UnreadableFile.UnknownFormat)
        when (release) {
            Release.Silent -> ProbeResult.NoPlayableAudio(listOf("E-AC-3 (en)"))
            Release.NoVideoDecoder -> ProbeResult.NoPlayableVideo(listOf("2160p Dolby Vision profile 5"))
            // As the probe fails them: in no words a reader could show (L317).
            Release.DataEncrypted, Release.Unreadable -> ProbeResult.Failed(UsenetError.Unknown(null), UnreadableFile.UnknownFormat)
            Release.BrokenData -> ProbeResult.Failed(UsenetError.Unknown(null), UnreadableFile.Broken)
            Release.CrashingReader -> ProbeResult.Failed(UsenetError.Unknown(null), UnreadableFile.ReaderCrashed)
            else -> ProbeResult.Playable()
        }
    }

    private fun pipeline(maxAttempts: Int = 3, availability: AvailabilityBackend = this.availability, tried: TriedReleases = TriedReleases.None) =
        usenetPipeline(source, availability, preparer, probe, maxAttempts, dispatcher = Dispatchers.Unconfined, tried = tried)

    /**
     * Skips the candidates [known] names (by id) for the reason given, and keeps where the pipeline says a release's
     * articles are missing, which releases it says played, needed a password or are disc copies.
     */
    private class FakeTried(private val known: Map<String, String> = emptyMap()) : TriedReleases {
        val recorded = mutableListOf<Pair<String, MissingOn>>()
        val found = mutableListOf<String>()
        val lookedUp = mutableListOf<String>()
        val passwordsNeeded = mutableListOf<String>()
        val discCopies = mutableListOf<String>()

        override suspend fun knownMissing(candidate: PlayCandidate): String? = known[candidate.id].also { lookedUp += candidate.id }

        override suspend fun missing(candidate: PlayCandidate, on: MissingOn) {
            // As TriedReleases promises: a candidate is looked up before it is tried.
            assertTrue(candidate.id in lookedUp, "${candidate.id} found missing before it was looked up")
            recorded += candidate.id to on
        }

        override suspend fun found(candidate: PlayCandidate) {
            found += candidate.id
        }

        override suspend fun passwordNeeded(candidate: PlayCandidate) {
            assertTrue(candidate.id in lookedUp, "${candidate.id} found to need a password before it was looked up")
            passwordsNeeded += candidate.id
        }

        override suspend fun discCopy(candidate: PlayCandidate) {
            assertTrue(candidate.id in lookedUp, "${candidate.id} found a disc copy before it was looked up")
            discCopies += candidate.id
        }
    }

    private fun candidates(vararg releases: Release) =
        releases.mapIndexed { i, r -> PlayCandidate("$r:$i", if (r in packs) packTitle else "$r $i") }

    /** Candidates served by the named indexers; ids read e.g. "Good:B1" (release, indexer, position). */
    private fun fromIndexers(vararg releases: Pair<String, Release>) =
        releases.mapIndexed { i, (indexer, r) -> PlayCandidate("$r:$indexer$i", "$r $i", indexer) }

    @Test
    fun `first good release plays after four steps`() = runTest {
        val events = pipeline().play(candidates(Release.Good, Release.Good)).toList()
        val success = assertIs<PipelineEvent.Succeeded>(events.last())
        assertEquals("a8f3c1.mkv", success.target.file.name)
        assertEquals(listOf("Good:0"), fetched)
        val steps = events.filterIsInstance<PipelineEvent.Step>().filter { it.status is StepStatus.Done }.map { it.step }
        assertEquals(listOf(PipelineStep.FetchNzb, PipelineStep.CheckAvailability, PipelineStep.Open, PipelineStep.ProbeTracks), steps)
    }

    /**
     * What the probe read of the video that plays (its frame rate, length and Dolby Vision) comes with the play's success,
     * for the player's TV frame-rate match: the release that played's, not one that failed before it.
     */
    @Test
    fun `the probe's video facts come with the play's success`() = runTest {
        val film = ProbedVideo(fps = 24_000.0 / 1001, durationMs = 2_640_000, dolbyVision = true)
        val probed = TrackProbe { target ->
            if (release(target) == Release.Silent) ProbeResult.NoPlayableAudio(listOf("TrueHD (en)")) else ProbeResult.Playable(film)
        }
        val played = usenetPipeline(source, availability, preparer, probed, dispatcher = Dispatchers.Unconfined).play(candidates(Release.Silent, Release.Good)).last()
        val success = assertIs<PipelineEvent.Succeeded>(played)
        assertEquals("Good:1", success.target.candidate.id)
        assertEquals(film, success.video)
        // Audio alone, or a probe that read nothing of the video: none.
        val silent = usenetPipeline(source, availability, preparer, TrackProbe { ProbeResult.Playable() }, dispatcher = Dispatchers.Unconfined)
        assertNull(assertIs<PipelineEvent.Succeeded>(silent.play(candidates(Release.Good)).last()).video)
        assertNull(assertIs<PipelineEvent.Succeeded>(pipeline().play(candidates(Release.Good)).last()).video)
    }

    /**
     * L129: what the probe read of the file's tracks comes with the play's success, for a cast's verdict and the launch: the
     * release that played's, not one that failed before it, whose tracks came with its failure.
     */
    @Test
    fun `the probe's tracks come with the play's success`() = runTest {
        val film = ProbedTracks(video = listOf(ProbedTrack("video/avc", "H.264", width = 1920, height = 1080)), audio = listOf(ProbedTrack("audio/ac3", "AC-3", "en", channels = 6)))
        val silent = ProbedTracks(audio = listOf(ProbedTrack("audio/true-hd", "TrueHD", "en", channels = 8)))
        val probed = TrackProbe { target ->
            if (release(target) == Release.Silent) ProbeResult.NoPlayableAudio(listOf("TrueHD (en)"), silent) else ProbeResult.Playable(tracks = film)
        }
        val played = usenetPipeline(source, availability, preparer, probed, dispatcher = Dispatchers.Unconfined).play(candidates(Release.Silent, Release.Good)).last()
        val success = assertIs<PipelineEvent.Succeeded>(played)
        assertEquals("Good:1", success.target.candidate.id)
        assertEquals(film, success.tracks)
        // A probe that reads none: none.
        assertNull(assertIs<PipelineEvent.Succeeded>(pipeline().play(candidates(Release.Good)).last()).tracks)
    }

    /** Fetching NZB's detail gives the NZB's size as the app's sizes read: a 2.3 MB NZB is "2.3 MB", not "2246 KB". */
    @Test
    fun `fetching the NZB says its size as the app's sizes read`() = runTest {
        val saved = Locale.getDefault()
        // The digits follow the default locale; pin one so the decimal separator is predictable.
        Locale.setDefault(Locale.US)
        try {
            val large = object : NzbSource {
                override suspend fun fetch(candidate: PlayCandidate) = fetchWithPassword(candidate).bytes

                // The good release's NZB, padded to 2.3 MB with spaces after its end.
                override suspend fun fetchWithPassword(candidate: PlayCandidate): FetchedNzb {
                    val nzb = nzbOf(candidate.id)
                    return FetchedNzb(nzb + ByteArray(2_300_000 - nzb.size) { ' '.code.toByte() })
                }
            }
            val events = usenetPipeline(large, availability, preparer, probe, dispatcher = Dispatchers.Unconfined).play(candidates(Release.Good)).toList()
            assertIs<PipelineEvent.Succeeded>(events.last(), "${events.last()}")
            val fetch = events.filterIsInstance<PipelineEvent.Step>().single { it.step == PipelineStep.FetchNzb && it.status is StepStatus.Done }
            assertEquals("2.3 MB", (fetch.status as StepStatus.Done).detail)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `incomplete, passworded, unfetchable, silent and undecodable releases fall through to the next one`() = runTest {
        val cases = listOf(Release.Missing, Release.Passworded, Release.Unfetchable, Release.Silent, Release.CompressedOnly, Release.NoVideoDecoder)
        cases.forEach { bad ->
            fetched.clear()
            val last = pipeline().play(candidates(bad, Release.Good)).last()
            val success = assertIs<PipelineEvent.Succeeded>(last, "$bad")
            assertEquals("Good:1", success.target.candidate.id)
            assertEquals(1, success.failedAttempts.size)
        }
    }

    @Test
    fun `failure reasons are specific`() = runTest {
        val last = pipeline(maxAttempts = 6).play(
            candidates(Release.Missing, Release.Passworded, Release.Unfetchable, Release.Silent, Release.CompressedOnly, Release.NoVideoDecoder),
        ).last()
        val failures = assertIs<PipelineEvent.Exhausted>(last).failedAttempts.map { it.second }
        assertIs<AttemptFailure.MissingArticles>(failures[0])
        assertIs<AttemptFailure.Passworded>(failures[1])
        assertEquals("Couldn't get the NZB: HTTP 404", failures[2].text)
        assertEquals("No audio this device can play (E-AC-3 (en))", failures[3].text)
        assertIs<AttemptFailure.Unsupported>(failures[4])
        assertEquals("No video this device can play: 2160p Dolby Vision profile 5", failures[5].text)
    }

    /**
     * Video no decoder of the device takes is this device's limit, and sometimes its screen's: the release isn't remembered
     * (that memory moves with the library to other devices), the same indexer's next release is tried, and the attempt
     * counts against the releases to try, as each costs a grab.
     */
    @Test
    fun `a release without playable video tries the next one, uses an attempt and isn't remembered`() = runTest {
        val tried = FakeTried()
        val next = pipeline(tried = tried).play(fromIndexers("A" to Release.NoVideoDecoder, "A" to Release.Good)).last()
        assertEquals("Good:A1", assertIs<PipelineEvent.Succeeded>(next).target.candidate.id)
        val failure = assertIs<AttemptFailure.NoPlayableVideo>(next.failedAttempts.single().second)
        assertFalse(failure.route != null || failure.blocksIndexer)
        assertEquals(emptyList(), tried.recorded)
        assertEquals(emptyList(), tried.passwordsNeeded)
        val exhausted = pipeline(maxAttempts = 2).play(candidates(Release.NoVideoDecoder, Release.NoVideoDecoder, Release.Good)).last()
        assertEquals(2, assertIs<PipelineEvent.Exhausted>(exhausted).failedAttempts.size)
        assertEquals("No video this device can play", AttemptFailure.NoPlayableVideo(emptyList()).text)
    }

    /**
     * A file of a type a Cast device can't play (L57), found before anything is sent to it: the release's file plays on this
     * phone, so it isn't remembered against the release, and the next release is tried, as for any failed attempt.
     */
    @Test
    fun `a file a Cast device can't play fails its attempt, the next release is tried, and nothing is remembered`() = runTest {
        val tried = FakeTried()
        val castProbe = TrackProbe { target -> if (target.candidate.id == "Good:0") ProbeResult.NotForCast("avi") else ProbeResult.Playable() }
        val events = usenetPipeline(source, availability, preparer, castProbe, dispatcher = Dispatchers.Unconfined, tried = tried)
            .play(candidates(Release.Good, Release.Good)).toList()
        val success = assertIs<PipelineEvent.Succeeded>(events.last())
        assertEquals("Good:1", success.target.candidate.id)
        val failure = assertIs<AttemptFailure.NotForCast>(success.failedAttempts.single().second)
        assertEquals("avi", failure.extension)
        assertEquals("AVI file. Cast devices play MKV, MP4, WebM and MOV.", failure.text)
        assertFalse(failure.route != null || failure.blocksIndexer)
        // The probe's step failed with it, as the sheet shows.
        val probed = events.filterIsInstance<PipelineEvent.Step>().first { it.step == PipelineStep.ProbeTracks && it.status is StepStatus.Failed }
        assertEquals("Good:0", probed.candidate.id)
        assertEquals(emptyList(), tried.recorded)
        assertEquals(emptyList(), tried.passwordsNeeded)
        assertEquals(listOf("Good:1"), tried.found)
        // A file with no extension has no type to name.
        assertEquals("Unknown file type. Cast devices play MKV, MP4, WebM and MOV.", AttemptFailure.NotForCast("").text)
    }

    /**
     * A cast's file its device can't fully play (L129), found before anything is sent to it: the release plays on this phone
     * and the device's limits are no fault of it, so it isn't remembered against it, and the next release is tried, as for
     * any failed attempt. Its words name no device: the log has them.
     */
    @Test
    fun `a file its Cast device can't fully play fails its attempt, the next release is tried, and nothing is remembered`() = runTest {
        val misses = listOf(ProbeResult.NoCastAudio(listOf("DTS-HD", "TrueHD")), ProbeResult.NoCastVideo("VC-1"), ProbeResult.TooBigForCast(2160, 1080))
        misses.forEach { miss ->
            val tried = FakeTried()
            val castProbe = TrackProbe { target -> if (target.candidate.id == "Good:0") miss else ProbeResult.Playable() }
            val events = usenetPipeline(source, availability, preparer, castProbe, dispatcher = Dispatchers.Unconfined, tried = tried)
                .play(candidates(Release.Good, Release.Good)).toList()
            val success = assertIs<PipelineEvent.Succeeded>(events.last(), "$miss")
            assertEquals("Good:1", success.target.candidate.id)
            val failure = success.failedAttempts.single().second
            assertIs<AttemptFailure.CastMiss>(failure)
            assertEquals(miss.failure.text, failure.text)
            assertFalse(failure.route != null || failure.blocksIndexer)
            // The probe's step failed with it, as the sheet shows.
            val probed = events.filterIsInstance<PipelineEvent.Step>().first { it.step == PipelineStep.ProbeTracks && it.status is StepStatus.Failed }
            assertEquals("Good:0", probed.candidate.id)
            assertEquals(emptyList(), tried.recorded)
            assertEquals(emptyList(), tried.passwordsNeeded)
            assertEquals(listOf("Good:1"), tried.found)
        }
        assertEquals("No sound for Cast devices (DTS-HD, TrueHD)", AttemptFailure.NoCastAudio(listOf("DTS-HD", "TrueHD")).text)
        assertEquals("No sound for Cast devices", AttemptFailure.NoCastAudio(emptyList()).text)
        assertEquals("Video Cast devices can't show (VC-1)", AttemptFailure.NoCastVideo("VC-1").text)
        assertEquals("2160p over the device's 1080p", AttemptFailure.TooBigForCast(2160, 1080).text)
        // Each kind fails as its own failure.
        assertEquals(listOf(AttemptFailure.NoCastAudio::class, AttemptFailure.NoCastVideo::class, AttemptFailure.TooBigForCast::class), misses.map { it.failure::class })
    }

    /** Every release failing as one its Cast device can't fully play exhausts the play, each with its own words. */
    @Test
    fun `a play whose every file its Cast device can't fully play is exhausted with each one's words`() = runTest {
        val castProbe = TrackProbe { target -> if (target.candidate.id.endsWith(":0")) ProbeResult.NoCastAudio(listOf("DTS-HD")) else ProbeResult.NoCastVideo("VC-1") }
        val last = usenetPipeline(source, availability, preparer, castProbe, dispatcher = Dispatchers.Unconfined).play(candidates(Release.Good, Release.Good)).last()
        val exhausted = assertIs<PipelineEvent.Exhausted>(last)
        assertEquals(listOf("No sound for Cast devices (DTS-HD)", "Video Cast devices can't show (VC-1)"), exhausted.failedAttempts.map { it.second.text })
    }

    @Test
    fun `an encrypted release opens with the password its indexer sent beside the NZB`() = runTest {
        val success = assertIs<PipelineEvent.Succeeded>(pipeline().play(candidates(Release.EncryptedByIndexer)).last())
        assertEquals("EncryptedByIndexer:0", success.target.candidate.id)
        assertEquals(listOf<Pair<Release, String?>>(Release.EncryptedByIndexer to INDEXER_PASSWORD), passwords)
    }

    /** nzb-streamer's prepare takes a password given in place of the NZB's own: the indexer's is given only when the NZB has none. */
    @Test
    fun `the password in the NZB wins over the one its indexer sent`() = runTest {
        assertIs<PipelineEvent.Succeeded>(pipeline().play(candidates(Release.EncryptedOwnPassword)).last())
        assertEquals(listOf<Pair<Release, String?>>(Release.EncryptedOwnPassword to null), passwords)
    }

    /**
     * No password in the NZB, none from its indexer: the release fails as passworded, without asking for one, and is
     * remembered as needing one, so later plays skip it; the next release is tried. A source that says nothing of passwords
     * (one that only fetches) gives none.
     */
    @Test
    fun `an encrypted release with no password anywhere fails as passworded, is remembered so, and the next one plays`() = runTest {
        val tried = FakeTried()
        val success = assertIs<PipelineEvent.Succeeded>(pipeline(tried = tried).play(candidates(Release.Passworded, Release.Good)).last())
        assertEquals("Good:1", success.target.candidate.id)
        assertEquals(listOf<AttemptFailure>(AttemptFailure.Passworded), success.failedAttempts.map { it.second })
        assertEquals("Passworded", success.failedAttempts.single().second.text)
        assertEquals(listOf("Passworded:0"), tried.passwordsNeeded)
        assertEquals(emptyList(), tried.recorded)

        val plain = NzbSource { candidate -> nzbOf(candidate.id) }
        val unlockedByNothing = FakeTried()
        val pipeline = usenetPipeline(plain, availability, preparer, probe, dispatcher = Dispatchers.Unconfined, tried = unlockedByNothing)
        val end = assertIs<PipelineEvent.Exhausted>(pipeline.play(candidates(Release.EncryptedByIndexer)).last())
        assertEquals(listOf<AttemptFailure>(AttemptFailure.Passworded), end.failedAttempts.map { it.second })
        assertEquals(listOf("EncryptedByIndexer:0"), unlockedByNothing.passwordsNeeded)
        // Other failures aren't a password needed.
        val other = FakeTried()
        pipeline(maxAttempts = 5, tried = other).play(candidates(Release.Missing, Release.Unfetchable, Release.Silent, Release.CompressedOnly)).last()
        assertEquals(emptyList(), other.passwordsNeeded)
    }

    /**
     * The indexer flags a release passworded, and no password came with it, but its archive's headers aren't encrypted
     * (rar -p): nzb-streamer opens it as if it were plain, and the player can't read the video it lists. That fails as
     * passworded, and is remembered so. Not where a password came with it, a release the indexer doesn't flag, or a file
     * that isn't in an archive: then it is a file the player can't read, as before.
     */
    @Test
    fun `a release flagged passworded whose archive opens but whose video can't be read fails as passworded`() = runTest {
        val tried = FakeTried()
        val flagged = PlayCandidate("${Release.DataEncrypted}:0", "DataEncrypted 0", passworded = true)
        val success = assertIs<PipelineEvent.Succeeded>(pipeline(tried = tried).play(listOf(flagged, PlayCandidate("${Release.Good}:1", "Good 1"))).last())
        assertEquals("Good:1", success.target.candidate.id)
        assertEquals(listOf<AttemptFailure>(AttemptFailure.Passworded), success.failedAttempts.map { it.second })
        assertEquals(listOf("DataEncrypted:0"), tried.passwordsNeeded)

        fun unreadable(candidate: PlayCandidate, source: NzbSource = this@PlayPipelineTest.source) = runBlocking {
            val other = FakeTried()
            val end = assertIs<PipelineEvent.Exhausted>(usenetPipeline(source, availability, preparer, probe, dispatcher = Dispatchers.Unconfined, tried = other).play(listOf(candidate)).last())
            assertEquals(emptyList(), other.passwordsNeeded, "$candidate")
            end.failedAttempts.single().second.text
        }
        // In plain words, its type named (L318), never the player's own.
        assertEquals(UNREADABLE_MKV, unreadable(flagged.copy(passworded = false)))
        assertEquals(UNREADABLE_MKV, unreadable(PlayCandidate("${Release.Unreadable}:0", "Unreadable 0", passworded = true)))
        val withPassword = object : NzbSource {
            override suspend fun fetch(candidate: PlayCandidate) = nzbOf(candidate.id)
            override suspend fun fetchWithPassword(candidate: PlayCandidate) = FetchedNzb(nzbOf(candidate.id), INDEXER_PASSWORD)
        }
        assertEquals(UNREADABLE_MKV, unreadable(flagged, withPassword))
    }

    /**
     * A file an extractor took and then failed on (L317) may be damaged, or encrypted data where the indexer flags the
     * release; a reader that crashed read no data to tell, so it is never taken for a password needed (L330), whatever
     * the flag. Each is said in plain words, on its step's row as in the attempt's failure (L318).
     */
    @Test
    fun `a file an extractor failed on may be encrypted data, a reader that crashed never is`() = runTest {
        val tried = FakeTried()
        val events = pipeline(tried = tried).play(
            listOf(
                PlayCandidate("${Release.BrokenData}:0", "BrokenData 0"),
                PlayCandidate("${Release.CrashingReader}:1", "CrashingReader 1", passworded = true),
                PlayCandidate("${Release.BrokenData}:2", "BrokenData 2", passworded = true),
            ),
        ).toList()
        val end = assertIs<PipelineEvent.Exhausted>(events.last())
        assertEquals(
            listOf(
                "BrokenData:0" to "The player can't read this MKV file: it may be damaged",
                "CrashingReader:1" to "Reading the release failed",
                "BrokenData:2" to "Passworded",
            ),
            end.failedAttempts.map { (candidate, failure) -> candidate.id to failure.text },
        )
        assertEquals(listOf("BrokenData:2"), tried.passwordsNeeded)
        val probed = events.filterIsInstance<PipelineEvent.Step>().filter { it.step == PipelineStep.ProbeTracks }.mapNotNull { (it.status as? StepStatus.Failed)?.failure }
        assertEquals(
            listOf(AttemptFailure.Unreadable(UnreadableFile.Broken, "MKV"), AttemptFailure.Unreadable(UnreadableFile.ReaderCrashed, "MKV"), AttemptFailure.Passworded),
            probed,
        )
    }

    /**
     * W103, L311: a release whose files are a copy of a disc fails at Opening as soon as prepare lists them, in the words of
     * its kind, before any file is picked or probed, and the next release plays. Its grab counts as an attempt (L315).
     */
    @Test
    fun `a disc copy fails as soon as it is opened, without a probe, and the next release plays`() = runTest {
        listOf(
            Release.BluRayDisc to DiscKind.BluRay, Release.FlatDisc to DiscKind.BluRay, Release.DvdCopy to DiscKind.Dvd, Release.DiscImage to DiscKind.Image,
        ).forEach { (disc, kind) ->
            val events = pipeline().play(candidates(disc, Release.Good)).toList()
            val success = assertIs<PipelineEvent.Succeeded>(events.last(), "$disc")
            assertEquals("Good:1", success.target.candidate.id)
            assertEquals(listOf("$disc:0" to AttemptFailure.DiscCopy(kind)), success.failedAttempts.map { (candidate, failure) -> candidate.id to failure })
            val steps = events.filterIsInstance<PipelineEvent.Step>().filter { it.candidate.id == "$disc:0" }
            assertEquals(listOf(PipelineStep.FetchNzb, PipelineStep.CheckAvailability, PipelineStep.Open), steps.map { it.step }.distinct(), "$disc")
            assertEquals(AttemptFailure.DiscCopy(kind), (steps.last().status as StepStatus.Failed).failure)
            assertEquals(listOf(1 to 2, 2 to 2), events.filterIsInstance<PipelineEvent.AttemptStarted>().map { it.attempt to it.of })
        }
    }

    /** A disc's streams name no episode: a disc copy in a play of an episode fails before anyone is asked which is the episode (L311). */
    @Test
    fun `a disc copy fails before anyone is asked which file is the episode`() = runTest {
        val asked = mutableListOf<String>()
        val pipeline = asking { candidate, _, choices -> asked += candidate.id; choices.first() }
        listOf(Release.FlatDisc, Release.BluRayDisc, Release.DvdCopy, Release.DiscImage).forEach { disc ->
            val success = assertIs<PipelineEvent.Succeeded>(pipeline.play(candidates(disc, Release.Good), FilePicker.Episode(1, 1)).last(), "$disc")
            assertIs<AttemptFailure.DiscCopy>(success.failedAttempts.single().second, "$disc")
        }
        assertEquals(emptyList(), asked)
    }

    /** L312: a disc beside a video of the release's own plays that video, however larger the disc's stream is. */
    @Test
    fun `a disc beside a video of the release's own plays the video`() = runTest {
        val success = assertIs<PipelineEvent.Succeeded>(pipeline().play(candidates(Release.DiscWithMkv)).last())
        assertEquals("Copper.Crown.2019.2160p.BluRay.REMUX.mkv", success.target.file.name)
        assertEquals(emptyList(), success.failedAttempts)
    }

    /**
     * L312: a disc's files posted without their folders beside a video of the release's own are no disc copy. Its larger
     * stream may be picked, which no extractor reads: said as a file the player can't read (W104), and the next release plays.
     */
    @Test
    fun `a flat disc beside a video of its own may pick the disc's stream, which the player can't read, and the next release plays`() = runTest {
        val success = assertIs<PipelineEvent.Succeeded>(pipeline().play(candidates(Release.FlatDiscWithMkv, Release.Good)).last())
        assertEquals("Good:1", success.target.candidate.id)
        assertEquals(listOf("FlatDiscWithMkv:0" to "The player can't read this M2TS file"), success.failedAttempts.map { (candidate, failure) -> candidate.id to failure.text })
    }

    /**
     * W103, L314: a disc copy is remembered, whatever play found it, a film's or an episode's, and nothing else is: the
     * next play skips it without a grab, and it isn't counted among the releases to try (L315).
     */
    @Test
    fun `a disc copy is remembered, and a play that knows it skips it without a fetch`() = runTest {
        val tried = FakeTried()
        assertIs<PipelineEvent.Succeeded>(pipeline(tried = tried).play(candidates(Release.BluRayDisc, Release.Good)).last())
        assertIs<PipelineEvent.Succeeded>(pipeline(tried = tried).play(candidates(Release.DiscImage, Release.Good), FilePicker.Episode(1, 1)).last())
        assertIs<PipelineEvent.Succeeded>(pipeline(tried = tried).play(candidates(Release.FlatDiscWithMkv, Release.DiscWithMkv)).last())
        assertEquals(listOf("BluRayDisc:0", "DiscImage:0"), tried.discCopies)
        assertEquals(emptyList(), tried.recorded)
        assertEquals(emptyList(), tried.passwordsNeeded)

        fetched.clear()
        val known = FakeTried(known = mapOf("BluRayDisc:0" to DISC_KNOWN))
        val events = pipeline(tried = known).play(candidates(Release.BluRayDisc, Release.Good)).toList()
        assertEquals("Good:1", assertIs<PipelineEvent.Succeeded>(events.last()).target.candidate.id)
        assertEquals(listOf("Good:1"), fetched)
        assertEquals(listOf("BluRayDisc:0" to DISC_KNOWN), events.filterIsInstance<PipelineEvent.Skipped>().map { it.candidate.id to it.reason })
        assertEquals(listOf(1 to 1), events.filterIsInstance<PipelineEvent.AttemptStarted>().map { it.attempt to it.of })
        // The user's own choice is looked up as nothing known: it is grabbed again, and fails as one again.
        val chosen = FakeTried()
        assertIs<PipelineEvent.Exhausted>(pipeline(tried = chosen).play(candidates(Release.BluRayDisc)).last())
        assertEquals(listOf("BluRayDisc:0"), chosen.discCopies)
    }

    /**
     * L314: a disc copy is the release's shape, not a gap in one file a play picked of several: remembered whatever the
     * source says of what failed ([AttemptSteps.wholeRelease]), as no source's file of a release can play it.
     */
    @Test
    fun `a disc copy is remembered though what failed was one file of the release's`() = runTest {
        val source = object : PlaySource<PlaybackTarget> {
            override val steps = listOf(PipelineStep.Open, PipelineStep.ProbeTracks)

            override suspend fun AttemptSteps.attempt(candidate: PlayCandidate, pickFile: FilePicker): PlaybackTarget {
                wholeRelease = false
                step<Nothing>(candidate, PipelineStep.Open) { StepResult.Fail(AttemptFailure.DiscCopy(DiscKind.Dvd)) }
            }

            override suspend fun AttemptSteps.probeFailed(failed: ProbeResult.Failed, target: PlaybackTarget): AttemptFailure = error("nothing was opened")

            override fun failed(error: Exception): AttemptFailure = AttemptFailure.Failed(error.toUsenetError())
        }
        val tried = FakeTried()
        assertIs<PipelineEvent.Exhausted>(PlayPipeline({ source }, probe, dispatcher = Dispatchers.Unconfined, tried = tried).play(candidates(Release.DvdCopy)).last())
        assertEquals(listOf("DvdCopy:0"), tried.discCopies)
    }

    /** nzb-streamer's own timeout is a library exception now (N20), which the attempt reports in its words. */
    @Test
    fun `a provider timing out while a release opens fails that release, and the next one is tried`() = runTest {
        val success = assertIs<PipelineEvent.Succeeded>(pipeline().play(candidates(Release.TimesOut, Release.Good)).last())
        val failure = assertIs<AttemptFailure.Failed>(success.failedAttempts.single().second)
        assertEquals(UsenetError.Timeout(TIMED_OUT), failure.error)
        assertEquals(TIMED_OUT, failure.text)
    }

    /**
     * A connection the server or the network dropped while a release opened is the network's (#256): with no other provider
     * to take over, that release fails and the next one is tried. A provider that can't be reached still stops them all.
     */
    @Test
    fun `a connection lost while a release opens fails that release, and the next one is tried`() = runTest {
        val success = assertIs<PipelineEvent.Succeeded>(pipeline().play(candidates(Release.ConnectionLostOnOpen, Release.Good)).last())
        assertEquals("Good:1", success.target.candidate.id)
        val failure = assertIs<AttemptFailure.Failed>(success.failedAttempts.single().second)
        assertEquals(UsenetError.Network(LOST), failure.error)
        assertNull(failure.route)

        val exhausted = assertIs<PipelineEvent.Exhausted>(pipeline().play(candidates(Release.UnreachableOnOpen, Release.Good)).last())
        val unreachable = assertIs<AttemptFailure.Provider>(exhausted.failedAttempts.single().second)
        assertEquals(UsenetError.ConnectionFailed(UNREACHABLE), unreachable.error)
        assertEquals(PlayCandidate.USENET, unreachable.route)
    }

    @Test
    fun `at most three releases are fetched`() = runTest {
        val last = pipeline().play(candidates(Release.Missing, Release.Missing, Release.Missing, Release.Good)).last()
        val exhausted = assertIs<PipelineEvent.Exhausted>(last)
        assertEquals(3, exhausted.failedAttempts.size)
        assertEquals(1, exhausted.notTried)
        assertEquals(3, fetched.size)
    }

    @Test
    fun `provider errors stop at once`() = runTest {
        val exhausted = assertIs<PipelineEvent.Exhausted>(pipeline().play(candidates(Release.ProviderDown, Release.Good)).last())
        assertEquals(1, fetched.size)
        assertEquals(PlayCandidate.USENET, exhausted.failedAttempts.single().second.route)
        assertEquals(1, exhausted.notTried)
    }

    /**
     * Fails every release it is asked for at its first step, as an outage of [route] would, stopping that route: what a
     * source other than Usenet says when, say, its account's limit is reached. Nothing of it is fetched.
     */
    private class RouteDown(private val route: String) : PlaySource<PlaybackTarget> {
        val tried = mutableListOf<String>()
        override val steps = listOf(PipelineStep.Open, PipelineStep.ProbeTracks)

        override suspend fun AttemptSteps.attempt(candidate: PlayCandidate, pickFile: FilePicker): PlaybackTarget {
            tried += candidate.id
            step<Nothing>(candidate, PipelineStep.Open) { StepResult.Fail(AttemptFailure.FetchFailed("out of reach", route = route)) }
        }

        override suspend fun AttemptSteps.probeFailed(failed: ProbeResult.Failed, target: PlaybackTarget): AttemptFailure = error("nothing was opened")

        override fun failed(error: Exception): AttemptFailure = AttemptFailure.Failed(error.toUsenetError())
    }

    /** Usenet's releases through [usenetPipeline]'s source, and those of the source "down" through [down]. */
    private fun routed(down: RouteDown, maxAttempts: Int = 5): PlayPipeline {
        val usenet = UsenetSource(source, availability, preparer, unusedStore)
        return PlayPipeline({ if (it.source == "down") down else usenet }, probe, maxAttempts, dispatcher = Dispatchers.Unconfined)
    }

    /**
     * A provider failing stops the Usenet route alone (L164): Usenet's releases after it aren't fetched, and a release that
     * plays by another route is tried as before (played here by the same fake source: the loop goes by the routes alone).
     */
    @Test
    fun `a provider failure stops the Usenet releases and leaves another route's to be tried`() = runTest {
        val elsewhere = PlayCandidate("Good:2", "Good 2", routes = listOf("service:a"))
        val events = pipeline().play(candidates(Release.ProviderDown, Release.Good) + elsewhere).toList()
        val played = assertIs<PipelineEvent.Succeeded>(events.last())
        assertEquals("Good:2", played.target.candidate.id)
        assertEquals(listOf("ProviderDown:0", "Good:2"), fetched)
        assertEquals(listOf("ProviderDown:0" to PlayCandidate.USENET), played.failedAttempts.map { (candidate, failure) -> candidate.id to failure.route })
        // Release 2 of 2: the Usenet release after the failure is no longer counted as one to try.
        assertEquals(listOf(1 to 3, 2 to 2), events.filterIsInstance<PipelineEvent.AttemptStarted>().map { it.attempt to it.of })
    }

    /** A release that plays by two routes is tried while either is open, and passed over once both are stopped. */
    @Test
    fun `a release with two routes is tried until both are stopped`() = runTest {
        val down = RouteDown("service:a")
        val both = listOf(PlayCandidate.USENET, "service:a")
        val releases = listOf(
            PlayCandidate("ProviderDown:0", "ProviderDown 0"),
            PlayCandidate("Down:1", "Down 1", routes = both, source = "down"),
            PlayCandidate("Good:2", "Good 2", routes = both),
            PlayCandidate("Good:3", "Good 3", routes = listOf("service:b")),
        )
        val played = assertIs<PipelineEvent.Succeeded>(routed(down).play(releases).last())
        assertEquals("Good:3", played.target.candidate.id)
        assertEquals(
            listOf("ProviderDown:0" to PlayCandidate.USENET, "Down:1" to "service:a"),
            played.failedAttempts.map { (candidate, failure) -> candidate.id to failure.route },
        )
        assertEquals(listOf("Down:1"), down.tried)
        assertEquals(listOf("ProviderDown:0", "Good:3"), fetched)

        // With no release left on an open route, the play ends: the release passed over counts as not tried, as Usenet's
        // releases after a provider failure always have, and isn't taken for one an indexer refused.
        fetched.clear()
        val exhausted = assertIs<PipelineEvent.Exhausted>(routed(RouteDown("service:a")).play(releases.take(3)).last())
        assertEquals(listOf("ProviderDown:0", "Down:1"), exhausted.failedAttempts.map { it.first.id })
        assertEquals(1 to 0, exhausted.notTried to exhausted.skipped)
        assertEquals(listOf("ProviderDown:0"), fetched)
    }

    @TempDir
    lateinit var storeDir: File

    /**
     * The app's probe keeps each attempt's NZB in the store as it reads it ([PlaybackTarget.keep]), where a Resume finds
     * those of the releases played before. An attempt that fails lets go of what it kept, so a play's failed attempts take
     * none of their places; an NZB kept before the play stays, failed or not.
     */
    @Test
    fun `failed attempts let go of what they kept, and leave what was kept before the play`() = runTest {
        val store = PreparedNzbStore(storeDir, ioDispatcher = Dispatchers.Unconfined)
        val keeping = TrackProbe { target -> target.keep(); probe.probe(target) }
        val pipeline = usenetPipeline(source, availability, preparer, keeping, maxAttempts = 10, dispatcher = Dispatchers.Unconfined, store = store)
        // Nine releases played, and one kept as Import keeps an NZB: the store's ten places.
        val played = (0 until 9).map { PlayCandidate("Good:R$it", "Good $it") }
        played.forEach { assertIs<PipelineEvent.Succeeded>(pipeline.play(listOf(it)).last()) }
        val imported = nzbOf("NoVideoDecoder:0")
        store.put(assertIs<PrepareOutcome.Ready>(preparer.prepare(imported, "NoVideoDecoder 0")).nzb, imported)
        val before = (played.map { it.id } + "NoVideoDecoder:0").map { PreparedNzb.idFor(nzbOf(it)) }

        val failing = candidates(*Array(10) { Release.NoVideoDecoder })
        assertEquals(10, assertIs<PipelineEvent.Exhausted>(pipeline.play(failing).last()).failedAttempts.size)
        assertEquals(before, before.filter { store.stored(it) != null })
        assertEquals(listOf(PreparedNzb.idFor(imported)), failing.map { PreparedNzb.idFor(nzbOf(it.id)) }.filter { store.stored(it) != null })
    }

    /** A file a service lists, as [Cached] opens it. */
    private data class Listed(override val index: Int, override val path: String, override val size: Long) : ListedFile

    private class OneFile(override val id: String, override val title: String) : FileListing<Listed> {
        override val files = listOf(Listed(0, "$title.mkv", 900))
    }

    private class CachedTarget(override val candidate: PlayCandidate, override val listing: OneFile) : PlaybackTarget {
        override val file: Listed get() = listing.files.single()
        override val uri: String get() = "debrid://${listing.id}/file/0"
        override suspend fun keep() = Unit
    }

    /**
     * A source of releases a service holds (L203): Preparing first, whatever the service needs, then Opening and the probe.
     * A release may play from several accounts, each its own route, tried in turn within its one attempt: one of the
     * accounts [down] stops its route there ([AttemptSteps.stop]) and the next is tried; one it is [missing] from fails the
     * release alone. Routes stopped before aren't tried. A release [failing] fails as given there, its route stopped or not
     * as the failure says.
     */
    private class Cached(
        private val down: Set<String> = emptySet(),
        private val missing: Set<String> = emptySet(),
        private val failing: Map<String, AttemptFailure> = emptyMap(),
    ) : PlaySource<CachedTarget> {
        val tried = mutableListOf<String>()
        override val steps = listOf(PipelineStep.Prepare, PipelineStep.Open, PipelineStep.ProbeTracks)

        override suspend fun AttemptSteps.attempt(candidate: PlayCandidate, pickFile: FilePicker): CachedTarget {
            step(candidate, PipelineStep.Prepare) {
                var failure: AttemptFailure? = null
                for (route in candidate.routes.filterNot { it in stopped }) {
                    tried += "${candidate.id} on $route"
                    failing[candidate.id]?.let { return@step StepResult.Fail(it) }
                    when (route) {
                        in down -> stop(route).also { failure = AttemptFailure.ServiceDown(route) }
                        in missing -> failure = AttemptFailure.NoLongerCached
                        else -> return@step StepResult.Ok(route)
                    }
                }
                StepResult.Fail(failure ?: AttemptFailure.NoLongerCached)
            }
            val listing = OneFile(candidate.id, candidate.title)
            step(candidate, PipelineStep.Open) { StepResult.Ok(Unit, listing.files.single().name) }
            return CachedTarget(candidate, listing)
        }

        override suspend fun AttemptSteps.probeFailed(failed: ProbeResult.Failed, target: CachedTarget): AttemptFailure = AttemptFailure.Failed(failed.error)

        override fun failed(error: Exception): AttemptFailure = AttemptFailure.Failed(error.toUsenetError())
    }

    /** Usenet's releases through [usenetPipeline]'s source, and torrents through [cached], each probed as playable once opened. */
    private fun withService(cached: Cached): PlayPipeline {
        val usenet = UsenetSource(source, availability, preparer, unusedStore)
        val probe = TrackProbe { target -> if (target is CachedTarget) ProbeResult.Playable() else this.probe.probe(target) }
        return PlayPipeline({ if (it.source == TORRENT) cached else usenet }, probe, maxAttempts = 5, dispatcher = Dispatchers.Unconfined)
    }

    private fun torrent(n: Int, vararg routes: String) = PlayCandidate("btih:$n", "Harbor.Lights.S01E02.Torrent$n", "Home Prowlarr", source = TORRENT, routes = routes.toList())

    /** In a mixed list a torrent tried after a Usenet release that failed has its source's steps, Preparing first (L203). */
    @Test
    fun `in a mixed list a torrent after a Usenet release that failed starts at Preparing`() = runTest {
        val events = withService(Cached()).play(candidates(Release.Missing) + torrent(1, "service:a")).toList()
        val played = assertIs<PipelineEvent.Succeeded>(events.last())
        assertEquals("btih:1", played.target.candidate.id)
        assertEquals(listOf("Missing:0"), played.failedAttempts.map { it.first.id })
        val first = events.filterIsInstance<PipelineEvent.Step>().filter { it.status is StepStatus.Running }.groupBy { it.candidate.id }.mapValues { it.value.first().step }
        assertEquals(mapOf("Missing:0" to PipelineStep.FetchNzb, "btih:1" to PipelineStep.Prepare), first)
        val done = events.filterIsInstance<PipelineEvent.Step>().filter { it.candidate.id == "btih:1" && it.status is StepStatus.Done }.map { it.step }
        assertEquals(listOf(PipelineStep.Prepare, PipelineStep.Open, PipelineStep.ProbeTracks), done)
        assertEquals(listOf(1 to 2, 2 to 2), events.filterIsInstance<PipelineEvent.AttemptStarted>().map { it.attempt to it.of })
    }

    /**
     * A route a source stops inside an attempt ([AttemptSteps.stop]: an account that stopped answering) is passed over by
     * the rest of the play, as one a failure stops: the release goes on to its next account, and a release cached on that
     * account alone isn't tried.
     */
    @Test
    fun `a route stopped inside an attempt moves on to the next account, and its other releases are passed over`() = runTest {
        val cached = Cached(down = setOf("service:a"), missing = setOf("service:b"))
        val releases = listOf(torrent(1, "service:a", "service:b"), torrent(2, "service:a"), torrent(3, "service:c"))
        val events = withService(cached).play(releases).toList()
        val played = assertIs<PipelineEvent.Succeeded>(events.last())
        assertEquals("btih:3", played.target.candidate.id)
        assertEquals(listOf("btih:1 on service:a", "btih:1 on service:b", "btih:3 on service:c"), cached.tried)
        assertEquals(listOf("btih:1" to null), played.failedAttempts.map { (candidate, failure) -> candidate.id to failure.route })
        // Release 2 of 2: the one cached on the stopped account alone is no longer counted, as after a provider failure.
        assertEquals(listOf(1 to 3, 2 to 2), events.filterIsInstance<PipelineEvent.AttemptStarted>().map { it.attempt to it.of })
    }

    /**
     * DEP-5: the routes the play stopped come with its success, those stopped inside the attempt that played among them (an
     * account that stopped answering before the next played the release), so Try another passes over that account's
     * releases too.
     */
    @Test
    fun `the routes a play stopped come with its success, those stopped inside the attempt that played among them`() = runTest {
        val played = assertIs<PipelineEvent.Succeeded>(withService(Cached(down = setOf("service:a"))).play(listOf(torrent(1, "service:a", "service:b"))).toList().last())
        assertEquals("btih:1", played.target.candidate.id)
        assertEquals(emptyList(), played.failedAttempts)
        assertEquals(setOf("service:a"), played.stopped)
        // A play that stopped nothing says so.
        assertEquals(emptySet(), assertIs<PipelineEvent.Succeeded>(withService(Cached()).play(listOf(torrent(2, "service:b"))).toList().last()).stopped)
    }

    /**
     * An account whose limit is reached, or whose key is refused, stops its route (L164): its other releases are passed
     * over without an attempt, and a Usenet release plays.
     */
    @Test
    fun `a limit or a refused key on an account passes over its other releases, and a Usenet release plays`() = runTest {
        listOf(AttemptFailure.ServiceLimit(null, "service:a"), AttemptFailure.KeyRefused("TorBox", "service:a")).forEach { failure ->
            fetched.clear()
            val cached = Cached(failing = mapOf("btih:1" to failure))
            val events = withService(cached).play(listOf(torrent(1, "service:a"), torrent(2, "service:a")) + candidates(Release.Good)).toList()
            val played = assertIs<PipelineEvent.Succeeded>(events.last(), "$failure")
            assertEquals("Good:0", played.target.candidate.id)
            assertEquals(listOf("btih:1 on service:a"), cached.tried)
            assertEquals(listOf("btih:1" to "service:a"), played.failedAttempts.map { (candidate, f) -> candidate.id to f.route })
            assertEquals(listOf("Good:0"), fetched)
        }
    }

    /**
     * DEP-8: of the releases left untried, those left as every route they play by stopped are counted apart, as no grab was
     * saved by leaving them: an account's limit leaves its other release with nothing to play from.
     */
    @Test
    fun `releases left with no route to play by are counted apart from those left to save grabs`() = runTest {
        val cached = Cached(failing = mapOf("btih:1" to AttemptFailure.ServiceLimit(null, "service:a")))
        val exhausted = assertIs<PipelineEvent.Exhausted>(withService(cached).play(listOf(torrent(1, "service:a"), torrent(2, "service:a"))).toList().last())
        assertEquals(1 to 1, exhausted.notTried to exhausted.noRoute)
    }

    /** A release the service won't take is that release's alone (L204): its account's route stays open, and its next release is tried. */
    @Test
    fun `a release its account won't take is passed, and the same account's next release is tried`() = runTest {
        val cached = Cached(failing = mapOf("btih:1" to AttemptFailure.ServiceRefused(tooLarge = true)))
        val played = assertIs<PipelineEvent.Succeeded>(withService(cached).play(listOf(torrent(1, "service:a"), torrent(2, "service:a"))).last())
        assertEquals("btih:2", played.target.candidate.id)
        assertEquals(listOf("btih:1 on service:a", "btih:2 on service:a"), cached.tried)
    }

    /** A provider failing stops Usenet's route alone: the torrents after it are tried, as an outage of a service leaves Usenet's. */
    @Test
    fun `a provider failure leaves the torrents to be tried`() = runTest {
        val cached = Cached()
        val played = assertIs<PipelineEvent.Succeeded>(withService(cached).play(candidates(Release.ProviderDown, Release.Good) + torrent(1, "service:a")).last())
        assertEquals("btih:1", played.target.candidate.id)
        assertEquals(listOf("ProviderDown:0"), fetched)
        assertEquals(listOf("btih:1 on service:a"), cached.tried)
    }

    /**
     * With another provider to fall back on, one that fails (its login rejected since the check before the build, out of
     * reach) while the others lack an article fails the release it was found in, not the play: the next release may be
     * whole on the others. Whichever step finds it. Nor is it the release's fault: the article may be on the one that failed.
     */
    @Test
    fun `a provider failing while another serves fails that release, not the play`() = runTest {
        val downOnProbe = TrackProbe { target ->
            if (target.candidate.id.endsWith(":0")) ProbeResult.Failed(UsenetError.ConnectionFailed("Could not connect to news.example.net:563")) else ProbeResult.Playable()
        }
        listOf(true, false).forEach { fallback ->
            fun play(release: Release, probe: TrackProbe = this@PlayPipelineTest.probe) =
                usenetPipeline(source, availability, preparer, probe, dispatcher = Dispatchers.Unconfined, providerFallback = { fallback })
                    .play(candidates(release, Release.Good))
            listOf(play(Release.ProviderDown), play(Release.ProviderDownOnOpen), play(Release.Good, downOnProbe)).forEach { events ->
                val last = events.last()
                if (fallback) {
                    val played = assertIs<PipelineEvent.Succeeded>(last)
                    assertEquals("Good:1", played.target.candidate.id)
                    val failure = assertIs<AttemptFailure.MissingArticles>(played.failedAttempts.single().second)
                    assertNull(failure.route)
                    assertTrue(failure.unanswered is UsenetError.AuthFailed || failure.unanswered is UsenetError.ConnectionFailed, "${failure.unanswered}")
                    assertTrue(failure.text.startsWith("Incomplete on the providers that answered · a provider failed: "), failure.text)
                } else {
                    val exhausted = assertIs<PipelineEvent.Exhausted>(last)
                    assertEquals(PlayCandidate.USENET, assertIs<AttemptFailure.Provider>(exhausted.failedAttempts.single().second).route)
                }
            }
        }
    }

    @Test
    fun `no usable provider stops after the first grab`() = runTest {
        UsenetEngine().use { engine ->
            // An engine without providers throws UsenetException(NotConfigured) from streamer().
            val pipeline = usenetPipeline(source, EngineAvailabilityBackend(engine), preparer, probe, dispatcher = Dispatchers.Unconfined)
            val exhausted = assertIs<PipelineEvent.Exhausted>(pipeline.play(candidates(Release.Good, Release.Good, Release.Good)).last())
            assertEquals(listOf("Good:0"), fetched)
            val failure = assertIs<AttemptFailure.Provider>(exhausted.failedAttempts.single().second)
            assertEquals(UsenetError.NotConfigured, failure.error)
            assertEquals(PlayCandidate.USENET, failure.route)
            assertEquals(2, exhausted.notTried)
        }
    }

    @Test
    fun `the engine checks the articles it is given, every one when asked to`() = runBlocking<Unit> {
        val volumes = NzbParser.parse(nzbOf("RarPack:0")).let { it.copy(files = it.files.take(2)) }
        // A sample of one is the first and last article of each volume.
        val sampled = volumes.files.flatMap { listOf(it.segments.first(), it.segments.last()) }.map { it.articleId }
        mapOf(true to articlesOf(volumes), false to sampled).forEach { (checkAll, expected) ->
            // The fake server answers every STAT with 430 and records it. Its answers wait until every STAT expected has
            // come: the check stops at the first article missing, and an answer that came sooner could stop it before it
            // sent the rest, or before the server had read them.
            FakeNntpServer(holdStatsFor = expected.size).use { server ->
                UsenetEngine().use { engine ->
                    engine.configure(EngineSettings(listOf(server.provider())))
                    val result = EngineAvailabilityBackend(engine, samples = 1, checkAll = { checkAll }).verify(volumes) { _, _ -> }
                    assertNull(server.statHoldTimedOut, "checkAll=$checkAll")
                    assertIs<VerificationResult.MissingArticles>(result)
                    assertEquals(expected.map { "STAT <$it>" }.sorted(), server.commands.filter { it.startsWith("STAT ") }.sorted(), "checkAll=$checkAll")
                }
            }
        }
    }

    @Test
    fun `an indexer refusing a grab moves on to the other indexers`() = runTest {
        refusals.keys.forEach { refusal ->
            fetched.clear()
            val last = pipeline().play(fromIndexers("A" to refusal, "B" to Release.Good, "B" to Release.Good)).last()
            assertEquals("Good:B1", assertIs<PipelineEvent.Succeeded>(last, "$refusal").target.candidate.id)
            assertEquals(listOf("$refusal:A0", "Good:B1"), fetched)
        }
    }

    @Test
    fun `later releases from a refusing indexer are skipped without a fetch`() = runTest {
        refusals.keys.forEach { refusal ->
            fetched.clear()
            val events = pipeline().play(fromIndexers("A" to refusal, "A" to Release.Good, "B" to Release.Good)).toList()
            assertEquals("Good:B2", assertIs<PipelineEvent.Succeeded>(events.last(), "$refusal").target.candidate.id)
            assertEquals(listOf("$refusal:A0", "Good:B2"), fetched)
            // Once A refuses, B's release is the last one left to try.
            val starts = events.filterIsInstance<PipelineEvent.AttemptStarted>()
            assertEquals(listOf(1 to 3, 2 to 2), starts.map { it.attempt to it.of })
        }
    }

    @Test
    fun `skipped releases don't use up attempts`() = runTest {
        val events = pipeline().play(
            fromIndexers("A" to Release.GrabLimit, "A" to Release.Good, "A" to Release.Good, "B" to Release.Missing, "B" to Release.Good),
        ).toList()
        assertEquals("Good:B4", assertIs<PipelineEvent.Succeeded>(events.last()).target.candidate.id)
        val starts = events.filterIsInstance<PipelineEvent.AttemptStarted>()
        assertEquals(listOf("GrabLimit:A0", "Missing:B3", "Good:B4"), starts.map { it.candidate.id })
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), starts.map { it.attempt to it.of })
    }

    @Test
    fun `skipped releases are counted apart from untried ones`() = runTest {
        val exhausted = assertIs<PipelineEvent.Exhausted>(
            pipeline().play(
                fromIndexers(
                    "A" to Release.GrabLimit, "A" to Release.Good, "B" to Release.Missing,
                    "B" to Release.Missing, "B" to Release.Good, "A" to Release.Good,
                ),
            ).last(),
        )
        assertEquals(listOf("GrabLimit:A0", "Missing:B2", "Missing:B3"), exhausted.failedAttempts.map { it.first.id })
        assertEquals(1, exhausted.notTried)
        assertEquals(2, exhausted.skipped)
    }

    @Test
    fun `releases all from a refusing indexer stop after the first`() = runTest {
        val runs = refusals.keys.flatMap { refusal ->
            listOf(
                refusal to fromIndexers("A" to refusal, "A" to Release.Good, "A" to Release.Good),
                // Without indexer ids every release counts as the same indexer's.
                refusal to candidates(refusal, Release.Good, Release.Good),
            )
        }
        runs.forEach { (refusal, candidates) ->
            fetched.clear()
            val exhausted = assertIs<PipelineEvent.Exhausted>(pipeline().play(candidates).last(), "$refusal")
            assertEquals(listOf(candidates.first().id), fetched)
            assertEquals("Couldn't get the NZB: ${refusals.getValue(refusal)}", exhausted.failedAttempts.single().second.text)
            assertEquals(0, exhausted.notTried)
            assertEquals(2, exhausted.skipped)
        }
    }

    @Test
    fun `episode picker finds the episode inside a season pack`() = runTest {
        val success = pipeline().play(candidates(Release.Pack), FilePicker.Episode(1, 3)).last()
        assertEquals("Show.S01E03.1080p.WEB.mkv", assertIs<PipelineEvent.Succeeded>(success).target.file.name)
    }

    @Test
    fun `episode picker takes an obfuscated single video but not a pack without the episode`() = runTest {
        val single = pipeline().play(candidates(Release.Good), FilePicker.Episode(1, 3)).last()
        assertEquals("a8f3c1.mkv", assertIs<PipelineEvent.Succeeded>(single).target.file.name)

        val missing = assertIs<PipelineEvent.Exhausted>(pipeline().play(candidates(Release.Pack), FilePicker.Episode(1, 9)).last())
        assertEquals("No file for S01E09", missing.failedAttempts.single().second.text)
    }

    /**
     * nzb-streamer leaves out a file that lacks its first article while another of its size stands in for it (library
     * issue N15): in a season pack, that may be the episode asked for. The pack is then incomplete, not without it.
     */
    @Test
    fun `a season pack whose wanted episode prepare left out is incomplete, not without the episode`() = runTest {
        listOf(true, false).forEach { leftOut ->
            val pipeline = usenetPipeline(source, availability, preparer, probe, dispatcher = Dispatchers.Unconfined, providersLeftOut = { leftOut })
            val exhausted = assertIs<PipelineEvent.Exhausted>(pipeline.play(candidates(Release.PackEpisodeGone), FilePicker.Episode(1, 3)).last())
            val failure = assertIs<AttemptFailure.MissingArticles>(exhausted.failedAttempts.single().second)
            assertEquals(leftOut, failure.providersLeftOut)
            assertTrue("Show.S01E03.1080p.WEB.mkv" in failure.detail, failure.detail)
        }
        // The episodes it kept play, and one it doesn't list is still not in it.
        assertIs<PipelineEvent.Succeeded>(pipeline().play(candidates(Release.PackEpisodeGone), FilePicker.Episode(1, 2)).last())
        val other = assertIs<PipelineEvent.Exhausted>(pipeline().play(candidates(Release.PackEpisodeGone), FilePicker.Episode(1, 9)).last())
        assertEquals("No file for S01E09", other.failedAttempts.single().second.text)
    }

    @Test
    fun `episode picker wants the episode's video or archive volumes by name`() {
        val picker = FilePicker.Episode(1, 3)
        listOf("Show.S01E03.1080p.WEB.mkv", "Show.S01E03.1080p.WEB.part01.rar", "Show.S01E03.1080p.WEB.r00", "show.s01e03.720p.mp4")
            .forEach { assertTrue(picker.wants(it), it) }
        listOf("Show.S01E04.1080p.WEB.mkv", "Show.S01E03.1080p.WEB.nfo", "Show.S01E03.1080p.WEB.srt", "Show.S01E03.sample.mkv", "Show.S01E03.vol00+01.par2", "a8f3c1.mkv")
            .forEach { assertFalse(picker.wants(it), it) }
        assertFalse(FilePicker.Largest.wants("Show.S01E03.1080p.WEB.mkv"))
    }

    @Test
    fun `attempt events count the attempts`() = runTest {
        val starts = pipeline().play(candidates(Release.Missing, Release.Good, Release.Good, Release.Good))
            .filterIsInstance<PipelineEvent.AttemptStarted>().toList()
        assertEquals(listOf(1 to 3, 2 to 3), starts.map { it.attempt to it.of })
    }

    @Test
    fun `stopping collection stops fetching`() = runTest {
        pipeline().play(candidates(Release.Missing, Release.Good)).first()
        assertEquals(emptyList(), fetched)
    }

    @Test
    fun `episode picker rejects a single video named as another episode`() = runTest {
        val exhausted = assertIs<PipelineEvent.Exhausted>(pipeline().play(candidates(Release.OtherEpisode), FilePicker.Episode(1, 3)).last())
        assertEquals("No file for S01E03", exhausted.failedAttempts.single().second.text)
    }

    private fun asking(chooser: FileChooser) = usenetPipeline(source, availability, preparer, probe, dispatcher = Dispatchers.Unconfined, chooser = chooser)

    /**
     * Episode numbers are read only in the well-known forms (S01E02, 1x02): a season pack whose videos are named
     * otherwise ("Show.1_6.mp4", episode 1 of 6) asks which of them is the episode, and plays the one chosen, checked on
     * its own volume once chosen. The question lists the release's videos, its sample among them.
     */
    @Test
    fun `a season pack whose videos name no episode asks which is the episode and plays the one chosen`() = runTest {
        val asked = mutableListOf<Pair<String, List<String>>>()
        val pipeline = asking { candidate, _, choices ->
            asked += candidate.id to choices.map { it.name }
            choices.single { it.name == "Show.2-6.mp4" }
        }
        val events = pipeline.play(candidates(Release.UnnamedPack), FilePicker.Episode(1, 2)).toList()
        val success = assertIs<PipelineEvent.Succeeded>(events.last(), "${events.last()}")
        assertEquals("Show.2-6.mp4", success.target.file.name)
        assertEquals(listOf("UnnamedPack:0" to listOf("Show.1_6.mp4", "Show.2-6.mp4", "Show.3_6.mp4", "Show.sample.mp4")), asked)
        assertEquals((1..5).map { articleId("UnnamedPack:0", FakeFile("Show.2-6.mp4", 5), it) }, checked)
        val open = events.filterIsInstance<PipelineEvent.Step>().single { it.step == PipelineStep.Open && it.status is StepStatus.Done }
        assertEquals("Show.2-6.mp4", (open.status as StepStatus.Done).detail)
        listOf("Show.1_6.mp4", "Show.2-6.mp4").forEach { assertFalse(FilePicker.Episode(1, 2).wants(it), it) }
    }

    /**
     * A sample is never the episode where the release holds other videos: in a pack whose videos name no episode, the one
     * whose sample is named for it is asked for as any other, and with no one to ask has no file; in a release of one
     * video under an obfuscated name, that video plays.
     */
    @Test
    fun `a sample named for the episode isn't played as it`() = runTest {
        val asked = mutableListOf<List<String>>()
        val pipeline = asking { _, _, choices -> asked += choices.map { it.name }; choices.single { it.name == "Show.1_6.mp4" } }
        val success = assertIs<PipelineEvent.Succeeded>(pipeline.play(candidates(Release.UnnamedPackNamedSample), FilePicker.Episode(1, 1)).last())
        assertEquals("Show.1_6.mp4", success.target.file.name)
        assertEquals(listOf(listOf("Show.1_6.mp4", "Show.2-6.mp4", "Show.3_6.mp4", "Show.S01E01.Sample.mkv")), asked)
        val exhausted = assertIs<PipelineEvent.Exhausted>(pipeline().play(candidates(Release.UnnamedPackNamedSample), FilePicker.Episode(1, 1)).last())
        assertEquals("No file for S01E01", exhausted.failedAttempts.single().second.text)
        assertEquals("a8f3c1.mkv", assertIs<PipelineEvent.Succeeded>(pipeline().play(candidates(Release.ObfuscatedNamedSample), FilePicker.Episode(1, 1)).last()).target.file.name)
    }

    /** With no one to ask, or nothing chosen, such a pack has no file for the episode, as before. */
    @Test
    fun `with no one to ask or nothing chosen, a pack whose videos name no episode has no file for it`() = runTest {
        listOf(pipeline(), asking { _, _, _ -> null }).forEach { pipeline ->
            val exhausted = assertIs<PipelineEvent.Exhausted>(pipeline.play(candidates(Release.UnnamedPack), FilePicker.Episode(1, 2)).last())
            assertEquals("No file for S01E02", exhausted.failedAttempts.single().second.text)
        }
    }

    /**
     * Nothing is asked where the names tell: videos named for other episodes (the pack lacks this one), beside an extra
     * named for none too, a release named for another season or for other episodes, a release of one video (taken as the
     * episode, or named for another), one with a file named for the episode, and a film.
     */
    @Test
    fun `nothing is asked where the names tell`() = runTest {
        val asked = mutableListOf<String>()
        val pipeline = asking { candidate, _, choices -> asked += candidate.id; choices.first() }
        suspend fun failure(candidates: List<PlayCandidate>, picker: FilePicker) =
            assertIs<PipelineEvent.Exhausted>(pipeline.play(candidates, picker).last()).failedAttempts.single().second.text
        assertEquals("No file for S01E09", failure(candidates(Release.Pack), FilePicker.Episode(1, 9)))
        assertEquals("No file for S01E09", failure(candidates(Release.PackWithExtra), FilePicker.Episode(1, 9)))
        assertEquals("No file for S02E01", failure(candidates(Release.UnnamedPack), FilePicker.Episode(2, 1)))
        assertEquals("No file for S01E02", failure(listOf(PlayCandidate("UnnamedPack:0", "Show.S01E05E06.1080p.WEB-GRP")), FilePicker.Episode(1, 2)))
        assertEquals("No file for S01E03", failure(candidates(Release.OtherEpisode), FilePicker.Episode(1, 3)))
        assertEquals("a8f3c1.mkv", assertIs<PipelineEvent.Succeeded>(pipeline.play(candidates(Release.Good), FilePicker.Episode(1, 3)).last()).target.file.name)
        assertEquals("Show.S01E03.1080p.WEB.mkv", assertIs<PipelineEvent.Succeeded>(pipeline.play(candidates(Release.Pack), FilePicker.Episode(1, 3)).last()).target.file.name)
        assertEquals("Show.2-6.mp4", assertIs<PipelineEvent.Succeeded>(pipeline.play(candidates(Release.UnnamedPack)).last()).target.file.name)
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `a gap in another episode of a season pack doesn't stop the requested one`() = runTest {
        val success = assertIs<PipelineEvent.Succeeded>(pipeline().play(candidates(Release.RarPackGapElsewhere), FilePicker.Episode(1, 3)).last())
        assertEquals("Show.S01E03.1080p.WEB.mkv", success.target.file.name)
        assertEquals(emptyList(), success.failedAttempts)
    }

    @Test
    fun `a season pack missing an article of the requested episode is rejected and the next release tried`() = runTest {
        val last = pipeline().play(candidates(Release.RarPackGapInEpisode, Release.Pack), FilePicker.Episode(1, 3)).last()
        val success = assertIs<PipelineEvent.Succeeded>(last)
        assertEquals("Pack:1", success.target.candidate.id)
        assertEquals("Show.S01E03.1080p.WEB.mkv", success.target.file.name)
        val (failed, failure) = success.failedAttempts.single()
        assertEquals("RarPackGapInEpisode:0", failed.id)
        assertIs<AttemptFailure.MissingArticles>(failure)
    }

    @Test
    fun `a gap in a release's repair files doesn't reject it`() = runTest {
        listOf(FilePicker.Largest, FilePicker.Episode(1, 3)).forEach { picker ->
            val last = pipeline().play(candidates(Release.Par2Gap), picker).last()
            assertEquals("a8f3c1.mkv", assertIs<PipelineEvent.Succeeded>(last, "$picker").target.file.name)
        }
    }

    @Test
    fun `a season pack is checked on the picked episode's volumes only`() = runTest {
        val events = pipeline().play(candidates(Release.RarPack), FilePicker.Episode(1, 3)).toList()
        assertIs<PipelineEvent.Succeeded>(events.last())
        val volumes = listOf("part05", "part06").map { FakeFile("Show.S01.1080p.WEB.$it.rar") }
        assertEquals(volumes.flatMap { file -> (1..3).map { articleId("RarPack:0", file, it) } }, checked)
        // Progress counts the articles checked, not the whole pack's.
        val progress = events.filterIsInstance<PipelineEvent.Step>().filter { it.step == PipelineStep.CheckAvailability }.map { it.status }
        assertTrue(progress.any { it is StepStatus.Running && it.total == 6 }, "$progress")
    }

    @Test
    fun `a release named for the episode is checked before it is opened`() = runTest {
        val named = PlayCandidate("Missing:0", "Show.S01E03.1080p.WEB-GRP")
        val events = pipeline().play(listOf(named, PlayCandidate("Good:1", "Good 1")), FilePicker.Episode(1, 3)).toList()
        assertEquals("Good:1", assertIs<PipelineEvent.Succeeded>(events.last()).target.candidate.id)
        val failedStep = events.filterIsInstance<PipelineEvent.Step>().single { it.candidate == named && it.status is StepStatus.Failed }
        assertEquals(PipelineStep.CheckAvailability, failedStep.step)
        assertEquals(listOf(Release.Good), opened)
    }

    /**
     * The availability check stops at the first missing article, and says how many of the articles
     * it had answers for were missing: all of them for a release taken down, one of many for a gap.
     * The failure line says so; the log has the counts and how long the check took.
     */
    @Test
    fun `an incomplete release says how many of the checked articles were missing`() = runTest {
        val checks = listOf(
            Triple(309, 309, 309) to "Incomplete on your providers · 309 of 309 checked articles missing",
            Triple(1, 240, 309) to "Incomplete on your providers · 1 of 240 checked articles missing",
            Triple(1, 1, 309) to "Incomplete on your providers · 1 of 1 checked article missing",
        )
        checks.forEach { (counts, line) ->
            val (missing, answered, total) = counts
            val check = AvailabilityBackend { _, progress ->
                progress(answered, total)
                VerificationResult.MissingArticles("Missing article x", ArticleNotFoundException("x"), missing, answered, total)
            }
            val (last, log) = stderrOf { pipeline(availability = check).play(candidates(Release.Good)).last() }
            val failure = assertIs<PipelineEvent.Exhausted>(last).failedAttempts.single().second
            assertIs<AttemptFailure.MissingArticles>(failure)
            assertEquals(line, failure.text)
            assertTrue(
                Regex("""play_pipeline: Good 0 availability check in \d+ ms: $answered of $total articles answered, $missing missing""").containsMatchIn(log),
                log,
            )
        }
    }

    @Test
    fun `a complete release's check is logged with its counts`() = runTest {
        val (_, log) = stderrOf { pipeline().play(candidates(Release.Good)).last() }
        // The fake checks the film's ten articles; its sample is left out as an extra.
        assertTrue(Regex("""play_pipeline: Good 0 availability check in \d+ ms: 10 of 10 articles answered, none missing""").containsMatchIn(log), log)
    }

    /**
     * A step still running is logged every so often (#273: a play stalled, and nothing said where), as the open probe
     * stall on a large release would be. Once the step ends it says no more, and the play goes on and ends: the line's
     * watch is a child of the pipeline's flow, which waits for its children, so one left running would hold the play open.
     */
    @Test
    fun `a step still running says so every so often, and no more once it ends`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val slow = AvailabilityBackend { nzb, progress -> gate.await(); availability.verify(nzb, progress) }
        val pipeline = usenetPipeline(source, slow, preparer, probe, dispatcher = Dispatchers.Unconfined, stepWarnAfter = 150.milliseconds)
        val (last, log) = stderrOf {
            coroutineScope {
                val played = async { pipeline.play(candidates(Release.Good)).last() }
                // About three lines' worth.
                delay(500)
                gate.complete(Unit)
                val ended = withTimeout(5_000) { played.await() }
                // A watch left running would go on saying so.
                delay(600)
                ended
            }
        }
        assertIs<PipelineEvent.Succeeded>(last)
        val lines = log.lines().filter { "still running after" in it }
        assertTrue(lines.size in 2..4, "expected about three lines, got ${lines.size}:\n$log")
        lines.forEach { assertTrue(Regex("""play_pipeline: Good 0 CheckAvailability still running after \d+ s""").containsMatchIn(it), it) }
        // None once the step had ended.
        val stepDone = log.lines().indexOfFirst { Regex("""play_pipeline: Good 0 CheckAvailability \d+ ms""").containsMatchIn(it) }
        assertTrue(stepDone >= 0, log)
        assertTrue(log.lines().drop(stepDone).none { "still running after" in it }, "a line after the step ended:\n$log")
    }

    /**
     * Open waits for the user where it asks which file plays: nothing is stuck however long they take, so nothing says
     * it's still running meanwhile. Opening before the question is watched as any step is.
     */
    @Test
    fun `a step waiting for the user's choice of file doesn't say it's still running`() = runBlocking<Unit> {
        val opening = CompletableDeferred<Unit>()
        val slowOpen = NzbPreparer(
            object : PrepareBackend by prepareBackend {
                override suspend fun prepare(nzbBytes: ByteArray, password: String?): PrepareResult {
                    opening.await()
                    return prepareBackend.prepare(nzbBytes, password)
                }
            },
            dispatcher = Dispatchers.Unconfined,
        )
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        val chooser = FileChooser { _, _, choices ->
            asked.complete(Unit)
            answer.await()
            choices.single { it.name == "Show.2-6.mp4" }
        }
        val pipeline = usenetPipeline(source, availability, slowOpen, probe, dispatcher = Dispatchers.Unconfined, chooser = chooser, stepWarnAfter = 150.milliseconds)
        val (last, log) = stderrOf {
            coroutineScope {
                val played = async { pipeline.play(candidates(Release.UnnamedPack), FilePicker.Episode(1, 2)).last() }
                // About three lines' worth of opening, then as long again for the user's answer.
                delay(500)
                opening.complete(Unit)
                withTimeout(5_000) { asked.await() }
                delay(500)
                answer.complete(Unit)
                withTimeout(5_000) { played.await() }
            }
        }
        assertEquals("Show.2-6.mp4", assertIs<PipelineEvent.Succeeded>(last).target.file.name)
        val lines = log.lines()
        val question = lines.indexOfFirst { "asking which of" in it }
        val chosen = lines.indexOfFirst { "Show.2-6.mp4 chosen" in it }
        assertTrue(question in 0 until chosen, log)
        val opened = lines.take(question).filter { "Open still running after" in it }
        assertTrue(opened.size in 2..4, "expected about three lines while it opened, got ${opened.size}:\n$log")
        assertTrue(lines.subList(question, chosen).none { "still running after" in it }, "a line while the user chose:\n$log")
    }

    /**
     * While a provider is left out (its login rejected), an article missing on the others may be on
     * it: whichever step finds the gap, the release is incomplete on the providers in use (#172).
     */
    @Test
    fun `while a provider is left out, a gap is incomplete on the providers in use`() = runTest {
        val gapInStream = TrackProbe { ProbeResult.Failed(UsenetError.MissingArticle("430 No such article")) }
        listOf(true, false).forEach { leftOut ->
            fun play(release: Release, probe: TrackProbe = this@PlayPipelineTest.probe) =
                usenetPipeline(source, availability, preparer, probe, dispatcher = Dispatchers.Unconfined, providersLeftOut = { leftOut }).play(candidates(release))
            listOf(play(Release.Missing), play(Release.GapOnOpen), play(Release.Good, gapInStream)).forEach { events ->
                val failure = assertIs<PipelineEvent.Exhausted>(events.last()).failedAttempts.single().second
                assertEquals(leftOut, assertIs<AttemptFailure.MissingArticles>(failure).providersLeftOut, "$failure")
                assertEquals(if (leftOut) "Incomplete on the providers in use" else "Incomplete on your providers", failure.text.substringBefore(" ·"))
            }
        }
    }

    /**
     * A volume the NZB doesn't hold is on no provider, whichever is left out: the release is
     * incomplete on all of them, not maybe whole, so it counts against the release. One nothing
     * places can't be opened in order: the release is broken, not failing in a way nothing knows
     * (library issue NR-18).
     */
    @Test
    fun `a release whose NZB lacks a volume is incomplete on every provider, one it can't place broken`() = runTest {
        listOf(true, false).forEach { leftOut ->
            suspend fun failure(release: Release): AttemptFailure {
                val events = usenetPipeline(source, availability, preparer, probe, dispatcher = Dispatchers.Unconfined, providersLeftOut = { leftOut }).play(candidates(release))
                return assertIs<PipelineEvent.Exhausted>(events.last()).failedAttempts.single().second
            }
            val notInNzb = assertIs<AttemptFailure.MissingArticles>(failure(Release.VolumeNotInNzb))
            assertFalse(notInNzb.mayBeWhole, "$notInNzb")
            assertEquals("Incomplete on your providers", notInNzb.text)
            assertEquals(UsenetError.Corrupt(UNPLACED), assertIs<AttemptFailure.Failed>(failure(Release.VolumeUnplaced)).error)
        }
    }

    @Test
    fun `a gap found without the availability check keeps the plain line`() {
        // Opening the release or playing it finds a missing article without counting any.
        assertEquals("Incomplete on your providers", AttemptFailure.MissingArticles("430 No such article").text)
        assertEquals(
            "Incomplete on the providers that answered · a provider failed: AUTHINFO PASS failed: 481 Authentication failed",
            AttemptFailure.MissingArticles("No provider could answer", unanswered = UsenetError.AuthFailed("AUTHINFO PASS failed: 481 Authentication failed")).text,
        )
    }

    /**
     * A release remembered as missing on every provider in use is skipped before its NZB is fetched: no grab, and no
     * attempt used up, so the next release is tried in its place. The play sheet says why, and so does the log.
     */
    @Test
    fun `a release known to be missing is skipped without a fetch and doesn't use up an attempt`() = runTest {
        val tried = FakeTried(known = mapOf("Missing:0" to KNOWN, "Missing:2" to KNOWN))
        val (events, log) = stderrOf {
            pipeline(maxAttempts = 2, tried = tried).play(candidates(Release.Missing, Release.Missing, Release.Missing, Release.Good)).toList()
        }
        val success = assertIs<PipelineEvent.Succeeded>(events.last())
        assertEquals("Good:3", success.target.candidate.id)
        assertEquals(listOf("Missing:1"), success.failedAttempts.map { it.first.id })
        assertEquals(listOf("Missing:1", "Good:3"), fetched)
        assertEquals(listOf("Missing:0" to KNOWN, "Missing:2" to KNOWN), events.filterIsInstance<PipelineEvent.Skipped>().map { it.candidate.id to it.reason })
        assertEquals(listOf(1 to 2, 2 to 2), events.filterIsInstance<PipelineEvent.AttemptStarted>().map { it.attempt to it.of })
        assertTrue("play_pipeline: Missing 0 skipped without a fetch: $KNOWN" in log, log)

        // Every one known: nothing is fetched, nothing failed, nothing is left to try.
        fetched.clear()
        val exhausted = assertIs<PipelineEvent.Exhausted>(pipeline(tried = FakeTried(mapOf("Missing:0" to KNOWN, "Good:1" to KNOWN))).play(candidates(Release.Missing, Release.Good)).last())
        assertEquals(emptyList(), fetched)
        assertEquals(emptyList(), exhausted.failedAttempts)
        assertEquals(0 to 0, exhausted.notTried to exhausted.skipped)
    }

    /**
     * "Release 1 of 3" counts the releases that will be tried, not those known to be missing, which are skipped when their
     * turn comes; nor does "not tried" count them when the attempts run out, as no play would try them. Each is looked up
     * once, and only as far as the count needs.
     */
    @Test
    fun `releases known to be missing aren't counted as releases to try`() = runTest {
        val tried = FakeTried(known = mapOf("Missing:1" to KNOWN, "Missing:2" to KNOWN))
        val events = pipeline(maxAttempts = 3, tried = tried).play(candidates(Release.Missing, Release.Missing, Release.Missing)).toList()
        assertEquals(listOf(1 to 1), events.filterIsInstance<PipelineEvent.AttemptStarted>().map { it.attempt to it.of })
        assertEquals(0, assertIs<PipelineEvent.Exhausted>(events.last()).notTried)
        assertEquals(listOf("Missing:0", "Missing:1", "Missing:2"), tried.lookedUp)

        val ranOut = FakeTried(known = mapOf("Missing:1" to KNOWN))
        val exhausted = assertIs<PipelineEvent.Exhausted>(
            pipeline(maxAttempts = 1, tried = ranOut).play(candidates(Release.Missing, Release.Missing, Release.Good, Release.Good)).last(),
        )
        assertEquals(2, exhausted.notTried)
        assertEquals(listOf("Missing:0", "Missing:1", "Good:2", "Good:3"), ranOut.lookedUp)

        // Two attempts: the count looks no further than the second release it would try.
        val ahead = FakeTried(known = mapOf("Missing:1" to KNOWN))
        val started = pipeline(maxAttempts = 2, tried = ahead).play(candidates(Release.Good, Release.Missing, Release.Good, Release.Good)).toList()
        assertEquals(listOf(1 to 2), started.filterIsInstance<PipelineEvent.AttemptStarted>().map { it.attempt to it.of })
        assertEquals(listOf("Good:0", "Missing:1", "Good:2"), ahead.lookedUp)
    }

    /**
     * A gap the availability check finds is remembered as on the providers in use, also while one is left out (it wasn't
     * asked: it is tried when it is back). One found opening the release or by its probe is remembered as on the providers
     * fetched from, which may not be all of those in use: nzb-streamer passes over one whose circuit breaker is open without
     * asking it. So is a file the NZB seems to lack, not as on any provider: it may be one the NZB holds whose first article
     * the providers fetched from lack (library issue N72), which another provider may have.
     */
    @Test
    fun `a release found missing is remembered as on the providers in use when checked, on those fetched from when fetched`() = runTest {
        val gapInStream = TrackProbe { target -> if (target.candidate.id == "Good:3") ProbeResult.Failed(UsenetError.MissingArticle("430 No such article")) else ProbeResult.Playable() }
        listOf(false, true).forEach { leftOut ->
            val tried = FakeTried()
            val pipeline = usenetPipeline(
                source, availability, preparer, gapInStream, maxAttempts = 5, dispatcher = Dispatchers.Unconfined, providersLeftOut = { leftOut }, tried = tried,
            )
            assertIs<PipelineEvent.Succeeded>(pipeline.play(candidates(Release.Missing, Release.GapOnOpen, Release.VolumeNotInNzb, Release.Good, Release.Good)).last())
            assertEquals(
                listOf(
                    "Missing:0" to MissingOn.ProvidersInUse, "GapOnOpen:1" to MissingOn.ProvidersFetchedFrom,
                    "VolumeNotInNzb:2" to MissingOn.ProvidersFetchedFrom, "Good:3" to MissingOn.ProvidersFetchedFrom,
                ),
                tried.recorded,
                "left out: $leftOut",
            )
        }
    }

    /**
     * Only a gap every provider that answered has is remembered: not a provider failing where the others lack an article
     * (it may have it), a timeout, a failed grab, a passworded release, one without playable audio or video or that
     * can't be streamed, or one broken otherwise.
     */
    @Test
    fun `what isn't the providers lacking a release's articles isn't remembered`() = runTest {
        val tried = FakeTried()
        val pipeline = usenetPipeline(source, availability, preparer, probe, maxAttempts = 10, dispatcher = Dispatchers.Unconfined, providerFallback = { true }, tried = tried)
        val releases = arrayOf(
            Release.ProviderDown, Release.ProviderDownOnOpen, Release.TimesOut, Release.Unfetchable, Release.Passworded,
            Release.Silent, Release.CompressedOnly, Release.VolumeUnplaced, Release.NoVideoDecoder, Release.Good,
        )
        assertEquals("Good:9", assertIs<PipelineEvent.Succeeded>(pipeline.play(candidates(*releases)).last()).target.candidate.id)
        assertEquals(emptyList(), tried.recorded)
    }

    /**
     * What is remembered is the release's: a gap in the one episode of a season pack (in its volumes, or the episode left
     * out) isn't, as its other episodes may be whole. Nor is one in a release of two episodes whose wanted one prepare left
     * out, though the other is all it kept: that one may be whole. Nor, while prepare left one out, a gap in the one it
     * kept. A release of the one episode is, as every play of it plays that, and so is one of a single video under a pack's
     * name, beside what prepare leaves out that holds no video (a sample, a subtitle).
     */
    @Test
    fun `a gap in one episode of a season pack isn't remembered against the pack`() = runTest {
        val tried = FakeTried()
        listOf(Release.RarPackGapInEpisode, Release.PackEpisodeGone).forEach { pack ->
            assertIs<PipelineEvent.Exhausted>(pipeline(tried = tried).play(candidates(pack), FilePicker.Episode(1, 3)).last())
        }
        val pair = listOf(PlayCandidate("EpisodePairFirstGone:0", "Show.S01E01-E02.1080p.WEB-GRP"))
        val leftOut = assertIs<PipelineEvent.Exhausted>(pipeline(tried = tried).play(pair, FilePicker.Episode(1, 1)).last())
        assertIs<AttemptFailure.MissingArticles>(leftOut.failedAttempts.single().second)
        assertIs<PipelineEvent.Succeeded>(pipeline(tried = tried).play(pair, FilePicker.Episode(1, 2)).last())
        val gapBesideLeftOut = usenetPipeline(
            source, { _, _ -> VerificationResult.MissingArticles("Missing article <x>", ArticleNotFoundException("<x>")) }, preparer, probe,
            dispatcher = Dispatchers.Unconfined, tried = tried,
        )
        assertIs<PipelineEvent.Exhausted>(gapBesideLeftOut.play(pair, FilePicker.Episode(1, 2)).last())
        assertEquals(emptyList(), tried.recorded)

        val single = listOf(PlayCandidate("Missing:0", "Show.S01E03.1080p.WEB-GRP"))
        assertIs<PipelineEvent.Exhausted>(pipeline(tried = tried).play(single, FilePicker.Episode(1, 3)).last())
        assertIs<PipelineEvent.Exhausted>(pipeline(tried = tried).play(listOf(PlayCandidate("LoneVideoGap:1", packTitle)), FilePicker.Episode(1, 3)).last())
        assertEquals(listOf("Missing:0" to MissingOn.ProvidersInUse, "LoneVideoGap:1" to MissingOn.ProvidersInUse), tried.recorded)
    }

    /**
     * A release that passes every step is told to [TriedReleases.found]: whatever was remembered of it for the providers in
     * use is wrong (a provider's bad day, a post still propagating), as when the user chose a release marked missing and it
     * played. The releases that failed before it aren't, nor is anything when none plays.
     */
    @Test
    fun `a release that plays is told as found, so what was remembered of it is forgotten`() = runTest {
        val tried = FakeTried()
        assertIs<PipelineEvent.Succeeded>(pipeline(tried = tried).play(candidates(Release.Missing, Release.Good)).last())
        assertEquals(listOf("Good:1"), tried.found)
        assertEquals(listOf("Missing:0" to MissingOn.ProvidersInUse), tried.recorded)

        val none = FakeTried()
        assertIs<PipelineEvent.Exhausted>(pipeline(tried = none).play(candidates(Release.Missing, Release.Passworded)).last())
        assertEquals(emptyList(), none.found)
    }

    /** What is remembered is a help, never in the way: failing to read it tries the release, failing to write it goes on. */
    @Test
    fun `remembering failing doesn't fail the play`() = runTest {
        val failing = object : TriedReleases {
            override suspend fun knownMissing(candidate: PlayCandidate): String? = throw java.io.IOException("disk I/O error")
            override suspend fun missing(candidate: PlayCandidate, on: MissingOn): Unit = throw java.io.IOException("disk I/O error")
            override suspend fun found(candidate: PlayCandidate): Unit = throw java.io.IOException("disk I/O error")
            override suspend fun passwordNeeded(candidate: PlayCandidate): Unit = throw java.io.IOException("disk I/O error")
            override suspend fun discCopy(candidate: PlayCandidate): Unit = throw java.io.IOException("disk I/O error")
        }
        val success = assertIs<PipelineEvent.Succeeded>(
            pipeline(maxAttempts = 4, tried = failing).play(candidates(Release.Missing, Release.Passworded, Release.BluRayDisc, Release.Good)).last(),
        )
        assertEquals("Good:3", success.target.candidate.id)
        assertEquals(listOf("Missing:0", "Passworded:1", "BluRayDisc:2", "Good:3"), fetched)
    }

    /** The release that was playing as the player's prompt left it for its missing parts: the way back if nothing else plays (W3). */
    private val wasPlaying = PlayCandidate("Missing:9", "Playing 9", wasPlaying = true)

    /**
     * The way back (L9): tried after every other release has failed, from where it was, without the availability check,
     * which would find the parts the player was skipping; the sheet's check step says so.
     */
    @Test
    fun `the release that was playing is tried last, without the availability check`() = runTest {
        val events = pipeline().play(candidates(Release.Silent, Release.Unfetchable), last = wasPlaying).toList()
        val success = assertIs<PipelineEvent.Succeeded>(events.last())
        assertEquals("Missing:9", success.target.candidate.id)
        assertEquals(listOf("Silent:0", "Unfetchable:1"), success.failedAttempts.map { it.first.id })
        assertEquals(listOf(Triple(1, 3, "Silent:0"), Triple(2, 3, "Unfetchable:1"), Triple(3, 3, "Missing:9")), events.filterIsInstance<PipelineEvent.AttemptStarted>().map { Triple(it.attempt, it.of, it.candidate.id) })
        val check = events.filterIsInstance<PipelineEvent.Step>().single { it.candidate == wasPlaying && it.step == PipelineStep.CheckAvailability }
        assertEquals(StepStatus.Done(0, PlayPipeline.SKIPPED_PLAYING), check.status)
        assertTrue(checked.none { it.contains("Missing:9") }, "the way back was checked: $checked")
    }

    /** The others share the attempts but one: the way back always has its own. */
    @Test
    fun `the way back keeps its attempt when the others would use them all`() = runTest {
        val events = pipeline(maxAttempts = 3).play(candidates(Release.Silent, Release.Unfetchable, Release.Good), last = wasPlaying).toList()
        assertEquals("Missing:9", assertIs<PipelineEvent.Succeeded>(events.last()).target.candidate.id)
        assertEquals(listOf("Silent:0", "Unfetchable:1", "Missing:9"), fetched)
        // A release that plays before it leaves it untried.
        fetched.clear()
        val first = pipeline().play(candidates(Release.Good), last = wasPlaying).toList()
        assertEquals("Good:0", assertIs<PipelineEvent.Succeeded>(first.last()).target.candidate.id)
        assertEquals(listOf(1 to 2), first.filterIsInstance<PipelineEvent.AttemptStarted>().map { it.attempt to it.of })
        assertEquals(listOf("Good:0"), fetched)
    }

    /**
     * Releases to try set to 1 still tries one other before the way back, which costs no grab: else Try another release, or
     * a smaller one, would go straight back to the release that was playing.
     */
    @Test
    fun `with one attempt to make one other release is tried before the way back`() = runTest {
        val events = pipeline(maxAttempts = 1).play(candidates(Release.Unfetchable, Release.Good), last = wasPlaying).toList()
        assertEquals(listOf("Unfetchable:0", "Missing:9"), fetched)
        assertEquals(listOf(1 to 2, 2 to 2), events.filterIsInstance<PipelineEvent.AttemptStarted>().map { it.attempt to it.of })
        assertEquals("Missing:9", assertIs<PipelineEvent.Succeeded>(events.last()).target.candidate.id)
        // Without a way back, one attempt is one release, as before.
        fetched.clear()
        val alone = pipeline(maxAttempts = 1).play(candidates(Release.Unfetchable, Release.Good)).toList()
        assertEquals(listOf("Unfetchable:0"), fetched)
        assertIs<PipelineEvent.Exhausted>(alone.last())
    }

    /** It isn't skipped as known to be missing, nor for its indexer refusing grabs: it was just playing, from the NZB the store keeps. */
    @Test
    fun `the way back is tried whatever is remembered of it or its indexer`() = runTest {
        val tried = FakeTried(known = mapOf(wasPlaying.id to KNOWN))
        val events = pipeline(tried = tried).play(fromIndexers("A" to Release.GrabLimit, "A" to Release.Good), last = wasPlaying.copy(indexer = "A")).toList()
        assertEquals("Missing:9", assertIs<PipelineEvent.Succeeded>(events.last()).target.candidate.id)
    }

    /** A failure that would fail every release (the provider down) fails the way back too: it isn't tried. */
    @Test
    fun `the way back isn't tried after a failure that would fail it too`() = runTest {
        val events = pipeline().play(candidates(Release.ProviderDown), last = wasPlaying).toList()
        assertIs<PipelineEvent.Exhausted>(events.last())
        assertEquals(listOf("ProviderDown:0"), fetched)
    }

    /** What [block] logs: slf4j-simple writes to System.err as it is at the time. */
    private suspend fun <T> stderrOf(block: suspend () -> T): Pair<T, String> {
        val original = System.err
        val captured = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(captured, true))
        try {
            return block() to captured.toString()
        } finally {
            System.setErr(original)
        }
    }

    private companion object {
        /** The source of a torrent's releases, which [Cached] plays. */
        const val TORRENT = "torrent"
        const val SEGMENT_SIZE = 1_000L
        const val GONE = "gone:"
        const val TIMED_OUT = "Segment <seg> timed out after 5000ms on pool[0] (news.example.net:563)"
        const val LOST = "Connection to news.example.net:563 failed: Connection reset"
        const val UNREACHABLE = "Could not connect to news.example.net:563: Connection refused"
        const val NOT_IN_NZB = "Archive volume 'Movie.2020.part2.rar', which the PAR2 index names, isn't in the NZB"
        const val KNOWN = "Skipped: missing on your providers (checked 3 days ago)"
        const val INDEXER_PASSWORD = "salt-and-signal"
        const val OWN_PASSWORD = "copper-crown"
        const val UNREADABLE_MKV = "The player can't read this MKV file"
        const val DISC = "Harbor.Lights.2024.2160p.UHD.BluRay.HEVC-GRP"
        const val DISC_KNOWN = "Skipped: a disc copy (checked 3 days ago)"
        const val UNPLACED = "Archive volume 'x7f3k9q2.avi' was posted under a name that doesn't give its place in its archive, and no PAR2 index names it"
    }

    /** A phone shows a TV's failed step in the TV's own words (L37): those words, and nothing stopped by them here. */
    @Test
    fun `a failure on another device is told in its words, and stops nothing`() {
        val failure = AttemptFailure.Elsewhere("Incomplete on the TV's providers · 41 of 256 checked articles missing")
        assertEquals("Incomplete on the TV's providers · 41 of 256 checked articles missing", failure.text)
        assertFalse(failure.route != null || failure.blocksIndexer)
    }
}
