package io.skjaere.usenet.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.describe
import io.skjaere.usenet.app.setup.FormPhoneSetupRow
import io.skjaere.usenet.app.tv.searchKeyOpensSearch
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FieldKind
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.Stepper
import io.skjaere.usenet.app.ui.FormField
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.ResultLine
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.SwitchRow
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.TvTypingTip
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.rememberFieldChain
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.core.UsenetEngine
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ProviderWithPassword
import io.skjaere.usenet.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ProviderForm(
    val id: String,
    /** Optional: the host is shown without one. */
    val name: String = "",
    val host: String = "",
    val port: String = "563",
    val username: String = "",
    /** Empty while editing means "keep the saved password". */
    val password: String = "",
    val hasSavedPassword: Boolean = false,
    /** A password was saved but can't be read on this device (e.g. after moving to a new one), so the provider isn't used. */
    val passwordUnreadable: Boolean = false,
    val useTls: Boolean = true,
    val connections: String = NEW_CONNECTIONS.toString(),
    /** Switched on or off in the list; kept as it is by a save. */
    val enabled: Boolean = true,
) {
    val portValue: Int? get() = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val connectionsValue: Int? get() = connections.toIntOrNull()?.takeIf { it in CONNECTIONS }
    val displayName: String get() = name.trim().ifEmpty { host.trim() }

    /**
     * The saved password can't be read and nothing is typed in its place: a provider with a login
     * can't be saved like that, or Save would store "" over it and the warning would go (#131).
     */
    val needsPassword: Boolean get() = passwordUnreadable && password.isEmpty() && username.isNotBlank()

    /**
     * Why the host can't be saved as typed, or null. A login typed into it (user:password@host) would
     * go to the engine with it, and the logs name the host; a scheme (nntps://) never connects.
     */
    val hostProblem: String?
        get() = when {
            '@' in host -> "Only the server's name: the username and password go in their own fields."
            "://" in host -> "Only the server's name, without nntps:// or nntp://."
            else -> null
        }

    val valid: Boolean get() = host.isNotBlank() && hostProblem == null && portValue != null && connectionsValue != null && !needsPassword

    /**
     * What Save stores as the password: the one typed; with the field empty, nothing (the saved one
     * is kept), or "" for a provider without a login, which needs none.
     */
    val passwordToSave: String?
        get() = when {
            password.isNotEmpty() -> password
            username.isBlank() -> ""
            hasSavedPassword || passwordUnreadable -> null
            else -> ""
        }
}

/**
 * The form for editing a saved provider; the password field starts empty. A name that is the host
 * starts empty too: before providers had names, saving one stored its host as its name, which would
 * stay behind when the host changes rather than follow it (S9-4).
 */
internal fun providerForm(p: ProviderWithPassword) = ProviderForm(
    p.record.id, p.record.name.takeUnless { it.trim() == p.record.host.trim() }.orEmpty(), p.record.host, p.record.port.toString(), p.record.username, "",
    !p.password.isNullOrEmpty(), p.password == null, p.record.useTls, p.record.connections.toString(), p.record.enabled,
)

/**
 * A TV's Connections stepper pressed: [by] more (or fewer), within the form's limits. A number out of them, typed on a
 * phone, steps from the nearest; none from a new provider's.
 */
internal fun ProviderForm.steppedConnections(by: Int): ProviderForm {
    val from = connections.toIntOrNull()?.coerceIn(CONNECTIONS) ?: NEW_CONNECTIONS
    return copy(connections = (from + by).coerceIn(CONNECTIONS).toString())
}

/** What a provider's connections may be, and a new provider's. */
private val CONNECTIONS = 1..100
private const val NEW_CONNECTIONS = 8

/**
 * The form for a provider that isn't saved yet. Its id is fixed now rather than when saving, so
 * pressing Save twice updates the one provider instead of adding a second.
 */
internal fun newProviderForm() = ProviderForm(id = UUID.randomUUID().toString())

/** Saves [f] ([ProviderForm.passwordToSave]); a new provider goes last. Returns the provider's id. */
internal suspend fun SettingsRepository.save(f: ProviderForm): String = saveProvider(
    ProviderRecord(f.id, f.name, f.host, f.portValue ?: 563, f.username, f.useTls, f.connectionsValue ?: NEW_CONNECTIONS, enabled = f.enabled),
    password = f.passwordToSave,
)

/**
 * Logs in to [host] with [password] over one throwaway connection, as the provider screen's Test
 * does, without saving anything; at most two connections are asked for.
 */
internal suspend fun testLogin(host: String, port: Int, username: String, password: String, useTls: Boolean, connections: Int): ProviderTestResult {
    val config = ProviderConfig("test", host.trim(), host.trim(), port, username.trim(), password, useTls, minOf(connections, 2))
    return config.loginResult(UsenetEngine.testProvider(config))
}

/** How logging in to this provider went, as a Test shows it: [error] is null when it worked. */
internal fun ProviderConfig.loginResult(error: UsenetError?): ProviderTestResult {
    val login = ProviderLogin.of(host, port, username, useTls, password)
    return when (error) {
        null -> ProviderTestResult(login, ok = true, message = "Connected and logged in to ${host.trim()}")
        else -> ProviderTestResult(login, ok = false, message = error.describe())
    }
}

/** A saved provider's Test, with its saved password. */
internal suspend fun testLogin(p: ProviderWithPassword): ProviderTestResult =
    testLogin(p.record.host, p.record.port, p.record.username, p.password.orEmpty(), p.record.useTls, p.record.connections)

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Done(val ok: Boolean, val message: String) : TestState
}

/** One provider's screen: [id] is a saved provider's, null for a new one. */
class ProviderViewModel(private val container: AppContainer, private val id: String?) : ViewModel() {
    val form = MutableStateFlow(id?.let { ProviderForm(it) } ?: newProviderForm())

    /** The provider as saved, which the title names; null for a new one, and until it is read. */
    val saved = MutableStateFlow<ProviderRecord?>(null)
    val done = MutableStateFlow(false)

    private val tests = ProviderTestRun(viewModelScope, ::test) { result -> container.providerTests.record(form.value.id, result) }
    val test: StateFlow<TestState> = tests.state

    init {
        id?.let {
            viewModelScope.launch {
                container.settings.provider(it)?.let { p ->
                    saved.value = p.record
                    form.value = providerForm(p)
                    tests.formChanged(form.value)
                }
            }
        }
    }

    fun update(change: (ProviderForm) -> ProviderForm) {
        form.update(change)
        tests.formChanged(form.value)
    }

    /** Logs in with these settings over one throwaway connection, without saving; the list shows how it went. */
    fun runTest() = tests.start(form.value)

    private suspend fun test(f: ProviderForm): ProviderTestResult {
        val password = f.password.ifEmpty { container.settings.provider(f.id)?.password.orEmpty() }
        return testLogin(f.host, f.portValue ?: 563, f.username, password, f.useTls, f.connectionsValue ?: 1)
    }

    fun save() = viewModelScope.launch {
        container.settings.save(form.value)
        done.value = true
    }

    /** Deletes the provider, its password with it: a tombstone stays in the settings. */
    fun delete() = viewModelScope.launch {
        container.settings.deleteProvider(form.value.id)
        container.providerTests.forget(form.value.id)
        done.value = true
    }
}

/** [onPhoneSetup]: "Set up with your phone" for this provider (a TV's; null shows none). */
@Composable
fun ProviderScreen(id: String?, onBack: () -> Unit, onPhoneSetup: (() -> Unit)? = null) {
    val container = LocalContext.current.container
    val vm: ProviderViewModel = viewModel { ProviderViewModel(container, id) }
    val form by vm.form.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(done) { if (done) onBack() }
    // The keyboard's Next goes down the fields.
    val chain = rememberFieldChain()

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        TopBar(
            when {
                id == null -> "Add provider"
                saved == null -> "Edit provider"
                else -> "Edit · ${saved?.displayName}"
            },
            onBack,
        )
        TvTypingTip(phoneBelow = onPhoneSetup != null)
        FormPhoneSetupRow(onPhoneSetup)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FormField(
                "Name", form.name, { v -> vm.update { it.copy(name = v) } },
                placeholder = form.host.trim().ifEmpty { "Optional" }, help = "Optional. Without one, the host is shown.", chain = chain,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FormField(
                    "Host", form.host, { v -> vm.update { it.copy(host = v) } }, Modifier.weight(1f), kind = FieldKind.Host,
                    placeholder = "news.example.net", help = form.hostProblem, helpWarn = form.hostProblem != null, chain = chain,
                )
                FormField("Port", form.port, { v -> vm.update { it.copy(port = v) } }, Modifier.weight(0.4f), kind = FieldKind.Port, chain = chain)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FormField("Username", form.username, { v -> vm.update { it.copy(username = v) } }, Modifier.weight(1f), kind = FieldKind.Username, chain = chain)
                FormField(
                    "Password", form.password, { v -> vm.update { it.copy(password = v) } }, Modifier.weight(1f), kind = FieldKind.Password,
                    placeholder = if (form.hasSavedPassword) "Saved" else null,
                    help = if (form.passwordUnreadable && form.username.isNotBlank()) "The saved password can't be read here. Enter it again." else null,
                    helpWarn = form.passwordUnreadable, chain = chain,
                )
            }
        }
        Column(Modifier.padding(top = 12.dp)) {
            GroupCard {
                SwitchRow("Use TLS", form.useTls, { v -> vm.update { it.copy(useTls = v, port = if (it.port == "119" && v) "563" else if (it.port == "563" && !v) "119" else it.port) } })
                InsetDivider()
                val connectionsHelp = "Your provider's limit, shared with other devices that use the same account."
                if (LocalFormFactor.current == FormFactor.Tv) {
                    // Typing a number with a remote is slow: [ − ] N [ + ].
                    Stepper(
                        "Connections", form.steppedConnections(0).connections, onStep = { by -> vm.update { it.steppedConnections(by) } },
                        fewer = "Fewer connections", more = "More connections", help = connectionsHelp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                } else {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        FormField("Connections", form.connections, { v -> vm.update { it.copy(connections = v) } }, kind = FieldKind.Number, help = connectionsHelp, chain = chain)
                    }
                }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (val t = test) {
                TestState.Idle -> Unit
                TestState.Running -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp, color = colors.accent)
                }
                is TestState.Done -> ResultLine(t.ok, t.message)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Test", vm::runTest, Modifier.weight(1f), enabled = form.valid && test != TestState.Running)
                PrimaryButton("Save", { vm.save() }, Modifier.weight(1f), enabled = form.valid)
            }
        }
        if (id != null) {
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp)) {
                Text("Delete provider", style = AppType.body, color = colors.warnStrong)
            }
        }
    }
    if (confirmDelete) {
        val name = saved?.displayName ?: form.displayName
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            modifier = Modifier.searchKeyOpensSearch { confirmDelete = false },
            containerColor = colors.surface,
            title = { Text("Delete $name?", style = AppType.cardTitle, color = colors.text) },
            text = { Text("Its settings and saved password are removed from this device.", style = AppType.body, color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete", style = AppType.bodyStrong, color = colors.warnStrong) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", style = AppType.body, color = colors.text) }
            },
        )
    }
}
