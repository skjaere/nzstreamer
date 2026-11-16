package io.skjaere.usenet.app.download

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.ShowRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.exists
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.show
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitFor
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitTill
import io.skjaere.usenet.app.engineOnSavedProviders
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.releases.ReleaseKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A season downloaded from one fetch of its pack, end to end on a phone (W114, W119; L392, L394, L406, L431): the fake
 * indexer's Harbor Lights (10.0.2.2:8089) as the only indexer, its season 1's S01E03 only in the season's pack, and the
 * mock NNTP server (10.0.2.2:1119) as the only provider, saved as a user saves them; TMDB's seam for the show.
 *
 * The queue is held first by a paused download of an invented film whose indexer isn't set up, so nothing runs while the
 * season is queued from its row and its sheet and S01E03 is moved to the head. The film's download cancelled, its hold
 * passes to S01E03, and Resume on the Downloads page lets the host run the queue: S01E03's turn searches and grabs the
 * pack, kept for the season; S01E01 and S01E02 come from that copy with no grab and no search. Grabs are counted through
 * the coordinator's seam: one in all. The page says "Queued · from the season pack" of the season's rows meanwhile, and
 * "From the season pack" of the one written from it; the copy goes with the season's last download. No Wi-Fi is toggled.
 * The debug build's throttle holds the writer to a few hundred KB a second, so each episode lasts long enough to be seen.
 *
 * Put back after, whatever happened: the coordinator's grab, the downloads' rows and files, the pack's copy, their
 * notifications and jobs, the throttle's file, POST_NOTIFICATIONS as it was, and the settings as they were, the engine back
 * on the saved providers.
 */
@RunWith(AndroidJUnit4::class)
class DownloadSeasonEndToEndDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val pkg = context.packageName
    private var tmdb: FakeTmdb? = null
    private lateinit var saved: AppSettings
    private var mock: String? = null
    private var notificationsWere: Boolean? = null
    private val throttle = File(File(context.filesDir, DownloadStorage.DIR), DownloadDebug.THROTTLE)
    private val realFetch = PlaybackCoordinator.fetchNzb

    /** The coordinator's grabs, by guid, in order. */
    private val grabbed = CopyOnWriteArrayList<String>()

    /** The rows this test made: the film's, and the season's once queued. */
    private val made = CopyOnWriteArrayList<String>()

    /** The season pack's key, as its copy is kept (L394). */
    private val pack = ReleaseKey.of(FAKE_INDEXER, PACK)

    /** When the test's own rows began: the show's rows from then on are the test's (the season's, once queued). */
    private var since: Long? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeFalse("downloads never run on a TV (L369)", container.television)
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        // The debug build's start saves its dev settings: before the test reads or takes any.
        withTimeout(SEEDING_MILLIS) { container.devSettingsSaved.await() }
        cancelJobs()
        // A download left by another class would run first, or hold the queue; one of the show's season would be passed over.
        val left = container.data.downloads.all().first().filter { it.state in DownloadRunner.GOING_ON || it.showTmdbId == SHOW }
        if (left.isNotEmpty()) throw AssertionError("${left.size} download(s) still to finish or of the test's show before the test: ${left.map { it.id }}")
        since = System.currentTimeMillis()
        saved = container.settings.settings.first()
        val settings = container.settings
        // The mock alone, as a user saves a provider, the others hidden (their passwords kept); the fake indexer alone.
        settings.restore(saved.copy(providers = saved.providers.map { it.copy(deleted = true) }))
        mock = settings.saveProvider(ProviderRecord("", "Mock (season end to end)", "10.0.2.2", 1119, useTls = false, connections = 4), "")
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        settings.torrentIndexers.first().filter { it.enabled }.forEach { settings.setTorrentIndexerEnabled(it.id, false) }
        settings.services.first().filter { it.enabled }.forEach { settings.setServiceEnabled(it.id, false) }
        settings.saveIndexer(FAKE_INDEXER, "Fake (season end to end)", "http://10.0.2.2:8089", "fake-indexer-key")
        // Whatever network the emulator calls its Wi-Fi, not on a charger only, and no Download size: the pack leads.
        settings.setDownloadsUnmeteredOnly(false)
        settings.setDownloadsWhileCharging(false)
        settings.setDownloadSizeCap(null)
        engineSetup("the engine on the mock alone") { container.engine.configuredProviders.first { it == listOf(mock) } }
        notificationsWere = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) shell("pm grant $pkg ${Manifest.permission.POST_NOTIFICATIONS}")
        throttle.parentFile?.mkdirs()
        throttle.writeText("$THROTTLE")
        tmdb = FakeTmdb(shows = mapOf(SHOW to harborLights), seasons = { id, n -> season.takeIf { id == SHOW && n == 1 } }, strict = true)
        PlaybackCoordinator.fetchNzb = { c, indexer, item ->
            grabbed += item.guid
            realFetch(c, indexer, item)
        }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        val failures = mutableListOf<Throwable>()
        suspend fun back(what: suspend () -> Unit) {
            try {
                what()
            } catch (e: Throwable) {
                failures += e
            }
        }
        PlaybackCoordinator.fetchNzb = realFetch
        back { cancelJobs() }
        throttle.delete()
        val manager = context.getSystemService(NotificationManager::class.java)
        val rows = container.data.downloads.all().first().filter { row -> row.id in made || since?.let { row.showTmdbId == SHOW && row.createdAt >= it } == true }
        rows.forEach { row ->
            back { container.downloadStorage.delete(row.id) }
            back { manager.cancel(row.id, DownloadNotifications.DONE_ID) }
            back { row.release?.let { container.library.forgetRelease(row.watchKey, it) } }
        }
        if (since != null) {
            back { container.downloadStorage.packs.folder(pack).deleteRecursively() }
            back { container.downloadStorage.sweepPacks() }
        }
        back { manager.cancel(DownloadNotifications.ONGOING_ID) }
        back { manager.cancel(DownloadNotifications.STATUS_ID) }
        back { tmdb?.close() }
        if (::saved.isInitialized) {
            back {
                mock?.let { container.settings.deleteProvider(it) }
                if (saved.indexers.none { it.id == FAKE_INDEXER } && container.settings.indexer(FAKE_INDEXER) != null) container.settings.deleteIndexer(FAKE_INDEXER)
                container.settings.restore(saved)
                engineOnSavedProviders()
            }
        }
        if (notificationsWere == false && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) back { revokeNotifications() }
        failures.firstOrNull()?.let { first -> failures.drop(1).forEach(first::addSuppressed); throw first }
    }

    @Test
    fun aSeasonGrabsItsPackOnceItsOtherEpisodesComeFromItsCopy() = runBlocking<Unit> {
        // The queue held by a paused download of a film whose indexer isn't set up: nothing runs meanwhile.
        val film = heldFilm()
        lateinit var nav: NavHostController
        compose.show(context) {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        compose.waitFor(hasText("Search your indexers"), 15_000)
        compose.runOnIdle { nav.navigate(ShowRoute(SHOW)) }

        // The season row's Download, then its sheet's.
        val season = hasText("Download season 1") and hasClickAction()
        compose.waitTill("the season's Download", 20_000) { scrolledTo(season) }
        compose.onNode(season).performClick()
        val download = hasText("Download 3 episodes") and hasClickAction()
        compose.waitTill("the season sheet's Download", SEARCH_MILLIS) { compose.exists(download) }
        compose.onNode(download).performScrollTo().performClick()
        compose.waitTill("the sheet to close") { !compose.exists(download) }

        val queued = waitFor("the season queued") { runBlocking { season() }.takeIf { it.size == EPISODES } }
        made += queued.map { it.id }
        assertTrue("each queued with its search facts: $queued", queued.all { it.state == DownloadState.Queued && DownloadColumns.search(it) != null })
        val (e1, e2, e3) = queued.sortedBy { it.episode }.map { it.id }
        assertEquals(DownloadState.Paused, container.data.downloads.get(film)?.state)

        // S01E03, in the pack alone, to the head of the season.
        container.downloads.move(e3, up = true)
        container.downloads.move(e3, up = true)
        assertEquals(listOf(e3, e1, e2), container.data.downloads.queue().first().map { it.id }.filter { it in queued.map(Download::id) })
        // The film's download cancelled: its hold passes to S01E03, the next to run.
        container.downloads.cancel(film)
        assertEquals(DownloadState.Paused, container.data.downloads.get(e3)?.state)

        // Resume on the Downloads page: the held row first, then its host runs the queue.
        compose.runOnIdle { nav.navigate(DownloadsRoute()) }
        val resume = hasText(DownloadsPage.RESUME) and hasClickAction()
        compose.waitFor(resume)
        compose.onNode(resume).performClick()

        // S01E03 written from the pack it grabbed, the season's others queued to come from its copy.
        compose.waitTill("the season's rows from its pack", DOWNLOAD_MILLIS) {
            compose.exists(hasText(DownloadsPage.QUEUED_FROM_PACK)) && compose.exists(hasText(DownloadsPage.FROM_PACK))
        }
        val copy = container.downloadStorage.packs.find(pack) ?: throw AssertionError("no copy of the pack kept for the season")
        assertTrue("the copy leads", copy.meta.leads)

        // All three done, each whole, from one grab in all; the copy gone with the season's last.
        val done = listOf(e3, e1, e2).map { id ->
            waitFor("download $id done", DOWNLOAD_MILLIS) {
                val row = runBlocking { container.data.downloads.get(id) }
                if (row?.state == DownloadState.Failed) throw AssertionError("download $id failed: ${row.reason}, ${row.tried.size} tried")
                row?.takeIf { it.state == DownloadState.Done }
            }
        }
        done.forEach { row ->
            assertEquals("S01E0${row.episode}'s release", PACK, row.release?.guid)
            assertEquals("S01E0${row.episode}'s releases tried", listOf(pack), row.tried)
            assertTrue("S01E0${row.episode} has parts missing", row.gaps.isEmpty())
            val file = container.downloadStorage.file(row) ?: throw AssertionError("no file of S01E0${row.episode}")
            assertEquals("S01E0${row.episode}'s file", row.size, file.length())
            assertTrue("S01E0${row.episode}'s file is its episode's", row.fileName.orEmpty().contains("S01E0${row.episode}"))
        }
        assertEquals("one grab in all", listOf(PACK), grabbed.toList())
        waitFor("the season's copy dropped") { runBlocking { container.downloadStorage.packs.find(pack) }?.let { null } ?: Unit }
        assertFalse("the copy's folder", container.downloadStorage.packs.folder(pack).exists())
    }

    /** A paused download of an invented film, at the queue's end, its release of an indexer that isn't set up. */
    private suspend fun heldFilm(): String {
        val at = System.currentTimeMillis()
        val position = (container.data.downloads.all().first().maxOfOrNull { it.position } ?: -1) + 1
        val row = Download(
            id = Download.newId(), watchKey = WatchKey.movie(FILM), kind = DownloadKind.Movie, title = "Copper Crown", movieTmdbId = FILM, year = 2019,
            state = DownloadState.Paused, position = position,
            candidates = listOf(DownloadCandidate(NO_INDEXER, "cc-held", "Copper.Crown.2019.1080p.WEB.h264-WREN", null, 4_000_000_000L)),
            indexerSize = 4_000_000_000L, createdAt = at, updatedAt = at,
        )
        made += row.id
        container.downloadStorage.add(row)
        return row.id
    }

    /** The season's downloads, from the table now. */
    private suspend fun season(): List<Download> = container.data.downloads.all().first().filter { it.showTmdbId == SHOW && it.season == 1 }

    /** The Show page's own list, not the season chips' row, which scrolls too. */
    private val pageList = hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    /** Scrolls the Show page's list to [matcher] where it has it, and says whether it shows. */
    private fun scrolledTo(matcher: SemanticsMatcher): Boolean {
        compose.waitForIdle()
        try {
            compose.onNode(pageList).performScrollToNode(matcher)
        } catch (e: AssertionError) {
            // Not in the list, or not yet.
        }
        return compose.exists(matcher)
    }

    private fun cancelJobs() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) DownloadJobService.scheduler(context).cancelAll()
    }

    /**
     * POST_NOTIFICATIONS taken back without ending the app's process, as it was before the test: `pm revoke` ends it, the
     * instrumentation with it. Through the platform's test API for it, with the shell's permissions, as DownloadEndToEndDeviceTest takes it back.
     */
    private fun revokeNotifications() {
        val type = Class.forName("android.permission.PermissionManager")
        val permissions = context.getSystemService(type) ?: throw AssertionError("no PermissionManager")
        val revoke = type.getMethod("revokePostNotificationPermissionWithoutKillForTest", String::class.java, Int::class.javaPrimitiveType)
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity(REVOKE_RUNTIME_PERMISSIONS, REVOKE_WITHOUT_KILL)
        try {
            revoke.invoke(permissions, pkg, Process.myUid() / PER_USER_RANGE)
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    private val today = LocalDate.now()

    /** Harbor Lights as the fake indexer's catalogue numbers it (mock-seed), its season 1 the three episodes its pack holds. */
    private val harborLights = ShowDetails(
        SHOW, "Harbor Lights", null, 2021, null, null, null, "Returning Series", true, 6, listOf(SeasonSummary(1, null, EPISODES, today.minusDays(60), null)),
        lastEpisode = EpisodeSummary(1, EPISODES, "Episode $EPISODES", today.minusDays(30)), nextEpisode = null, ids = ExternalIds(tvdb = 900001, imdb = "tt9000003"),
    )

    private val season = Season(SHOW, 1, null, null, (1..EPISODES).map { Episode(1, it, "Episode $it", null, today.minusDays(60L - 7 * it), 6, null) })

    private companion object {
        const val SHOW = 900002
        const val EPISODES = 3
        const val FILM = 990_811
        const val FAKE_INDEXER = "download-season-device-test"

        /** The fake indexer's season pack of season 1, the one release with S01E03 in it. */
        const val PACK = "hl-s01-pack"

        /** An indexer no indexer set up has: the held film's release could never be grabbed. */
        const val NO_INDEXER = "download-season-device-test-gone"
        const val REVOKE_RUNTIME_PERMISSIONS = "android.permission.REVOKE_RUNTIME_PERMISSIONS"
        const val REVOKE_WITHOUT_KILL = "android.permission.REVOKE_POST_NOTIFICATIONS_WITHOUT_KILL"

        /** Android's uids a user apart (UserHandle.PER_USER_RANGE). */
        const val PER_USER_RANGE = 100_000

        /** The writer's bytes a second (the debug build's throttle): a few seconds an episode, long enough to be seen. */
        const val THROTTLE = 256_000L

        const val SEEDING_MILLIS = 60_000L
        const val SEARCH_MILLIS = 60_000L
        const val DOWNLOAD_MILLIS = 180_000L
    }
}
