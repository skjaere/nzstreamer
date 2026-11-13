package io.skjaere.usenet.logging

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the app's slf4j logger hands logcat and the in-app log ([LogBuffer]). */
class AndroidLoggerTest {
    private val logcat = mutableListOf<String>()
    private val saved = AndroidLogConfig.minLevel
    private val log = LoggerFactory.getLogger("io.skjaere.test.Probe")

    @BeforeEach
    fun capture() {
        AndroidLogConfig.logcat = { _, tag, message -> synchronized(logcat) { logcat += "$tag: $message" } }
        LogBuffer.clear()
    }

    @AfterEach
    fun restore() {
        AndroidLogConfig.logcat = AndroidLogConfig.systemLog
        AndroidLogConfig.minLevel = saved
        LogBuffer.clear()
    }

    @Test
    fun `secrets are written over before a line reaches logcat or the in-app log`() {
        AndroidLogConfig.detailed = false
        log.info("GET {}", "https://indexer.example.org/api?t=search&apikey=0123abcd")
        log.warn("Login failed", IOException("refused for nntps://alice:hunter2@news.example.com"))
        val buffered = LogBuffer.snapshot().map { it.message }
        assertEquals("GET https://indexer.example.org/api?t=search&apikey=<redacted>", buffered[0])
        assertEquals("Login failed (IOException: refused for nntps://<redacted>@news.example.com)", buffered[1])
        assertEquals(2, logcat.size)
        assertEquals("Probe: GET https://indexer.example.org/api?t=search&apikey=<redacted>", logcat[0])
        // Logcat gets the stack trace, redacted too.
        assertTrue(logcat[1].startsWith("Probe: Login failed\njava.io.IOException: refused for nntps://<redacted>@news.example.com\n"), logcat[1])
        assertTrue("\tat io.skjaere.usenet.logging.AndroidLoggerTest" in logcat[1], logcat[1])
        listOf("0123abcd", "hunter2", "alice").forEach { secret ->
            assertFalse((buffered + logcat).any { secret in it }, "$secret reached a log")
        }
    }

    @Test
    fun `detailed logging lets debug lines through to logcat and the in-app log alike`() {
        AndroidLogConfig.detailed = false
        log.debug("quiet {}", 1)
        log.info("plain {}", 2)
        assertEquals(listOf("plain 2"), LogBuffer.snapshot().map { it.message })
        assertEquals(listOf("Probe: plain 2"), logcat)

        AndroidLogConfig.detailed = true
        assertEquals(Level.DEBUG, AndroidLogConfig.minLevel)
        log.debug("detailed {}", 3)
        log.trace("never {}", 4)
        assertEquals(listOf("plain 2", "detailed 3"), LogBuffer.snapshot().map { it.message })
        assertEquals(Level.DEBUG, LogBuffer.snapshot().last().level)
        assertEquals(listOf("Probe: plain 2", "Probe: detailed 3"), logcat)
        assertTrue(AndroidLogConfig.detailed)

        AndroidLogConfig.detailed = false
        assertEquals(Level.INFO, AndroidLogConfig.minLevel)
    }

    @Test
    fun `the stream-cost line, one every 10 s of a play, keeps to its own last lines and pushes nothing else out`() {
        AndroidLogConfig.detailed = false
        val streamCost = LoggerFactory.getLogger("io.skjaere.usenet.core.StreamCost")
        log.warn("Playback failed: the first film")
        streamCost.warn("Stopped the stream-cost line")
        // Six hours of play.
        repeat(2_160) { streamCost.info("Stream cost over 10.0 s: line {}", it) }
        val kept = LogBuffer.snapshot()
        // The warnings stay with the rest, and of the line its last 10 minutes, in the order they came.
        assertEquals(
            listOf("Playback failed: the first film", "Stopped the stream-cost line") + (2_100 until 2_160).map { "Stream cost over 10.0 s: line $it" },
            kept.map { it.message },
        )
        assertEquals(kept.map { it.seq }.sorted(), kept.map { it.seq })
        // Logcat has every one.
        assertEquals(2_162, logcat.size)
    }
}
