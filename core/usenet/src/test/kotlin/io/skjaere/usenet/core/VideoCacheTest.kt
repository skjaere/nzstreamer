package io.skjaere.usenet.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The video cache (Settings › Cache): one segment cache for the process, lent to every streamer the
 * engine builds, held to the limit chosen or to what fits on the device, cleared when turned off.
 */
class VideoCacheTest {
    @TempDir
    lateinit var tmp: File

    private val dir get() = File(tmp, "segments")
    private val clip = TestClip()
    private val servers = mutableListOf<FakeNntpServer>()

    /** Free bytes on the cache's volume, as the test says. */
    private var free = 50 * GB
    /** How many times the cache opened, each one passed on to the engine as the app does. */
    private var opened = 0
    private lateinit var engine: UsenetEngine
    private val cache by lazy {
        VideoCache(dir, freeSpace = { free }, opened = { opened++; if (::engine.isInitialized) engine.rebuildWhenIdle() })
    }

    private val fakeMain = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()
    private val ioThread = Executors.newSingleThreadExecutor { Thread(it, "fake-io") }
    private val fakeIo = ioThread.asCoroutineDispatcher()

    @AfterEach
    fun tearDown() {
        if (::engine.isInitialized) engine.close()
        servers.forEach { it.close() }
        fakeMain.close()
        fakeIo.close()
    }

    /** An engine whose streamers are lent the cache, as the app's are, on a server with the clip; [built] counts its builds. */
    private var built = 0

    private suspend fun engineOnTheClip(): FakeNntpServer {
        val server = clip.server().also { servers += it }
        engine = UsenetEngine(factory = { s -> built++; UsenetEngine.defaultStreamer(s, cache.forStreamer()) })
        engine.configure(EngineSettings(listOf(server.provider())))
        return server
    }

    /** Streams the clip to its end; the session is over when this returns, so the engine is idle. */
    private suspend fun play(): ByteArray = withTimeout(30_000) {
        val stream = NzbStreamOpener(engine).open(clip.nzb, 0, 0L until clip.data.size.toLong())
        val (received, cause) = stream.readToEnd()
        stream.job.join()
        assertNull(cause, "the stream ended with $cause")
        received
    }

    private fun FakeNntpServer.bodies() = commands.count { it.startsWith("BODY ") }

    @Test
    fun `the limit is the one chosen, or what fits leaving 1 GB free, and nothing under 100 MB`() {
        assertEquals(2 * GB, videoCacheLimit(chosen = 2 * GB, used = 0, free = 50 * GB))
        // What it holds counts as room: it may keep it.
        assertEquals(2 * GB, videoCacheLimit(chosen = 2 * GB, used = 2 * GB, free = GB))
        assertEquals(1_500_000_000, videoCacheLimit(chosen = 2 * GB, used = 500_000_000, free = 2 * GB))
        assertEquals(1_400_000_000, videoCacheLimit(chosen = 5 * GB, used = 0, free = 2_400_000_000))
        assertEquals(100_000_000, videoCacheLimit(chosen = 5 * GB, used = 0, free = 1_100_000_000))
        assertEquals(0, videoCacheLimit(chosen = 5 * GB, used = 0, free = 1_099_999_999))
        assertEquals(0, videoCacheLimit(chosen = 5 * GB, used = 0, free = 0))
    }

    @Test
    fun `before the settings are applied there is no cache and no usage`() {
        assertNull(cache.forStreamer())
        assertNull(cache.usageNow())
    }

    /**
     * A rebuild of the streamer (a provider setting changed during a play) neither closes the cache nor
     * opens another: what was played is read from it, on the new streamer too.
     */
    @Test
    fun `what plays is kept in the one cache, which a rebuild of the streamer leaves open`() = runBlocking<Unit> {
        cache.apply(keep = true, chosenBytes = 2 * GB)
        val server = engineOnTheClip()
        assertContentEquals(clip.data, play())
        assertEquals(6, server.bodies())
        assertEquals(VideoCacheUsage(on = true, usedBytes = clip.data.size.toLong(), limitBytes = 2 * GB, chosenBytes = 2 * GB, freeBytes = 50 * GB), cache.usageNow())

        val lent = cache.forStreamer()
        engine.configure(EngineSettings(listOf(server.provider().copy(maxConnections = 3))))
        assertContentEquals(clip.data, play())
        assertEquals(2, built)
        assertEquals(6, server.bodies(), "played again from the provider")
        assertSame(lent, cache.forStreamer())
        assertEquals(1, opened)
    }

    @Test
    fun `turned off it is cleared and closed, and turned on again the next streamer gets a new one`() = runBlocking<Unit> {
        cache.apply(keep = true, chosenBytes = 2 * GB)
        val server = engineOnTheClip()
        play()
        val first = assertNotNull(cache.forStreamer())

        cache.apply(keep = false, chosenBytes = 2 * GB)
        assertNull(cache.forStreamer())
        assertFalse(dir.exists(), "left ${dir.walk().toList()}")
        assertNull(first.get("seg0@test"), "the streamer still has it open")
        assertEquals(VideoCacheUsage(on = false, usedBytes = 0, limitBytes = 0, chosenBytes = 2 * GB, freeBytes = 50 * GB), cache.usageNow())
        // The streamer it was lent to plays on, from the provider.
        play()
        assertEquals(12, server.bodies())
        assertEquals(1, built)

        cache.apply(keep = true, chosenBytes = 2 * GB)
        val second = assertNotNull(cache.forStreamer())
        assertNotSame(first, second)
        assertEquals(2, opened)
        // Built again at the next use, the streamer has it.
        play()
        assertEquals(2, built)
        play()
        assertEquals(18, server.bodies())
        assertEquals(clip.data.size.toLong(), cache.usageNow()?.usedBytes)
    }

    @Test
    fun `turned off in an earlier process, what it left is deleted`() = runBlocking<Unit> {
        File(dir, "ab").mkdirs()
        File(dir, "index.json").writeText("{}")
        cache.apply(keep = false, chosenBytes = 2 * GB)
        assertFalse(dir.exists())
        assertNull(cache.forStreamer())
    }

    @Test
    fun `the limit follows the setting and the room, and without room nothing is kept`() = runBlocking<Unit> {
        cache.apply(keep = true, chosenBytes = 5 * GB)
        engineOnTheClip()
        play()
        assertEquals(5 * GB, cache.usageNow()?.limitBytes)
        cache.apply(keep = true, chosenBytes = GB)
        assertEquals(GB, cache.usageNow()?.limitBytes)
        assertEquals(GB, cache.forStreamer()?.stats()?.maxBytes)

        // Other apps filled the device: as it goes to the background, the cache is held to what fits.
        free = 1_500_000_000
        cache.save()
        val held = clip.data.size + 500_000_000L
        assertEquals(VideoCacheUsage(on = true, usedBytes = clip.data.size.toLong(), limitBytes = held, chosenBytes = GB, freeBytes = free), cache.usageNow())
        assertEquals(held, cache.forStreamer()?.stats()?.maxBytes)

        // No room for 100 MB: nothing is kept, as when it is off, but it stays on.
        free = 1_050_000_000
        cache.refresh()
        assertNull(cache.forStreamer())
        assertFalse(dir.exists())
        assertEquals(VideoCacheUsage(on = true, usedBytes = 0, limitBytes = 0, chosenBytes = GB, freeBytes = free), cache.usageNow())

        free = 10 * GB
        cache.refresh()
        assertEquals(GB, cache.forStreamer()?.stats()?.maxBytes)
        assertEquals(2, opened)
    }

    /**
     * The Cache screen fits the cache to the room as it opens, in the screen's scope, which leaving
     * cancels. A refresh cancelled as it dropped the cache (no room) left that instance open and lent
     * to the streamer, so the next would open beside it on the same directory; one cancelled as room
     * came back never passed the new one on to the engine.
     */
    @Test
    fun `a refresh cancelled midway still drops or opens the cache whole`() = runBlocking<Unit> {
        var refreshing: Job? = null
        // The screen is left as the room is measured.
        val cache = VideoCache(dir, freeSpace = { refreshing?.cancel(); free }, opened = { yield(); opened++ })
        cache.apply(keep = true, chosenBytes = GB)
        val first = assertNotNull(cache.forStreamer())
        first.getOrFetch("seg0@test") { clip.segments[0] }

        free = 1_050_000_000
        refreshing = launch { cache.refresh() }
        refreshing.join()
        assertTrue(refreshing.isCancelled)
        assertNull(cache.forStreamer())
        assertNull(first.get("seg0@test"), "the instance dropped is still open")
        assertFalse(dir.exists(), "left ${dir.walk().toList()}")
        assertEquals(VideoCacheUsage(on = true, usedBytes = 0, limitBytes = 0, chosenBytes = GB, freeBytes = free), cache.usageNow())

        free = 10 * GB
        refreshing = launch { cache.refresh() }
        refreshing.join()
        assertNotNull(cache.forStreamer())
        assertEquals(2, opened, "the new instance wasn't passed on")
        assertEquals(GB, cache.usageNow()?.limitBytes)
    }

    private fun segmentFiles() = dir.walk().filter { it.isFile && it.name != "index.json" }.toList()

    /**
     * Android deletes files from the cache directory when storage runs low, the app running. Still
     * counted, they were room the cache could take back: fitted to the room as the app went to the
     * background, it kept a limit that left the device under 1 GB free, and evicting them freed nothing.
     */
    @Test
    fun `files the system deletes are counted out before the room is measured`() = runBlocking<Unit> {
        cache.apply(keep = true, chosenBytes = GB)
        engineOnTheClip()
        play()
        // Storage ran low: other apps took the room, and the system deleted the cache's files.
        assertTrue(segmentFiles().isNotEmpty())
        segmentFiles().forEach { it.delete() }
        free = 1_500_000_000
        cache.save()
        assertEquals(VideoCacheUsage(on = true, usedBytes = 0, limitBytes = 500_000_000, chosenBytes = GB, freeBytes = free), cache.usageNow())
        assertEquals(500_000_000, cache.forStreamer()?.stats()?.maxBytes)

        // The system's Clear cache, the app running: the Cache screen shows nothing kept as it opens.
        play()
        assertEquals(clip.data.size.toLong(), cache.usageNow()?.usedBytes)
        segmentFiles().forEach { it.delete() }
        cache.refresh()
        assertEquals(0, cache.usageNow()?.usedBytes)
    }

    @Test
    fun `clear empties it, and saving keeps what it holds for the next process`() = runBlocking<Unit> {
        cache.apply(keep = true, chosenBytes = 2 * GB)
        engineOnTheClip()
        play()
        cache.save()
        // A process killed after the save: the next one finds the clip.
        val next = VideoCache(File(tmp, "copy").also { dir.copyRecursively(it) }, freeSpace = { free })
        next.apply(keep = true, chosenBytes = 2 * GB)
        assertEquals(clip.data.size.toLong(), next.usageNow()?.usedBytes)
        assertContentEquals(clip.segments[0], next.forStreamer()?.get("seg0@test"))

        cache.clear()
        assertEquals(0, cache.usageNow()?.usedBytes)
        assertEquals(emptyList(), dir.walk().filter { it.isFile && it.name != "index.json" }.toList())
        assertNotNull(cache.forStreamer(), "cleared, not closed")
    }

    @Test
    fun `settings applied and Clear on the main thread work on the io dispatcher`() = runBlocking<Unit> {
        val cache = VideoCache(dir, ioDispatcher = fakeIo, freeSpace = { free })
        val gate = CountDownLatch(1).also { gate -> ioThread.execute { gate.await() } }
        try {
            val apply = launch(fakeMain) { cache.apply(keep = true, chosenBytes = 2 * GB) }
            withContext(fakeMain) {} // apply has done all it can on fake-main
            assertFalse(dir.exists(), "opened on the calling thread")
            gate.countDown()
            apply.join()
        } finally {
            gate.countDown()
        }
        assertTrue(dir.isDirectory)
        val segments = assertNotNull(cache.forStreamer())
        segments.getOrFetch("seg0@test") { clip.segments[0] }

        // Clear too: the segment cache's own index lock is waited on only off the main thread.
        val held = CountDownLatch(1).also { gate -> ioThread.execute { gate.await() } }
        try {
            val clear = launch(fakeMain) { cache.clear() }
            withContext(fakeMain) {} // clear has done all it can on fake-main
            assertNotNull(segments.get("seg0@test"), "cleared on the calling thread")
            held.countDown()
            clear.join()
        } finally {
            held.countDown()
        }
        assertNull(segments.get("seg0@test"))
    }

    private companion object {
        const val GB = 1_000_000_000L
    }
}
