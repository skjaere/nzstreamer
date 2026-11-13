package io.skjaere.usenet.debrid

import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ServiceHttpTest {
    private val clock = MutableClock()
    private val gate = ServiceGate(clock)
    private val lines = mutableListOf<String>()

    private fun TestScope.calls(api: FakeApi) = ServiceCalls(
        ServiceKind.TorBox, api.http, ServiceRateLimit(Duration.ofMillis(250)) { testScheduler.currentTime }, gate, lines::add, clock,
    )

    private suspend fun ServiceCalls.ask(gated: Boolean = true, read: (Answer) -> String = { it.text() }): String =
        call("user/me", gated = gated, said = { "read" }, request = { url("https://api.example.org/v1/api/user/me") }, read = read)

    @Test
    fun `each request has the app's user agent, asks for json and is given the timeouts`() = runTest {
        val api = FakeApi(this) { json("{}") }
        calls(api).ask()
        val request = api.requests.single()
        assertEquals("NZStreamer/0.1", request.headers[HttpHeaders.UserAgent])
        assertEquals("application/json", request.headers[HttpHeaders.Accept])
        val timeouts = request.getCapabilityOrNull(HttpTimeoutCapability)
        assertEquals(8_000, timeouts?.connectTimeoutMillis)
        assertEquals(15_000, timeouts?.requestTimeoutMillis)
    }

    @Test
    fun `an answer is read, and its line names the service, the call and what it said`() = runTest {
        val api = FakeApi(this) { json("""{"x":1}""") }
        assertEquals("""{"x":1}""", calls(api).ask())
        val line = lines.single()
        assertContains(line, "debrid: TorBox user/me: read")
    }

    @Test
    fun `a closed gate answers its error without a request, and a key's test is asked all the same`() = runTest {
        val api = FakeApi(this) { json("{}") }
        gate.record(ServiceError.Limit(Duration.ofMinutes(20)))
        val closed = assertFailsWith<ServiceException> { calls(api).ask() }
        assertEquals(ServiceError.Limit(Duration.ofMinutes(20)), closed.error)
        assertEquals(0, api.requests.size)
        assertContains(lines.single(), "not asked")

        calls(api).ask(gated = false)
        assertEquals(1, api.requests.size)
        assertNull(gate.closed(), "its success opened the gate")
    }

    @Test
    fun `an answer over 2 MB is the service's fault, with or without its length`() = runTest {
        val big = ByteArray(2 * 1024 * 1024 + 1) { 'a'.code.toByte() }
        val sized = FakeApi(this) { respond(big, HttpStatusCode.OK) }
        assertEquals(ServiceError.Down, assertFailsWith<ServiceException> { calls(sized).ask() }.error)
        val streamed = FakeApi(this) { respond(ByteReadChannel(big), HttpStatusCode.OK, headersOf()) }
        assertEquals(ServiceError.Down, assertFailsWith<ServiceException> { calls(streamed).ask() }.error)
        // Two megabytes exactly read.
        val atCap = FakeApi(this) { respond(ByteReadChannel(big.copyOf(big.size - 1)), HttpStatusCode.OK, headersOf()) }
        assertEquals(big.size - 1, calls(atCap).ask().length)
    }

    @Test
    fun `a 429 waits out its Retry-After in the limiter and is asked again`() = runTest {
        var answered = 0
        val api = FakeApi(this) { if (answered++ < 2) json("{}", HttpStatusCode.TooManyRequests, "Retry-After" to "10") else json("ok") }
        assertEquals("ok", calls(api).ask())
        assertEquals(listOf(0L, 10_000L, 20_000L), api.times.toList())
        assertNull(gate.closed())
    }

    @Test
    fun `once a call's 429s have held it 30 s it is the account's limit, with the last wait`() = runTest {
        val api = FakeApi(this) { json("{}", HttpStatusCode.TooManyRequests, "Retry-After" to "20") }
        val limited = assertFailsWith<ServiceException> { calls(api).ask() }
        assertEquals(ServiceError.Limit(Duration.ofSeconds(20)), limited.error)
        assertEquals(listOf(0L, 20_000L), api.times.toList(), "20 s waited, 20 more would pass 30")
        assertEquals(ServiceError.Limit(Duration.ofSeconds(20)), gate.closed())
    }

    @Test
    fun `a 429 asking for longer than that is the limit at once, and one without a wait is waited a little`() = runTest {
        val long = FakeApi(this) { json("{}", HttpStatusCode.TooManyRequests, "Retry-After" to "1200") }
        assertEquals(ServiceError.Limit(Duration.ofMinutes(20)), assertFailsWith<ServiceException> { calls(long).ask() }.error)
        assertEquals(1, long.requests.size)

        gate.reset()
        var answered = 0
        val bare = FakeApi(this) { if (answered++ == 0) json("{}", HttpStatusCode.TooManyRequests) else json("ok") }
        val started = testScheduler.currentTime
        assertEquals("ok", calls(bare).ask())
        assertEquals(listOf(started, started + 5_000), bare.times.toList())
    }

    /** A wait past any end (a broken answer's) is a day: still the account's limit, its gate closed that long. */
    @Test
    fun `a 429 naming a wait past any end is the limit for a day`() = runTest {
        val api = FakeApi(this) { json("{}", HttpStatusCode.TooManyRequests, "Retry-After" to Long.MAX_VALUE.toString()) }
        assertEquals(ServiceError.Limit(Duration.ofDays(1)), assertFailsWith<ServiceException> { calls(api).ask() }.error)
        assertEquals(ServiceError.Limit(Duration.ofDays(1)), gate.closed())
    }

    @Test
    fun `a network failure is down, and its words hold neither the key nor the address`() = runTest {
        val api = FakeApi(this) { throw IOException("failed: https://api.example.org/v1/api/torrents/requestdl?token=SECRETKEY42") }
        val down = assertFailsWith<ServiceException> { calls(api).ask() }
        assertEquals(ServiceError.Down, down.error)
        assertNull(down.cause)
        val words = down.message + lines.joinToString()
        assertFalse("SECRETKEY42" in words)
        assertFalse("api.example.org" in words)
        assertContains(lines.single(), "IOException")
    }

    @Test
    fun `a client's failure closes the gate for a limit or a refused key, never for one refused release`() = runTest {
        val api = FakeApi(this) { json("{}") }
        val calls = calls(api)
        val tooLarge = assertFailsWith<ServiceException> { calls.ask { throw Failed(ServiceError.Refused(tooLarge = true), "DOWNLOAD_TOO_LARGE") } }
        assertEquals(ServiceError.Refused(tooLarge = true), tooLarge.error)
        assertNull(gate.closed())
        assertContains(lines.last(), "DOWNLOAD_TOO_LARGE")

        assertFailsWith<ServiceException> { calls.ask { throw Failed(ServiceError.KeyRefused, "BAD_TOKEN") } }
        assertEquals(ServiceError.KeyRefused, gate.closed())
    }

    @Test
    fun `a wait a failed answer asked for holds the account's next call`() = runTest {
        val api = FakeApi(this) { json("{}") }
        val calls = calls(api)
        assertFailsWith<ServiceException> { calls.ask { throw Failed(ServiceError.Limit(Duration.ofSeconds(60)), "rate_limit_reached", holdFor = Duration.ofSeconds(60)) } }
        gate.reset()
        calls.ask()
        assertEquals(listOf(0L, 60_000L), api.times.toList())
    }

    @Test
    fun `retry-after reads as seconds or as a date, and nothing else`() {
        val now = Instant.parse("2026-11-05T10:00:00Z")
        assertEquals(Duration.ofSeconds(30), ServiceCalls.retryAfter("30", now))
        assertEquals(Duration.ofMinutes(2), ServiceCalls.retryAfter("Thu, 05 Nov 2026 10:02:00 GMT", now))
        assertNull(ServiceCalls.retryAfter("Thu, 05 Nov 2026 09:00:00 GMT", now), "already over")
        assertNull(ServiceCalls.retryAfter("soon", now))
        assertNull(ServiceCalls.retryAfter(null, now))
        // A day at most: a limit's wait is kept as a time, which has an end.
        listOf("86401", "99999999999999999", Long.MAX_VALUE.toString(), "Fri, 31 Dec 9999 23:59:59 GMT").forEach {
            assertEquals(Duration.ofDays(1), ServiceCalls.retryAfter(it, now), "Retry-After: $it")
        }
    }

    @Test
    fun `a service's code is shown as a code, never as words`() {
        assertEquals("DOWNLOAD_TOO_LARGE", ServiceCalls.codeOf("DOWNLOAD_TOO_LARGE"))
        assertEquals("rate_limit_reached", ServiceCalls.codeOf("rate_limit_reached"))
        assertEquals("keyabcd", ServiceCalls.codeOf("key=abcd"))
        assertEquals(40, ServiceCalls.codeOf("A".repeat(80))?.length)
        assertNull(ServiceCalls.codeOf(""))
        assertNull(ServiceCalls.codeOf(null))
    }
}
