package io.skjaere.usenet.player

import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A debrid play's read-ahead (L239, W88) over a fake reader of a made-up file: from the file's first byte it reads 2 MiB
 * ahead until the player has taken 1 MiB, then the budget; from any other byte the budget at once; never more than the
 * budget and one chunk ahead; a failure comes after the bytes before it, at its byte; a cut after some bytes is opened
 * again once at its byte; its chunks go back to a pool the next open takes them from; and a close ends a read waiting.
 */
class ReadAheadTest {
    /** A file of [size] bytes, [byteAt]'s, read from [from]: what it hands out is counted, its faults are the case's. */
    private class FakeFile(private val size: Long, from: Long) : ReadAhead.Upstream {
        @Volatile
        private var at = from

        /** Every byte it handed the reader, across opens. */
        val served = AtomicLong()

        /** Where it was opened again. */
        val reopens = CopyOnWriteArrayList<Long>()

        /** Bytes at which a read fails as a cut, in order, each once. */
        val cuts = ConcurrentLinkedQueue<Long>()

        /** What opening again throws, if anything. */
        @Volatile
        var reopenFails: Throwable? = null

        /** The most one read gives. */
        @Volatile
        var most = Int.MAX_VALUE

        /** While set, a read waits for it: a server gone silent. */
        @Volatile
        var silent: CountDownLatch? = null

        /** Counted down as a read starts waiting out the silence: the reader is in its read. */
        val inSilence = CountDownLatch(1)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            silent?.let {
                inSilence.countDown()
                it.await(30, TimeUnit.SECONDS)
            }
            if (at >= size) return -1
            val cut = cuts.peek()
            if (cut != null && at >= cut) {
                cuts.poll()
                throw IOException("cut at $at")
            }
            val n = minOf(length.toLong(), size - at, most.toLong(), (cut ?: Long.MAX_VALUE) - at).toInt()
            for (i in 0 until n) buffer[offset + i] = byteAt(at + i)
            at += n
            served.addAndGet(n.toLong())
            return n
        }

        override fun reopen(position: Long) {
            reopens += position
            reopenFails?.let { throw it }
            at = position
        }
    }

    private class Refused : IOException("the service refused a fresh link")

    /** Runs [ahead]'s reader over [file] on a thread of its own, as the data source does. */
    private fun reading(ahead: ReadAhead, file: FakeFile): Thread = thread(isDaemon = true, name = "read-ahead-test") { ahead.fill(file) }

    /** What [file] has handed out once [ahead]'s reader has stopped asking for more: it waits for room, none to be had. */
    private fun settled(ahead: ReadAhead, file: FakeFile): Long {
        while (!ahead.readerWaitsForRoom) Thread.sleep(5)
        return file.served.get()
    }

    /** Waits for [thread] to be parked: in the case below, the player waiting for a byte. */
    private fun parked(thread: Thread) {
        val until = System.nanoTime() + 5_000_000_000
        while (thread.state != Thread.State.WAITING) {
            assertTrue(System.nanoTime() < until, "${thread.name} never waited: ${thread.state}")
            Thread.sleep(5)
        }
    }

    /** Takes [count] bytes from [ahead], checking each is the file's byte from [from]. */
    private fun take(ahead: ReadAhead, count: Long, from: Long) {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        while (done < count) {
            val n = ahead.take(buffer, 0, minOf(buffer.size.toLong(), count - done).toInt())
            assertTrue(n > 0, "a byte at least before $count, got $n after $done")
            for (i in 0 until n) assertEquals(byteAt(from + done + i), buffer[i], "byte ${from + done + i}")
            done += n
        }
    }

    @Test(timeout = 20_000)
    fun fromTheFilesFirstByteItReads2MiBAheadUntilThePlayerHasTaken1MiBThenTheBudget() {
        val file = FakeFile(64 * MIB, from = 0)
        val ahead = ReadAhead(start = 0, budget = 8 * MIB, pool = ReadAhead.Pool(keep = 40))
        reading(ahead, file)
        assertEquals(2 * MIB, settled(ahead, file), "2 MiB from the first byte")
        take(ahead, MIB - 1, from = 0)
        assertEquals(3 * MIB, settled(ahead, file), "still 2 MiB ahead of what the player took, to its chunk")
        take(ahead, 1, from = MIB - 1)
        assertEquals(9 * MIB, settled(ahead, file), "the whole budget once 1 MiB is taken")
        ahead.close()
    }

    @Test(timeout = 20_000)
    fun fromAnyOtherByteItReadsTheWholeBudgetAtOnce() {
        val file = FakeFile(64 * MIB, from = 5_000_000)
        val ahead = ReadAhead(start = 5_000_000, budget = 8 * MIB, pool = ReadAhead.Pool(keep = 40))
        reading(ahead, file)
        assertEquals(8 * MIB, settled(ahead, file))
        take(ahead, 3_000_000, from = 5_000_000)
        assertEquals(toChunk(3_000_000 + 8 * MIB), settled(ahead, file), "kept full as the player takes, to its chunk")
        ahead.close()
    }

    @Test(timeout = 60_000)
    fun itNeverHoldsMoreThanTheBudgetAndOneChunkAhead() {
        val budget = 3 * MIB + 12_345
        val file = FakeFile(64 * MIB, from = 777).apply { most = 100_003 }
        val ahead = ReadAhead(start = 777, budget = budget, pool = ReadAhead.Pool(keep = 40))
        reading(ahead, file)
        val random = Random(7)
        var taken = 0L
        repeat(30) {
            val held = settled(ahead, file) - taken
            assertTrue(held in budget..budget + ReadAhead.CHUNK, "$held held ahead after $taken taken, of a $budget budget")
            val n = random.nextLong(1, 700_000)
            take(ahead, n, from = 777 + taken)
            taken += n
        }
        ahead.close()
    }

    @Test(timeout = 20_000)
    fun aFailureIsKeptAtItsByteAfterTheBytesBeforeIt() {
        val refused = Refused()
        val file = FakeFile(64 * MIB, from = 1_000).apply {
            cuts += 301_000
            reopenFails = refused
        }
        val ahead = ReadAhead(start = 1_000, budget = 8 * MIB, pool = ReadAhead.Pool(keep = 40))
        reading(ahead, file)
        take(ahead, 300_000, from = 1_000)
        assertSame(refused, assertFailsWith<Refused> { ahead.take(ByteArray(10), 0, 10) })
        assertSame(refused, assertFailsWith<Refused> { ahead.take(ByteArray(10), 0, 10) }, "and again at the same byte")
        assertEquals(listOf(301_000L), file.reopens.toList())
        ahead.close()
    }

    @Test(timeout = 20_000)
    fun aCutAfterSomeBytesIsOpenedAgainOnceAtItsByteAndOneWithNoneSinceFailsThere() {
        val file = FakeFile(64 * MIB, from = 2_000).apply {
            cuts += 502_000
            cuts += 502_000
        }
        val ahead = ReadAhead(start = 2_000, budget = 8 * MIB, pool = ReadAhead.Pool(keep = 40))
        reading(ahead, file)
        take(ahead, 500_000, from = 2_000)
        val failed = assertFailsWith<IOException> { ahead.take(ByteArray(10), 0, 10) }
        assertEquals("cut at 502000", failed.message, "the cut that came with nothing since the open")
        assertEquals(listOf(502_000L), file.reopens.toList(), "opened again once, at the byte reached")
        ahead.close()
    }

    @Test(timeout = 20_000)
    fun eachCutAfterBytesSinceTheLastOpenIsOpenedAgainAtItsByteTheBytesUnbroken() {
        val file = FakeFile(3_000_000, from = 0).apply {
            cuts += 200_000
            cuts += 1_600_000
        }
        val ahead = ReadAhead(start = 0, budget = 8 * MIB, pool = ReadAhead.Pool(keep = 40))
        reading(ahead, file)
        take(ahead, 3_000_000, from = 0)
        assertEquals(-1, ahead.take(ByteArray(10), 0, 10), "the file's end")
        assertEquals(listOf(200_000L, 1_600_000L), file.reopens.toList())
        ahead.close()
    }

    @Test(timeout = 20_000)
    fun itsChunksGoBackToThePoolAsTheyAreTakenAndOnCloseAndTheNextOpenTakesThem() {
        val pool = ReadAhead.Pool(keep = 40)
        val first = FakeFile(64 * MIB, from = 10)
        val one = ReadAhead(start = 10, budget = MIB, pool = pool)
        reading(one, first)
        settled(one, first)
        // 20 MiB through a 1 MiB read-ahead: what the player has taken is filled again.
        take(one, 20 * MIB, from = 10)
        settled(one, first)
        val made = pool.made
        assertTrue(made <= MIB / ReadAhead.CHUNK + 2, "$made chunks made for a 1 MiB read-ahead")
        one.close()
        assertEquals(made, pool.kept, "every chunk back on close")
        val second = FakeFile(64 * MIB, from = 3 * MIB)
        val two = ReadAhead(start = 3 * MIB, budget = MIB, pool = pool)
        reading(two, second)
        settled(two, second)
        take(two, 5 * MIB, from = 3 * MIB)
        settled(two, second)
        assertEquals(made, pool.made, "the next open made none")
        two.close()
    }

    @Test(timeout = 20_000)
    fun aCloseEndsAReadWaitingAndTheReaderLetsGoOfItsChunkAsItsReadComesBack() {
        val pool = ReadAhead.Pool(keep = 40)
        val file = FakeFile(64 * MIB, from = 0).apply { silent = CountDownLatch(1) }
        val ahead = ReadAhead(start = 0, budget = 8 * MIB, pool = pool)
        val reader = reading(ahead, file)
        val failed = AtomicReference<Throwable?>()
        assertTrue(file.inSilence.await(5, TimeUnit.SECONDS), "the reader in its read")
        val player = thread(isDaemon = true, name = "player") {
            try {
                ahead.take(ByteArray(10), 0, 10)
            } catch (e: Throwable) {
                failed.set(e)
            }
        }
        parked(player)
        assertTrue(ahead.close(), "the reader was in its read")
        player.join(1_000)
        assertTrue(failed.get() is InterruptedIOException, "the waiting read ended: ${failed.get()}")
        assertEquals(0, pool.kept, "the chunk the reader writes into isn't the pool's yet")
        file.silent?.countDown()
        reader.join(5_000)
        assertTrue(!reader.isAlive, "the reader ends as its read comes back")
        assertEquals(pool.made, pool.kept, "and its chunk goes back")
        assertTrue(!ahead.close(), "closed once")
    }

    /**
     * GRA-1: a close waits a moment for a read under way, so that only the reader touches what it reads from while bytes
     * flow (Android's HTTP stack reads the rest of a body on the thread that closes it). One that comes back in that time
     * leaves the upstream to the closing thread, the reader done with it; as Media3 closes a load it cancelled, its thread
     * interrupted, the close waits all the same, and the thread stays interrupted.
     */
    @Test(timeout = 20_000)
    fun aCloseWaitsAMomentForAReadUnderWayAndOneThatComesBackLeavesTheUpstreamToTheCloser() {
        val pool = ReadAhead.Pool(keep = 40)
        val file = FakeFile(64 * MIB, from = 0).apply { silent = CountDownLatch(1) }
        val ahead = ReadAhead(start = 0, budget = 8 * MIB, pool = pool)
        val reader = reading(ahead, file)
        assertTrue(file.inSilence.await(5, TimeUnit.SECONDS), "the reader in its read")
        val left = AtomicReference<Boolean?>()
        val interrupted = AtomicReference<Boolean?>()
        val tookMs = AtomicLong()
        val closer = thread(isDaemon = true, name = "loader") {
            Thread.currentThread().interrupt()
            val started = System.nanoTime()
            left.set(ahead.close(waitMs = 2_000))
            tookMs.set((System.nanoTime() - started) / 1_000_000)
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        // The read comes back a tenth of a second into the close's wait, as one from a body still flowing does.
        Thread.sleep(100)
        file.silent?.countDown()
        closer.join(5_000)
        assertEquals(false, left.get(), "the read came back: the closing thread lets the upstream go, in ${tookMs.get()} ms")
        assertTrue(tookMs.get() in 50..1_500, "closed as the read came back: ${tookMs.get()} ms")
        assertEquals(true, interrupted.get(), "the closing thread still interrupted")
        assertTrue(!ahead.upstreamLeftToReader)
        reader.join(1_000)
        assertTrue(!reader.isAlive, "the reader ends")
        assertEquals(pool.made, pool.kept, "every chunk back")
    }

    /** One still in its read when the wait is out (a CDN gone silent) is left to the reader: the caller aborts it. */
    @Test(timeout = 20_000)
    fun aReadStillUnderWayWhenTheWaitIsOutIsLeftToTheReader() {
        val pool = ReadAhead.Pool(keep = 40)
        val file = FakeFile(64 * MIB, from = 0).apply { silent = CountDownLatch(1) }
        val ahead = ReadAhead(start = 0, budget = 8 * MIB, pool = pool)
        val reader = reading(ahead, file)
        assertTrue(file.inSilence.await(5, TimeUnit.SECONDS), "the reader in its read")
        val started = System.nanoTime()
        assertTrue(ahead.close(waitMs = 200), "still in its read")
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs in 150..1_500, "waited the 200 ms first: $tookMs ms")
        assertTrue(ahead.upstreamLeftToReader)
        file.silent?.countDown()
        reader.join(5_000)
        assertTrue(!reader.isAlive, "the reader ends as its read comes back")
        assertEquals(pool.made, pool.kept, "and its chunk goes back")
    }

    @Test(timeout = 20_000)
    fun aCloseWithTheReaderWaitingForRoomLeavesNothingToTheReader() {
        val pool = ReadAhead.Pool(keep = 40)
        val file = FakeFile(64 * MIB, from = 4_000)
        val ahead = ReadAhead(start = 4_000, budget = MIB, pool = pool)
        val reader = reading(ahead, file)
        settled(ahead, file)
        assertTrue(!ahead.close(), "not in a read: the closing thread lets the source go")
        reader.join(1_000)
        assertTrue(!reader.isAlive)
        assertEquals(pool.made, pool.kept)
    }

    private companion object {
        const val MIB = 1024L * 1024

        fun byteAt(position: Long) = (position * 31 + 7).toByte()

        /** [bytes] up to the end of the chunk they end in: a read fills its chunk. */
        fun toChunk(bytes: Long) = (bytes + ReadAhead.CHUNK - 1) / ReadAhead.CHUNK * ReadAhead.CHUNK
    }
}
