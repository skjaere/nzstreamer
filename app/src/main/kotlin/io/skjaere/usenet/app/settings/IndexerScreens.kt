package io.skjaere.usenet.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.setup.FormPhoneSetupRow
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FieldKind
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.FormField
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.NavRow
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.ResultLine
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.SwitchRow
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.TvTypingTip
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.rememberFieldChain
import io.skjaere.usenet.data.SettingsRepository
import io.skjaere.usenet.newznab.CapsCache
import io.skjaere.usenet.newznab.IndexerCaps
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.newznab.LeftOut
import io.skjaere.usenet.newznab.NewznabClient
import io.skjaere.usenet.newznab.NewznabException
import io.skjaere.usenet.newznab.SearchPage
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.newznab.SearchType
import io.skjaere.usenet.newznab.UrlSecurity
import io.skjaere.usenet.newznab.ApiLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class IndexersViewModel(container: AppContainer) : ViewModel() {
    val indexers = container.settings.indexers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun IndexersScreen(onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val container = LocalContext.current.container
    val vm: IndexersViewModel = viewModel { IndexersViewModel(container) }
    val indexers by vm.indexers.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Indexers", onBack) {
            TextButton(onClick = { onEdit(null) }) { Text("+ Add", style = AppType.bodyStrong, color = colors.accent) }
        }
        if (indexers.isEmpty()) {
            Footnote("Add the Newznab API address and key of an indexer you have an account with, or of your Prowlarr or NZBHydra.")
        } else {
            GroupCard {
                indexers.forEachIndexed { i, indexer ->
                    if (i > 0) InsetDivider()
                    val host = runCatching { java.net.URI(indexer.baseUrl).host }.getOrNull() ?: indexer.baseUrl
                    NavRow(indexer.name, if (indexer.enabled) host else "$host · off", warn = UrlSecurity.of(indexer.baseUrl).warn) { onEdit(indexer.id) }
                }
            }
        }
    }
}

data class IndexerForm(
    val id: String,
    val name: String = "",
    val url: String = "",
    val apiKey: String = "",
    val hasSavedKey: Boolean = false,
    val enabled: Boolean = true,
) {
    val security: UrlSecurity get() = UrlSecurity.of(url.trim())
    val valid: Boolean get() = url.isNotBlank() && security != UrlSecurity.Invalid && (apiKey.isNotBlank() || hasSavedKey)
}

/**
 * The form for an indexer that isn't saved yet. Its id is fixed now rather than when saving, so
 * pressing Save twice updates the one indexer instead of adding a second.
 */
internal fun newIndexerForm() = IndexerForm(UUID.randomUUID().toString())

/** Saves [f], named after its host when no name was given; an empty key field keeps the saved key. Returns the id. */
internal suspend fun SettingsRepository.save(f: IndexerForm): String {
    val name = f.name.trim().ifEmpty { runCatching { java.net.URI(f.url.trim()).host }.getOrNull() ?: "Indexer" }
    return saveIndexer(f.id, name, f.url, f.apiKey.trim().takeIf { it.isNotEmpty() }, f.enabled)
}

/** An http address over the internet: the indexer form's warning, and the phone setup's for an indexer sent so. */
const val INDEXER_PUBLIC_HTTP = "⚠ Not encrypted: your API key would be readable on its way over the internet. Use https:// if the indexer offers it."

/** An http address on the home network: no warning, but said. */
const val INDEXER_LOCAL_HTTP = "Plain http on your local network."

/** How many of a torrent indexer's latest results its Test reads, to say how many have no info hash (L238). */
internal const val TORRENT_TEST_RESULTS = 10

sealed interface IndexerTest {
    data object Idle : IndexerTest
    data object Running : IndexerTest
    /**
     * [results]: the kind of what the Test's search found, the form's own; null when it found nothing to tell. A torrent
     * indexer's Test reads its [latest] results (10 at most, L238), [noHash] of which have no info hash; both 0 otherwise.
     */
    data class Ok(val caps: IndexerCaps, val limits: ApiLimits?, val results: IndexerKind? = null, val noHash: Int = 0, val latest: Int = 0) : IndexerTest
    data class Failed(val message: String) : IndexerTest
}

/** "Connected · Example Indexer 1.2": a passed test as the indexer form shows it, and the phone setup with it. */
internal fun indexerConnected(ok: IndexerTest.Ok): String =
    "Connected" + (ok.caps.serverTitle?.let { " · $it" } ?: "") + (ok.caps.serverVersion?.let { " $it" } ?: "")

/**
 * The Test's line for a feed whose results are the other kind than its form's [form] (L161): a torrent feed under
 * Indexers, an NZB feed under Torrent indexers. While debrid is hidden ([shown] false) the Indexers form's says the
 * first sentence alone, as the screen the second names isn't there.
 */
internal fun resultsOfOtherKind(form: IndexerKind, shown: Boolean): String = when (form) {
    IndexerKind.Newznab -> if (shown) "$NOT_NZBS Add it under Torrent indexers instead." else NOT_NZBS
    IndexerKind.Torznab -> "This indexer’s results are NZBs. Add it under Indexers instead."
}

private const val NOT_NZBS = "This indexer’s results aren’t NZBs."

/**
 * An indexer's Test, as its screen and "Set up with your phone" run it: the caps, then one tiny search, which is where
 * indexers report API and grab usage, and what tells whether its results are of [config]'s kind: a search whose results
 * were all left out as the other kind fails with [resultsOfOtherKind]'s line. A torrent indexer's asks its latest
 * [TORRENT_TEST_RESULTS] torrents (no words: Torznab's RSS search), as a tracker may have nothing called "test", and
 * counts those without an info hash, of the first that many it answered (L238); a Usenet indexer's asks one result for
 * "test" as ever, and its latest one too when that found nothing, so a torrent feed added there is told (L161). The caps
 * the searches keep for it are forgotten, so a changed address or key is read again at once.
 */
internal suspend fun testIndexer(newznab: NewznabClient, caps: CapsCache, config: IndexerConfig, shown: Boolean = DebridGate.shown): IndexerTest = try {
    val found = newznab.caps(config)
    val torrents = config.kind == IndexerKind.Torznab
    val probe: suspend (String) -> SearchPage? = { words ->
        try {
            newznab.search(config, SearchQuery.Text(words), limit = if (torrents) TORRENT_TEST_RESULTS else 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The counts are a bonus: the caps answered, so the indexer works.
            null
        }
    }
    val page = if (torrents) {
        probe("")
    } else {
        probe("test")?.let { first -> if (first.items.isEmpty() && first.leftOut.isEmpty()) probe("") ?: first else first }
    }
    caps.invalidate(config)
    val otherKind = if (config.kind == IndexerKind.Newznab) LeftOut.NotNzb else LeftOut.Nzb
    if (page != null && page.items.isEmpty() && (page.leftOut[otherKind] ?: 0) > 0) {
        IndexerTest.Failed(resultsOfOtherKind(config.kind, shown))
    } else {
        // Torrents left out for want of a hash are torrents all the same (D2PhoneIndexerTest 2).
        val told = page != null && (page.items.isNotEmpty() || (page.leftOut[LeftOut.NoHash] ?: 0) > 0)
        // An indexer may answer more than it was asked: its first ones are what it was asked for.
        val latest = if (torrents) page?.feed.orEmpty().take(TORRENT_TEST_RESULTS) else emptyList()
        IndexerTest.Ok(
            found, page?.limits ?: found.limits, results = config.kind.takeIf { told },
            noHash = latest.count { it == LeftOut.NoHash }, latest = latest.size,
        )
    }
} catch (e: NewznabException) {
    IndexerTest.Failed(e.error.message)
}

/**
 * The indexer screen's Test, as the provider screen's ([ProviderTestRun]): one test at a time, and a result only for
 * the form as it is. A new Test cancels the one running, and a change to the form cancels it and clears the result,
 * so a test of older values never shows, even one that answers after it was given up on.
 */
class IndexerTestRun(private val scope: CoroutineScope, private val test: suspend (IndexerForm) -> IndexerTest) {
    private val _state = MutableStateFlow<IndexerTest>(IndexerTest.Idle)
    val state: StateFlow<IndexerTest> = _state.asStateFlow()

    /** The form the running test, or the result shown, is for. */
    private var tested: IndexerForm? = null
    private var job: Job? = null

    fun start(form: IndexerForm) {
        job?.cancel()
        tested = form
        _state.value = IndexerTest.Running
        job = scope.launch {
            val result = test(form)
            ensureActive()
            if (tested == form) _state.value = result
        }
    }

    /** The form is now [form]: a test of other values is cancelled, and a result for them cleared. */
    fun formChanged(form: IndexerForm) {
        if (form == tested) return
        job?.cancel()
        job = null
        tested = null
        _state.value = IndexerTest.Idle
    }
}

class IndexerEditViewModel(private val container: AppContainer, private val id: String?) : ViewModel() {
    val form = MutableStateFlow(id?.let { IndexerForm(it) } ?: newIndexerForm())
    val done = MutableStateFlow(false)
    private val tests = IndexerTestRun(viewModelScope, ::test)
    val test: StateFlow<IndexerTest> = tests.state

    init {
        id?.let {
            viewModelScope.launch {
                container.settings.indexer(it)?.let { saved ->
                    form.value = IndexerForm(it, saved.record.name, saved.record.baseUrl, "", saved.apiKey != null, saved.record.enabled)
                    tests.formChanged(form.value)
                }
            }
        }
    }

    fun update(change: (IndexerForm) -> IndexerForm) {
        form.update(change)
        tests.formChanged(form.value)
    }

    private suspend fun config(f: IndexerForm): IndexerConfig {
        val key = f.apiKey.trim().ifEmpty { container.settings.indexer(f.id)?.apiKey.orEmpty() }
        return IndexerConfig(f.id, f.name, f.url.trim(), key)
    }

    fun runTest() = tests.start(form.value)

    private suspend fun test(f: IndexerForm): IndexerTest = testIndexer(container.newznab, container.caps, config(f))

    fun save() = viewModelScope.launch {
        container.settings.save(form.value)
        done.value = true
    }

    fun delete() = viewModelScope.launch {
        container.settings.deleteIndexer(form.value.id)
        done.value = true
    }
}

/** [onPhoneSetup]: "Set up with your phone" for this indexer (a TV's; null shows none). */
@Composable
fun IndexerEditScreen(id: String?, onBack: () -> Unit, onPhoneSetup: (() -> Unit)? = null) {
    val container = LocalContext.current.container
    val vm: IndexerEditViewModel = viewModel { IndexerEditViewModel(container, id) }
    val form by vm.form.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    LaunchedEffect(done) { if (done) onBack() }
    // The keyboard's Next goes down the fields.
    val chain = rememberFieldChain()

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        TopBar(if (id == null) "Add indexer" else "Indexer", onBack) {
            TextButton(onClick = { vm.save() }, enabled = form.valid) { Text("Save", style = AppType.bodyStrong, color = if (form.valid) colors.accent else colors.muted) }
        }
        TvTypingTip(phoneBelow = onPhoneSetup != null)
        FormPhoneSetupRow(onPhoneSetup)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FormField("Name", form.name, { v -> vm.update { it.copy(name = v) } }, placeholder = "My indexer", chain = chain)
            FormField(
                "API URL", form.url, { v -> vm.update { it.copy(url = v) } }, kind = FieldKind.Url, mono = true,
                placeholder = "https://indexer.example.org/api",
                help = when (form.security) {
                    UrlSecurity.PublicHttp -> INDEXER_PUBLIC_HTTP
                    UrlSecurity.LocalHttp -> INDEXER_LOCAL_HTTP
                    else -> "Works with Newznab-compatible indexers and with Prowlarr or NZBHydra."
                },
                helpWarn = form.security == UrlSecurity.PublicHttp, chain = chain,
            )
            FormField(
                "API key", form.apiKey, { v -> vm.update { it.copy(apiKey = v) } }, kind = FieldKind.Key, mono = true,
                placeholder = if (form.hasSavedKey) "Saved" else null, help = "Stored encrypted on this device.", chain = chain,
            )
            SecondaryButton("Test connection", vm::runTest, Modifier.fillMaxWidth(), enabled = form.valid && test != IndexerTest.Running)
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
                        CapsLine("TV search by", searchBy(t.caps, SearchType.Tv))
                        CapsLine("Movie search by", searchBy(t.caps, SearchType.Movie))
                        CapsLine("Categories", t.caps.categories.joinToString { it.name }.ifEmpty { "—" })
                        t.limits?.let { l ->
                            l.apiMax?.let { CapsLine("API calls today", "${l.apiCurrent ?: "?"} of $it") }
                            l.grabMax?.let { CapsLine("Grabs today", "${l.grabCurrent ?: "?"} of $it") }
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(top = 8.dp)) {
            GroupCard { SwitchRow("Use for searches", form.enabled, { v -> vm.update { it.copy(enabled = v) } }) }
        }
        if (id != null) {
            TextButton(onClick = { vm.delete() }, modifier = Modifier.padding(8.dp)) { Text("Remove indexer", style = AppType.body, color = colors.warnStrong) }
        }
    }
}

@Composable
internal fun CapsLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = AppType.secondary, color = colors.muted, modifier = Modifier.weight(1f))
        Text(value, style = AppType.secondary, color = colors.text)
    }
}

internal fun searchBy(caps: IndexerCaps, type: SearchType): String {
    val params = caps.searching[type] ?: return "not supported"
    val ids = listOf("tvdbid" to "TVDB", "tmdbid" to "TMDB", "imdbid" to "IMDb", "tvmazeid" to "TVmaze").filter { it.first in params }.map { it.second }
    return ids.ifEmpty { listOf("name only") }.joinToString()
}
