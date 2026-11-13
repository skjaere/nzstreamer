package io.skjaere.usenet.app.settings

import android.os.Build
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.debrid.HubLine
import io.skjaere.usenet.app.debrid.debridServicesHubLine
import io.skjaere.usenet.app.debrid.torrentIndexersHubLine
import io.skjaere.usenet.app.link.PHONES_TITLE
import io.skjaere.usenet.app.link.TVS_TITLE
import io.skjaere.usenet.app.link.tvsLine
import io.skjaere.usenet.app.link.phonesLine
import io.skjaere.usenet.app.setup.PHONE_SETUP_TITLE
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.NavRow
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.releases.QualityPreference
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

data class SettingsSummary(
    val providers: String = "",
    /** Settings › Phones' line, on a TV. */
    val phones: String = "",
    /** Settings › TVs' line, on a phone or tablet. */
    val tvs: String = "",
    val indexers: String = "",
    /** Settings › Debrid services' line, and its warning dot while a key is refused or missing (L196). */
    val debridServices: HubLine = HubLine(""),
    /** Settings › Torrent indexers' line, and its warning dot while none is searched (L166). */
    val torrentIndexers: HubLine = HubLine(""),
    val metadata: String = "",
    val playback: String = "",
    val cache: String = "",
    /** Settings › Google Cast's line, on a phone or tablet. */
    val cast: String = "",
)

class SettingsViewModel(container: AppContainer) : ViewModel() {
    private val settings = container.settings
    // The accounts with their keys, read again as they change: a key that can't be read is said in the row's line.
    private val services = settings.services.map { settings.servicesWithKeys() }
    private val sources = combine(settings.settings, container.serviceOn, services) { s, serviceOn, services -> Triple(s, serviceOn, services) }
    val summary = combine(sources, settings.hasTmdbKey, container.videoCache.usage(), container.data.link.phones, container.data.link.tvs) { (s, serviceOn, services), hasKey, cache, phones, tvs ->
        val indexers = s.activeIndexers
        SettingsSummary(
            providers = providersSummary(s.activeProviders),
            phones = phonesLine(phones),
            tvs = tvsLine(tvs),
            indexers = when (indexers.size) {
                0 -> "None yet"
                else -> "${indexers.size} indexer${if (indexers.size > 1) "s" else ""}" +
                    indexers.count { !it.enabled }.takeIf { it > 0 }?.let { " · $it off" }.orEmpty()
            },
            debridServices = debridServicesHubLine(services),
            torrentIndexers = torrentIndexersHubLine(s.activeTorrentIndexers, serviceOn),
            metadata = if (hasKey) "TMDB key set · ${languageLabel(s.metadata.language)}" else "No TMDB key: no posters or collection",
            playback = "${qualityLabel(s.playback.qualityPreference)} · " +
                s.playback.effectiveAudioLanguages(Locale.getDefault().language).joinToString { languageName(it) },
            cache = cacheSummary(cache),
            cast = GoogleCastPage.rowLine(s.cast.look),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsSummary())
}

fun languageName(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }
fun languageLabel(tag: String?): String = tag?.let { Locale.forLanguageTag(it).getDisplayName(Locale.ENGLISH) } ?: "Device language"
fun qualityLabel(q: QualityPreference): String = when (q) {
    QualityPreference.Auto -> "Auto"
    QualityPreference.P2160 -> "2160p"
    QualityPreference.P1080 -> "1080p"
    QualityPreference.P720 -> "720p"
}

/**
 * [onBack] null: Settings is a tab (a TV's), with no Back of its own; it then starts a remote on the
 * providers' row, or on the row whose page it is coming back from. [onThisDevice]: Playback's third
 * row, the device's facts. [onPhoneSetup]: a TV's "Set up with your phone", in a group of its own
 * above the sources (null shows none). [onPhones]: a TV's Phones (Play on TV), in that group after it:
 * both are about your phone (null shows none). [onTvs]: a phone's TVs (Play on TV), in Playback after
 * This device, since it is about where plays go (null shows none). [onCast]: a phone's Google Cast (L112),
 * after the TVs, where plays go too (null shows none). [onPrivacy]: Privacy, under About, on every device
 * (L138). [onTorrentIndexers]: Torrent indexers, after Indexers, while debrid shows (null shows none; L192), and
 * [onDebridServices] Debrid services between them (L196). The rows and their order are [settingsHub]'s.
 */
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)?, onProviders: () -> Unit, onIndexers: () -> Unit, onMetadata: () -> Unit, onPlayback: () -> Unit, onCache: () -> Unit,
    onLogs: () -> Unit, onThisDevice: () -> Unit = {}, onPhoneSetup: (() -> Unit)? = null, onPhones: (() -> Unit)? = null,
    onTvs: (() -> Unit)? = null, onCast: (() -> Unit)? = null, onPrivacy: () -> Unit = {}, onTorrentIndexers: (() -> Unit)? = null,
    onDebridServices: (() -> Unit)? = null,
) {
    val container = LocalContext.current.container
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val summary by vm.summary.collectAsStateWithLifecycle()
    val rows = remember { HashMap<String, FocusRequester>() }
    fun row(title: String): FocusRequester = rows.getOrPut(title) { FocusRequester() }
    // The row that opened a page: saved, as this screen is gone while the page covers it.
    var opener by rememberSaveable { mutableStateOf<String?>(null) }
    fun opens(title: String, page: () -> Unit): () -> Unit = { opener = title; page() }
    val initial = rememberInitialFocus()
    // Where a remote starts, and where the shell's way in lands: not the first row at the top.
    val entry = remember { opener ?: HubRow.Providers.title }
    if (onBack == null) LaunchedEffect(Unit) {
        opener = null
        initial.request(row(entry))
    }
    // Build's facts only: a line the hub can say at once.
    val thisDevice = remember { thisDeviceLine(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), Build.VERSION.RELEASE.orEmpty()) }
    val pages = buildMap<HubRow, () -> Unit> {
        onPhoneSetup?.let { put(HubRow.PhoneSetup, it) }
        onPhones?.let { put(HubRow.Phones, it) }
        put(HubRow.Providers, onProviders)
        put(HubRow.Indexers, onIndexers)
        onDebridServices?.let { put(HubRow.DebridServices, it) }
        onTorrentIndexers?.let { put(HubRow.TorrentIndexers, it) }
        put(HubRow.Metadata, onMetadata)
        put(HubRow.Playback, onPlayback)
        put(HubRow.Cache, onCache)
        put(HubRow.ThisDevice, onThisDevice)
        onTvs?.let { put(HubRow.Tvs, it) }
        onCast?.let { put(HubRow.GoogleCast, it) }
        put(HubRow.Logs, onLogs)
        put(HubRow.Privacy, onPrivacy)
    }
    fun line(row: HubRow): String? = when (row) {
        HubRow.PhoneSetup -> phoneSetupHubLine(DebridGate.shown)
        HubRow.Phones -> summary.phones
        HubRow.Providers -> summary.providers
        HubRow.Indexers -> summary.indexers
        HubRow.DebridServices -> summary.debridServices.text
        HubRow.TorrentIndexers -> summary.torrentIndexers.text
        HubRow.Metadata -> summary.metadata
        HubRow.Playback -> summary.playback
        HubRow.Cache -> summary.cache
        HubRow.ThisDevice -> thisDevice
        HubRow.Tvs -> summary.tvs
        HubRow.GoogleCast -> summary.cast
        HubRow.Logs -> null
        HubRow.Privacy -> privacyRowLine(container.television)
    }
    val wayIn = if (onBack == null) Modifier.focusRestorer(row(entry)).focusGroup() else Modifier
    Column(Modifier.fillMaxSize().then(wayIn).verticalScroll(rememberScrollState())) {
        TopBar("Settings", onBack)
        settingsHub(container.television, pages.keys, DebridGate.shown).forEach { group ->
            // A TV's phone rows have no heading: not "Usenet providers" in their words either, as the remote's first stop
            // is that row, found by them.
            group.heading?.let { SectionHeader(it) }
            GroupCard(if (group.heading == null) Modifier.padding(top = 8.dp) else Modifier) {
                group.rows.forEachIndexed { i, page ->
                    if (i > 0) InsetDivider()
                    val warn = page == HubRow.TorrentIndexers && summary.torrentIndexers.warn || page == HubRow.DebridServices && summary.debridServices.warn
                    NavRow(page.title, line(page), warn = warn, modifier = Modifier.focusRequester(row(page.title)), onClick = opens(page.title, pages.getValue(page)))
                }
            }
        }
        Footnote(HUB_FOOTNOTE)
    }
}

/** A row of the Settings hub, by the page it opens, with its [title]. */
enum class HubRow(val title: String) {
    PhoneSetup(PHONE_SETUP_TITLE),
    Phones(PHONES_TITLE),
    Providers("Usenet providers"),
    Indexers("Indexers"),
    DebridServices("Debrid services"),
    TorrentIndexers("Torrent indexers"),
    Metadata("Metadata"),
    Playback("Quality and languages"),
    Cache("Cache"),
    ThisDevice("This device"),
    Tvs(TVS_TITLE),
    GoogleCast(GoogleCastPage.TITLE),
    Logs("Logs"),
    Privacy(PRIVACY_TITLE),
}

/** A group of the hub's rows, in order, under its [heading]; a TV's first, about your phone, has none. */
data class HubGroup(val heading: String?, val rows: List<HubRow>)

/** Under the hub (W60): About has Privacy now. */
const val HUB_FOOTNOTE = "Backup is still to come."

/**
 * The hub's groups and their rows (CsPhoneSettingsCast 1; W60, L112, L138). [optional]: of the rows not every hub has,
 * those its caller opens: a TV's Set up with your phone and Phones, first; a phone's TVs and Google Cast, last in
 * Playback, Google Cast never on a [television] (L110); Debrid services, then Torrent indexers, after Indexers on both
 * (DbPhoneSources, DbTvSources 1), only while debrid is [shown] (L192). About, with Privacy, last, on every device.
 */
fun settingsHub(television: Boolean, optional: Set<HubRow>, shown: Boolean): List<HubGroup> {
    fun has(row: HubRow) = row in optional && (row != HubRow.GoogleCast || GoogleCastPage.shows(television)) &&
        (row !in debridRows || shown)
    return listOfNotNull(
        HubGroup(null, listOf(HubRow.PhoneSetup, HubRow.Phones).filter(::has)).takeIf { it.rows.isNotEmpty() },
        HubGroup("Sources", listOf(HubRow.Providers, HubRow.Indexers) + debridRows.filter(::has) + HubRow.Metadata),
        HubGroup("Playback", listOf(HubRow.Playback, HubRow.Cache, HubRow.ThisDevice) + listOf(HubRow.Tvs, HubRow.GoogleCast).filter(::has)),
        HubGroup("App", listOf(HubRow.Logs)),
        HubGroup("About", listOf(HubRow.Privacy)),
    )
}

/** The hub's debrid rows, in their order: what plays, then what searches, as Usenet's pair above them. */
private val debridRows = listOf(HubRow.DebridServices, HubRow.TorrentIndexers)

/** The Privacy row's line (L138): what leaves the device it is on. */
fun privacyRowLine(television: Boolean): String = "What leaves this ${if (television) "TV" else "phone"}, and to whom"

/** The hub's line under "Set up with your phone". */
const val PHONE_SETUP_HUB_ROW = "Add or change providers, indexers and the TMDB key from your phone"

/** The hub's line under "Set up with your phone": what its page takes, the debrid cards only while debrid is [shown] (L209). */
fun phoneSetupHubLine(shown: Boolean): String =
    if (shown) "Add or change providers, indexers, a debrid service, torrent indexers and the TMDB key from your phone" else PHONE_SETUP_HUB_ROW

