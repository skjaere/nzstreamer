package app.nzstreamer.plugin.sportarr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FieldKind
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.FormField
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.tvFocusMemory

/**
 * The setup form's first lines: [title], the [intro] on the tab's first screen, the Sportarr address and the API key, with
 * their help. Test and Save come with the rest of the form, so for now nothing typed here is kept or sent anywhere: nothing
 * reaches Sportarr before Test or Save (L640). [onBack] is the settings page's way back; the tab's own page has none.
 *
 * On a TV the address takes the first focus once the rail lets it, and the page keeps the field it had when the focus comes
 * back from the rail (L590, L660).
 */
@Composable
internal fun SetupPage(title: String, intro: String?, onBack: (() -> Unit)?) {
    // Only strings in saved state, which the host restores with its own class loader (NzPlugin's rule).
    var address by rememberSaveable { mutableStateOf("") }
    // Never in saved state: a key is kept nowhere but the host's secrets.
    var key by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val initial = rememberInitialFocus()
    val tv = LocalFormFactor.current == FormFactor.Tv
    LaunchedEffect(tv) { if (tv) initial.request(first) }
    Column(Modifier.fillMaxSize().tvFocusMemory().verticalScroll(rememberScrollState())) {
        TopBar(title, onBack = onBack)
        intro?.let { Text(it, style = AppType.body, color = colors.textSecondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FormField(
                Words.ADDRESS, address, { address = it }, Modifier.focusRequester(first), kind = FieldKind.Url, mono = true,
                placeholder = Words.ADDRESS_PLACEHOLDER, help = Words.ADDRESS_HELP,
            )
            FormField(Words.KEY, key, { key = it }, kind = FieldKind.Key, mono = true, help = Words.KEY_HELP, spoken = true)
        }
    }
}
