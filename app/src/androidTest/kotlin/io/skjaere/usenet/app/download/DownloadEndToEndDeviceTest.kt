package io.skjaere.usenet.app.download

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.ShowRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.exists
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.scrolledTo
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.show
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitFor
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitTill
import io.skjaere.usenet.app.engineOnSavedProviders
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.read
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.player.PlayUris
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.util.concurrent.ConcurrentHashMap

/**
 * A download as a user makes one and watches it offline, end to end on a phone (L338, L353, L356, L358, L372): the fake
 * indexer's Harbor Lights (10.0.2.2:8089) as the only indexer and the mock NNTP server (10.0.2.2:1119) as the only
 * provider, saved as a user saves them. Its Show page's ⋮ on S01E01, Download; the sheet's pick, Download; the host runs
 * it as Android schedules it, its notification saying how it goes, then that it is done; done on the Downloads page;
 * Wi-Fi and mobile data off; played from the show's downloads, from its file, to the end; the watched mark saved.
 * The debug build's throttle holds the writer to a few hundred KB a second, so the download lasts long enough to be seen.
 *
 * Put back after, whatever happened: Wi-Fi on and mobile data as it was, POST_NOTIFICATIONS as it was, the throttle's file
 * removed, the download's row and files deleted and its notifications cancelled, the downloads' jobs cancelled, the
 * episode unwatched and its release forgotten, the settings as they were and the engine back on the saved providers. The
 * TMDB seam answers for the invented show and refuses anything else.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadEndToEndDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its window's focus. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

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
    private var wifiOff = false
    private var dataWas: String? = null
    private val throttle = File(File(context.filesDir, DownloadStorage.DIR), DownloadDebug.THROTTLE)
    private val watching = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeFalse("downloads never run on a TV (L369)", container.television)
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        // The debug build's start saves its dev settings: before the test reads or takes any.
        withTimeout(SEEDING_MILLIS) { container.devSettingsSaved.await() }
        cancelJobs()
        saved = container.settings.settings.first()
        val settings = container.settings
        // The mock alone, as a user saves a provider, the others hidden (their passwords kept); the fake indexer alone.
        settings.restore(saved.copy(providers = saved.providers.map { it.copy(deleted = true) }))
        mock = settings.saveProvider(ProviderRecord("", "Mock (download end to end)", "10.0.2.2", 1119, useTls = false, connections = 4), "")
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        settings.torrentIndexers.first().filter { it.enabled }.forEach { settings.setTorrentIndexerEnabled(it.id, false) }
        settings.services.first().filter { it.enabled }.forEach { settings.setServiceEnabled(it.id, false) }
        settings.saveIndexer(FAKE_INDEXER, "Fake (download end to end)", "http://10.0.2.2:8089", "fake-indexer-key")
        // Whatever network the emulator calls its Wi-Fi, and not on a charger only.
        settings.setDownloadsUnmeteredOnly(false)
        settings.setDownloadsWhileCharging(false)
        engineSetup("the engine on the mock alone") { container.engine.configuredProviders.first { it == listOf(mock) } }
        notificationsWere = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) shell("pm grant $pkg ${Manifest.permission.POST_NOTIFICATIONS}")
        throttle.parentFile?.mkdirs()
        throttle.writeText("$THROTTLE")
        tmdb = FakeTmdb(shows = mapOf(SHOW to harborLights), seasons = { id, n -> season.takeIf { id == SHOW && n == 1 } }, strict = true)
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
        watching.forEach { it.cancel() }
        container.playback.cancel()
        back { cancelJobs() }
        throttle.delete()
        val manager = context.getSystemService(NotificationManager::class.java)
        mine().forEach { row ->
            back { container.downloadStorage.delete(row.id) }
            back { manager.cancel(row.id, DownloadNotifications.DONE_ID) }
            back { row.release?.let { container.library.forgetRelease(row.watchKey, it) } }
        }
        // The show's finished episodes are said in its one notification (L437).
        back { manager.cancel(DownloadNotice.showTag(SHOW), DownloadNotifications.DONE_ID) }
        back { manager.cancel(DownloadNotifications.ONGOING_ID) }
        back { manager.cancel(DownloadNotifications.STATUS_ID) }
        back { container.library.markEpisodes(listOf(EpisodeRef(SHOW, 1, 1, WatchTitle.episode("Harbor Lights", 1, 1))), watched = false) }
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
        if (wifiOff) back { shell("svc wifi enable") }
        dataWas?.let { was -> back { shell(if (was == "1") "svc data enable" else "svc data disable") } }
        if (wifiOff) back { waitFor("a validated network for the next test", BACK_MILLIS) { if (container.validatedNetwork.value) Unit else null } }
        failures.firstOrNull()?.let { first -> failures.drop(1).forEach(first::addSuppressed); throw first }
    }

    @Test
    fun downloadedFromItsShowPageWatchedOfflineFromItsDownloads() = runBlocking<Unit> {
        lateinit var nav: NavHostController
        compose.show(context) {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        compose.waitFor(hasText("Search your indexers"), 15_000)
        compose.runOnIdle { nav.navigate(ShowRoute(SHOW)) }

        // The Show page's ⋮ on S01E01: Download, then the sheet's pick.
        val more = hasContentDescription("More for episode 1")
        compose.waitFor(more, 15_000)
        compose.onNode(more).performClick()
        compose.waitFor(hasText("Download") and hasClickAction())
        compose.onNode(hasText("Download") and hasClickAction()).performClick()
        compose.waitTill("the sheet's pick", SEARCH_MILLIS) { compose.exists(hasText(DownloadSheetModel.BEST)) }
        assertTrue("the sheet says when it starts", compose.exists(hasText("Starts now")))
        val notifications = watchNotifications()
        compose.onNode(hasText(DownloadSheetModel.TITLE) and hasClickAction()).performClick()
        compose.waitTill("the sheet to close") { !compose.exists(hasText(DownloadSheetModel.BEST)) }

        // Queued with the releases that fit, and run by the host Android schedules, to done.
        val queued = waitFor("the download queued") { runBlocking { mine() }.singleOrNull() }
        assertEquals(WatchKey.episode(SHOW, 1, 1), queued.watchKey)
        assertTrue("the row keeps no torrent", queued.candidates.all { it.indexerId == FAKE_INDEXER })
        val done = waitFor("the download done", DOWNLOAD_MILLIS) {
            val row = runBlocking { container.data.downloads.get(queued.id) }
            if (row?.state == DownloadState.Failed) throw AssertionError("the download failed: ${row.reason}, ${row.tried.size} tried")
            row?.takeIf { it.state == DownloadState.Done }
        }
        assertTrue("its file is there", container.downloadStorage.file(done)?.isFile == true)
        // Its notification while it downloaded, then the finished one: its show's (L437).
        assertTrue("no notification while it downloaded: ${notifications.keys}", notifications.keys.any { it.first == DownloadNotice.DOWNLOADS_CHANNEL })
        waitFor("the finished notification") { activeNotifications().firstOrNull { it.tag == DownloadNotice.showTag(SHOW) && it.id == DownloadNotifications.DONE_ID } }

        // Done on the Downloads page, as the show's row.
        compose.runOnIdle { nav.navigate(DownloadsRoute()) }
        // Section headers are said in capitals.
        compose.waitFor(hasText(DownloadsPage.DOWNLOADED, ignoreCase = true))
        assertTrue(compose.scrolledTo(hasText("Harbor Lights") and hasClickAction()))

        // No network: Wi-Fi and mobile data off.
        offline()

        // Played from the show's downloads, from its file, to the end; the watched mark saved.
        compose.onNode(hasText("Harbor Lights") and hasClickAction()).performClick()
        compose.waitFor(hasText("Episode 1") and hasClickAction())
        val launches = watchLaunches(done.watchKey)
        compose.onNode(hasText("Episode 1") and hasClickAction()).performClick()
        val launch = waitFor("the file's play", LAUNCH_MILLIS) { launches.values.firstOrNull() }
        assertTrue("S01E01 plays from ${launch.uri}", PlayUris.parse(launch.uri) is PlayUris.Target.Download)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            val duration = waitFor("the player to know the duration", PLAYER_MILLIS) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
            scenario.onActivity { it.player!!.seekTo(duration - END_MS) }
            waitFor("the play to end", PLAYER_MILLIS) { scenario.read { it.player?.playbackState?.takeIf { s -> s == Player.STATE_ENDED } } }
        }
        val state = waitFor("the watched mark") { runBlocking { container.library.state(done.watchKey).first() }?.takeIf { it.watched } }
        assertTrue(state.watched)
        assertFalse("offline throughout the play", container.validatedNetwork.value)
    }

    /** The downloads of the test's episode, from the table now. */
    private suspend fun mine(): List<Download> = container.data.downloads.all().first().filter { it.watchKey == WatchKey.episode(SHOW, 1, 1) }

    /** The app's notifications seen from now on, by channel and id, gathered every 100 ms until the test ends. */
    private fun watchNotifications(): Map<Pair<String, Int>, Boolean> {
        val seen = ConcurrentHashMap<Pair<String, Int>, Boolean>()
        val scope = CoroutineScope(Dispatchers.Default).also { watching += it }
        scope.launch {
            while (true) {
                activeNotifications().forEach { seen[it.notification.channelId to it.id] = true }
                delay(100)
            }
        }
        return seen
    }

    /** The coordinator's launches for [watchKey] from now on. */
    private fun watchLaunches(watchKey: String): Map<String, PlayerLaunch> {
        val seen = ConcurrentHashMap<String, PlayerLaunch>()
        val scope = CoroutineScope(Dispatchers.Default).also { watching += it }
        scope.launch { container.playback.launches.filter { it.watchKey == watchKey }.collect { seen[it.uri] = it } }
        return seen
    }

    private fun activeNotifications() = context.getSystemService(NotificationManager::class.java).activeNotifications.toList()

    /** No network: Wi-Fi and mobile data off, until Android says none is validated. */
    private suspend fun offline() {
        if (dataWas == null) dataWas = shell("settings get global mobile_data").trim()
        shell("svc data disable")
        wifiOff = true
        shell("svc wifi disable")
        waitFor("no validated network", NETWORK_MILLIS) { if (!container.validatedNetwork.value) Unit else null }
    }

    private fun cancelJobs() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) DownloadJobService.scheduler(context).cancelAll()
    }

    /**
     * POST_NOTIFICATIONS taken back without ending the app's process, as it was before the test: `pm revoke` ends it, the
     * instrumentation with it. Through the platform's test API for it (PermissionManager's, a system API, so found by
     * name), with the shell's permissions, as UidtHostDeviceTest takes it back.
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

    /** Harbor Lights as the fake indexer's catalogue numbers it (mock-seed): nothing real has these ids. */
    private val harborLights = ShowDetails(
        SHOW, "Harbor Lights", null, 2021, null, null, null, "Returning Series", true, 6, listOf(SeasonSummary(1, null, EPISODES, today.minusDays(60), null)),
        lastEpisode = EpisodeSummary(1, EPISODES, "Episode $EPISODES", today.minusDays(20)), nextEpisode = null, ids = ExternalIds(tvdb = 900001, imdb = "tt9000003"),
    )

    private val season = Season(SHOW, 1, null, null, (1..EPISODES).map { Episode(1, it, "Episode $it", null, today.minusDays(60L - 7 * it), 6, null) })

    private companion object {
        const val SHOW = 900002
        const val EPISODES = 4
        const val FAKE_INDEXER = "download-end-to-end-device-test"
        const val REVOKE_RUNTIME_PERMISSIONS = "android.permission.REVOKE_RUNTIME_PERMISSIONS"
        const val REVOKE_WITHOUT_KILL = "android.permission.REVOKE_POST_NOTIFICATIONS_WITHOUT_KILL"

        /** Android's uids a user apart (UserHandle.PER_USER_RANGE). */
        const val PER_USER_RANGE = 100_000

        /** The writer's bytes a second (the debug build's throttle): a few seconds for the episode, long enough to be seen. */
        const val THROTTLE = 256_000L

        /** Where the play is taken to: a few seconds before the end, which it plays through. */
        const val END_MS = 5_000L

        const val SEEDING_MILLIS = 60_000L
        const val SEARCH_MILLIS = 60_000L
        const val DOWNLOAD_MILLIS = 180_000L
        const val LAUNCH_MILLIS = 15_000L
        const val PLAYER_MILLIS = 60_000L
        const val NETWORK_MILLIS = 30_000L
        const val BACK_MILLIS = 90_000L
    }
}
