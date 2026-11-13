package io.skjaere.usenet.releases

import java.time.LocalDate
import java.util.Locale

/** Ordered from worst to best, so `compareTo` reads as "better than". */
enum class Source { Cam, Telesync, Dvd, Hdtv, WebRip, WebDl, BluRay, Remux }

enum class VideoCodec(val label: String) {
    H264("H.264"), Hevc("HEVC"), Av1("AV1"), Vp9("VP9"), Xvid("XviD"), Vc1("VC-1"), Mpeg2("MPEG-2"),
}

enum class Hdr(val label: String) { Hdr10("HDR10"), Hdr10Plus("HDR10+"), DolbyVision("Dolby Vision"), Hlg("HLG") }

enum class AudioCodec(val label: String) {
    Aac("AAC"), Ac3("DD"), Eac3("DD+"), Dts("DTS"), DtsHd("DTS-HD"), DtsHdMa("DTS-HD MA"), DtsX("DTS:X"),
    TrueHd("TrueHD"), Flac("FLAC"), Opus("Opus"), Mp3("MP3"), Pcm("PCM"),
}

/**
 * What a scene-style release name says about its content. Every field is a claim made by the
 * name, not a fact about the files; absent fields mean the name didn't say.
 */
data class ParsedRelease(
    val raw: String,
    val title: String,
    val year: Int? = null,
    val seasons: List<Int> = emptyList(),
    val episodes: List<Int> = emptyList(),
    val airDate: LocalDate? = null,
    val resolution: Int? = null,
    val source: Source? = null,
    val videoCodec: VideoCodec? = null,
    val bitDepth: Int? = null,
    val hdr: Set<Hdr> = emptySet(),
    val audio: List<AudioCodec> = emptyList(),
    val atmos: Boolean = false,
    val channels: String? = null,
    /**
     * ISO 639-1 codes of the audio. `DL` adds the original language, assumed to be English, and so does a
     * bare `DUBBED` (the scene's English dub; "German.Dubbed" is German); a language tagged as the
     * subtitles ("German.Subbed") is left out, so such a release reads as original audio.
     */
    val languages: Set<String> = emptySet(),
    val dualLanguage: Boolean = false,
    val multiLanguage: Boolean = false,
    val edition: String? = null,
    val proper: Boolean = false,
    val discImage: Boolean = false,
    val group: String? = null,
) {
    val isEpisode: Boolean get() = episodes.isNotEmpty() || airDate != null
    val isSeasonPack: Boolean get() = seasons.isNotEmpty() && !isEpisode
    val normalizedTitle: String get() = normalizeTitle(title)

    /** Ten-bit video: stated, or implied by HDR on codecs that carry HDR. */
    val tenBit: Boolean
        get() = (bitDepth ?: 8) >= 10 || (hdr.isNotEmpty() && videoCodec in setOf(VideoCodec.Hevc, VideoCodec.Av1, VideoCodec.Vp9))

    /** Dolby Vision without an HDR10 base layer (typically profile 5): unwatchable without a DV decoder. */
    val dolbyVisionOnly: Boolean
        get() = Hdr.DolbyVision in hdr && Hdr.Hdr10 !in hdr && Hdr.Hdr10Plus !in hdr

    /** Releases of the same movie or episode share this key. */
    val groupKey: String
        get() = buildString {
            append(normalizedTitle)
            year?.let { append(" (").append(it).append(')') }
            when {
                airDate != null -> append(' ').append(airDate)
                isEpisode -> append(" s%02d".format(Locale.ROOT, seasons.firstOrNull() ?: 0))
                    .append(episodes.joinToString("") { "e%02d".format(Locale.ROOT, it) })
                isSeasonPack -> append(" ").append(seasons.joinToString("-") { "s%02d".format(Locale.ROOT, it) })
            }
        }
}

/**
 * A comparison key of lower-case ASCII words: "Salt & Signal's" and "salt.and.signals" compare equal, and so do
 * the scene spellings of letters accents can't be stripped from ("Weißenwald" and "Weissenwald", "Æon" and "Aeon").
 * Umlauts are spelled ae/oe/ue in some names and without dots in others, so a vowel's trailing e is dropped:
 * "Für", "Fuer" and "Fur" all compare equal.
 */
fun normalizeTitle(title: String): String =
    java.text.Normalizer.normalize(
        title.lowercase().map { TRANSLITERATIONS[it] ?: it.toString() }.joinToString(""),
        java.text.Normalizer.Form.NFD,
    )
        .replace(Regex("\\p{M}+"), "")
        .replace("&", " and ")
        .replace(Regex("['’`]"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("([aou])e+"), "$1")
        .trim()

/** Lower-case letters NFD does not decompose, as scene names spell them. */
private val TRANSLITERATIONS = mapOf(
    'ß' to "ss", 'æ' to "ae", 'ø' to "o", 'œ' to "oe", 'ł' to "l", 'đ' to "d", 'ð' to "d", 'þ' to "th", 'ı' to "i",
)
