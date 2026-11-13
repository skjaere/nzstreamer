package io.skjaere.usenet.app.play

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.CoroutineContext

/**
 * The play sheet's state as the coordinator writes it (L33, L34): each write sets [state] and [sheet] together, with the
 * session of the play it is for, so a phone is never told one play's sheet as another's.
 *
 * Each play takes the sheet in a turn of its own ([begin]), and its job's coroutine carries that turn ([writer]). A job's
 * cancel doesn't stop a write it is making on another thread at that moment, nor what a channel's buffer still hands it:
 * once another play has taken the sheet, or Cancel has cleared it ([end]), what the job before writes is dropped, and a
 * launch it would send isn't sent ([ifCurrent]). Writes from outside the jobs (the main thread's: Cancel's, a failure
 * shown, a play's first line) are the sheet's play's now.
 */
internal class SheetWrites {
    private val shown = MutableStateFlow<PlayState>(PlayState.Idle)
    val state: StateFlow<PlayState> = shown.asStateFlow()

    private val _sheet = MutableStateFlow(SheetNow(PlayState.Idle, null))

    /** What the sheet shows, with the paired phone's session of the play it shows it for. */
    val sheet: StateFlow<SheetNow> = _sheet.asStateFlow()

    /** Where the sheet's play was asked for, set as each takes the sheet. */
    @Volatile
    var origin: PlayOrigin = PlayOrigin.Here
        private set

    /** The sheet's turn now; a write made in another is a job's that lost the sheet. Guarded by this. */
    private var turn = 0L

    /** The turn of the job whose coroutine runs on this thread now; null outside the jobs. */
    private val writing = ThreadLocal<Long?>()

    /** A play from [origin] takes the sheet: what the jobs before it still write is dropped from now. */
    fun begin(origin: PlayOrigin) = synchronized(this) {
        turn++
        this.origin = origin
    }

    /** No play has the sheet now (Cancel): what the job before still writes is dropped. */
    fun end() = synchronized(this) {
        turn++
    }

    /** What a job's coroutine carries, so its writes are known as the play's that has the sheet now. */
    fun writer(): CoroutineContext = synchronized(this) { writing.asContextElement(turn) }

    var value: PlayState
        get() = shown.value
        set(state) = synchronized(this) { if (current()) show(state) }

    fun update(change: (PlayState) -> PlayState) = synchronized(this) { if (current()) show(change(shown.value)) }

    /** Runs [block] while the writer's play has the sheet, in one step with a write; whether it ran. */
    fun ifCurrent(block: () -> Unit): Boolean = synchronized(this) {
        current().also { if (it) block() }
    }

    private fun current(): Boolean = writing.get().let { it == null || it == turn }

    private fun show(state: PlayState) {
        shown.value = state
        _sheet.value = SheetNow(state, origin.remote)
    }
}
