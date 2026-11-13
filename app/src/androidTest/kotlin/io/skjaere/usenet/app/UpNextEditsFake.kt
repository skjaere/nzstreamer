package io.skjaere.usenet.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.skjaere.usenet.app.collection.UndoTimer
import io.skjaere.usenet.app.collection.UndoUi
import io.skjaere.usenet.app.home.HomeState
import io.skjaere.usenet.app.home.UpNextEdit
import io.skjaere.usenet.app.home.UpNextEdits
import io.skjaere.usenet.app.home.UpNextRow
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Up next's menu and Undo bar over a test's own Home ([state]), as HomeViewModel does them over the library, by
 * UpNextEdit's rules: Remove takes the row out; Mark watched takes a film's out and leaves a show's (its next episode);
 * each offers Undo and says where a remote's focus goes after. Undo puts the row back where it was. The offer goes
 * [timeoutMs] after it shows, whatever time the screen asks for ([timeouts] keeps that), so a test needn't wait 10 s.
 */
class UpNextEditsFake(private val state: MutableState<HomeState?>, private val timeoutMs: Long = 2_000) : AutoCloseable {
    private val scope = MainScope()
    private val timer = UndoTimer<() -> Unit>(scope)
    private var focusAfter by mutableStateOf<String?>(null)
    val removed = CopyOnWriteArrayList<UpNextRow>()
    val marked = CopyOnWriteArrayList<UpNextRow>()
    val timeouts = CopyOnWriteArrayList<Long>()

    @Volatile
    var undone = 0
        private set

    @Composable
    fun edits(): UpNextEdits {
        val offer by timer.offer.collectAsState()
        return UpNextEdits(
            offer?.let { UndoUi(it.id, it.message) }, focusAfter, ::remove, ::markWatched, ::undo,
            shown = { timeouts += it; timer.shown(timeoutMs) }, dismiss = timer::dismiss, focusTaken = { focusAfter = null },
        )
    }

    private fun remove(row: UpNextRow) {
        removed += row
        val edit = UpNextEdit.remove(row)
        takeOut(row, edit, edit.message)
    }

    private fun markWatched(row: UpNextRow) {
        val edit = UpNextEdit.markWatched(row) ?: return
        marked += row
        if (edit is UpNextEdit.MarkEpisode) {
            focusAfter = edit.focusAfter(keys())
            timer.offer(edit.message) {}
        } else {
            takeOut(row, edit, edit.message)
        }
    }

    private fun keys() = state.value?.upNext?.map { it.key }.orEmpty()

    private fun takeOut(row: UpNextRow, edit: UpNextEdit, message: String) {
        val before = state.value ?: return
        val at = before.upNext.indexOf(row)
        focusAfter = edit.focusAfter(keys())
        state.value = before.copy(upNext = before.upNext - row)
        timer.offer(message) {
            state.value?.let { now -> state.value = now.copy(upNext = now.upNext.toMutableList().apply { add(at.coerceIn(0, size), row) }) }
        }
    }

    private fun undo() {
        undone++
        timer.take()?.invoke()
    }

    override fun close() = scope.cancel()
}
