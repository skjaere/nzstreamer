package app.nzstreamer.plugin.sportarr.bouts

import java.text.Normalizer
import java.util.Locale

/** One piece of a page as [HtmlReader] reads it. */
internal sealed interface HtmlToken {
    /**
     * A start tag: its lower-case [name], its [attributes] by lower-case name with their values decoded (a bare one's is
     * empty), and whether it closes itself ("<br/>").
     */
    class Start(val name: String, val attributes: Map<String, String>, val selfClosing: Boolean) : HtmlToken {
        /** Whether its class attribute holds [word] among its words. */
        fun hasClass(word: String): Boolean = attributes["class"]?.split(SPACES)?.contains(word) == true
    }

    /** An end tag, by its lower-case [name]. */
    class End(val name: String) : HtmlToken

    /** Text between tags, its entities decoded and its whitespace as written. */
    class Text(val text: String) : HtmlToken
}

/** A cell's first link outside its marks: its [href] and [classes] as written, and its [text]. */
internal class HtmlLink(val href: String, val classes: String, val text: String)

/**
 * A table cell: a `<th>` ([header]) or a `<td>`, the [columns] and [rows] it spans, its [text] with its marks taken off
 * and its whitespace collapsed (a nested table's text among it), and its first [link].
 */
internal class HtmlCell(val header: Boolean, val columns: Int, val rows: Int, val text: String, val link: HtmlLink?)

/** A table's row: its own cells, never a nested table's. */
internal class HtmlRow(val cells: List<HtmlCell>)

/**
 * A small tokenizer over a page (L-B2), as the plugin bundles no library to parse HTML: tags with their attributes, text
 * with its entities decoded; comments, declarations, scripts and styles skipped. It walks the page with a loop and reads
 * a table's nesting by depth, never by recursion, so no page, however it nests or wherever it was cut, makes it throw.
 * Nothing it throws carries the page's text.
 */
internal class HtmlReader(private val page: String) {
    private var at = 0

    /** The next token, or null at the page's end. */
    fun next(): HtmlToken? {
        while (at < page.length) {
            val open = page.indexOf('<', at)
            if (open != at) {
                val end = if (open < 0) page.length else open
                val text = page.substring(at, end)
                at = end
                return HtmlToken.Text(Html.decode(text))
            }
            val following = page.getOrNull(at + 1)
            when {
                page.startsWith("<!--", at) -> at = page.indexOf("-->", at + 4).let { if (it < 0) page.length else it + 3 }
                following == '!' || following == '?' -> at = page.indexOf('>', at).let { if (it < 0) page.length else it + 1 }
                following == '/' && page.getOrNull(at + 2)?.isAsciiLetter() == true -> return endTag()
                following?.isAsciiLetter() == true -> return startTag() ?: continue
                else -> {
                    at++
                    return HtmlToken.Text("<")
                }
            }
        }
        return null
    }

    private fun endTag(): HtmlToken? {
        val name = name(at + 2)
        val close = page.indexOf('>', at)
        at = if (close < 0) page.length else close + 1
        return if (close < 0) null else HtmlToken.End(name)
    }

    /** The start tag at [at], or null where it is a script's or a style's, skipped whole, or the page ends inside it. */
    private fun startTag(): HtmlToken.Start? {
        val name = name(at + 1)
        var i = at + 1 + name.length
        val attributes = LinkedHashMap<String, String>()
        var selfClosing = false
        while (true) {
            while (i < page.length && page[i].isWhitespace()) i++
            if (i >= page.length) {
                at = page.length
                return null
            }
            val c = page[i]
            if (c == '>') {
                i++
                break
            }
            if (c == '/') {
                i++
                if (page.getOrNull(i) == '>') {
                    selfClosing = true
                    i++
                    break
                }
                continue
            }
            val nameStart = i
            while (i < page.length && !page[i].isWhitespace() && page[i] != '=' && page[i] != '>' && page[i] != '/') i++
            if (i == nameStart) {
                // A stray '=' where a name would be.
                i++
                continue
            }
            val attribute = page.substring(nameStart, i).lowercase(Locale.ROOT)
            while (i < page.length && page[i].isWhitespace()) i++
            var value = ""
            if (page.getOrNull(i) == '=') {
                i++
                while (i < page.length && page[i].isWhitespace()) i++
                val quote = page.getOrNull(i)
                if (quote == '"' || quote == '\'') {
                    val close = page.indexOf(quote, i + 1)
                    if (close < 0) {
                        at = page.length
                        return null
                    }
                    value = page.substring(i + 1, close)
                    i = close + 1
                } else {
                    val valueStart = i
                    while (i < page.length && !page[i].isWhitespace() && page[i] != '>') i++
                    value = page.substring(valueStart, i)
                }
            }
            if (attribute.isNotEmpty() && attribute !in attributes) attributes[attribute] = Html.decode(value)
        }
        at = i
        if ((name == "script" || name == "style") && !selfClosing) {
            val close = page.indexOf("</$name", at, ignoreCase = true)
            at = if (close < 0) page.length else page.indexOf('>', close).let { if (it < 0) page.length else it + 1 }
            return null
        }
        return HtmlToken.Start(name, attributes, selfClosing)
    }

    /** A tag's name from [from]: its letters, digits and dashes, lower-cased. */
    private fun name(from: Int): String {
        var i = from
        while (i < page.length && (page[i].isAsciiLetter() || page[i] in '0'..'9' || page[i] == '-')) i++
        return page.substring(from, i).lowercase(Locale.ROOT)
    }

    /**
     * The table whose start tag was just read, to its end: its rows, each with its own cells, the reader left after it.
     * A nested table's text is its cell's; its rows aren't this table's. A cell, row or table whose end tag is missing ends
     * where the next one starts, or at the page's end. Inside a cell, `<sup>` (a reference or a note) is left out, and so
     * is every bracketed mark ("[4]", "[a]") of its text.
     */
    fun table(): List<HtmlRow> {
        val rows = ArrayList<HtmlRow>()
        var cells: ArrayList<HtmlCell>? = null
        var cell: CellBuilder? = null
        var depth = 1
        var marks = 0

        fun endCell() {
            marks = 0
            val built = cell ?: return
            val row = cells ?: ArrayList<HtmlCell>().also { cells = it }
            row.add(built.build())
            cell = null
        }

        fun endRow() {
            endCell()
            cells?.let { rows += HtmlRow(it) }
            cells = null
        }

        while (true) {
            val token = next() ?: break
            when (token) {
                is HtmlToken.Text -> if (marks == 0) cell?.text(token.text)
                is HtmlToken.Start -> when {
                    token.name == "table" && !token.selfClosing -> {
                        depth++
                        cell?.space()
                    }
                    token.name == "sup" && !token.selfClosing -> marks++
                    depth > 1 -> cell?.inner(token, marks)
                    token.name == "tr" -> {
                        endRow()
                        cells = ArrayList()
                    }
                    token.name == "td" || token.name == "th" -> {
                        endCell()
                        if (cells == null) cells = ArrayList()
                        cell = CellBuilder(token.name == "th", span(token.attributes["colspan"]), span(token.attributes["rowspan"]))
                    }
                    else -> cell?.inner(token, marks)
                }
                is HtmlToken.End -> when {
                    token.name == "table" -> {
                        depth--
                        if (depth == 0) break
                        cell?.space()
                    }
                    token.name == "sup" -> if (marks > 0) marks--
                    depth > 1 -> cell?.innerEnd(token)
                    token.name == "tr" -> endRow()
                    token.name == "td" || token.name == "th" -> endCell()
                    else -> cell?.innerEnd(token)
                }
            }
        }
        endRow()
        return rows
    }

    /** A colspan or rowspan: its number, one where it is missing or unreadable, at most a thousand. */
    private fun span(value: String?): Int = value?.trim()?.toIntOrNull()?.coerceIn(1, MAX_SPAN) ?: 1

    /** A cell as its tokens come: its text, a space where a tag breaks a line, and its first link outside its marks. */
    private class CellBuilder(val header: Boolean, val columns: Int, val rows: Int) {
        private val text = StringBuilder()
        private var link: LinkBuilder? = null
        private var linkOpen = false

        fun text(words: String) {
            text.append(words)
            if (linkOpen) link?.text?.append(words)
        }

        fun space() = text(" ")

        /** A tag inside the cell; [marks] says how many `<sup>` it is inside. */
        fun inner(start: HtmlToken.Start, marks: Int) {
            if (start.name in BREAKS) space()
            if (start.name == "a" && marks == 0 && link == null && !start.selfClosing) {
                link = LinkBuilder(start.attributes["href"].orEmpty(), start.attributes["class"].orEmpty())
                linkOpen = true
            }
        }

        fun innerEnd(end: HtmlToken.End) {
            if (end.name in BREAKS) space()
            if (end.name == "a") linkOpen = false
        }

        fun build(): HtmlCell {
            val built = link?.let { HtmlLink(it.href, it.classes, Html.clean(it.text.toString())) }
            return HtmlCell(header, columns, rows, Html.clean(text.toString()), built)
        }

        private class LinkBuilder(val href: String, val classes: String) {
            val text = StringBuilder()
        }
    }

    private companion object {
        const val MAX_SPAN = 1000

        /** Tags that break a cell's text into lines. */
        val BREAKS = setOf("br", "p", "div", "li", "tr", "td", "th", "dd", "dt")
    }
}

/** The text helpers the reader and the rules share. */
internal object Html {
    /**
     * [text] with its entities decoded: by name amp, lt, gt, quot, apos, nbsp, ndash and mdash; by decimal or hex number.
     * An entity it doesn't know, or one that names no character, stays as written.
     */
    fun decode(text: String): String {
        if ('&' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val semicolon = if (c == '&') text.indexOf(';', i + 1).takeIf { it > i + 1 && it - i <= MAX_ENTITY } else null
            val decoded = semicolon?.let { entity(text.substring(i + 1, it)) }
            if (decoded == null) {
                out.append(c)
                i++
            } else {
                out.append(decoded)
                i = semicolon + 1
            }
        }
        return out.toString()
    }

    private fun entity(name: String): String? {
        val number = when {
            name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).takeIf { it.isNotEmpty() }?.toIntOrNull(16)
            name.startsWith("#") -> name.substring(1).takeIf { digits -> digits.isNotEmpty() && digits.all { it in '0'..'9' } }?.toIntOrNull()
            else -> return NAMED[name]
        } ?: return null
        val character = number > 0 && Character.isValidCodePoint(number) && number !in Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code
        return if (character) String(Character.toChars(number)) else null
    }

    /** [text]'s runs of whitespace (a no-break space among it) as one space, trimmed. */
    fun collapse(text: String): String {
        val out = StringBuilder(text.length)
        var space = false
        text.forEach { c ->
            if (c.isWhitespace()) {
                space = out.isNotEmpty()
            } else {
                if (space) out.append(' ')
                space = false
                out.append(c)
            }
        }
        return out.toString()
    }

    /** A cell's or link's text: its bracketed marks taken off, its whitespace collapsed. */
    fun clean(text: String): String = collapse(MARKS.replace(text, " "))

    /** [text] NFD-normalised with its combining marks taken off, upper-cased in Locale.ROOT: "Quéssel" is "QUESSEL". */
    fun fold(text: String): String = COMBINING.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").uppercase(Locale.ROOT)

    /** [text]'s words, folded: its runs of letters and digits. */
    fun words(text: String): List<String> = fold(text).split(NOT_A_WORD).filter { it.isNotEmpty() }

    private const val MAX_ENTITY = 12

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–", "mdash" to "—",
    )

    /** A bracketed mark: "[4]", "[a]", "[note 2]". */
    private val MARKS = Regex("""\[[^\[\]]*]""")
    private val COMBINING = Regex("""\p{Mn}+""")
    private val NOT_A_WORD = Regex("""[^\p{L}\p{N}]+""")
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/** Whitespace as a token's text reads it: words' whitespace and the no-break space alike. */
private val SPACES = Regex("""\s+""")
