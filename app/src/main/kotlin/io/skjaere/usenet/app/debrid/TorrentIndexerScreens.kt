package io.skjaere.usenet.app.debrid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.settings.CapsLine
import io.skjaere.usenet.app.settings.INDEXER_LOCAL_HTTP
import io.skjaere.usenet.app.settings.INDEXER_PUBLIC_HTTP
import io.skjaere.usenet.app.settings.IndexerForm
import io.skjaere.usenet.app.settings.IndexerTest
import io.skjaere.usenet.app.settings.IndexerTestRun
import io.skjaere.usenet.app.settings.indexerConnected
import io.skjaere.usenet.app.settings.newIndexerForm
import io.skjaere.usenet.app.settings.searchBy
import io.skjaere.usenet.app.settings.testIndexer
import io.skjaere.usenet.app.setup.FormPhoneSetupRow
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FieldKind
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.FormField
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.NavRow
import io.skjaere.usenet.app.ui.ResultLine
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.SwitchRow
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.TvTypingTip
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.rememberFieldChain
import io.skjaere.usenet.data.SettingsRepository
import io.skjaere.usenet.data.TorrentIndexerRecord
import io.skjaere.usenet.newznab.IndexerCaps
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.newznab.MOVIES_CATEGORY
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.newznab.SearchType
import io.skjaere.usenet.newznab.TV_CATEGORY
import io.skjaere.usenet.newznab.UrlSecurity
import io.skjaere.usenet.newznab.torznabCategories
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Under the list's title (DbPhoneTorrentIndexer 1). */
const val TORRENT_INDEXERS_LINE = "Searched beside your Indexers while a debrid service is on."

/** The list with none saved yet. */
const val NO_TORRENT_INDEXERS = "Add the Torznab address and API key of an indexer in your Prowlarr or Jackett."

/**
 * The list's footnote (L194). The boards' "the tracker sees nothing" said more than is so: a search reaches the tracker's
 * site under the user's account, so it is what is played that it never sees.
 */
fun torrentIndexersFootnote(television: Boolean): String =
    "Only a torrent’s ID (its info hash) goes to your debrid service, and only cached torrents play: no passkey leaves " +
        "this ${if (television) "TV" else "phone"}, and the tracker sees nothing of what you play."

/** The form's second switch (W75), on by default, for private trackers whose rules forbid debrid services. */
const val USE_WITH_SERVICE = "Use with your debrid service"
const val USE_WITH_SERVICE_HELP = "Some private trackers don’t allow debrid services. Turn this off for those."

/** A torrent indexer's row in the list: its host, then "off", else "not used with your debrid service" (DbPhoneTorrentIndexer 1). */
fun torrentIndexerRowLine(record: TorrentIndexerRecord): String {
    val host = runCatching { java.net.URI(record.baseUrl).host }.getOrNull() ?: record.baseUrl
    return when {
        !record.enabled -> "$host · off"
        !record.useWithService -> "$host · not used with your debrid service"
        else -> host
    }
}

/** Settings' Torrent indexers row: its line, and whether the warning dot shows by it. */
data class HubLine(val text: String, val warn: Boolean = false)

/**
 * The hub row's line (DbPhoneSources): as Indexers' ("3 torrent indexers · 1 off"), but while no service account is on,
 * none is searched (L166), which the line says, with the warning dot.
 */
fun torrentIndexersHubLine(indexers: List<TorrentIndexerRecord>, serviceOn: Boolean): HubLine {
    if (indexers.isEmpty()) return HubLine("None yet")
    val count = "${indexers.size} torrent indexer${if (indexers.size > 1) "s" else ""}"
    if (!serviceOn) return HubLine("$count · not searched without a service", warn = true)
    return HubLine(count + indexers.count { !it.enabled }.takeIf { it > 0 }?.let { " · $it off" }.orEmpty())
}

/** "Searches in" (in the indexer form's Categories' place): what [torznabCategories] will ask of it, by name. */
fun searchesIn(caps: IndexerCaps): String {
    val asked = torznabCategories(caps, SearchQuery.Text(""))
    if (asked.isEmpty()) return "All categories"
    return asked.joinToString { if (it == MOVIES_CATEGORY) "Movies" else if (it == TV_CATEGORY) "TV" else it.toString() }
}

/**
 * A passed Test's lines on the torrent form (DbPhoneTorrentIndexer 2): what its results are ("—" when the search found
 * none to tell by), how it searches, and in what. Nothing about public or private (L194).
 */
fun torrentTestLines(ok: IndexerTest.Ok): List<Pair<String, String>> = listOfNotNull(
    "Results" to if (ok.results == IndexerKind.Torznab) "Torrents" else "—",
    "TV search by" to searchBy(ok.caps, SearchType.Tv),
    "Movie search by" to searchBy(ok.caps, SearchType.Movie),
    "Searches in" to searchesIn(ok.caps),
    ok.limits?.apiMax?.let { "API calls today" to "${ok.limits.apiCurrent ?: "?"} of $it" },
)

/**
 * The warning under a passed Test's lines (D2PhoneIndexerTest, choice 2): how many of the latest results the Test read
 * have no info hash, which the app leaves out, as a debrid service is asked by hash alone; null when every one has one,
 * or there were none (Results then says "—"). Only this form shows it.
 */
fun torrentHashLine(ok: IndexerTest.Ok): String? = when {
    ok.noHash <= 0 || ok.latest <= 0 -> null
    ok.noHash >= ok.latest && ok.latest == 1 -> "The last result has no info hash, so the app leaves it out."
    ok.noHash >= ok.latest -> "None of the last ${ok.latest} results has an info hash, so the app leaves them all out."
    ok.noHash == 1 -> "1 of the last ${ok.latest} results has no info hash, so the app leaves it out."
    else -> "${ok.noHash} of the last ${ok.latest} results have no info hash, so the app leaves them out."
}

/** The torrent form: an indexer's fields, with "Use with your debrid service" beside them. */
data class TorrentIndexerForm(val fields: IndexerForm, val useWithService: Boolean = true) {
    val valid: Boolean get() = fields.valid
}

/** A new torrent indexer's form: both switches on (W75). Its id is fixed now, as [newIndexerForm]'s. */
internal fun newTorrentIndexerForm() = TorrentIndexerForm(newIndexerForm())

/** The indexer the form's Test asks: a Torznab one, its key the field's, else the saved one ([savedKey]). */
internal fun torrentIndexerConfig(f: IndexerForm, savedKey: String?): IndexerConfig =
    IndexerConfig(f.id, f.name, f.url.trim(), f.apiKey.trim().ifEmpty { savedKey.orEmpty() }, kind = IndexerKind.Torznab)

/** Saves [f], named after its host when no name was given; an empty key field keeps the saved key. Returns the id. */
internal suspend fun SettingsRepository.save(f: TorrentIndexerForm): String {
    val name = f.fields.name.trim().ifEmpty { runCatching { java.net.URI(f.fields.url.trim()).host }.getOrNull() ?: "Torrent indexer" }
    return saveTorrentIndexer(f.fields.id, name, f.fields.url, f.fields.apiKey.trim().takeIf { it.isNotEmpty() }, f.fields.enabled, f.useWithService)
}

class TorrentIndexersViewModel(container: AppContainer) : ViewModel() {
    val indexers = container.settings.torrentIndexers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Settings › Torrent indexers (DbPhoneTorrentIndexer 1, DbTvSources 5): the Indexers list's shape, in its own list. */
@Composable
fun TorrentIndexersScreen(onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val container = LocalContext.current.container
    val vm: TorrentIndexersViewModel = viewModel { TorrentIndexersViewModel(container) }
    val indexers by vm.indexers.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Torrent indexers", onBack) {
            TextButton(onClick = { onEdit(null) }) { Text("+ Add", style = AppType.bodyStrong, color = colors.accent) }
        }
        Text(TORRENT_INDEXERS_LINE, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
        if (indexers.isEmpty()) {
            Footnote(NO_TORRENT_INDEXERS)
        } else {
            GroupCard {
                indexers.forEachIndexed { i, indexer ->
                    if (i > 0) InsetDivider()
                    NavRow(indexer.name, torrentIndexerRowLine(indexer), warn = UrlSecurity.of(indexer.baseUrl).warn) { onEdit(indexer.id) }
                }
            }
        }
        Footnote(torrentIndexersFootnote(container.television))
    }
}

class TorrentIndexerEditViewModel(private val container: AppContainer, private val id: String?) : ViewModel() {
    val form = MutableStateFlow(id?.let { TorrentIndexerForm(IndexerForm(it)) } ?: newTorrentIndexerForm())
    val done = MutableStateFlow(false)

    // On the fields alone: the debrid switch changes nothing the Test reads.
    private val tests = IndexerTestRun(viewModelScope, ::test)
    val test: StateFlow<IndexerTest> = tests.state

    init {
        id?.let {
            viewModelScope.launch {
                container.settings.torrentIndexer(it)?.let { saved ->
                    val r = saved.record
                    form.value = TorrentIndexerForm(IndexerForm(it, r.name, r.baseUrl, "", saved.apiKey != null, r.enabled), r.useWithService)
                    tests.formChanged(form.value.fields)
                }
            }
        }
    }

    fun update(change: (TorrentIndexerForm) -> TorrentIndexerForm) {
        form.update(change)
        tests.formChanged(form.value.fields)
    }

    fun runTest() = tests.start(form.value.fields)

    private suspend fun test(f: IndexerForm): IndexerTest =
        testIndexer(container.newznab, container.caps, torrentIndexerConfig(f, container.settings.torrentIndexer(f.id)?.apiKey))

    fun save() = viewModelScope.launch {
        container.settings.save(form.value)
        done.value = true
    }

    fun delete() = viewModelScope.launch {
        container.settings.deleteTorrentIndexer(form.value.fields.id)
        done.value = true
    }
}

/**
 * A torrent indexer's form (DbPhoneTorrentIndexer 2-3, DbTvSources 6): the indexer form's fields, key rules and Test, its
 * Test's lines for torrents, and the two switches. [onPhoneSetup]: a TV's "Set up with your phone" (null shows none).
 */
@Composable
fun TorrentIndexerEditScreen(id: String?, onBack: () -> Unit, onPhoneSetup: (() -> Unit)? = null) {
    val container = LocalContext.current.container
    val vm: TorrentIndexerEditViewModel = viewModel { TorrentIndexerEditViewModel(container, id) }
    val form by vm.form.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    LaunchedEffect(done) { if (done) onBack() }
    val chain = rememberFieldChain()
    val fields = form.fields

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        TopBar(if (id == null) "Add torrent indexer" else "Torrent indexer", onBack) {
            TextButton(onClick = { vm.save() }, enabled = form.valid) { Text("Save", style = AppType.bodyStrong, color = if (form.valid) colors.accent else colors.muted) }
        }
        TvTypingTip(phoneBelow = onPhoneSetup != null)
        FormPhoneSetupRow(onPhoneSetup)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FormField("Name", fields.name, { v -> vm.update { it.copy(fields = it.fields.copy(name = v)) } }, placeholder = "My indexer", chain = chain)
            FormField(
                "API URL", fields.url, { v -> vm.update { it.copy(fields = it.fields.copy(url = v)) } }, kind = FieldKind.Url, mono = true,
                placeholder = "https://indexer.example.org/api",
                help = when (fields.security) {
                    UrlSecurity.PublicHttp -> INDEXER_PUBLIC_HTTP
                    UrlSecurity.LocalHttp -> INDEXER_LOCAL_HTTP
                    else -> "Works with Torznab indexers in Prowlarr or Jackett."
                },
                helpWarn = fields.security == UrlSecurity.PublicHttp, chain = chain,
            )
            FormField(
                "API key", fields.apiKey, { v -> vm.update { it.copy(fields = it.fields.copy(apiKey = v)) } }, kind = FieldKind.Key, mono = true,
                placeholder = if (fields.hasSavedKey) "Saved" else null, help = "Stored encrypted on this device.", chain = chain,
            )
            // Not disabled while it runs: a remote's focus would leave the button it pressed for a field above it (DbTvSources
            // 6). Another press starts it again for the form as it is.
            SecondaryButton("Test connection", vm::runTest, Modifier.fillMaxWidth(), enabled = form.valid)
        }
        when (val t = test) {
            IndexerTest.Idle -> Unit
            IndexerTest.Running -> CircularProgressIndicator(Modifier.padding(16.dp), strokeWidth = 2.dp, color = colors.accent)
            is IndexerTest.Failed -> Column(Modifier.padding(16.dp)) { ResultLine(false, t.message) }
            is IndexerTest.Ok -> Column(Modifier.padding(top = 14.dp)) {
                GroupCard {
                    Column(Modifier.padding(12.dp)) { ResultLine(true, indexerConnected(t)) }
                    InsetDivider()
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        torrentTestLines(t).forEach { (label, value) -> CapsLine(label, value) }
                    }
                    torrentHashLine(t)?.let { line ->
                        InsetDivider()
                        Column(Modifier.padding(12.dp)) { ResultLine(false, line) }
                    }
                }
            }
        }
        Column(Modifier.padding(top = 8.dp)) {
            GroupCard {
                SwitchRow("Use for searches", fields.enabled, { v -> vm.update { it.copy(fields = it.fields.copy(enabled = v)) } })
                SwitchRow(USE_WITH_SERVICE, form.useWithService, { v -> vm.update { it.copy(useWithService = v) } }, detail = USE_WITH_SERVICE_HELP)
            }
        }
        if (id != null) {
            TextButton(onClick = { vm.delete() }, modifier = Modifier.padding(8.dp)) { Text("Remove torrent indexer", style = AppType.body, color = colors.warnStrong) }
        }
    }
}
