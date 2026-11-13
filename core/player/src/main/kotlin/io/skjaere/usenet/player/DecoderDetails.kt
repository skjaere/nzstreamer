package io.skjaere.usenet.player

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/** A decoder of a MIME type as Media3 lists it for playback: its [name], and whether Media3 takes it for [hardware]. */
data class ListedDecoder(val name: String, val hardware: Boolean)

/** File details' Decoder row: the decoder the player started, and what it is. */
object DecoderDetails {
    /**
     * The decoder [name] the player started for [format]: "hardware" or "software" as Media3's own lists mark it ([decoders],
     * [playback] on a device), which the player chooses from and the release list's "no hardware decoder" judges by, so
     * the two can't disagree. A Dolby Vision track notes whether the decoder takes Dolby Vision, or only decodes the base
     * layer under it, as Media3 falls back to where it chose no Dolby Vision decoder, or passes them over: on a display
     * that doesn't show Dolby Vision ([displayDolbyVision], as Media3 reads it) for the plain decoders profiles 4, 8, 9
     * and 10 fall back to, where there are any. A decoder no list has is named alone.
     */
    @OptIn(UnstableApi::class)
    fun of(name: String, format: Format, decoders: (mime: String) -> List<ListedDecoder>, displayDolbyVision: Boolean): Fact {
        val mime = format.sampleMimeType ?: return Fact(name)
        val dolbyVision = DolbyVisionTrack.of(format)
        // The plain decoders Media3 chooses from in their place; empty where it keeps to them (the display shows Dolby
        // Vision, or no plain decoder is listed for the profile).
        val passedOver = if (dolbyVision != null && !displayDolbyVision) MediaCodecUtil.getAlternativeCodecMimeType(format)?.let(decoders).orEmpty() else emptyList()
        // Some chips list one decoder under both types: the track's own is asked first, unless Media3 passed those over.
        if (passedOver.isEmpty()) {
            decoders(mime).firstOrNull { it.name == name }?.let { own ->
                return Fact(name, listOfNotNull(own.kind, "Dolby Vision".takeIf { mime == MimeTypes.VIDEO_DOLBY_VISION }).joinToString(" · "))
            }
        }
        dolbyVision ?: return Fact(name)
        val base = passedOver.ifEmpty { dolbyVision.baseMime?.let(decoders).orEmpty() }.firstOrNull { it.name == name } ?: return Fact(name)
        return Fact(name, "${base.kind} · decodes the ${listOfNotNull(dolbyVision.baseLayer, "base layer").joinToString(" ")}")
    }

    /**
     * Media3's decoders of [mime] for playback, which the player chooses from; none when the query fails. Media3 keeps
     * the lists once asked, but the first ask is slow: call it off the main thread.
     */
    @OptIn(UnstableApi::class)
    fun playback(mime: String): List<ListedDecoder> = try {
        MediaCodecUtil.getDecoderInfos(mime, false, false).map { ListedDecoder(it.name, it.hardwareAccelerated) }
    } catch (e: MediaCodecUtil.DecoderQueryException) {
        emptyList()
    }

    private val ListedDecoder.kind get() = if (hardware) "hardware" else "software"
}
