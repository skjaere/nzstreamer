package app.nzstreamer.plugin.sportarr.ui

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

/**
 * The TV list's first focus as its states come (L590, L660): each state's own first focus, moved on from where the list
 * put it and never from where the user took it, the list's placement counting as its own from the moment it asks, so one
 * cut off by the next state (the list coming while the blank cards' Recent took the focus) is moved on from too.
 * A frame comes when the test sends one; the focus goes where it is asked at once, as an attached element's does.
 */
class TvListFocusTest {
    private val frames = Channel<Unit>(Channel.UNLIMITED)
    private val asked: MutableList<String> = CopyOnWriteArrayList()
    private lateinit var focus: TvListFocus

    private fun focus(): TvListFocus = TvListFocus(
        request = { key ->
            asked += key
            focus.focused = key
        },
        frame = { frames.receive() },
    ).also { focus = it }

    private suspend fun settle(first: String) = focus.settle(first, restore = { false }, awaitTurn = {}, bringIntoView = {})

    @Test
    fun `the list coming while the blank cards' Recent takes the focus moves it on to All`() = runBlocking<Unit> {
        focus()
        val loading = launch { settle(RECENT) }
        frames.send(Unit)
        // The focus on Recent, the frame that confirms it not come yet: the list comes, and its state's effect starts over.
        waitUntil { focus.focused == RECENT }
        loading.cancelAndJoin()

        val list = launch { settle(ALL) }
        repeat(3) { frames.send(Unit) }
        waitUntil { focus.focused == ALL }
        list.cancelAndJoin()
        assertEquals(ALL, focus.focused, "the list's first focus, past the blank cards' Recent")
    }

    @Test
    fun `a focus the shell left before the list put it anywhere moves on to the list's first focus`() = runBlocking<Unit> {
        focus()
        // The tab shown again, the shell's settling has put the focus on one of the list's rows first.
        focus.focused = ROW
        val list = launch { settle(ALL) }
        repeat(3) { frames.send(Unit) }
        list.join()
        assertEquals(ALL, focus.focused)
    }

    @Test
    fun `a focus the user moved stays where it is as the state changes`() = runBlocking<Unit> {
        focus()
        val list = launch { settle(ALL) }
        repeat(2) { frames.send(Unit) }
        list.join()
        assertEquals(ALL, focus.focused)

        // Down into a row, then a failed Refresh brings the notice: the focus stays on the row.
        focus.focused = ROW
        asked.clear()
        val notice = launch { settle(NOTICE) }
        repeat(2) { frames.send(Unit) }
        notice.join()
        assertEquals(ROW, focus.focused)
        assertEquals(emptyList(), asked, "nothing asked for")
    }

    private suspend fun waitUntil(holds: () -> Boolean) {
        repeat(1_000) {
            if (holds()) return
            yield()
        }
        throw AssertionError("never held; the focus is on ${focus.focused}")
    }

    private companion object {
        const val RECENT = "side:Recent"
        const val ALL = "sport:*"
        const val NOTICE = "notice"
        const val ROW = "row:ev-000012"
    }
}
