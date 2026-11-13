package io.skjaere.usenet.serve

import java.io.Closeable

/**
 * A file the server serves: [size] bytes of [mimeType], at a path that ends `v.<extension>` (the container's, never the
 * file's name), read from [source] with at most [ServeLimits.upstreams] streams of it open at once (L56).
 */
class ServedFile(val size: Long, val mimeType: String, val extension: String, val source: ByteSource) {
    init {
        require(size >= 0) { "a size of $size" }
        require(extension.length in 1..8 && extension.all { it in 'a'..'z' || it in '0'..'9' }) { "an extension the path can't hold" }
        require(mimeType.isNotBlank() && mimeType.all { it in ' '..'~' }) { "a type a header can't hold" }
    }

    override fun toString() = "ServedFile($size bytes, $mimeType)"
}

/** Where a served file's bytes come from: the app's adapter over the Usenet stream, a test's made-up bytes. */
fun interface ByteSource {
    /**
     * The file's bytes from [position] to its end. A stretch of them no provider had comes as zeros, and [gaps] hears of it
     * (as offsets in the file, an inclusive range) before its zeros are read; from any thread, once or more. Throws an
     * IOException when it can't open.
     */
    fun open(position: Long, gaps: (LongRange) -> Unit): Upstream
}

/** An open stretch of a served file, read on the server's thread for one connection at a time. */
interface Upstream : Closeable {
    /**
     * Up to [length] bytes into [buffer] from [offset]: how many came; 0 when none came within [waitMillis], so the server
     * can look at its connection and ask again; -1 at the file's end. Throws an IOException when they can't be read: the
     * provider failed, or this was closed meanwhile.
     */
    fun read(buffer: ByteArray, offset: Int, length: Int, waitMillis: Long): Int

    /** Stops the download; from any thread, also while a read waits, which then ends. Closing twice does nothing. */
    override fun close()
}
