package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.PsExtractor
import androidx.media3.extractor.ts.TsExtractor
import org.slf4j.LoggerFactory
import java.util.Locale

/**
 * One of Media3's extractors ([delegate]), that goes past a stretch of the file no provider has rather than fail the play.
 * The reader stops at such a stretch's first byte ([GapReached], [UsenetStreamReader]): its bytes are the zeros the
 * stream fills it with, which Media3's extractors can't take. Here the extractor is reset, as for a seek, and reads on
 * from the first point of the file's index whose bytes all lie after the stretch: Matroska's next cue, an MP4's next
 * keyframe whose tracks' samples at its time all start after it ([SeekMaps.firstFrom]). Media3 goes on from there as
 * after its own seeks, the picture and sound jumping ahead by the stretch and the rest of the keyframe interval it was
 * in. MPEG-TS and MPEG-PS, which have no index, go on from the first packet after the stretch (MPEG-PS from the first
 * pack after it, which is looked for here), never by the time Media3's estimate for their bytes gives: that is
 * the bytes over the file's as if the stream were steady, seconds out, and a seek to it may play the stretch before the
 * gap again. Media3 seeks in them by a search over their times, which looks at the file a window at a time; one that
 * meets a stretch searches on clear of the stretches met so far ([ClearOfGaps]). After a jump each video track's
 * samples are dropped until its next keyframe: a cue's cluster can begin with frames from before the cue's keyframe,
 * which Media3's own seeks don't show (they decode them only) but a jump has no way to hide, and a decoder given them
 * without what they refer to shows garbage. MPEG-PS's first picture after a jump is dropped whatever it is: it is the
 * one the extractor had begun before the stretch, ended with what follows it. Each jump is logged with the time it cost,
 * and told to the player ([gaps]), with the file's index.
 *
 * Where there is nothing to go on from, it doesn't: a stretch read before the index is (the header, or the index
 * itself) fails the play as a missing article, as it did before zeros were filled in, and so does a file whose index
 * can't be searched. A stretch in the file's last stretch of the index ends the film there. Stretch after stretch with
 * no sample between them ([MAX_JUMPS_IN_A_ROW]) gives up too, rather than skip on through a film whose articles are
 * mostly gone.
 *
 * Everything else is the delegate's, as it is: sniffing, its output (only watched), seeking, and what it is
 * ([getUnderlyingImplementation], which Media3 asks of the MP3 extractor).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class GapSkippingExtractor(private val delegate: Extractor, private val gaps: PlayGaps? = null) : Extractor {
    private val log = LoggerFactory.getLogger(GapSkippingExtractor::class.java)

    /** The file's index, once the delegate has sent it. */
    private var seekMap: SeekMap? = null

    /** The latest time of a sample a track took since the last seek or jump; unset when none has. */
    private var lastSampleUs = C.TIME_UNSET

    /** Jumps made one after the other with no sample between them. */
    private var jumpsInARow = 0

    /** The video tracks' outputs, which drop samples after a jump until a keyframe. */
    private val videoTracks = mutableListOf<Track>()

    // MPEG-TS's ([transport]) or MPEG-PS's ([program]): with no index, they find their way again from any packet, and
    // Media3 seeks in them by a search over their times.
    private val transport = delegate.underlyingImplementation is TsExtractor
    private val program = delegate.underlyingImplementation is PsExtractor

    /** The stretches met so far, merged, in file order: a search for a seek's time keeps clear of them (MPEG-TS, MPEG-PS). */
    private val met = mutableListOf<LongRange>()

    /** Since Media3's last seek, until a sample comes: the delegate may be searching for that seek's time. */
    private var sought = false

    /** After a jump in MPEG-PS: the next read goes on from the next pack ([skipToPack]). */
    private var toPack = false

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

    override fun init(output: ExtractorOutput) = delegate.init(Output(output))

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = try {
        if (toPack && !skipToPack(input)) {
            Extractor.RESULT_END_OF_INPUT
        } else {
            toPack = false
            delegate.read(if (sought && met.isNotEmpty()) ClearOfGaps(input, met) else input, seekPosition)
        }
    } catch (reached: GapReached) {
        skip(reached, input.length, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        lastSampleUs = C.TIME_UNSET
        jumpsInARow = 0
        sought = true
        toPack = false
        // Media3's own seek hides what comes before the keyframe it starts from by itself.
        videoTracks.forEach { it.waitForKeyframe = false; it.dropOne = false }
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation

    /**
     * Past [reached]'s stretch: the input to read next from where the play goes on ([Extractor.RESULT_SEEK]), or the
     * film's end ([Extractor.RESULT_END_OF_INPUT]); else it is thrown on, a missing article. [length] is the file's.
     */
    private fun skip(reached: GapReached, length: Long, seekPosition: PositionHolder): Int {
        val gap = reached.range
        val resyncs = transport || program
        // MPEG-TS and MPEG-PS find their way again from any packet, but not while they read the file's ends for its length, before their map.
        val map = seekMap?.takeIf { resyncs || it.isSeekable }
        if (map == null) {
            log.info("Missing part at bytes {}-{} can't be skipped: the file's index isn't read yet", gap.first, gap.last)
            throw reached
        }
        jumpsInARow = if (lastSampleUs == C.TIME_UNSET && jumpsInARow > 0) jumpsInARow + 1 else 1
        if (jumpsInARow > MAX_JUMPS_IN_A_ROW) {
            log.info("Missing part at bytes {}-{} not skipped: {} jumps in a row found nothing to play between them", gap.first, gap.last, MAX_JUMPS_IN_A_ROW)
            throw reached
        }
        if (resyncs) meet(gap)
        val from = lastSampleUs.takeIf { it != C.TIME_UNSET } ?: SeekMaps.timeAt(map, gap.first)
        val resume = when {
            transport -> packetAfter(gap.last + 1, length)
            program -> (gap.last + 1).takeUnless { length != C.LENGTH_UNSET.toLong() && it >= length }?.let { SeekPoint(C.TIME_UNSET, it) }
            else -> SeekMaps.firstFrom(map, gap.last + 1)
        }
        if (resume == null) {
            if (!resyncs && map.durationUs == C.TIME_UNSET) {
                log.info("Missing part at bytes {}-{} can't be skipped: the file's index doesn't say its length", gap.first, gap.last)
                throw reached
            }
            log.info("Missing part at bytes {}-{} skipped: nothing of the file's index after it, so the film ends at {}", gap.first, gap.last, time(from))
            return Extractor.RESULT_END_OF_INPUT
        }
        val lost = if (from != null && resume.timeUs != C.TIME_UNSET) " (${time(resume.timeUs - from)} lost)" else ""
        log.info(
            "Missing part at bytes {}-{} skipped, from {} to {}{}: going on from byte {}",
            gap.first, gap.last, time(from), time(resume.timeUs), lost, resume.position,
        )
        // The delegate starts over there as after a seek, but MPEG-TS's and MPEG-PS's: their seek would search for the
        // time given (Media3's estimate, for them) and take it for the next sample's, and they find the packets again by
        // themselves. Not while they search for a seek's time either: they search on, clear of the stretch.
        if (!resyncs) delegate.seek(resume.position, resume.timeUs)
        gaps?.jumped(PlayGaps.Jump(gap, from?.let { it / 1000 }, resume.timeUs.takeIf { it != C.TIME_UNSET }?.let { it / 1000 }))
        videoTracks.forEach { it.waitForKeyframe = true; it.dropOne = program }
        toPack = program
        lastSampleUs = C.TIME_UNSET
        seekPosition.position = resume.position
        return Extractor.RESULT_SEEK
    }

    /**
     * Skips [input] to the next MPEG-PS pack, after a jump: from inside a packet the extractor would take the video's own
     * start codes (a slice's, a picture's) for packets', and skip as far as the bytes after them say, up to 64 KB of the
     * film. False when the file ends first.
     */
    private fun skipToPack(input: ExtractorInput): Boolean {
        val window = ByteArray(PACK_SEARCH_BYTES)
        while (true) {
            input.resetPeekPosition()
            var peeked = 0
            while (peeked < window.size) {
                val n = input.peek(window, peeked, window.size - peeked)
                if (n == C.RESULT_END_OF_INPUT) break
                peeked += n
            }
            input.resetPeekPosition()
            val at = (0..peeked - 4).firstOrNull { i ->
                window[i] == 0.toByte() && window[i + 1] == 0.toByte() && window[i + 2] == 1.toByte() && window[i + 3] == PACK_START.toByte()
            }
            if (at != null) {
                input.skipFully(at)
                return true
            }
            if (peeked < window.size) return false
            // A start code may begin in the window's last three bytes.
            input.skipFully(peeked - 3)
        }
    }

    /** Keeps [gap] among the stretches met, merged with those it touches. */
    private fun meet(gap: LongRange) {
        val touching = met.filter { it.first <= gap.last + 1 && gap.first <= it.last + 1 }
        met -= touching.toSet()
        met += minOf(gap.first, touching.minOfOrNull { it.first } ?: gap.first)..maxOf(gap.last, touching.maxOfOrNull { it.last } ?: gap.last)
        met.sortBy { it.first }
    }

    /** The first MPEG-TS packet (188 bytes from the file's start) at [position] or after; null when the file ends first. */
    private fun packetAfter(position: Long, length: Long): SeekPoint? {
        val packet = (position + TS_PACKET - 1) / TS_PACKET * TS_PACKET
        return if (length != C.LENGTH_UNSET.toLong() && packet >= length) null else SeekPoint(C.TIME_UNSET, packet)
    }

    /** [us] as the log says a time: "1:02.3", or "?" when not known. */
    private fun time(us: Long?): String {
        if (us == null || us == C.TIME_UNSET) return "?"
        val tenths = us / 100_000
        val s = tenths / 10
        return if (s >= 3600) "%d:%02d:%02d.%d".format(Locale.ROOT, s / 3600, s / 60 % 60, s % 60, tenths % 10) else "%d:%02d.%d".format(Locale.ROOT, s / 60, s % 60, tenths % 10)
    }

    /** The delegate's output, as Media3 gave it, keeping the index sent and noting when each track takes a sample. */
    private inner class Output(private val output: ExtractorOutput) : ExtractorOutput {
        private val tracks = HashMap<Int, TrackOutput>()

        // The same output for the same track, as the contract says.
        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) {
            Track(output.track(id, type)).also { if (type == C.TRACK_TYPE_VIDEO) videoTracks += it }
        }

        override fun endTracks() = output.endTracks()

        override fun seekMap(seekMap: SeekMap) {
            this@GapSkippingExtractor.seekMap = seekMap
            gaps?.indexed(seekMap)
            output.seekMap(seekMap)
        }
    }

    /**
     * A track's output, as it was, but that a sample it takes counts as one since the last jump ([lastSampleUs]) and ends
     * a seek's search ([sought]), and that while [waitForKeyframe] (a video track's, after a jump) it drops each sample
     * until a keyframe, the first whatever it is with [dropOne]: their bytes, already written, are never committed, and
     * the next sample's place is reckoned from what was written after them.
     */
    private inner class Track(private val track: TrackOutput) : TrackOutput {
        var waitForKeyframe = false
        var dropOne = false

        override fun durationUs(durationUs: Long) = track.durationUs(durationUs)

        override fun format(format: Format) = track.format(format)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int = track.sampleData(input, length, allowEndOfInput)

        override fun sampleData(data: ParsableByteArray, length: Int) = track.sampleData(data, length)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            track.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = track.sampleData(data, length, sampleDataPart)

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            lastSampleUs = if (lastSampleUs == C.TIME_UNSET) timeUs else maxOf(lastSampleUs, timeUs)
            sought = false
            if (dropOne) {
                dropOne = false
                return
            }
            if (waitForKeyframe && flags and C.BUFFER_FLAG_KEY_FRAME == 0) return
            waitForKeyframe = false
            track.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }
    }

    /**
     * [input] as it is, but that a search for a seek's time (Media3's BinarySearchSeeker, which MPEG-TS and MPEG-PS seek
     * with, looking at the file a window at a time from the input's position, as long as the file goes) keeps clear of
     * the stretches [met]: from before one, the file ends at it, and from inside one, where the input is, so the search
     * finds no timestamp there and goes on from it, as after any search that finds none, and the read reaches the
     * stretch and skips it. The delegate's own look at the file's length, its first in each read, is the file's: only
     * the search's, after it, see the stretches.
     */
    private class ClearOfGaps(private val input: ExtractorInput, private val met: List<LongRange>) : ExtractorInput by input {
        private var asked = 0

        override fun getLength(): Long {
            val length = input.length
            if (asked++ == 0 || length == C.LENGTH_UNSET.toLong()) return length
            val position = input.position
            val next = met.firstOrNull { it.last >= position } ?: return length
            return if (next.first <= position) position else minOf(length, next.first)
        }
    }

    companion object {
        /** Jumps in a row, with no sample between them, before a stretch is no longer skipped. */
        const val MAX_JUMPS_IN_A_ROW = 8

        private const val TS_PACKET = 188L

        /** MPEG-PS's pack start code's last byte (after 00 00 01), and how much is looked at a time for it. */
        private const val PACK_START = 0xBA
        private const val PACK_SEARCH_BYTES = 4096
    }
}
