package io.skjaere.usenet.app.link

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.OkGuard
import io.skjaere.usenet.app.play.okGuard
import io.skjaere.usenet.app.setup.PHONE_SETUP_NO_NETWORK
import io.skjaere.usenet.app.setup.PHONE_SETUP_SAME_NETWORK
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.Sora
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.TvDimens
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.rememberInitialFocus
import kotlinx.coroutines.delay

/** Waiting's first step (PtTvPair 1). */
fun pairStepOne(tvName: String) = "On your phone: NZStreamer › Play on › Add a TV, then pick $tvName."
const val PAIR_STEP_TWO = "Check that both screens show the same number, then choose Yes on the phone and Pair here."

/** "Not in the phone's list? …": the TV's address to type ([tvAddress]). */
fun pairTypeAddress(address: String) = "Not in the phone’s list? Choose “Enter the TV’s address” there and type $address."
const val PAIR_DOES_IT_SHOW = "Does your phone show this number?"
const val PAIR_IF_THEY_MATCH = "If they match, choose Pair here and Yes on the phone. If not, choose Cancel."

/** "Pair with Galaxy phone?" */
fun pairWith(name: String) = "Pair with $name?"

/** "Galaxy phone can now play on this TV" */
fun pairedNow(name: String) = "$name can now play on this TV"

/** The paired card's line (PtTvPair 3): how the phone plays here. */
fun pairedPlayOn(tvName: String) = "On the phone, open a show or film and choose Play on › $tvName. NZStreamer must be open here."
const val PAIR_REMOVE_ANY_TIME = "Remove it any time in Settings › Phones."

/**
 * Pair a phone on a TV (PtTvPair, W3, L22): the TV takes pairings only while this page is in front, and the screen stays
 * on while it waits and while a phone asks. Laid out as "Set up with your phone": a 240 dp card in the QR code's place,
 * with who is pairing, readable from the sofa. A phone's question appears by itself, its focus on Pair, so an OK in its
 * first [PairPhoneDeps.questionGuardMillis] is ignored: one meant for the page before, or pressed by reflex as the
 * question appears, doesn't pair. An OK after that is taken as meant, and pairing still needs the phone's Yes. Done, and
 * Back, return to Phones. [deps]: for tests, a fake listener in place of the app's.
 */
@Composable
fun PairPhoneScreen(onDone: () -> Unit, onBack: () -> Unit, deps: PairPhoneDeps? = null) {
    val context = LocalContext.current
    val d = remember { deps ?: PairPhoneDeps.of(context.container) }
    val vm: PairPhoneViewModel = viewModel { PairPhoneViewModel(d) }
    LifecycleStartEffect(vm) {
        vm.start()
        onStopOrDispose { vm.stop() }
    }
    val page by vm.page.collectAsStateWithLifecycle()
    val tv = LocalFormFactor.current == FormFactor.Tv
    Column(Modifier.fillMaxSize().then(if (page.keepsScreenOn) Modifier.keepScreenOn() else Modifier)) {
        TopBar(PAIR_PHONE_TITLE, onBack)
        when (val screen = page.screen) {
            PairScreen.Starting -> Unit
            PairScreen.NoNetwork -> Body(tv, card = { TvCard(d.name, null) }) {
                Text(PHONE_SETUP_NO_NETWORK, style = AppType.cardTitle, color = colors.text)
                Text("Connect it to Wi-Fi or Ethernet. A VPN on this TV can't be used for this.", style = AppType.secondary, color = colors.textSecondary)
            }
            PairScreen.NoKey -> NoKey(tv, d.name, onDone)
            is PairScreen.Waiting -> Waiting(tv, d.name, screen.address, d.okGuardMillis, onBack)
            is PairScreen.Asking -> Asking(tv, screen, page, d)
            is PairScreen.Paired -> Paired(tv, d.name, screen, onDone)
            is PairScreen.NotKept -> NotKept(tv, screen, onDone)
            PairScreen.Closed -> Closed(tv, d.name, onDone)
        }
    }
}

/** The card beside the words, then [foot] under both; side by side on a TV, one under the other on a phone. */
@Composable
private fun Body(tv: Boolean, card: @Composable () -> Unit, modifier: Modifier = Modifier, foot: String? = null, right: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (tv) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                card()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), content = right)
            }
        } else {
            card()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = right)
        }
        foot?.let { Text(it, style = AppType.secondary, color = colors.muted) }
    }
}

/** In TV-8's QR code's place (240 dp): who is pairing, its name and address big enough to read from the sofa. */
@Composable
private fun IdCard(icon: ImageVector, caption: String, name: String, address: String?, captionColor: Color = colors.muted) {
    Column(
        Modifier.width(TvDimens.qr).heightIn(min = TvDimens.qr).clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(48.dp))
        Text(caption, style = AppType.small, color = captionColor, modifier = Modifier.padding(top = 4.dp))
        Text(name, style = AppType.screenTitle, color = colors.text)
        address?.let { Text(it, style = AppType.address, color = colors.textSecondary) }
    }
}

@Composable
private fun TvCard(name: String, address: String?) = IdCard(TvIcon, "This TV", name, address)

/** A step: its number, then its words. */
@Composable
private fun Numbered(number: Int, words: androidx.compose.ui.text.AnnotatedString) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$number", style = AppType.body, color = colors.text)
        Text(words, style = AppType.body, color = colors.text)
    }
}

/**
 * Waiting for a phone: what to do on it, the address to type, and Cancel, the one stop, focused. It shows again as a
 * question goes, so OK presses in its first moments are ignored too: an OK meant for the question doesn't leave the page.
 */
@Composable
private fun Waiting(tv: Boolean, name: String, address: String, okGuardMillis: Long, onCancel: () -> Unit) {
    val cancel = remember { FocusRequester() }
    val initial = rememberInitialFocus()
    LaunchedEffect(Unit) { initial.request(cancel) }
    Body(tv, card = { TvCard(name, address) }, foot = PHONE_SETUP_SAME_NETWORK, modifier = Modifier.okGuard(okGuardMillis)) {
        Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent, trackColor = colors.outline)
            Text(PAIR_WAITING, style = AppType.cardTitle, color = colors.text)
        }
        val one = pairStepOne(name)
        Numbered(
            1,
            buildAnnotatedString {
                append(one.substringBefore(name))
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(name) }
                append(one.substringAfter(name))
            },
        )
        Numbered(2, buildAnnotatedString { append(PAIR_STEP_TWO) })
        Text(pairTypeAddress(address), style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.padding(top = 4.dp))
        SecondaryButton("Cancel", onCancel, Modifier.padding(top = 10.dp).widthIn(min = 160.dp).focusRequester(cancel))
    }
}

/** The number at Sora 56 for the sofa. */
private val NUMBER = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 64.sp, letterSpacing = 3.sp)

/**
 * A phone asks (PtTvPair 2): its name, the number, Pair focused and Cancel beside it, and how long the question has left.
 * Who asks is heard as it shows (L43), not only the Pair it focuses. OK presses in its first
 * [PairPhoneDeps.questionGuardMillis] are ignored, from the moment this question showed, on Pair as anywhere in it.
 */
@Composable
private fun Asking(tv: Boolean, screen: PairScreen.Asking, page: PairPage, deps: PairPhoneDeps) {
    val question = screen.question
    val pair = remember(question) { FocusRequester() }
    val initial = rememberInitialFocus()
    val guard = remember(question) { OkGuard(SystemClock.uptimeMillis(), deps.questionGuardMillis) }
    var now by remember(question) { mutableLongStateOf(deps.now()) }
    LaunchedEffect(question) {
        initial.request(pair)
        while (true) {
            delay(TICK_MS)
            now = deps.now()
        }
    }
    Box(Modifier.okGuard(guard)) {
        Body(tv, card = { IdCard(PhoneIcon, "Asking to pair", question.name, question.address) }) {
            Text(pairWith(question.name), style = AppType.screenTitle, color = colors.text, modifier = Modifier.heard())
            Text(PAIR_DOES_IT_SHOW, style = AppType.body, color = colors.textSecondary)
            // Read digit by digit (L43), not as a number in the hundreds of thousands.
            Text(numberShown(question.digits), style = NUMBER, color = colors.text, modifier = Modifier.clearAndSetSemantics { contentDescription = numberSpoken(question.digits) })
            Text(PAIR_IF_THEY_MATCH, style = AppType.secondary, color = colors.textSecondary)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", question::cancel, Modifier.widthIn(min = 150.dp))
                PrimaryButton("Pair", question::ok, Modifier.widthIn(min = 150.dp).focusRequester(pair))
            }
            // Not a live region: it would be read out every second.
            Text(closesIn(page.left(now, deps.questionMillis())), style = AppType.small, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
            if (screen.another) Text(PAIR_ANOTHER, style = AppType.secondary, color = colors.textSecondary)
        }
    }
}

/** Paired (PtTvPair 3): Done has the focus and returns to Phones. */
@Composable
private fun Paired(tv: Boolean, tvName: String, screen: PairScreen.Paired, onDone: () -> Unit) {
    val done = remember { FocusRequester() }
    val initial = rememberInitialFocus()
    LaunchedEffect(screen) { initial.request(done) }
    Body(tv, card = { IdCard(PhoneIcon, "Paired", screen.name, screen.address, captionColor = colors.good) }) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(colors.goodBg), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = colors.good, modifier = Modifier.size(28.dp))
        }
        Text(pairedNow(screen.name), style = AppType.screenTitle, color = colors.text, modifier = Modifier.padding(top = 6.dp))
        Text(pairedPlayOn(tvName), style = AppType.body, color = colors.textSecondary)
        Text(PAIR_REMOVE_ANY_TIME, style = AppType.secondary, color = colors.muted)
        PrimaryButton("Done", onDone, Modifier.padding(top = 10.dp).widthIn(min = 160.dp).focusRequester(done))
    }
}

/** Both said yes, and this TV couldn't keep the phone: why, and Done, focused, to leave; another phone may still ask. */
@Composable
private fun NotKept(tv: Boolean, screen: PairScreen.NotKept, onDone: () -> Unit) {
    val done = remember { FocusRequester() }
    val initial = rememberInitialFocus()
    LaunchedEffect(screen) { initial.request(done) }
    Body(tv, card = { IdCard(PhoneIcon, "Not paired", screen.name, null, captionColor = colors.warn) }) {
        Text(notKeptTitle(screen.name), style = AppType.screenTitle, color = colors.text)
        Text(if (screen.full) PAIR_NOT_KEPT_FULL else PAIR_NOT_KEPT, style = AppType.body, color = colors.textSecondary)
        PrimaryButton("Done", onDone, Modifier.padding(top = 10.dp).widthIn(min = 160.dp).focusRequester(done))
    }
}

/** No link key to pair with (L20): why, and Done, focused, to leave; the screen may sleep. */
@Composable
private fun NoKey(tv: Boolean, name: String, onDone: () -> Unit) {
    val done = remember { FocusRequester() }
    val initial = rememberInitialFocus()
    LaunchedEffect(Unit) { initial.request(done) }
    Body(tv, card = { TvCard(name, null) }) {
        Text(PAIR_NO_KEY, style = AppType.cardTitle, color = colors.text)
        Text(PAIR_NO_KEY_LINE, style = AppType.secondary, color = colors.textSecondary)
        PrimaryButton("Done", onDone, Modifier.padding(top = 10.dp).widthIn(min = 160.dp).focusRequester(done))
    }
}

/** Pairing closed by itself (L41): the screen may sleep again, and Done leaves. */
@Composable
private fun Closed(tv: Boolean, name: String, onDone: () -> Unit) {
    val done = remember { FocusRequester() }
    val initial = rememberInitialFocus()
    LaunchedEffect(Unit) { initial.request(done) }
    Body(tv, card = { TvCard(name, null) }) {
        Text(PAIR_CLOSED, style = AppType.cardTitle, color = colors.text)
        PrimaryButton("Done", onDone, Modifier.padding(top = 10.dp).widthIn(min = 160.dp).focusRequester(done))
    }
}

/** How often the question's countdown is read again. */
private const val TICK_MS = 250L
