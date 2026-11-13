package io.skjaere.usenet.app.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import io.skjaere.usenet.app.BuildConfig
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.openExternalUrl
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.NavRow
import io.skjaere.usenet.app.ui.ResultLine
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.pagesThrough
import io.skjaere.usenet.app.ui.tryFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which paragraph of the policy a node is, by its place on the page: for tests, which find the one a remote's focus is on. */
val PrivacyPart = SemanticsPropertyKey<Int>("PrivacyPart")
private var SemanticsPropertyReceiver.privacyPart by PrivacyPart

/**
 * Settings › Privacy (W60, L138, L139; CsPhoneSettingsCast 3), on phones and TVs. Once the site's page is live
 * ([privacyLink]): on a phone "Read it online" over the address, which opens it in the browser (or, with none, says where
 * to read it); on a TV the address alone, opening nothing (TV-WB); until then neither shows. Then the policy from the
 * app's own copy, so it reads offline, after the paragraph a build set up with someone's accounts says first
 * (SEED_DEV_SETTINGS), its debrid parts left out while debrid is hidden (L211, L192). Each paragraph, a heading with the
 * first under it, takes a remote's focus whole (the first once it is read, in the remote's input mode): Down and Up go a
 * paragraph at a time, paging through one taller than the screen, so the page scrolls on a TV. For TalkBack each heading
 * is a heading. Whether the site's page is [online] is [PRIVACY_ONLINE]'s to say, but in tests, which show the page both
 * ways.
 */
@Composable
fun PrivacyScreen(onBack: () -> Unit, online: Boolean = PRIVACY_ONLINE) {
    val context = LocalContext.current
    val formFactor = LocalFormFactor.current
    val link = privacyLink(tv = formFactor == FormFactor.Tv, online = online)
    val sections by produceState<List<PrivacySection>?>(null) {
        value = withContext(Dispatchers.IO) { privacyPage(readPrivacyPolicy(context), BuildConfig.SEED_DEV_SETTINGS, DebridGate.shown) }
    }
    var noBrowser by rememberSaveable { mutableStateOf(false) }
    val keys = LocalInputModeManager.current
    val first = remember { FocusRequester() }
    LaunchedEffect(sections != null) { if (sections != null && keys.inputMode == InputMode.Keyboard) first.tryFocus() }
    var room by remember { mutableIntStateOf(Int.MAX_VALUE) }
    // Scrolls only once the text is read: a turn brings back the place read to, which the top rows alone, before the text
    // is read again, have no room for, and would move back to the top.
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().onSizeChanged { room = it.height }.then(if (sections != null) Modifier.verticalScroll(scroll) else Modifier)) {
        TopBar(PRIVACY_TITLE, onBack)
        val url = link?.url
        when {
            // The site's page isn't live: nothing names it, and the policy comes under the top bar.
            link == null -> Unit
            // A remote's stop would open nothing: the address, for the user's phone or computer.
            url == null -> Text(link.title, style = AppType.body, color = colors.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            else -> {
                GroupCard(Modifier.padding(top = 8.dp)) {
                    NavRow(link.title, link.line, end = "↗") { noBrowser = !context.openExternalUrl(url, formFactor) }
                }
                if (noBrowser) {
                    // Selectable, to copy.
                    SelectionContainer(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                        ResultLine(false, "This device can't open web links. Visit $url in a browser elsewhere.")
                    }
                }
            }
        }
        // As the board has the text: 16 dp in and 16 dp under what is above it (the link, or the top bar while there is
        // none), the paragraphs 10 dp apart and 14 dp between parts, each with 8 dp beside it and 5 dp above and below
        // inside its own focus ring.
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 11.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val all = sections.orEmpty()
            // Where each part's paragraphs start in the page's count, worked out once rather than as each part composes.
            val starts = remember(all) { all.runningFold(0) { at, section -> at + section.paragraphs.size } }
            all.forEachIndexed { k, section ->
                Column {
                    section.paragraphs.forEachIndexed { i, paragraph ->
                        val index = starts[k] + i
                        PolicyParagraph(index, section.heading.takeIf { i == 0 }, paragraph, room, first.takeIf { index == 0 })
                    }
                }
            }
        }
    }
}

/**
 * A paragraph of the policy, one stop for a remote's focus, with its part's [heading] over it if it is the first, in the
 * cards' title style: ringed in cream on a TV (in the accent on a phone) and filled darker while it has the focus. One
 * taller than the screen's [room] pages ([pagesThrough]).
 */
@Composable
private fun PolicyParagraph(index: Int, heading: String?, paragraph: String, room: Int, focus: FocusRequester?) {
    val tv = LocalFormFactor.current == FormFactor.Tv
    Column(
        Modifier.fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .semantics { privacyPart = index }
            .pagesThrough(room)
            .focusOutline(RoundedCornerShape(12.dp), color = if (tv) colors.text else null)
            // Focusable without merging: TalkBack reads its heading and its paragraph each as its own.
            .focusable()
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        heading?.let { Text(it, style = AppType.cardTitle, color = colors.text, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() }) }
        Text(paragraph, style = AppType.secondary, color = colors.textSecondary)
    }
}
