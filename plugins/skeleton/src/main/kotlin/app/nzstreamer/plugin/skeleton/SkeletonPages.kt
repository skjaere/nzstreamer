package app.nzstreamer.plugin.skeleton

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import io.skjaere.usenet.app.ui.NavRow
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.plugin.Found
import io.skjaere.usenet.plugin.ItemReleases
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.PluginHost
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.ReleaseInfo
import io.skjaere.usenet.plugin.SearchOffer
import kotlinx.coroutines.launch

/**
 * The tab's own page: the words to search (Copper Crown unless changed), Search, each indexer's line and the releases
 * found, best first, each of which Play plays as the skeleton's one item; then Mark watched, the second page, the
 * settings, and the page that throws. It searches only when Search is pressed, never as it opens (L515).
 */
@Composable
internal fun SearchPage(host: PluginHost, nav: PluginNav) {
    // Only strings in saved state: the host restores it with its own class loader (NzPlugin's rule).
    var words by rememberSaveable { mutableStateOf(Skeleton.WORDS) }
    // The words searched, kept only while the page lives: after a process death nothing is searched until asked again.
    var asked by remember { mutableStateOf<String?>(null) }
    val found by produceState<Found?>(null, asked) {
        value = null
        host.search.search(asked ?: return@produceState, runtimeMinutes = null).collect { value = it }
    }
    val watched by remember(host) { host.watch.observe(listOf(Skeleton.ITEM_KEY)) }.collectAsState(emptyMap())
    val first = rememberFirstFocus()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(Skeleton.LABEL, onBack = null)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FormField("Words", words, { words = it })
            PrimaryButton("Search", onClick = { asked = words }, modifier = Modifier.focusRequester(first))
            found?.let { answer ->
                answer.indexers.forEach { line -> Text("${line.name}: ${line.text}", style = AppType.secondary, color = colors.textSecondary) }
                answer.releases.take(SHOWN).forEach { release -> ReleaseRow(release) { play(host, release) } }
            }
            NavRow(
                "Mark ${Skeleton.ITEM_TITLE} watched",
                status = if (watched[Skeleton.ITEM_KEY]?.watched == true) "Watched" else "Not watched",
            ) {
                host.scope.launch { host.watch.mark(listOf(Skeleton.ITEM_KEY to Skeleton.ITEM_TITLE), watched = true) }
            }
            NavRow("Second page") { nav.open(Skeleton.SECOND, "Opened from the skeleton's first page") }
            NavRow("Settings") { nav.openSettings() }
            NavRow("Crash", status = "Throws as it is drawn") { nav.open(Skeleton.CRASH, null) }
        }
    }
}

/** A release found: its name, then its size and indexer, or why it doesn't play here. */
@Composable
private fun ReleaseRow(release: ReleaseInfo, onPlay: () -> Unit) {
    val line = if (release.playable) "${release.size / 1_000_000} MB · ${release.indexer}" else release.whyNot ?: release.indexer
    NavRow(release.name, status = line, onClick = onPlay)
}

/** Plays [release] as the skeleton's one item, its file the largest video in it. */
private fun play(host: PluginHost, release: ReleaseInfo) {
    val item = PlayItem(
        key = Skeleton.ITEM_KEY,
        title = Skeleton.ITEM_TITLE,
        subtitle = Skeleton.LABEL,
        runtimeMinutes = null,
        search = SearchOffer(Skeleton.WORDS, note = null),
        picker = LargestVideo,
    )
    host.play.play(item) { ItemReleases(accepted = listOf(release.handle)) }
}

/** A page opened over the first with [arg], and Back. */
@Composable
internal fun SecondPage(arg: String?, nav: PluginNav) {
    val first = rememberFirstFocus()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Second page", onBack = { nav.back() })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(arg ?: "Opened with nothing", style = AppType.body, color = colors.text)
            PrimaryButton("Back", onClick = { nav.back() }, modifier = Modifier.focusRequester(first))
        }
    }
}

/**
 * Throws as it is first drawn. Compose can't catch it, so it closes NZStreamer, and the crash guard blames this version
 * by its package at the next start ("Turned off after a crash", L619): the device tests' real crash.
 */
@Composable
internal fun CrashPage() {
    throw IllegalStateException("The skeleton's crash page")
}

/** The skeleton's settings: one key, kept as its secret, never shown again once saved. */
@Composable
internal fun SettingsPage(host: PluginHost, nav: PluginNav) {
    // Never in saved state: a key isn't kept anywhere but the host's secrets.
    var key by remember { mutableStateOf("") }
    val saved by remember(host) { host.secrets.has(Skeleton.SECRET) }.collectAsState(false)
    val first = rememberFirstFocus()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("${Skeleton.LABEL} settings", onBack = { nav.back() })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FormField(
                "Key", key, { key = it }, modifier = Modifier.focusRequester(first), kind = FieldKind.Key,
                help = if (saved) "A key is saved" else "No key saved",
            )
            PrimaryButton("Save", onClick = {
                val value = key.ifBlank { null }
                host.scope.launch { host.secrets.put(Skeleton.SECRET, value) }
                key = ""
            })
        }
    }
}

/** On a TV, the page's first focus, once the rail lets it take one ([rememberInitialFocus]); a phone's pages take none. */
@Composable
private fun rememberFirstFocus(): FocusRequester {
    val first = remember { FocusRequester() }
    val initial = rememberInitialFocus()
    val tv = LocalFormFactor.current == FormFactor.Tv
    LaunchedEffect(tv) { if (tv) initial.request(first) }
    return first
}

private const val SHOWN = 5
