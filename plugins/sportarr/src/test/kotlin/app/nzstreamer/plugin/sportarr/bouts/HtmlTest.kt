package app.nzstreamer.plugin.sportarr.bouts

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L-B2's tokenizer: tags with their attributes in either quote, text with its entities decoded, comments, scripts and
 * styles skipped, and tables read row by row with nested tables kept inside their cell; marks taken off a cell's text.
 * It never throws, whatever it is given: a page cut anywhere, stray characters, a table nested a thousand deep.
 */
class HtmlTest {
    private fun tokens(page: String): List<HtmlToken> {
        val reader = HtmlReader(page)
        return generateSequence { reader.next() }.toList()
    }

    private fun text(page: String): String = Html.collapse(tokens(page).filterIsInstance<HtmlToken.Text>().joinToString("") { it.text })

    /** The rows of [page]'s first table, each its cells' texts. */
    private fun firstTable(page: String): List<HtmlRow> {
        val reader = HtmlReader(page)
        while (true) {
            val token = reader.next() ?: return emptyList()
            if (token is HtmlToken.Start && token.name == "table") return reader.table()
        }
    }

    private fun texts(rows: List<HtmlRow>): List<List<String>> = rows.map { row -> row.cells.map { it.text } }

    @Test
    fun `entities are decoded, by name and by number, and the unknown or broken ones left as written`() {
        assertEquals("Quessel & Halde <b> \"c\" 'ic' a b – — é é é", Html.decode("Quessel &amp; Halde &lt;b&gt; &quot;c&quot; &apos;ic&apos; a&nbsp;b &ndash; &mdash; &#233; &#xE9; &#XE9;"))
        assertEquals("&eacute; &#xZZ; & &; &#; &#x; &#99999999999; &#xD800; &#0; &amp", Html.decode("&eacute; &#xZZ; & &; &#; &#x; &#99999999999; &#xD800; &#0; &amp"))
        assertEquals("Women's Flyweight", text("<p>Women&#39;s&#32;Flyweight</p>"))
    }

    @Test
    fun `a run of ampersands with no semicolon near them decodes in time in step with its length`() {
        // An entity's ';' is a few characters on or none: a page of ampersands is decoded without a look past that for each.
        val ampersands = "&".repeat(4_000_000)
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            assertEquals(ampersands, Html.decode(ampersands))
            assertEquals("$ampersands&", Html.decode("$ampersands&amp;"))
        }
    }

    @Test
    fun `whitespace collapses, a no-break space among it, and folding takes accents off`() {
        assertEquals("Ambrel Savrin", Html.collapse("  Ambrel \n\t  Savrin "))
        assertEquals("QUESSEL-HALDE JR.", Html.fold("Quéssel-Halde Jr."))
        assertEquals(listOf("QUESSEL", "HALDE", "JR"), Html.words("Quéssel-Halde Jr."))
        assertEquals(listOf("UFC", "999", "QUESSEL", "VS", "HALDE"), Html.words("UFC 999: Quessel vs. Halde"))
    }

    @Test
    fun `tags have lower-case names and attributes in either quote, unquoted or bare, their values decoded`() {
        val start = tokens("""<A HREF='/wiki/UFC_999' Class="mw-redirect new" title=UFC&amp;999 data-x>UFC 999</a>""").first() as HtmlToken.Start
        assertEquals("a", start.name)
        assertEquals(mapOf("href" to "/wiki/UFC_999", "class" to "mw-redirect new", "title" to "UFC&999", "data-x" to ""), start.attributes)
        assertTrue(start.hasClass("new") && start.hasClass("mw-redirect") && !start.hasClass("mw"))
        val br = tokens("<br/><img src=\"a.png\" />").map { it as HtmlToken.Start }
        assertEquals(listOf("br" to true, "img" to true), br.map { it.name to it.selfClosing })
        assertEquals("a > b", (tokens("""<span title="a > b">x</span>""").first() as HtmlToken.Start).attributes["title"])
        assertEquals("td", (tokens("</TD >").single() as HtmlToken.End).name)
    }

    @Test
    fun `comments, declarations, scripts and styles are skipped, whatever they hold`() {
        val page = """<!DOCTYPE html><?xml version="1.0"?><p>Quessel<!-- <td>Halde</td> --> vs.<script type="text/javascript">var a = "<td>Brisk</td>";</SCRIPT> Loam<style>td::before { content: "<b>"; }</style></p>"""
        assertEquals("Quessel vs. Loam", text(page))
        assertEquals(listOf("p", "p"), tokens(page).mapNotNull { (it as? HtmlToken.Start)?.name ?: (it as? HtmlToken.End)?.name })
    }

    @Test
    fun `a stray lt or ampersand is text`() {
        assertEquals("a < b & c <3 d", text("a < b & c <3 d"))
    }

    @Test
    fun `a table reads row by row, a nested table's text staying in its cell and its rows not the table's`() {
        val page = """<table><tbody><tr><th colspan="2">Head</th></tr><tr><td>One<table><tr><td>Inner</td><td>Two</td></tr></table></td><td>Three</td></tr>""" +
            """<tr><td>Four</td></tr></tbody></table><p>After</p>"""
        val reader = HtmlReader(page)
        reader.next()
        val rows = reader.table()
        assertEquals(listOf(listOf("Head"), listOf("One Inner Two", "Three"), listOf("Four")), texts(rows))
        assertEquals(listOf(true, false, false), rows.map { it.cells.first().header })
        assertEquals(2, rows[0].cells[0].columns)
        // The reader is past the outer table's end, and reads on from there.
        assertEquals("p", (reader.next() as HtmlToken.Start).name)
    }

    @Test
    fun `a cell without its end tag ends at the next cell, row or the table's end`() {
        assertEquals(listOf(listOf("a", "b"), listOf("c")), texts(firstTable("<table><tr><td>a<td>b<tr><td>c</table>")))
        assertEquals(listOf(listOf("a", "b")), texts(firstTable("<table><tr><td>a</td><td>b")))
    }

    @Test
    fun `a cell's text has its sup marks and bracketed marks taken off, and a break between its lines`() {
        val rows = firstTable(
            """<table><tr><td>Savrin<sup id="cite_ref-4" class="reference"><a href="#cite_note-4">[4]</a></sup></td>""" +
                """<td>Light Heavyweight[a]</td><td>Quessel<br>(c)</td><td><sup><sup>x</sup>y</sup>Halde [note 2]</td></tr></table>""",
        )
        assertEquals(listOf(listOf("Savrin", "Light Heavyweight", "Quessel (c)", "Halde")), texts(rows))
        assertNull(rows.single().cells.first().link, "a link inside a mark is no link of the cell")
    }

    @Test
    fun `a cell's first link is kept with its address, class and text`() {
        val rows = firstTable(
            """<table><tr><td><i><a href="/wiki/UFC_999" class="mw-redirect" title="UFC 999">UFC 999: Quessel <b>vs.</b> Halde</a></i> <a href="/wiki/Other">x</a></td>""" +
                """<td colspan="x" rowspan="3">Dec 12, 2026</td></tr></table>""",
        )
        val cell = rows.single().cells.first()
        assertEquals("/wiki/UFC_999", cell.link?.href)
        assertEquals("mw-redirect", cell.link?.classes)
        assertEquals("UFC 999: Quessel vs. Halde", cell.link?.text)
        assertEquals(1, rows.single().cells[1].columns, "colspan=\"x\" spans one column")
        assertEquals(3, rows.single().cells[1].rows)
    }

    @Test
    fun `nothing it is given makes it throw`() {
        val pages = listOf(
            Fixtures.article(),
            Fixtures.article(form = Form.Results, heading = Heading.Legacy),
            "<", "&", "&#", "&#x", "&#xZZ;", "<a", "<a href=\"", "<a href='x", "<!--", "<!", "<?", "</", "</a", "<script>", "<script>x</scr",
            "<table><tr><td colspan=\"x\">a", "<table><tr><td colspan=\"99999999999\">a</td></tr></table>", "<td><tr></table></table>",
            "<sup><sup></sup></sup></sup>b", "a<<<b>>>c", "<a b c=d e='f' g=\"h\"/>", "\u0000<\u0000>",
            "<table>".repeat(1000) + "<tr><td>deep</td></tr>" + "</table>".repeat(1000),
            "<table>".repeat(1000),
        )
        pages.forEach { page -> readAll(page) }
        val page = Fixtures.article(form = Form.Results)
        Fixtures.cuts(page).forEach { cut -> readAll(page.substring(0, cut)) }
    }

    @Test
    fun `a table nested a thousand deep is read as the outer table's one cell`() {
        val page = "<table><tr><td>" + "<table><tr><td>".repeat(1000) + "deep" + "</td></tr></table>".repeat(1000) + "</td></tr></table>"
        assertEquals(listOf(listOf("deep")), texts(firstTable(page)))
    }

    /** Every token of [page], and every table read where one starts. */
    private fun readAll(page: String) {
        val reader = HtmlReader(page)
        while (true) {
            val token = reader.next() ?: break
            if (token is HtmlToken.Start && token.name == "table") reader.table()
        }
    }
}
