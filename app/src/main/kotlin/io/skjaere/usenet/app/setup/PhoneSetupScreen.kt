package io.skjaere.usenet.app.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.okGuard
import io.skjaere.usenet.app.tv.searchKeyOpensSearch
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.QrCode
import io.skjaere.usenet.app.ui.ResultLine
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.TvDimens
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.handoff.SETUP_NOT_ENCRYPTED
import io.skjaere.usenet.handoff.SetupLimits
import io.skjaere.usenet.handoff.SetupMode
import io.skjaere.usenet.handoff.SetupOutcome
import io.skjaere.usenet.handoff.SetupRejection
import io.skjaere.usenet.handoff.SetupSection
import io.skjaere.usenet.handoff.SetupStep
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val PHONE_SETUP_TITLE = "Set up with your phone"
const val PHONE_SETUP_SCAN = "Scan the code with your phone's camera, or open this address in its browser:"
/** The review's note: what Save does. */
const val PHONE_SETUP_REVIEW_NOTE = "Nothing is saved until you choose Save. Items your phone tested with these values aren't tested again."
const val PHONE_SETUP_SAVE = "Save"
const val PHONE_SETUP_SEND_AGAIN = "Send again"
const val PHONE_SETUP_DONE = "Done"
const val PHONE_SETUP_TRY_AGAIN = "Try again"
const val PHONE_SETUP_SAVE_ANYWAY = "Save anyway"
const val PHONE_SETUP_LEAVE_OUT = "Leave out"
/** Waiting's second step for the wizard's page. */
const val PHONE_SETUP_FOLLOW = "Follow the steps on your phone."
/** The same for the edit page. */
const val PHONE_SETUP_CHANGE = "Change, add or remove on your phone, then send."
/** Waiting's third step. */
const val PHONE_SETUP_SAVE_HERE = "Nothing is saved until you choose Save here."
const val PHONE_SETUP_SAME_NETWORK = "Your phone must be on the same network as this TV, not a guest network."
const val PHONE_SETUP_NO_NETWORK = "This TV isn't on a home network your phone can reach."
const val PHONE_SETUP_NETWORK_CHANGED = "The network changed."
const val PHONE_SETUP_EXPIRED = "The code has expired."
const val PHONE_SETUP_REVIEW = "From your phone"
const val PHONE_SETUP_DISCARD_ASK = "Discard what your phone sent?"
const val PHONE_SETUP_QR = "QR code of the address beside it"
const val PHONE_SETUP_TESTING = "Your phone is testing…"
val PHONE_SETUP_LOCKED = "The code was closed after ${SetupLimits().wrongTries} wrong tries."

/** What a part of the page is, for the tests: [ADDRESS], [QR], or an item of the review by its [ReviewItem.part]. */
val PhoneSetupPart = SemanticsPropertyKey<String>("PhoneSetupPart")
private var SemanticsPropertyReceiver.phoneSetupPart by PhoneSetupPart

const val ADDRESS = "address"
const val QR = "qr"

/** An item's Try again, as a part of the page, by its [ReviewItem.part]. */
fun retryPart(part: String) = "$part try again"

/**
 * "Set up with your phone" (TV only; the route is harmless elsewhere): a code for the phone's camera, the same address
 * to type, then what the phone sent, reviewed, and Save. The session runs only while this page is in front (its
 * back-stack entry started), and the screen stays on meanwhile and until what arrived is saved: a TV's screensaver would
 * stop it while the user types on the phone. Every entry opens the same page; what is saved decides the phone's.
 * [onDone] leaves it once anything is saved, [onBack] before. [deps]: for tests, fakes in place of the app's.
 */
@Composable
fun PhoneSetupScreen(onDone: () -> Unit, onBack: () -> Unit, deps: PhoneSetupDeps? = null) {
    val context = LocalContext.current
    val vm: PhoneSetupViewModel = viewModel { PhoneSetupViewModel(deps ?: PhoneSetupDeps.of(context.container, context)) }
    LifecycleStartEffect(vm) {
        vm.start()
        onStopOrDispose { vm.stop() }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val savedSomething by vm.savedSomething.collectAsStateWithLifecycle()
    var askDiscard by remember { mutableStateOf(false) }
    // Once anything is saved, a form under the page would write its old values over it on Save: every way out is
    // Done then, which closes that form too, even after Send again.
    val leave = if (savedSomething) onDone else onBack
    // Back on what arrived asks first, nothing being saved yet; while testing it stops the tests; once saved, as Done.
    val back: () -> Unit = {
        when (val s = state) {
            is PhoneSetupState.Received -> if (s.review.items.isEmpty()) leave() else askDiscard = true
            is PhoneSetupState.Testing -> vm.cancelTests()
            is PhoneSetupState.Results -> askDiscard = true
            // A moment's writes, which Back can't stop half way.
            is PhoneSetupState.Saving -> Unit
            is PhoneSetupState.Saved -> onDone()
            else -> leave()
        }
    }
    val reviewing = state.let {
        it is PhoneSetupState.Received || it is PhoneSetupState.Testing || it is PhoneSetupState.Results || it is PhoneSetupState.Saving ||
            it is PhoneSetupState.Saved
    }
    BackHandler(enabled = reviewing || savedSomething) { back() }
    // On while the phone fills the page in, and while what it sent waits to be saved; not once it is, with nothing left
    // running: a TV left there can sleep.
    val awake = reviewing && state !is PhoneSetupState.Saved || state is PhoneSetupState.Waiting

    Column(Modifier.fillMaxSize().then(if (awake) Modifier.keepScreenOn() else Modifier)) {
        TopBar(PHONE_SETUP_TITLE, back)
        when (val s = state) {
            PhoneSetupState.Starting -> Message(null) {}
            PhoneSetupState.NoNetwork -> Message(PHONE_SETUP_NO_NETWORK) {
                Text("Connect it to Wi-Fi or Ethernet. A VPN on this TV can't be used for this.", style = AppType.secondary, color = colors.textSecondary)
            }
            is PhoneSetupState.Waiting -> Waiting(s)
            PhoneSetupState.Expired -> Ended(PHONE_SETUP_EXPIRED, vm::newCode)
            PhoneSetupState.Locked -> Ended(PHONE_SETUP_LOCKED, vm::newCode)
            PhoneSetupState.NetworkChanged -> Ended(PHONE_SETUP_NETWORK_CHANGED, vm::newCode)
            else -> if (reviewing) Review(s, vm, onDone = onDone, onLeave = leave)
        }
    }
    if (askDiscard) {
        val keep = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            modifier = Modifier.searchKeyOpensSearch { askDiscard = false },
            containerColor = colors.surface,
            title = { Text(PHONE_SETUP_DISCARD_ASK, style = AppType.cardTitle, color = colors.text) },
            text = { Text("To send it again, the phone needs a new code.", style = AppType.body, color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = { askDiscard = false; vm.discard(); leave() }) { Text("Discard", style = AppType.bodyStrong, color = colors.warnStrong) }
            },
            dismissButton = {
                // Keep first: Back twice in a row must not throw away what was typed on the phone.
                TextButton(onClick = { askDiscard = false }, modifier = Modifier.focusRequester(keep)) { Text("Keep", style = AppType.body, color = colors.text) }
                LaunchedEffect(Unit) { keep.requestFocusWhenReady() }
            },
        )
    }
}

/** A focus request that waits for the node: a dialog's window may not be laid out on its first frame. */
private suspend fun FocusRequester.requestFocusWhenReady() {
    repeat(10) {
        if (tryFocus()) return
        delay(16)
    }
}

/** A state with nothing to do but read it: its [title], what [content] adds, the help line. */
@Composable
private fun Message(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        title?.let { Text(it, style = AppType.cardTitle, color = colors.text) }
        content()
        Text(PHONE_SETUP_SAME_NETWORK, style = AppType.secondary, color = colors.muted)
    }
}

/** A code that ended ([why]): New code, which takes the focus. */
@Composable
private fun Ended(why: String, onNewCode: () -> Unit) {
    val button = remember { FocusRequester() }
    val initial = rememberInitialFocus()
    LaunchedEffect(why) { initial.request(button) }
    Message(why) {
        PrimaryButton("New code", onNewCode, Modifier.focusRequester(button))
    }
}

/**
 * The code and its address, the steps and the countdown. Nothing here is pressed: a button would be a stop for a stray
 * OK (New code would end the code the phone is filling in), and Back leaves. At a large font size it doesn't all fit,
 * and the page then scrolls with the remote ([remoteScroll]).
 */
@Composable
private fun Waiting(s: PhoneSetupState.Waiting) {
    val tv = LocalFormFactor.current == FormFactor.Tv
    val steps: @Composable ColumnScope.() -> Unit = {
        Text("1  $PHONE_SETUP_SCAN", style = AppType.body, color = colors.text)
        // Without http://, the code in fours, the authority on a line of its own: what a person types.
        val (authority, code) = s.display.substringBefore('/') to s.display.substringAfter('/')
        Text("$authority/\n$code", style = AppType.address, color = colors.text, modifier = Modifier.semantics { phoneSetupPart = ADDRESS })
        Text("2  ${stepsLine(s.mode)}", style = AppType.body, color = colors.text)
        Text("3  $PHONE_SETUP_SAVE_HERE", style = AppType.body, color = colors.text)
        // Not a live region: it would be read out every second.
        Text("Waiting for your phone · the code works for ${countdown(s.left)}", style = AppType.secondary, color = colors.textSecondary)
        if (s.wrongTries > 0) Text("${s.wrongTries} of ${SetupLimits().wrongTries} wrong attempts", style = AppType.secondary, color = colors.warnStrong)
        s.openedBy?.let {
            Text("Page opened on $it", style = AppType.secondary, color = colors.textSecondary)
            Text(progressLine(s.step), style = AppType.secondary, color = colors.textSecondary)
        }
        // Not a live region either: TalkBack reads it with the rest of the page.
        s.phoneTest?.let { Text(phoneTestLine(it), style = AppType.secondary, color = colors.textSecondary) }
        s.rejected?.let { Text(unreadable(it), style = AppType.secondary, color = colors.warnStrong) }
    }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().remoteScroll(scroll).verticalScroll(scroll).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val qr = @Composable { QrCode(s.url, PHONE_SETUP_QR, Modifier.semantics { phoneSetupPart = QR }, size = if (tv) TvDimens.qr else 200.dp) }
        if (tv) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                qr()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), content = steps)
            }
        } else {
            qr()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = steps)
        }
        Warning()
        Text(PHONE_SETUP_SAME_NETWORK, style = AppType.secondary, color = colors.muted)
    }
}

/**
 * A page with nothing to press that the remote must still read to its end when it doesn't fit: while it overflows it
 * takes the focus itself (OK does nothing there), and Up and Down scroll it a few lines at a time. Without a stop for
 * the focus, the scroll would follow nothing and the end of the page would stay out of reach.
 */
@Composable
private fun Modifier.remoteScroll(scroll: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { REMOTE_SCROLL_STEP.toPx() }
    // Before its first layout a scroll's size is unknown, and its end said to be far off.
    val overflows = scroll.viewportSize > 0 && scroll.maxValue > 0
    val self = remember { FocusRequester() }
    val initial = rememberInitialFocus()
    LaunchedEffect(overflows) { if (overflows) initial.request(self) }
    return onKeyEvent { event ->
        val by = when {
            event.type != KeyEventType.KeyDown -> return@onKeyEvent false
            event.key == Key.DirectionDown && scroll.canScrollForward -> step
            event.key == Key.DirectionUp && scroll.canScrollBackward -> -step
            else -> return@onKeyEvent false
        }
        scope.launch { scroll.animateScrollBy(by) }
        true
    }.focusRequester(self).focusable(enabled = overflows)
}

/** How far Up or Down scrolls a page that doesn't fit: a few lines, so no text taller than the rest of it is skipped. */
private val REMOTE_SCROLL_STEP = 120.dp

/** The page crosses the home network as plain text: said in the first run's bordered note, no stop for the focus. */
@Composable
private fun Warning() {
    val warn = colors.warnStrong
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = warn)) { append(SETUP_NOT_ENCRYPTED.substringBefore(' ')) }
            append(SETUP_NOT_ENCRYPTED.substring(SETUP_NOT_ENCRYPTED.indexOf(' ')))
        },
        style = AppType.secondary, color = colors.textSecondary,
        modifier = Modifier.fillMaxWidth().border(1.dp, colors.chip, RoundedCornerShape(14.dp)).padding(12.dp),
    )
}

/**
 * What arrived, as the TV's review mockup has it: on a TV two panes, on the left the summary and Save, on the right each
 * change; then, as Save tests what the phone didn't, each one's progress; failures with Try again, Save anyway and Leave
 * out, nothing saved until none is left; then what each came to. Each item is a stop for the focus that does nothing on
 * OK, so a long list scrolls with the remote. It appears unprompted, its first focus on Save: an OK meant for something
 * else in its first second is ignored.
 */
@Composable
private fun Review(s: PhoneSetupState, vm: PhoneSetupViewModel, onDone: () -> Unit, onLeave: () -> Unit) {
    val review = when (s) {
        is PhoneSetupState.Received -> s.review
        is PhoneSetupState.Testing -> s.review
        is PhoneSetupState.Results -> s.review
        is PhoneSetupState.Saving -> s.review
        is PhoneSetupState.Saved -> s.review
        else -> return
    }
    val statuses = when (s) {
        is PhoneSetupState.Testing -> s.items
        is PhoneSetupState.Results -> s.items
        is PhoneSetupState.Saved -> s.items
        else -> emptyMap()
    }
    val results = s as? PhoneSetupState.Results
    val busy = s is PhoneSetupState.Testing || s is PhoneSetupState.Saving
    val primary = remember { FocusRequester() }
    val rowFocus = remember(review) { review.items.associate { it.key to FocusRequester() } }
    val initial = rememberInitialFocus()
    val focus = results?.focus
    // The failing ones too: Leave out or Save anyway on a row other than the focus target takes its buttons away, the
    // focus with them, while the target stays the same.
    val failing = results?.items?.filterValues { it is ItemStatus.Failed }?.keys
    LaunchedEffect(review, s::class, focus, results?.settled, failing) {
        when {
            results != null -> initial.request(focus?.let(rowFocus::getValue) ?: primary)
            !busy -> initial.request(primary)
        }
    }
    val tv = LocalFormFactor.current == FormFactor.Tv
    val left: @Composable ColumnScope.() -> Unit = {
        Text(PHONE_SETUP_REVIEW, style = AppType.cardTitle, color = colors.text)
        review.sentFrom?.let { Text("Sent from $it", style = AppType.secondary, color = colors.muted) }
        Text(review.summary, style = AppType.body, color = colors.textSecondary)
        // As Save's tests and choices leave it: a new item failing or left out takes no place of what goes.
        val warnings = when (s) {
            is PhoneSetupState.Testing -> review.warnings(s.items)
            is PhoneSetupState.Results -> review.warnings(s.items)
            is PhoneSetupState.Saving -> review.warnings(s.items)
            is PhoneSetupState.Saved -> review.warnings(s.items)
            else -> review.warnings
        }
        warnings.forEach { Text(it, style = AppType.secondary, color = colors.warnStrong) }
        val wide = Modifier.fillMaxWidth()
        // One button, its words and its press by the state: Save stays while testing and saving, as "Testing…" doing
        // nothing, so the focus stays on it, and turns into Done.
        val (label, press) = when {
            s is PhoneSetupState.Saved -> PHONE_SETUP_DONE to onDone
            review.items.isEmpty() -> PHONE_SETUP_DONE to onLeave
            s is PhoneSetupState.Saving -> "Saving…" to {}
            s is PhoneSetupState.Testing -> "Testing…" to {}
            // A failure waits: Save comes once none is left.
            results != null && !results.settled -> null to {}
            results != null -> PHONE_SETUP_SAVE to vm::saveNow
            else -> PHONE_SETUP_SAVE to vm::save
        }
        label?.let { PrimaryButton(it, press, wide.focusRequester(primary)) }
        SecondaryButton(PHONE_SETUP_SEND_AGAIN, { if (!busy) vm.newCode() }, if (label == null) wide.focusRequester(primary) else wide)
        if (s !is PhoneSetupState.Saved && review.items.isNotEmpty()) Text(PHONE_SETUP_REVIEW_NOTE, style = AppType.small, color = colors.muted)
    }
    val right: @Composable ColumnScope.() -> Unit = {
        if (review.items.isNotEmpty()) {
            SectionHeader("Changes", top = 0.dp)
            GroupCard {
                review.items.forEachIndexed { i, item ->
                    if (i > 0) InsetDivider()
                    ItemRow(item, statuses[item.key], s is PhoneSetupState.Received)
                    val failed = statuses[item.key] as? ItemStatus.Failed
                    val first = Modifier.focusRequester(rowFocus.getValue(item.key))
                    val buttons = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    if (results != null && failed != null) {
                        Row(buttons, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (failed.canRetry) {
                                SecondaryButton(PHONE_SETUP_TRY_AGAIN, { vm.retry(item.key) }, first.semantics { phoneSetupPart = retryPart(item.part) })
                            }
                            if (failed.canSaveAnyway) SecondaryButton(PHONE_SETUP_SAVE_ANYWAY, { vm.saveAnyway(item.key) })
                            SecondaryButton(PHONE_SETUP_LEAVE_OUT, { vm.leaveOut(item.key) }, if (failed.canRetry) Modifier else first)
                        }
                    } else if ((s as? PhoneSetupState.Testing)?.retry == item.key) {
                        // In Try again's place while it runs, doing nothing: a node of its own, so the focus goes to the
                        // left pane's Testing… meanwhile, and the results' focus brings it back once they return.
                        Row(buttons) { SecondaryButton("Testing…", {}, first.semantics { phoneSetupPart = retryPart(item.part) }) }
                    }
                }
            }
        }
        if (review.unchanged.isNotEmpty()) {
            Text("Unchanged: ${review.unchanged.joinToString(", ")}.", style = AppType.small, color = colors.muted, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
    val guard = Modifier.fillMaxWidth().okGuard(REVIEW_GUARD_MS)
    if (tv) {
        Row(guard.fillMaxSize().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(TvDimens.paneGap)) {
            Column(Modifier.width(TvDimens.pagePane).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = left)
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = right)
        }
    } else {
        Column(guard.verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = left)
            right()
        }
    }
}

/**
 * An item as it arrived: its kind and what it does ("Usenet provider · Changes “Main”", a removal in the warning colour),
 * its lines, why it can't be saved, and whether the phone tested it ([fresh]: before Save) or how Save's test went.
 */
@Composable
private fun ItemRow(item: ReviewItem, status: ItemStatus?, fresh: Boolean) {
    Column(
        Modifier.fillMaxWidth().focusOutline().focusable().semantics(mergeDescendants = true) { phoneSetupPart = item.part }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val muted = if (item.warn) colors.warnStrong else colors.muted
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = muted)) { append("${item.title} · ") }
                withStyle(SpanStyle(color = if (item.warn) colors.warnStrong else colors.text)) { append(item.heading) }
            },
            style = AppType.body,
        )
        item.lines.forEach { Text(it.text, style = AppType.secondary, color = if (it.warn) colors.warnStrong else colors.muted) }
        when {
            status is ItemStatus.Running -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
                Text("Testing…", style = AppType.secondary, color = colors.textSecondary)
            }
            status is ItemStatus.Passed -> if (item.action != ReviewAction.Remove) ResultLine(true, status.message)
            status is ItemStatus.Failed -> ResultLine(false, status.message)
            status is ItemStatus.Anyway -> ResultLine(false, "${status.message} · $PHONE_SETUP_SAVED_ANYWAY")
            status == ItemStatus.LeftOut -> Text(PHONE_SETUP_LEFT_OUT, style = AppType.secondary, color = colors.muted)
            status is ItemStatus.Done -> ResultLine(status.ok, status.message)
            item.problem != null -> ResultLine(false, item.problem.orEmpty())
            fresh && item.action != ReviewAction.Remove ->
                if (item.tested) Text("✓ $PHONE_TESTED", style = AppType.secondary, color = colors.good) else Text(TESTED_AT_SAVE, style = AppType.secondary, color = colors.muted)
        }
    }
}

/** How long an OK on the review is ignored after it appears. */
private const val REVIEW_GUARD_MS = 1_000L

/** Waiting's second step: what the phone's page asks of the user, by its [mode]. */
internal fun stepsLine(mode: SetupMode): String = if (mode == SetupMode.Wizard) PHONE_SETUP_FOLLOW else PHONE_SETUP_CHANGE

/** "On your phone: step 2 of 4, indexers": where the phone's page is, from the server's events. */
internal fun progressLine(step: SetupStep): String = "On your phone: " + when (step) {
    SetupStep.Providers -> "step 1 of 4, providers"
    SetupStep.Indexers -> "step 2 of 4, indexers"
    SetupStep.Tmdb -> "step 3 of 4, TMDB key"
    SetupStep.Check -> "step 4 of 4, check and send"
    SetupStep.Edit -> "editing"
}

/**
 * "Tested from your phone: Provider 1 works · Indexer 2 failed": each card's latest test from the phone's page, by the
 * card's place, in the page's order. A place, never a name: names are typed on the phone and can say anything.
 */
internal fun phoneTestLine(test: PhoneTest): String = when (test) {
    PhoneTest.Running -> PHONE_SETUP_TESTING
    is PhoneTest.Finished -> "Tested from your phone: " +
        test.outcomes.entries.sortedWith(compareBy({ it.key.section.ordinal }, { it.key.number })).joinToString(" · ") { (slot, outcome) ->
            val how = when (outcome) {
                SetupOutcome.Passed -> "works"
                SetupOutcome.Failed -> "failed"
                SetupOutcome.NotTested -> "not tested"
            }
            "${slot.label} $how"
        }
}

/** "9:41": minutes and seconds left, rounded up, so it reads 0:01 to the end. */
internal fun countdown(leftMillis: Long): String {
    val seconds = (leftMillis.coerceAtLeast(0) + 999) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** What the TV says of a form it couldn't read: the field at fault, never what was in it. */
private fun unreadable(rejection: SetupRejection): String {
    val field = rejection.field ?: return "What your phone sent couldn't be read. Check it there and send again."
    val section = when (field.section) {
        SetupSection.Provider -> "Usenet provider"
        SetupSection.Indexer -> "Indexer"
        SetupSection.Service -> "Debrid service"
        SetupSection.TorrentIndexer -> "Torrent indexer"
        SetupSection.Tmdb -> "TMDB"
    }
    return "What your phone sent couldn't be read: check $section › ${field.label} there and send again."
}
