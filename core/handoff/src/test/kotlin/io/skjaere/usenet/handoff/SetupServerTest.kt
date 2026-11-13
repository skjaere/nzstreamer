package io.skjaere.usenet.handoff

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The setup server over real sockets on loopback: each rule of the design, one test each. */
class SetupServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val token = SetupToken.generate()
    private val events = LinkedBlockingQueue<SetupEvent>()
    private val servers = mutableListOf<SetupServer>()
    private val sockets = mutableListOf<Socket>()
    private val tester = FakeTester()
    /** A clock the lifetime tests move by hand. */
    private val now = AtomicLong(1_000_000)

    private val main = SavedProvider("prov-1", "Main provider", "news.example.invalid", 563, true, "someone", 20, true, SavedSecret.Saved)
    private val indexerA = SavedIndexer("idx-1", "Indexer A", "https://indexer-a.example.invalid/api", false, true, SavedSecret.Saved)
    private val seed = SetupSeed(listOf(main), listOf(indexerA))
    private val p1 = SetupSlot(SetupSection.Provider, 1)
    private val i1 = SetupSlot(SetupSection.Indexer, 1)

    /** A test from the page, as the tester was asked for it: the card, and the future the server waits on. */
    private class Call(val item: SetupTestItem, val future: Held, val thread: String)

    /**
     * A test's future that says when the server first waits on it. Only then does the server hold it: a close or a newer
     * test before that can't cancel it yet, and the server cancels it itself once the tester hands it over.
     */
    private class Held : CompletableFuture<SetupResult>() {
        val waitedOn = CountDownLatch(1)

        override fun get(): SetupResult {
            waitedOn.countDown()
            return super.get()
        }

        override fun get(timeout: Long, unit: TimeUnit): SetupResult {
            waitedOn.countDown()
            return super.get(timeout, unit)
        }
    }

    /**
     * The app's tester: answers at once with [result] of the card, or, while [hold], leaves the test running. While
     * [fails] it throws instead of starting the test; while [failsLater] the test it starts fails.
     */
    private inner class FakeTester : SetupTester {
        val calls = LinkedBlockingQueue<Call>()
        @Volatile var hold = false
        @Volatile var fails = false
        @Volatile var failsLater = false

        override fun test(item: SetupTestItem): Future<SetupResult> {
            if (fails) throw IllegalStateException("the tester broke")
            val future = Held()
            calls.put(Call(item, future, Thread.currentThread().name))
            when {
                failsLater -> future.completeExceptionally(IllegalStateException("the test broke at marker-host"))
                !hold -> future.complete(result(item))
            }
            return future
        }

        /** The next test the tester was asked for, once the server waits on it. */
        fun next(): Call {
            val call = assertNotNull(calls.poll(5, TimeUnit.SECONDS), "the tester wasn't asked")
            assertTrue(call.future.waitedOn.await(5, TimeUnit.SECONDS), "the server never waited on the test")
            return call
        }
    }

    /** A provider and a debrid service work, an indexer and a torrent indexer don't, a TMDB key isn't tried. */
    private fun result(item: SetupTestItem) = when (item) {
        is SetupTestItem.Provider -> SetupResult(SetupOutcome.Passed, "Connected and logged in to ${item.draft.host}")
        is SetupTestItem.Indexer -> SetupResult(SetupOutcome.Failed, "No answer within 15 s")
        is SetupTestItem.Service -> SetupResult(SetupOutcome.Passed, "Signed in as quay.viewer@example.invalid")
        is SetupTestItem.TorrentIndexer -> SetupResult(SetupOutcome.Failed, "No answer within 25 s")
        is SetupTestItem.Tmdb -> SetupResult(SetupOutcome.NotTested, "not this time.")
    }

    @AfterEach
    fun closeAll() {
        servers.forEach { it.close() }
        sockets.forEach { it.close() }
    }

    private fun server(
        limits: SetupLimits = SetupLimits(),
        mode: SetupMode = SetupMode.Wizard,
        seed: SetupSeed = SetupSeed(),
        allowPeer: (InetAddress) -> Boolean = { it.isLoopbackAddress },
        clock: (() -> Long)? = null,
    ): SetupServer {
        val server = if (clock == null) {
            SetupServer(loopback, token = token, mode = mode, seed = seed, limits = limits, allowPeer = allowPeer, tester = tester, onEvent = { events.put(it) })
        } else {
            SetupServer(loopback, token = token, mode = mode, seed = seed, clock = clock, limits = limits, allowPeer = allowPeer, tester = tester, onEvent = { events.put(it) })
        }
        return server.also { servers += it }
    }

    private fun start(limits: SetupLimits = SetupLimits(), mode: SetupMode = SetupMode.Wizard, seed: SetupSeed = SetupSeed()) = server(limits, mode, seed).start()

    /** The page a fresh session shows first. */
    private fun firstPage(mode: SetupMode = SetupMode.Wizard, seed: SetupSeed = SetupSeed()) = SetupPage.render(SetupFlow(mode, seed).show())

    private class Answer(val status: Int, val headers: List<Pair<String, String>>, val body: String) {
        /** The 303 that led here, for a POST followed to its page. */
        var redirect: Answer? = null

        fun header(name: String) = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
    }

    /** A connection from [from], connected but with nothing sent yet. */
    private fun connect(port: Int, from: String = "127.0.0.1") = Socket().also { socket ->
        sockets += socket
        socket.bind(InetSocketAddress(from, 0))
        socket.connect(InetSocketAddress(loopback, port), 2000)
        socket.soTimeout = 5000
    }

    /** Everything the server sends until it closes the connection (a reset counts as the end); null for nothing at all. */
    private fun answer(socket: Socket): Answer? {
        val raw = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val n = socket.getInputStream().read(buffer)
                if (n < 0) break
                raw.write(buffer, 0, n)
            }
        } catch (_: IOException) {
            // A reset after a quiet close.
        }
        val bytes = raw.toByteArray()
        if (bytes.isEmpty()) return null
        val text = String(bytes, Charsets.UTF_8)
        val head = text.substringBefore("\r\n\r\n")
        val lines = head.split("\r\n")
        val status = lines.first().split(' ')[1].toInt()
        val headers = lines.drop(1).map { it.substringBefore(": ") to it.substringAfter(": ") }
        return Answer(status, headers, text.substringAfter("\r\n\r\n"))
    }

    /**
     * Sends [request] whole and reads the answer. One turned away busy goes again: a connection gives its slot back a
     * moment after its answer was read, once its thread lets it go, and the request turned away was never read.
     */
    private fun send(port: Int, request: ByteArray, from: String = "127.0.0.1"): Answer? {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (true) {
            val answer = sendOnce(port, request, from)
            if (answer?.status != 503 || System.nanoTime() > until) return answer
            Thread.sleep(20)
        }
    }

    /** [send]'s one try, on a connection of its own. */
    private fun sendOnce(port: Int, request: ByteArray, from: String): Answer? {
        val socket = connect(port, from)
        try {
            socket.getOutputStream().write(request)
        } catch (_: IOException) {
            // The server may answer and close before taking it all.
        }
        return answer(socket).also { socket.close() }
    }

    private fun request(
        port: Int,
        method: String = "GET",
        target: String = "/${token.value}",
        host: String? = "127.0.0.1:$port",
        headers: List<String> = emptyList(),
        body: String? = null,
        contentType: String? = if (body != null) "application/x-www-form-urlencoded" else null,
    ): ByteArray = buildString {
        append("$method $target HTTP/1.1\r\n")
        host?.let { append("Host: $it\r\n") }
        contentType?.let { append("Content-Type: $it\r\n") }
        body?.let { append("Content-Length: ${it.toByteArray().size}\r\n") }
        headers.forEach { append(it).append("\r\n") }
        append("\r\n")
        body?.let { append(it) }
    }.toByteArray(Charsets.UTF_8)

    private fun get(port: Int, target: String = "/${token.value}", from: String = "127.0.0.1", headers: List<String> = emptyList()) =
        assertNotNull(send(port, request(port, target = target, headers = headers), from), "no answer")

    /** A POST and its answer as it comes, a 303 not followed. */
    private fun postOnly(port: Int, body: String, from: String = "127.0.0.1", headers: List<String> = emptyList(), target: String = "/${token.value}") =
        assertNotNull(send(port, request(port, method = "POST", target = target, body = body, headers = headers), from), "no answer")

    /** A POST as a browser makes it: a 303 is followed with a GET of where it points, from the same device. */
    private fun post(port: Int, body: String, from: String = "127.0.0.1", headers: List<String> = emptyList()): Answer {
        val first = postOnly(port, body, from, headers)
        if (first.status != 303) return first
        val location = first.header("Location").single()
        return get(port, location.substringBefore('#'), from).also { it.redirect = first }
    }

    private fun form(vararg fields: Pair<String, String>) = fields.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    /** Provider 1's card as the phone fills it in. */
    private val providerCard = arrayOf("p.name" to "", "p.host" to "news.example.invalid", "p.port" to "", "p.tls" to "on", "p.user" to "someone", "p.pass" to "pass word", "p.conns" to "")
    private val providerDraft = ProviderDraft("", "news.example.invalid", null, true, "someone", "pass word", null)

    private fun card(vararg changes: Pair<String, String>) = providerCard.map { field -> changes.firstOrNull { it.first == field.first } ?: field }.filterNot { it.second == "-" }.toTypedArray()

    /** The page's form at render [v], with [action] pressed and [fields] in it. */
    private fun pressed(v: Int, action: String, vararg fields: Pair<String, String>) = form("v" to "$v", "do" to action, *fields)

    private val testing = pressed(1, "test", *providerCard)

    /**
     * A request for a test of [body] on a connection of its own, not yet answered, once the tester was asked for it. One
     * turned away busy goes again, as [send]'s does.
     */
    private fun startTest(port: Int, body: String = testing): Socket {
        val asked = tester.calls.size
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            val socket = connect(port).also { it.getOutputStream().write(request(port, method = "POST", body = body)) }
            // Taken, the tester is asked before any answer comes; turned away, a 503 comes at once.
            while (tester.calls.size == asked && socket.getInputStream().available() == 0) {
                assertTrue(System.nanoTime() < until, "the tester wasn't asked")
                Thread.sleep(5)
            }
            if (tester.calls.size > asked) return socket
            assertEquals(503, answer(socket)?.status, "a test's request answered before the tester was asked")
            Thread.sleep(20)
        }
    }

    /** Events that come with every request from the owner, or every form read: tests that look for others skip them. */
    private fun quiet(event: SetupEvent) = event is SetupEvent.DeadlineMoved || event == SetupEvent.FormRead

    private fun nextEvent(): SetupEvent {
        while (true) {
            val event = assertNotNull(events.poll(5, TimeUnit.SECONDS), "no event")
            if (!quiet(event)) return event
        }
    }

    /** Every event, the quiet ones too. */
    private fun nextRawEvent(): SetupEvent = assertNotNull(events.poll(5, TimeUnit.SECONDS), "no event")

    /** No event comes but the quiet ones: the server sends them once an answer is out, well within this. */
    private fun assertNoEvents() {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(600)
        while (true) {
            val left = until - System.nanoTime()
            if (left <= 0) return
            val event = events.poll(left, TimeUnit.NANOSECONDS) ?: return
            assertTrue(quiet(event), "an event: $event")
        }
    }

    /**
     * The port refuses connections, within a moment: the listening socket closed by a connection's thread is let go
     * once the accept thread wakes, and the events may come first. Until then it may still take a connection, and reset
     * it as it closes. A refusal is told by its type: the text of a reset is the C library's, in the desktop's language.
     */
    private fun refused(port: Int) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var failed: SocketException? = null
        while (true) {
            try {
                Socket().use { it.connect(InetSocketAddress(loopback, port), 2000) }
            } catch (_: ConnectException) {
                return
            } catch (e: SocketException) {
                // Taken by the listener as it closed, then reset: it still took connections then, so try again. Any other
                // error comes out at the deadline.
                failed = e
            }
            assertTrue(System.nanoTime() < until, "the port still takes connections" + (failed?.let { "; the last error: $it" } ?: ""))
            Thread.sleep(20)
        }
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition()) {
            assertTrue(System.nanoTime() < until, what)
            Thread.sleep(20)
        }
    }

    @Test
    fun `the page with the right code has the security headers, and the connection closes after it`() {
        val port = start()
        val page = get(port, headers = listOf("Cookie: session=abc"))
        assertEquals(200, page.status)
        assertEquals(firstPage(), page.body)
        assertEquals(
            SetupPage.headers + listOf("Content-Length" to page.body.toByteArray().size.toString(), "Connection" to "close"),
            page.headers,
        )
        assertEquals(SetupEvent.Opened(loopback), nextEvent())
        // Opening it again changes nothing.
        assertEquals(firstPage(), get(port).body)
        assertNoEvents()
    }

    @Test
    fun `the page is the session's mode, from what is saved`() {
        assertEquals(firstPage(SetupMode.Edit, seed), get(start(mode = SetupMode.Edit, seed = seed)).body)
        val wizard = get(start(seed = SetupSeed(tmdbKey = SavedSecret.Saved)))
        assertTrue(wizard.body.contains("Step 1 of 4"))
        assertTrue(get(start(mode = SetupMode.Edit, seed = seed)).body.contains("news.example.invalid:563 · TLS · someone · password saved"))
    }

    @Test
    fun `the code works however it was typed`() {
        val port = start()
        val typed = token.grouped.uppercase().replace('0', 'O').replace('1', 'L').replace("-", "%20")
        assertEquals(200, get(port, "/$typed").status)
        assertEquals(200, get(port, "/${token.grouped}?from=qr").status)
    }

    @Test
    fun `paths that aren't a code are not found and don't count`() {
        val port = start()
        repeat(2) {
            for (path in listOf("/favicon.ico", "/apple-touch-icon.png", "/apple-touch-icon-precomposed.png", "/", "/robots.txt", "/${token.value}/", "/%zz")) {
                val answer = get(port, path)
                assertEquals(404, answer.status, path)
                assertEquals(SetupPage.notFound, answer.body, path)
            }
        }
        assertNoEvents()
        assertEquals(200, get(port).status)
    }

    @Test
    fun `wrong codes count, and the fifth closes the session`() {
        val port = start()
        val waiting = connect(port)
        val wrong = SetupToken.generate()
        for (count in 1..4) {
            val answer = get(port, "/${wrong.value}")
            assertEquals(404, answer.status)
            assertEquals(SetupPage.notFound, answer.body)
            assertEquals(SetupEvent.WrongTry(count), nextEvent())
        }
        val fifth = get(port, "/${wrong.grouped}")
        assertEquals(410, fifth.status)
        assertEquals(SetupPage.locked, fifth.body)
        assertEquals(SetupEvent.Locked, nextEvent())
        refused(port)
        // A connection open from before is told so, even with the right code, and nothing more is counted.
        waiting.getOutputStream().write(request(port))
        val late = assertNotNull(answer(waiting))
        assertEquals(410, late.status)
        assertEquals(SetupPage.locked, late.body)
        assertNoEvents()
    }

    /** A prank on the home network mustn't close a half-done setup: once the page is open, only its own device's wrong codes count. */
    @Test
    fun `once the page is open, wrong codes from another device aren't counted, its own device's are`() {
        val port = start()
        assertEquals(200, get(port).status)
        assertEquals(SetupEvent.Opened(loopback), nextEvent())
        val wrong = SetupToken.generate()
        repeat(6) {
            val answer = get(port, "/${wrong.value}", from = "127.0.0.2")
            assertEquals(404, answer.status)
            assertEquals(SetupPage.notFound, answer.body)
        }
        assertNoEvents()
        assertEquals(200, get(port).status)
        for (count in 1..4) {
            assertEquals(404, get(port, "/${wrong.value}").status)
            assertEquals(SetupEvent.WrongTry(count), nextEvent())
        }
        assertEquals(410, get(port, "/${wrong.value}").status)
        assertEquals(SetupEvent.Locked, nextEvent())
    }

    @Test
    fun `a TLS ClientHello is closed quietly and not counted`() {
        val port = start()
        val hello = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x2f, 0x01, 0x00, 0x00, 0x2b, 0x03, 0x03) + ByteArray(32)
        repeat(6) {
            val started = System.nanoTime()
            assertNull(send(port, hello))
            // At once, not when the deadline runs out waiting for a request line that never comes.
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000, "not closed at once")
        }
        assertNoEvents()
        assertEquals(200, get(port).status)
    }

    @Test
    fun `malformed requests are bad requests and don't count`() {
        val port = start()
        val wrong = SetupToken.generate().value
        val host = "Host: 127.0.0.1:$port"
        val bad = listOf(
            "GARBAGE\r\n\r\n",
            "GET /$wrong HTTP/2.0\r\n$host\r\n\r\n",
            "GET  /$wrong HTTP/1.1\r\n$host\r\n\r\n",
            "GET /$wrong\r\n$host\r\n\r\n",
            "GET * HTTP/1.1\r\n$host\r\n\r\n",
            "GET /a\u0001b HTTP/1.1\r\n$host\r\n\r\n",
            "GET /$wrong HTTP/1.1\r$host\r\n\r\n",
            "GET /$wrong HTTP/1.1\r\n$host\r\nNo colon here\r\n\r\n",
            "GET /$wrong HTTP/1.1\r\n$host\r\nX-Name : value\r\n\r\n",
            "GET /$wrong HTTP/1.1\r\n$host\r\nBad Name: value\r\n\r\n",
            "GET /$wrong HTTP/1.1\r\n$host\r\nX-Fold: a\r\n b\r\n\r\n",
            "GET /$wrong HTTP/1.1\r\n$host\r\nX-Nul: a\u0000b\r\n\r\n",
        )
        for (request in bad) {
            val answer = assertNotNull(send(port, request.toByteArray(Charsets.ISO_8859_1)), request)
            assertEquals(400, answer.status, request)
            assertEquals(SetupPage.refused, answer.body, request)
        }
        assertNoEvents()
        // Bare LFs end lines too, as a recipient may allow.
        assertEquals(200, assertNotNull(send(port, "GET /${token.value} HTTP/1.1\n$host\n\n".toByteArray())).status)
    }

    @Test
    fun `methods other than GET and POST are not found, with no body`() {
        val port = start()
        for (method in listOf("OPTIONS", "HEAD", "PUT", "DELETE", "PATCH", "TRACE", "get")) {
            val answer = assertNotNull(send(port, request(port, method = method, headers = listOf("Origin: http://127.0.0.1:$port"))), method)
            assertEquals(404, answer.status, method)
            assertEquals("", answer.body, method)
            assertEquals(listOf("0"), answer.header("Content-Length"), method)
            assertTrue(answer.headers.none { it.first.startsWith("Access-Control", ignoreCase = true) }, method)
        }
        assertNoEvents()
    }

    /** The method is checked before any header is read: a request the page never takes isn't read further. */
    @Test
    fun `a PUT with oversized headers still answers 404, the method being checked before the headers`() {
        val port = start()
        val big = "X-Big: " + "a".repeat(9000)
        for (headers in listOf(listOf(big), List(70) { "X-H$it: v" }, listOf("X-Fold: a", " b"))) {
            val answer = assertNotNull(send(port, request(port, method = "PUT", headers = headers)))
            assertEquals(404, answer.status)
            assertEquals("", answer.body)
        }
        // The same headers on a GET are read, and refused.
        assertEquals(431, get(port, headers = listOf(big)).status)
        assertNoEvents()
    }

    @Test
    fun `a POST whose body came in the same packet as its head is read whole`() {
        val port = start()
        val socket = connect(port)
        socket.tcpNoDelay = true
        // One write: the head and the body reach the server together, and the body is read from what was buffered with the head.
        socket.getOutputStream().write(request(port, method = "POST", body = testing))
        assertEquals(303, assertNotNull(answer(socket)).status)
        assertEquals(providerDraft, assertIs<SetupTestItem.Provider>(tester.next().item).draft)
    }

    @Test
    fun `the Host must be the TV's address`() {
        val port = start()
        val wrong = SetupToken.generate().value
        val requests = listOf(
            request(port, host = null),
            request(port, host = "evil.example:$port"),
            request(port, host = "localhost:$port"),
            request(port, host = "127.0.0.1"),
            request(port, host = "127.0.0.1:${port + 1}"),
            request(port, headers = listOf("Host: 127.0.0.1:$port")),
            request(port, target = "http://127.0.0.1:$port/${token.value}"),
        ) + List(6) { request(port, target = "/$wrong", host = "evil.example:$port") }
        for (r in requests) {
            val answer = assertNotNull(send(port, r))
            assertEquals(400, answer.status, String(r))
            assertEquals(SetupPage.refused, answer.body)
        }
        // Six wrong codes with the wrong Host: none counted, so the session is still open.
        assertNoEvents()
        assertEquals(200, get(port).status)
    }

    @Test
    fun `an Origin other than the page's own is refused`() {
        val port = start()
        val wrong = SetupToken.generate().value
        for (origin in listOf("null", "http://evil.example", "https://127.0.0.1:$port", "http://127.0.0.1:${port + 1}", "http://127.0.0.1")) {
            val answer = get(port, headers = listOf("Origin: $origin"))
            assertEquals(403, answer.status, origin)
            assertEquals(SetupPage.refused, answer.body, origin)
            assertEquals(403, get(port, "/$wrong", headers = listOf("Origin: $origin")).status)
        }
        assertEquals(403, get(port, headers = listOf("Origin: http://127.0.0.1:$port", "Origin: http://127.0.0.1:$port")).status)
        assertNoEvents()
        assertEquals(200, get(port, headers = listOf("Origin: http://127.0.0.1:$port")).status)
        assertEquals(SetupEvent.Opened(loopback), nextEvent())
        assertEquals(303, postOnly(port, testing, headers = listOf("Origin: HTTP://127.0.0.1:$port")).status)
    }

    @Test
    fun `the request line is at most 2 KB`() {
        val port = start()
        // "GET " and " HTTP/1.1" around the target: 13 bytes.
        val atLimit = request(port, target = "/" + "a".repeat(2048 - 13 - 1))
        assertEquals(404, assertNotNull(send(port, atLimit)).status)
        val over = request(port, target = "/" + "a".repeat(2048 - 13))
        val answer = assertNotNull(send(port, over))
        assertEquals(414, answer.status)
        assertEquals(SetupPage.refused, answer.body)
    }

    @Test
    fun `headers are at most 8 KB and 64 of them`() {
        val port = start()
        val hostLine = "Host: 127.0.0.1:$port\r\n".length
        // The Host line, the big one ("X-Big: " and CRLF) and the empty line make 8192 bytes.
        val fits = "X-Big: " + "a".repeat(8192 - hostLine - 9 - 2)
        assertEquals(200, get(port, headers = listOf(fits)).status)
        val tooBig = get(port, headers = listOf(fits + "a"))
        assertEquals(431, tooBig.status)
        assertEquals(SetupPage.refused, tooBig.body)
        assertEquals(200, get(port, headers = List(63) { "X-H$it: v" }).status)
        assertEquals(431, get(port, headers = List(64) { "X-H$it: v" }).status)
    }

    @Test
    fun `a POST needs one Content-Length within 32 KB`() {
        val port = start()
        val head = "POST /${token.value} HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/x-www-form-urlencoded\r\n"
        fun status(headers: String, body: String = "") = assertNotNull(send(port, "$head$headers\r\n$body".toByteArray())).status
        assertEquals(411, status(""))
        assertEquals(400, status("Transfer-Encoding: chunked\r\n", "0\r\n\r\n"))
        assertEquals(400, status("Content-Length: 3\r\nTransfer-Encoding: chunked\r\n", "a=b"))
        assertEquals(400, status("Content-Length: 3\r\nContent-Length: 3\r\n", "a=b"))
        assertEquals(400, status("Content-Length: 3\r\nContent-Length: 4\r\n", "a=b"))
        assertEquals(400, status("Content-Length: three\r\n", "a=b"))
        assertEquals(400, status("Content-Length: -3\r\n", "a=b"))
        // Too long: refused from the headers, without waiting for a body that never comes.
        val started = System.nanoTime()
        assertEquals(413, status("Content-Length: 32769\r\n"))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000, "waited for the body")
        // 32 KB is read: these are too many fields, so the form is refused, not the request.
        assertEquals(400, status("Content-Length: 32768\r\n", "&".repeat(32768)))
        assertEquals(SetupEvent.Opened(loopback), nextEvent())
        assertEquals(SetupEvent.Rejected(SetupRejection(null, SetupRejection.Reason.TooManyFields)), nextEvent())
        assertNoEvents()
    }

    @Test
    fun `a POST must be form-encoded`() {
        val port = start()
        for (type in listOf(null, "text/plain", "multipart/form-data; boundary=x", "application/json", "application/x-www-form-urlencodedx")) {
            val answer = assertNotNull(send(port, request(port, method = "POST", body = testing, contentType = type)), type)
            assertEquals(415, answer.status, type)
            assertEquals(SetupPage.refused, answer.body, type)
        }
        assertEquals(SetupEvent.Opened(loopback), nextEvent())
        assertNoEvents()
        val withCharset = send(port, request(port, method = "POST", body = testing, contentType = "Application/X-WWW-Form-Urlencoded; charset=UTF-8"))
        assertEquals(303, assertNotNull(withCharset).status)
    }

    /** Post, redirect, get: a reload or Back is a GET, never the form again, and the page is the TV's as it is now. */
    @Test
    fun `a form that is read is answered with a 303 to the page's own address, at the card it was about`() {
        val port = start()
        val typedPath = "/" + token.grouped.uppercase()
        val tested = postOnly(port, testing, target = typedPath)
        assertEquals(303, tested.status)
        assertEquals("", tested.body)
        assertEquals(
            SetupPage.headers + listOf("Location" to "/${token.value}#p1", "Content-Length" to "0", "Connection" to "close"),
            tested.headers,
        )
        // The page it points at shows the test's result.
        val page = get(port, tested.header("Location").single().substringBefore('#'))
        assertTrue(page.body.contains("<p class=\"result ok\">✓ Connected and logged in to news.example.invalid</p>"), page.body)
        assertEquals("/${token.value}#top", postOnly(port, pressed(7, "next", *providerCard)).header("Location").single())
        assertEquals("/${token.value}#p2", postOnly(port, pressed(1, "add:provider", *providerCard)).header("Location").single())
    }

    @Test
    fun `a form from an older page changes nothing, and the page that follows says so`() {
        val port = start()
        val stale = post(port, pressed(4, "test", *providerCard))
        assertEquals("/${token.value}#top", stale.redirect!!.header("Location").single())
        assertTrue(stale.body.contains("<p class=\"notice\">${SetupPage.STALE}</p>"))
        assertTrue(tester.calls.isEmpty())
        assertFalse(stale.body.contains("news.example.invalid"), "nothing from it was kept")
        assertFalse(get(port).body.contains(SetupPage.STALE), "said once")
    }

    @Test
    fun `Send is answered directly, hands over the changes and ends the session`() {
        val port = start(mode = SetupMode.Edit, seed = seed)
        assertEquals(200, post(port, pressed(1, "drop:p1")).status)
        val sent = postOnly(port, pressed(2, "send"))
        assertEquals(200, sent.status)
        assertEquals(SetupPage.sent, sent.body)
        assertEquals(SetupPage.headers + listOf("Content-Length" to sent.body.toByteArray().size.toString(), "Connection" to "close"), sent.headers)
        assertEquals(SetupEvent.Opened(loopback), nextEvent())
        val changes = SetupChanges(
            listOf(SetupChange("prov-1", ProviderDraft("Main provider", "news.example.invalid", 563, true, "someone", "", 20), removed = true)),
            listOf(SetupChange("idx-1", IndexerDraft("Indexer A", "https://indexer-a.example.invalid/api", ""), removed = false)),
            null,
        )
        assertEquals(SetupEvent.Received(changes, loopback), nextEvent())
        refused(port)
        assertNoEvents()
    }

    @Test
    fun `the wizard goes step by step to Send, which hands over what the phone added`() {
        val port = start()
        assertEquals(200, post(port, testing).status)
        assertEquals(SetupTestItem.Provider(p1, null, providerDraft), tester.next().item)
        assertTrue(post(port, pressed(1, "next", *card("p.pass" to ""))).body.contains("Step 2 of 4 · Indexers"))
        assertTrue(post(port, pressed(2, "next", "i.name" to "", "i.url" to "", "i.key" to "")).body.contains("Step 3 of 4"))
        assertTrue(post(port, pressed(3, "next", "t.key" to "")).body.contains("Step 4 of 4 · Check and send"))
        assertEquals(200, postOnly(port, pressed(4, "send")).status)
        assertEquals(SetupEvent.Opened(loopback), nextEvent())
        assertEquals(listOf(SetupEvent.TestStarted(p1), SetupEvent.TestFinished(p1, SetupOutcome.Passed)), listOf(nextEvent(), nextEvent()))
        assertEquals(
            listOf(SetupEvent.StepChanged(SetupStep.Indexers), SetupEvent.StepChanged(SetupStep.Tmdb), SetupEvent.StepChanged(SetupStep.Check)),
            listOf(nextEvent(), nextEvent(), nextEvent()),
        )
        assertEquals(SetupEvent.Received(SetupChanges(listOf(SetupChange(null, providerDraft, false)), emptyList(), null), loopback), nextEvent())
        refused(port)
    }

    @Test
    fun `Next waits for a card changed since its test, and says why on the page that follows`() {
        val port = start()
        post(port, testing)
        val waits = post(port, pressed(1, "next", *card("p.port" to "443", "p.pass" to "")))
        assertEquals("/${token.value}#p1", waits.redirect!!.header("Location").single())
        assertTrue(waits.body.contains("Step 1 of 4"))
        assertTrue(waits.body.contains("<p class=\"result\">${SetupPage.CHANGED_SINCE_TEST}</p>"), waits.body)
        assertTrue(waits.body.contains("<p class=\"notice\">To go on, test Provider 1 again or don't add it.</p>"), waits.body)
        post(port, pressed(1, "test", *card("p.port" to "443", "p.pass" to "")))
        assertTrue(post(port, pressed(1, "next", *card("p.port" to "443", "p.pass" to ""))).body.contains("Step 2 of 4"))
    }

    /** With no validation in the page, a bad field reaches the TV: it is named on its card, and the phone's other buttons still work. */
    @Test
    fun `a bad field in one card doesn't block another card's buttons`() {
        val port = start()
        post(port, testing)
        post(port, pressed(1, "add:provider", *card("p.pass" to "")))
        val tested = post(port, pressed(2, "test:p1", *card("p.host" to "fill.example.invalid", "p.port" to "1e3")))
        assertEquals("/${token.value}#p1", tested.redirect!!.header("Location").single())
        assertEquals(2, tester.calls.size, "provider 1 tested")
        // Provider 2 kept what it could and says what it couldn't.
        val page = get(port).body
        assertTrue(page.contains("<p id=\"p.port-p\" class=\"result fail\">Port: it isn't a whole number, so it wasn't kept.</p>"), page)
        assertTrue(page.contains("value=\"fill.example.invalid\""))
        assertFalse(page.contains("1e3"))
    }

    @Test
    fun `a connection open when the form was sent is told the page was used`() {
        val port = start(mode = SetupMode.Edit, seed = seed)
        post(port, pressed(1, "drop:p1"))
        // Accepted before Send's connection, so it is still open when the session ends.
        val waiting = connect(port)
        assertEquals(200, postOnly(port, pressed(2, "send")).status)
        assertEquals(SetupEvent.Opened(loopback), nextEvent())
        assertTrue(nextEvent() is SetupEvent.Received)
        waiting.getOutputStream().write(request(port, method = "POST", body = pressed(2, "send")))
        val late = assertNotNull(answer(waiting))
        assertEquals(410, late.status)
        assertEquals(SetupPage.used, late.body)
        assertNoEvents()
    }

    @Test
    fun `a form that can't be read is refused, says nothing of it, and the phone can send again`() {
        val port = start()
        val answer = postOnly(port, form("v" to "1", "do" to "test", "p.host" to "marker-host", "p.bogus" to "marker-value"))
        assertEquals(400, answer.status)
        assertEquals(SetupPage.rejected, answer.body)
        assertEquals(SetupEvent.Opened(loopback), nextEvent())
        assertEquals(SetupEvent.Rejected(SetupRejection(null, SetupRejection.Reason.UnknownField)), nextEvent())
        assertEquals(303, postOnly(port, testing).status)
    }

    @Test
    fun `a form that can't be read is refused whichever button sent it`() {
        val port = start()
        val bad = listOf(
            form("v" to "1", "do" to "test", "p.host" to "marker-host", "p.bogus" to "x") to SetupRejection.Reason.UnknownField,
            form("v" to "1", "do" to "check", *providerCard) to SetupRejection.Reason.Malformed,
            form("v" to "1", *providerCard) to SetupRejection.Reason.Malformed,
            form("do" to "test", *providerCard) to SetupRejection.Reason.Malformed,
            // Step 1 has no Send, and its form no indexer.
            form("v" to "1", "do" to "send") to SetupRejection.Reason.Malformed,
            form("v" to "1", "do" to "test", "i.url" to "https://x.invalid") to SetupRejection.Reason.UnknownField,
        )
        for ((body, reason) in bad) {
            val answer = postOnly(port, body)
            assertEquals(400, answer.status, body)
            assertEquals(SetupPage.rejected, answer.body)
            if (reason == SetupRejection.Reason.UnknownField && body.contains("bogus")) assertEquals(SetupEvent.Opened(loopback), nextEvent())
            assertEquals(SetupEvent.Rejected(SetupRejection(null, reason)), nextEvent(), body)
        }
        assertTrue(tester.calls.isEmpty())
        assertEquals(303, postOnly(port, testing).status)
    }

    @Test
    fun `nothing a request carries comes back but what the phone typed, on its own page, never a password`() {
        val port = start()
        val answers = listOf(
            get(port, "/${token.value}?q=marker", headers = listOf("X-Echo: marker", "Cookie: marker=marker")),
            get(port, "/marker"),
            get(port, "/marker", headers = listOf("Origin: http://marker.example")),
            assertNotNull(send(port, request(port, host = "marker.example"))),
            postOnly(port, pressed(1, "test", *card("p.host" to "marker", "p.pass" to "marker-pass", "p.port" to "marker-port"))),
            postOnly(port, form("v" to "1", "do" to "test", "t.key" to "marker\u0001")),
            postOnly(port, form("v" to "marker", "do" to "test")),
        )
        answers.forEach { answer ->
            assertFalse(answer.body.contains("marker"), answer.body)
            assertFalse(answer.headers.any { it.second.contains("marker") }, answer.headers.toString())
        }
        val page = get(port).body
        assertTrue(page.contains("value=\"marker\""), "the phone's own host, on its page")
        assertFalse(page.contains("marker-pass"))
        assertFalse(page.contains("marker-port"))
        assertFalse(get(port, from = "127.0.0.2").body.contains("marker"), "another device")
    }

    @Test
    fun `the first device to open the page owns the session`() {
        val port = start()
        assertEquals(200, get(port).status)
        assertEquals(SetupEvent.Opened(loopback), nextEvent())
        val other = InetAddress.getByName("127.0.0.2")
        for (answer in listOf(postOnly(port, testing, from = "127.0.0.2"), postOnly(port, pressed(1, "add:provider", *providerCard), from = "127.0.0.2"), get(port, from = "127.0.0.2"))) {
            assertEquals(403, answer.status)
            assertEquals(SetupPage.otherDevice, answer.body)
            assertEquals(SetupEvent.PeerRefused(other), nextEvent())
        }
        assertTrue(tester.calls.isEmpty())
        assertEquals(303, postOnly(port, testing).status)
        assertEquals(listOf(SetupEvent.TestStarted(p1), SetupEvent.TestFinished(p1, SetupOutcome.Passed)), listOf(nextEvent(), nextEvent()))
        // The first to use the code owns it, as for the page: a form can be the first.
        val fresh = SetupServer(loopback, token = token, mode = SetupMode.Wizard, allowPeer = { it.isLoopbackAddress }, tester = tester, onEvent = { events.put(it) })
            .also { servers += it }.start()
        assertEquals(303, postOnly(fresh, testing, from = "127.0.0.2").status)
        assertEquals(SetupEvent.Opened(other), nextEvent())
        assertEquals(403, postOnly(fresh, testing).status)
    }

    @Test
    fun `peers the predicate refuses are dropped without an answer`() {
        val port = server(allowPeer = { it.hostAddress == "127.0.0.1" }).start()
        assertNull(send(port, request(port), from = "127.0.0.2"))
        assertNoEvents()
        assertEquals(200, get(port).status)
    }

    @Test
    fun `by default only private IPv4 peers are served`() {
        val port = SetupServer(loopback, token = token, mode = SetupMode.Wizard, tester = tester, onEvent = { events.put(it) }).also { servers += it }.start()
        assertNull(send(port, request(port)))
        assertNoEvents()
        for (ip in listOf("10.0.2.15", "172.16.0.1", "172.31.255.254", "192.168.1.40")) {
            assertTrue(SetupServer.isPrivateIpv4(InetAddress.getByName(ip)), ip)
        }
        for (ip in listOf("127.0.0.1", "169.254.1.1", "100.64.0.1", "172.32.0.1", "8.8.8.8", "::1", "fd00::1", "fe80::1")) {
            assertFalse(SetupServer.isPrivateIpv4(InetAddress.getByName(ip)), ip)
        }
    }

    @Test
    fun `one deadline covers the whole request, however slowly it drips`() {
        val port = start(SetupLimits(deadlineMillis = 600))
        val socket = connect(port)
        val out = socket.getOutputStream()
        val started = System.nanoTime()
        out.write("GET /".toByteArray())
        // A byte every 100 ms: each read would be well within a per-read timeout of 600 ms.
        thread(isDaemon = true) {
            try {
                repeat(100) {
                    out.write('a'.code)
                    Thread.sleep(100)
                }
            } catch (_: IOException) {
                // Closed by the server.
            }
        }
        assertNull(answer(socket), "an answer to a request that never ended")
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(elapsed in 500..2500, "closed after $elapsed ms")
        // An idle connection goes the same way.
        val idle = connect(port)
        val idleStart = System.nanoTime()
        assertNull(answer(idle))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - idleStart) < 2500)
        assertNoEvents()
    }

    @Test
    fun `four connections at a time, and a fifth is turned away`() {
        val port = start()
        val held = List(4) { connect(port) }
        // It isn't read: the answer goes out as soon as the connection is taken.
        val fifth = assertNotNull(answer(connect(port)))
        assertEquals(503, fifth.status)
        assertEquals("", fifth.body)
        assertEquals(listOf("close"), fifth.header("Connection"))
        assertNoEvents()
        // A slot comes back once its connection goes.
        held.first().close()
        eventually("no slot came back") { send(port, request(port))?.status == 200 }
    }

    @Test
    fun `one answer per connection, then it closes`() {
        val port = start()
        val twice = request(port) + request(port)
        val socket = connect(port)
        socket.getOutputStream().write(twice)
        val raw = assertNotNull(answer(socket))
        assertEquals(200, raw.status)
        assertEquals(firstPage(), raw.body, "a second answer after the first")
    }

    @Test
    fun `close is prompt, closes every connection, and nothing is heard after it`() {
        val server = server()
        val port = server.start()
        val held = List(3) { connect(port) }
        // One half-way through a request.
        val partial = connect(port).also { it.getOutputStream().write("GET /${token.value} HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n".toByteArray()) }
        Thread.sleep(200)
        val started = System.nanoTime()
        server.close()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500, "close took long")
        (held + partial).forEach { socket ->
            val readStarted = System.nanoTime()
            assertNull(answer(socket))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - readStarted) < 1000, "a connection stayed open")
        }
        refused(port)
        assertNoEvents()
        server.close()
    }

    /** The TV may stop the page within the moment the answer is drained: what was sent has been handed over by then. */
    @Test
    fun `Send hands over before the answer is drained, so closing during the drain loses nothing`() {
        val server = server(SetupLimits(drainMillis = 3000), SetupMode.Edit, seed)
        val port = server.start()
        post(port, pressed(1, "drop:p1"))
        assertEquals(SetupEvent.Opened(loopback), nextEvent())
        val socket = connect(port)
        socket.getOutputStream().write(request(port, method = "POST", body = pressed(2, "send")))
        val started = System.nanoTime()
        assertEquals(200, assertNotNull(answer(socket)).status)
        assertTrue(nextEvent() is SetupEvent.Received)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2500, "it waited for the drain")
        server.close()
        assertNoEvents()
    }

    @Test
    fun `a test is answered with its card's page, its result on it, and the session goes on`() {
        val port = start()
        val answer = post(port, testing)
        assertEquals(200, answer.status)
        assertEquals("/${token.value}#p1", answer.redirect!!.header("Location").single())
        assertTrue(answer.body.contains("<p class=\"result ok\">✓ Connected and logged in to news.example.invalid</p>"))
        assertTrue(answer.body.contains(">Test again</button>"))
        val call = tester.next()
        assertEquals(SetupTestItem.Provider(p1, null, providerDraft), call.item)
        assertEquals(SetupEvent.Opened(loopback), nextEvent())
        assertEquals(SetupEvent.TestStarted(p1), nextEvent())
        assertEquals(SetupEvent.TestFinished(p1, SetupOutcome.Passed), nextEvent())
        assertNoEvents()
        // Still open, the code still good; the password stays on the TV.
        assertFalse(get(port).body.contains("pass word"))
        assertEquals(303, postOnly(port, testing).status)
        assertEquals(SetupEvent.TestStarted(p1), nextEvent())
    }

    /**
     * L192, L209 over the socket: a seed without the debrid services has no debrid section, and a form naming one of its
     * cards or buttons is refused as no page of this TV's sends it; with them, a service card is tested on the TV and sent
     * as any card is, its key never back on a page, and a second card of the same service is refused.
     */
    @Test
    fun `the debrid cards are served only with their services, tested on the TV and sent like any card`() {
        val torbox = SavedService("svc-1", "torbox", "quay.viewer@example.invalid", true, SavedSecret.Saved)
        val hidden = start(mode = SetupMode.Edit, seed = seed.copy(services = listOf(torbox)))
        assertFalse(get(hidden).body.contains("Debrid service"))
        for (body in listOf(pressed(1, "add:service"), pressed(1, "open:s1"), pressed(1, "add:torrent-indexer"), pressed(1, "test", "s.key" to "K"))) {
            val answer = postOnly(hidden, body)
            assertEquals(listOf(400, SetupPage.rejected), listOf(answer.status, answer.body), body)
        }

        val choices = listOf(ServiceChoice("premiumize", "Premiumize", "In Premiumize: Account › API key"), ServiceChoice("torbox", "TorBox", "In TorBox: Settings › API key"))
        val shown = start(mode = SetupMode.Edit, seed = seed.copy(serviceKinds = choices))
        assertTrue(post(shown, pressed(1, "add:service")).body.contains("<fieldset id=\"s1\">"))
        val tested = post(shown, pressed(2, "test", "s.kind" to "torbox", "s.key" to "marker-service-key"))
        assertTrue(tested.body.contains("<p class=\"result ok\">✓ Signed in as quay.viewer@example.invalid</p>"), tested.body)
        assertFalse(tested.body.contains("marker"))
        val s1 = SetupSlot(SetupSection.Service, 1)
        assertEquals(SetupTestItem.Service(s1, null, ServiceDraft("torbox", "marker-service-key")), tester.next().item)
        post(shown, pressed(2, "add:service", "s.kind" to "torbox", "s.key" to ""))
        val second = postOnly(shown, pressed(3, "test", "s.kind" to "torbox", "s.key" to "another-key"))
        assertEquals(400, second.status, "a second TorBox card")
        assertEquals(200, postOnly(shown, pressed(3, "send", "s.key" to "")).status)
        val received = generateSequence { events.poll(5, TimeUnit.SECONDS) }.filterIsInstance<SetupEvent.Received>().first()
        assertEquals(listOf(SetupChange(null, ServiceDraft("torbox", "marker-service-key"), removed = false)), received.changes.services)
    }

    @Test
    fun `a card's TLS box comes back as posted`() {
        val port = start()
        val unticked = post(port, pressed(1, "test", *card("p.tls" to "-")))
        assertFalse("checked" in tlsBox(unticked.body), "the box came back ticked")
        assertEquals(false, (tester.next().item as SetupTestItem.Provider).draft.useTls)
        val ticked = post(port, testing)
        assertTrue("checked" in tlsBox(ticked.body), "the box came back unticked")
    }

    private fun tlsBox(page: String) = assertNotNull(Regex("""<input type="checkbox" [^>]*>""").find(page), "no TLS box").value

    @Test
    fun `only the device that opened the page can test`() {
        val port = start()
        assertEquals(200, get(port).status)
        assertEquals(SetupEvent.Opened(loopback), nextEvent())
        val other = postOnly(port, testing, from = "127.0.0.2")
        assertEquals(403, other.status)
        assertEquals(SetupPage.otherDevice, other.body)
        assertEquals(SetupEvent.PeerRefused(InetAddress.getByName("127.0.0.2")), nextEvent())
        assertNoEvents()
        assertTrue(tester.calls.isEmpty())
    }

    @Test
    fun `a code allows thirty tests, one a card tested, and with none left an untested card may go on`() {
        assertEquals(30, SetupLimits().tests)
        val port = start(SetupLimits(tests = 2))
        post(port, testing)
        post(port, pressed(1, "test", *card("p.port" to "443")))
        // None left: the tester isn't asked, and the card may go on, the TV testing it at Save.
        val none = post(port, pressed(1, "test", *card("p.port" to "444")))
        assertEquals(2, tester.calls.size)
        assertTrue(none.body.contains("<p class=\"result\">${SetupPage.UNTESTED_NO_TESTS}</p>"), none.body)
        assertTrue(none.body.contains(SetupPage.NO_TESTS_LEFT))
        assertTrue(none.body.contains("value=\"enter\" class=\"offscreen\""), "Enter's button stays")
        assertFalse(none.body.contains("value=\"test\""), "no Test in sight")
        assertTrue(post(port, pressed(1, "next", *card("p.port" to "444"))).body.contains("Step 2 of 4"))
    }

    @Test
    fun `a newer test replaces the running one, which is cancelled`() {
        val port = start()
        tester.hold = true
        val first = startTest(port)
        val running = tester.next()
        tester.hold = false
        val newer = post(port, pressed(1, "test", *card("p.host" to "fixed.example.invalid")))
        assertEquals(200, newer.status)
        assertTrue(newer.body.contains("value=\"fixed.example.invalid\""))
        assertTrue(newer.body.contains("✓ Connected and logged in to fixed.example.invalid"))
        // Its own page went; its answer points at the page all the same.
        val replaced = assertNotNull(answer(first))
        assertEquals(303, replaced.status)
        assertTrue(running.future.isCancelled, "the replaced test's work goes on")
        assertEquals(listOf(SetupEvent.Opened(loopback), SetupEvent.TestStarted(p1), SetupEvent.TestStarted(p1)), listOf(nextEvent(), nextEvent(), nextEvent()))
        assertEquals(SetupEvent.TestFinished(p1, SetupOutcome.Passed), nextEvent())
        assertNoEvents()
    }

    /**
     * Next or Back leaves the step a running test is of: the test stops, as a newer one would stop it, so a late result
     * can't hold up a step the phone has left; the card keeps the pass it had with these values.
     */
    @Test
    fun `Next or Back stops a test running, and its card keeps what it had`() {
        val port = start()
        post(port, testing)
        tester.next()
        tester.hold = true
        val again = startTest(port)
        val running = tester.next()
        assertTrue(post(port, pressed(1, "next", *providerCard)).body.contains("Step 2 of 4"))
        assertTrue(running.future.isCancelled, "the test goes on past Next")
        assertEquals(303, assertNotNull(answer(again)).status)
        running.future.complete(SetupResult(SetupOutcome.Failed, "late"))
        // Back on step 1, it still works.
        val indexer = arrayOf("i.name" to "", "i.url" to "https://indexer.example.invalid/api", "i.key" to "k")
        val test = startTest(port, pressed(2, "test", *indexer))
        val indexerTest = tester.next()
        val back = post(port, pressed(2, "back", *indexer))
        assertTrue(back.body.contains("<p class=\"st good\">✓ Works</p>"), back.body)
        assertTrue(indexerTest.future.isCancelled, "the test goes on past Back")
        assertEquals(303, assertNotNull(answer(test)).status)
    }

    /**
     * A test Next or Back stops never finishes, so the TV is told it stopped, after the step it went to, or Waiting would
     * go on saying the phone is testing. Its late result tells nothing.
     */
    @Test
    fun `the TV is told when Next or Back stops a test`() {
        val port = start()
        post(port, testing)
        tester.next()
        tester.hold = true
        val again = startTest(port)
        val running = tester.next()
        postOnly(port, pressed(1, "next", *providerCard))
        assertEquals(303, assertNotNull(answer(again)).status)
        running.future.complete(SetupResult(SetupOutcome.Failed, "late"))
        assertEquals(
            listOf(SetupEvent.Opened(loopback), SetupEvent.TestStarted(p1), SetupEvent.TestFinished(p1, SetupOutcome.Passed), SetupEvent.TestStarted(p1)),
            listOf(nextEvent(), nextEvent(), nextEvent(), nextEvent()),
        )
        assertEquals(listOf(SetupEvent.StepChanged(SetupStep.Indexers), SetupEvent.TestStopped(p1)), listOf(nextEvent(), nextEvent()))
        val indexer = arrayOf("i.name" to "", "i.url" to "https://indexer.example.invalid/api", "i.key" to "k")
        val test = startTest(port, pressed(2, "test", *indexer))
        tester.next()
        postOnly(port, pressed(2, "back", *indexer))
        assertEquals(303, assertNotNull(answer(test)).status)
        assertEquals(
            listOf(SetupEvent.TestStarted(i1), SetupEvent.StepChanged(SetupStep.Providers), SetupEvent.TestStopped(i1)),
            listOf(nextEvent(), nextEvent(), nextEvent()),
        )
        assertNoEvents()
    }

    @Test
    fun `Send while a test runs cancels it, its page told the code was used`() {
        val port = start(mode = SetupMode.Edit, seed = seed)
        post(port, pressed(1, "drop:p1"))
        tester.hold = true
        val test = startTest(port, pressed(2, "test:i1"))
        val running = tester.next()
        assertEquals(SetupTestItem.Indexer(i1, "idx-1", IndexerDraft("Indexer A", "https://indexer-a.example.invalid/api", ""), plainHttp = false), running.item)
        assertEquals(200, postOnly(port, pressed(2, "send")).status)
        val used = assertNotNull(answer(test))
        assertEquals(410, used.status)
        assertEquals(SetupPage.used, used.body)
        assertTrue(running.future.isCancelled)
        assertEquals(listOf(SetupEvent.Opened(loopback), SetupEvent.TestStarted(i1)), listOf(nextEvent(), nextEvent()))
        assertTrue(nextEvent() is SetupEvent.Received)
        // The test's late answer changes nothing.
        running.future.complete(SetupResult(SetupOutcome.Passed, "late"))
        assertNoEvents()
        refused(port)
    }

    @Test
    fun `five wrong codes while a test runs cancel it`() {
        val port = start()
        tester.hold = true
        val test = startTest(port)
        val running = tester.next()
        val wrong = SetupToken.generate()
        repeat(5) { get(port, "/${wrong.value}") }
        val locked = assertNotNull(answer(test))
        assertEquals(410, locked.status)
        assertEquals(SetupPage.locked, locked.body)
        assertTrue(running.future.isCancelled)
    }

    @Test
    fun `close during a test cancels it, the page unanswered and nothing heard after`() {
        val server = server()
        val port = server.start()
        tester.hold = true
        val test = startTest(port)
        val running = tester.next()
        assertEquals(listOf(SetupEvent.Opened(loopback), SetupEvent.TestStarted(p1)), listOf(nextEvent(), nextEvent()))
        val started = System.nanoTime()
        server.close()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500, "close took long")
        assertNull(answer(test))
        assertTrue(running.future.isCancelled)
        running.future.complete(result(running.item))
        assertNoEvents()
        refused(port)
    }

    @Test
    fun `a test that takes too long fails on its card, and is cancelled`() {
        val port = start(SetupLimits(testMillis = 500))
        assertEquals(30_000, SetupLimits().testMillis)
        tester.hold = true
        val started = System.nanoTime()
        val answer = post(port, testing)
        val took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(took in 400..3000, "answered after $took ms")
        assertTrue(tester.next().future.isCancelled)
        assertEquals("The test took too long.", SetupPage.TOOK_TOO_LONG)
        assertTrue(answer.body.contains("<p class=\"result fail\">✗ ${SetupPage.TOOK_TOO_LONG}</p>"), answer.body)
        assertEquals(listOf(SetupEvent.Opened(loopback), SetupEvent.TestStarted(p1)), listOf(nextEvent(), nextEvent()))
        assertEquals(SetupEvent.TestFinished(p1, SetupOutcome.Failed), nextEvent())
        // The session goes on.
        assertEquals(200, get(port).status)
    }

    /** The words as the page writes them, escaped. */
    private val couldntRun = SetupPage.COULDNT_RUN.replace("'", "&#39;")

    @Test
    fun `a tester that breaks is a test that couldn't run`() {
        val port = start()
        tester.fails = true
        val answer = post(port, testing)
        assertEquals(200, answer.status)
        assertTrue(answer.body.contains("<p class=\"result fail\">✗ ${couldntRun}</p>"), answer.body)
        assertFalse(answer.body.contains("broke"))
        tester.fails = false
        assertTrue(post(port, testing).body.contains("✓ Connected"))
    }

    /** As the app's future fails when a card's test throws past its own catch: answered as one that couldn't run. */
    @Test
    fun `a test whose future fails is a test that couldn't run, and the session goes on`() {
        val port = start()
        tester.failsLater = true
        val answer = post(port, testing)
        assertTrue(answer.body.contains("<p class=\"result fail\">✗ ${couldntRun}</p>"), answer.body)
        assertFalse(answer.body.contains("marker-host"))
        assertEquals(listOf(SetupEvent.Opened(loopback), SetupEvent.TestStarted(p1), SetupEvent.TestFinished(p1, SetupOutcome.Failed)), listOf(nextEvent(), nextEvent(), nextEvent()))
        tester.failsLater = false
        assertTrue(post(port, testing).body.contains("✓ Connected"))
    }

    /** The tester's call runs on the connection's own thread, and the server stays responsive while it waits. */
    @Test
    fun `while a test runs the page still opens and other requests are answered`() {
        val port = start()
        tester.hold = true
        val test = startTest(port)
        val running = tester.next()
        assertEquals("phone-setup-connection", running.thread)
        assertEquals(200, get(port).status)
        assertEquals(404, get(port, "/favicon.ico").status)
        running.future.complete(result(running.item))
        assertEquals(303, assertNotNull(answer(test)).status)
    }

    /** The phone's browser may give up on a slow test's request: its result is on the card all the same. */
    @Test
    fun `a test's result stays on its card even when its own connection went`() {
        val port = start()
        tester.hold = true
        val test = startTest(port)
        val running = tester.next()
        test.close()
        running.future.complete(result(running.item))
        eventually("no result on the page") { get(port).body.contains("✓ Connected and logged in to news.example.invalid") }
    }

    @Test
    fun `a test doesn't count as a wrong try`() {
        val port = start()
        repeat(6) { assertEquals(303, postOnly(port, testing).status) }
        events.clear()
        val wrong = SetupToken.generate()
        for (count in 1..4) {
            get(port, "/${wrong.value}")
            assertEquals(SetupEvent.WrongTry(count), nextEvent())
        }
        assertEquals(200, get(port).status)
    }

    @Test
    fun `the TV is told each form read, each step, and a form read after one that couldn't be`() {
        val port = start()
        assertEquals(400, postOnly(port, form("v" to "1", "do" to "nope")).status)
        assertEquals(SetupEvent.Opened(loopback), nextRawEvent().let { if (it is SetupEvent.DeadlineMoved) nextRawEvent() else it })
        val rejected = generateSequence { nextRawEvent() }.first { it !is SetupEvent.DeadlineMoved }
        assertTrue(rejected is SetupEvent.Rejected)
        postOnly(port, pressed(1, "next", *card("p.host" to "", "p.user" to "", "p.pass" to "")))
        val read = generateSequence { nextRawEvent() }.filterNot { it is SetupEvent.DeadlineMoved }.take(2).toList()
        assertEquals(listOf(SetupEvent.FormRead, SetupEvent.StepChanged(SetupStep.Indexers)), read)
        postOnly(port, pressed(2, "back", "i.name" to "", "i.url" to "", "i.key" to ""))
        assertEquals(listOf(SetupEvent.FormRead, SetupEvent.StepChanged(SetupStep.Providers)), generateSequence { nextRawEvent() }.filterNot { it is SetupEvent.DeadlineMoved }.take(2).toList())
    }

    // The code's lifetime, on a clock moved by hand.

    private val minute = 60_000L

    @Test
    fun `a code nobody opens closes after ten minutes`() {
        val server = server(clock = { now.get() })
        val port = server.start()
        assertEquals(1_000_000 + 10 * minute, server.deadline)
        now.addAndGet(10 * minute - 1)
        assertEquals(404, get(port, "/favicon.ico").status)
        now.addAndGet(1)
        val expired = get(port)
        assertEquals(410, expired.status)
        assertEquals(SetupPage.expired, expired.body)
        assertEquals(SetupEvent.Expired, nextEvent())
        refused(port)
    }

    @Test
    fun `an opened code lasts ten minutes from its device's last request, and says how long it lasts`() {
        val server = server(clock = { now.get() })
        val port = server.start()
        now.addAndGet(minute)
        assertTrue(get(port).body.contains(SetupPage.IDLE_LIFETIME))
        assertEquals(SetupEvent.Opened(loopback), nextEvent())
        assertEquals(SetupEvent.DeadlineMoved(1_000_000 + 11 * minute), nextRawEvent())
        repeat(3) {
            now.addAndGet(9 * minute)
            assertEquals(200, get(port).status)
            assertEquals(now.get() + 10 * minute, server.deadline)
            assertEquals(SetupEvent.DeadlineMoved(now.get() + 10 * minute), nextRawEvent())
        }
        // Another device's requests don't keep it open.
        now.addAndGet(9 * minute)
        get(port, from = "127.0.0.2")
        now.addAndGet(minute)
        assertEquals(410, get(port).status)
        assertEquals(SetupEvent.Expired, generateSequence { nextEvent() }.first { it !is SetupEvent.PeerRefused })
    }

    @Test
    fun `an opened code closes forty-five minutes after it was opened, saying so in its last minutes`() {
        val server = server(clock = { now.get() })
        val port = server.start()
        get(port)
        val opened = now.get()
        for (at in listOf(9, 18, 27, 36)) {
            now.set(opened + at * minute)
            assertFalse(get(port).body.contains("This code closes in"), "at $at")
        }
        now.set(opened + 41 * minute)
        assertTrue(get(port).body.contains("<p class=\"hint\">This code closes in about 4 minutes.</p>"))
        assertEquals(opened + 45 * minute, server.deadline)
        now.set(opened + 45 * minute)
        val closed = get(port)
        assertEquals(410, closed.status)
        assertEquals(SetupPage.closedAtCap, closed.body)
    }

    @Test
    fun `no test starts with too little time left for it, and the card may go on untested`() {
        val server = server(clock = { now.get() })
        val port = server.start()
        get(port)
        val opened = now.get()
        for (at in listOf(9, 18, 27, 36, 44)) {
            now.set(opened + at * minute)
            get(port)
        }
        // With a little more than the room a test needs, one starts.
        assertEquals(35_000, SetupLimits().testRoomMillis)
        now.set(opened + 45 * minute - 36_000)
        post(port, testing)
        tester.next()
        now.set(opened + 45 * minute - 34_000)
        val late = post(port, pressed(1, "test", *card("p.port" to "443")))
        assertTrue(tester.calls.isEmpty(), "a test that could run into the code's end")
        assertTrue(late.body.contains("<p class=\"result\">${SetupPage.UNTESTED_CLOSING}</p>"), late.body)
        assertTrue(late.body.contains("This code closes in about 1 minute."))
        assertTrue(post(port, pressed(1, "next", *card("p.pass" to ""))).body.contains("Step 2 of 4"))
    }

    @Test
    fun `the end of a test counts as use`() {
        val server = server(clock = { now.get() })
        val port = server.start()
        tester.hold = true
        val test = startTest(port)
        val running = tester.next()
        val started = now.get()
        now.addAndGet(5 * minute)
        running.future.complete(result(running.item))
        assertEquals(303, assertNotNull(answer(test)).status)
        assertEquals(started + 15 * minute, server.deadline)
    }

    @Test
    fun `the address is the page's, and hides the code when printed`() {
        val server = server()
        val port = server.start()
        val address = server.address
        assertEquals("http://127.0.0.1:$port/${token.value}", address.url)
        assertEquals("127.0.0.1:$port/${token.grouped}", address.display)
        assertEquals("127.0.0.1:$port", address.authority)
        assertEquals(port, address.port)
        assertFalse(address.toString().contains(token.value))
        assertFalse(address.toString().contains(token.grouped))
        assertEquals(200, get(port, "/${address.display.substringAfter('/')}").status)
    }
}
