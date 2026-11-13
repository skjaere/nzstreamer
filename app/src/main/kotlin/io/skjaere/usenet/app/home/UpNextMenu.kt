package io.skjaere.usenet.app.home

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import io.skjaere.usenet.app.collection.UNDO_MS
import io.skjaere.usenet.app.collection.UndoBar
import io.skjaere.usenet.app.collection.UndoUi
import io.skjaere.usenet.app.tv.TvMenuItem
import io.skjaere.usenet.app.ui.tryFocus
import kotlinx.coroutines.delay

/**
 * What Up next's menu and its Undo bar do, for Home's content: HomeViewModel's ([HomeViewModel.remove] and the rest), or a
 * test's own. [offer] is the bar's (null without one); [focusAfter] the row a remote's focus goes to as the bar goes
 * ([HomeViewModel.focusAfter]); [shown] says the bar is on screen, with its time; [focusTaken] that [focusAfter] was used.
 */
@Immutable
data class UpNextEdits(
    val offer: UndoUi? = null,
    val focusAfter: String? = null,
    val remove: (UpNextRow) -> Unit = {},
    val markWatched: (UpNextRow) -> Unit = {},
    val undo: () -> Unit = {},
    val shown: (timeoutMs: Long) -> Unit = {},
    val dismiss: () -> Unit = {},
    val focusTaken: () -> Unit = {},
)

/** What Up next's menu says, which the tests match. */
const val REMOVE_FROM_UP_NEXT = "Remove from Up next"
const val MARK_WATCHED = "Mark watched"

/**
 * What [row]'s menu offers, in order, and does (L21, W3): Remove from Up next on every row, Mark watched on an episode or a
 * film in progress. No Remove from collection (the row comes from what was played) and no Go to show.
 */
internal fun UpNextEdits.items(row: UpNextRow): List<Pair<String, () -> Unit>> = listOfNotNull(
    REMOVE_FROM_UP_NEXT to { remove(row) },
    (MARK_WATCHED to { markWatched(row) }).takeIf { UpNextEdit.markWatched(row) != null },
)

/** The menu's [items], each closing it ([close]) first; [modifier] goes on the first, which a TV's focus goes to as the menu opens. */
@Composable
internal fun UpNextMenuItems(items: List<Pair<String, () -> Unit>>, close: () -> Unit, modifier: Modifier = Modifier) {
    items.forEachIndexed { i, (label, act) ->
        TvMenuItem(label, if (i == 0) modifier else Modifier) {
            close()
            act()
        }
    }
}

/** The menu's [items] as TalkBack's actions on the row or card, which TalkBack can't long-press. */
internal fun upNextActions(items: List<Pair<String, () -> Unit>>): List<CustomAccessibilityAction> =
    items.map { (label, act) -> CustomAccessibilityAction(label) { act(); true } }

/**
 * A row's menu item picked: the row's [key], the rows' keys then ([before]), the offer the bar had then ([offerBefore]),
 * and whether this one's has come ([offered]).
 */
internal data class UpNextPick(val key: String, val before: List<String>, val offerBefore: Long?, val offered: Boolean = false)

/**
 * Up next's Undo bar and the focus around it, a phone's Home's and a TV's: the row a menu item was picked on ([pick]),
 * until the focus is handed on; whether Undo has the focus ([undoFocused]) or had it as the bar went ([undoHeld]); the row
 * Undo brings back ([restoring]), focused once it shows.
 */
@Stable
internal class UpNextUndoFocus {
    val undo = FocusRequester()
    var pick by mutableStateOf<UpNextPick?>(null)
    var undoFocused by mutableStateOf(false)
    var undoHeld by mutableStateOf(false)
    var restoring by mutableStateOf<String?>(null)

    /** The focus is this one's to move after an edit: a screen's own hand-on, for a row gone under the focus, stands down. */
    val busy: Boolean get() = pick != null || restoring != null

    /** [edits] with each menu item's row kept ([pick]) before it acts, of [keys] (the rows shown, in order). */
    fun tracking(edits: UpNextEdits, keys: List<String>): UpNextEdits = edits.copy(
        remove = { row -> picked(row, keys, edits.offer); edits.remove(row) },
        markWatched = { row -> picked(row, keys, edits.offer); edits.markWatched(row) },
    )

    private fun picked(row: UpNextRow, keys: List<String>, offer: UndoUi?) {
        pick = UpNextPick(row.key, keys, offer?.id)
    }
}

/**
 * The Undo bar after Remove from Up next or Mark watched (L23), the collection's, placed by [modifier] at Home's foot, and
 * the focus around it (the collection's, L24). With a remote ([dpad]) Undo takes the focus as the bar shows after an edit
 * picked here. Up or Back from it hands the focus on and sends the bar away; so does its time running out with the focus
 * on it. The focus goes to [UpNextEdit.handBack]'s row of [keys] ([handTo], null for Home's first focus without Up next).
 * Undo hands it on too, then to the row it brings back once that shows. On touch the focus stays where it is. The bar
 * goes as the screen does (a page or a tab in front of Home), not as a phone turns round.
 */
@Composable
internal fun UpNextUndo(
    edits: UpNextEdits,
    focus: UpNextUndoFocus,
    keys: List<String>,
    dpad: Boolean,
    handTo: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val a11y = LocalAccessibilityManager.current
    val offer = edits.offer
    // Read as they are when the callbacks run: the bar's focus is lost as the bar goes, after it was composed without it.
    val latestOffer by rememberUpdatedState(offer)
    val latestEdits by rememberUpdatedState(edits)
    val latestKeys by rememberUpdatedState(keys)
    val dpadNow by rememberUpdatedState(dpad)
    val latestHandTo by rememberUpdatedState(handTo)

    fun handBack() {
        val pick = focus.pick
        focus.pick = null
        focus.undoHeld = false
        val target = UpNextEdit.handBack(latestEdits.focusAfter, pick?.key, pick?.before.orEmpty(), latestKeys)
        latestEdits.focusTaken()
        if (dpadNow) latestHandTo(target)
    }

    // An offer: its time runs from now (longer when the accessibility settings ask), and Undo takes a remote's focus if
    // the edit was picked here. One from before this screen was composed (the phone turned round) leaves the focus as it is.
    LaunchedEffect(offer?.id) {
        val shown = offer ?: return@LaunchedEffect
        val mine = focus.pick?.takeIf { it.offerBefore != shown.id }?.also { pick -> focus.pick = pick.copy(offered = true) } != null
        latestEdits.shown(a11y?.calculateRecommendedTimeoutMillis(UNDO_MS, containsIcons = false, containsText = true, containsControls = true) ?: UNDO_MS)
        if (dpad && mine) {
            withFrameNanos {}
            focus.undo.tryFocus()
        }
    }
    // Leaving the screen loses Undo, what was done staying (L23); turning a phone round doesn't.
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        onDispose { if (activity?.isChangingConfigurations != true) latestEdits.dismiss() }
    }
    // The bar gone (its time up): the focus handed on if Undo had it, else the edit is over where the focus is.
    LaunchedEffect(offer == null) {
        val pick = focus.pick ?: return@LaunchedEffect
        if (offer != null || !pick.offered) return@LaunchedEffect
        if (focus.undoHeld) {
            handBack()
        } else {
            focus.pick = null
            latestEdits.focusTaken()
        }
    }
    // No bar came (the write failed, and was logged): the edit is over, and the screen's own hand-on is back.
    LaunchedEffect(focus.pick) {
        val pick = focus.pick ?: return@LaunchedEffect
        if (pick.offered) return@LaunchedEffect
        delay(OFFER_WAIT_MS)
        if (focus.pick == pick) focus.pick = null
    }
    // Undo's row back: the focus on it, once it shows; given up after a while (an Undo refused, the row changed since).
    LaunchedEffect(focus.restoring, keys) {
        val key = focus.restoring ?: return@LaunchedEffect
        if (key !in keys) return@LaunchedEffect
        withFrameNanos {}
        latestHandTo(key)
        focus.restoring = null
    }
    LaunchedEffect(focus.restoring) {
        focus.restoring ?: return@LaunchedEffect
        delay(RESTORE_WAIT_MS)
        focus.restoring = null
    }
    BackHandler(enabled = focus.undoFocused) {
        handBack()
        latestEdits.dismiss()
    }
    val shown = offer ?: return
    UndoBar(
        shown.message, focus.undo,
        onUndo = {
            val pick = focus.pick
            if (dpadNow && pick != null) {
                // On to where the bar going would take it, then to the row once it is back.
                handBack()
                focus.restoring = pick.key
            } else {
                focus.pick = null
                latestEdits.focusTaken()
            }
            latestEdits.undo()
        },
        modifier = modifier
            .onFocusChanged {
                focus.undoFocused = it.hasFocus
                // Lost with the bar (no offer left) isn't the remote moving off it.
                if (it.hasFocus) focus.undoHeld = true else if (latestOffer != null) focus.undoHeld = false
            }
            .onPreviewKeyEvent { event ->
                // Up from Undo goes back to Up next, and the bar goes.
                if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && focus.undoFocused) {
                    handBack()
                    latestEdits.dismiss()
                    true
                } else false
            },
    )
}

/** How long an edit waits for its bar: the write is quick, and failing it offers nothing. */
private const val OFFER_WAIT_MS = 5_000L

/** How long Undo's row has to come back to take the focus. */
private const val RESTORE_WAIT_MS = 5_000L
