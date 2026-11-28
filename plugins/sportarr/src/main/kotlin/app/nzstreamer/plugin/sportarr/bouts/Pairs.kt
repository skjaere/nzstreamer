package app.nzstreamer.plugin.sportarr.bouts

import app.nzstreamer.plugin.sportarr.source.Families
import java.util.Locale

/**
 * W6 as L-B4 writes it, applied while a card is read: a pair's order must never say who won, and Wikipedia moves each
 * winner to the left of its results table. So every pair is put in one total order of the plugin's own, by surname, then
 * the whole name, then the text; the main event alone follows the event's title, and only where that title names both
 * fighters, one on each side. Whichever way round a table lists a pair, it reads the same.
 */
internal object Pairs {
    /** A name's text from its cell's (whose `<sup>` and bracketed marks the reader took off): "(c)" and "(ic)" taken off. */
    fun name(cell: String): String = Html.collapse(CHAMPION.replace(cell, " "))

    /** Whether [cell] holds a champion's "(c)" or an interim champion's "(ic)". */
    fun champion(cell: String): Boolean = CHAMPION.containsMatchIn(cell)

    /**
     * [name]'s surname key: its last word that isn't Jr., Sr., II, III or IV (dots ignored), folded ("Quéssel" is
     * "QUESSEL"), its letters and digits alone. A name of nothing but such words keys by its last.
     */
    fun surnameKey(name: String): String {
        val words = name.split(SPACES).filter { it.isNotEmpty() }
        val last = words.lastOrNull { word -> word.replace(".", "").uppercase(Locale.ROOT) !in SUFFIXES } ?: words.lastOrNull()
        return key(last.orEmpty())
    }

    /** [name]'s full key: each of its words folded as the surname key is, single spaces between them. */
    fun fullKey(name: String): String = name.split(SPACES).map(::key).filter { it.isNotEmpty() }.joinToString(" ")

    /** [a] and [b] in the pairs' one order: by surname key, then full key, then the text by code point. */
    fun ordered(a: String, b: String): Pair<String, String> = if (ORDER.compare(a, b) <= 0) a to b else b to a

    /**
     * The main event's pair: as [ordered] gives it, unless the event's [title] (Sportarr's, as the page shows it) names both
     * fighters, one on each of its sides ([Families.sides]): then the fighter on its first side goes first. Only where the
     * two surname keys differ and each is among one side's words and not the other's; a shared surname, a side naming
     * both, or a fighter the title doesn't name leaves the order as it is, so the table's never decides.
     */
    fun mainEvent(a: String, b: String, title: String): Pair<String, String> {
        val pair = ordered(a, b)
        val (home, away) = Families.sides(title) ?: return pair
        val first = surnameKey(pair.first)
        val second = surnameKey(pair.second)
        if (first == second) return pair
        val one = home.split(SPACES).map(::key).toSet()
        val two = away.split(SPACES).map(::key).toSet()
        fun onlyOne(key: String) = key in one && key !in two
        fun onlyTwo(key: String) = key in two && key !in one
        return if (onlyOne(second) && onlyTwo(first)) pair.second to pair.first else pair
    }

    /** A word as the keys compare it: folded, its letters and digits alone. */
    private fun key(word: String): String = Html.fold(word).filter(Char::isLetterOrDigit)

    /** Two strings by their code points, so the order is one whatever characters they hold. */
    private fun byCodePoint(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        return when {
            i < a.length -> 1
            j < b.length -> -1
            else -> 0
        }
    }

    private val ORDER: Comparator<String> = compareBy<String>({ surnameKey(it) }, { fullKey(it) }).then(::byCodePoint)

    private val CHAMPION = Regex("""\(i?c\)""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")
    private val SUFFIXES = setOf("JR", "SR", "II", "III", "IV")
}
