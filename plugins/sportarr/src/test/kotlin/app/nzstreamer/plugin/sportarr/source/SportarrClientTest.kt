package app.nzstreamer.plugin.sportarr.source

import app.nzstreamer.plugin.sportarr.CapturedLogs
import app.nzstreamer.plugin.sportarr.Logged
import io.skjaere.usenet.plugin.AddressSafety
import io.skjaere.usenet.plugin.HttpAnswer
import io.skjaere.usenet.plugin.PluginHttpException.Kind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L640 against a fake of the host's HTTP that records every request: Test asks Sportarr's status, then its leagues, and
 * answers in L640's order; the version is compared with 4.1.1 by its first three parts' leading digits; the key goes in
 * X-Api-Key alone, never in an address or a log line; every request keeps L641's bound; the address keeps its URL base,
 * loses one trailing slash and is refused with a query or a fragment; and nothing is sent until Test or a read asks.
 */
class SportarrClientTest {
    private val status = "$BASE/api/system/status"
    private val leagues = "$BASE/api/leagues"

    private fun client(http: RecordingHttp, address: String = BASE, key: String = KEY) = SportarrClient(http, address, key, ::loopbackSafety)

    /** Sportarr as Test meets it: [status] for the status, [leagues] for the leagues. */
    private fun sportarr(
        status: () -> HttpAnswer = { answer(200, statusBody("4.1.12.1122")) },
        leagues: () -> HttpAnswer = { answer(200, "[]") },
    ) = RecordingHttp { request ->
        when {
            request.url.endsWith("/api/system/status") -> status()
            request.url.endsWith("/api/leagues") -> leagues()
            else -> answer(404)
        }
    }

    private fun test(http: RecordingHttp, address: String = BASE): TestAnswer = runBlocking { client(http, address).test() }

    @Test
    fun `Test asks the status, then the leagues, and says Connected with the version as sent`() {
        val http = sportarr()

        assertEquals(TestAnswer.Connected("4.1.12.1122"), test(http))
        assertEquals(listOf(status, leagues), http.requests.map { it.url })
    }

    @Test
    fun `the status's answers, in L640's order`() {
        val cases = listOf<Pair<() -> HttpAnswer, TestAnswer>>(
            { noAnswer(Kind.Unreachable) } to TestAnswer.Unreachable(HOST_PORT),
            { noAnswer(Kind.Timeout) } to TestAnswer.Unreachable(HOST_PORT),
            { answer(401) } to TestAnswer.Refused,
            { answer(403, "Forbidden") } to TestAnswer.Refused,
            { answer(200, statusBody("4.0.998")) } to TestAnswer.TooOld("4.0.998"),
            { answer(500) } to TestAnswer.ServerError(500),
            { answer(503, "<html>Service Unavailable</html>") } to TestAnswer.ServerError(503),
            { answer(404) } to TestAnswer.NotSportarr,
            { answer(200, "<html><body>A router's login page</body></html>") } to TestAnswer.NotSportarr,
            { answer(200, """{"appName":"Kestrel","version":"5.0.0"}""") } to TestAnswer.NotSportarr,
            { answer(200, """{"version":"4.1.12.1122"}""") } to TestAnswer.NotSportarr,
            { answer(200, "[]") } to TestAnswer.NotSportarr,
            { answer(204) } to TestAnswer.NotSportarr,
            { answer(302) } to TestAnswer.NotSportarr,
            { noAnswer(Kind.RedirectRefused) } to TestAnswer.NotSportarr,
            { noAnswer(Kind.TooLarge) } to TestAnswer.NotSportarr,
            { noAnswer(Kind.InvalidAddress) } to TestAnswer.InvalidAddress,
        )
        cases.forEachIndexed { index, (status, expected) ->
            val http = sportarr(status = status)
            assertEquals(expected, test(http), "case $index")
            assertEquals(listOf(this.status), http.requests.map { it.url }, "no second request after a failed first, case $index")
        }
    }

    @Test
    fun `Sportarr's name is read whatever its case`() {
        assertEquals(TestAnswer.Connected("4.1.12.1122"), test(sportarr(status = { answer(200, """{"appName":"sportarr","version":"4.1.12.1122"}""") })))
        assertEquals(TestAnswer.Connected("4.1.12.1122"), test(sportarr(status = { answer(200, """{"appName":"SPORTARR","version":"4.1.12.1122"}""") })))
    }

    @Test
    fun `the leagues' answers, by the same rules`() {
        val cases = listOf<Pair<() -> HttpAnswer, TestAnswer>>(
            { answer(200, """[{"id":1,"name":"Premier League","sport":"Soccer","monitored":true}]""") } to TestAnswer.Connected("4.1.12.1122"),
            { answer(401) } to TestAnswer.Refused,
            { answer(403) } to TestAnswer.Refused,
            { answer(500) } to TestAnswer.ServerError(500),
            { answer(502) } to TestAnswer.ServerError(502),
            { noAnswer(Kind.Unreachable) } to TestAnswer.Unreachable(HOST_PORT),
            { noAnswer(Kind.Timeout) } to TestAnswer.Unreachable(HOST_PORT),
            { answer(404) } to TestAnswer.NotSportarr,
            { answer(200, """{"records":[]}""") } to TestAnswer.NotSportarr,
            { answer(200, "<html></html>") } to TestAnswer.NotSportarr,
            { noAnswer(Kind.TooLarge) } to TestAnswer.NotSportarr,
            { noAnswer(Kind.RedirectRefused) } to TestAnswer.NotSportarr,
        )
        cases.forEachIndexed { index, (leagues, expected) ->
            val http = sportarr(leagues = leagues)
            assertEquals(expected, test(http), "case $index")
            assertEquals(listOf(status, this.leagues), http.requests.map { it.url }, "case $index")
        }
    }

    @Test
    fun `the version must reach 4, 1, 1 by its first three parts' leading digits`() {
        val fine = listOf("4.1.1", "4.1.12.1122", "4.1.12.0-abc1234", "4.1.12.0-local", "4.1.2", "4.10.0", "5.0.0", "10.0.0", "4.1.1-rc1", " 4.1.12.1122")
        val tooOld = listOf("4.1.0.1110", "4.1.0", "4.0.998", "3.9.9", "4.1", "4", "", "v4.1.12", "four.one.twelve", "4..12", "4.1.x")
        fine.forEach { version ->
            assertEquals(TestAnswer.Connected(version), test(sportarr(status = { answer(200, statusBody(version)) })), version)
        }
        tooOld.forEach { version ->
            assertEquals(TestAnswer.TooOld(version), test(sportarr(status = { answer(200, statusBody(version)) })), version)
        }
        assertEquals(TestAnswer.TooOld(null), test(sportarr(status = { answer(200, """{"appName":"Sportarr"}""") })))
    }

    @Test
    fun `the key goes in X-Api-Key alone, never in an address or a log line`() {
        assertIs<CapturedLogs.Factory>(LoggerFactory.getILoggerFactory(), "the tests' capturing provider is the only one")
        val http = RecordingHttp { request ->
            when {
                request.url.endsWith("/api/system/status") -> answer(200, statusBody("4.1.12.1122"))
                request.url.endsWith("/api/leagues") -> answer(200, "[]")
                else -> answer(200, """{"page":1,"pageSize":1000,"totalRecords":0,"totalPages":0,"records":[]}""")
            }
        }
        val mark = Logged.mark()

        runBlocking {
            val client = client(http)
            client.test()
            client.leagues()
            client.events(7, 1)
            // Each answer Test can give, so each of its log lines is seen.
            listOf(401, 500, 404).forEach { code -> client(RecordingHttp { answer(code) }).test() }
            client(RecordingHttp { noAnswer(Kind.Unreachable) }).test()
            client(RecordingHttp { answer(200, statusBody("4.0.998")) }).test()
            client(RecordingHttp { error("never asked") }, address = "$BASE/?apikey=$KEY").test()
        }

        assertEquals(4, http.requests.size)
        http.requests.forEach { request ->
            assertEquals(mapOf("X-Api-Key" to KEY), request.headers, request.url)
            assertFalse(KEY in request.url, request.url)
        }
        val lines = Logged.pluginLinesSince(mark)
        assertTrue(lines.isNotEmpty(), "Test logs its answer's kind")
        lines.forEach { line ->
            listOf(KEY, "127.0.0.1", BASE, "CANARY-os").forEach { secret -> assertFalse(secret in line.words, "a log line holds $secret: ${line.words}") }
        }
    }

    @Test
    fun `every request keeps its bound`() {
        val http = sportarr()
        runBlocking {
            val client = client(http)
            client.test()
            client.events(7, 3)
        }
        assertEquals(
            listOf(status to 64 * 1024, leagues to 4 * 1024 * 1024, "$BASE/api/leagues/7/events?page=3&pageSize=1000" to 8 * 1024 * 1024),
            http.requests.map { it.url to it.maxBytes },
        )
    }

    @Test
    fun `a URL base is kept, and one trailing slash dropped`() {
        val cases = mapOf(
            "http://127.0.0.1:1867/sportarr" to "http://127.0.0.1:1867/sportarr",
            "http://127.0.0.1:1867/sportarr/" to "http://127.0.0.1:1867/sportarr",
            "http://127.0.0.1:1867/" to "http://127.0.0.1:1867",
            "http://127.0.0.1:1867" to "http://127.0.0.1:1867",
            "http://127.0.0.1:1867/apps/sportarr//" to "http://127.0.0.1:1867/apps/sportarr/",
            "  https://localhost:8443/sportarr/ \n" to "https://localhost:8443/sportarr",
        )
        cases.forEach { (typed, base) ->
            val http = sportarr()
            runBlocking {
                val client = client(http, typed)
                assertEquals(TestAnswer.Connected("4.1.12.1122"), client.test(), typed)
                client.events(12, 1)
            }
            assertEquals(
                listOf("$base/api/system/status", "$base/api/leagues", "$base/api/leagues/12/events?page=1&pageSize=1000"),
                http.requests.map { it.url },
                typed,
            )
        }
    }

    @Test
    fun `an address with a query or a fragment, or one that isn't http, is refused before anything is sent`() {
        val refused = listOf(
            "$BASE/?apikey=$KEY", "$BASE?", "$BASE/#leagues", "$BASE/sportarr#", "127.0.0.1:1867", "ftp://127.0.0.1:1867", "", "   ",
            "http://", "http://127.0.0.1:1867 /sportarr",
        )
        refused.forEach { typed ->
            val http = RecordingHttp { error("never asked") }
            runBlocking {
                val client = client(http, typed)
                assertNull(client.address, typed)
                assertEquals(TestAnswer.InvalidAddress, client.test(), typed)
                assertEquals(Reply.NoAnswer(Kind.InvalidAddress), client.leagues(), typed)
                assertEquals(Reply.NoAnswer(Kind.InvalidAddress), client.events(1, 1), typed)
            }
            assertEquals(emptyList(), http.requests, typed)
            assertEquals(AddressSafety.Invalid, SportarrAddress.safety(typed, ::loopbackSafety), typed)
        }
        // The help line follows the host's judgement of an address the plugin reads.
        assertEquals(AddressSafety.LocalHttp, SportarrAddress.safety("$BASE/sportarr", ::loopbackSafety))
        assertEquals(AddressSafety.PublicHttp, SportarrAddress.safety("http://203.0.113.7:1867") { AddressSafety.PublicHttp })
        assertEquals(AddressSafety.Encrypted, SportarrAddress.safety("https://localhost:8443", ::loopbackSafety))
        assertEquals(AddressSafety.Invalid, SportarrAddress.safety("http://127.0.0.1:1867") { AddressSafety.Invalid })
    }

    @Test
    fun `no answer names the host and port`() {
        val cases = mapOf(
            "http://127.0.0.1:1867/sportarr" to "127.0.0.1:1867",
            "http://localhost/sportarr" to "localhost",
            "https://[::1]:8443" to "[::1]:8443",
            "http://LOCALHOST:1867" to "localhost:1867",
        )
        cases.forEach { (typed, hostPort) ->
            assertEquals(TestAnswer.Unreachable(hostPort), test(RecordingHttp { noAnswer(Kind.Unreachable) }, typed), typed)
        }
    }

    @Test
    fun `nothing is sent before Test or a read`() {
        val http = sportarr()
        val secrets = MapSecrets()
        runBlocking {
            Connection(secrets).save(BASE, KEY)
            val client = client(http)
            assertEquals("127.0.0.1:1867", client.address?.hostPort)
        }
        assertEquals(emptyList(), http.requests)

        runBlocking { client(http).test() }
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `the address and the key are the plugin's two secrets, the address trimmed`() {
        val secrets = MapSecrets()
        runBlocking {
            val connection = Connection(secrets)
            assertNull(connection.saved())

            assertTrue(connection.save("  $BASE/sportarr \n", " $KEY\n"))
            assertEquals(mapOf("url" to "$BASE/sportarr", "key" to KEY), secrets.stored.value)
            val saved = connection.saved()
            assertEquals("$BASE/sportarr", saved?.address)
            assertEquals(KEY, saved?.key)
            assertFalse(KEY in saved.toString(), "a saved key's words never show it")

            val puts = secrets.puts
            assertFalse(connection.save("$BASE/sportarr", KEY), "the same address and key change nothing")
            assertEquals(puts, secrets.puts)
            assertTrue(connection.save("$BASE/sportarr", "another-key"))
            assertTrue(connection.save(BASE, "another-key"))

            secrets.put("key", null)
            assertNull(connection.saved(), "an address without a key isn't set up")
        }
    }

    private companion object {
        const val BASE = "http://127.0.0.1:1867"
        const val HOST_PORT = "127.0.0.1:1867"
        const val KEY = "kestrel0123456789abcdef0123456789"

        fun statusBody(version: String) =
            """{"appName":"Sportarr","instanceName":"Sportarr","version":"$version","isDebug":false,"osName":"CANARY-os"}"""
    }
}
