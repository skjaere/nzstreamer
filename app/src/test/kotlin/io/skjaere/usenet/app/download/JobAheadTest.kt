package io.skjaere.usenet.app.download

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The job's starts ahead of their walks (L335, C5): from the app's schedule of it, and from a start of it, until that
 * start's walk has taken the queue up or ended. While one is ahead the job reads as waiting to run, so the app's own
 * schedule never stops a job Android is starting, or has started and whose walk hasn't begun.
 */
class JobAheadTest {
    @Test
    fun `scheduled, then started, a job is ahead until its walk takes the queue up`() {
        val ahead = JobAhead()
        assertFalse(ahead.ahead)
        ahead.scheduling()
        assertTrue(ahead.ahead, "scheduled, not yet started here")
        val start = Any()
        ahead.started(start)
        assertTrue(ahead.ahead, "started, its walk not yet at the queue")
        ahead.past(start)
        assertFalse(ahead.ahead, "its walk at the queue: walking says the rest")
    }

    @Test
    fun `a start Android makes without the app's schedule is ahead too, and one that ends before its walk is past`() {
        val ahead = JobAhead()
        // The job a reboot or an update left waiting, run as the process starts.
        val start = Any()
        ahead.started(start)
        assertTrue(ahead.ahead)
        ahead.past(start)
        ahead.past(start)
        assertFalse(ahead.ahead)
    }

    @Test
    fun `a schedule as an earlier start ends is ahead until the start it brings, whatever the earlier one does`() {
        val ahead = JobAhead()
        val earlier = Any()
        ahead.started(earlier)
        ahead.past(earlier)
        // A changed switch, or a Download as the walk ended: Android stops the earlier start and runs the job again.
        ahead.scheduling()
        assertTrue(ahead.ahead)
        ahead.past(earlier)
        assertTrue(ahead.ahead, "the earlier start's end leaves the new schedule ahead")
        val later = Any()
        ahead.started(later)
        assertTrue(ahead.ahead)
        ahead.past(later)
        assertFalse(ahead.ahead)
    }

    @Test
    fun `a schedule Android refuses leaves nothing ahead, and one whose start came before it returned stays taken up`() {
        val ahead = JobAhead()
        ahead.scheduling()
        ahead.refused()
        assertFalse(ahead.ahead)
        // Android ran the job as the schedule was made: its start took the schedule up, and its walk the start.
        ahead.scheduling()
        val start = Any()
        ahead.started(start)
        ahead.past(start)
        assertFalse(ahead.ahead, "nothing left ahead once the start's walk is at the queue")
    }

    @Test
    fun `of two starts at once, the first's walk at the queue leaves the second ahead`() {
        val ahead = JobAhead()
        val first = Any()
        val second = Any()
        ahead.started(first)
        ahead.started(second)
        ahead.past(first)
        assertTrue(ahead.ahead)
        ahead.past(second)
        assertFalse(ahead.ahead)
    }
}
