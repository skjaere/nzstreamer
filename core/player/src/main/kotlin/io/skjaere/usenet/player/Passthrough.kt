package io.skjaere.usenet.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import io.skjaere.usenet.releases.AudioCodec

/**
 * What the TV or receiver takes over HDMI now, of the encodings the ranking asks about (C.ENCODING_*). Compared by the
 * codecs that pass through, which is all the ranking asks of it: the route read again says the same, and so does one
 * that differs only in an encoding that adds no codec (E-AC-3 JOC, Atmos, beside E-AC-3; DTS-HD beside DTS, which plays
 * every DTS codec's core). The ranking contexts holding them stay equal, and auto-advance's prefetched search is still
 * handed to the play (TCR-1). The [encodings] are kept as read, for the wait after a TV's switch, which asks for all of
 * them back.
 */
class Passthrough(val encodings: Set<Int>) {
    /** Whether [codec] passes through: one of its encodings does (a DTS-HD track's DTS core among them, which a DTS route takes). */
    fun takes(codec: AudioCodec): Boolean = ENCODINGS[codec].orEmpty().any { it in encodings }

    /** The codecs that pass through, in [AudioCodec] order: what the log header and This device say. */
    val codecs: List<AudioCodec> get() = AudioCodec.entries.filter(::takes)

    /** As the log header says it: "DD, DD+, TrueHD", or "none". */
    val said: String get() = codecs.joinToString { it.label }.ifEmpty { "none" }

    override fun equals(other: Any?): Boolean = other is Passthrough && other.codecs == codecs

    override fun hashCode(): Int = codecs.hashCode()

    override fun toString(): String = "Passthrough($said; encodings $encodings)"

    companion object {
        val NONE = Passthrough(emptySet())

        /**
         * Media3's reading of the route ([capabilities]; null when it couldn't be read), as the ranking asks about it: only
         * the encodings of [ENCODINGS], so a change Media3 sees in another, or in a channel count alone, isn't one.
         */
        @OptIn(UnstableApi::class)
        fun of(capabilities: AudioCapabilities?): Passthrough =
            Passthrough(RANKED.filterTo(mutableSetOf()) { capabilities?.supportsEncoding(it) == true })

        /** Each codec's encodings, any of which passing through plays it. */
        internal val ENCODINGS = mapOf(
            AudioCodec.Ac3 to listOf(C.ENCODING_AC3),
            AudioCodec.Eac3 to listOf(C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC),
            AudioCodec.Dts to listOf(C.ENCODING_DTS),
            AudioCodec.DtsHd to listOf(C.ENCODING_DTS_HD, C.ENCODING_DTS),
            AudioCodec.DtsHdMa to listOf(C.ENCODING_DTS_HD, C.ENCODING_DTS),
            AudioCodec.DtsX to listOf(C.ENCODING_DTS_UHD_P2, C.ENCODING_DTS_HD, C.ENCODING_DTS),
            AudioCodec.TrueHd to listOf(C.ENCODING_DOLBY_TRUEHD),
        )

        private val RANKED = ENCODINGS.values.flatten().toSet()
    }
}
