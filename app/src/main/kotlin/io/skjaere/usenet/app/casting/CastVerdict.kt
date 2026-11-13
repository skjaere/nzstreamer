@file:androidx.annotation.OptIn(UnstableApi::class)

package io.skjaere.usenet.app.casting

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import io.skjaere.usenet.core.ProbeResult
import io.skjaere.usenet.core.ProbedTrack
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.player.SubtitleChoice
import io.skjaere.usenet.player.SubtitleRule
import io.skjaere.usenet.releases.AudioCodec
import io.skjaere.usenet.releases.DeviceCapabilities
import io.skjaere.usenet.releases.MediaInfo
import io.skjaere.usenet.releases.VideoCodec

/**
 * What a Google Cast device makes of a file (L129, W51), from the tracks the probe read and the device's profile (L128),
 * not this phone's decoders: a Cast device plays the file's first video track and the first audio track it can play, in
 * the file's order, and nothing switches it to another. [miss]: what it can't play of
 * the file, no sound, no picture or too big; null for nothing. [otherLanguage]: the language of the audio it plays, where
 * that isn't the first one wanted (Media3's code, "de"); null when it is, or nothing says. [subtitles]: this phone would
 * show subtitles for the file, which don't show on Cast devices (W55, L142). It names no device: the sheet's and the
 * notices' words add it.
 */
data class CastVerdict(val miss: ProbeResult.CastMiss?, val otherLanguage: String?, val subtitles: Boolean) {
    /** A video as a Cast device decodes it: its [codec], [tenBit] or not. */
    data class Decoded(val codec: VideoCodec, val tenBit: Boolean)

    /**
     * What the user wants of a file's tracks, as this phone's player reads it: the audio languages, the most wanted first
     * ([audioLanguages], as the settings give them, the device's language and English when none is chosen); Subtitles
     * when audio isn't your first language ([subtitlesWhenForeign]); and the subtitles the show remembers ([show], null
     * for none, or a film).
     */
    data class Prefs(val audioLanguages: List<String>, val subtitlesWhenForeign: Boolean, val show: SubtitleChoice? = null)

    companion object {
        /** The verdict on [tracks] for a device of [profile], with what the user wants of them ([prefs]). */
        fun judge(tracks: ProbedTracks, profile: DeviceCapabilities, prefs: Prefs): CastVerdict {
            val language = played(tracks, profile)?.language
            val first = prefs.audioLanguages.firstOrNull()
            return CastVerdict(
                miss = miss(tracks, profile),
                otherLanguage = language?.takeIf { first != null && !sameLanguage(first, it) },
                subtitles = subtitles(tracks.text, language, prefs),
            )
        }

        /**
         * What a device of [profile] can't play of [tracks], if anything: its first video track's codec at its bit depth
         * (no picture), then its size (too big), as without a picture the sound doesn't matter; then audio, none of which
         * it plays (no sound). No video or no audio at all is nothing it can't play, and a video codec the profile has no
         * word on isn't held against the file, as the ranking holds nothing against it.
         */
        fun miss(tracks: ProbedTracks, profile: DeviceCapabilities): ProbeResult.CastMiss? {
            tracks.video.firstOrNull()?.let { video -> videoMiss(video, profile)?.let { return it } }
            if (tracks.audio.isNotEmpty() && played(tracks, profile) == null) return ProbeResult.NoCastAudio(tracks.audio.map { it.label }.distinct())
            return null
        }

        /**
         * The video [track] is decoded as, as a Cast device's profile names it: its codec at its bit depth, Dolby Vision by its
         * base layer's (AVC for profile 9, AV1 for 10, HEVC for the others: 4, 5, 7, 8), ten bits where the file doesn't say
         * but Dolby Vision's own HEVC and AV1 are. Null for a codec the profile has no word on.
         */
        fun decodedAs(track: ProbedTrack): Decoded? {
            val dolbyVision = track.mime == MimeTypes.VIDEO_DOLBY_VISION
            val codec = if (dolbyVision) DOLBY_VISION_BASES[track.dolbyVisionProfile] ?: VideoCodec.Hevc else VIDEOS[track.mime] ?: return null
            return Decoded(codec, track.bitDepth?.let { it >= 10 } ?: (dolbyVision && track.dolbyVisionProfile != 9))
        }

        private fun videoMiss(track: ProbedTrack, profile: DeviceCapabilities): ProbeResult.CastMiss? {
            val dolbyVision = track.mime == MimeTypes.VIDEO_DOLBY_VISION
            val (codec, tenBit) = decodedAs(track) ?: return null
            if (!profile.canDecode(codec, tenBit)) {
                // As the release list names them: "HEVC 10-bit", Dolby Vision by its profile, which says its bits.
                val name = if (dolbyVision) listOfNotNull("Dolby Vision", track.dolbyVisionProfile?.let { "profile $it" }) else listOfNotNull(track.label, "10-bit".takeIf { tenBit })
                return ProbeResult.NoCastVideo(name.joinToString(" "))
            }
            val height = resolution(track) ?: return null
            val maxHeight = profile.maxHeight(codec, tenBit) ?: return null
            return ProbeResult.TooBigForCast(height, maxHeight).takeIf { height > maxHeight }
        }

        /** [track]'s size as release names have it: 2160 for a film cropped to 3840x1600. Null when the file doesn't say. */
        private fun resolution(track: ProbedTrack): Int? {
            val height = track.height ?: return null
            return track.width?.let { MediaInfo.resolutionClass("${it}x$height") } ?: height
        }

        /**
         * The audio track the device plays: its first that a device of [profile] plays, in the file's order. A receiver
         * skips a track whose codec it can't map, as it skips DTS (MP2, or one of no known type): only the codecs it maps
         * that no release name says ([RECEIVER_MAPS]) play beside the profile's.
         */
        private fun played(tracks: ProbedTracks, profile: DeviceCapabilities): ProbedTrack? =
            tracks.audio.firstOrNull { track -> audioCodec(track.mime)?.let(profile::canDecode) ?: (track.mime in RECEIVER_MAPS) }

        /** The codec of an audio track of [mime], as a Cast device's profile names it; null for one it has no word on. */
        internal fun audioCodec(mime: String?): AudioCodec? = when {
            mime == null -> null
            mime.startsWith("audio/vnd.dts.uhd") -> AudioCodec.DtsX
            mime.startsWith(MimeTypes.AUDIO_DTS_HD) -> AudioCodec.DtsHd
            mime.startsWith(MimeTypes.AUDIO_DTS) -> AudioCodec.Dts
            else -> AUDIOS[mime]
        }

        /**
         * Whether this phone's player would show subtitles of [text] over audio in [audioLanguage], as it plans them
         * (TrackPlanner): the show's choice where the file meets it, else Subtitles when audio isn't your first language
         * ([SubtitleRule]); short of full subtitles, a forced track in the audio's language still shows, unless the show's
         * subtitles are off.
         */
        private fun subtitles(text: List<ProbedTrack>, audioLanguage: String?, prefs: Prefs): Boolean {
            val show = prefs.show
            val full = when {
                show == SubtitleChoice.Off -> return false
                show == SubtitleChoice.ForcedOnly -> false
                show is SubtitleChoice.Language && text.any { it.forced == show.forced && sameLanguage(show.language, it.language) } -> true
                else -> SubtitleRule.choose(prefs.subtitlesWhenForeign, audioLanguage, prefs.audioLanguages, text.mapNotNull { it.language }) != null
            }
            return full || audioLanguage != null && text.any { it.forced && sameLanguage(audioLanguage, it.language) }
        }

        /** The same main language after Media3's normalisation ("iw" is "he", "en-us" is "en"); one unknown meets only another. */
        private fun sameLanguage(a: String?, b: String?): Boolean =
            if (a == null || b == null) a == b else Util.normalizeLanguageCode(a).substringBefore('-') == Util.normalizeLanguageCode(b).substringBefore('-')

        private val VIDEOS = mapOf(
            MimeTypes.VIDEO_H264 to VideoCodec.H264, MimeTypes.VIDEO_H265 to VideoCodec.Hevc, MimeTypes.VIDEO_AV1 to VideoCodec.Av1,
            MimeTypes.VIDEO_VP9 to VideoCodec.Vp9, MimeTypes.VIDEO_MPEG2 to VideoCodec.Mpeg2, MimeTypes.VIDEO_MPEG to VideoCodec.Mpeg2,
            MimeTypes.VIDEO_VC1 to VideoCodec.Vc1, MimeTypes.VIDEO_MP4V to VideoCodec.Xvid, MimeTypes.VIDEO_DIVX to VideoCodec.Xvid,
        )

        private val DOLBY_VISION_BASES = mapOf(9 to VideoCodec.H264, 10 to VideoCodec.Av1)

        /**
         * Audio a Cast receiver maps beside the profile's codecs, which no release name says (Chromium's
         * CodecIDToAudioCodec): Vorbis, ALAC, A-law and μ-law PCM, and MPEG-H where the device's build maps it.
         */
        private val RECEIVER_MAPS = setOf(
            MimeTypes.AUDIO_VORBIS, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_ALAW, MimeTypes.AUDIO_MLAW, MimeTypes.AUDIO_MPEGH_MHA1, MimeTypes.AUDIO_MPEGH_MHM1,
        )

        private val AUDIOS = mapOf(
            MimeTypes.AUDIO_AAC to AudioCodec.Aac, MimeTypes.AUDIO_AC3 to AudioCodec.Ac3, MimeTypes.AUDIO_E_AC3 to AudioCodec.Eac3,
            MimeTypes.AUDIO_E_AC3_JOC to AudioCodec.Eac3, MimeTypes.AUDIO_TRUEHD to AudioCodec.TrueHd, MimeTypes.AUDIO_FLAC to AudioCodec.Flac,
            MimeTypes.AUDIO_OPUS to AudioCodec.Opus, MimeTypes.AUDIO_MPEG to AudioCodec.Mp3, MimeTypes.AUDIO_RAW to AudioCodec.Pcm,
        )
    }
}
