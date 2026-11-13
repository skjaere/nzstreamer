package io.skjaere.usenet.link

import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The link's frames (L5): a 4-byte big-endian length, then that many bytes, 1 to [MAX_PAYLOAD], of UTF-8 JSON with its
 * type in `t` ([LinkCodec]). Anything else closes the connection. An NZB (S3) will cross in chunks of its own, never in
 * a bigger frame.
 */
object LinkFrames {
    const val MAX_PAYLOAD = 65_536

    internal const val HEADER = 4

    /** [payload] framed: its length, then it; null when it is empty or bigger than a frame. */
    fun encode(payload: ByteArray): ByteArray? {
        if (payload.isEmpty() || payload.size > MAX_PAYLOAD) return null
        val n = payload.size
        val framed = ByteArray(HEADER + n)
        framed[0] = (n ushr 24).toByte()
        framed[1] = (n ushr 16).toByte()
        framed[2] = (n ushr 8).toByte()
        framed[3] = n.toByte()
        System.arraycopy(payload, 0, framed, HEADER, n)
        return framed
    }
}

/** Why a frame was refused; the connection closes. */
enum class FrameError {
    /** A length of 0. */
    Empty,

    /** A length over [LinkFrames.MAX_PAYLOAD]: refused from the length alone, before a byte of the rest is read. */
    TooBig,

    /** Not complete within [LinkLimits.frameMillis] of its first byte, or the idle limit, whichever came first. */
    TimedOut,

    /** The stream ended, or the connection went, inside it. */
    Cut,
}

/** What the next read of a connection found. None holds more than a frame's bytes, and none prints them. */
internal sealed interface FrameRead {
    /** A whole frame. */
    class Frame(val payload: ByteArray) : FrameRead {
        override fun toString() = "Frame"
    }

    /** The stream ended, or the connection went, between frames. */
    data object End : FrameRead

    /** No frame began within [LinkLimits.idleMillis] of the last complete one. */
    data object Idle : FrameRead

    data class Broken(val error: FrameError) : FrameRead
}

/**
 * Reads [socket]'s frames, one at a time, against two deadlines: [LinkLimits.frameMillis] from a frame's first byte,
 * and [LinkLimits.idleMillis] from the last complete frame (from now, for the first). Before each read from the socket
 * its timeout is set to the time left, so a peer that sends a byte now and then can't stretch either.
 */
internal class FrameReader(private val socket: Socket, private val limits: LinkLimits, private val clock: () -> Long) {
    private val input = socket.getInputStream()
    private val buffer = ByteArray(BUFFER)
    private var pos = 0
    private var end = 0
    private var lastFrame = clock()

    fun read(): FrameRead {
        val idleUntil = lastFrame + limits.idleMillis
        try {
            if (pos == end && !fill(idleUntil)) return FrameRead.End
        } catch (_: SocketTimeoutException) {
            return FrameRead.Idle
        } catch (_: IOException) {
            return FrameRead.End
        }
        val deadline = minOf(clock() + limits.frameMillis, idleUntil)
        return try {
            frame(deadline).also { if (it is FrameRead.Frame) lastFrame = clock() }
        } catch (_: SocketTimeoutException) {
            FrameRead.Broken(FrameError.TimedOut)
        } catch (_: IOException) {
            FrameRead.Broken(FrameError.Cut)
        }
    }

    /** The frame whose first byte is buffered. */
    private fun frame(deadline: Long): FrameRead {
        var length = 0L
        repeat(LinkFrames.HEADER) {
            if (pos == end && !fill(deadline)) return FrameRead.Broken(FrameError.Cut)
            length = (length shl 8) or (buffer[pos++].toLong() and 0xff)
        }
        if (length == 0L) return FrameRead.Broken(FrameError.Empty)
        if (length > LinkFrames.MAX_PAYLOAD) return FrameRead.Broken(FrameError.TooBig)
        val payload = ByteArray(length.toInt())
        var filled = 0
        while (filled < payload.size) {
            if (pos == end && !fill(deadline)) return FrameRead.Broken(FrameError.Cut)
            val chunk = minOf(payload.size - filled, end - pos)
            System.arraycopy(buffer, pos, payload, filled, chunk)
            pos += chunk
            filled += chunk
        }
        return FrameRead.Frame(payload)
    }

    /** Reads what the socket has into the buffer by [until]; false at the end of the stream. */
    private fun fill(until: Long): Boolean {
        val left = until - clock()
        if (left <= 0) throw SocketTimeoutException()
        // 0 would wait for ever.
        socket.soTimeout = left.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        val n = input.read(buffer)
        if (n <= 0) return false
        pos = 0
        end = n
        return true
    }

    private companion object {
        const val BUFFER = 8192
    }
}
