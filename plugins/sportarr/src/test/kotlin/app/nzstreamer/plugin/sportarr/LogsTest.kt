package app.nzstreamer.plugin.sportarr

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
import io.skjaere.usenet.plugin.IndexerLine
import io.skjaere.usenet.plugin.PluginSearch
import io.skjaere.usenet.plugin.ReleaseInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
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
 * The plugin's log lines (L653, L660): they reach the app's Logs page, which users share, so they say counts, durations
 * and kinds, and never the key, the address or its host, an event's or a league's title, a release's name, or anything
 * Sportarr sends that the plugin never declares (the canary values of the fake's every event and league). The test-only
 * capturing provider records every line of the plugin's loggers while Test answers, a refresh reads the fake Sportarr's
 * library and the runner judges the catalogue's names.
 */
class LogsTest {
    private val anchor = LocalDate.of(2026, 11, 22)
    private val catalog = SportsCatalog.load()
    private val fake = FakeSportarrServer(catalog, anchor, port = 0)
    private val http = LoopbackHttp()
    private val address = "http://127.0.0.1:${fake.port}"

    @AfterEach
    fun stop() = fake.close()

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
        assertClean(lines, *(releases.map { it.name } + asked).toTypedArray())
    }

    private companion object {
        const val INDEXER = "Sports indexer"
    }
}
