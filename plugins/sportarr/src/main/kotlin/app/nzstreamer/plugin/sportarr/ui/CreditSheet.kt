package app.nzstreamer.plugin.sportarr.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.play.okGuard
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.OwnedRipple
import io.skjaere.usenet.app.ui.PaneMemory
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.TvSafeArea
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.focusScale
import io.skjaere.usenet.app.ui.paneElement
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.app.ui.turnsOver

/*
 * The credit under a UFC card's bouts and its sheet, "About the bouts" (L-B7; BtPhoneCard, BtPhoneCredit, BtTvCard): where
 * the card comes from, its licence, what the bouts leave out, and on a phone the way to Wikipedia's page, which shows
 * the results.
 */

/** CC BY-SA 4.0's own page, which the licence asks a credit to link to. */
internal const val LICENCE_ADDRESS = "https://creativecommons.org/licenses/by-sa/4.0/"

/**
 * Opens [url] with [opener] (Compose's, which hands it to whatever opens web links); null once it did. Where nothing can
 * (some locked-down phones have no browser: Compose's opener throws IllegalArgumentException, an Intent with no activity
 * ActivityNotFoundException), the address, for the sheet to show under the link, as NZStreamer's own links do. A throw
 * from a plugin's click would end NZStreamer, and turn the plugin off at its next start.
 */
internal fun open(url: String, opener: (String) -> Unit): String? = try {
    opener(url)
    null
} catch (e: IllegalArgumentException) {
    url
} catch (e: ActivityNotFoundException) {
    url
}

/**
 * The credit line on a phone (BtPhoneCard): ⓘ and its words, small and muted, a 48 dp target; a tap opens its sheet
 * ([onOpen]), which the page holds: the row is an item of the page's list, there only while it is in view.
 */
@Composable
internal fun PhoneCreditRow(actions: PageActions, onOpen: () -> Unit) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).fillMaxWidth().focusRequester(actions.focus.requester(PageKeys.CREDIT))
            .heightIn(min = 48.dp).focusOutline().clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.muted)
        Text(Words.Bouts.CREDIT, style = AppType.small, color = colors.muted)
    }
}

/**
 * The credit line on a TV (BtTvCard), the right pane's last stop: as a bout's row, turned over while focused, and Right
 * does nothing. OK opens its sheet, whose Close takes the focus; as the sheet closes the focus is back here.
 */
@Composable
internal fun TvCreditRow(credit: Credit, panes: PaneMemory, actions: PageActions) {
    var about by rememberSaveable { mutableStateOf(false) }
    var closed by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    LaunchedEffect(closed) {
        if (!closed) return@LaunchedEffect
        // The sheet's window gone, the page's takes the focus again a frame or two later.
        for (frame in 0 until FOCUS_FRAMES) {
            withFrameNanos {}
            if (actions.focus.requester(PageKeys.CREDIT).tryFocus()) break
        }
        closed = false
    }
    Row(Modifier.fillMaxWidth().padding(start = 5.dp, end = 13.dp + TV_MORE, top = 6.dp)) {
        OwnedRipple {
            Row(
                Modifier.paneElement(panes, RIGHT, PageKeys.CREDIT).focusProperties { right = FocusRequester.Cancel }.fillMaxWidth().heightIn(min = 44.dp)
                    .focusScale(interaction, { it.rowScale }).clip(RoundedCornerShape(10.dp))
                    .then(if (turned) Modifier.background(colors.text) else Modifier)
                    .clickable(interaction, LocalIndication.current, role = Role.Button) { about = true }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(20.dp), tint = if (turned) colors.turnedSecondary else colors.muted)
                Text(Words.Bouts.CREDIT, style = AppType.secondary, color = if (turned) colors.background else colors.muted)
            }
        }
    }
    if (about) {
        CreditSheet(credit, tv = true) {
            about = false
            closed = true
        }
    }
}

/**
 * "About the bouts", material3's sheet with the kit's OK guard on it, as the play sheet's are: the OK that opened it,
 * pressed twice or held (a remote over CEC sends a held key as separate presses), never presses Close. On a phone:
 * the attribution with the licence linked, what the bouts leave out, the warning that Wikipedia's page shows the results,
 * View on Wikipedia and Close. On a TV, 640 dp at the bottom: the attribution as plain words, what is left out, and Close,
 * which takes the focus; no link and no warning, as a TV may have no browser. Close, Back or a tap beside it closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreditSheet(credit: Credit, tv: Boolean, onClose: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // A remote can't drag, and the handle would take the focus first.
    val handle: (@Composable () -> Unit)? = if (tv) null else ({ BottomSheetDefaults.DragHandle() })
    ModalBottomSheet(onDismissRequest = onClose, modifier = Modifier.okGuard(), sheetState = sheet, containerColor = colors.background, dragHandle = handle) {
        if (tv) TvSheet(credit, onClose) else PhoneSheet(credit, onClose)
    }
}

@Composable
private fun PhoneSheet(credit: Credit, onClose: () -> Unit) {
    val opener = LocalUriHandler.current
    // A link nothing could open: its address, under it.
    var licence by remember { mutableStateOf<String?>(null) }
    var article by remember { mutableStateOf<String?>(null) }
    Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
        Text(Words.Bouts.ABOUT, style = AppType.screenTitle, color = colors.text, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp))
        val linked = TextLinkStyles(SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline))
        Text(
            buildAnnotatedString {
                append(Words.Bouts.attribution(credit.article).removeSuffix(Words.Bouts.LICENCE))
                withLink(LinkAnnotation.Clickable(LICENCE_TAG, linked) { licence = open(LICENCE_ADDRESS, opener::openUri) }) { append(Words.Bouts.LICENCE) }
            },
            style = AppType.body, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp),
        )
        licence?.let { Address(it, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp)) }
        Text(Words.Bouts.LEFT_OUT, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp))
        // As Search's note: the mark and the words in the warning colour, read once by TalkBack.
        Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp).fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Words.Bouts.WARN, style = AppType.secondary, color = colors.warn)
            Text(Words.Bouts.SHOWS_RESULTS, style = AppType.secondary, color = colors.warn, modifier = Modifier.weight(1f))
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            credit.address?.let { address ->
                SecondaryButton(Words.Bouts.VIEW, { article = open(address, opener::openUri) }, Modifier.fillMaxWidth())
                article?.let { Address(it, Modifier.padding(horizontal = 4.dp)) }
            }
            SecondaryButton(Words.Bouts.CLOSE, onClose, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TvSheet(credit: Credit, onClose: () -> Unit) {
    val close = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        for (frame in 0 until FOCUS_FRAMES) {
            if (close.tryFocus()) break
            withFrameNanos {}
        }
    }
    Column(Modifier.padding(bottom = TvSafeArea.vertical)) {
        Text(Words.Bouts.ABOUT, style = AppType.screenTitle, color = colors.text, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp))
        Text(Words.Bouts.attribution(credit.article), style = AppType.body, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp))
        Text(Words.Bouts.LEFT_OUT, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp))
        SecondaryButton(
            Words.Bouts.CLOSE, onClose,
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().focusRequester(close).focusOutline(RoundedCornerShape(24.dp), fill = false),
        )
    }
}

/** A link's address where nothing opens it, small and muted, to type into a browser elsewhere. */
@Composable
private fun Address(address: String, modifier: Modifier) {
    Text(address, style = AppType.small, color = colors.muted, modifier = modifier)
}

private const val LICENCE_TAG = "licence"

/** Frames the focus is asked again for, as the sheet's window, or the page's again, may take it a frame late. */
private const val FOCUS_FRAMES = 10
