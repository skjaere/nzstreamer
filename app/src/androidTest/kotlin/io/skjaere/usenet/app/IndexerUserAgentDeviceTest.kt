package io.skjaere.usenet.app

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.settings.USER_AGENT_CHARACTERS
import io.skjaere.usenet.app.settings.USER_AGENT_HELP
import io.skjaere.usenet.app.settings.USER_AGENT_TOO_LONG
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * An indexer's own User-Agent on a phone (L546-L553), over the real settings and the app's own client, its indexer one
 * on this device ([AgentIndexer]) that keeps the User-Agent of each request: added on Settings › Indexers with
 * ExampleReader/2.1, it goes on the form's Test connection and, once saved, on a Search; emptied, the next Search of
 * the same words asks again at once (no cache answers it) with NZStreamer/0.1, the client's own, which the field's
 * placeholder shows. A value with é, or of 257 characters, keeps Save and Test connection disabled, its words in the
 * help's place. The other indexers are switched off, the torrent indexers and service accounts hidden (tombstoned,
 * their keys untouched) and the TMDB key taken away, so a Search asks no one else; all put back after, the test's
 * indexer deleted with its key and its searches forgotten.
 */
@RunWith(AndroidJUnit4::class)
class IndexerUserAgentDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val settings = container.settings
    private val library = container.library
    private val home = HomeContentShown()
    private val indexer = AgentIndexer()
    private lateinit var saved: AppSettings
    private var tmdbKey: String? = null
    private var searchedBefore = emptySet<String>()
    private lateinit var nav: NavHostController

    @Before
    fun setUp() = runBlocking<Unit> {
        // The debug build's own settings saved first, as the ones put back after are read after them.
        withTimeout(SEEDING_MS) { container.devSettingsSaved.await() }
        home.show()
        saved = settings.settings.first()
        tmdbKey = settings.tmdbKey()
        searchedBefore = library.recentSearches.first().map { it.key }.toSet()
        // Hidden, not deleted: deleting would remove their keys from the secret store. With no service account on, no
        // torrent indexer is searched either.
        settings.restore(saved.copy(torrentIndexers = saved.torrentIndexers.map { it.copy(deleted = true) }, services = saved.services.map { it.copy(deleted = true) }))
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        // Search asks TMDB nothing without a key.
        settings.setTmdbKey(null)
    }

    @After
    fun restore() = runBlocking<Unit> {
        everyStep(
            { indexer.close() },
            { home.restore() },
            {
                // Skipped before anything was changed.
                if (::saved.isInitialized) {
                    settings.indexers.first().filter { i -> saved.indexers.none { it.id == i.id } }.forEach { settings.deleteIndexer(it.id) }
                    settings.restore(saved)
                    settings.setTmdbKey(tmdbKey)
                    library.recentSearches.first().filter { it.key !in searchedBefore }.forEach { library.removeSearch(it.key) }
                    assertEquals(saved, settings.settings.first())
                }
            },
        )
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}", e)
        }
    }

    /** Waits for [what]'s search among the requests the indexer was sent from the [from]th on. */
    private fun waitForSearch(what: String, from: Int) {
        try {
            compose.waitUntil(SEARCH_MS) { indexer.requests.drop(from).any { it.kind == "search" } }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("$what: the indexer was sent no search; it was sent ${indexer.requests.drop(from)}", e)
        }
    }

    private fun click(matcher: SemanticsMatcher) = compose.onNode(matcher).performScrollTo().performClick()

    /** The form's fields, in order: Name, API URL, API key, User-Agent. */
    private fun field(label: String) = compose.onAllNodes(hasSetTextAction())[FIELDS.indexOf(label)]

    /** The form alone on screen, the screen it came from gone with its transition: its fields are [field]'s. */
    private fun waitForTheForm() {
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == FIELDS.size }
    }

    private val save = hasText("Save") and hasClickAction()
    private val testConnection = hasText("Test connection") and hasClickAction()

    private fun showTheApp() {
        compose.setContent {
            AppTheme {
                nav = rememberNavController()
                AppNav(nav = nav)
            }
        }
        compose.waitForIdle()
    }

    /** Settings › Indexers › + Add, as the list's button opens it. */
    private fun addAnIndexer() {
        compose.runOnIdle { nav.navigate(IndexersRoute) }
        waitFor(hasText("+ Add") and hasClickAction())
        click(hasText("+ Add") and hasClickAction())
        waitFor(hasText("Add indexer"))
        waitForTheForm()
    }

    /** The Search tab started afresh, [WORDS] typed and searched on the indexers with its button. */
    private fun search() {
        compose.runOnIdle { nav.switchTab(SearchRoute(), restore = false) }
        compose.waitForIdle()
        // Its field alone: the screen it came from gone.
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
        compose.onNode(hasSetTextAction()).performTextInput(WORDS)
        val button = hasContentDescription("Search indexers for “$WORDS”") and hasClickAction()
        waitFor(button)
        compose.onNode(button).performClick()
    }

    @Test
    fun anIndexersUserAgentGoesOnItsTestAndItsSearchesAndEmptiedTheClientsOwnGoesAtOnce() {
        assertEquals("the client's own, as L561 keeps it", "NZStreamer/0.1", container.newznab.ownUserAgent)
        showTheApp()
        addAnIndexer()
        // Empty, the field shows what goes then: the client's own.
        assertTrue("the placeholder", compose.onAllNodes(hasText(container.newznab.ownUserAgent), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("the help under the field", exists(hasText(USER_AGENT_HELP)))
        field("Name").performTextReplacement(NAME)
        field("API URL").performTextReplacement(indexer.url)
        field("API key").performTextReplacement("invented-indexer-key")
        // Padded as a paste may leave it: sent, and saved, trimmed.
        field("User-Agent").performTextReplacement(" $AGENT ")

        click(testConnection)
        waitFor(hasText("Connected · Agent 1.0"), timeoutMs = 30_000)
        val tested = indexer.requests.toList()
        assertEquals("the Test's caps and its search", listOf("caps", "search"), tested.map { it.kind })
        assertEquals("the Test's User-Agents", listOf(AGENT, AGENT), tested.map { it.userAgent })

        click(save)
        waitFor(hasText(NAME) and hasClickAction())
        val id = runBlocking { settings.indexers.first().single { it.name == NAME }.id }
        assertEquals("saved trimmed", AGENT, runBlocking { settings.indexer(id) }?.record?.userAgent)

        // Searched: its caps asked again (the Test forgot them), and the words, both with its own.
        val searched = indexer.requests.size
        search()
        waitForSearch("the Search", searched)
        assertEquals("the Search's User-Agents", listOf(AGENT), indexer.requests.drop(searched).map { it.userAgent }.distinct())

        // Emptied on its form, which loads it as saved.
        compose.runOnIdle { nav.navigate(IndexerRoute(id)) }
        waitFor(hasText(AGENT) and hasSetTextAction())
        waitForTheForm()
        field("User-Agent").performTextReplacement("")
        click(save)
        compose.waitUntil(10_000) { runBlocking { settings.indexer(id) }?.record?.userAgent == "" }

        // The same words again, at once: asked again, not answered from the search's cache, with the client's own.
        val emptied = indexer.requests.size
        search()
        waitForSearch("the Search after the User-Agent was emptied", emptied)
        assertEquals("the User-Agents once emptied", listOf(container.newznab.ownUserAgent), indexer.requests.drop(emptied).map { it.userAgent }.distinct())
    }

    @Test
    fun aUserAgentTheClientWouldntSendKeepsSaveAndTestDisabledAndSaysWhy() {
        showTheApp()
        addAnIndexer()
        field("Name").performTextReplacement(NAME)
        field("API URL").performTextReplacement(indexer.url)
        field("API key").performTextReplacement("invented-indexer-key")
        field("User-Agent").performTextReplacement(AGENT)
        waitFor(save)
        compose.onNode(save).assertIsEnabled()

        // A letter outside printable ASCII, as a phone's own keyboard types one: the client wouldn't send it.
        field("User-Agent").performTextReplacement("${AGENT}é")
        waitFor(hasText(USER_AGENT_CHARACTERS))
        assertFalse("the help still shown beside the problem's words", exists(hasText(USER_AGENT_HELP)))
        compose.onNode(save).assertIsNotEnabled()
        compose.onNode(testConnection).performScrollTo().assertIsNotEnabled()

        field("User-Agent").performTextReplacement("a".repeat(257))
        waitFor(hasText(USER_AGENT_TOO_LONG))
        compose.onNode(save).assertIsNotEnabled()
        compose.onNode(testConnection).performScrollTo().assertIsNotEnabled()

        // Put right, both go back on, and the help with them.
        field("User-Agent").performTextReplacement(AGENT)
        waitFor(hasText(USER_AGENT_HELP))
        compose.onNode(save).assertIsEnabled()
        compose.onNode(testConnection).performScrollTo().assertIsEnabled()
        assertEquals("nothing was sent", emptyList<AgentIndexer.Request>(), indexer.requests.toList())
        assertTrue("nothing was saved", runBlocking { settings.indexers.first() }.none { it.name == NAME })
    }

    /**
     * An indexer on this device that keeps the User-Agent of each request it is sent, by its kind (t=caps, t=search),
     * until closed; it answers its caps, and one invented release for any search. Just enough of Newznab.
     */
    private class AgentIndexer : AutoCloseable {
        /** A request's kind, its t=, and the User-Agent it carried (null for none). */
        data class Request(val kind: String, val userAgent: String?)

        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}"
        val requests = CopyOnWriteArrayList<Request>()

        private val results = buildString {
            val date = ZonedDateTime.now().minusDays(1).format(DateTimeFormatter.RFC_1123_DATE_TIME)
            append("""<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/"><channel>""")
            append("""<newznab:response offset="0" total="1"/>""")
            append("<item><title>$RELEASE</title><guid isPermaLink=\"false\">agent-1</guid><pubDate>$date</pubDate>")
            append("""<enclosure url="$url/api?t=get&amp;id=agent-1" length="1000000000" type="application/x-nzb"/>""")
            listOf("category" to "5040", "size" to "1000000000", "guid" to "agent-1", "grabs" to "10", "files" to "1", "password" to "0", "season" to "S01", "episode" to "E01")
                .forEach { (attr, value) -> append("""<newznab:attr name="$attr" value="$value"/>""") }
            append("</item></channel></rss>")
        }

        init {
            thread(isDaemon = true, name = "agent-indexer") {
                while (!server.isClosed) runCatching { server.accept().use(::answer) }
            }
        }

        private fun answer(socket: Socket) {
            val request = socket.getInputStream().bufferedReader()
            val line = request.readLine().orEmpty()
            val headers = generateSequence { request.readLine()?.takeIf { it.isNotEmpty() } }.toList()
            val agent = headers.firstOrNull { it.substringBefore(':').trim().equals("User-Agent", ignoreCase = true) }?.substringAfter(':')?.trim()
            // Kept before it is answered, so a request is there by the time its answer is read.
            requests += Request(Regex("""[?&]t=([a-z]+)""").find(line)?.groupValues?.get(1) ?: "?", agent)
            val body = (if ("t=caps" in line) CAPS else results).toByteArray()
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: application/xml; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(body)
                flush()
            }
        }

        override fun close() = server.close()

        private companion object {
            const val CAPS = """<?xml version="1.0" encoding="UTF-8"?><caps><server version="1.0" title="Agent"/><limits max="100" default="100"/>""" +
                """<searching><search available="yes" supportedParams="q"/><tv-search available="yes" supportedParams="q,season,ep"/><movie-search available="no"/></searching>""" +
                """<categories><category id="5000" name="TV"><subcat id="5040" name="HD"/></category></categories></caps>"""
        }
    }

    private companion object {
        val FIELDS = listOf("Name", "API URL", "API key", "User-Agent")
        /** An invented product token, no real app's. */
        const val AGENT = "ExampleReader/2.1"
        const val NAME = "Agent (User-Agent device test)"
        /** Invented words, and an invented show's release for them. */
        const val WORDS = "harbor lights"
        const val RELEASE = "Harbor.Lights.S01E01.1080p.WEB-DL.AAC2.0.H.264-KESTREL"
        /** How long the debug build's start may take to save its settings. */
        const val SEEDING_MS = 30_000L
        /** A search's wait: seconds, where the search's cache would keep an answer for 15 minutes. */
        const val SEARCH_MS = 20_000L
    }
}
