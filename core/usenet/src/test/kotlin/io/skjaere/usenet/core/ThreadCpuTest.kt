package io.skjaere.usenet.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the threads' CPU for the stream-cost line: stat lines as the Shield's Android 11 and a desktop Linux write
 * them, names with spaces and parentheses of their own, the groups names fall in, and a /proc tree read whole.
 */
class ThreadCpuTest {
    @Test
    fun `a thread's stat line as the Shield writes it gives its id, name and CPU`() {
        val stat = assertNotNull(parseTaskStat(JIT))
        assertEquals(13829, stat.tid)
        assertEquals("Jit thread pool", stat.name)
        // utime 499 and stime 128, the 14th and 15th fields.
        assertEquals(627L, stat.ticks)
    }

    @Test
    fun `the main thread's line, named for the end of the package name, and a desktop kernel's longer line read the same`() {
        val main = assertNotNull(parseTaskStat(MAIN))
        assertEquals(Triple(13815, ".skjaere.usenet", 3763L), Triple(main.tid, main.name, main.ticks))
        val desktop = assertNotNull(parseTaskStat(DESKTOP))
        assertEquals(Triple(1715416, "cat", 3L), Triple(desktop.tid, desktop.name, desktop.ticks))
    }

    @Test
    fun `a name with spaces and parentheses of its own ends at the last parenthesis`() {
        // What looks like fields inside the name isn't counted as fields.
        val stat = assertNotNull(parseTaskStat("4242 (a) S 1 (b c) R 3344 3344 0 0 -1 1077952576 3295 0 0 0 46 6 0 0 20 0 58 0 121969785 15613095936 87009"))
        assertEquals("a) S 1 (b c", stat.name)
        assertEquals(52L, stat.ticks)
        assertEquals("", assertNotNull(parseTaskStat("7 () S 1 1 0 0 -1 0 0 0 0 0 5 6 0 0 20 0 1 0")).name)
        assertEquals(")", assertNotNull(parseTaskStat("7 ()) S 1 1 0 0 -1 0 0 0 0 0 5 6 0 0 20 0 1 0")).name)
    }

    @Test
    fun `what isn't a whole stat line is no thread`() {
        listOf(
            "",
            "13829",
            "13829 (Jit thread pool",
            "13829 (Jit thread pool) S 3344 3344 0 0 -1 1077952576 179948 0 1 0 499",
            "13829 (Jit thread pool) S 3344 3344 0 0 -1 1077952576 179948 0 1 0 499 x128 0",
            "13829 (Jit thread pool) S 3344 3344 0 0 -1 1077952576 179948 0 1 0 -499 128 0",
            "x13829 (Jit thread pool) S 3344 3344 0 0 -1 1077952576 179948 0 1 0 499 128 0",
            "(Jit thread pool) S 3344 3344 0 0 -1 1077952576 179948 0 1 0 499 128 0",
            "13829 (Jit thread pool)S 3344 3344 0 0 -1 1077952576 179948 0 1 0 499 128 0",
            "13829 (Jit thread pool) S 3344  3344 0 0 -1 1077952576 179948 0 1 0 499 128 0",
        ).forEach { assertNull(parseTaskStat(it), it) }
    }

    @Test
    fun `a line read into a larger buffer ends where the read did`() {
        // A buffer kept from read to read holds what a longer line left in it.
        val buffer = ByteArray(1024) { ')'.code.toByte() }
        val bytes = DISPATCHER.toByteArray()
        bytes.copyInto(buffer)
        val stat = assertNotNull(parseTaskStat(buffer, bytes.size))
        assertEquals("DefaultDispatch", stat.name)
        assertEquals(52L, stat.ticks)
    }

    @Test
    fun `threads are grouped by what their names say they work at`() {
        mapOf(
            "DefaultDispatch" to ThreadKind.Workers,
            "DefaultDispatcher-worker-12" to ThreadKind.Workers,
            // ktor-nntp-client reads in its callers' threads now, on its view of Dispatchers.IO: it has no threads of its own.
            "nntp-read-timeo" to ThreadKind.Rest,
            "ExoPlayer:Loade" to ThreadKind.Loader,
            "ExoPlayer:Loader:ProgressiveMediaPeriod" to ThreadKind.Loader,
            "ExoPlayer:Playb" to ThreadKind.Playback,
            "ExoPlayer:Simpl" to ThreadKind.Playback,
            "ExoPlayer:Media" to ThreadKind.Codec,
            "MediaCodec_loop" to ThreadKind.Codec,
            "CodecLooper" to ThreadKind.Codec,
            "CCodecWatchdog" to ThreadKind.Codec,
            "HeapTaskDaemon" to ThreadKind.Gc,
            "FinalizerDaemon" to ThreadKind.Gc,
            "FinalizerWatchd" to ThreadKind.Gc,
            "ReferenceQueueD" to ThreadKind.Gc,
            "Jit thread pool" to ThreadKind.Jit,
            "Profile Saver" to ThreadKind.Jit,
            ".skjaere.usenet" to ThreadKind.Rest,
            "RenderThread" to ThreadKind.Rest,
            "Binder:13815_2" to ThreadKind.Rest,
            "JNA Cleaner" to ThreadKind.Rest,
            "" to ThreadKind.Rest,
        ).forEach { (name, kind) -> assertEquals(kind, ThreadKind.of(name), name) }
    }

    @Test
    fun `a proc tree is read thread by thread with the whole process`() {
        val proc = Files.createTempDirectory("proc").toFile()
        try {
            File(proc, "stat").writeText(MAIN + "\n")
            File(proc, "task/13829").mkdirs()
            File(proc, "task/13829/stat").writeText(JIT + "\n")
            File(proc, "task/13847").mkdirs()
            File(proc, "task/13847/stat").writeText(DISPATCHER + "\n")
            // A thread that ended between the listing and its read.
            File(proc, "task/13900").mkdirs()
            // Without schedstats, the stats' ticks, of 10 ms at 100 a second.
            val reader = ThreadCpuReader(proc, ticksPerSecond = 100)
            val cpu = assertNotNull(reader.read())
            assertEquals(37_630_000_000L, cpu.processNanos)
            assertEquals(setOf("Jit thread pool", "DefaultDispatch"), reader.names().toSet())
            assertEquals(kinds(ThreadKind.Workers to 520_000_000L, ThreadKind.Jit to 6_270_000_000L), cpu.kindNanos.toList())
            assertEquals(kinds(ThreadKind.Workers to 1L, ThreadKind.Jit to 1L), cpu.threads.map { it.toLong() })
            reader.close()
        } finally {
            proc.deleteRecursively()
        }
    }

    /** [KeptFiles.jvm], counting what is opened, closed and read, by path. */
    private class Counting(private val goneAfter: Map<String, Int> = emptyMap()) : KeptFiles {
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val reads = HashMap<String, Int>()

        override fun open(path: String): KeptFile? {
            val file = KeptFiles.jvm.open(path) ?: return null
            // The first file opened for a path in goneAfter reads as gone after that many reads.
            val reads = goneAfter[path]?.takeIf { path !in opened }
            opened += path
            return object : KeptFile {
                var n = 0

                override fun readFromStart(buffer: ByteArray): Int {
                    this@Counting.reads.merge(path, 1, Int::plus)
                    return if (reads != null && n++ >= reads) -1 else file.readFromStart(buffer)
                }

                override fun close() {
                    closed += path
                    file.close()
                }
            }
        }
    }

    private fun tree(): File = Files.createTempDirectory("proc").toFile().apply {
        File(this, "stat").writeText(MAIN + "\n")
        File(this, "task/13829").mkdirs()
        File(this, "task/13829/stat").writeText(JIT + "\n")
        File(this, "task/13847").mkdirs()
        File(this, "task/13847/stat").writeText(DISPATCHER + "\n")
    }

    @Test
    fun `a thread's stat stays open from one read to the next, and goes once the thread has`() {
        val proc = tree()
        val files = Counting()
        try {
            val reader = ThreadCpuReader(proc, files = files)
            assertNotNull(reader.read())
            // Nothing used since: nothing added.
            assertEquals(kinds(ThreadKind.Workers to 520_000_000L, ThreadKind.Jit to 6_270_000_000L), assertNotNull(reader.read()).kindNanos.toList())
            assertEquals(3, files.opened.size)
            assertEquals(emptyList<String>(), files.closed)
            File(proc, "task/13847").deleteRecursively()
            val ended = assertNotNull(reader.read())
            assertEquals(kinds(ThreadKind.Jit to 1L), ended.threads.map { it.toLong() })
            // What the ended thread had used stays counted.
            assertEquals(kinds(ThreadKind.Workers to 520_000_000L, ThreadKind.Jit to 6_270_000_000L), ended.kindNanos.toList())
            assertEquals(listOf("${proc.path}/task/13847/stat"), files.closed)
            reader.close()
            assertEquals(3, files.closed.size)
            // Read again after its close, it opens what it reads anew, and counts its threads from naught.
            val again = assertNotNull(reader.read())
            assertEquals(kinds(ThreadKind.Workers to 520_000_000L, ThreadKind.Jit to 12_540_000_000L), again.kindNanos.toList())
            assertEquals(5, files.opened.size)
        } finally {
            proc.deleteRecursively()
        }
    }

    @Test
    fun `a stat kept open that reads as gone is opened afresh, for a new thread the kernel gave the id to`() {
        val proc = tree()
        val path = "${proc.path}/task/13847/stat"
        val files = Counting(goneAfter = mapOf(path to 1))
        try {
            val reader = ThreadCpuReader(proc, files = files)
            assertNotNull(reader.read())
            assertEquals(setOf("Jit thread pool", "DefaultDispatch"), reader.names().toSet())
            File(path).writeText(DISPATCHER.replace("(DefaultDispatch)", "(ExoPlayer:Loade)") + "\n")
            val cpu = assertNotNull(reader.read())
            assertEquals(setOf("Jit thread pool", "ExoPlayer:Loade"), reader.names().toSet())
            // The new thread counts all it has used, as its own kind; the old one's stays its kind's.
            assertEquals(kinds(ThreadKind.Workers to 520_000_000L, ThreadKind.Loader to 520_000_000L, ThreadKind.Jit to 6_270_000_000L), cpu.kindNanos.toList())
            assertEquals(2, files.opened.count { it == path })
            assertEquals(1, files.closed.count { it == path })
        } finally {
            proc.deleteRecursively()
        }
    }

    @Test
    fun `where the kernel keeps schedstats, a thread's CPU comes from its own, in nanoseconds, its name from its stat now and then`() {
        val proc = tree()
        val schedstat = File(proc, "task/13847/schedstat")
        schedstat.writeText("520000123 99 7\n")
        File(proc, "task/13829/schedstat").writeText("6270000456 3 1\n")
        try {
            val reader = ThreadCpuReader(proc)
            assertEquals(kinds(ThreadKind.Workers to 520_000_123L, ThreadKind.Jit to 6_270_000_456L), assertNotNull(reader.read()).kindNanos.toList())
            // Renamed: its name is read again within 30 reads (a thirtieth of the threads' at each), and what it uses
            // from then on is its new kind's.
            File(proc, "task/13847/stat").writeText(DISPATCHER.replace("(DefaultDispatch)", "(ExoPlayer:Loade)") + "\n")
            schedstat.writeText("520000200 99 7\n")
            val reads = generateSequence { assertNotNull(reader.read()) }.take(30).toList()
            val renamed = reads.indexOfFirst { it.threads[ThreadKind.Loader.ordinal] == 1 }
            assertTrue(renamed in 0..29, "renamed at read $renamed")
            // Before the name was read again, the thread's new CPU went to its old kind; after it, nothing more.
            assertEquals(ThreadKind.entries.sumOf { reads.last().kindNanos[it.ordinal] }, 520_000_200L + 6_270_000_456L)
            assertEquals(setOf("Jit thread pool", "ExoPlayer:Loade"), reader.names().toSet())
            schedstat.writeText("520000300 99 7\n")
            assertEquals(100L, assertNotNull(reader.read()).kindNanos[ThreadKind.Loader.ordinal] - reads.last().kindNanos[ThreadKind.Loader.ordinal])
        } finally {
            proc.deleteRecursively()
        }
    }

    @Test
    fun `the rest's threads are counted and named but not read for their CPU, which is what the process used beyond the others'`() {
        // The listing's order gives each thread its turn among the 30 reads: the main thread made first, between or
        // last, as filesystems list in the order made, the newest first (this box's tmpfs) or by hash.
        for (at in 0..2) {
            val made = mutableListOf("13829" to JIT, "13847" to DISPATCHER).apply { add(at, "13815" to MAIN) }
            val proc = Files.createTempDirectory("proc").toFile()
            try {
                File(proc, "stat").writeText(MAIN + "\n")
                made.forEach { (tid, line) ->
                    File(proc, "task/$tid").mkdirs()
                    File(proc, "task/$tid/stat").writeText(line + "\n")
                }
                val files = Counting()
                val reader = ThreadCpuReader(proc, files = files)
                val first = assertNotNull(reader.read())
                assertEquals(1, first.threads[ThreadKind.Rest.ordinal])
                assertEquals(0L, first.kindNanos[ThreadKind.Rest.ordinal])
                files.reads.clear()
                // In the next 30 reads each thread's turn comes once.
                repeat(30) { assertEquals(1, assertNotNull(reader.read()).threads[ThreadKind.Rest.ordinal]) }
                val reads = made.associate { (tid, _) -> tid to files.reads["${proc.path}/task/$tid/stat"] }
                // The main thread's stat only on its turn, for its name; the other two's at every read, as the process's.
                val listed = File(proc, "task").list()!!.toList()
                assertEquals(mapOf("13815" to 1, "13829" to 30, "13847" to 30), reads, "made ${made.map { it.first }}, listed $listed")
                assertEquals(30, files.reads["${proc.path}/stat"])
                reader.close()
            } finally {
                proc.deleteRecursively()
            }
        }
    }

    @Test
    fun `without a proc to read there is nothing`() {
        assertNull(ThreadCpuReader(File("/no/such/proc")).read())
    }

    @Test
    fun `this process's threads from the real proc, their names cut to 15 bytes as the kernel keeps them`() {
        assumeTrue(File("/proc/self/task").isDirectory, "no /proc here")
        val started = CountDownLatch(2)
        val done = CountDownLatch(1)
        val threads = listOf("ExoPlayer:Loader:ThreadCpuTest", "odd) S 1 (name").map { name ->
            thread(name = name) {
                started.countDown()
                done.await()
            }
        }
        val reader = ThreadCpuReader()
        try {
            started.await()
            val cpu = assertNotNull(reader.read())
            val names = reader.names()
            assertTrue("ExoPlayer:Loade" in names, "$names")
            assertTrue("odd) S 1 (name" in names, "$names")
            assertEquals(1, cpu.threads[ThreadKind.Loader.ordinal])
            // The whole process counts every thread, those that ended too.
            // The process's count is in ticks of 10 ms, its user and its system time each rounded down; the threads' in
            // nanoseconds.
            assertTrue(cpu.processNanos + 30_000_000 >= cpu.kindNanos.sum(), "process ${cpu.processNanos}, threads ${cpu.kindNanos.sum()}")
            // Read again through the files kept open: the threads ended meanwhile are gone, once the kernel has let them
            // go (a moment after the JVM's join), and nothing counts less.
            done.countDown()
            threads.forEach { it.join() }
            val deadline = System.nanoTime() + 5_000_000_000
            var again = assertNotNull(reader.read())
            while (reader.names().any { it == "ExoPlayer:Loade" || it == "odd) S 1 (name" } && System.nanoTime() < deadline) {
                Thread.sleep(10)
                again = assertNotNull(reader.read())
            }
            assertTrue(reader.names().none { it == "ExoPlayer:Loade" || it == "odd) S 1 (name" }, "${reader.names()}")
            assertEquals(0, again.threads[ThreadKind.Loader.ordinal])
            assertTrue(ThreadKind.entries.all { again.kindNanos[it.ordinal] >= cpu.kindNanos[it.ordinal] })
            assertTrue(again.processNanos >= cpu.processNanos)
        } finally {
            done.countDown()
            threads.forEach { it.join() }
            reader.close()
        }
    }

    /** Each kind's figure, in [ThreadKind]'s order, naught for the kinds not given. */
    private fun kinds(vararg given: Pair<ThreadKind, Long>): List<Long> = ThreadKind.entries.map { kind -> given.firstOrNull { it.first == kind }?.second ?: 0L }

    companion object {
        // As the Shield (Android 11) wrote them during the baseline play.
        const val JIT = "13829 (Jit thread pool) S 3344 3344 0 0 -1 1077952576 179948 0 1 0 499 128 0 0 20 0 58 0 121969560 15613095936 84818 18446744073709551615 1 1 0 0 0 0 4612 1 1073775864 0 0 0 -1 1 0 0 0 0 0 0 0 0 0 0 0 0 0"
        const val DISPATCHER = "13847 (DefaultDispatch) S 3344 3344 0 0 -1 1077952576 3295 0 0 0 46 6 0 0 20 0 58 0 121969785 15613095936 87009 18446744073709551615 1 1 0 0 0 0 4612 1 1073775864 0 0 0 -1 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
        const val MAIN = "13815 (.skjaere.usenet) S 3344 3344 0 0 -1 1077952832 376701 0 103 0 3344 419 0 0 10 -10 58 0 121969549 15613095936 83447 18446744073709551615 1 1 0 0 0 0 4612 1 1073775864 0 0 0 17 1 0 0 18 0 0 0 0 0 0 0 0 0 0"

        // A desktop kernel's (Linux 7.0), with the addresses Android leaves out.
        const val DESKTOP = "1715416 (cat) R 1715411 1715416 1715411 0 -1 4194304 472 0 0 0 2 1 0 0 20 0 1 0 73768879 16637952 1814 18446744073709551615 102751705944064 102751710889661 140724690449728 0 0 0 0 0 0 0 0 0 17 1 0 0 0 0 0 102751714821840 102751716306712 102752695246848 140724690454925 140724690454945 140724690454945 140724690460651 0"
    }
}
