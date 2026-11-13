package io.skjaere.usenet.app.casting

import io.skjaere.usenet.data.CastDeviceMemory
import io.skjaere.usenet.data.CastVideoCodec
import io.skjaere.usenet.releases.AudioCodec
import io.skjaere.usenet.releases.DeviceCapabilities
import io.skjaere.usenet.releases.Hdr
import io.skjaere.usenet.releases.VideoCodec

/**
 * What a Google Cast device plays, as the ranking reads a device (L128): its model's row ([CastModel]), then what the
 * device itself has shown ([CastDeviceMemory]), which wins over the row. [video] and [videoTenBit]: the videos it shows at
 * 8 and at 10 bits, each with the largest height it shows of it (null: no limit known). The audio is every device's
 * ([CAST_AUDIO]); there is no hardware limit, as the phone can't see a Cast device's decoders. Equal facts make equal
 * profiles, whenever they were learned.
 */
data class CastProfile(
    override val displayHeight: Int,
    override val hdrTypes: Set<Hdr>,
    override val dolbyVision: Boolean,
    val video: Map<VideoCodec, Int?>,
    val videoTenBit: Map<VideoCodec, Int?>,
) : DeviceCapabilities {
    override fun canDecode(video: VideoCodec, tenBit: Boolean) = video in videos(tenBit)
    override fun canDecode(audio: AudioCodec) = audio in CAST_AUDIO
    override fun maxHeight(video: VideoCodec, tenBit: Boolean) = videos(tenBit)[video]
    override fun hardwareMaxHeight(video: VideoCodec, tenBit: Boolean): Int? = null

    private fun videos(tenBit: Boolean) = if (tenBit) videoTenBit else video
}

/**
 * The sound every Cast device plays: AC-3 and E-AC-3 count as fine everywhere, beside AAC, Opus,
 * MP3, FLAC and PCM, with no preference among them; DTS of any kind and TrueHD never play, so the picture plays silent.
 */
val CAST_AUDIO = setOf(AudioCodec.Aac, AudioCodec.Ac3, AudioCodec.Eac3, AudioCodec.Opus, AudioCodec.Mp3, AudioCodec.Flac, AudioCodec.Pcm)

/**
 * A Cast device's model, by the name its route gives (CastDevice.getModelName, L153), as L128's rows have them. No row
 * shows VC-1, MPEG-2, XviD or H.264 at 10 bits, which no Cast device's player maps.
 */
enum class CastModel(private val row: CastProfile) {
    /** "Chromecast" exactly, the first three generations (VLC's rule): H.264 up to 1080p, no HDR. */
    Chromecast(CastProfile(1080, emptySet(), false, mapOf(VideoCodec.H264 to 1080), emptyMap())),

    /** "Chromecast Ultra": HEVC and VP9 up to 2160p, at 10 bits too, with HDR10 and Dolby Vision; H.264 up to 1080p. */
    ChromecastUltra(
        CastProfile(
            2160, setOf(Hdr.Hdr10, Hdr.DolbyVision), true,
            mapOf(VideoCodec.H264 to 1080, VideoCodec.Hevc to 2160, VideoCodec.Vp9 to 2160),
            mapOf(VideoCodec.Hevc to 2160, VideoCodec.Vp9 to 2160),
        ),
    ),

    /**
     * An NVIDIA SHIELD ("SHIELD Android TV", in any case), as measured: 4K from the start
     * (W53), VP9 at 10 bits up to 1080p, no AV1, HDR not held back, Dolby Vision only once shown.
     */
    Shield(
        CastProfile(
            2160, Hdr.entries.toSet(), false,
            mapOf(VideoCodec.H264 to 2160, VideoCodec.Hevc to 2160, VideoCodec.Vp9 to 2160),
            mapOf(VideoCodec.Hevc to 2160, VideoCodec.Vp9 to 1080),
        ),
    ),

    /**
     * Any other (a TV with Google Cast built in, a newer Chromecast, whose model names are unverified): H.264 for sure;
     * HEVC, VP9 and AV1 not held back, with no size limit known; HDR not held back; no Dolby Vision until shown, and 1080p
     * until it has shown 4K (W53).
     */
    Other(
        CastProfile(
            1080, Hdr.entries.toSet(), false,
            mapOf(VideoCodec.H264 to null, VideoCodec.Hevc to null, VideoCodec.Vp9 to null, VideoCodec.Av1 to null),
            mapOf(VideoCodec.Hevc to null, VideoCodec.Vp9 to null, VideoCodec.Av1 to null),
        ),
    ),
    ;

    /**
     * The row with what the device has shown ([memory]; null: nothing yet) over it. A 4K picture of a video lifts its
     * limit to 2160p (none known stays none) and the screen to 2160 lines; an HDR file shown as SDR leaves no HDR; Dolby
     * Vision shown adds it; a video played with no picture shows no more, over a 4K picture said of it too, as the newer
     * fact (a later picture clears it from the memory). A video by a name this version doesn't know is passed over.
     */
    fun profile(memory: CastDeviceMemory?): CastProfile {
        if (memory == null) return row
        val shown = memory.shown2160.mapNotNull(::known)
        val dark = memory.noPicture.mapNotNull(::known).toSet()
        fun learned(videos: Map<VideoCodec, Int?>, tenBit: Boolean): Map<VideoCodec, Int?> =
            shown.filter { it.second == tenBit }
                .fold(videos) { all, (codec) -> all + (codec to if (codec in all) all[codec]?.coerceAtLeast(2160) else 2160) }
                .filterKeys { (it to tenBit) !in dark }
        return CastProfile(
            displayHeight = if (shown.isEmpty()) row.displayHeight else row.displayHeight.coerceAtLeast(2160),
            hdrTypes = if (memory.hdrSdr) emptySet() else row.hdrTypes + listOfNotNull(Hdr.DolbyVision.takeIf { memory.dolbyVision }),
            dolbyVision = row.dolbyVision || memory.dolbyVision,
            video = learned(row.video, tenBit = false),
            videoTenBit = learned(row.videoTenBit, tenBit = true),
        )
    }

    private fun known(video: CastVideoCodec): Pair<VideoCodec, Boolean>? =
        VideoCodec.entries.firstOrNull { it.name == video.codec }?.let { it to video.tenBit }

    companion object {
        /** The row for [model] as a device's route gives it (any device can set it); null or another name: [Other]. */
        fun of(model: String?): CastModel {
            val name = model?.trim().orEmpty()
            return when {
                name.contains("Chromecast Ultra", ignoreCase = true) -> ChromecastUltra
                name.equals("Chromecast", ignoreCase = true) -> Chromecast
                name.contains("SHIELD", ignoreCase = true) -> Shield
                else -> Other
            }
        }
    }
}

/**
 * The profiles of Cast devices (L128), with what each has shown kept on this phone ([devices], the cast settings' own,
 * CastPrefs.devices), so a play goes with its device's.
 */
class CastProfiles(private val devices: List<CastDeviceMemory>) {
    /** The device's whose Cast id is [deviceId] (null: none known, its row alone) and whose route names its [model]. */
    fun of(deviceId: String?, model: String?): CastProfile =
        CastModel.of(model).profile(deviceId?.let { id -> devices.firstOrNull { it.id == id } })
}
