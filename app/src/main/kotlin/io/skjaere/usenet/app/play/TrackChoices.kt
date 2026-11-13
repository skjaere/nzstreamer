package io.skjaere.usenet.app.play

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import io.skjaere.usenet.data.ShowTrackChoice
import io.skjaere.usenet.data.SubtitleMode
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.player.AudioChoice
import io.skjaere.usenet.player.SubtitleChoice
import io.skjaere.usenet.player.SubtitlePlan
import io.skjaere.usenet.player.TrackChoice
import io.skjaere.usenet.player.TrackKey
import io.skjaere.usenet.player.TrackOption
import io.skjaere.usenet.player.TrackSelection
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The player's choices of audio and subtitles where they are kept: a show's (Room, by language
 * and kind) and the play's (saved instance state, with the very tracks picked).
 */
object TrackChoices {
    /** The show whose episodes share their choices: a TMDB episode's; null for films, releases and opened NZB files. */
    fun showOf(launch: PlayerLaunch?): Int? = launch?.watch?.takeIf { it.kind == WatchKind.Episode }?.showTmdbId

    /** [choice] as the show remembers it for its later episodes: languages and kinds, not this file's tracks. */
    fun toShow(showTmdbId: Int, choice: TrackChoice): ShowTrackChoice {
        val audio = choice.audio
        val subtitles = choice.subtitles
        return ShowTrackChoice(
            showTmdbId = showTmdbId,
            audioLanguage = audio?.let { it.language ?: C.LANGUAGE_UNDETERMINED },
            audioCommentary = audio?.commentary == true,
            audioDescription = audio?.audioDescription == true,
            subtitles = when (subtitles) {
                null -> null
                SubtitleChoice.Off -> SubtitleMode.Off
                SubtitleChoice.ForcedOnly -> SubtitleMode.ForcedOnly
                is SubtitleChoice.Language -> SubtitleMode.Language
            },
            subtitleLanguage = (subtitles as? SubtitleChoice.Language)?.let { it.language ?: C.LANGUAGE_UNDETERMINED },
            subtitleSdh = (subtitles as? SubtitleChoice.Language)?.sdh == true,
            subtitleForced = (subtitles as? SubtitleChoice.Language)?.forced == true,
        )
    }

    fun fromShow(row: ShowTrackChoice): TrackChoice = TrackChoice(
        audio = row.audioLanguage?.let { AudioChoice(known(it), row.audioCommentary, audioDescription = row.audioDescription) },
        subtitles = when (row.subtitles) {
            null -> null
            SubtitleMode.Off -> SubtitleChoice.Off
            SubtitleMode.ForcedOnly -> SubtitleChoice.ForcedOnly
            SubtitleMode.Language -> SubtitleChoice.Language(row.subtitleLanguage?.let(::known), row.subtitleSdh, row.subtitleForced)
        },
    )

    /** [current] with the parts [change] picks replaced. */
    fun updated(current: TrackChoice?, change: TrackChoice): TrackChoice =
        TrackChoice(change.audio ?: current?.audio, change.subtitles ?: current?.subtitles)

    /** What plays now, as a choice: the audio track selected, and the subtitles as the parameters have them ([TrackSelection.shownSubtitles]). */
    fun shown(options: List<TrackOption>, parameters: TrackSelectionParameters): TrackChoice = TrackChoice(
        audio = options.firstOrNull { it.isAudio && it.selected }?.let(AudioChoice::of),
        subtitles = when (val shown = TrackSelection.shownSubtitles(options, parameters)) {
            SubtitlePlan.Off -> SubtitleChoice.Off
            SubtitlePlan.ForcedOnly -> SubtitleChoice.ForcedOnly
            is SubtitlePlan.Track -> SubtitleChoice.of(shown.option)
        },
    )

    /**
     * The play's choice after the sheet's [change]: its parts, else those chosen [before], else
     * what [shown] now. Both parts are kept, so the next player of this play shows what the user
     * last saw, rather than the subtitle rule deciding again for a new audio track. A show keeps
     * only what was picked ([updated]).
     */
    fun afterPick(change: TrackChoice, before: TrackChoice?, shown: TrackChoice): TrackChoice = updated(updated(shown, before ?: TrackChoice()), change)

    fun encode(choice: TrackChoice): String = json.encodeToString(Saved.serializer(), Saved.of(choice))

    /** What [encode] wrote; nothing chosen if it can't be read (an older version's state). */
    fun decode(text: String): TrackChoice = try {
        json.decodeFromString(Saved.serializer(), text).choice()
    } catch (e: SerializationException) {
        TrackChoice()
    } catch (e: IllegalArgumentException) {
        TrackChoice()
    }

    private fun known(language: String) = language.takeUnless { it == C.LANGUAGE_UNDETERMINED }

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Saved(val audio: Audio? = null, val subtitles: Subtitles? = null) {
        fun choice() = TrackChoice(
            audio?.let { AudioChoice(it.language, it.commentary, it.track?.key(), it.audioDescription) },
            subtitles?.let {
                when (it.mode) {
                    SubtitleMode.Off -> SubtitleChoice.Off
                    SubtitleMode.ForcedOnly -> SubtitleChoice.ForcedOnly
                    SubtitleMode.Language -> SubtitleChoice.Language(it.language, it.sdh, it.forced, it.track?.key())
                }
            },
        )

        companion object {
            fun of(choice: TrackChoice) = Saved(
                choice.audio?.let { Audio(it.language, it.commentary, it.track?.let(::Key), it.audioDescription) },
                when (val s = choice.subtitles) {
                    null -> null
                    SubtitleChoice.Off -> Subtitles(SubtitleMode.Off)
                    SubtitleChoice.ForcedOnly -> Subtitles(SubtitleMode.ForcedOnly)
                    is SubtitleChoice.Language -> Subtitles(SubtitleMode.Language, s.language, s.sdh, s.forced, s.track?.let(::Key))
                },
            )
        }
    }

    /** [audioDescription] defaults for a play's state saved by an earlier version. */
    @Serializable
    private data class Audio(val language: String?, val commentary: Boolean, val track: Key?, val audioDescription: Boolean = false)

    @Serializable
    private data class Subtitles(val mode: SubtitleMode, val language: String? = null, val sdh: Boolean = false, val forced: Boolean = false, val track: Key? = null)

    @Serializable
    private data class Key(val id: String?, val mimeType: String?, val channels: Int, val label: String?) {
        constructor(key: TrackKey) : this(key.id, key.mimeType, key.channels, key.label)

        fun key() = TrackKey(id, mimeType, channels, label)
    }
}
