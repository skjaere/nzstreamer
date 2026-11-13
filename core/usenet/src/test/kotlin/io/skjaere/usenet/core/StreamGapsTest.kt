package io.skjaere.usenet.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamGapsTest {
    @Test
    fun `stretches that touch or overlap are one, in file order`() {
        val gaps = StreamGaps()
        // A run of missing articles comes one article at a time, the read-ahead's in any order.
        gaps.add(300L..399L)
        gaps.add(100L..199L)
        gaps.add(200L..299L)
        gaps.add(600L..699L)
        gaps.add(650L..749L)
        gaps.add(900L..999L)
        assertEquals(listOf(100L..399L, 600L..749L, 900L..999L), gaps.all())
        // One that bridges two makes them one.
        gaps.add(750L..899L)
        assertEquals(listOf(100L..399L, 600L..999L), gaps.all())
        // Found again (a seek back, a second stream): nothing changes.
        gaps.add(150L..160L)
        assertEquals(listOf(100L..399L, 600L..999L), gaps.all())
    }

    @Test
    fun `the next stretch is the one holding a position, else the first after it`() {
        val gaps = StreamGaps()
        gaps.add(100L..199L)
        gaps.add(300L..399L)
        assertEquals(100L..199L, gaps.next(0))
        assertEquals(100L..199L, gaps.next(100))
        assertEquals(100L..199L, gaps.next(199))
        assertEquals(300L..399L, gaps.next(200))
        assertEquals(300L..399L, gaps.next(350))
        assertNull(gaps.next(400))
        assertNull(StreamGaps().next(0))
    }

    @Test
    fun `a listener hears of the stretches so far at once, then of each as it is added`() {
        val gaps = StreamGaps()
        gaps.add(100L..199L)
        gaps.add(200L..299L)
        gaps.add(400L..499L)
        val heard = mutableListOf<LongRange>()
        gaps.listen { heard += it }
        // Those so far as they are now, merged.
        assertEquals(listOf(100L..299L, 400L..499L), heard)
        // Then each as it comes, before it is merged with those it touches.
        gaps.add(500L..599L)
        assertEquals(listOf(100L..299L, 400L..499L, 500L..599L), heard)
        // Nothing for an empty range.
        gaps.add(LongRange.EMPTY)
        assertEquals(3, heard.size)
    }

    @Test
    fun `stretches added from several download threads at once are all kept`() {
        val gaps = StreamGaps()
        val heard = CopyOnWriteArrayList<LongRange>()
        gaps.listen { heard += it }
        val start = CountDownLatch(1)
        // Every other article of 400, from four threads: none touch.
        val threads = (0 until 4).map { t ->
            thread {
                start.await()
                for (i in t until 400 step 4) gaps.add(i * 2_000L until i * 2_000L + 1_000)
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals((0 until 400).map { it * 2_000L until it * 2_000L + 1_000 }, gaps.all())
        assertEquals(400, heard.size)
    }
}
