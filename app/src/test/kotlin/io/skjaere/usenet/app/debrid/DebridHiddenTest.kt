package io.skjaere.usenet.app.debrid

import io.skjaere.usenet.app.BuildConfig
import io.skjaere.usenet.app.download.DownloadHost
import io.skjaere.usenet.app.download.DownloadHostRules
import io.skjaere.usenet.app.download.DownloadNzbs
import io.skjaere.usenet.app.download.DownloadRunner
import io.skjaere.usenet.app.download.DownloadRunnerDeps
import io.skjaere.usenet.app.download.DownloadStorage
import io.skjaere.usenet.app.download.ServiceDownloadWriter
import io.skjaere.usenet.app.home.SetupState
import io.skjaere.usenet.app.home.SetupStep
import io.skjaere.usenet.app.play.Replay
import io.skjaere.usenet.app.play.UsableRoutes
import io.skjaere.usenet.app.play.playCandidate
import io.skjaere.usenet.app.search.IndexerConfigs
import io.skjaere.usenet.app.settings.HubRow
import io.skjaere.usenet.app.settings.privacyPage
import io.skjaere.usenet.app.settings.settingsHub
import io.skjaere.usenet.app.settings.resultsOfOtherKind
import io.skjaere.usenet.app.setup.SavedSources
import io.skjaere.usenet.app.setup.setupSeed
import io.skjaere.usenet.core.StreamOpener
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.Clock
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadDao
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.data.ServiceWithKey
import io.skjaere.usenet.data.TorrentIndexerRecord
import io.skjaere.usenet.data.TorrentIndexerWithKey
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.debrid.ServiceListing
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.releases.ReleaseReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * L192: a build with debrid hidden offers no way in. One gate says whether it shows ([DebridGate.shown]), and each entry
 * point's pure rule takes it: each has its case here, with the gate closed.
 */
class DebridHiddenTest {
    @org.junit.jupiter.api.io.TempDir
    lateinit var dir: File

    @Test
    fun `the gate shows debrid as the build has it`() {
        assertEquals(!BuildConfig.HIDE_DEBRID, DebridGate.shown)
    }

    @Test
    fun `the seam closes or opens the gate while its block runs, then gives the build's answer back`() {
        val built = !BuildConfig.HIDE_DEBRID
        assertEquals("closed", DebridGate.showing(false) { if (DebridGate.shown) "open" else "closed" })
        assertEquals(built, DebridGate.shown)
        DebridGate.showing(true) {
            assertTrue(DebridGate.shown)
            DebridGate.showing(false) { assertFalse(DebridGate.shown) }
            // The inner block's end gives back the outer one's answer, not the build's.
            assertTrue(DebridGate.shown)
        }
        assertEquals(built, DebridGate.shown)
        // A block that fails gives the build's answer back too.
        assertFailsWith<IllegalStateException> { DebridGate.showing(!built) { error("a check that failed") } }
        assertEquals(built, DebridGate.shown)
    }

    /**
     * L192: hidden, AppNav's `debridScreens` puts none of debrid's screens in the app's graph and gives no way into one, so
     * Settings and First run have nothing to open; shown, each way opens its own screen.
     */
    @Test
    fun `hidden, the app's graph has no debrid screen and nothing opens one`() {
        var registered = 0
        val opened = mutableListOf<Any>()
        assertEquals(DebridPages(), debridPages(shown = false, register = { registered++ }, open = { opened += it }))
        assertEquals(0, registered, "a screen in the graph")
        val pages = debridPages(shown = true, register = { registered++ }, open = { opened += it })
        assertEquals(1, registered)
        listOf(pages.torrentIndexers, pages.addTorrentIndexer, pages.services, pages.addService).forEach { assertNotNull(it).invoke() }
        assertEquals(listOf(TorrentIndexersRoute, TorrentIndexerRoute(), DebridServicesRoute, AddServiceRoute), opened)
    }

    /**
     * L209, L192: Set up with your phone's Done closes a torrent indexer form and a service's key form under it as it
     * closes the Usenet forms, and Add a service under a key form (DbTvSignIn 6), only while those are in the graph at all.
     */
    @Test
    fun `hidden, Set up with your phone closes no debrid form, as none is in the graph`() {
        assertEquals(emptyList(), debridPages(shown = false, register = {}, open = {}).setupForms)
        assertEquals(listOf(TorrentIndexerRoute::class, ServiceKeyRoute::class), debridPages(shown = true, register = {}, open = {}).setupForms)
        // Nor goes back past Add a service, as a key form's own Save does (DbTvSignIn 6), while there is none.
        assertEquals(emptyList(), debridPages(shown = false, register = {}, open = {}).setupPickers)
        assertEquals(listOf(AddServiceRoute::class), debridPages(shown = true, register = {}, open = {}).setupPickers)
    }

    /**
     * L192, L209: hidden, the setup page has no Debrid service or Torrent indexer cards, whatever a shown build saved: the
     * seed offers no service (so the page has no section and its server refuses their slots), the TV reads no debrid
     * record for a review, and the hub's line is today's.
     */
    @Test
    fun `hidden, the setup page has no debrid cards and its line is today's`() = kotlinx.coroutines.runBlocking<Unit> {
        val settings = io.skjaere.usenet.app.settings.memorySettings(cipher = io.skjaere.usenet.app.settings.TestCipher())
        settings.saveService("TorBox", "TB-KEY") { io.skjaere.usenet.data.ServiceSignIn("quay.viewer@example.invalid") }
        settings.saveTorrentIndexer(null, "Home Prowlarr", "http://192.168.1.20:9696/3/api", "TP-KEY")
        val hidden = settings.setupSeed(shown = false)
        assertFalse(hidden.debrid)
        assertEquals(emptyList(), hidden.services + hidden.torrentIndexers)
        assertTrue(settings.setupSeed(shown = true).debrid)
        val sources = SavedSources.read(settings, shown = false)
        assertEquals(emptyList(), sources.services + sources.torrentIndexers)
        assertEquals(1, SavedSources.read(settings, shown = true).services.size)
        assertEquals(io.skjaere.usenet.app.settings.PHONE_SETUP_HUB_ROW, io.skjaere.usenet.app.settings.phoneSetupHubLine(shown = false))
        assertFalse("debrid" in io.skjaere.usenet.app.settings.phoneSetupHubLine(shown = false))
    }

    /** L161: the Indexers form's Test names no Torrent indexers screen while there is none. */
    @Test
    fun `hidden, an indexer whose results aren't NZBs is told so without being sent to torrent indexers`() {
        assertEquals("This indexer’s results aren’t NZBs.", resultsOfOtherKind(IndexerKind.Newznab, shown = false))
    }

    /** L192: hidden, torrent indexers are never searched, even with what a shown build saved and a service on. */
    @Test
    fun `hidden, no torrent indexer is searched`() {
        val saved = listOf(TorrentIndexerWithKey(TorrentIndexerRecord("t1", "Home Prowlarr", "http://192.168.1.20:9696/1"), "KP"))
        assertEquals(emptyList(), IndexerConfigs.searchable(emptyList(), saved, serviceOn = true, shown = false))
    }

    /**
     * L254, L224: hidden, a TV's readiness for a phone's play is today's, whatever a shown build saved: a service on and a
     * torrent indexer make no TV ready, its hello says nothing of them, and a Play is refused for the Usenet provider.
     */
    @Test
    fun `hidden, a TV's readiness is today's`() = kotlinx.coroutines.runBlocking<Unit> {
        val settings = io.skjaere.usenet.app.settings.memorySettings()
        settings.saveService("TorBox", "TB-KEY") { io.skjaere.usenet.data.ServiceSignIn("quay.viewer@example.invalid") }
        settings.saveTorrentIndexer(null, "Home Prowlarr", "http://192.168.1.20:9696/3/api", "TP-KEY")
        val setup = io.skjaere.usenet.app.link.TvSetup.read(settings)
        val hidden = io.skjaere.usenet.app.link.TvReadiness.of(setup, shown = false)
        assertFalse(hidden.ready)
        assertEquals(io.skjaere.usenet.link.Rejection.NotReadyProvider, hidden.rejection)
        assertEquals(io.skjaere.usenet.link.LinkMessage.Hello.Ready(provider = false, indexer = false, tmdb = false), hidden.hello)
        assertEquals(io.skjaere.usenet.link.LinkMessage.Hello.Ready(provider = false, indexer = false, tmdb = false), io.skjaere.usenet.app.link.TvReadiness.hellos(settings) { false }.first())
        assertTrue(io.skjaere.usenet.app.link.TvReadiness.of(setup, shown = true).ready, "shown, the same TV plays from its service")
    }

    /** L192: hidden, a service account counts for no gate (searches, plays, Settings' lines), whatever a shown build saved. */
    @Test
    fun `hidden, a service account counts for no gate`() = kotlinx.coroutines.runBlocking<Unit> {
        val saved = listOf(io.skjaere.usenet.data.ServiceWithKey(io.skjaere.usenet.data.ServiceAccount("tb", "TorBox"), "TB-KEY"))
        val registry = ServiceRegistry(accounts = { saved }, markRefused = {}, make = { _, _, _, _, _ -> error("no client is made") })
        assertEquals(emptyList(), registry.on(shown = false))
        assertEquals(emptyList(), registry.usable(shown = false))
        assertEquals(listOf("tb"), registry.on(shown = true).map { it.id })
    }

    /**
     * L192, L165, L194: hidden, what a shown build saved makes no debrid-only user: with no provider, the sheet asks for one
     * in today's words, never that the service is off, and Search asks for an indexer though torrent indexers are saved.
     */
    @Test
    fun `hidden, a user with only a service is told to add a provider, and Search asks for an indexer`() = kotlinx.coroutines.runBlocking<Unit> {
        val saved = listOf(io.skjaere.usenet.data.ServiceWithKey(io.skjaere.usenet.data.ServiceAccount("tb", "TorBox", enabled = false), "TB-KEY"))
        val none = io.skjaere.usenet.app.play.UsableRoutes.of(providerUsable = false, accountsOn = emptyList())
        assertEquals(
            io.skjaere.usenet.app.settings.NoUsableProvider.NoneSaved,
            io.skjaere.usenet.app.play.noUsableSource(none, emptyList(), 0, io.skjaere.usenet.app.play.servicesSaved(saved.map { it.record }, shown = false)),
        )
        assertEquals(
            io.skjaere.usenet.app.settings.NoUsableProvider.ServiceOff,
            io.skjaere.usenet.app.play.noUsableSource(none, emptyList(), 0, io.skjaere.usenet.app.play.servicesSaved(saved.map { it.record }, shown = true)),
        )
        val on = listOf(io.skjaere.usenet.data.ServiceWithKey(io.skjaere.usenet.data.ServiceAccount("tb", "TorBox"), "TB-KEY"))
        val registry = ServiceRegistry(accounts = { on }, markRefused = {}, make = { _, _, _, _, _ -> error("no client is made") })
        val torrents = listOf(TorrentIndexerRecord("t1", "Home Prowlarr", "http://192.168.1.20:9696/1"))
        assertEquals(false, IndexerConfigs.anySearched(emptyList(), torrents, serviceOn = registry.on(shown = false).isNotEmpty()))
    }

    /**
     * L192, L199: hidden, the cache check as the container makes it (over the registry's open accounts) asks no service
     * about a torrent a shown build's search found: it isn't checked, so it never plays.
     */
    @Test
    fun `hidden, the cache check asks no service`() = kotlinx.coroutines.runBlocking<Unit> {
        val saved = listOf(io.skjaere.usenet.data.ServiceWithKey(io.skjaere.usenet.data.ServiceAccount("tb", "TorBox"), "TB-KEY"))
        val registry = ServiceRegistry(accounts = { saved }, markRefused = {}, make = { _, _, _, _, _ -> error("no client is made") })
        val prowlarr = io.skjaere.usenet.newznab.IndexerConfig("p", "Home Prowlarr", "http://192.168.1.20:9696/1", "KP", kind = IndexerKind.Torznab)
        val hash = "1111111111111111111111111111111111111111"
        val torrent = io.skjaere.usenet.newznab.NewznabItem(
            indexerId = "p", guid = "btih:$hash", title = "Quiet.Orbit.S02E05.1080p.WEB.H264-GRP", sizeBytes = 1, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null,
            season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(), infoHash = hash,
        )
        val settled = DebridGate.showing(false) {
            kotlinx.coroutines.runBlocking { DebridCacheCheck({ registry.usable() }).check(prowlarr, listOf(torrent)).toList().flatten() }
        }
        assertEquals(listOf(io.skjaere.usenet.releases.Availability.NotChecked), settled.map { it.availability })
    }

    /**
     * L192, L254 (#318): hidden, what a shown build's plays added to the account is never removed, nor any list read for a
     * new add: the sweep, over the registry's clients as the container makes it, asks for none, and the record waits
     * until it is past its day, then goes with no call.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `hidden, the sweep of what plays added makes no call, and a record goes only past its day`() = kotlinx.coroutines.test.runTest {
        val account = io.skjaere.usenet.data.ServiceAccount("tb", "TorBox", keySavedAt = 1_762_000_000_000)
        val registry = ServiceRegistry(accounts = { listOf(ServiceWithKey(account, "TB-KEY")) }, markRefused = {}, make = { _, _, _, _, _ -> error("no client is made") })
        val adds = DebridAdds(
            File(dir, "debrid/added.json"), accounts = { listOf(account) },
            usable = { id -> registry.usable().firstOrNull { it.account.id == id } }, scope = backgroundScope,
            clock = { testScheduler.currentTime }, io = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        DebridGate.showing(false) {
            val watch = adds.watch(account)
            assertFalse(watch.wanted(), "no whole list read, nothing recorded")
            // As a shown build's play left it.
            watch.added(io.skjaere.usenet.debrid.InfoHash.of("1111111111111111111111111111111111111111"), "42")
            testScheduler.advanceTimeBy(DebridAdds.GRACE.toMillis() + 1_000)
            testScheduler.runCurrent()
            adds.sweep()
            assertEquals(listOf("42"), adds.records().map { it.handle }, "kept, with no call")
            testScheduler.advanceTimeBy(DebridAdds.STALE.toMillis())
            adds.sweep()
            assertEquals(emptyList(), adds.records(), "past its day: dropped with no call")
        }
    }

    /**
     * L192 (DDX-6): hidden, a Resume of a torrent a shown build played searches again without it. Its routes are the
     * accounts the gate lets be on, none, so the play's cut leaves it out with a provider usable, and the episode is
     * searched for as when a service no longer has it; nothing of it goes to the account a shown build saved.
     */
    @Test
    fun `hidden, a Resume of a torrent searches for the episode again, without it`() = kotlinx.coroutines.runBlocking<Unit> {
        val saved = listOf(io.skjaere.usenet.data.ServiceWithKey(io.skjaere.usenet.data.ServiceAccount("tb", "TorBox"), "TB-KEY"))
        val registry = ServiceRegistry(accounts = { saved }, markRefused = {}, make = { _, _, _, _, _ -> error("no client is made") })
        val hash = "1111111111111111111111111111111111111111"
        val torrent = io.skjaere.usenet.data.RememberedRelease(
            "p", "btih:$hash", "Quiet.Orbit.S02.1080p.WEB.H264-GRP", null, 1, "btih:$hash", "Quiet.Orbit.S02E05.1080p.WEB.H264-GRP.mkv", 900, source = "torrent",
        )
        // As the play gives a remembered torrent its routes and cuts its releases, with the gate closed.
        val accountsOn = registry.on(shown = false).map { it.id }
        val routes = io.skjaere.usenet.app.play.UsableRoutes.of(providerUsable = true, accountsOn = accountsOn)
        val remembered = io.skjaere.usenet.app.play.Replay.onAccounts(io.skjaere.usenet.app.play.Replay.candidate(torrent), accountsOn)
        assertEquals(emptyList(), routes.cut(listOf(remembered).map(routes::narrow)) { it.playCandidate })

        val state = io.skjaere.usenet.data.WatchState(
            io.skjaere.usenet.data.WatchKey.episode(900002, 2, 5), io.skjaere.usenet.data.WatchKind.Episode, showTmdbId = 900002, season = 2, episode = 5,
            title = "Quiet Orbit S02E05", positionMs = 900_000, durationMs = 2_700_000, lastPlayedAt = 10, release = torrent, updatedAt = 10,
        )
        var searched: io.skjaere.usenet.app.play.Retry? = null
        // The play of it has nothing left to try, as the pipeline's cut says.
        io.skjaere.usenet.app.play.ResumePlan.of(state).run(
            play = { request -> routes.cut(request.candidates.map { routes.narrow(io.skjaere.usenet.app.play.Replay.onAccounts(it, accountsOn)) }) { it.playCandidate }
                .let { left -> if (left.isEmpty()) io.skjaere.usenet.app.play.PlayState.Failed(request.title, request.subtitle, listOf(request.title to "No other release to try"), notTried = 0) else null } },
            searchEpisode = { _, retry -> searched = retry },
        )
        assertEquals(setOf("btih:$hash"), assertNotNull(searched).failed)
    }

    /**
     * L192, L194 (DDC-2b): hidden, a Resume of a torrent remembered from a saved indexer whose "Use with your debrid service"
     * is off names no service: the switched-off set is empty while debrid is hidden, so nothing is left out as not for the
     * service, and with no account on the torrent has no route and nothing is tried, its hash sent nowhere ("No other
     * release to try", as before). Shown, the same torrent is left out as not used with the service.
     */
    @Test
    fun `hidden, a remembered torrent of a switched-off indexer is left out with no debrid words`() = kotlinx.coroutines.runBlocking<Unit> {
        val saved = AppSettings(
            torrentIndexers = listOf(TorrentIndexerRecord("q", "Quarry", "http://192.168.1.20:9696/2", useWithService = false)),
            services = listOf(ServiceAccount("tb", "TorBox")),
        )
        val registry = ServiceRegistry(
            accounts = { listOf(ServiceWithKey(ServiceAccount("tb", "TorBox"), "TB-KEY")) }, markRefused = {}, make = { _, _, _, _, _ -> error("no client is made") },
        )
        val hash = "2222222222222222222222222222222222222222"
        val torrent = RememberedRelease(
            "q", "btih:$hash", "Quiet.Orbit.S02.1080p.WEB.H264-GRP", null, 1, "btih:$hash", "Quiet.Orbit.S02E05.1080p.WEB.H264-GRP.mkv", 900, source = "torrent",
        )
        // As the play asks it, the gate read where the play reads it: what it tries, and why it leaves the rest out.
        suspend fun play(shown: Boolean): Pair<List<String>, List<ReleaseReason>> = DebridGate.showing(shown) {
            val offService = IndexerConfigs.offService(saved)
            val accountsOn = registry.on().map { it.id }
            val routes = UsableRoutes.of(providerUsable = true, accountsOn = accountsOn)
            val (tried, left) = Replay.toTry(emptyList(), torrent, stored = true, first = true, emptySet(), accountsOn, offService)
            routes.cut(tried.map(routes::narrow)) { it.playCandidate }.map { it.key } to left.mapNotNull { Replay.leftOut(it, offService) }
        }
        assertEquals(emptyList<String>() to emptyList(), play(shown = false))
        assertEquals(emptyList<String>() to listOf<ReleaseReason>(ReleaseReason.NotForService), play(shown = true))
    }

    /**
     * L422: the downloads' one lookup of an account's service (Resolve's, their writer's and their holds') gives none while
     * debrid is hidden, whatever a shown build saved, and makes no client; shown, it gives the account's while it is on.
     */
    @Test
    fun `hidden, the downloads' lookup gives no account's service`() = kotlinx.coroutines.runBlocking<Unit> {
        var made = 0
        val service = object : DebridService {
            override val kind = ServiceKind.TorBox
            override suspend fun test() = error("not asked")
            override suspend fun check(hashes: List<InfoHash>) = error("not asked")
            override suspend fun prepare(hash: InfoHash, pick: suspend (ServiceListing) -> ServiceFile?) = error("not asked")
            override suspend fun link(prepared: Prepared, file: ServiceFile) = error("not asked")
        }
        var saved = listOf(ServiceWithKey(ServiceAccount("tb", "TorBox"), "TB-KEY"))
        val registry = ServiceRegistry(accounts = { saved }, markRefused = {}, make = { _, _, _, _, _ -> service.also { made++ } })
        assertEquals(null, registry.gated("tb", shown = false))
        assertEquals(0, made, "no client made while hidden")
        assertNotNull(registry.gated("tb", shown = true))
        saved = listOf(ServiceWithKey(ServiceAccount("tb", "TorBox", enabled = false), "TB-KEY"))
        assertEquals(null, registry.gated("tb", shown = true), "switched off")
        saved = listOf(ServiceWithKey(ServiceAccount("tb", "TorBox", keyRefused = true), "TB-KEY"))
        assertEquals(null, registry.gated("tb", shown = true), "its key refused")
    }

    /**
     * L422: at the app's start in a build that hides debrid, each torrent download a build that showed it left still to
     * finish fails "Not available in this version", with no call of any kind (Resolve, the writer and the services all
     * fail the case if asked): queued, under way, paused (its hold on the queue passed on, as a cancel passes it) and
     * waiting with each debrid reason, its listing's copy gone. A Usenet row, a finished torrent row and a failed one stay as
     * they were, and nothing changes while debrid shows.
     */
    @Test
    fun `hidden, the start fails the torrent downloads a shown build left, with no call`() = kotlinx.coroutines.test.runTest {
        val dao = Rows()
        val storage = Places()
        val runner = DownloadRunner(
            DownloadRunnerDeps(
                downloads = dao, storage = storage, resolve = { error("no resolve at the start") },
                opener = StreamOpener { _, _, _ -> error("no stream at the start") },
                missing = { _, _, _ -> }, inUse = { emptyList() }, lastExit = { null }, television = false,
                host = { DownloadHost.Job }, network = MutableStateFlow(true),
                clock = Clock { 1_800_000_000_000L },
                serviceWriter = ServiceDownloadWriter(
                    accounts = { error("no service asked") }, keyRefused = { error("no service asked") },
                    listings = DebridListingStore(File(dir, "listings.json")), accountsOn = { error("no service asked") },
                    streaming = MutableStateFlow(false), folder = storage,
                ),
            ),
            backgroundScope,
        )
        val torrent = DownloadCandidate("t1", "btih:1111111111111111111111111111111111111111", "Quiet.Orbit.S02E05.1080p.WEB.H264-GRP", source = "torrent")
        val usenet = DownloadCandidate("i1", "qo-s02e05", "Quiet.Orbit.S02E05.1080p.WEB.H264-WREN")
        val release = RememberedRelease("t1", torrent.guid, torrent.title, null, 0, torrent.guid, "Quiet.Orbit.S02E05.1080p.WEB.H264-GRP.mkv", 900, source = "torrent")
        fun row(id: String, position: Long, state: DownloadState, reason: String? = null, candidates: List<DownloadCandidate> = listOf(torrent), resolved: Boolean = false) =
            Download(
                id = id, watchKey = "episode:900002:2:$position", kind = DownloadKind.Episode, title = "Quiet Orbit", showTmdbId = 900002,
                season = 2, episode = position.toInt(), state = state, reason = reason, position = position, candidates = candidates,
                release = release.takeIf { resolved }, size = 900L.takeIf { resolved }, createdAt = 1, updatedAt = 1,
            )
        val left = listOf(
            row("queued", 1, DownloadState.Queued),
            row("running", 2, DownloadState.Running, resolved = true),
            row("paused", 3, DownloadState.Paused, resolved = true),
            row("check", 4, DownloadState.Waiting, DownloadHostRules.CHECK_SERVICE),
            row("reach", 5, DownloadState.Waiting, DownloadHostRules.CANT_REACH_SERVICE, resolved = true),
            row("limit", 6, DownloadState.Waiting, DownloadHostRules.SERVICE_LIMIT),
            row("stream", 7, DownloadState.Running, DownloadHostRules.WAITS_FOR_STREAM, resolved = true),
        )
        val stays = listOf(
            row("usenet", 8, DownloadState.Queued, candidates = listOf(usenet)),
            row("both", 9, DownloadState.Queued, candidates = listOf(torrent, usenet)),
            row("done", 10, DownloadState.Done, resolved = true),
            row("failed", 11, DownloadState.Failed, "This release is no longer cached", resolved = true),
        )
        (left + stays).forEach { dao.insert(it) }

        assertEquals(emptyList(), runner.failHiddenTorrents(shown = true))
        assertEquals(left + stays, (left + stays).map { dao.get(it.id) })

        assertEquals(left.map { it.id }.toSet(), runner.failHiddenTorrents(shown = false).toSet())
        left.forEach { row ->
            val now = assertNotNull(dao.get(row.id))
            assertEquals(DownloadState.Failed to DownloadHostRules.NOT_IN_THIS_VERSION, now.state to now.reason, row.id)
            assertTrue("drop source ${row.id}" in storage.events, storage.events.toString())
        }
        // The pause held the queue: the next that would run holds it now, as Resume asks.
        assertEquals(DownloadState.Paused, dao.get("usenet")?.state)
        assertEquals(stays.drop(1), stays.drop(1).map { dao.get(it.id) })
    }

    /** The downloads' table in memory, ordered as Room's queries order them. */
    private class Rows : DownloadDao {
        private val rows = MutableStateFlow<Map<String, Download>>(emptyMap())
        private fun sorted(filter: (Download) -> Boolean) =
            rows.map { all -> all.values.filter(filter).sortedWith(compareBy({ it.position }, { it.createdAt })) }

        override fun queue() = sorted { it.state != DownloadState.Done && it.state != DownloadState.Deleting }
        override fun all() = sorted { true }
        override suspend fun get(id: String) = rows.value[id]
        override fun done() = sorted { it.state == DownloadState.Done }
        override suspend fun doneFor(watchKey: String) = rows.value.values.firstOrNull { it.state == DownloadState.Done && it.watchKey == watchKey }
        override fun forShow(showTmdbId: Int) = sorted { it.showTmdbId == showTmdbId }
        override fun forMovie(movieTmdbId: Int) = rows.map { all -> all.values.firstOrNull { it.movieTmdbId == movieTmdbId } }
        override suspend fun insert(download: Download) = rows.update { it + (download.id to download) }
        override suspend fun update(download: Download) = rows.update { if (download.id in it) it + (download.id to download) else it }
        override suspend fun progress(id: String, safeOffset: Long, gaps: List<DownloadGap>, updatedAt: Long) =
            change(id) { it.copy(safeOffset = safeOffset, gaps = gaps, updatedAt = updatedAt) }
        override suspend fun setState(id: String, state: DownloadState, reason: String?, updatedAt: Long) =
            change(id) { it.copy(state = state, reason = reason, updatedAt = updatedAt) }
        override suspend fun move(id: String, position: Long) = change(id) { it.copy(position = position) }
        override suspend fun delete(id: String) = rows.update { it - id }
        private fun change(id: String, block: (Download) -> Download) =
            rows.update { all -> all[id]?.let { all + (id to block(it)) } ?: all }
    }

    /** The downloads' places on the device: what the start drops of them, and nothing else asked. */
    private class Places : DownloadRunner.Storage {
        val events = mutableListOf<String>()
        override suspend fun add(row: Download, grab: DownloadStorage.Grab?, copy: DownloadNzbs.Copy?) = error("not asked")
        override suspend fun addAll(rows: List<Download>, putBack: List<Download>, copy: DownloadNzbs.Copy?) = error("not asked")
        override suspend fun keepGrab(id: String, grab: DownloadStorage.Grab) = error("not asked")
        override suspend fun open(id: String, fileName: String, size: Long) = error("not asked")
        override suspend fun finish(id: String, fileName: String) = error("not asked")
        override fun relativePath(id: String, fileName: String) = error("not asked")
        override suspend fun deleteVideo(id: String) = error("not asked")
        override suspend fun dropSource(id: String) {
            events += "drop source $id"
        }
        override suspend fun delete(id: String) = error("not asked")
        override suspend fun reconcile(held: (String) -> Boolean) = Unit
        override fun volumeThere() = true
        override suspend fun sweepPacks(removed: Set<String>) {
            events += "sweep packs"
        }
        override val copies = MutableStateFlow(emptyList<DownloadNzbs.Meta>())
        override suspend fun keepListing(id: String, entry: DebridListingStore.Entry) = error("not asked")
        override suspend fun listing(id: String) = error("not asked")
        override suspend fun markRestarted(id: String) = error("not asked")
        override suspend fun restarted(id: String) = error("not asked")
    }

    /** L192: hidden, Settings has no Torrent indexers row, on a phone or a TV, though the app's graph would open one. */
    @Test
    fun `hidden, settings has no torrent indexers row`() {
        listOf(false, true).forEach { television ->
            assertFalse(HubRow.TorrentIndexers in settingsHub(television, setOf(HubRow.TorrentIndexers), shown = false).flatMap { it.rows })
            assertTrue(HubRow.TorrentIndexers in settingsHub(television, setOf(HubRow.TorrentIndexers), shown = true).flatMap { it.rows })
        }
    }

    /** L192: hidden, Settings has no Debrid services row, on a phone or a TV, though the app's graph would open one. */
    @Test
    fun `hidden, settings has no debrid services row`() {
        listOf(false, true).forEach { television ->
            assertFalse(HubRow.DebridServices in settingsHub(television, setOf(HubRow.DebridServices), shown = false).flatMap { it.rows })
            assertTrue(HubRow.DebridServices in settingsHub(television, setOf(HubRow.DebridServices), shown = true).flatMap { it.rows })
        }
    }

    /** L211, L192: hidden, the Privacy page leaves out the policy's debrid part and the opening's words naming it. */
    @Test
    fun `hidden, the privacy page leaves out the debrid part and the opening's words`() {
        val policy = File("src/main/assets/privacy.txt").readText()
        fun page(shown: Boolean) = DebridGate.showing(shown) { privacyPage(policy, seeded = false) }
        val heading = "Your torrent indexers and debrid services"
        assertFalse(page(shown = false).any { it.heading == heading }, "hidden, the debrid part")
        assertFalse("torrent indexers" in page(shown = false).first().paragraphs.single(), "hidden, the opening's words")
        assertTrue(page(shown = true).any { it.heading == heading })
        assertTrue("torrent indexers and debrid services" in page(shown = true).first().paragraphs.single())
        // L421's words for a download's torrent, as the rest of the part: shown, said; hidden, nowhere.
        val download = listOf("play or download asks them", "a download finishes", "debrid")
        assertTrue(download.all { words -> page(shown = true).flatMap { it.paragraphs }.any { words in it } })
        assertTrue(download.none { words -> page(shown = false).flatMap { it.paragraphs }.any { words in it } }, "hidden, a debrid word")
    }

    /** L192: hidden, First run shows only Usenet's way (today's screen), and a saved torrent pair finishes nothing. */
    @Test
    fun `hidden, first run offers no debrid way`() {
        val hidden = SetupState(emptyList(), hasIndexer = false, hasTmdbKey = false, indexerSkipped = false, services = listOf("TorBox"), hasTorrentIndexer = true, shown = false)
        assertEquals(listOf(SetupStep.Provider, SetupStep.Indexer, SetupStep.Metadata), hidden.rows.map { it.step })
        assertEquals(null, hidden.alternative)
        assertFalse(hidden.complete)
        val shown = hidden.copy(shown = true)
        assertTrue(shown.complete)
        assertTrue(SetupStep.Service in SetupState(emptyList(), false, false, false, shown = true).rows.map { it.step })
    }
}
