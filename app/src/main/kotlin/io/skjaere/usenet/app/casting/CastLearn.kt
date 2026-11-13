package io.skjaere.usenet.app.casting

import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.data.CastDeviceMemory
import io.skjaere.usenet.data.CastVideoCodec
import io.skjaere.usenet.releases.Hdr
import io.skjaere.usenet.releases.MediaInfo

/**
 * What a Cast device teaches of what it plays (L128, L131), from the media statuses of one play of ours there, as each is
 * heard: that it reports the picture's size at all; a 2160-line picture of the file's video (its codec at its bit depth);
 * an HDR file shown as SDR; Dolby Vision shown; and, on a device that has reported pictures before, PLAYING for
 * [DARK_MS] with no picture of the file's video, which then gives no picture there. Without that report nothing is learned
 * from silence, so a receiver that never says its picture is never marked wrong. A later picture clears what was learned
 * against it: no picture of its video, and SDR once HDR is shown. [device] is the device's Cast id, [load] our load's
 * content id (another sender's media teaches nothing), [tracks] what the probe read of the file. Nothing about sound, ever:
 * a device's silence is never an input (W41).
 *
 * Each answer is the device's facts ([CastDeviceMemory]) once they change, seen at the wall clock's time given; null when
 * nothing changed, so they are written only on a change. Statuses and ticks come in turn, on one thread.
 */
class CastLearn(private val device: String, private val load: String, tracks: ProbedTracks?) {
    /** The file's video as the device decodes it, as its facts name it; null for a video the probe didn't read, or none. */
    private val video: CastVideoCodec? =
        tracks?.video?.firstOrNull()?.let(CastVerdict::decodedAs)?.let { CastVideoCodec(it.codec.name, it.tenBit) }

    /**
     * The file's video has HDR the device could show without Dolby Vision, so SDR of it says it shows none: HDR10 or HLG,
     * or Dolby Vision over such a base (profiles 7 and 8). Profile 5's base is no HDR a device without Dolby Vision shows.
     */
    private val hdr: Boolean = tracks?.video?.firstOrNull()?.let { it.hdr != null && (it.hdr != Hdr.DolbyVision || it.dolbyVisionProfile in HDR_BASES) } == true

    /** Since when (the statuses' clock) the device has played this load with no picture; null while not so. */
    private var dark: Long? = null

    /** It showed a picture of this load: its silence after is a status's, not no picture's. */
    private var shown = false

    /** [memory] (null: nothing kept of the device yet) with what [status] shows, at [wall]; null when it shows nothing new. */
    fun heard(memory: CastDeviceMemory?, status: CastEvent.Status, wall: Long): CastDeviceMemory? {
        if (status.contentId != load) return null
        val picture = status.video
        dark = if (status.state == CastPlayerState.Playing && picture == null && !shown) dark ?: status.at else null
        picture ?: return null
        shown = true
        val before = memory ?: CastDeviceMemory(device)
        val uhd = (MediaInfo.resolutionClass("${picture.width}x${picture.height}") ?: 0) >= 2160
        val after = before.copy(
            reportsVideo = true,
            shown2160 = before.shown2160 + listOfNotNull(video?.takeIf { uhd && it !in before.shown2160 }),
            hdrSdr = when (picture.hdr) {
                CastHdr.Sdr -> before.hdrSdr || hdr
                CastHdr.Hdr10, CastHdr.Hdr, CastHdr.Dv -> false
                CastHdr.Unknown -> before.hdrSdr
            },
            dolbyVision = before.dolbyVision || picture.hdr == CastHdr.Dv,
            noPicture = before.noPicture - setOfNotNull(video),
        )
        return after.takeIf { it != before }?.copy(seenAt = wall)
    }

    /**
     * [memory] as it is [now] (the statuses' clock, elapsedRealtime), at [wall]: the file's video marked no picture once the
     * device has played it [DARK_MS] with none, where it has reported pictures before; null when nothing changes.
     */
    fun tick(memory: CastDeviceMemory?, now: Long, wall: Long): CastDeviceMemory? {
        val since = dark ?: return null
        val video = video ?: return null
        if (now - since < DARK_MS || memory?.reportsVideo != true || video in memory.noPicture) return null
        return memory.copy(noPicture = memory.noPicture + video, seenAt = wall)
    }

    private companion object {
        /** How long PLAYING with no picture says there is none (L128): a receiver says its picture as it starts decoding. */
        const val DARK_MS = 5_000L

        /** Dolby Vision's profiles whose base layer is HDR10 or HLG, which a device without Dolby Vision shows as such. */
        val HDR_BASES = setOf(7, 8)
    }
}
