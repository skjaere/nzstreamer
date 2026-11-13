package io.skjaere.usenet.releases

/** What this device can decode and show. The Android implementation asks MediaCodecList, Media3 and the display. */
interface DeviceCapabilities {
    /** Height in pixels (landscape) of the mode the display outputs now, e.g. 1080. */
    val displayHeight: Int

    /** The HDR formats the screen shows now, less those the user turned off; empty on a screen without HDR. */
    val hdrTypes: Set<Hdr>
    val dolbyVision: Boolean
    fun canDecode(video: VideoCodec, tenBit: Boolean): Boolean
    fun canDecode(audio: AudioCodec): Boolean

    /**
     * Largest landscape frame height, e.g. 1080, that the best decoder for [video] at this bit
     * depth plays; null when unknown or unlimited, and where [canDecode] is false.
     */
    fun maxHeight(video: VideoCodec, tenBit: Boolean): Int?

    /**
     * As [maxHeight], counting only the decoders the player takes for hardware ones: a software decoder that claims 4K
     * can't keep up with it. 0 when none is hardware; null when unknown, and where [canDecode] is false.
     */
    fun hardwareMaxHeight(video: VideoCodec, tenBit: Boolean): Int?
}

data class StaticDeviceCapabilities(
    override val displayHeight: Int = 1080,
    val video: Set<VideoCodec> = setOf(VideoCodec.H264, VideoCodec.Hevc, VideoCodec.Vp9, VideoCodec.Mpeg2),
    val videoTenBit: Set<VideoCodec> = setOf(VideoCodec.Hevc, VideoCodec.Vp9),
    /** Largest decodable height per codec; a codec not listed has no known limit. */
    val videoMaxHeight: Map<VideoCodec, Int> = emptyMap(),
    val videoTenBitMaxHeight: Map<VideoCodec, Int> = emptyMap(),
    val audio: Set<AudioCodec> = setOf(AudioCodec.Aac, AudioCodec.Ac3, AudioCodec.Eac3, AudioCodec.Flac, AudioCodec.Opus, AudioCodec.Mp3, AudioCodec.Pcm),
    override val dolbyVision: Boolean = false,
    /** Largest height a hardware decoder plays per codec; a codec not listed is decoded in hardware up to its [videoMaxHeight]. */
    val videoHardwareMaxHeight: Map<VideoCodec, Int> = emptyMap(),
    val videoTenBitHardwareMaxHeight: Map<VideoCodec, Int> = emptyMap(),
    /** Every format unless set: no release is held to the screen's HDR. */
    override val hdrTypes: Set<Hdr> = Hdr.entries.toSet(),
) : DeviceCapabilities {
    override fun canDecode(video: VideoCodec, tenBit: Boolean) = video in (if (tenBit) videoTenBit else this.video)
    override fun maxHeight(video: VideoCodec, tenBit: Boolean) =
        (if (tenBit) videoTenBitMaxHeight else videoMaxHeight)[video]?.takeIf { canDecode(video, tenBit) }
    override fun hardwareMaxHeight(video: VideoCodec, tenBit: Boolean) =
        (if (tenBit) videoTenBitHardwareMaxHeight else videoHardwareMaxHeight)[video]?.takeIf { canDecode(video, tenBit) } ?: maxHeight(video, tenBit)
    override fun canDecode(audio: AudioCodec) = audio in this.audio
}
