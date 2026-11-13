package io.skjaere.usenet.app.play

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L33, L34: the play sheet's state as the coordinator's jobs write it, each write with the session of the play it is
 * for. A job's cancel doesn't stop a write it is making on another thread at that moment (nor what a channel's buffer
 * still hands it): once another play has taken the sheet, what the job before writes is dropped, never told to the new
 * play's phone as its own. The titles and phones are invented.
 */
class SheetWritesTest {
    private val tablet = RemotePlay("s-b", "bb".repeat(32), "Invented tablet")
    private val copper = PlayState.Searching("Copper Crown", "2019", "Searching your indexers…")
    private val copperOn = PlayState.Searching("Copper Crown", "2019", "Searched 2 indexers · 9 found")
    private val harbor = PlayState.Searching("Harbor Lights", "S02E04 · Low Water", "Searching your indexers…")

    /** A job of the sheet's play now, writing on a thread of its own: its first write, then [rest] once [go] completes. */
    private fun kotlinx.coroutines.CoroutineScope.job(writes: SheetWrites, first: PlayState, go: CompletableDeferred<Unit>, rest: () -> Unit) = launch(Dispatchers.Default + writes.writer()) {
        writes.value = first
        go.await()
        rest()
    }

    @Test
    fun `what a job another play replaced writes is dropped, never told as the new play's`() = runBlocking<Unit> {
        val writes = SheetWrites()
        // The TV's own play, Copper Crown, mid-way through its search.
        writes.begin(PlayOrigin.Here)
        val go = CompletableDeferred<Unit>()
        var sent = true
        val before = job(writes, copper, go) {
            writes.value = copperOn
            sent = writes.ifCurrent { }
        }
        while (writes.state.value != copper) Thread.yield()
        // A phone's Play takes the sheet on the main thread as the job's next write is on its way.
        writes.begin(PlayOrigin(remote = tablet))
        go.complete(Unit)
        before.join()
        assertTrue(writes.sheet.value.remote == null || writes.sheet.value.state != copperOn, "Copper Crown told to the tablet: ${writes.sheet.value.state}")
        assertEquals(copper, writes.state.value, "nor shown")
        assertFalse(sent, "nor a launch sent")

        // The new play's own writes are its: from its job, and from the main thread.
        launch(Dispatchers.Default + writes.writer()) { writes.value = harbor }.join()
        assertEquals(SheetNow(harbor, tablet), writes.sheet.value)
        writes.value = PlayState.Idle
        assertEquals(SheetNow(PlayState.Idle, tablet), writes.sheet.value)
    }

    @Test
    fun `after Cancel what the cancelled job still writes is dropped`() = runBlocking<Unit> {
        val writes = SheetWrites()
        writes.begin(PlayOrigin(remote = tablet))
        val go = CompletableDeferred<Unit>()
        val cancelled = job(writes, harbor, go) { writes.value = copperOn }
        while (writes.state.value != harbor) Thread.yield()
        writes.end()
        writes.value = PlayState.Idle
        go.complete(Unit)
        cancelled.join()
        assertEquals(SheetNow(PlayState.Idle, tablet), writes.sheet.value)
    }
}
