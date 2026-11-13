package io.skjaere.usenet.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.PsExtractor
import androidx.media3.extractor.ts.TsExtractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player's extractors past a stretch of a file no provider has, where the JVM's tests can't go (GapExtractionTest):
 * every extractor a play's factory makes skips such stretches, and MPEG-TS and MPEG-PS, whose extractors keep their state
 * in Android's own collections, go on from the first packet after one, also where Media3's search for a seek's time (they
 * have no index) meets one. `gaps.ts` and `gaps.mpg` are in this test's assets (see the README there).
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class GapSkippingDeviceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun everyExtractorAPlaysFactoryMakesSkipsMissingStretches() {
        val factory = TrackFlagsExtractorsFactory()
        val made = factory.createExtractors().toList() + factory.createExtractors(Uri.parse("usenet://ab12/0"), mapOf("Content-Type" to listOf("video/x-matroska"))).toList()
        assertTrue(made.isNotEmpty())
        assertTrue(made.filterNot { it is GapSkippingExtractor }.toString(), made.all { it is GapSkippingExtractor })
        // Matroska's is the play's own, flagged one.
        assertTrue(made.any { it.underlyingImplementation is FlaggedMatroskaExtractor })
    }

    /**
     * A stretch in the middle of a TS file: read up to it, then from the first whole packet after it, and the film plays
     * on to its end, from its next keyframe as a whole read does. The extractor searches 50 packets, not Media3's 600, at
     * each end for the file's length: in this 50 KB file those would take in the stretch, before Media3 has anything to
     * go on from (a release's are gigabytes).
     */
    @Test
    fun aStretchInATransportStreamIsSkippedToThePacketAfterIt() {
        val file = assets.open("gaps.ts").use { it.readBytes() }
        val whole = extract(file, transportExtractor())
        val gap = 20_000L until 22_000L
        val seeks = mutableListOf<Long>()
        val read = extract(file, transportExtractor(), listOf(gap), seeks)
        // Packets are 188 bytes from the file's start.
        assertEquals(listOf(22_000L + (188 - 22_000L % 188) % 188), seeks.filter { it >= gap.first && it < file.size - 50 * 188 })
        val wholeVideo = whole.video
        val video = read.video
        assertTrue("nothing was skipped", video.size < wholeVideo.size)
        // From the last keyframe on, the film is all there.
        val lastKeyframe = wholeVideo.last { it.second }.first
        assertTrue(lastKeyframe > 3_000_000)
        assertEquals(wholeVideo.filter { it.first >= lastKeyframe }, video.filter { it.first >= lastKeyframe })
    }

    /**
     * A seek (or Resume) near a stretch of a TS file: Media3's search for the time, which looks at the file a window at a
     * time as it narrows on it, meets the stretch in its first window. It searches on clear of it, and the film plays on
     * from where a read through the stretch goes on, to its end, rather than fail as a missing article after jump upon
     * jump at the same place.
     */
    @Test
    fun aSeekWhoseSearchMeetsAStretchInATransportStreamGoesOnPastIt() {
        val file = assets.open("gaps.ts").use { it.readBytes() }
        assertSeekGoesOnPast(file, ::transportExtractor, targetUs = 3_000_000, window = 50 * 188)
    }

    /**
     * MPEG-PS has no index either, and no packets of a size: the player goes on from the stretch's end, where its
     * extractor finds the next pack, once, not by the time Media3's estimate gives that place (bytes over the file's, as
     * if the stream were steady). `gaps.mpg`'s bytes come nine in ten in its second half, so that estimate is seconds
     * early there, and a seek to it would play the stretch before the gap again and again. The picture the extractor had
     * begun before the stretch comes out first after it, part of it missing: it is dropped, and the video waits for its
     * next keyframe.
     */
    @Test
    fun aStretchInAProgramStreamIsSkippedOnceToTheBytesAfterIt() {
        val file = assets.open("gaps.mpg").use { it.readBytes() }
        val whole = extract(file, programExtractor())
        val gap = 200_000L until 204_000L
        val seeks = mutableListOf<Long>()
        val read = extract(file, programExtractor(), listOf(gap), seeks)
        assertEquals(listOf(gap.last + 1), seeks.filter { it >= gap.first && it < file.size - 20_000 })
        val (before, after) = jump(whole.video, read.video)
        // Nothing played twice (as a seek back to before the stretch would), the rest all there, and no more than a
        // second lost: the stretch and its keyframe's wait.
        val times = whole.video.groupingBy { it.first }.eachCount()
        assertTrue("played again", read.video.groupingBy { it.first }.eachCount().all { (time, n) -> n <= (times[time] ?: 0) })
        assertEquals(whole.video.filter { it.first >= after }, read.video.filter { it.first >= after })
        assertTrue("from $before to $after us", after - before <= 1_000_000)
    }

    @Test
    fun aSeekWhoseSearchMeetsAStretchInAProgramStreamGoesOnPastIt() {
        val file = assets.open("gaps.mpg").use { it.readBytes() }
        assertSeekGoesOnPast(file, ::programExtractor, targetUs = 6_500_000, window = 20_000)
    }

    /**
     * A seek to [targetUs] in [file], made as it plays, with a stretch missing just inside the first [window] Media3's
     * search looks at, from the seek point it gives: past the stretch the film plays on to its end as a read through it
     * does, from the same keyframe, nothing of the stretch shown but what that read shows too (the pictures shown before
     * that keyframe that come after it in the file).
     */
    private fun assertSeekGoesOnPast(file: ByteArray, extractor: () -> Extractor, targetUs: Long, window: Int) {
        val whole = extract(file, extractor())
        val first = checkNotNull(whole.seekMap) { "no index" }.getSeekPoints(targetUs).first.position
        val gap = first + window / 5 until first + window * 2 / 5
        val through = extract(file, extractor(), listOf(gap)).video
        val (before, after) = jump(whole.video, through)
        val sought = extract(file, extractor(), listOf(gap), seekToUs = targetUs).video
        assertTrue("nothing after the seek", sought.isNotEmpty())
        assertEquals(whole.video.filter { it.first >= after }, sought.filter { it.first >= after })
        fun List<Pair<Long, Boolean>>.inTheStretch() = filter { it.first > before && it.first < after }
        assertTrue("from $before to $after us: ${sought.inTheStretch()}", through.inTheStretch().containsAll(sought.inTheStretch()))
    }

    /** Where [read] (a read through a stretch from the start) left [whole]: its last sample in front of the stretch, and its first after it. */
    private fun jump(whole: List<Pair<Long, Boolean>>, read: List<Pair<Long, Boolean>>): Pair<Long, Long> {
        val at = read.indices.first { read[it] != whole[it] }
        assertTrue("it goes on from a keyframe", read[at].second)
        return read[at - 1].first to read[at].first
    }

    private fun transportExtractor(): Extractor = TrackFlagsExtractorsFactory().forPlay(
        TsExtractor(TsExtractor.MODE_SINGLE_PMT, 0, SubtitleParser.Factory.UNSUPPORTED, TimestampAdjuster(0), DefaultTsPayloadReaderFactory(0), 50 * 188),
    )

    private fun programExtractor(): Extractor = TrackFlagsExtractorsFactory().forPlay(PsExtractor())

    /** [file] from [position] as the player's reader hands it over: every byte in front of a missing stretch, then [GapReached] at it. */
    private class Reader(private val file: ByteArray, private var position: Long, private val gaps: List<LongRange>) : DataReader {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position == file.size.toLong()) return C.RESULT_END_OF_INPUT
            var n = minOf(length.toLong(), file.size - position).toInt()
            gaps.firstOrNull { it.last >= position }?.let { gap ->
                if (gap.first <= position) throw GapReached(gap)
                n = minOf(n.toLong(), gap.first - position).toInt()
            }
            file.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
            position += n
            return n
        }
    }

    /** Each track type's samples an extractor output, their times and whether keyframes, and the index it gave. */
    private class Extracted(val samples: Map<Int, List<Pair<Long, Boolean>>>, val seekMap: SeekMap?) {
        val video get() = samples.getValue(C.TRACK_TYPE_VIDEO)
    }

    /**
     * What [extractor] outputs reading [file] whole, as Media3's loader does. With [seekToUs], it is sought there as it
     * plays (once it has given its index and a sample), as Media3 seeks to a seek's time: told the time and the position
     * its index gives, and read from that position on, only what it outputs from there kept. (A seek before any sample,
     * as a play's start, would also have the extractor take the time for that of the first packet it then reads, which
     * Media3's search finds; a test can't tell then where the film went on from.)
     */
    private fun extract(
        file: ByteArray,
        extractor: Extractor,
        gaps: List<LongRange> = emptyList(),
        seeks: MutableList<Long> = mutableListOf(),
        seekToUs: Long? = null,
    ): Extracted {
        val samples = mutableMapOf<Int, MutableList<Pair<Long, Boolean>>>()
        var index: SeekMap? = null
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val list = samples.getOrPut(type) { mutableListOf() }
                return object : TrackOutput by DiscardingTrackOutput() {
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                        list += timeUs to (flags and C.BUFFER_FLAG_KEY_FRAME != 0)
                    }
                }
            }

            override fun endTracks() = Unit

            override fun seekMap(seekMap: SeekMap) {
                index = seekMap
            }
        })
        val holder = PositionHolder()
        var input: ExtractorInput = DefaultExtractorInput(Reader(file, 0, gaps), 0, file.size.toLong())
        var seekTo = seekToUs
        while (true) {
            // A search that keeps coming back to the same place never ends: a failure, not a hang.
            assertTrue("reading without end: ${seeks.takeLast(10)}", seeks.size < 200)
            val result = extractor.read(input, holder)
            val map = index
            if (seekTo != null && map != null && samples[C.TRACK_TYPE_VIDEO].orEmpty().isNotEmpty()) {
                val position = map.getSeekPoints(seekTo).first.position
                extractor.seek(position, seekTo)
                samples.values.forEach { it.clear() }
                input = DefaultExtractorInput(Reader(file, position, gaps), position, file.size.toLong())
                seekTo = null
                continue
            }
            when (result) {
                Extractor.RESULT_SEEK -> {
                    seeks += holder.position
                    input = DefaultExtractorInput(Reader(file, holder.position, gaps), holder.position, file.size.toLong())
                }
                Extractor.RESULT_END_OF_INPUT -> return Extracted(samples, index)
            }
        }
    }
}
