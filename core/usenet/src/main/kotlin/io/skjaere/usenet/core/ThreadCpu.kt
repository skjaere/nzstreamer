package io.skjaere.usenet.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * A thread as its stat line in /proc has it: its id, its name and the CPU it has used so far, user and system, in clock
 * ticks. The name is the kernel's, at most 15 bytes: ART keeps the first 15 characters of a thread's name, or the last
 * 15 of a long one with a dot in it (the main thread's, the package name).
 */
class TaskStat(val tid: Int, val name: String, val ticks: Long)

/**
 * Where [parseStat] found a stat line's fields: the id, the name's bytes (from [nameFrom] up to [nameTo]) and the CPU.
 * Fields, not properties, as [ThreadCpuReader.Task]'s are.
 */
internal class StatFields {
    @JvmField var tid = 0
    @JvmField var nameFrom = 0
    @JvmField var nameTo = 0
    @JvmField var ticks = 0L
}

/**
 * Reads a /proc/<pid>/stat or /proc/<pid>/task/<tid>/stat line ("1234 (name) S 1 …") from its first [length] bytes into
 * [into]; false for anything that isn't a whole one. The name sits between the first "(" and the last ")": it may hold
 * spaces and parentheses of its own, so the fields are counted from that last ")". utime and stime are the 14th and
 * 15th. Makes nothing.
 */
internal fun parseStat(line: ByteArray, length: Int, into: StatFields): Boolean {
    var open = 0
    while (open < length && line[open] != OPEN) open++
    // "1234 (": the id, then a space.
    if (open < 2 || open >= length || line[open - 1] != SPACE) return false
    val tid = digits(line, 0, open - 1)
    if (tid < 0 || tid > Int.MAX_VALUE) return false
    // The kernel keeps a name to 15 bytes, so its ")" is within 16 of the "(", and the fields after it are numbers,
    // which hold none: looking back from there finds it at once. Back from the end, for a name kept longer.
    return fields(line, length, open, lastClose(line, open, minOf(length - 1, open + 16)), tid.toInt(), into) ||
        fields(line, length, open, lastClose(line, open, length - 1), tid.toInt(), into)
}

/** The last ")" after [open], looking back from [from]; -1 if there is none. */
private fun lastClose(line: ByteArray, open: Int, from: Int): Int {
    var at = from
    while (at > open) {
        if (line[at] == CLOSE) return at
        at--
    }
    return -1
}

/** Reads the fields after the name, ending at [close], into [into]; false if they aren't a stat line's. */
private fun fields(line: ByteArray, length: Int, open: Int, close: Int, tid: Int, into: StatFields): Boolean {
    if (close < 0 || close + 1 >= length || line[close + 1] != SPACE) return false
    // From the 3rd field (the state): utime is the 12th of them, stime the 13th.
    var at = close + 2
    var utime = 0L
    for (field in 0..STIME) {
        val start = at
        while (at < length && line[at] != SPACE && line[at] != NEWLINE) at++
        if (at == start) return false
        if (field == UTIME) {
            utime = digits(line, start, at)
            if (utime < 0) return false
        } else if (field == STIME) {
            val stime = digits(line, start, at)
            if (stime < 0) return false
            into.tid = tid
            into.nameFrom = open + 1
            into.nameTo = close
            into.ticks = utime + stime
            return true
        }
        at++
    }
    return false
}

/** The thread or process a stat line is about, from its first [length] bytes; null for anything that isn't a whole one ([parseStat]). */
fun parseTaskStat(line: ByteArray, length: Int = line.size): TaskStat? {
    val fields = StatFields()
    if (!parseStat(line, length, fields)) return null
    return TaskStat(fields.tid, String(line, fields.nameFrom, fields.nameTo - fields.nameFrom, Charsets.UTF_8), fields.ticks)
}

fun parseTaskStat(line: String): TaskStat? = line.toByteArray().let { parseTaskStat(it, it.size) }

/** The number written in [line] from [from] up to [to], digits only; -1 for anything else, or one too long for a Long. */
private fun digits(line: ByteArray, from: Int, to: Int): Long {
    if (to <= from || to - from > 18) return -1
    var n = 0L
    for (i in from until to) {
        val d = line[i] - ZERO
        if (d !in 0..9) return -1
        n = n * 10 + d
    }
    return n
}

/**
 * The CPU a thread has used, in nanoseconds, from the first [length] bytes of its schedstat ("123456789 2345 67": the
 * time it ran, the time it waited to, and its turns on a CPU); -1 for anything else.
 */
internal fun parseSchedstat(line: ByteArray, length: Int): Long {
    var n = 0L
    var at = 0
    while (at < length && at < 18) {
        val d = line[at] - ZERO
        if (d !in 0..9) return if (at > 0 && line[at] == SPACE) n else -1
        n = n * 10 + d
        at++
    }
    return -1
}

private const val OPEN = '('.code.toByte()
private const val CLOSE = ')'.code.toByte()
private const val SPACE = ' '.code.toByte()
private const val NEWLINE = '\n'.code.toByte()
private const val ZERO = '0'.code.toByte()
private const val UTIME = 11
private const val STIME = 12

/**
 * What a thread works at, as its name says: the groups the stream-cost line ([StreamCost]) splits the CPU into. Names
 * are matched as /proc has them, cut to 15 bytes; a name in full (a thread on the JVM) falls in the same group.
 */
enum class ThreadKind(val label: String) {
    /**
     * kotlinx.coroutines' workers ("DefaultDispatcher-worker-N"). Dispatchers.IO, its views and Dispatchers.Default share
     * the one pool of workers, each worker taking work of any, so no thread's name tells them apart: the stream machinery
     * runs on IO's (the NNTP client's reads and their waits for the server, TLS and yEnc on ktor-nntp-client's view of it,
     * the segment cache on IO), and little else runs on any while a stream plays.
     */
    Workers("workers"),

    /** Media3's loader threads: the extractor and the DataSource's reads. */
    Loader("loader"),

    /** Media3's playback thread and its others: software decoders, the background thread. */
    Playback("playback"),

    /** MediaCodec's threads in the app, Media3's asynchronous MediaCodec adapter's among them. */
    Codec("codec"),

    /** ART's garbage collector and its finalizer and reference daemons. */
    Gc("GC"),

    /** ART's JIT compiler and its profile saver. */
    Jit("JIT"),

    /**
     * Everything else: the main thread, binder and render threads, and the threads that ended within the window. What
     * the process used beyond the other kinds' threads: a third of a play's threads, not read for their CPU.
     */
    Rest("rest"),
    ;

    companion object {
        private val codec = listOf("ExoPlayer:Media", "MediaCodec", "CodecLooper", "CCodec")
        private val gc = listOf("HeapTaskDaemon", "FinalizerDaemon", "FinalizerWatchd", "ReferenceQueueD")

        fun of(name: String): ThreadKind = when {
            name.startsWith("DefaultDispatch") -> Workers
            name.startsWith("ExoPlayer:Load") -> Loader
            codec.any(name::startsWith) -> Codec
            name.startsWith("ExoPlayer:") -> Playback
            gc.any(name::startsWith) -> Gc
            name.startsWith("Jit thread pool") || name.startsWith("Profile Saver") -> Jit
            else -> Rest
        }
    }
}

/**
 * This process's CPU as [ThreadCpuReader] read it, in nanoseconds: the whole process's so far ([processNanos]), what the
 * threads of each kind had used since the reader's first read ([kindNanos], by [ThreadKind.ordinal]; naught for
 * [ThreadKind.Rest], whose threads aren't read for it: theirs is what the process used beyond the others'), and the
 * threads of each kind alive then ([threads], likewise).
 */
class CpuSnapshot(val processNanos: Long, val kindNanos: LongArray, val threads: IntArray)

/** A /proc file kept open between reads, each read from its start: /proc writes it anew for each. */
interface KeptFile : AutoCloseable {
    /** Reads it from its start into [buffer], as much as fits: the bytes read, or -1 once what it tells of is gone (a thread that ended). */
    fun readFromStart(buffer: ByteArray): Int

    override fun close()
}

/** Opens [KeptFile]s; null for a file that can't be opened (a thread that ended since it was listed). */
fun interface KeptFiles {
    fun open(path: String): KeptFile?

    companion object {
        /** The JVM's own: a seek and a read for each. Android's own calls are leaner (the player module's). */
        val jvm = KeptFiles { path ->
            try {
                val file = RandomAccessFile(path, "r")
                object : KeptFile {
                    override fun readFromStart(buffer: ByteArray): Int = try {
                        file.seek(0)
                        file.read(buffer)
                    } catch (e: IOException) {
                        -1
                    }

                    override fun close() {
                        try {
                            file.close()
                        } catch (e: IOException) {
                            // Nothing was written: there is nothing to lose.
                        }
                    }
                }
            } catch (e: IOException) {
                null
            }
        }
    }
}

/**
 * Reads this process's CPU from [proc] (/proc/self): each live thread's, added up by kind, and the whole process's, which
 * counts the threads that have ended too. A thread's CPU comes from its schedstat, in nanoseconds: a line of three
 * numbers, quicker to make and to read than its stat, which gives its name (read again every [RENAMES] reads, for a
 * thread that renamed itself, a thirtieth of the threads' at each) and its CPU where the kernel keeps no schedstat, in
 * ticks of 1/[ticksPerSecond] s (100 on Android and desktop Linux). [ThreadKind.Rest]'s threads are only counted, and
 * their names read: their CPU is the process's beyond the others'. What a thread used since the read before is added
 * to its kind as it is read, so comparing two reads is a sum for each kind, whatever threads came and went. [close] lets
 * the files go. Not for two threads at once.
 *
 * A read every 10 s is too seldom for the JIT to compile this: ART interprets it, and in a play on the emulator (65
 * threads, a debug build) a read took about half a millisecond of CPU, 0.9 ms before the rest's threads were left out.
 * Hence the rest left unread, each file kept open from one read to the next through [files] (on Android a read of one
 * kept open costs a quarter of an open, a read and a close), the threads read in the listing's order while it stays the
 * same, and fields where a property's getter would be a call.
 */
class ThreadCpuReader(
    private val proc: File = File("/proc/self"),
    private val ticksPerSecond: Long = 100,
    private val files: KeptFiles = KeptFiles.jvm,
) : AutoCloseable {
    private val tasks = File(proc, "task")
    private val taskPath = tasks.path
    // A stat line is about 300 bytes; the fields read come well before 512.
    private val buffer = ByteArray(512)
    private val fields = StatFields()
    private val nanosPerTick = 1_000_000_000 / ticksPerSecond

    /**
     * A thread's stat, kept open, and its schedstat if the kernel keeps one; its name as last read, made a string (and
     * grouped) again only when it changes; and the CPU it had used then, in nanoseconds, from which the next read adds
     * what it has used since.
     */
    private class Task(
        @JvmField val stat: KeptFile,
        @JvmField val schedstat: KeptFile?,
        /** Which of the [RENAMES] reads in turn reads its name again. */
        @JvmField val turn: Long,
        @JvmField var name: String,
        @JvmField var nameBytes: ByteArray,
        @JvmField var kind: ThreadKind,
        @JvmField var nanos: Long,
        @JvmField var seen: Long,
    )

    /** By the task directory's name, the thread's id as the listing writes it: no number to make of it. */
    private val open = HashMap<String, Task>()

    /**
     * The listing as last read, and its threads in its order: while the listing is the same (the same threads, as through
     * most of a play), the threads are read in that order, without a lookup for each.
     */
    private var listed: Array<String>? = null
    private var inOrder: Array<Task?> = emptyArray()
    private val kindNanos = LongArray(ThreadKind.entries.size)
    private var processStat: KeptFile? = null
    private var reads = 0L
    private var opened = 0L

    /** Null when there is no /proc to read. */
    fun read(): CpuSnapshot? {
        val ids = tasks.list() ?: return null
        val round = ++reads
        val threads = IntArray(ThreadKind.entries.size)
        val same = ids.contentEquals(listed)
        if (!same) inOrder = arrayOfNulls(ids.size)
        var live = 0
        for (i in ids.indices) {
            val task = readTask(ids[i], if (same) inOrder[i] else open[ids[i]], round, threads)
            inOrder[i] = task
            if (task != null) live++
        }
        listed = ids
        // Threads no longer listed have ended: their files go.
        if (open.size > live) open.values.removeAll { task -> (task.seen != round).also { gone -> if (gone) task.close() } }
        // After the threads: the process's count then holds all they had used.
        val process = processStat ?: files.open(File(proc, "stat").path)?.also { processStat = it } ?: return null
        val n = process.readFromStart(buffer)
        if (n <= 0 || !parseStat(buffer, n, fields)) return null
        return CpuSnapshot(fields.ticks * nanosPerTick, kindNanos.copyOf(), threads)
    }

    /** The names of the threads the last [read] found, for a look at how they group. */
    fun names(): List<String> = open.values.map { it.name }

    /**
     * Reads the thread [id]'s CPU through its kept files ([known], null for a thread not seen before) and adds what it
     * used since the last read to its kind, counting it in [threads]; null if it has ended. A kept file that reads as
     * gone was a thread whose id the kernel has given a new one (or none): opened afresh once, the new one counts from
     * naught.
     */
    private fun readTask(id: String, known: Task?, round: Long, threads: IntArray): Task? {
        var task = known ?: openTask(id, round) ?: return null
        repeat(2) { attempt ->
            val turn = attempt > 0 || task !== known || round % RENAMES == task.turn
            // One of the rest, listed: alive, and nothing to read till its turn.
            if (!turn && task.kind == ThreadKind.Rest) {
                task.seen = round
                threads[REST]++
                return task
            }
            val nanos = if (turn || task.schedstat == null) readStat(task) else readSchedstat(task)
            if (nanos >= 0) {
                task.seen = round
                val kind = task.kind.ordinal
                if (nanos > task.nanos && kind != REST) kindNanos[kind] += nanos - task.nanos
                task.nanos = nanos
                threads[kind]++
                return task
            }
            task.close()
            open.remove(id)
            if (attempt == 0) task = openTask(id, round) ?: return null
        }
        return null
    }

    private fun openTask(id: String, round: Long): Task? {
        val stat = files.open("$taskPath/$id/stat") ?: return null
        return Task(stat, files.open("$taskPath/$id/schedstat"), opened++ % RENAMES, "", EMPTY, ThreadKind.of(""), 0, round).also { open[id] = it }
    }

    /** The thread's CPU from its schedstat; -1 if it has ended. */
    private fun readSchedstat(task: Task): Long {
        val n = task.schedstat!!.readFromStart(buffer)
        return if (n > 0) parseSchedstat(buffer, n) else -1
    }

    /**
     * The thread's name from its stat, and its CPU: from its schedstat, which counts it more finely, where there is one,
     * else as the stat counts it. -1 if it has ended.
     */
    private fun readStat(task: Task): Long {
        val n = task.stat.readFromStart(buffer)
        if (n <= 0 || !parseStat(buffer, n, fields)) return -1
        // A thread that renamed itself works at what its new name says from now on.
        if (!sameName(task.nameBytes)) {
            task.nameBytes = buffer.copyOfRange(fields.nameFrom, fields.nameTo)
            task.name = String(task.nameBytes, Charsets.UTF_8)
            task.kind = ThreadKind.of(task.name)
        }
        return if (task.schedstat != null) readSchedstat(task) else fields.ticks * nanosPerTick
    }

    private fun sameName(bytes: ByteArray): Boolean {
        if (bytes.size != fields.nameTo - fields.nameFrom) return false
        for (i in bytes.indices) if (bytes[i] != buffer[fields.nameFrom + i]) return false
        return true
    }

    private fun Task.close() {
        stat.close()
        schedstat?.close()
    }

    /** Lets the files go; the next read opens them again, and its threads count all they have used, as at the first. */
    override fun close() {
        open.values.forEach { it.close() }
        open.clear()
        listed = null
        inOrder = emptyArray()
        processStat?.close()
        processStat = null
    }

    private companion object {
        val EMPTY = ByteArray(0)

        /** Every this many reads, each thread's name is read again: 5 minutes, at a read every 10 s. */
        const val RENAMES = 30L
        val REST = ThreadKind.Rest.ordinal
    }
}
