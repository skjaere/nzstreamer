package app.nzstreamer.plugin.sportarr

import app.nzstreamer.plugin.sportarr.source.ListReader
import app.nzstreamer.plugin.sportarr.source.LoopbackHttp
import app.nzstreamer.plugin.sportarr.source.SportarrClient
import app.nzstreamer.plugin.sportarr.source.loopbackSafety
import io.skjaere.usenet.mockseed.FakeSportarrServer
import io.skjaere.usenet.mockseed.SportsCatalog
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The plugin's log lines (L653, L660): they reach the app's Logs page, which users share, so they say counts, durations
 * and kinds, and never the key, the address or its host, an event's or a league's title, or anything Sportarr sends that
 * the plugin never declares (the canary values of the fake's every event and league). The test-only capturing provider
 * records every line of the plugin's loggers while Test answers and a refresh reads the fake Sportarr's library.
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
}
