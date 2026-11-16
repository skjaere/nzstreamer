package io.skjaere.usenet.app.download

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.show
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitFor
import io.skjaere.usenet.app.engineOnSavedProviders
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An indexer's download limit is a wait, not a failure, on a phone (L399, L434): the fake indexer's Harbor Lights
 * (10.0.2.2:8089) as the only indexer, its season 1 queued as the season sheet queues it with nothing found yet (each
 * episode's search facts, no release), and the mock NNTP server (10.0.2.2:1119) as the only provider, saved as a user saves
 * them, as DownloadSeasonEndToEndDeviceTest has them. The coordinator's grab seam refuses every grab as the indexer's 501
 * does (its download limit), so the indexer is searched and nothing is ever grabbed.
 *
 * The first episode's turn searches, writes what it found and is refused its grab: it waits "Your indexer's limit is
 * reached", the Downloads page naming the indexer and its download limit under it, with Resume; nothing counted, no
 * episode failed, the others still queued, its walk and its job ended. Resume on the page asks again at once: refused
 * again, the same wait, its releases tried none, nothing searched again. No Wi-Fi is toggled.
 *
 * Put back after, whatever happened: the coordinator's grab, the season's rows and files, their notifications and the
 * job, POST_NOTIFICATIONS as it was, and the settings as they were, the engine back on the saved providers.
 */
@RunWith(AndroidJUnit4::class)
class DownloadLimitDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val dao = container.data.downloads
    private val pkg = context.packageName
    private lateinit var saved: AppSettings
    private var mock: String? = null
    private var notificationsWere: Boolean? = null
    private val realFetch = PlaybackCoordinator.fetchNzb

    /** The grabs the indexer refused, by guid, in order. */
    private val refused = CopyOnWriteArrayList<String>()

    /** The season's rows, once queued. */
    private val made = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeFalse("downloads never run on a TV (L369)", container.television)
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        // The debug build's start saves its dev settings: before the test reads or takes any.
        withTimeout(SEEDING_MILLIS) { container.devSettingsSaved.await() }
        cancelJobs()
        // A download left by another class would run first, or hold the queue; one of the show's would be passed over.
        val left = dao.all().first().filter { it.state in DownloadRunner.GOING_ON || it.showTmdbId == SHOW }
        if (left.isNotEmpty()) throw AssertionError("${left.size} download(s) still to finish or of the test's show before the test: ${left.map { it.id }}")
        saved = container.settings.settings.first()
        val settings = container.settings
        // The mock alone, as a user saves a provider, the others hidden (their passwords kept); the fake indexer alone.
        settings.restore(saved.copy(providers = saved.providers.map { it.copy(deleted = true) }))
        mock = settings.saveProvider(ProviderRecord("", "Mock (indexer limit)", "10.0.2.2", 1119, useTls = false, connections = 4), "")
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        settings.torrentIndexers.first().filter { it.enabled }.forEach { settings.setTorrentIndexerEnabled(it.id, false) }
        settings.services.first().filter { it.enabled }.forEach { settings.setServiceEnabled(it.id, false) }
        settings.saveIndexer(FAKE_INDEXER, INDEXER_NAME, "http://10.0.2.2:8089", "fake-indexer-key")
        // Whatever network the emulator calls its Wi-Fi, not on a charger only.
        settings.setDownloadsUnmeteredOnly(false)
        settings.setDownloadsWhileCharging(false)
        engineSetup("the engine on the mock alone") { container.engine.configuredProviders.first { it == listOf(mock) } }
        notificationsWere = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) shell("pm grant $pkg ${Manifest.permission.POST_NOTIFICATIONS}")
        // The indexer's 501: its downloads for today used up.
        PlaybackCoordinator.fetchNzb = { _, _, item ->
            refused += item.guid
            throw NewznabException(NewznabError.DownloadLimitReached("Download limit reached"))
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
        back { waitFor("no host under way", 30_000) { if (!container.downloads.walking.value && starts() == 0) Unit else null } }
        val manager = context.getSystemService(NotificationManager::class.java)
        made.forEach { id ->
            back { container.downloadStorage.delete(id) }
            back { manager.cancel(id, DownloadNotifications.DONE_ID) }
        }
        back { manager.cancel(DownloadNotifications.ONGOING_ID) }
        back { manager.cancel(DownloadNotifications.STATUS_ID) }
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
    fun aGrabRefusedForTheIndexersDownloadLimitWaitsCountingNothingAndResumeAsksAgainAtOnce() = runBlocking<Unit> {
        // The season as its sheet queues it with nothing found yet: each episode's facts, searched at its turn.
        val queued = container.downloads.enqueueAll((1..EPISODES).map { episode ->
            DownloadRunner.QueueItem(DownloadTarget.episode(SHOW, "Harbor Lights", 1, episode, seasonEpisodes = EPISODES, runtimeMinutes = 6), emptyList(), search = facts(episode))
        })
        made += queued.map { it.id }
        assertEquals(EPISODES, queued.size)
        val (e1, e2, e3) = queued.sortedBy { it.episode }.map { it.id }

        lateinit var nav: NavHostController
        compose.show(context) {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        compose.waitFor(hasText("Search your indexers"), 15_000)
        compose.runOnIdle { nav.navigate(DownloadsRoute()) }
        compose.waitFor(hasText(DownloadsPage.TITLE))
        assertTrue("the host scheduled from the app in front", container.downloadHosts.schedule(ScheduleCause.Download))

        // S01E01's turn: searched, then refused its grab. A wait, nothing counted, the others still queued.
        val waiting = waitFor("S01E01 waiting on the indexer's limit", SEARCH_MILLIS) { limited(e1) }
        assertEquals("nothing counted", emptyList<String>(), waiting.tried)
        assertTrue("what its turn found, written before the grab", waiting.candidates.isNotEmpty())
        assertEquals("searched once", true, DownloadColumns.search(waiting)?.searched)
        val wait = DownloadColumns.limitWait(waiting) ?: throw AssertionError("no limit wait kept")
        assertEquals(FAKE_INDEXER, wait.indexerId)
        assertEquals(LimitWait.Kind.Download, wait.kind)
        assertNull("a download limit comes with no end", wait.until)
        assertEquals("one grab asked: the indexer refuses its others alike", listOf(waiting.candidates.first().guid), refused.toList())
        waitFor("the walk and its job ended", 30_000) { if (!container.downloads.walking.value && starts() == 0) Unit else null }
        listOf(e2, e3).forEach { assertEquals("S01E0${dao.get(it)?.episode} still queued", DownloadState.Queued, dao.get(it)?.state) }
        assertTrue("no episode failed", season().none { it.state == DownloadState.Failed })

        // The page: the wait's words, the indexer's name and its limit under them, and Resume.
        compose.waitFor(hasText(DownloadHostRules.LIMIT_REACHED))
        compose.waitFor(hasText("$INDEXER_NAME's download limit. It goes on when NZStreamer next opens, or on Resume."))
        val resume = hasText(DownloadsPage.RESUME) and hasClickAction()
        compose.waitFor(resume)

        // Resume asks again at once: refused again, the same wait, nothing counted, nothing searched again.
        val before = waiting.updatedAt
        compose.onNode(resume).performClick()
        waitFor("asked again", SEARCH_MILLIS) { refused.size.takeIf { it >= 2 } }
        val again = waitFor("S01E01 waiting again", SEARCH_MILLIS) { limited(e1)?.takeIf { it.updatedAt > before } }
        assertEquals("nothing counted", emptyList<String>(), again.tried)
        assertEquals("its releases as its turn found them", waiting.candidates, again.candidates)
        assertEquals(listOf(waiting.candidates.first().guid, waiting.candidates.first().guid), refused.toList())
        assertEquals(LimitWait.Kind.Download, DownloadColumns.limitWait(again)?.kind)
        waitFor("the walk and its job ended again", 30_000) { if (!container.downloads.walking.value && starts() == 0) Unit else null }
        listOf(e2, e3).forEach { assertEquals(DownloadState.Queued, dao.get(it)?.state) }
        assertTrue("no episode failed", season().none { it.state == DownloadState.Failed })
    }

    /** Download [id], once it waits on its indexer's limit. */
    private fun limited(id: String): Download? = runBlocking { dao.get(id) }?.also {
        if (it.state == DownloadState.Failed) throw AssertionError("download $id failed: ${it.reason}, ${it.tried.size} tried")
    }?.takeIf { it.state == DownloadState.Waiting && it.reason == DownloadHostRules.LIMIT_REACHED }

    /** The season's downloads, from the table now. */
    private suspend fun season(): List<Download> = dao.all().first().filter { it.id in made }

    /** The search facts the season sheet keeps for episode [episode], as the fake indexer's Harbor Lights is known to TMDB. */
    private fun facts(episode: Int) = SeasonSearch(
        "Harbor Lights", 2021, tvdb = 900001, tmdb = SHOW, imdb = "tt9000003", airDate = LocalDate.now().minusDays(60L - 7 * episode).toString(),
        runtimeMinutes = 6, seasonEpisodes = EPISODES,
    )

    /** How many starts of the job are under way in the process (none below Android 14, where the service hosts). */
    private fun starts(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) DownloadJobService.starts.value else 0

    private fun cancelJobs() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) DownloadJobService.scheduler(context).cancelAll()
    }

    /**
     * POST_NOTIFICATIONS taken back without ending the app's process, as it was before the test: `pm revoke` ends it, the
     * instrumentation with it. Through the platform's test API for it, with the shell's permissions.
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

    private companion object {
        const val SHOW = 900002
        const val EPISODES = 3
        const val FAKE_INDEXER = "download-limit-device-test"
        const val INDEXER_NAME = "Fake (indexer limit)"
        const val REVOKE_RUNTIME_PERMISSIONS = "android.permission.REVOKE_RUNTIME_PERMISSIONS"
        const val REVOKE_WITHOUT_KILL = "android.permission.REVOKE_POST_NOTIFICATIONS_WITHOUT_KILL"

        /** Android's uids a user apart (UserHandle.PER_USER_RANGE). */
        const val PER_USER_RANGE = 100_000

        const val SEEDING_MILLIS = 60_000L
        const val SEARCH_MILLIS = 90_000L
    }
}
