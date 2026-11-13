package io.skjaere.usenet.app.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.BuildConfig
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.NavRow
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.SwitchRow
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.logging.LogLine
import io.skjaere.usenet.logging.levelLetter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val log = LoggerFactory.getLogger(LogsViewModel::class.java)

/** What the Logs screen lists: the [lines] its filter shows, of [total] kept. A new one for each snapshot of the log. */
class LogView(val lines: List<LogLine>, val total: Int)

/** Where a remote's focus goes on the Logs screen: Detailed logging's switch, or the last crash's row. */
enum class LogsFocus { Switch, Crash }

class LogsViewModel(private val container: AppContainer, private val app: Context) : ViewModel() {
    private val _filter = MutableStateFlow(LogFilter())
    val filter: StateFlow<LogFilter> = _filter.asStateFlow()

    /**
     * The lines [filter] shows, oldest first: a snapshot of the log at most every [REFRESH_MS] however
     * fast lines come, filtered off the main thread.
     */
    val view: StateFlow<LogView?> = combine(LogBuffer.version.latestEvery(REFRESH_MS), _filter) { _, filter ->
        val all = LogBuffer.snapshot()
        LogView(filter.apply(all), all.size)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Null until the settings are read. */
    val detailed: StateFlow<Boolean?> = container.settings.logs.map { it.isDetailed(BuildConfig.DEBUG) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _crashAt = MutableStateFlow<Long?>(null)

    /** When the last crash's log was written; null while none is kept. */
    val crashAt: StateFlow<Long?> = _crashAt.asStateFlow()

    private val _canShare = MutableStateFlow(false)
    val canShare: StateFlow<Boolean> = _canShare.asStateFlow()

    init {
        viewModelScope.launch { saveOrLog("see whether an app takes a shared log") { _canShare.value = LogExport.canShare(app) } }
    }

    private val _refocus = MutableStateFlow<LogsFocus?>(LogsFocus.Switch)

    /**
     * Where a remote's focus goes, until [refocused]: to the switch as the screen first shows; back
     * from the last crash's log, to its row again, or to the switch once it is deleted there (S11-16).
     */
    val refocus: StateFlow<LogsFocus?> = _refocus.asStateFlow()

    /** Whether the last crash's log opened over the screen, which gives its row the focus back on its return. */
    private var crashOpened = false

    fun crashOpened() {
        crashOpened = true
    }

    /** Each time the screen shows: the last crash's log may have been deleted on its own screen. */
    fun shown() {
        val back = crashOpened
        crashOpened = false
        viewModelScope.launch {
            saveOrLog("look for the last crash's log") { _crashAt.value = container.crashLog.writtenAt() }
            if (back) _refocus.value = if (_crashAt.value != null) LogsFocus.Crash else LogsFocus.Switch
        }
    }

    fun refocused() {
        _refocus.value = null
    }

    /** The app applies it as it is saved. */
    fun setDetailed(on: Boolean) = viewModelScope.launch {
        saveOrLog("save the logging setting") { container.settings.updateLogs { it.copy(detailed = on) } }
    }

    fun setWarningsAndErrors(on: Boolean) = _filter.update { it.copy(warningsAndErrors = on) }

    fun setText(text: String) = _filter.update { it.copy(text = text) }

    fun clear() = LogBuffer.clear()

    /** The lines shown, as Copy (at most [maxChars]) and Share hand them over. */
    suspend fun text(maxChars: Int = Int.MAX_VALUE): String = withContext(Dispatchers.Default) {
        logText(container.logHeader, _filter.value.apply(LogBuffer.snapshot()), maxChars)
    }

    suspend fun shareIntent(): Intent = LogExport.shareIntent(app, "log", text())

    private companion object {
        const val REFRESH_MS = 250L
    }
}

/** The latest value at most every [ms]: the first at once, then the latest after each pause. */
internal fun <T> Flow<T>.latestEvery(ms: Long): Flow<T> = conflate().transform {
    emit(it)
    delay(ms)
}

private val crashStamp = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/** "16 Oct, 14:02": when the last crash's log was written. */
internal fun crashTime(millis: Long): String = crashStamp.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/**
 * Settings › App › Logs: the app's recent log lines, newest at the bottom and followed while the list
 * shows its end; which are shown; Detailed logging; Copy, Share and Clear; and the log of the last
 * crash when one is kept.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogsScreen(onBack: () -> Unit, onCrashLog: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val vm: LogsViewModel = viewModel { LogsViewModel(container, context.applicationContext) }
    val view by vm.view.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val detailed by vm.detailed.collectAsStateWithLifecycle()
    val crashAt by vm.crashAt.collectAsStateWithLifecycle()
    val canShare by vm.canShare.collectAsStateWithLifecycle()
    val refocus by vm.refocus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val keys = LocalInputModeManager.current
    val first = remember { FocusRequester() }
    val crash = remember { FocusRequester() }
    val actions = remember { FocusRequester() }
    // Composed again on the way back from the last crash's log, which may have been deleted there.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.shown() }

    Column(Modifier.fillMaxSize()) {
        TopBar("Logs", onBack)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // The controls scroll within this much at large font sizes, so the lines always have room.
            val controlsMax = maxHeight * 0.6f
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.heightIn(max = controlsMax).verticalScroll(rememberScrollState())) {
                    val on = detailed
                    if (on != null) {
                        // A remote starts on the switch, the screen's setting, rather than on Back; back from the
                        // crash log, on its row again rather than on the switch, which OK would turn (S11-16). Here, in
                        // the constraints' own composition, which comes after the screen's: the rows are there by then.
                        LaunchedEffect(refocus) {
                            val to = refocus ?: return@LaunchedEffect
                            if (keys.inputMode == InputMode.Keyboard) runCatching { (if (to == LogsFocus.Crash) crash else first).requestFocus() }
                            vm.refocused()
                        }
                        GroupCard {
                            SwitchRow(
                                "Detailed logging", on, vm::setDetailed,
                                detail = "Keeps debug lines too, for tracking down a problem.",
                                modifier = Modifier.focusRequester(first).focusOutline(),
                            )
                            crashAt?.let { at ->
                                InsetDivider()
                                NavRow(
                                    "Log of the last crash", crashTime(at), onClick = { vm.crashOpened(); onCrashLog() },
                                    modifier = Modifier.focusRequester(crash).focusOutline(),
                                )
                            }
                        }
                    }
                    SectionHeader("Recent lines")
                    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("All", !filter.warningsAndErrors) { vm.setWarningsAndErrors(false) }
                        Choice("Warnings and errors", filter.warningsAndErrors) { vm.setWarningsAndErrors(true) }
                    }
                    FilterField(filter.text, vm::setText, Modifier.padding(horizontal = 16.dp).padding(top = 10.dp).fillMaxWidth())
                    FlowRow(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ActionButton("Copy", "Copy the log", Modifier.focusRequester(actions)) {
                            scope.launch { exporting(context, "copy the log") { LogExport.copy(context, vm.text(LogExport.CLIPBOARD_CHARS)) } }
                        }
                        if (canShare) {
                            ActionButton("Share", "Share the log") {
                                scope.launch { exporting(context, "share the log") { context.startActivity(vm.shareIntent()) } }
                            }
                        }
                        ActionButton("Clear", "Clear the log", onClick = vm::clear)
                    }
                    Footnote(LOG_CONTENTS_NOTE)
                    val shown = view?.lines?.size
                    if (filter.narrows && shown != null) Footnote(shownNote(shown, canShare))
                }
                LogLines(view, Modifier.weight(1f).backToControls(actions))
            }
        }
    }
}

/** One of the level choices, a radio button. */
@Composable
private fun RowScope.Choice(text: String, selected: Boolean, onSelect: () -> Unit) {
    Box(
        Modifier.weight(1f).heightIn(min = 48.dp)
            // The focus outline in the text colour, so the chosen one's accent border doesn't hide it.
            .focusOutline(color = colors.text, fill = false)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.accentTint else colors.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.chip, RoundedCornerShape(12.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = if (selected) AppType.bodyStrong else AppType.body, color = colors.text, textAlign = TextAlign.Center)
    }
}

/**
 * The text the lines must hold. A remote's up and down leave it rather than move the cursor, as do
 * a keyboard's; the keyboard comes up on a tap, or on OK with a remote, not as the remote passes by.
 * TalkBack names it as its placeholder does, which goes once there is text.
 */
@Composable
private fun FilterField(text: String, onChange: (String) -> Unit, modifier: Modifier) {
    val focus = LocalFocusManager.current
    val touch = LocalInputModeManager.current.inputMode == InputMode.Touch
    val name = "Filter the lines"
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        singleLine = true,
        textStyle = AppType.body.copy(color = colors.text),
        placeholder = { Text(name, style = AppType.body, color = colors.muted) },
        keyboardOptions = KeyboardOptions(showKeyboardOnFocus = touch),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surface, unfocusedContainerColor = colors.surface,
            focusedBorderColor = colors.accent, unfocusedBorderColor = colors.chip, cursorColor = colors.accent,
        ),
        modifier = modifier.semantics { contentDescription = name }.onPreviewKeyEvent { event ->
            event.type == KeyEventType.KeyDown && when (event.key) {
                Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
                Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                else -> false
            }
        },
    )
}

@Composable
private fun ActionButton(text: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    SecondaryButton(text, onClick, modifier.focusOutline(RoundedCornerShape(24.dp), fill = false).semantics { contentDescription = label })
}

/**
 * On a list of lines, which a remote moves through one at a time, thousands of presses from the
 * controls above at worst: while a line has the focus, Back takes the remote to [controls] rather than
 * leaving the screen.
 */
private fun Modifier.backToControls(controls: FocusRequester): Modifier = composed {
    var linesFocused by remember { mutableStateOf(false) }
    val keys = LocalInputModeManager.current
    BackHandler(enabled = linesFocused && keys.inputMode == InputMode.Keyboard) { runCatching { controls.requestFocus() } }
    onFocusChanged { linesFocused = it.hasFocus }
}

/**
 * Runs [export], a Copy or Share the user asked for; a failure (no room for the file, no app to share
 * with after all, a clipboard that refuses it) is said, as "Couldn't [what]", and logged.
 */
private suspend fun exporting(context: Context, what: String, export: suspend () -> Unit) {
    try {
        export()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Couldn't {}", what, e)
        Toast.makeText(context, "Couldn't $what", Toast.LENGTH_SHORT).show()
    }
}

/** The lines shown, oldest at the top, following new ones while the newest shows; a remote moves a line at a time. */
@Composable
private fun LogLines(view: LogView?, modifier: Modifier) {
    Box(modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface)) {
        val lines = view?.lines
        when {
            lines == null -> Unit
            lines.isEmpty() -> Text(
                if (view.total == 0) "Nothing logged yet." else "No lines match.",
                style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(16.dp),
            )
            else -> {
                // Laid out from the bottom, newest first: the newest stays at the bottom as the controls
                // above take more or less room, and older lines dropping off the top move nothing.
                val newestFirst = remember(view) { lines.asReversed() }
                val state = rememberLazyListState()
                // Whether the newest showed before these lines came (the list is still as it was): then it follows them.
                val follow = remember(view) { Snapshot.withoutReadObservation { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 } }
                LaunchedEffect(view) { if (follow) state.scrollToItem(0) }
                LazyColumn(
                    Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(vertical = 6.dp),
                    reverseLayout = true, verticalArrangement = Arrangement.Top,
                ) {
                    items(newestFirst, key = { it.seq }) { line ->
                        val time = clockTime(line.timeMillis)
                        val tint = levelColor(line.level)
                        LineText(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = colors.muted)) { append("$time ") }
                                withStyle(SpanStyle(color = tint ?: colors.muted)) { append(levelLetter(line.level)) }
                                withStyle(SpanStyle(color = colors.muted)) { append(" ${line.tag} ") }
                                append(line.message)
                            },
                            color = tint ?: colors.textSecondary,
                            description = "${levelName(line.level)}, $time, ${line.tag}: ${line.message}",
                        )
                    }
                }
            }
        }
    }
}

/** The warn colours for warnings and errors; null for the rest. */
@Composable
private fun levelColor(level: Level): Color? = when (level) {
    Level.ERROR -> colors.warnStrong
    Level.WARN -> colors.warn
    else -> null
}

private fun levelName(level: Level): String = when (level) {
    Level.TRACE -> "Trace"
    Level.DEBUG -> "Debug"
    Level.INFO -> "Info"
    Level.WARN -> "Warning"
    Level.ERROR -> "Error"
}

/** A line of a log, in the monospace font, wrapped; it takes the focus, so a remote scrolls a line at a time. */
@Composable
private fun LineText(text: androidx.compose.ui.text.AnnotatedString, color: Color, description: String? = null) {
    Text(
        text,
        style = AppType.release,
        color = color,
        modifier = Modifier.fillMaxWidth()
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .focusOutline(RoundedCornerShape(6.dp))
            .focusable()
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** The log of the last crash: the lines a line each, or that there is none; null while it is read. */
sealed interface CrashLogState {
    class Kept(val writtenAt: Long, val text: String) : CrashLogState {
        val lines: List<String> = text.lines().dropLastWhile { it.isEmpty() }
    }

    data object None : CrashLogState
}

class CrashLogViewModel(private val container: AppContainer, private val app: Context) : ViewModel() {
    private val _state = MutableStateFlow<CrashLogState?>(null)
    val state: StateFlow<CrashLogState?> = _state.asStateFlow()

    private val _canShare = MutableStateFlow(false)
    val canShare: StateFlow<Boolean> = _canShare.asStateFlow()

    init {
        viewModelScope.launch { saveOrLog("see whether an app takes a shared log") { _canShare.value = LogExport.canShare(app) } }
        viewModelScope.launch {
            saveOrLog("read the last crash's log") {
                val crash = container.crashLog
                val text = crash.read()
                val at = crash.writtenAt()
                // Split into lines off the main thread: it may be some hundred kilobytes.
                _state.value = if (text != null && at != null) withContext(Dispatchers.Default) { CrashLogState.Kept(at, text) } else CrashLogState.None
            }
        }
    }

    fun delete() = viewModelScope.launch {
        saveOrLog("delete the last crash's log") {
            container.crashLog.delete()
            _state.value = CrashLogState.None
        }
    }

    suspend fun shareIntent(): Intent {
        val kept = _state.value as? CrashLogState.Kept ?: error("No crash log is kept")
        return LogExport.shareIntent(app, "crash log", kept.text)
    }
}

/** The line's level letter where the line is a log line ("… 14:02:31.123 W Tag: …"); null for the rest (the stack trace). */
private val logLevel = Regex("""^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} ([VDIWE]) """)

/** The log of the last crash, kept until deleted here: its lines, Share and Delete. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CrashLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val vm: CrashLogViewModel = viewModel { CrashLogViewModel(container, context.applicationContext) }
    val state by vm.state.collectAsStateWithLifecycle()
    val canShare by vm.canShare.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirm by rememberSaveable { mutableStateOf(false) }
    val keys = LocalInputModeManager.current
    val first = remember { FocusRequester() }
    val kept = state as? CrashLogState.Kept
    // A remote starts on the first action rather than on Back.
    LaunchedEffect(kept != null) {
        if (kept != null && keys.inputMode == InputMode.Keyboard) runCatching { first.requestFocus() }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Log of the last crash", onBack, subtitle = kept?.let { crashTime(it.writtenAt) })
        when (val shown = state) {
            null -> Unit
            CrashLogState.None -> Footnote("No crash log is kept.")
            is CrashLogState.Kept -> {
                val lines = shown.lines
                FlowRow(
                    Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (canShare) {
                        SecondaryButton(
                            "Share", { scope.launch { exporting(context, "share the crash log") { context.startActivity(vm.shareIntent()) } } },
                            Modifier.focusRequester(first).focusOutline(RoundedCornerShape(24.dp), fill = false).semantics { contentDescription = "Share the log of the last crash" },
                        )
                    }
                    SecondaryButton(
                        "Delete", { confirm = true },
                        (if (canShare) Modifier else Modifier.focusRequester(first))
                            .focusOutline(RoundedCornerShape(24.dp), fill = false).semantics { contentDescription = "Delete the log of the last crash" },
                    )
                }
                Footnote(LOG_CONTENTS_NOTE)
                Box(Modifier.weight(1f).backToControls(first).padding(horizontal = 16.dp).padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface)) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(lines.size) { i ->
                            val line = lines[i]
                            val tint = when (logLevel.find(line)?.groupValues?.get(1)) {
                                "E" -> colors.warnStrong
                                "W" -> colors.warn
                                else -> colors.textSecondary
                            }
                            LineText(buildAnnotatedString { append(line.replace("\t", "    ")) }, tint)
                        }
                    }
                }
            }
        }
    }

    if (confirm) {
        ConfirmDialog(
            title = "Delete the log of the last crash?",
            text = "It can't be shared once it's deleted.",
            action = "Delete",
            onConfirm = { confirm = false; vm.delete() },
            onDismiss = { confirm = false },
        )
    }
}
