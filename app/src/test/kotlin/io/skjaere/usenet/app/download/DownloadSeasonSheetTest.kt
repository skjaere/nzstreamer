package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.download.SheetFixtures.GB
import io.skjaere.usenet.app.download.SheetFixtures.MOBILE
import io.skjaere.usenet.app.download.SheetFixtures.nzb
import io.skjaere.usenet.app.download.SheetFixtures.ranked
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankingTarget
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.search.FindResult
import io.skjaere.usenet.search.FoundRelease
import io.skjaere.usenet.search.IndexerReport
import io.skjaere.usenet.search.SearchStage
import io.skjaere.usenet.search.SeasonEpisode
import io.skjaere.usenet.search.SeasonFind
import io.skjaere.usenet.search.SeasonRequest
import io.skjaere.usenet.search.ShowRef
import io.skjaere.usenet.search.StageReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A season's Download sheet (W114, L396, L400, L433), each state from fakes of the season's one search, the device's
 * space, network and queue, Android's notification ask and the runner: Finding searches once, ranked for downloading;
 * the season's line, each episode's size or that nothing is found yet, the episodes counted apart, a failed one listed
 * again, one found only above the Download size, when it starts; Won't fit's prefix in episode order; nothing for the
 * whole season; and what Download queues: each episode once, in order, its releases that fit with the first's need and
 * its search facts, torrents left out, then the ask and the host's schedule. Invented show and releases.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSeasonSheetTest {
    private val saved = Locale.getDefault()

    // formatSize follows the default locale; pin one so the decimal separator is predictable.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    /** The sheet opened for [request]: its search started and run as far as it goes. */
    private fun TestScope.open(request: SeasonDownloadRequest, fakes: SheetFakes): DownloadSeasonSheetModel =
        DownloadSeasonSheetModel(request, fakes.deps(this), backgroundScope).also {
            it.start()
            runCurrent()
        }

    /** A season's search that found [found] for each episode by its number, in that order, ranked for downloading. */
    private fun SheetFakes.finds(found: Map<Int, List<NewznabItem>>, stages: List<StageReport> = emptyList()) {
        seasons = { request, _ ->
            SeasonFind(
                request.episodes.associate { e ->
                    val releases = found[e.number].orEmpty().mapIndexed { i, item -> ranked(item, 100.0 - i).let { FoundRelease(it.item, it.ranked, SearchStage.SeasonPack) } }
                    e.number to FindResult(request.request(e), releases, emptyList(), stages)
                },
                stages,
            )
        }
    }

    /** Season 2's six listed, in the sizes drawn: S02E03 to E08 not yet downloaded, about 8.4 GB in all. */
    private val sizes = mapOf(3 to 1_400_000_000L, 4 to 1_200_000_000L, 5 to 1_500_000_000L, 6 to 1_300_000_000L, 7 to 1_600_000_000L, 8 to 1_400_000_000L)

    private fun singles(numbers: Iterable<Int> = sizes.keys) = numbers.associateWith { listOf(nzb(single(it), sizes.getValue(it))) }

    private fun pick(sheet: DownloadSeasonSheetModel) = assertIs<SeasonSheetState.Pick>(sheet.state.value)

    private fun wontFit(sheet: DownloadSeasonSheetModel) = assertIs<SeasonSheetState.WontFit>(sheet.state.value)

    @Test
    fun `Finding searches the season once, ranked for downloading, and says nothing downloads until Download`() = runTest {
        val answer = CompletableDeferred<Unit>()
        val fakes = SheetFakes().apply {
            finds(singles())
            val found = seasons
            seasons = { request, context ->
                answer.await()
                found(request, context)
            }
        }
        val sheet = open(SEASON, fakes)
        assertEquals(SeasonSheetState.Finding, sheet.state.value)
        assertEquals("Finding releases for season 2…", DownloadSeasonSheetModel.finding(2))
        assertEquals(
            "Searches your indexers and ranks what they find for downloading. Nothing is downloaded until you choose Download.",
            DownloadSeasonSheetModel.FINDING_NOTE,
        )
        assertEquals("Harbor Lights · Season 2", SEASON.subtitle)
        // One search for the season, by the show's names and ids and each listed episode's date and length (L393, L433).
        assertEquals(
            SeasonRequest(
                ShowRef("Harbor Lights", 2021, emptyList(), TvIds(tvdb = 7_001, tmdb = SHOW_ID)), 2,
                (3..8).map { SeasonEpisode(it, AIRED.plusWeeks(it - 1L), 52) }, seasonEpisodes = 8,
            ),
            fakes.seasonRequests.single(),
        )
        assertEquals(RankingTarget.Download, fakes.contexts.single().target)
        assertEquals(emptyList(), fakes.calls, "nothing grabbed or queued while it finds")

        answer.complete(Unit)
        runCurrent()
        assertEquals((3..8).toList(), pick(sheet).episodes.map { it.listed.play.episode })
        assertEquals(emptyList(), fakes.calls, "nothing queued before Download")
    }

    @Test
    fun `the season's line counts the episodes listed and what is known of their sizes, one in the singular`() = runTest {
        val all = pick(open(SEASON, SheetFakes().apply { finds(singles()) }))
        assertEquals("Season 2 · 6 episodes · about 8.4 GB", DownloadSeasonSheetModel.head(2, all.episodes))
        assertEquals("Download 6 episodes", DownloadSeasonSheetModel.download(all))

        val one = pick(open(request(listOf(8), downloaded = (1..6).toList(), queued = listOf(7)), SheetFakes().apply { finds(singles()) }))
        assertEquals("Season 2 · 1 episode · about 1.4 GB", DownloadSeasonSheetModel.head(2, one.episodes))
        assertEquals("Download 1 episode", DownloadSeasonSheetModel.download(one))

        // The total counts the sizes known: S02E08 with nothing found yet adds none.
        val known = pick(open(SEASON, SheetFakes().apply { finds(singles(3..7)) }))
        assertEquals("Season 2 · 6 episodes · about 7.0 GB", DownloadSeasonSheetModel.head(2, known.episodes))
        assertEquals(7_000_000_000, DownloadSeasonSheetModel.total(known.episodes))
    }

    @Test
    fun `each episode says about how much its first release needs here, a season pack its share, or that nothing is found yet`() = runTest {
        val fakes = SheetFakes().apply {
            // S02E04 only in the season's 11.2 GB pack, an eighth of it for the episode; S02E05 found nowhere.
            finds(mapOf(3 to listOf(nzb(single(3), 1_400_000_000), nzb(single(3, "ATMOS"), 3_400_000_000)), 4 to listOf(nzb(PACK, 11_200_000_000)), 6 to listOf(nzb(single(6), 1_300_000_000))))
        }
        val episodes = pick(open(request(listOf(3, 4, 5, 6)), fakes)).episodes
        assertEquals(listOf("about 1.4 GB", "about 1.4 GB", "nothing found yet", "about 1.3 GB"), episodes.map(DownloadSeasonSheetModel::size))
        assertEquals(listOf(1_400_000_000L, 1_400_000_000L, null, 1_300_000_000L), episodes.map { it.need })
        assertEquals(listOf("Fog Signal", "Low Water", "The Long Tide", "Salt and Iron"), episodes.map { it.listed.play.episodeName })
    }

    @Test
    fun `the episodes left out are counted apart by their numbers, each line in its number's form`() {
        assertEquals("1 and 2", DownloadSeasonSheetModel.numbers(listOf(1, 2)))
        assertEquals("1 to 6", DownloadSeasonSheetModel.numbers((1..6).toList()))
        assertEquals("1, 3 and 5", DownloadSeasonSheetModel.numbers(listOf(5, 1, 3)))
        assertEquals("7", DownloadSeasonSheetModel.numbers(listOf(7)))
        assertEquals("6 to 8", DownloadSeasonSheetModel.numbers(listOf(6, 7, 8)))
        assertEquals("1 to 3 and 5", DownloadSeasonSheetModel.numbers(listOf(1, 2, 3, 5)))
        assertEquals("1, 2 and 4", DownloadSeasonSheetModel.numbers(listOf(1, 2, 4)))

        val lines = DownloadSeasonSheetModel.apart(request(listOf(3, 4, 5), downloaded = listOf(1, 2), queued = listOf(6), notAired = listOf(7, 8)))
        assertEquals(
            listOf(
                SeasonApart.Downloaded to "1 and 2 are downloaded already, so they're left out.",
                SeasonApart.Queued to "6 is in the queue already, so it's left out.",
                SeasonApart.NotAired to "7 and 8 haven't aired yet and aren't included. Nothing downloads by itself when they air.",
            ),
            lines,
        )
        assertEquals(
            listOf(
                SeasonApart.Downloaded to "1 is downloaded already, so it's left out.",
                SeasonApart.Queued to "2 and 7 are in the queue already, so they're left out.",
                SeasonApart.NotAired to "8 hasn't aired yet and isn't included. Nothing downloads by itself when it airs.",
            ),
            DownloadSeasonSheetModel.apart(request(listOf(3, 4, 5, 6), downloaded = listOf(1), queued = listOf(2, 7), notAired = listOf(8))),
        )
        assertEquals(
            listOf(SeasonApart.Downloaded to "1 to 6 are downloaded already, so they're left out.", SeasonApart.Queued to "7 is in the queue already, so it's left out."),
            DownloadSeasonSheetModel.apart(request(listOf(8), downloaded = (1..6).toList(), queued = listOf(7))),
        )
        assertEquals(
            listOf(SeasonApart.NotAired to "6 to 8 haven't aired yet and aren't included. Nothing downloads by itself when they air."),
            DownloadSeasonSheetModel.apart(request((1..5).toList(), notAired = listOf(6, 7, 8))),
        )
        assertEquals(emptyList(), DownloadSeasonSheetModel.apart(SEASON.copy(downloaded = emptyList())))
    }

    @Test
    fun `an episode whose download failed before is listed again, searched afresh, and one found only above the Download size says so`() = runTest {
        val above = ReleaseReason.AboveDownloadSize(4)
        val fakes = SheetFakes().apply {
            space = DownloadSpace(available = 42 * GB, used = 0, queued = 0)
            seasons = { request, _ ->
                SeasonFind(
                    request.episodes.associate { e ->
                        val releases = when (e.number) {
                            // Above the cap alone: the ranking tries those last, so the first says it.
                            7 -> listOf(ranked(nzb(single(7, "REMUX"), 9_800_000_000), 2.0, listOf(above)), ranked(nzb(single(7, "UHD"), 16_400_000_000), 1.0, listOf(above)))
                            // One that fits the cap first: no line, though one after it is above.
                            8 -> listOf(ranked(nzb(single(8), 1_400_000_000), 2.0), ranked(nzb(single(8, "REMUX"), 9_800_000_000), 1.0, listOf(above)))
                            else -> listOf(ranked(nzb(single(e.number), sizes.getValue(e.number)), 1.0))
                        }
                        e.number to FindResult(request.request(e), releases.map { FoundRelease(it.item, it.ranked, SearchStage.SeasonPack) }, emptyList(), emptyList())
                    },
                    emptyList(),
                )
            }
        }
        val episodes = pick(open(request((3..8).toList(), failed = setOf(3)), fakes)).episodes.associateBy { it.listed.play.episode }
        assertEquals(listOf("Failed before · searched afresh"), DownloadSeasonSheetModel.notes(episodes.getValue(3)))
        assertEquals(listOf("⚠ Above your 4 GB an hour"), DownloadSeasonSheetModel.notes(episodes.getValue(7)))
        assertEquals(emptyList(), DownloadSeasonSheetModel.notes(episodes.getValue(8)))
        assertEquals(emptyList(), DownloadSeasonSheetModel.notes(episodes.getValue(4)))
        assertTrue(episodes.getValue(3).listed.failedBefore)
    }

    @Test
    fun `the space free says the sizes are estimates, and names each episode with nothing found yet as searched again at its turn`() = runTest {
        val fakes = SheetFakes().apply {
            space = DownloadSpace(available = 42 * GB, used = 0, queued = 0)
            finds(singles(listOf(3, 4, 6, 7)))
        }
        val pick = pick(open(SEASON, fakes))
        assertEquals("41.0 GB free on this device", DownloadSeasonSheetModel.free(pick.free))
        assertEquals(
            "Each episode is its own download. Its release is found again when its turn comes, so these sizes are estimates. " +
                "The Long Tide is searched again at its turn. Undertow is searched again at its turn.",
            DownloadSeasonSheetModel.freeNote(pick.episodes),
        )
        val all = pick(open(SEASON, SheetFakes().apply { finds(singles()) }))
        assertEquals(
            "Each episode is its own download. Its release is found again when its turn comes, so these sizes are estimates.",
            DownloadSeasonSheetModel.freeNote(all.episodes),
        )
    }

    @Test
    fun `on mobile data it starts on Wi-Fi, one episode at a time, and otherwise as one download starts`() = runTest {
        suspend fun TestScope.start(fakes: SheetFakes): DownloadStart {
            fakes.finds(singles())
            return pick(open(SEASON, fakes)).start
        }
        assertEquals(DownloadStart.Now, start(SheetFakes()))
        assertEquals(DownloadStart.OnWifi, start(SheetFakes().apply { network = MOBILE }))
        assertEquals(DownloadStart.AfterQueue(1), start(SheetFakes().apply { rows = listOf(row("q", DownloadState.Queued)) }))
        assertEquals(DownloadStart.Held, start(SheetFakes().apply { rows = listOf(row("p", DownloadState.Paused)) }))

        assertEquals("Starts now, one episode at a time" to null, DownloadSeasonSheetModel.start(DownloadStart.Now))
        assertEquals(
            "Starts on Wi-Fi, one episode at a time" to "This phone is on mobile data. Downloads wait for Wi-Fi or Ethernet: Settings › Downloads.",
            DownloadSeasonSheetModel.start(DownloadStart.OnWifi),
        )
        // The others as one download's sheet says them.
        listOf(DownloadStart.AfterQueue(1), DownloadStart.Held, DownloadStart.Charging, DownloadStart.Online(wifiOnly = true)).forEach {
            assertEquals(DownloadSheetModel.start(it), DownloadSeasonSheetModel.start(it))
        }
    }

    @Test
    fun `a season that won't all fit offers the longest run of its episodes in order that fits`() = runTest {
        // 5.1 GB free: S02E03 to E05 come to 4.1 GB, and S02E06's 1.3 GB more doesn't fit.
        val three = SheetFakes().apply {
            space = DownloadSpace(available = 6_100_000_000, used = 0, queued = 0)
            finds(singles())
        }
        val sheet = open(SEASON, three)
        val cut = wontFit(sheet)
        assertEquals(3, cut.fit)
        assertEquals("Season 2 · 6 episodes · about 8.4 GB", DownloadSeasonSheetModel.head(2, cut.episodes))
        assertEquals("Won't all fit: 5.1 GB free on this device", DownloadSeasonSheetModel.wontAllFit(cut.free))
        assertEquals("The first 3 fit: about 4.1 GB.", DownloadSeasonSheetModel.firstFit(cut))
        assertEquals("No room for these 3", DownloadSeasonSheetModel.noRoom(cut))
        assertEquals("Download the first 3", DownloadSeasonSheetModel.download(cut))
        sheet.download()
        advanceUntilIdle()
        assertEquals(listOf(3, 4, 5), three.enqueuedAll.single().map { it.target.episode })

        // 1.9 GB free: S02E03 alone.
        val one = wontFit(open(SEASON, SheetFakes().apply { space = DownloadSpace(available = 2_900_000_000, used = 0, queued = 0); finds(singles()) }))
        assertEquals(1, one.fit)
        assertEquals("Won't all fit: 1.9 GB free on this device", DownloadSeasonSheetModel.wontAllFit(one.free))
        assertEquals("The first fits: about 1.4 GB.", DownloadSeasonSheetModel.firstFit(one))
        assertEquals("No room for these 5", DownloadSeasonSheetModel.noRoom(one))
        assertEquals("Download the first episode", DownloadSeasonSheetModel.download(one))

        // 900 MB free, after the 1.0 GB kept free for the phone: none fits, and there is no Download.
        val noneFakes = SheetFakes().apply { space = DownloadSpace(available = 1_900_000_000, used = 0, queued = 0); finds(singles()) }
        val noneSheet = open(SEASON, noneFakes)
        val none = wontFit(noneSheet)
        assertEquals(0, none.fit)
        assertEquals("Won't fit on this device", DownloadSeasonSheetModel.WONT_FIT)
        assertEquals("The first needs about 1.4 GB · 900 MB free on this device", DownloadSeasonSheetModel.firstNeeds(none))
        assertEquals("That is after the 1.0 GB kept free for the phone.", DownloadSheetModel.after(none.queued, none.queuedDownloads))
        assertEquals(
            "A smaller release may fit: each episode's ⋮ › Download shows what does. Storage settings opens Android's page for making room.",
            DownloadSeasonSheetModel.NONE_FITS_NOTE,
        )
        assertEquals("No room for these 6", DownloadSeasonSheetModel.noRoom(none))
        assertNull(DownloadSeasonSheetModel.download(none))
        assertFalse(none.asks, "nothing to download, so nothing Android asks next")
        noneSheet.download()
        advanceUntilIdle()
        assertEquals(emptyList(), noneFakes.calls, "no Download where none fits")

        // A queued download's need counts against it too, as one episode's sheet counts it.
        val queued = wontFit(open(SEASON, SheetFakes().apply { space = DownloadSpace(available = 7_300_000_000, used = 0, queued = 1_200_000_000, queuedDownloads = 1); finds(singles()) }))
        assertEquals(3, queued.fit)
        // 2.0 GB free for S02E03 and E04: one left after the cut.
        val last = wontFit(open(request(listOf(3, 4)), SheetFakes().apply { space = DownloadSpace(available = 3 * GB, used = 0, queued = 0); finds(singles()) }))
        assertEquals("No room for this one", DownloadSeasonSheetModel.noRoom(last))
    }

    /**
     * L400: episodes with nothing found yet count no size, so a run of them at the head fits whatever is free; what fits is
     * then said with no size, as none is known, never "about 0 KB". Where some of the run have a size, it is theirs.
     */
    @Test
    fun `a run that fits with nothing found yet for it says so, with no size`() = runTest {
        // 900 MB free: S02E03 has nothing found, and S02E04's 1.2 GB doesn't fit.
        val tight = DownloadSpace(available = 1_900_000_000, used = 0, queued = 0)
        val one = wontFit(open(request(listOf(3, 4, 5)), SheetFakes().apply { space = tight; finds(singles(listOf(4, 5))) }))
        assertEquals(1, one.fit)
        assertEquals("The first fits: nothing found for it yet.", DownloadSeasonSheetModel.firstFit(one))
        assertEquals("Download the first episode", DownloadSeasonSheetModel.download(one))

        val two = wontFit(open(request(listOf(3, 4, 5)), SheetFakes().apply { space = tight; finds(singles(listOf(5))) }))
        assertEquals(2, two.fit)
        assertEquals("The first 2 fit: nothing found for them yet.", DownloadSeasonSheetModel.firstFit(two))

        // One of the run with a size: its size, as the season's line counts it.
        val known = wontFit(open(request(listOf(3, 4, 5)), SheetFakes().apply { space = DownloadSpace(available = 2_300_000_000, used = 0, queued = 0); finds(singles(listOf(4, 5))) }))
        assertEquals(2, known.fit)
        assertEquals("The first 2 fit: about 1.2 GB.", DownloadSeasonSheetModel.firstFit(known))
    }

    @Test
    fun `nothing found for the whole season says what the season's search did, with Close alone`() = runTest {
        val a = IndexerConfig("idx-a", "Indexer A", "https://indexer-a.invalid/api", "")
        val b = IndexerConfig("idx-b", "Indexer B", "https://indexer-b.invalid/api", "")
        val stages = listOf(StageReport(SearchStage.SeasonPack, listOf(IndexerReport(a, "TVDB id", found = 20), IndexerReport(b, "TVDB id", found = 18))))
        val nothing = SheetFakes().apply { finds(emptyMap(), stages) }
        assertEquals(
            SeasonSheetState.NoRelease(listOf("Searched 2 indexers for season 2 by TVDB id" to "38 results, none for season 2")),
            open(request(listOf(1, 2, 3, 4, 5, 6)), nothing).state.value,
        )
        assertEquals("No playable release found for season 2", DownloadSeasonSheetModel.noRelease(2))
        assertEquals(
            "Each episode's ⋮ › Download still searches it on its own, by air date and by name too. Indexers can also number some shows differently from TMDB.",
            DownloadSeasonSheetModel.NO_RELEASE_NOTE,
        )

        // One indexer that didn't answer, the other with nothing: its error, as the play sheet says it.
        val failed = listOf(StageReport(SearchStage.SeasonPack, listOf(IndexerReport(a, "name", error = NewznabError.Timeout("Indexer A timed out")), IndexerReport(b, "name"))))
        assertEquals(
            SeasonSheetState.NoRelease(listOf("Searched 2 indexers for season 2 by name" to "⚠ Indexer A timed out")),
            open(SEASON, SheetFakes().apply { finds(emptyMap(), failed) }).state.value,
        )
        val one = listOf(StageReport(SearchStage.SeasonPack, listOf(IndexerReport(a, "TVDB id"))))
        assertEquals(
            SeasonSheetState.NoRelease(listOf("Searched 1 indexer for season 2 by TVDB id" to "Nothing yet")),
            open(SEASON, SheetFakes().apply { finds(emptyMap(), one) }).state.value,
        )
        // A torrent alone found: nothing to download from Usenet (L370).
        assertIs<SeasonSheetState.NoRelease>(open(SEASON, SheetFakes().apply { finds(mapOf(3 to listOf(nzb(TORRENT, 1_000_000_000, hash = HASH)))) }).state.value)
        // A search that fails says nothing of the indexers.
        assertEquals(SeasonSheetState.NoRelease(emptyList()), open(SEASON, SheetFakes().apply { seasons = { _, _ -> throw IllegalStateException("the finder broke") } }).state.value)
    }

    @Test
    fun `Download queues each episode once, in order, with its releases that fit its turn, the first's need and its search facts, then asks and schedules`() = runTest {
        val fakes = SheetFakes().apply {
            // 8.0 GB free; S02E03 and E04 need 2.8 GB: S02E03 has 6.6 GB at its turn, so its 9.8 GB remux is left out.
            space = DownloadSpace(available = 9 * GB, used = 0, queued = 0)
            finds(
                mapOf(
                    3 to listOf(nzb(single(3), 1_400_000_000), nzb(single(3, "REMUX"), 9_800_000_000), nzb(single(3, "ATMOS"), 3_400_000_000, indexer = "idx-b", passworded = true)),
                    4 to listOf(nzb(PACK, 11_200_000_000)),
                ),
            )
        }
        val sheet = open(request(listOf(3, 4, 5), failed = setOf(5)), fakes)
        sheet.download()
        sheet.download()
        advanceUntilIdle()
        val items = fakes.enqueuedAll.single()
        assertEquals(listOf(3, 4, 5), items.map { it.target.episode }, "in episode order, each once")
        assertEquals(target(3), items[0].target)
        assertEquals(listOf(single(3), single(3, "ATMOS")), items[0].candidates.map { it.title })
        assertEquals(
            DownloadCandidate("idx-b", single(3, "ATMOS"), single(3, "ATMOS"), "https://indexer.invalid/api?t=get&id=${single(3, "ATMOS")}&apikey={apikey}", 3_400_000_000, passworded = true),
            items[0].candidates[1],
            "the link as the app keeps it, the indexer's size and its passworded flag",
        )
        assertEquals(listOf(1_400_000_000L, 1_400_000_000L, 0L), items.map { it.need }, "the first's need, a pack's share of its episode; none where nothing is found yet")
        assertEquals(listOf(listOf(PACK), emptyList()), items.drop(1).map { item -> item.candidates.map { it.title } })
        // Its search facts, with which its turn searches (L392): as a play of the episode asks the finder.
        assertEquals((3..5).map { SeasonSearch.of(play(it).request) }, items.map { it.search })
        assertEquals(listOf("enqueueAll", "ask", "schedule"), fakes.calls)
        assertTrue(sheet.closed.value)
    }

    @Test
    fun `at most 10 releases are kept for each episode, and torrents are left out`() = runTest {
        val fakes = SheetFakes().apply {
            finds(
                mapOf(
                    3 to listOf(nzb(TORRENT, 1_000_000_000, hash = HASH)) + (1..12).map { nzb(single(3, "GRP$it"), 900_000_000) },
                    4 to listOf(nzb(single(4), 1_200_000_000)),
                ),
            )
        }
        val sheet = open(request(listOf(3, 4)), fakes)
        val pick = pick(sheet)
        assertEquals(single(3, "GRP1"), pick.episodes.first().best?.item?.title, "the torrent never the pick")
        sheet.download()
        advanceUntilIdle()
        val items = fakes.enqueuedAll.single()
        assertEquals((1..10).map { single(3, "GRP$it") }, items[0].candidates.map { it.title })
        assertEquals(listOf(single(4)), items[1].candidates.map { it.title })
    }

    @Test
    fun `back from Android's storage page the season is weighed again against the room made, with no new search`() = runTest {
        val fakes = SheetFakes().apply {
            space = DownloadSpace(available = 6_100_000_000, used = 0, queued = 0)
            finds(singles())
        }
        val sheet = open(SEASON, fakes)
        assertEquals(3, wontFit(sheet).fit)
        fakes.space = DownloadSpace(available = 42 * GB, used = 0, queued = 0)
        sheet.shown()
        runCurrent()
        assertEquals(41 * GB, pick(sheet).free)
        assertEquals(1, fakes.seasonRequests.size, "searched once")
        sheet.download()
        advanceUntilIdle()
        assertEquals((3..8).toList(), fakes.enqueuedAll.single().map { it.target.episode })
    }

    @Test
    fun `the notification line shows where Android asks next, and nothing is asked or scheduled when the runner queued nothing`() = runTest {
        var asked = false
        val fakes = SheetFakes().apply {
            ask = NotificationAsk(sdk = 34, shown = { true }, granted = { false }, asked = { asked }, markAsked = { asked = true }, launch = { ask?.answered() })
            finds(singles())
        }
        assertTrue(pick(open(SEASON, fakes)).asks)
        val cut = SheetFakes().apply {
            ask = fakes.ask
            space = DownloadSpace(available = 6_100_000_000, used = 0, queued = 0)
            finds(singles())
        }
        assertTrue(wontFit(open(SEASON, cut)).asks, "Download the first 3 asks too")

        // A TV's runner queues nothing (L369): the sheet closes, asking nothing.
        val tv = SheetFakes().apply {
            queues = false
            finds(singles())
        }
        val sheet = open(SEASON, tv)
        sheet.download()
        advanceUntilIdle()
        assertEquals(listOf("enqueueAll"), tv.calls)
        assertTrue(sheet.closed.value)
    }

    private companion object {
        const val SHOW_ID = 990_417
        val AIRED: LocalDate = LocalDate.of(2025, 3, 4)
        val NAMES = mapOf(
            1 to "Brine", 2 to "The Breakwater", 3 to "Fog Signal", 4 to "Low Water", 5 to "The Long Tide", 6 to "Salt and Iron", 7 to "Slack Water", 8 to "Undertow",
        )
        const val PACK = "Harbor.Lights.S02.1080p.WEB-DL.DDP5.1.H.264-DRIFTWOOD"
        const val TORRENT = "Harbor.Lights.S02E03.1080p.WEB.H264-SALTMARSH"
        const val HASH = "0123456789abcdef0123456789abcdef01234567"

        /** S02E[n]'s single release by [group]. */
        fun single(n: Int, group: String = "KESTREL") = "Harbor.Lights.S02E%02d.1080p.WEB-DL.DDP5.1.H.265-%s".format(n, group)

        /** Episode [n] of Harbor Lights' season 2, as the Show page's answer gives it: aired a week apart from 4 March 2025. */
        fun play(n: Int) = EpisodePlay(
            showTmdbId = SHOW_ID, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tvdb = 7_001, tmdb = SHOW_ID),
            season = 2, episode = n, episodeName = NAMES[n], airDate = AIRED.plusWeeks(n - 1L), runtimeMinutes = 52, stillPath = "/s2e$n.jpg",
            seasonEpisodes = 8,
        )

        fun target(n: Int) = DownloadTarget.episode(
            SHOW_ID, "Harbor Lights", 2, n, seasonEpisodes = 8, episodeName = NAMES[n], subtitle = "S02E%02d · %s".format(n, NAMES[n]),
            posterPath = "/poster.jpg", stillPath = "/s2e$n.jpg", runtimeMinutes = 52,
        )

        fun request(
            listed: List<Int>,
            failed: Set<Int> = emptySet(),
            downloaded: List<Int> = emptyList(),
            queued: List<Int> = emptyList(),
            notAired: List<Int> = emptyList(),
        ) = SeasonDownloadRequest(
            "Harbor Lights", 2, listed.map { SeasonDownloadRequest.Listed(play(it), failedBefore = it in failed) }, downloaded, queued, notAired, posterPath = "/poster.jpg",
        )

        /** Season 2 as drawn: S02E01 and E02 downloaded, S02E03 to E08 listed. */
        val SEASON = request((3..8).toList(), downloaded = listOf(1, 2))

        fun row(id: String, state: DownloadState) = Download(
            id = id, watchKey = WatchKey.episode(SHOW_ID, 1, 1), kind = DownloadKind.Episode, title = "Harbor Lights", state = state, position = 0,
            indexerSize = 1_000_000_000, createdAt = 0, updatedAt = 0,
        )
    }
}
