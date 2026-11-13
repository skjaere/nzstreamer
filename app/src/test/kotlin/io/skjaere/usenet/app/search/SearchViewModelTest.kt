package io.skjaere.usenet.app.search

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.skjaere.usenet.data.IndexerRecord
import io.skjaere.usenet.data.RecentKind
import io.skjaere.usenet.data.RecentSearch
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
import io.skjaere.usenet.data.TorrentIndexerRecord
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.TitleSuggestion
import io.skjaere.usenet.newznab.CapsCache
import io.skjaere.usenet.newznab.IndexerCaps
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.newznab.IndexerOutcome
import io.skjaere.usenet.newznab.MultiIndexerSearch
import io.skjaere.usenet.newznab.NewznabClient
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.SearchPage
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.newznab.SearchType
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.app.casting.CastDeviceFacts
import io.skjaere.usenet.app.casting.CastDeviceKind
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastLinkNow
import io.skjaere.usenet.app.casting.CastModel
import io.skjaere.usenet.app.casting.CastProfiles
import io.skjaere.usenet.app.casting.CastRanking
import io.skjaere.usenet.app.casting.listRankings
import io.skjaere.usenet.app.casting.rankingTargetOf
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayOn
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.RankingTarget
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Search's view model over fakes: TMDB's titles for the words typed, one indexer, the recent searches. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @AfterEach
    fun main() {
        Dispatchers.resetMain()
    }

    private val glasswater = TitleSuggestion(MediaType.Tv, 900071, "Glasswater Bay", null, 2024, null, null)
    private val lantern = TitleSuggestion(MediaType.Movie, 900073, "Lantern Field", null, 2023, null, null)
    private val catalogue = listOf(glasswater, lantern)
    private val indexer = IndexerConfig("a", "Indexer A", "http://a.invalid", "key-a")
    /** An indexer that searches by words alone. */
    private val words = IndexerCaps(searching = mapOf(SearchType.Search to setOf("q")))
    private val context = RankingContext(StaticDeviceCapabilities(videoTenBit = emptySet()))

    /** The recent searches, newest first, one per words as the library keeps them. */
    private class History : SearchHistory {
        val entries = MutableStateFlow<List<RecentSearch>>(emptyList())
        override val recent: Flow<List<RecentSearch>> = entries

        override suspend fun add(query: String, pick: SearchPick?) {
            val key = query.trim().lowercase()
            entries.update { list -> listOf(RecentSearch(key, query.trim(), 0, 0, pick = pick)) + list.filter { it.key != key } }
        }

        override suspend fun addTitle(title: SearchPick) {
            val key = RecentSearch.titleKey(title)
            entries.update { list -> listOf(RecentSearch(key, title.name, 0, 0, pick = title, kind = RecentKind.Title)) + list.filter { it.key != key } }
        }

        override suspend fun remove(key: String) = entries.update { list -> list.filter { it.key != key } }

        override suspend fun clear() = entries.update { emptyList() }
    }

    /** One search asked of the indexer: what was planned for it, and whether its answer had to be fresh. */
    private data class Asked(val query: SearchQuery?, val fresh: Boolean)

    private fun item(title: String) = NewznabItem(
        indexerId = indexer.id, guid = title, title = title, sizeBytes = 2_000_000_000, publishedAt = null, postedAt = null,
        categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
        tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
    )

    private inner class Fixture {
        val key = MutableStateFlow(true)
        /** TMDB can't be asked: its search throws. */
        var tmdbDown = false
        /** Holds TMDB's answers until completed. */
        var tmdbAnswer: CompletableDeferred<Unit>? = null
        /** The words TMDB was asked about, in order. */
        val tmdbAsked = mutableListOf<String>()
        val asked = mutableListOf<Asked>()
        /** What the indexer answers every search with. */
        var releases = listOf("Quiet.Marsh.2021.1080p.WEB-DL.H.264-GRP")
        /** Holds the indexer's answers until completed; [cancelled] counts the searches dropped meanwhile. */
        var gate: CompletableDeferred<Unit>? = null
        var cancelled = 0
        val history = History()
        /** The indexers a search asks, in Settings' order. */
        var configs = listOf(indexer)
        /** What a search ranks by as it starts: the choice in force (L127). */
        var ranking = context
        /** What a list on screen ranks again by as a choice is made in Play on (L127). */
        val rankings = MutableStateFlow(context)
        /** Play on itself, set before [start]: what a search ranks by and the lists' cue as the app makes them, in place of [ranking] and [rankings]. */
        var playOn: PlayOn? = null
        private val store = ViewModelStore()
        private lateinit var deps: SearchDeps

        /** [search] in place of the fake indexer's. */
        fun start(scope: TestScope, search: ((List<IndexerConfig>, Boolean, (IndexerCaps) -> SearchQuery?) -> Flow<IndexerOutcome>)? = null): Fixture {
            val dispatcher = StandardTestDispatcher(scope.testScheduler)
            Dispatchers.setMain(dispatcher)
            deps = SearchDeps(
                hasTmdbKey = key, showArtwork = MutableStateFlow(true), hasIndexers = MutableStateFlow(true),
                collectedShows = MutableStateFlow(emptySet()), collectedMovies = MutableStateFlow(emptySet()),
                history = history,
                adder = CollectionAdder(CoroutineScope(SupervisorJob() + dispatcher), {}, {}, {}),
                searchTitles = { q ->
                    tmdbAsked += q
                    tmdbAnswer?.await()
                    if (tmdbDown) throw IOException("TMDB unreachable")
                    catalogue.filter { it.name.contains(q, ignoreCase = true) }
                },
                show = { error("no details looked up here") }, movie = { error("no details looked up here") },
                indexers = { configs },
                rankingContext = { playOn?.let { forChoice(rankingTargetOf(null, it.target.value, CastProfiles(emptyList()))) } ?: ranking },
                rankings = playOn?.let { listRankings(it.picked, it.target, flowOf(emptyList())).map { by -> forChoice(by.cast) } } ?: rankings,
                search = search ?: { indexers, fresh, plan ->
                    flow {
                        val query = plan(words)
                        asked += Asked(query, fresh)
                        try {
                            gate?.await()
                        } catch (e: CancellationException) {
                            cancelled++
                            throw e
                        }
                        indexers.forEach { emit(IndexerOutcome.Results(it, SearchPage(releases.map(::item), 0, null, null), query!!)) }
                    }
                },
                dispatcher = dispatcher,
            )
            return this
        }

        fun vm(initial: SearchRequest? = null): SearchViewModel =
            ViewModelProvider(store, viewModelFactory { initializer { SearchViewModel(deps, initial) } })[SearchViewModel::class.java]
    }

    private fun TestScope.fixture() = Fixture().start(this)

    /** What the app ranks by for [cast] (its rankingContext(cast)): the Cast device's profile, by its name, or this device. */
    private fun forChoice(cast: CastRanking?): RankingContext = cast?.let { context.copy(device = it.profile, target = it.target) } ?: context

    /** The release names a search's results show, playable ones, as grouped. */
    private fun SearchResults.names(): List<String> = grouped.sections.flatMap { s -> s.groups.flatMap { g -> g.playable.map { it.item.title } } }

    /** [text] typed, and [ms] gone by since. */
    private fun TestScope.type(vm: SearchViewModel, text: String, ms: Long = 400) {
        vm.onQueryChange(text)
        advanceTimeBy(ms)
        runCurrent()
    }

    /** Enter closes the keyboard (the screen does) and keeps the list the preview shows: its titles, no indexer asked. */
    @Test
    fun `Enter keeps the titles the preview shows and asks no indexer`() = runTest {
        val f = fixture()
        val vm = f.vm()
        type(vm, "glasswater")
        assertEquals(listOf(glasswater), vm.suggestions.value)

        vm.keep()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(vm.editing.value, "the preview still shows")
        assertEquals(listOf(glasswater), vm.suggestions.value)
        assertNull(vm.results.value)
        assertEquals(emptyList(), f.asked)
        assertEquals(emptyList(), f.history.entries.value)
    }

    /** About a second after the last keystroke, with no titles from TMDB: the preview's own search, there with no extra click. */
    @Test
    fun `after a pause the preview searches the indexers for words TMDB has no titles for`() = runTest {
        val f = fixture()
        val vm = f.vm()
        type(vm, "quiet marsh", ms = 999)
        assertEquals(TitleLookup("quiet marsh", emptyList(), settled = true, NoTitles.NotFound), vm.titles.value)
        assertEquals(emptyList(), f.asked, "an indexer asked before the pause")
        assertNull(vm.fallback.value)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(Asked(SearchQuery.Text("quiet marsh"), fresh = false)), f.asked)
        val shown = assertNotNull(vm.fallback.value)
        assertTrue(shown.fallback)
        assertTrue(shown.done)
        assertEquals(listOf("Quiet.Marsh.2021.1080p.WEB-DL.H.264-GRP"), shown.names())
        assertTrue(vm.editing.value, "the preview shows it, not a search's results")
        assertEquals(emptyList(), f.history.entries.value, "a pause in typing remembered as a search")
    }

    @Test
    fun `with TMDB unreachable the preview searches the indexers after the pause`() = runTest {
        val f = fixture()
        f.tmdbDown = true
        val vm = f.vm()
        type(vm, "glasswater", ms = 1_001)
        assertEquals(TitleLookup("glasswater", emptyList(), settled = true, NoTitles.Unreachable), vm.titles.value)
        assertEquals(listOf(Asked(SearchQuery.Text("glasswater"), fresh = false)), f.asked)
        assertNotNull(vm.fallback.value)
    }

    @Test
    fun `without a TMDB key the preview searches the indexers after the pause, and TMDB isn't asked`() = runTest {
        val f = fixture()
        f.key.value = false
        val vm = f.vm()
        type(vm, "glasswater", ms = 1_001)
        assertEquals(TitleLookup("glasswater", emptyList(), settled = true, NoTitles.NoKey), vm.titles.value)
        assertEquals(emptyList(), f.tmdbAsked)
        assertEquals(listOf(Asked(SearchQuery.Text("glasswater"), fresh = false)), f.asked)
        assertNotNull(vm.fallback.value)
    }

    @Test
    fun `words TMDB has titles for show them, and no indexer is asked`() = runTest {
        val f = fixture()
        val vm = f.vm()
        type(vm, "lantern", ms = 10_000)
        assertEquals(TitleLookup("lantern", listOf(lantern), settled = true), vm.titles.value)
        assertEquals(emptyList(), f.asked)
        assertNull(vm.fallback.value)
        assertNull(vm.results.value)
    }

    @Test
    fun `fewer than three characters never search the indexers`() = runTest {
        val f = fixture()
        val vm = f.vm()
        type(vm, "qm", ms = 10_000)
        assertEquals(emptyList(), f.asked)
        assertNull(vm.fallback.value)
    }

    @Test
    fun `typing on before the pause ends asks no indexer about the words before`() = runTest {
        val f = fixture()
        val vm = f.vm()
        type(vm, "quiet", ms = 600)
        type(vm, "quiet ma", ms = 600)
        type(vm, "quiet marsh", ms = 999)
        assertEquals(emptyList(), f.asked)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(Asked(SearchQuery.Text("quiet marsh"), fresh = false)), f.asked)
    }

    @Test
    fun `typing on drops the preview's search, one still waiting on its indexer too`() = runTest {
        val f = fixture()
        val vm = f.vm()
        type(vm, "quiet marsh", ms = 1_001)
        assertNotNull(vm.fallback.value)
        type(vm, "quiet marshe", ms = 0)
        assertNull(vm.fallback.value)
        assertNull(vm.results.value)

        f.gate = CompletableDeferred()
        type(vm, "quiet marshes", ms = 1_001)
        assertEquals(false, vm.fallback.value?.done, "the indexer still to answer")
        type(vm, "quiet marshes s", ms = 0)
        assertEquals(1, f.cancelled)
        assertNull(vm.fallback.value)
        f.gate?.complete(Unit)
        runCurrent()
        assertNull(vm.results.value, "the dropped search came back")
    }

    /** Enter keeps the list as the preview shows it, the preview's own search too: the user settled on it, so it is remembered. */
    @Test
    fun `Enter keeps the preview's own search and remembers it as a search of the indexers`() = runTest {
        val f = fixture()
        val vm = f.vm()
        type(vm, "quiet marsh", ms = 1_001)
        val shown = assertNotNull(vm.fallback.value)

        vm.keep()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(shown, vm.fallback.value)
        assertTrue(vm.editing.value)
        assertEquals(1, f.asked.size)
        assertEquals(listOf(RecentSearch("quiet marsh", "quiet marsh", 0, 0)), f.history.entries.value)
    }

    /**
     * Enter pressed at once, before the pause, with no titles from TMDB: the preview's own search comes in the list the
     * user settled on, so it is remembered as Enter would have it remembered a moment later. Words typed on after Enter
     * aren't.
     */
    @Test
    fun `Enter before the preview's own search comes remembers it once it does`() = runTest {
        val f = fixture()
        val vm = f.vm()
        type(vm, "quiet marsh", ms = 0)
        vm.keep()
        advanceTimeBy(5_000)
        runCurrent()
        assertNotNull(vm.fallback.value)
        assertEquals(listOf(RecentSearch("quiet marsh", "quiet marsh", 0, 0)), f.history.entries.value, "the search settled on, not remembered")

        // TMDB still to answer as Enter is pressed: the same.
        f.tmdbAnswer = CompletableDeferred()
        type(vm, "still pond", ms = 500)
        vm.keep()
        advanceTimeBy(5_000)
        runCurrent()
        f.tmdbAnswer?.complete(Unit)
        advanceTimeBy(5_000)
        runCurrent()
        assertNotNull(vm.fallback.value)
        assertEquals(listOf("still pond", "quiet marsh"), f.history.entries.value.map { it.query })

        type(vm, "reed bank", ms = 0)
        vm.keep()
        type(vm, "reed banks", ms = 5_000)
        assertNotNull(vm.fallback.value)
        assertEquals(listOf("still pond", "quiet marsh"), f.history.entries.value.map { it.query }, "words typed after Enter remembered")
    }

    @Test
    fun `Search indexers before the pause ends is the only search`() = runTest {
        val f = fixture()
        val vm = f.vm()
        type(vm, "quiet marsh", ms = 500)
        vm.searchIndexers()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(Asked(SearchQuery.Text("quiet marsh"), fresh = false)), f.asked)
        assertFalse(assertNotNull(vm.results.value).fallback)
        assertFalse(vm.editing.value)
        assertNull(vm.fallback.value)
    }

    /**
     * The indexer's API behind MultiIndexerSearch, as the app searches it: the words of each search sent to it, counted
     * at the HTTP client, its answers held back while [answer] is incomplete.
     */
    private class Api {
        val searched = CopyOnWriteArrayList<String>()
        var answer: CompletableDeferred<Unit>? = null
        /** Completes as the first search reaches the API. */
        val reached = CompletableDeferred<Unit>()
        private val client = NewznabClient(
            HttpClient(
                MockEngine { request ->
                    val caps = request.url.parameters["t"] == "caps"
                    if (!caps) {
                        searched += request.url.parameters["q"].orEmpty()
                        reached.complete(Unit)
                        answer?.await()
                    }
                    respond(if (caps) CAPS else RESULTS, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xml"))
                },
            ) {
                install(HttpTimeout)
                expectSuccess = false
            },
        )
        private val multi = MultiIndexerSearch(client, CapsCache(client))

        // Its HTTP calls and timeouts in real time, off the test's clock.
        val search: (List<IndexerConfig>, Boolean, (IndexerCaps) -> SearchQuery?) -> Flow<IndexerOutcome> =
            { indexers, fresh, plan -> multi.search(indexers, fresh, plan).flowOn(Dispatchers.IO) }
    }

    /** Real time for the searches' HTTP calls, which run off the test's clock. */
    private suspend fun meanwhile() = withContext(Dispatchers.Default) { delay(500) }

    /**
     * The preview's searches go through MultiIndexerSearch, whose answers are reused for 15 minutes: back to words
     * searched a moment ago, the indexer isn't asked again. Its API calls, counted at the HTTP client.
     */
    @Test
    fun `the preview's searches of the same words within 15 minutes call the indexer's API once`() = runTest {
        val api = Api()
        val f = Fixture().start(this, api.search)
        val vm = f.vm()
        suspend fun answered(text: String) {
            type(vm, text, ms = 1_001)
            vm.fallback.first { it?.query == text && it.done }
        }

        answered("quiet marsh")
        assertEquals(listOf("quiet marsh"), api.searched)
        answered("quiet marshes")
        answered("quiet marsh")
        assertEquals(listOf("quiet marsh", "quiet marshes"), api.searched)
        assertEquals(listOf("Quiet.Marsh.2021.1080p.WEB-DL.H.264-GRP"), assertNotNull(vm.fallback.value).names())
    }

    /**
     * Search indexers pressed while the preview's own search of the same words is still waiting on a slow indexer: the
     * indexer is asked once, its answer to the first serving the second (it counts every call against a daily limit).
     */
    @Test
    fun `Search indexers over the preview's own search of the words still on its way asks the indexer once`() = runTest {
        val api = Api().apply { answer = CompletableDeferred() }
        val f = Fixture().start(this, api.search)
        val vm = f.vm()
        type(vm, "quiet marsh", ms = 1_001)
        api.reached.await()

        vm.searchIndexers()
        runCurrent()
        meanwhile()
        assertEquals(listOf("quiet marsh"), api.searched, "the indexer asked again")
        api.answer?.complete(Unit)
        val shown = vm.results.first { it?.done == true && !it.fallback }
        assertEquals(listOf("Quiet.Marsh.2021.1080p.WEB-DL.H.264-GRP"), assertNotNull(shown).names())
        assertEquals(listOf("quiet marsh"), api.searched)
    }

    /** The field tapped again while Search indexers still waits on a slow indexer: the preview's own search after the pause doesn't ask it again. */
    @Test
    fun `the preview's own search after Search indexers of the same words still on its way asks the indexer once`() = runTest {
        val api = Api().apply { answer = CompletableDeferred() }
        val f = Fixture().start(this, api.search)
        val vm = f.vm()
        type(vm, "quiet marsh", ms = 500)
        vm.searchIndexers()
        runCurrent()
        api.reached.await()

        vm.startEditing()
        advanceTimeBy(1_001)
        runCurrent()
        meanwhile()
        assertEquals(listOf("quiet marsh"), api.searched, "the indexer asked again")
        api.answer?.complete(Unit)
        val shown = vm.fallback.first { it?.done == true }
        assertEquals(listOf("Quiet.Marsh.2021.1080p.WEB-DL.H.264-GRP"), assertNotNull(shown).names())
        assertEquals(listOf("quiet marsh"), api.searched)
    }

    /** A space typed after the words: they are the same words, so the preview's own search of them stays as it is. */
    @Test
    fun `a space typed after the words keeps the preview's own search of them`() = runTest {
        val f = fixture()
        val vm = f.vm()
        type(vm, "quiet marsh", ms = 1_001)
        val shown = assertNotNull(vm.fallback.value)

        type(vm, "quiet marsh ", ms = 0)
        assertEquals(shown, vm.fallback.value, "the releases went, the words unchanged")
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(shown, vm.fallback.value)
        assertEquals(1, f.asked.size, "the same words searched again")
    }

    /** "Search indexers": the releases for the words as typed, with TMDB's titles for them in a strip on top. */
    @Test
    fun `Search indexers shows the releases for the words, TMDB's titles for them on top`() = runTest {
        val f = fixture()
        val vm = f.vm()
        type(vm, "glasswater")
        vm.searchIndexers()
        advanceTimeBy(1_000)
        runCurrent()
        val shown = assertNotNull(vm.results.value)
        assertFalse(vm.editing.value, "still the preview")
        assertFalse(shown.fallback)
        assertNull(shown.pick)
        assertTrue(shown.done)
        assertEquals(listOf(Asked(SearchQuery.Text("glasswater"), fresh = false)), f.asked)
        assertEquals(listOf(glasswater), vm.strip.value)
        assertEquals(listOf("glasswater"), f.tmdbAsked, "TMDB asked again for the strip")
        assertEquals(listOf(RecentSearch("glasswater", "glasswater", 0, 0)), f.history.entries.value)
    }

    /**
     * Search indexers pressed before TMDB has answered for new words: no strip until it has, not the titles of the words
     * before over the new words' releases (a tap would open an unrelated page, and remember it).
     */
    @Test
    fun `Search indexers before TMDB has answered for the words shows no strip until it has`() = runTest {
        val f = fixture()
        val vm = f.vm()
        type(vm, "glass")
        assertEquals(listOf(glasswater), vm.suggestions.value)

        f.tmdbAnswer = CompletableDeferred()
        vm.onQueryChange("lantern")
        vm.searchIndexers()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(assertNotNull(vm.results.value).done)
        assertEquals(TitleLookup("lantern", listOf(glasswater), settled = false), vm.titles.value, "TMDB answered already")
        assertEquals(emptyList(), vm.strip.value, "the titles of the words before, over the new words' releases")

        f.tmdbAnswer?.complete(Unit)
        runCurrent()
        assertEquals(listOf(lantern), vm.strip.value)
    }

    @Test
    fun `a recent search of words shows TMDB's titles for them on top of its releases`() = runTest {
        val f = fixture()
        val vm = f.vm(SearchRequest("lantern"))
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(assertNotNull(vm.results.value).done)
        assertEquals(listOf(lantern), vm.strip.value)
    }

    /** A search by a picked title has its card; words for other titles have no strip there, and while typing it's the preview's. */
    @Test
    fun `no strip over a picked title's search, nor while typing`() = runTest {
        val f = fixture()
        val info = TitleInfo(MediaType.Tv, glasswater.tmdbId, glasswater.name, 2024, "TV series · 2024 · 1 season", null, null, null, TvIds(tmdb = glasswater.tmdbId), 45)
        val vm = f.vm(SearchRequest("Glasswater Bay", glasswater, info))
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(assertNotNull(vm.results.value).done)
        assertEquals(emptyList(), vm.strip.value)
        assertEquals(emptyList(), f.tmdbAsked)

        type(vm, "lantern")
        assertEquals(listOf(lantern), vm.suggestions.value)
        assertEquals(emptyList(), vm.strip.value)
    }

    /** A title opened opens its page again, first in the list again; a search runs again. */
    @Test
    fun `a recent title opens its page again, and a recent search searches again`() = runTest {
        val f = fixture()
        val show = SearchPick(TitleType.Tv, glasswater.tmdbId, glasswater.name)
        f.history.addTitle(show)
        f.history.add("quiet marsh", null)
        val vm = f.vm()
        runCurrent()
        val (search, title) = vm.recent.value

        assertEquals(show, vm.openRecent(title))
        runCurrent()
        assertEquals(emptyList(), f.asked, "a title opened searched the indexers")
        assertNull(vm.results.value)
        assertEquals(listOf(RecentKind.Title, RecentKind.Search), f.history.entries.value.map { it.kind })

        assertNull(vm.openRecent(search))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(Asked(SearchQuery.Text("quiet marsh"), fresh = false)), f.asked)
        assertEquals("quiet marsh", assertNotNull(vm.results.value).query)
        assertFalse(vm.editing.value)
    }

    /** A title from the Titles opens its own page (the screen does): remembered as a title opened, no indexer asked. */
    @Test
    fun `a title opened from the Titles is remembered as a title, and no indexer is asked`() = runTest {
        val f = fixture()
        val vm = f.vm()
        type(vm, "glass")
        vm.openTitle(glasswater)
        advanceTimeBy(5_000)
        runCurrent()
        val show = SearchPick(TitleType.Tv, glasswater.tmdbId, "Glasswater Bay")
        assertEquals(listOf(RecentSearch("title:tv:900071", "Glasswater Bay", 0, 0, pick = show, kind = RecentKind.Title)), f.history.entries.value)
        assertEquals(emptyList(), f.asked)
        assertNull(vm.results.value)
        // Back from the page finds the preview as it was.
        assertTrue(vm.editing.value)
        assertEquals("glass", vm.query.value)
        assertEquals(listOf(glasswater), vm.suggestions.value)

        vm.openTitle(lantern)
        runCurrent()
        assertEquals(SearchPick(TitleType.Movie, lantern.tmdbId, "Lantern Field"), f.history.entries.value.first().openedTitle)
        assertEquals(emptyList(), f.asked)
    }

    /** Words said into the mic ([SearchViewModel.spoken]), from a coroutine of the test's: what came of them once it has returned. */
    private fun TestScope.say(vm: SearchViewModel, words: String): CompletableDeferred<Spoken> {
        val outcome = CompletableDeferred<Spoken>()
        backgroundScope.launch { outcome.complete(vm.spoken(words)) }
        return outcome
    }

    /** Voice search: what is said goes in the field, and TMDB's titles for it, here at once, show to pick from; no indexer is asked. */
    @Test
    fun `words said show TMDB's titles for them when they come within a second and a half`() = runTest {
        val f = fixture()
        val vm = f.vm()
        val outcome = say(vm, " glasswater ")
        advanceTimeBy(400)
        runCurrent()
        assertTrue(outcome.isCompleted, "still waiting with the titles there")
        assertEquals(Spoken.Titles, outcome.await())
        assertEquals("glasswater", vm.query.value)
        assertTrue(vm.editing.value, "the titles show to pick from")
        assertEquals(listOf(glasswater), vm.suggestions.value)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(emptyList(), f.asked, "an indexer asked though there are titles")
    }

    @Test
    fun `words said that TMDB has no titles for search the indexers at once`() = runTest {
        val f = fixture()
        val vm = f.vm()
        val outcome = say(vm, "quiet marsh")
        advanceTimeBy(400)
        runCurrent()
        assertTrue(outcome.isCompleted, "still waiting with TMDB's answer in")
        assertEquals(Spoken.Searched, outcome.await())
        assertEquals(listOf(Asked(SearchQuery.Text("quiet marsh"), fresh = false)), f.asked)
        val shown = assertNotNull(vm.results.value)
        assertTrue(shown.byWords, "a search of the words, not the preview's own")
        assertFalse(vm.editing.value)
    }

    @Test
    fun `words said search the indexers after a second and a half while TMDB is slow`() = runTest {
        val f = fixture()
        f.tmdbAnswer = CompletableDeferred()
        val vm = f.vm()
        val outcome = say(vm, "glasswater")
        advanceTimeBy(1_400)
        runCurrent()
        assertFalse(outcome.isCompleted, "gave up on TMDB before a second and a half")
        assertEquals(emptyList(), f.asked)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(Spoken.Searched, outcome.await())
        assertEquals(listOf(Asked(SearchQuery.Text("glasswater"), fresh = false)), f.asked)
    }

    @Test
    fun `words said without a TMDB key search the indexers at once`() = runTest {
        val f = fixture()
        f.key.value = false
        val vm = f.vm()
        val outcome = say(vm, "glasswater")
        advanceTimeBy(400)
        runCurrent()
        assertEquals(Spoken.Searched, outcome.await())
        assertEquals(emptyList(), f.tmdbAsked)
        assertEquals(listOf(Asked(SearchQuery.Text("glasswater"), fresh = false)), f.asked)
    }

    /** Ranked for Kitchen display, an HD Chromecast, as the app ranks while it is chosen in Play on (L126, L127). */
    private val kitchen = context.copy(device = CastModel.Chromecast.profile(null), target = RankingTarget.Cast("Kitchen display"))

    /** The playable releases, best first, and the hidden ones, of the search's only group. */
    private fun SearchResults.ranked(): Pair<List<String>, List<String>> {
        val group = grouped.sections.single().groups.single()
        return group.playable.map { it.item.title } to group.hidden.map { it.item.title }
    }

    /**
     * L127: a Cast device chosen in Play on ranks the releases the search holds again for it, with no new search (the
     * indexer asked once), and says so; This phone chosen again ranks them for this phone. Here a DTS-only remux is hidden
     * (this phone has no DTS decoder); for Kitchen display it is tried last, warned, as nothing is blocked for a Cast device.
     */
    @Test
    fun `choosing a Cast device ranks the results held again for it, with no new search`() = runTest {
        val f = fixture()
        f.releases = listOf(DTS, ORCHID)
        val vm = f.vm(SearchRequest("lantern field 2008"))
        runCurrent()
        val here = assertNotNull(vm.results.value)
        assertTrue(here.done)
        assertNull(here.rankedFor)
        assertEquals(listOf(ORCHID) to listOf(DTS), here.ranked())

        f.rankings.value = kitchen
        runCurrent()
        val cast = assertNotNull(vm.results.value)
        assertEquals("Kitchen display", cast.rankedFor)
        assertEquals(listOf(ORCHID, DTS) to emptyList(), cast.ranked())
        val dts = cast.grouped.sections.single().groups.single().playable.last()
        assertEquals(listOf(ReleaseReason.NoSoundOn("DTS-HD MA", "Kitchen display")), dts.ranked.reasons.filterIsInstance<ReleaseReason.NoSoundOn>())
        assertEquals(1, f.asked.size, "an indexer asked again")

        f.rankings.value = context
        runCurrent()
        assertNull(vm.results.value?.rankedFor)
        assertEquals(listOf(ORCHID) to listOf(DTS), vm.results.value?.ranked())
        assertEquals(1, f.asked.size, "an indexer asked again")
    }

    /**
     * L127, W36: This phone back by itself (Play on's cue says nothing) leaves the list as it was ranked, its line too;
     * opened again, it ranks for the choice in force then, still with no new search.
     */
    @Test
    fun `a list left as it was ranks for the choice in force as it is opened again`() = runTest {
        val f = fixture()
        f.releases = listOf(DTS, ORCHID)
        f.ranking = kitchen
        f.rankings.value = kitchen
        val vm = f.vm(SearchRequest("lantern field 2008"))
        runCurrent()
        assertEquals("Kitchen display", vm.results.value?.rankedFor)
        // The session ended: Play went back to this phone by itself, which a list on screen doesn't follow.
        f.ranking = context
        runCurrent()
        assertEquals("Kitchen display", vm.results.value?.rankedFor)
        assertEquals(listOf(ORCHID, DTS) to emptyList(), vm.results.value?.ranked())
        vm.listOpened()
        runCurrent()
        assertNull(vm.results.value?.rankedFor)
        assertEquals(listOf(ORCHID) to listOf(DTS), vm.results.value?.ranked())
        assertEquals(1, f.asked.size, "an indexer asked again")
    }

    /** L127: a choice made while the indexers still answer: what comes after is ranked for it too. */
    @Test
    fun `a Cast device chosen while the search runs ranks what comes after for it`() = runTest {
        val f = fixture()
        f.releases = listOf(DTS, ORCHID)
        f.gate = CompletableDeferred()
        val vm = f.vm(SearchRequest("lantern field 2008"))
        runCurrent()
        f.rankings.value = kitchen
        runCurrent()
        f.gate?.complete(Unit)
        runCurrent()
        val cast = assertNotNull(vm.results.value)
        assertTrue(cast.done)
        assertEquals("Kitchen display", cast.rankedFor)
        assertEquals(listOf(ORCHID, DTS) to emptyList(), cast.ranked())
    }

    /**
     * L127 (CFR-1): Kitchen display chosen again after This phone came back by itself, and a list opened meanwhile ranked
     * the results for this phone: they rank for it again, as at its first choice. Play on and its cue are the app's own,
     * over a fake session.
     */
    @Test
    fun `the same Cast device chosen again after This phone came back by itself ranks the results for it again`() = runTest {
        val session = MutableStateFlow<CastLinkNow?>(null)
        val on = PlayOn(backgroundScope, flowOf(emptyList()), flowOf(emptyMap()), session) { AutoCloseable {} }
        val f = Fixture().apply { playOn = on }.start(this)
        f.releases = listOf(DTS, ORCHID)
        val kitchenChoice = PlayChoice.Cast("route:kitchen", "Kitchen display")
        val connected = CastLinkNow("route:kitchen", "Kitchen display", CastLink.Connected, CastDeviceFacts("device-kitchen", "Chromecast", null, CastDeviceKind.Video, fixedVolume = false))
        session.value = connected
        on.choose(kitchenChoice)
        runCurrent()
        val vm = f.vm(SearchRequest("lantern field 2008"))
        runCurrent()
        assertEquals("Kitchen display", vm.results.value?.rankedFor)
        assertEquals(listOf(ORCHID, DTS) to emptyList(), vm.results.value?.ranked())
        // The device ended the session: This phone by itself, which the list doesn't follow; opened again, it ranks here.
        session.value = null
        runCurrent()
        assertEquals(PlayChoice.Phone, on.chosen.value)
        assertEquals("Kitchen display", vm.results.value?.rankedFor)
        vm.listOpened()
        runCurrent()
        assertNull(vm.results.value?.rankedFor)
        assertEquals(listOf(ORCHID) to listOf(DTS), vm.results.value?.ranked())
        // Kitchen display chosen again on the Play on list: its row says connecting as it chooses it, then it connects.
        session.value = connected.copy(link = CastLink.Connecting)
        on.choose(kitchenChoice)
        runCurrent()
        session.value = connected
        runCurrent()
        assertEquals("Kitchen display", vm.results.value?.rankedFor, "ranked for the device chosen again")
        assertEquals(listOf(ORCHID, DTS) to emptyList(), vm.results.value?.ranked())
        assertEquals(1, f.asked.size, "an indexer asked again")
    }

    private val prowlarr = IndexerConfig("p", "Home Prowlarr", "http://p.invalid", "key-p", kind = IndexerKind.Torznab)

    /** [title] as Home Prowlarr lists it: a torrent of [hash], its check settled as [availability] (null: not yet). */
    private fun torrent(title: String, hash: String, availability: Availability? = null) =
        item(title).copy(indexerId = prowlarr.id, guid = "btih:$hash", infoHash = hash, availability = availability)

    private fun page(vararg items: NewznabItem) = SearchPage(items.toList(), 0, null, null)

    /**
     * L199, W71: Usenet's results show as they come; a torrent indexer's pill says how many of its torrents are being
     * checked while they are, each joining the list as its check settles; once all have, its pill says how many it found,
     * and how many couldn't be checked.
     */
    @Test
    fun `a torrent indexer's pill counts the torrents being checked, which join the list as they settle`() = runTest {
        val usenet = item("Quiet.Marsh.2021.1080p.WEB-DL.H.264-GRP")
        val cached = torrent("Quiet.Marsh.2021.1080p.WEB-DL.H.264-TIDE", "1111111111111111111111111111111111111111")
        val uncached = torrent("Quiet.Marsh.2021.720p.HDTV.x264-DRIFT", "2222222222222222222222222222222222222222")
        val unchecked = torrent("Quiet.Marsh.2021.2160p.WEB.H265-KESTREL", "3333333333333333333333333333333333333333")
        val held = CompletableDeferred<Unit>()
        val f = Fixture().apply { configs = listOf(indexer, prowlarr) }.start(this) { _, _, plan ->
            flow {
                val query = plan(words)!!
                emit(IndexerOutcome.Results(indexer, page(usenet), query))
                emit(IndexerOutcome.Checking(prowlarr, query, page(cached, uncached, unchecked), waiting = 3))
                emit(IndexerOutcome.Checked(prowlarr, listOf(cached.copy(availability = Availability.Cached(listOf("torbox-1")))), waiting = 2))
                held.await()
                emit(IndexerOutcome.Checked(prowlarr, listOf(uncached.copy(availability = Availability.NotCached)), waiting = 1))
                emit(IndexerOutcome.Checked(prowlarr, listOf(unchecked.copy(availability = Availability.NotChecked)), waiting = 0))
            }
        }
        val vm = f.vm(SearchRequest("quiet marsh"))
        runCurrent()
        val checking = assertNotNull(vm.results.value)
        assertEquals(
            listOf(IndexerStatus.State.Answered(1), IndexerStatus.State.Checking(results = 3, waiting = 2)),
            checking.statuses.map { it.state },
        )
        assertTrue(checking.done, "every indexer answered: nothing waits for the check")
        assertFalse(checking.nothingPlayable)
        assertEquals(setOf(usenet.title, cached.title), checking.listed(), "Usenet's at once, the cached torrent once checked")
        // The footnote's "4 results from 2 of 2 indexers": the torrents still checked count as found.
        assertEquals(4 to 2, checking.found to checking.answered)

        held.complete(Unit)
        runCurrent()
        val after = assertNotNull(vm.results.value)
        assertEquals(listOf(IndexerStatus.State.Answered(1), IndexerStatus.State.Answered(3, unchecked = 1)), after.statuses.map { it.state })
        assertEquals(setOf(usenet.title, cached.title, uncached.title, unchecked.title), after.listed())
    }

    /** Every release a search's results hold, playable or hidden. */
    private fun SearchResults.listed(): Set<String> = grouped.sections.flatMap { s -> s.groups.flatMap { g -> (g.playable + g.hidden).map { it.item.title } } }.toSet()

    private companion object {
        const val DTS = "Lantern.Field.2008.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY"
        const val ORCHID = "Lantern.Field.2008.1080p.BluRay.DD5.1.x264-ORCHID"
        const val CAPS = """<?xml version="1.0" encoding="UTF-8"?><caps><server version="1.0" title="Words"/><limits max="100" default="100"/>""" +
            """<searching><search available="yes" supportedParams="q"/><tv-search available="no"/><movie-search available="no"/></searching></caps>"""
        const val RESULTS = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/">""" +
            """<channel><newznab:response offset="0" total="1"/><item><title>Quiet.Marsh.2021.1080p.WEB-DL.H.264-GRP</title>""" +
            """<guid isPermaLink="false">qm-1</guid><enclosure url="http://a.invalid/api?t=get&amp;id=qm-1" length="2000000000" type="application/x-nzb"/>""" +
            """<newznab:attr name="category" value="2040"/><newznab:attr name="size" value="2000000000"/><newznab:attr name="guid" value="qm-1"/>""" +
            """</item></channel></rss>"""
    }

    /**
     * A debrid-only user (L194): with torrent indexers searched while a service is on, Search doesn't ask for an indexer;
     * with the service off, or the torrent indexers off, it does, as it does without any.
     */
    @Test
    fun `torrent indexers with a service on count as indexers, not without one`() {
        val torrent = TorrentIndexerRecord("t", "Home Prowlarr", "http://10.0.2.2:8096/prowlarr/1")
        val usenet = IndexerRecord("a", "Indexer A", "http://10.0.2.2:8089")
        assertTrue(IndexerConfigs.anySearched(emptyList(), listOf(torrent), serviceOn = true))
        assertFalse(IndexerConfigs.anySearched(emptyList(), listOf(torrent), serviceOn = false))
        assertFalse(IndexerConfigs.anySearched(emptyList(), listOf(torrent.copy(enabled = false)), serviceOn = true))
        assertTrue(IndexerConfigs.anySearched(listOf(usenet), emptyList(), serviceOn = false))
        assertFalse(IndexerConfigs.anySearched(listOf(usenet.copy(enabled = false)), emptyList(), serviceOn = false))
        assertFalse(IndexerConfigs.anySearched(emptyList(), emptyList(), serviceOn = true))
    }
}
