package io.skjaere.usenet.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [LocalNntp]'s cut and throttle as its KDoc says, seen from plain sockets on the device (item 23): a BODY cut after
 * a number of its article's bytes, once or every time, and each connection's answers held to the throttle's bytes a
 * second. The file is seeded bytes posted under this test's own ids; the server is this test's alone.
 */
@RunWith(AndroidJUnit4::class)
class LocalNntpTest {
    private val local = LocalNntp()
    private val posted = local.post("Harbor.Lights.S03E07.bin", Random(SEED).nextBytes(PARTS * PART), PART, "local-nntp-test")

    @After
    fun tearDown() {
        local.close()
    }

    /** A plain client on a connection of its own: sends commands and reads the answers' bytes as they come. */
    private class Client(local: LocalNntp) : Closeable {
        private val socket = Socket(local.provider.host, local.provider.port).apply { soTimeout = READ_LIMIT_MS }
        private val input = BufferedInputStream(socket.getInputStream())
        val greeting: String? = line()

        fun send(command: String) = socket.getOutputStream().run {
            write("$command\r\n".toByteArray(Charsets.ISO_8859_1))
            flush()
        }

        /** The next line without its CRLF, or what came of it before the stream's end (null if nothing did). */
        fun line(): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
                if (b == '\n'.code) return bytes.toString("ISO-8859-1").removeSuffix("\r")
                bytes.write(b)
            }
        }

        /** The next [count] bytes, or those that came before the stream's end. */
        fun bytes(count: Int): ByteArray {
            val bytes = ByteArray(count)
            var read = 0
            while (read < count) {
                val n = input.read(bytes, read, count - read)
                if (n < 0) break
                read += n
            }
            return bytes.copyOf(read)
        }

        /** Whether the server has closed the connection: nothing more comes. */
        fun atEnd(): Boolean = input.read() < 0

        override fun close() = socket.close()
    }

    /** A BODY's answer: its status line, the article's bytes as they came, and whether the dot that ends an answer followed. */
    private class Answer(val status: String?, val article: ByteArray, val ended: Boolean)

    /** Asks for part [i] and reads the answer: at most the part's article, then the dot if it comes. */
    private fun Client.body(i: Int): Answer {
        send("BODY <${posted.ids[i]}>")
        val status = line()
        val article = if (status?.startsWith("222 ") == true) bytes(posted.sizes[i]) else ByteArray(0)
        val dot = if (article.size == posted.sizes[i]) line() else null
        return Answer(status, article, dot == ".")
    }

    /** The bytes the server sends for part [i]'s BODY: the 222 line, the article and the dot's line. */
    private fun answerBytes(i: Int): Int = "222 0 <${posted.ids[i]}>\r\n".length + posted.sizes[i] + ".\r\n".length

    /** The least time part [i]'s answer can take at [RATE], each byte waiting its share before it goes. */
    private fun leastMs(i: Int): Long = answerBytes(i) * 1_000L / RATE - 1

    /** [block]'s answer, and how long it took in milliseconds. */
    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val started = System.nanoTime()
        val answer = block()
        return answer to (System.nanoTime() - started) / 1_000_000
    }

    @Test
    fun aCutOnceEndsTheFirstBodyItTakesAfterItsBytesAndTheConnectionWithIt() {
        val whole = Client(local).use { it.body(0) }
        assertEquals("222 0 <${posted.ids[0]}>", whole.status)
        assertTrue("whole without a cut: the article, then the dot", whole.ended)

        local.cut = LocalNntp.Cut(CUT)
        local.missing += posted.ids[2]
        Client(local).use { client ->
            // An article that isn't there is no answer to cut: the cut waits for one that is.
            client.send("BODY <${posted.ids[2]}>")
            assertEquals("430 no such article", client.line())
            assertNotNull("not spent on a 430", local.cut)

            val cut = client.body(0)
            assertEquals("222 0 <${posted.ids[0]}>", cut.status)
            assertArrayEquals("the article's first bytes, as they come whole", whole.article.copyOf(CUT), cut.article)
            assertFalse("no dot after a cut", cut.ended)
            assertTrue("the connection closed after the cut", client.atEnd())
        }
        assertNull("a cut once is spent", local.cut)
        assertEquals(1, local.cuts.get())

        // The same article again, on a new connection, comes whole, and so does the next one on it.
        Client(local).use { client ->
            val again = client.body(0)
            assertTrue(again.ended)
            assertArrayEquals(whole.article, again.article)
            assertTrue(client.body(1).ended)
        }
        assertEquals(1, local.cuts.get())
        assertEquals("BODY counts the cut answer as any other", 3, local.bodies.getValue(posted.ids[0]).get())
    }

    @Test
    fun aCutEveryTimeEndsEachBodyOfItsArticlesWhileItIsSetAndLeavesTheOthersWhole() {
        local.cut = LocalNntp.Cut(CUT, everyTime = true, ids = setOf(posted.ids[1]))
        repeat(2) { n ->
            Client(local).use { client ->
                assertTrue("an article the cut doesn't name comes whole ($n)", client.body(0).ended)
                val cut = client.body(1)
                assertEquals("222 0 <${posted.ids[1]}>", cut.status)
                assertEquals("cut after its bytes ($n)", CUT, cut.article.size)
                assertFalse(cut.ended)
                assertTrue("the connection closed after the cut ($n)", client.atEnd())
            }
        }
        assertEquals(2, local.cuts.get())
        assertNotNull("a cut every time stays until taken away", local.cut)

        local.cut = null
        Client(local).use { client -> assertTrue("with the cut taken away, the article comes whole", client.body(1).ended) }
        assertEquals(2, local.cuts.get())
        assertEquals(3, local.bodies.getValue(posted.ids[1]).get())
    }

    @Test
    fun aThrottledConnectionSendsNoFasterThanItsBytesASecondFromWhenTheThrottleIsSetUntilItIsLifted() {
        Client(local).use { client ->
            assertEquals("200 local server ready", client.greeting)
            val (free, freeMs) = timed { client.body(0) }
            local.throttle = RATE
            val (throttled, throttledMs) = timed { client.body(0) }
            local.throttle = null
            val (lifted, liftedMs) = timed { client.body(0) }

            assertTrue(free.ended && throttled.ended && lifted.ended)
            assertArrayEquals("the throttle changes when bytes come, not which", free.article, throttled.article)
            val least = leastMs(0)
            assertTrue("throttled: $throttledMs ms for ${answerBytes(0)} bytes at $RATE bytes a second", throttledMs >= least)
            assertTrue("before the throttle: $freeMs ms, against at least $least ms throttled", freeMs < least / 2)
            assertTrue("after it: $liftedMs ms, against at least $least ms throttled", liftedMs < least / 2)
        }
    }

    @Test
    fun eachConnectionHasAThrottleOfItsOwn() {
        local.throttle = RATE
        val clients = List(CONNECTIONS) { Client(local) }
        val threads = Executors.newFixedThreadPool(CONNECTIONS)
        try {
            val (times, allMs) = timed {
                clients.mapIndexed { i, client -> threads.submit(Callable { timed { client.body(i % PARTS) } }) }
                    .map { it.get(READ_LIMIT_MS.toLong(), TimeUnit.MILLISECONDS) }
            }
            times.forEachIndexed { i, (answer, ms) ->
                assertTrue("connection $i: whole", answer.ended)
                assertTrue("connection $i: $ms ms, against at least ${leastMs(i % PARTS)} ms at $RATE bytes a second", ms >= leastMs(i % PARTS))
            }
            // Each took about what one alone takes; a throttle the connections shared would take them four times that.
            val alone = (0 until CONNECTIONS).maxOf { leastMs(it % PARTS) }
            assertTrue("$CONNECTIONS connections at once: $allMs ms, against about $alone ms for one", allMs < 2 * alone)
        } finally {
            threads.shutdownNow()
            clients.forEach { it.close() }
        }
    }

    private companion object {
        const val SEED = 2307
        const val PARTS = 4
        const val PART = 60_000

        /** Where a cut ends a BODY: well inside a part's article. */
        const val CUT = 25_000

        /** Bytes a second for each throttled connection: about half a second for a part's answer. */
        const val RATE = 120_000L
        const val CONNECTIONS = 4

        /** How long a read waits for bytes that should come: only a failing test waits it out. */
        const val READ_LIMIT_MS = 20_000
    }
}
