package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import io.skjaere.usenet.releases.MediaInfo

/**
 * A file's video tracks as the track probe and the player's guard name them: in the words the ranker uses for releases
 * ("2160p HEVC 10-bit"), so the play sheet, the release list and the player's panel read alike.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object VideoTracks {
    /**
     * [format] as "2160p HEVC 10-bit", "2160p Dolby Vision profile 7" or "1080p VC-1": its resolution as an indexer's
     * MediaInfo sizes read (a film cropped to 2.40:1 is still 1080p), its codec, and ten bits where its configuration says
     * so, except for Dolby Vision, whose profile says it.
     */
    fun label(format: Format): String {
        val resolution = resolutionClass(format)
        val mime = format.sampleMimeType
        val dolbyVision = mime == MimeTypes.VIDEO_DOLBY_VISION
        val codec = when {
            // Media3's codecs for it are "dvhe.07.06": the profile, then the level.
            dolbyVision -> format.codecs?.split('.')?.getOrNull(1)?.toIntOrNull()?.let { "Dolby Vision profile $it" } ?: "Dolby Vision"
            else -> NAMES[mime] ?: mime ?: "unknown codec"
        }
        val tenBit = !dolbyVision && (format.colorInfo?.lumaBitdepth ?: 0) >= 10
        return listOfNotNull(resolution?.let { "${it}p" }, codec, "10-bit".takeIf { tenBit }).joinToString(" ")
    }

    /**
     * The video of [tracks] when it has some and the player selected none of it, named ([label]): no decoder takes it, so
     * the sound would play over a black screen. Null when there is no video, or some of it is selected; video the
     * extractor dropped (a codec it doesn't read) is no video here.
     */
    fun leftOut(tracks: Tracks): List<String>? {
        val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        return if (video.isEmpty() || video.any { it.isSelected }) null else labels(video)
    }

    /** [format]'s resolution class as an indexer's MediaInfo sizes read: 1080 for a film cropped to 1920x800; null without a height. */
    internal fun resolutionClass(format: Format): Int? = when {
        format.height <= 0 -> null
        format.width <= 0 -> format.height
        else -> MediaInfo.resolutionClass("${format.width}x${format.height}") ?: format.height
    }

    /** The [groups]' tracks named, each name once, in the file's order. */
    internal fun labels(groups: List<Tracks.Group>): List<String> =
        groups.flatMap { group -> (0 until group.length).map { label(group.getTrackFormat(it)) } }.distinct()

    /** Codecs by their MIME type, as the release list names them. */
    internal val NAMES = mapOf(
        MimeTypes.VIDEO_H264 to "H.264", MimeTypes.VIDEO_H265 to "HEVC", MimeTypes.VIDEO_AV1 to "AV1", MimeTypes.VIDEO_VP9 to "VP9",
        MimeTypes.VIDEO_VP8 to "VP8", MimeTypes.VIDEO_MPEG2 to "MPEG-2", MimeTypes.VIDEO_MP4V to "MPEG-4", MimeTypes.VIDEO_DIVX to "DivX",
        MimeTypes.VIDEO_VC1 to "VC-1", MimeTypes.VIDEO_H263 to "H.263", MimeTypes.VIDEO_UNKNOWN to "unknown codec",
    )
}
