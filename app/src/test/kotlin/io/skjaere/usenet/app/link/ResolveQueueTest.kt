package io.skjaere.usenet.app.link

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Below API 34, discovery's resolves one at a time (L12, the gate's note), as decisions over service names: the order,
 * a service lost while its resolve runs or after it was passed over, and one that met another resolve still running.
 * The names are invented.
 */
class ResolveQueueTest {
    private val queue = ResolveQueue<String> { it }

    @Test
    fun `one at a time, in the order found, a service found twice waiting once`() {
        assertEquals("Living room TV", queue.found("Living room TV"))
        assertNull(queue.found("Bedroom TV"), "one resolves already")
        assertNull(queue.found("Bedroom TV"))
        assertNull(queue.found("Den TV"))
        assertEquals(true to "Bedroom TV", queue.answered("Living room TV"))
        assertEquals(true to "Den TV", queue.answered("Bedroom TV"))
        assertEquals(true to null, queue.answered("Den TV"))
        assertNull(queue.resolving)
    }

    @Test
    fun `a service lost while it waits is never resolved`() {
        queue.found("Living room TV")
        queue.found("Bedroom TV")
        queue.lost("Bedroom TV")
        assertEquals(true to null, queue.answered("Living room TV"))
    }

    /** Its answer would bring a TV back that has gone, and no later word would take it away. */
    @Test
    fun `a service lost while its resolve runs is dropped when the answer comes, and the next one's turn comes`() {
        queue.found("Living room TV")
        queue.found("Bedroom TV")
        queue.lost("Living room TV")
        assertEquals(false to "Bedroom TV", queue.answered("Living room TV"))
        // Found again before its answer: wanted again.
        queue.lost("Bedroom TV")
        queue.found("Bedroom TV")
        assertTrue(queue.answered("Bedroom TV").first)
    }

    @Test
    fun `a stuck resolve is passed over, and its late answer is taken unless the service went`() {
        queue.found("Living room TV")
        queue.found("Bedroom TV")
        queue.found("Den TV")
        assertEquals("Bedroom TV", queue.passOver("Living room TV"))
        assertEquals("Bedroom TV", queue.resolving)
        assertEquals(true to null, queue.answered("Living room TV"), "late, and still wanted; Bedroom TV still resolves")
        assertEquals("Den TV", queue.passOver("Bedroom TV"))
        queue.lost("Bedroom TV")
        assertFalse(queue.answered("Bedroom TV").first, "late, and gone")
        assertNull(queue.passOver("Living room TV"), "not resolving now: nothing passed over")
    }

    @Test
    fun `a resolve that meets another still running is tried again later, unless lost meanwhile`() {
        queue.found("Living room TV")
        queue.found("Bedroom TV")
        assertNull(queue.failed("Living room TV", busy = true), "none starts while the other runs")
        assertNull(queue.resolving)
        assertEquals("Bedroom TV", queue.retry("Living room TV").let { queue.resolving })
        assertEquals(true to "Living room TV", queue.answered("Bedroom TV"))
        assertEquals(true to null, queue.answered("Living room TV"))

        queue.found("Den TV")
        queue.failed("Den TV", busy = true)
        queue.lost("Den TV")
        assertNull(queue.retry("Den TV"), "lost while it waited to be tried again")
        assertNull(queue.resolving)
    }

    @Test
    fun `a resolve that fails otherwise gives the next one its turn`() {
        queue.found("Living room TV")
        queue.found("Bedroom TV")
        assertEquals("Bedroom TV", queue.failed("Living room TV", busy = false))
    }
}
