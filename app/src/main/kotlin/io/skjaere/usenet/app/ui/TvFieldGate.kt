package io.skjaere.usenet.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * A text field on a TV is a button until OK. A TV's keyboard comes up as soon as a field has the
 * focus and takes the remote's arrows (#236), so there the remote's focus lands on the field's gate instead and passes
 * over it; OK on the gate puts the focus in the field ([open]), which brings the keyboard; the keyboard's Done, or Back,
 * puts it back on the gate ([close]). On a phone there is no gate: the field takes the focus itself, as it always did.
 */
@Stable
class FieldGate internal constructor(val tv: Boolean) {
    /** What a screen focuses to put the focus on the field ([rememberInitialFocus], Clear): a TV's gate, a phone's field. */
    val requester = FocusRequester()

    internal val field: FocusRequester = if (tv) FocusRequester() else requester

    /** Whether the field has the focus, to type in. */
    var isOpen by mutableStateOf(false)
        internal set

    /** Whether a TV's gate has the focus, for what a screen says beside it. */
    var gateFocused by mutableStateOf(false)
        internal set

    /** The focus in the field, to type: its keyboard comes up. False when the field isn't there to take it. */
    fun open(): Boolean = field.tryFocus()

    /** A TV's focus back on the gate from the field, its keyboard put away; a phone's field keeps the focus. */
    fun close(): Boolean = tv && requester.tryFocus()
}

/** A field's gate, for [TvFieldGate]: a no-op one on a phone. */
@Composable
fun rememberFieldGate(): FieldGate {
    val tv = LocalFormFactor.current == FormFactor.Tv
    return remember(tv) { FieldGate(tv) }
}

/**
 * A form's fields in order, for the keyboard's Next: it goes on to the next field, the keyboard staying up, and the
 * last field has Done instead ([imeAction]). Each field joins as it is composed, so in the form's order.
 */
@Stable
class FieldChain {
    private val gates = mutableStateListOf<FieldGate>()

    internal fun join(gate: FieldGate) {
        if (gate !in gates) gates += gate
    }

    internal fun leave(gate: FieldGate) {
        gates -= gate
    }

    /** The keyboard's action for [gate]'s field: Next while another field follows it, else Done. */
    fun imeAction(gate: FieldGate): ImeAction = if (gate in gates && gates.last() !== gate) ImeAction.Next else ImeAction.Done

    /** The field after [gate]'s opened, to type on in it; false when [gate]'s is the last. */
    fun next(gate: FieldGate): Boolean {
        val at = gates.indexOf(gate)
        return at >= 0 && gates.getOrNull(at + 1)?.open() == true
    }
}

@Composable
fun rememberFieldChain(): FieldChain = remember { FieldChain() }

/** [gate] in [chain], while it is composed. */
@Composable
fun JoinChain(chain: FieldChain?, gate: FieldGate) {
    if (chain == null) return
    DisposableEffect(chain, gate) {
        chain.join(gate)
        onDispose { chain.leave(gate) }
    }
}

/** A field's gate: TalkBack and the tests know it by this, its value the field's [label]. */
val FieldGateLabel = SemanticsPropertyKey<String>("FieldGate")
private var SemanticsPropertyReceiver.fieldGate by FieldGateLabel

/**
 * The field [content] lays out behind [gate], with the modifier it gets for its text field. On a TV the gate wraps it:
 * the remote's focus lands there and shows as the app's rings do, OK opens the field, and Back or the keyboard put away
 * close it again. TalkBack on the gate hears the field's [label] and the words in it, [value] (of a [secret] field, only
 * that there are some). On a phone [content] gets [modifier] itself, nothing around it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvFieldGate(
    gate: FieldGate,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    secret: Boolean = false,
    content: @Composable (field: Modifier) -> Unit,
) {
    if (!gate.tv) {
        content(modifier.focusRequester(gate.requester).onFocusChanged { gate.isOpen = it.isFocused })
        return
    }
    // Back with the field open: the gate again. A TV's keyboard takes the first Back itself, to go: its going does the same.
    BackHandler(enabled = gate.isOpen) { gate.close() }
    val imeUp = WindowInsets.isImeVisible
    val view = LocalView.current
    // [0]: the keyboard seen up while the field is open; [1]: the field open since this ran last.
    val seen = remember { BooleanArray(2) }
    LaunchedEffect(gate.isOpen, imeUp) {
        when {
            !gate.isOpen -> {
                seen[0] = false
                seen[1] = false
            }
            imeUp -> {
                // Up as the field opens: the last field's, staying up for this one (the keyboard's Next), or one that just
                // went (the field opened again at once), which Compose still says is up while it animates away, and whose
                // end would close the field. Only the first is up in the window too. Coming up later, it is this field's.
                seen[0] = seen[1] || ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                seen[1] = true
            }
            seen[0] -> {
                seen[0] = false
                gate.close()
            }
            else -> seen[1] = true
        }
    }
    Box(
        modifier.focusRequester(gate.requester)
            .onFocusChanged { gate.gateFocused = it.isFocused }
            .focusOutline(shape, fill = false)
            .onKeyEvent { event ->
                if (!gate.gateFocused || !event.isOk()) return@onKeyEvent false
                // Down taken too, so nothing else sees half a press; the field opens on the way up, held OK's repeats aside.
                if (event.type == KeyEventType.KeyUp) gate.open()
                true
            }
            .semantics {
                fieldGate = label
                contentDescription = label
                // The words in the field, which the gate stands for: what the user typed is still there (Back keeps it).
                if (value.isNotEmpty()) stateDescription = if (secret) SECRET_WORDS else value
                role = Role.Button
                onClick("Type") { gate.open() }
            }
            .focusable(),
    ) {
        content(Modifier.fillMaxWidth().focusRequester(gate.field).onFocusChanged { gate.isOpen = it.isFocused })
    }
}

/** What TalkBack hears on a secret field's gate with words in it: that there are some, not what they are. */
private const val SECRET_WORDS = "Entered, hidden"

private fun androidx.compose.ui.input.key.KeyEvent.isOk() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
