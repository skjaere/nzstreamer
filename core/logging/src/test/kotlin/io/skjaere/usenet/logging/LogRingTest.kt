package io.skjaere.usenet.logging

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.slf4j.event.Level
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The in-app log's ring of recent lines: what it keeps, in what order, and that adding a line costs the same however full it is. */
class LogRingTest {
    private fun line(n: Int, level: Level = Level.INFO) = LogLine(n.toLong(), level, "Tag", "line $n")

    @Test
    fun `the last lines are kept oldest first, each numbered as it was added`() {
        val ring = LogRing(3)
        assertEquals(emptyList(), ring.snapshot())
        (1..2).forEach { ring.add(line(it)) }
        assertEquals(listOf("line 1", "line 2"), ring.snapshot().map { it.message })
        (3..7).forEach { ring.add(line(it)) }
        val kept = ring.snapshot()
        assertEquals(listOf("line 5", "line 6", "line 7"), kept.map { it.message })
        assertEquals(listOf(5L, 6L, 7L), kept.map { it.seq })
    }

    @Test
    fun `the version moves with every line and a clear, and a snapshot taken earlier stays as it was`() {
        val ring = LogRing(2)
        val versions = mutableListOf(ring.version.value)
        ring.add(line(1))
        versions += ring.version.value
        val before = ring.snapshot()
        ring.add(line(2))
        ring.add(line(3))
        versions += ring.version.value
        ring.clear()
        versions += ring.version.value
        assertEquals(versions.distinct(), versions)
        assertEquals(listOf("line 1"), before.map { it.message })
        assertEquals(emptyList(), ring.snapshot())
        // Numbering goes on after a clear, so a line is never taken for one shown before it.
        ring.add(line(4))
        assertEquals(4L, ring.snapshot().single().seq)
    }

    @Test
    fun `a tag kept apart keeps its own last lines, merged with the rest's in the order they came`() {
        val ring = LogRing(3, apart = mapOf("Tick" to 2))
        fun tick(n: Int, level: Level = Level.INFO) = LogLine(n.toLong(), level, "Tick", "tick $n")
        ring.add(line(1))
        ring.add(tick(2))
        ring.add(line(3))
        (4..7).forEach { ring.add(tick(it)) }
        ring.add(line(8))
        // A warning of the tag's stays with the rest.
        ring.add(tick(9, Level.WARN))
        ring.add(tick(10))
        val kept = ring.snapshot()
        assertEquals(listOf("line 3", "tick 7", "line 8", "tick 9", "tick 10"), kept.map { it.message })
        assertEquals(listOf(3L, 7L, 8L, 9L, 10L), kept.map { it.seq })
        ring.clear()
        assertEquals(emptyList(), ring.snapshot())
        ring.add(tick(11))
        assertEquals(listOf(11L), ring.snapshot().map { it.seq })
    }

    /**
     * A ring of 100 000 lines takes 200 000 well within the time (some milliseconds): copying the ring
     * on every line, as the log once did, is 1.5·10¹⁰ line copies.
     */
    @Test
    fun `adding a line doesn't copy the ring`() {
        val ring = LogRing(100_000)
        val lines = List(1_000) { line(it) }
        assertTimeoutPreemptively(Duration.ofSeconds(3)) {
            repeat(200) { lines.forEach(ring::add) }
        }
        assertEquals(100_000, ring.snapshot().size)
        assertNotEquals(0L, ring.version.value)
    }

    @Test
    fun `lines added from many threads are all counted`() {
        val ring = LogRing(100_000)
        val threads = List(8) { t -> Thread { repeat(5_000) { ring.add(line(t * 10_000 + it)) } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        val kept = ring.snapshot()
        assertEquals(40_000, kept.size)
        assertEquals((1L..40_000L).toList(), kept.map { it.seq })
        assertTrue(kept.groupBy { it.message.removePrefix("line ").toInt() / 10_000 }.values.all { it.size == 5_000 })
    }

    @Test
    fun `a line as text has its date and time, level, tag and message`() {
        val at = LocalDateTime.of(2026, 10, 16, 14, 2, 31, 123_000_000).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("2026-10-16 14:02:31.123 W PlayPipeline: step failed", LogLine(at, Level.WARN, "PlayPipeline", "step failed").text(ZoneOffset.UTC))
        // The letters logcat uses.
        assertEquals(listOf("V", "D", "I", "W", "E"), Level.entries.sortedBy { it.toInt() }.map { LogLine(at, it, "T", "m").text(ZoneOffset.UTC).split(' ')[2] })
    }
}
