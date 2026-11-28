package app.nzstreamer.plugin.sportarr.bouts

import app.nzstreamer.plugin.sportarr.model.Parts
import java.util.Locale

/**
 * An event's fight card from its Wikipedia article (L-B2, L-B3), with every result left out as it is read (W6, L-B4).
 *
 * It reads one thing of the page: the first heading (an `<h2>`, or the legacy parser's mw-headline span) whose id starts
 * "Fight_card", "Results" or "Card", and the first table after it before the next `<h2>` that has a part's header or a
 * bout's row, so a section's notice box (a table of its own) above the card is passed over. Nothing else: not the lead,
 * Background, Announced bouts or Bonus awards, no other table and no link. In that table a part's header is a `<th>` across
 * six columns or more; a bout's row has four `<td>` cells or more, the third "vs.", "vs" or "def.": its weight class, its
 * fighters (columns 2 and 4) and its notes (column 8), each cell at the column the cells before it end on. The method,
 * round and time (columns 5 to 7) are never put together; the separator is read only to know a bout's row, and the
 * notes only for the title flag. Each row is read as it ends and let go, so none of them outlives its row (L-B4).
 */
internal object BoutTable {
    /** What the page's card says. */
    sealed interface Read {
        /** The card, split into its parts. */
        data class Out(val card: BoutCard) : Read

        /** A card not split into parts yet, or one with no main card or nothing else (W4: not out yet). */
        data object NotOut : Read

        /** No card heading, no table after it, or no bout in it: the article weeks before the event, or changed HTML. */
        data object Missing : Read
    }

    /**
     * The card of [page], the article titled [article], for the event titled [title] (its main event's order may follow
     * it) whose parts' ids are [parts], in running order. A bout under a part the event doesn't have goes under the next
     * part in running order the event has (early prelims on a fight night under Prelims), whatever headers the table has;
     * a bout under a header that names no part goes with the next header up the table that does. Either way before that
     * part's own bouts; with no part after it, it is left out. The card is out only when the table names the main card and
     * another part.
     */
    fun read(page: String, article: String, title: String, parts: List<String>): Read {
        val groups = cardGroups(page, title) ?: return Read.Missing
        if (groups.all { it.bouts.isEmpty() }) return Read.Missing
        val named = groups.mapNotNull { it.id }.toSet()
        if (Parts.MAIN_CARD !in named || named.none { it != Parts.MAIN_CARD }) return Read.NotOut
        // Running order is the table upside down: parts from the bottom header up, bouts from the bottom row up.
        val placed = HashMap<String, MutableList<Bout>>()
        val waiting = ArrayList<Bout>()
        groups.asReversed().forEach { group ->
            waiting += group.bouts.asReversed()
            val id = group.id ?: return@forEach
            partFor(id, parts)?.let { part -> placed.getOrPut(part) { ArrayList() } += waiting }
            waiting.clear()
        }
        val card = BoutCard(article, parts.mapNotNull { id -> placed[id]?.takeIf { it.isNotEmpty() }?.let { BoutPart(id, it) } })
        return if (card.parts.isEmpty()) Read.Missing else Read.Out(card)
    }

    /**
     * The parts of the first table after the card's heading, before the next `<h2>`, that has a part's header or a bout
     * (any other, a notice box, passed over); null where none has.
     */
    private fun cardGroups(page: String, title: String): List<Group>? {
        val reader = HtmlReader(page)
        var headed = false
        while (true) {
            val token = reader.next() ?: return null
            if (token !is HtmlToken.Start) continue
            when {
                !headed -> headed = cardHeading(token)
                token.name == "h2" -> return null
                token.name == "table" && !token.selfClosing -> {
                    val groups = Groups(title)
                    reader.table(keep = { it in READ }, row = groups::add)
                    if (groups.read.isNotEmpty()) return groups.read
                }
            }
        }
    }

    private fun cardHeading(start: HtmlToken.Start): Boolean {
        val id = start.attributes["id"] ?: return false
        val heading = start.name == "h2" || (start.name == "span" && start.hasClass("mw-headline"))
        return heading && HEADINGS.any { id.startsWith(it) }
    }

    /** A header's part, by [id] where its words name one, and its bouts as the table lists them. */
    private class Group(val id: String?) {
        val bouts = ArrayList<Bout>()
    }

    /**
     * A table's parts in its order ([read]), as its rows come ([add]), each row let go once read: a part's header starts
     * one, a bout joins the last, and bouts before any header are a part of their own that names none. [title] is the
     * event's, which the main event's order may follow.
     */
    private class Groups(private val title: String) {
        val read = ArrayList<Group>()
        private var current: Group? = null
        private var mainSeen = false

        fun add(row: HtmlRow) {
            val header = row.cells.firstOrNull { it.header && it.columns >= HEADER_COLUMNS }
            if (header != null) {
                current = Group(partOf(header.text)).also { read += it }
                return
            }
            val group = current
            val main = !mainSeen && group?.id == Parts.MAIN_CARD
            val bout = bout(row, main, title) ?: return
            if (main) mainSeen = true
            (group ?: Group(null).also { read += it; current = it }).bouts += bout
        }
    }

    /**
     * The one of the event's [parts] that a header naming the part [id] puts its bouts under: [id] where the event has it,
     * else the next in running order it has; null where it has none after it.
     */
    private fun partFor(id: String, parts: List<String>): String? = RUNNING.dropWhile { it != id }.firstOrNull { it in parts }

    /**
     * A part's id from its header's words, any bracketed broadcaster left out and the rest folded to its letters and
     * digits; null for a header that names no part, "Fight card" and "Card" among them (a card not split).
     */
    private fun partOf(header: String): String? = PART_WORDS[unbracketed(header).lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)]

    /**
     * [text] with each "(" and what follows it to the next ")" left out; a "(" that no ")" follows stays, with the rest.
     * A walk along it, so a header of brackets that never close is read in a time in step with its length.
     */
    private fun unbracketed(text: String): String = buildString {
        var at = 0
        while (at < text.length) {
            val open = text.indexOf('(', at)
            val close = if (open < 0) -1 else text.indexOf(')', open + 1)
            if (close < 0) {
                append(text, at, text.length)
                break
            }
            append(text, at, open)
            at = close + 1
        }
    }

    /**
     * The bout of [row], or null where the row isn't one. [main] marks it the main event, ordered by the event's [title];
     * every other pair by the alphabet's rule (L-B4).
     */
    private fun bout(row: HtmlRow, main: Boolean, title: String): Bout? {
        val cells = row.cells.filter { !it.header }
        if (cells.size < 4) return null
        fun at(column: Int): String? {
            var end = 0
            return cells.firstOrNull { cell ->
                end += cell.columns
                column <= end
            }?.text
        }
        if (at(3)?.lowercase(Locale.ROOT) !in SEPARATORS) return null
        val left = at(2).orEmpty()
        val right = at(4).orEmpty()
        val a = Pairs.name(left)
        val b = Pairs.name(right)
        if (a.isEmpty() || b.isEmpty()) return null
        val titleFight = Pairs.champion(left) || Pairs.champion(right) || forTheTitle(at(8).orEmpty())
        val (first, second) = if (main) Pairs.mainEvent(a, b, title) else Pairs.ordered(a, b)
        return Bout(at(1).orEmpty(), first, second, titleFight, main)
    }

    /**
     * Whether a bout's [note] says it is for a title: it begins "For the" and names "Championship" or "title" in its first
     * sentence. A note beginning "For the" and a count ("For the first time", "For the second time in his career", "For
     * the 3rd time", "For the last time") never does: such notes are written after the event, so the two forms would read
     * apart.
     */
    private fun forTheTitle(note: String): Boolean {
        val words = note.lowercase(Locale.ROOT)
        if (!words.startsWith("for the ")) return false
        val next = words.removePrefix("for the ").takeWhile(Char::isLetterOrDigit)
        if (next in COUNTS || ORDINAL.matches(next)) return false
        val sentence = Html.words(words.split(SENTENCE_END, limit = 2).first())
        return "CHAMPIONSHIP" in sentence || "TITLE" in sentence
    }

    private const val HEADER_COLUMNS = 6

    /** The columns of a bout's row put together: the weight class, the fighters, the separator and the notes. */
    private val READ = setOf(1, 2, 3, 4, 8)

    /** A card's parts in running order, which every header's words map to one of. */
    private val RUNNING = listOf(Parts.EARLY_PRELIMS, Parts.PRELIMS, Parts.MAIN_CARD)

    private val HEADINGS = listOf("Fight_card", "Results", "Card")
    private val SEPARATORS = setOf("vs.", "vs", "def.")
    private val SENTENCE_END = Regex("""\.(\s|$)""")

    /** The words after "For the" that count times, which only a note written after the event begins with. */
    private val COUNTS = setOf(
        "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth", "last",
        "final",
    )
    private val ORDINAL = Regex("""\d+(st|nd|rd|th)""")

    /** L-B3's header words, folded, each with the part it names. */
    private val PART_WORDS = mapOf(
        "earlypreliminarycard" to Parts.EARLY_PRELIMS,
        "earlyprelims" to Parts.EARLY_PRELIMS,
        "preliminarycard" to Parts.PRELIMS,
        "prelims" to Parts.PRELIMS,
        "maincard" to Parts.MAIN_CARD,
    )
}
