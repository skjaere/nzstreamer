package io.skjaere.usenet.logging

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.event.Level
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The log of the last crash: written as an uncaught exception ends the process, read and deleted from the Logs screen. */
class CrashLogTest {
    @TempDir
    lateinit var dir: File

    private val before = Thread.getDefaultUncaughtExceptionHandler()
    private val handedOn = mutableListOf<Pair<String, Throwable>>()

    @BeforeEach
    fun setUp() {
        LogBuffer.clear()
        // Stands in for Android's own handler, which ends the process.
        Thread.setDefaultUncaughtExceptionHandler { thread, e -> synchronized(handedOn) { handedOn += thread.name to e } }
    }

    @AfterEach
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(before)
        LogBuffer.clear()
    }

    private fun crashOn(name: String, e: Throwable) = Thread { throw e }.apply { this.name = name; start(); join() }

    @Test
    fun `an uncaught exception leaves the log and its stack trace, redacted, and goes on to the handler before`() = runBlocking<Unit> {
        LogBuffer.add(LogLine(0, Level.INFO, "UsenetEngine", "Building the streamer again"))
        LogBuffer.add(LogLine(0, Level.WARN, "PlayPipeline", "step failed"))
        val crash = CrashLog(File(dir, "logs/last-crash.txt"), header = "NZStreamer 0.1.0\nAndroid 15")
        assertNull(crash.read())
        assertNull(crash.writtenAt())
        crash.install()

        val boom = IllegalStateException("GET https://indexer.example.org/api?t=caps&apikey=0123abcd failed", IllegalArgumentException("AUTHINFO PASS hunter2"))
        crashOn("worker-7", boom)

        assertEquals(listOf("worker-7" to boom), handedOn.toList())
        val text = assertNotNull(crash.read())
        assertTrue(text.startsWith("NZStreamer 0.1.0\nAndroid 15\n"), text)
        assertTrue(Regex("""Crashed \d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} on thread worker-7""") in text, text)
        // What crashed first, then the log that led up to it.
        val lines = text.lines()
        val trace = lines.indexOf("java.lang.IllegalStateException: GET https://indexer.example.org/api?t=caps&apikey=<redacted> failed")
        val engine = lines.indexOfFirst { it.endsWith(" I UsenetEngine: Building the streamer again") }
        val pipeline = lines.indexOfFirst { it.endsWith(" W PlayPipeline: step failed") }
        assertTrue(trace in 0 until engine && engine < pipeline, text)
        assertTrue("Caused by: java.lang.IllegalArgumentException: AUTHINFO PASS <redacted>" in lines, text)
        assertTrue(lines.any { it.startsWith("\tat io.skjaere.usenet.logging.CrashLogTest") }, text)
        assertFalse("0123abcd" in text || "hunter2" in text, text)
        assertNotNull(crash.writtenAt())
        assertFalse(File(dir, "logs/last-crash.txt.tmp").exists())

        crash.delete()
        assertNull(crash.read())
        assertNull(crash.writtenAt())
    }

    @Test
    fun `a crash whose log can't be written still goes on to the handler before`() {
        // A file where the log's folder would be: nothing can be written there.
        File(dir, "logs").writeText("in the way")
        CrashLog(File(dir, "logs/last-crash.txt"), header = "h").install()
        val boom = OutOfMemoryError("no room")
        crashOn("main", boom)
        assertEquals(listOf("main" to boom), handedOn.toList())
    }

    /** Some of the header is learnt after start (the app's: the device's memory), so it is said as it is when the crash is written. */
    @Test
    fun `the header is the one there is when the crash is written`() = runBlocking<Unit> {
        var header = "NZStreamer 0.1.0"
        val crash = CrashLog(File(dir, "last-crash.txt")) { header }
        crash.install()
        header = "NZStreamer 0.1.0\nMemory: 2.0 GB RAM"
        crashOn("worker-2", RuntimeException("boom"))
        val text = assertNotNull(crash.read())
        assertTrue(text.startsWith("NZStreamer 0.1.0\nMemory: 2.0 GB RAM\n\n"), text)
    }

    @Test
    fun `a later crash replaces the log of the one before`() = runBlocking<Unit> {
        val crash = CrashLog(File(dir, "last-crash.txt"), header = "h")
        crash.install()
        crashOn("first", RuntimeException("first crash"))
        crashOn("second", RuntimeException("second crash"))
        val text = assertNotNull(crash.read())
        assertTrue("java.lang.RuntimeException: second crash" in text && "first crash" !in text, text)
    }
}
