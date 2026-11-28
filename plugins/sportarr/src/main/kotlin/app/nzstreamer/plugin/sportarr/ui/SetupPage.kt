package app.nzstreamer.plugin.sportarr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.nzstreamer.plugin.sportarr.Session
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FieldChain
import io.skjaere.usenet.app.ui.FieldGate
import io.skjaere.usenet.app.ui.FieldKind
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.FormField
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.JoinChain
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.OwnedRipple
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.ResultLine
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.SwitchRow
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.TvFieldGate
import io.skjaere.usenet.app.ui.TvTypingTip
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.fieldKeyboard
import io.skjaere.usenet.app.ui.focusScale
import io.skjaere.usenet.app.ui.rememberFieldChain
import io.skjaere.usenet.app.ui.rememberFieldGate
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.secretFieldSpoken
import io.skjaere.usenet.app.ui.showSpoken
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.app.ui.turnsOver
import io.skjaere.usenet.app.ui.tvFocusMemory
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The setup form (L640, L652), the same on phones and TVs: the tab's first screen until a Save, under [title] "Sports"
 * with its [intro]; and the settings page, "Sportarr settings" with [onBack]'s arrow and no intro. The address and its
 * help, the key (or "Saved" with Change), Test and Save side by side, Test's answer under them, and what Sports does with
 * the key. [onSaved] follows a Save: the settings page goes back where it was opened from, while the tab shows the list by
 * itself once the address and the key are stored. Nothing reaches Sportarr before Test or Save ([SetupModel]). The settings
 * page has [eventPages], its part under the footnote with the Fight cards from Wikipedia switch (L-B8); the tab's first
 * screen never has it.
 *
 * On a TV the typing tip comes first, each field is a gate the remote passes over (OK types, Back or Done closes the
 * keyboard), and the shell holds the phone screens' 720 dp column. The address takes the first focus once the rail lets
 * it, and Left goes to the rail while the form is the tab; the page keeps the place the focus had when it comes back from
 * the rail. After a passed Test the focus moves to Save; after any other answer it stays on Test, where Up goes back to
 * the key. Change sits in the key's field, one Down from the address, and Right from the key's field reaches Show. Down
 * from Test or Save reaches the switch, and Up goes back to them (L-B10).
 */
@Composable
internal fun SetupPage(
    session: Session,
    title: String,
    intro: String?,
    onBack: (() -> Unit)?,
    onSaved: () -> Unit,
    eventPages: Boolean = false,
) {
    val tv = LocalFormFactor.current == FormFactor.Tv
    // Only strings and booleans in saved state, which the host restores with its own class loader (NzPlugin's rule): the
    // address as typed and Change. Never the key, kept nowhere but the host's secrets.
    var keptAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var keptChanging by rememberSaveable { mutableStateOf(false) }
    val model = remember(session, tv) { session.setup(tv, keptAddress?.let { SetupModel.Restored(it, keptChanging) }, eventPages) }
    val form by model.form.collectAsState()
    LaunchedEffect(model) { model.open() }
    // The stored address and key read: a moment, before which there is nothing to show.
    if (!form.loaded) return
    LaunchedEffect(form.address, form.changing) {
        val kept = model.kept()
        keptAddress = kept.address
        keptChanging = kept.changing
    }

    val scope = rememberCoroutineScope()
    val chain = rememberFieldChain()
    val keyGate = rememberFieldGate()
    val address = remember { FocusRequester() }
    val test = remember { FocusRequester() }
    val save = remember { FocusRequester() }
    val initial = rememberInitialFocus()
    Column(Modifier.fillMaxSize().imePadding().tvFocusMemory().verticalScroll(rememberScrollState())) {
        if (onBack != null) {
            TopBar(title, onBack)
        } else {
            Text(
                title, style = AppType.screenTitle, color = colors.text,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (tv) 8.dp else 18.dp, bottom = if (tv) 8.dp else 6.dp)
                    .semantics { heading() },
            )
        }
        TvTypingTip()
        intro?.let {
            Text(it, style = AppType.body, color = colors.textSecondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
        }
        Column(
            Modifier.padding(horizontal = 16.dp).padding(top = if (onBack != null) 4.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FormField(
                Words.Setup.ADDRESS, form.address, model::address, Modifier.focusRequester(address), kind = FieldKind.Url, mono = true,
                placeholder = Words.Setup.ADDRESS_PLACEHOLDER, help = form.help, helpWarn = form.helpWarns, chain = chain,
            )
            KeyField(form, keyGate, chain, model::key, model::change)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 10.dp)) {
                SecondaryButton(form.testLabel, { scope.launch { model.test() } }, Modifier.weight(1f).focusRequester(test), enabled = form.canTest)
                PrimaryButton(
                    Words.Setup.SAVE, { scope.launch { if (model.save()) onSaved() } }, Modifier.weight(1f).focusRequester(save),
                    enabled = form.canSave,
                )
            }
        }
        Answer(form)
        Footnote(Words.Setup.READ_ONLY)
        model.cardsSwitch?.let { EventPages(it, model::switchCards) }
    }
    if (tv) LaunchedEffect(Unit) { initial.request(address) }
    LaunchedEffect(form.move) {
        when (form.move?.to) {
            // Change's field comes in with the move: opened once it is laid out, its keyboard up.
            SetupFocus.Key -> if (!keyGate.open()) {
                withFrameNanos { }
                keyGate.open()
            }
            // A phone's buttons take no focus from a touch.
            SetupFocus.Save -> if (tv) save.tryFocus()
            SetupFocus.Test -> if (tv) test.tryFocus()
            SetupFocus.Address -> if (tv) address.tryFocus()
            null -> Unit
        }
    }
}

/**
 * The settings page's Event pages part (BtPhoneSettings, BtTvSettings): the Fight cards from Wikipedia switch with its
 * detail, kept the moment it is switched. Shown once options.json has been read, so it never shows on while the file
 * says off. On a TV the row turns over while focused, as the app's switches do.
 */
@Composable
private fun EventPages(switch: StateFlow<Boolean?>, onSwitch: (Boolean) -> Unit) {
    val on by switch.collectAsState()
    on?.let { checked ->
        SectionHeader(Words.Setup.EVENT_PAGES, top = 10.dp)
        GroupCard { SwitchRow(Words.Setup.FIGHT_CARDS, checked, onSwitch, detail = Words.Setup.FIGHT_CARDS_DETAIL) }
        Spacer(Modifier.height(16.dp))
    }
}

/** Test's spinner while it runs, then its line, ✓ or ⚠; TalkBack reads it as it comes. */
@Composable
private fun Answer(form: SetupForm) {
    Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        val line = form.line
        when {
            form.testing -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = colors.accent)
            line != null -> ResultLine(line.ok, line.text)
        }
    }
}

/**
 * The key's field: typed hidden, with Show, as the kit's secret fields are; once a key is saved, "Saved" with Change in
 * the field, never the key; after Change, empty with "Paste the new key". Built here rather than with the kit's field, as
 * Change opens it to type through its [gate].
 */
@Composable
private fun KeyField(form: SetupForm, gate: FieldGate, chain: FieldChain, onType: (String) -> Unit, onChange: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Words.Setup.KEY, style = AppType.secondary, color = colors.textSecondary)
        if (form.showsSavedKey) SavedKey(onChange) else KeyInput(form.key, form.keyPlaceholder, gate, chain, onType)
        Text(Words.Setup.KEY_HELP, style = AppType.small, color = colors.muted)
    }
}

/** The saved key's field: "Saved", and Change, the field's one stop for a remote, turned over while focused. */
@Composable
private fun SavedKey(onChange: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(colors.surface).border(1.dp, colors.chip, shape)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Words.Setup.SAVED, style = AppType.body, color = colors.muted, modifier = Modifier.weight(1f))
        val interaction = remember { MutableInteractionSource() }
        val turned = interaction.turnsOver()
        OwnedRipple {
            TextButton(
                onClick = onChange, interactionSource = interaction, shape = RoundedCornerShape(20.dp),
                modifier = Modifier.heightIn(min = 40.dp).focusScale(interaction, { it.buttonScale }),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (turned) colors.text else Color.Transparent,
                    contentColor = if (turned) colors.background else colors.accent,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) { Text(Words.Setup.CHANGE, style = AppType.bodyStrong) }
        }
    }
}

/**
 * The key typed, hidden until Show, the form's last field (the keyboard's Done; on a TV back to its gate). On a TV the
 * remote's Right goes from the field (its gate, or the field while typing) to Show, inside it where the focus wouldn't
 * find it, and Left back to the gate. TalkBack reads it by its label and whether it is hidden, and Show by what it does.
 */
@Composable
private fun KeyInput(value: String, placeholder: String?, gate: FieldGate, chain: FieldChain, onType: (String) -> Unit) {
    var reveal by remember { mutableStateOf(false) }
    JoinChain(chain, gate)
    val show = remember { FocusRequester() }
    val toShow = if (gate.tv) Modifier.focusProperties { right = show } else Modifier
    TvFieldGate(gate, Words.Setup.KEY, value, Modifier.fillMaxWidth().then(toShow), secret = true) { field ->
        OutlinedTextField(
            value = value,
            onValueChange = onType,
            singleLine = true,
            textStyle = AppType.release.copy(fontSize = AppType.secondary.fontSize, color = colors.text),
            placeholder = placeholder?.let { { Text(it, style = AppType.body, color = colors.muted) } },
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = fieldKeyboard(FieldKind.Key, chain.imeAction(gate)),
            keyboardActions = KeyboardActions(onDone = { if (!gate.close()) defaultKeyboardAction(ImeAction.Done) }),
            trailingIcon = {
                TextButton(
                    onClick = { reveal = !reveal },
                    modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp).focusRequester(show)
                        .then(if (gate.tv) Modifier.focusProperties { left = gate.requester } else Modifier)
                        .semantics { contentDescription = showSpoken(Words.Setup.KEY, reveal) },
                    colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text(if (reveal) Words.Setup.HIDE else Words.Setup.SHOW, style = AppType.small, color = colors.muted)
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.surface, unfocusedContainerColor = colors.surface,
                focusedBorderColor = colors.accent, unfocusedBorderColor = colors.chip, cursorColor = colors.accent,
            ),
            modifier = field.then(toShow).semantics { contentDescription = secretFieldSpoken(Words.Setup.KEY, reveal) },
        )
    }
}
