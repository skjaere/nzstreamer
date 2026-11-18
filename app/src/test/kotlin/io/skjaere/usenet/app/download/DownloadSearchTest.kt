package io.skjaere.usenet.app.download

import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadDao
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.PlaybackPrefs
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.EpisodeRequest
import io.skjaere.usenet.search.FindResult
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.FoundRelease
import io.skjaere.usenet.search.IndexerReport
import io.skjaere.usenet.search.SearchStage
import io.skjaere.usenet.search.StageReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The turn's search (L392, L398, L443, L459): a season row's episode searched at its turn by the facts its row keeps, over a
 * fake finder and a fake room on the device. What it finds that fits is written into the row before anything is grabbed,
 * as its releases, a torrent a debrid service has cached among them with its source, with what the first needs and the
 * facts as searched; nothing found, nothing that fits, the indexers out of reach and an indexer's limit each say so. The
 * torrent indexers are asked only while debrid shows and an account is on (L422). Harbor Lights S02E04; releases and
 * indexers are invented.
 */
class DownloadSearchTest {
    private val dao = FakeDao()

    /** The finder's answer: its releases, ranked as given, those it hid, and each indexer's report. */
    private var playable: List<FoundRelease> = emptyList()
    private var hidden: List<FoundRelease> = emptyList()
    private var reports: List<IndexerReport> = emptyList()

    /**
     * The finder's answer once its torrents are checked, where it has one: the real finder is Finished before a torrent
     * indexer's torrents are checked, and gives them after, as they join ([FinderEvent.Joined]).
     */
    private var joined: List<FoundRelease>? = null

    /** What the finder was asked, in order: the episode, the indexers, the ranking. */
    private val asked = mutableListOf<Triple<EpisodeRequest, List<String>, RankingContext>>()

    /** The row's state as the finder was asked: what it says meanwhile. */
    private val meanwhile = mutableListOf<Pair<DownloadState, String?>>()

    private val find: (EpisodeRequest, List<IndexerConfig>, RankingContext) -> Flow<FinderEvent> = { request, indexers, context ->
        flow {
            asked += Triple(request, indexers.map { it.id }, context)
            dao.rows.value[ID]?.let { meanwhile += it.state to it.reason }
            emit(FinderEvent.Finished(FindResult(request, playable, hidden, listOf(StageReport(SearchStage.Episode, reports)))))
            joined?.let { emit(FinderEvent.Joined(FindResult(request, it, hidden, listOf(StageReport(SearchStage.Episode, reports))))) }
        }
    }

    private var space: DownloadSpace? = DownloadSpace(available = 100 * GB, used = 0, queued = 0)
    private val spaceFor = mutableListOf<String>()

    /** The walk's memory of the indexers' limits: those it keeps out, and each limit it was told of. */
    private val limits = object : DownloadSearch.Limits {
        var out: Map<String, LimitWait> = emptyMap()
        val told = mutableListOf<Triple<String, Duration?, Long>>()

        override fun keptOut(indexers: List<IndexerConfig>, at: Long) = out.filterKeys { id -> indexers.any { it.id == id } }

        override fun answered(indexer: IndexerConfig, retryAfter: Duration?, at: Long) {
            told += Triple(indexer.id, retryAfter, at)
        }
    }

    /** The search's clock: epoch milliseconds, moved on by a case. */
    private var now = NOW

    private fun search(indexers: List<IndexerConfig> = listOf(A, B), limits: DownloadSearch.Limits = this.limits, asks: (suspend () -> List<IndexerConfig>)? = null) = DownloadSearch(
        downloads = dao,
        find = find,
        indexers = asks ?: { indexers },
        context = { DOWNLOADING },
        space = { id ->
            spaceFor += id
            space
        },
        limits = limits,
        clock = { now },
    )

    /** Harbor Lights S02E04's season row, its facts not yet searched, with the sheet's [candidates]. */
    private suspend fun row(vararg candidates: DownloadCandidate, edit: (Download) -> Download = { it }): Download {
        val row = Download(
            id = ID, watchKey = WatchKey.episode(SHOW, 2, 4), kind = DownloadKind.Episode, title = "Harbor Lights", showTmdbId = SHOW, season = 2,
            episode = 4, seasonEpisodes = 8, episodeName = "Low Water", position = 0, candidates = candidates.toList(),
            indexerSize = candidates.firstOrNull()?.size ?: 0, search = DownloadColumns.text(FACTS), createdAt = 1, updatedAt = 1,
        ).let(edit)
        dao.insert(row)
        return row
    }

    private suspend fun stored(): Download = assertNotNull(dao.get(ID))

    @Test
    fun `found, the releases that fit are written into the row in their order, at most 10, with what the first needs, searched`() = runBlocking<Unit> {
        val pack = found("Harbor.Lights.S02.1080p.WEB.h264-PACK", 8 * GB)
        val tooBig = found("Harbor.Lights.S02E04.2160p.WEB.h265-WREN", 30 * GB)
        val singles = (1..11).map { found("Harbor.Lights.S02E04.720p.WEB.h264-GRP$it", GB + it) }
        val torrent = found("Harbor.Lights.S02E04.1080p.WEB.h264-TIDE", GB, torrent = true)
        playable = listOf(pack, tooBig, torrent) + singles
        reports = listOf(IndexerReport(A, "TVDB id", found = 14, accepted = 14), IndexerReport(B, "name"))
        space = DownloadSpace(available = 20 * GB + DownloadSpace.KEPT_FREE, used = 0, queued = 0)
        val sheet = candidate("Harbor.Lights.S02E04.1080p.WEB.h264-KESTREL", 2 * GB)
        val before = row(sheet)
        val search = search()
        assertTrue(search.due(before))

        val found = assertIs<DownloadSearch.Searched.Found>(search.search(before))
        // The episode asked as a play of it asks, over the Usenet indexers given, ranked for downloading.
        val (request, indexers, context) = asked.single()
        assertEquals(FACTS.request(2, 4), request)
        assertEquals(listOf(A.id, B.id), indexers)
        assertSame(DOWNLOADING, context)
        assertEquals(listOf<Pair<DownloadState, String?>>(DownloadState.Resolving to DownloadHostRules.FINDING_RELEASES), meanwhile)
        // The pack first, then the torrent a debrid service has cached and the singles that fit, the one too big left out:
        // 10 in all.
        val kept = stored()
        assertEquals(listOf(pack.item.title, torrent.item.title) + singles.take(8).map { it.item.title }, kept.candidates.map { it.title })
        assertEquals(listOf(null, "torrent") + List(8) { null }, kept.candidates.map { it.source }, "the torrent's source written, Usenet's left out (L459)")
        assertEquals(DownloadSheetModel.candidate(io.skjaere.usenet.search.RankedItem(pack.item, pack.ranked)), kept.candidates.first())
        // What the first needs here: the pack's share of the episode, a season of 8.
        assertEquals(GB, kept.indexerSize)
        assertEquals(FACTS.copy(searched = true), DownloadColumns.search(kept))
        assertEquals(kept, found.row)
        assertEquals(listOf(ID), spaceFor)
        assertFalse(search.due(kept), "searched once: its next resolve takes what it found")
    }

    /**
     * L459: a torrent a debrid service has cached is kept as any release, by its content id and with its source, the turn
     * asking the services' cache from the background host as a play's search does; one not cached the finder hid is never
     * kept.
     */
    @Test
    fun `a cached torrent is kept with its source, and one not cached that the finder hid is not`() = runBlocking<Unit> {
        val cached = found("Harbor.Lights.S02E04.1080p.WEB.h264-TIDE", GB, torrent = true)
        val single = found("Harbor.Lights.S02E04.720p.WEB.h264-GRP", 2 * GB)
        playable = listOf(cached, single)
        hidden = listOf(found("Harbor.Lights.S02E04.720p.HDTV.x264-DRIFT", GB / 2, torrent = true, hash = "fedcba9876543210fedcba9876543210fedcba98"))
        reports = listOf(IndexerReport(A, "TVDB id", found = 3, accepted = 3))
        val before = row(candidate("Harbor.Lights.S02E04.1080p.WEB.h264-KESTREL", 2 * GB))

        assertIs<DownloadSearch.Searched.Found>(search().search(before))
        val kept = stored().candidates
        assertEquals(listOf(cached.item.title, single.item.title), kept.map { it.title }, "the one not cached never kept")
        assertEquals(DownloadCandidate(A.id, "btih:0123456789abcdef0123456789abcdef01234567", cached.item.title, null, GB, source = "torrent"), kept.first())
        assertNull(kept[1].source)
    }

    /**
     * L459, L462: the finder is Finished before a torrent indexer's torrents are checked against the services' caches; they
     * join after, once checked. The turn's search takes what the finder gives last, so a cached torrent (a season's pack
     * among them) is found from the background host as the sheets find it.
     */
    @Test
    fun `a torrent the finder gives once checked, after it is Finished, is kept`() = runBlocking<Unit> {
        val single = found("Harbor.Lights.S02E04.720p.WEB.h264-GRP", 2 * GB)
        val pack = found("Harbor.Lights.S02.1080p.WEB-DL.H.264-TIDE", 8 * GB, torrent = true)
        playable = listOf(single)
        joined = listOf(pack, single)
        reports = listOf(IndexerReport(A, "TVDB id", found = 2, accepted = 2))
        val before = row(candidate("Harbor.Lights.S02E04.1080p.WEB.h264-KESTREL", 2 * GB))

        assertIs<DownloadSearch.Searched.Found>(search().search(before))
        assertEquals(listOf(pack.item.title, single.item.title), stored().candidates.map { it.title })
        assertEquals(listOf("torrent", null), stored().candidates.map { it.source })
        assertEquals(GB, stored().indexerSize, "the pack's share of the episode, a season of 8")
    }

    /**
     * L422, L459: the turn's search asks the indexers the container gives a search
     * ([io.skjaere.usenet.app.search.IndexerConfigs.of]): the torrent indexers only while debrid shows and a service account
     * is on; hidden, or with no account on, Usenet's alone.
     */
    @Test
    fun `the turn's search asks the torrent indexers only while debrid shows and an account is on`() = runBlocking<Unit> {
        suspend fun searchedBy(account: Boolean, shown: Boolean): List<String> {
            val sources = SearchSources.of(kotlinx.coroutines.Dispatchers.Unconfined, account)
            asked.clear()
            dao.delete(ID)
            search(asks = { sources.indexers(shown) }).search(row(candidate("Harbor.Lights.S02E04.1080p.WEB.h264-KESTREL", 2 * GB)))
            val ids = asked.single().second
            val names = sources.indexers(shown = true).associate { it.id to it.name }
            return ids.map { names.getValue(it) }
        }
        assertEquals(listOf(SearchSources.USENET, SearchSources.TORZNAB), searchedBy(account = true, shown = true))
        assertEquals(listOf(SearchSources.USENET), searchedBy(account = false, shown = true), "no account on")
        assertEquals(listOf(SearchSources.USENET), searchedBy(account = true, shown = false), "debrid hidden, whatever a shown build saved")
    }

    @Test
    fun `nothing found while every indexer answered is none found, the row as it was`() = runBlocking<Unit> {
        reports = listOf(IndexerReport(A, "TVDB id", found = 3), IndexerReport(B, "name", error = NewznabError.Auth("Bad key")))
        val before = row(candidate("Harbor.Lights.S02E04.1080p.WEB.h264-KESTREL", 2 * GB))
        assertEquals(DownloadSearch.Searched.NoneFound, search().search(before))
        assertEquals(before.candidates, stored().candidates)
        assertEquals(FACTS, DownloadColumns.search(stored()))
        assertEquals(emptyList<String>(), spaceFor)
    }

    @Test
    fun `found and none fits won't fit, the row as it was`() = runBlocking<Unit> {
        playable = listOf(found("Harbor.Lights.S02E04.2160p.WEB.h265-WREN", 30 * GB), found("Harbor.Lights.S02E04.1080p.WEB.h264-GRP", 3 * GB))
        reports = listOf(IndexerReport(A, "TVDB id", found = 2, accepted = 2))
        space = DownloadSpace(available = 2 * GB + DownloadSpace.KEPT_FREE, used = 0, queued = 0)
        val before = row()
        assertEquals(DownloadSearch.Searched.WontFit, search().search(before))
        assertEquals(emptyList(), stored().candidates)
        assertEquals(FACTS, DownloadColumns.search(stored()))

        // The volume away: it waits for it.
        space = null
        assertEquals(DownloadSearch.Searched.Unavailable, search().search(before))
    }

    @Test
    fun `nothing found with an indexer out of reach or failing is out, to be searched again`() = runBlocking<Unit> {
        listOf(NewznabError.Network("Unable to resolve host"), NewznabError.Timeout("No answer within 15 s"), NewznabError.Server("HTTP 503")).forEach { error ->
            reset()
            reports = listOf(IndexerReport(A, null, error = error), IndexerReport(B, "name", found = 0))
            val before = row()
            assertEquals(DownloadSearch.Searched.Out, search().search(before), "for $error")
            assertTrue(search().due(stored()), "for $error: still to search")
        }
        // Every indexer out.
        reset()
        reports = listOf(IndexerReport(A, null, error = NewznabError.Server("HTTP 502")), IndexerReport(B, null, error = NewznabError.Timeout("No answer")))
        assertEquals(DownloadSearch.Searched.Out, search().search(row()))
    }

    @Test
    fun `an indexer's search limit with nothing found elsewhere is a wait, its end where the indexer said`() = runBlocking<Unit> {
        reports = listOf(IndexerReport(A, null, error = NewznabError.ApiLimitReached("Request limit reached", Duration.ofHours(2))), IndexerReport(B, "name"))
        val waited = assertIs<DownloadSearch.Searched.Limited>(search().search(row()))
        assertEquals(LimitWait(A.id, LimitWait.Kind.Search, NOW, NOW + 2 * HOUR), waited.wait)
        assertEquals(listOf(Triple(A.id, Duration.ofHours(2), NOW)), limits.told)

        // None said: no end. And a limit beside an indexer out of reach is still a wait, never the outage's backoff.
        reset()
        reports = listOf(IndexerReport(A, null, error = NewznabError.Server("HTTP 503")), IndexerReport(B, null, error = NewznabError.ApiLimitReached("Request limit reached")))
        assertEquals(LimitWait(B.id, LimitWait.Kind.Search, NOW, null), assertIs<DownloadSearch.Searched.Limited>(search().search(row())).wait)

        // Found elsewhere: found, the limit told all the same.
        reset()
        playable = listOf(found("Harbor.Lights.S02E04.1080p.WEB.h264-GRP", GB))
        reports = listOf(IndexerReport(A, null, error = NewznabError.ApiLimitReached("Request limit reached")), IndexerReport(B, "name", found = 1, accepted = 1))
        assertIs<DownloadSearch.Searched.Found>(search().search(row()))
        assertEquals(listOf(Triple(A.id, null as Duration?, NOW)), limits.told)
    }

    @Test
    fun `an indexer kept out for its limit isn't asked, and with nothing found elsewhere the row waits for it`() = runBlocking<Unit> {
        val kept = LimitWait(A.id, LimitWait.Kind.Search, NOW - HOUR / 2, NOW + HOUR)
        limits.out = mapOf(A.id to kept)
        reports = listOf(IndexerReport(B, "name"))
        assertEquals(kept, assertIs<DownloadSearch.Searched.Limited>(search().search(row())).wait)
        assertEquals(listOf(B.id), asked.single().second)

        // Every one kept out: nothing is asked.
        reset()
        limits.out = mapOf(A.id to kept)
        assertIs<DownloadSearch.Searched.Limited>(search(listOf(A)).search(row()))
        assertEquals(emptyList<Triple<EpisodeRequest, List<String>, RankingContext>>(), asked)
    }

    /**
     * L434, L443: with nothing found while several indexers are limited, the row waits for the limit that ends first, of
     * those answered now and those the walk keeps out, so the app's start queues it again once any of them may answer: an
     * indexer held off for a long Retry-After never keeps the row waiting past another's 30 minutes.
     */
    @Test
    fun `with several indexers limited the row waits for the limit that ends first`() = runBlocking<Unit> {
        reports = listOf(
            IndexerReport(A, null, error = NewznabError.ApiLimitReached("Request limit reached", Duration.ofHours(20))),
            IndexerReport(B, null, error = NewznabError.ApiLimitReached("Request limit reached")),
        )
        assertEquals(LimitWait(B.id, LimitWait.Kind.Search, NOW, null), assertIs<DownloadSearch.Searched.Limited>(search().search(row())).wait)

        // One kept out until an hour from now, beside one met now that says two hours: the one kept out.
        reset()
        val kept = LimitWait(A.id, LimitWait.Kind.Search, NOW - HOUR, NOW + HOUR)
        limits.out = mapOf(A.id to kept)
        reports = listOf(IndexerReport(B, null, error = NewznabError.ApiLimitReached("Request limit reached", Duration.ofHours(2))))
        assertEquals(kept, assertIs<DownloadSearch.Searched.Limited>(search().search(row())).wait)
    }

    /**
     * L443, as the walk's memory keeps it (DownloadLimits): an indexer that answered a turn's search with its limit, with no
     * Retry-After (Newznab's 429 and 500), is left out of the walk's later searches for 30 minutes from its answer, so the
     * season's next row asks it nothing, and with nothing found elsewhere (an indexer failing beside it included) waits for
     * it, never backing off; then it is asked again, and its limit met again is a wait from that answer. One that said when
     * its limit ends is kept out until then. Another account at the same indexer is another limit, and Resume forgets them.
     */
    @Test
    fun `the walk keeps an indexer limited without Retry-After out of its later searches for 30 minutes, then asks it again`() = runBlocking<Unit> {
        val memory = DownloadLimits()
        reports = listOf(IndexerReport(A, null, error = NewznabError.ApiLimitReached("Request limit reached")), IndexerReport(B, "name"))
        val first = assertIs<DownloadSearch.Searched.Limited>(search(limits = memory).search(row()))
        assertEquals(LimitWait(A.id, LimitWait.Kind.Search, NOW, null), first.wait)
        assertEquals(listOf(A.id, B.id), asked.single().second)

        // The walk's second row a minute later: no query to A, and B failing beside it is still the limit's wait.
        reset()
        now = NOW + MINUTE
        reports = listOf(IndexerReport(B, null, error = NewznabError.Server("HTTP 503")))
        val second = assertIs<DownloadSearch.Searched.Limited>(search(limits = memory).search(row()))
        assertEquals(listOf(B.id), asked.single().second)
        assertEquals(LimitWait(A.id, LimitWait.Kind.Search, NOW, null), second.wait, "the wait A's answer began")

        // Found at B meanwhile: found, A still not asked.
        reset()
        now = NOW + 29 * MINUTE
        playable = listOf(found("Harbor.Lights.S02E04.1080p.WEB.h264-GRP", GB))
        reports = listOf(IndexerReport(B, "name", found = 1, accepted = 1))
        assertIs<DownloadSearch.Searched.Found>(search(limits = memory).search(row()))
        assertEquals(listOf(B.id), asked.single().second)

        // 30 minutes after its answer: asked again. Its limit met again is a wait from this answer, its start afresh.
        reset()
        now = NOW + 30 * MINUTE
        reports = listOf(IndexerReport(A, null, error = NewznabError.ApiLimitReached("Request limit reached")), IndexerReport(B, "name"))
        val again = assertIs<DownloadSearch.Searched.Limited>(search(limits = memory).search(row()))
        assertEquals(listOf(A.id, B.id), asked.single().second)
        assertEquals(LimitWait(A.id, LimitWait.Kind.Search, NOW + 30 * MINUTE, null), again.wait)

        // One that said when its limit ends is kept out until then, past the 30 minutes.
        val saidWhen = DownloadLimits()
        saidWhen.answered(A, Duration.ofHours(2), NOW)
        assertEquals(mapOf(A.id to LimitWait(A.id, LimitWait.Kind.Search, NOW, NOW + 2 * HOUR)), saidWhen.keptOut(listOf(A, B), NOW + 31 * MINUTE))
        assertEquals(emptyMap(), saidWhen.keptOut(listOf(A, B), NOW + 2 * HOUR))

        // Another account at the same indexer, its key changed, is another limit; Resume forgets them all.
        saidWhen.answered(A, null, NOW)
        assertEquals(emptyMap(), saidWhen.keptOut(listOf(A.copy(apiKey = "key-a2")), NOW))
        assertEquals(setOf(A.id), saidWhen.keptOut(listOf(A), NOW).keys)
        saidWhen.forget()
        assertEquals(emptyMap(), saidWhen.keptOut(listOf(A), NOW))
    }

    @Test
    fun `a release tried before is left out of what the search finds`() = runBlocking<Unit> {
        val pack = found("Harbor.Lights.S02.1080p.WEB.h264-PACK", 8 * GB)
        val single = found("Harbor.Lights.S02E04.1080p.WEB.h264-GRP", GB)
        playable = listOf(pack, single)
        val before = row { it.copy(tried = listOf(pack.key)) }
        assertIs<DownloadSearch.Searched.Found>(search().search(before))
        assertEquals(listOf(single.item.title), stored().candidates.map { it.title })

        // Only what was tried found: nothing else.
        reset()
        playable = listOf(pack)
        reports = listOf(IndexerReport(A, "TVDB id", found = 1, accepted = 1))
        assertEquals(DownloadSearch.Searched.NoneFound, search().search(row { it.copy(tried = listOf(pack.key)) }))
    }

    @Test
    fun `only a season row whose turn hasn't searched yet searches, and never one with a file chosen by hand`() = runBlocking<Unit> {
        val search = search()
        assertTrue(search.due(row()))
        assertFalse(search.due(row { it.copy(search = DownloadColumns.text(FACTS.copy(searched = true))) }))
        assertFalse(search.due(row { it.copy(search = null) }), "an episode queued from its own ⋮")
        assertFalse(search.due(row { it.copy(search = "{not facts") }))
        assertFalse(search.due(row { it.copy(pick = DownloadColumns.text(DownloadPick(2, "Harbor.Lights.S02E04.mkv", GB, "nzb-1"))) }))
    }

    @Test
    fun `its log lines name the row by its id, never the show, the release, the query or the indexer`() = runBlocking<Unit> {
        val named = IndexerConfig("idx-plover", "Plover's indexer", "https://plover.indexer.invalid/api", "key")
        playable = listOf(found("Harbor.Lights.S02E04.1080p.WEB.h264-PLOVER", GB))
        reports = listOf(IndexerReport(named, "TVDB id", found = 1, accepted = 1))
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        assertIs<DownloadSearch.Searched.Found>(search(listOf(named)).search(row()))
        reset()
        reports = listOf(IndexerReport(named, null, error = NewznabError.ApiLimitReached("Request limit reached for plover.indexer.invalid")))
        assertIs<DownloadSearch.Searched.Limited>(search(listOf(named)).search(row()))
        reset()
        reports = listOf(IndexerReport(named, null, error = NewznabError.Server("HTTP 503 from plover.indexer.invalid")))
        assertEquals(DownloadSearch.Searched.Out, search(listOf(named)).search(row()))
        reset()
        assertEquals(DownloadSearch.Searched.NoneFound, search(listOf(named)).search(row()))
        val lines = LogBuffer.snapshot().filter { it.seq > from && "DownloadSearch" in it.tag }.map { it.message }
        assertTrue(lines.size >= 4 && lines.all { ID in it }, lines.toString())
        val secret = listOf("Harbor Lights", "Harbor.Lights", "PLOVER", named.name, "plover.indexer", named.id, "900001", "Low Water")
        assertEquals(emptyList(), lines.filter { line -> secret.any { it in line } })
    }

    private suspend fun reset() {
        now = NOW
        dao.rows.value = emptyMap()
        playable = emptyList()
        reports = emptyList()
        asked.clear()
        meanwhile.clear()
        spaceFor.clear()
        limits.out = emptyMap()
        limits.told.clear()
        space = DownloadSpace(available = 100 * GB, used = 0, queued = 0)
    }

    /** The downloads' table in memory: only what the search reads and writes. */
    private class FakeDao : DownloadDao {
        val rows = MutableStateFlow<Map<String, Download>>(emptyMap())

        private fun sorted(filter: (Download) -> Boolean): Flow<List<Download>> = rows.map { it.values.filter(filter).sortedBy(Download::position) }

        override fun queue() = sorted { it.state != DownloadState.Done && it.state != DownloadState.Deleting }
        override fun all() = sorted { true }
        override suspend fun get(id: String) = rows.value[id]
        override fun done() = sorted { it.state == DownloadState.Done }
        override suspend fun doneFor(watchKey: String) = rows.value.values.firstOrNull { it.state == DownloadState.Done && it.watchKey == watchKey }
        override fun forShow(showTmdbId: Int) = sorted { it.showTmdbId == showTmdbId }
        override fun forMovie(movieTmdbId: Int) = rows.map { it.values.firstOrNull { row -> row.movieTmdbId == movieTmdbId } }
        override suspend fun insert(download: Download) = rows.update { it + (download.id to download) }
        override suspend fun insertAll(downloads: List<Download>, putBack: List<Download>) = rows.update { it + downloads.associateBy { row -> row.id } }
        override suspend fun update(download: Download) = rows.update { if (download.id in it) it + (download.id to download) else it }
        override suspend fun progress(id: String, safeOffset: Long, gaps: List<DownloadGap>, updatedAt: Long) =
            change(id) { it.copy(safeOffset = safeOffset, gaps = gaps, updatedAt = updatedAt) }

        override suspend fun setState(id: String, state: DownloadState, reason: String?, updatedAt: Long) =
            change(id) { it.copy(state = state, reason = reason, updatedAt = updatedAt) }

        override suspend fun move(id: String, position: Long) = change(id) { it.copy(position = position) }
        override suspend fun delete(id: String) = rows.update { it - id }

        private fun change(id: String, block: (Download) -> Download) = rows.update { all -> all[id]?.let { all + (id to block(it)) } ?: all }
    }

    private companion object {
        const val ID = "5e7a9c1d-search"
        const val SHOW = 990_617
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L
        const val MINUTE = 60_000L
        const val GB = 1_000_000_000L
        val A = IndexerConfig("idx-a", "Indexer A", "https://a.indexer.invalid/api", "key-a")
        val B = IndexerConfig("idx-b", "Indexer B", "https://b.indexer.invalid/api", "key-b")
        val DOWNLOADING: RankingContext = DownloadRanking.context(StaticDeviceCapabilities(displayHeight = 1080), PlaybackPrefs(), DownloadPrefs(), "en")

        /** What the season's sheet kept for S02E04: the show's names and ids, the episode's date and length, the season's count. */
        val FACTS = SeasonSearch(
            "Harbor Lights", 2021, listOf("Harbour Lights"), tvdb = 900001, tmdb = SHOW, imdb = "tt9000003", originalLanguage = "en",
            airDate = "2025-03-25", runtimeMinutes = 52, seasonEpisodes = 8,
        )

        fun candidate(title: String, size: Long) = DownloadCandidate("idx-a", title, title, null, size)

        /**
         * [title] as the finder ranks it, of [size] bytes, from Indexer A; a [torrent] has its [hash], is known by its content
         * id and keeps no link, as a torrent indexer's answer gives it.
         */
        fun found(title: String, size: Long, torrent: Boolean = false, hash: String = "0123456789abcdef0123456789abcdef01234567"): FoundRelease {
            val item = NewznabItem(
                indexerId = A.id, guid = if (torrent) "btih:$hash" else title, title = title, sizeBytes = size, publishedAt = null, postedAt = null,
                categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null,
                season = null, episode = null, coverUrl = null, mediaInfo = null,
                downloadTemplate = "https://a.indexer.invalid/api?t=get&id=$title&apikey={apikey}".takeIf { !torrent }, attributes = emptyMap(),
                infoHash = hash.takeIf { torrent },
            )
            val ranked = RankedRelease(
                ReleaseFacts(item.key, title, size, source = if (torrent) ReleaseFacts.TORRENT else ReleaseFacts.USENET), ReleaseParser.parse(title),
                playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null,
            )
            return FoundRelease(item, ranked, SearchStage.Episode)
        }
    }
}
