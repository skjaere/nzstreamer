package io.skjaere.usenet.serve

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The faults item 6(b)'s spike arms per file, as the receiver spike's `server.py` took them on `/ctl/fault` (so its driver
 * keeps its requests): `{"file": "b.mkv", "stall": [{"offset": N, "seconds": S, "count": 1}], "cut": [{"offset": N,
 * "rst": true, "count": 1}], "zero": [[start, end]], "e416": [{"min_start": A, "max_start": B, "pretend_size": P,
 * "count": 1}]}`, `zero`'s ends exclusive. Two more: `"fail": [{"offset": N, "count": 1}]`, the source failing at a byte
 * (the server's own close, L76), and `"latency": {"part": 3840000, "ms": 200, "stallEvery": 0, "stallSeconds": 5}`, a wait
 * after each part as Usenet's articles come, and a longer one every so many parts. Stalls, zeros, failures and latency act
 * on the server's source ([FaultySource]); cuts and 416s on the relay in front of it ([FaultRelay]).
 */
class SpikeFaults(private val log: (Map<String, Any?>) -> Unit) {
    /** One armed fault: fires [count] times. */
    class Fault(val offset: Long, val seconds: Double, val rst: Boolean, val minStart: Long, val maxStart: Long, val pretendSize: Long?, count: Int) {
        val left = AtomicInteger(count)
        val armed: Boolean get() = left.get() > 0

        /** Fires it once if it has a firing left. */
        fun take(): Boolean = left.getAndUpdate { maxOf(it - 1, 0) } > 0
    }

    class Latency(val part: Long, val millis: Long, val stallEvery: Long, val stallMillis: Long)

    class FileFaults(
        val stalls: List<Fault>,
        val cuts: List<Fault>,
        val fails: List<Fault>,
        val e416: List<Fault>,
        val zeros: List<LongRange>,
        val latency: Latency?,
        val spec: Map<String, Any?>,
    )

    private val byFile = ConcurrentHashMap<String, FileFaults>()

    operator fun get(file: String): FileFaults? = byFile[file]

    /** What is armed, as it was asked for. */
    fun specs(): Map<String, Any?> = byFile.mapValues { it.value.spec }

    /** Arms [spec]'s faults for its file, in place of any before. */
    fun arm(spec: Map<String, Any?>) {
        val file = spec["file"] as? String ?: throw IllegalArgumentException("a fault names no file")
        fun faults(kind: String) = (spec[kind] as? List<*>).orEmpty().map { item ->
            val f = item as? Map<*, *> ?: throw IllegalArgumentException("a $kind that isn't an object")
            fun long(key: String, default: Long) = (f[key] as? Number)?.toLong() ?: default
            Fault(
                offset = long("offset", -1), seconds = (f["seconds"] as? Number)?.toDouble() ?: 0.0, rst = f["rst"] == true,
                minStart = long("min_start", 0), maxStart = long("max_start", Long.MAX_VALUE),
                pretendSize = (f["pretend_size"] as? Number)?.toLong(), count = long("count", 1).toInt(),
            )
        }
        val zeros = (spec["zero"] as? List<*>).orEmpty().map { pair ->
            val (start, end) = (pair as List<*>).map { (it as Number).toLong() }
            start until end
        }
        val latency = (spec["latency"] as? Map<*, *>)?.let { l ->
            fun long(key: String, default: Long) = (l[key] as? Number)?.toLong() ?: default
            Latency(long("part", 3_840_000), long("ms", 200), long("stallEvery", 0), ((l["stallSeconds"] as? Number)?.toDouble() ?: 5.0).times(1000).toLong())
        }
        val ignored = spec.keys - setOf("file", "stall", "cut", "fail", "e416", "zero", "latency")
        byFile[file] = FileFaults(faults("stall"), faults("cut"), faults("fail"), faults("e416"), zeros, latency, spec)
        log(mapOf("ev" to "fault", "file" to file, "spec" to spec, "ignored" to ignored.toList().ifEmpty { null }))
    }

    fun clear() {
        byFile.clear()
        log(mapOf("ev" to "fault_clear"))
    }

    fun fired(file: String, kind: String, at: Long, extra: Map<String, Any?> = emptyMap()) =
        log(mapOf("ev" to "fault_fired", "file" to file, "kind" to kind, "at" to at) + extra)
}

/**
 * A media file as the server's source, under [faults] armed for [name]: stalls, failures, zeros and latency, at the bytes
 * the source gives (a stretch of zeros told to the server as a gap, as the engine tells missing articles).
 */
class FaultySource(private val file: File, private val name: String, private val faults: SpikeFaults) : ByteSource {
    private val size = file.length()

    override fun open(position: Long, gaps: (LongRange) -> Unit): Upstream = Stream(position, gaps)

    private inner class Stream(private var position: Long, private val sink: (LongRange) -> Unit) : Upstream {
        private val input = RandomAccessFile(file, "r")
        private val closed = CountDownLatch(1)
        private val told = mutableSetOf<LongRange>()
        /** Nothing comes before this (nanoTime): a stall, or latency. */
        private var holdUntil = 0L

        override fun read(buffer: ByteArray, offset: Int, length: Int, waitMillis: Long): Int {
            if (closed.count == 0L) throw IOException("closed")
            val left = holdUntil - System.nanoTime()
            if (left > 0) {
                closed.await(minOf(TimeUnit.NANOSECONDS.toMillis(left) + 1, waitMillis), TimeUnit.MILLISECONDS)
                if (closed.count == 0L) throw IOException("closed")
                if (System.nanoTime() < holdUntil) return 0
            }
            if (position >= size) return -1
            var n = minOf(length.toLong(), size - position)
            val armed = faults[name]
            if (armed != null) {
                // At a failure's byte: the source fails there, as a part of the wrong size does (L76).
                armed.fails.firstOrNull { it.offset == position && it.armed }?.let { fail ->
                    if (fail.take()) {
                        faults.fired(name, "fail", position)
                        throw IOException("an armed failure")
                    }
                }
                // At a stall's byte: nothing for its time, then on.
                armed.stalls.firstOrNull { it.offset == position && it.armed }?.let { stall ->
                    if (stall.take()) {
                        faults.fired(name, "stall", position, mapOf("seconds" to stall.seconds))
                        holdUntil = System.nanoTime() + (stall.seconds * 1e9).toLong()
                        return 0
                    }
                }
                // Short of the next armed byte, so each lands exactly.
                for (fault in armed.fails + armed.stalls) if (fault.armed && fault.offset > position && fault.offset < position + n) n = fault.offset - position
                armed.latency?.let { latency ->
                    val next = (position / latency.part + 1) * latency.part
                    if (position + n > next) n = next - position
                }
            }
            val got = read(buffer, offset, n, armed?.zeros.orEmpty())
            armed?.latency?.let { latency ->
                // A part ends: the next one's wait, and every so many parts a longer one.
                if (position % latency.part == 0L && position < size) {
                    val part = position / latency.part
                    val stall = if (latency.stallEvery > 0 && part % latency.stallEvery == 0L) latency.stallMillis else 0
                    holdUntil = System.nanoTime() + (latency.millis + stall) * 1_000_000
                    if (stall > 0) faults.fired(name, "latency-stall", position, mapOf("seconds" to stall / 1000.0))
                }
            }
            return got
        }

        private fun read(buffer: ByteArray, offset: Int, count: Long, zeros: List<LongRange>): Int {
            val end = position + count - 1
            for (zero in zeros) if (zero.first <= end && zero.last >= position && told.add(zero)) sink(zero)
            synchronized(input) {
                input.seek(position)
                input.readFully(buffer, offset, count.toInt())
            }
            for (zero in zeros) {
                val from = maxOf(zero.first, position)
                val to = minOf(zero.last, end)
                if (from <= to) buffer.fill(0, offset + (from - position).toInt(), offset + (to - position).toInt() + 1)
            }
            position += count
            return count.toInt()
        }

        override fun close() {
            if (closed.count == 0L) return
            closed.countDown()
            synchronized(input) { input.close() }
        }
    }
}
