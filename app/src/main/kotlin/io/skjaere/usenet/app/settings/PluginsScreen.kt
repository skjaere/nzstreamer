package io.skjaere.usenet.app.settings

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.requestFocusWhenReady
import io.skjaere.usenet.app.play.okGuard
import io.skjaere.usenet.app.pluginTabsFollow
import io.skjaere.usenet.app.plugins.InstallState
import io.skjaere.usenet.app.plugins.InstalledPlugin
import io.skjaere.usenet.app.plugins.PluginCompat
import io.skjaere.usenet.app.plugins.PluginListState
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.app.tv.searchKeyOpensSearch
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.ResultLine
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.tryFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * What Settings › Plugins reads and does: the app's registry, plugin list and installer ([of]), or a test's fakes. Remove
 * and Turn on again run in [scope], the app's, so leaving the page doesn't stop one half-way; [television]: the device,
 * whose words the lines use ("this TV"), whatever its layout.
 */
class PluginsDeps(
    val installed: Flow<List<InstalledPlugin>?>,
    val pluginsOff: Flow<Boolean>,
    val list: Flow<PluginListState>,
    val installs: Flow<Map<String, InstallState>>,
    /** Whether Install again would find a version to fetch for an installed plugin, as the list now stands. */
    val installAgainOffered: (String) -> Boolean,
    val pageOpened: () -> Unit,
    val checkAgain: () -> Unit,
    val install: (String) -> Unit,
    val cancel: (String) -> Unit,
    val tryAgain: (String) -> Unit,
    val installAgain: (String) -> Unit,
    val remove: suspend (String) -> Unit,
    val turnOnAgain: suspend (String) -> Unit,
    val turnPluginsOnAgain: suspend () -> Unit,
    /** The page was left: an install's ended lines go with it. */
    val pageLeft: () -> Unit,
    val scope: CoroutineScope,
    val television: Boolean,
    val now: () -> Long = System::currentTimeMillis,
    val zone: () -> ZoneId = ZoneId::systemDefault,
    val sdk: Int = Build.VERSION.SDK_INT,
    val compat: PluginCompat = PluginCompat(),
) {
    companion object {
        fun of(c: AppContainer): PluginsDeps {
            val installer = c.pluginInstaller
            return PluginsDeps(
                installed = c.plugins.plugins, pluginsOff = c.plugins.pluginsOff, list = c.pluginList.state, installs = installer.states,
                installAgainOffered = { id -> installer.installAgainVersion(id) != null },
                pageOpened = { c.pluginList.pageOpened() }, checkAgain = { c.pluginList.checkAgain() },
                install = { id -> installer.install(id) }, cancel = installer::cancel, tryAgain = { id -> installer.tryAgain(id) },
                installAgain = { id -> installer.installAgain(id) }, remove = installer::remove, turnOnAgain = installer::turnOnAgain,
                turnPluginsOnAgain = installer::turnPluginsOnAgain, pageLeft = installer::pageLeft, scope = c.appScope, television = c.television,
            )
        }
    }
}

/**
 * The page as it stands, from [deps]: the tab plugin tabs follow ([after]) and whether a card's Settings and Open have the
 * slot's pages to open ([slots]). The list is read as the page opens (unless read in the last 5 minutes, L620); leaving
 * the page drops an install's ended lines.
 */
class PluginsViewModel(private val deps: PluginsDeps, after: String, slots: Boolean) : ViewModel() {
    val page: StateFlow<PluginsPage?> = combine(deps.installed, deps.pluginsOff, deps.list, deps.installs) { installed, off, list, installs ->
        PluginsPage.of(installed, off, list, installs, deps.installAgainOffered, deps.now(), deps.zone(), deps.television, after, slots, deps.sdk, deps.compat)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        deps.pageOpened()
    }

    fun checkAgain() = deps.checkAgain()

    fun install(id: String) = deps.install(id)

    fun cancel(id: String) = deps.cancel(id)

    fun tryAgain(id: String) = deps.tryAgain(id)

    fun installAgain(id: String) = deps.installAgain(id)

    fun remove(id: String) {
        deps.scope.launch { saveOrLog("remove plugin $id") { deps.remove(id) } }
    }

    fun turnOnAgain(id: String) {
        deps.scope.launch { saveOrLog("turn plugin $id on again") { deps.turnOnAgain(id) } }
    }

    fun turnPluginsOnAgain() {
        deps.scope.launch { saveOrLog("turn the plugins on again") { deps.turnPluginsOnAgain() } }
    }

    override fun onCleared() {
        deps.pageLeft()
    }
}

/**
 * Settings › Plugins (W157, L620, L621, L633, L638), in every build, on phones and TVs: the intro line, the page's ⚠ lines,
 * one card per plugin (its icon, name, the list's summary, its state's lines and buttons), and the list's source with
 * Check again. No page per plugin. Remove asks first, Cancel focused on a TV and an OK just after it opens let by
 * (OkGuard). [focus]: the plugin whose card opens in view, its first button focused on a TV (Open Plugins' way in).
 * [onSettings] and [onOpen]: a card's Settings and "Open <label>", by its slot; without them (a test's page), the cards
 * have neither. [onRemove]: a card's slot just before its plugin is removed, for the app to leave the slot first where an
 * entry of it is still on the current stack (L581).
 *
 * On a TV the first focus is Turn on again while plugins are off, else the first card's first enabled button, else Check
 * again; a button that goes hands the focus to what took its place ([PluginsPage.focusAfter]), so something is focused
 * after every change. Left and Right move along a card's buttons, Down to the next card, then to Check again; a greyed
 * button takes no focus.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PluginsScreen(
    focus: String?,
    onBack: () -> Unit,
    onSettings: ((Int) -> Unit)? = null,
    onOpen: ((Int) -> Unit)? = null,
    deps: PluginsDeps? = null,
    onRemove: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val formFactor = LocalFormFactor.current
    val tv = formFactor == FormFactor.Tv
    val after = remember(formFactor) { pluginTabsFollow(formFactor) }
    val slots = onSettings != null && onOpen != null
    val vm: PluginsViewModel = viewModel { PluginsViewModel(deps ?: PluginsDeps.of(context.container), after, slots) }
    val page by vm.page.collectAsStateWithLifecycle()
    var asking by rememberSaveable { mutableStateOf<String?>(null) }

    // A remote's focus: where the page put it ([placed]), the element of the page that last had it ([focused]), and whether
    // it is still where the page put it as it opened, the user having moved or pressed nothing ([auto]).
    val requesters = remember { HashMap<PluginsFocus, FocusRequester>() }
    fun requester(key: PluginsFocus): FocusRequester = requesters.getOrPut(key) { FocusRequester() }
    var focused by remember { mutableStateOf<PluginsFocus?>(null) }
    var placed by remember { mutableStateOf<PluginsFocus?>(null) }
    var auto by remember { mutableStateOf(true) }
    var shown by remember { mutableStateOf<PluginsPage?>(null) }
    val initial = rememberInitialFocus()
    val current = page
    // Where the focus was as this page was composed, before a button it replaces went: Compose then gives the focus to
    // another element of its own accord, which mustn't count as the user's move.
    val atChange = remember(current) { focused to auto }
    if (tv && current != null) LaunchedEffect(current, asking == null) {
        // Not while Remove asks: its Cancel has the focus; the page's next change after it puts it back. Only the question
        // gone, the page the same, the focus comes back where it was.
        if (asking != null || shown == current) return@LaunchedEffect
        val before = shown
        val (was, wasAuto) = atChange
        val target = if (before == null || was == null) current.firstFocus(focus) else PluginsPage.focusAfter(before, was, current, wasAuto)
        shown = current
        if (target != focused || placed == null) {
            placed = target
            initial.awaitTurn()
            // The element may be laid out a frame after the page that holds it.
            val element = requester(target)
            repeat(FOCUS_TRIES) { if (element.tryFocus()) return@LaunchedEffect else delay(16) }
        }
    }
    fun Modifier.remoteFocus(key: PluginsFocus): Modifier = if (!tv) this else focusRequester(requester(key)).onFocusChanged { state ->
        if (state.isFocused) {
            if (key != placed) auto = false
            focused = key
        }
    }
    fun pressed(action: () -> Unit): () -> Unit = {
        auto = false
        action()
    }

    // A phone opened for one plugin: its card brought into view, once.
    val inView = remember { BringIntoViewRequester() }
    var broughtIn by rememberSaveable { mutableStateOf(false) }
    if (!tv && focus != null && !broughtIn && current?.cards?.any { it.id == focus } == true) LaunchedEffect(Unit) {
        broughtIn = true
        inView.bringIntoView()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(PluginsPage.TITLE, onBack)
        Text(PluginsPage.INTRO, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
        val shownPage = current ?: return@Column
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shownPage.lines.forEach { ResultLine(false, it) }
            if (shownPage.pluginsOff) {
                ResultLine(false, PluginsPage.PLUGINS_OFF)
                SecondaryButton(CardAction.TurnOnAgain.label, pressed(vm::turnPluginsOnAgain), Modifier.remoteFocus(PluginsFocus.TurnPluginsOn))
            }
        }
        if (shownPage.lines.isNotEmpty() || shownPage.pluginsOff) Spacer(Modifier.height(12.dp))
        if (shownPage.cards.isNotEmpty()) {
            GroupCard {
                shownPage.cards.forEachIndexed { i, card ->
                    if (i > 0) InsetDivider()
                    key(card.id) {
                        PluginCardView(
                            card, tv,
                            modifier = if (card.id == focus) Modifier.bringIntoViewRequester(inView) else Modifier,
                            button = { button -> Modifier.remoteFocus(PluginsFocus.Button(card.id, button.action)) },
                            onPress = { button ->
                                pressed {
                                    when (button.action) {
                                        CardAction.Install -> vm.install(card.id)
                                        CardAction.Cancel -> vm.cancel(card.id)
                                        CardAction.TryAgain -> vm.tryAgain(card.id)
                                        CardAction.InstallAgain -> vm.installAgain(card.id)
                                        CardAction.Open -> card.slot?.let { slot -> onOpen?.invoke(slot) }
                                        CardAction.Settings -> card.slot?.let { slot -> onSettings?.invoke(slot) }
                                        CardAction.Remove -> asking = card.id
                                        CardAction.TurnOnAgain -> vm.turnOnAgain(card.id)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        // In the place of the cards the list would add: the list being read, or nothing listed.
        shownPage.placeholder?.let { placeholder ->
            GroupCard(Modifier.padding(top = if (shownPage.cards.isEmpty()) 0.dp else 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (placeholder == PagePlaceholder.Reading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent, trackColor = colors.outline)
                    Text(if (placeholder == PagePlaceholder.Reading) PluginsPage.READING else PluginsPage.NONE_LISTED, style = AppType.secondary, color = colors.textSecondary)
                }
            }
        }
        // The list's source, always, with Check again: it keeps the focus while it reads, its words "Checking…".
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(shownPage.source, style = AppType.small, color = colors.muted, modifier = Modifier.weight(1f))
            TextButton(onClick = pressed { if (!shownPage.checking) vm.checkAgain() }, modifier = Modifier.remoteFocus(PluginsFocus.CheckAgain)) {
                Text(shownPage.checkAgain, style = AppType.small.copy(fontWeight = FontWeight.SemiBold), color = colors.accent)
            }
        }
    }

    val removing = asking?.let { id -> current?.cards?.firstOrNull { it.id == id } }
    // The card gone meanwhile (removed elsewhere): nothing to ask.
    if (asking != null && current != null && removing == null) LaunchedEffect(asking) { asking = null }
    removing?.let { card ->
        RemoveQuestion(card, tv, onCancel = { asking = null }) {
            asking = null
            card.slot?.let(onRemove)
            vm.remove(card.id)
        }
    }
}

/**
 * Remove's question (L633), in the app's confirm dialog: "Remove <name>?", what goes and what stays, then Cancel and
 * Remove in the warn colour. On a TV Cancel has the focus, and an OK just after the question opened (the one that opened
 * it, pressed twice or held) is let by, so two OKs in a row never remove the plugin.
 */
@Composable
private fun RemoveQuestion(card: PluginCard, tv: Boolean, onCancel: () -> Unit, onRemove: () -> Unit) {
    val cancel = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.okGuard().searchKeyOpensSearch(onCancel),
        containerColor = colors.surface,
        title = { Text(PluginsPage.removeQuestion(card.name), style = AppType.cardTitle, color = colors.text) },
        text = { Text(PluginsPage.removeText(card.label), style = AppType.body, color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onRemove) { Text(CardAction.Remove.label, style = AppType.bodyStrong, color = colors.warnStrong) }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.focusRequester(cancel)) { Text("Cancel", style = AppType.body, color = colors.text) }
            LaunchedEffect(Unit) { if (tv) cancel.requestFocusWhenReady() }
        },
    )
}

/**
 * One plugin's card: its icon (muted; the core set's Build until it has loaded in this process), name, the list's summary,
 * its lines, then its buttons; on a phone "Open <label>" on a row of its own above the others, which wrap at a large text
 * size, and on a TV on one row. [button] gives each button its focus modifier, [onPress] what it does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginCardView(card: PluginCard, tv: Boolean, modifier: Modifier, button: (CardButton) -> Modifier, onPress: (CardButton) -> () -> Unit) {
    Row(modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(card.icon ?: Icons.Filled.Build, contentDescription = null, tint = colors.muted, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(card.name, style = AppType.bodyStrong, color = colors.text)
            card.summary?.let { Text(it, style = AppType.secondary, color = colors.muted) }
            card.lines.forEach { line -> CardLineView(line) }
            if (card.buttons.isNotEmpty()) {
                val open = card.buttons.filter { it.action == CardAction.Open }
                val rest = if (tv) card.buttons else card.buttons - open.toSet()
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!tv) open.forEach { CardButtonView(it, button(it), onPress(it)) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rest.forEach { CardButtonView(it, button(it), onPress(it)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardLineView(line: CardLine) {
    when (line) {
        is CardLine.Version -> Text(line.text, style = AppType.small, color = colors.muted)
        is CardLine.Note -> Text(line.text, style = AppType.small, color = colors.textSecondary)
        is CardLine.Done -> ResultLine(true, line.text)
        is CardLine.Warn -> ResultLine(false, line.text)
        is CardLine.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(PluginsPage.DOWNLOADING, style = AppType.small, color = colors.accent)
            LinearProgressIndicator(progress = { line.percent / 100f }, modifier = Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.outline)
        }
        CardLine.Checking -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.accent, trackColor = colors.outline)
            Text(PluginsPage.CHECKING_FILE, style = AppType.secondary, color = colors.textSecondary)
        }
    }
}

/** Install, Try again and Open in the accent; Remove a text button in the warn colour; the others outlined. */
@Composable
private fun CardButtonView(button: CardButton, modifier: Modifier, onClick: () -> Unit) {
    when (button.action) {
        CardAction.Install, CardAction.TryAgain, CardAction.Open -> PrimaryButton(button.label, onClick, modifier, enabled = button.enabled)
        CardAction.Remove -> TextButton(onClick = onClick, modifier = modifier, enabled = button.enabled) {
            Text(button.label, style = AppType.body, color = colors.warnStrong)
        }
        else -> SecondaryButton(button.label, onClick, modifier, enabled = button.enabled)
    }
}

/** How many frames the page waits for an element it gives the focus to be laid out. */
private const val FOCUS_TRIES = 10
