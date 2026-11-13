package io.skjaere.usenet.app.collection.model

import java.text.Normalizer

/**
 * A row's title as the lists search and sort it. The keys are worked out when first needed, once per
 * instance, and a row built again keeps its instance while the title stays the same: the default sorts
 * and an empty search never need them.
 */
class TitleText(val name: String, val originalName: String?, val year: Int?) {
    /** The title, the original title and the year, folded ([TitleSearch.fold]). */
    val searchKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        TitleSearch.fold(listOfNotNull(name, originalName?.takeIf { it != name }, year?.toString()).joinToString(" "))
    }

    /** The title folded, a leading "the", "a" or "an" left out ("The Quiet Orbit" sorts under Q; a title that *is* "The" stays). */
    val sortKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val folded = TitleSearch.fold(name)
        ARTICLES.firstOrNull { folded.startsWith(it) && folded.length > it.length }?.let { folded.removePrefix(it) } ?: folded
    }

    /** What A–Z files it under: A to Z, or '#' for a digit or anything else. */
    val letter: Char by lazy(LazyThreadSafetyMode.PUBLICATION) { sortKey.firstOrNull()?.takeIf { it in 'a'..'z' }?.uppercaseChar() ?: '#' }

    /** Each of [tokens] (folded, as [TitleSearch.tokens] gives them) starts a word of [searchKey]. */
    fun matches(tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return true
        val words = " $searchKey"
        return tokens.all { words.contains(" $it") }
    }

    fun isFor(name: String, originalName: String?, year: Int?) = this.name == name && this.originalName == originalName && this.year == year

    override fun equals(other: Any?) = other is TitleText && other.isFor(name, originalName, year)

    override fun hashCode() = (name.hashCode() * 31 + originalName.hashCode()) * 31 + (year ?: 0)

    override fun toString() = "TitleText($name, $originalName, $year)"

    private companion object {
        val ARTICLES = listOf("the ", "a ", "an ")
    }
}

/** How titles and what is typed are compared: lower case, without accents or punctuation. */
object TitleSearch {
    private val marks = Regex("\\p{Mn}+")

    /**
     * Lower case, accents dropped ("É" → "e"), apostrophes dropped ("Ébrin's" → "ebrins"), anything
     * else that isn't a letter or a digit a space, spaces collapsed. ASCII, most titles, skips the
     * Unicode normalisation.
     */
    fun fold(text: String): String = if (text.all { it.code < 0x80 }) clean(text.lowercase()) else foldSlowly(text)

    /** [fold] without the fast path, which must give the same for ASCII. */
    fun foldSlowly(text: String): String = clean(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(marks, ""))

    /** What is typed, folded and split into words. */
    fun tokens(query: String): List<String> = fold(query).split(' ').filter { it.isNotEmpty() }

    private fun clean(lower: String): String {
        val out = StringBuilder(lower.length)
        var space = true
        for (c in lower) {
            when {
                c == '\'' || c == '’' -> Unit
                c.isLetterOrDigit() -> { out.append(c); space = false }
                !space -> { out.append(' '); space = true }
            }
        }
        return out.trimEnd().toString()
    }
}
