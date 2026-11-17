package io.skjaere.usenet.app

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.nzbstreamer.nzb.NzbParser
import io.skjaere.usenet.app.download.DownloadEntry
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.app.download.DownloadLine
import io.skjaere.usenet.app.download.DownloadMarkIcon
import io.skjaere.usenet.app.download.DownloadRequest
import io.skjaere.usenet.app.download.DownloadSheet
import io.skjaere.usenet.app.download.DownloadUndoBar
import io.skjaere.usenet.app.download.PROGRESS_REDRAW_MILLIS
import io.skjaere.usenet.app.download.TitleDownload
import io.skjaere.usenet.app.download.TitleDownloadActions
import io.skjaere.usenet.app.download.TitleDownloads
import io.skjaere.usenet.app.download.atMostEvery
import io.skjaere.usenet.app.download.downloadsShown
import io.skjaere.usenet.app.download.undoTimeout
import io.skjaere.usenet.app.link.CastButton
import io.skjaere.usenet.app.link.SheetPlace
import io.skjaere.usenet.app.settings.NoUsableProvider
import io.skjaere.usenet.app.tv.TvMenu
import io.skjaere.usenet.app.tv.TvMenuItem
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FieldKind
import io.skjaere.usenet.app.ui.FormField
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.ResultLine
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.core.EngineState
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.NzbPreparer
import io.skjaere.usenet.core.NzbTooLargeException
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.core.checkNzbSize
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.core.readNzbBytes
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ImportUi {
    data object Idle : ImportUi
    /** [title] is null while the picked file is still being read. */
    data class Working(val title: String?) : ImportUi
    data class NeedsPassword(val title: String, val wrongPassword: Boolean) : ImportUi
    /** Opened while no Usenet provider is usable, for [reason]: it waits for one ([NzbImporter.retry]). */
    data class NeedsProvider(val title: String, val reason: NoUsableProvider) : ImportUi {
        /** Why the file can't be opened yet, and when it will be. */
        val text: String
            get() = reason.message + " " + when (reason) {
                // Import asks for a provider alone: never told a service is off.
                NoUsableProvider.NoneSaved, NoUsableProvider.ServiceOff -> "$title opens as soon as one is added."
                NoUsableProvider.AllOff -> "$title opens as soon as one is switched on."
                is NoUsableProvider.Unreadable -> "$title opens once ${if (reason.count == 1) "it is" else "one is"}."
            }
    }
    data class Ready(val nzb: PreparedNzb) : ImportUi {
        val files: List<FileCandidate> get() = nzb.candidates.sortedWith(compareByDescending<FileCandidate> { it.isVideo }.thenByDescending { it.size })
    }
    data class Error(val message: String) : ImportUi
}

/**
 * Opens a local .nzb with the saved provider(s): prepare, unlock if needed, pick a file. Picked
 * here, or opened in another app and handed over ([openHandedOver]). Without a usable provider the
 * file waits, and is prepared as soon as there is one. Its files' downloads (L454): their rows, the Download sheet's
 * file, and what their menus do, the host scheduled from here by [schedule] (the app's, or a test's seam).
 */
class ImportViewModel internal constructor(app: Application, schedule: (suspend (ScheduleCause) -> Unit)?) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, null)

    private val container = app.container
    private val importer = NzbImporter(viewModelScope) { bytes, title, password ->
        importWhenUsable(
            title, container.noUsableProvider(),
            // The engine takes up a provider a moment after it is saved (or after the app starts).
            awaitEngine = { withTimeoutOrNull(ENGINE_WAIT_MS) { container.engine.state.first { it != EngineState.NotConfigured } } },
            import = { importNzb(container.preparer, container.store, bytes, title, password) },
        )
    }
    val state: StateFlow<ImportUi> = importer.state

    private val _sheet = MutableStateFlow<DownloadRequest.File?>(null)

    /** The Download sheet's file (L455), from the file's Download until the sheet closes. */
    val sheet: StateFlow<DownloadRequest.File?> = _sheet.asStateFlow()

    /**
     * The shown NZB's downloads, by file (L454): the runner's rows of its files, at most four times a second as the writer
     * says each chunk, each finished one going tomorrow so marked (L415). None while no file is Ready.
     */
    val downloads: StateFlow<Map<Int, ImportDownload>> = combine(
        state, container.downloads.state.atMostEvery(PROGRESS_REDRAW_MILLIS), container.downloads.deletingTomorrow,
    ) { ui, items, deleting ->
        (ui as? ImportUi.Ready)?.let { ImportDownloads.of(it.nzb.id, it.nzb.files, items, deleting) }.orEmpty()
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * What the rows' Download buttons' menus do (L379, L469), as a title's page's entries do, but Download again: it queues
     * the failed file again with the NZB this screen holds, its copy written again.
     */
    val actions = TitleDownloadActions(
        TitleDownloadActions.Deps.importing(
            schedule?.let { TitleDownloadActions.Deps.of(container, it) } ?: TitleDownloadActions.Deps.of(container),
            request = ::again, enqueueImport = container.downloads::enqueueImport,
        ),
        viewModelScope, container.appScope,
    )

    init {
        // Added, switched on, switched off, deleted, or a password entered again: each is a change to the saved providers.
        viewModelScope.launch { container.settings.providers.collect { importer.providersChanged(container.noUsableProvider()) } }
    }

    fun open(uri: Uri) = importer.open {
        val app = getApplication<Application>()
        withContext(Dispatchers.IO) {
            val (displayName, size) = app.contentResolver.nameAndSize(uri)
            checkNzbSize(size)
            val name = displayName ?: uri.lastPathSegment ?: "NZB"
            val bytes = app.contentResolver.openInputStream(uri)?.use { it.readNzbBytes() } ?: error("Cannot read $uri")
            bytes to name.removeSuffix(".nzb")
        }
    }

    /** Opens the .nzb another app handed over as [id] ([NzbHandoff]), read already. */
    fun openHandedOver(id: String) = importer.open {
        container.nzbHandoff.take(id)?.getOrThrow()
            ?: throw UnreadableNzbException("That file is no longer open here. Open it again from the app you opened it in.")
    }

    fun unlock(password: String) = importer.unlock(password)

    fun reset() = importer.reset()

    /** [file]'s Download (L455): its sheet, with the NZB's bytes and the password it was opened with. */
    fun download(file: FileCandidate) {
        _sheet.value = request(file)
    }

    fun closeSheet() {
        _sheet.value = null
    }

    /** [file] of the NZB shown Ready, as its download asks for it; null while none is, or where it has no Download. */
    private fun request(file: FileCandidate): DownloadRequest.File? {
        val nzb = (state.value as? ImportUi.Ready)?.nzb ?: return null
        return DownloadRequest.File.of(nzb, file, importer.readyBytes(nzb.id) ?: return null)
    }

    /** What Download again queues for [row]: its file of the NZB shown Ready, if it is one of that NZB's. */
    private fun again(row: Download): DownloadRequest.File? {
        val ready = state.value as? ImportUi.Ready ?: return null
        return ImportDownloads.fileOf(row, ready.nzb.files)?.let(::request)
    }

    private companion object {
        const val ENGINE_WAIT_MS = 5_000L
    }
}

/** Prepares [bytes] (unlocking with [password]) and saves a ready NZB for the player; what the screen shows next. */
internal suspend fun importNzb(preparer: NzbPreparer, store: PreparedNzbStore, bytes: ByteArray, title: String, password: String?): ImportUi {
    val outcome = preparer.prepare(bytes, title, password)
    // Only when it failed, so a good NZB isn't parsed twice: was it one at all?
    if ((outcome is PrepareOutcome.Failed || outcome is PrepareOutcome.Unsupported) && !isNzb(bytes)) return ImportUi.Error("This file isn't a valid NZB.")
    return when (outcome) {
        is PrepareOutcome.Ready -> try {
            store.put(outcome.nzb, bytes)
            ImportUi.Ready(outcome.nzb)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Disk full or an I/O error: the player finds the NZB in the store, so it can't be opened.
            ImportUi.Error("Couldn't save the NZB: ${e.message}")
        }
        is PrepareOutcome.NeedsPassword -> ImportUi.NeedsPassword(title, outcome.passwordWasTried)
        is PrepareOutcome.MissingArticles -> ImportUi.Error("Articles are missing from your providers. ${outcome.message}")
        is PrepareOutcome.Unsupported -> ImportUi.Error(outcome.message)
        is PrepareOutcome.Failed -> ImportUi.Error(outcome.error.describe())
    }
}

/**
 * What Import shows for [title] once read: while no provider is usable ([missing]), why, at once
 * (the engine would never be ready, #159); otherwise what [import] makes of it once the engine has
 * had a moment to take the providers up ([awaitEngine]).
 */
internal suspend fun importWhenUsable(title: String, missing: NoUsableProvider?, awaitEngine: suspend () -> Unit, import: suspend () -> ImportUi): ImportUi {
    if (missing != null) return ImportUi.NeedsProvider(title, missing)
    awaitEngine()
    return import()
}

/** Whether [bytes] parse as an NZB listing at least one file. */
private suspend fun isNzb(bytes: ByteArray): Boolean = withContext(Dispatchers.Default) {
    try {
        NzbParser.parse(bytes).files.isNotEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}

/**
 * Import: opens the system file picker as it shows, or the .nzb another app [opened] ([NzbHandoff]
 * id), then prepares it and lists its files to play. Many TV builds have no picker (#121): the
 * screen then says how else a .nzb gets here. [onProviders] opens the providers list when the file
 * needs a usable provider first. A file tapped plays where Play goes from here (L147, W44 (i)): here, as always, with
 * This phone or a TV chosen, and on the Cast device connected; the Cast button in the bar says where (CsPhonePlaces).
 * A file downloaded plays its download's file there instead, offline too (L458).
 *
 * Where downloads show (W108, L454): each video's row says its download's state after its size, and ends
 * in a 48 dp Download button, none on what isn't a video nor on any file of a copy of a disc. Not started, it opens the
 * Download sheet for that file; started, a menu of what can be done with its download, Open Downloads ([onDownloads])
 * among it. A Remove's Undo bar shows at the foot.
 */
@Composable
fun ImportScreen(opened: String?, onBack: () -> Unit, onProviders: () -> Unit, onDownloads: (() -> Unit)? = null, vm: ImportViewModel = viewModel()) =
    ImportScreen(opened, onBack, onProviders, onDownloads, vm) { request, onClose -> DownloadSheet(request, onClose) }

/** Import over [vm], its files' Download sheet shown by [sheet]: the app's, or a test's over its own seams. */
@Composable
internal fun ImportScreen(
    opened: String?,
    onBack: () -> Unit,
    onProviders: () -> Unit,
    onDownloads: (() -> Unit)?,
    vm: ImportViewModel,
    sheet: @Composable (DownloadRequest.File, onClose: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val state by vm.state.collectAsStateWithLifecycle()
    val playOn by container.playOn.target.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(remember { ImportPicker.contract() }) { uri -> uri?.let(vm::open) }
    var noPicker by rememberSaveable { mutableStateOf(false) }
    val pick = {
        try {
            picker.launch(arrayOf("application/x-nzb", "application/xml", "text/xml", "application/octet-stream", "*/*"))
        } catch (e: ActivityNotFoundException) {
            noPicker = true
        }
    }
    // Once per visit: a recreation (rotation, dark mode, locale) mustn't pop the picker up again.
    var autoPicked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoPicked) {
            autoPicked = true
            if (opened != null) vm.openHandedOver(opened) else pick()
        }
    }

    // Nothing of downloads on a TV, or a phone's layout on one (W108).
    val shown = downloadsShown()
    val downloads = if (shown) vm.downloads.collectAsStateWithLifecycle().value else emptyMap()
    val keepFor = undoTimeout(vm.actions.undoMillis)
    var undoRoom by remember { mutableStateOf(0.dp) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Open NZB file", onBack) {
                // A file plays here with a TV chosen (W11): the list greys the TVs, and the face says this phone (W59).
                CastButton(playOn, { container.playOn.openSheet(SheetPlace.PhoneOnly) }, place = SheetPlace.PhoneOnly)
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val s = state) {
                    ImportUi.Idle -> if (noPicker) NoPicker() else PrimaryButton("Choose a .nzb file", pick)
                    is ImportUi.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = colors.accent)
                        Text(s.title?.let { "Opening $it…" } ?: "Reading the file…", style = AppType.body, color = colors.text)
                    }
                    is ImportUi.NeedsPassword -> {
                        var password by remember { mutableStateOf("") }
                        Text("${s.title} is password protected." + if (s.wrongPassword) " That password didn't work." else "", style = AppType.body, color = colors.text)
                        FormField("Archive password", password, { password = it }, kind = FieldKind.Password)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton("Unlock", { vm.unlock(password) }, enabled = password.isNotEmpty())
                            SecondaryButton("Cancel", vm::reset)
                        }
                    }
                    is ImportUi.Error -> {
                        ResultLine(false, s.message)
                        if (noPicker) NoPicker() else SecondaryButton("Choose another file", { vm.reset(); pick() })
                    }
                    is ImportUi.Ready -> Text(s.nzb.title, style = AppType.cardTitle, color = colors.text)
                    is ImportUi.NeedsProvider -> {
                        Text(s.text, style = AppType.body, color = colors.text)
                        PrimaryButton(NoUsableProvider.ACTION, onProviders)
                    }
                }
            }
            (state as? ImportUi.Ready)?.let { ready ->
                LazyColumn(Modifier.padding(top = 12.dp), contentPadding = PaddingValues(bottom = undoRoom)) {
                    item {
                        GroupCard {
                            ready.files.forEachIndexed { i, file ->
                                if (i > 0) InsetDivider()
                                val download = downloads[file.index]
                                val done = download?.row?.takeIf { it.state == DownloadState.Done }
                                ImportFileRow(
                                    file, download?.download, button = shown && ImportDownloads.offered(ready.nzb, file),
                                    onPlay = {
                                        container.playRouter.playFile(ready.nzb.id, file, done) {
                                            // Its finished download's file first, offline too (L458); else the NZB streams, as ever.
                                            val played = done != null && container.playback.playDownloaded(done.watchKey)
                                            if (!played) PlayerActivity.start(context, UsenetUris.of(ready.nzb.id, file), file.name)
                                        }
                                    },
                                    onDownload = { vm.download(file) },
                                    onEntry = { entry ->
                                        when (entry) {
                                            DownloadEntry.Download -> vm.download(file)
                                            DownloadEntry.OpenDownloads -> onDownloads?.invoke()
                                            else -> download?.let { vm.actions.take(entry, it.download, keepFor) }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (shown) DownloadUndoBar(vm.actions, Modifier.align(Alignment.BottomCenter)) { undoRoom = it }
    }
    vm.sheet.collectAsStateWithLifecycle().value?.let { request -> sheet(request, vm::closeSheet) }
}

/**
 * A file of the NZB: its name, its size with its download's state after it ([ImportDownloads.line]), a tap playing it
 * ([onPlay]); where it has one ([button]), the 48 dp Download button at its end, its mark 24 dp, read "Download <name>"
 * with its download's state. Not started it opens the sheet ([onDownload]); started, its download's menu, each item done
 * by [onEntry].
 */
@Composable
private fun ImportFileRow(
    file: FileCandidate,
    download: TitleDownload?,
    button: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onEntry: (DownloadEntry) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onPlay).padding(12.dp)) {
            Text(file.name, style = AppType.release, color = colors.text)
            DownloadLine(ImportDownloads.line(file, download))
        }
        if (button) {
            var menu by remember { mutableStateOf(false) }
            val spoken = download?.takeUnless { ImportDownloads.opensSheet(it) }?.let(TitleDownloads::spoken)
            Box(Modifier.padding(end = 4.dp)) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .clickable(role = Role.Button) { if (ImportDownloads.opensSheet(download)) onDownload() else menu = true }
                        .semantics {
                            contentDescription = ImportDownloads.label(file)
                            spoken?.let { stateDescription = it }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    DownloadMarkIcon(ImportDownloads.mark(download), size = 24.dp, offered = colors.accent)
                }
                if (download != null && !ImportDownloads.opensSheet(download)) {
                    TvMenu(menu, onDismiss = { menu = false }) { first ->
                        ImportDownloads.menu(download).forEachIndexed { i, entry ->
                            TvMenuItem(entry.label, if (i == 0) first else Modifier) {
                                menu = false
                                onEntry(entry)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Without a document picker: how else a .nzb gets here. */
@Composable
private fun NoPicker() {
    Text(
        "This device has no file picker. Open a .nzb from a file manager, or share it to this app, and it opens here.",
        style = AppType.body, color = colors.text,
    )
}

/** The document picker Import opens. */
internal object ImportPicker {
    /** Test seam: the picker's contract, which a test can make one no app answers, as on TV builds without a picker. */
    @VisibleForTesting
    @Volatile
    internal var contract: () -> ActivityResultContract<Array<String>, Uri?> = { ActivityResultContracts.OpenDocument() }
}

/** DISPLAY_NAME and SIZE of a picked document, each null when the provider doesn't report it (or for a file: URI). */
internal fun ContentResolver.nameAndSize(uri: Uri): Pair<String?, Long?> =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use null
        fun column(name: String) = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }
        column(OpenableColumns.DISPLAY_NAME)?.let(c::getString) to column(OpenableColumns.SIZE)?.let(c::getLong)
    } ?: (null to null)

/** What the import screen shows when the picked file can't be read. */
internal fun describeReadFailure(e: Exception): String = when (e) {
    is UnreadableNzbException -> e.message.orEmpty()
    is NzbTooLargeException -> "This file is too large to be an NZB (${e.size?.let(::formatSize) ?: "over ${formatSize(e.limit)}"})."
    else -> "Could not read the file: ${e.message}"
}

fun UsenetError.describe(): String = when (this) {
    is UsenetError.MissingArticle -> "An article is missing from every provider."
    is UsenetError.AuthFailed -> "The provider rejected the username or password."
    is UsenetError.ConnectionFailed -> "Could not connect to the provider. ${detail.orEmpty()}"
    is UsenetError.Timeout -> "The provider did not answer in time."
    is UsenetError.Corrupt -> "The download is corrupt. ${detail.orEmpty()}"
    is UsenetError.Protocol -> "The provider sent an unexpected response. ${detail.orEmpty()}"
    is UsenetError.Network -> "Network error. ${detail.orEmpty()}"
    UsenetError.NotConfigured -> "No Usenet provider is set up yet (Settings › Usenet providers)."
    is UsenetError.Unknown -> "Unexpected error. ${detail.orEmpty()}"
}.trim()
