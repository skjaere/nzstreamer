@file:androidx.annotation.OptIn(UnstableApi::class)

package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import io.skjaere.usenet.releases.Hdr
import io.skjaere.usenet.releases.VideoCodec
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/** A fact as File details show it: its [value], and a [note] under it in the muted colour. */
data class Fact(val value: String, val note: String? = null)

/**
 * A video track's HDR as File details show it: [types] as chips, Dolby Vision first, empty for video without HDR; the
 * [note] says a Dolby Vision track's profile, level and base layer, or HDR10's mastering display and MaxCLL.
 */
data class HdrFact(val types: List<Hdr>, val note: String?)

/**
 * A video track as File details describe it, in the approved mockups' words ("HEVC Main 10", "3840 × 1606 · 2160p ·
 * 2.39:1", "23.976 fps"), each fact null where the file doesn't state it, so its row is left out; and beside the words what the
 * file states for certain ([resolutionClass], [codec], [bits], [hdr]'s types), to set against the release name's
 * claims. [VideoTracks.label] stays the probe's and the guard's wording, which its failures and their tests read.
 */
data class VideoDetails(
    /** A friendly name, noting the level and tier: "HEVC Main 10", "level 5.1, high tier". A Dolby Vision track's is the codec's under it. */
    val format: Fact?,
    /** The size the file stores, its class as release names have it, and the ratio it shows at ([aspectRatio]): "3840 × 1606 · 2160p · 2.39:1". */
    val resolution: Fact?,
    /** Matroska's DefaultDuration ([MatroskaVideoFacts]), else Media3's own (an MP4's), as [formatRate] writes it: "23.976 fps". */
    val frameRate: Fact?,
    /** "10-bit". */
    val bitDepth: Fact?,
    val hdr: HdrFact,
    /** The primaries and the transfer, noting the range: "BT.2020 · PQ", "limited range". */
    val colour: Fact?,
    /** The resolution class, 2160 for 3840x1606, as the ranker reads a release's. */
    val resolutionClass: Int?,
    /** The codec; a Dolby Vision track's is the one under it. Null for one the ranker doesn't name. */
    val codec: VideoCodec?,
    /** Bits per sample. */
    val bits: Int?,
) {
    companion object {
        fun of(format: Format): VideoDetails {
            val dolbyVision = DolbyVisionTrack.of(format)
            val bits = format.colorInfo?.lumaBitdepth?.takeIf { it > 0 }
                ?: format.codecs?.takeIf { format.sampleMimeType == MimeTypes.VIDEO_AV1 }?.split('.')?.getOrNull(3)?.toIntOrNull()
            val resolutionClass = VideoTracks.resolutionClass(format)
            val rate = format.matroskaFacts?.frameRate ?: format.frameRate.takeIf { it > 0 }?.toDouble()
            return VideoDetails(
                format = formatOf(format, dolbyVision, bits),
                resolution = aspectRatio(format.width, format.height, format.pixelWidthHeightRatio)?.let { Fact("${format.width} × ${format.height} · ${resolutionClass}p · $it") },
                frameRate = rate?.let { Fact("${formatRate(it)} fps") },
                bitDepth = bits?.let { Fact("$it-bit") },
                hdr = dolbyVision?.let { HdrFact(it.types, it.note) } ?: hdrOf(format),
                colour = colourOf(format.colorInfo),
                resolutionClass = resolutionClass,
                codec = CODECS[dolbyVision?.baseMime ?: format.sampleMimeType],
                bits = bits,
            )
        }

        /** A Dolby Vision track's is the codec's under it, when the Matroska extractor kept its codecs, else "Dolby Vision". */
        private fun formatOf(format: Format, dolbyVision: DolbyVisionTrack?, bits: Int?): Fact? = when {
            dolbyVision == null -> format.sampleMimeType?.let { codecOf(it, format.codecs, bits) }
            dolbyVision.baseMime != null && dolbyVision.baseCodecs != null -> codecOf(dolbyVision.baseMime, dolbyVision.baseCodecs, bits)
            else -> Fact("Dolby Vision")
        }

        /** [mime]'s name with the profile [codecs] give (RFC 6381's), noting the level and tier; the codec alone where they say nothing. */
        private fun codecOf(mime: String, codecs: String?, bits: Int?): Fact {
            val parts = codecs?.split('.').orEmpty()
            val profiled = when (mime) {
                MimeTypes.VIDEO_H264 -> avc(parts)
                MimeTypes.VIDEO_H265 -> hevc(parts)
                MimeTypes.VIDEO_AV1 -> av1(parts, bits)
                MimeTypes.VIDEO_VP9 -> vp9(parts)
                else -> null
            }
            return profiled ?: Fact(listOfNotNull(VideoTracks.NAMES[mime] ?: mime, bits?.takeIf { it >= 10 }?.let { "$it-bit" }).joinToString(" "))
        }

        /** "avc1.640029": the profile, its constraint flags and the level, a byte each. */
        private fun avc(parts: List<String>): Fact? {
            val hex = parts.getOrNull(1)?.takeIf { it.length == 6 } ?: return null
            val (profile, constraints, level) = hex.chunked(2).map { it.toIntOrNull(16) ?: return null }
            // constraint_set1 makes Baseline the constrained one, which most decoders take.
            val name = if (profile == 66 && (constraints and 0x40) != 0) "Constrained Baseline" else AVC_PROFILES[profile]
            return Fact(listOfNotNull("H.264", name).joinToString(" "), "level " + if (level == 9) "1b" else tenths(level))
        }

        /** "hvc1.2.4.H153.90": the profile (after a profile space's letter), its compatibility flags, the tier and level (30 a step). */
        private fun hevc(parts: List<String>): Fact? {
            val profile = parts.getOrNull(1)?.trimStart('A', 'B', 'C')?.toIntOrNull() ?: return null
            val tierLevel = parts.getOrNull(3)
            val level = tierLevel?.drop(1)?.toIntOrNull()
            val note = level?.let { l -> "level ${l / 30}" + (l % 30 / 3).let { if (it > 0) ".$it" else "" } + if (tierLevel.startsWith('H')) ", high tier" else "" }
            return Fact(listOfNotNull("HEVC", HEVC_PROFILES[profile]).joinToString(" "), note)
        }

        /** "av01.0.13H.10": the profile, the level's index and the tier, the bit depth. Its bit depth isn't in the profile's name, so it is said. */
        private fun av1(parts: List<String>, bits: Int?): Fact? {
            val profile = parts.getOrNull(1)?.toIntOrNull() ?: return null
            val levelTier = parts.getOrNull(2)?.takeIf { it.length == 3 } ?: return null
            val level = levelTier.take(2).toIntOrNull() ?: return null
            val depth = parts.getOrNull(3)?.toIntOrNull() ?: bits
            val name = listOfNotNull("AV1", AV1_PROFILES[profile], depth?.takeIf { it >= 10 }?.let { "$it-bit" }).joinToString(" ")
            return Fact(name, "level ${2 + level / 4}.${level % 4}" + if (levelTier[2] == 'H') ", high tier" else "")
        }

        /** "vp09.02.41.10": the profile, the level in tenths, the bit depth. */
        private fun vp9(parts: List<String>): Fact? {
            val profile = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return Fact("VP9 Profile $profile", parts.getOrNull(2)?.toIntOrNull()?.let { "level ${tenths(it)}" })
        }

        /** A level counted in tenths, as H.264's and VP9's are: 41 is "4.1", 40 "4". */
        private fun tenths(level: Int) = "${level / 10}" + if (level % 10 > 0) ".${level % 10}" else ""

        private fun hdrOf(format: Format): HdrFact = when (format.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> HdrFact(listOf(Hdr.Hdr10), masteringOf(format))
            C.COLOR_TRANSFER_HLG -> HdrFact(listOf(Hdr.Hlg), null)
            else -> HdrFact(emptyList(), null)
        }

        /**
         * HDR10's static metadata as Media3 lays it out (CTA-861.3's, little-endian): the mastering display's peak in nits
         * at byte 17, MaxCLL at 21. Media3 reads it from Matroska's MasteringMetadata, never from the HEVC stream's SEI.
         * 0 is CTA-861.3's unknown. A Matroska track's MaxCLL is the one the player's extractor saw in the file: Media3 writes
         * its own default of 1000 for a file without one.
         */
        private fun masteringOf(format: Format): String? {
            val info = format.colorInfo?.hdrStaticInfo
            if (info == null || info.size != 25 || info[0].toInt() != 0) return null
            fun uint16(at: Int) = (info[at].toInt() and 0xFF) or ((info[at + 1].toInt() and 0xFF) shl 8)
            val maxCll = if (format.containerMimeType in MATROSKA) format.matroskaFacts?.maxCll else uint16(21)
            val parts = listOfNotNull(uint16(17).takeIf { it > 0 }?.let { "mastering display $it nits" }, maxCll?.takeIf { it > 0 }?.let { "MaxCLL $it" })
            return parts.joinToString(", ").ifEmpty { null }?.let { "$it (from the file)" }
        }

        /** A range alone says nothing worth a row. */
        private fun colourOf(color: ColorInfo?): Fact? {
            color ?: return null
            val value = listOfNotNull(PRIMARIES[color.colorSpace], TRANSFERS[color.colorTransfer]).joinToString(" · ")
            return if (value.isEmpty()) null else Fact(value, RANGES[color.colorRange])
        }

        private val AVC_PROFILES = mapOf(66 to "Baseline", 77 to "Main", 88 to "Extended", 100 to "High", 110 to "High 10", 122 to "High 4:2:2", 244 to "High 4:4:4")
        private val HEVC_PROFILES = mapOf(1 to "Main", 2 to "Main 10", 3 to "Main Still Picture", 4 to "Range Extensions")
        private val AV1_PROFILES = mapOf(0 to "Main", 1 to "High", 2 to "Professional")
        private val PRIMARIES = mapOf(C.COLOR_SPACE_BT709 to "BT.709", C.COLOR_SPACE_BT601 to "BT.601", C.COLOR_SPACE_BT2020 to "BT.2020")
        private val TRANSFERS = mapOf(
            C.COLOR_TRANSFER_SDR to "SDR", C.COLOR_TRANSFER_ST2084 to "PQ", C.COLOR_TRANSFER_HLG to "HLG",
            C.COLOR_TRANSFER_LINEAR to "linear", C.COLOR_TRANSFER_SRGB to "sRGB", C.COLOR_TRANSFER_GAMMA_2_2 to "gamma 2.2",
        )
        private val MATROSKA = setOf(MimeTypes.VIDEO_MATROSKA, MimeTypes.VIDEO_WEBM)
        private val RANGES = mapOf(C.COLOR_RANGE_LIMITED to "limited range", C.COLOR_RANGE_FULL to "full range")
        private val CODECS = mapOf(
            MimeTypes.VIDEO_H264 to VideoCodec.H264, MimeTypes.VIDEO_H265 to VideoCodec.Hevc, MimeTypes.VIDEO_AV1 to VideoCodec.Av1,
            MimeTypes.VIDEO_VP9 to VideoCodec.Vp9, MimeTypes.VIDEO_MPEG2 to VideoCodec.Mpeg2, MimeTypes.VIDEO_VC1 to VideoCodec.Vc1,
            MimeTypes.VIDEO_MP4V to VideoCodec.Xvid, MimeTypes.VIDEO_DIVX to VideoCodec.Xvid,
        )
    }
}

/**
 * A Dolby Vision track's profile, level and base layer: from its codecs ("dvhe.08.06"), and its record where the player's
 * Matroska extractor read one ([MatroskaVideoFacts]). Without a record (an MP4's) the base layer is the one the profile
 * allows, or for profile 8 the one the track's own transfer shows: Media3 keeps the base layer's colour beside the
 * Dolby Vision MIME type, PQ for 8.1, HLG for 8.4.
 */
internal class DolbyVisionTrack private constructor(format: Format) {
    private val parts = format.codecs?.split('.').orEmpty()
    private val record = format.matroskaFacts?.dolbyVision
    val profile: Int? = parts.getOrNull(1)?.toIntOrNull()
    private val level: Int? = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }

    /** dv_bl_signal_compatibility_id ([DolbyVisionRecord.compatibility]). */
    private val compatibility: Int? = record?.compatibility ?: profile?.let { ONLY_COMPATIBILITY[it] }
        ?: TRANSFER_COMPATIBILITY[format.colorInfo?.colorTransfer].takeIf { profile == 8 }

    /** The codec under the record, by the codecs' prefix: "dvhe" and "dvh1" are HEVC, "dvav" and "dva1" H.264, "dav1" AV1. */
    val baseMime: String? = BASE_MIMES[parts.firstOrNull()]

    /** That codec's codecs, as the Matroska extractor kept them ([MatroskaVideoFacts.baseCodecs]). */
    val baseCodecs: String? = format.matroskaFacts?.baseCodecs

    /** What the base layer shows as on a player without Dolby Vision; null for none it can show, or unknown. */
    val baseLayer: String? = when (compatibility) {
        1, 6 -> "HDR10"
        2 -> "SDR"
        4 -> "HLG"
        else -> null
    }

    val types: List<Hdr> = listOfNotNull(Hdr.DolbyVision, Hdr.Hdr10.takeIf { baseLayer == "HDR10" }, Hdr.Hlg.takeIf { baseLayer == "HLG" })

    /**
     * "Dolby Vision profile 8.1, level 6 · HDR10 base layer", "Dolby Vision profile 5, level 6 · no HDR10 base layer", or a
     * profile 7's "· HDR10 base layer, enhancement layer": the layer is in the stream, which says nothing of whether a
     * decoder uses it. Profiles 8 and 10 are named with their base layer's id, as releases name them.
     */
    val note: String?
        get() {
            val profile = profile ?: return null
            val name = if (profile in NAMED_WITH_BASE_LAYER && compatibility != null) "$profile.$compatibility" else "$profile"
            val layers = listOfNotNull(
                baseLayer?.let { "$it base layer" } ?: "no HDR10 base layer".takeIf { compatibility == 0 },
                "enhancement layer".takeIf { record?.enhancementLayer == true },
            )
            return "Dolby Vision profile $name" + level?.let { ", level $it" }.orEmpty() + layers.joinToString(", ").ifEmpty { null }?.let { " · $it" }.orEmpty()
        }

    companion object {
        fun of(format: Format): DolbyVisionTrack? = if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) DolbyVisionTrack(format) else null

        // The profiles whose base layer can be only one thing: 4 and 9 SDR, 5 none, 7 a UHD Blu-ray's HDR10.
        private val ONLY_COMPATIBILITY = mapOf(4 to 2, 5 to 0, 7 to 6, 9 to 2)
        private val TRANSFER_COMPATIBILITY = mapOf(C.COLOR_TRANSFER_ST2084 to 1, C.COLOR_TRANSFER_HLG to 4, C.COLOR_TRANSFER_SDR to 2)
        private val NAMED_WITH_BASE_LAYER = setOf(8, 10)
        private val BASE_MIMES = mapOf(
            "dvhe" to MimeTypes.VIDEO_H265, "dvh1" to MimeTypes.VIDEO_H265, "dvav" to MimeTypes.VIDEO_H264, "dva1" to MimeTypes.VIDEO_H264,
            "dav1" to MimeTypes.VIDEO_AV1,
        )
    }
}

/**
 * A frame or refresh rate as File details write it: snapped to the usual rates, NTSC's as x/1.001 ("23.976", "59.94"),
 * else with up to three decimals ("12.5"); the digits the [locale]'s, by default the user's, as sizes are (a log's are
 * the root locale's). A rate read from a whole number of ns, or Media3's from an MP4's sample count, lands a little off
 * the true one; 0.02 % is within it and well short of the 0.1 % between 23.976 and 24.
 */
fun formatRate(rate: Double, locale: Locale = Locale.getDefault(Locale.Category.FORMAT)): String {
    val snapped = USUAL_RATES.firstOrNull { abs(rate - it) <= it * 0.0002 } ?: rate
    return DecimalFormat("0.###", DecimalFormatSymbols.getInstance(locale)).apply { roundingMode = RoundingMode.HALF_UP }.format(snapped)
}

/**
 * The ratio a picture of [width] × [height] pixels shows at (W11): its width times the pixel aspect ratio (Media3's
 * [pixelWidthHeightRatio]; 0 or not known is square) over its height. Within 1 % of 16:9 or 4:3 it is named ("16:9"),
 * else written with two decimals to 1 ("2.39:1"); pixels that aren't square, as a DVD's, add "(anamorphic)": "16:9
 * (anamorphic)". The digits the [locale]'s, by default the user's, as frame rates are (a log's the root locale's); null
 * without a size.
 */
fun aspectRatio(width: Int, height: Int, pixelWidthHeightRatio: Float, locale: Locale = Locale.getDefault(Locale.Category.FORMAT)): String? {
    if (width <= 0 || height <= 0) return null
    val pixel = pixelWidthHeightRatio.toDouble().takeIf { it > 0 && it.isFinite() } ?: 1.0
    val ratio = width * pixel / height
    val named = NAMED_RATIOS.entries.firstOrNull { (value, _) -> abs(ratio - value) <= value * RATIO_TOLERANCE }?.value
    val shown = named ?: (DecimalFormat("0.00", DecimalFormatSymbols.getInstance(locale)).apply { roundingMode = RoundingMode.HALF_UP }.format(ratio) + ":1")
    // As near square as a named ratio is to its name: a muxer's rounding of the display size isn't anamorphic.
    return if (abs(pixel - 1) > RATIO_TOLERANCE) "$shown (anamorphic)" else shown
}

/** The ratios named rather than written out, by their value. */
private val NAMED_RATIOS = mapOf(16.0 / 9 to "16:9", 4.0 / 3 to "4:3")

/** How near a named ratio a picture's is to be called by its name: 1 %. */
private const val RATIO_TOLERANCE = 0.01

private val USUAL_RATES = listOf(24, 30, 48, 60, 120).flatMap { listOf(it / 1.001, it.toDouble()) } + listOf(25.0, 50.0)
