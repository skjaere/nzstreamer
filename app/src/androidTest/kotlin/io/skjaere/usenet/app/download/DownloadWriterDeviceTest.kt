package io.skjaere.usenet.app.download

import android.os.Process
import android.os.SystemClock
import android.os.storage.StorageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.utils.io.readAvailable
import io.skjaere.usenet.app.LocalNntp
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadFixtures.Packing
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.DownloadWriter
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.NzbStreamOpener
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.LibraryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The download writer on a device (item 6): the downloads' fixtures (T4) posted on a server in the test's process
 * ([LocalNntp], with T1's cut and throttle), prepared as Resolve prepares them, then written through the engine's download
 * opener into a file [DownloadStorage] allocates whole, in places of the test's own, and renamed as a finished download's.
 * Each comes whole and equal to its source: plain, a split stored RAR4 set and a split 7z (Copy). A connection cut part way
 * through a BODY is opened again from the safe offset. The video cache is as it was after a download (M3), where a play's
 * stream of the same file adds to it. A prepare started during a throttled download that takes every connection isn't held
 * behind its fetches (M2's guard). The record's cadence is a seam: its cost against throughput at 8, 32 and 128 MiB goes
 * to the log under [TAG] for M5. Files are 64 MB at most, deleted after.
 */
@RunWith(AndroidJUnit4::class)
class DownloadWriterDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val fixtures = DownloadFixtures()
    private val local get() = fixtures.local
    private val db = LibraryDatabase.inMemory(context)

    // The test's own places, in the fixtures' folder: deleted with it.
    private val storage = DownloadStorage(
        fixtures.file("files"), { fixtures.file("volume") }, db.downloads(), context.getSystemService(StorageManager::class.java),
    )

    @Before
    fun setUp() = runBlocking<Unit> {
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(local.provider)))
        }
    }

    @After
    fun tearDown() {
        local.throttle = null
        local.cut = null
        db.close()
        fixtures.close()
    }

    /** [release] prepared as Resolve prepares one. */
    private suspend fun prepared(release: DownloadFixtures.Release): PreparedNzb {
        val outcome = engineSetup("${release.title} prepared") { container.preparer.prepare(release.nzb, release.title) }
        return (outcome as? PrepareOutcome.Ready)?.nzb ?: throw AssertionError("${release.title} (${release.packing}) didn't prepare: $outcome")
    }

    /** A write: its outcome, each stream's first byte, each record's offset, the finished file, and how long it took. */
    private class Written(
        val id: String,
        val outcome: DownloadWriter.Outcome,
        val opens: List<Long>,
        val records: List<Long>,
        val file: File?,
        val seconds: Double,
    )

    /**
     * [prepared]'s one file written whole by [writer] into a file allocated for download [id], through [channel]'s wrap of
     * it, and renamed once done.
     */
    private suspend fun write(
        release: DownloadFixtures.Release,
        prepared: PreparedNzb,
        writer: DownloadWriter = DownloadWriter(NzbStreamOpener.download(container.engine)),
        id: String = Download.newId(),
        onOpen: (Long) -> Unit = {},
        channel: (FileChannel) -> FileChannel = { it },
    ): Written {
        val file = prepared.files.single()
        val opens = CopyOnWriteArrayList<Long>()
        val records = CopyOnWriteArrayList<Long>()
        val start = SystemClock.elapsedRealtimeNanos()
        val outcome = withContext(Dispatchers.IO) {
            channel(storage.open(id, release.fileName, file.size)).use { out ->
                withTimeout(120_000) {
                    writer.write(id, prepared, file, out, 0, emptyList(), onOpen = { opens += it; onOpen(it) }) { offset, _ -> records += offset }
                }
            }
        }
        val seconds = (SystemClock.elapsedRealtimeNanos() - start) / 1e9
        val done = if (outcome is DownloadWriter.Outcome.Done) storage.finish(id, release.fileName) else null
        return Written(id, outcome, opens, records, done, seconds)
    }

    @Test
    fun plainSplitRar4And7zCopyAreEachWrittenWholeAndEqualToTheSource() = runBlocking<Unit> {
        listOf(Packing.Plain, Packing.Rar4, Packing.SevenZip).forEach { packing ->
            val release = fixtures.post(packing, "$TITLE.$packing", DownloadFixtures.seeded(16 * DownloadFixtures.MB, fresh()))
            val written = write(release, prepared(release))
            assertEquals(packing.name, DownloadWriter.Outcome.Done(emptyList()), written.outcome)
            val file = checkNotNull(written.file)
            assertEquals(packing.name, release.size, file.length())
            assertEquals(packing.name, release.sha256, DownloadFixtures.sha256(file))
            assertEquals(packing.name, listOf(0L), written.opens)
            assertEquals(packing.name, release.size, written.records.last())
            Log.i(TAG, "written ${packing.name}: ${release.size} bytes in ${"%.2f".format(Locale.ROOT, written.seconds)} s")
        }
    }

    /**
     * A connection lost part way through an article's BODY, every time it is asked for until the writer opens its stream
     * again: the stream fails there, the writer records what it took and opens the stream again from that safe offset, and
     * nothing before it is fetched again. The file comes whole.
     */
    @Test
    fun aConnectionCutMidBodyIsOpenedAgainFromTheSafeOffsetAndTheFileComesWhole() = runBlocking<Unit> {
        // Fresh: an article the video cache had would never be asked of the server, nor cut.
        val release = fixtures.post(Packing.Plain, TITLE, DownloadFixtures.seeded(16 * DownloadFixtures.MB, fresh()))
        val prepared = prepared(release)
        val posted = release.posted.single()
        local.cut = LocalNntp.Cut(bytes = 100_000, everyTime = true, ids = setOf(posted.ids[CUT_AT]))
        val before = posted.ids.associateWith { local.bodies[it]?.get() ?: 0 }
        val written = write(release, prepared, onOpen = { offset -> if (offset > 0) local.cut = null })
        assertEquals(DownloadWriter.Outcome.Done(emptyList()), written.outcome)
        assertTrue("cut ${local.cuts.get()} times", local.cuts.get() >= 1)
        assertEquals("streams opened at ${written.opens}", 2, written.opens.size)
        val safe = written.opens[1]
        assertTrue("opened again at $safe", safe in 1..CUT_AT.toLong() * DownloadFixtures.PART)
        assertTrue("recorded at ${written.records}, opened again at $safe", safe in written.records)
        posted.ids.withIndex().filter { (i, _) -> (i + 1L) * DownloadFixtures.PART <= safe }.forEach { (i, id) ->
            // Once, or not at all where the video cache had it from the prepare.
            val bodies = (local.bodies[id]?.get() ?: 0) - before.getValue(id)
            assertTrue("article $i fetched $bodies times", bodies <= 1)
        }
        assertEquals(release.sha256, DownloadFixtures.sha256(checkNotNull(written.file)))
        Log.i(TAG, "cut at article $CUT_AT: opened again at $safe of ${release.size} bytes")
    }

    /**
     * M3: a download reads the video cache and stores nothing in it, so it is as it was after the download, its entries and
     * bytes. A play's stream of the same file then adds to it: the cache was there to be filled.
     */
    @Test
    fun aDownloadLeavesTheVideoCacheAsItWasWhereAPlaysStreamAddsToIt() = runBlocking<Unit> {
        val cache = waitFor("the video cache to be open", timeoutMs = 10_000) { container.videoCache.forStreamer() }
        // The streamer built from here on is lent it.
        container.engine.rebuildWhenIdle()
        // Fresh: none of its articles is in the cache.
        val release = fixtures.post(Packing.Plain, TITLE, DownloadFixtures.seeded(16 * DownloadFixtures.MB, fresh()))
        val prepared = prepared(release)
        val before = cache.stats()
        val written = write(release, prepared)
        assertEquals(DownloadWriter.Outcome.Done(emptyList()), written.outcome)
        assertEquals(release.sha256, DownloadFixtures.sha256(checkNotNull(written.file)))
        val after = cache.stats()
        Log.i(
            TAG,
            "M3: the video cache held ${before.entryCount} entries, ${before.totalBytes} bytes before a download of ${release.size} bytes, " +
                "${after.entryCount} and ${after.totalBytes} after",
        )
        assertEquals("the video cache's entries", before.entryCount, after.entryCount)
        assertEquals("the video cache's bytes", before.totalBytes, after.totalBytes)

        val file = prepared.files.single()
        val play = NzbStreamOpener(container.engine).open(prepared, file.index, 0 until 4L * DownloadFixtures.PART)
        try {
            val buffer = ByteArray(64 * 1024)
            withTimeout(60_000) { while (play.channel.readAvailable(buffer, 0, buffer.size) >= 0) Unit }
        } finally {
            play.job.cancel()
        }
        val played = waitFor("a play's stream to store what it fetched") { cache.stats().takeIf { it.entryCount > after.entryCount } }
        Log.i(TAG, "M3: a play's stream of 4 of its articles left the video cache at ${played.entryCount} entries")
    }

    /**
     * M2's guard: during a download throttled to a provider's rate whose fetches queue for the provider's connections (more
     * segments at once than it has connections, as when a play's stream shares them), a release's prepare gets the next
     * connection that comes free, ahead of the download's queued fetches, and is done long before the download is. At a
     * play's priority the download's fetches would go first, every one of them, while it read on (C1).
     */
    @Test
    fun aPrepareStartedDuringAThrottledDownloadIsntHeldBehindItsFetches() = runBlocking<Unit> {
        engineSetup("the engine, a stream whose fetches queue for the connections") {
            container.engine.configure(EngineSettings(listOf(local.provider), concurrency = 2 * local.provider.maxConnections))
        }
        val big = fixtures.post(Packing.Plain, TITLE, DownloadFixtures.seeded(48 * DownloadFixtures.MB, fresh()))
        // Fresh: its first article isn't in the video cache, so the prepare asks the server for it.
        val other = fixtures.post(Packing.Plain, OTHER_TITLE, DownloadFixtures.seeded(16 * DownloadFixtures.MB, fresh()))
        val prepared = prepared(big)
        local.throttle = THROTTLE
        val writer = DownloadWriter(NzbStreamOpener.download(container.engine))
        val download = async(Dispatchers.IO) { write(big, prepared, writer) }
        try {
            waitFor("the download under way", timeoutMs = 60_000) { writer.live.value?.takeIf { it.written >= 4 * DownloadFixtures.MB } }
            val start = SystemClock.elapsedRealtime()
            val outcome = withTimeout(120_000) { container.preparer.prepare(other.nzb, other.title) }
            val took = SystemClock.elapsedRealtime() - start
            val writtenThen = writer.live.value?.written
            Log.i(TAG, "M2 guard: a prepare during a download took $took ms; the download at ${writtenThen ?: "its end"} of ${big.size} bytes")
            assertTrue("$outcome", outcome is PrepareOutcome.Ready)
            assertTrue("the prepare took $took ms", took < 5_000)
            assertTrue("the download was over before the prepare was", writtenThen != null && writtenThen < big.size)
        } finally {
            download.cancelAndJoin()
        }
    }

    /**
     * M5: the record's cadence (L345, 32 MiB or 10 s) is a seam. A 64 MB file written at 8, 32 and 128 MiB, twice over:
     * the records each, the time in force(), the throughput and the process's CPU a MB (the server's included: it is in the
     * process) go to the log. Each comes whole, with a record each cadence's bytes or 10 s and one at its end.
     */
    @Test
    fun theRecordsCadenceIsASeamForItsCostAgainstThroughput() = runBlocking<Unit> {
        // Fresh: every article comes from the server, none from the video cache.
        val release = fixtures.post(Packing.Plain, TITLE, DownloadFixtures.seeded(64 * DownloadFixtures.MB, fresh()))
        val prepared = prepared(release)
        listOf(8, 32, 128, 128, 32, 8).forEach { mib ->
            val cadence = mib * MIB
            var timed: TimedChannel? = null
            val cpu = Process.getElapsedCpuTime()
            val written = write(
                release, prepared, DownloadWriter(NzbStreamOpener.download(container.engine), recordBytes = cadence),
                channel = { TimedChannel(it).also { t -> timed = t } },
            )
            val cpuMs = Process.getElapsedCpuTime() - cpu
            assertEquals("at $mib MiB", DownloadWriter.Outcome.Done(emptyList()), written.outcome)
            assertEquals("at $mib MiB", release.sha256, DownloadFixtures.sha256(checkNotNull(written.file)))
            val each = release.size / cadence
            assertTrue("records at ${written.records} at $mib MiB", written.records.size.toLong() in each + 1..each + 2 + (written.seconds / 10).toLong())
            val forces = checkNotNull(timed)
            val mb = release.size / 1e6
            Log.i(
                TAG,
                "M5 cadence=${mib}MiB records=${written.records.size} forces=${forces.forces} forceMs=${forces.forceNanos / 1_000_000} " +
                    "seconds=${"%.2f".format(Locale.ROOT, written.seconds)} MBps=${"%.1f".format(Locale.ROOT, mb / written.seconds)} " +
                    "cpuMsPerMB=${"%.1f".format(Locale.ROOT, cpuMs / mb)}",
            )
            storage.deleteVideo(written.id)
        }
    }

    /** A file channel over [real] that times its forces. */
    private class TimedChannel(private val real: FileChannel) : FileChannel() {
        @Volatile
        var forces = 0
            private set

        @Volatile
        var forceNanos = 0L
            private set

        override fun force(metaData: Boolean) {
            val start = System.nanoTime()
            real.force(metaData)
            forceNanos += System.nanoTime() - start
            forces++
        }

        override fun write(src: ByteBuffer, position: Long): Int = real.write(src, position)
        override fun read(dst: ByteBuffer): Int = real.read(dst)
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = real.read(dsts, offset, length)
        override fun write(src: ByteBuffer): Int = real.write(src)
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = real.write(srcs, offset, length)
        override fun position(): Long = real.position()
        override fun position(newPosition: Long): FileChannel = apply { real.position(newPosition) }
        override fun size(): Long = real.size()
        override fun truncate(size: Long): FileChannel = apply { real.truncate(size) }
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = real.transferTo(position, count, target)
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = real.transferFrom(src, position, count)
        override fun read(dst: ByteBuffer, position: Long): Int = real.read(dst, position)
        override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = real.map(mode, position, size)
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = real.lock(position, size, shared)
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = real.tryLock(position, size, shared)
        override fun implCloseChannel() = real.close()
    }

    private companion object {
        const val TAG = "DownloadWriterDeviceTest"
        const val TITLE = "Harbor.Lights.S03E07.1080p.WEB-DL.H.264-KESTREL"
        const val OTHER_TITLE = "Copper.Crown.2019.1080p.WEB-DL.H.264-GRP"
        const val MIB = 1024L * 1024

        /** The article whose BODY the cut ends part way. */
        const val CUT_AT = 20

        /** Each connection's bytes a second: about a provider's, a 512 KiB article taking half a second. */
        const val THROTTLE = 1_000_000L

        /**
         * A seed no run used before: its bytes' articles have ids of their own (DownloadFixtures tags them by their bytes),
         * so none is in the video cache, whatever an earlier run stored there.
         */
        fun fresh(): Long = System.nanoTime()
    }
}
