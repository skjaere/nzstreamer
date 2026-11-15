package io.skjaere.usenet.app.download

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobScheduler
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadFixtures.Packing
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.newznab.NzbDownload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The downloads' job on a phone (item 9, L335, L376, L388, C4, C5): the user-initiated data transfer job, run by Android as
 * the app schedules it, over the test's own NNTP server (LocalNntp, in the app's process, so the network's comings and
 * goings reach the job's constraint and never the transfer itself), the releases' NZBs through the coordinator's grab
 * (its test seam) from a test indexer saved for it. The episode is the test assets' film, so Resolve's probe reads a real
 * file; the debug build's throttle holds the writer to a few KB a second, so a download lasts the case.
 * - Scheduled from the activity (the app's start); from the background the app's own rule asks nothing of Android, whose
 *   own refusal an instrumented process can't show (the case says why).
 * - The job's notification within Android's 10 s, with Pause and Cancel as it downloads.
 * - Pause from it ends the job, the job's notification going with it, and the status notification offers Resume; Resume
 *   opens the app, which schedules the job, and the download goes on from where it stopped; Cancel cancels it.
 * - With POST_NOTIFICATIONS revoked, the download still runs.
 * - Wi-Fi off and mobile data on, Only on Wi-Fi on: stopped for the network, "Waiting for Wi-Fi"; back on Wi-Fi it goes
 *   on, and LocalNntp's BODY counts show nothing before where it stopped fetched again.
 * - A timeout from JobScheduler's shell: stopped, the job kept with its backoff, and on from its safe offset when run.
 * - Unplugged and forced into Doze, and in the rare standby bucket: it keeps transferring.
 *
 * Android 14 on, as user-initiated jobs are. Everything is put back after each, whatever happened: Wi-Fi on, mobile data as
 * it was, the battery and Doze as the device has them, the standby bucket active, POST_NOTIFICATIONS granted, the debug
 * throttle gone, the job cancelled, the rows and their files deleted, the settings and the memory of tried releases as
 * they were. The show is invented.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UidtHostDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val dao = container.data.downloads
    private val settings = container.settings
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val scheduler by lazy { DownloadJobService.scheduler(context) }
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
    private var unplugged = false
    private var idled = false
    private var deepWasOff = false
    private var bucketChanged = false

    @Before
    fun setUp() = runBlocking<Unit> {
        assertFalse("a phone", container.television)
        // The job is the host here: a host override another class's process left as it died (ServiceHostDeviceTest's) goes.
        File(folder, DownloadDebug.HOST).delete()
        assertFalse("the debug build's host override still read", container.downloadDebug.serviceHost)
        // No job or walk of another test's.
        scheduler.cancelAll()
        noHost()
        shell("pm grant $pkg ${Manifest.permission.POST_NOTIFICATIONS}")
        val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        assertTrue(
            "the job's network: validated Wi-Fi (Wi-Fi ${network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}, validated ${network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)})",
            network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
        // The debug build's own seeding first (its provider, saved as a fresh install's first process starts): settings taken
        // before it would be put back without it, and every later test in the process would find no provider.
        withTimeout(SEEDING_MILLIS) { container.devSettingsSaved.await() }
        saved = settings.settings.first()
        assertTrue("Only on Wi-Fi or Ethernet, as a new install has it (W110)", saved.downloads.unmeteredOnly)
        assertFalse(saved.downloads.whileCharging)
        remembered = container.library.triedReleaseEntries()
        // The candidates' indexer, which the grab's seam stands in for: never asked.
        settings.saveIndexer(INDEXER, "Test indexer (job)", "http://indexer.invalid/api", "test-key")
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(local.provider)))
        }
        PlaybackCoordinator.fetchNzb = { _, _, item -> NzbDownload(nzbs.getValue(item.guid)) }
        throttle(SLOW)
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
        if (idled) back { shell("dumpsys deviceidle unforce") }
        if (deepWasOff) back { shell("dumpsys deviceidle disable deep") }
        if (unplugged) back { shell("dumpsys battery reset") }
        if (bucketChanged) back { shell("am set-standby-bucket $pkg active") }
        back { shell("pm grant $pkg ${Manifest.permission.POST_NOTIFICATIONS}") }
        back { File(folder, DownloadDebug.THROTTLE).delete() }
        // Then the app: its job, its rows and their notifications, its seams and settings.
        back { scheduler.cancelAll() }
        back { runBlocking { noHost() } }
        made.forEach { id ->
            back { runBlocking { container.downloadStorage.delete(id) } }
            back { manager.cancel(id, DownloadNotifications.DONE_ID) }
        }
        back { manager.cancel(DownloadNotifications.ONGOING_ID) }
        back { manager.cancel(DownloadNotifications.STATUS_ID) }
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
        // Off the test's server, which is gone: on the mock, the debug build's saved provider (the app's own sync runs only
        // as the saved providers change).
        back { runBlocking { engineSetup("the engine back on the mock", endIfStuck = false) { mockOnEngine() } } }
        failures.firstOrNull()?.let { first -> failures.drop(1).forEach(first::addSuppressed); throw first }
    }

    /**
     * Android refuses a user-initiated job from the background with RESULT_FAILURE, unless the app may start an activity
     * from there. An instrumentation the shell starts gives the app's process that leave (the shell holds
     * START_ACTIVITIES_FROM_BACKGROUND), so Android takes the job from this test's background: the app's own rule is what
     * keeps it from asking there (L335, C4), and that is what is checked.
     */
    @Test
    fun scheduledFromTheActivityAndNotFromTheBackground() = runBlocking<Unit> {
        val release = queue(1)
        val id = made.single()
        waitFor("the app out of front") { if (!container.inFront.value.value) Unit else null }
        assertFalse(container.downloadHosts.schedule(ScheduleCause.Download))
        assertNull(scheduler.getPendingJob(JobPlan.ID))
        assertEquals(emptyList<Long>(), starts())

        ActivityScenario.launch(MainActivity::class.java).use {
            // The app's start, with a download to run and no job (L388).
            val job = waitFor("the job scheduled from the activity") { scheduler.getPendingJob(JobPlan.ID) }
            assertTrue(job.isUserInitiated)
            assertTrue(job.isPersisted)
            assertFalse(job.isRequireCharging)
            assertFalse("no storage constraint (L376)", job.isRequireStorageNotLow)
            val network = job.requiredNetwork ?: throw AssertionError("no network asked for")
            assertTrue(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            assertTrue("Only on Wi-Fi or Ethernet", network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            assertEquals(release.size, job.estimatedNetworkDownloadBytes)
            assertEquals(0, job.estimatedNetworkUploadBytes)
            waitFor("the job started from the activity", 30_000) { starts().firstOrNull() }
            underWay(id)
        }
    }

    @Test
    fun theJobsNotificationIsPostedWithinTenSecondsWithPauseAndCancel() = runBlocking<Unit> {
        queue(2)
        val id = made.single()
        launchApp()
        val started = waitFor("the job started", 30_000) { starts().firstOrNull() }
        val first = waitFor("the job's notification", 15_000) { active(DownloadNotifications.ONGOING_ID) }
        assertTrue("posted ${first.postTime - started} ms after the job started", first.postTime - started <= 10_000)
        underWay(id)
        val downloading = waitFor("its notification as it downloads") { active(DownloadNotifications.ONGOING_ID)?.takeIf { text(it).startsWith("Downloading") } }
        assertEquals("Harbor Lights · S03E02", downloading.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(listOf("Pause", "Cancel"), actions(downloading))
        assertEquals(DownloadNotice.DOWNLOADS_CHANNEL, downloading.notification.channelId)
        assertEquals(Notification.VISIBILITY_SECRET, downloading.notification.visibility)
        assertTrue("ongoing while the job runs", downloading.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun pauseFromItEndsTheJobThenResumeOpensTheAppAndGoesOnAndCancelCancels() = runBlocking<Unit> {
        queue(3)
        val id = made.single()
        launchApp()
        underWay(id)
        val downloading = waitFor("its notification as it downloads") { active(DownloadNotifications.ONGOING_ID)?.takeIf { "Pause" in actions(it) } }

        action(downloading, "Pause").send()
        val paused = waitFor("paused from the notification") { runBlocking { dao.get(id) }?.takeIf { it.state == DownloadState.Paused } }
        waitFor("the job's end") { jobEnded() }
        // The job's notification went with the job; the status one says what is left, after its end.
        gone("the job's notification gone with the job", DownloadNotifications.ONGOING_ID)
        val status = waitFor("the status notification") { active(DownloadNotifications.STATUS_ID) }
        assertTrue(text(status), text(status).startsWith("Paused"))
        assertEquals(listOf("Resume", "Cancel"), actions(status))
        assertTrue("Resume opens the app (C4)", action(status, "Resume").isActivity)

        // Resume, as a tap on it sends it, with the sender's leave to start an activity, as the system's tap has: the app
        // opens on Downloads, lets the queue go and schedules the job.
        val before = starts().size
        // Android 14's mode: the ones that replace it are 16's.
        @Suppress("DEPRECATION")
        val options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        action(status, "Resume").send(context, 0, null, null, null, null, options.toBundle())
        val opened = waitFor("the app opened by Resume") { resumedMain() }
        try {
            waitFor("the job again", 30_000) { starts().size.takeIf { it > before } }
            waitFor("the status notification gone as the job starts") { if (active(DownloadNotifications.STATUS_ID) == null) Unit else null }
            underWay(id, paused.safeOffset + 1)
            assertEquals("on from where it stopped", paused.safeOffset, opens(id).last())

            // Cancel, from the job's notification: the download is gone, and the job ends with nothing left.
            val again = waitFor("the job's notification again") { active(DownloadNotifications.ONGOING_ID)?.takeIf { "Cancel" in actions(it) && text(it).startsWith("Downloading") } }
            action(again, "Cancel").send()
            waitFor("cancelled") { if (runBlocking { dao.get(id) } == null) Unit else null }
            waitFor("the job's end") { jobEnded() }
            gone("the job's notification gone with the job", DownloadNotifications.ONGOING_ID)
            gone("nothing left to say", DownloadNotifications.STATUS_ID)
        } finally {
            instrumentation.runOnMainSync { opened.finish() }
        }
    }

    @Test
    fun withPostNotificationsRevokedTheDownloadStillRuns() = runBlocking<Unit> {
        queue(4)
        val id = made.single()
        revokeNotifications()
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        launchApp()
        waitFor("the job started", 30_000) { starts().firstOrNull() }
        val at = underWay(id).written
        underWay(id, at + 3 * SLOW)
        assertEquals(DownloadState.Running, dao.get(id)?.state)
    }

    @Test
    fun offWifiWithWifiOnlyItWaitsForWifiAndBackOnItGoesOnFetchingNothingTwice() = runBlocking<Unit> {
        val release = queue(5)
        val id = made.single()
        launchApp()
        // Past a record, so the stop has something before it to keep.
        waitFor("a progress record", 60_000) { runBlocking { dao.get(id) }?.takeIf { it.state == DownloadState.Running && it.safeOffset > 0 } }

        dataWas = shell("settings get global mobile_data").trim()
        shell("svc data enable")
        wifiOff = true
        shell("svc wifi disable")
        val waiting = waitFor("stopped for the network", 60_000) { runBlocking { dao.get(id) }?.takeIf { it.state == DownloadState.Waiting } }
        assertEquals("the connectivity reason's words", DownloadHostRules.WAITING_FOR_WIFI, waiting.reason)
        assertNotNull("the job waits for its network", scheduler.getPendingJob(JobPlan.ID))
        val status = waitFor("the status notification") { active(DownloadNotifications.STATUS_ID) }
        assertTrue(text(status), text(status).startsWith(DownloadHostRules.WAITING_FOR_WIFI))
        // What each article had been fetched when it stopped.
        val posted = release.posted.single()
        val stoppedAt = waiting.safeOffset
        val before = posted.ids.associateWith { local.bodies[it]?.get() ?: 0 }
        val starts = starts().size

        throttle(FAST)
        shell("svc wifi enable")
        wifiOff = false
        waitFor("the job again on Wi-Fi", 120_000) { starts().size.takeIf { it > starts } }
        val done = waitFor("done", 120_000) { runBlocking { dao.get(id) }?.takeIf { it.state == DownloadState.Done || it.state == DownloadState.Failed } }
        assertEquals(done.reason, DownloadState.Done, done.state)
        assertEquals("on from where it stopped", stoppedAt, opens(id).last())
        // The transfer's: the reopen prepares the NZB again from the download's own copy (L346), which reads the file's first
        // article as every prepare does.
        val again = posted.ids.drop(1).filter { posted.bytesOf(it).last < stoppedAt && (local.bodies[it]?.get() ?: 0) != before.getValue(it) }
        assertEquals("articles before $stoppedAt fetched again", emptyList<String>(), again)
        assertEquals(release.sha256, DownloadFixtures.sha256(container.downloadStorage.file(done) ?: throw AssertionError("no file")))
    }

    /**
     * Android runs the job again after a timeout when its backoff lets it, which on phone34 came 110 s, 156 s and over 300 s
     * after the stop in three runs: the case runs it as soon as the stop is seen, its backoff cut short from the shell and
     * its constraints still asked (`cmd jobscheduler run` without -f).
     */
    @Test
    fun aTimeoutStopsTheJobWhichWaitsOnItsBackoffAndGoesOnWhenRun() = runBlocking<Unit> {
        val release = queue(6)
        val id = made.single()
        launchApp()
        waitFor("a progress record", 60_000) { runBlocking { dao.get(id) }?.takeIf { it.state == DownloadState.Running && it.safeOffset > 0 } }
        val starts = starts().size
        shell("cmd jobscheduler timeout -n ${JobPlan.NAMESPACE} $pkg ${JobPlan.ID}")
        // Stopped: its walk ended, the download left as a kill leaves it (neither waiting nor held), and the job still
        // scheduled, user-initiated, waiting on its backoff alone.
        waitFor("the job stopped", 30_000) { if (DownloadJobService.starts.value == 0) Unit else null }
        val stopped = dao.get(id) ?: throw AssertionError("the row is gone")
        assertEquals(DownloadState.Running, stopped.state)
        assertTrue("its safe offset kept", stopped.safeOffset > 0)
        val job = scheduler.getPendingJob(JobPlan.ID) ?: throw AssertionError("not run again: onStopJob said not to")
        assertTrue(job.isUserInitiated)
        assertEquals("its backoff", JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY, scheduler.getPendingJobReason(JobPlan.ID))

        throttle(FAST)
        shell("cmd jobscheduler run -n ${JobPlan.NAMESPACE} $pkg ${JobPlan.ID}")
        waitFor("the job again", 60_000) { starts().size.takeIf { it > starts } }
        val done = waitFor("done", 120_000) { runBlocking { dao.get(id) }?.takeIf { it.state == DownloadState.Done || it.state == DownloadState.Failed } }
        assertEquals(done.reason, DownloadState.Done, done.state)
        assertEquals("on from where it stopped", stopped.safeOffset, opens(id).last())
        assertEquals(release.sha256, DownloadFixtures.sha256(container.downloadStorage.file(done) ?: throw AssertionError("no file")))
    }

    @Test
    fun unpluggedAndForcedIntoDozeItKeepsTransferring() = runBlocking<Unit> {
        queue(7)
        val id = made.single()
        launchApp()
        underWay(id)
        unplugged = true
        shell("dumpsys battery unplug")
        if (shell("dumpsys deviceidle enabled deep").trim() == "0") {
            deepWasOff = true
            shell("dumpsys deviceidle enable deep")
        }
        idled = true
        val idle = shell("dumpsys deviceidle force-idle")
        assertTrue("forced into Doze: $idle", idle.contains("deep idle", ignoreCase = true) && !idle.contains("unable", ignoreCase = true))
        keepsTransferring(id)
    }

    @Test
    fun inTheRareStandbyBucketItKeepsTransferring() = runBlocking<Unit> {
        queue(8)
        val id = made.single()
        launchApp()
        underWay(id)
        bucketChanged = true
        shell("am set-standby-bucket $pkg rare")
        keepsTransferring(id)
    }

    /** Download [id] goes on for a few seconds more, its job never stopped meanwhile. */
    private suspend fun keepsTransferring(id: String) {
        val starts = starts().size
        val from = underWay(id).written
        underWay(id, from + 5 * SLOW, timeoutMs = 30_000)
        assertEquals("no stop and start again", starts, starts().size)
        assertEquals(DownloadState.Running, dao.get(id)?.state)
        assertNotNull("its job still runs", scheduler.getPendingJob(JobPlan.ID))
    }

    /** Episode [episode] of Harbor Lights' season 3, the test assets' film posted fresh, queued through the app's runner first in line. */
    private suspend fun queue(episode: Int): DownloadFixtures.Release {
        val title = "Harbor.Lights.S03E%02d.720p.WEB.h264-WREN".format(episode)
        val release = fixtures.post(Packing.Plain, title, DownloadFixtures.media("chapters.mkv"), partSize = PART, salt = "${System.nanoTime()}")
        val guid = "hl-job-$episode"
        nzbs[guid] = release.nzb
        val row = container.downloads.enqueue(
            DownloadTarget.episode(SHOW, "Harbor Lights", 3, episode, seasonEpisodes = 8), listOf(DownloadCandidate(INDEXER, guid, title, size = release.size)),
        ) ?: throw AssertionError("not queued")
        made += row.id
        // First in line, whatever another test left.
        dao.move(row.id, (dao.all().first().filter { it.id != row.id }.minOfOrNull { it.position } ?: 0) - 100)
        return release
    }

    /** MainActivity opened and closed again: its start schedules the job, which then runs on without it. */
    private suspend fun launchApp() {
        val before = starts().size
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("the job started from the activity", 30_000) { starts().size.takeIf { it > before } }
        }
    }

    /** Download [id] written, at [bytes] or more, as the runner's state says it. */
    private suspend fun underWay(id: String, bytes: Long = 1, timeoutMs: Long = 60_000): DownloadRunner.Item =
        waitFor("download $id under way at $bytes bytes", timeoutMs) {
            container.downloads.state.value.firstOrNull { it.row.id == id }?.takeIf { it.row.state == DownloadState.Running && it.written >= bytes }
        }

    /** No start of the job is under way in the process, nor a walk. */
    private suspend fun noHost() {
        waitFor("no host under way", 30_000) { if (DownloadJobService.starts.value == 0 && !container.downloads.walking.value) Unit else null }
    }

    private fun jobEnded(): Unit? = if (scheduler.getPendingJob(JobPlan.ID) == null && DownloadJobService.starts.value == 0) Unit else null

    /**
     * Notification [id] gone: Android takes a job's own away with the job on NotificationManager's thread, after the job is
     * gone from JobScheduler's, so it is waited for rather than read once.
     */
    private suspend fun gone(what: String, id: Int) = waitFor(what, GONE_MILLIS) { if (active(id) == null) Unit else null }

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

    /** When each start of the job came, as the trace times them. */
    private fun starts(): List<Long> = mine().filter { it.endsWith(" start host=job cause=job") }.map { it.substringBefore(' ').toLong() }

    /** Each stream's first byte for download [id], in order. */
    private fun opens(id: String): List<Long> = mine().mapNotNull { REOPEN.find(it)?.takeIf { m -> m.groupValues[1] == id }?.groupValues?.get(2)?.toLong() }

    private fun active(id: Int): StatusBarNotification? = manager.activeNotifications.firstOrNull { it.id == id && it.tag == null }

    private fun text(n: StatusBarNotification): String = n.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun actions(n: StatusBarNotification): List<String> = n.notification.actions.orEmpty().map { it.title.toString() }

    private fun action(n: StatusBarNotification, title: String): PendingIntent = n.notification.actions.orEmpty().single { it.title.toString() == title }.actionIntent

    private fun resumedMain(): MainActivity? {
        var found: MainActivity? = null
        instrumentation.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
        }
        return found
    }

    /**
     * POST_NOTIFICATIONS taken back without ending the app's process: `pm revoke` ends it, the instrumentation with it.
     * Through the platform's test API for it (PermissionManager's, a system API, so found by name), with the shell's
     * permissions.
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

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    private companion object {
        const val SHOW = 900_791
        const val INDEXER = "dl-job-test"

        /** How long the debug build's seeding may take, as the app's start runs it. */
        const val SEEDING_MILLIS = 30_000L

        /** How long a notification Android takes away may take to go. */
        const val GONE_MILLIS = 10_000L

        /** The film's articles: 16 KiB, about 43 of them. */
        const val PART = 16 * 1024

        /** The writer's bytes a second while a case watches it: the film takes over a minute. */
        const val SLOW = 8_000L

        /** Once a case has seen what it needs: the rest in a moment. */
        const val FAST = 4_000_000L

        const val REVOKE_RUNTIME_PERMISSIONS = "android.permission.REVOKE_RUNTIME_PERMISSIONS"
        const val REVOKE_WITHOUT_KILL = "android.permission.REVOKE_POST_NOTIFICATIONS_WITHOUT_KILL"

        /** Android's uids a user apart (UserHandle.PER_USER_RANGE). */
        const val PER_USER_RANGE = 100_000

        val REOPEN = Regex("""reopen id=(\S+) offset=(\d+)""")
    }
}
