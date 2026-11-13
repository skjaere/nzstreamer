package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util

/**
 * An audio track picked in the sheet: its language and whether it is commentary or the audio
 * description, which the show remembers, and [track] to tell it from other tracks of that language
 * within the same file.
 */
data class AudioChoice(val language: String?, val commentary: Boolean, val track: TrackKey? = null, val audioDescription: Boolean = false) {
    companion object {
        fun of(option: TrackOption) = AudioChoice(option.language, option.commentary, option.key, option.audioDescription)
    }
}

/** What the sheet's Subtitles section picks. */
sealed interface SubtitleChoice {
    /** Nothing shows, forced subtitles included. */
    data object Off : SubtitleChoice

    /** No full subtitles; a forced track in the audio's language still shows. The rule's "none". */
    data object ForcedOnly : SubtitleChoice

    /** Subtitles in [language], SDH or not; [forced] and [track] find a picked track again in the same file. */
    data class Language(val language: String?, val sdh: Boolean, val forced: Boolean = false, val track: TrackKey? = null) : SubtitleChoice

    companion object {
        fun of(option: TrackOption) = Language(option.language, option.sdh, option.forced, option.key)
    }
}

/** A choice of audio, subtitles or both; a part left null is decided further down ([TrackPlanner.plan]). */
data class TrackChoice(val audio: AudioChoice? = null, val subtitles: SubtitleChoice? = null)

/** Where a part of a [TrackPlan] came from. */
enum class ChoiceSource(val label: String) { Sheet("sheet"), Show("show"), Preferences("preferences"), Rule("rule") }

/** The subtitles a plan puts on the player. */
sealed interface SubtitlePlan {
    data object Off : SubtitlePlan

    /** No full subtitles; a forced track in the audio's language still shows, flagged or named so ([TrackSelection.withSubtitles]). */
    data object ForcedOnly : SubtitlePlan

    data class Track(val option: TrackOption) : SubtitlePlan
}

/** What a player plays: [audio] null leaves the audio to the track selector (nothing but commentary or audio description is playable). */
data class TrackPlan(val audio: TrackOption?, val audioSource: ChoiceSource, val subtitles: SubtitlePlan, val subtitleSource: ChoiceSource) {
    /** For the log: what the file has (as the sheet shows it, and as the file says it), where each part came from and what plays. */
    fun describe(options: List<TrackOption>): String {
        fun list(tracks: List<TrackOption>) = tracks.joinToString("; ", "[", "]") { "${it.summary} (${it.raw})" }
        val text = when (subtitles) {
            SubtitlePlan.Off -> "off"
            SubtitlePlan.ForcedOnly -> "forced only"
            is SubtitlePlan.Track -> subtitles.option.summary
        }
        return "audio ${list(options.filter { it.isAudio })} subtitles ${list(options.filter { it.isText })} " +
            "-> audio from ${audioSource.label}: ${audio?.summary ?: "the player's pick"}; subtitles from ${subtitleSource.label}: $text"
    }
}

/**
 * Decides what plays, each part (audio, subtitles) on its own: the choice made in the sheet
 * during this play, else the show's remembered choice, else the preferences (preferred audio
 * languages) and, for subtitles, [SubtitleRule]. A choice the file can't meet (its language
 * missing, or not playable) falls through to the next for that part only. Commentary and audio
 * description play only when chosen, whatever the file's default flag says.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object TrackPlanner {
    fun plan(options: List<TrackOption>, sheet: TrackChoice, show: TrackChoice?, preferred: List<String>, rule: Boolean): TrackPlan {
        val (audio, audioSource) = sheet.audio?.let { findAudio(options, it) }?.let { it to ChoiceSource.Sheet }
            ?: show?.audio?.let { findAudio(options, it) }?.let { it to ChoiceSource.Show }
            ?: (preferredAudio(options, preferred) to ChoiceSource.Preferences)
        val audioLanguage = (audio ?: options.firstOrNull { it.isAudio && it.selected })?.language
        val (subtitles, subtitleSource) = sheet.subtitles?.let { findSubtitles(options, it) }?.let { it to ChoiceSource.Sheet }
            ?: show?.subtitles?.let { findSubtitles(options, it) }?.let { it to ChoiceSource.Show }
            ?: (byRule(options, audioLanguage, preferred, rule) to ChoiceSource.Rule)
        return TrackPlan(audio, audioSource, subtitles, subtitleSource)
    }

    /** The playable track [choice] names: the very one when it is there, else the file's default of that language and kind. */
    private fun findAudio(options: List<TrackOption>, choice: AudioChoice): TrackOption? {
        val language = options.filter { it.isAudio && it.selectable && match(choice.language, it.language) > 0 }
        language.firstOrNull { choice.track != null && it.key == choice.track }?.let { return it }
        // Commentary or the description chosen but not in this file: the language's main track will do.
        val kind = language.filter { it.commentary == choice.commentary && it.audioDescription == choice.audioDescription }
            .ifEmpty { if (choice.commentary || choice.audioDescription) language.filter(::isMain) else emptyList() }
        return kind.maxWithOrNull(compareBy({ match(choice.language, it.language) }, { likeness(choice.track, it.key) }, { it.isDefault }))
    }

    private fun findSubtitles(options: List<TrackOption>, choice: SubtitleChoice): SubtitlePlan? = when (choice) {
        SubtitleChoice.Off -> SubtitlePlan.Off
        SubtitleChoice.ForcedOnly -> SubtitlePlan.ForcedOnly
        is SubtitleChoice.Language -> {
            val language = options.filter { it.isText && it.selectable && it.forced == choice.forced && match(choice.language, it.language) > 0 }
            (language.firstOrNull { choice.track != null && it.key == choice.track }
                ?: language.maxWithOrNull(compareBy({ it.sdh == choice.sdh }, { match(choice.language, it.language) }, { likeness(choice.track, it.key) }, { it.isDefault })))
                ?.let(SubtitlePlan::Track)
        }
    }

    /**
     * Forced only's track for audio in [audioLanguage]: a playable forced track (flagged or named
     * so) of that language, as the track selector would pick a flagged one. None for audio of no
     * known language.
     */
    fun forcedFor(options: List<TrackOption>, audioLanguage: String?): TrackOption? {
        val language = audioLanguage?.takeUnless { it.isBlank() || it == C.LANGUAGE_UNDETERMINED } ?: return null
        return options.filter { it.isText && it.selectable && it.forced && match(language, it.language) > 0 }
            .maxWithOrNull(compareBy({ match(language, it.language) }, { !it.sdh }, { it.isDefault }))
    }

    /** The first preferred language's main track; else the file's default main track. Null when only commentary or audio description plays. */
    private fun preferredAudio(options: List<TrackOption>, preferred: List<String>): TrackOption? {
        val main = options.filter { it.isAudio && it.selectable && isMain(it) }
        return preferred.firstNotNullOfOrNull { language ->
            main.filter { match(language, it.language) > 0 }.maxWithOrNull(compareBy({ match(language, it.language) }, { it.isDefault }))
        } ?: main.firstOrNull { it.isDefault } ?: main.firstOrNull()
    }

    /**
     * [SubtitleRule]'s choice for [audioLanguage], as a track: full subtitles (not forced, not SDH,
     * where the file has them) in the viewer's own variant of the language.
     */
    private fun byRule(options: List<TrackOption>, audioLanguage: String?, preferred: List<String>, enabled: Boolean): SubtitlePlan {
        val text = options.filter { it.isText && it.selectable }
        val language = SubtitleRule.choose(enabled, audioLanguage, preferred, text.mapNotNull { it.language }) ?: return SubtitlePlan.ForcedOnly
        return text.filter { match(language, it.language) > 0 }
            .maxWithOrNull(compareBy({ !it.forced }, { match(language, it.language) }, { !it.sdh }, { it.isDefault }))
            ?.let(SubtitlePlan::Track) ?: SubtitlePlan.ForcedOnly
    }

    /** Audio that is the film's or show's own: neither commentary nor the audio description, which play only when chosen. */
    private fun isMain(option: TrackOption) = !option.commentary && !option.audioDescription

    /** 2 for the same language code after Media3's normalisation, 1 for the same main language, else 0. Unknown meets only unknown. */
    private fun match(wanted: String?, language: String?): Int {
        if (wanted == null || language == null) return if (wanted == language) 2 else 0
        val a = Util.normalizeLanguageCode(wanted)
        val b = Util.normalizeLanguageCode(language)
        return when {
            a == b -> 2
            a.substringBefore('-') == b.substringBefore('-') -> 1
            else -> 0
        }
    }

    /** How much of [wanted] [key] shares, besides the id: the closest track when the very one is gone. */
    private fun likeness(wanted: TrackKey?, key: TrackKey): Int =
        if (wanted == null) 0 else listOf(wanted.mimeType == key.mimeType, wanted.channels == key.channels, wanted.label == key.label).count { it }
}
