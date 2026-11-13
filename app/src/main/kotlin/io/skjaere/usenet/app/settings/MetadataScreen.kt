package io.skjaere.usenet.app.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.openExternalUrl
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.app.setup.FormPhoneSetupRow
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FieldKind
import io.skjaere.usenet.app.ui.FormField
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.ResultLine
import io.skjaere.usenet.app.ui.SwitchRow
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.TvTypingTip
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.metadata.KeyCheck
import io.skjaere.usenet.metadata.TMDB_ATTRIBUTION
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

private const val TMDB_KEY_HELP_URL = "https://developer.themoviedb.org/docs/getting-started"

/** How a TMDB key's check went, in the words Settings › Metadata shows: [ok] when TMDB took it, and it was saved. */
data class TmdbKeyCheck(val ok: Boolean, val message: String)

/**
 * Checks [key] (trimmed) with TMDB ([validate]) and saves it ([save]) only when TMDB takes it: a key is never saved
 * untested, so the collection never runs on one TMDB refuses. Settings › Metadata and "Set up with your phone" both
 * save a key this way.
 */
internal suspend fun checkAndSaveTmdbKey(key: String, validate: suspend (String) -> KeyCheck, save: suspend (String) -> Unit): TmdbKeyCheck {
    val trimmed = key.trim()
    val check = validate(trimmed)
    if (check == KeyCheck.Valid) save(trimmed)
    return tmdbKeyCheck(check)
}

/** How TMDB's [check] of a key went, in Settings › Metadata's words; a test from the phone's page says them too, saving nothing. */
internal fun tmdbKeyCheck(check: KeyCheck): TmdbKeyCheck = when (check) {
    KeyCheck.Valid -> TmdbKeyCheck(ok = true, message = "Key works")
    KeyCheck.Invalid -> TmdbKeyCheck(ok = false, message = TMDB_REJECTED)
    is KeyCheck.Failed -> TmdbKeyCheck(ok = false, message = "Couldn't check the key: ${check.error.message}")
}

/** TMDB's answer to a key it refuses. */
const val TMDB_REJECTED = "TMDB rejected this key."

class MetadataViewModel(private val container: AppContainer) : ViewModel() {
    val hasKey = container.settings.hasTmdbKey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val prefs = container.settings.metadata.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), io.skjaere.usenet.data.MetadataPrefs())
    val keyInput = MutableStateFlow("")
    val status = MutableStateFlow<String?>(null)
    val statusOk = MutableStateFlow(true)
    val cacheBytes = MutableStateFlow(0L)

    init {
        refreshCacheSize()
    }

    /** Checks the key with TMDB before saving it. */
    fun saveKey() = viewModelScope.launch {
        status.value = "Checking…"
        val check = checkAndSaveTmdbKey(keyInput.value, { TmdbLookup.validate(container, it) }, container.settings::setTmdbKey)
        if (check.ok) keyInput.value = ""
        statusOk.value = check.ok
        status.value = check.message
    }

    fun removeKey() = viewModelScope.launch {
        container.settings.setTmdbKey(null)
        status.value = null
    }

    fun setArtwork(on: Boolean) = viewModelScope.launch { container.settings.updateMetadata { it.copy(showArtwork = on) } }

    fun setLanguage(tag: String?) = viewModelScope.launch { container.settings.updateMetadata { it.copy(language = tag) } }

    /** As Settings › Cache's "TMDB metadata" clears it. */
    fun clearCache() = viewModelScope.launch {
        saveOrLog("clear the cached metadata") { container.storedData.clearMetadata() }
        refreshCacheSize()
    }

    private fun refreshCacheSize() = viewModelScope.launch {
        cacheBytes.value = container.storedData.metadataBytes()
    }
}

/** [onPhoneSetup]: "Set up with your phone" for the TMDB key (a TV's; null shows none). */
@Composable
fun MetadataScreen(onBack: () -> Unit, onPhoneSetup: (() -> Unit)? = null) {
    val context = LocalContext.current
    val container = context.container
    val vm: MetadataViewModel = viewModel { MetadataViewModel(container) }
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val keyInput by vm.keyInput.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val statusOk by vm.statusOk.collectAsStateWithLifecycle()
    val cacheBytes by vm.cacheBytes.collectAsStateWithLifecycle()
    var noBrowser by remember { mutableStateOf(false) }
    val formFactor = LocalFormFactor.current

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        TopBar("Metadata", onBack)
        TvTypingTip(phoneBelow = onPhoneSetup != null)
        FormPhoneSetupRow(onPhoneSetup)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "With your own TMDB key the app can show posters, episode lists and air dates, and keep a collection of shows and movies. Your key is used only from this device.",
                style = AppType.secondary, color = colors.textSecondary,
            )
            FormField(
                "TMDB API key or read access token", keyInput, { vm.keyInput.value = it }, kind = FieldKind.Key, mono = true,
                placeholder = if (hasKey) "Saved · enter a new one to replace it" else null,
            )
            status?.let { ResultLine(statusOk, it) } ?: if (hasKey) ResultLine(true, "Key saved") else Unit
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Check and save", { vm.saveKey() }, enabled = keyInput.isNotBlank())
                TextButton(onClick = { noBrowser = !context.openExternalUrl(TMDB_KEY_HELP_URL, formFactor) }) {
                    Text("How to get a key ›", style = AppType.secondary, color = colors.accent)
                }
            }
            if (noBrowser) {
                val line = @Composable { ResultLine(false, "This device can't open web links. Visit $TMDB_KEY_HELP_URL in a browser elsewhere.") }
                // Selectable on a phone, to copy; a remote can't select, and the line would be a stop for its focus that shows nothing.
                if (formFactor == FormFactor.Tv) line() else SelectionContainer { line() }
            }
        }
        Column(Modifier.padding(top = 16.dp)) {
            GroupCard {
                val device = Locale.getDefault().toLanguageTag()
                SwitchRow(
                    "Use device language", prefs.language == null, { on -> vm.setLanguage(if (on) null else "en-US") },
                    detail = if (prefs.language == null) languageLabel(device) else "English",
                )
                InsetDivider()
                SwitchRow("Show posters and stills", prefs.showArtwork, vm::setArtwork)
                InsetDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Cached metadata", style = AppType.body, color = colors.text, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.clearCache() }) { Text("Clear ${io.skjaere.usenet.core.formatSize(cacheBytes)}", style = AppType.secondary, color = colors.text) }
                }
            }
        }
        Text(
            TMDB_ATTRIBUTION, style = AppType.small, color = colors.textSecondary,
            modifier = Modifier.padding(16.dp).fillMaxWidth().border(1.dp, colors.chip, RoundedCornerShape(14.dp)).padding(12.dp),
        )
        if (hasKey) TextButton(onClick = { vm.removeKey() }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Remove key", style = AppType.body, color = colors.warnStrong)
        }
    }
}
