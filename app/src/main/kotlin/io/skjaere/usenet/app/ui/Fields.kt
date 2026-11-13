package io.skjaere.usenet.app.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable

/** What a form's field holds, for its keyboard ([fieldKeyboard]); a [secret] one is hidden as it is typed, with a Show. */
enum class FieldKind(val secret: Boolean = false) {
    /** Words, such as a name: the keyboard's corrections help. */
    Text,
    Host,
    Url,
    Port,
    Number,
    Username,
    Password(secret = true),

    /** An API key or token. */
    Key(secret = true),
}

/**
 * The keyboard for a field of [kind], on every device: the address layout for hosts and URLs, the password one for
 * passwords and keys, the number pad for numbers; no corrections or capitals where the keyboard changing a word would
 * break it (hosts, URLs, usernames, keys). [imeAction]: Next down a form, Done on its last field.
 */
fun fieldKeyboard(kind: FieldKind, imeAction: ImeAction): KeyboardOptions = when (kind) {
    FieldKind.Text -> KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = imeAction)
    FieldKind.Host, FieldKind.Url -> KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = imeAction)
    FieldKind.Port, FieldKind.Number -> KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction)
    FieldKind.Username -> KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Text, imeAction = imeAction)
    FieldKind.Password, FieldKind.Key -> KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = imeAction)
}

/** What TalkBack reads of a secret field that says so (L214): its label, and while its words are hidden, that they are. */
fun secretFieldSpoken(label: String, revealed: Boolean): String = if (revealed) label else "$label, hidden"

/** What TalkBack reads of such a field's Show (L214): what it shows, or hides. */
fun showSpoken(label: String, revealed: Boolean): String = "${if (revealed) "Hide" else "Show"} $label"

/**
 * A labelled field of a form, its keyboard for its [kind]. On a TV it is a button until OK ([TvFieldGate]). With a
 * [chain], the form's fields in order, the keyboard's Next goes on to the next one and the last has Done. [spoken]: a
 * secret field TalkBack reads by its label and whether it is hidden, its Show by what it does ([secretFieldSpoken],
 * [showSpoken]; a debrid service's key, L214).
 */
@Composable
fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    kind: FieldKind = FieldKind.Text,
    mono: Boolean = false,
    placeholder: String? = null,
    help: String? = null,
    helpWarn: Boolean = false,
    chain: FieldChain? = null,
    spoken: Boolean = false,
) {
    var reveal by remember { mutableStateOf(false) }
    val secret = kind.secret
    val gate = rememberFieldGate()
    JoinChain(chain, gate)
    // On a TV the remote's Right goes from a secret field (its gate, or the field while typing) to its Show (inside the
    // field, so the focus wouldn't find it), and Left back to the gate.
    val tv = gate.tv
    val show = remember { FocusRequester() }
    val toShow = if (tv && secret) Modifier.focusProperties { right = show } else Modifier
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = AppType.secondary, color = colors.textSecondary)
        TvFieldGate(gate, label, value, Modifier.fillMaxWidth().then(toShow), secret = secret) { field ->
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = if (mono) AppType.release.copy(fontSize = AppType.secondary.fontSize, color = colors.text) else AppType.body.copy(color = colors.text),
                placeholder = placeholder?.let { { Text(it, style = AppType.body, color = colors.muted) } },
                visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = fieldKeyboard(kind, chain?.imeAction(gate) ?: ImeAction.Done),
                // Next to the next field, the keyboard staying up; Done on a TV back to the gate, as Back does.
                keyboardActions = KeyboardActions(
                    onNext = { if (chain?.next(gate) != true) defaultKeyboardAction(ImeAction.Next) },
                    onDone = { if (!gate.close()) defaultKeyboardAction(ImeAction.Done) },
                ),
                trailingIcon = if (secret) ({
                    // An icon button's 40 dp, growing with a large font size so the word isn't cut off. Its
                    // press and focus layer in the field's icon colour, as the icon button's was, not the accent.
                    TextButton(
                        onClick = { reveal = !reveal },
                        modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp).focusRequester(show)
                            .then(if (tv) Modifier.focusProperties { left = gate.requester } else Modifier)
                            .then(if (spoken) Modifier.semantics { contentDescription = showSpoken(label, reveal) } else Modifier),
                        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Text(if (reveal) "Hide" else "Show", style = AppType.small, color = colors.muted)
                    }
                }) else null,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.surface, unfocusedContainerColor = colors.surface,
                    focusedBorderColor = colors.accent, unfocusedBorderColor = colors.chip, cursorColor = colors.accent,
                ),
                modifier = field.then(toShow).then(if (spoken && secret) Modifier.semantics { contentDescription = secretFieldSpoken(label, reveal) } else Modifier),
            )
        }
        help?.let { Text(it, style = AppType.small, color = if (helpWarn) colors.warnStrong else colors.muted) }
    }
}

/** [modifier] goes before the row's toggle: a focus outline or requester. On a TV it turns over while focused ([turnsOver]). */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, detail: String? = null, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    OwnedRipple {
        Row(
            modifier.fillMaxWidth().heightIn(min = 56.dp).focusScale(interaction, { it.rowScale })
                .then(if (turned) Modifier.background(colors.text) else Modifier)
                .toggleable(checked, interaction, LocalIndication.current, role = Role.Switch, onValueChange = onChange).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = AppType.body, color = if (turned) colors.background else colors.text)
                detail?.let { Text(it, style = AppType.small, color = if (turned) colors.turnedSecondary else colors.muted) }
            }
            Switch(
                checked = checked, onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent, uncheckedTrackColor = colors.chip, uncheckedBorderColor = colors.outline),
            )
        }
    }
}

/**
 * A number set a step at a time, as a TV's remote sets it: "[label]  [ − ] [value] [ + ]", the buttons named [fewer] and
 * [more] for TalkBack. Buttons, as Left and Right on the row would fight the remote's moves; enabled at the limits
 * too, where they do nothing, as a button disabled under the focus would drop it.
 */
@Composable
fun Stepper(label: String, value: String, onStep: (Int) -> Unit, fewer: String, more: String, modifier: Modifier = Modifier, help: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = AppType.body, color = colors.text)
            help?.let { Text(it, style = AppType.small, color = colors.muted) }
        }
        SecondaryButton("−", { onStep(-1) }, Modifier.semantics { contentDescription = fewer })
        // Read out as it changes.
        Text(
            value, style = AppType.cardTitle, color = colors.text, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 40.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        SecondaryButton("+", { onStep(1) }, Modifier.semantics { contentDescription = more })
    }
}

/** One line of a test result: ✓/⚠ and text. */
@Composable
fun ResultLine(ok: Boolean, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Text(if (ok) "✓" else "⚠", style = AppType.bodyStrong, color = if (ok) colors.good else colors.warnStrong)
        Text(text, style = AppType.secondary, color = colors.textSecondary)
    }
}
