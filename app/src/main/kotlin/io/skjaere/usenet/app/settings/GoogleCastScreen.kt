package io.skjaere.usenet.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.skjaere.usenet.app.casting.CAST_LOOK_FOR
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.NavRow
import io.skjaere.usenet.app.ui.SwitchRow
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.data.CastPrefs
import kotlinx.coroutines.launch

/**
 * Settings › Playback › Google Cast (W46, L112; CsPhoneSettingsCast frames 1, 2 and 4), on phones and tablets alone: the
 * Play on list's "Look for Cast devices" as a switch, with what Google's Cast software does once it runs. Its words, the
 * hub row's line and what the switch saves, where a JVM test reads them.
 */
object GoogleCastPage {
    const val TITLE = "Google Cast"

    /** The switch: the list's row, by its words (CsPhoneLookFor), so the two read as one choice. */
    const val SWITCH = CAST_LOOK_FOR
    const val DETAIL = "Finds Chromecasts and TVs with Google Cast on this Wi-Fi, with Google’s Cast software from Google Play services."

    /** Under the switch. Once started, Google's software runs until the process ends, so off counts from the next start (L112). */
    val FOOTNOTE = listOf(
        "Google’s Cast software starts when you open the list of devices to play on, and runs until NZStreamer closes. It sends " +
            "Google data about finding and using Cast devices, this phone and this app, also while NZStreamer is closed.",
        "Turned off, it no longer starts, from the next time NZStreamer opens, and the list asks again before it looks. " +
            "Your TVs with NZStreamer work either way.",
    )

    /** Under the footnote: the privacy page (L138), from where its Cast part is read. */
    const val PRIVACY_LINE = "What Google’s Cast software sends, and to whom"

    /** The hub row's line while this phone looks for Cast devices. */
    const val ON = "On: looks for Cast devices"

    /** The hub row's line while it doesn't (L149), as it comes after the update. */
    const val OFF = "Off"

    /** Whether the hub has the row, and the app the page: never on a TV, where nothing of Cast shows or starts (W28, L110). */
    fun shows(television: Boolean): Boolean = !television

    /** The hub row's line, by the opt-in as saved. */
    fun rowLine(look: Boolean): String = if (look) ON else OFF

    /**
     * What the switch saves as it is turned [on]: the opt-in alone. On counts as the list's tap; off has the list ask again
     * at once, and nothing of Google's start from the next start on (L112).
     */
    fun turned(prefs: CastPrefs, on: Boolean): CastPrefs = prefs.copy(look = on)
}

/**
 * Settings › Playback › Google Cast (CsPhoneSettingsCast 2): "Look for Cast devices" as a switch, then the footnote saying
 * when Google's Cast software starts and that turning it off counts from the next start, then Privacy ([onPrivacy], the
 * privacy page, L138). The switch shows the opt-in as the list reads it (off until settings.json is read). AppNav has no
 * such page on a TV.
 */
@Composable
fun GoogleCastScreen(onBack: () -> Unit, onPrivacy: () -> Unit) {
    val container = LocalContext.current.container
    val looking by container.castLooking.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(GoogleCastPage.TITLE, onBack)
        GroupCard(Modifier.padding(top = 8.dp)) {
            SwitchRow(
                GoogleCastPage.SWITCH, looking == true, detail = GoogleCastPage.DETAIL,
                onChange = { on ->
                    container.appScope.launch {
                        saveOrLog("turn ${if (on) "on" else "off"} Look for Cast devices") { container.settings.updateCast { GoogleCastPage.turned(it, on) } }
                    }
                },
            )
        }
        Footnote(GoogleCastPage.FOOTNOTE)
        GroupCard { NavRow(PRIVACY_TITLE, GoogleCastPage.PRIVACY_LINE, onClick = onPrivacy) }
    }
}
