package io.skjaere.usenet.app.play

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.preferKeepClear
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.TvSafeArea
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.pagesThrough
import io.skjaere.usenet.app.ui.tryFocus
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.ServiceSource
import io.skjaere.usenet.app.search.SourceTagChip
import io.skjaere.usenet.app.casting.CAST_STARTING
import io.skjaere.usenet.app.casting.CastAction
import io.skjaere.usenet.app.casting.CastFailed
import io.skjaere.usenet.app.casting.CastLine
import io.skjaere.usenet.app.casting.CastPrecheck
import io.skjaere.usenet.app.casting.CastStart
import io.skjaere.usenet.app.casting.castingTo
import io.skjaere.usenet.app.casting.couldNotPlayOn
import io.skjaere.usenet.app.casting.onDevice
import io.skjaere.usenet.app.casting.startingOn
import io.skjaere.usenet.app.link.CastConnectedIcon
import io.skjaere.usenet.app.link.CastIcon
import io.skjaere.usenet.app.link.SoundIcon
import io.skjaere.usenet.app.link.SubtitlesIcon
import io.skjaere.usenet.app.searchRoute
import io.skjaere.usenet.app.link.PhoneIcon
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.TvMiniBar
import io.skjaere.usenet.app.link.TvPlaySheet
import io.skjaere.usenet.app.link.RemotePlaying
import io.skjaere.usenet.app.link.RemotePlayingBarHost
import io.skjaere.usenet.app.link.TvStart
import io.skjaere.usenet.app.settings.NoUsableProvider
import io.skjaere.usenet.app.setup.PHONE_SETUP_TITLE
import io.skjaere.usenet.app.tv.searchKeyOpensSearch
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.ChipKind
import io.skjaere.usenet.app.ui.ChipRow
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InfoChip
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.core.UsenetSource
import io.skjaere.usenet.core.formatSize
import kotlinx.coroutines.flow.first

internal val STEP_LABELS = mapOf(
    // One step for whatever a debrid service needs before a link, whichever service it is (L203, W80).
    PipelineStep.Prepare to "Preparing",
    PipelineStep.FetchNzb to "Fetching NZB",
    PipelineStep.CheckAvailability to "Checking availability",
    PipelineStep.Open to "Opening",
    PipelineStep.ProbeTracks to "Checking audio and video",
)

/**
 * The steps a search's sheet lists, before a release is tried (L203): Usenet's while a provider is usable (or nothing is,
 * as before routes), else a debrid service's, which is all a user with a service account and no provider will see.
 */
internal fun searchingSteps(routes: UsableRoutes): List<PipelineStep> =
    if (!routes.any || PlayCandidate.USENET in routes.routes) UsenetSource.STEPS else ServiceSource.STEPS

/** The running sheet's step rows: the release's source's steps, in their order, each with how it went, if it has started. */
internal fun stepRows(s: PlayState.Running): List<Pair<String, StepStatus?>> = s.sourceSteps.map { STEP_LABELS.getValue(it) to s.steps[it] }

/** The line over the release a play tries: "Release 2 of 3", or "Release" when it is the only one; its source's tag beside it. */
internal fun releaseLine(s: PlayState.Running): String = if (s.of > 1) "Release ${s.attempt} of ${s.of}" else "Release"

/**
 * What TalkBack reads of a step's row: its name, then how it went ("Preparing, in progress", "Fetching NZB, done"), so the
 * marks beside it aren't read as symbols, and the step names no service (L214).
 */
internal fun stepSpoken(label: String, status: StepStatus?): String = when (status) {
    null -> label
    is StepStatus.Running -> listOfNotNull(label, "in progress", status.total?.let { "${status.done ?: 0} of $it" }).joinToString(", ")
    is StepStatus.Done -> "$label, done"
    is StepStatus.Failed -> "$label, failed: ${status.failure.text}"
}

/**
 * The "Starting playback" sheet, shown over any screen while the coordinator is working or has
 * failed. Tapping outside or swiping it down only minimises it to [PlayMiniBar]; Cancel stops.
 * [onProviders] opens the providers list, when the play failed for want of a usable provider, and [onServices] Debrid
 * services, when it failed as the debrid service is off (null while debrid is hidden). A TV's sheet for a phone's play it
 * can't start yet opens the page for what it lacks: [onAddService] and [onAddTorrentIndexer] too, also null while debrid
 * is hidden. On a phone, a play started on a TV ([tv], L37) shows in its place, the same sheet fed by the TV
 * ([TvPlaySheet]).
 */
@Composable
fun PlaySheetHost(
    coordinator: PlaybackCoordinator,
    onSearch: (SearchRoute) -> Unit,
    onProviders: () -> Unit,
    onPhoneSetup: () -> Unit = {},
    onAddProvider: () -> Unit = {},
    onAddIndexer: () -> Unit = {},
    tv: TvStart? = null,
    onServices: (() -> Unit)? = null,
    onAddService: (() -> Unit)? = null,
    onAddTorrentIndexer: (() -> Unit)? = null,
) {
    if (tv != null) {
        val onTv by tv.now.collectAsStateWithLifecycle()
        onTv?.let { now ->
            if (now.expanded) TvPlaySheet(now, onMinimize = tv::minimize, onCancel = tv::cancel, onClose = tv::close)
            return
        }
    }
    val state by coordinator.state.collectAsStateWithLifecycle()
    val expanded by coordinator.expanded.collectAsStateWithLifecycle()
    val startedFrom by coordinator.startedFrom.collectAsStateWithLifecycle()
    val castTo by coordinator.castTo.collectAsStateWithLifecycle()
    val castGoesOn by coordinator.castGoesOn.collectAsStateWithLifecycle()
    val searchSteps by coordinator.searchSteps.collectAsStateWithLifecycle()
    // Where Play goes now: a cast's failure offers its device more only while it is the one chosen, connected (W36).
    val target by LocalContext.current.container.playOn.target.collectAsStateWithLifecycle()
    if (!expanded) return
    PlaySheet(
        state, onMinimize = coordinator::minimize, onCancel = coordinator::cancel, onDismiss = coordinator::dismiss,
        onSearch = { route -> coordinator.dismiss(); onSearch(route) },
        onProviders = { coordinator.dismiss(); onProviders() },
        onChooseFile = coordinator::chooseFile,
        onNotInRelease = coordinator::notInRelease,
        startedFrom = startedFrom,
        onPhoneSetup = { coordinator.dismiss(); onPhoneSetup() },
        onAddProvider = { coordinator.dismiss(); onAddProvider() },
        onAddIndexer = { coordinator.dismiss(); onAddIndexer() },
        castTo = castTo,
        castTarget = target,
        onCast = coordinator::act,
        castGoesOn = castGoesOn,
        onPrecheck = coordinator::precheck,
        searchSteps = searchSteps,
        onServices = onServices?.let { open -> { coordinator.dismiss(); open() } },
        onAddService = onAddService?.let { open -> { coordinator.dismiss(); open() } },
        onAddTorrentIndexer = onAddTorrentIndexer?.let { open -> { coordinator.dismiss(); open() } },
    )
}

/**
 * The sheet for [state]: [onMinimize] as it is tapped outside, swiped down or left with Back,
 * [onCancel] stops the play, [onDismiss] closes a sheet that has nothing more to do. [onSearch]
 * and [onProviders] are the failures' ways on, which also close the sheet. [onChooseFile] answers
 * the question of which file plays ([PlayState.ChoosingFile]), which the play waits on, and
 * [onNotInRelease] that none does, where nothing told: leaving that sheet in any way stops the
 * play instead.
 *
 * With a remote, an OK just after the sheet opened is the user's second press on what started the play: [okGuard] keeps
 * it off the sheet. On a TV, where nothing is tapped outside, Searching and Running offer Hide beside Cancel, focused
 * first, and the other sheets focus their way on; the remote's search key hides the sheet and opens Search. A play a
 * paired phone asked for ([startedFrom]) says so under its title (PtTvIncoming). One the TV can't start yet offers
 * [onPhoneSetup] and the page for what it lacks ([notReadyButtons]: [onAddProvider], [onAddIndexer], [onAddService],
 * [onServices] or [onAddTorrentIndexer]; PtTvNotReady, D2TvNotReady). A play cast to a Google Cast device ([castTo])
 * says so under its title, and ends on the device's own step; its failures there have their own sheet, whose buttons
 * ask [onCast] for what they say (CsPhoneStarting), the device's own only while it is still where Play goes, connected
 * ([castTarget]). One started from the missing-parts prompt or the slow-stream banner during a cast says under that
 * what goes on there meanwhile ([castGoesOn]; CsPhoneMissingParts 3, CsPhoneSlowCast 3). A release picked by hand that
 * its Cast device can't fully play asks first ([PlayState.Prechecking], CsPhonePrecheck), the play waiting on
 * [onPrecheck]'s answer; leaving that sheet in any way stops the play, as Cancel does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaySheet(
    state: PlayState,
    onMinimize: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onSearch: (SearchRoute) -> Unit,
    onProviders: () -> Unit,
    onChooseFile: (Int) -> Unit = {},
    onNotInRelease: () -> Unit = {},
    startedFrom: StartedFrom? = null,
    onPhoneSetup: () -> Unit = {},
    onAddProvider: () -> Unit = {},
    onAddIndexer: () -> Unit = {},
    castTo: CastTo? = null,
    castTarget: PlayTarget = PlayTarget.Phone,
    onCast: (CastAction) -> Unit = {},
    castGoesOn: String? = null,
    onPrecheck: (CastPrecheck.Button) -> Unit = {},
    searchSteps: List<PipelineStep> = UsenetSource.STEPS,
    onServices: (() -> Unit)? = null,
    onAddService: (() -> Unit)? = null,
    onAddTorrentIndexer: (() -> Unit)? = null,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tv = LocalFormFactor.current == FormFactor.Tv
    // A remote can't drag, and the handle would take the focus first, where OK minimises the sheet.
    val handle: (@Composable () -> Unit)? = if (tv) null else ({ BottomSheetDefaults.DragHandle() })
    when (val s = state) {
        PlayState.Idle -> Unit
        // The play can't go on without an answer: Back, a tap beside the sheet or a swipe stops it, as Cancel does.
        is PlayState.ChoosingFile -> ModalBottomSheet(onDismissRequest = onCancel, modifier = Modifier.tvSheetTop(tv).okGuard().searchKeyOpensSearch(onCancel), sheetState = sheet, containerColor = colors.background, dragHandle = handle) {
            ChoosingFileSheet(s, tv, startedFrom, castTo, castGoesOn, onChoose = onChooseFile, onNotInRelease = onNotInRelease, onCancel = onCancel)
        }
        is PlayState.Prechecking -> ModalBottomSheet(onDismissRequest = onCancel, modifier = Modifier.okGuard(), sheetState = sheet, containerColor = colors.background, dragHandle = handle) {
            CastPrecheckSheet(s, tv, live = castTo?.let { CastStart.live(it, castTarget) } == true, onPrecheck)
        }
        is PlayState.Searching -> ModalBottomSheet(onDismissRequest = onMinimize, modifier = Modifier.tvSheetTop(tv).okGuard().searchKeyOpensSearch(onMinimize), sheetState = sheet, containerColor = colors.background, dragHandle = handle) {
            SearchingSheet(s, tv, startedFrom, castTo, castGoesOn, searchSteps, onHide = onMinimize, onCancel = onCancel)
        }
        is PlayState.NoRelease -> ModalBottomSheet(onDismissRequest = onMinimize, modifier = Modifier.tvSheetTop(tv).okGuard().searchKeyOpensSearch(onMinimize), sheetState = sheet, containerColor = colors.background, dragHandle = handle) {
            NoReleaseSheet(s, tv, startedFrom, onSearch = { onSearch(s.searchRoute()) }, onClose = onDismiss)
        }
        is PlayState.Running -> ModalBottomSheet(onDismissRequest = onMinimize, modifier = Modifier.tvSheetTop(tv).okGuard().searchKeyOpensSearch(onMinimize), sheetState = sheet, containerColor = colors.background, dragHandle = handle) {
            RunningSheet(s, tv, startedFrom, castTo, castGoesOn, onHide = onMinimize, onCancel = onCancel)
        }
        is PlayState.Failed -> ModalBottomSheet(onDismissRequest = onMinimize, modifier = Modifier.tvSheetTop(tv).okGuard().searchKeyOpensSearch(onMinimize), sheetState = sheet, containerColor = colors.background, dragHandle = handle) {
            val notReady = s.notReady
            val cast = s.cast
            if (cast != null) {
                CastFailedSheet(s, cast, tv, live = CastStart.live(cast.device, castTarget), onCast)
            } else if (notReady != null) {
                val ways = NotReadyWays(onPhoneSetup, onAddProvider, onAddIndexer, onAddService, onServices, onAddTorrentIndexer)
                NotReadySheet(s, tv, notReady, notReadyButtons(notReady, ways), onClose = onDismiss)
            } else when (val missing = s.noProvider) {
                null -> FailedSheet(s, tv, startedFrom, onSearch = s.searchRoute()?.let { route -> { onSearch(route) } }, onClose = onDismiss)
                else -> NoProviderSheet(s, tv, startedFrom, missing, onProviders = onProviders, onServices = onServices, onClose = onDismiss)
            }
        }
    }
}

/**
 * Whether the running sheet says "Plays on this device", the ranker's word for the release: not beside the probe's
 * failed step, which found no audio or video this device plays, or couldn't tell, and never for a play [cast] to a
 * device, whose probe is this phone's, not the device's (L65).
 */
internal fun playsHere(steps: Map<PipelineStep, StepStatus>, cast: CastTo? = null): Boolean = cast == null && steps[PipelineStep.ProbeTracks] !is StepStatus.Failed

/** The minimised start's line under its title: the step it is on; for a play [cast] to a device, the device first, then its own step once it has the release. */
internal fun runningLine(s: PlayState.Running, cast: CastTo?): String {
    val step = if (cast != null && s.castStep != null) CAST_STARTING else currentStep(s.steps, s.sourceSteps)?.let { STEP_LABELS.getValue(it) + "…" } ?: "Starting…"
    return cast?.let { onDevice(it.name, step) } ?: step
}

/**
 * The lines a cast's starting sheet says under "Casting to Kitchen display" once its file is checked (W63): the audio
 * language the device will play, and that subtitles won't show there; none for a play here.
 */
internal fun castLinesShown(s: PlayState.Running, cast: CastTo?): List<CastLine> = if (cast == null) emptyList() else s.castLines

/** The minimised search's line, after the device a [cast] goes to. */
internal fun searchingLine(s: PlayState.Searching, cast: CastTo?): String = cast?.let { onDevice(it.name, s.line) } ?: s.line

/** The minimised failure's words, naming the device a [cast] was to play on. */
internal fun failedLine(s: PlayState.Failed, cast: CastTo?): String = cast?.let { couldNotPlayOn(s.title, it.name) } ?: "Couldn't play ${s.title}"

/**
 * The step a minimised playback start names: the one running (a season pack is checked after it
 * is opened, so that isn't always the last in order), else the furthest one reached of its
 * source's steps, [order] (L203).
 */
internal fun currentStep(steps: Map<PipelineStep, StepStatus>, order: List<PipelineStep> = PipelineStep.entries): PipelineStep? =
    steps.entries.firstOrNull { it.value is StepStatus.Running }?.key ?: order.lastOrNull { steps[it] != null }

/**
 * One line above the bottom navigation while a minimised playback start is running or failed.
 * [windowInsets] keep it clear of the system navigation bar when nothing below it does. On a phone, a start on a TV
 * ([tv]) has its own ([TvMiniBar]), and with no start here or there the play this phone follows on a TV or a Cast device
 * ([playing]) has the Playing on bar in its place, which opens the remote ([onRemote]).
 */
@Composable
fun PlayMiniBar(
    coordinator: PlaybackCoordinator,
    windowInsets: WindowInsets = WindowInsets.navigationBars,
    tv: TvStart? = null,
    playing: RemotePlaying? = null,
    onRemote: () -> Unit = {},
) {
    if (tv != null) {
        val onTv by tv.now.collectAsStateWithLifecycle()
        onTv?.let { now ->
            if (!now.expanded) TvMiniBar(now, windowInsets, onExpand = tv::expand)
            return
        }
    }
    val state by coordinator.state.collectAsStateWithLifecycle()
    val expanded by coordinator.expanded.collectAsStateWithLifecycle()
    val castTo by coordinator.castTo.collectAsStateWithLifecycle()
    // On a TV, OK on the bar opens the sheet and the bar leaves the screen with the focus: it takes the
    // focus back as the sheet is hidden, else nothing would have it and the next arrow would go to the rail.
    val tv = LocalFormFactor.current == FormFactor.Tv
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var refocus by remember { mutableStateOf(false) }
    if (state == PlayState.Idle) {
        // Stopped or closed from the sheet: a later play's bar doesn't take the focus.
        SideEffect { refocus = false }
        playing?.let { RemotePlayingBarHost(it, windowInsets, onRemote) }
        return
    }
    if (expanded) return
    if (refocus) LaunchedEffect(Unit) {
        focus.tryFocus()
        refocus = false
    }
    Row(
        Modifier.fillMaxWidth().background(colors.surface).windowInsetsPadding(windowInsets)
            .focusRequester(focus).onFocusChanged { focused = it.isFocused }
            .clickable { refocus = tv && focused; coordinator.expand() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val s = state) {
            is PlayState.Running -> {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent, trackColor = colors.outline)
                Column(Modifier.weight(1f)) {
                    Text("Starting ${s.title}" + (s.subtitle?.let { " · $it" } ?: ""), style = AppType.bodyStrong, color = colors.text, maxLines = 1)
                    Text(runningLine(s, castTo), style = AppType.small, color = colors.muted, maxLines = 1)
                }
            }
            is PlayState.Failed -> {
                Text("⚠", style = AppType.bodyStrong, color = colors.warnStrong)
                Text(failedLine(s, castTo), style = AppType.bodyStrong, color = colors.text, maxLines = 1, modifier = Modifier.weight(1f))
            }
            is PlayState.Searching -> {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent, trackColor = colors.outline)
                Column(Modifier.weight(1f)) {
                    Text("Finding ${s.title}" + (s.subtitle?.let { " · $it" } ?: ""), style = AppType.bodyStrong, color = colors.text, maxLines = 1)
                    Text(searchingLine(s, castTo), style = AppType.small, color = colors.muted, maxLines = 1)
                }
            }
            is PlayState.NoRelease -> {
                Text("⚠", style = AppType.bodyStrong, color = colors.warnStrong)
                Text("No release for ${s.title} ${s.subtitle.orEmpty()}", style = AppType.bodyStrong, color = colors.text, maxLines = 1, modifier = Modifier.weight(1f))
            }
            is PlayState.ChoosingFile -> {
                Column(Modifier.weight(1f)) {
                    Text("Choose the file for ${s.title}" + (s.subtitle?.let { " · $it" } ?: ""), style = AppType.bodyStrong, color = colors.text, maxLines = 1)
                    Text(s.lookingFor, style = AppType.small, color = colors.muted, maxLines = 1)
                }
            }
            // Asked over the sheet, opened as it asks: here only if it was minimised since.
            is PlayState.Prechecking -> {
                Text("⚠", style = AppType.bodyStrong, color = colors.warnStrong)
                Text(s.precheck.headline, style = AppType.bodyStrong, color = colors.text, maxLines = 1, modifier = Modifier.weight(1f))
            }
            PlayState.Idle -> Unit
        }
        Text("Show", style = AppType.secondary, color = colors.accent)
    }
}

/**
 * Cancel; on a TV ([tv]) with Hide before it, which takes the focus as the sheet opens: OK there
 * only minimises the sheet, while Cancel would stop the play just started. On a phone a
 * picture-in-picture window floating over the app keeps clear of Cancel ([cancelKeptClear]).
 */
@Composable
private fun StopButtons(tv: Boolean, onHide: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    if (!tv) {
        SecondaryButton("Cancel", onCancel, modifier.fillMaxWidth().cancelKeptClear())
        return
    }
    val hide = rememberFirstOnTv(tv)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton("Hide", onHide, Modifier.weight(1f).focusRequester(hide).tvFocusLook(tv))
        SecondaryButton("Cancel", onCancel, Modifier.weight(1f).tvFocusLook(tv))
    }
}

/** The button a TV's sheet focuses as it opens, its way on; a phone's sheet focuses nothing. */
@Composable
private fun rememberFirstOnTv(tv: Boolean): FocusRequester {
    val first = remember { FocusRequester() }
    if (tv) LaunchedEffect(Unit) { first.tryFocus() }
    return first
}

/**
 * Below a sheet's last button: on a TV clear of the bottom of its safe area, which the shell's padding
 * doesn't reach in the sheet's own window (and there is no navigation bar to keep clear of).
 */
private fun sheetBottom(tv: Boolean): Dp = if (tv) TvSafeArea.vertical else 16.dp

/**
 * Cancel kept clear of the player's picture-in-picture window, which floats at the bottom of the screen over the app's
 * screens, where Cancel is, as the user plays another film there (L9, PipWindowNewPlay). Android 14 and later move the
 * window up off it; Android 13 notes the area and leaves the window where it is, which the user can drag.
 */
internal fun Modifier.cancelKeptClear(): Modifier = preferKeepClear()

/**
 * On a TV, the sheet keeps inside the safe area's top (PtTvIncoming 3): one taller than the screen, as a season pack's file
 * chooser is, starts 27 dp down rather than at the screen's top edge, its rows scrolling inside it; a shorter one still
 * sits at the bottom. Outermost, so the sheet's own size and where it rests are measured inside it.
 */
private fun Modifier.tvSheetTop(tv: Boolean): Modifier = if (tv) padding(top = TvSafeArea.vertical) else this

/** On a TV, a sheet's buttons show the remote's focus as the rest of the app's do. */
private fun Modifier.tvFocusLook(tv: Boolean): Modifier = if (tv) focusOutline(RoundedCornerShape(24.dp), fill = false) else this

@Composable
internal fun SheetTitle(title: String, subtitle: String?) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp)) {
        Text(title, style = AppType.screenTitle, color = colors.text)
        subtitle?.let { Text(it, style = AppType.secondary, color = colors.muted) }
    }
}

/**
 * Under the title, where a warning goes but in the plain secondary colour (PtTvIncoming): the phone that asked for this
 * play, by the phone icon, "Started from Galaxy phone", or while the play asks which file it is, that the phone waits
 * ([waiting]); then, after a hand-over, what stopped for it, muted.
 */
@Composable
private fun FromLine(from: StartedFrom?, waiting: Boolean = false) {
    from ?: return
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(PhoneIcon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            Text(if (waiting) from.waiting else from.line, style = AppType.secondary, color = colors.textSecondary)
        }
        from.stoppedLine?.let { Text(it, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(start = 22.dp)) }
    }
}

/**
 * Under the title, where [FromLine] goes, for a play cast to a Google Cast device ([cast], L65): "Casting to Kitchen
 * display" by Google's filled Cast icon, in the accent; or, for a failure ([warning]), [words] by its outline icon in the
 * warning colour (CsPhoneStarting).
 */
@Composable
private fun CastLine(cast: CastTo?, words: String? = cast?.let { castingTo(it.name) }, warning: Boolean = false) {
    words ?: return
    Row(
        Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (warning) CastIcon else CastConnectedIcon, contentDescription = null, tint = if (warning) colors.warnStrong else colors.accent, modifier = Modifier.size(18.dp))
        Text(words, style = AppType.secondary, color = if (warning) colors.warnStrong else colors.textSecondary)
    }
}

/**
 * Under the cast's line while the next release starts from the missing-parts prompt (L84, L94; CsPhoneMissingParts 3):
 * "The release playing goes on there until this one is ready, at 33:35.", under the line's words.
 */
@Composable
private fun GoesOnLine(words: String?) {
    words ?: return
    Text(words, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(start = 46.dp, end = 20.dp, bottom = 12.dp))
}

/**
 * Lines a cast says before it plays (W63, L142; CsPhonePrecheck): each a stop of its own, a 16 dp muted icon (sound, or
 * subtitles) by its words in the secondary colour.
 */
@Composable
private fun CastLines(lines: List<CastLine>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    Column(modifier) {
        lines.forEach { line ->
            Row(
                Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val icon = if (line.kind == CastLine.Kind.Audio) SoundIcon else SubtitlesIcon
                Icon(icon, contentDescription = null, tint = colors.muted, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                Text(line.words, style = AppType.secondary, color = colors.textSecondary)
            }
        }
    }
}

/**
 * The pre-check (L130; CsPhonePrecheck), drawn as a cast's failure sheet is: the play's title; what the device can't play
 * as the headline, a heading, which names the sheet for TalkBack; a card with "⚠ <reason>" over the release's name, read
 * as one; the lines that apply; that nothing was sent yet; then the answers, amber Play a compatible release first where
 * there is one, the device's own only while it is [live] ([CastPrecheck.buttons]), each asking [onAnswer] for itself.
 */
@Composable
private fun CastPrecheckSheet(s: PlayState.Prechecking, tv: Boolean, live: Boolean, onAnswer: (CastPrecheck.Button) -> Unit) {
    val precheck = s.precheck
    Column(Modifier.navigationBarsPadding().padding(bottom = sheetBottom(tv)).verticalScroll(rememberScrollState()).semantics { paneTitle = precheck.headline }) {
        SheetTitle(s.title, s.subtitle)
        Text(precheck.headline, style = AppType.cardTitle, color = colors.text, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp).semantics { heading() })
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(12.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("⚠ ${precheck.reason}", style = AppType.secondary, color = colors.warn)
                Text(precheck.release, style = AppType.release, color = colors.muted)
            }
        }
        CastLines(precheck.lines, Modifier.padding(top = 12.dp))
        Text(
            precheck.note, style = AppType.small, color = colors.muted,
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = if (precheck.lines.isEmpty()) 12.dp else 4.dp),
        )
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            precheck.buttons(live).forEach { button ->
                val press = { onAnswer(button) }
                when (button) {
                    CastPrecheck.Button.Compatible -> PrimaryButton(button.words, press, Modifier.fillMaxWidth())
                    CastPrecheck.Button.Cancel -> SecondaryButton(button.words, press, Modifier.fillMaxWidth().cancelKeptClear())
                    else -> SecondaryButton(button.words, press, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * The pre-check as the player asks it over its film, before anything connects (L124 step 2): [precheck] for the play
 * [title] ([subtitle]) in a sheet of its own, as the play sheet shows it, every answer offered (the device is there to
 * connect to); Back, a tap beside it or a swipe down is Cancel. Each answer goes to [onAnswer].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CastPrecheckQuestion(title: String, subtitle: String?, precheck: CastPrecheck, onAnswer: (CastPrecheck.Button) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = { onAnswer(CastPrecheck.Button.Cancel) }, modifier = Modifier.okGuard(),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background,
    ) {
        CastPrecheckSheet(PlayState.Prechecking(title, subtitle, precheck), tv = false, live = true, onAnswer)
    }
}

/**
 * The phone's steps under the search's line, the release's source's own ([stepRows], L203), then, for a play cast to a
 * device ([cast]), the device's own ([castStep], L65).
 */
@Composable
private fun Steps(searchLine: String?, rows: List<Pair<String, StepStatus?>>, cast: CastTo?, castStep: StepStatus?) {
    GroupCard {
        searchLine?.let { StepRow(it, StepStatus.Done(0)); InsetDivider() }
        rows.forEachIndexed { i, (label, status) ->
            if (i > 0) InsetDivider()
            StepRow(label, status)
        }
        cast?.let { InsetDivider(); StepRow(startingOn(it.name), castStep) }
    }
}

@Composable
private fun RunningSheet(s: PlayState.Running, tv: Boolean, from: StartedFrom?, cast: CastTo?, goesOn: String?, onHide: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.navigationBarsPadding().padding(bottom = sheetBottom(tv))) {
        SheetTitle(s.title, s.subtitle)
        FromLine(from)
        CastLine(cast)
        GoesOnLine(goesOn)
        CastLines(castLinesShown(s, cast), Modifier.padding(bottom = 6.dp))
        s.notice?.let {
            Text(it, style = AppType.secondary, color = colors.warn, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp))
        }
        Steps(s.searchLine, stepRows(s), cast, s.castStep)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Where it plays from beside it, as the lists tag it (DbPhonePlaySheet), while a service account is on.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(releaseLine(s), style = AppType.small, color = colors.muted)
                s.tag?.let { SourceTagChip(it) }
            }
            Text(s.release, style = AppType.release, color = colors.chipText)
            ChipRow {
                s.chips.forEach { InfoChip(it) }
                if (playsHere(s.steps, cast)) InfoChip("✓ Plays on this device", ChipKind.Good)
            }
        }
        StopButtons(tv, onHide, onCancel, Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
internal fun StepRow(label: String, status: StepStatus?) {
    val spoken = stepSpoken(label, status)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp).semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                when (status) {
                    is StepStatus.Running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent, trackColor = colors.outline)
                    is StepStatus.Done -> Text("✓", style = AppType.bodyStrong, color = colors.good)
                    is StepStatus.Failed -> Text("✕", style = AppType.bodyStrong, color = colors.warnStrong)
                    null -> Text("·", style = AppType.bodyStrong, color = colors.outline)
                }
            }
            Text(label, style = AppType.body, color = if (status == null) colors.muted else colors.text, modifier = Modifier.weight(1f))
            val detail = when (status) {
                is StepStatus.Running -> status.total?.let { "${status.done ?: 0} / $it" }.orEmpty()
                is StepStatus.Done -> listOfNotNull(status.detail?.takeIf { it.length < 24 }, status.millis.takeIf { it > 0 }?.let { "%.1f s".format(it / 1000.0) }).joinToString(" · ")
                is StepStatus.Failed -> status.failure.text
                null -> ""
            }
            if (detail.isNotEmpty()) Text(detail, style = AppType.secondary, color = if (status is StepStatus.Failed) colors.warn else colors.muted, maxLines = 1, modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false))
        }
        // A determinate bar while the step knows how far along it is.
        val total = (status as? StepStatus.Running)?.total
        val done = (status as? StepStatus.Running)?.done ?: 0
        if (total != null && total > 0) {
            LinearProgressIndicator(
                progress = { done.toFloat() / total },
                modifier = Modifier.padding(start = 28.dp).fillMaxWidth(),
                color = colors.accent, trackColor = colors.divider, drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun SearchingSheet(
    s: PlayState.Searching, tv: Boolean, from: StartedFrom?, cast: CastTo?, goesOn: String?, steps: List<PipelineStep>, onHide: () -> Unit, onCancel: () -> Unit,
) {
    Column(Modifier.navigationBarsPadding().padding(bottom = sheetBottom(tv))) {
        SheetTitle(s.title, s.subtitle)
        FromLine(from)
        CastLine(cast)
        GoesOnLine(goesOn)
        GroupCard {
            StepRow(s.line, StepStatus.Running())
            steps.forEach { step -> InsetDivider(); StepRow(STEP_LABELS.getValue(step), null) }
            cast?.let { InsetDivider(); StepRow(startingOn(it.name), null) }
        }
        StopButtons(tv, onHide, onCancel, Modifier.padding(16.dp))
    }
}

/**
 * Which of the release's videos is the episode ([PlayState.ChoosingFile.lookingFor]): none is named for it, or the user asked
 * to choose, as its [PlayState.ChoosingFile.prompt] says. A row plays its file. Where nothing told, the release may not
 * hold it: "Not in this release" goes on without the release (its next one, or a search). Cancel stops the play, back to
 * where it started (a release list, for a release chosen there). With a remote, and on a TV, the focus starts on the first row.
 */
@Composable
private fun ChoosingFileSheet(
    s: PlayState.ChoosingFile, tv: Boolean, from: StartedFrom?, cast: CastTo?, goesOn: String?, onChoose: (Int) -> Unit, onNotInRelease: () -> Unit, onCancel: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val keys = LocalInputModeManager.current
    val window = LocalWindowInfo.current
    LaunchedEffect(Unit) {
        // The sheet is a window of its own: until it has the focus its input mode reads Touch, even on a phone used with a
        // keyboard, and as it gets it, it gives its first focus to the drag handle. So wait: the first file takes it then.
        snapshotFlow { window.isWindowFocused }.first { it }
        if (tv || keys.inputMode == InputMode.Keyboard) first.tryFocus()
    }
    Column(Modifier.navigationBarsPadding().padding(bottom = sheetBottom(tv)).verticalScroll(rememberScrollState()).semantics { paneTitle = "Choose the file" }) {
        SheetTitle(s.title, s.subtitle)
        FromLine(from, waiting = true)
        CastLine(cast)
        GoesOnLine(goesOn)
        Text(s.lookingFor, style = AppType.cardTitle, color = colors.text, modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() })
        Text(
            s.prompt,
            style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 6.dp),
        )
        Text(s.release, style = AppType.release, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp))
        GroupCard {
            s.files.forEachIndexed { i, file ->
                if (i > 0) InsetDivider()
                FileRow(file, if (i == 0) Modifier.focusRequester(first) else Modifier) { onChoose(file.index) }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!s.asked) SecondaryButton("Not in this release", onNotInRelease, Modifier.fillMaxWidth().tvFocusLook(tv))
            SecondaryButton("Cancel", onCancel, Modifier.fillMaxWidth().tvFocusLook(tv).cancelKeptClear())
        }
    }
}

/**
 * A video to choose: its name, and its size (and that it is a sample, or named for the episode looked for). At least 48 dp
 * tall, growing with the font size.
 */
@Composable
private fun FileRow(file: FileChoice, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().focusOutline().clickable(onClickLabel = "Play this file", onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(file.name, style = AppType.release, color = if (file.sample) colors.muted else colors.text, modifier = Modifier.weight(1f))
        Text(
            listOfNotNull(file.named?.let { "Named $it" }, "Sample".takeIf { file.sample }, formatSize(file.sizeBytes)).joinToString(" · "),
            style = AppType.secondary, color = colors.muted,
        )
    }
}

@Composable
private fun NoReleaseSheet(s: PlayState.NoRelease, tv: Boolean, from: StartedFrom?, onSearch: () -> Unit, onClose: () -> Unit) {
    val first = rememberFirstOnTv(tv)
    var showHidden by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.navigationBarsPadding().padding(bottom = sheetBottom(tv)).verticalScroll(rememberScrollState())) {
        SheetTitle(s.title, s.subtitle)
        FromLine(from)
        Text("No playable release found", style = AppType.cardTitle, color = colors.text, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp))
        GroupCard {
            s.stages.forEachIndexed { i, (what, outcome) ->
                if (i > 0) InsetDivider()
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(what, style = AppType.body, color = colors.text)
                    Text(outcome, style = AppType.secondary, color = if (outcome.startsWith("⚠")) colors.warn else colors.muted)
                }
            }
        }
        s.note?.let { Text(it, style = AppType.small, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Search “${s.nameQuery}”", onSearch, Modifier.fillMaxWidth().focusRequester(first).tvFocusLook(tv))
            if (s.hidden.isNotEmpty()) SecondaryButton(if (showHidden) "Hide the ${s.hidden.size} found" else "Show the ${s.hidden.size} found", { showHidden = !showHidden }, Modifier.fillMaxWidth().tvFocusLook(tv))
            if (showHidden) GroupCard(Modifier.padding(horizontal = 0.dp)) {
                s.hidden.forEachIndexed { i, h ->
                    if (i > 0) InsetDivider()
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(h.item.title, style = AppType.release, color = colors.muted)
                        io.skjaere.usenet.app.search.ReasonChips(h)
                    }
                }
            }
            SecondaryButton("Close", onClose, Modifier.fillMaxWidth().tvFocusLook(tv))
        }
    }
}

/**
 * The failure sheet's lines on the [notTried] releases left: those left to save the indexer's download allowance, which one
 * chosen from the list may still cost, and apart from them the [noRoute] left as what they play from failed above (a
 * provider down, a debrid account's limit or refused key), which leaving them saved nothing of (DEP-8).
 */
internal fun notTriedLines(notTried: Int, noRoute: Int): List<String> {
    val saved = notTried - noRoute
    return listOfNotNull(
        "$saved more release${if (saved == 1) "" else "s"} not tried, to save your indexer's download allowance. Choose one from the release list.".takeIf { saved > 0 },
        (if (noRoute == 1) "1 more release not tried: what it plays from failed above." else "$noRoute more releases not tried: what they play from failed above.")
            .takeIf { noRoute > 0 },
    )
}

/**
 * The no-provider sheet's button for [missing], its words and what it opens: Debrid services ([services]) for a debrid-only
 * user whose service is off, as the sheet's words say where it is switched on (DEP-9); else, or with no way there (debrid
 * hidden), the providers ([providers]).
 */
internal fun noProviderWay(missing: NoUsableProvider, services: (() -> Unit)?, providers: () -> Unit): Pair<String, () -> Unit> =
    if (missing == NoUsableProvider.ServiceOff && services != null) NoUsableProvider.SERVICES_ACTION to services else NoUsableProvider.ACTION to providers

/**
 * Nothing was tried: no Usenet provider is usable, for [missing]. [onProviders] opens the providers list, [onServices]
 * Debrid services, for the sheet that says the service is off ([noProviderWay]).
 */
@Composable
private fun NoProviderSheet(
    s: PlayState.Failed, tv: Boolean, from: StartedFrom?, missing: NoUsableProvider, onProviders: () -> Unit, onServices: (() -> Unit)?, onClose: () -> Unit,
) {
    val first = rememberFirstOnTv(tv)
    Column(Modifier.navigationBarsPadding().padding(bottom = sheetBottom(tv)).verticalScroll(rememberScrollState())) {
        SheetTitle(s.title, s.subtitle)
        FromLine(from)
        Text(
            "Couldn't start playback. ${missing.message}",
            style = AppType.body, color = colors.text, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp),
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val (words, open) = noProviderWay(missing, onServices, onProviders)
            PrimaryButton(words, open, Modifier.fillMaxWidth().focusRequester(first).tvFocusLook(tv))
            SecondaryButton("Close", onClose, Modifier.fillMaxWidth().tvFocusLook(tv))
        }
    }
}

/** Where a TV's not-ready sheet goes on: "Set up with your phone", each form, and debrid's pages, null while debrid is hidden (L192). */
class NotReadyWays(
    val phoneSetup: () -> Unit,
    val addProvider: () -> Unit,
    val addIndexer: () -> Unit,
    val addService: (() -> Unit)? = null,
    val services: (() -> Unit)? = null,
    val addTorrentIndexer: (() -> Unit)? = null,
)

/**
 * A TV's not-ready sheet's buttons above Close, in order, the first focused (PtTvNotReady, D2TvNotReady): "Set up with
 * your phone", the quickest way from the sofa, then the page for what is missing. For a service that is off, Open Debrid
 * services first, then the phone's: the phone's page can't switch a service on (L248, choice 6). A debrid page with no
 * way there (debrid hidden) is left out.
 */
internal fun notReadyButtons(notReady: NotReady, ways: NotReadyWays): List<Pair<String, () -> Unit>> {
    val phone = PHONE_SETUP_TITLE to ways.phoneSetup
    val page = when (notReady.missing) {
        NotReady.Missing.Provider -> ways.addProvider
        NotReady.Missing.Indexer -> ways.addIndexer
        NotReady.Missing.Service -> ways.addService
        is NotReady.Missing.ServiceOff -> ways.services
        NotReady.Missing.TorrentIndexer -> ways.addTorrentIndexer
    }?.let { notReady.add to it }
    return if (notReady.missing is NotReady.Missing.ServiceOff) listOfNotNull(page, phone) else listOfNotNull(phone, page)
}

/**
 * A paired phone asked for a play this TV can't start yet (PtTvNotReady, D2TvNotReady), as the sheet for a missing
 * provider says it: the phone and what it asked for, then its [buttons] ([notReadyButtons]), the first primary and
 * focused, and Close. The TV doesn't keep the request.
 */
@Composable
private fun NotReadySheet(s: PlayState.Failed, tv: Boolean, notReady: NotReady, buttons: List<Pair<String, () -> Unit>>, onClose: () -> Unit) {
    val first = rememberFirstOnTv(tv)
    Column(Modifier.navigationBarsPadding().padding(bottom = sheetBottom(tv)).verticalScroll(rememberScrollState())) {
        SheetTitle(s.title, s.subtitle)
        Text(notReady.text, style = AppType.body, color = colors.text, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            buttons.forEachIndexed { i, (words, open) ->
                if (i == 0) {
                    PrimaryButton(words, open, Modifier.fillMaxWidth().focusRequester(first).tvFocusLook(tv))
                } else {
                    SecondaryButton(words, open, Modifier.fillMaxWidth().tvFocusLook(tv))
                }
            }
            SecondaryButton("Close", onClose, Modifier.fillMaxWidth().tvFocusLook(tv))
        }
    }
}

/** What the failure sheet says above the releases: that each tried failed, or, with some skipped as found incomplete before, only that it couldn't start. */
internal fun failedHeader(s: PlayState.Failed): String = when {
    s.missing.isNotEmpty() -> "Couldn't start playback:"
    s.failures.size == 1 -> "Couldn't start playback. The release failed:"
    else -> "Couldn't start playback. Each release tried failed:"
}

/**
 * [onSearch]: what the sheet's Search does, when it offers one ([PlayState.Failed.searchRoute]). On a TV, releases more
 * than the screen holds are a stop for the remote's focus, read a page at a time ([pagesThrough]): nothing else above the
 * buttons takes the focus, so Up from them would leave the first releases, the best ranked, out of reach.
 */
@Composable
private fun FailedSheet(s: PlayState.Failed, tv: Boolean, from: StartedFrom?, onSearch: (() -> Unit)?, onClose: () -> Unit) {
    val first = rememberFirstOnTv(tv)
    val scroll = rememberScrollState()
    val overflows = tv && scroll.viewportSize > 0 && scroll.maxValue > 0
    // Its focused button is at its end, focused before the sheet was laid out: the focus didn't bring it into view.
    LaunchedEffect(overflows) { if (overflows) scroll.scrollTo(scroll.maxValue) }
    Column(Modifier.navigationBarsPadding().padding(bottom = sheetBottom(tv)).verticalScroll(scroll)) {
        SheetTitle(s.title, s.subtitle)
        FromLine(from)
        Text(
            failedHeader(s),
            style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp),
        )
        GroupCard {
            val stop = Modifier.pagesThrough(scroll.viewportSize).focusOutline(RoundedCornerShape(16.dp), color = colors.text, fill = false).focusable()
            Column(if (overflows) stop else Modifier) {
                s.failures.forEachIndexed { i, (release, reason) ->
                    if (i > 0) InsetDivider()
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("✕ $reason", style = AppType.secondary, color = colors.warn)
                        Text(release, style = AppType.release, color = colors.muted)
                    }
                }
                // Skipped, not tried: muted, without the cross.
                s.missing.forEachIndexed { i, (release, line) ->
                    if (i > 0 || s.failures.isNotEmpty()) InsetDivider()
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(line, style = AppType.secondary, color = colors.muted)
                        Text(release, style = AppType.release, color = colors.muted)
                    }
                }
            }
        }
        if (s.skipped > 0) Text(
            "Skipped ${s.skipped} more release${if (s.skipped == 1) "" else "s"} from an indexer that refused a download above.",
            style = AppType.small, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        notTriedLines(s.notTried, s.noRoute).forEach { line ->
            Text(line, style = AppType.small, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        }
        if (onSearch != null && s.search != null) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Search “${s.search}”", onSearch, Modifier.fillMaxWidth().focusRequester(first).tvFocusLook(tv))
                SecondaryButton("Close", onClose, Modifier.fillMaxWidth().tvFocusLook(tv))
            }
        } else {
            PrimaryButton("Close", onClose, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().focusRequester(first).tvFocusLook(tv))
        }
    }
}

/**
 * A cast play that failed (L83; CsPhoneStarting), in [cast]'s words ([CastStart.words]): can't reach says so under the
 * title in the warning colour, with what to check and the phone's steps, the last one failed; couldn't play, the file
 * types and the releases the device can't fully play (L149) head a card per release, "✕ <reason>" over its name, and a
 * small line under them. Its buttons ([CastStart.buttons], the first the main one, the device's own only while it is
 * [live]) ask [onCast] for what they say.
 */
@Composable
private fun CastFailedSheet(s: PlayState.Failed, cast: CastFailed, tv: Boolean, live: Boolean, onCast: (CastAction) -> Unit) {
    val first = rememberFirstOnTv(tv)
    val words = CastStart.words(cast)
    Column(Modifier.navigationBarsPadding().padding(bottom = sheetBottom(tv)).verticalScroll(rememberScrollState())) {
        SheetTitle(s.title, s.subtitle)
        CastLine(cast.device, words.where, warning = true)
        words.advice?.let { Text(it, style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)) }
        words.headline?.let { Text(it, style = AppType.cardTitle, color = colors.text, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp)) }
        if (words.cards) GroupCard {
            s.failures.forEachIndexed { i, (release, reason) ->
                if (i > 0) InsetDivider()
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("✕ $reason", style = AppType.secondary, color = colors.warn)
                    Text(release, style = AppType.release, color = colors.muted)
                }
            }
        }
        cast.running?.takeIf { words.steps }?.let { Steps(it.searchLine, stepRows(it), cast.device, it.castStep) }
        words.note?.let { Text(it, style = AppType.small, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp).padding(top = 12.dp)) }
        notTriedLines(s.notTried, s.noRoute).forEach { line ->
            Text(line, style = AppType.small, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp).padding(top = 10.dp))
        }
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CastStart.buttons(cast, live).forEachIndexed { i, button ->
                val press = { onCast(CastStart.press(button, cast)) }
                if (i == 0) PrimaryButton(button.words, press, Modifier.fillMaxWidth().focusRequester(first).tvFocusLook(tv))
                else SecondaryButton(button.words, press, Modifier.fillMaxWidth().tvFocusLook(tv))
            }
        }
    }
}
