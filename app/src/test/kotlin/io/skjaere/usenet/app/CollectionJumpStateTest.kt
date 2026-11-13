package io.skjaere.usenet.app

import androidx.compose.runtime.saveable.SaverScope
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The jump "+ Collection" makes is consumed once; Back to Search stays armed only within the Collection tab. */
class CollectionJumpStateTest {
    private val orbit = CollectionJump("show:900005", "The Quiet Orbit")

    @Test
    fun `adding arms the jump and Back to Search, and the jump is consumed once`() {
        val state = CollectionJumpState()
        state.added(orbit)
        assertEquals(orbit, state.jump)
        assertTrue(state.backToSearch)
        state.jumpDone()
        assertNull(state.jump)
        // Revealed: Back still returns to Search.
        assertTrue(state.backToSearch)
        state.backHandled()
        assertFalse(state.backToSearch)
    }

    @Test
    fun `leaving the Collection tab disarms Back to Search, staying in it doesn't`() {
        val state = CollectionJumpState()
        state.added(orbit)
        state.destinationChanged(inCollectionTab = true, onList = true)
        assertTrue(state.backToSearch)
        // A Show page opened from the list.
        state.destinationChanged(inCollectionTab = true, onList = false)
        assertTrue(state.backToSearch)
        state.destinationChanged(inCollectionTab = false, onList = false)
        assertFalse(state.backToSearch)
        // Coming back to the tab doesn't arm it again.
        state.destinationChanged(inCollectionTab = true, onList = true)
        assertFalse(state.backToSearch)
    }

    @Test
    fun `a jump the list hasn't revealed yet goes with the list, so a later visit doesn't replay it`() {
        val state = CollectionJumpState()
        state.added(orbit)
        state.destinationChanged(inCollectionTab = true, onList = true)
        assertEquals(orbit, state.jump)
        // Another tab, Back to Search, an opened .nzb.
        state.destinationChanged(inCollectionTab = false, onList = false)
        assertNull(state.jump)

        // A Show page opened from the list mid-reveal: Back to Search stays armed, the jump doesn't.
        val page = CollectionJumpState()
        page.added(orbit)
        page.destinationChanged(inCollectionTab = true, onList = true)
        page.destinationChanged(inCollectionTab = true, onList = false)
        assertNull(page.jump)
        assertTrue(page.backToSearch)
    }

    @Test
    fun `the jump and Back to Search survive being saved and restored`() {
        val scope = SaverScope { true }
        val state = CollectionJumpState()
        state.added(CollectionJump("movie:900011", "Copper Crown", added = false))
        val restored = assertNotNull(CollectionJumpState.Saver.restore(with(CollectionJumpState.Saver) { scope.save(state) }!!))
        assertEquals(CollectionJump("movie:900011", "Copper Crown", added = false), restored.jump)
        assertTrue(restored.backToSearch)

        state.jumpDone()
        val done = assertNotNull(CollectionJumpState.Saver.restore(with(CollectionJumpState.Saver) { scope.save(state) }!!))
        assertNull(done.jump)
        assertTrue(done.backToSearch)
    }
}
