package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.newznab.IndexerCaps
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerOutcome
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.ReleaseSearch
import io.skjaere.usenet.newznab.SearchPage
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.plugin.Found
import io.skjaere.usenet.plugin.IndexerLine
import io.skjaere.usenet.plugin.PluginSearch
import io.skjaere.usenet.plugin.ReleaseHandle
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.RankingPrefs
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * L630 on a fake search and a fake clock: what a plugin's search gives (each indexer's line in Settings' order, the releases
 * best first, ranked over the runtime it gives), what never leaves the host (a key, an address, a guid, a download link, an
 * indexer's own error, an indexer named after its host), the handle table, and the soft cap on searches that ask an
 * indexer anew.
 */
class PluginSearchServiceTest {
    @TempDir
    lateinit var dir: File

    // None of these may reach a plugin.
    private val canaryKey = "canary-key-5d1e09"
    private val canaryHost = "canary-host-7b2c.invalid"
    private val canaryGuid = "canary-guid-31f4a8"
    private val canaryTemplate = "https://$canaryHost/getnzb/$canaryGuid.nzb?r=$canaryKey&apikey={apikey}"
    private val canaries = listOf(canaryKey, canaryHost, canaryGuid, "getnzb", "apikey", "canary")

    /** Saved without a name: Settings named it after its host. */
    private val hostNamed = IndexerConfig("h", canaryHost, "https://$canaryHost/api", canaryKey)
    private val switchedOff = IndexerConfig("o", "Shelf", "https://shelf.invalid/api", "k", enabled = false)
    private val quarry = IndexerConfig("q", "Quarry", "https://quarry.invalid/api", canaryKey)

    /** A device with no 10-bit decoding: a 10-bit HEVC release is hidden. */
    private val context = RankingContext(StaticDeviceCapabilities(videoTenBit = emptySet()))

    private fun item(indexer: IndexerConfig, title: String, size: Long = 4_000_000_000, posted: Instant? = null, published: Instant? = null) = NewznabItem(
        indexerId = indexer.id, guid = "$canaryGuid-$title", title = title, sizeBytes = size, publishedAt = published, postedAt = posted,
        categories = listOf(2040), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
        tvmazeId = null, season = null, episode = null, coverUrl = "https://$canaryHost/covers/$canaryGuid.jpg", mediaInfo = null,
        downloadTemplate = canaryTemplate, attributes = mapOf("guid" to listOf(canaryGuid), "comments" to listOf("https://$canaryHost/details/$canaryGuid")),
    )

    private fun results(indexer: IndexerConfig, words: String, vararg items: NewznabItem, requested: Boolean = false) =
        IndexerOutcome.Results(indexer, SearchPage(items.toList(), offset = 0, total = null, limits = null), SearchQuery.Text(words), requested)

    /**
     * The app's search as a plugin's goes through it: [answer] gives each indexer's outcomes for the words asked, in order.
     * Each search's words and whether it was fresh are kept ([asked]).
     */
    private class FakeSearch(var answer: (words: String, indexers: List<IndexerConfig>) -> List<IndexerOutcome> = { _, _ -> emptyList() }) : ReleaseSearch {
        val asked = mutableListOf<Pair<String, Boolean>>()

        override fun search(indexers: List<IndexerConfig>, fresh: Boolean, plan: (IndexerCaps) -> SearchQuery?): Flow<IndexerOutcome> = flow {
            val query = plan(IndexerCaps())
            // A plugin's search is its words alone, with no category, whatever the indexer's caps.
            check(query is SearchQuery.Text && query.categories.isEmpty()) { "not a text search: $query" }
            asked += query.query to fresh
            answer(query.query, indexers).forEach { emit(it) }
        }
    }

    private var clock = 0L

    private fun TestScope.service(
        search: ReleaseSearch,
        indexers: List<IndexerConfig> = listOf(hostNamed, switchedOff, quarry),
        ranking: RankingContext = context,
        maxHandles: Int = PluginSearchService.MAX_HANDLES,
    ) = PluginSearchService({ search }, { indexers }, { ranking }, now = { clock }, maxHandles = maxHandles, work = StandardTestDispatcher(testScheduler))

    /** Every word a [Found] holds, its handles' own included, for the canaries to be looked for. */
    private fun Found.words(): List<String> =
        indexers.flatMap { listOf(it.name, it.text, it.state.name) } +
            releases.flatMap { r -> listOfNotNull(r.name, r.indexer, r.whyNot, r.postedAt?.toString(), r.handle.toString()) + r.handle.fieldValues() }

    private fun ReleaseHandle.fieldValues(): List<String> =
        generateSequence<Class<*>>(javaClass) { it.superclass }.flatMap { it.declaredFields.asSequence() }
            .map { field -> field.isAccessible = true; field.get(this).toString() }.toList()

    private fun assertNoCanary(found: List<Found>) {
        val words = found.flatMap { it.words() }
        canaries.forEach { canary -> assertFalse(words.any { canary in it }, "\"$canary\" reached a plugin: $words") }
    }

    private fun line(found: Found, name: String): IndexerLine = found.indexers.single { it.name == name }

    @Test
    fun `each indexer's line in Settings' order and the releases best first, as each indexer answers`() = runTest {
        val posted = Instant.parse("2026-11-20T18:00:00Z")
        val published = Instant.parse("2026-11-21T09:30:00Z")
        val fake = FakeSearch { words, _ ->
            listOf(
                results(
                    quarry, words,
                    item(quarry, "Copper.Crown.2019.720p.WEB.h264-SHORT", size = 2_000_000_000, posted = posted),
                    item(quarry, "Copper.Crown.2019.1080p.WEB-DL.HEVC.10bit-TEN", size = 3_000_000_000, published = published),
                ),
                results(hostNamed, words, item(hostNamed, "Copper.Crown.2019.1080p.BluRay.x264-GOOD", size = 9_000_000_000)),
            )
        }
        val found = service(fake).of("kestrel", "Kestrel").search("Copper Crown", runtimeMinutes = 118).toList()

        assertEquals(listOf("Copper Crown" to false), fake.asked)
        assertEquals(2, found.size, "a Found as each indexer answered")
        val first = found.first()
        assertEquals(listOf("Indexer 1", "Quarry"), first.indexers.map { it.name }, "the indexers on, in Settings' order")
        assertEquals(IndexerLine.State.Searching, line(first, "Indexer 1").state)
        assertEquals("Searching…", line(first, "Indexer 1").text)
        assertEquals(listOf(IndexerLine.State.Found, 2, "2 found"), line(first, "Quarry").let { listOf(it.state, it.found, it.text) })

        val last = found.last()
        assertEquals(listOf(IndexerLine.State.Found, 1, "1 found"), line(last, "Indexer 1").let { listOf(it.state, it.found, it.text) })
        assertEquals(
            listOf("Copper.Crown.2019.1080p.BluRay.x264-GOOD", "Copper.Crown.2019.720p.WEB.h264-SHORT", "Copper.Crown.2019.1080p.WEB-DL.HEVC.10bit-TEN"),
            last.releases.map { it.name },
            "the playable ones first, best first",
        )
        val (good, short, tenBit) = last.releases
        assertEquals(listOf("Indexer 1", "Quarry", "Quarry"), last.releases.map { it.indexer })
        assertEquals(listOf(9_000_000_000, 2_000_000_000, 3_000_000_000), last.releases.map { it.size })
        assertEquals(listOf(true, true, false), last.releases.map { it.playable })
        assertNull(good.whyNot)
        assertTrue(tenBit.whyNot.orEmpty().endsWith("no decoder"), "${tenBit.whyNot}")
        assertEquals(posted, short.postedAt)
        assertEquals(published, tenBit.postedAt, "its published date where it gives no posted one")
        assertNull(good.postedAt)
        assertNoCanary(found)
    }

    /**
     * The item's runtime is what each release's bitrate is estimated over (C28): a 1080p release of 8 GB is about 18 Mbps
     * over an hour, above a maximum of 10, and about 4.4 over four hours, where the larger one fitting ranks first.
     */
    @Test
    fun `the runtime given changes the ranking`() = runTest {
        val fake = FakeSearch { words, _ ->
            listOf(
                results(
                    quarry, words,
                    item(quarry, "Kestrel.Cup.2026.Final.1080p.WEB-DL.H.264-BIG", size = 8_000_000_000),
                    item(quarry, "Kestrel.Cup.2026.Final.1080p.WEB-DL.H.264-SMALL", size = 3_000_000_000),
                ),
            )
        }
        val search = service(fake, listOf(quarry), ranking = context.copy(prefs = RankingPrefs(maxBitrate = 10_000_000))).of("kestrel", "Kestrel")
        suspend fun first(runtime: Int?) = search.search("Kestrel Cup Final", runtime).last().releases.first().name

        assertEquals("Kestrel.Cup.2026.Final.1080p.WEB-DL.H.264-SMALL", first(60))
        assertEquals("Kestrel.Cup.2026.Final.1080p.WEB-DL.H.264-BIG", first(240))
        assertEquals(listOf("Kestrel Cup Final", "Kestrel Cup Final"), fake.asked.map { it.first }, "the same words, ranked again")
    }

    /**
     * A failure is said by its kind in the host's words: an indexer's own message can hold its address, the request with
     * its words, and the key where the indexer echoes it.
     */
    @Test
    fun `a failure is worded by its kind, never by the indexer's message`() = runTest {
        val message = "GET https://$canaryHost/api?t=search&q=copper+crown&apikey=$canaryKey failed: $canaryHost/203.0.113.9:443 said $canaryGuid"
        val words = mapOf<NewznabError, String>(
            NewznabError.Auth(message) to "Sign-in refused",
            NewznabError.Disabled(message) to "Sign-in refused",
            NewznabError.ApiLimitReached(message) to "Limit reached",
            NewznabError.DownloadLimitReached(message) to "Limit reached",
            NewznabError.Timeout(message) to "No answer in time",
            NewznabError.Network(message) to "Couldn't reach it",
            NewznabError.Server(message) to "Server error",
            NewznabError.BadResponse(message) to "Bad answer",
            NewznabError.NotFound(message) to "Bad answer",
            NewznabError.Unsupported(message) to "Bad answer",
        )
        val fake = FakeSearch()
        val search = service(fake, listOf(hostNamed)).of("kestrel", "Kestrel")
        words.forEach { (error, said) ->
            fake.answer = { _, _ -> listOf(IndexerOutcome.Failed(hostNamed, error)) }
            val found = search.search("Copper Crown", runtimeMinutes = null).toList()
            val failed = line(found.last(), "Indexer 1")
            assertEquals(listOf(IndexerLine.State.Failed, 0, said), listOf(failed.state, failed.found, failed.text), error::class.simpleName)
            assertEquals(emptyList(), found.last().releases)
            assertNoCanary(found)
            assertFalse(found.flatMap { it.words() }.any { "203.0.113.9" in it || "copper+crown" in it })
        }
    }

    /**
     * An indexer is named only where the user typed a name that isn't its address; otherwise by its place in Settings.
     * Settings names one saved without a name after its host.
     */
    @Test
    fun `an indexer named after its host is Indexer and its place, one the user named keeps its name`() = runTest {
        val byAddress = IndexerConfig("u", "https://lantern.invalid/api", "https://lantern.invalid/api", "k")
        val shouting = IndexerConfig("s", "HARBOR.INVALID", "http://harbor.invalid:5076", "k")
        val indexers = listOf(hostNamed, quarry, switchedOff, byAddress, shouting)
        val fake = FakeSearch { words, asked -> asked.filter { it.enabled }.map { results(it, words, item(it, "Copper.Crown.2019.1080p.WEB-DL.H.264-${it.id.uppercase()}")) } }
        val last = service(fake, indexers).of("kestrel", "Kestrel").search("Copper Crown", runtimeMinutes = null).last()

        assertEquals(listOf("Indexer 1", "Quarry", "Indexer 4", "Indexer 5"), last.indexers.map { it.name })
        assertEquals(
            mapOf("H" to "Indexer 1", "Q" to "Quarry", "U" to "Indexer 4", "S" to "Indexer 5"),
            last.releases.associate { it.name.substringAfterLast('-') to it.indexer },
        )
        assertFalse(last.words().any { "lantern" in it || "harbor" in it.lowercase() })
    }

    /**
     * A name that reads as an address is never given, whosever it was: Settings names an indexer saved with no name after
     * its host, and an edit of its address alone keeps that name, now another host's. Names with words in them are kept.
     */
    @Test
    fun `a name that reads as a host or an address is never given`() = runTest {
        val addresses = listOf("api.oldindexer.invalid", "OLD.Indexer.invalid", "nzb.lantern.invalid:5076", "192.0.2.10", "192.0.2.10:8080", "[2001:db8::1]", "2001:db8::1", "localhost", "localhost:5076")
        val typed = listOf("Quarry", "Kestrel Feed", "NZB-Lantern", "Lantern (fast)", "Indexer.One backup")
        val indexers = (addresses + typed).mapIndexed { i, name -> IndexerConfig("i$i", name, "https://moved-$i.invalid/api", "k") }
        val fake = FakeSearch { words, asked -> asked.map { results(it, words) } }
        val last = service(fake, indexers).of("kestrel", "Kestrel").search("Copper Crown", runtimeMinutes = null).last()
        assertEquals(addresses.indices.map { "Indexer ${it + 1}" } + typed, last.indexers.map { it.name })
    }

    /**
     * L630's soft cap, on the test's clock: ten searches a minute that ask an indexer anew, failed ones too; an answer from
     * the cache never counts. The eleventh is answered at once, every indexer paused, and asks nothing; the pause is
     * logged once a minute, by the plugin's id alone. Another plugin's searches are its own.
     */
    @Test
    fun `ten searches a minute that ask an indexer, the eleventh paused and logged once`() = runTest {
        val seen = mutableSetOf<String>()
        // As the app's cache answers: new words ask the indexer, words asked before don't.
        val fake = FakeSearch { words, _ -> listOf(results(quarry, words, item(quarry, "Copper.Crown.2019.1080p.WEB-DL.H.264-GOOD"), requested = seen.add(words))) }
        val service = service(fake, listOf(hostNamed, quarry))
        val kestrel = service.of("kestrel", "Kestrel")
        suspend fun PluginSearch.found(words: String) = search(words, runtimeMinutes = null).toList()
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L

        (1..9).forEach { kestrel.found("words $it") }
        repeat(20) { kestrel.found("words 1") }
        clock += 30_000
        val tenth = kestrel.found("words 10")
        assertEquals(listOf("words 10" to false), fake.asked.takeLast(1), "the tenth search to ask an indexer is made")
        assertEquals(1, tenth.last().releases.size)

        val asked = fake.asked.size
        val paused = kestrel.found("words 11")
        assertEquals(asked, fake.asked.size, "a paused search asks nothing")
        val limited = paused.single()
        assertEquals(listOf("Indexer 1", "Quarry"), limited.indexers.map { it.name })
        limited.indexers.forEach { assertEquals(listOf(IndexerLine.State.Limited, 0, "Paused: too many searches from Kestrel"), listOf(it.state, it.found, it.text)) }
        assertEquals(emptyList(), limited.releases)
        assertEquals(IndexerLine.State.Limited, kestrel.found("words 1").single().indexers.first().state, "a repeat is paused too")
        val logged = LogBuffer.snapshot().filter { it.seq > from }.map { it.message }.filter { "paused" in it }
        assertEquals(1, logged.size, "$logged")
        assertTrue("kestrel" in logged.single())
        assertFalse(logged.single().contains("words"), "the plugin's words never reach the log")

        val other = service.of("skeleton", "Skeleton")
        assertEquals(1, other.found("words 12").last().releases.size, "another plugin's searches are its own")

        // The first nine count for a minute, to the millisecond; then one more may ask.
        clock = 59_999
        assertEquals(IndexerLine.State.Limited, kestrel.found("words 13").single().indexers.first().state)
        clock = 60_000
        assertEquals(1, kestrel.found("words 13").last().releases.size)
        assertEquals(1, LogBuffer.snapshot().filter { it.seq > from }.count { "paused" in it.message }, "logged once a minute")
    }

    /**
     * Searches started together (one per row of a list) each wait for the app's ranking settings before their first
     * request: the cap is taken as each begins, so no more than ten of them ask the indexers, the rest paused at once.
     */
    @Test
    fun `searches started together get no further past the cap than one at a time`() = runTest {
        val fake = FakeSearch { words, _ -> listOf(results(quarry, words, item(quarry, "Copper.Crown.2019.1080p.WEB-DL.H.264-GOOD"), requested = true)) }
        val service = PluginSearchService(
            { fake }, { listOf(quarry) }, { yield(); context }, now = { clock }, work = StandardTestDispatcher(testScheduler),
        )
        val kestrel = service.of("kestrel", "Kestrel")
        val found = (1..15).map { round -> async { kestrel.search("words $round", null).toList() } }.awaitAll()
        assertEquals(10, fake.asked.size, "the indexers asked by searches begun together")
        assertEquals(5, found.count { it.single().indexers.single().state == IndexerLine.State.Limited }, "the rest paused")
    }

    /**
     * The host's own failure in a plugin's search (its settings unreadable, its ranking's): the search still ends with a
     * Found, an empty one where it had none to give, so a plugin taking the last one isn't turned off for the host's
     * fault. Logged by its kind and the plugin's id; it counts nothing towards the cap.
     */
    @Test
    fun `a host failure ends the search with a Found, never with none`() = runTest {
        val fake = FakeSearch { words, _ -> listOf(results(quarry, words, item(quarry, "Copper.Crown.2019.1080p.WEB-DL.H.264-GOOD"), requested = true)) }
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        val settingsFail = PluginSearchService(
            { fake }, { throw java.io.IOException("unreadable: /data/$canaryHost/settings") }, { context }, now = { clock }, work = StandardTestDispatcher(testScheduler),
        ).of("kestrel", "Kestrel")
        val first = settingsFail.search("Copper Crown", null).last()
        assertEquals(emptyList(), first.indexers)
        assertEquals(emptyList(), first.releases)
        val rankingFails = PluginSearchService(
            { fake }, { listOf(quarry) }, { throw java.io.IOException("unreadable") }, now = { clock }, work = StandardTestDispatcher(testScheduler),
        ).of("kestrel", "Kestrel")
        repeat(PluginSearchService.CAP + 1) { assertEquals(emptyList(), rankingFails.search("Copper Crown", null).single().indexers, "not paused") }
        assertEquals(emptyList(), fake.asked, "no indexer asked")
        val logged = LogBuffer.snapshot().filter { it.seq > from }.map { it.message }.filter { "search failed" in it }
        assertTrue(logged.isNotEmpty() && logged.all { "kestrel" in it && "IOException" in it }, "$logged")
        assertFalse(logged.any { canaryHost in it || "settings" in it }, "$logged")
    }

    /** A search whose requests fail still asked the indexer, and counts: a plugin can't hammer an indexer that is down. */
    @Test
    fun `searches whose requests failed count towards the cap`() = runTest {
        val fake = FakeSearch { _, _ -> listOf(IndexerOutcome.Failed(quarry, NewznabError.Network("refused"), requested = true)) }
        val kestrel = service(fake, listOf(quarry)).of("kestrel", "Kestrel")
        repeat(10) { assertEquals(IndexerLine.State.Failed, kestrel.search("Copper Crown", null).last().indexers.single().state) }
        assertEquals(IndexerLine.State.Limited, kestrel.search("Copper Crown", null).single().indexers.single().state)
        assertEquals(10, fake.asked.size)
    }

    /**
     * A search the plugin drops before any indexer answered may have asked them all the same (a request outlives its
     * search, into the cache): it counts, so a plugin dropping its searches can't ask the indexers more often.
     */
    @Test
    fun `a search dropped before any indexer answered counts towards the cap`() = runTest {
        val words = mutableListOf<String>()
        val unanswered = object : ReleaseSearch {
            override fun search(indexers: List<IndexerConfig>, fresh: Boolean, plan: (IndexerCaps) -> SearchQuery?): Flow<IndexerOutcome> = flow {
                words += (plan(IndexerCaps()) as SearchQuery.Text).query
                awaitCancellation()
            }
        }
        val kestrel = service(unanswered, listOf(quarry)).of("kestrel", "Kestrel")
        repeat(10) { round ->
            val dropped = launch { kestrel.search("words $round", null).collect() }
            testScheduler.runCurrent()
            dropped.cancelAndJoin()
        }
        assertEquals(10, words.size)
        val eleventh = mutableListOf<Found>()
        val paused = launch { kestrel.search("words 10", null).toList(eleventh) }
        testScheduler.runCurrent()
        paused.cancelAndJoin()
        assertEquals(10, words.size, "the eleventh search asked the indexers")
        assertEquals(IndexerLine.State.Limited, eleventh.single().indexers.single().state)
    }

    /**
     * The handles of each plugin's table, the latest [maxHandles] kept: an older one, one the plugin made itself and another
     * plugin's stand for nothing. Within a search a release keeps its handle as more indexers answer.
     */
    @Test
    fun `the handle table keeps the latest handles, and a dropped or foreign one stands for nothing`() = runTest {
        assertEquals(4_096, PluginSearchService.MAX_HANDLES)
        val fake = FakeSearch { words, _ ->
            listOf(
                results(quarry, words, item(quarry, "$words.1080p.WEB-DL.H.264-ONE")),
                results(hostNamed, words, item(hostNamed, "$words.720p.WEB-DL.H.264-TWO")),
            )
        }
        val service = service(fake, listOf(hostNamed, quarry), maxHandles = 3)
        val kestrel = service.of("kestrel", "Kestrel")
        val first = kestrel.search("Copper.Crown", null).toList()
        val one = first.first().releases.single()
        assertSame(one.handle, first.last().releases.single { it.name == one.name }.handle, "a release keeps its handle as the search goes on")
        val second = kestrel.search("Harbor.Lights", null).last()
        val handles = first.last().releases.map { it.handle } + second.releases.map { it.handle }

        val kept = service.ranked("kestrel", handles)
        assertEquals(
            listOf("Copper.Crown.720p.WEB-DL.H.264-TWO", "Harbor.Lights.1080p.WEB-DL.H.264-ONE", "Harbor.Lights.720p.WEB-DL.H.264-TWO"),
            kept.map { it.item.title },
            "the oldest dropped, the rest in the order handed",
        )
        assertEquals(listOf(canaryTemplate), kept.map { it.item.downloadTemplate }.distinct(), "the host's own item, link and all")
        val made = object : ReleaseHandle {}
        assertEquals(emptyList(), service.ranked("kestrel", listOf(made)), "a handle the plugin made itself")
        assertEquals(emptyList(), service.ranked("skeleton", handles), "another plugin's handles")
    }

    /** The host an instance gets searches through the service, under the plugin's own name. */
    @Test
    fun `a host's search is the app's, under the plugin's name`() = runTest {
        val fake = FakeSearch { _, _ -> listOf(IndexerOutcome.Failed(quarry, NewznabError.Server("down"), requested = true)) }
        val service = service(fake, listOf(quarry))
        val store = PluginStore(File(dir, "files"), File(dir, "cache"), FakeSecrets())
        val host = PluginHosts(store, FakeSecrets(), main = StandardTestDispatcher(testScheduler), search = service)
            .create(PluginVersion("kestrel", 1, 1), "Kestrel") { _, _ -> }
        repeat(10) { assertEquals("Server error", host.search.search("Copper Crown", null).last().indexers.single().text) }
        assertEquals("Paused: too many searches from Kestrel", host.search.search("Copper Crown", null).single().indexers.single().text)
        host.cancel()
    }
}
