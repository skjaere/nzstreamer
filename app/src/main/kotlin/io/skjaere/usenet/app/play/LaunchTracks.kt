package io.skjaere.usenet.app.play

import io.skjaere.usenet.core.ProbedTrack
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.releases.Hdr
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The probe's tracks as the launch keeps them (L129): [ProbedTracks] is the play pipeline's, a module without serialization,
 * so the launch writes them through copies of its own. A track's HDR goes by its name, and one this version doesn't know (a
 * launch a newer version kept) reads as none, so the intent still decodes.
 */
internal object ProbedTracksSerializer : KSerializer<ProbedTracks> {
    @Serializable
    @SerialName("io.skjaere.usenet.app.play.LaunchTracks")
    private data class Saved(val video: List<Track> = emptyList(), val audio: List<Track> = emptyList(), val text: List<Track> = emptyList())

    @Serializable
    private data class Track(
        val mime: String? = null,
        val label: String = "unknown",
        val language: String? = null,
        val default: Boolean = false,
        val forced: Boolean = false,
        val width: Int? = null,
        val height: Int? = null,
        val bitDepth: Int? = null,
        val hdr: String? = null,
        val dolbyVisionProfile: Int? = null,
        val channels: Int? = null,
    ) {
        fun probed() = ProbedTrack(
            mime, label, language, default, forced, width, height, bitDepth, Hdr.entries.firstOrNull { it.name == hdr }, dolbyVisionProfile, channels,
        )

        companion object {
            fun of(t: ProbedTrack) = Track(t.mime, t.label, t.language, t.default, t.forced, t.width, t.height, t.bitDepth, t.hdr?.name, t.dolbyVisionProfile, t.channels)
        }
    }

    override val descriptor: SerialDescriptor = Saved.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ProbedTracks) =
        encoder.encodeSerializableValue(Saved.serializer(), Saved(value.video.map(Track::of), value.audio.map(Track::of), value.text.map(Track::of)))

    override fun deserialize(decoder: Decoder): ProbedTracks =
        decoder.decodeSerializableValue(Saved.serializer()).let { ProbedTracks(it.video.map(Track::probed), it.audio.map(Track::probed), it.text.map(Track::probed)) }
}
