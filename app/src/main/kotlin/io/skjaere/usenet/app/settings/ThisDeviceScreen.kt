package io.skjaere.usenet.app.settings

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.app.ui.FactRow
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.focusScale
import io.skjaere.usenet.app.ui.pagesThrough
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.player.DisplayDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which card of This device a node is, by its title: for tests, which find the one a remote's focus is on. */
val ThisDeviceCard = SemanticsPropertyKey<String>("ThisDeviceCard")
private var SemanticsPropertyReceiver.thisDeviceCard by ThisDeviceCard

/** Under the cards: where else the page's facts are. */
const val THIS_DEVICE_NOTE = "The same facts go into the log each time the app starts."

/** What This device reads, and what it shows of what has been read: the app's ([of]), or a test's. */
class ThisDeviceDeps(
    /** The facts as read so far ([AppContainer.thisDevice]): the page's refresh rate and layouts, and whether every read was tried. */
    val facts: (refreshRate: Float?, tvLayouts: Boolean, settled: Boolean) -> ThisDevice,
    /** The device's kind and its display's modes, kept for the header, and the refresh rate the display runs at now: a few cheap calls. */
    val readDisplay: () -> Float?,
    /** The display again, with the decoders (probed once a process, off the main thread). */
    val probe: suspend () -> Unit,
    /** The memory, which the start reads once (a call to another process): off the main thread. */
    val memory: suspend () -> Unit,
    /** rapidyenc's facts, which the start reads once, off the main thread. */
    val native: suspend () -> Unit,
    /** The default display's changes, while collected: its mode, a TV's HDR as it wakes, a receiver switched in. */
    val displayChanges: Flow<Unit>,
    /** The HDMI audio route's changes that counted (DeviceCapabilitiesLoader.audioChanges): a receiver switched on or off. */
    val audioChanges: Flow<Unit> = emptyFlow(),
) {
    companion object {
        fun of(container: AppContainer, context: Context) = ThisDeviceDeps(
            facts = container::thisDevice,
            readDisplay = {
                container.television
                container.readDisplayModes()
                DisplayDetails.read(context).refreshRate
            },
            probe = { container.device.get() },
            memory = { withContext(Dispatchers.IO) { container.memory } },
            native = { container.native.get() },
            displayChanges = displayChanges(context),
            audioChanges = container.device.audioChanges,
        )

        /** As File details' panel follows the display: DisplayManager's word that the default display changed, on the main thread. */
        private fun displayChanges(context: Context): Flow<Unit> {
            val displays = context.getSystemService(DisplayManager::class.java) ?: return emptyFlow()
            return callbackFlow {
                val changed = object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) = Unit

                    override fun onDisplayRemoved(displayId: Int) = Unit

                    override fun onDisplayChanged(displayId: Int) {
                        if (displayId == Display.DEFAULT_DISPLAY) trySend(Unit)
                    }
                }
                displays.registerDisplayListener(changed, Handler(Looper.getMainLooper()))
                awaitClose { displays.unregisterDisplayListener(changed) }
            }.buffer(Channel.CONFLATED)
        }
    }
}

/**
 * This device's facts as they are read. [tvLayouts]: whether the activity shows the TV's layouts, which a debug build's
 * launch extra can put on a phone.
 */
class ThisDeviceViewModel(private val deps: ThisDeviceDeps, private val tvLayouts: Boolean) : ViewModel() {
    private val _facts = MutableStateFlow(deps.facts(null, tvLayouts, false))
    val facts: StateFlow<ThisDevice> = _facts.asStateFlow()
    private var reading: Job? = null

    /** Read as it was made, and not resumed since: its first resume finds the facts just read. */
    private var fresh = true

    // As the page opens: its first resume comes only once the screens' cross-fade is over, 700 ms on.
    init {
        read()
    }

    /** The page is in front again: read again, as a box's output mode may have changed meanwhile; not on the first. */
    fun resumed() {
        if (fresh) fresh = false else read()
    }

    /**
     * While the page is in front, until cancelled: read again as the display or the HDMI audio route changes. A Shield's
     * TV says HDR10 without Dolby Vision for a few seconds after it wakes, which is often just as the page resumes; an
     * output mode can change under the page too, and a receiver switched on or off changes what passes through.
     */
    suspend fun followDisplay() {
        merge(deps.displayChanges, deps.audioChanges).collect { read() }
    }

    /**
     * Reads what the page shows, each shown as it comes: the display's modes and the rate it runs at (a few cheap calls,
     * here), the display again with the decoders (probed once a process, off the main thread), then the memory and
     * rapidyenc, which the start reads once and this waits for if it hasn't yet. Each read on its own: one that fails
     * leaves only its facts unread, not those after it. The container keeps what is read, so a shared log's header says
     * the same.
     */
    fun read() {
        reading?.cancel()
        reading = viewModelScope.launch {
            var rate: Float? = null
            fun show(settled: Boolean = _facts.value.settled) {
                _facts.value = deps.facts(rate, tvLayouts, settled)
            }
            saveOrLog("read the display") { rate = deps.readDisplay() }
            show()
            saveOrLog("probe the decoders") { deps.probe() }
            show()
            saveOrLog("read the device's memory") { deps.memory() }
            show()
            saveOrLog("read rapidyenc's facts") { deps.native() }
            show(settled = true)
        }
    }
}

/**
 * Settings › Playback › This device: what the device is, its display, the video and audio it decodes and how it decodes
 * yEnc, a card each, in the approved mockups' look (TdTvFull, TdPhoneFull), with File details' rows. It only reads, so
 * each card takes a remote's focus whole (the first, Device, once the facts are there, in the remote's input mode); Down
 * and Up go a card at a time, paging through one taller than the screen. Read as it opens, again on every return to it
 * (a box's output mode may have been changed in the system's settings meanwhile) and as the display changes while it
 * is in front. For TalkBack each card's title is a heading and each row is read as one. [deps]: for tests, fakes in
 * place of the app's.
 */
@Composable
fun ThisDeviceScreen(onBack: () -> Unit, deps: ThisDeviceDeps? = null) {
    val context = LocalContext.current
    val tvLayouts = LocalFormFactor.current == FormFactor.Tv
    val vm: ThisDeviceViewModel = viewModel { ThisDeviceViewModel(deps ?: ThisDeviceDeps.of(context.container, context.applicationContext), tvLayouts) }
    val facts by vm.facts.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.resumed()
            vm.followDisplay()
        }
    }
    val keys = LocalInputModeManager.current
    val first = remember { FocusRequester() }
    // Not before: a card's rows change height as their "…" are filled in.
    LaunchedEffect(facts.settled) { if (facts.settled && keys.inputMode == InputMode.Keyboard) first.tryFocus() }
    var room by remember { mutableIntStateOf(Int.MAX_VALUE) }
    Column(Modifier.fillMaxSize().onSizeChanged { room = it.height }.verticalScroll(rememberScrollState())) {
        TopBar("This device", onBack)
        facts.sections.forEachIndexed { i, section ->
            // Keyed by its title, as File details' sections are: a card whose rows change keeps the remote's focus.
            key(section.title) {
                SectionHeader(section.title, Modifier.semantics(mergeDescendants = true) { heading() })
                FactCard(section.title, room, first.takeIf { i == 0 }) {
                    section.rows.forEach { FactRow(it.label, it.value, it.note, listOfNotNull(it.warning), it.hdr, it.failed) }
                }
            }
        }
        Footnote(THIS_DEVICE_NOTE)
    }
}

/**
 * A card of facts, one stop for a remote's focus: grown a little and ringed in cream on a TV, ringed in the accent on a
 * phone, filled darker while it has the focus (TdTvOpen); never turned over, as its rows keep their colours. One taller
 * than the screen's [room] pages ([pagesThrough]).
 */
@Composable
private fun FactCard(title: String, room: Int, focus: FocusRequester?, content: @Composable ColumnScope.() -> Unit) {
    val tv = LocalFormFactor.current == FormFactor.Tv
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .semantics { thisDeviceCard = title }
            .pagesThrough(room)
            .focusScale(interaction, { it.rowScale })
            .clip(shape)
            .background(colors.surface)
            .focusOutline(shape, color = if (tv) colors.text else null)
            // Focusable without merging: TalkBack passes over the card to its rows (each a node of its own).
            .focusable(interactionSource = interaction)
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
        content = content,
    )
}
