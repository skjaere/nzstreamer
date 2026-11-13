package io.skjaere.usenet.link

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The link's frames (L5) over a plain socket on loopback: a 4-byte big-endian length, then 1 to 65,536 bytes; each frame
 * complete within its deadline from its first byte, and some frame within the idle limit. TLS carries them the same way.
 */
class LinkFramesTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val open = mutableListOf<Closeable>()

    @AfterEach
    fun closeAll() {
        open.forEach {
            try {
                it.close()
            } catch (_: IOException) {
                // Closed already.
            }
        }
    }

    /** Two ends of a connection: the one written to by the test, and the one a [FrameReader] reads. */
    private fun pair(): Pair<Socket, Socket> {
        val server = ServerSocket(0, 8, loopback).also { open += it }
        val writer = Socket().also { open += it }
        writer.connect(InetSocketAddress(loopback, server.localPort), 2_000)
        writer.tcpNoDelay = true
        val reader = server.accept().also { open += it }
        return writer to reader
    }

    private fun reader(socket: Socket, limits: LinkLimits = LinkLimits()) = FrameReader(socket, limits, ::monotonicMillis)

    private fun Socket.send(bytes: ByteArray) {
        getOutputStream().apply { write(bytes); flush() }
    }

    private fun Socket.send(vararg bytes: Int) = send(ByteArray(bytes.size) { bytes[it].toByte() })

    private fun millisSince(started: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    @Test
    fun `a frame is its length in four big-endian bytes, then its bytes`() {
        val ping = """{"t":"ping"}""".toByteArray()
        assertContentEquals(byteArrayOf(0, 0, 0, 12) + ping, LinkFrames.encode(ping))
        val biggest = ByteArray(LinkFrames.MAX_PAYLOAD) { 'a'.code.toByte() }
        val framed = LinkFrames.encode(biggest)!!
        assertContentEquals(byteArrayOf(0, 1, 0, 0), framed.copyOf(4))
        assertEquals(4 + 65_536, framed.size)

        val (writer, socket) = pair()
        writer.send(LinkFrames.encode(ping)!! + framed)
        val reader = reader(socket)
        assertContentEquals(ping, assertIs<FrameRead.Frame>(reader.read()).payload)
        assertContentEquals(biggest, assertIs<FrameRead.Frame>(reader.read()).payload)
    }

    @Test
    fun `0 and 65,537 bytes are refused, written or read`() {
        assertNull(LinkFrames.encode(ByteArray(0)))
        assertNull(LinkFrames.encode(ByteArray(65_537)))

        val (writer, socket) = pair()
        writer.send(0, 0, 0, 0)
        assertEquals(FrameRead.Broken(FrameError.Empty), reader(socket).read())

        // Refused from the length alone: nothing after it is waited for.
        for (length in listOf(byteArrayOf(0, 1, 0, 1), byteArrayOf(-1, -1, -1, -1), byteArrayOf(-128, 0, 0, 0))) {
            val (w, s) = pair()
            w.send(length)
            val started = System.nanoTime()
            assertEquals(FrameRead.Broken(FrameError.TooBig), reader(s).read(), length.contentToString())
            assertTrue(millisSince(started) < 1_000, "waited for a body")
        }
    }

    @Test
    fun `a frame split across reads is read whole, and two in one write are two`() {
        val (writer, socket) = pair()
        val payload = """{"t":"status","session":"s-1","state":"playing","positionMs":1930000}""".toByteArray()
        val framed = LinkFrames.encode(payload)!!
        thread(isDaemon = true) {
            // The length in two pieces, the bytes in three.
            for (piece in listOf(0..1, 2..3, 4..10, 11..30, 31 until framed.size)) {
                writer.send(framed.sliceArray(piece))
                Thread.sleep(50)
            }
            writer.send(framed + framed)
        }
        val reader = reader(socket)
        repeat(3) { assertContentEquals(payload, assertIs<FrameRead.Frame>(reader.read()).payload, "frame $it") }
    }

    @Test
    fun `a peer that stops mid-frame times out at the frame's deadline`() {
        val (writer, socket) = pair()
        val framed = LinkFrames.encode(ByteArray(100) { 'x'.code.toByte() })!!
        writer.send(framed.copyOf(50))
        val started = System.nanoTime()
        assertEquals(FrameRead.Broken(FrameError.TimedOut), reader(socket, LinkLimits(frameMillis = 400)).read())
        val elapsed = millisSince(started)
        assertTrue(elapsed in 300..2_000, "timed out after $elapsed ms")
    }

    @Test
    fun `a peer that sends one byte every third of the deadline inside a frame is closed at the deadline`() {
        val (writer, socket) = pair()
        val framed = LinkFrames.encode(ByteArray(40) { 'x'.code.toByte() })!!
        thread(isDaemon = true) {
            try {
                // 44 bytes at 200 ms each would take 8.8 s; every read is well within the 600 ms deadline.
                for (b in framed) {
                    writer.send(byteArrayOf(b))
                    Thread.sleep(200)
                }
            } catch (_: IOException) {
                // Closed by the test.
            }
        }
        val started = System.nanoTime()
        assertEquals(FrameRead.Broken(FrameError.TimedOut), reader(socket, LinkLimits(frameMillis = 600)).read())
        val elapsed = millisSince(started)
        assertTrue(elapsed in 500..2_000, "closed after $elapsed ms")
    }

    @Test
    fun `the stream's end between frames is the end, inside one it is cut`() {
        val (writer, socket) = pair()
        writer.send(LinkFrames.encode("""{"t":"pong"}""".toByteArray())!!)
        writer.close()
        val reader = reader(socket)
        assertIs<FrameRead.Frame>(reader.read())
        assertEquals(FrameRead.End, reader.read())

        val (w, s) = pair()
        w.send(LinkFrames.encode(ByteArray(10))!!.copyOf(8))
        w.close()
        assertEquals(FrameRead.Broken(FrameError.Cut), reader(s).read())

        // In the length too.
        val (w2, s2) = pair()
        w2.send(0, 0)
        w2.close()
        assertEquals(FrameRead.Broken(FrameError.Cut), reader(s2).read())
    }

    @Test
    fun `a link without a complete frame for the idle limit is idle, whatever is under way`() {
        val (writer, socket) = pair()
        val started = System.nanoTime()
        assertEquals(FrameRead.Idle, reader(socket, LinkLimits(idleMillis = 400)).read())
        assertTrue(millisSince(started) in 300..2_000)

        // A frame that begins late is held to the idle limit too, not given its own whole deadline on top.
        val (w, s) = pair()
        val limits = LinkLimits(idleMillis = 800, frameMillis = 5_000)
        val reader = reader(s, limits)
        val framed = LinkFrames.encode(ByteArray(40))!!
        thread(isDaemon = true) {
            try {
                Thread.sleep(400)
                for (b in framed) {
                    w.send(byteArrayOf(b))
                    Thread.sleep(100)
                }
            } catch (_: IOException) {
                // Closed by the test.
            }
        }
        val begun = System.nanoTime()
        assertEquals(FrameRead.Broken(FrameError.TimedOut), reader.read())
        val elapsed = millisSince(begun)
        assertTrue(elapsed in 700..2_500, "closed after $elapsed ms")
        writer.close()
    }

    @Test
    fun `each complete frame starts the idle limit again`() {
        val (writer, socket) = pair()
        val reader = reader(socket, LinkLimits(idleMillis = 500))
        val frame = LinkFrames.encode("""{"t":"ping"}""".toByteArray())!!
        thread(isDaemon = true) {
            repeat(5) {
                Thread.sleep(250)
                writer.send(frame)
            }
        }
        // 1.25 s of frames 250 ms apart: never 500 ms without one.
        repeat(5) { assertIs<FrameRead.Frame>(reader.read(), "frame $it") }
        assertEquals(FrameRead.Idle, reader.read())
    }
}
