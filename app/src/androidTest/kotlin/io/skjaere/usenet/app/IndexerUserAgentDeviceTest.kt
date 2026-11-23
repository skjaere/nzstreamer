package io.skjaere.usenet.app

import android.app.Activity
import androidx.activity.ComponentActivity
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
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.settings.USER_AGENT_CHARACTERS
import io.skjaere.usenet.app.settings.USER_AGENT_HELP
import io.skjaere.usenet.app.settings.USER_AGENT_TOO_LONG
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
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
 * their keys untouched) and the TMDB key taken away, so a Search asks no one else; all put back once the app's screens
 * are closed, the test's indexer deleted with its key and its searches forgotten.
 */
@RunWith(AndroidJUnit4::class)
class IndexerUserAgentDeviceTest {
    val compose = createComposeRule()

    /**
     * Around [compose]: the settings are taken before its activity opens and put back once it has closed it, the app's
     * view models cleared with it. Inside it, as @After is, the TMDB key would be back while the Search screen still
     * showed its words, which it asks TMDB about as soon as there is a key, and Home its titles.
     */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ -> aroundTheScreens(base) }).around(compose)

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val settings = container.settings
    private val library = container.library
    private val home = HomeContentShown()
    private val indexer = AgentIndexer()
    private lateinit var saved: AppSettings
    private var tmdbKey: String? = null
    private var keyTaken = false
    private var searchedBefore: Set<String>? = null
    private lateinit var nav: NavHostController

    /** [setUp], then [screens], then [restore] whether or not they passed, its failure kept with theirs. */
    private fun aroundTheScreens(screens: Statement) = object : Statement() {
        override fun evaluate() {
            try {
                setUp()
                screens.evaluate()
            } catch (e: Throwable) {
                try {
                    restore()
                } catch (after: Throwable) {
                    e.addSuppressed(after)
                }
                throw e
            }
            restore()
        }
    }

    private fun setUp() = runBlocking<Unit> {
        // The debug build's own settings saved first, as the ones put back after are read after them.
        withTimeout(SEEDING_MS) { container.devSettingsSaved.await() }
        // Before Home's switch is set, so the settings put back, and checked after, are as the test found them.
        saved = settings.settings.first()
        home.show()
        tmdbKey = settings.tmdbKey()
        searchedBefore = library.recentSearches.first().map { it.key }.toSet()
        // Hidden, not deleted: deleting would remove their keys from the secret store. With no service account on, no
        // torrent indexer is searched either.
        settings.restore(saved.copy(torrentIndexers = saved.torrentIndexers.map { it.copy(deleted = true) }, services = saved.services.map { it.copy(deleted = true) }))
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        // Search asks TMDB nothing without a key.
        keyTaken = true
        settings.setTmdbKey(null)
    }

    private fun restore() = runBlocking<Unit> {
        everyStep(
            { indexer.close() },
            // None by now: a screen still open would ask TMDB once the key is back, Search for its words and Home for its
            // titles. Were one open, it is closed first, and the test fails for it.
            { assertEquals("the test's screens still open as the settings went back", emptyList<String>(), closeOpenScreens()) },
            // Each its own step, so one that fails leaves the rest to run; each skipped if the setup never got to it.
            { if (::saved.isInitialized) settings.indexers.first().filter { i -> saved.indexers.none { it.id == i.id } }.forEach { settings.deleteIndexer(it.id) } },
            { if (::saved.isInitialized) settings.restore(saved) },
            { home.restore() },
            { if (keyTaken) settings.setTmdbKey(tmdbKey) },
            { searchedBefore?.let { before -> library.recentSearches.first().filter { it.key !in before }.forEach { library.removeSearch(it.key) } } },
            { if (::saved.isInitialized) assertEquals("the settings as the test found them", saved, settings.settings.first()) },
        )
    }

    /** Compose's activity, which holds the app's screens and their view models, while it is open. */
    private fun openScreens(): List<Activity> = onMainThread {
        OPEN.flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.filter { it.javaClass == ComponentActivity::class.java }.distinct()
    }

    /** Finishes the test's screens still open, if any, and waits for them to close: says which were. */
    private suspend fun closeOpenScreens(): List<String> {
        val open = openScreens()
        open.forEach { activity -> onMainThread { activity.finish() } }
        waitFor("the test's screens closed", SCREENS_MS) { Unit.takeIf { openScreens().isEmpty() } }
        return open.map { it.javaClass.simpleName }
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
        // Its row on the list, which shows once the save is done; not the form's Name field, which holds the same words.
        waitFor(hasText(NAME) and hasClickAction() and !hasSetTextAction())
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
        /** How long the test's screens may take to close. */
        const val SCREENS_MS = 30_000L
        /** An activity's stages before it is destroyed. */
        val OPEN = arrayOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
    }
}
