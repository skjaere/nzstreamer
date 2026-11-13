package io.skjaere.usenet.app.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InFrontTest {
    /** An InFront on the test's clock, and every value it has had, in order. */
    private fun TestScope.inFront(): Pair<InFront, List<Boolean>> {
        val front = InFront(backgroundScope)
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { front.value.collect { seen += it } }
        runCurrent()
        return front to seen
    }

    @Test
    fun `out of front until an activity starts`() = runTest {
        val (front, seen) = inFront()
        assertFalse(front.value.value)
        front.started()
        runCurrent()
        assertTrue(front.value.value)
        assertEquals(listOf(false, true), seen)
    }

    /** The player over MainActivity: two started, then each stops; in front until the last has been stopped a while. */
    @Test
    fun `counts the started activities`() = runTest {
        val (front, seen) = inFront()
        front.started()
        front.started()
        front.stopped()
        advanceTimeBy(5_000)
        assertTrue(front.value.value, "one activity still started")
        front.stopped()
        runCurrent()
        assertTrue(front.value.value, "within the grace")
        advanceTimeBy(InFront.GRACE_MS + 1)
        assertFalse(front.value.value)
        assertEquals(listOf(false, true, false), seen)
    }

    @Test
    fun `out of front the grace after the last stop, not before`() = runTest {
        val (front, _) = inFront()
        front.started()
        front.stopped()
        advanceTimeBy(InFront.GRACE_MS - 1)
        assertTrue(front.value.value)
        advanceTimeBy(2)
        assertFalse(front.value.value)
    }

    /** A rotation stops the old activity before it starts the new one: the count passes through 0 and nothing changes. */
    @Test
    fun `a recreation stays in front`() = runTest {
        val (front, seen) = inFront()
        front.started()
        repeat(3) {
            front.stopped()
            advanceTimeBy(50)
            front.started()
        }
        advanceTimeBy(10 * InFront.GRACE_MS)
        assertTrue(front.value.value)
        assertEquals(listOf(false, true), seen, "never out of front on the way")
    }

    /** A grace cut short by a start, then a stop: out of front only once the second stop's grace has passed. */
    @Test
    fun `each stop's grace is its own`() = runTest {
        val (front, _) = inFront()
        front.started()
        front.stopped()
        advanceTimeBy(900)
        front.started()
        advanceTimeBy(50)
        front.stopped()
        advanceTimeBy(InFront.GRACE_MS - 100)
        assertTrue(front.value.value, "the first stop's grace ended long ago, the second's not yet")
        advanceTimeBy(200)
        assertFalse(front.value.value)
    }

    @Test
    fun `back in front at once after leaving`() = runTest {
        val (front, seen) = inFront()
        front.started()
        front.stopped()
        advanceTimeBy(InFront.GRACE_MS + 1)
        front.started()
        runCurrent()
        assertTrue(front.value.value)
        assertEquals(listOf(false, true, false, true), seen)
    }

    /** A stop whose start came before the callbacks were registered never takes the count below 0. */
    @Test
    fun `a stray stop changes nothing`() = runTest {
        val (front, _) = inFront()
        front.stopped()
        front.started()
        advanceTimeBy(10 * InFront.GRACE_MS)
        assertTrue(front.value.value, "the start still counts")
        front.stopped()
        advanceTimeBy(InFront.GRACE_MS + 1)
        assertFalse(front.value.value)
    }
}
