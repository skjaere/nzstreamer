package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util

/**
 * "Subtitles when audio isn't your first language": with the rule on, a release whose audio
 * isn't in the user's first preferred language gets subtitles in that language, if the file
 * has them. Codes are ISO 639 ("de", "ger", "nb") or Media3 track languages ("no-nob"); they
 * compare by main language after Media3's normalization, so Android's "nb", "in" and "iw" meet
 * the tracks' "no", "ms" and "he".
 */
@androidx.annotation.OptIn(UnstableApi::class)
object SubtitleRule {
    /**
     * The subtitle language to show, as Media3 normalizes the first preferred language ("no-nob",
     * "ms-ind"), or null for no subtitles. The full code lets the track selector put the viewer's
     * own variant ahead of other members of its macrolanguage (Indonesian over Malay).
     */
    fun choose(enabled: Boolean, audioLanguage: String?, preferred: List<String>, subtitleLanguages: Collection<String>): String? {
        if (!enabled) return null
        val first = preferred.firstOrNull()?.let(Util::normalizeLanguageCode) ?: return null
        val main = first.substringBefore('-')
        // Unknown audio language ("und", missing): don't guess, leave subtitles off.
        val audio = audioLanguage?.let(::mainLanguage)?.takeIf { it.isNotBlank() && it != "und" } ?: return null
        if (audio == main) return null
        return first.takeIf { subtitleLanguages.any { mainLanguage(it) == main } }
    }

    /**
     * [parameters] showing subtitles in [language], or for null no full subtitles. Text stays on
     * then, so a forced track in the audio's language (the foreign lines of an own-language film)
     * still shows; only the default flag that would pick a full track is ignored.
     */
    fun withSubtitles(parameters: TrackSelectionParameters, language: String?): TrackSelectionParameters =
        parameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(language)
            .setIgnoredTextSelectionFlags(if (language == null) C.SELECTION_FLAG_DEFAULT else 0)
            .build()

    private fun mainLanguage(code: String) = Util.normalizeLanguageCode(code).substringBefore('-')
}
