package io.skjaere.usenet.app.download

import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.app.NetworkNow
import io.skjaere.usenet.app.download.SheetFixtures.DOWNLOADING
import io.skjaere.usenet.app.download.SheetFixtures.ETHERNET
import io.skjaere.usenet.app.download.SheetFixtures.GB
import io.skjaere.usenet.app.download.SheetFixtures.MOBILE
import io.skjaere.usenet.app.download.SheetFixtures.PLAYING
import io.skjaere.usenet.app.download.SheetFixtures.byRanker
import io.skjaere.usenet.app.download.SheetFixtures.finished
import io.skjaere.usenet.app.download.SheetFixtures.inOrder
import io.skjaere.usenet.app.download.SheetFixtures.nzb
import io.skjaere.usenet.app.download.SheetFixtures.ranked
import io.skjaere.usenet.app.download.SheetFixtures.ranking
import io.skjaere.usenet.app.download.SheetFixtures.torrent
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.IMPORTED
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.search.IndexerStatus
import io.skjaere.usenet.app.search.MovieFind
import io.skjaere.usenet.app.search.forEpisode
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankingTarget
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Download sheet's model for one episode or film (L338, L358, L422, L459), each state from fakes of its search, the
 * device's space, network and queue, Android's notification ask and the runner: the pick is the download ranking's first;
 * the free space and what a pick needs; when it starts by the network and the queue; one that won't fit; no release; the
 * notification line once, on Android 13 and later; and what Download stores: the releases that fit, in download order, the
 * one chosen first, a torrent a debrid service has cached among them with its source, then the ask and the host's
 * schedule. A torrent not cached stays hidden, and the torrent indexers are asked only while debrid shows and an account
 * is on. Finding only searches. Invented releases.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSheetTest {
    private val saved = Locale.getDefault()

    // formatSize follows the default locale; pin one so the decimal separator is predictable.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    /** The sheet opened for [request]: its search started and run as far as it goes. */
    private fun TestScope.open(request: DownloadRequest, fakes: SheetFakes): DownloadSheetModel =
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
        val fakes = SheetFakes().apply {
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
        val fakes = SheetFakes().apply {
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
        val fakes = SheetFakes().apply {
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
        val fakes = SheetFakes().apply { finder = inOrder(nzb(PACK, 11_200_000_000)) }
        val sheet = open(EPISODE, fakes)
        sheet.download()
        advanceUntilIdle()
        // What the queue counts until Resolve has the file's own size: the next sheet's free space is reckoned after it.
        assertEquals(listOf(1_400_000_000L), fakes.needs)
        assertEquals(listOf(11_200_000_000L), fakes.enqueued.single().second.map { it.size })
    }

    @Test
    fun `when it starts goes by the network and the queue`() = runTest {
        suspend fun TestScope.start(fakes: SheetFakes): DownloadStart {
            fakes.finder = inOrder(nzb(KESTREL, 1_200_000_000))
            return assertIs<DownloadSheetState.Pick>(open(EPISODE, fakes).state.value).start
        }
        assertEquals(DownloadStart.Now, start(SheetFakes()))
        // Mobile data is the default network metered, while downloads wait for Wi-Fi or Ethernet (W110).
        assertEquals(DownloadStart.OnWifi, start(SheetFakes().apply { network = MOBILE }))
        assertEquals(DownloadStart.Now, start(SheetFakes().apply { network = MOBILE; prefs = DownloadPrefs(unmeteredOnly = false) }))
        assertEquals(DownloadStart.Now, start(SheetFakes().apply { network = ETHERNET }))
        // One at a time (L337): those still to go on wait ahead of it, the finished and failed don't.
        assertEquals(DownloadStart.AfterQueue(1), start(SheetFakes().apply { rows = listOf(row("q", DownloadState.Queued)) }))
        assertEquals(DownloadStart.Now, start(SheetFakes().apply { rows = listOf(row("d", DownloadState.Done), row("f", DownloadState.Failed), row("x", DownloadState.Deleting)) }))
        assertEquals(DownloadStart.AfterQueue(2), start(SheetFakes().apply { rows = listOf(row("r", DownloadState.Running), row("w", DownloadState.Waiting)) }))
        assertEquals(DownloadStart.OnWifi, start(SheetFakes().apply { network = MOBILE; rows = listOf(row("q", DownloadState.Queued)) }), "Wi-Fi first: it can't start before")
        // No connection now: not mobile data, whatever the switch says.
        assertEquals(DownloadStart.Online(wifiOnly = true), start(SheetFakes().apply { network = NetworkNow.NONE }))
        assertEquals(DownloadStart.Online(wifiOnly = false), start(SheetFakes().apply { network = NetworkNow.NONE; prefs = DownloadPrefs(unmeteredOnly = false) }))
        // Only while charging (L365), off the charger: it waits for it, as the host does.
        assertEquals(DownloadStart.Charging, start(SheetFakes().apply { prefs = DownloadPrefs(whileCharging = true) }))
        assertEquals(DownloadStart.Now, start(SheetFakes().apply { prefs = DownloadPrefs(whileCharging = true); charging = true }))
        assertEquals(DownloadStart.Now, start(SheetFakes().apply { charging = false }), "the charger asked for only while the switch is on")
        // A paused or stopped download holds the whole queue until Resume (L380): nothing starts after it by itself.
        assertEquals(DownloadStart.Held, start(SheetFakes().apply { rows = listOf(row("r", DownloadState.Running), row("p", DownloadState.Paused)) }))
        assertEquals(DownloadStart.Held, start(SheetFakes().apply { network = MOBILE; rows = listOf(row("s", DownloadState.Stopped)) }), "the hold first: only Resume ends it")

        assertEquals("Starts now" to null, DownloadSheetModel.start(DownloadStart.Now))
        assertEquals(
            "Starts on Wi-Fi" to "This phone is on mobile data. Downloads wait for Wi-Fi or Ethernet: Settings › Downloads.",
            DownloadSheetModel.start(DownloadStart.OnWifi),
        )
        assertEquals(
            "Starts on Wi-Fi" to "This phone has no connection now. Downloads wait for Wi-Fi or Ethernet: Settings › Downloads.",
            DownloadSheetModel.start(DownloadStart.Online(wifiOnly = true)),
        )
        assertEquals("Starts when this phone is online" to "This phone has no connection now.", DownloadSheetModel.start(DownloadStart.Online(wifiOnly = false)))
        assertEquals(
            "Starts when this phone is charging" to "Downloads wait until this phone is plugged in: Settings › Downloads.",
            DownloadSheetModel.start(DownloadStart.Charging),
        )
        assertEquals(
            "Starts when downloads resume" to "Downloads are on hold until you resume them on the Downloads page.",
            DownloadSheetModel.start(DownloadStart.Held),
        )
        assertEquals("Starts after the download in the queue" to "One download runs at a time.", DownloadSheetModel.start(DownloadStart.AfterQueue(1)))
        assertEquals("Starts after the downloads in the queue" to "One download runs at a time.", DownloadSheetModel.start(DownloadStart.AfterQueue(3)))
    }

    @Test
    fun `on mobile data the list says it starts on Wi-Fi too`() = runTest {
        val fakes = SheetFakes().apply {
            network = MOBILE
            finder = inOrder(nzb(KESTREL, 1_200_000_000))
        }
        val pick = assertIs<DownloadSheetState.Pick>(open(EPISODE, fakes).state.value)
        assertTrue(pick.mode.startsOnWifi)
        assertEquals(8, pick.mode.seasonEpisodes)
        // With no connection at all, the list says nothing of mobile data.
        val offline = SheetFakes().apply {
            network = NetworkNow.NONE
            finder = inOrder(nzb(KESTREL, 1_200_000_000))
        }
        assertFalse(assertIs<DownloadSheetState.Pick>(open(EPISODE, offline).state.value).mode.startsOnWifi)
    }

    @Test
    fun `a pick that won't fit offers no Download, and says what it needs and why less is free`() = runTest {
        val fakes = SheetFakes().apply {
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
    fun `back from Android's storage page the space is read again, and a pick that fits now offers Download`() = runTest {
        val fakes = SheetFakes().apply {
            space = DownloadSpace(available = 4 * GB, used = 0, queued = 0)
            films = { _, context -> flowOf(MovieFind(film(listOf(ranked(nzb(COPPER, 4_300_000_000), 2.0), ranked(nzb(COPPER_720, 2_200_000_000), 1.0))), STATUSES, context)) }
        }
        val sheet = open(FILM, fakes)
        assertIs<DownloadSheetState.WontFit>(sheet.state.value)
        // Storage settings: room made there, and the sheet in front again, with no new search.
        fakes.space = DownloadSpace(available = 15 * GB, used = 0, queued = 0)
        sheet.shown()
        runCurrent()
        val pick = assertIs<DownloadSheetState.Pick>(sheet.state.value)
        assertEquals(COPPER, pick.best.item.title)
        assertEquals(14 * GB, pick.free)
        assertTrue(pick.mode.fits(pick.best), "the list reads the room made too")
        assertEquals(1, fakes.contexts.size, "searched once")
    }

    @Test
    fun `no release to download says what each search of the indexers did, as the play sheet says it`() = runTest {
        val indexer = IndexerConfig("idx-a", "Indexer A", "https://indexer.invalid/api", "")
        val stages = listOf(
            StageReport(SearchStage.Episode, listOf(IndexerReport(indexer, "TVDB id"))),
            StageReport(SearchStage.SeasonPack, listOf(IndexerReport(indexer, "TVDB id"))),
        )
        val fakes = SheetFakes().apply { finder = { request, _ -> flowOf(FinderEvent.Finished(FindResult(request, emptyList(), emptyList(), stages))) } }
        assertEquals(
            DownloadSheetState.NoRelease(
                listOf("Searched 1 indexer by TVDB id" to "Nothing yet", "Looked for season 2 packs" to "None containing E04"),
                "Indexers can also number some shows differently from TMDB.",
            ),
            open(EPISODE, fakes).state.value,
        )

        // A torrent alone found, which no debrid service has cached: hidden, so nothing to download (L422).
        val torrent = SheetFakes().apply { finder = ranking(torrent(TORRENT, 1_000_000_000, Availability.NotCached, HASH)) }
        assertIs<DownloadSheetState.NoRelease>(open(EPISODE, torrent).state.value)

        // A film: each indexer and what it answered.
        val film = SheetFakes().apply { films = { _, context -> flowOf(MovieFind(null, listOf(IndexerStatus("idx-a", "Indexer A", IndexerStatus.State.Answered(0))), context)) } }
        assertEquals(DownloadSheetState.NoRelease(listOf("Indexer A" to "No results")), open(FILM, film).state.value)
    }

    @Test
    fun `a search that fails or says nothing ends the finding with no release`() = runTest {
        val failing = SheetFakes().apply { finder = { _, _ -> flow { throw IllegalStateException("the finder broke") } } }
        assertEquals(DownloadSheetState.NoRelease(emptyList()), open(EPISODE, failing).state.value)
        val silent = SheetFakes().apply { finder = { _, _ -> flow {} } }
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
        fun fakes(sdk: Int) = SheetFakes().apply {
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
    fun `a pick that won't fit says that Android asks next too, as a release that fits is chosen in its list`() = runTest {
        var asked = false
        fun fakes(sdk: Int) = SheetFakes().apply {
            ask = NotificationAsk(sdk = sdk, shown = { true }, granted = { false }, asked = { asked }, markAsked = { asked = true }, launch = { ask?.answered() })
            space = DownloadSpace(available = 4 * GB, used = 0, queued = 0)
            films = { _, context -> flowOf(MovieFind(film(listOf(ranked(nzb(COPPER, 4_300_000_000), 2.0), ranked(nzb(COPPER_720, 2_200_000_000), 1.0))), STATUSES, context)) }
        }
        assertFalse(assertIs<DownloadSheetState.WontFit>(open(FILM, fakes(32)).state.value).asks, "Android 12 asks nothing")
        val first = fakes(33)
        val sheet = open(FILM, first)
        val wontFit = assertIs<DownloadSheetState.WontFit>(sheet.state.value)
        assertTrue(wontFit.asks, "the line before Android's dialog (L358), which a pick in Choose release's list brings up")
        wontFit.mode.onDownload(wontFit.group.playable.single { it.item.title == COPPER_720 })
        advanceUntilIdle()
        assertEquals(listOf("enqueue", "ask", "schedule"), first.calls)
        assertFalse(assertIs<DownloadSheetState.WontFit>(open(FILM, fakes(33)).state.value).asks, "once")
    }

    @Test
    fun `the sheet closes once the download is queued, while Android's ask still waits for its answer`() = runTest {
        var asked = false
        lateinit var ask: NotificationAsk
        // Android's dialog up, not answered yet: a rotation now remakes the page, whose sheet must not come back.
        ask = NotificationAsk(sdk = 34, shown = { true }, granted = { false }, asked = { asked }, markAsked = { asked = true }, launch = {})
        val fakes = SheetFakes().apply {
            this.ask = ask
            finder = inOrder(nzb(KESTREL, 1_200_000_000))
        }
        val sheet = open(EPISODE, fakes)
        sheet.download()
        runCurrent()
        assertTrue(sheet.closed.value, "closed as soon as the runner has it")
        assertEquals(listOf("enqueue", "ask"), fakes.calls)
        ask.answered()
        runCurrent()
        assertEquals(listOf("enqueue", "ask", "schedule"), fakes.calls, "the host asked for once Android has its answer")
    }

    @Test
    fun `Download queues the pick and the releases that fit after it, a cached torrent among them, then asks, schedules and closes`() = runTest {
        val fakes = SheetFakes().apply {
            // 4.0 GB free: the 9.8 GB remux doesn't fit; the pack's share of the episode does.
            space = DownloadSpace(available = 5 * GB, used = 0, queued = 0)
            finder = inOrder(
                nzb(KESTREL, 1_200_000_000), torrent(TORRENT, 1_800_000_000, Availability.Cached(listOf("tb")), HASH),
                nzb(PACK, 11_200_000_000, indexer = "idx-b", passworded = true), nzb(FERRY, 9_800_000_000), nzb(ATMOS, 3_400_000_000),
            )
        }
        val sheet = open(EPISODE, fakes)
        val pick = assertIs<DownloadSheetState.Pick>(sheet.state.value)
        assertEquals(listOf(KESTREL, TORRENT, PACK, FERRY, ATMOS), pick.group.playable.map { it.item.title }, "a torrent the service has cached listed as any release")
        assertFalse(sheet.closed.value)

        sheet.download()
        sheet.download()
        advanceUntilIdle()
        val (target, candidates) = fakes.enqueued.single()
        assertEquals(EPISODE.target, target)
        assertEquals(listOf(KESTREL, TORRENT, PACK, ATMOS), candidates.map { it.title })
        assertEquals(
            DownloadCandidate("idx-b", PACK, PACK, "https://indexer.invalid/api?t=get&id=$PACK&apikey={apikey}", 11_200_000_000, passworded = true),
            candidates[2],
            "the link as the app keeps it, the indexer's size and its passworded flag",
        )
        assertEquals(
            DownloadCandidate(SheetFixtures.INDEXER_T.id, "btih:$HASH", TORRENT, null, 1_800_000_000, source = "torrent"),
            candidates[1],
            "a torrent by its content id, with no link, its source written (L459)",
        )
        assertEquals(listOf(null, "torrent", null, null), candidates.map { it.source }, "a Usenet release's source left unwritten")
        assertEquals(listOf("enqueue", "ask", "schedule"), fakes.calls)
        assertTrue(sheet.closed.value)
    }

    @Test
    fun `nothing is asked or scheduled when the runner queued nothing, and the sheet still closes`() = runTest {
        val fakes = SheetFakes().apply {
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
        val fakes = SheetFakes().apply { finder = inOrder(nzb(KESTREL, 1_200_000_000), nzb(PACK, 11_200_000_000), nzb(ATMOS, 3_400_000_000)) }
        val pick = assertIs<DownloadSheetState.Pick>(open(EPISODE, fakes).state.value)
        pick.mode.onDownload(pick.group.playable.single { it.item.title == ATMOS })
        advanceUntilIdle()
        assertEquals(listOf(ATMOS, KESTREL, PACK), fakes.enqueued.single().second.map { it.title })
        assertEquals(listOf("enqueue", "ask", "schedule"), fakes.calls)
    }

    @Test
    fun `at most 10 releases are kept to try`() = runTest {
        val fakes = SheetFakes().apply { finder = inOrder(*(1..12).map { nzb("Harbor.Lights.S02E04.720p.WEB-DL.H.264-GRP$it", 900_000_000) }.toTypedArray()) }
        val sheet = open(EPISODE, fakes)
        sheet.download()
        advanceUntilIdle()
        assertEquals((1..10).map { "Harbor.Lights.S02E04.720p.WEB-DL.H.264-GRP$it" }, fakes.enqueued.single().second.map { it.title })
    }

    /**
     * L422, L459: the search ranks a torrent by its cache check, as a play's does. One a debrid service has cached is offered
     * as any release, the pick where it ranks first, and kept with its source; one no service has cached stays hidden with
     * the ranker's reason, never kept.
     */
    @Test
    fun `a torrent a debrid service has cached is offered and kept with its source, and one not cached stays hidden`() = runTest {
        val fakes = SheetFakes().apply {
            finder = ranking(
                torrent(TORRENT, 1_800_000_000, Availability.Cached(listOf("tb")), HASH), torrent(DRIFT, 900_000_000, Availability.NotCached, HASH_2),
                nzb(KESTREL, 1_200_000_000),
            )
        }
        val sheet = open(EPISODE, fakes)
        val pick = assertIs<DownloadSheetState.Pick>(sheet.state.value)
        assertEquals(setOf(TORRENT, KESTREL), pick.group.playable.map { it.item.title }.toSet())
        val hidden = pick.group.hidden.single()
        assertEquals(DRIFT, hidden.item.title)
        assertEquals("Not cached", hidden.ranked.reasons.first().text)

        sheet.download()
        advanceUntilIdle()
        val candidates = fakes.enqueued.single().second
        assertEquals(pick.group.playable.map { it.item.title }, candidates.map { it.title }, "in the list's order, the one not cached left out")
        assertEquals("torrent", candidates.single { it.title == TORRENT }.source)
        assertEquals("btih:$HASH", candidates.single { it.title == TORRENT }.guid)
        assertNull(candidates.single { it.title == KESTREL }.source)
    }

    /**
     * L422: the searches ask the indexers the container gives a search ([io.skjaere.usenet.app.search.IndexerConfigs.of]):
     * the torrent indexers only while debrid shows and a service account is on, so a hidden build, or one with no account
     * on, searches Usenet's alone.
     */
    @Test
    fun `the torrent indexers are asked only while debrid shows and an account is on, and hidden Usenet's alone`() = runTest {
        suspend fun asked(request: DownloadRequest, account: Boolean, shown: Boolean): List<Pair<String, IndexerKind>> {
            val sources = SearchSources.of(StandardTestDispatcher(testScheduler), account)
            val fakes = SheetFakes().apply { indexers = { sources.indexers(shown) } }
            open(request, fakes)
            return fakes.searched.single().map { it.name to it.kind }
        }
        val both = listOf(SearchSources.USENET to IndexerKind.Newznab, SearchSources.TORZNAB to IndexerKind.Torznab)
        val usenet = listOf(SearchSources.USENET to IndexerKind.Newznab)
        mapOf("an episode" to EPISODE, "a film" to FILM).forEach { (what, request) ->
            assertEquals(both, asked(request, account = true, shown = true), "$what: an account on, debrid shown")
            assertEquals(usenet, asked(request, account = false, shown = true), "$what: no account on")
            assertEquals(usenet, asked(request, account = true, shown = false), "$what: debrid hidden, whatever a shown build saved")
        }
    }

    /**
     * L422: the sheets' and the turn's searches read the container's indexers, with nothing they find left out by its
     * source: the app's deps and the turn's search ask [io.skjaere.usenet.app.AppContainer.indexerConfigs], and no torrent
     * filter stands in the three files.
     */
    @Test
    fun `the download searches ask the container's indexers and leave no torrent out themselves`() {
        val dir = java.io.File("src/main/kotlin/io/skjaere/usenet/app/download")
        val files = listOf("DownloadSheetModel.kt", "DownloadSeasonSheetModel.kt", "DownloadSearch.kt").associateWith { java.io.File(dir, it).readText() }
        files.forEach { (name, text) ->
            listOf("usenetOnly", "infoHash == null").forEach { assertFalse(it in text, "$name still has $it") }
        }
        listOf("DownloadSheetModel.kt", "DownloadSearch.kt").forEach { name ->
            assertTrue("indexers = { container.indexerConfigs() }" in files.getValue(name), "$name's app deps ask the container's indexers")
        }
        assertTrue("deps.findSeason(request.search, deps.indexers(), deps.context())" in files.getValue("DownloadSeasonSheetModel.kt"))
    }

    /** An imported file's sheet asks no search (L455): each of these fails the test if a sheet asks it. */
    private fun SheetFakes.unsearched() = apply {
        finder = { _, _ -> throw AssertionError("an episode's search asked") }
        films = { _, _ -> throw AssertionError("a film's search asked") }
        seasons = { _, _ -> throw AssertionError("a season's search asked") }
    }

    /**
     * L455: a file of an NZB opened on Import is the release. Its sheet asks no finder and ranks nothing: "This file", its
     * quality with its exact size (no "about"), the NZB's name, and over the file's name, where the NZB holds more than one
     * video, that only it is downloaded. The sheet's second line names it as Up next names a release played without TMDB.
     */
    @Test
    fun `an imported file's sheet asks no finder and says This file with its exact size, its NZB and that only it is downloaded`() = runTest {
        val fakes = SheetFakes().unsearched()
        val file = assertIs<DownloadSheetState.File>(open(QM_E02_FILE, fakes).state.value)
        assertEquals(QM_E02_FILE, file.file)
        assertEquals(emptyList(), fakes.contexts, "nothing searched or ranked")
        assertEquals(emptyList(), fakes.calls, "nothing queued before Download")
        assertEquals("Quiet Meridian · S01E02", QM_E02_FILE.subtitle)
        assertEquals("Quiet Meridian", QM_E02_FILE.title)
        assertEquals("This file", DownloadSheetModel.THIS_FILE)
        assertEquals("1080p · WEB-DL · 1.3 GB", DownloadSheetModel.fileQuality(file.file))
        assertEquals("Season pack · only this file is downloaded:", DownloadSheetModel.onlyThisFile(file.file))
        // An NZB of one video says nothing of it; one of several named for no season, without its kind.
        assertNull(DownloadSheetModel.onlyThisFile(imported(videos = 1)))
        assertEquals("Only this file is downloaded:", DownloadSheetModel.onlyThisFile(imported(nzbTitle = "Quiet.Meridian.Extras.1080p.WEB-DL.H264", videos = 3)))
        // A file named for nothing: its quality as its NZB's name says it.
        assertEquals("1080p · WEB-DL · 1.3 GB", DownloadSheetModel.fileQuality(imported(name = "5d41402abc4b2a76b9719d911017c592.mkv")))
        assertEquals("5d41402abc4b2a76b9719d911017c592", imported(name = "5d41402abc4b2a76b9719d911017c592.mkv").subtitle)
    }

    /**
     * L455, L454: an imported file's sheet searches nowhere, so it never says it does: from its first frame, while the room
     * is read, it shows the file's card alone, never "Finding releases…" with the indexers' note; then the room as weighed.
     * Back from Android's storage page before the room was read, it weighs nothing twice.
     */
    @Test
    fun `an imported file's sheet shows its card while the room is read, never saying it searches`() = runTest {
        val fakes = SheetFakes().unsearched()
        val sheet = DownloadSheetModel(QM_E02_FILE, fakes.deps(this), backgroundScope)
        assertEquals(DownloadSheetState.Weighing(QM_E02_FILE), sheet.state.value)
        sheet.shown()
        runCurrent()
        assertEquals(DownloadSheetState.Weighing(QM_E02_FILE), sheet.state.value, "weighed before the sheet began")
        sheet.start()
        runCurrent()
        assertEquals(QM_E02_FILE, assertIs<DownloadSheetState.File>(sheet.state.value).file)
        assertEquals(emptyList(), fakes.contexts, "nothing searched")
    }

    /** L455: the space free, read with where the size comes from (the file's), when it starts and the ask's line, as a pick's. */
    @Test
    fun `an imported file's sheet says the space free, when it starts, and that Android asks next`() = runTest {
        val fakes = SheetFakes().unsearched().apply {
            // 1.0 GB kept free and 2.2 GB a queued download still needs: 14.0 GB left of 17.2.
            space = DownloadSpace(available = 17_200_000_000, used = 3 * GB, queued = 2_200_000_000, queuedDownloads = 1)
            rows = listOf(row("q", DownloadState.Queued))
        }
        val file = assertIs<DownloadSheetState.File>(open(QM_E02_FILE, fakes).state.value)
        assertEquals(14 * GB, file.free)
        assertEquals("14.0 GB free on this device", DownloadSheetModel.free(file.free))
        assertEquals("The size is the file's, read from the release.", DownloadSheetModel.FILES_SIZE)
        assertEquals(DownloadStart.AfterQueue(1), file.start)
        assertFalse(file.asks, "no ask outside the app's activity")
        assertEquals(DownloadStart.OnWifi, assertIs<DownloadSheetState.File>(open(QM_E02_FILE, SheetFakes().unsearched().apply { network = MOBILE }).state.value).start)

        var asked = false
        val asking = SheetFakes().unsearched().apply {
            ask = NotificationAsk(sdk = 33, shown = { true }, granted = { false }, asked = { asked }, markAsked = { asked = true }, launch = { ask?.answered() })
        }
        assertTrue(assertIs<DownloadSheetState.File>(open(QM_E02_FILE, asking).state.value).asks)
    }

    /**
     * L455: a file that won't fit says what it needs, exactly (no "about": it is the file's own size), and why less is free;
     * its note keeps Storage settings alone. No Download, and nothing to choose from: no Choose release.
     */
    @Test
    fun `an imported file that won't fit says what it needs exactly, with no Download and no Choose release`() = runTest {
        // 900 MB free, after the 1.0 GB kept free: the file's 1.4 GB doesn't fit. Sizes as the app says them everywhere.
        val fakes = SheetFakes().unsearched().apply { space = DownloadSpace(available = 1_900_000_000, used = 0, queued = 0) }
        val e01 = imported(name = QM_E01, size = 1_400_000_000, index = 0)
        val sheet = open(e01, fakes)
        val wontFit = assertIs<DownloadSheetState.FileWontFit>(sheet.state.value)
        assertEquals(e01, wontFit.file)
        assertEquals("Needs 1.4 GB · 900 MB free on this device", DownloadSheetModel.needsExactly(wontFit.file.size, wontFit.free))
        assertEquals("That is after the 1.0 GB kept free for the phone.", DownloadSheetModel.after(wontFit.queued, wontFit.queuedDownloads))
        assertEquals("Storage settings opens Android's page for making room.", DownloadSheetModel.STORAGE_NOTE)
        assertEquals("Choose release shows which releases fit. ${DownloadSheetModel.STORAGE_NOTE}", DownloadSheetModel.WONT_FIT_NOTE)
        sheet.download()
        advanceUntilIdle()
        assertEquals(emptyList(), fakes.calls, "no Download for a file that won't fit")
        assertFalse(sheet.closed.value)
    }

    /** Back from Android's storage page an imported file is weighed again against the room as it is now, with no search. */
    @Test
    fun `back from Android's storage page an imported file is weighed again`() = runTest {
        val fakes = SheetFakes().unsearched().apply { space = DownloadSpace(available = 1_900_000_000, used = 0, queued = 0) }
        val sheet = open(imported(name = QM_E01, size = 1_400_000_000, index = 0), fakes)
        assertIs<DownloadSheetState.FileWontFit>(sheet.state.value)
        fakes.space = DownloadSpace(available = 15 * GB, used = 0, queued = 0)
        sheet.shown()
        runCurrent()
        assertEquals(14 * GB, assertIs<DownloadSheetState.File>(sheet.state.value).free)
        // Room taken again meanwhile.
        fakes.space = DownloadSpace(available = 1_900_000_000, used = 0, queued = 0)
        sheet.shown()
        runCurrent()
        assertIs<DownloadSheetState.FileWontFit>(sheet.state.value)
        assertEquals(emptyList(), fakes.contexts, "nothing searched")
    }

    /**
     * L455, L423: Download queues the file once, through the runner's import: its target, Import's own release of the NZB
     * at the file's exact size, the file as its pick, and the NZB's bytes with the password typed for it, which the
     * download keeps; then Android's ask and the host's schedule, and the sheet closes. On a TV the runner queues nothing:
     * nothing is asked or scheduled.
     */
    @Test
    fun `Download queues an imported file once, with its NZB and the password typed, then asks, schedules and closes`() = runTest {
        val fakes = SheetFakes().unsearched()
        val sheet = open(QM_E02_FILE, fakes)
        sheet.download()
        sheet.download()
        advanceUntilIdle()
        val queued = fakes.imported.single()
        assertEquals(DownloadTarget.release(QM_ID, 1, QM_E02, QM_NZB), queued.target)
        assertEquals(DownloadCandidate(IMPORTED, QM_ID, QM_NZB, size = QM_E02_SIZE), queued.candidate)
        assertEquals(DownloadPick(1, QM_E02, QM_E02_SIZE, QM_ID), queued.pick)
        assertContentEquals(QM_BYTES, queued.source.nzb)
        assertEquals(TYPED, queued.source.password)
        assertEquals(listOf("enqueueImport", "ask", "schedule"), fakes.calls)
        assertTrue(sheet.closed.value)

        val tv = SheetFakes().unsearched().apply { queues = false }
        val none = open(QM_E02_FILE, tv)
        none.download()
        advanceUntilIdle()
        assertEquals(listOf("enqueueImport"), tv.calls)
        assertTrue(none.closed.value)
    }

    /** The request is held by the sheet and Import: its names and the password are never in a log line or a trace. */
    @Test
    fun `an imported file's request names no file, NZB or password in its toString`() {
        val text = QM_E02_FILE.toString()
        listOf(QM_E02, QM_NZB, TYPED, "Quiet", "Meridian").forEach { assertFalse(it in text, text) }
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
        const val SHOW_ID = 990_417
        const val FILM_ID = 990_418

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
        const val DRIFT = "Harbor.Lights.S02E04.720p.HDTV.x264-DRIFT"
        const val HASH_2 = "fedcba9876543210fedcba9876543210fedcba98"
        const val COPPER = "Copper.Crown.2022.1080p.WEB-DL.DDP5.1.H.264-KESTREL"
        const val COPPER_BLURAY = "Copper.Crown.2022.1080p.BluRay.x264-DRIFTWOOD"
        const val COPPER_720 = "Copper.Crown.2022.720p.WEB-DL.DDP5.1.H.264-KESTREL"
        const val COPPER_REMUX = "Copper.Crown.2022.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY"

        /** An NZB opened on Import (L455): Quiet Meridian's first season, its bytes, its id and the password typed for it. */
        const val QM_NZB = "Quiet.Meridian.S01.1080p.WEB-DL.H264"
        const val QM_E01 = "Quiet.Meridian.S01E01.1080p.WEB-DL.H264.mkv"
        const val QM_E02 = "Quiet.Meridian.S01E02.1080p.WEB-DL.H264.mkv"
        const val QM_E02_SIZE = 1_312_345_678L
        const val TYPED = "typed-on-import"
        val QM_BYTES = "<nzb>Quiet Meridian season 1</nzb>".toByteArray()
        val QM_ID = PreparedNzb.idFor(QM_BYTES)

        /** File [name] at [index] of [size] bytes, of an NZB titled [nzbTitle] that holds [videos] videos, as Import asks for it. */
        fun imported(name: String = QM_E02, size: Long = QM_E02_SIZE, index: Int = 1, videos: Int = 6, nzbTitle: String = QM_NZB) =
            DownloadRequest.File(QM_ID, FileCandidate(index, StreamableFile(name, size, index, 0, 0, 0)), nzbTitle, videos, DownloadStorage.Source(QM_BYTES, TYPED))

        val QM_E02_FILE = imported()

        /** A film's group of [ranked] releases. */
        fun film(ranked: List<RankedItem>) = ResultGroup("copper crown|2022", GroupKind.Movie, null, emptyList(), null, 2022, ranked, emptyList())

        fun row(id: String, state: DownloadState) = Download(
            id = id, watchKey = WatchKey.episode(SHOW_ID, 1, 1), kind = DownloadKind.Episode, title = "Harbor Lights", state = state, position = 0,
            indexerSize = 1_000_000_000, createdAt = 0, updatedAt = 0,
        )
    }
}
