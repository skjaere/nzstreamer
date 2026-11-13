package io.skjaere.usenet.app.serve

import io.skjaere.usenet.serve.Json
import io.skjaere.usenet.serve.SpikeStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ServedStreamSpikeTest]'s stack on 127.0.0.1 alone, so that a Shield session spends no time on a stand-in that doesn't
 * serve: through the relay and the direct server, each file comes byte-exact through [ServedStream] and its stand-in
 * stream; armed zeros come as zeros, counted once as a gap; an armed failure, the stand-in's stream ending there, is the
 * server's own close at its byte (L76).
 * Nothing here reaches the home network.
 */
class SpikeStreamTest {
    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val size = 2 * 1024 * 1024
    private val film = Random(6).nextBytes(size)
    private val lines = CopyOnWriteArrayList<String>()
    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var stack: SpikeStack

    /** The stack over [folder]'s copy of the film; [endsShort] as [SpikeOpener]'s. */
    private fun start(folder: File, endsShort: Boolean = false) {
        File(folder, "b.mkv").writeBytes(film)
        stack = SpikeStack(
            loopback, folder, relayPort = 0, controlPort = 0, directPort = 0, directPeers = { it.isLoopbackAddress }, out = { lines += it },
            source = { file, name, faults -> servedStream(file, name, faults, scope, endsShort) },
        )
    }

    @AfterEach
    fun stop() {
        if (::stack.isInitialized) stack.close()
        scope.cancel()
    }

    @Suppress("UNCHECKED_CAST")
    private fun ctl(path: String, body: Map<String, Any?>? = null): Map<String, Any?> {
        val connection = URL("http://127.0.0.1:${stack.controlAddress.port}$path").openConnection() as HttpURLConnection
        try {
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.outputStream.use { it.write(Json.write(body).toByteArray()) }
            }
            assertEquals(200, connection.responseCode)
            return Json.parse(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>
        } finally {
            connection.disconnect()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun url(kind: String) = (ctl("/ctl/info")[kind] as Map<String, String>).getValue("b.mkv")

    /** `bytes=<from>-` of [url]: the status, the body as far as it came. */
    private fun get(url: String, from: Long = 0): Pair<Int, ByteArray> {
        val uri = URI(url)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, uri.port), 2_000)
            socket.soTimeout = 10_000
            socket.getOutputStream().write("GET ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nRange: bytes=$from-\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            val input = BufferedInputStream(socket.getInputStream())
            val head = ByteArrayOutputStream()
            while (!head.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) head.write(input.read().also { assertTrue(it >= 0, "no answer") })
            val status = head.toString(Charsets.ISO_8859_1).substringBefore("\r\n").split(' ')[1].toInt()
            val body = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    body.write(buffer, 0, n)
                }
            } catch (_: IOException) {
                // Reset: the body as far as it came.
            }
            return status to body.toByteArray()
        }
    }

    private fun counters(): String = (ctl("/ctl/info")["counters"] as Map<*, *>)["b.mkv"].toString()

    @Test
    fun `a file comes byte-exact through the app's adapter, relayed and direct`(@TempDir folder: File) {
        start(folder)
        val relayed = get(url("urls"), 1_000)
        assertEquals(206, relayed.first)
        assertContentEquals(film.copyOfRange(1_000, size), relayed.second)
        val direct = get(url("direct"))
        assertEquals(206, direct.first)
        assertContentEquals(film, direct.second)
    }

    @Test
    fun `armed zeros come through as zeros and the server counts one gap`(@TempDir folder: File) {
        start(folder)
        ctl("/ctl/fault", mapOf("file" to "b.mkv", "zero" to listOf(listOf(100_000, 400_000))))
        val (status, body) = get(url("urls"))
        assertEquals(206, status)
        assertContentEquals(film.copyOf().apply { fill(0, 100_000, 400_000) }, body)
        assertTrue("gaps=1" in counters(), counters())
    }

    /**
     * The stand-in's stream ends at the failure, so the server has every byte before it. Closed with the failure as its
     * cause, as the engine's is, the channel drops what ServedStream hasn't taken yet: under load the close comes short.
     */
    @Test
    fun `an armed failure is the server's own close at its byte`(@TempDir folder: File) {
        start(folder, endsShort = true)
        ctl("/ctl/fault", mapOf("file" to "b.mkv", "fail" to listOf(mapOf("offset" to 300_000))))
        val (status, body) = get(url("direct"))
        assertEquals(206, status)
        assertContentEquals(film.copyOf(300_000), body)
        assertTrue("upstreamFailures=1" in counters(), counters())
        assertTrue(lines.any { "\"fault_fired\"" in it && "\"fail\"" in it }, "$lines")
    }
}
