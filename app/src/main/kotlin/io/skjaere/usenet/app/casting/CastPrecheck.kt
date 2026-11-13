@file:androidx.annotation.OptIn(UnstableApi::class)

package io.skjaere.usenet.app.casting

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import io.skjaere.usenet.app.play.ChooseFile
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.core.ProbeResult
import io.skjaere.usenet.core.ProbedTrack
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.releases.AudioCodec
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.search.RankedItem
import java.util.Locale

// The pre-check (W51, W63, L130, L142, L152; CsPhonePrecheck): what a release picked by hand that its Cast device can't
// fully play asks before anything is sent there, and the lines a cast says before it plays. The coordinator only asks and
// waits; this decides the words and what each answer plays, where a JVM test reads them.

/** W55, in the design's words: no subtitles on a cast, said where this phone would show them (L142). */
const val CAST_NO_SUBTITLES = "Subtitles don’t show on Cast devices"

/** The audio line (W51): "Kitchen display will play the German audio: Cast devices play a file’s first sound track." */
fun playsTheAudio(name: String, language: String) = "$name will play the $language audio: Cast devices play a file’s first sound track."

/**
 * A line said before a cast plays (L142, W63), by its icon ([kind]): the device will play another audio language than the
 * first one wanted, or subtitles this phone would show won't show there. It prints without its words, which name the
 * device.
 */
data class CastLine(val kind: Kind, val words: String) {
    enum class Kind { Audio, Subtitles }

    override fun toString() = "CastLine($kind)"
}

/**
 * The pre-check's sheet (CsPhonePrecheck), top to bottom under the play's title: what the device can't play, as the
 * [headline] ("No sound on Kitchen display"); a card with the [reason] after ⚠ over the [release]'s name; the [lines] that
 * apply; the small [note] that nothing was sent yet; then its [buttons], Play a compatible release only while the list it
 * was picked from has one the device should play ([compatible]), Play on this phone only where this phone's own probe
 * plays the file ([here]). It prints without its words, which name the device.
 */
data class CastPrecheck(
    val headline: String,
    val reason: String,
    val release: String,
    val lines: List<CastLine>,
    val note: String,
    val compatible: Boolean,
    val here: Boolean,
) {
    /**
     * The buttons, the main one first: amber Play a compatible release, where there is one to play. Play on this phone ends
     * the cast (L85), so it isn't offered for a file this phone can't play either (DTS with no DTS decoder here): its play
     * here would fail at once with the session gone. Nothing more goes to a device no longer chosen and connected ([live],
     * W36), as a cast's failure sheet has it: this phone, where it plays the file, and Cancel are left.
     */
    fun buttons(live: Boolean = true): List<Button> {
        val playHere = Button.PlayHere.takeIf { here }
        return if (!live) listOfNotNull(playHere, Button.Cancel) else listOfNotNull(Button.Compatible.takeIf { compatible }, playHere, Button.CastAnyway, Button.Cancel)
    }

    /**
     * The answers (L130): the list's releases the device should play, played there in place of this one ([Compatible],
     * L152); this phone chosen, the file played here at once, its NZB in the store ([PlayHere]); the file sent there all
     * the same ([CastAnyway], Terms 3.4.5); the play stopped ([Cancel], also Back and a swipe down).
     */
    enum class Button(val words: String) {
        Compatible("Play a compatible release"),
        PlayHere("Play on this phone"),
        CastAnyway("Cast anyway"),
        Cancel("Cancel"),
    }

    override fun toString() = "CastPrecheck(lines=${lines.map { it.kind }}, compatible=$compatible, here=$here)"

    companion object {
        /**
         * The sheet for a file whose [verdict] says what the Cast device [device] can't play of it ([tracks]); null with
         * nothing to ask. [release] is the release's name, whose audio ([named]) may say which DTS a track is where the
         * file doesn't. [compatible]: the list it was picked from has another release the device should play. [here]: this
         * phone's own probe plays the file.
         */
        fun of(verdict: CastVerdict, tracks: ProbedTracks, device: String, release: String, named: List<AudioCodec>, compatible: Boolean, here: Boolean): CastPrecheck? {
            val (headline, reason) = when (val miss = verdict.miss ?: return null) {
                is ProbeResult.NoCastAudio -> noSoundOn(device) to silent(sounds(tracks.audio, named), tracks.audio.all { CastVerdict.audioCodec(it.mime) in DTS_OR_TRUEHD })
                is ProbeResult.NoCastVideo -> noPictureOn(device) to "${miss.found} video. ${if (anyModelShows(tracks.video.first())) device else "Cast devices"} can’t play it."
                is ProbeResult.TooBigForCast ->
                    ReleaseReason.TooBigFor.shows(device, miss.maxHeight) to "${sizeWords(miss.height)}. $device shows up to ${miss.maxHeight}p, so it may not play this file."
            }
            return CastPrecheck(headline, reason, release, lines(verdict, device), checkedBefore(device), compatible, here)
        }

        /**
         * What Play a compatible release plays (L152): [request]'s list's other releases in the list's order, those ranked
         * with a reason the device can't play them left out, as an automatic pick, which moves past any the verdict fails;
         * each release's own file, as Choose file's question was this release's. Null with none left: the button is hidden.
         */
        fun compatible(request: PlayRequest): PlayRequest? {
            val others = request.alternatives.filterNot { it.warnedForTheDevice() }.ifEmpty { return null }
            return request.copy(
                candidates = others, chosen = false, rememberedFirst = false, alternatives = emptyList(),
                picker = (request.picker as? ChooseFile)?.wanted ?: request.picker,
            )
        }

        /**
         * W63: the lines the starting sheet says under "Casting to Kitchen display" once the file is checked, for a file
         * with nothing else the device can't play ([verdict]); none for one the pre-check asked about, which said them.
         */
        fun startingLines(verdict: CastVerdict?, device: String): List<CastLine> = verdict?.takeIf { it.miss == null }?.let { lines(it, device) }.orEmpty()

        private fun lines(verdict: CastVerdict, device: String): List<CastLine> = listOfNotNull(
            verdict.otherLanguage?.let(::languageName)?.let { CastLine(CastLine.Kind.Audio, playsTheAudio(device, it)) },
            CastLine(CastLine.Kind.Subtitles, CAST_NO_SUBTITLES).takeIf { verdict.subtitles },
        )

        /**
         * "DTS-HD MA 5.1 is its only sound", or of two "… and TrueHD 7.1 are": then why it would play silent, by the codecs
         * where they are all DTS or TrueHD ([dtsOrTrueHd]), else as what Cast devices can't play (MP2, which they skip too).
         */
        private fun silent(sounds: List<String>, dtsOrTrueHd: Boolean): String {
            val one = sounds.size == 1
            val why = if (dtsOrTrueHd) "DTS or TrueHD" else if (one) "it" else "them"
            return "${spoken(sounds)} ${if (one) "is" else "are"} its only sound. Cast devices can’t play $why, so it would play silent."
        }

        /**
         * Each sound track of [tracks] as its codec and channels ("DTS-HD MA 5.1"), each said once. Media3 reads a Matroska
         * DTS track as plain DTS whichever it is, and can't tell DTS-HD MA from the rest: the release's name ([named]) says
         * it of the file's first DTS, where it names a DTS more exact than the file's.
         */
        private fun sounds(tracks: List<ProbedTrack>, named: List<AudioCodec>): List<String> {
            var dts = named.firstOrNull { it in DTS_RANK }
            return tracks.map { track ->
                val rank = DTS_RANK[CastVerdict.audioCodec(track.mime)]
                val sharper = dts?.takeIf { rank != null && DTS_RANK.getValue(it) > rank }
                if (sharper != null) dts = null
                listOfNotNull(sharper?.label ?: track.label, track.channels?.let(::layout)).joinToString(" ")
            }.distinct()
        }

        /** How release names say a track's channels: "2.0", "5.1", "7.1". */
        private fun layout(channels: Int) = if (channels <= 2) "$channels.0" else "${channels - 1}.1"

        /** "A", "A and B", "A, B and C". */
        private fun spoken(words: List<String>): String =
            if (words.size <= 1) words.joinToString() else words.dropLast(1).joinToString(", ") + " and " + words.last()

        /** Whether some Cast device's model shows [video]: then this device's own limit, said in its name (L149). */
        private fun anyModelShows(video: ProbedTrack): Boolean {
            val decoded = CastVerdict.decodedAs(video) ?: return false
            return CastModel.entries.any { it.profile(null).canDecode(decoded.codec, decoded.tenBit) }
        }

        /** A size as the sheet says it: "2160p (4K)", "4320p (8K)", else its lines. */
        private fun sizeWords(height: Int) = when (height) {
            2160 -> "2160p (4K)"
            4320 -> "4320p (8K)"
            else -> "${height}p"
        }

        /** [code]'s language in English ("German"), by its main language; null where Java has no name for it. */
        internal fun languageName(code: String): String? {
            val main = Util.normalizeLanguageCode(code).substringBefore('-')
            return Locale.forLanguageTag(main).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && !it.equals(main, ignoreCase = true) }
        }

        /** Whether the ranking warned of [this] for the Cast device it ranked for (L126): a reason it can't fully play it there. */
        private fun RankedItem.warnedForTheDevice(): Boolean = ranked.reasons.any {
            it is ReleaseReason.NoSoundOn || it is ReleaseReason.NoPictureOn || it is ReleaseReason.TooBigFor || it is ReleaseReason.DolbyVisionOnlyOn
        }

        /** The DTS family, by how much a name says of it: plain DTS, DTS-HD, then DTS-HD MA and DTS:X. */
        private val DTS_RANK = mapOf(AudioCodec.Dts to 0, AudioCodec.DtsHd to 1, AudioCodec.DtsHdMa to 2, AudioCodec.DtsX to 2)

        /** The sound no Cast device plays, as the board names it. */
        private val DTS_OR_TRUEHD = DTS_RANK.keys + AudioCodec.TrueHd
    }
}
