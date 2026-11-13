package io.skjaere.usenet.app.collection.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionTest {
    private val keys = (1..6).map { TitleKey.show(it) }

    @Test
    fun `a long press starts with its row, Select with nothing`() {
        assertEquals(Selection(true, setOf(keys[2])), Selection().start(keys[2]))
        assertEquals(Selection(true, emptySet()), Selection().start())
        assertFalse(Selection().active)
    }

    @Test
    fun `a row toggles, and exit forgets everything`() {
        val s = Selection().start().toggle(keys[0]).toggle(keys[1]).toggle(keys[0])
        assertEquals(setOf(keys[1]), s.keys)
        assertEquals(Selection(), s.exit())
    }

    @Test
    fun `select all takes the visible rows, and again none of them`() {
        val visible = keys.take(4)
        val all = Selection().start(keys[5]).selectAll(visible)
        assertEquals(setOf(keys[0], keys[1], keys[2], keys[3], keys[5]), all.keys)
        assertTrue(all.allSelected(visible))
        assertEquals(setOf(keys[5]), all.selectAll(visible).keys)
        assertFalse(Selection().start().allSelected(emptyList()))
    }

    @Test
    fun `actions take what is selected and still visible, in list order`() {
        val s = Selection().start(keys[4]).toggle(keys[1]).toggle(keys[3])
        assertEquals(listOf(keys[1], keys[3], keys[4]), s.effective(keys))
        // A filter or a search hides some: only the visible ones count.
        assertEquals(listOf(keys[3], keys[1]), s.effective(listOf(keys[3], keys[0], keys[1])))
        assertEquals(2, s.count(listOf(keys[3], keys[0], keys[1])))
    }

    @Test
    fun `the selection is saved while it is small`() {
        val small = Selection().start().selectAll(keys)
        assertEquals(keys.map { it.value }, small.saved())
        assertEquals(small, Selection.restore(small.saved()))
        val big = Selection().start().selectAll((1..301).map { TitleKey.movie(it) })
        assertNull(big.saved())
        assertEquals(Selection(), Selection.restore(null))
        assertEquals(Selection(true, setOf(keys[0])), Selection.restore(listOf(keys[0].value, "nonsense")))
    }
}
