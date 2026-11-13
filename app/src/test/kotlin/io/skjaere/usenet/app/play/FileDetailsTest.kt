package io.skjaere.usenet.app.play

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.nzb.NzbFile
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.FileListing
import io.skjaere.usenet.core.ListedFile
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.core.UsenetTarget
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.IndexerRecord
import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.data.TorrentIndexerRecord
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.player.DolbyVisionRecord
import io.skjaere.usenet.player.Fact
import io.skjaere.usenet.player.MatroskaVideoFacts
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.releases.AudioCodec
import io.skjaere.usenet.releases.Hdr
import io.skjaere.usenet.releases.MediaInfo
import io.skjaere.usenet.releases.VideoCodec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What File details list ([FileDetails]): the release and its file, then File, Video, On this device and Source in the
 * approved mockups' order and words (FdPhoneFull, FdTvFull), each fact left out where it isn't known, nothing about
 * audio or subtitles, and a note where the release's name claims what the file states otherwise (W4). Where it came
 * from ([FileOrigin]) is the launch's, so a Resume reads as a play from search does, and never a secret: a screenshot
 * passes the panel on. Releases, indexers and providers are invented.
 */
class FileDetailsTest {
    /** Where a play would keep what plays: nothing here keeps it. */
    @TempDir
    lateinit var nzbDir: File

    private val saved = Locale.getDefault()

    // Sizes, rates and lengths follow the default locale's digits; pin one so the decimal separator is predictable.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    private val name = "Harbor.Lights.S02E05.The.Long.Tide.2160p.WEB-DL.DV.HDR10.DDP5.1.Atmos.H.265-GRP"
    private val file = "Harbor.Lights.S02E05.2160p.WEB-DL.DV.HDR10.H.265-GRP.mkv"
    private val nzbId = "0dd5e7"
    private val release = SavedRelease("my-indexer", "hl-s02e05", name, null, 0, nzbId, file, 8_424_000_000)

    private val indexers = listOf(IndexerRecord("my-indexer", "My indexer", "https://indexer.example.com"))
    private val providers = listOf(
        ProviderConfig("news", "news.example.net", "news.example.net", 563, "user", "pass", true),
        ProviderConfig("fill", "", "fill.example.org", 563, "user2", "pass2", true, priority = 1),
    )

    private fun launch(saved: SavedRelease = release) = PlayerLaunch(
        uri = UsenetUris.build(saved.nzbId, saved.fileIndex, saved.fileName, saved.fileSize), title = "Harbor Lights", subtitle = "S02E05 · The Long Tide",
        watchKey = "tv:900002:2:5", watch = WatchTarget(WatchKind.Episode, "Harbor Lights S02E05", showTmdbId = 900002, season = 2, episode = 5), release = saved,
    )

    private fun origin(
        launch: PlayerLaunch? = launch(),
        uri: String = launch?.uri ?: UsenetUris.build(nzbId, 0, file, 8_424_000_000),
        listing: FileListing<*>? = null,
        indexers: List<IndexerRecord>? = this.indexers,
        providers: List<ProviderConfig> = this.providers,
        connections: Int? = 6,
    ) = FileOrigin.of(launch, uri, listing, indexers?.let { SourceNames(it) }, providers, connections)

    private fun color(space: Int, transfer: Int, range: Int, bits: Int, staticInfo: ByteArray? = null) =
        ColorInfo.Builder().setColorSpace(space).setColorTransfer(transfer).setColorRange(range).setLumaBitdepth(bits).setChromaBitdepth(bits)
            .setHdrStaticInfo(staticInfo).build()

    /** CTA-861.3's static metadata as Media3 lays it out: the primaries, the mastering display's peak and floor, MaxCLL, MaxFALL. */
    private fun staticInfo(peak: Int, maxCll: Int): ByteArray = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(0)
        listOf(34_000, 16_000, 13_250, 34_500, 7_500, 3_000, 15_635, 16_450, peak, 0, maxCll, 0).forEach { putShort(it.toShort()) }
    }.array()

    private val pq = color(C.COLOR_SPACE_BT2020, C.COLOR_TRANSFER_ST2084, C.COLOR_RANGE_LIMITED, 10, staticInfo(1000, 812))
    private val sdr = color(C.COLOR_SPACE_BT709, C.COLOR_TRANSFER_SDR, C.COLOR_RANGE_LIMITED, 8)

    private fun video(
        mime: String = MimeTypes.VIDEO_H265,
        codecs: String? = "hvc1.2.4.H153.90",
        width: Int = 3840,
        height: Int = 2160,
        color: ColorInfo? = pq,
        facts: MatroskaVideoFacts? = MatroskaVideoFacts(frameDurationNs = 41_708_333),
    ): Format = Format.Builder().setSampleMimeType(mime).setCodecs(codecs).setContainerMimeType(MimeTypes.VIDEO_MATROSKA).setWidth(width).setHeight(height)
        .setColorInfo(color).setMetadata(facts?.let { Metadata(it) }).build()

    /** Dolby Vision profile 8.1 at level 6 over HEVC Main 10, as the player's Matroska extractor reads it. */
    private val dolbyVision = video(
        MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06",
        facts = MatroskaVideoFacts(41_708_333, DolbyVisionRecord(compatibility = 1, enhancementLayer = false, baseLayer = true), "hvc1.2.4.H153.90"),
    )

    private val shield = FileDetails.Playing(
        tracksKnown = true, video = dolbyVision, durationMs = 48 * 60_000L,
        decoder = Fact("OMX.Nvidia.DOVI.decode", "hardware · Dolby Vision"), display = Fact("3840 × 2160 · 59.94 Hz", "can show Dolby Vision, HDR10, HLG"),
    )

    /** The sections as "label: value (note) [the name says]" lines, a section reading the file as one line. */
    private fun rows(details: FileDetails): List<Pair<String, List<String>>> = details.sections.map { s ->
        s.title to if (s.reading) listOf("Reading the file…") else s.rows.map { r ->
            "${r.label}: ${r.value}" + r.note?.let { " ($it)" }.orEmpty() + r.nameSays.joinToString("") { " [$it]" }
        }
    }

    private fun row(details: FileDetails, label: String) = details.sections.flatMap { it.rows }.singleOrNull { it.label == label }

    /** Every word the panel would show. */
    private fun words(details: FileDetails): String =
        (listOfNotNull(details.release, details.fileName) + details.sections.flatMap { s -> listOf(s.title) + s.rows.flatMap { r -> listOfNotNull(r.label, r.value, r.note) + r.nameSays } })
            .joinToString("\n")

    @Test
    fun `the whole panel reads as the mockup, in its order`() {
        val details = FileDetails(origin(), shield)
        assertEquals(name, details.release)
        assertEquals(file, details.fileName)
        assertEquals(
            listOf(
                "File" to listOf("Container: Matroska (MKV)", "Size: 8.4 GB", "Average bitrate: 23.4 Mbps (the file’s size over its length)", "Length: 48:00"),
                "Video" to listOf(
                    "Format: HEVC Main 10 (level 5.1, high tier)",
                    "Resolution: 3840 × 2160 · 2160p · 16:9",
                    "Frame rate: 23.976 fps",
                    "Bit depth: 10-bit",
                    "HDR: Dolby Vision, HDR10 (Dolby Vision profile 8.1, level 6 · HDR10 base layer)",
                    "Colour: BT.2020 · PQ (limited range)",
                ),
                "On this device" to listOf("Decoder: OMX.Nvidia.DOVI.decode (hardware · Dolby Vision)", "Display: 3840 × 2160 · 59.94 Hz (can show Dolby Vision, HDR10, HLG)"),
                "Source" to listOf("Indexer: My indexer", "Providers: news.example.net, then fill.example.org (in this order · 6 connections)"),
            ),
            rows(details),
        )
        // The HDR row's chips, Dolby Vision first.
        assertEquals(listOf(Hdr.DolbyVision, Hdr.Hdr10), row(details, "HDR")?.hdr)
    }

    @Test
    fun `before the tracks are known the release, the file and the source show, and the video and the device are being read`() {
        val details = FileDetails(origin(), FileDetails.Playing())
        assertEquals(name, details.release)
        assertEquals(
            listOf(
                // The container from the file's extension.
                "File" to listOf("Container: Matroska (MKV)", "Size: 8.4 GB"),
                "Video" to listOf("Reading the file…"),
                "On this device" to listOf("Reading the file…"),
                "Source" to listOf("Indexer: My indexer", "Providers: news.example.net, then fill.example.org (in this order · 6 connections)"),
            ),
            rows(details),
        )
    }

    /**
     * Playback stopped before the player read the tracks (the first articles missing everywhere): nothing more will be
     * read, so no section says it is being read. The video is left out, as unknown; the display, read as the panel opened,
     * shows.
     */
    @Test
    fun `stopped before the tracks were known, nothing is being read any more`() {
        val display = Fact("1080 × 2400 · 60 Hz", "can't show HDR")
        assertEquals(
            listOf(
                "File" to listOf("Container: Matroska (MKV)", "Size: 8.4 GB"),
                "On this device" to listOf("Display: 1080 × 2400 · 60 Hz (can't show HDR)"),
                "Source" to listOf("Indexer: My indexer", "Providers: news.example.net, then fill.example.org (in this order · 6 connections)"),
            ),
            rows(FileDetails(origin(), FileDetails.Playing(stopped = true, display = display))),
        )
        // What was read before it stopped stays.
        assertEquals(rows(FileDetails(origin(), shield)), rows(FileDetails(origin(), shield.copy(stopped = true))))
    }

    @Test
    fun `a fact not known is left out, never said to be unknown`() {
        val bare = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        val origin = FileOrigin(release = null, fileName = "clip", fileSize = null, indexer = null, providers = emptyList(), connections = null)
        val details = FileDetails(origin, FileDetails.Playing(tracksKnown = true, video = bare))
        assertEquals(
            listOf(
                "Video" to listOf("Format: H.264", "HDR: None"),
            ),
            rows(details),
        )
        assertTrue("unknown" !in words(details).lowercase(), words(details))
    }

    /** The container is what the extractor read, before the file's name: an obfuscated name has no extension, or a wrong one. */
    @Test
    fun `the container is the one the player read, else the one the name's extension says`() {
        fun container(fileName: String, video: Format?) =
            row(FileDetails(FileOrigin(null, fileName, null, null, emptyList(), null), FileDetails.Playing(tracksKnown = true, video = video)), "Container")?.value
        assertEquals("Matroska (MKV)", container("a8f3e2b1", video()))
        assertEquals("Matroska (MKV)", container("Copper.Crown.2021.1080p.WEB-DL.H.264-GRP.mp4", video()))
        assertEquals("MP4", container("Copper.Crown.2021.1080p.WEB-DL.H.264-GRP.mp4", null))
        assertNull(container("a8f3e2b1", null))
    }

    /** The audio and subtitles sheet has those; the name's audio claims aren't set against anything either. */
    @Test
    fun `no audio or subtitle rows, whatever the name says of them`() {
        val details = FileDetails(origin(), shield)
        val shown = details.sections.flatMap { s -> listOf(s.title) + s.rows.map { it.label } }
        listOf("audio", "subtitle", "atmos", "dd+", "channels", "language").forEach { word ->
            assertTrue(shown.none { word in it.lowercase() }, "$word in $shown")
        }
        assertTrue(details.sections.flatMap { it.rows }.all { it.nameSays.isEmpty() }, "the name agrees with the file: ${rows(details)}")
    }

    @Test
    fun `the average bitrate is the file's size over its length, one decimal under 100 Mbps, and left out without either`() {
        fun bitrate(size: Long?, durationMs: Long?): String? {
            val origin = FileOrigin(release = null, fileName = "a.mkv", fileSize = size, indexer = null, providers = emptyList(), connections = null)
            return row(FileDetails(origin, FileDetails.Playing(tracksKnown = true, durationMs = durationMs)), "Average bitrate")?.value
        }
        // tracks.mkv, 60 s; chapters.mkv, 6 min.
        assertEquals("0.1 Mbps", bitrate(765_993, 60_000))
        assertEquals("< 0.1 Mbps", bitrate(689_982, 360_000))
        // A 4K remux: 60 GB over 2 h 10 min; another of 100 GB over 2 h.
        assertEquals("61.5 Mbps", bitrate(60_000_000_000, 130 * 60_000L))
        assertEquals("111 Mbps", bitrate(100_000_000_000, 120 * 60_000L))
        assertNull(bitrate(765_993, null))
        assertNull(bitrate(765_993, 0))
        assertNull(bitrate(null, 60_000))
    }

    @Test
    fun `the length reads as the player's controls show it`() {
        fun length(ms: Long) = row(FileDetails(origin(), FileDetails.Playing(tracksKnown = true, durationMs = ms)), "Length")?.value
        assertEquals("48:00", length(48 * 60_000L))
        assertEquals("1:54:10", length((114 * 60 + 10) * 1000L))
        assertEquals("0:59", length(59_400))
    }

    @Test
    fun `sizes, rates and lengths are in the locale's digits`() {
        Locale.setDefault(Locale.GERMANY)
        val details = FileDetails(origin(), shield)
        assertEquals("8,4 GB", row(details, "Size")?.value)
        assertEquals("23,4 Mbps", row(details, "Average bitrate")?.value)
        assertEquals("23,976 fps", row(details, "Frame rate")?.value)
    }

    @Test
    fun `the indexer by its name, a disabled one too, and one since removed said so`() {
        fun indexer(list: List<IndexerRecord>?) = row(FileDetails(origin(indexers = list)), "Indexer")?.value
        assertEquals("My indexer", indexer(indexers))
        assertEquals("My indexer", indexer(listOf(indexers.single().copy(enabled = false))))
        assertEquals("An indexer since removed", indexer(listOf(IndexerRecord("other", "Another indexer", "https://other.example.com"))))
        // Until the settings are read the row waits.
        assertNull(indexer(null))
    }

    @Test
    fun `an NZB opened as a file is named by its title and said to be opened from a file`() {
        val nzb = prepared("Copper.Crown.2021.1080p.WEB-DL.H.264-GRP", listOf("Copper.Crown.2021.1080p.WEB-DL.H.264-GRP.mkv" to 5_200_000_000L))
        val details = FileDetails(origin(launch = null, uri = UsenetUris.of(nzbId, nzb.candidates.single()), listing = nzb, indexers = null))
        assertEquals("Copper.Crown.2021.1080p.WEB-DL.H.264-GRP", details.release)
        assertEquals("Copper.Crown.2021.1080p.WEB-DL.H.264-GRP.mkv", details.fileName)
        assertEquals("Opened from an NZB file", row(details, "Indexer")?.value)
        assertEquals("5.2 GB", row(details, "Size")?.value)
    }

    /** A launch saved before names were kept has only the file's position: the URI's name and size, else the NZB's file. */
    @Test
    fun `a launch from before file names were kept takes the file from the URI, else from the NZB`() {
        val old = release.copy(fileName = null, fileSize = null)
        val fromUri = FileDetails(origin(launch(old).copy(uri = UsenetUris.build(nzbId, 0, file, 8_424_000_000))))
        assertEquals(file, fromUri.fileName)
        assertEquals("8.4 GB", row(fromUri, "Size")?.value)
        val nzb = prepared(name, listOf("sample.mkv" to 50_000_000L, file to 8_424_000_000L))
        val fromNzb = FileDetails(origin(launch(old.copy(fileIndex = 1)).copy(uri = UsenetUris.build(nzbId, 1)), listing = nzb))
        assertEquals(file, fromNzb.fileName)
        assertEquals("8.4 GB", row(fromNzb, "Size")?.value)
        // Neither: the file's rows that need it are left out.
        val neither = FileDetails(origin(launch(old).copy(uri = UsenetUris.build(nzbId, 0))))
        assertNull(neither.fileName)
        assertNull(row(neither, "Size"))
    }

    /**
     * A play of another source's file: its URI names the listing the app keeps and the file's place there, never a name
     * ([PlayUris]), so the file is the launch's, else that listing's at the launch's place; the rest as any play's.
     */
    @Test
    fun `another source's play takes its file from the launch, else from its listing`() {
        val uri = PlayUris.service("l7f3a9", 1)
        val fromLaunch = FileDetails(origin(launch().copy(uri = uri)))
        assertEquals(name, fromLaunch.release)
        assertEquals(file, fromLaunch.fileName)
        assertEquals("8.4 GB", row(fromLaunch, "Size")?.value)
        assertEquals("My indexer", row(fromLaunch, "Indexer")?.value)
        val old = release.copy(fileIndex = 1, fileName = null, fileSize = null)
        val listing = Listing("l7f3a9", name, listOf(Listed(0, "Sample/sample.mkv", 50_000_000), Listed(1, "Harbor Lights S02/$file", 8_424_000_000)))
        val fromListing = FileDetails(origin(launch(old).copy(uri = uri), listing = listing))
        assertEquals(file, fromListing.fileName)
        assertEquals("8.4 GB", row(fromListing, "Size")?.value)
        // Without it, nothing is made up.
        val neither = FileDetails(origin(launch(old).copy(uri = uri)))
        assertNull(neither.fileName)
        assertNull(row(neither, "Size"))
    }

    /**
     * A debrid play (L207, DbPhoneFileDetails): the indexer, named from the torrent indexers, then the service the release
     * played from, in place of the providers and their connections; never the link, the torrent's hash or anything of the
     * account. One signed out of since, or a torrent indexer since removed, is said so.
     */
    @Test
    fun `a debrid play names its torrent indexer, then its service in place of the providers`() {
        val hash = "2f5a3c9e1b7d4f6a8c0e2b4d6f8a1c3e5b7d9f0a"
        val torrent = SavedRelease("prowlarr-1", "btih:$hash", name, null, 1, "btih:$hash", file, 8_424_000_000, source = "torrent")
        val launch = launch(torrent).copy(uri = PlayUris.service("0f3e9a1c5b7d2e4f6a8b0c1d2e3f4a5b", 1))
        val names = SourceNames(
            // A Usenet indexer that shares the torrent indexer's id is not the one.
            listOf(IndexerRecord("prowlarr-1", "Indexer A", "https://indexer.example.com")),
            listOf(TorrentIndexerRecord("prowlarr-1", "Home Prowlarr", "https://prowlarr.example.org/1")),
            listOf(ServiceAccount("acct-7f3e", "TorBox", accountName = "quay.viewer@example.org"), ServiceAccount("acct-91c2", "Premiumize", accountName = "customer 900000042")),
        )
        fun of(names: SourceNames?) = FileOrigin.of(launch, launch.uri, null, names, providers, 6, account = "acct-7f3e")
        val details = FileDetails(of(names))
        assertEquals(listOf("Indexer: Home Prowlarr", "Service: TorBox (a cached torrent, streamed from the service)"), rows(details).toMap()["Source"])
        assertNull(row(details, "Providers"))
        assertEquals("A service since signed out of", row(FileDetails(of(names.copy(services = emptyList()))), "Service")?.value)
        assertEquals("An indexer since removed", row(FileDetails(of(names.copy(torrentIndexers = emptyList()))), "Indexer")?.value)
        // Until the settings are read both rows wait.
        assertNull(row(FileDetails(of(null)), "Service"))
        assertNull(row(FileDetails(of(null)), "Indexer"))
        listOf(hash, "acct-7f3e", "quay.viewer", "0f3e9a1c", "prowlarr.example.org", "900000042").forEach { secret ->
            assertFalse(secret in of(names).toString(), "\"$secret\" in ${of(names)}")
            assertFalse(secret in words(details), "\"$secret\" in the panel")
        }
    }

    /** A file a source lists by its path. */
    private data class Listed(override val index: Int, override val path: String, override val size: Long) : ListedFile

    /** A source's files, not an NZB's. */
    private class Listing(override val id: String, override val title: String, override val files: List<Listed>) : FileListing<Listed>

    @Test
    fun `the providers in use in their order, each by its name or else its host, with the stream's connections`() {
        fun providers(list: List<ProviderConfig>, connections: Int? = 6) = row(FileDetails(origin(providers = list, connections = connections)), "Providers")
        assertEquals(FileDetails.Row("Providers", "news.example.net", "6 connections"), providers(providers.take(1)))
        assertEquals(FileDetails.Row("Providers", "news.example.net", "1 connection"), providers(providers.take(1), 1))
        assertEquals(FileDetails.Row("Providers", "news.example.net"), providers(providers.take(1), null))
        val three = providers + ProviderConfig("block", "Block account", "block.example.com", 119, "u", "p", false, priority = 2)
        assertEquals(FileDetails.Row("Providers", "news.example.net, then fill.example.org, then Block account", "in this order · 6 connections"), providers(three))
        assertEquals(FileDetails.Row("Providers", "fill.example.org, then news.example.net", "in this order"), providers(providers.reversed(), null))
        assertNull(providers(emptyList()))
    }

    @Test
    fun `where the name claims what the file states otherwise, the row says so`() {
        fun said(name: String, video: Format): Map<String, List<String>> =
            FileDetails(origin(launch(release.copy(title = name))), FileDetails.Playing(tracksKnown = true, video = video)).sections.flatMap { it.rows }
                .filter { it.nameSays.isNotEmpty() }.associate { it.label to it.nameSays }
        val hd = video(MimeTypes.VIDEO_H264, "avc1.640028", 1920, 1080, sdr)
        assertEquals(
            mapOf(
                "Format" to listOf("The name says HEVC"),
                "Resolution" to listOf("The name says 2160p"),
                "Bit depth" to listOf("The name says 10-bit"),
                "HDR" to listOf("The name says Dolby Vision and HDR10; the file has none"),
            ),
            said("Harbor.Lights.S01E01.2160p.WEB.H265.10bit.DV.HDR.DDP5.1-TENBIT", hd),
        )
        // Dolby Vision the file hasn't, though it has HDR10.
        assertEquals(mapOf("HDR" to listOf("The name says Dolby Vision; the file has none")), said("Harbor.Lights.S01E01.2160p.WEB.DV.HDR10.H.265-GRP", video()))
        // HLG claimed of SDR.
        assertEquals(mapOf("HDR" to listOf("The name says HLG; the file has none")), said("Copper.Crown.2021.1080p.HLG.WEB.H.264-GRP", hd))
        // The name agrees, or says nothing: no notes.
        assertEquals(emptyMap(), said(name, dolbyVision))
        assertEquals(emptyMap(), said("Copper.Crown.2021.1080p.WEB.H.264-GRP", hd))
        assertEquals(emptyMap(), said("Copper.Crown.2021", hd))
    }

    /**
     * Media3 reads Dolby Vision from Matroska's and MP4's records only: in an MPEG-TS (an .m2ts remux) the stream plays as
     * HEVC with PQ whether it carries Dolby Vision or not, so the file can't be said to have none.
     */
    @Test
    fun `Dolby Vision is said to be missing only from a container that would show it`() {
        fun said(container: String) = FileDetails(
            origin(launch(release.copy(title = "Copper.Crown.2019.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC-GRP"))),
            FileDetails.Playing(tracksKnown = true, video = video().buildUpon().setContainerMimeType(container).build()),
        ).let { row(it, "HDR")?.nameSays }
        assertEquals(listOf("The name says Dolby Vision; the file has none"), said(MimeTypes.VIDEO_MATROSKA))
        assertEquals(listOf("The name says Dolby Vision; the file has none"), said(MimeTypes.VIDEO_MP4))
        assertEquals(emptyList(), said(MimeTypes.VIDEO_MP2T))
    }

    /**
     * SD names are loose: a film cropped in a 480p or 576p encode keeps its width and loses height, so its class is only
     * its stored height (852x356 is "356p"), and DVD's 720 is anamorphic. Under 720p, name and file never contradict.
     */
    @Test
    fun `a cropped SD file doesn't contradict its name's SD resolution`() {
        fun said(name: String, width: Int, height: Int) =
            row(FileDetails(origin(launch(release.copy(title = name))), FileDetails.Playing(tracksKnown = true, video = video(MimeTypes.VIDEO_H264, "avc1.64001e", width, height, sdr))), "Resolution")?.nameSays
        assertEquals(emptyList(), said("Copper.Crown.2021.480p.BluRay.x264-GRP", 852, 356))
        assertEquals(emptyList(), said("Copper.Crown.2021.576p.BluRay.x264-GRP", 1024, 428))
        assertEquals(emptyList(), said("Copper.Crown.2021.360p.WEB.x264-GRP", 640, 272))
        assertEquals(emptyList(), said("Copper.Crown.2021.DVDRip.480p.x264-GRP", 720, 480))
        // HD said of SD, and SD of HD, still are.
        assertEquals(listOf("The name says 1080p"), said("Copper.Crown.2021.1080p.BluRay.x264-GRP", 852, 356))
        assertEquals(listOf("The name says 480p"), said("Copper.Crown.2021.480p.BluRay.x264-GRP", 1920, 800))
    }

    /**
     * HDR10+ rides in the video's SEI, which Media3 1.11.1 doesn't read: the player can't say the file has it or not. Both
     * spellings read the same, though the parser also reads the "HDR10" in "HDR10+" as an HDR10 claim.
     */
    @Test
    fun `a name claiming HDR10+ is only said to be unconfirmed`() {
        listOf("Copper.Crown.2021.2160p.WEB-DL.HDR10Plus.H.265-GRP", "Copper.Crown.2021.2160p.WEB-DL.HDR10+.H.265-GRP").forEach { name ->
            fun said(video: Format) = FileDetails(origin(launch(release.copy(title = name))), FileDetails.Playing(tracksKnown = true, video = video))
                .let { row(it, "HDR")?.nameSays }
            assertEquals(listOf("The name says HDR10+; the player can't confirm it"), said(video()), name)
            assertEquals(listOf("The name says HDR10+; the player can't confirm it"), said(video(MimeTypes.VIDEO_H264, "avc1.640028", 1920, 1080, sdr)), name)
        }
    }

    /**
     * Resume plays the release a search played before, its item rebuilt from what was remembered (Replay.candidate),
     * without the search's size, grabs or media info: the panel takes nothing from the item but what both have, so it
     * reads the same.
     */
    @Test
    fun `a Resume shows the same rows as the play from search`() {
        val searched = NewznabItem(
            indexerId = "my-indexer", guid = "hl-s02e05", title = name, sizeBytes = 9_100_000_000, publishedAt = Instant.parse("2026-03-04T10:00:00Z"),
            postedAt = Instant.parse("2026-03-04T09:00:00Z"), categories = listOf(5045), grabs = 812, passworded = false, files = 61, tvdbId = 900001,
            tmdbId = 900002, imdbId = null, tvmazeId = null, season = 2, episode = 5, coverUrl = null,
            mediaInfo = MediaInfo(VideoCodec.Hevc, 2160, listOf(AudioCodec.Eac3), setOf("en")),
            downloadTemplate = "https://indexer.example.com/api?t=get&id=hl-s02e05&apikey={apikey}", attributes = emptyMap(),
        )
        val nzb = prepared(name, listOf(file to 8_424_000_000L))
        val target = UsenetTarget(PlayCandidate("my-indexer/hl-s02e05", name, "my-indexer"), ByteArray(0), nzb, nzb.candidates.single(), PreparedNzbStore(nzbDir))
        val fromSearch = savedRelease(searched, target)
        val resumed = savedRelease(Replay.candidate(fromSearch.toRemembered()).item, target)
        val playing = FileDetails.Playing(tracksKnown = true, video = dolbyVision, durationMs = 48 * 60_000L)
        val a = FileDetails(origin(launch(fromSearch), listing = nzb), playing)
        val b = FileDetails(origin(launch(resumed), listing = nzb), playing)
        assertEquals(rows(a), rows(b))
        assertEquals(a.release to a.fileName, b.release to b.fileName)
    }

    /**
     * A screenshot of the panel passes on what it shows: it shows names, hosts and sizes, never how the NZB is downloaded
     * (the link keeps the account's i= and r= tokens), the guid (a URL keeps other query secrets), the NZB's id, its
     * archive password or poster, an indexer's address, or a provider's login.
     */
    @Test
    fun `no secret reaches the panel`() {
        val secrets = listOf(
            "https://indexer.example.com/getnzb/hl-s02e05.nzb?i=4242&r=0f1e2d3c4b5a&apikey=[redacted]", "i=4242", "r=0f1e2d3c4b5a",
            "https://indexer.example.com/details/hl-s02e05?r=9a8b7c6d", "9a8b7c6d", "ab12cd34ef56", "salt-and-signal", "uploader@example.invalid",
            "https://indexer.example.com", "provider-login", "provider-password", "fill-login", "fill-password", "my-indexer",
        )
        val leaky = SavedRelease(
            "my-indexer", "https://indexer.example.com/details/hl-s02e05?r=9a8b7c6d", name, "https://indexer.example.com/getnzb/hl-s02e05.nzb?i=4242&r=0f1e2d3c4b5a&apikey=[redacted]",
            0, "ab12cd34ef56", file, 8_424_000_000,
        )
        val logins = listOf(
            ProviderConfig("news", "news.example.net", "news.example.net", 563, "provider-login", "provider-password", true),
            ProviderConfig("fill", "", "fill.example.org", 563, "fill-login", "fill-password", true, priority = 1),
        )
        val archive = NzbDocument(listOf(NzbFile("uploader@example.invalid", 1_772_600_000, "\"$file\" yEnc (1/1)", listOf("alt.binaries.example"), emptyList())), password = "salt-and-signal")
        val nzb = PreparedNzb(
            "ab12cd34ef56", name, ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), archive),
            listOf(FileCandidate(0, StreamableFile(file, 8_424_000_000, 0, 0, 0, 0))),
        )
        listOf(launch(leaky), null).forEach { l ->
            val details = FileDetails(origin(l, UsenetUris.build("ab12cd34ef56", 0, file, 8_424_000_000), nzb, indexers, logins), shield)
            val shown = words(details)
            secrets.forEach { secret -> assertTrue(secret !in shown, "\"$secret\" shows in:\n$shown") }
        }
    }

    private fun prepared(title: String, files: List<Pair<String, Long>>) = PreparedNzb(
        nzbId, title, ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(emptyList())),
        files.mapIndexed { i, (name, size) -> FileCandidate(i, StreamableFile(name, size, i, 0, 0, 0)) },
    )
}
