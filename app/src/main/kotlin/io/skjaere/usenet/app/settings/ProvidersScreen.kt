package io.skjaere.usenet.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.ListMove
import io.skjaere.usenet.app.ui.OrderedRow
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProvidersViewModel(private val container: AppContainer) : ViewModel() {
    private val settings = container.settings

    /** The provider "Test all" is testing now. */
    private val testing = MutableStateFlow<String?>(null)
    private var testAll: Job? = null
    private val _testingAll = MutableStateFlow(false)
    val testingAll: StateFlow<Boolean> = _testingAll.asStateFlow()

    /** Null until the saved providers are read. */
    val rows: StateFlow<List<ProviderRow>?> = combine(
        settings.providersWithPasswords.map { list -> list.map { SavedProvider(it.record, it.login) } },
        container.providerTests.results,
        testing,
    ) { saved, tests, now -> providerRows(saved, tests, now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The provider whose screen was opened from the list (null: Add), and the providers listed then. */
    private var opened: Pair<String?, List<String>>? = null
    private val _refocus = MutableStateFlow<ListFocus?>(null)

    /** Where the focus goes as the list shows again after a provider's screen (S9-11), until [refocused]. */
    val refocus: StateFlow<ListFocus?> = _refocus.asStateFlow()

    /** Provider [id]'s screen (null: Add) opens over the list, which gives the focus back on its return. */
    fun opened(id: String?) {
        opened = id to rows.value.orEmpty().map { it.id }
    }

    /**
     * The list shows again: after a provider's screen, the focus goes back to its place there
     * ([focusAfterEdit]), from the providers as saved now rather than as the list last showed them.
     */
    fun shown() {
        val (id, listed) = opened ?: return
        opened = null
        viewModelScope.launch { _refocus.value = focusAfterEdit(id, listed, settings.providers.first().map { it.id }) }
    }

    fun refocused() {
        _refocus.value = null
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { settings.setProviderEnabled(id, enabled) }
    }

    /** [by] places up (negative) or down; the engine takes up the new order at once. */
    fun move(id: String, by: Int) {
        viewModelScope.launch { settings.moveProvider(id, by) }
    }

    /**
     * Tests the providers switched on, one after another ([testEnabledProviders]), and shows how
     * each went. Pressed again while they are tested, nothing: the button stays enabled, as one
     * disabled would lose a remote's focus (S9-11).
     */
    fun testAll() {
        if (testAll?.isActive == true) return
        testAll = viewModelScope.launch {
            _testingAll.value = true
            try {
                testEnabledProviders(settings, container.providerTests, testing, ::testLogin)
            } finally {
                _testingAll.value = false
            }
        }
    }
}

/**
 * Settings › Usenet providers: the saved providers in the order articles are asked of them, each
 * switched on or off here and moved up or down; [onEdit] opens one (null adds one).
 */
@Composable
fun ProvidersScreen(onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val container = LocalContext.current.container
    val vm: ProvidersViewModel = viewModel { ProvidersViewModel(container) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val testingAll by vm.testingAll.collectAsStateWithLifecycle()
    val refocus by vm.refocus.collectAsStateWithLifecycle()
    // The last move: the row keeps the focus in its new place, and TalkBack reads it out there.
    var moved by remember { mutableStateOf<ListMove?>(null) }
    val add = remember { FocusRequester() }
    val edit = { id: String? -> vm.opened(id); onEdit(id) }
    // Back from a provider's screen, a remote's focus is where it was rather than at the top (S9-11).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.shown() }
    LaunchedEffect(refocus) {
        if (refocus != ListFocus.Add) return@LaunchedEffect
        runCatching { add.requestFocus() }
        vm.refocused()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Usenet providers", onBack) {
            TextButton(
                onClick = { edit(null) }, modifier = Modifier.padding(end = 4.dp).focusRequester(add).focusOutline(CircleShape, fill = false),
                colors = ButtonDefaults.textButtonColors(containerColor = colors.chip, contentColor = colors.text),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Add", style = AppType.body, modifier = Modifier.padding(start = 6.dp))
            }
        }
        val list = rows
        when {
            list == null -> Unit
            list.isEmpty() -> {
                Footnote("Add the Usenet account you stream from: its server address, and its username and password if it has them.")
                PrimaryButton("Add a provider", { edit(null) }, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
            }
            else -> {
                Text(
                    "Articles are fetched from the first provider; missing ones are tried on the next.",
                    style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 12.dp),
                )
                GroupCard {
                    list.forEachIndexed { i, row ->
                        if (i > 0) InsetDivider()
                        key(row.id) {
                            ProviderRowView(
                                row,
                                moved = moved?.takeIf { it.id == row.id },
                                refocus = refocus == ListFocus.Row(row.id),
                                onRefocused = vm::refocused,
                                onEdit = { edit(row.id) },
                                onEnabled = { vm.setEnabled(row.id, it) },
                                onMove = { by -> moved = ListMove(row.id, by, row.position); vm.move(row.id, by) },
                            )
                        }
                    }
                }
                SecondaryButton(
                    if (testingAll) "Testing…" else "Test all", vm::testAll,
                    Modifier.padding(16.dp).fillMaxWidth().focusOutline(RoundedCornerShape(24.dp), fill = false), enabled = list.any { it.enabled },
                )
            }
        }
    }
}

/**
 * One provider ([OrderedRow]): its place, name, address and status open it; then its switch, and Move up and Move down.
 * [moved]: the row's last move, which keeps the focus on the arrow pressed (S9-2). [refocus]: the list shows again after
 * this provider's screen, or another's that was in its place, and the row takes the focus back (S9-11).
 */
@Composable
private fun ProviderRowView(
    row: ProviderRow, moved: ListMove?, refocus: Boolean, onRefocused: () -> Unit,
    onEdit: () -> Unit, onEnabled: (Boolean) -> Unit, onMove: (Int) -> Unit,
) {
    val body = remember { FocusRequester() }
    LaunchedEffect(refocus) {
        if (!refocus) return@LaunchedEffect
        runCatching { body.requestFocus() }
        onRefocused()
    }
    OrderedRow(
        row.place, row.name, moved, onEdit, onMove, body = body, off = !row.enabled,
        end = {
            Switch(
                checked = row.enabled, onCheckedChange = onEnabled,
                modifier = Modifier.focusOutline(CircleShape, fill = false).padding(horizontal = 4.dp).semantics { contentDescription = "Use ${row.name}" },
                colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent, uncheckedTrackColor = colors.chip, uncheckedBorderColor = colors.outline),
            )
        },
    ) {
        Text(row.address, style = AppType.secondary, color = colors.muted)
        row.status?.let { StatusLine(it) }
    }
}

@Composable
private fun StatusLine(status: ProviderStatus) {
    val color = when (status) {
        ProviderStatus.Connected -> colors.good
        ProviderStatus.Off, ProviderStatus.Testing -> colors.muted
        ProviderStatus.Unreadable, is ProviderStatus.Failed -> colors.warnStrong
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (status == ProviderStatus.Connected) Box(Modifier.size(7.dp).clip(CircleShape).background(colors.goodDot))
        if (status is ProviderStatus.Failed || status == ProviderStatus.Unreadable) Text("⚠", style = AppType.small, color = color)
        Text(status.text, style = AppType.small, color = color)
    }
}
