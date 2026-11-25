package app.nzstreamer.plugin.sportarr.match

import app.nzstreamer.plugin.sportarr.model.Numbering
import app.nzstreamer.plugin.sportarr.model.SessionKind
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import kotlin.coroutines.cancellation.CancellationException

/**
 * One word of a release name (L646): as [written], its case kept for the team-code rule (L648), and as [word], the form
 * every rule compares: lower case, diacritics folded, apostrophes dropped ("Women's" is "womens"); "&" is "and" and "@"
 * stays "@". [sep] is what stood between it and the word before ("." or " - "), empty for the first.
 */
data class Token(val written: String, val word: String, val sep: String) {
    /** Whether it is a whole number: digits alone. */
    val isNumber: Boolean get() = word.isNotEmpty() && word.all { it in '0'..'9' }

    val hasLetter: Boolean get() = word.any(Char::isLetter)

    override fun toString(): String = written
}

/** How a name's words are split and folded, the same for a release name and the event's own words it is read against. */
object Tokens {
    /**
     * [text]'s words: split at dots, spaces, underscores, hyphens, slashes, brackets and every other mark; "&" and "@" are
     * words of their own; an apostrophe inside a word joins it ("Women's").
     */
    fun of(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        val written = StringBuilder()
        val sep = StringBuilder()
        fun flush() {
            if (written.isEmpty()) return
            val word = fold(written)
            if (word.isEmpty()) {
                // A word of marks alone (a stray accent) folds to nothing: it separates, as any other mark.
                sep.append(written)
            } else {
                tokens += Token(written.toString(), word, sep.toString())
                sep.setLength(0)
            }
            written.setLength(0)
        }
        for (i in text.indices) {
            val c = text[i]
            when {
                c.isLetterOrDigit() || isMark(c) -> written.append(c)
                c in APOSTROPHES && written.isNotEmpty() && text.getOrNull(i + 1)?.isLetter() == true -> Unit
                c == '&' || c == '@' -> {
                    flush()
                    tokens += Token(c.toString(), if (c == '&') "and" else "@", sep.toString())
                    sep.setLength(0)
                }
                else -> {
                    flush()
                    sep.append(c)
                }
            }
        }
        flush()
        return tokens
    }

    /** [text] as the rules compare it: decomposed, its accents dropped, lower case, and the letters that don't decompose spelled out. */
    fun fold(text: CharSequence): String {
        val out = StringBuilder(text.length)
        for (c in Normalizer.normalize(text, Normalizer.Form.NFD)) {
            if (isMark(c)) continue
            when (c) {
                'ß' -> out.append("ss")
                'æ', 'Æ' -> out.append("ae")
                'œ', 'Œ' -> out.append("oe")
                'þ', 'Þ' -> out.append("th")
                'ø', 'Ø' -> out.append('o')
                'đ', 'Đ' -> out.append('d')
                'ł', 'Ł' -> out.append('l')
                'ı' -> out.append('i')
                else -> out.append(c.lowercaseChar())
            }
        }
        return out.toString()
    }

    private fun isMark(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    /** The straight apostrophe, the typographic ones and the modifier letter, as names write "Women's". */
    private const val APOSTROPHES = "'\u2019\u02BC\u2018"
}

/**
 * What a release name is read against (L646): the event's own [title], its [league]'s name, its [sides] and its [series]'
 * forms. Their numbers are masked where they stand in the name beside a word of theirs, so a race's "500" or a club's "03"
 * is never read as a date, a year or a score; and no word of theirs is a result word. [organisations] are the words a card
 * number follows ("UFC" of "UFC 999"). [show] says the event is a show with a pre-show, whose "Countdown" is that part
 * rather than a preview programme.
 */
data class NameContext(
    val title: String? = null,
    val league: String? = null,
    val sides: List<String> = emptyList(),
    val series: List<String> = emptyList(),
    val organisations: List<String> = emptyList(),
    val show: Boolean = false,
) {
    companion object {
        /** A name read against nothing of an event's. */
        val NONE = NameContext()
    }
}

/**
 * A date a name holds (L646), each way it reads (one, or a day-first date's two while its day is 12 or less), its [form],
 * and where it stands: [at], the place of its first word in the identity.
 */
data class NameDate(val readings: List<LocalDate>, val form: DateForm, val at: Int) {
    val ambiguous: Boolean get() = readings.size > 1

    /** The places of its words in the identity. */
    val places: IntRange get() = at until at + form.words
}

/**
 * How a date was written: "2026.11.21", "20261121", "21.11.2026", or a trailing "21.11" with the year elsewhere; [words]
 * is how many of the name's words it takes.
 */
enum class DateForm(val words: Int) { YearFirst(3), Compact(1), DayFirst(3), DayMonth(2) }

/** A season span, "2026.2027" or "2026-27": two years, never a date. */
data class SeasonSpan(val first: Int, val second: Int)

/** A year with a running number ("2026x07", [IndexForm.Cross]) or an episode ("S2026E34", [IndexForm.Episode]). */
data class YearIndex(val year: Int, val index: Int, val form: IndexForm)

enum class IndexForm { Cross, Episode }

/** A number after its word: "Round 20", "Game 1". */
data class Numbered(val word: NumberWord, val number: Int)

/** A name's two sides, the words [before] and [after] its [separator] ("vs", "v", "@" or "at"). */
data class Sides(val before: List<Token>, val separator: String, val after: List<Token>)

/**
 * A release name as the plugin reads it (L646): for its identity alone, leaving quality, source and repacks to the host's
 * ranking. The identity is the name up to its tail, the first quality word; the group, the tail's last hyphenated word, is
 * set aside. What the identity holds is read in this order, each word read once: the event's own numbers are masked; the
 * dates (compact, year first, day first) and what is never one (season spans, a year with a running number or an
 * episode); the years; a trailing day and month, with a year elsewhere; the word lists, content words before parts and
 * sessions (so "Pre-Race" is no race); the numbers after their words; a card's number; then the siblings, the result
 * words, the sides and a score.
 *
 * Reading is linear in the name's length, with no regular expression, and never throws: a name it somehow can't read
 * comes back with [readable] false and nothing read, which the shared rules leave out.
 */
class SportsName internal constructor(
    /** The name as given. */
    val name: String,
    /** The identity's words: the name up to its tail. */
    val identity: List<Token>,
    /** The quality words from the first one on, the group aside. */
    val tail: List<Token>,
    /** The release group, written as given, or null. */
    val group: String?,
    val dates: List<NameDate>,
    /** Years written as words of their own, outside a date. */
    val years: List<Int>,
    val seasons: List<SeasonSpan>,
    val indexes: List<YearIndex>,
    /** The numbers read after their words, in the name's order. */
    val numbered: List<Numbered>,
    /** The numbers after an organisation word of the event's ("UFC 999"). */
    val cardNumbers: List<Int>,
    val sides: Sides?,
    val nonEvent: List<NonEvent>,
    /** Partial words: "1st half", "half 2", "quarter 3". */
    val partial: List<String>,
    /** Pack words, and "week" or "matchday" for one with no sides. */
    val packs: List<String>,
    val parts: List<PartWord>,
    /** Session words naming a session; "Grand Prix" and "GP" name the weekend, in [grandPrix]. */
    val sessions: List<SessionWord>,
    val grandPrix: Boolean,
    /** Result words no word of the event's own makes one. */
    val results: List<String>,
    /** Sibling markers: "women", "youth", "reserves", "u21". */
    val siblings: Set<String>,
    /** Whether the identity holds a score (L646). */
    val score: Boolean,
    /** Whether the tail says 480p, 576p or SD, for the size floor's lower rate (L647). */
    val standardDefinition: Boolean,
    /** False only for a name the parser failed on; then nothing else is read. */
    val readable: Boolean,
) {
    /** The identity's whole numbers, as written ("999" of "UFC.999"; nothing of "UFC999"). */
    val numbers: List<Int> = identity.filter { it.isNumber && it.word.length <= 9 }.map { it.word.toInt() }

    /** Whether [number] stands in the identity as a whole token: 999 is not 9990. */
    fun hasNumber(number: Int): Boolean = number in numbers

    /** The numbers [word] leads in the name. */
    fun numbered(word: NumberWord): List<Int> = numbered.filter { it.word == word }.map { it.number }

    /** Whether [phrase]'s words, folded as a name's are, stand together in the identity. */
    fun has(phrase: String): Boolean {
        val words = Tokens.of(phrase).map { it.word }
        if (words.isEmpty()) return false
        return (0..identity.size - words.size).any { at -> words.indices.all { identity[at + it].word == words[it] } }
    }

    companion object {
        /** [name] read against [context], the event's own words. */
        fun read(name: String, context: NameContext = NameContext.NONE): SportsName = try {
            Reader(name, context).read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            unreadable(name)
        }

        private fun unreadable(name: String) = SportsName(
            name = name, identity = emptyList(), tail = emptyList(), group = null, dates = emptyList(), years = emptyList(), seasons = emptyList(),
            indexes = emptyList(), numbered = emptyList(), cardNumbers = emptyList(), sides = null, nonEvent = emptyList(), partial = emptyList(),
            packs = emptyList(), parts = emptyList(), sessions = emptyList(), grandPrix = false, results = emptyList(), siblings = emptySet(),
            score = false, standardDefinition = false, readable = false,
        )
    }
}

/** One name's reading: the tokens, which of them each reading took, and which are the event's own numbers. */
private class Reader(private val name: String, private val context: NameContext) {
    private val all = Tokens.of(name)

    /** Where the tail begins: the first quality word, else the end. */
    private val tailStart = all.indices.firstOrNull { Words.isTailWord(all[it].word, all.getOrNull(it + 1)?.word) } ?: all.size

    /** The group: the last word, hyphenated onto the tail. */
    private val groupAt = all.lastIndex.takeIf { it > tailStart && all[it].sep == "-" }

    /** The end of the words the lists read: everything but the group. */
    private val end = groupAt ?: all.size

    /** Words a reading took, so no other reads them again. */
    private val used = BooleanArray(all.size)

    /** Numbers of the event's own words, where they stand in the name. */
    private val masked = BooleanArray(all.size)

    private val siblingAt = BooleanArray(all.size)
    private val resultAt = BooleanArray(all.size)

    /** The event's own phrases, folded, and every word of them, which is never a result word. */
    private val phrases: List<List<String>> =
        (listOfNotNull(context.title, context.league) + context.sides + context.series).map { words(it) }.filter { it.isNotEmpty() }
    private val ownWords: Set<String> = (phrases.flatten() + context.organisations.flatMap(::words)).toSet()

    private val dates = mutableListOf<NameDate>()
    private val years = mutableListOf<Int>()
    private val seasons = mutableListOf<SeasonSpan>()
    private val indexes = mutableListOf<YearIndex>()

    /** The numbers after their words, by where they stand, so the list keeps the name's order. */
    private val numbered = sortedMapOf<Int, Numbered>()

    fun read(): SportsName {
        maskOwnNumbers()
        readGlued()
        readDates()
        readYears()
        readDayMonth()
        val nonEvent = find(if (context.show) Words.nonEventInShows else Words.nonEvent).map { it.first }
        val partial = find(Words.partial).map { (word, number) -> if (number != null) "$word $number" else word }
        val packs = find(Words.packs).map { it.first }
        val parts = find(if (context.show) Words.showParts else Words.parts).map { it.first }
        val sessionWords = find(Words.sessions)
        // "Race 2" is a session and a race's number at once.
        sessionWords.forEach { (entry, number, at) ->
            if (entry.kind == SessionKind.Race && number != null) numbered[at] = Numbered(NumberWord.Race, number)
        }
        readNumbered()
        val cardNumbers = readCardNumbers()
        val siblings = readSiblings()
        val results = (0 until end).filter { all[it].word in Words.results && all[it].word !in ownWords }.onEach { resultAt[it] = true }
            .map { all[it].word }
        val sides = readSides()
        val weekPack = numbered.values.firstOrNull { it.word == NumberWord.Week || it.word == NumberWord.Matchday }
            ?.takeIf { sides == null }?.let { it.word.name.lowercase() }
        return SportsName(
            name = name,
            identity = all.subList(0, tailStart),
            tail = all.subList(tailStart, end),
            group = groupAt?.let { all[it].written },
            dates = dates,
            years = years,
            seasons = seasons,
            indexes = indexes,
            numbered = numbered.values.toList(),
            cardNumbers = cardNumbers,
            sides = sides,
            nonEvent = nonEvent,
            partial = partial,
            packs = packs + listOfNotNull(weekPack),
            parts = parts,
            sessions = sessionWords.mapNotNull { (entry, number) -> entry.kind?.let { SessionWord(it, entry.number ?: number, entry.foreign) } },
            grandPrix = sessionWords.any { it.first.kind == null },
            results = results,
            siblings = siblings,
            score = readScore(),
            standardDefinition = (tailStart until end).any { Words.isStandardDefinition(all[it].word) },
            readable = true,
        )
    }

    /**
     * Masks each number of the event's own phrases where it stands in the identity beside the word it has beside it there
     * ("500" after "Kestrel" for "Kestrel 500"), or anywhere when the phrase is that number alone.
     */
    private fun maskOwnNumbers() {
        phrases.forEach { phrase ->
            phrase.forEachIndexed { k, word ->
                if (!word.all { it in '0'..'9' }) return@forEachIndexed
                for (j in 0 until tailStart) {
                    if (all[j].word != word) continue
                    val before = k > 0 && j > 0 && all[j - 1].word == phrase[k - 1]
                    val after = k < phrase.lastIndex && j + 1 < tailStart && all[j + 1].word == phrase[k + 1]
                    if (phrase.size == 1 || before || after) masked[j] = true
                }
            }
        }
    }

    /** One-token forms: a compact date "20261121", a year and a running number "2026x07", a year's episode "S2026E34". */
    private fun readGlued() {
        for (i in 0 until tailStart) {
            if (masked[i]) continue
            val word = all[i].word
            when {
                word.length == 8 && all[i].isNumber ->
                    date(word.substring(0, 4).toInt(), word.substring(4, 6).toInt(), word.substring(6, 8).toInt())?.let {
                        dates += NameDate(listOf(it), DateForm.Compact, i)
                        used[i] = true
                    }
                else -> yearIndex(word)?.let {
                    indexes += it
                    used[i] = true
                }
            }
        }
    }

    /** "2026x07" or "S2026E34". */
    private fun yearIndex(word: String): YearIndex? {
        val cross = word.length in 6..8 && word[4] == 'x' && digits(word, 0, 4) && digits(word, 5, word.length)
        if (cross) return YearIndex(word.substring(0, 4).toInt(), word.substring(5).toInt(), IndexForm.Cross).takeIf { it.year in YEARS }
        val episode = word.length in 7..10 && word[0] == 's' && word[5] == 'e' && digits(word, 1, 5) && digits(word, 6, word.length)
        if (episode) return YearIndex(word.substring(1, 5).toInt(), word.substring(6).toInt(), IndexForm.Episode).takeIf { it.year in YEARS }
        return null
    }

    /** Year-first dates, season spans and day-first dates, read left to right, three or two free numbers each. */
    private fun readDates() {
        var i = 0
        while (i < tailStart) {
            i += yearFirst(i) ?: span(i) ?: dayFirst(i) ?: 1
        }
    }

    /** "2026.11.21" with any separator: the words it took, or null. */
    private fun yearFirst(i: Int): Int? {
        val year = free(i, 4, 4) ?: return null
        val month = free(i + 1, 1, 2) ?: return null
        val day = free(i + 2, 1, 2) ?: return null
        val date = date(year, month, day) ?: return null
        dates += NameDate(listOf(date), DateForm.YearFirst, i)
        take(i, 3)
        return 3
    }

    /** "2026.2027" or "2026-27": a season, never a date. */
    private fun span(i: Int): Int? {
        val first = free(i, 4, 4)?.takeIf { it in YEARS } ?: return null
        val written = all.getOrNull(i + 1)?.word ?: return null
        val second = free(i + 1, 2, 4) ?: return null
        val next = when (written.length) {
            4 -> first + 1
            2 -> (first + 1) % 100
            else -> return null
        }
        if (second != next) return null
        seasons += SeasonSpan(first, first + 1)
        take(i, 2)
        return 2
    }

    /** "21.11.2026": a date when its day is above 12, else both readings, the day's first, that are dates. */
    private fun dayFirst(i: Int): Int? {
        val first = free(i, 1, 2) ?: return null
        val second = free(i + 1, 1, 2) ?: return null
        val year = free(i + 2, 4, 4) ?: return null
        val readings = readings(first, second, year).ifEmpty { return null }
        dates += NameDate(readings, DateForm.DayFirst, i)
        take(i, 3)
        return 3
    }

    /** A day and a month as a day-first date reads them: the day's reading, and the month's too while the day is 12 or less. */
    private fun readings(first: Int, second: Int, year: Int): List<LocalDate> =
        listOfNotNull(date(year, second, first), if (first <= 12) date(year, first, second) else null).distinct()

    /** Every free four-digit year left: a year written as a word of its own. */
    private fun readYears() {
        for (i in 0 until tailStart) {
            val year = free(i, 4, 4)?.takeIf { it in YEARS } ?: continue
            years += year
            used[i] = true
        }
    }

    /**
     * A trailing day and month ("… Gulls 22 11"), read as a date only with one year elsewhere in the name. Before the words
     * a number follows, so "Race 21 11" keeps its date.
     */
    private fun readDayMonth() {
        val year = years.distinct().singleOrNull() ?: return
        val first = free(tailStart - 2, 1, 2) ?: return
        val second = free(tailStart - 1, 1, 2) ?: return
        val readings = readings(first, second, year).ifEmpty { return }
        dates += NameDate(readings, DateForm.DayMonth, tailStart - 2)
        take(tailStart - 2, 2)
    }

    /**
     * Each entry of [list] found among the words not yet read, the longest first at each word, with the number it takes:
     * as (value, number, where it starts).
     */
    private fun <T> find(list: PhraseList<T>): List<Triple<T, Int?, Int>> {
        val found = mutableListOf<Triple<T, Int?, Int>>()
        var i = 0
        while (i < end) {
            if (used[i]) {
                i++
                continue
            }
            val match = list.startingWith(all[i].word).firstNotNullOfOrNull { phrase -> fit(phrase, i)?.let { phrase to it.number } }
            if (match != null) {
                val (phrase, number) = match
                val size = phrase.words.size + if (number != null) 1 else 0
                take(i, size)
                found += Triple(phrase.value, number, i)
                i += size
            } else {
                // A word glued to its number: "Race2", "Practice2", "Half2".
                val glued = gluedPhrase(list, all[i].word)
                if (glued != null) {
                    used[i] = true
                    found += Triple(glued.first.value, glued.second, i)
                }
                i++
            }
        }
        return found
    }

    /** [phrase] standing at [i] among the free words, with the number it takes there if any; null where it doesn't stand. */
    private fun fit(phrase: Phrase<*>, i: Int): Fit? {
        val size = phrase.words.size
        if (i + size > end) return null
        for (k in 0 until size) if (used[i + k] || all[i + k].word != phrase.words[k]) return null
        val number = if (phrase.numbering == Numbering.None) null else number(i + size)
        if (phrase.numbering == Numbering.Required && number == null) return null
        return Fit(number)
    }

    private class Fit(val number: Int?)

    private fun <T> gluedPhrase(list: PhraseList<T>, word: String): Pair<Phrase<T>, Int>? {
        val split = word.indexOfFirst { it in '0'..'9' }
        if (split < 1 || word.length - split > 2 || !digits(word, split, word.length)) return null
        val phrase = list.startingWith(word.substring(0, split)).firstOrNull { it.words.size == 1 && it.numbering != Numbering.None } ?: return null
        return phrase to word.substring(split).toInt()
    }

    /** The numbers after their words, in the identity: "Round 20", "Round20", "R20", "Week 10", "Night 2". */
    private fun readNumbered() {
        for (i in 0 until tailStart) {
            if (used[i]) continue
            val word = all[i].word
            val kind = Words.numberWords[word]
            if (kind != null) {
                val number = number(i + 1, 3) ?: continue
                numbered[i] = Numbered(kind, number)
                take(i, 2)
                continue
            }
            val split = word.indexOfFirst { it in '0'..'9' }
            if (split < 1 || word.length - split > 3 || !digits(word, split, word.length)) continue
            val glued = Words.numberWords[word.substring(0, split)] ?: continue
            numbered[i] = Numbered(glued, word.substring(split).toInt())
            used[i] = true
        }
    }

    /** A card's number after an organisation word of the event's, apart or glued ("UFC 999", "UFC999"). */
    private fun readCardNumbers(): List<Int> {
        val found = mutableListOf<Int>()
        context.organisations.map(::words).filter { it.isNotEmpty() }.forEach { organisation ->
            // An organisation of several words: those before its last stand right before it.
            val lead = organisation.dropLast(1)
            val last = organisation.last()
            for (i in lead.size until tailStart) {
                if (lead.indices.any { all[i - lead.size + it].word != lead[it] }) continue
                val word = all[i].word
                val number = when {
                    word == last -> number(i + 1, 4)?.also { used[i + 1] = true }
                    !used[i] && word.startsWith(last) && word.length - last.length <= 4 && digits(word, last.length, word.length) ->
                        word.substring(last.length).toInt().also { used[i] = true }
                    else -> null
                }
                if (number != null) found += number
            }
        }
        return found
    }

    /**
     * The sibling markers in the identity, the longest first at each word ("Reserve Team", "Under 21"), and a "U" with its
     * age group apart ("U-21"). A marker's number is its own, never a score's.
     */
    private fun readSiblings(): Set<String> {
        val found = sortedSetOf<String>()
        var i = 0
        while (i < tailStart) {
            val word = all[i].word
            val marker = Words.siblings.startingWith(word).firstOrNull { standsAt(it, i) }
            if (marker != null) {
                found += marker.value
                for (j in i until i + marker.words.size) {
                    siblingAt[j] = true
                    if (all[j].isNumber) used[j] = true
                }
                i += marker.words.size
                continue
            }
            if (word == "u" && !used[i]) {
                number(i + 1)?.takeIf { it in Words.ageGroups }?.let { age ->
                    found += "u$age"
                    siblingAt[i] = true
                    used[i + 1] = true
                }
            }
            i++
        }
        return found
    }

    /** Whether [phrase]'s words stand at [i] in the identity, none of them taken by another reading. */
    private fun standsAt(phrase: Phrase<*>, i: Int): Boolean =
        i + phrase.words.size <= tailStart && phrase.words.indices.all { k -> !used[i + k] && all[i + k].word == phrase.words[k] }

    /**
     * The sides around the identity's separator, the first of "vs", "v", "@" and "at" it holds in that order ("at" is a
     * common word, so it splits a name only with no other): the words next to it on each side, up to a word a reading took
     * (a date, a number's word, a list's word), a result word or another separator; a sibling marker is passed over.
     */
    private fun readSides(): Sides? {
        val at = Words.sideSeparators.firstNotNullOfOrNull { separator ->
            (0 until tailStart).firstOrNull { !used[it] && all[it].word == separator }
        } ?: return null
        val before = side((at - 1 downTo 0)).asReversed()
        val after = side(at + 1 until tailStart)
        if (before.isEmpty() || after.isEmpty()) return null
        return Sides(before, all[at].word, after)
    }

    private fun side(order: IntProgression): List<Token> {
        val words = mutableListOf<Token>()
        for (j in order) {
            if (siblingAt[j]) continue
            val token = all[j]
            val nameWord = token.hasLetter || (token.isNumber && token.word.length <= 3)
            if (used[j] || resultAt[j] || token.word in Words.sideSeparators || !nameWord) break
            words += token
        }
        return words
    }

    /**
     * A score (L646) in the identity: two numbers of one to three digits that no reading took and that aren't the event's
     * own, joined by a dot, space, underscore, hyphen, colon or dash ("3.1", "3 - 1", "1-2"), or glued with an x ("3x1"),
     * with a word beside them. Channels right before the tail ("5.1", "7.1", "2.0") are audio, not a score.
     */
    private fun readScore(): Boolean {
        for (i in 0 until tailStart) {
            if (!used[i] && !masked[i] && crossed(all[i].word) && (wordBeside(i - 1) || wordBeside(i + 1))) return true
            if (!scoreNumber(i) || !scoreNumber(i + 1) || !joins(all[i + 1].sep)) continue
            val channels = all[i + 1].sep == "." && "${all[i].word}.${all[i + 1].word}" in CHANNELS && i + 2 == tailStart && tailStart < all.size
            if (!channels && (wordBeside(i - 1) || wordBeside(i + 2))) return true
        }
        return false
    }

    private fun scoreNumber(i: Int): Boolean = i < tailStart && !used[i] && !masked[i] && all[i].isNumber && all[i].word.length <= 3

    /** A word beside a score: any word with a letter, or a side's or the title's own number ("Kestrel Club 07"). */
    private fun wordBeside(i: Int): Boolean = i in 0 until tailStart && (all[i].hasLetter || masked[i])

    /** "3x1": two numbers of one to three digits glued with an x. */
    private fun crossed(word: String): Boolean {
        val x = word.indexOf('x')
        return x in 1..3 && word.length - x - 1 in 1..3 && digits(word, 0, x) && digits(word, x + 1, word.length)
    }

    /** A score's join: a dot, an underscore or spaces, or a hyphen, colon or dash with or without spaces around it. */
    private fun joins(sep: String): Boolean =
        sep in PLAIN_JOINS || (sep.isNotEmpty() && sep.isBlank()) || sep.trim().let { it.length == 1 && it in DASHES }

    /** The number at [i] when no reading took it and it has at most [max] digits; the event's own numbers count here. */
    private fun number(i: Int, max: Int = 2): Int? {
        if (i !in 0 until tailStart || used[i] || !all[i].isNumber || all[i].word.length > max) return null
        return all[i].word.toInt()
    }

    /** The number at [i] with [min] to [max] digits that no reading took and that isn't the event's own. */
    private fun free(i: Int, min: Int, max: Int): Int? {
        if (i !in 0 until tailStart || used[i] || masked[i] || !all[i].isNumber || all[i].word.length !in min..max) return null
        return all[i].word.toInt()
    }

    private fun take(from: Int, count: Int) {
        for (j in from until from + count) used[j] = true
    }

    private fun words(text: String): List<String> = Tokens.of(text).map { it.word }

    private fun digits(word: String, from: Int, to: Int): Boolean = from < to && (from until to).all { word[it] in '0'..'9' }

    /** A date, or null where the numbers make none. */
    private fun date(year: Int, month: Int, day: Int): LocalDate? {
        if (year !in YEARS || month !in 1..12 || day < 1 || day > YearMonth.of(year, month).lengthOfMonth()) return null
        return LocalDate.of(year, month, day)
    }

    private companion object {
        /** The years a name's four digits may be. */
        val YEARS = 1900..2099
        val CHANNELS = setOf("5.1", "7.1", "2.0")
        val PLAIN_JOINS = setOf(".", "_")
        /** A hyphen, a colon, an en dash and an em dash. */
        const val DASHES = "-:\u2013\u2014"
    }
}
