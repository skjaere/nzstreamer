package app.nzstreamer.plugin.sportarr.bouts

// Invented pages in the shapes L-B2 describes, written for these tests: invented fighters, a pair who share one of their
// surnames, and UFC's real part and weight-class words. None is a copy, a trim or an edit of a real page.

/** How a page writes its card's heading: Parsoid's `<h2 id>`, or the legacy parser's mw-headline span inside the `<h2>`. */
enum class Heading { Parsoid, Legacy }

/** A page before the event (every bout "vs.", no result) or after it (the winners moved left with "def."). */
enum class Form { Pre, Results }

/**
 * A bout as a fixture writes it: its [weight] cell, and its two fighters' cells as the pre-event table lists them ([left],
 * [right]: HTML, marks and all). Its result: [winner] 0 for left, 1 for right, null for a draw or a no contest (listed as
 * [left] and [right] in both forms); [method], [round] and [time]. [note] is the notes cell before the event, [resultNote]
 * after it.
 */
class FixtureBout(
    val weight: String,
    val left: String,
    val right: String,
    val winner: Int? = 0,
    val method: String = "Decision (unanimous)",
    val round: Int = 3,
    val time: String = "5:00",
    val note: String = "",
    val resultNote: String = note,
)

/** A part of a fixture card: its header's words, and its bouts as fought (the table lists them the other way up). */
class FixturePart(val header: String, val bouts: List<FixtureBout>)

/**
 * A row of the fixture index: its Event cell's link [text] and [href] (no link where null), its Date cell as written
 * ([date]), and whether its link is [red] (an event with no article yet).
 */
class IndexEntry(val text: String, val href: String?, val date: String, val red: Boolean = false)

object Fixtures {
    const val CANONICAL_999 = "https://en.wikipedia.org/wiki/UFC_999"

    /** The infobox's Date cell as Wikipedia writes it: the day in words, then the ISO date in a hidden span. */
    fun dateCell(words: String, iso: String) =
        """$words<span style="display:none">&#160;(<span class="bday dtstart published updated">$iso</span>)</span>"""

    /**
     * UFC 999 as fought: thirteen bouts, twelve of fighters with one name each and a pair who share a surname, told apart
     * by their given names. The winners are chosen so the results table's order gives them away: in most "def." rows the
     * fighter later in the alphabet wins, and Halde, the title's second side, wins the main event. The draw and the no
     * contest are listed against the alphabet. Marks: a reference on a fighter and on a weight class, entities in the
     * women's weight classes, "(c)" on both champions.
     */
    val UFC_999: List<FixturePart> = listOf(
        FixturePart(
            "Early preliminary card (Fight Pass)",
            listOf(
                FixtureBout("Flyweight", """Savrin<sup id="cite_ref-4" class="reference"><a href="#cite_note-4">[4]</a></sup>""", "Ambrel",
                    method = "Submission (rear-naked choke)", round = 2, time = "3:12"),
                FixtureBout("Women&#39;s Strawweight", "Durnay", "Wexmoor", winner = 1, method = "KO (punch)", round = 1, time = "0:48",
                    resultNote = "Durnay was deducted one point in round 1 due to an illegal knee."),
                FixtureBout("Welterweight", "Kjelsen", "Tollerup"),
                FixtureBout("Bantamweight", "Elsvin Marhoff", "Brannoc Marhoff", method = "TKO (elbows)", time = "4:01"),
            ),
        ),
        FixturePart(
            "Preliminary card (Kestrel Sports / Kestrel+)",
            listOf(
                FixtureBout("Bantamweight", "Tarnoy", "Ferlund", winner = null, method = "Draw (majority)"),
                FixtureBout("""Light Heavyweight<sup class="reference"><a href="#cite_note-a">[a]</a></sup>""", "Pellicot", "Grisholm",
                    method = "TKO (punches)", round = 2, time = "1:55"),
                FixtureBout("Featherweight", "Velmar", "Kestvik", winner = null, method = "No Contest (accidental eye poke)", round = 1,
                    time = "2:10"),
                FixtureBout("Middleweight", "Brenneck", "Marhoff", winner = 1, method = "Decision (split)"),
            ),
        ),
        FixturePart(
            "Main card (Pay-per-view)",
            listOf(
                FixtureBout("Heavyweight", "Zelinek", "Lindqvar", winner = 1, method = "KO (head kick)", round = 1, time = "1:02",
                    resultNote = "For the first time, a heavyweight title contender opened the main card. Lindqvar called for a rematch."),
                FixtureBout("Women&#8217;s Flyweight", "Ostrem", "Tessmar", winner = 1),
                FixtureBout("Welterweight", "Rendahl", "Varnholt", method = "Submission (guillotine choke)", round = 2, time = "2:40"),
                FixtureBout("Bantamweight", "Brisk (c)", "Loam", winner = 1, method = "TKO (punches)", round = 4, time = "3:33",
                    note = "For the UFC Bantamweight Championship."),
                FixtureBout("Lightweight", "Quessel (c)", "Halde", winner = 1, method = "Decision (split)", round = 5,
                    note = "For the UFC Lightweight Championship.",
                    resultNote = "For the UFC Lightweight Championship. Halde took the title in a close fight."),
            ),
        ),
    )

    /** UFC 999's card as the reader keeps it from either form: running order, pairs alphabetical but the main event's. */
    val UFC_999_CARD = BoutCard(
        "UFC 999",
        listOf(
            BoutPart(
                "early-prelims",
                listOf(
                    Bout("Flyweight", "Ambrel", "Savrin", titleFight = false, mainEvent = false),
                    Bout("Women's Strawweight", "Durnay", "Wexmoor", titleFight = false, mainEvent = false),
                    Bout("Welterweight", "Kjelsen", "Tollerup", titleFight = false, mainEvent = false),
                    Bout("Bantamweight", "Brannoc Marhoff", "Elsvin Marhoff", titleFight = false, mainEvent = false),
                ),
            ),
            BoutPart(
                "prelims",
                listOf(
                    Bout("Bantamweight", "Ferlund", "Tarnoy", titleFight = false, mainEvent = false),
                    Bout("Light Heavyweight", "Grisholm", "Pellicot", titleFight = false, mainEvent = false),
                    Bout("Featherweight", "Kestvik", "Velmar", titleFight = false, mainEvent = false),
                    Bout("Middleweight", "Brenneck", "Marhoff", titleFight = false, mainEvent = false),
                ),
            ),
            BoutPart(
                "main-card",
                listOf(
                    Bout("Heavyweight", "Lindqvar", "Zelinek", titleFight = false, mainEvent = false),
                    Bout("Women’s Flyweight", "Ostrem", "Tessmar", titleFight = false, mainEvent = false),
                    Bout("Welterweight", "Rendahl", "Varnholt", titleFight = false, mainEvent = false),
                    Bout("Bantamweight", "Brisk", "Loam", titleFight = true, mainEvent = false),
                    Bout("Lightweight", "Quessel", "Halde", titleFight = true, mainEvent = true),
                ),
            ),
        ),
    )

    const val UFC_999_TITLE = "UFC 999: Quessel vs. Halde"

    /** The numbered card's part ids, as the event's parts name them. */
    val NUMBERED = listOf("early-prelims", "prelims", "main-card")

    /** A fight night's part ids. */
    val FIGHT_NIGHT = listOf("prelims", "main-card")

    /** Words a reader that kept anything of a result would carry into a card: separators, methods, times. */
    val RESULT_WORDS = listOf(
        "def", "Decision", "Submission", "KO", "TKO", "Draw", "No Contest", "unanimous", "split", "majority", "choke", "punch",
        "elbows", "kick", "eye poke", "deducted", "first time", "rematch", "took the title", ":12", ":48", ":00", ":01", ":55", ":10",
        ":02", ":40", ":33",
    )

    /**
     * An article page: its head with the [canonical] link (none where null), a script and a style holding table-like text,
     * the infobox (a nested table inside it) with its Date row's cell [date] (no Date row where null), a lead and a
     * Background section naming fighters, the card's heading in [heading]'s form reading [headingText] (with [headingId]),
     * a comment and a script between the heading and the table, the table of [parts] in [form] (each bout's columns
     * swapped where [swaps]' bit for its place in running order is set), then Announced bouts (a list and a table of bouts
     * not on the card) and Bonus awards naming fighters.
     */
    fun article(
        parts: List<FixturePart> = UFC_999,
        form: Form = Form.Pre,
        heading: Heading = Heading.Parsoid,
        canonical: String? = CANONICAL_999,
        date: String? = dateCell("November 21, 2026", "2026-11-21"),
        headingText: String = if (form == Form.Pre) "Fight card" else "Results",
        headingId: String = headingText.replace(' ', '_'),
        swaps: Long = 0,
        cardTable: String? = table(parts, form, swaps),
    ): String = buildString {
        append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"UTF-8\"><title>UFC 999 - Wikipedia</title>\n")
        canonical?.let { append("""<link rel="canonical" href="$it">""").append('\n') }
        append("<script>var rows = \"<table><tr><th colspan='8'>Main card</th></tr><tr><td>Heavyweight</td><td>Ostrem</td><td>vs.</td><td>Ambrel</td></tr></table>\";</script>\n")
        append("<style>td.x::before { content: \"<table>\"; }</style>\n</head><body>\n")
        append("""<table class="infobox vevent"><tbody>""")
        append("""<tr><th colspan="2" class="infobox-above">UFC 999: Quessel vs. Halde</th></tr>""")
        append("""<tr><td colspan="2"><table class="infobox-subbox"><tbody><tr><th>Date</th><td>1999-01-01</td></tr></tbody></table></td></tr>""")
        append("""<tr><th scope="row" class="infobox-label">Promotion</th><td class="infobox-data">Ultimate Fighting Championship</td></tr>""")
        date?.let { append("""<tr><th scope="row" class="infobox-label">Date</th><td class="infobox-data">$it</td></tr>""") }
        append("""<tr><th scope="row" class="infobox-label">Venue</th><td class="infobox-data">Kestrel Arena</td></tr>""")
        append("</tbody></table>\n")
        append("<p><b>UFC 999: Quessel vs. Halde</b> was a mixed martial arts event. ")
        if (form == Form.Results) append("Halde def. Quessel to take the title; Savrin def. Ambrel early on. ")
        append("</p>\n")
        append("""<div class="mw-heading mw-heading2"><h2 id="Background">Background</h2></div>""")
        append("<p>A bout between Grisholm and Tollerup was expected but never booked.</p>\n")
        append(
            when (heading) {
                Heading.Parsoid -> """<div class="mw-heading mw-heading2"><h2 id="$headingId">$headingText</h2></div>"""
                Heading.Legacy -> """<h2><span class="mw-headline" id="$headingId">$headingText</span><span class="mw-editsection">[edit]</span></h2>"""
            },
        )
        append("\n<!-- <table><tr><th colspan=\"8\">Main card</th></tr><tr><td>Flyweight</td><td>Velmar</td><td>vs.</td><td>Tarnoy</td></tr></table> -->\n")
        append("<script>document.write('<table><tr><td>Lightweight</td><td>Ferlund</td><td>def.</td><td>Durnay</td></tr></table>');</script>\n")
        cardTable?.let { append(it).append('\n') }
        append("""<div class="mw-heading mw-heading2"><h2 id="Announced_bouts">Announced bouts</h2></div>""")
        append("<ul><li>Heavyweight bout: Savrin vs. Ferlund</li></ul>")
        append("""<table class="wikitable"><tbody><tr><th colspan="8">Main card</th></tr>""")
        append("<tr><td>Heavyweight</td><td>Savrin</td><td>vs.</td><td>Ferlund</td><td></td><td></td><td></td><td></td></tr></tbody></table>\n")
        if (form == Form.Results) {
            append("""<div class="mw-heading mw-heading2"><h2 id="Bonus_awards">Bonus awards</h2></div>""")
            append("<ul><li>Fight of the Night: Halde vs. Quessel</li><li>Performance of the Night: Wexmoor and Savrin</li></ul>\n")
        }
        append("</body></html>\n")
    }

    /**
     * The card's table: each part's header (a `<th>` across eight columns) and the column headers, then its bouts, the
     * main card first and each part's last bout at its top, as Wikipedia lists them.
     */
    fun table(parts: List<FixturePart>, form: Form, swaps: Long = 0): String = buildString {
        append("""<table class="toccolours"><tbody>""")
        var place = parts.sumOf { it.bouts.size }
        parts.reversed().forEach { part ->
            append("""<tr><th colspan="8" style="background-color: #E6E8FA;">${part.header}</th></tr>""")
            append("<tr><th>Weight class</th><th></th><th></th><th></th><th>Method</th><th>Round</th><th>Time</th><th>Notes</th></tr>")
            part.bouts.reversed().forEach { bout ->
                place--
                append(row(bout, form, swaps and (1L shl place) != 0L))
            }
        }
        append("</tbody></table>")
    }

    /** One bout's row in [form]; [swapped] lists its two fighters the other way round. */
    fun row(bout: FixtureBout, form: Form, swapped: Boolean = false): String {
        val (left, right) = when {
            form == Form.Pre || bout.winner == null || bout.winner == 0 -> bout.left to bout.right
            else -> bout.right to bout.left
        }.let { if (swapped) it.second to it.first else it }
        val separator = if (form == Form.Results && bout.winner != null) "def." else "vs."
        return if (form == Form.Pre) {
            data(bout.weight, left, separator, right, "", "", "", bout.note)
        } else {
            data(bout.weight, left, separator, right, bout.method, "${bout.round}", bout.time, bout.resultNote)
        }
    }

    /** A table row of `<td>` cells. */
    fun data(vararg cells: String): String = cells.joinToString("", "<tr>", "</tr>") { "<td>$it</td>" }

    /** A part's header row, across eight columns. */
    fun header(words: String, columns: Int = 8): String = """<tr><th colspan="$columns">$words</th></tr>"""

    /** A table of [rows]. */
    fun rows(vararg rows: String): String = rows.joinToString("", """<table class="toccolours"><tbody>""", "</tbody></table>")

    /** A pre-event bout's row: [left] vs. [right], with [note]. */
    fun vs(weight: String, left: String, right: String, note: String = ""): String = data(weight, left, "vs.", right, "", "", "", note)

    /**
     * The index page, `List of UFC events`, in L-B2's shape: a lead naming events, then the scheduled events' table (Event,
     * Date, Venue, Location) and the past events' (#, Event, Date, Venue, Location, Attendance), both newest first, and a
     * table of something else with an Event column of its own.
     */
    fun index(scheduled: List<IndexEntry>, past: List<IndexEntry>): String = buildString {
        append("""<!DOCTYPE html><html><head><link rel="canonical" href="https://en.wikipedia.org/wiki/List_of_UFC_events"></head><body>""")
        append("""<p>This list of UFC events names <a href="/wiki/UFC_999">UFC 999</a> among others.</p>""")
        append("""<table class="wikitable"><tbody><tr><th>Event</th><th>Champion</th></tr>""")
        append("""<tr><td><a href="/wiki/UFC_999">UFC 999: Quessel vs. Halde</a></td><td>Halde</td></tr></tbody></table>""")
        append("""<div class="mw-heading mw-heading2"><h2 id="Scheduled_events">Scheduled events</h2></div>""")
        append("""<table class="wikitable sortable"><tbody><tr><th>Event</th><th>Date</th><th>Venue</th><th>Location</th></tr>""")
        scheduled.forEach { append("<tr><td>${event(it)}</td><td>${it.date}</td><td>Kestrel Arena</td><td>Tarnholm</td></tr>") }
        append("</tbody></table>")
        append("""<div class="mw-heading mw-heading2"><h2 id="Past_events">Past events</h2></div>""")
        append("""<table class="wikitable sortable"><tbody><tr><th>#</th><th>Event</th><th>Date</th><th>Venue</th><th>Location</th><th>Attendance</th></tr>""")
        past.forEachIndexed { i, entry ->
            append("<tr><td>${past.size - i + 700}</td><td>${event(entry)}</td><td>${entry.date}</td><td>Kestrel Arena</td><td>Tarnholm</td><td>12,345</td></tr>")
        }
        append("</tbody></table></body></html>")
    }

    private fun event(entry: IndexEntry): String = when {
        entry.href == null -> entry.text
        entry.red -> """<a href="${entry.href}" class="new" title="${entry.text} (page does not exist)">${entry.text}</a>"""
        else -> """<i><a href="${entry.href}" title="${entry.text}">${entry.text}</a></i><sup class="reference"><a href="#cite_note-9">[9]</a></sup>"""
    }

    /** Where [page] can be cut at a tag's edge: before each '<' and after each '>'. */
    fun cuts(page: String): List<Int> =
        page.indices.filter { page[it] == '<' } + page.indices.filter { page[it] == '>' }.map { it + 1 }
}
