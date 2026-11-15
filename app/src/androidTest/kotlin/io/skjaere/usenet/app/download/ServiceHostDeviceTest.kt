package io.skjaere.usenet.app.download

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadFixtures.Packing
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.newznab.NzbDownload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The downloads' service on a phone (item 10, L334, L336, L375, L388): the dataSync foreground service that runs the queue
 * on Android 8 to 13, run here on phone34 by the debug build's host override (L336, W116), which the test writes first and
 * removes after. It runs over the test's own NNTP server (LocalNntp, in the app's process, so the network's comings and
 * goings reach the service's own network callback and never the transfer itself), the releases' NZBs through the
 * coordinator's grab (its test seam) from a test indexer saved for it; the episode is the test assets' film, and the debug
 * build's throttle holds the writer to a pace each case can watch.
 * - Started by the visible app, it is in the foreground within 5 s, as a dataSync service with the queue's notification,
 *   and leaves it, the notification with it, once nothing is left to run.
 * - Wi-Fi off with Only on Wi-Fi on: it waits in place, in the foreground, "Waiting for Wi-Fi", the watchdog scheduled for
 *   the network; back on Wi-Fi it goes on from where it stopped, never started again, the watchdog gone, and LocalNntp's
 *   BODY counts show nothing before where it stopped fetched again.
 * - Its partial wake lock is held while bytes flow, and let go while it waits (`dumpsys power`).
 * - The boot and update receiver is on while a download can run (a held queue can't), and off after.
 *
 * Everything is put back after each, whatever happened: Wi-Fi on, mobile data as it was, POST_NOTIFICATIONS granted, the
 * override and the throttle gone, the service stopped, the jobs cancelled, the rows and their files deleted, the receiver
 * off, the settings and the memory of tried releases as they were. The show is invented.
 */
@RunWith(AndroidJUnit4::class)
class ServiceHostDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val dao = container.data.downloads
    private val settings = container.settings
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val defaultScheduler = context.getSystemService(JobScheduler::class.java)
    private val fixtures = DownloadFixtures()
    private val local get() = fixtures.local
    private val realFetch = PlaybackCoordinator.fetchNzb
    private val nzbs = ConcurrentHashMap<String, ByteArray>()
    private val made = mutableListOf<String>()
    private val pkg = context.packageName
    private val folder = File(context.filesDir, DownloadStorage.DIR)

    private lateinit var saved: AppSettings
    private lateinit var remembered: List<TriedRelease>

    /** The trace's lines before the test: its own come after. */
    private var traceFrom = 0

    // What the test changed of the device, so its end puts back exactly that.
    private var wifiOff = false
    private var dataWas: String? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        assertFalse("a phone", container.television)
        // No job, service or walk of another test's.
        cancelJobs()
        stopService()
        noHost()
        shell("pm grant $pkg ${Manifest.permission.POST_NOTIFICATIONS}")
        val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        assertTrue(
            "the service's network: validated Wi-Fi (Wi-Fi ${network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}, validated ${network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)})",
            network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
        // The container's one flow of it, which the runner and its writer follow (L381), says so too, from its callback.
        waitFor("the container's validated network", 10_000) { container.validatedNetwork.value.takeIf { it } }
        saved = settings.settings.first()
        assertTrue("Only on Wi-Fi or Ethernet, as a new install has it (W110)", saved.downloads.unmeteredOnly)
        assertFalse(saved.downloads.whileCharging)
        remembered = container.library.triedReleaseEntries()
        // The candidates' indexer, which the grab's seam stands in for: never asked.
        settings.saveIndexer(INDEXER, "Test indexer (service)", "http://indexer.invalid/api", "test-key")
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(local.provider)))
        }
        PlaybackCoordinator.fetchNzb = { _, _, item -> NzbDownload(nzbs.getValue(item.guid)) }
        throttle(SLOW)
        // The service is the host on phone34 too, by the debug build's override (L336).
        folder.mkdirs()
        File(folder, DownloadDebug.HOST).writeText("service")
        assertTrue("the override read", container.downloadDebug.serviceHost)
        traceFrom = traced().size
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        val failures = mutableListOf<Throwable>()
        fun back(what: () -> Unit) {
            try {
                what()
            } catch (e: Throwable) {
                failures += e
            }
        }
        // The device first, whatever the test got to.
        if (wifiOff) back { shell("svc wifi enable") }
        dataWas?.let { was -> back { shell(if (was == "1") "svc data enable" else "svc data disable") } }
        back { shell("pm grant $pkg ${Manifest.permission.POST_NOTIFICATIONS}") }
        back { File(folder, DownloadDebug.THROTTLE).delete() }
        // Then the app: its service and jobs, its rows and their notifications, the receiver, its seams and settings.
        back { runBlocking { stopService() } }
        back { cancelJobs() }
        back { runBlocking { noHost() } }
        made.forEach { id ->
            back { runBlocking { container.downloadStorage.delete(id) } }
            back { manager.cancel(id, DownloadNotifications.DONE_ID) }
        }
        back { manager.cancel(DownloadNotifications.ONGOING_ID) }
        back { manager.cancel(DownloadNotifications.STATUS_ID) }
        // With nothing of the test's left to run, the runner turns the receiver off.
        back { runBlocking { waitFor("the receiver off after the test", 10_000) { if (!receiverOn()) Unit else null } } }
        back { File(folder, DownloadDebug.HOST).delete() }
        PlaybackCoordinator.fetchNzb = realFetch
        if (::saved.isInitialized) {
            back {
                runBlocking {
                    if (saved.indexers.none { it.id == INDEXER } && settings.indexer(INDEXER) != null) settings.deleteIndexer(INDEXER)
                    settings.restore(saved)
                    container.library.clearTriedReleases()
                    container.library.restoreTriedReleases(remembered)
                }
            }
        }
        back { fixtures.close() }
        if (wifiOff) back { runBlocking { waitFor("Wi-Fi back for the next test", 90_000) { validatedWifi() } } }
        // Off the test's server, which is gone: on the mock, the debug build's saved provider.
        back { runBlocking { engineSetup("the engine back on the mock", endIfStuck = false) { mockOnEngine() } } }
        failures.firstOrNull()?.let { first -> failures.drop(1).forEach(first::addSuppressed); throw first }
    }

    @Test
    fun startedByTheAppItIsInTheForegroundWithinFiveSecondsAndLeavesItWhenDone() = runBlocking<Unit> {
        throttle(MEDIUM)
        val release = queue(1)
        val id = made.single()
        launchApp()
        val foreground = waitFor("the service in the foreground", 10_000) { DownloadService.foreground.value }
        assertTrue("in the foreground ${foreground.afterMillis} ms after its start", foreground.afterMillis <= 5_000)
        assertEquals("a dataSync service", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, foreground.type)
        assertEquals("started by the app", listOf("activity"), serviceStarts())
        assertEquals("no job where the override gives the downloads to the service", emptyList<String>(), mine().filter { "start host=job" in it })

        val downloading = waitFor("its notification as it downloads", 30_000) { active(DownloadNotifications.ONGOING_ID)?.takeIf { text(it).startsWith("Downloading") } }
        assertTrue("the service's own", downloading.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertEquals("Harbor Lights · S04E01", downloading.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(listOf("Pause", "Cancel"), actions(downloading))
        assertEquals(DownloadNotice.DOWNLOADS_CHANNEL, downloading.notification.channelId)
        assertEquals(Notification.VISIBILITY_SECRET, downloading.notification.visibility)

        val done = waitFor("done", 120_000) { runBlocking { dao.get(id) }?.takeIf { it.state == DownloadState.Done || it.state == DownloadState.Failed } }
        assertEquals(done.reason, DownloadState.Done, done.state)
        assertEquals(release.sha256, DownloadFixtures.sha256(container.downloadStorage.file(done) ?: throw AssertionError("no file")))
        // Nothing left to run: it stops, its notification removed with the foreground, and nothing left to say.
        waitFor("the service stopped", 30_000) { if (DownloadService.phase.value == DownloadService.Phase.None) Unit else null }
        assertNull(active(DownloadNotifications.ONGOING_ID))
        assertNull(active(DownloadNotifications.STATUS_ID))
    }

    @Test
    fun offWifiWithWifiOnlyItWaitsInPlaceAndGoesOnWhenWifiIsBack() = runBlocking<Unit> {
        val release = queue(2)
        val id = made.single()
        launchApp()
        // Past a record, so the stop has something before it to keep.
        waitFor("a progress record", 60_000) { runBlocking { dao.get(id) }?.takeIf { it.state == DownloadState.Running && it.safeOffset > 0 } }

        dataWas = shell("settings get global mobile_data").trim()
        shell("svc data enable")
        wifiOff = true
        shell("svc wifi disable")
        val waiting = waitFor("waiting for Wi-Fi", 60_000) { runBlocking { dao.get(id) }?.takeIf { it.state == DownloadState.Waiting } }
        assertEquals(DownloadHostRules.WAITING_FOR_WIFI, waiting.reason)
        // In place: still up and in the foreground, its notification saying what it waits for.
        assertEquals(DownloadService.Phase.Waiting, DownloadService.phase.value)
        val notice = waitFor("its notification as it waits") { active(DownloadNotifications.ONGOING_ID)?.takeIf { text(it).startsWith(DownloadHostRules.WAITING_FOR_WIFI) } }
        assertTrue("still the service's own", notice.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertEquals(listOf("Cancel"), actions(notice))
        val watchdog = waitFor("the watchdog scheduled as it waits") { defaultScheduler.getPendingJob(ServicePlan.WATCHDOG_ID) }
        assertTrue(watchdog.isPersisted)
        // Android 9's getRequiredNetwork says the same; this one is every level's.
        @Suppress("DEPRECATION")
        assertEquals("the watchdog needs Wi-Fi or Ethernet", JobInfo.NETWORK_TYPE_UNMETERED, watchdog.networkType)
        // What each article had been fetched when it stopped.
        val posted = release.posted.single()
        val stoppedAt = waiting.safeOffset
        val before = posted.ids.associateWith { local.bodies[it]?.get() ?: 0 }
        val starts = serviceStarts().size

        throttle(FAST)
        shell("svc wifi enable")
        wifiOff = false
        waitFor("on again on Wi-Fi", 120_000) { runBlocking { dao.get(id) }?.takeIf { it.state != DownloadState.Waiting } }
        assertNull("the watchdog gone with the wait", defaultScheduler.getPendingJob(ServicePlan.WATCHDOG_ID))
        val done = waitFor("done", 120_000) { runBlocking { dao.get(id) }?.takeIf { it.state == DownloadState.Done || it.state == DownloadState.Failed } }
        assertEquals(done.reason, DownloadState.Done, done.state)
        assertEquals("in place: never started again", starts, serviceStarts().size)
        assertEquals("on from where it stopped", stoppedAt, opens(id).last())
        // The transfer's: the reopen prepares the NZB again from the download's own copy (L346), which reads the file's first
        // article as every prepare does.
        val again = posted.ids.drop(1).filter { posted.bytesOf(it).last < stoppedAt && (local.bodies[it]?.get() ?: 0) != before.getValue(it) }
        assertEquals("articles before $stoppedAt fetched again", emptyList<String>(), again)
        assertEquals(release.sha256, DownloadFixtures.sha256(container.downloadStorage.file(done) ?: throw AssertionError("no file")))
    }

    @Test
    fun itsWakeLockIsHeldOnlyWhileBytesFlow() = runBlocking<Unit> {
        queue(3)
        val id = made.single()
        launchApp()
        underWay(id)
        waitFor("the wake lock held while bytes flow", 15_000) { if (wakeLockHeld()) Unit else null }

        dataWas = shell("settings get global mobile_data").trim()
        shell("svc data enable")
        wifiOff = true
        shell("svc wifi disable")
        val waiting = waitFor("waiting for Wi-Fi", 60_000) { runBlocking { dao.get(id) }?.takeIf { it.state == DownloadState.Waiting } }
        waitFor("the wake lock let go while nothing flows", 30_000) { if (!wakeLockHeld()) Unit else null }
        assertEquals("still up, waiting in place", DownloadService.Phase.Waiting, DownloadService.phase.value)

        shell("svc wifi enable")
        wifiOff = false
        underWay(id, waiting.safeOffset + 1, timeoutMs = 120_000)
        waitFor("held again as bytes flow", 15_000) { if (wakeLockHeld()) Unit else null }
    }

    @Test
    fun theBootReceiverIsOnWhileADownloadCanRunAndOffAfter() = runBlocking<Unit> {
        assertEquals("no download of another test's to run", emptyList<String>(), container.downloads.runnable().map { it.id })
        waitFor("the receiver off with nothing to run") { if (!receiverOn()) Unit else null }
        queue(4)
        val id = made.single()
        waitFor("the receiver on while a download can run") { if (receiverOn()) Unit else null }
        // A held queue can't run (L388): nothing to wake at boot.
        container.downloads.pause()
        waitFor("the receiver off while the queue is held") { if (!receiverOn()) Unit else null }
        container.downloads.resume()
        waitFor("on again") { if (receiverOn()) Unit else null }

        throttle(FAST)
        launchApp()
        waitFor("done", 120_000) { runBlocking { dao.get(id) }?.takeIf { it.state == DownloadState.Done } }
        waitFor("the receiver off once nothing can run") { if (!receiverOn()) Unit else null }
    }

    /** Episode [episode] of Harbor Lights' season 4, the test assets' film posted fresh, queued through the app's runner first in line. */
    private suspend fun queue(episode: Int): DownloadFixtures.Release {
        val title = "Harbor.Lights.S04E%02d.720p.WEB.h264-KESTREL".format(episode)
        val release = fixtures.post(Packing.Plain, title, DownloadFixtures.media("chapters.mkv"), partSize = PART, salt = "${System.nanoTime()}")
        val guid = "hl-service-$episode"
        nzbs[guid] = release.nzb
        val row = container.downloads.enqueue(
            DownloadTarget.episode(SHOW, "Harbor Lights", 4, episode, seasonEpisodes = 8), listOf(DownloadCandidate(INDEXER, guid, title, size = release.size)),
        ) ?: throw AssertionError("not queued")
        made += row.id
        // First in line, whatever another test left.
        dao.move(row.id, (dao.all().first().filter { it.id != row.id }.minOfOrNull { it.position } ?: 0) - 100)
        return release
    }

    /** MainActivity opened and closed again: its start starts the service, which then runs on without it. */
    private suspend fun launchApp() {
        val before = serviceStarts().size
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("the service started from the activity", 30_000) { serviceStarts().size.takeIf { it > before } }
        }
    }

    /** Download [id] written, at [bytes] or more, as the runner's state says it. */
    private suspend fun underWay(id: String, bytes: Long = 1, timeoutMs: Long = 60_000): DownloadRunner.Item =
        waitFor("download $id under way at $bytes bytes", timeoutMs) {
            container.downloads.state.value.firstOrNull { it.row.id == id }?.takeIf { it.row.state == DownloadState.Running && it.written >= bytes }
        }

    /** The service stopped, as Android stops it for the app: its walk stops at its safe offset, and it ends as it would. */
    private suspend fun stopService() {
        context.stopService(Intent(context, DownloadService::class.java))
        waitFor("the service stopped", 30_000) { if (DownloadService.phase.value == DownloadService.Phase.None) Unit else null }
    }

    /** No job of the downloads', nor the watchdog. */
    private fun cancelJobs() {
        defaultScheduler.cancel(ServicePlan.WATCHDOG_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) DownloadJobService.scheduler(context).cancelAll()
    }

    /** No host under way in the process, nor a walk. */
    private suspend fun noHost() {
        waitFor("no host under way", 30_000) {
            val job = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && DownloadJobService.starts.value > 0
            if (!job && DownloadService.phase.value == DownloadService.Phase.None && !container.downloads.walking.value) Unit else null
        }
    }

    /** Whether Android would wake the boot and update receiver at boot: an enabled one is listed, a disabled one isn't. */
    private fun receiverOn(): Boolean {
        // The flags' overload is Android 13's; this one serves every level.
        @Suppress("DEPRECATION")
        val found = context.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(pkg), 0)
        return found.any { it.activityInfo.name == DownloadBootReceiver::class.java.name }
    }

    /** Whether `dumpsys power` lists the service's partial wake lock among those held now (not in its log of earlier ones). */
    private fun wakeLockHeld(): Boolean {
        val lines = shell("dumpsys power").lines()
        val at = lines.indexOfFirst { it.trim().startsWith("Wake Locks: size=") }
        if (at < 0) throw AssertionError("dumpsys power lists no wake locks")
        val size = lines[at].substringAfter("size=").trim().toInt()
        return lines.drop(at + 1).take(size).any { "'${DownloadService.WAKE_TAG}'" in it }
    }

    private fun validatedWifi(): Unit? = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }?.let { }

    /** The writer held to [bytesPerSecond] from its next stream on (the debug build's seam, item 8). */
    private fun throttle(bytesPerSecond: Long) {
        folder.mkdirs()
        File(folder, DownloadDebug.THROTTLE).writeText("$bytesPerSecond")
    }

    private fun traced(): List<String> = File(folder, DownloadDebug.TRACE).takeIf { it.isFile }?.readLines().orEmpty()

    /** The trace's lines since the test began: all of them where it started again past its cap. */
    private fun mine(): List<String> = traced().let { lines -> if (lines.size >= traceFrom) lines.drop(traceFrom) else lines }

    /** What started the service each time, in order, as the trace says it. */
    private fun serviceStarts(): List<String> = mine().mapNotNull { SERVICE_START.find(it)?.groupValues?.get(1) }

    /** Each stream's first byte for download [id], in order. */
    private fun opens(id: String): List<Long> = mine().mapNotNull { REOPEN.find(it)?.takeIf { m -> m.groupValues[1] == id }?.groupValues?.get(2)?.toLong() }

    private fun active(id: Int): StatusBarNotification? = manager.activeNotifications.firstOrNull { it.id == id && it.tag == null }

    private fun text(n: StatusBarNotification): String = n.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun actions(n: StatusBarNotification): List<String> = n.notification.actions.orEmpty().map { it.title.toString() }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    private companion object {
        const val SHOW = 900_792
        const val INDEXER = "dl-service-test"

        /** The film's articles: 16 KiB, about 43 of them. */
        const val PART = 16 * 1024

        /** The writer's bytes a second while a case watches it: the film takes over a minute. */
        const val SLOW = 8_000L

        /** Fast enough to finish in seconds, slow enough to see it under way. */
        const val MEDIUM = 64_000L

        /** Once a case has seen what it needs: the rest in a moment. */
        const val FAST = 4_000_000L

        val SERVICE_START = Regex(""" start host=service cause=(\w+)$""")
        val REOPEN = Regex("""reopen id=(\S+) offset=(\d+)""")
    }
}
