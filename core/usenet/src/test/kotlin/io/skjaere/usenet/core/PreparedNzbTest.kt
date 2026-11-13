package io.skjaere.usenet.core

import io.skjaere.compressionutils.SplitInfo
import io.skjaere.nntp.YencHeaders
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.nzb.NzbFile
import io.skjaere.nzbstreamer.nzb.NzbSegment
import io.skjaere.nzbstreamer.stream.StreamableFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreparedNzbTest {

    private fun nzbFile(subject: String, size: Long = 0) = NzbFile(
        poster = "fake", date = 0, subject = subject, groups = listOf("alt.binaries.fake"),
        segments = listOf(NzbSegment(bytes = size, number = 1, articleId = "$subject@fake")),
        yencHeaders = YencHeaders(line = 128, size = size, name = subject),
    )

    /** Four 100-byte volumes. */
    private val volumes = (1..4).map { nzbFile("v.7z.%03d".format(it), 100) }

    private fun prepared(vararg files: StreamableFile) = PreparedNzb(
        id = "id", title = "title",
        metadata = ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(volumes)),
        candidates = files.mapIndexed(::FileCandidate),
    )

    @Test
    fun `a file's volumes are those its bytes lie in`() {
        // One split from byte 150 to 249, the second half of volume 2 and the first half of volume 3,
        // as a 7z entry's is: its volume index says where the archive starts, not where the bytes are.
        val spanning = StreamableFile(
            "a.mkv", 100, startVolumeIndex = 0, startOffsetInVolume = 0, continuationHeaderSize = 0, endOfArchiveSize = 0,
            preComputedSplits = listOf(SplitInfo(volumeIndex = 0, dataStartPosition = 150, dataSize = 100)),
        )
        // From halfway into volume 3 to the end of volume 4, split by volume.
        val split = StreamableFile("b.mkv", 150, startVolumeIndex = 2, startOffsetInVolume = 50, continuationHeaderSize = 0, endOfArchiveSize = 0)
        val nzb = prepared(spanning, split)
        assertEquals(volumes.subList(1, 3), nzb.volumesOf(nzb.candidates[0]).files)
        assertEquals(volumes.subList(2, 4), nzb.volumesOf(nzb.candidates[1]).files)
    }

    /**
     * The file a play picked is found by its name (and size, when known) wherever the release lists
     * it now, and by its position only for a play from before names were kept (#179).
     */
    @Test
    fun `a file is found by its name and size, by its position only without a name`() {
        val nzb = prepared(StreamableFile("Show.S01E01.mkv", 100, 0, 0, 0, 0), StreamableFile("Show.S01E02.mkv", 200, 1, 0, 0, 0))
        assertEquals(nzb.candidates[1], nzb.find(0, "Show.S01E02.mkv", 200))
        assertEquals(nzb.candidates[1], nzb.find(5, "Show.S01E02.mkv", null))
        assertEquals(null, nzb.find(1, "Show.S01E02.mkv", 201))
        assertEquals(null, nzb.find(1, "Show.S01E03.mkv", 200))
        assertEquals(nzb.candidates[0], nzb.find(0, null, null))
        assertEquals(null, nzb.find(2, null, null))
        // Two of one name and size, in two folders: the one at the position, else the first.
        val twice = prepared(StreamableFile("a/movie.mkv", 100, 0, 0, 0, 0), StreamableFile("b/movie.mkv", 100, 1, 0, 0, 0))
        assertEquals(twice.candidates[1], twice.find(1, "movie.mkv", 100))
        assertEquals(twice.candidates[0], twice.find(7, "movie.mkv", 100))
    }

    /**
     * nzb-streamer lists a file posted under an obfuscated name by the name its PAR2 data gives it
     * (library issue N54), and by the posted one again where that data can't be read: renamed in its
     * place, as big as it was. A file remembered by one of those names is the one now at its position
     * under the other, when it is as big as it was and no other file is (NR-19). Two names that say
     * what they are, neither obfuscated, are two files, as before (#179).
     */
    @Test
    fun `a file renamed from or to an obfuscated name is found at its position by its size`() {
        val named = prepared(StreamableFile("Show.S01E01.mkv", 100, 0, 0, 0, 0), StreamableFile("Show.S01E02.mkv", 200, 1, 0, 0, 0))
        assertEquals(named.candidates[1], named.find(1, "b91c2d3e.mkv", 200))
        val obfuscated = prepared(StreamableFile("a8f7d6e5.mkv", 100, 0, 0, 0, 0), StreamableFile("b91c2d3e.mkv", 200, 1, 0, 0, 0))
        assertEquals(obfuscated.candidates[1], obfuscated.find(1, "Show.S01E02.mkv", 200))
        // Not at another position, nor of another size, nor where another file is as big, nor with no size known.
        assertEquals(null, named.find(0, "b91c2d3e.mkv", 200))
        assertEquals(null, named.find(1, "b91c2d3e.mkv", 201))
        val alike = prepared(StreamableFile("Show.S01E01.mkv", 200, 0, 0, 0, 0), StreamableFile("Show.S01E02.mkv", 200, 1, 0, 0, 0))
        assertEquals(null, alike.find(1, "b91c2d3e.mkv", 200))
        assertEquals(null, named.find(1, "b91c2d3e.mkv", null))
    }

    @Test
    fun `repair, checksum, note and artwork files and sample clips are extras`() {
        listOf("a.par2", "a.vol07+08.PAR2", "a.nfo", "a.sfv", "a.nzb", "a.txt", "a.jpg", "a.png", "a.srr", "a-sample.mkv", "Sample.mkv")
            .forEach { assertTrue(ExtraFileFilter.isExtra(it), it) }
        listOf("a.mkv", "a.part01.rar", "a.r00", "a.7z.001", "a8f3c1", "Sampler.mkv")
            .forEach { assertFalse(ExtraFileFilter.isExtra(it), it) }
    }

    /**
     * A release's extras in folders of their own (L163): a video in Sample/, Samples/, Featurettes/, Extras/ or Bonus/ is
     * no feature, whatever its name says, so a film's largest video outside them plays, and a pack's episode is the one
     * outside them.
     */
    @Test
    fun `a video in a folder of samples or extras is a sample, and what plays is outside them`() {
        listOf(
            "Sample/x.mkv", "Quiet.Orbit.2019/Samples/quiet.orbit.mkv", "Quiet.Orbit.2019/Featurettes/Making.Of.mkv",
            "Quiet.Orbit.2019/extras/Deleted.Scenes.mp4", "a/BONUS/b.mp4",
            // Whatever the separator (a 7z made on Windows may keep its own), as a disc's folders are told (L310).
            "Quiet.Orbit.2019\\Sample\\sample.mkv", "Quiet.Orbit.2019\\Extras\\Trailer.mkv", "a\\BONUS\\b.mp4",
        ).forEach {
            assertTrue(VideoFileFilter.isSample(it), it)
            assertTrue(ExtraFileFilter.isExtra(it), it)
        }
        listOf(
            "Quiet.Orbit.2019/Quiet.Orbit.2019.mkv", "Sampler/x.mkv", "Bonus.Round.S01/Bonus.Round.S01E01.mkv", "Extras/x.r00",
            // A sample's name is the file's own, not its folder's.
            "Sampler\\x.mkv", "Harbor.Lights.Sample.Set\\Harbor.Lights.S01E01.mkv",
        ).forEach { assertFalse(VideoFileFilter.isSample(it), it) }
        val film = prepared(
            StreamableFile("Quiet.Orbit.2019/Featurettes/Making.Of.mkv", 6_000, 0, 0, 0, 0),
            StreamableFile("Quiet.Orbit.2019/Quiet.Orbit.2019.1080p.WEB-GRP.mkv", 4_000, 1, 0, 0, 0),
            StreamableFile("Quiet.Orbit.2019/Extras/Trailer.mkv", 7_000, 2, 0, 0, 0),
        )
        assertEquals(film.candidates[1], film.defaultPick)
        assertEquals(film.candidates[1], FilePicker.Largest.pick(film))
        assertTrue(film.oneVideo)
        val pack = prepared(
            StreamableFile("Harbor.Lights.S01/Extras/Harbor.Lights.S01E02.Behind.The.Scenes.mkv", 900, 0, 0, 0, 0),
            StreamableFile("Harbor.Lights.S01/Harbor.Lights.S01E02.mkv", 800, 1, 0, 0, 0),
            StreamableFile("Harbor.Lights.S01/Harbor.Lights.S01E03.mkv", 820, 2, 0, 0, 0),
        )
        assertEquals(pack.candidates[1], FilePicker.Episode(1, 2).pick(pack))
    }

    /**
     * Inside a Blu-ray disc's `BDMV` folder nothing is a video (L312), whatever the separator or the case: its streams are
     * no film the player reads. A stream file with no such folder still is one, and so is a video in a folder whose name
     * only holds "BDMV".
     */
    @Test
    fun `nothing in a disc's BDMV folder is a video, a stream file outside one is`() {
        listOf(
            "Harbor.Lights.2024.2160p.UHD.BluRay.HEVC-GRP/BDMV/STREAM/00001.m2ts", "BDMV/STREAM/00001.m2ts", "Harbor.Lights.2024\\BDMV\\STREAM\\00001.m2ts",
            "harbor.lights.2024/bdmv/stream/00001.M2TS", "Harbor.Lights.2024/BDMV/x.mkv",
        ).forEach { assertFalse(VideoFileFilter.isVideo(it), it) }
        listOf("00001.m2ts", "Copper.Crown.2019.2160p.BluRay-LONE.M2TS", "Harbor.Lights.2024.BDMV-GRP/Harbor.Lights.2024.1080p.mkv", "BDMV.mkv")
            .forEach { assertTrue(VideoFileFilter.isVideo(it), it) }
    }

    @Test
    fun `extras are dropped by the names in their subjects`() {
        val video = nzbFile("[1/3] - \"Show.S01E03.mkv\" yEnc (1/50)")
        val obfuscated = nzbFile("a8f3c1b2e9d4 yEnc (1/50)")
        val extras = listOf("\"Show.S01E03.nfo\" yEnc (1/1)", "[3/3] - \"Show.S01E03.vol00+01.par2\" yEnc (1/4)").map { nzbFile(it) }
        val nzb = NzbDocument(listOf(video) + extras + obfuscated, password = "secret")
        assertEquals(NzbDocument(listOf(video, obfuscated), password = "secret"), ExtraFileFilter.withoutExtras(nzb))
    }
}
