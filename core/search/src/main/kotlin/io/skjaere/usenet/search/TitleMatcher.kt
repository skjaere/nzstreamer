package io.skjaere.usenet.search

import io.skjaere.usenet.releases.ParsedRelease
import io.skjaere.usenet.releases.normalizeTitle
import kotlin.math.abs

/** Does a release's parsed title name this show or movie? Name or alias, year within one. */
object TitleMatcher {
    fun matches(parsed: ParsedRelease, name: String, year: Int?, aliases: List<String> = emptyList()): Boolean {
        if (parsed.year != null && year != null && abs(parsed.year!! - year) > 1) return false
        val names = (listOf(name) + aliases).map(::normalizeTitle).filter { it.isNotEmpty() }
        val title = parsed.normalizedTitle
        return names.any { n -> title == n || (year != null && title == "$n $year") }
    }
}
