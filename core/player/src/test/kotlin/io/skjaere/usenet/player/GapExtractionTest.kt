package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ChunkIndexProvider
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A stretch of a file no provider has, as the player meets it: why the zeros its stream fills it with can't go to Media3's
 * extractors, and how the player's extractors go past it instead ([GapSkippingExtractor]): at the first point of the file's
 * index after it, as after a seek, for Matroska and MP4, and at the next packet for MPEG-TS. Where there is no index yet (a
 * stretch in the header or the index itself), the play fails as a missing article, as before. The files are the fixtures,
 * read as the player reads them: the reader hands over every byte in front of a stretch and then fails at its first byte.
 * (MPEG-TS's extractor keeps its state in android.util.SparseArray, which the JVM's stubs lack: GapSkippingTsDeviceTest.)
 */
@androidx.annotation.OptIn(UnstableApi::class)
class GapExtractionTest {
    private val chapters = ExtractedFormats.fixture("chapters.mkv")

    /** A sample as an extractor output it: when, and whether it is a keyframe. */
    private data class Sample(val timeUs: Long, val key: Boolean)

    /** What an extractor output: each track's samples in order, its type, and the index it sent. */
    private class Samples : ExtractorOutput {
        val tracks = linkedMapOf<Int, MutableList<Sample>>()
        val types = mutableMapOf<Int, Int>()
        var seekMap: SeekMap? = null

        override fun track(id: Int, type: Int): TrackOutput {
            types[id] = type
            val samples = tracks.getOrPut(id) { mutableListOf() }
            return object : TrackOutput by DiscardingTrackOutput() {
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    samples += Sample(timeUs, flags and C.BUFFER_FLAG_KEY_FRAME != 0)
                }
            }
        }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }

        fun of(type: Int): List<Sample> = tracks.filterKeys { types[it] == type }.values.single()
        val video: List<Sample> get() = of(C.TRACK_TYPE_VIDEO)
        val audio: List<Sample> get() = of(C.TRACK_TYPE_AUDIO)
    }

    /**
     * [file] from [position] as the player's reader hands it over where [gaps] (merged) are missing: with [zeros], their
     * bytes as the zeros the stream fills them with (as before the reader stopped at them); else every byte in front of one,
     * then [GapReached] at its first byte.
     */
    private class Reader(private val file: ByteArray, private var position: Long, private val gaps: List<LongRange>, private val zeros: Boolean) : DataReader {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position == file.size.toLong()) return C.RESULT_END_OF_INPUT
            var n = minOf(length.toLong(), file.size - position).toInt()
            if (!zeros) {
                gaps.firstOrNull { it.last >= position }?.let { gap ->
                    if (gap.first <= position) throw GapReached(gap)
                    n = minOf(n.toLong(), gap.first - position).toInt()
                }
            }
            file.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
            if (zeros) {
                for (gap in gaps) {
                    val from = maxOf(gap.first, position)
                    val to = minOf(gap.last, position + n - 1)
                    if (from <= to) buffer.fill(0, offset + (from - position).toInt(), offset + (to - position).toInt() + 1)
                }
            }
            position += n
            return n
        }
    }

    /** What a read of the file ended with: what was output, where the extractor asked to read from, and the input as it ended. */
    private class Extraction(val samples: Samples, val seeks: List<Long>, val input: ExtractorInput)

    /**
     * Reads [file] whole with [extractor], as Media3's loader does: from the start, again from wherever the extractor asks
     * ([Extractor.RESULT_SEEK]), until it says the input ended. A failure is thrown, as the loader's is.
     */
    private fun extract(file: ByteArray, extractor: Extractor, gaps: List<LongRange> = emptyList(), zeros: Boolean = false, samples: Samples = Samples()): Extraction {
        extractor.init(samples)
        val holder = PositionHolder()
        val seeks = mutableListOf<Long>()
        var input: ExtractorInput = DefaultExtractorInput(Reader(file, 0, gaps, zeros), 0, file.size.toLong())
        while (true) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_SEEK -> {
                    seeks += holder.position
                    input = DefaultExtractorInput(Reader(file, holder.position, gaps, zeros), holder.position, file.size.toLong())
                }
                Extractor.RESULT_END_OF_INPUT -> return Extraction(samples, seeks, input)
            }
        }
    }

    /**
     * The player's extractor for the fixture [name]: Media3's for its container, then as a play's factory takes it
     * ([TrackFlagsExtractorsFactory.forPlay]). Chosen by the name, as the player's data source tells Media3 the container
     * (sniffing the factory's list needs android.net.Uri, which the JVM's stubs lack). Subtitles aren't parsed: the
     * parsers need Android, and how they're read has nothing to do with a stretch skipped.
     */
    private fun playersExtractor(name: String): Extractor = TrackFlagsExtractorsFactory().apply { setSubtitleParserFactory(SubtitleParser.Factory.UNSUPPORTED) }.forPlay(
        when (name.substringAfterLast('.')) {
            "mkv" -> MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, 0)
            "mp4" -> Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)
            else -> error("no extractor for $name")
        },
    )

    /** The player's Matroska extractor as it reads a play, unwrapped: without skipping. */
    private fun plainMatroska(): Extractor = TrackFlagsExtractorsFactory().apply { setSubtitleParserFactory(SubtitleParser.Factory.UNSUPPORTED) }.matroska()

    /** The cue points (where a Matroska file's index lets a play start) of [file], from a read of it whole: byte offsets and times. */
    private fun cues(file: ByteArray): Pair<LongArray, LongArray> {
        val index = assertNotNull((extract(file, plainMatroska()).samples.seekMap as? ChunkIndexProvider)?.chunkIndex)
        return index.offsets to index.timesUs
    }

    /** Where [file]'s Cues element starts: its id, 1C 53 BB 6B, as it is last in the file (the SeekHead names it first). */
    private fun cuesAt(file: ByteArray): Long =
        (0..file.size - 4).last { i -> file[i] == 0x1C.toByte() && file[i + 1] == 0x53.toByte() && file[i + 2] == 0xBB.toByte() && file[i + 3] == 0x6B.toByte() }.toLong()

    /** Where [file]'s top-level MP4 box [type] lies. */
    private fun box(file: ByteArray, type: String): LongRange {
        var at = 0
        while (at < file.size) {
            val size = ((file[at].toInt() and 0xFF) shl 24) or ((file[at + 1].toInt() and 0xFF) shl 16) or ((file[at + 2].toInt() and 0xFF) shl 8) or (file[at + 3].toInt() and 0xFF)
            if (String(file, at + 4, 4, Charsets.US_ASCII) == type) return at.toLong() until at.toLong() + size
            at += size
        }
        error("no $type box")
    }

    /**
     * That [broken], a read past a stretch, output what [whole] did but for a stretch of each track: everything in front of
     * it, then, from where the read went on, everything the whole read output from there, nothing twice or out of order;
     * the video from the keyframe at [resumeUs]. (An MP4's audio goes on from its sample at or before that time.)
     */
    private fun assertSkipped(whole: Samples, broken: Samples, resumeUs: Long) {
        assertEquals(whole.tracks.keys, broken.tracks.keys)
        for ((id, all) in whole.tracks) {
            val got = broken.tracks.getValue(id)
            val inFront = got.zip(all).takeWhile { (a, b) -> a == b }.size
            val after = got.drop(inFront)
            assertTrue(after.isNotEmpty(), "track $id: nothing after the stretch")
            val from = all.indexOf(after.first())
            assertTrue(from > inFront, "track $id: went on from sample $from, after $inFront in front: nothing lost")
            assertEquals(all.drop(from), after, "track $id from where it went on")
        }
        val video = broken.video
        assertEquals(Sample(resumeUs, key = true), video[video.zip(whole.video).takeWhile { (a, b) -> a == b }.size])
    }

    /**
     * Why the player never hands an extractor a stretch's zeros: Matroska's fails on the first element whose id or size
     * reads as a zero byte, and with an IllegalStateException, which Media3 reports as an unexpected loader failure (the
     * provider's, as the player would have read it), not as a damaged file. And it fails where it meets them, after
     * output it can't take back.
     */
    @Test
    fun zerosInAMatroskaClusterFailItsExtractorWithAnIllegalStateException() {
        val (offsets, _) = cues(chapters)
        val gap = offsets[20] + 3_000 until offsets[20] + 5_000
        val failure = assertFailsWith<IllegalStateException> { extract(chapters, plainMatroska(), listOf(gap), zeros = true) }
        assertEquals("No valid varint length mask found", failure.message)
    }

    @Test
    fun aStretchInAMatroskaClusterIsSkippedToTheFirstCueAfterIt() {
        val (offsets, times) = cues(chapters)
        val whole = extract(chapters, plainMatroska()).samples
        val gap = offsets[20] + 3_000 until offsets[20] + 5_000
        val read = extract(chapters, playersExtractor("chapters.mkv"), listOf(gap))
        val next = offsets.indexOfFirst { it > gap.last }
        // Its seeks but Matroska's own, to the cues and back.
        assertEquals(listOf(offsets[next]), read.seeks.filter { it > gap.last && it < cuesAt(chapters) })
        assertSkipped(whole, read.samples, times[next])
    }

    /** A run of missing articles, merged by the reader: one jump past all of it, to the first cue after its end. */
    @Test
    fun aRunOfMissingArticlesIsSkippedWhole() {
        val (offsets, times) = cues(chapters)
        val whole = extract(chapters, plainMatroska()).samples
        val run = offsets[30] + 100 until offsets[33] + 100
        val read = extract(chapters, playersExtractor("chapters.mkv"), listOf(run))
        assertEquals(listOf(offsets[34]), read.seeks.filter { it > run.first && it < cuesAt(chapters) })
        assertSkipped(whole, read.samples, times[34])
    }

    /**
     * An MP4's samples are found by its index, so the player goes on from the first keyframe whose own samples (each
     * track's at its time, which may come before it in the file) all start after the stretch: Media3's seek point there.
     */
    @Test
    fun aStretchInAnMp4IsSkippedToTheFirstKeyframeWhoseSamplesAllLieAfterIt() {
        for (name in listOf("gaps-faststart.mp4", "gaps-moov-end.mp4")) {
            val file = ExtractedFormats.fixture(name)
            val whole = extract(file, Mp4Extractor()).samples
            val map = assertNotNull(whole.seekMap)
            val mdat = box(file, "mdat")
            val middle = (mdat.first + mdat.last) / 2
            val gap = middle until middle + 2_000
            val keyframes = whole.video.filter { it.key }.map { it.timeUs }
            val resume = keyframes.first { map.getSeekPoints(it).first.position > gap.last }
            val read = extract(file, playersExtractor(name), listOf(gap))
            assertIs<Mp4Extractor>(playersExtractor(name).underlyingImplementation)
            assertSkipped(whole, read.samples, resume)
            // A second's keyframes: the stretch costs about a second.
            assertTrue(resume - keyframes.last { it < resume } <= 1_000_000, "$name: resumes at $resume us")
        }
    }

    /** The header or the index missing: nothing to go on from, so the play fails as a missing article, as before. */
    @Test
    fun aStretchInTheHeaderOrTheIndexCantBeSkipped() {
        // The EBML header, the tracks and the chapters.
        assertFailsWith<GapReached> { extract(chapters, playersExtractor("chapters.mkv"), listOf(0L until 2_000L)) }
        // The cues, after the last cluster: read before the first cluster, through a seek to them.
        val cues = cuesAt(chapters)
        assertFailsWith<GapReached> { extract(chapters, playersExtractor("chapters.mkv"), listOf(cues until cues + 1_000)) }
        // An MP4's index at its end.
        val moovAtEnd = ExtractedFormats.fixture("gaps-moov-end.mp4")
        val moov = box(moovAtEnd, "moov")
        assertFailsWith<GapReached> { extract(moovAtEnd, playersExtractor("gaps-moov-end.mp4"), listOf(moov.first + 1_000 until moov.first + 3_000)) }
    }

    /** In the last cue's cluster there is no cue after it to go on from: the film ends there, rather than stop with an error. */
    @Test
    fun aStretchInTheLastClusterEndsTheFilm() {
        val (offsets, _) = cues(chapters)
        val whole = extract(chapters, plainMatroska()).samples
        val gap = offsets.last() + 1_000 until offsets.last() + 3_000
        assertTrue(gap.last < cuesAt(chapters))
        val read = extract(chapters, playersExtractor("chapters.mkv"), listOf(gap)).samples
        // What was read is what a whole read began with, and stops short of its end.
        for ((id, all) in whole.tracks) {
            val got = read.tracks.getValue(id)
            assertEquals(all.take(got.size), got, "track $id")
            assertTrue(got.size < all.size, "track $id went on past the stretch")
        }
    }

    /**
     * Stretch after stretch with no sample between them (every cluster's start missing from one on): the player gives up
     * after [GapSkippingExtractor.MAX_JUMPS_IN_A_ROW] jumps rather than skip on through the film, and fails as before.
     */
    @Test
    fun stretchesInARowWithNothingBetweenThemGiveUpAfterAFewJumps() {
        val (offsets, _) = cues(chapters)
        val gaps = offsets.drop(10).map { it until it + 50 }
        val seeks = mutableListOf<Long>()
        val extractor = playersExtractor("chapters.mkv")
        assertFailsWith<GapReached> {
            extractor.init(Samples())
            val holder = PositionHolder()
            var input: ExtractorInput = DefaultExtractorInput(Reader(chapters, 0, gaps, zeros = false), 0, chapters.size.toLong())
            while (true) {
                if (extractor.read(input, holder) == Extractor.RESULT_SEEK) {
                    seeks += holder.position
                    input = DefaultExtractorInput(Reader(chapters, holder.position, gaps, zeros = false), holder.position, chapters.size.toLong())
                }
            }
        }
        // The Matroska extractor's own seek, to the cues and back, then the jumps.
        assertEquals(GapSkippingExtractor.MAX_JUMPS_IN_A_ROW, seeks.count { it in offsets.drop(11) }, "$seeks")
    }

    /**
     * The player hears of each jump, from the last sample in front of the stretch to where the play went on, for the
     * notice, and of the file's index, to place a stretch the stream finds ahead in the film, for the count.
     */
    @Test
    fun theJumpsAndTheIndexAreToldToThePlayer() {
        val (offsets, times) = cues(chapters)
        val whole = extract(chapters, plainMatroska()).samples
        val jumps = mutableListOf<PlayGaps.Jump>()
        val gaps = PlayGaps { jumps += it }
        val gap = offsets[20] + 3_000 until offsets[20] + 5_000
        val extractor = TrackFlagsExtractorsFactory(gaps = gaps).apply { setSubtitleParserFactory(SubtitleParser.Factory.UNSUPPORTED) }
            .forPlay(MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, 0))
        val read = extract(chapters, extractor, listOf(gap)).samples
        val next = offsets.indexOfFirst { it > gap.last }
        // The latest of the samples each track took in front of the stretch.
        val inFront = whole.tracks.keys.flatMap { id ->
            read.tracks.getValue(id).zip(whole.tracks.getValue(id)).takeWhile { (a, b) -> a == b }.map { it.first.timeUs }
        }.max()
        assertEquals(listOf(PlayGaps.Jump(gap, fromMs = inFront / 1000, toMs = times[next] / 1000)), jumps)
        // A byte is placed at the cue in front of it.
        assertEquals(times[20] / 1000, gaps.timeMsAt(gap.first))
        assertEquals(times[next] / 1000, gaps.timeMsAt(offsets[next]))
    }

    /**
     * The wrapper only adds the skip: every Matroska and MP4 fixture sniffs as it does without it, its
     * extractor named as Media3's own (Media3 asks for the MP3 one's), and gives the same tracks through it; a sniff that
     * fails says why as Media3's does.
     */
    @Test
    fun everyFixtureExtractsAsItDoesWithoutTheWrapper() {
        val names = listOf(
            "chapters.mkv", "tracks.mkv", "flags.mkv", "subtitles.mkv", "cues-at-end.mkv", "av1-10bit.mkv", "dv-p5.mkv", "dv-p7.mkv",
            "dv-p8.mkv", "dv-p8-hdr10.mkv", "hdr10-hevc.mkv", "hevc-level62.mkv", "hevc10.mkv", "hlg-hevc.mkv", "mpeg2.mkv",
            "rate-23976.mkv", "gaps-faststart.mp4", "gaps-moov-end.mp4",
        )
        fun inputOf(file: ByteArray) = DefaultExtractorInput(Reader(file, 0, emptyList(), zeros = false), 0, file.size.toLong())
        for (name in names) {
            val file = ExtractedFormats.fixture(name)
            val wrapped = playersExtractor(name)
            assertIs<GapSkippingExtractor>(wrapped, name)
            val plain = playersExtractor(name).underlyingImplementation
            assertEquals(plain.javaClass, wrapped.underlyingImplementation.javaClass, name)
            assertTrue(wrapped.sniff(inputOf(file)), "$name: not sniffed")
            assertEquals(formats(file, plain), formats(file, wrapped), name)
        }
        val mkv = ExtractedFormats.fixture("chapters.mkv")
        val mp4 = playersExtractor("gaps-faststart.mp4")
        assertFalse(mp4.sniff(inputOf(mkv)))
        val plainMp4 = Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0).apply { sniff(inputOf(mkv)) }
        assertEquals(plainMp4.sniffFailureDetails, mp4.sniffFailureDetails)
    }

    /** Each track's last format as [extractor] gives it for [file], read whole. */
    private fun formats(file: ByteArray, extractor: Extractor): Map<Int, Format?> {
        val formats = linkedMapOf<Int, Format?>()
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
                override fun format(format: Format) {
                    formats[id] = format
                }
            }

            override fun endTracks() = Unit

            override fun seekMap(seekMap: SeekMap) = Unit
        }
        extractor.init(output)
        val holder = PositionHolder()
        var input: ExtractorInput = DefaultExtractorInput(Reader(file, 0, emptyList(), zeros = false), 0, file.size.toLong())
        while (true) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_SEEK -> input = DefaultExtractorInput(Reader(file, holder.position, emptyList(), zeros = false), holder.position, file.size.toLong())
                Extractor.RESULT_END_OF_INPUT -> return formats
            }
        }
    }

    companion object {
        /**
         * Media3 checks its reads strictly in tests, which it tells by Build.FINGERPRINT first: null in android.jar's
         * stubs, so that check throws. Strict, as Media3 would have chosen.
         */
        @BeforeClass
        @JvmStatic
        fun strictReads() = ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
    }
}
