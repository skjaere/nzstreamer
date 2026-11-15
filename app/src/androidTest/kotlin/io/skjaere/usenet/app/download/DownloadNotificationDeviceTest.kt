package io.skjaere.usenet.app.download

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.R
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The download notifications on a phone (item 11, L358, L380, L387, C4; DlPhoneNotification). No host exists yet in the
 * build's order (items 9 and 10 come next), so the test posts the ongoing one through the app's builder from the
 * runner's state, as a host will, and ends a host's life by hand for the status one; the app's own watch posts Finished
 * and Failed as the rows come to them.
 * - The ongoing one: its channel, the show and episode, its words, bar and actions, nothing on a lock screen, the drawn
 *   small icon.
 * - Pause and Cancel reach the runner as immutable broadcasts to the receiver, which isn't exported.
 * - The status one a host's end leaves, gone as a host starts; Finished with Play, Failed with Choose release.
 * - MainActivity's download extras: a resume without the app's token only opens the page, and resumes nothing; the app's
 *   own resumes.
 *
 * The rows are seeded through the runner (their folders and markers made) and put back after, with every notification
 * the test made. The app's POST_NOTIFICATIONS is granted for it, as it is left after. The show is invented.
 */
@RunWith(AndroidJUnit4::class)
class DownloadNotificationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val dao = container.data.downloads
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifications = container.downloadNotifications
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val made = mutableListOf<String>()

    /** Where the test's rows go in the queue: before any other's, so the queue's Pause takes the test's running one. */
    private var base: Long? = null

    @Before
    fun setUp() {
        shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        scope.cancel()
        // The job an own Resume schedules (item 9), ended before its rows go.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            DownloadJobService.scheduler(context).cancelAll()
            waitFor("no host under way", 30_000) { if (DownloadJobService.starts.value == 0 && !container.downloads.walking.value) Unit else null }
        }
        made.forEach { id ->
            container.downloadStorage.delete(id)
            manager.cancel(id, DownloadNotifications.DONE_ID)
        }
        manager.cancel(DownloadNotifications.ONGOING_ID)
        manager.cancel(DownloadNotifications.STATUS_ID)
        // As the app is left: allowed to post.
        shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
    }

    @Test
    fun theOngoingOneSaysTheEpisodeAndHowFarItIsWithPauseAndCancelAndNothingOnTheLockScreen() = runBlocking<Unit> {
        val running = seed(4, DownloadState.Running)
        seed(5, DownloadState.Queued)
        val ongoing = postOngoing(running) { it.extras.getCharSequence(Notification.EXTRA_TEXT)?.startsWith("Downloading") == true }
        val n = ongoing.notification
        assertEquals(DownloadNotice.DOWNLOADS_CHANNEL, n.channelId)
        assertEquals("Harbor Lights · S02E04", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Downloading · 0.6 of 1.2 GB", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("1 more queued", n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertEquals(listOf("Pause", "Cancel"), n.actions.orEmpty().map { it.title.toString() })
        assertEquals("nothing on a secure lock screen (L387)", Notification.VISIBILITY_SECRET, n.visibility)
        assertNull("no public version", n.publicVersion)
        assertEquals(R.drawable.ic_stat_download, n.smallIcon.resId)
        assertEquals(Notification.CATEGORY_PROGRESS, n.category)
        assertEquals(1_000, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(500, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertTrue("ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("alerts once", n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue("a tap opens the app", n.contentIntent.isActivity && n.contentIntent.isImmutable)
        // The channels as Android keeps them: "Downloads" low, "Finished downloads" at the default.
        val downloads = manager.getNotificationChannel(DownloadNotice.DOWNLOADS_CHANNEL)
        assertEquals("Downloads", downloads.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, downloads.importance)
        val finished = manager.getNotificationChannel(DownloadNotice.FINISHED_CHANNEL)
        assertEquals("Finished downloads", finished.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, finished.importance)
    }

    @Test
    fun pauseAndCancelReachTheRunnerAsImmutableBroadcasts() = runBlocking<Unit> {
        val id = seed(4, DownloadState.Running)
        val ongoing = postOngoing(id) { it.actions.orEmpty().any { a -> a.title.toString() == "Pause" } }
        val pause = ongoing.notification.actions.single { it.title.toString() == "Pause" }.actionIntent
        assertTrue("a broadcast", pause.isBroadcast)
        assertTrue("immutable", pause.isImmutable)
        assertEquals(context.packageName, pause.creatorPackage)
        pause.send()
        // The receiver isn't exported and has no filter: only an explicit broadcast of the app's own reaches it.
        waitFor("the download paused from its notification") { runBlocking { dao.get(id) }?.takeIf { it.state == DownloadState.Paused } }
        val paused = postOngoing(id) { it.actions.orEmpty().any { a -> a.title.toString() == "Resume" } }.notification
        assertEquals("Paused · 0.6 of 1.2 GB", paused.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Resume", "Cancel"), paused.actions.orEmpty().map { it.title.toString() })
        assertTrue("Resume opens the app (C4)", paused.actions.first().actionIntent.isActivity)
        val cancel = paused.actions.single { it.title.toString() == "Cancel" }.actionIntent
        assertTrue(cancel.isBroadcast && cancel.isImmutable)
        cancel.send()
        waitFor("the download cancelled from its notification") { if (runBlocking { dao.get(id) } == null) Unit else null }
    }

    @Test
    fun aHostsEndLeavesTheStatusOneWhichGoesAsAHostStarts() = runBlocking<Unit> {
        val id = seed(4, DownloadState.Paused)
        notifications.hostEnded(items(id))
        val status = waitFor("the status notification") { active(DownloadNotifications.STATUS_ID) }.notification
        assertEquals("Paused · 0.6 of 1.2 GB", status.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Resume", "Cancel"), status.actions.orEmpty().map { it.title.toString() })
        assertEquals(Notification.VISIBILITY_SECRET, status.visibility)
        assertTrue("it can be swiped away", status.flags and Notification.FLAG_ONGOING_EVENT == 0)
        notifications.hostStarted()
        waitFor("the status notification gone") { if (active(DownloadNotifications.STATUS_ID) == null) Unit else null }
    }

    @Test
    fun finishedHasPlayAndFailedHasChooseReleaseAndEachGoesWithItsDownload() = runBlocking<Unit> {
        val done = seed(4, DownloadState.Running)
        val failed = seed(5, DownloadState.Running)
        // Rows the app's watch has seen under way before they end.
        waitFor("both under way in the runner's state") { container.downloads.state.value.count { it.row.id in setOf(done, failed) && it.row.state == DownloadState.Running }.takeIf { it == 2 } }
        delay(500)
        val row = dao.get(done)!!
        dao.update(row.copy(state = DownloadState.Done, safeOffset = row.size!!, completedAt = row.updatedAt + 1, updatedAt = row.updatedAt + 1))
        val bad = dao.get(failed)!!
        dao.update(bad.copy(state = DownloadState.Failed, reason = DownloadResolver.INCOMPLETE, tried = listOf("t1", "t2", "t3"), updatedAt = bad.updatedAt + 1))
        val finished = waitFor("the finished notification") { active(DownloadNotifications.DONE_ID, done) }.notification
        assertEquals(DownloadNotice.FINISHED_CHANNEL, finished.channelId)
        assertEquals("Harbor Lights · S02E04", finished.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Downloaded · 1.2 GB", finished.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Play"), finished.actions.orEmpty().map { it.title.toString() })
        assertTrue("Play is the activity's, immutable", finished.actions.single().actionIntent.let { it.isActivity && it.isImmutable })
        assertEquals(R.drawable.ic_stat_downloaded, finished.smallIcon.resId)
        assertEquals(Notification.VISIBILITY_SECRET, finished.visibility)
        val couldnt = waitFor("the failed notification") { active(DownloadNotifications.DONE_ID, failed) }.notification
        assertEquals(DownloadNotice.FINISHED_CHANNEL, couldnt.channelId)
        assertEquals("Harbor Lights · S02E05", couldnt.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(
            "Couldn't download it. Your provider is missing parts of all 3 releases tried.",
            couldnt.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertEquals(listOf("Choose release"), couldnt.actions.orEmpty().map { it.title.toString() })
        assertEquals(Notification.VISIBILITY_SECRET, couldnt.visibility)
        // Two finished downloads' intents are told apart, each Play its own download's: Android's PendingIntents leave the
        // extras out when they compare.
        val plays = listOf(dao.get(done)!!, dao.get(failed)!!).map { notifications.build(DownloadNotices.finished(it)).actions.single().actionIntent }
        assertNotEquals(plays[0], plays[1])
        // Removed (hidden at once, L379) or tried again: what was said of it goes.
        container.downloads.remove(listOf(done))
        container.downloads.retry(failed)
        waitFor("both taken back") { if (active(DownloadNotifications.DONE_ID, done) == null && active(DownloadNotifications.DONE_ID, failed) == null) Unit else null }
    }

    @Test
    fun aResumeWithoutTheAppsTokenOnlyOpensThePageAndTheAppsOwnResumes() = runBlocking<Unit> {
        val id = seed(4, DownloadState.Paused)
        // As another app would send it: the extras' names, no token.
        val foreign = Intent(context, MainActivity::class.java).putExtra("openDownloads", true).putExtra("downloadResume", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ActivityScenario.launch<MainActivity>(foreign).use { scenario ->
            scenario.onActivity { assertEquals(DownloadAsk.Open, it.downloadAsked) }
            // The page's ask taken, and nothing resumed meanwhile.
            delay(3_000)
            assertEquals(DownloadState.Paused, dao.get(id)?.state)
        }
        ActivityScenario.launch<MainActivity>(MainActivity.openDownloads(context, resume = true)).use { scenario ->
            scenario.onActivity { assertEquals(DownloadAsk.Resume, it.downloadAsked) }
            // Queued again, and its host scheduled with it (item 9), which may take it up at once: no longer held.
            val resumed = waitFor("the download resumed by the app's own ask") {
                runBlocking { dao.get(id) }?.takeIf { it.state != DownloadState.Paused && it.state != DownloadState.Stopped }
            }
            assertNotEquals("nothing held", DownloadHostRules.STOPPED_BY_ANDROID, resumed.reason)
        }
    }

    /** Episode [episode] of Harbor Lights' season 2, 1.2 GB, half of it written, in [state], first in the queue. */
    private suspend fun seed(episode: Int, state: DownloadState): String {
        val candidates = listOf(DownloadCandidate("idx-notify", "g$episode", "Harbor.Lights.S02E0$episode.720p.WEB-DL-WREN", size = SIZE))
        val row = container.downloads.enqueue(DownloadTarget.episode(SHOW, "Harbor Lights", 2, episode, seasonEpisodes = 8, episodeName = "Low Water"), candidates)
            ?: throw AssertionError("no row queued")
        made += row.id
        val first = base ?: ((dao.all().first().filter { it.id !in made }.minOfOrNull { it.position } ?: 0) - 100).also { base = it }
        dao.update(row.copy(state = state, size = SIZE, safeOffset = SIZE / 2, position = first + episode, updatedAt = row.updatedAt + 1))
        waitFor("download ${row.id} in the runner's state as $state") { container.downloads.state.value.firstOrNull { it.row.id == row.id && it.row.state == state } }
        return row.id
    }

    /** The runner's state, of the test's own rows alone, from the one [first] on. */
    private fun items(vararg first: String): List<DownloadRunner.Item> {
        val own = container.downloads.state.value.filter { it.row.id in made }
        return own.sortedBy { if (it.row.id in first) 0 else 1 }
    }

    /** The ongoing notification as a host keeps it, from the runner's state of the test's own rows, once [shown] says it is posted as wanted. */
    private suspend fun postOngoing(id: String, shown: (Notification) -> Boolean): StatusBarNotification {
        val keeping = scope.launch { notifications.keepOngoing(container.downloads.state.map { items -> items.filter { it.row.id in made }.sortedBy { if (it.row.id == id) 0 else 1 } }) }
        try {
            return waitFor("the ongoing notification") { active(DownloadNotifications.ONGOING_ID)?.takeIf { shown(it.notification) } }
        } finally {
            keeping.cancel()
        }
    }

    private fun active(id: Int, tag: String? = null): StatusBarNotification? = manager.activeNotifications.firstOrNull { it.id == id && it.tag == tag }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private companion object {
        const val SHOW = 900_771
        const val SIZE = 1_200_000_000L
    }
}
