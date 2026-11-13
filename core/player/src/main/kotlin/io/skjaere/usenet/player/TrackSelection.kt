package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

/**
 * A [TrackPlan] as TrackSelectionParameters for the player whose [Tracks] it was planned from.
 * Tracks are pinned with overrides, which are bound to that player's TrackGroup instances: a new
 * player gets a new plan from its own tracks ([TrackPlanner]), never another player's parameters.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object TrackSelection {
    fun apply(parameters: TrackSelectionParameters, tracks: Tracks, plan: TrackPlan, preferred: List<String>): TrackSelectionParameters =
        withSubtitles(withAudio(parameters, tracks, plan.audio, preferred), tracks, plan.subtitles)

    /**
     * Pins [option], or with null leaves the audio to the selector's [preferred] languages. The
     * pinned track's language goes first among the preferred: the selector picks forced subtitles
     * for the audio it would pick itself, before overrides apply. Forced only follows the new
     * audio: its forced track is pinned again.
     */
    fun withAudio(parameters: TrackSelectionParameters, tracks: Tracks, option: TrackOption?, preferred: List<String>): TrackSelectionParameters {
        val builder = parameters.buildUpon().setPreferredAudioLanguages(*(listOfNotNull(option?.language) + preferred).distinct().toTypedArray())
        if (option == null) builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO) else builder.setOverrideForType(override(tracks, option))
        val updated = builder.build()
        return if (forcedOnly(updated)) withSubtitles(updated, tracks, SubtitlePlan.ForcedOnly) else updated
    }

    /**
     * Forced only pins the forced track in the audio's language ([TrackPlanner.forcedFor]): the
     * selector knows a forced track only by its flag, and many files just name it so. Without one
     * it is [SubtitleRule]'s "none", which leaves text on for a flagged one anyway.
     */
    fun withSubtitles(parameters: TrackSelectionParameters, tracks: Tracks, plan: SubtitlePlan): TrackSelectionParameters = when (plan) {
        SubtitlePlan.Off -> parameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        SubtitlePlan.ForcedOnly -> {
            val none = SubtitleRule.withSubtitles(parameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).build(), null)
            val forced = TrackPlanner.forcedFor(TrackOptions.of(tracks), audioLanguage(none, tracks))
            if (forced == null) none else none.buildUpon().setOverrideForType(override(tracks, forced)).build()
        }
        is SubtitlePlan.Track -> parameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(plan.option.language)
            .setIgnoredTextSelectionFlags(0)
            .setOverrideForType(override(tracks, plan.option))
            .build()
    }

    /**
     * The subtitles as the parameters have them, which the sheet marks and keeps: Off only while
     * text is off, forced subtitles included; a track pinned or picked by language; else Forced
     * only, the rule's "none", also in a file without a forced track to show (the sheet marks Off
     * then, but a choice kept for the next player or episode must not turn forced subtitles off).
     */
    fun shownSubtitles(options: List<TrackOption>, parameters: TrackSelectionParameters): SubtitlePlan {
        if (C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes) return SubtitlePlan.Off
        val shown = options.firstOrNull { it.isText && it.selected }
        // A track pinned by forced only isn't one asked for.
        val asked = !forcedOnly(parameters) &&
            (parameters.overrides.keys.any { it.type == C.TRACK_TYPE_TEXT } || parameters.preferredTextLanguages.isNotEmpty())
        return if (shown != null && (asked || !shown.forced)) SubtitlePlan.Track(shown) else SubtitlePlan.ForcedOnly
    }

    /** Whether [parameters] are forced only's: text on, no language asked for, the default flag ignored. */
    private fun forcedOnly(parameters: TrackSelectionParameters) =
        C.TRACK_TYPE_TEXT !in parameters.disabledTrackTypes && parameters.preferredTextLanguages.isEmpty() &&
            parameters.ignoredTextSelectionFlags and C.SELECTION_FLAG_DEFAULT != 0

    /** The audio's language: the track pinned, else the one playing. */
    private fun audioLanguage(parameters: TrackSelectionParameters, tracks: Tracks): String? {
        val pinned = parameters.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.trackIndices.isNotEmpty() }
        return pinned?.let { it.mediaTrackGroup.getFormat(it.trackIndices[0]).language }
            ?: tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                ?.let { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let { g.getTrackFormat(it).language } }
    }

    private fun override(tracks: Tracks, option: TrackOption) =
        TrackSelectionOverride(tracks.groups[option.groupIndex].mediaTrackGroup, option.trackIndex)
}
