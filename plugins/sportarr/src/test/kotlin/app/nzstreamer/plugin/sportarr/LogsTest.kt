package app.nzstreamer.plugin.sportarr

import app.nzstreamer.plugin.sportarr.bouts.WikiFakes
import app.nzstreamer.plugin.sportarr.bouts.WikiFakes.Companion.UFC_999
import app.nzstreamer.plugin.sportarr.bouts.again
import app.nzstreamer.plugin.sportarr.bouts.open
import app.nzstreamer.plugin.sportarr.family.Matchers
import app.nzstreamer.plugin.sportarr.match.Runner
import app.nzstreamer.plugin.sportarr.match.TestHandle
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.source.ListRead
import app.nzstreamer.plugin.sportarr.source.ListReader
import app.nzstreamer.plugin.sportarr.source.LoopbackHttp
import app.nzstreamer.plugin.sportarr.source.SportarrClient
import app.nzstreamer.plugin.sportarr.source.loopbackSafety
import io.skjaere.usenet.mockseed.FakeSportarrServer
import io.skjaere.usenet.mockseed.SportsCatalog
import io.skjaere.usenet.plugin.Found
import io.skjaere.usenet.plugin.HttpAnswer
import io.skjaere.usenet.plugin.IndexerLine
import io.skjaere.usenet.plugin.PluginHttp
import io.skjaere.usenet.plugin.PluginHttpException
import io.skjaere.usenet.plugin.PluginSearch
import io.skjaere.usenet.plugin.ReleaseInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The plugin's log lines (L653, L660, L-B13): they reach the app's Logs page and last-crash.txt, which users share, so they
 * say counts, durations and kinds, and never the key, the address or its host, an event's or a league's title, a
 * release's name, or anything Sportarr sends that the plugin never declares (the canary values of the fake's every event
 * and league); nor anything of a fight card: an article's title, a fighter, a weight class, a result, a note or a path.
 * The test-only capturing provider records every line of the plugin's loggers while Test answers, a refresh reads the
 * fake Sportarr's library, the runner judges the catalogue's names, and the fight cards are read from the fake Wikipedia
 * in its every form and failure.
 */
class LogsTest {
    @TempDir
    lateinit var dir: File

    private val anchor = LocalDate.of(2026, 11, 22)
    private val catalog = SportsCatalog.load()
    private val fake = FakeSportarrServer(catalog, anchor, port = 0)
    private val http = LoopbackHttp()
    private val address = "http://127.0.0.1:${fake.port}"

    /** The fake Wikipedia and the fake Sportarr holding its switches, started for the fight cards' cases alone. */
    private val wiki = lazy { WikiFakes(anchor) }

    @AfterEach
    fun stop() {
        fake.close()
        if (wiki.isInitialized()) wiki.value.close()
    }

    private fun control(path: String) {
        val connection = URI("$address/control/$path").toURL().openConnection() as java.net.HttpURLConnection
        assertEquals(200, connection.responseCode, path)
        connection.disconnect()
    }

    private fun client(address: String = this.address) = SportarrClient(http, address, FakeSportarrServer.KEY, ::loopbackSafety)

    /** What no plugin line may hold: the key, the address and its host, every title of the library, the canaries. */
    private fun secrets(vararg more: String): List<String> {
        val library = catalog.leagues(anchor) + catalog.busy(anchor, events = 5200)
        return listOf(FakeSportarrServer.KEY, address, "127.0.0.1", "localhost", FakeSportarrServer.CANARY, "canary.invalid") +
            library.map { it.name } + library.flatMap { league -> league.events.map { it.title } } + more
    }

    private fun assertClean(lines: List<Logged.Line>, vararg more: String) {
        val secrets = secrets(*more)
        lines.forEach { line ->
            val held = secrets.filter { it in line.words }
            assertTrue(held.isEmpty(), "${line.logger}: \"${line.words}\" holds $held")
        }
    }

    @Test
    fun `Test logs no key, address, host, title or canary value, whatever it answers`() {
        assertIs<CapturedLogs.Factory>(LoggerFactory.getILoggerFactory(), "the capturing provider is the only one")
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val switches = listOf(
            null, "refuse-key", "version?v=4.0.998", "html", "fail?status=500&route=status", "fail?status=503&route=leagues", "url-base?base=/sportarr",
        )
        val lines = switches.flatMap { switch ->
            control("reset")
            switch?.let(::control)
            val mark = Logged.mark()
            runBlocking { client().test() }
            Logged.pluginLinesSince(mark).also { assertTrue(it.isNotEmpty(), "Test says what it answered: $switch") }
        } + run {
            val mark = Logged.mark()
            runBlocking { client("http://127.0.0.1:$closed").test() }
            runBlocking { client("$address/?apikey=${FakeSportarrServer.KEY}").test() }
            Logged.pluginLinesSince(mark)
        }
        assertEquals(switches.size + 2, lines.size, lines.joinToString("\n") { it.words })
        assertClean(lines, "127.0.0.1:$closed")
    }

    @Test
    fun `a refresh logs no key, address, host, title or canary value, read whole, cut or failed`() {
        val now = anchor.atTime(12, 0).toInstant(ZoneOffset.UTC)
        fun read() = runBlocking { ListReader(client(), now, ZoneOffset.UTC).read() }
        val mark = Logged.mark()
        read()
        control("busy?events=5200")
        read()
        control("fail?status=500&route=events&page=2")
        read()
        control("reset")
        control("remove-league?id=1")
        read()
        control("refuse-key")
        read()
        val lines = Logged.pluginLinesSince(mark)
        assertEquals(5, lines.size, lines.joinToString("\n") { it.words })
        // A read's line counts its leagues by family, by the family's name alone; the whole fake's first.
        val read = lines.filter { it.words.startsWith("Sportarr list read:") }
        assertEquals(3, read.size, lines.joinToString("\n") { it.words })
        assertTrue(read.all { LEAGUES_BY_FAMILY.containsMatchIn(it.words) }, read.joinToString("\n") { it.words })
        assertTrue(read.first().words.startsWith("Sportarr list read: $WHOLE_FAKE, "), read.first().words)
        assertClean(lines)
    }

    @Test
    fun `the runner logs no release name, title or canary value, whatever it judges`() {
        // The library as a read of the fake gives it, its canary fields sent with every event and league; and every name of
        // the catalogue, scored and result-word ones among them, found by every search of every part of every listed event.
        val now = anchor.atTime(12, 0).toInstant(ZoneOffset.UTC)
        val read = assertIs<ListRead.Read>(runBlocking { ListReader(client(), now, ZoneOffset.UTC).read() })
        val sports = Sports.of(read.list)
        val matchers = Matchers(sports)
        val releases = catalog.releases(anchor)
        val asked = CopyOnWriteArrayList<String>()
        val search = object : PluginSearch {
            override fun search(words: String, runtimeMinutes: Int?, fresh: Boolean): Flow<Found> = flow {
                asked += words
                val found = releases.map { ReleaseInfo(it.name, it.sizeBytes, it.postedAt, INDEXER, playable = true, whyNot = null, handle = TestHandle(it.id)) }
                emit(Found(listOf(IndexerLine(INDEXER, IndexerLine.State.Found, found.size, "${found.size} found")), found))
            }
        }
        val items = sports.events.flatMap { event -> event.parts.map { event to it } }
        val mark = Logged.mark()
        items.forEach { (event, part) -> runBlocking { Runner(search).releases(matchers.of(event), event, part) } }
        val lines = Logged.pluginLinesSince(mark)
        assertEquals(items.size, lines.size, lines.joinToString("\n") { it.words })
        // Every run judged every name, and counted the scores and result words it left out without naming one.
        assertTrue(lines.all { "${releases.size} judged" in it.words }, lines.first().words)
        assertTrue(lines.any { "Score=" in it.words } && lines.any { "Result=" in it.words }, lines.joinToString("\n") { it.words })
        // Each says its family, the sport it came from by Sportarr's category word, and each search's one indexer by its count.
        val wrong = lines.filterNot { RELEASES.matches(it.words) }
        assertEquals(emptyList(), wrong.map { it.words })
        assertTrue(lines.any { "FightCard part (the league's sport \"" in it.words }, lines.joinToString("\n") { it.words })
        assertClean(lines, *(releases.map { it.name } + asked).toTypedArray())
    }

    @Test
    fun `the fight cards log their outcomes and counts alone, whatever the fake Wikipedia serves or fails`() = runBlocking<Unit> {
        val fakes = wiki.value
        val events = fakes.events.values.sortedBy { it.id }
        val ufc999 = fakes.events.getValue(UFC_999)
        var made = 0
        fun folder() = File(dir, "cards-${made++}")
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val mark = Logged.mark()
        // Each event's article in every form, read by a session of its own.
        WIKI_FORMS.forEach { form ->
            events.forEach { event ->
                fakes.control("reset")
                fakes.control("wiki-form?event=${event.id}&form=$form")
                fakes.cards(this, folder()).open(event)
            }
        }
        // Each of the fake's failures, then Try again while its hold lasts.
        listOf(503, 429, 403).forEach { status ->
            fakes.control("reset")
            fakes.control("wiki-fail?status=$status")
            val cards = fakes.cards(this, folder())
            events.forEach { event ->
                cards.open(event)
                cards.again(event)
            }
        }
        fakes.control("reset")
        // The fake gone, and every kind of request that gets no answer.
        fakes.cards(this, folder(), base = "http://127.0.0.1:$closed").open(ufc999)
        PluginHttpException.Kind.entries.forEach { kind -> fakes.cards(this, folder(), http = unanswered(kind)).open(ufc999) }
        // The plugin's folders not writable, with the index read; then its kept cards torn, and its switch.
        val unwritable = folder().also { it.mkdirs() }
        listOf("data", "cache").forEach { File(unwritable, it).writeText("not a folder") }
        fakes.control("wiki-form?event=$UFC_999&form=moved")
        fakes.cards(this, unwritable).open(ufc999)
        fakes.control("reset")
        val torn = folder().also { File(it, "data").mkdirs() }
        File(torn, "data/cards.json").writeText("""{"format":1,"cards":[{"event":""")
        fakes.cards(this, torn).open(ufc999)
        val off = folder().also { File(it, "data").mkdirs() }
        File(off, "data/options.json").writeText("""{"format":""")
        fakes.cards(this, off).open(ufc999)

        val lines = Logged.pluginLinesSince(mark)
        assertEquals(emptyList(), lines.filterNot { it.logger.startsWith("app.nzstreamer.plugin.sportarr.bouts.") }.map { it.words })
        // Each line an outcome and its counts, a kind, or an exception or a file by its class alone.
        assertEquals(emptyList(), lines.filterNot { line -> CARD_LINES.any { it.matches(line.words) } }.map { it.words })
        // The index's name in two lines' own words is no title read from a page, and its "UFC" the one league cards are read for.
        assertClean(lines.map { it.copy(words = it.words.replace(INDEX_WORDS, "list of events")) }, *cardSecrets(fakes), "127.0.0.1:$closed")
        // Every way a read ends, logged.
        val said = lines.map { it.words.replace(MS, "N ms") }.toSet()
        val expected = listOf(
            "Fight card read: found, 12 bouts in 3 parts, 1 requests in N ms",
            "Fight card read: found, 9 bouts in 2 parts, 2 requests in N ms",
            "Fight card read: found, 12 bouts in 3 parts, 4 requests in N ms",
            "Fight card read: not out yet, 1 requests in N ms",
            "Fight card read: not out yet, 3 requests in N ms",
            "Fight card read: no card, 1 requests in N ms",
            "Fight card read: no card, 3 requests in N ms",
            "Fight card not read: 503 after 1 requests in N ms",
            "Fight card not read: 429 after 1 requests in N ms",
            "Fight card not read: 403 after 1 requests in N ms",
            "Fight card not read: held after 0 requests in N ms",
            "The fight cards weren't kept: FileNotFoundException",
            "The list of UFC events wasn't kept: FileNotFoundException",
            "The kept fight cards are unreadable: read as none",
            "The fight cards switch is unreadable: read as off",
        ) + PluginHttpException.Kind.entries.map { "Fight card not read: ${it.name} after 1 requests in N ms" }
        assertEquals(emptyList(), expected.filterNot { it in said }, said.joinToString("\n"))
    }

    @Test
    fun `a card read before its event and one read with its results log the same lines, but for their times`() = runBlocking<Unit> {
        val fakes = wiki.value
        val logged = listOf("pre", "results").map { form ->
            fakes.control("reset")
            fakes.events.keys.forEach { fakes.control("wiki-form?event=$it&form=$form") }
            val cards = fakes.cards(this, File(dir, form))
            val mark = Logged.mark()
            fakes.events.values.sortedBy { it.id }.forEach { cards.open(it) }
            // Each article answered in this form, so the lines compared are two forms' and not the fake's usual ones twice.
            val answered = fakes.asked().filter { it.status == 200 }.map { it.path to it.form }
            assertEquals(ARTICLES.map { it to form }, answered, "the articles answered in the $form form")
            Logged.pluginLinesSince(mark).map { it.copy(words = it.words.replace(MS, "N ms")) }
        }
        assertEquals(logged[0], logged[1])
        assertEquals(
            listOf("Fight card read: found, 12 bouts in 3 parts, 1 requests in N ms", "Fight card read: found, 9 bouts in 2 parts, 2 requests in N ms"),
            logged[0].map { it.words },
        )
    }

    /** A host's HTTP whose every request gets no answer, for the [kind] of reason. */
    private fun unanswered(kind: PluginHttpException.Kind) = object : PluginHttp {
        override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Int): HttpAnswer =
            throw PluginHttpException(kind, "No answer")
    }

    /**
     * What no fight card line may hold besides [secrets]': all the fake Wikipedia's pages hold of a card (its articles'
     * and index rows' titles and names, places, parts, weight classes, fighters with and without their marks, results and
     * notes, the lead and the bonuses), the separators, and its address and paths.
     */
    private fun cardSecrets(fakes: WikiFakes): Array<String> {
        val cards = fakes.wiki.cards
        val articles = cards.articles.flatMap { article ->
            listOf(article.title, article.name, article.moved, article.venue, article.city, article.background, article.result) +
                article.redirects + article.announced + article.bonus + article.parts.flatMap { part ->
                    listOf(part.header) + part.bouts.flatMap { bout ->
                        listOf(bout.weight, bout.method, bout.time, bout.note, bout.resultNote) + bout.fighters + bout.fighters.map { it.removeSuffix(" (c)") }
                    }
                }
        }
        val index = cards.index.flatMap { listOf(it.name, it.title, it.venue, it.city) }
        val address = listOf(fakes.base, ":${fakes.wiki.port}", "/wiki/", "wiki", "http", "def.", "vs.")
        return (articles + index + address).filter { it.isNotBlank() }.distinct().toTypedArray()
    }

    private companion object {
        const val INDEXER = "Sports indexer"

        /** Where each event's article is answered, in the events' order: at UFC 999's title, and at the fight night's number. */
        val ARTICLES = listOf("/wiki/UFC_999", "/wiki/UFC_Fight_Night_888")

        /** The fake Wikipedia's forms of an article (L-B12). */
        val WIKI_FORMS = listOf("results", "pre", "unsplit", "bare", "missing", "moved")

        /** A line's duration. */
        val MS = Regex("""\d+ ms""")

        /** The index's name, as two of the fight cards' lines write it. */
        val INDEX_WORDS = Regex("""(?i)list of UFC events""")

        /**
         * The fight cards' lines (L-B13): a read's outcome with the bouts and parts it kept, its requests and its time; why
         * one failed (a status, a kind of failure, a hold, an error); an exception or a file's failure by its class alone.
         */
        val CARD_LINES = listOf(
            Regex("""^Fight card read: found, \d+ bouts in \d+ parts, \d+ requests in \d+ ms$"""),
            Regex("""^Fight card read: (not out yet|no card), \d+ requests in \d+ ms$"""),
            Regex(
                """^Fight card not read: (\d{3}|${PluginHttpException.Kind.entries.joinToString("|") { it.name }}|held|an error) """ +
                    """after \d+ requests in \d+ ms$""",
            ),
            Regex(
                """^(A Wikipedia page was unreadable|Wikipedia's list of UFC events was unreadable|A fight card's page was unreadable|""" +
                    """Fight card read failed|Fight cards failed|The list of UFC events wasn't kept|The fight cards weren't kept|""" +
                    """The fight cards switch wasn't kept): [A-Z][A-Za-z0-9]*$""",
            ),
            Regex("""^The kept fight cards are unreadable: read as none$"""),
            Regex("""^The fight cards switch is unreadable: read as off$"""),
        )

        /**
         * The whole fake's leagues by family: its fight leagues under "Combat", the one kept on "Fighting" and the one with
         * an unknown sport whose events carry part names are all fight cards.
         */
        const val WHOLE_FAKE = "14 leagues {TeamGame=5, Motorsport=3, FightCard=5, Generic=1}"

        /** A read's leagues by family, as [WHOLE_FAKE] gives the whole fake's. */
        val LEAGUES_BY_FAMILY = Regex("""^Sportarr list read: \d+ leagues \{(?:(?:TeamGame|Motorsport|FightCard|Generic)=\d+(?:, )?)+}, """)

        /** A run's line, each of its searches answered by the one indexer with every name the catalogue has. */
        val RELEASES = Regex(
            """^Releases for a (TeamGame|Motorsport|FightCard|Generic) part """ +
                """\((?:(?:the (?:league|event)'s sport "[a-z ]+"|part names, (?:sport "[a-z ]+"|no sport))(?:, stepped down)?|sport "[a-z ]+" in no family|no sport)\): """ +
                """\d searches( \[\d+])+, \d+ judged, .* in \d+ ms$""",
        )
    }
}
