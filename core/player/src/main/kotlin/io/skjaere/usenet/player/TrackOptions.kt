package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.util.Locale

/** What kind of track it is, from the file's flags and the track's name; the sheet shows these next to the label. */
enum class TrackTag(val label: String) {
    Commentary("Commentary"),
    Forced("Forced"),
    Sdh("SDH"),
    AudioDescription("Audio description"),

    /** In the film's or show's original language: Matroska's FlagOriginal, which Media3 calls the main role. */
    Original("Original"),
}

/**
 * Enough of a track to find it again among another player's tracks of the same file. Overrides
 * are bound to a player's TrackGroup instances, and each player (PlayerActivity builds one in
 * every onStart) makes new ones, so choices keep this instead.
 */
data class TrackKey(val id: String?, val mimeType: String?, val channels: Int, val label: String?)

/**
 * One audio or subtitle track of the file playing, as the audio and subtitles sheet lists it.
 * [groupIndex] and [trackIndex] place it in the Tracks it was read from, and only there.
 * [language] is Media3's normalised code ("en", "no-nob"), null when missing or undetermined.
 * [name] is the file's own name for the track, when it says more than [label] and [tags].
 */
data class TrackOption(
    val type: Int,
    val groupIndex: Int,
    val trackIndex: Int,
    val language: String?,
    val label: String,
    val tags: Set<TrackTag>,
    val name: String?,
    /** Whether a decoder on this device plays it. */
    val playable: Boolean,
    /** Kept for Pro (premium audio for free users); nothing sets it yet. */
    val locked: Boolean,
    val isDefault: Boolean,
    val selected: Boolean,
    val key: TrackKey,
    /** The file's flags as Media3 read them (C.SELECTION_FLAG_*, C.ROLE_FLAG_*), for the log. */
    val selectionFlags: Int = 0,
    val roleFlags: Int = 0,
) {
    val isAudio: Boolean get() = type == C.TRACK_TYPE_AUDIO
    val isText: Boolean get() = type == C.TRACK_TYPE_TEXT
    val commentary: Boolean get() = TrackTag.Commentary in tags
    val audioDescription: Boolean get() = TrackTag.AudioDescription in tags
    val forced: Boolean get() = TrackTag.Forced in tags
    val sdh: Boolean get() = TrackTag.Sdh in tags

    /** Whether the sheet lets it be picked. */
    val selectable: Boolean get() = playable && !locked

    /** Why it can't be picked; null when it can. */
    val reason: String?
        get() = when {
            !playable -> TrackOptions.CANT_PLAY
            locked -> TrackOptions.NEEDS_PRO
            else -> null
        }

    /** The line under the label: tags (but those the track's own name says), that name, and why it can't be picked. */
    val details: String?
        get() = (tags.filterNot { name?.contains(it.label, ignoreCase = true) == true }.map { it.label } + listOfNotNull(name, reason))
            .joinToString(" · ").ifEmpty { null }

    /** The label with its details, as one line: for the log, and for the player to say as a remote's key picks the track. */
    val summary: String get() = listOfNotNull(label, details).joinToString(" · ")

    /**
     * What the file itself says of the track, for the log: its id, its own name as it is and its
     * flags. Two tracks the sheet shows alike can then be told apart from a shared log.
     */
    @get:androidx.annotation.OptIn(UnstableApi::class)
    val raw: String
        get() {
            val flags = Util.getSelectionFlagStrings(selectionFlags) + Util.getRoleFlagStrings(roleFlags)
            val name = key.label?.let { "name \"$it\"" } ?: "no name"
            return "id ${key.id}, $name, ${if (flags.isEmpty()) "no flags" else flags.joinToString(", ", "flags ")}"
        }
}

/**
 * The audio and subtitle tracks of a player's Tracks, labelled for the sheet: "English · 5.1 ·
 * E-AC-3", "Deutsch · SRT". Language names are in the locale given (the device's), the rest is the
 * app's English.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object TrackOptions {
    const val CANT_PLAY = "Can't play on this device"
    const val NEEDS_PRO = "Needs Pro"
    const val UNKNOWN_LANGUAGE = "Unknown language"
    private const val SEPARATOR = " · "

    /** [locked] says which tracks need Pro; nothing does yet. */
    fun of(tracks: Tracks, locale: Locale = Locale.getDefault(), locked: (Format) -> Boolean = { false }): List<TrackOption> =
        tracks.groups.withIndex()
            .filter { (_, g) -> g.type == C.TRACK_TYPE_AUDIO || g.type == C.TRACK_TYPE_TEXT }
            .flatMap { (groupIndex, g) ->
                (0 until g.length).map { i ->
                    val format = g.getTrackFormat(i)
                    val audio = g.type == C.TRACK_TYPE_AUDIO
                    val label = if (audio) audioLabel(format, locale) else textLabel(format, locale)
                    val tags = tags(format)
                    TrackOption(
                        type = g.type,
                        groupIndex = groupIndex,
                        trackIndex = i,
                        language = format.language?.takeUnless { it.isBlank() || it == C.LANGUAGE_UNDETERMINED },
                        label = label,
                        tags = tags,
                        name = format.label?.takeIf { addsTo(it, label, tags, format, locale) },
                        playable = g.getTrackSupport(i) == C.FORMAT_HANDLED,
                        locked = locked(format),
                        isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                        selected = g.isTrackSelected(i),
                        key = TrackKey(format.id, format.mime(), format.channelCount, format.label),
                        selectionFlags = format.selectionFlags,
                        roleFlags = format.roleFlags,
                    )
                }
            }

    private fun audioLabel(format: Format, locale: Locale) =
        listOfNotNull(languageName(format.language, locale), channels(format.channelCount), codec(format.mime())).joinToString(SEPARATOR)

    private fun textLabel(format: Format, locale: Locale) =
        listOfNotNull(languageName(format.language, locale), subtitleFormat(format.mime())).joinToString(SEPARATOR)

    /**
     * The track's own format: subtitles parsed while extracting (Media3's default) keep it in codecs. A play's player
     * leaves PGS and VobSub to the text renderer, so their formats are the tracks' own (PlaySubtitleParserFactory).
     */
    private fun Format.mime(): String? = if (sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) codecs else sampleMimeType

    private fun channels(count: Int): String? = when (count) {
        Format.NO_VALUE -> null
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$count channels"
    }

    private fun codec(mime: String?): String? = when {
        mime == null -> null
        mime == MimeTypes.AUDIO_AAC -> "AAC"
        mime == MimeTypes.AUDIO_AC3 -> "AC-3"
        mime == MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
        mime == MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3 Atmos"
        mime == MimeTypes.AUDIO_DTS -> "DTS"
        mime.startsWith(MimeTypes.AUDIO_DTS_HD) -> "DTS-HD"
        mime.startsWith("audio/vnd.dts.uhd") -> "DTS:X"
        mime == MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        mime == MimeTypes.AUDIO_FLAC -> "FLAC"
        mime == MimeTypes.AUDIO_OPUS -> "Opus"
        mime == MimeTypes.AUDIO_MPEG -> "MP3"
        mime == MimeTypes.AUDIO_RAW -> "PCM"
        mime == MimeTypes.AUDIO_VORBIS -> "Vorbis"
        else -> null
    }

    /** A subtitle track's format in its label's words ("SRT", "PGS"), by its own MIME type; null for one it has no name for. */
    internal fun subtitleFormat(mime: String?): String? = when (mime) {
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_SSA -> "SSA/ASS"
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.APPLICATION_VOBSUB -> "VobSub"
        MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_MP4VTT -> "WebVTT"
        MimeTypes.APPLICATION_TTML -> "TTML"
        else -> null
    }

    /**
     * The language's name in [locale], capitalised as a label starts. Media3 writes some languages
     * as macrolanguage plus language ("no-nob", "ms-ind"); those are named by the second, as its
     * two-letter code where it has one, which the platform names best ("nb": Norwegian Bokmål).
     */
    private fun languageName(code: String?, locale: Locale): String {
        val tag = code?.takeUnless { it.isBlank() || it == C.LANGUAGE_UNDETERMINED } ?: return UNKNOWN_LANGUAGE
        val parts = tag.split('-')
        val own = parts.getOrNull(1)?.takeIf { it.length == 3 && it.all(Char::isLetter) }
        val language = Locale.forLanguageTag(
            if (own != null) (listOf(twoLetter[own] ?: own) + parts.drop(2)).joinToString("-") else tag,
        )
        val name = language.getDisplayName(locale).takeIf { it.isNotBlank() && it != language.language } ?: return tag
        return name.replaceFirstChar { it.titlecase(locale) }
    }

    /** ISO 639-2 codes to ISO 639-1, where a language has both. */
    private val twoLetter: Map<String, String> by lazy {
        Locale.getISOLanguages().associateBy { runCatching { Locale(it).isO3Language }.getOrDefault("") }
    }

    private fun tags(format: Format): Set<TrackTag> {
        val name = format.label.orEmpty()
        return buildSet {
            if (format.roleFlags and C.ROLE_FLAG_COMMENTARY != 0 || COMMENTARY.containsMatchIn(name)) add(TrackTag.Commentary)
            if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0 || FORCED.containsMatchIn(name)) add(TrackTag.Forced)
            if (format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0 || SDH.containsMatchIn(name)) add(TrackTag.Sdh)
            if (format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) add(TrackTag.AudioDescription)
            if (format.roleFlags and C.ROLE_FLAG_MAIN != 0) add(TrackTag.Original)
        }
    }

    /**
     * Whether the track's [name] says something [label] and [tags] don't: any word left once the
     * language (in [locale], in English and in itself), the channels, the codec, the format and
     * the tags' words are taken out. "English SDH" adds nothing; "Director's commentary" does.
     */
    private fun addsTo(name: String, label: String, tags: Set<TrackTag>, format: Format, locale: Locale): Boolean {
        val language = format.language?.let { Locale.forLanguageTag(it) }
        val known = (
            listOf(label, languageName(format.language, Locale.ENGLISH), format.language.orEmpty()) +
                listOfNotNull(language?.let { it.getDisplayName(it) }, language?.let { runCatching { it.isO3Language }.getOrNull() }) +
                tags.map { it.label }
            ).flatMap(::words).toSet() + KNOWN_WORDS
        return words(name).any { it !in known && !COMMENTARY.containsMatchIn(it) && it.any(Char::isLetter) }
    }

    /** Lower-case words with their inner punctuation dropped, so "E-AC3", "E-AC-3" and "EAC3" meet, and "5.1" and "51". */
    private fun words(text: String): List<String> =
        text.lowercase(Locale.ROOT).split(Regex("[\\s()\\[\\]{},;:/&|_]+")).map { w -> w.filter(Char::isLetterOrDigit) }.filter { it.isNotEmpty() }

    private val COMMENTARY = Regex("commentary|kommentar", RegexOption.IGNORE_CASE)
    private val FORCED = Regex("\\bforced\\b", RegexOption.IGNORE_CASE)
    private val SDH = Regex("\\bsdh\\b|\\bcc\\b|hearing[ -]impaired", RegexOption.IGNORE_CASE)

    /** Words a track name may use that say no more than the label and tags. */
    private val KNOWN_WORDS = setOf(
        "audio", "track", "subtitles", "subtitle", "subs", "sub", "full", "default", "the", "for", "and", "with",
        "hearing", "impaired", "hi", "cc", "sdh", "forced", "description", "ad",
        "mono", "stereo", "surround", "20", "51", "71", "ch", "channels",
        "aac", "ac3", "eac3", "dd", "ddp", "dts", "dtshd", "truehd", "flac", "opus", "mp3", "pcm", "vorbis", "atmos",
        "srt", "subrip", "ssa", "ass", "pgs", "sup", "vobsub", "webvtt", "vtt", "ttml",
    )
}
