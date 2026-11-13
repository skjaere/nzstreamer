package io.skjaere.usenet.app.link

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FieldKind
import io.skjaere.usenet.app.ui.FormField
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.PlexMono
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.Sora
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.link.KeyId
import java.net.InetSocketAddress

/**
 * Add a TV on a phone (PtPhonePair, PtPhonePairFail, W3): the TVs nearby taking pairings, or the TV's address typed;
 * then connecting, the number large with Yes and No, "Now choose Pair", and paired. Each failure says what to do next, with
 * Try again and Close. Back steps back within the page first (the address to the list, a pairing under way stopped);
 * Done, and Close, leave it; Done tells [onPaired] the TV paired (opened from the Play on sheet, that chooses it). [again]:
 * Pair again's TV (L30). [deps]: for tests, fakes or a test's own links in place of the app's.
 */
@Composable
fun AddTvScreen(onDone: () -> Unit, onBack: () -> Unit, again: KeyId? = null, deps: AddTvDeps? = null, onPaired: (KeyId) -> Unit = {}) {
    val context = LocalContext.current
    val vm: AddTvViewModel = viewModel { AddTvViewModel(deps ?: AddTvDeps.of(context.container), again) }
    LaunchedEffect(vm) { vm.start() }
    val state by vm.state.collectAsStateWithLifecycle()
    val back = { if (!vm.back()) onBack() }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(if (state.step == AddTvStep.Address) ENTER_ADDRESS else ADD_TV_TITLE, back)
        when (val step = state.step) {
            AddTvStep.Nearby -> Nearby(state, vm::pick, vm::enterAddress)
            AddTvStep.Address -> Address(state, vm::type, vm::connect)
            is AddTvStep.Connecting -> Centred(150.dp) {
                CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 4.dp, color = colors.accent, trackColor = colors.outline)
                Text(connectingTo(step.target.name), style = AppType.cardTitle, color = colors.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp).heard())
                Text(shown(step.target.address), style = MONO, color = colors.muted)
                Buttons(20.dp) { SecondaryButton("Cancel", vm::cancel, Modifier.fillMaxWidth()) }
            }
            is AddTvStep.Waiting -> Centred(150.dp) {
                CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 4.dp, color = colors.accent, trackColor = colors.outline)
                Text(waitingWords(step.target.name), style = AppType.body, color = colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp).heard())
                Buttons(20.dp) { SecondaryButton("Cancel", vm::cancel, Modifier.fillMaxWidth()) }
            }
            is AddTvStep.Compare -> Centred(72.dp) {
                Text(doesItShow(step.name), style = AppType.screenTitle, color = colors.text, textAlign = TextAlign.Center, modifier = Modifier.heard())
                Number(step.digits, NUMBER, colors.text)
                Text(COMPARE_HOW, style = AppType.body, color = colors.textSecondary, textAlign = TextAlign.Center)
                Buttons(12.dp) {
                    PrimaryButton(YES_IT_MATCHES, vm::yes, Modifier.fillMaxWidth())
                    SecondaryButton("No", vm::no, Modifier.fillMaxWidth())
                }
            }
            is AddTvStep.Confirming -> Centred(96.dp) {
                Text(choosePairOn(step.name), style = AppType.screenTitle, color = colors.text, textAlign = TextAlign.Center, modifier = Modifier.heard())
                Number(step.digits, NUMBER_SMALL, colors.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent, trackColor = colors.outline)
                    Text(WAITING_FOR_TV, style = AppType.body, color = colors.textSecondary)
                }
                Buttons(20.dp) { SecondaryButton("Cancel", vm::cancel, Modifier.fillMaxWidth()) }
            }
            is AddTvStep.Paired -> Result(Icons.Filled.Check, colors.goodBg, colors.good, isReady(step.name), pairedLine(step.name)) {
                PrimaryButton("Done", {
                    onPaired(step.key)
                    onDone()
                }, Modifier.fillMaxWidth())
            }
            is AddTvStep.Failed -> Result(markOf(step.failure), colors.warnBg, colors.warnStrong, failureTitle(step.failure, step.name), failureText(step.failure, step.name)) {
                if (step.target != null) PrimaryButton("Try again", vm::tryAgain, Modifier.fillMaxWidth())
                SecondaryButton("Close", onDone, Modifier.fillMaxWidth())
            }
        }
    }
}

/** The TVs nearby (PtPhonePair 1), or, nothing found in its time, the way to type an address made the main one (PtPhonePairFail). */
@Composable
private fun Nearby(state: AddTvState, onPick: (NearbyTv) -> Unit, onAddress: () -> Unit) {
    Text(ADD_TV_HINT, style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp))
    SectionHeader(TVS_NEARBY, top = 14.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.accent, trackColor = colors.outline)
            Text(if (state.nothingYet) STILL_LOOKING else LOOKING, style = AppType.small, color = colors.muted)
        }
    }
    when {
        state.nearby.isNotEmpty() -> {
            GroupCard {
                state.nearby.forEachIndexed { i, tv ->
                    if (i > 0) InsetDivider()
                    FoundRow(tv) { onPick(tv) }
                }
            }
            Text(NOT_LISTED, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp))
            SecondaryButton(ENTER_ADDRESS, onAddress, Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth())
        }
        state.nothingYet -> {
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.warnStrong, modifier = Modifier.padding(top = 1.dp).size(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(NO_TVS_YET, style = AppType.body, color = colors.text)
                    Text(SAME_NETWORK, style = AppType.secondary, color = colors.textSecondary)
                }
            }
            PrimaryButton(ENTER_ADDRESS, onAddress, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp).fillMaxWidth())
        }
        else -> {
            Text(NOT_LISTED, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            SecondaryButton(ENTER_ADDRESS, onAddress, Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth())
        }
    }
}

/** A TV nearby: its name and address, a tap pairs. */
@Composable
private fun FoundRow(tv: NearbyTv, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).focusOutline(RoundedCornerShape(16.dp))
            .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TvIcon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tv.name, style = AppType.body, color = colors.text)
            Text(shown(InetSocketAddress(tv.address, tv.port)), style = AppType.secondary, color = colors.muted)
        }
        Text("›", style = AppType.body, color = colors.muted)
    }
}

/** Enter the TV's address (PtPhonePair 2): monospaced, as the TV's page shows it. */
@Composable
private fun Address(state: AddTvState, onType: (String) -> Unit, onConnect: () -> Unit) {
    Text(ADDRESS_HINT, style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
    FormField(
        ADDRESS_LABEL, state.typed, onType, Modifier.padding(horizontal = 16.dp), kind = FieldKind.Host, mono = true,
        help = state.typedError ?: ADDRESS_HELP, helpWarn = state.typedError != null,
    )
    PrimaryButton("Connect", onConnect, Modifier.padding(horizontal = 16.dp, vertical = 24.dp).fillMaxWidth())
}

/**
 * A step's title as TalkBack meets it (L43): a heading, read out as it shows. Each step takes the whole page, so the
 * focus a tap left goes with the last one; without this the number, and how pairing ended, would come unannounced while
 * the TV's question runs out. The TV's question is heard so too (PairPhoneScreen): it comes by itself, and its first
 * focus, Pair, would be all TalkBack said of it.
 */
internal fun Modifier.heard() = semantics {
    heading()
    liveRegion = LiveRegionMode.Polite
}

/** A column of centred words under the bar, [top] down, as the mockup's steps are laid out. */
@Composable
private fun Centred(top: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = top, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun Buttons(top: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = top), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** The number in threes, on a card; TalkBack reads it digit by digit (L43). */
@Composable
private fun Number(digits: String, style: TextStyle, color: Color) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(horizontal = 12.dp, vertical = 18.dp), contentAlignment = Alignment.Center) {
        Text(numberShown(digits), style = style, color = color, modifier = Modifier.clearAndSetSemantics { contentDescription = numberSpoken(digits) })
    }
}

/** A step's end (PtPhonePair 5, PtPhonePairFail): its mark, what happened, what to do, and its buttons. */
@Composable
private fun Result(mark: ImageVector, markBg: Color, markFg: Color, title: String, text: String?, buttons: @Composable ColumnScope.() -> Unit) {
    Centred(110.dp) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(markBg), contentAlignment = Alignment.Center) {
            Icon(mark, contentDescription = null, tint = markFg, modifier = Modifier.size(32.dp))
        }
        Text(title, style = AppType.screenTitle, color = colors.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp).heard())
        text?.let { Text(it, style = AppType.body, color = colors.textSecondary, textAlign = TextAlign.Center) }
        Buttons(16.dp, buttons)
    }
}

/** A stop for what this phone or the TV ended; a warning for what is to be done somewhere first. */
private fun markOf(failure: AddTvFailure): ImageVector = when (failure) {
    AddTvFailure.NoMatch, AddTvFailure.CancelledOnTv, AddTvFailure.TimedOut, AddTvFailure.ClosedOnTv, AddTvFailure.NotSaved, AddTvFailure.Interfered, AddTvFailure.Stopped -> Icons.Filled.Close
    else -> Icons.Filled.Warning
}

/** An address as the TV's page shows it: "ip" on the default port, else "ip:port" (L12). */
private fun shown(address: InetSocketAddress): String =
    address.address.hostAddress.orEmpty() + if (address.port == PortChoice.DEFAULT) "" else ":${address.port}"

/** The number large (Sora 48) and grouped in threes (PtPhonePair 4). */
private val NUMBER = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 58.sp, letterSpacing = 3.sp)

/** Smaller and muted while the TV's Pair is awaited (PtPhonePair 4b). */
private val NUMBER_SMALL = NUMBER.copy(fontSize = 28.sp, lineHeight = 34.sp)

/** An address to read and type: the app's monospaced face at the secondary size. */
private val MONO = TextStyle(fontFamily = PlexMono, fontSize = 13.sp, lineHeight = 19.sp)
