package io.skjaere.usenet.releases

import java.time.LocalDate

/**
 * Parses scene-style release names ("Title.S01E02.German.DL.1080p.WEB.H264-GROUP").
 *
 * Each token pattern is matched with word boundaries that treat `.`, `_`, `-`, spaces and brackets
 * as separators. The title ends at the first anchor: an episode, season or air-date token, or a token
 * no title contains (resolution, codec, `WEB-DL`, `BluRay`, ...); a year directly before it belongs to
 * the release, not the title. Word-like tokens (languages, `WEB`, `Opus`, editions, `5.1`, ...) are
 * only read after that point, so "The.French.Lantern.2021" is neither cut short nor French; they end
 * the title only in names with no anchor and no year ("Show.German.DL"), or in names with no year where
 * they run straight up to a resolution, codec or source token ("Northbound.German.DL.1080p").
 */
object ReleaseParser {

    fun parse(name: String): ParsedRelease {
        val raw = name.trim()
        val (body, group) = splitGroup(unwrap(stripExtension(raw)))
        val anchors = mutableListOf<IntRange>()

        val airDate = DAILY.find(body)?.let { m ->
            anchors += m.range
            runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
        }
        val (seasons, episodes) = parseSeasonsAndEpisodes(body, anchors)
        anchors += COMPLETE_SERIES.findAll(body).map { it.range }
        val tagAnchors = TAG_ANCHORS.flatMap { regex -> regex.findAll(body).map { it.range } }
        val allAnchors = anchors + tagAnchors

        // A title is never empty, so a token at the very start ("4K.Wildlife.2019") is part of it.
        val anchorStart = allAnchors.filter { it.first > 0 }.minOfOrNull { it.first }
        val end = anchorStart ?: body.length
        val years = YEAR.findAll(body).filter { y -> allAnchors.none { y.range.first in it } }.toList()
        val releaseYear = years.lastOrNull { it.range.first in 1..end } ?: years.firstOrNull { it.range.first >= end }
        val titleEnd = releaseYear?.range?.first?.takeIf { it < end }
            ?: anchorStart?.let { start -> if (anchors.any { it.first in 1..start }) start else tagsBefore(body, start) }
            ?: MARKERS.flatMap { it.findAll(body) }.map { it.range.first }.filter { it > 0 }.minOrNull()
            ?: body.length

        // Everything below reads only the release part, after the title.
        fun Regex.all() = findAll(body, titleEnd).toList()
        fun Regex.first() = find(body, titleEnd)

        val resolution = RESOLUTION.first()?.let { it.groupValues[1].toInt() } ?: UHD.first()?.let { 2160 }
        val source = SOURCES.firstNotNullOfOrNull { (source, regex) -> regex.all().takeIf { it.isNotEmpty() }?.let { source } }
        val discImage = DISC.all().isNotEmpty()
        val videoCodec = VIDEO.firstNotNullOfOrNull { (codec, regex) -> regex.first()?.let { codec } }
        val bitDepth = BIT_DEPTH.first()?.let { m -> if (m.value.startsWith("hi", ignoreCase = true)) 10 else m.groupValues[1].toInt() }
        val hdr = HDR.filter { (_, regex) -> regex.all().isNotEmpty() }.map { it.first }.toSet()

        val audioHits = AUDIO.all()
        val audio = audioHits.mapNotNull { audioCodec(it.groupValues[1]) }.distinct()
        val channels = audioHits.firstNotNullOfOrNull { m -> m.groupValues[2].takeIf { it.isNotEmpty() }?.let { "$it.${m.groupValues[3]}" } }
            ?: CHANNELS.first()?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
        val atmos = ATMOS.all().isNotEmpty()
        val germanDub = GERMAN_DUB.all().isNotEmpty()

        // A language directly followed by a subtitle tag ("German.Subbed") names the subtitles, not the audio.
        fun Regex.spoken() = all().filterNot { SUBTITLED.matchesAt(body, it.range.last + 1) }
        val stated = LANGUAGES.filter { (_, regex) -> regex.spoken().isNotEmpty() }.map { it.first }.toSet()
        val dualLanguage = DUAL_LANGUAGE.all().isNotEmpty()
        val multiLanguage = MULTI.spoken().isNotEmpty()
        // A bare DUBBED is the scene's English dub of a foreign title.
        val englishDub = DUBBED.all().isNotEmpty() && stated.isEmpty() && !germanDub && !multiLanguage
        val languages = stated + (if (germanDub) setOf("de") else emptySet()) + (if (dualLanguage || englishDub) setOf("en") else emptySet())

        val edition = EDITIONS.firstNotNullOfOrNull { (label, regex) -> regex.all().takeIf { it.isNotEmpty() }?.let { label } }
        val proper = PROPER.all().isNotEmpty()

        return ParsedRelease(
            raw = raw,
            title = cleanTitle(body.substring(0, titleEnd)),
            year = releaseYear?.value?.toInt(),
            seasons = seasons,
            episodes = episodes,
            airDate = airDate,
            resolution = resolution,
            source = source,
            videoCodec = videoCodec,
            bitDepth = bitDepth,
            hdr = hdr,
            audio = audio,
            atmos = atmos,
            channels = channels,
            languages = languages,
            dualLanguage = dualLanguage,
            multiLanguage = multiLanguage,
            edition = edition,
            proper = proper,
            discImage = discImage,
            group = group,
        )
    }

    private fun parseSeasonsAndEpisodes(body: String, anchors: MutableList<IntRange>): Pair<List<Int>, List<Int>> {
        SEASON_EPISODE.find(body)?.let { m ->
            anchors += m.range
            val first = m.groupValues[2].toInt()
            val more = Regex("\\d+").findAll(m.groupValues[3]).map { it.value.toInt() }.toList()
            val rangeEnd = m.groupValues[4].toIntOrNull()
            val episodes = when {
                rangeEnd != null && rangeEnd > first -> (first..rangeEnd).toList()
                more.size == 1 && '-' in m.groupValues[3] && more[0] > first -> (first..more[0]).toList()
                else -> listOf(first) + more
            }
            return listOf(m.groupValues[1].toInt()) to episodes
        }
        CROSS_EPISODE.find(body)?.let { m ->
            anchors += m.range
            return listOf(m.groupValues[1].toInt()) to listOf(m.groupValues[2].toInt())
        }
        SEASON_LIST.find(body)?.let { m ->
            anchors += m.range
            return Regex("\\d+").findAll(m.value).map { it.value.toInt() }.distinct().toList() to emptyList()
        }
        (SEASON_RANGE.find(body) ?: SEASON_WORD.find(body) ?: SEASON.find(body))?.let { m ->
            anchors += m.range
            val from = m.groupValues[1].toInt()
            val to = m.groupValues.getOrNull(2)?.toIntOrNull()?.takeIf { it > from } ?: from
            return (from..to).toList() to emptyList()
        }
        return emptyList<Int>() to emptyList()
    }

    /**
     * Moves [end] back over the word tokens that run straight up to it, separated only by separators
     * ("Northbound.German.DL" before "1080p"), but never to the start of the name.
     */
    private fun tagsBefore(body: String, end: Int): Int {
        val tags = (MARKERS - CHANNELS).flatMap { it.findAll(body) }.map { it.range }.filter { it.first > 0 }
        return generateSequence(end) { current ->
            tags.filter { it.last < current && TAG_GAP.matches(body.substring(it.last + 1, current)) }.minOfOrNull { it.first }
        }.last()
    }

    private fun stripExtension(name: String): String =
        name.replace(Regex("\\.(nzb|mkv|mp4|m4v|avi|ts|nfo)$", RegexOption.IGNORE_CASE), "")

    /**
     * [name] without what torrent names (and some others) carry around the release's own (L178): a site's tag before it
     * ("www.example.org - ", "[ www.example.org ]") and the brackets after it holding nothing the parser reads ("[siteA]",
     * "(SiteB)"). A bracket that holds a quality token, a year or an episode is the release's, and stays: "[x265]" was once
     * dropped as the one bracket at the end (review finding #58).
     */
    private fun unwrap(name: String): String {
        var text = SITE_TAG.replace(name, "")
        while (true) {
            val bracket = TRAILING_BRACKET.find(text) ?: break
            if (READ_IN_BRACKETS.any { it.containsMatchIn(bracket.groupValues[1]) }) break
            text = text.substring(0, bracket.range.first)
        }
        return text
    }

    /** `-GROUP` at the end, unless the suffix is part of a token (`WEB-DL`, `DTS-HD.MA`, `E-AC-3`). */
    private fun splitGroup(name: String): Pair<String, String?> {
        val m = Regex("-([A-Za-z0-9]+)$").find(name) ?: return name to null
        val candidate = m.groupValues[1]
        val partOfToken = candidate.uppercase() in NOT_GROUPS || candidate.all { it.isDigit() } ||
            RESOLUTION.matches(candidate) || VIDEO.any { (_, regex) -> regex.matches(candidate) }
        return if (partOfToken) name to null else name.substring(0, m.range.first) to candidate
    }

    private fun cleanTitle(text: String): String =
        text.replace(Regex("[._\\[\\]()]+"), " ").replace(Regex("\\s+"), " ").trim().trim('-', ' ')

    private fun audioCodec(token: String): AudioCodec? {
        val t = token.uppercase().replace(Regex("[ ._-]"), "")
        return when {
            t == "DDP" || t == "DD+" || t.startsWith("EAC3") -> AudioCodec.Eac3
            t == "DD" || t.startsWith("AC3") || t == "DOLBYDIGITAL" -> AudioCodec.Ac3
            t == "AAC" -> AudioCodec.Aac
            t == "DTSHDMA" -> AudioCodec.DtsHdMa
            t == "DTSHD" -> AudioCodec.DtsHd
            t == "DTSX" -> AudioCodec.DtsX
            t.startsWith("DTS") -> AudioCodec.Dts
            t == "TRUEHD" -> AudioCodec.TrueHd
            t == "FLAC" -> AudioCodec.Flac
            t == "OPUS" -> AudioCodec.Opus
            t == "MP3" -> AudioCodec.Mp3
            t == "PCM" || t == "LPCM" -> AudioCodec.Pcm
            else -> null
        }
    }

    private const val B1 = "(?<![A-Za-z0-9])"
    private const val B2 = "(?![A-Za-z0-9])"
    private const val SEP = "[ ._-]?"
    private fun token(pattern: String) = Regex("$B1(?:$pattern)$B2", RegexOption.IGNORE_CASE)

    private val DAILY = token("((?:19|20)\\d\\d)[ ._-](0[1-9]|1[0-2])[ ._-](0[1-9]|[12]\\d|3[01])")
    private val SEASON_EPISODE = token("S(\\d{1,3})[ ._]?E(\\d{1,4})((?:[ ._-]*E\\d{1,4})*)(?:-(\\d{1,4}))?")
    private val CROSS_EPISODE = token("(\\d{1,2})x(\\d{2,3})")
    /** "S01-S03", "S01 - S03", or "S01-03" (not "S2 - 04", which some name an episode by). */
    private val SEASON_RANGE = token("S(\\d{1,2})(?:$SEP-${SEP}S|-)(\\d{1,2})")
    /** "S01.S02.S03": the seasons one after another. */
    private val SEASON_LIST = token("S\\d{1,2}(?:[ ._]S\\d{1,2})+")
    private val SEASON_WORD = token("Seasons?$SEP(\\d{1,2})(?:$SEP-$SEP(\\d{1,2}))?")
    private val SEASON = token("S(\\d{1,2})")
    private val COMPLETE = token("COMPLETE")
    private val YEAR = token("(?:19|20)\\d\\d")
    private val RESOLUTION = token("(\\d{3,4})[pi]")
    private val UHD = token("4K|UHD")

    private val SOURCES = listOf(
        Source.Remux to token("(?:BD)?REMUX"),
        Source.WebRip to token("WEB${SEP}RIP"),
        Source.WebDl to token("WEB${SEP}DL|WEB|AMZN|NF|DSNP|ATVP|HMAX"),
        Source.BluRay to token("BLU${SEP}RAY|BDRIP|BRRIP|BD"),
        Source.Hdtv to token("HDTV|PDTV|SDTV|DSR"),
        Source.Dvd to token("DVDRIP|DVD"),
        Source.Telesync to token("TS|HDTS|TELESYNC|TC|TELECINE"),
        Source.Cam to token("CAM|HDCAM|CAMRIP"),
    )
    private val DISC = token("BDMV|ISO|DVD[59R]|BD(?:25|50|66|100)|COMPLETE${SEP}(?:UHD$SEP)?BLU${SEP}RAY|AVC${SEP}BD")

    private val VIDEO = listOf(
        VideoCodec.Hevc to token("[XH]\\.?265|HEVC"),
        VideoCodec.H264 to token("[XH]\\.?264|AVC"),
        VideoCodec.Av1 to token("AV1"),
        VideoCodec.Vp9 to token("VP9"),
        VideoCodec.Xvid to token("XVID|DIVX"),
        VideoCodec.Vc1 to token("VC${SEP}1"),
        VideoCodec.Mpeg2 to token("MPEG${SEP}2"),
    )
    private val BIT_DEPTH = token("(8|10|12)${SEP}BIT|HI10P?")
    private val HDR = listOf(
        Hdr.Hdr10Plus to Regex("$B1(?:HDR10(?:\\+|PLUS))(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        Hdr.Hdr10 to token("HDR10|HDR"),
        Hdr.DolbyVision to token("DV|DOVI|DOLBY${SEP}VISION"),
        Hdr.Hlg to token("HLG"),
    )

    private val AUDIO = Regex(
        "$B1(E?AC3[MLD]D?|DTSD|DDP|DD\\+|E${SEP}AC${SEP}3|DD|AC${SEP}3|DOLBY${SEP}DIGITAL|AAC|DTS${SEP}HD${SEP}MA|DTS${SEP}HD|DTS${SEP}X|DTS${SEP}ES|DTS|TRUE${SEP}HD|FLAC|OPUS|L?PCM|MP3)" +
            "(?:[ ._]?([1-9])[ .]([01]))?$B2",
        RegexOption.IGNORE_CASE,
    )
    private val CHANNELS = token("([1-9])[ .]([01])")
    private val ATMOS = token("ATMOS")
    /** German scene tags for dubbed audio: AC3D, EAC3D, DTSD (+ MD/LD variants for mic/line dubs). */
    private val GERMAN_DUB = token("E?AC3[MLD]D?|E?AC3D|DTSD")

    private val LANGUAGES = listOf(
        "de" to token("GERMAN|DEUTSCH"),
        "en" to token("ENGLISH|ENG"),
        "fr" to token("FRENCH|TRUEFRENCH|VFF"),
        "es" to token("SPANISH|CASTELLANO|LATINO"),
        "it" to token("ITALIAN|ITA"),
        "nl" to token("DUTCH|FLEMISH"),
        "pl" to token("POLISH|PL"),
        "ru" to token("RUSSIAN|RUS"),
        "ja" to token("JAPANESE|JPN"),
        "ko" to token("KOREAN|KOR"),
        "zh" to token("CHINESE|MANDARIN|CANTONESE"),
        "sv" to token("SWEDISH|SWE"),
        "da" to token("DANISH"),
        "no" to token("NORWEGIAN|NOR"),
        "fi" to token("FINNISH"),
        "tr" to token("TURKISH"),
        "pt" to token("PORTUGUESE"),
        "hi" to token("HINDI"),
    )
    private val DUAL_LANGUAGE = Regex("$B1(?<!WEB[ ._-])(?:DL|DUAL)$B2", RegexOption.IGNORE_CASE)
    private val MULTI = token("MULTI|ML")
    /** Read straight after a language token: "German.Subbed", "ENG.SUBS", "Korean.HC", "MULTi.SUBS". */
    private val SUBTITLED = Regex("[ ._-]+(?:(?:HARD$SEP)?SUB(?:BED|S)?|HC)$B2", RegexOption.IGNORE_CASE)

    private val EDITIONS = listOf(
        "Extended" to token("EXTENDED(?:${SEP}(?:CUT|EDITION))?"),
        "Director's Cut" to token("DIRECTORS?$SEP'?S?${SEP}CUT"),
        "Unrated" to token("UNRATED"),
        "Uncut" to token("UNCUT"),
        "Theatrical" to token("THEATRICAL(?:${SEP}CUT)?"),
        "Remastered" to token("REMASTERED"),
        "IMAX" to token("IMAX"),
        "Criterion" to token("CRITERION"),
    )
    private val PROPER = token("PROPER|REPACK\\d?|RERIP")
    private val DUBBED = token("DUBBED")
    private val OTHER_MARKERS = token("INTERNAL|LIMITED|READ${SEP}NFO|SUBBED|HC|HARDSUB|DOCU|DOKU|CONVERT|SDR")

    /** "COMPLETE.SERIES" ends a title like a season token; "COMPLETE" alone can be a word ("A.Complete.Harbor"). */
    private val COMPLETE_SERIES = token("(?:THE$SEP)?COMPLETE${SEP}(?:SERIES|SEASONS?)")
    /** Sources spelled the way no title is; "WEB", "BD" or "CAM" alone can be words ("Gull's.Web"). */
    private val SOURCE_ANCHOR = token(
        "(?:BD)?REMUX|WEB${SEP}(?:DL|RIP)|BLU${SEP}RAY|BDRIP|BRRIP|HDTV|PDTV|SDTV|DVDRIP|HDTS|TELESYNC|TELECINE|HDCAM|CAMRIP",
    )
    /** Tokens that end the title wherever they appear, along with season, episode, air-date and complete-series tokens. */
    private val TAG_ANCHORS = listOf(RESOLUTION, UHD, SOURCE_ANCHOR) + VIDEO.map { it.second }
    private val TAG_GAP = Regex("[ ._\\-\\[\\]()]*")
    /**
     * Word-like tokens: read only after the title. They end it in a name with no anchor or year, or when
     * they run straight up to the first tag anchor in a name with no year ("Northbound.German.DL.1080p").
     */
    private val MARKERS = listOf(COMPLETE, DISC, BIT_DEPTH, AUDIO, CHANNELS, ATMOS, GERMAN_DUB, DUAL_LANGUAGE, MULTI, PROPER, DUBBED, OTHER_MARKERS) +
        listOf(SOURCES, HDR, LANGUAGES, EDITIONS).flatMap { tokens -> tokens.map { it.second } }

    private val NOT_GROUPS = setOf("DL", "RIP", "HD", "MA", "X", "ES", "RAY", "AC3", "DTS", "CUT", "NFO", "SUBS")

    /**
     * A site's tag at the start: its address in brackets, or followed by a space (a dash after either). Never an address
     * run into the name by dots: where it would end can't be told from the name's own words. Unbracketed, the address is a
     * name, at most one more label and a top-level domain right before the space, so it can't run on over a dotted name's
     * own words to a space before a trailing tag (DDR-4: "www.Example.org.Quiet.Orbit…-GRP [siteA]" was all taken).
     */
    private val SITE_TAG = Regex(
        "^\\s*(?:\\[\\s*www\\.[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+\\s*]\\s*(?:-\\s*)?|www\\.[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)?\\.[A-Za-z]{2,6}\\s+(?:-\\s*)?)",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_BRACKET = Regex("\\s*[\\[(]([^\\[\\]()]*)[\\])]$")

    /** What makes a trailing bracket the release's own: anything [parse] reads after the title, a year and an episode. */
    private val READ_IN_BRACKETS = TAG_ANCHORS + MARKERS + listOf(YEAR, DAILY, SEASON_EPISODE, CROSS_EPISODE, SEASON_WORD, SEASON)
}
