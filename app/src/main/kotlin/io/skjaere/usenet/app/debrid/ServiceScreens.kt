package io.skjaere.usenet.app.debrid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.requestFocusWhenReady
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.app.settings.CapsLine
import io.skjaere.usenet.app.setup.FormPhoneSetupRow
import io.skjaere.usenet.app.tv.searchKeyOpensSearch
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FieldKind
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.FormField
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.ListMove
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.NavRow
import io.skjaere.usenet.app.ui.OrderedRow
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.ResultLine
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.SwitchRow
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.TvTypingTip
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.data.ServiceWithKey
import io.skjaere.usenet.debrid.ServiceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** The saved accounts with their keys, read again as they change: a key that can't be read is said in its row. */
private fun AppContainer.servicesWithKeys() = settings.services.map { settings.servicesWithKeys() }

class DebridServicesViewModel(private val container: AppContainer) : ViewModel() {
    /** Null until read, so an empty list's button doesn't flash before the accounts show. */
    val services: StateFlow<List<ServiceWithKey>?> =
        container.servicesWithKeys().map(::servicesInOrder).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Account [id] [by] places up (negative) or down: plays try them in the new order from then on (L228). */
    fun move(id: String, by: Int) {
        viewModelScope.launch { saveOrLog("move a debrid service") { container.settings.moveService(id, by) } }
    }
}

/**
 * Settings › Debrid services (DbPhoneServiceAdd 1; D2PhoneServiceOrder, D2TvServiceOrder): the accounts in the user's
 * order, each row with its place and the move arrows, as Usenet providers' rows (L229), opening its page; no switch on
 * the list (on and off are the page's). Add while a service isn't signed in yet; with none, the line saying what a
 * service is for and its Add a service button.
 */
@Composable
fun DebridServicesScreen(onBack: () -> Unit, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val container = LocalContext.current.container
    val vm: DebridServicesViewModel = viewModel { DebridServicesViewModel(container) }
    val services by vm.services.collectAsStateWithLifecycle()
    val list = services
    val add = remember { FocusRequester() }
    val firstRow = remember { FocusRequester() }
    // The last move: the row keeps the focus in its new place, and TalkBack reads it out there.
    var moved by remember { mutableStateOf<ListMove?>(null) }
    val initial = rememberInitialFocus()
    // On a TV, once the accounts are read: + Add, as the other lists start, or with every service signed in the first row
    // (DbTvSources 2). Nothing focusable shows before they are, so the shell's own first focus would find nothing.
    if (LocalFormFactor.current == FormFactor.Tv && list != null) LaunchedEffect(Unit) { initial.request(if (canAddService(list)) add else firstRow) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Debrid services", onBack) {
            // DbPhoneServiceAdd 1: Add as Usenet providers have it, a chip with the + and the word.
            if (list != null && canAddService(list)) {
                TextButton(
                    onClick = onAdd, modifier = Modifier.padding(end = 4.dp).focusRequester(add).focusOutline(CircleShape, fill = false),
                    colors = ButtonDefaults.textButtonColors(containerColor = colors.chip, contentColor = colors.text),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Add", style = AppType.body, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        when {
            list == null -> Unit
            list.isEmpty() -> {
                Footnote(NO_SERVICE_LINE)
                PrimaryButton("Add a service", onAdd, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
                Footnote(SEARCHED_ONLY_WHILE_ON, Modifier.padding(top = 4.dp))
            }
            else -> {
                GroupCard(Modifier.padding(top = 4.dp)) {
                    serviceRows(list).forEachIndexed { i, row ->
                        if (i > 0) InsetDivider()
                        key(row.id) {
                            OrderedRow(
                                row.place, row.name, moved?.takeIf { it.id == row.id }, onOpen = { onOpen(row.id) },
                                onMove = { by -> moved = ListMove(row.id, by, row.place.position); vm.move(row.id, by) },
                                bodyModifier = if (i == 0) Modifier.focusRequester(firstRow) else Modifier, off = row.off, spoken = row.spoken,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(row.line.text, style = AppType.secondary, color = colors.muted, modifier = Modifier.weight(1f, fill = false))
                                    // A key refused or missing: the warning dot, as the D1 row had it.
                                    if (row.line.warn) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.warnStrong))
                                }
                            }
                        }
                    }
                }
                Footnote(SERVICES_FOOTNOTE)
            }
        }
    }
}

class AddServiceViewModel(container: AppContainer) : ViewModel() {
    /** The services not signed in to yet, A to Z. */
    val kinds: StateFlow<List<ServiceKind>?> = container.settings.services
        .map { saved -> pickerKinds(saved.mapNotNull(::kindOf).toSet()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * Add a service (DbPhoneServiceAdd 2, DbTvSignIn 1): the services that work and aren't signed in yet, A to Z, as plain
 * names (L183), each opening its key form; under them what goes to a service, and one place at a time. A remote starts on
 * the first service.
 */
@Composable
fun AddServiceScreen(onBack: () -> Unit, onPick: (ServiceKind) -> Unit) {
    val container = LocalContext.current.container
    val vm: AddServiceViewModel = viewModel { AddServiceViewModel(container) }
    val kinds by vm.kinds.collectAsStateWithLifecycle()
    val firstRow = remember { FocusRequester() }
    val initial = rememberInitialFocus()
    // On a TV, once the services are read: nothing focusable shows before they are, so the shell's own first focus would
    // find nothing (DbTvSignIn 1).
    if (LocalFormFactor.current == FormFactor.Tv && !kinds.isNullOrEmpty()) LaunchedEffect(Unit) { initial.request(firstRow) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Add a service", onBack)
        Text(PICKER_LINE, style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
        kinds?.takeIf { it.isNotEmpty() }?.let { list ->
            GroupCard {
                list.forEachIndexed { i, kind ->
                    if (i > 0) InsetDivider()
                    NavRow(kind.displayName, modifier = if (i == 0) Modifier.focusRequester(firstRow) else Modifier) { onPick(kind) }
                }
            }
        }
        Footnote(listOf(HASH_LINE, shareLine(container.television)))
    }
}

/**
 * The key form's Test and Save, one at a time, for the key as it is: a new Test or Save cancels the one running, and a
 * change to the key cancels it and clears its line, so a result for another key never shows.
 */
class ServiceKeyViewModel(private val container: AppContainer, private val kind: ServiceKind, private val replacing: String?) : ViewModel() {
    val key = MutableStateFlow("")
    val result = MutableStateFlow<KeyTest>(KeyTest.Idle)
    val done = MutableStateFlow(false)
    private var job: Job? = null

    fun update(value: String) {
        key.value = value
        job?.cancel()
        job = null
        result.value = KeyTest.Idle
    }

    fun test() = attempt { testKey(kind, container.services.trial(kind, key.value.trim())) }

    fun save() = attempt {
        saveKey(container.settings, container.services::trial, kind, key.value, replacing).also { if (it is KeyTest.Passed) done.value = true }
    }

    private fun attempt(call: suspend () -> KeyTest) {
        if (key.value.isBlank()) return
        job?.cancel()
        result.value = KeyTest.Running
        job = viewModelScope.launch {
            result.value = try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not the service's answer (that is a ServiceException, in words of its own): the settings couldn't be written.
                log.warn("Couldn't keep a {} key", kind.displayName, e)
                KeyTest.Failed("The key couldn’t be saved on this device. Try again.")
            }
        }
    }
}

/**
 * The key form (DbPhoneServiceAdd 3, 4, close-ups 3 and 6; DbTvSignIn 2-4): the API key, hidden while typed with its Show,
 * where the service keeps it, Test (signs in, saving nothing) and Save (signs in again, keeping the key only if the
 * service takes it, then [onSaved]). [replacing]: Replace key's form for that account, the old key kept until then. On a
 * TV the typing tip and "Easier: Set up with your phone" ([onPhoneSetup]) above the field, which has the first focus.
 */
@Composable
fun ServiceKeyScreen(kindName: String, replacing: String?, onBack: () -> Unit, onSaved: () -> Unit, onPhoneSetup: (() -> Unit)? = null) {
    val kind = ServiceKind.entries.firstOrNull { it.name == kindName }
    if (kind == null) {
        // A route only a newer version names: nothing to sign in to here.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val container = LocalContext.current.container
    val vm: ServiceKeyViewModel = viewModel { ServiceKeyViewModel(container, kind, replacing) }
    val key by vm.key.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    LaunchedEffect(done) { if (done) onSaved() }
    val tv = LocalFormFactor.current == FormFactor.Tv
    val field = remember { FocusRequester() }
    val initial = rememberInitialFocus()
    // DbTvSignIn 2: a remote starts on the field, the key being what the page is for.
    if (tv) LaunchedEffect(Unit) { initial.request(field) }
    // Not disabled while one runs: a remote's focus would leave the button it pressed (DbTvSignIn 4). Another press
    // starts it again for the key as it is.
    val ready = key.isNotBlank()

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        TopBar(keyFormTitle(kind, replacing != null), onBack)
        TvTypingTip(phoneBelow = onPhoneSetup != null)
        FormPhoneSetupRow(onPhoneSetup, title = KEY_FORM_PHONE_ROW)
        FormField(
            "API key", key, vm::update, Modifier.padding(horizontal = 16.dp).padding(top = 4.dp).focusRequester(field),
            kind = FieldKind.Key, mono = true, placeholder = if (replacing != null) REPLACE_KEY_PLACEHOLDER else null, help = kind.keyPlace, spoken = true,
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KeyTestLine(result)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Test", vm::test, Modifier.weight(1f), enabled = ready)
                PrimaryButton("Save", vm::save, Modifier.weight(1f), enabled = ready)
            }
        }
        Footnote(keyFootnote(kind, container.television))
    }
}

/** A Test's or Save's line: ✓ with the account, ⚠ with what failed, a spinner while it runs. */
@Composable
private fun KeyTestLine(result: KeyTest) {
    when (result) {
        KeyTest.Idle -> Unit
        KeyTest.Running -> CircularProgressIndicator(strokeWidth = 2.dp, color = colors.accent)
        is KeyTest.Passed -> ResultLine(true, result.line)
        is KeyTest.Failed -> ResultLine(false, result.line)
    }
}

/**
 * A service's page's account as last read: [account] null once it is signed out of (or never was). Equal when the same:
 * every settings write reads it again (a move is one), and the page needn't draw again for one that changed nothing.
 */
data class ServiceRead(val account: ServiceWithKey?)

class ServiceViewModel(private val container: AppContainer, private val id: String) : ViewModel() {
    /** The account with its key, as last read; null until it is. */
    val saved: StateFlow<ServiceRead?> = container.settings.services
        .map { ServiceRead(container.settings.service(id)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val test = MutableStateFlow<KeyTest>(KeyTest.Idle)
    val done = MutableStateFlow(false)
    private var testing: Job? = null

    /** Signs in with the saved key (not held by a closed gate), keeping what the service says now of the account. */
    fun runTest() {
        if (test.value == KeyTest.Running) return
        test.value = KeyTest.Running
        testing = viewModelScope.launch {
            val client = container.services.service(id)
            test.value = if (client == null) {
                KeyTest.Failed(KEY_MISSING_LINE)
            } else try {
                val account = client.service.test()
                saveOrLog("keep what the service says of its account") { container.settings.updateSignIn(id, account.signIn()) }
                KeyTest.Passed(signedInLine(account))
            } catch (e: io.skjaere.usenet.debrid.ServiceException) {
                KeyTest.Failed(keyFailure(client.kind, e.error))
            }
        }
    }

    /** Replace key opened: a Test's line, or one running, is the replaced key's, so it goes before the page shows the new one. */
    fun replacingKey() {
        testing?.cancel()
        testing = null
        test.value = KeyTest.Idle
    }

    fun setEnabled(on: Boolean) = viewModelScope.launch { saveOrLog("switch a service") { container.settings.setServiceEnabled(id, on) } }

    /** Off, what this device's plays add to the account stays there, and the records of what they added go (L246). */
    fun setRemoveAfterPlay(on: Boolean) = viewModelScope.launch { saveOrLog("switch remove after playing") { container.settings.setRemoveAfterPlay(id, on) } }

    fun signOut() = viewModelScope.launch {
        saveOrLog("sign out of a service") { container.settings.signOut(id) }
        done.value = true
    }
}

/**
 * A service's page (DbPhoneServiceAdd 6, 7, close-up 5; DbTvSources 3, 4): whose account and its plan, Test, Replace key
 * ([onReplaceKey]), "Use this service" with, for a service whose plays add to the account, "Remove after playing" under it
 * (D2PhoneServiceSwitch, D2TvServiceSwitch; on a TV two Downs from Test), the links line, and Sign out, confirmed first (on
 * a TV the focus on Cancel, so two OKs in a row don't sign out). A key the service refused, or one missing, is said above
 * the buttons. A remote starts on Test, or on Replace key with no key to test. Signed out of, here or elsewhere, the page
 * goes back ([onBack]).
 */
@Composable
fun ServiceScreen(id: String, onBack: () -> Unit, onReplaceKey: (ServiceKind) -> Unit) {
    val container = LocalContext.current.container
    val vm: ServiceViewModel = viewModel { ServiceViewModel(container, id) }
    val read by vm.saved.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    // Signed out of by its own Sign out, or gone while the page stood under another (Set up with your phone, opened from
    // Replace key, can remove it, and Done then uncovers this page with nothing to show): back, once this is the page
    // showing, as Back goes only from that one. One flag for both, so Sign out's going doesn't go back twice.
    val saved = read
    val gone = done || saved != null && saved.account == null
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(gone) {
        if (gone) {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            onBack()
        }
    }
    var asking by rememberSaveable { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val initial = rememberInitialFocus()
    val account = saved?.account ?: return
    val record = account.record
    val kind = kindOf(record)
    val line = serviceRowLine(account)
    val testable = account.apiKey != null
    // DbTvSources 3: once the account is read, as nothing focusable shows before it is and the shell's own first focus
    // would find nothing.
    if (LocalFormFactor.current == FormFactor.Tv) LaunchedEffect(Unit) { initial.request(first) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(nameOf(record), onBack)
        Spacer(Modifier.height(8.dp))
        GroupCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                accountFacts(record).forEach { (label, value) -> CapsLine(label, value) }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (line.warn) ResultLine(false, line.text)
            KeyTestLine(test)
            if (kind != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Kept enabled while it runs (the remote's focus stays on it); a press meanwhile is let go by.
                    SecondaryButton("Test", vm::runTest, Modifier.weight(1f).then(if (testable) Modifier.focusRequester(first) else Modifier), enabled = testable)
                    SecondaryButton("Replace key", { vm.replacingKey(); onReplaceKey(kind) }, Modifier.weight(1f).then(if (testable) Modifier else Modifier.focusRequester(first)))
                }
            }
        }
        if (kind != null) {
            GroupCard {
                PageSwitch(USE_THIS_SERVICE, USE_THIS_SERVICE_HELP, record.enabled, vm::setEnabled)
                if (removeAfterPlayingShown(record)) {
                    InsetDivider()
                    PageSwitch(REMOVE_AFTER_PLAYING, REMOVE_AFTER_PLAYING_HELP, record.removeAfterPlay, vm::setRemoveAfterPlay)
                }
            }
            Footnote(linksLine(kind))
        }
        TextButton(onClick = { asking = true }, modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp).then(if (kind == null) Modifier.focusRequester(first) else Modifier)) {
            Text("Sign out", style = AppType.body, color = colors.warnStrong)
        }
    }
    if (asking) {
        val cancel = remember { FocusRequester() }
        val dismiss = { asking = false }
        val name = nameOf(record)
        AlertDialog(
            onDismissRequest = dismiss,
            modifier = Modifier.searchKeyOpensSearch(dismiss),
            containerColor = colors.surface,
            title = { Text(kind?.let(::signOutQuestion) ?: "Sign out of $name?", style = AppType.cardTitle, color = colors.text) },
            text = { Text(kind?.let { signOutText(it, container.television) } ?: "The key is removed from this device.", style = AppType.body, color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    vm.signOut()
                }) { Text("Sign out", style = AppType.bodyStrong, color = colors.warnStrong) }
            },
            dismissButton = {
                // Cancel first: OK twice in a row must not sign out (DbTvSources 4).
                TextButton(onClick = dismiss, modifier = Modifier.focusRequester(cancel)) { Text("Cancel", style = AppType.body, color = colors.text) }
                LaunchedEffect(Unit) { cancel.requestFocusWhenReady() }
            },
        )
    }
}

/** A switch on a service's page, its line under its words: TalkBack reads the two as one, the words first (L251). */
@Composable
private fun PageSwitch(label: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    SwitchRow(label, on, onChange, detail = detail, modifier = Modifier.semantics { contentDescription = serviceSwitchSpoken(label, detail) })
}

private val log = LoggerFactory.getLogger(ServiceKeyViewModel::class.java)
