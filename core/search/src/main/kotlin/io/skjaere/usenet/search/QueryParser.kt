package io.skjaere.usenet.search

import io.skjaere.usenet.releases.ReleaseParser
import java.util.Locale

/** What the user typed, split into words and the parts indexers can search by. */
data class ParsedQuery(
    val raw: String,
    /** The query without episode/season/year tokens, e.g. "harbor lights". */
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
) {
    val isEpisode: Boolean get() = season != null && episode != null

    /** The season/episode as release names write it ("S02E05", "S02"); null without a season. */
    val episodeTag: String?
        get() = season?.let { s -> "S%02d".format(Locale.ROOT, s) + episode?.let { "E%02d".format(Locale.ROOT, it) }.orEmpty() }

    /**
     * A pasted release name ("Harbor.Lights.S02E05.1080p.WEB.h264-GRP"), searched as it is: one dot- or
     * underscore-separated token, or words tagged with a resolution or source. A trailing "-word" alone
     * doesn't count: it's as often part of a title ("Tide-Walker") as a release group.
     */
    val isReleaseName: Boolean
        get() = (' ' !in raw && raw.any { it == '.' || it == '_' }) ||
            ReleaseParser.parse(raw).let { it.resolution != null || it.source != null }
}

object QueryParser {
    private val seasonEpisode = Regex("""(?i)\bs(\d{1,2})\s*e(\d{1,3})\b""")
    private val cross = Regex("""(?i)\b(\d{1,2})x(\d{2,3})\b""")
    private val seasonOnly = Regex("""(?i)\b(?:s|season\s*)(\d{1,2})\b""")
    private val year = Regex("""\b(19\d\d|20\d\d)\b""")

    fun parse(query: String): ParsedQuery {
        val raw = query.trim().replace(Regex("\\s+"), " ")
        var rest = raw
        var season: Int? = null
        var episode: Int? = null
        (seasonEpisode.find(rest) ?: cross.find(rest))?.let { m ->
            season = m.groupValues[1].toInt()
            episode = m.groupValues[2].toInt()
            rest = rest.removeRange(m.range)
        } ?: seasonOnly.find(rest)?.let { m ->
            season = m.groupValues[1].toInt()
            rest = rest.removeRange(m.range)
        }
        // A year counts only after other words: "1999" alone is a title.
        val y = year.findAll(rest).lastOrNull()?.takeIf { it.range.first > 0 }
        y?.let { rest = rest.removeRange(it.range) }
        val title = rest.replace(Regex("\\s+"), " ").trim()
        return ParsedQuery(raw, title.ifEmpty { raw }, season, episode, y?.value?.toInt())
    }
}
