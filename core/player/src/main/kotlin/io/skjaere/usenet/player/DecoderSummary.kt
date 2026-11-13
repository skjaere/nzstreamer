package io.skjaere.usenet.player

import io.skjaere.usenet.releases.AudioCodec
import io.skjaere.usenet.releases.VideoCodec
import java.util.Locale

/**
 * What the decoders play, as the log header and This device say it: each codec's largest size and its hardware's, by
 * bit depth; Dolby Vision and its profiles; the codecs nothing decodes; the audio decoded and passed through over HDMI.
 * One probe's, whatever the display ([MediaCodecDeviceCapabilities.decoderSummary]).
 */
data class DecoderSummary(
    /** Each codec a decoder takes, in [VideoCodec] order: 8-bit, then 10-bit where a decoder lists a 10-bit profile. */
    val video: List<VideoDecoding>,
    /** Whether any decoder takes Dolby Vision. */
    val dolbyVision: Boolean,
    /** The profiles the player's Dolby Vision decoders list, each at its highest level, in the order of their [DolbyVisionProfile.codecs]. */
    val dolbyVisionProfiles: List<DolbyVisionProfile>,
    /** The codecs nothing decodes, in [VideoCodec] order. */
    val noDecoder: List<VideoCodec>,
    /** In [AudioCodec] order. */
    val audioDecoded: List<AudioCodec>,
    /** What the TV or receiver takes over HDMI, as read when the capabilities were; in [AudioCodec] order. */
    val audioPassedThrough: List<AudioCodec>,
) {
    /** The log's "Video: …" and "Audio: …" lines. */
    val lines: List<String>
        get() {
            val video = video.joinToString { decoding ->
                val name = if (decoding.tenBit) "${decoding.codec.label} 10-bit" else decoding.codec.label
                "$name ${sizeText(decoding.maxHeight)} (${hardwareText(decoding.hardwareMaxHeight)})"
            }
            val dolbyVision = if (!dolbyVision) "no Dolby Vision" else "Dolby Vision ${dolbyVisionProfiles.joinToString { it.codecs }.ifEmpty { "with no profiles listed" }}"
            val none = noDecoder.joinToString { it.label }
            val decoded = audioDecoded.joinToString { it.label }.ifEmpty { "none" }
            val passed = audioPassedThrough.joinToString { it.label }.ifEmpty { "none" }
            val videoParts = listOf(video.ifEmpty { "none decoded" }, dolbyVision) + listOfNotNull(none.ifEmpty { null }?.let { "no decoder for $it" })
            return listOf("Video: ${videoParts.joinToString("; ")}", "Audio: $decoded decoded; $passed passed through over HDMI")
        }

    private fun sizeText(height: Int?) = when (height) {
        null -> "size unknown"
        0 -> "below 480p"
        else -> "${height}p"
    }

    private fun hardwareText(height: Int?) = when (height) {
        null -> "hardware unknown"
        0 -> "no hardware"
        else -> "hardware ${height}p"
    }
}

/**
 * One codec at one bit depth, as [MediaCodecDeviceCapabilities.maxHeight] and [MediaCodecDeviceCapabilities.hardwareMaxHeight]
 * answer it: the largest height some decoder plays (0 below 480p, null when a decoder hides its limits), and the
 * largest a hardware one does (0 when none is hardware, null when unknown).
 */
data class VideoDecoding(val codec: VideoCodec, val tenBit: Boolean, val maxHeight: Int?, val hardwareMaxHeight: Int?)

/** A Dolby Vision [profile] number (4, 5, 7, 8, 9…) at its [level] number (0 when none is listed). */
data class DolbyVisionProfile(val profile: Int, val level: Int) {
    /** As Media3 names a track's: "dvhe.08.09" for profile 8 at level 9 (UHD at 60 fps). */
    val codecs: String get() = String.format(Locale.ROOT, "%s.%02d", name, level)

    /** Without the level: "dvhe.08". */
    val name: String
        get() = String.format(
            Locale.ROOT, "%s.%02d",
            when (profile) {
                0, 1, 9 -> "dvav"
                10 -> "dav1"
                else -> "dvhe"
            },
            profile,
        )

    companion object {
        /** From MediaCodecInfo.CodecProfileLevel's constants, a bit each: DolbyVisionProfileDvheSt at DolbyVisionLevelUhd60 is 8 at 9. */
        internal fun of(profile: Int, level: Int) =
            DolbyVisionProfile(Integer.numberOfTrailingZeros(profile), if (level > 0) Integer.numberOfTrailingZeros(level) + 1 else 0)
    }
}
