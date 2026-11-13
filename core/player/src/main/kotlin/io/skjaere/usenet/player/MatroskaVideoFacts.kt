package io.skjaere.usenet.player

import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi

/**
 * What the player's Matroska extractor ([FlaggedMatroskaExtractor]) reads of a video track beyond Media3's own [Format],
 * for the player's File details. It rides in the format's metadata, as Media3's chapters do, so the player and the track
 * probe both see it. A data class: two reads of a file give equal formats, as Media3 takes an unequal one for a change.
 */
data class MatroskaVideoFacts(
    /**
     * The track's DefaultDuration, one frame's length in ns; null without one (a variable frame rate). Media3 1.11.1 times
     * laced blocks by it and leaves Format.frameRate unset for Matroska. It isn't written there: Media3 sets the codec's
     * operating rate and the surface's frame rate from that field, so the label would change how the video plays.
     */
    val frameDurationNs: Int? = null,
    /** What a Dolby Vision track's configuration record says that Media3 leaves out (it keeps the profile and level, in the codecs). */
    val dolbyVision: DolbyVisionRecord? = null,
    /**
     * The codecs of a Dolby Vision track's codec as Media3 names them before the record's replace them ("hvc1.2.4.L153.B0",
     * HEVC Main 10 at level 5.1); null when Media3 names none for it.
     */
    val baseCodecs: String? = null,
    /**
     * The MaxCLL the file states (its Colour's MaxCLL element), in nits; null without one. Media3's static metadata can't
     * say: for a track without one it holds Media3's own default of 1000.
     */
    val maxCll: Int? = null,
) : Metadata.Entry {
    /** Frames a second, from [frameDurationNs]. */
    val frameRate: Double? get() = frameDurationNs?.let { 1e9 / it }
}

/** The facts of a Dolby Vision configuration record (dvcC, dvvC) that Media3's DolbyVisionConfig drops. */
data class DolbyVisionRecord(
    /**
     * dv_bl_signal_compatibility_id, what the base layer is to a player without Dolby Vision: 0 nothing it can show
     * (profile 5), 1 HDR10, 2 SDR, 4 HLG, 6 HDR10 as a UHD Blu-ray has it (profile 7).
     */
    val compatibility: Int,
    /** el_present_flag: the stream has an enhancement layer (profile 7's). It says nothing of whether a decoder uses it. */
    val enhancementLayer: Boolean,
    /** bl_present_flag: the stream has the base layer. */
    val baseLayer: Boolean,
) {
    companion object {
        /** [record] as the DOVIDecoderConfigurationRecord lays it out (the layer flags in byte 3, the id in byte 4's top bits); null when shorter, or none. */
        fun parse(record: ByteArray?): DolbyVisionRecord? = if (record == null || record.size < 5) null else DolbyVisionRecord(
            compatibility = (record[4].toInt() and 0xF0) shr 4,
            enhancementLayer = (record[3].toInt() and 0x02) != 0,
            baseLayer = (record[3].toInt() and 0x01) != 0,
        )
    }
}

/** What the player's Matroska extractor read of this video track beyond the format; null for a track it added nothing to. */
@get:androidx.annotation.OptIn(UnstableApi::class)
val Format.matroskaFacts: MatroskaVideoFacts? get() = metadata?.getFirstEntryOfType(MatroskaVideoFacts::class.java)
