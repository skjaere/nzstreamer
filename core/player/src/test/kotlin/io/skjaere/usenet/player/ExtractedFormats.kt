package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

/** The formats an extractor sends for a media fixture, as the JVM tests read them. */
@androidx.annotation.OptIn(UnstableApi::class)
internal object ExtractedFormats {
    /** The test resource [name]'s bytes: the device tests' media fixtures (see the README there). */
    fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResource(name)) { "$name not on the test classpath" }.readBytes()

    /**
     * [fixture], an HDR10 Matroska file, with its MaxCLL element made a Void element of the same length: as a file muxed
     * from an encode with a mastering display but no MaxCLL (x265's --master-display without --max-cll) has none.
     */
    fun withoutMaxCll(fixture: ByteArray): ByteArray {
        // MaxCLL (0x55BC), two bytes long; Void (0xEC) with three bytes of nothing takes its five.
        val maxCll = byteArrayOf(0x55, 0xBC.toByte(), 0x82.toByte())
        val at = (0..fixture.size - maxCll.size).first { i -> maxCll.indices.all { fixture[i + it] == maxCll[it] } }
        return fixture.copyOf().also { byteArrayOf(0xEC.toByte(), 0x83.toByte(), 0, 0, 0).copyInto(it, at) }
    }

    /** The formats [extractor] sends for [fixture] until it ends its tracks, the last one per track id. */
    fun read(fixture: ByteArray, extractor: Extractor): Map<String, Format> {
        val output = Formats()
        extractor.init(output)
        val position = PositionHolder()
        var input = inputAt(fixture, 0)
        while (!output.ended) {
            when (extractor.read(input, position)) {
                Extractor.RESULT_SEEK -> input = inputAt(fixture, position.position)
                Extractor.RESULT_END_OF_INPUT -> error("the fixture ended before its tracks did")
            }
        }
        return output.formats
    }

    private fun inputAt(fixture: ByteArray, position: Long) =
        DefaultExtractorInput(Bytes(fixture, position.toInt()), position, fixture.size.toLong())

    private class Bytes(private val data: ByteArray, private var position: Int) : DataReader {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position == data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - position)
            data.copyInto(buffer, offset, position, position + n)
            position += n
            return n
        }
    }

    private class Formats : ExtractorOutput {
        val formats = linkedMapOf<String, Format>()
        var ended = false

        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
            override fun format(format: Format) {
                formats[checkNotNull(format.id)] = format
            }
        }

        override fun endTracks() {
            ended = true
        }

        override fun seekMap(seekMap: SeekMap) = Unit
    }
}
