package io.skjaere.usenet.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.skjaere.usenet.app.setup.PHONE_SETUP_TITLE
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.tryFocus

/**
 * "Set up your sources": shown by Home until a provider and an indexer are saved (or "Not now"), or, while debrid shows,
 * a service and a torrent indexer, the two ways in groups of their own, Usenet's first (DbPhoneFirstRun, W81). Each row
 * opens its settings screen; nothing is filled in for the user. The rows follow what is saved, so coming back from a
 * settings screen shows the step done. With nothing saved and debrid shown, "Add a debrid service" sits under the step to
 * do next. [onPhone]: a TV's "Set up with your phone", first, the step to do next one press below it (null shows none).
 */
@Composable
fun FirstRun(state: SetupState, onStep: (SetupRow) -> Unit, onSkip: () -> Unit, onImport: () -> Unit, onPhone: (() -> Unit)? = null) {
    // What opened a page, by its key: Back from that page puts the focus on it again. Saved, as Home leaves the
    // composition while the page is open.
    var opener by rememberSaveable { mutableStateOf<String?>(null) }
    val focusables = remember { mutableMapOf<String, FocusRequester>() }
    fun focus(key: String) = focusables.getOrPut(key) { FocusRequester() }
    fun opens(key: String, page: () -> Unit): () -> Unit = { opener = key; page() }
    // A TV remote starts on what opened the page it comes back from; else on the phone, typing being slow there, else
    // on the step to do next. A touch screen has nothing focused.
    val initial = rememberInitialFocus()
    LaunchedEffect(state.next) {
        if (state.next == null) return@LaunchedEffect
        val from = opener
        opener = null
        initial.awaitTurn()
        if (from == null || !focus(from).tryFocus()) focus(if (onPhone != null) PHONE else ACTION).tryFocus()
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 40.dp, bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Set up your sources", style = AppType.screenTitle.copy(fontSize = 30.sp, lineHeight = 33.sp, letterSpacing = (-0.02).em), color = colors.text)
                Text(
                    "This app streams from accounts you already have. Nothing is included and nothing leaves this device except requests to the services you add.",
                    style = AppType.body.copy(lineHeight = 22.sp), color = colors.textSecondary,
                )
            }
            state.groups.forEachIndexed { g, group ->
                group.heading?.let { SectionHeader(it, top = if (g == 0) 0.dp else 18.dp) }
                // A group after another without a heading (the TMDB key's) stands apart by its margin alone.
                GroupCard(if (group.heading == null && g > 0) Modifier.padding(top = 16.dp) else Modifier) {
                    group.rows.forEachIndexed { i, row ->
                        if (i > 0) InsetDivider()
                        StepRow(row, next = row.step == state.next, Modifier.focusRequester(focus(row.step.name)), opens(row.step.name) { onStep(row) })
                    }
                }
            }
            Row(
                Modifier.padding(horizontal = 16.dp).padding(top = 16.dp).fillMaxWidth()
                    .border(1.dp, colors.chip, RoundedCornerShape(14.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
                Text(
                    "Without a TMDB key you can still search your indexers and play releases. The collection needs one.",
                    style = AppType.secondary, color = colors.textSecondary,
                )
            }
        }
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            onPhone?.let {
                PrimaryButton(
                    PHONE_SETUP_TITLE, opens(PHONE, it),
                    Modifier.fillMaxWidth().focusRequester(focus(PHONE)).focusOutline(RoundedCornerShape(24.dp), color = colors.text, fill = false),
                )
            }
            state.action?.let { action ->
                val step = state.rows.first { it.step == state.next }
                val modifier = Modifier.fillMaxWidth().focusRequester(focus(ACTION))
                // Beside the phone's, the step to do next is the second way in.
                if (onPhone != null) {
                    SecondaryButton(action, opens(ACTION) { onStep(step) }, modifier)
                } else {
                    PrimaryButton(action, opens(ACTION) { onStep(step) }, modifier.focusOutline(RoundedCornerShape(24.dp), color = colors.text, fill = false))
                }
            }
            state.alternativeAction?.let { words ->
                val step = state.rows.first { it.step == state.alternative }
                SecondaryButton(words, opens(ALTERNATIVE) { onStep(step) }, Modifier.fillMaxWidth().focusRequester(focus(ALTERNATIVE)))
            }
            if (state.canSkip) TextLink("Not now", colors.accent, onSkip)
            TextLink("Open a .nzb file instead", colors.textSecondary, opens(IMPORT, onImport), Modifier.focusRequester(focus(IMPORT)))
        }
    }
}

/** The first run's elements that open a page, by the keys [FirstRun] remembers its opener by (a step's row: its step's name). */
private const val PHONE = "phone"
private const val ACTION = "action"
private const val ALTERNATIVE = "alternative"
private const val IMPORT = "import"

@Composable
private fun TextLink(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth().focusOutline(RoundedCornerShape(24.dp), fill = false)) {
        Text(text, style = AppType.body, color = color)
    }
}

/** A step: its number while to do (ringed in the accent when it's [next]), a check mark once done. */
@Composable
private fun StepRow(row: SetupRow, next: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().focusOutline().clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = if (row.done) "Done" else "Step ${row.number}, to do" }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // At least 32 dp: it grows with a large font size, so the number isn't cut off.
        val mark = Modifier.sizeIn(minWidth = 32.dp, minHeight = 32.dp).clearAndSetSemantics {}
        if (row.done) {
            Box(mark.background(colors.goodBg, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.good, modifier = Modifier.size(18.dp))
            }
        } else {
            Box(mark.border(2.dp, if (next) colors.accent else colors.outline, CircleShape), contentAlignment = Alignment.Center) {
                Text("${row.number}", style = AppType.bodyStrong.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = if (next) colors.accent else colors.textSecondary)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.step.title, style = AppType.bodyStrong, color = colors.text)
            Text(row.subtitle, style = AppType.secondary, color = colors.muted)
        }
        if (row.need.isNotEmpty()) Text(row.need, style = AppType.small, color = colors.muted)
    }
}
