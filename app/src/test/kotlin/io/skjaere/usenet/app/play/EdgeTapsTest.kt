package io.skjaere.usenet.app.play

import android.view.MotionEvent
import io.skjaere.usenet.app.play.EdgeTaps.Act
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Double-tap to seek on a 900 px wide picture: its thirds end at 300 and 600 px. */
class EdgeTapsTest {
    private var clock = 1_000_000L

    private fun edgeTaps() = EdgeTaps(now = { clock }, touchSlop = TOUCH_SLOP, doubleTapSlop = DOUBLE_TAP_SLOP)

    private fun at(x: Float, y: Float = 200f, tool: Int = MotionEvent.TOOL_TYPE_FINGER) = EdgeTaps.Touch(x, y, WIDTH, tool)

    /** A finger down at ([x], [y]) [afterMs] after the last event, that reached the picture unless [reached] says not. */
    private fun EdgeTaps.down(x: Float, afterMs: Long = 0, y: Float = 200f, reached: Boolean = true, scene: EdgeTaps.Scene = PLAYING, tool: Int = MotionEvent.TOOL_TYPE_FINGER): Act {
        clock += afterMs
        return down(at(x, y, tool), reached, scene)
    }

    /** That finger up at ([x], [y]), [afterMs] later. */
    private fun EdgeTaps.up(x: Float, afterMs: Long = 60, y: Float = 200f, tool: Int = MotionEvent.TOOL_TYPE_FINGER): Act {
        clock += afterMs
        return up(at(x, y, tool))
    }

    /** A tap: what its down and its up did. */
    private fun EdgeTaps.tap(x: Float, afterMs: Long = 0, y: Float = 200f, reached: Boolean = true, scene: EdgeTaps.Scene = PLAYING, tool: Int = MotionEvent.TOOL_TYPE_FINGER): Pair<Act, Act> =
        down(x, afterMs, y, reached, scene, tool) to up(x, y = y, tool = tool)

    /** The clock moved on by [ms], and the policy told, as the player's handler does when the wait is up. */
    private fun EdgeTaps.wait(ms: Long): Act {
        clock += ms
        return timeUp()
    }

    @Test
    fun `a single tap at a side waits out the double-tap timeout before the controls show or hide (W1)`() {
        listOf(LEFT, RIGHT).forEach { x ->
            val e = edgeTaps()
            assertEquals(Act.Pass to Act.Wait, e.tap(x), "at $x")
            assertEquals(EdgeTaps.DOUBLE_TAP_MS, e.toggleDueInMs())
            assertEquals(Act.Pass, e.wait(EdgeTaps.DOUBLE_TAP_MS - 1))
            assertEquals(1, e.toggleDueInMs())
            assertEquals(Act.Toggle, e.wait(1))
            assertNull(e.toggleDueInMs())
            assertEquals(Act.Pass, e.wait(1_000))
        }
    }

    @Test
    fun `the wait runs from the first tap's up, however long it was held`() {
        val e = edgeTaps()
        e.down(RIGHT)
        assertEquals(Act.Wait, e.up(RIGHT, afterMs = 900))
        assertEquals(EdgeTaps.DOUBLE_TAP_MS, e.toggleDueInMs())
    }

    @Test
    fun `a tap in the middle third shows or hides the controls at once, and a double-tap there does nothing new`() {
        val e = edgeTaps()
        assertEquals(Act.Pass to Act.Pass, e.tap(CENTRE))
        assertNull(e.toggleDueInMs())
        assertEquals(Act.Pass to Act.Pass, e.tap(CENTRE, afterMs = 100))
        assertNull(e.toggleDueInMs())
    }

    @Test
    fun `a double-tap at the right goes forward as its second tap lifts, at the left back, and the controls stay as they were`() {
        val right = edgeTaps()
        assertEquals(Act.Pass to Act.Wait, right.tap(RIGHT))
        assertEquals(Act.Pass to Act.Forward, right.tap(RIGHT, afterMs = 100))
        assertNull(right.toggleDueInMs())
        assertEquals(Act.Pass, right.wait(1_000))
        val left = edgeTaps()
        left.tap(LEFT)
        assertEquals(Act.Pass to Act.Back, left.tap(LEFT, afterMs = 100))
        assertEquals(Act.Pass, left.wait(1_000))
    }

    @Test
    fun `while the second tap is down nothing shows or hides, however long it stays`() {
        val e = edgeTaps()
        e.tap(RIGHT)
        assertEquals(Act.Pass, e.down(RIGHT, afterMs = 100))
        assertNull(e.toggleDueInMs())
        assertEquals(Act.Pass, e.wait(1_000))
        assertEquals(Act.Forward, e.up(RIGHT, afterMs = 0))
    }

    @Test
    fun `the zones are the outer thirds, their edges at exactly a third and two thirds the middle's`() {
        fun doubleTap(x: Float): Act {
            val e = edgeTaps()
            e.tap(x)
            return e.tap(x, afterMs = 100).second
        }
        assertEquals(Act.Back, doubleTap(299.9f))
        assertEquals(Act.Pass, doubleTap(300f))
        assertEquals(Act.Pass, doubleTap(450f))
        assertEquals(Act.Pass, doubleTap(600f))
        assertEquals(Act.Forward, doubleTap(600.1f))
        assertEquals(Act.Back, doubleTap(0f))
        assertEquals(Act.Forward, doubleTap(900f))
    }

    @Test
    fun `a second tap within the double-tap timeout of the first's up is its double, one past it a tap of its own`() {
        val within = edgeTaps()
        within.tap(RIGHT)
        assertEquals(Act.Pass to Act.Forward, within.tap(RIGHT, afterMs = EdgeTaps.DOUBLE_TAP_MS))
        val past = edgeTaps()
        past.tap(RIGHT)
        // The first acts now, its wait over; this one waits in its turn.
        assertEquals(Act.Toggle to Act.Wait, past.tap(RIGHT, afterMs = EdgeTaps.DOUBLE_TAP_MS + 1))
        assertEquals(EdgeTaps.DOUBLE_TAP_MS, past.toggleDueInMs())
    }

    @Test
    fun `a second tap within the double-tap slop of the first is its double, one farther a tap of its own`() {
        val within = edgeTaps()
        within.tap(RIGHT, y = 100f)
        assertEquals(Act.Pass to Act.Forward, within.tap(RIGHT, afterMs = 100, y = 100f + DOUBLE_TAP_SLOP))
        val past = edgeTaps()
        past.tap(RIGHT, y = 100f)
        assertEquals(Act.Toggle to Act.Wait, past.tap(RIGHT, afterMs = 100, y = 100f + DOUBLE_TAP_SLOP + 1))
    }

    @Test
    fun `a second tap in another third is a tap of its own, near as it may be`() {
        val middle = edgeTaps()
        middle.tap(610f)
        assertEquals(Act.Toggle to Act.Pass, middle.tap(590f, afterMs = 100))
        assertNull(middle.toggleDueInMs())
        val left = edgeTaps()
        left.tap(290f)
        assertEquals(Act.Toggle to Act.Pass, left.tap(310f, afterMs = 100))
        val across = edgeTaps()
        across.tap(LEFT)
        assertEquals(Act.Toggle to Act.Wait, across.tap(RIGHT, afterMs = 100))
    }

    @Test
    fun `after a double-tap, single taps at either side within the burst's window add to it, the burst held while each is down`() {
        val e = edgeTaps()
        e.tap(RIGHT)
        assertEquals(Act.Forward, e.tap(RIGHT, afterMs = 100).second)
        assertEquals(Act.Hold to Act.Forward, e.tap(RIGHT, afterMs = 200))
        // Anywhere in the third, not only near the taps before.
        assertEquals(Act.Hold to Act.Forward, e.tap(880f, afterMs = 200, y = 20f))
        // The other side takes 10 s off.
        assertEquals(Act.Hold to Act.Back, e.tap(LEFT, afterMs = 200))
        assertEquals(Act.Hold to Act.Back, e.tap(LEFT, afterMs = EdgeTaps.WINDOW_MS))
        assertNull(e.toggleDueInMs())
        // Past the window: a tap of its own, which waits.
        assertEquals(Act.Pass to Act.Wait, e.tap(RIGHT, afterMs = EdgeTaps.WINDOW_MS + 1))
    }

    @Test
    fun `a tap in the middle ends the taps that add up, and is a tap as ever`() {
        val e = edgeTaps()
        e.tap(RIGHT)
        e.tap(RIGHT, afterMs = 100)
        assertEquals(Act.Pass to Act.Pass, e.tap(CENTRE, afterMs = 200))
        assertEquals(Act.Pass to Act.Wait, e.tap(RIGHT, afterMs = 100))
    }

    @Test
    fun `a second finger or a drag makes a touch no tap`() {
        // A second tap with another finger down: the first tap was a single one after all, and acts now.
        val pinched = edgeTaps()
        pinched.tap(RIGHT)
        pinched.down(RIGHT, afterMs = 100)
        assertEquals(Act.Toggle, pinched.pointerDown())
        assertEquals(Act.Pass, pinched.up(RIGHT))
        assertNull(pinched.toggleDueInMs())
        // A second tap that drags.
        val dragged = edgeTaps()
        dragged.tap(RIGHT)
        dragged.down(RIGHT, afterMs = 100)
        assertEquals(Act.Pass, dragged.move(at(RIGHT + TOUCH_SLOP)))
        assertEquals(Act.Toggle, dragged.move(at(RIGHT + TOUCH_SLOP + 1)))
        assertEquals(Act.Pass, dragged.up(RIGHT + 40))
        // One that moves within the touch slop is still a tap.
        val steady = edgeTaps()
        steady.tap(RIGHT)
        steady.down(RIGHT, afterMs = 100)
        assertEquals(Act.Pass, steady.move(at(RIGHT, 200f + TOUCH_SLOP)))
        assertEquals(Act.Forward, steady.up(RIGHT, y = 200f + TOUCH_SLOP))
        // A first tap that drags is no tap of this policy's: the views have it, and show or hide the controls at once.
        val drag = edgeTaps()
        drag.down(RIGHT)
        assertEquals(Act.Pass, drag.move(at(RIGHT - 50)))
        assertEquals(Act.Pass, drag.up(RIGHT - 50))
        assertNull(drag.toggleDueInMs())
        // An up that lands past the slop with no move before it, the same.
        val far = edgeTaps()
        far.down(RIGHT)
        assertEquals(Act.Pass, far.up(RIGHT - 50))
        assertNull(far.toggleDueInMs())
        // A tap that adds up, with a second finger: the burst's seek follows as usual.
        val adding = edgeTaps()
        adding.tap(RIGHT)
        adding.tap(RIGHT, afterMs = 100)
        assertEquals(Act.Hold, adding.down(RIGHT, afterMs = 200))
        assertEquals(Act.Settle, adding.pointerDown())
        assertEquals(Act.Pass, adding.up(RIGHT))
        // Android's own cancel (a system gesture took the touch).
        val cancelled = edgeTaps()
        cancelled.tap(RIGHT)
        cancelled.down(RIGHT, afterMs = 100)
        assertEquals(Act.Toggle, cancelled.cancel())
        assertNull(cancelled.toggleDueInMs())
    }

    @Test
    fun `a tap and then a pinch never seek`() {
        val e = edgeTaps()
        assertEquals(Act.Wait, e.tap(RIGHT).second)
        e.down(RIGHT + 20, afterMs = 100)
        assertEquals(Act.Toggle, e.pointerDown())
        listOf(100f, 200f, 300f).forEach { assertEquals(Act.Pass, e.move(at(RIGHT + 20 - it))) }
        assertEquals(Act.Pass, e.up(RIGHT - 280))
        assertEquals(Act.Pass, e.wait(1_000))
        assertNull(e.toggleDueInMs())
    }

    @Test
    fun `only taps that reached the picture count, so a double-tap on a button or an overlay seeks nothing`() {
        val button = edgeTaps()
        assertEquals(Act.Pass to Act.Pass, button.tap(RIGHT, reached = false))
        assertEquals(Act.Pass to Act.Pass, button.tap(RIGHT, afterMs = 100, reached = false))
        assertNull(button.toggleDueInMs())
        // A second tap that lands on a button come up meanwhile: the first acts now, the second is the button's.
        val appeared = edgeTaps()
        appeared.tap(RIGHT)
        assertEquals(Act.Toggle to Act.Pass, appeared.tap(RIGHT, afterMs = 100, reached = false))
        // During taps that add up, one on a button (Next, in the right third with the controls up) is the button's, and ends them.
        val next = edgeTaps()
        next.tap(RIGHT)
        next.tap(RIGHT, afterMs = 100)
        assertEquals(Act.Pass to Act.Pass, next.tap(RIGHT, afterMs = 200, reached = false))
        assertEquals(Act.Pass to Act.Wait, next.tap(RIGHT, afterMs = 100))
    }

    @Test
    fun `off on a TV, under a side panel or Playback stopped, before the first frame and with TalkBack's touch exploration on`() {
        listOf(
            EdgeTaps.Scene(tv = true), EdgeTaps.Scene(panel = true), EdgeTaps.Scene(failure = true), EdgeTaps.Scene(pictureShown = false),
            EdgeTaps.Scene(touchExploration = true),
        ).forEach { scene ->
            val e = edgeTaps()
            assertEquals(Act.Pass to Act.Pass, e.tap(RIGHT, scene = scene), "$scene")
            assertEquals(Act.Pass to Act.Pass, e.tap(RIGHT, afterMs = 100, scene = scene), "$scene")
            assertNull(e.toggleDueInMs(), "$scene")
        }
        // A tap that waits acts at once as a touch comes that this policy can't count (the panel's close, "Playback stopped").
        val waiting = edgeTaps()
        waiting.tap(RIGHT)
        assertEquals(Act.Toggle to Act.Pass, waiting.tap(RIGHT, afterMs = 100, scene = EdgeTaps.Scene(failure = true)))
        // Taps that add up end there too.
        val adding = edgeTaps()
        adding.tap(RIGHT)
        adding.tap(RIGHT, afterMs = 100)
        assertEquals(Act.Pass to Act.Pass, adding.tap(RIGHT, afterMs = 100, scene = EdgeTaps.Scene(panel = true)))
        assertEquals(Act.Pass to Act.Wait, adding.tap(RIGHT, afterMs = 100))
    }

    @Test
    fun `a mouse's clicks seek nothing, but a stylus and a tool Android doesn't name count`() {
        val mouse = edgeTaps()
        assertEquals(Act.Pass to Act.Pass, mouse.tap(RIGHT, tool = MotionEvent.TOOL_TYPE_MOUSE))
        assertEquals(Act.Pass to Act.Pass, mouse.tap(RIGHT, afterMs = 100, tool = MotionEvent.TOOL_TYPE_MOUSE))
        listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_UNKNOWN, MotionEvent.TOOL_TYPE_FINGER).forEach { tool ->
            val e = edgeTaps()
            assertEquals(Act.Pass to Act.Wait, e.tap(RIGHT, tool = tool), "tool $tool")
            assertEquals(Act.Pass to Act.Forward, e.tap(RIGHT, afterMs = 100, tool = tool), "tool $tool")
        }
    }

    @Test
    fun `as the player goes, a waiting tap and the taps that add up are forgotten`() {
        val waiting = edgeTaps()
        waiting.tap(RIGHT)
        waiting.reset()
        assertNull(waiting.toggleDueInMs())
        assertEquals(Act.Pass, waiting.wait(1_000))
        val adding = edgeTaps()
        adding.tap(RIGHT)
        adding.tap(RIGHT, afterMs = 100)
        adding.reset()
        assertEquals(Act.Pass to Act.Wait, adding.tap(RIGHT, afterMs = 100))
    }

    @Test
    fun `the views get a cancel for the ups this policy takes, and every other event as it is`() {
        assertEquals(setOf(Act.Wait, Act.Back, Act.Forward), Act.entries.filter { it.takesUp }.toSet())
        assertTrue(Act.Forward.takesUp)
        assertFalse(Act.Toggle.takesUp)
    }

    private companion object {
        const val WIDTH = 900
        const val LEFT = 150f
        const val CENTRE = 450f
        const val RIGHT = 750f
        const val TOUCH_SLOP = 16f
        const val DOUBLE_TAP_SLOP = 200f
        val PLAYING = EdgeTaps.Scene()
    }
}
