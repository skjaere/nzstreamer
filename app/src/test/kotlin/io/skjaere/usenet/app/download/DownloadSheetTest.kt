package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.NetworkNow
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.search.IndexerStatus
import io.skjaere.usenet.app.search.MovieFind
import io.skjaere.usenet.app.search.forEpisode
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.PlaybackPrefs
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.RankingTarget
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.ReleaseRanker
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.EpisodeRequest
import io.skjaere.usenet.search.FindResult
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.FoundRelease
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.IndexerReport
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import io.skjaere.usenet.search.SearchStage
import io.skjaere.usenet.search.StageReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Download sheet's model for one episode or film (L338, L358, L370), each state from fakes of its search, the device's
 * space, network and queue, Android's notification ask and the runner: the pick is the download ranking's first; the free
 * space and what a pick needs; when it starts by the network and the queue; one that won't fit; no release; the
 * notification line once, on Android 13 and later; and what Download stores: the releases that fit, in download order, the
 * one chosen first, torrents left out, then the ask and the host's schedule. Finding only searches. Invented releases.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSheetTest {
    private val saved = Locale.getDefault()

    // formatSize follows the default locale; pin one so the decimal separator is predictable.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    /** What the model reads and asks, faked; what it asked recorded. */
    private class Fakes {
        var finder: (EpisodeRequest, RankingContext) -> Flow<FinderEvent> = { request, _ -> flowOf(finished(request, emptyList())) }
        var films: (MovieTarget, RankingContext) -> Flow<MovieFind> = { _, _ -> flowOf(MovieFind(null, emptyList())) }
        var space: DownloadSpace? = DownloadSpace(available = 15 * GB, used = 0, queued = 0)
        var rows: List<Download> = emptyList()
        var prefs = DownloadPrefs()
        var network = WIFI
        var ask: NotificationAsk? = null

        /** Whether the runner queues it: not on a TV, where it queues nothing (L369). */
        var queues = true
        val contexts = mutableListOf<RankingContext>()
        val calls = mutableListOf<String>()
        val enqueued = mutableListOf<Pair<DownloadTarget, List<DownloadCandidate>>>()

        /** What each download queued was counted as needing until it is resolved. */
        val needs = mutableListOf<Long>()

        fun deps(outlive: CoroutineScope) = DownloadSheetDeps(
            findEpisode = { request, context ->
                contexts += context
                finder(request, context)
            },
            findMovie = { movie, context ->
                contexts += context
                films(movie, context)
            },
            context = { DOWNLOADING },
            space = { space },
            rows = { rows },
            prefs = { prefs },
            network = { network },
            askDue = { ask?.due() == true },
            enqueue = { target, candidates, need ->
                calls += "enqueue"
                enqueued += target to candidates
                needs += need
                Download(
                    id = "queued", watchKey = target.watchKey, kind = target.kind, title = target.title, position = 0, candidates = candidates,
                    indexerSize = candidates.first().size, createdAt = 0, updatedAt = 0,
                ).takeIf { queues }
            },
            beforeDownload = {
                calls += "ask"
                ask?.beforeDownload()
            },
            schedule = { calls += "schedule" },
            outlive = outlive,
            today = { TODAY },
        )
    }

    /** The sheet opened for [request]: its search started and run as far as it goes. */
    private fun TestScope.open(request: DownloadRequest, fakes: Fakes): DownloadSheetModel =
        DownloadSheetModel(request, fakes.deps(this), backgroundScope).also {
            it.start()
            runCurrent()
        }

    @Test
    fun `Finding only searches, ranked for downloading, and the pick is the download ranking's first`() = runTest {
        // Of one standing: a play's ranking puts the larger, more grabbed one first, a download's the smaller (L355), so the
        // pick shows which ranking the sheet asked for.
        val releases = listOf(nzb(ATMOS, 3_400_000_000, grabs = 900), nzb(KESTREL, 1_200_000_000, grabs = 5))
        val request = EPISODE.play.request
        assertNotEquals(byRanker(releases, PLAYING.forEpisode(request)).first().item.title, byRanker(releases, DOWNLOADING.forEpisode(request)).first().item.title)
        val answer = CompletableDeferred<Unit>()
        val fakes = Fakes().apply {
            finder = { request, context ->
                flow {
                    answer.await()
                    emit(finished(request, byRanker(releases, context.forEpisode(request)).map { FoundRelease(it.item, it.ranked, SearchStage.Episode) }))
                }
            }
        }
        val sheet = open(EPISODE, fakes)
        assertEquals(DownloadSheetState.Finding, sheet.state.value)
        assertEquals(RankingTarget.Download, fakes.contexts.single().target)
        assertEquals(emptyList(), fakes.calls, "nothing grabbed or queued while it finds")

        answer.complete(Unit)
        runCurrent()
        val pick = assertIs<DownloadSheetState.Pick>(sheet.state.value)
        assertEquals(KESTREL, pick.best.item.title)
        assertEquals(1_200_000_000, pick.need)
        assertEquals("1080p · WEB-DL · HEVC · about 1.2 GB", DownloadSheetModel.quality(pick.best, pick.need))
        assertEquals(emptyList(), fakes.calls, "nothing queued before Download")
    }

    @Test
    fun `a film is searched as itself and ranked for downloading, and its row keeps its names`() = runTest {
        val fakes = Fakes().apply {
            films = { _, context -> flowOf(MovieFind(film(byRanker(listOf(nzb(COPPER_REMUX, 21_000_000_000), nzb(COPPER, 4_300_000_000)), context)), STATUSES, context)) }
        }
        val sheet = open(FILM, fakes)
        assertEquals(RankingTarget.Download, fakes.contexts.single().target)
        val pick = assertIs<DownloadSheetState.Pick>(sheet.state.value)
        assertEquals(COPPER, pick.best.item.title)
        assertEquals("1080p · WEB-DL · about 4.3 GB", DownloadSheetModel.quality(pick.best, pick.need))
        assertEquals("Copper Crown · 2022", FILM.subtitle)
        sheet.download()
        advanceUntilIdle()
        val (target, _) = fakes.enqueued.single()
        assertEquals(DownloadTarget.movie(FILM_ID, "Copper Crown", year = 2022, runtimeMinutes = 112, subtitle = "2022", posterPath = "/copper.jpg"), target)
        assertEquals(WatchKey.movie(FILM_ID), target.watchKey)
    }

    @Test
    fun `the space free is what a new download may take, and a season pack needs this episode's share`() = runTest {
        val fakes = Fakes().apply {
            // 1.0 GB kept free and 2.2 GB a queued download still needs: 14.0 GB left of 17.2.
            space = DownloadSpace(available = 17_200_000_000, used = 3 * GB, queued = 2_200_000_000, queuedDownloads = 1)
            finder = inOrder(nzb(PACK, 11_200_000_000))
        }
        val pick = assertIs<DownloadSheetState.Pick>(open(EPISODE, fakes).state.value)
        assertEquals(14 * GB, pick.free)
        assertEquals("14.0 GB free on this device", DownloadSheetModel.free(pick.free))
        assertEquals(1_400_000_000, pick.need, "an eighth of the season's pack")
        assertEquals("1080p · WEB-DL · about 1.4 GB", DownloadSheetModel.quality(pick.best, pick.need))
        assertEquals("Season pack · this episode about 1.4 GB", pick.mode.packLine(pick.best))
        assertEquals(14 * GB, pick.mode.free, "the list checks the same space")
    }

    @Test
    fun `a season pack is queued at this episode's share of it, its release keeping the indexer's size`() = runTest {
        val fakes = Fakes().apply { finder = inOrder(nzb(PACK, 11_200_000_000)) }
        val sheet = open(EPISODE, fakes)
        sheet.download()
        advanceUntilIdle()
        // What the queue counts until Resolve has the file's own size: the next sheet's free space is reckoned after it.
        assertEquals(listOf(1_400_000_000L), fakes.needs)
        assertEquals(listOf(11_200_000_000L), fakes.enqueued.single().second.map { it.size })
    }

    @Test
    fun `when it starts goes by the network and the queue`() = runTest {
        suspend fun TestScope.start(fakes: Fakes): DownloadStart {
            fakes.finder = inOrder(nzb(KESTREL, 1_200_000_000))
            return assertIs<DownloadSheetState.Pick>(open(EPISODE, fakes).state.value).start
        }
        assertEquals(DownloadStart.Now, start(Fakes()))
        // Mobile data is the default network metered, while downloads wait for Wi-Fi or Ethernet (W110).
        assertEquals(DownloadStart.OnWifi, start(Fakes().apply { network = MOBILE }))
        assertEquals(DownloadStart.Now, start(Fakes().apply { network = MOBILE; prefs = DownloadPrefs(unmeteredOnly = false) }))
        assertEquals(DownloadStart.Now, start(Fakes().apply { network = ETHERNET }))
        // One at a time (L337): those still to go on wait ahead of it, the finished and failed don't.
        assertEquals(DownloadStart.AfterQueue(1), start(Fakes().apply { rows = listOf(row("q", DownloadState.Queued)) }))
        assertEquals(DownloadStart.Now, start(Fakes().apply { rows = listOf(row("d", DownloadState.Done), row("f", DownloadState.Failed), row("x", DownloadState.Deleting)) }))
        assertEquals(DownloadStart.AfterQueue(2), start(Fakes().apply { rows = listOf(row("r", DownloadState.Running), row("p", DownloadState.Paused)) }))
        assertEquals(DownloadStart.OnWifi, start(Fakes().apply { network = MOBILE; rows = listOf(row("q", DownloadState.Queued)) }), "Wi-Fi first: it can't start before")

        assertEquals("Starts now" to null, DownloadSheetModel.start(DownloadStart.Now))
        assertEquals(
            "Starts on Wi-Fi" to "This phone is on mobile data. Downloads wait for Wi-Fi or Ethernet: Settings › Downloads.",
            DownloadSheetModel.start(DownloadStart.OnWifi),
        )
        assertEquals("Starts after the download in the queue" to "One download runs at a time.", DownloadSheetModel.start(DownloadStart.AfterQueue(1)))
        assertEquals("Starts after the downloads in the queue" to "One download runs at a time.", DownloadSheetModel.start(DownloadStart.AfterQueue(3)))
    }

    @Test
    fun `on mobile data the list says it starts on Wi-Fi too`() = runTest {
        val fakes = Fakes().apply {
            network = MOBILE
            finder = inOrder(nzb(KESTREL, 1_200_000_000))
        }
        val pick = assertIs<DownloadSheetState.Pick>(open(EPISODE, fakes).state.value)
        assertTrue(pick.mode.startsOnWifi)
        assertEquals(8, pick.mode.seasonEpisodes)
    }

    @Test
    fun `a pick that won't fit offers no Download, and says what it needs and why less is free`() = runTest {
        val fakes = Fakes().apply {
            // 3.0 GB free: after 1.0 GB kept free and the 1.2 GB the queued download still needs.
            space = DownloadSpace(available = 5_200_000_000, used = 0, queued = 1_200_000_000, queuedDownloads = 1)
            films = { _, context -> flowOf(MovieFind(film(listOf(ranked(nzb(COPPER, 4_300_000_000), 3.0), ranked(nzb(COPPER_BLURAY, 8_900_000_000), 2.0), ranked(nzb(COPPER_720, 2_200_000_000), 1.0))), STATUSES, context)) }
        }
        val sheet = open(FILM, fakes)
        val wontFit = assertIs<DownloadSheetState.WontFit>(sheet.state.value)
        assertEquals(COPPER, wontFit.best.item.title)
        assertEquals("Needs about 4.3 GB · 3.0 GB free on this device", DownloadSheetModel.needs(wontFit.need, wontFit.free))
        assertEquals(
            "That is after the 1.2 GB the queued download still needs, and the 1.0 GB kept free for the phone.",
            DownloadSheetModel.after(wontFit.queued, wontFit.queuedDownloads),
        )
        sheet.download()
        advanceUntilIdle()
        assertEquals(emptyList(), fakes.calls, "no Download for one that won't fit")

        // Choose release: the list greys it, and what fits downloads, alone of the three.
        assertFalse(wontFit.mode.fits(wontFit.best))
        wontFit.mode.onDownload(wontFit.group.playable.single { it.item.title == COPPER_720 })
        advanceUntilIdle()
        assertEquals(listOf(COPPER_720), fakes.enqueued.single().second.map { it.title })
        assertTrue(sheet.closed.value)

        assertEquals("That is after the 1.0 GB kept free for the phone.", DownloadSheetModel.after(0, 0))
        assertEquals("That is after the 3.4 GB the queued downloads still need, and the 1.0 GB kept free for the phone.", DownloadSheetModel.after(3_400_000_000, 2))
    }

    @Test
    fun `no release to download says what each search of the indexers did, as the play sheet says it`() = runTest {
        val indexer = IndexerConfig("idx-a", "Indexer A", "https://indexer.invalid/api", "")
        val stages = listOf(
            StageReport(SearchStage.Episode, listOf(IndexerReport(indexer, "TVDB id"))),
            StageReport(SearchStage.SeasonPack, listOf(IndexerReport(indexer, "TVDB id"))),
        )
        val fakes = Fakes().apply { finder = { request, _ -> flowOf(FinderEvent.Finished(FindResult(request, emptyList(), emptyList(), stages))) } }
        assertEquals(
            DownloadSheetState.NoRelease(
                listOf("Searched 1 indexer by TVDB id" to "Nothing yet", "Looked for season 2 packs" to "None containing E04"),
                "Indexers can also number some shows differently from TMDB.",
            ),
            open(EPISODE, fakes).state.value,
        )

        // A torrent alone found: nothing to download from Usenet (L370).
        val torrent = Fakes().apply { finder = inOrder(nzb(TORRENT, 1_000_000_000, hash = HASH)) }
        assertIs<DownloadSheetState.NoRelease>(open(EPISODE, torrent).state.value)

        // A film: each indexer and what it answered.
        val film = Fakes().apply { films = { _, context -> flowOf(MovieFind(null, listOf(IndexerStatus("idx-a", "Indexer A", IndexerStatus.State.Answered(0))), context)) } }
        assertEquals(DownloadSheetState.NoRelease(listOf("Indexer A" to "No results")), open(FILM, film).state.value)
    }

    @Test
    fun `a search that fails or says nothing ends the finding with no release`() = runTest {
        val failing = Fakes().apply { finder = { _, _ -> flow { throw IllegalStateException("the finder broke") } } }
        assertEquals(DownloadSheetState.NoRelease(emptyList()), open(EPISODE, failing).state.value)
        val silent = Fakes().apply { finder = { _, _ -> flow {} } }
        assertEquals(DownloadSheetState.NoRelease(emptyList()), open(EPISODE, silent).state.value)
    }

    @Test
    fun `the notification line shows on Android 13 and later, until the first Download has asked`() = runTest {
        var asked = false
        var launches = 0
        fun asking(sdk: Int): NotificationAsk {
            lateinit var ask: NotificationAsk
            ask = NotificationAsk(
                sdk = sdk, shown = { true }, granted = { false }, asked = { asked }, markAsked = { asked = true },
                // Android's dialog, answered at once.
                launch = {
                    launches++
                    ask.answered()
                },
            )
            return ask
        }
        fun fakes(sdk: Int) = Fakes().apply {
            ask = asking(sdk)
            finder = inOrder(nzb(KESTREL, 1_200_000_000))
        }
        assertFalse(assertIs<DownloadSheetState.Pick>(open(EPISODE, fakes(32)).state.value).asks, "Android 12 asks nothing")

        val first = fakes(33)
        val sheet = open(EPISODE, first)
        assertTrue(assertIs<DownloadSheetState.Pick>(sheet.state.value).asks)
        sheet.download()
        advanceUntilIdle()
        assertEquals(1, launches)
        assertEquals(listOf("enqueue", "ask", "schedule"), first.calls, "queued, then asked, then the host scheduled")
        assertFalse(assertIs<DownloadSheetState.Pick>(open(EPISODE, fakes(33)).state.value).asks, "once")
    }

    @Test
    fun `Download queues the pick and the releases that fit after it, torrents left out, then asks, schedules and closes`() = runTest {
        val fakes = Fakes().apply {
            // 4.0 GB free: the 9.8 GB remux doesn't fit; the pack's share of the episode does.
            space = DownloadSpace(available = 5 * GB, used = 0, queued = 0)
            finder = inOrder(
                nzb(KESTREL, 1_200_000_000), nzb(TORRENT, 1_800_000_000, hash = HASH), nzb(PACK, 11_200_000_000, indexer = "idx-b", passworded = true),
                nzb(FERRY, 9_800_000_000), nzb(ATMOS, 3_400_000_000),
            )
        }
        val sheet = open(EPISODE, fakes)
        val pick = assertIs<DownloadSheetState.Pick>(sheet.state.value)
        assertEquals(listOf(KESTREL, PACK, FERRY, ATMOS), pick.group.playable.map { it.item.title }, "the list lists Usenet's alone")
        assertFalse(sheet.closed.value)

        sheet.download()
        sheet.download()
        advanceUntilIdle()
        val (target, candidates) = fakes.enqueued.single()
        assertEquals(EPISODE.target, target)
        assertEquals(listOf(KESTREL, PACK, ATMOS), candidates.map { it.title })
        assertEquals(
            DownloadCandidate("idx-b", PACK, PACK, "https://indexer.invalid/api?t=get&id=$PACK&apikey={apikey}", 11_200_000_000, passworded = true),
            candidates[1],
            "the link as the app keeps it, the indexer's size and its passworded flag",
        )
        assertEquals(listOf("enqueue", "ask", "schedule"), fakes.calls)
        assertTrue(sheet.closed.value)
    }

    @Test
    fun `nothing is asked or scheduled when the runner queued nothing, and the sheet still closes`() = runTest {
        val fakes = Fakes().apply {
            queues = false
            finder = inOrder(nzb(KESTREL, 1_200_000_000))
        }
        val sheet = open(EPISODE, fakes)
        sheet.download()
        advanceUntilIdle()
        assertEquals(listOf("enqueue"), fakes.calls)
        assertTrue(sheet.closed.value)
    }

    @Test
    fun `a release chosen in the list goes first, the others that fit after it in their order`() = runTest {
        val fakes = Fakes().apply { finder = inOrder(nzb(KESTREL, 1_200_000_000), nzb(PACK, 11_200_000_000), nzb(ATMOS, 3_400_000_000)) }
        val pick = assertIs<DownloadSheetState.Pick>(open(EPISODE, fakes).state.value)
        pick.mode.onDownload(pick.group.playable.single { it.item.title == ATMOS })
        advanceUntilIdle()
        assertEquals(listOf(ATMOS, KESTREL, PACK), fakes.enqueued.single().second.map { it.title })
        assertEquals(listOf("enqueue", "ask", "schedule"), fakes.calls)
    }

    @Test
    fun `at most 10 releases are kept to try`() = runTest {
        val fakes = Fakes().apply { finder = inOrder(*(1..12).map { nzb("Harbor.Lights.S02E04.720p.WEB-DL.H.264-GRP$it", 900_000_000) }.toTypedArray()) }
        val sheet = open(EPISODE, fakes)
        sheet.download()
        advanceUntilIdle()
        assertEquals((1..10).map { "Harbor.Lights.S02E04.720p.WEB-DL.H.264-GRP$it" }, fakes.enqueued.single().second.map { it.title })
    }

    @Test
    fun `an episode's row keeps the show, the episode and its name, and the season's count`() {
        val target = EPISODE.target
        assertEquals(DownloadKind.Episode, target.kind)
        assertEquals("Harbor Lights", target.title)
        assertEquals("S02E04 · Low Water", target.subtitle)
        assertEquals(SHOW_ID to (2 to 4), target.showTmdbId to (target.season to target.episode))
        assertEquals(8, target.seasonEpisodes)
        assertEquals("Low Water", target.episodeName)
        // Its length, which a show's downloads say beside its size.
        assertEquals(52, target.runtimeMinutes)
        assertEquals("/poster.jpg" to "/still.jpg", target.posterPath to target.stillPath)
        assertEquals("Harbor Lights · S02E04 · Low Water", EPISODE.subtitle)
        assertEquals("Harbor Lights", EPISODE.title)
        assertNull(FILM.seasonEpisodes)
    }

    private companion object {
        const val GB = 1_000_000_000L
        const val SHOW_ID = 990_417
        const val FILM_ID = 990_418
        val TODAY: LocalDate = LocalDate.of(2026, 11, 15)
        val WIFI = NetworkNow(usable = true, unmetered = true, wifi = true)
        val ETHERNET = NetworkNow(usable = true, unmetered = true, wifi = false)
        val MOBILE = NetworkNow(usable = true, unmetered = false, wifi = false)

        val PHONE = StaticDeviceCapabilities(displayHeight = 1080)
        val DOWNLOADING: RankingContext = DownloadRanking.context(PHONE, PlaybackPrefs(), DownloadPrefs(), "en")
        val PLAYING = RankingContext(PHONE, PlaybackPrefs().rankingPrefs("en"))

        val EPISODE = DownloadRequest.Episode(
            EpisodePlay(
                showTmdbId = SHOW_ID, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tvdb = 7_001, tmdb = SHOW_ID),
                season = 2, episode = 4, episodeName = "Low Water", airDate = LocalDate.of(2025, 3, 25), runtimeMinutes = 52, stillPath = "/still.jpg",
                seasonEpisodes = 8,
            ),
            posterPath = "/poster.jpg",
        )
        val FILM = DownloadRequest.Movie(MovieTarget(FILM_ID, "Copper Crown", 2022, imdb = null, runtimeMinutes = 112), posterPath = "/copper.jpg")
        val STATUSES = listOf(IndexerStatus("idx-a", "Indexer A", IndexerStatus.State.Answered(3)))

        const val KESTREL = "Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.H.265-KESTREL"
        const val PACK = "Harbor.Lights.S02.1080p.WEB-DL.DDP5.1.H.264-DRIFTWOOD"
        const val ATMOS = "Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.Atmos.H.264-TIDEPOOL"
        const val FERRY = "Harbor.Lights.S02E04.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY"
        const val TORRENT = "Harbor.Lights.S02E04.1080p.WEB.H264-SALTMARSH"
        const val HASH = "0123456789abcdef0123456789abcdef01234567"
        const val COPPER = "Copper.Crown.2022.1080p.WEB-DL.DDP5.1.H.264-KESTREL"
        const val COPPER_BLURAY = "Copper.Crown.2022.1080p.BluRay.x264-DRIFTWOOD"
        const val COPPER_720 = "Copper.Crown.2022.720p.WEB-DL.DDP5.1.H.264-KESTREL"
        const val COPPER_REMUX = "Copper.Crown.2022.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY"

        fun nzb(title: String, size: Long, indexer: String = "idx-a", hash: String? = null, passworded: Boolean = false, grabs: Int? = null) = NewznabItem(
            indexerId = indexer, guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null, categories = emptyList(), grabs = grabs,
            passworded = passworded, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null, season = null, episode = null, coverUrl = null,
            mediaInfo = null, downloadTemplate = "https://indexer.invalid/api?t=get&id=$title&apikey={apikey}".takeIf { hash == null }, attributes = emptyMap(),
            infoHash = hash,
        )

        /** [item] as a ranking placed it, by [score]: playable, with nothing to say of it. */
        fun ranked(item: NewznabItem, score: Double) = RankedItem(
            item,
            RankedRelease(
                ReleaseFacts(item.key, item.title, item.sizeBytes, source = if (item.infoHash != null) ReleaseFacts.TORRENT else ReleaseFacts.USENET),
                ReleaseParser.parse(item.title), playable = true, score = score, reasons = emptyList(), estimatedBitrate = null,
            ),
        )

        /** [items] as the ranker ranks them by [context], playable first. */
        fun byRanker(items: List<NewznabItem>, context: RankingContext): List<RankedItem> {
            val held = items.associateBy { it.key }
            return ReleaseRanker().rank(items.map { ReleaseFacts(it.key, it.title, it.sizeBytes, grabs = it.grabs) }, context).map { RankedItem(held.getValue(it.facts.id), it) }
        }

        fun finished(request: EpisodeRequest, found: List<FoundRelease>) = FinderEvent.Finished(FindResult(request, found, emptyList(), emptyList()))

        /** A finder that found [items], in this order. */
        fun inOrder(vararg items: NewznabItem): (EpisodeRequest, RankingContext) -> Flow<FinderEvent> = { request, _ ->
            flowOf(finished(request, items.mapIndexed { i, item -> ranked(item, 100.0 - i).let { FoundRelease(it.item, it.ranked, SearchStage.Episode) } }))
        }

        /** A film's group of [ranked] releases. */
        fun film(ranked: List<RankedItem>) = ResultGroup("copper crown|2022", GroupKind.Movie, null, emptyList(), null, 2022, ranked, emptyList())

        fun row(id: String, state: DownloadState) = Download(
            id = id, watchKey = WatchKey.episode(SHOW_ID, 1, 1), kind = DownloadKind.Episode, title = "Harbor Lights", state = state, position = 0,
            indexerSize = 1_000_000_000, createdAt = 0, updatedAt = 0,
        )
    }
}
