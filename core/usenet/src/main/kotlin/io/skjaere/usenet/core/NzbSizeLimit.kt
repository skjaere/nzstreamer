package io.skjaere.usenet.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Upper bound for an NZB: it lists article ids, and even a 100 GB remux season pack is
 * 20-60 MB of XML. Anything bigger is a wrongly picked file (a video), not an NZB.
 */
const val MAX_NZB_BYTES: Long = 64_000_000

/** A file or stream is bigger than [limit]; [size] is its reported size, or null when unknown. */
class NzbTooLargeException(val size: Long?, val limit: Long) :
    IOException(if (size != null) "$size bytes is over the $limit-byte NZB limit" else "Over the $limit-byte NZB limit")

/** Rejects a file whose provider-reported [size] (null or negative when unknown) is over [limit]. */
fun checkNzbSize(size: Long?, limit: Long = MAX_NZB_BYTES) {
    if (size != null && size > limit) throw NzbTooLargeException(size, limit)
}

/**
 * Reads the whole stream as NZB bytes, throwing [NzbTooLargeException] as soon as it runs past
 * [limit], so a multi-GB file is never pulled into memory (a reported size can be missing or wrong).
 */
fun InputStream.readNzbBytes(limit: Long = MAX_NZB_BYTES): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val n = read(buffer)
        if (n < 0) return out.toByteArray()
        if (out.size().toLong() + n > limit) throw NzbTooLargeException(null, limit)
        out.write(buffer, 0, n)
    }
}
