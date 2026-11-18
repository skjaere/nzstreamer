package io.skjaere.usenet.app.debrid

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.NetworkNow
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.SettingsRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadFixtures
import io.skjaere.usenet.app.download.DownloadHost
import io.skjaere.usenet.app.download.DownloadHostRules
import io.skjaere.usenet.app.download.DownloadJobService
import io.skjaere.usenet.app.download.DownloadRequest
import io.skjaere.usenet.app.download.DownloadSheet
import io.skjaere.usenet.app.download.DownloadSheetDeps
import io.skjaere.usenet.app.download.DownloadSheetModel
import io.skjaere.usenet.app.download.DownloadSpace
import io.skjaere.usenet.app.download.DownloadsPageSeeds
import io.skjaere.usenet.app.download.DownloadsScreen
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.read
import io.skjaere.usenet.app.settings.PHONE_SETUP_HUB_ROW
import io.skjaere.usenet.app.settings.phoneSetupHubLine
import io.skjaere.usenet.app.setup.ADDRESS
import io.skjaere.usenet.app.setup.PHONE_SETUP_TITLE
import io.skjaere.usenet.app.setup.PhoneSetupPart
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.tv.setTvContent
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.waitFor as waitForValue
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.debrid.ServiceListing
import io.skjaere.usenet.handoff.SetupPage
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.releases.ReleaseFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.time.LocalDate

/**
 * A build with debrid hidden offers no way in (L192), over what a build that showed it saved earlier: a TorBox account
 * and Home Prowlarr, saved with invented sign-in facts (nothing asks a service). The gate is closed before each test
 * makes the app's screens, as -PhideDebrid=true, or a beta built without -PwithDebrid=true, closes it. Settings has
 * neither debrid row, on a phone or in a TV's layout, whose Set up with your phone line is today's; First run, with no
 * Usenet provider or indexer, shows Usenet's way alone though the torrent pair is saved; the setup page has no debrid
 * card and refuses a form naming one; and a search asks Usenet's indexer alone: no torrent indexer's pill, no release's
 * tag, no cache check at the debrid fake. What was saved stays saved. The device's own sources are taken away for each
 * test and put back after, keys included.
 *
 * Downloads, while hidden (L422): the Download sheet offers no torrent and asks no service about its cache; a torrent row a
 * shown build left, queued or opened again from its listing's copy, fails "Not available in this version" at its turn,
 * nothing asked of the debrid fake; the app's start fails those left waiting on a debrid service, and the Downloads page
 * says so; a finished torrent row plays its file. Every download row is deleted and the downloads' jobs cancelled before
 * and after each test (L464).
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DebridHiddenDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its window's focus. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val settings = container.settings
    private lateinit var saved: AppSettings
    private lateinit var nav: NavHostController
    /** Passwords and API keys by secret name, as they were. */
    private var secrets = emptyMap<String, String>()
    private var account: String? = null
    private var prowlarr: String? = null
    private var fakeBack: (() -> Unit)? = null
    private var endpointsBack: (() -> Unit)? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        // Closed before any settings change below, so what the app works out from them (First run's pairs) is hidden's.
        DebridGate.shownForTests = false
        // The debug build's own settings saved first: else they could land after this test took the settings away.
        container.devSettingsSaved.await()
        downloadsApart()
        saved = settings.settings.first()
        secrets = saved.activeProviders.mapNotNull { p -> settings.provider(p.id)?.password?.let { p.passwordSecret to it } }.toMap() +
            saved.activeIndexers.mapNotNull { i -> settings.indexer(i.id)?.apiKey?.let { i.apiKeySecret to it } }.toMap()
        saved.activeProviders.forEach { settings.deleteProvider(it.id) }
        saved.activeIndexers.forEach { settings.deleteIndexer(it.id) }
        // Hidden, not removed: removing would take their keys from the secret store.
        settings.restore(settings.settings.first().let { s -> s.copy(services = s.services.map { it.copy(deleted = true) }, torrentIndexers = s.torrentIndexers.map { it.copy(deleted = true) }) })
        settings.setIndexerSetupSkipped(false)
        // What a build that showed debrid saved: the torrent pair, which hidden counts for nothing.
        account = settings.saveService(ServiceKind.TorBox.name, "invented-hidden-build-key") { ServiceSignIn(DebridFakes.TORBOX_ACCOUNT) }
        prowlarr = settings.saveTorrentIndexer(null, "Home Prowlarr", DebridFakes.PROWLARR_URL, DebridFakes.TORZNAB_KEY)
    }

    /**
     * Each step whatever the one before it did: a debrid fake that stopped answering, or the check that what a shown
     * build saved stayed saved failing, mustn't leave the gate closed, the clients at the fake, or the device's sources
     * and keys away for the classes after this one.
     */
    @After
    fun restore() = runBlocking<Unit> {
        everyStep(
            { container.playback.cancel() },
            { downloadsApart() },
            { if (fakeBack != null) DebridFakes.control("reset") },
            { fakeBack?.invoke() },
            { endpointsBack?.invoke() },
            { DebridGate.shownForTests = null },
            { putBack() },
        )
    }

    /** No download row left of any test, and none of the downloads' jobs pending (L464). */
    private suspend fun downloadsApart() {
        container.data.downloads.all().first().forEach { container.downloadStorage.delete(it.id) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) DownloadJobService.scheduler(context).cancelAll()
    }

    /** The debrid fake held, reset and every service's client pointed at it for the test: what a call would reach, counted. */
    private fun atTheFake() {
        DebridFakes.assumeDebrid()
        fakeBack = DebridFakes.holdTheFake()
        DebridFakes.control("reset")
        endpointsBack = DebridFakes.pointServicesAtTheFake()
    }

    /** What the debrid fake was asked since its reset: every call by name, and every CDN body. */
    private fun asked(): Pair<Map<String, Int>, Int> {
        val counts = JSONObject(DebridFakes.control("counts"))
        val calls = counts.getJSONObject("calls").let { json -> json.keys().asSequence().associateWith { json.getInt(it) } }
        return calls.filterValues { it > 0 } to counts.getJSONObject("cdnFrom").length()
    }

    /** A torrent row a shown build left for Harbor Lights' S01E0[episode]: queued, or resolved ([resolved]) with its file and size. */
    private fun torrentRow(episode: Int, state: DownloadState = DownloadState.Queued, reason: String? = null, resolved: Boolean = false): Download {
        val key = "btih:$GOOD_HASH"
        val now = System.currentTimeMillis()
        return Download(
            id = Download.newId(), watchKey = WatchKey.episode(DebridSearchFixture.HARBOR_LIGHTS, 1, episode), kind = DownloadKind.Episode,
            title = "Harbor Lights", showTmdbId = DebridSearchFixture.HARBOR_LIGHTS, season = 1, episode = episode, state = state, reason = reason,
            position = episode.toLong(), candidates = listOf(DownloadCandidate(prowlarr ?: "t1", key, GOOD, size = SIZE, source = ReleaseFacts.TORRENT)),
            tried = if (resolved) listOf(key) else emptyList(),
            release = if (resolved) RememberedRelease(prowlarr ?: "t1", key, GOOD, null, 0, key, "$GOOD.mkv", SIZE, source = ReleaseFacts.TORRENT) else null,
            fileName = if (resolved) "$GOOD.mkv" else null, size = if (resolved) SIZE else null, indexerSize = SIZE, createdAt = now, updatedAt = now,
        )
    }

    /**
     * L422: the Download sheet over the fakes while hidden asks Usenet's indexer alone, though a torrent indexer and an
     * account are saved: Usenet's release is offered, no release is tagged as a torrent or cached, and no cache check
     * reaches the debrid fake.
     */
    @Test
    fun hiddenTheDownloadSheetOffersNoTorrent() {
        assumeTrue("the fake indexer isn't reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        atTheFake()
        runBlocking { settings.saveIndexer(DebridSearchFixture.INDEXER_A, "Indexer A", "http://10.0.2.2:8089", "fake-indexer-key") }
        val deps = DownloadSheetDeps.of(container, ask = null, context).copy(
            space = { DownloadSpace(available = 60_000_000_000L, used = 0, queued = 0) },
            rows = { emptyList() },
            prefs = { DownloadPrefs() },
            network = { NetworkNow(usable = true, unmetered = true, wifi = true) },
            schedule = { throw AssertionError("nothing is scheduled") },
        )
        compose.setContent { AppTheme { DownloadSheet(EPISODE, onClose = {}, deps) } }
        waitFor(hasText(DownloadSheetModel.BEST), 60_000)
        assertTrue("Usenet's release offered", exists(hasText(GOOD)))
        listOf("From TorBox", "Torrent, not playable").forEach { assertFalse("a release tagged \"$it\"", exists(hasContentDescription(it))) }
        assertFalse("a release marked cached", exists(hasText("✓ Cached")))
        assertEquals("nothing asked of the debrid fake", emptyMap<String, Int>() to 0, asked())
    }

    /**
     * L422: a torrent row a shown build left, queued, and one opened again from its listing's copy, fail "Not available in
     * this version" at their turns, with no call: the debrid fake, where every client points, counts nothing.
     */
    @Test
    fun hiddenATorrentRowsTurnFailsWithNoCall() = runBlocking<Unit> {
        atTheFake()
        val queued = torrentRow(2)
        val begun = torrentRow(3, resolved = true)
        container.downloadStorage.add(queued)
        container.downloadStorage.add(begun)
        container.downloadStorage.keepListing(begun.id, listingOf(begun))
        withTimeout(WALK_MILLIS) { container.downloads.run(DownloadHost.Service) }
        listOf(queued, begun).forEach { row ->
            val now = container.data.downloads.get(row.id) ?: throw AssertionError("download ${row.id} gone")
            assertEquals(DownloadState.Failed to DownloadHostRules.NOT_IN_THIS_VERSION, now.state to now.reason)
        }
        assertEquals(null, container.downloadStorage.listing(begun.id))
        assertEquals("nothing asked of the debrid fake", emptyMap<String, Int>() to 0, asked())
    }

    /**
     * L422: at the app's start while hidden, the torrent rows a shown build left waiting on a debrid service (or under way,
     * waiting while something streamed) fail "Not available in this version", with no call, and the Downloads page says so;
     * no debrid wait is said.
     */
    @Test
    fun hiddenTheStartFailsTheTorrentRowsLeftWaitingAndThePageSaysSo() = runBlocking<Unit> {
        atTheFake()
        val seeds = DownloadsPageSeeds(context, container)
        val left = listOf(
            torrentRow(1, DownloadState.Waiting, DownloadHostRules.CHECK_SERVICE),
            torrentRow(2, DownloadState.Waiting, DownloadHostRules.CANT_REACH_SERVICE, resolved = true),
            torrentRow(3, DownloadState.Waiting, DownloadHostRules.SERVICE_LIMIT),
            torrentRow(4, DownloadState.Running, DownloadHostRules.WAITS_FOR_STREAM, resolved = true),
        ).map(seeds::seed)
        try {
            assertEquals(left.map { it.id }.toSet(), container.downloads.failHiddenTorrents().toSet())
            left.forEach { row ->
                val now = container.data.downloads.get(row.id) ?: throw AssertionError("download ${row.id} gone")
                assertEquals(DownloadState.Failed to DownloadHostRules.NOT_IN_THIS_VERSION, now.state to now.reason)
            }
            compose.setContent { AppTheme { DownloadsScreen(seeds.deps(), onBack = {}, onSettings = {}, onShow = {}, onOpenMovie = {}) } }
            waitFor("the page's failed rows") { scrolledTo(hasText(DownloadHostRules.NOT_IN_THIS_VERSION, substring = true)) }
            listOf(DownloadHostRules.CHECK_SERVICE, DownloadHostRules.CANT_REACH_SERVICE, DownloadHostRules.SERVICE_LIMIT, DownloadHostRules.WAITS_FOR_STREAM)
                .forEach { assertFalse("\"$it\" said", exists(hasText(it, substring = true))) }
            assertEquals("nothing asked of the debrid fake", emptyMap<String, Int>() to 0, asked())
        } finally {
            seeds.after()
        }
    }

    /** L422: a finished torrent row plays its file while hidden, through the hook, nothing asked of the debrid fake. */
    @Test
    fun hiddenAFinishedTorrentRowPlaysItsFile() = runBlocking<Unit> {
        atTheFake()
        val row = torrentRow(2, DownloadState.Done, resolved = true)
        val bytes = DownloadFixtures.media(MEDIA)
        val name = checkNotNull(row.fileName)
        val storage = container.downloadStorage
        storage.open(row.id, name, bytes.size.toLong()).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        storage.finish(row.id, name)
        val size = bytes.size.toLong()
        val done = row.copy(
            release = row.release?.copy(fileSize = size), size = size, indexerSize = size, safeOffset = size, relativePath = storage.relativePath(row.id, name),
            completedAt = System.currentTimeMillis(),
        )
        storage.add(done)
        waitForValue("the finished torrent row among the finished ones") { container.downloads.done.value[done.watchKey]?.takeIf { it.id == done.id } }
        val launches = CoroutineScope(Dispatchers.Default)
        val launch = try {
            val next = launches.async { container.playback.launches.filter { it.watchKey == done.watchKey }.first() }
            assertTrue("the hook takes it", container.playback.playDownloaded(done.watchKey))
            withTimeout(15_000) { next.await() }
        } finally {
            launches.cancel()
        }
        assertEquals(PlayUris.download(done.id), launch.uri)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            waitForValue("the player playing the file", 60_000) { scenario.read { it.player?.takeIf { p -> p.playbackState == Player.STATE_READY } } }
            assertEquals(launch.uri, scenario.read { it.player?.currentMediaItem?.localConfiguration?.uri?.toString() })
        }
        DebridSearchFixture.forgetPlays(container.library)
        assertEquals("nothing asked of the debrid fake", emptyMap<String, Int>() to 0, asked())
    }

    /** [row]'s listing as a shown build's Resolve kept its copy: the fake account's TorBox listing of the episode. */
    private fun listingOf(row: Download) = DebridListingStore.Entry(
        DebridListingStore.idFor(account ?: "tb", InfoHash.of(GOOD_HASH)), account ?: "tb", GOOD,
        Prepared(InfoHash.of(GOOD_HASH), ServiceListing(listOf(ServiceFile(0, checkNotNull(row.fileName), SIZE, "0"))), ServiceFile(0, checkNotNull(row.fileName), SIZE, "0"), "1"),
    )

    /** The device's sources as they were, keys included; then the check of the torrent pair, read before any of it. */
    private suspend fun putBack() {
        if (!::saved.isInitialized) return
        val services = settings.services.first().filter { !it.deleted }.map { it.id }
        val torrentIndexers = settings.torrentIndexers.first().filter { !it.deleted }.map { it.id }
        // Their keys go with them (restore only puts back the settings document).
        account?.let { settings.signOut(it) }
        prowlarr?.let { settings.deleteTorrentIndexer(it) }
        settings.providers.first().filter { p -> saved.providers.none { it.id == p.id } }.forEach { settings.deleteProvider(it.id) }
        settings.indexers.first().filter { i -> saved.indexers.none { it.id == i.id } }.forEach { settings.deleteIndexer(it.id) }
        settings.restore(saved)
        secrets.forEach { (name, value) -> container.data.secrets.put(name, value) }
        // Still saved, as a shown build left them (as far as setUp got).
        account?.let { assertEquals(listOf(it), services) }
        prowlarr?.let { assertEquals(listOf(it), torrentIndexers) }
        assertEquals(saved, settings.settings.first())
    }

    private fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress(DebridFakes.HOST, port), 2_000) } }.isSuccess

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) { condition() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never: $what", e)
        }
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) = waitFor(matcher.description, timeoutMs) { exists(matcher) }

    /** Scrolls whichever list holds [matcher]'s node to it (a lazy list lays out only what shows) and says whether it is there. */
    private fun scrolledTo(matcher: SemanticsMatcher): Boolean {
        if (exists(matcher)) return true
        val lists = compose.onAllNodes(hasScrollAction())
        repeat(lists.fetchSemanticsNodes().size) { i ->
            if (runCatching { lists[i].performScrollToNode(matcher) }.isSuccess && exists(matcher)) return true
        }
        return exists(matcher)
    }

    private fun click(matcher: SemanticsMatcher) {
        assertTrue("nothing to click for ${matcher.description}", scrolledTo(matcher))
        val node = compose.onNode(matcher)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    /** Settings' rows: the hub's, Sources' among them, as [assertNoDebridRow] reads them. */
    private fun openSettings() {
        compose.runOnIdle { nav.switchTab(SettingsRoute) }
        waitFor(hasText("Usenet providers"))
        assertTrue("Indexers' row", exists(hasText("Indexers")))
        assertTrue("Metadata's row", exists(hasText("Metadata")))
    }

    private fun assertNoDebridRow() {
        assertFalse("a Debrid services row", exists(hasText("Debrid services")))
        assertFalse("a Torrent indexers row", exists(hasText("Torrent indexers")))
    }

    @Test
    fun settingsOnAPhoneHasNoDebridRow() {
        compose.setContent {
            nav = rememberNavController()
            AppTheme { AppNav(nav = nav) }
        }
        openSettings()
        assertNoDebridRow()
    }

    @Test
    fun settingsInATvsLayoutHasNoDebridRowAndThePhoneSetupLineIsTodays() {
        compose.setTvContent {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        openSettings()
        assertNoDebridRow()
        assertTrue("today's line under Set up with your phone", exists(hasText(PHONE_SETUP_HUB_ROW)))
        assertFalse("the line naming the debrid cards", exists(hasText(phoneSetupHubLine(shown = true))))
    }

    /** DbPhoneFirstRun's rule while hidden: the torrent pair saved counts for nothing, and only Usenet's way shows. */
    @Test
    fun firstRunShowsUsenetsWayAloneThoughTheTorrentPairIsSaved() {
        compose.setContent { AppTheme { AppNav() } }
        waitFor(hasText("Set up your sources"))
        assertTrue("Usenet's button", exists(hasText("Add a Usenet provider") and hasClickAction()))
        listOf("Add a debrid service", "Or torrents, through a debrid service", "Debrid service", "Torrent indexer", "Add a torrent indexer").forEach {
            assertFalse("\"$it\" shown", exists(hasText(it, ignoreCase = true)))
        }
        assertFalse("Home past First run", exists(hasText("Search your indexers")))
    }

    /**
     * L209 while hidden: the page a phone opens has no Debrid service or Torrent indexer section, on either of the
     * wizard's steps that would hold one, and a form naming one of their buttons or cards is refused as no page of this
     * TV's sends it: Add a debrid service and the saved account's card on step 1, Add a torrent indexer on step 2, where
     * a build that shows debrid takes each. The page is the wizard: hidden, the saved torrent pair makes nothing playable.
     */
    @Test
    fun theSetupPageHasNoDebridCardAndRefusesAFormNamingOne() {
        val own = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.any { it is Inet4Address && it.isSiteLocalAddress }
        assumeTrue("this device has no private IPv4 address for the page", own)
        compose.setTvContent {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        waitFor(hasText("Set up your sources"))
        click(hasText(PHONE_SETUP_TITLE) and hasClickAction())
        val addressNode = SemanticsMatcher.expectValue(PhoneSetupPart, ADDRESS)
        waitFor(addressNode)
        val address = compose.onNode(addressNode).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }.replace("\n", "")

        fun versionOf(page: String) = Regex("""name="v" value="([0-9]+)"""").find(page)?.groupValues?.get(1) ?: error("no form on the page")
        fun refused(page: String, action: String) =
            assertEquals(action, 400 to SetupPage.rejected, exchange("http://$address", listOf("v" to versionOf(page), "do" to action)))

        val (status, page) = exchange("http://$address", null)
        assertEquals(200, status)
        assertTrue("the wizard", "Step 1 of 4" in page)
        assertFalse("a debrid service section", "Debrid service" in page)
        assertFalse("a torrent indexer section", "Torrent indexer" in page)
        assertTrue("step 1's line that nothing plays, the saved account standing in for nothing", SetupPage.NO_PROVIDER in page)
        refused(page, "add:service")
        refused(page, "open:s1")

        // Step 2, where a build that shows debrid has its Torrent indexers section: its blank provider card goes as it is.
        assertEquals(303, exchange("http://$address", listOf("v" to versionOf(page), "do" to "next")).first)
        val (stepStatus, stepTwo) = exchange("http://$address", null)
        assertEquals(200, stepStatus)
        assertTrue("step 2", "Step 2 of 4" in stepTwo)
        assertFalse("a torrent indexer section", "Torrent indexer" in stepTwo)
        assertFalse("a debrid service section", "Debrid service" in stepTwo)
        assertTrue("step 2's line that nothing is searched, the saved torrent indexer standing in for nothing", SetupPage.NO_INDEXER in stepTwo)
        refused(stepTwo, "add:torrent-indexer")
    }

    /**
     * L192, L166: a search asks Usenet's indexer alone though a torrent indexer and a service account are saved:
     * Indexer A's pill, none of Home Prowlarr's; no release says where it plays from; and no cache check reaches the
     * debrid fake, where the services' clients point for the test.
     */
    @Test
    fun aSearchAsksUsenetsIndexerAloneThoughATorrentIndexerIsSaved() {
        assumeTrue("the fake indexer isn't reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        DebridFakes.assumeDebrid()
        fakeBack = DebridFakes.holdTheFake()
        DebridFakes.control("reset")
        endpointsBack = DebridFakes.pointServicesAtTheFake()
        runBlocking { settings.saveIndexer(DebridSearchFixture.INDEXER_A, "Indexer A", "http://10.0.2.2:8089", "fake-indexer-key") }
        compose.setContent { AppTheme { AppNav() } }
        val searchTab = hasText("Search") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        waitFor(searchTab, 15_000)
        compose.onNode(searchTab).performClick()
        waitFor(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput("harbor lights")
        click(hasContentDescription("Search indexers for “harbor lights”") and hasClickAction())
        val indexerA = hasText("Indexer A · ", substring = true)
        waitFor("Indexer A's pill, answered", 30_000) { exists(indexerA) && !exists(indexerA and hasText("searching", substring = true)) }
        assertFalse("Home Prowlarr's pill", exists(hasText("Home Prowlarr", substring = true)))

        click(hasContentDescription("for S01E02", substring = true) and hasClickAction())
        val good = hasText(GOOD) and hasClickAction()
        waitFor("S01E02's list") { scrolledTo(good) }
        assertEquals("GOOD once, from Usenet", 1, compose.onAllNodes(good).fetchSemanticsNodes().size)
        listOf("From Usenet", "From TorBox", "Torrent, not playable").forEach { assertFalse("a release tagged \"$it\"", exists(hasContentDescription(it))) }
        val calls = JSONObject(DebridFakes.control("counts")).getJSONObject("calls")
        assertEquals("cache checks at the debrid fake", 0 to 0, calls.getInt("torbox checkcached") to calls.getInt("premiumize cache/check"))
    }

    /** A request to the page as a phone's browser makes it: [form] posted urlencoded, else a GET. Its status and body. */
    private fun exchange(url: String, form: List<Pair<String, String>>?): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2_000
            connection.readTimeout = 20_000
            if (form != null) {
                val body = form.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }.toByteArray()
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            status to ((if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /** S01E02 on the shared fake indexer, as Usenet lists it (FakeCatalog), and as a cached torrent on the Torznab fake. */
        const val GOOD = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD"
        const val GOOD_HASH = "1bb5795cb9f72aa684193ddf405249b071b2ba5f"
        const val SIZE = 8_791_696L

        /** A film the player plays: a finished row's file. */
        const val MEDIA = "rate-23976.mkv"

        /** How long a walk of the queue may take here: its rows fail with no call, at once. */
        const val WALK_MILLIS = 30_000L

        /** The Download sheet's episode: the fakes' Harbor Lights S01E02, by their catalog's ids. */
        val EPISODE = DownloadRequest.Episode(
            EpisodePlay(
                showTmdbId = DebridSearchFixture.HARBOR_LIGHTS, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(),
                ids = TvIds(tvdb = 900001, tmdb = DebridSearchFixture.HARBOR_LIGHTS, imdb = "tt9000003"), season = 1, episode = 2,
                episodeName = DebridSearchFixture.EPISODES[1], airDate = LocalDate.now().minusDays(53), runtimeMinutes = 45, seasonEpisodes = 3,
            ),
        )
    }
}
