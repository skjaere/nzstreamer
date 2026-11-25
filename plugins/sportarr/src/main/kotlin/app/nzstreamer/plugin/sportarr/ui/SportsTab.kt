package app.nzstreamer.plugin.sportarr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nzstreamer.plugin.sportarr.Session
import io.skjaere.usenet.app.tv.SwitchSide
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.tvFocusMemory

/**
 * The tab's own page (L652): the setup until an address and a key are saved, then the list. As the list shows, it is read
 * when none is kept or the one kept is over 15 minutes old (L641); a Save from the setup has asked for a read already.
 */
@Composable
internal fun SportsTab(session: Session) {
    val setUp by session.setUp.collectAsState(initial = null)
    when (setUp) {
        // The secrets not read yet: a moment, as the host's slot draws nothing while a plugin loads.
        null -> Unit
        false -> SetupPage(session, Words.Setup.TAB, Words.Setup.INTRO, onBack = null, onSaved = {})
        true -> {
            LaunchedEffect(session) { session.refresh.shown() }
            ListLoading()
        }
    }
}

/**
 * The list's first look while Sportarr is asked: the title line with Recent and Upcoming, then blank cards. On a TV the
 * focus lands on Recent while the cards are blank. The list's rows and its other states draw over it as they come.
 */
@Composable
private fun ListLoading() {
    val tv = LocalFormFactor.current == FormFactor.Tv
    var upcoming by remember { mutableStateOf(false) }
    val recent = remember { FocusRequester() }
    val initial = rememberInitialFocus()
    Column(Modifier.fillMaxSize().tvFocusMemory()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = if (tv) 8.dp else 18.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(Words.Setup.TAB, style = AppType.screenTitle, color = colors.text, modifier = Modifier.weight(1f).semantics { heading() })
            SwitchSide(Words.SportsList.RECENT, !upcoming, Modifier.focusRequester(recent)) { upcoming = false }
            SwitchSide(Words.SportsList.UPCOMING, upcoming, Modifier) { upcoming = true }
        }
        Bar(70.dp, Modifier.padding(start = 16.dp, top = 22.dp, bottom = 10.dp))
        repeat(2) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(colors.surface).padding(vertical = 6.dp),
            ) {
                Bar(110.dp, Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp))
                listOf(220.dp, 250.dp, 190.dp).forEach { width ->
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Bar(40.dp)
                        Bar(width, height = 14.dp)
                    }
                }
            }
        }
    }
    if (tv) LaunchedEffect(Unit) { initial.request(recent) }
}

/** A blank line of a card still to come, [width] wide at most. */
@Composable
private fun Bar(width: Dp, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    Box(modifier.width(width).height(height).clip(RoundedCornerShape(height / 2)).background(colors.chip))
}
