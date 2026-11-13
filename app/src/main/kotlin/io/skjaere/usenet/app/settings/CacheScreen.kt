package io.skjaere.usenet.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.app.tv.searchKeyOpensSearch
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.SwitchRow
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.core.MIN_CACHE_BYTES
import io.skjaere.usenet.core.MIN_FREE_BYTES
import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.core.VideoCacheUsage
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.data.CachePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The settings hub's Cache row: "1.2 GB of 2 GB used", "Off", or that there is no room; empty until known. */
fun cacheSummary(usage: VideoCacheUsage?): String = when {
    usage == null -> ""
    !usage.on -> "Off"
    usage.limitBytes == 0L -> "No room on this device"
    else -> "${formatSize(usage.usedBytes)} of ${formatLimit(usage.limitBytes)} used"
}

/** The switch's line: what keeping video does; while a debrid service is on, that it keeps Usenet plays alone (L187). */
fun keepVideoDetail(serviceOn: Boolean): String =
    "Seeking back, playing again and resuming read it from here, not from your provider. Off deletes it." + if (serviceOn) " Keeps Usenet plays only." else ""

/** Why the video cache holds less than the limit chosen, or nothing; null when it doesn't. */
fun roomNote(usage: VideoCacheUsage): String? = when {
    !usage.short -> null
    usage.limitBytes == 0L -> "No room: nothing is kept while this device has under ${formatLimit(MIN_FREE_BYTES + MIN_CACHE_BYTES)} free."
    else -> "Only ${formatLimit(usage.limitBytes)} fits, leaving ${formatLimit(MIN_FREE_BYTES)} of this device's storage free."
}

/** A limit: "2 GB" when it is whole gigabytes, else as [formatSize] puts it. */
fun formatLimit(bytes: Long): String = if (bytes % CachePrefs.GB == 0L) "${bytes / CachePrefs.GB} GB" else formatSize(bytes)

/** "3 files · 120 KB", or "None". */
fun nzbFilesLine(files: PreparedNzbStore.StoredFiles): String =
    if (files.count == 0) "None" else "${files.count} file${if (files.count > 1) "s" else ""} · ${formatSize(files.bytes)}"

/** "12 releases", or "None". */
fun triedReleasesLine(count: Int): String = if (count == 0) "None" else "%,d release%s".format(count, if (count > 1) "s" else "")

/** The sizes of what Settings › Cache's "Stored data" lists, and how many releases were found incomplete. */
data class StoredSizes(val images: Long, val metadata: Long, val nzbs: PreparedNzbStore.StoredFiles, val triedReleases: Int)

class CacheViewModel(private val container: AppContainer) : ViewModel() {
    private val videoCache = container.videoCache
    private val stored = container.storedData

    /** What the video cache holds, as it changes (a play beside this screen, a Clear). */
    val usage: StateFlow<VideoCacheUsage?> = videoCache.usage().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), videoCache.usageNow())

    /** Null until read, so no limit shows chosen that isn't. */
    val prefs: StateFlow<CachePrefs?> = container.settings.cache.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether a debrid service account is on: its plays aren't kept (L187), which the switch then says. */
    val serviceOn: StateFlow<Boolean> = container.serviceOn.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _sizes = MutableStateFlow<StoredSizes?>(null)

    /** Null until measured. */
    val sizes: StateFlow<StoredSizes?> = _sizes.asStateFlow()

    init {
        // The room may be other than when the cache was last fitted to it.
        viewModelScope.launch { saveOrLog("fit the video cache to the room") { videoCache.refresh() } }
        viewModelScope.launch { measure() }
    }

    /** Off clears it: the app applies the setting as it is saved. */
    fun setKeep(on: Boolean) = viewModelScope.launch {
        saveOrLog("save the video cache setting") { container.settings.updateCache { it.copy(keepVideo = on) } }
    }

    fun setLimit(gb: Int) = viewModelScope.launch {
        saveOrLog("save the video cache limit") { container.settings.updateCache { it.copy(limitGb = gb) } }
    }

    fun clearVideo() = viewModelScope.launch { saveOrLog("clear the video cache") { videoCache.clear() } }

    fun clearImages() = clearing("clear the posters and images") { stored.clearImages() }

    fun clearMetadata() = clearing("clear the cached metadata") { stored.clearMetadata() }

    fun clearNzbFiles() = clearing("delete the NZB files kept for resume") { stored.clearNzbFiles() }

    fun clearTriedReleases() = clearing("forget the releases found incomplete") { stored.clearTriedReleases() }

    private fun clearing(what: String, clear: suspend () -> Unit) = viewModelScope.launch {
        saveOrLog(what, clear)
        measure()
    }

    private suspend fun measure() = saveOrLog("measure the stored data") {
        _sizes.value = StoredSizes(stored.imageBytes(), stored.metadataBytes(), stored.nzbFiles(), stored.triedReleases())
    }
}

/** What a Clear asks before it deletes. */
private enum class Confirm { Video, NzbFiles, TriedReleases }

/**
 * Settings › Playback › Cache: whether watched video is kept on the device, up to what limit, how
 * much it holds and Clear; then the other data the app stores, each with its size (or count) and Clear.
 */
@Composable
fun CacheScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    val vm: CacheViewModel = viewModel { CacheViewModel(container) }
    val usage by vm.usage.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val sizes by vm.sizes.collectAsStateWithLifecycle()
    val serviceOn by vm.serviceOn.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf<Confirm?>(null) }
    val keys = LocalInputModeManager.current
    val first = remember { FocusRequester() }
    // A remote starts on the switch, the screen's first setting, rather than on Back.
    LaunchedEffect(prefs != null) {
        if (prefs != null && keys.inputMode == InputMode.Keyboard) runCatching { first.requestFocus() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Cache", onBack)
        val p = prefs
        if (p != null) {
            GroupCard {
                SwitchRow(
                    "Keep watched video on this device", p.keepVideo, vm::setKeep,
                    detail = keepVideoDetail(serviceOn),
                    modifier = Modifier.focusRequester(first).focusOutline(),
                )
            }
            val u = usage
            if (p.keepVideo && u != null && u.on) VideoUsage(u, p.limitGb, vm::setLimit, onClear = { confirm = Confirm.Video })
        }

        SectionHeader("Stored data")
        GroupCard {
            val s = sizes
            StoredRow("Posters and images", s?.images?.let(::formatSize), "Clear posters and images", vm::clearImages)
            InsetDivider()
            StoredRow("TMDB metadata", s?.metadata?.let(::formatSize), "Clear TMDB metadata", vm::clearMetadata)
            InsetDivider()
            StoredRow(
                "NZB files kept for resume", s?.nzbs?.let(::nzbFilesLine), "Clear the NZB files kept for resume",
                onClear = { confirm = Confirm.NzbFiles },
                detail = "Resume fetches them from the indexer again, one grab each.",
            )
            InsetDivider()
            StoredRow(
                "Releases found incomplete", s?.triedReleases?.let(::triedReleasesLine), "Clear the releases found incomplete",
                onClear = { confirm = Confirm.TriedReleases },
                detail = "Plays skip them without a grab for 30 days, or until you add a provider they weren't checked on.",
            )
        }
        Footnote("Android may delete cached video, posters and metadata when the device runs low on storage.")
    }

    when (confirm) {
        Confirm.Video -> ConfirmDialog(
            title = "Clear the video cache?",
            text = "Deletes the ${usage?.usedBytes?.let(::formatSize) ?: "video"} of video kept on this device. Anything playing goes on, fetching from your provider again.",
            action = "Clear",
            onConfirm = { confirm = null; vm.clearVideo() },
            onDismiss = { confirm = null },
        )
        Confirm.NzbFiles -> ConfirmDialog(
            title = "Delete the NZB files kept for resume?",
            text = "Resume fetches them from the indexer again, one grab each.",
            action = "Delete",
            onConfirm = { confirm = null; vm.clearNzbFiles() },
            onDismiss = { confirm = null },
        )
        Confirm.TriedReleases -> ConfirmDialog(
            title = "Forget the releases found incomplete?",
            text = "Plays grab and check them again, one grab each.",
            action = "Clear",
            onConfirm = { confirm = null; vm.clearTriedReleases() },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }
}

/** How much the video cache holds of its limit, as a line and a bar, why it holds less when it does; the limits; Clear. */
@Composable
private fun VideoUsage(usage: VideoCacheUsage, limitGb: Int, onLimit: (Int) -> Unit, onClear: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).padding(top = 14.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(cacheSummary(usage), style = AppType.bodyStrong, color = colors.text)
            val fraction = if (usage.limitBytes > 0) (usage.usedBytes.toFloat() / usage.limitBytes).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = colors.accent, trackColor = colors.chip, gapSize = 0.dp, drawStopIndicator = {},
            )
            roomNote(usage)?.let { Text(it, style = AppType.small, color = colors.warn) }
        }
        SecondaryButton(
            "Clear", onClear,
            Modifier.focusOutline(RoundedCornerShape(24.dp), fill = false).semantics { contentDescription = "Clear the video cache" },
        )
    }

    SectionHeader("Size limit")
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CachePrefs.LIMITS_GB.forEach { gb ->
            val selected = limitGb == gb
            Column(
                Modifier.weight(1f).heightIn(min = 56.dp)
                    // The focus outline in the text colour, so the chosen limit's accent border doesn't hide it.
                    .focusOutline(color = colors.text, fill = false)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) colors.accentTint else colors.surface)
                    .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.chip, RoundedCornerShape(12.dp))
                    .selectable(selected, role = Role.RadioButton) { onLimit(gb) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(formatLimit(gb * CachePrefs.GB), style = if (selected) AppType.bodyStrong else AppType.body, color = colors.text, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A kind of stored data: what it is, its [size] (null until measured), an optional [detail], and Clear (read out as [clearLabel]). */
@Composable
private fun StoredRow(title: String, size: String?, clearLabel: String, onClear: () -> Unit, detail: String? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AppType.body, color = colors.text)
            Text(size ?: "…", style = AppType.secondary, color = colors.muted)
            detail?.let { Text(it, style = AppType.small, color = colors.muted) }
        }
        SecondaryButton(
            "Clear", onClear,
            Modifier.focusOutline(RoundedCornerShape(24.dp), fill = false).semantics { contentDescription = clearLabel },
        )
    }
}

/** Asks before a Clear or Delete: [action] in the warn colour, and Cancel. */
@Composable
internal fun ConfirmDialog(title: String, text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.searchKeyOpensSearch(onDismiss),
        containerColor = colors.surface,
        title = { Text(title, style = AppType.cardTitle, color = colors.text) },
        text = { Text(text, style = AppType.body, color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(action, style = AppType.bodyStrong, color = colors.warnStrong) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = AppType.body, color = colors.text) }
        },
    )
}
