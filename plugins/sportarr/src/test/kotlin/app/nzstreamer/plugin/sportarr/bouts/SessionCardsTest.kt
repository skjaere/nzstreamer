package app.nzstreamer.plugin.sportarr.bouts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.nzstreamer.plugin.sportarr.Session
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.article
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.index
import app.nzstreamer.plugin.sportarr.source.MapSecrets
import app.nzstreamer.plugin.sportarr.source.loopbackSafety
import io.skjaere.usenet.plugin.AddressSafety
import io.skjaere.usenet.plugin.Found
import io.skjaere.usenet.plugin.ItemReleases
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.PluginHost
import io.skjaere.usenet.plugin.PluginHttp
import io.skjaere.usenet.plugin.PluginPlay
import io.skjaere.usenet.plugin.PluginSearch
import io.skjaere.usenet.plugin.PluginSecrets
import io.skjaere.usenet.plugin.PluginWatch
import io.skjaere.usenet.plugin.WatchInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A session's fight cards as the plugin makes them (L-B12's base, L-B5's folders): read from the Wikipedia base the session
 * was given, in the host's scope; the cards kept in the plugin's own folder, the index's rows in its cache folder.
 */
class SessionCardsTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a session's cards are read from its base, kept in the plugin's folder, the index in its cache`() = runBlocking<Unit> {
        val wiki = TestWiki()
        wiki.pages.remove("/wiki/UFC_999")
        wiki.pages["/wiki/List_of_UFC_events"] =
            index(scheduled = emptyList(), past = listOf(IndexEntry("UFC 999: Quessel vs. Halde", "/wiki/UFC_999_(Kestrel)", "Nov 21, 2026")))
        wiki.pages["/wiki/UFC_999_(Kestrel)"] = article(canonical = "https://en.wikipedia.org/wiki/UFC_999_(Kestrel)")
        val host = Host(wiki.http, File(dir, "data"), File(dir, "cache"))
        try {
            val session = Session(host, WIKI_BASE)
            withTimeout(WAIT) { withContext(host.main) { session.cards.opened(UFC_999_EVENT) }.join() }
            assertEquals(CardState.Bouts(Fixtures.UFC_999_CARD.copy(article = "UFC 999 (Kestrel)")), session.cards.state(UFC_999_EVENT).first())
            assertEquals(
                listOf("/wiki/UFC_999", "/wiki/UFC_999:_Quessel_vs._Halde", "/wiki/List_of_UFC_events", "/wiki/UFC_999_(Kestrel)"),
                wiki.asked,
            )
            assertTrue(wiki.http.requests.all { it.url.startsWith("$WIKI_BASE/wiki/") })
            assertEquals(listOf(KeptCards.NAME), host.dataDir.list()!!.toList())
            assertEquals(listOf(IndexFile.NAME), host.cacheDir.list()!!.toList())
            assertIs<CardState.Bouts>(session.cards.state(UFC_999_EVENT).first())
        } finally {
            host.close()
        }
    }

    /** A host for one session: Wikipedia through [http], its folders, and a scope on a thread of its own, as the main one is. */
    private class Host(override val http: PluginHttp, override val dataDir: File, override val cacheDir: File) : PluginHost {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        override val id = "sportarr"
        override val scope = CoroutineScope(SupervisorJob() + main)
        override val secrets: PluginSecrets = MapSecrets()
        override val watch: PluginWatch = object : PluginWatch {
            override fun observe(keys: Collection<String>): Flow<Map<String, WatchInfo>> = flowOf(emptyMap())

            override suspend fun mark(items: List<Pair<String, String>>, watched: Boolean) = Unit
        }
        override val search = object : PluginSearch {
            override fun search(words: String, runtimeMinutes: Int?, fresh: Boolean): Flow<Found> = emptyFlow()
        }
        override val play = object : PluginPlay {
            override fun play(item: PlayItem, releases: suspend () -> ItemReleases) = Unit

            @Composable
            override fun PlayOnButton(modifier: Modifier) = Unit
        }

        override fun addressSafety(url: String): AddressSafety = loopbackSafety(url)

        fun close() {
            scope.cancel()
            main.close()
        }
    }

    private companion object {
        const val WAIT = 20_000L
    }
}
