package io.skjaere.usenet.app.download

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.R
import io.skjaere.usenet.app.cast.CastFakes
import io.skjaere.usenet.app.cast.CastSeams
import io.skjaere.usenet.app.cast.FakeConnector
import io.skjaere.usenet.app.cast.FakeLauncher
import io.skjaere.usenet.app.cast.FakeRoutes
import io.skjaere.usenet.app.cast.castRoute
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.play.IMPORTED
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.player.PlayUris
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The download notifications on a phone (item 11, L358, L380, L387, C4; DlPhoneNotification). No host exists yet in the
 * build's order (items 9 and 10 come next), so the test posts the ongoing one through the app's builder from the
 * runner's state, as a host will, and ends a host's life by hand for the status one; the app's own watch posts Finished
 * and Failed as the rows come to them.
 * - The ongoing one: its channel, the show and episode, its words, bar and actions, nothing on a lock screen, the drawn
 *   small icon.
 * - Pause and Cancel reach the runner as immutable broadcasts to the receiver, which isn't exported.
 * - The status one a host's end leaves, gone as a host starts; Finished with Play, Failed with Choose release.
 * - A show's episodes finishing: one Finished notification for the show, updated in place, secret on a lock screen, and
 *   its Play reaching the coordinator's hook with the show's next downloaded episode, through MainActivity and the show's
 *   downloads (L437, L445), those downloads already on top too.
 * - MainActivity's download extras: a resume without the app's token only opens the page, and resumes nothing; the app's
 *   own resumes.
 * - With Kitchen display connected (Cast's fakes, L73), a film's Finished notification's Play and a show's reach the
 *   cast's launcher with the download's file (L418), nothing played here; Play on put back to This phone after.
 *
 * The rows are seeded through the runner (their folders and markers made) and put back after, with every notification
 * the test made. The app's POST_NOTIFICATIONS is granted for it, as it is left after. The shows are invented. Where the
 * app opens, nothing is searched for, grabbed or asked of TMDB: the indexers are off, the grab seam refuses and TMDB's
 * seams are a strict fake, each put back after.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadNotificationDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its window's focus. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

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

    // What a case that opens the app changed (quiet), put back after.
    private var tmdb: FakeTmdb? = null
    private var saved: AppSettings? = null
    private val realFetch = PlaybackCoordinator.fetchNzb
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realLookUp = PlayerActivity.lookUpSegments
    private val grabs = AtomicInteger()
    private val played = mutableListOf<Download>()

    // Cast's, where a case chose Kitchen display: put back after.
    private val kitchen = castRoute("Kitchen display")
    private var cast = false

    @Before
    fun setUp() {
        shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        // The debug seams another class's process left as it died (ServiceHostDeviceTest's): the job is the host on Android
        // 14 here, and the writer unthrottled.
        val folder = File(context.filesDir, DownloadStorage.DIR)
        listOf(DownloadDebug.HOST, DownloadDebug.THROTTLE).forEach { File(folder, it).delete() }
        assertFalse("the debug build's host override still read", container.downloadDebug.serviceHost)
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        scope.cancel()
        // The app and its player, where a case opened them.
        finishAll<PlayerActivity>()
        finishAll<MainActivity>()
        container.playback.cancel()
        if (cast) {
            container.playOn.choose(PlayChoice.Phone)
            CastFakes.reset()
        }
        played.forEach { row ->
            try {
                // The player saves where it was as it closes: let it, before its marks are taken back.
                waitFor("the player's save on closing", 10_000) { runBlocking { container.library.state(row.watchKey).first() }?.takeIf { it.lastPlayedAt > 0 } }
            } catch (e: AssertionError) {
                // It never played: nothing to wait for.
            }
            // Out of Up next, unwatched, its release forgotten, as before the test.
            container.library.markEpisodes(listOf(EpisodeRef(PLAYED, 1, row.episode!!, WatchTitle.episode(PLAYED_NAME, 1, row.episode!!))), watched = false)
            row.release?.let { container.library.forgetRelease(row.watchKey, it) }
        }
        try {
            tmdb?.close()
        } catch (e: AssertionError) {
            // What the app asked of other titles was refused, never sent.
        }
        if (tmdb != null) {
            PlaybackCoordinator.fetchNzb = realFetch
            PlayerActivity.nextEpisode = realNext
            PlayerActivity.previousEpisode = realPrevious
            PlayerActivity.lookUpSegments = realLookUp
        }
        saved?.let { container.settings.restore(it) }
        // The job an own Resume schedules (item 9), ended before its rows go.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            DownloadJobService.scheduler(context).cancelAll()
            waitFor("no host under way", 30_000) { if (DownloadJobService.starts.value == 0 && !container.downloads.walking.value) Unit else null }
        }
        made.forEach { id ->
            container.downloadStorage.delete(id)
            manager.cancel(id, DownloadNotifications.DONE_ID)
        }
        listOf(SHOW, OTHER, PLAYED).forEach { manager.cancel(DownloadNotice.showTag(it), DownloadNotifications.DONE_ID) }
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
        val bad = dao.get(failed)!!
        dao.update(bad.copy(state = DownloadState.Failed, reason = DownloadResolver.INCOMPLETE, tried = listOf("t1", "t2", "t3"), updatedAt = bad.updatedAt + 1))
        waitFor("the failed notification first") { active(DownloadNotifications.DONE_ID, failed) }
        finish(done)
        // An episode is its show's (L437): the one finished alone says it as before, under the show's tag, none of its own.
        val finished = waitFor("the finished notification") { active(DownloadNotifications.DONE_ID, DownloadNotice.showTag(SHOW)) }.notification
        assertNull(active(DownloadNotifications.DONE_ID, done))
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
        waitFor("both taken back") {
            if (active(DownloadNotifications.DONE_ID, DownloadNotice.showTag(SHOW)) == null && active(DownloadNotifications.DONE_ID, failed) == null) Unit else null
        }
    }

    /**
     * L437, L445: as two episodes of a show finish, the manager holds one Finished notification for it, tagged by the show
     * and updated in place, alerting once and secret on a lock screen, with Play; its Play and its tap name the show's
     * latest finished download, so another show's are told apart. A removal updates it; the last finished one's takes it
     * back.
     */
    @Test
    fun twoEpisodesOfAShowFinishingAreOneNotificationUpdatedInPlaceAndSecret() = runBlocking<Unit> {
        val first = seed(4, DownloadState.Running)
        val second = seed(5, DownloadState.Running)
        seed(6, DownloadState.Queued)
        waitFor("all three in the runner's state") { container.downloads.state.value.count { it.row.id in made }.takeIf { it == 3 } }
        delay(500)
        finish(first)
        val one = waitFor("the show's notification") { shown(SHOW)?.takeIf { title(it) == "Harbor Lights · 1 of 3 downloaded" } }
        assertEquals("S02E04 · 1.2 GB", text(one))
        finish(second)
        val two = waitFor("the show's notification updated") { shown(SHOW)?.takeIf { title(it) == "Harbor Lights · 2 of 3 downloaded" } }
        assertEquals("S02E04, S02E05 · 2.4 GB", text(two))
        // One notification for the two: none of the show's downloads has one of its own.
        val finishedOnes = manager.activeNotifications.filter { it.id == DownloadNotifications.DONE_ID && (it.tag in made || it.tag == DownloadNotice.showTag(SHOW)) }
        assertEquals(listOf(DownloadNotice.showTag(SHOW)), finishedOnes.map { it.tag })
        val n = two.notification
        assertEquals(DownloadNotice.FINISHED_CHANNEL, n.channelId)
        assertEquals("nothing on a secure lock screen (L387)", Notification.VISIBILITY_SECRET, n.visibility)
        assertNull("no public version", n.publicVersion)
        assertTrue("alerts once: its updates are silent", n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(R.drawable.ic_stat_downloaded, n.smallIcon.resId)
        assertEquals(listOf("Play"), n.actions.orEmpty().map { it.title.toString() })
        val play = n.actions.single().actionIntent
        assertTrue("Play is the activity's, immutable", play.isActivity && play.isImmutable)
        assertTrue("a tap opens the app", n.contentIntent.isActivity && n.contentIntent.isImmutable)
        // Another show's notification, built as the app builds it: its Play and tap are its own.
        val other = Download(
            id = Download.newId(), watchKey = WatchKey.episode(OTHER, 1, 1), kind = DownloadKind.Episode, title = "Copper Crown", showTmdbId = OTHER, season = 1,
            episode = 1, state = DownloadState.Done, position = 0, size = SIZE, indexerSize = SIZE, safeOffset = SIZE, createdAt = 1, completedAt = 2, updatedAt = 2,
        )
        val built = notifications.build(checkNotNull(DownloadNotices.show(listOf(other), OTHER)))
        assertNotEquals(play, built.actions.single().actionIntent)
        assertNotEquals(n.contentIntent, built.contentIntent)
        // Removed (hidden at once, L379): updated in place; the show's last finished one removed: taken back.
        container.downloads.remove(listOf(first))
        val left = waitFor("updated as one leaves") { shown(SHOW)?.takeIf { title(it) == "Harbor Lights · 1 of 2 downloaded" } }
        assertEquals("S02E05 · 1.2 GB", text(left))
        container.downloads.remove(listOf(second))
        waitFor("taken back once none is finished") { if (shown(SHOW) == null) Unit else null }
    }

    /**
     * L437, L445: the show's notification's Play, sent as a tap sends it, opens the app with the app's token on the show's
     * downloads, read from the download it names, and plays the show's next downloaded episode there: through the
     * coordinator's hook, which launches the file's `download://` URI and holds it, nothing searched or grabbed. The next
     * is S01E01 (nothing watched), though the notification names S01E02, the latest finished.
     */
    @Test
    fun theShowsPlayPlaysItsNextDownloadedEpisodeThroughTheHook() = runBlocking<Unit> {
        quiet()
        val e1 = underWay(1)
        val e2 = underWay(2)
        waitFor("both under way in the runner's state") { container.downloads.state.value.count { it.row.id in setOf(e1.id, e2.id) && it.row.state == DownloadState.Running }.takeIf { it == 2 } }
        delay(500)
        val first = written(e1)
        val latest = written(e2)
        played += first
        val shown = waitFor("the show's notification") { shown(PLAYED)?.takeIf { title(it) == "$PLAYED_NAME · 2 of 2 downloaded" } }
        val play = shown.notification.actions.single()
        assertEquals("Play", play.title.toString())
        tap(play.actionIntent)
        val main = waitFor("the app opened by its Play") { resumed<MainActivity>() }
        assertEquals(DownloadAsk.PlayShow(latest.id), onMain { main.downloadAsked })
        val player = waitFor("the player ready on the show's next episode", READY_MILLIS) {
            resumed<PlayerActivity>()?.takeIf { p -> onMain { p.player?.playbackState } == Player.STATE_READY }
        }
        assertEquals(PlayUris.download(first.id), onMain { player.player!!.currentMediaItem?.localConfiguration?.uri?.toString() })
        // The hook's hold, or the player's after it (L413): the file is the one played.
        assertTrue("held: ${container.downloads.held.value}", first.id in container.downloads.held.value)
        assertEquals("indexer grabs", 0, grabs.get())
    }

    /**
     * L437: the show's notification's Play, sent while its tap's page, the show's downloads, is on top, plays the show's
     * next downloaded episode there all the same: the page takes each ask that comes to it once, not only the one it
     * opened with.
     */
    @Test
    fun theShowsPlayReachingItsDownloadsAlreadyOnTopPlaysItsNextDownloadedEpisode() = runBlocking<Unit> {
        quiet()
        val e1 = underWay(1)
        val e2 = underWay(2)
        waitFor("both under way in the runner's state") { container.downloads.state.value.count { it.row.id in setOf(e1.id, e2.id) && it.row.state == DownloadState.Running }.takeIf { it == 2 } }
        delay(500)
        val first = written(e1)
        val latest = written(e2)
        played += first
        val shown = waitFor("the show's notification") { shown(PLAYED)?.takeIf { title(it) == "$PLAYED_NAME · 2 of 2 downloaded" } }
        // Its tap: the show's downloads, shown, nothing played.
        tap(shown.notification.contentIntent)
        val main = waitFor("the app opened by its tap") { resumed<MainActivity>() }
        assertEquals(DownloadAsk.OpenShow(latest.id), onMain { main.downloadAsked })
        waitFor("the show's downloads shown", PAGE_MILLIS) { Unit.takeIf { DownloadsShow.seasonTitle(1).uppercase() in words(main) } }
        assertNull("nothing played from the tap", resumed<PlayerActivity>())
        // Then its Play, to the page on top.
        tap(shown.notification.actions.single().actionIntent)
        waitFor("the Play's ask taken") { onMain { main.downloadAsked }.takeIf { it == DownloadAsk.PlayShow(latest.id) } }
        val player = waitFor("the player ready on the show's next episode", READY_MILLIS) {
            resumed<PlayerActivity>()?.takeIf { p -> onMain { p.player?.playbackState } == Player.STATE_READY }
        }
        assertEquals(PlayUris.download(first.id), onMain { player.player!!.currentMediaItem?.localConfiguration?.uri?.toString() })
        assertEquals("indexer grabs", 0, grabs.get())
    }

    /**
     * L418: a film's Finished notification's Play, with Kitchen display connected, opens the Downloads page, which casts the
     * film's file there: the launch the cast's launcher is handed is the download's, nothing played here or grabbed.
     */
    @Test
    fun aFilmsPlayWithKitchenDisplayConnectedCastsItsFile() = runBlocking<Unit> {
        quiet()
        val film = finishedFilm()
        val launcher = connectKitchen()
        val play = notifications.build(DownloadNotices.finished(film)).actions.single()
        assertEquals("Play", play.title.toString())
        tap(play.actionIntent)
        val main = waitFor("the app opened by its Play") { resumed<MainActivity>() }
        assertEquals(DownloadAsk.Play(film.id), onMain { main.downloadAsked })
        waitFor("the film's file handed to Kitchen display", READY_MILLIS) { launcher.loads.firstOrNull() }
        val launch = launcher.loads.single()
        assertEquals(PlayUris.download(film.id), launch.uri)
        assertEquals("Kitchen display", launch.cast?.name)
        assertNull("nothing played here", resumed<PlayerActivity>())
        assertEquals("indexer grabs", 0, grabs.get())
        launcher.plays()
    }

    /**
     * L418, L472: a file from Import's Finished notification names it as its row does ("Quiet Meridian · S01E02"), a
     * notification of its own; with Kitchen display connected its Play opens the Downloads page, which casts the file there:
     * the launch the cast's launcher is handed is the download's, nothing played here or grabbed.
     */
    @Test
    fun aFileFromImportsPlayWithKitchenDisplayConnectedCastsItsFile() = runBlocking<Unit> {
        quiet()
        val file = finishedImport()
        val notice = DownloadNotices.finished(file)
        assertEquals("Quiet Meridian · S01E02", notice.title)
        assertEquals(NotificationCompat.VISIBILITY_SECRET, notice.visibility)
        val launcher = connectKitchen()
        val play = notifications.build(notice).actions.single()
        assertEquals("Play", play.title.toString())
        tap(play.actionIntent)
        val main = waitFor("the app opened by its Play") { resumed<MainActivity>() }
        assertEquals(DownloadAsk.Play(file.id), onMain { main.downloadAsked })
        waitFor("the file handed to Kitchen display", READY_MILLIS) { launcher.loads.firstOrNull() }
        val launch = launcher.loads.single()
        assertEquals(PlayUris.download(file.id), launch.uri)
        assertEquals("Kitchen display", launch.cast?.name)
        assertNull("nothing played here", resumed<PlayerActivity>())
        assertEquals("indexer grabs", 0, grabs.get())
        launcher.plays()
    }

    /**
     * L418, L437: the show's Finished notification's Play, with Kitchen display connected, opens the show's downloads,
     * which cast its next downloaded episode's file there (S01E01, nothing watched), nothing played here or grabbed.
     */
    @Test
    fun theShowsPlayWithKitchenDisplayConnectedCastsItsNextDownloadedEpisode() = runBlocking<Unit> {
        quiet()
        val e1 = underWay(1)
        val e2 = underWay(2)
        waitFor("both under way in the runner's state") { container.downloads.state.value.count { it.row.id in setOf(e1.id, e2.id) && it.row.state == DownloadState.Running }.takeIf { it == 2 } }
        delay(500)
        val first = written(e1)
        val latest = written(e2)
        val shown = waitFor("the show's notification") { shown(PLAYED)?.takeIf { title(it) == "$PLAYED_NAME · 2 of 2 downloaded" } }
        val launcher = connectKitchen()
        tap(shown.notification.actions.single().actionIntent)
        val main = waitFor("the app opened by its Play") { resumed<MainActivity>() }
        assertEquals(DownloadAsk.PlayShow(latest.id), onMain { main.downloadAsked })
        waitFor("the episode's file handed to Kitchen display", READY_MILLIS) { launcher.loads.firstOrNull() }
        val launch = launcher.loads.single()
        assertEquals(PlayUris.download(first.id), launch.uri)
        assertEquals("Kitchen display", launch.cast?.name)
        assertNull("nothing played here", resumed<PlayerActivity>())
        assertEquals("indexer grabs", 0, grabs.get())
        launcher.plays()
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

    /** [id]'s download finished just now, as the writer leaves one: Done, written whole. */
    private suspend fun finish(id: String) {
        val row = dao.get(id)!!
        val at = maxOf(System.currentTimeMillis(), row.updatedAt + 1)
        dao.update(row.copy(state = DownloadState.Done, safeOffset = row.size!!, completedAt = at, updatedAt = at))
    }

    /**
     * Nothing searched for, grabbed or asked of TMDB while the app is open: the indexers off, the grab seam refusing (and
     * counting), TMDB's seams a strict fake knowing only [PLAYED], and the player's lookups none.
     */
    private suspend fun quiet() {
        // The debug build's start saves its dev settings: before the test reads or takes any.
        withTimeout(SEEDING_MILLIS) { container.devSettingsSaved.await() }
        saved = container.settings.settings.first()
        container.settings.indexers.first().filter { it.enabled }.forEach { container.settings.setIndexerEnabled(it.id, false) }
        tmdb = FakeTmdb(
            shows = mapOf(
                PLAYED to ShowDetails(
                    PLAYED, PLAYED_NAME, null, 2024, null, null, null, "Ended", false, 6, listOf(SeasonSummary(1, null, 6, LocalDate.now().minusDays(400), null)),
                    lastEpisode = EpisodeSummary(1, 6, "Slack Water", LocalDate.now().minusDays(300)), nextEpisode = null, ids = ExternalIds(),
                ),
            ),
            strict = true,
        )
        PlaybackCoordinator.fetchNzb = { _, _, _ ->
            grabs.incrementAndGet()
            throw FetchException("Not to be fetched", stopsAll = true)
        }
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
    }

    /** Episode [episode] of Quiet Meridian's season 1 under way, its release kept as Resolve keeps one, first in the queue. */
    private suspend fun underWay(episode: Int): Download {
        val name = "Quiet.Meridian.S01E0$episode.720p.WEB.h264-WREN.mkv"
        val now = System.currentTimeMillis()
        val first = base ?: ((dao.all().first().filter { it.id !in made }.minOfOrNull { it.position } ?: 0) - 100).also { base = it }
        val row = Download(
            id = Download.newId(), watchKey = WatchKey.episode(PLAYED, 1, episode), kind = DownloadKind.Episode, title = PLAYED_NAME, subtitle = "S01E0$episode",
            showTmdbId = PLAYED, season = 1, episode = episode, seasonEpisodes = 6, state = DownloadState.Running, position = first + episode,
            release = RememberedRelease("idx-notify", "qm-10$episode", name.removeSuffix(".mkv"), null, 0, "nzb-qm-10$episode", name), fileName = name,
            createdAt = now, updatedAt = now,
        )
        made += row.id
        container.downloadStorage.add(row)
        return row
    }

    /** Copper Crown downloaded, its row Done with its release kept as Resolve keeps one, as the runner leaves one (no file: it is only cast's fakes'). */
    private suspend fun finishedFilm(): Download {
        val name = "Copper.Crown.2022.720p.WEB.h264-WREN.mkv"
        val now = System.currentTimeMillis()
        val row = Download(
            id = Download.newId(), watchKey = WatchKey.movie(FILM), kind = DownloadKind.Movie, title = "Copper Crown", subtitle = "2022", movieTmdbId = FILM,
            year = 2022, state = DownloadState.Done, position = 0, release = RememberedRelease("idx-notify", "cc-2022", name.removeSuffix(".mkv"), null, 0, "nzb-cc-2022", name, SIZE),
            fileName = name, size = SIZE, indexerSize = SIZE, safeOffset = SIZE, createdAt = now, completedAt = now, updatedAt = now,
        )
        made += row.id
        container.downloadStorage.add(row)
        waitFor("the film among the finished downloads") { container.downloads.done.value[row.watchKey]?.takeIf { it.id == row.id } }
        return row
    }

    /**
     * S01E02 of a season pack opened on Import, downloaded, its row Done with Import's release kept as Resolve keeps one
     * (L423), as the runner leaves one (no file: it is only cast's fakes').
     */
    private suspend fun finishedImport(): Download {
        val name = "Quiet.Meridian.S01E02.720p.WEB.h264-WREN.mkv"
        val title = "Quiet.Meridian.S01.720p.WEB.h264-WREN"
        val now = System.currentTimeMillis()
        val row = Download(
            id = Download.newId(), watchKey = WatchKey.release(IMPORTED_NZB, 1), kind = DownloadKind.Release, title = name, subtitle = title,
            state = DownloadState.Done, position = 0, candidates = listOf(DownloadCandidate(IMPORTED, IMPORTED_NZB, title, size = SIZE)),
            release = RememberedRelease(IMPORTED, IMPORTED_NZB, title, null, 1, IMPORTED_NZB, name, SIZE), fileName = name, size = SIZE, indexerSize = SIZE,
            safeOffset = SIZE, createdAt = now, completedAt = now, updatedAt = now,
        )
        made += row.id
        container.downloadStorage.add(row)
        waitFor("the file among the finished downloads") { container.downloads.done.value[row.watchKey]?.takeIf { it.id == row.id } }
        return row
    }

    /** Kitchen display's session connected and the device chosen in Play on (Cast's fakes); the launcher the cast is handed to. */
    private suspend fun connectKitchen(): FakeLauncher {
        cast = true
        val launcher = FakeLauncher()
        val connector = FakeConnector()
        CastFakes.started(FakeRoutes(), connector, CopyOnWriteArrayList())
        CastSeams.launcher = { launcher }
        CastFakes.connected(kitchen, connector)
        container.playOn.choose(PlayChoice.Cast(kitchen.id, kitchen.name))
        val connected = PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts)
        waitFor("Kitchen display connected") { Unit.takeIf { container.playOn.target.value == connected } }
        onMain { container.playback.withdraw() }
        return launcher
    }

    /** [row]'s file written whole (the device tests' chapters.mkv) and renamed done, then its row Done, as the writer leaves one. */
    private suspend fun written(row: Download): Download {
        val storage = container.downloadStorage
        val bytes = DownloadFixtures.media(MEDIA)
        val name = checkNotNull(row.fileName)
        storage.open(row.id, name, bytes.size.toLong()).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        storage.finish(row.id, name)
        val size = bytes.size.toLong()
        val at = System.currentTimeMillis()
        val done = row.copy(
            state = DownloadState.Done, release = checkNotNull(row.release).copy(fileSize = size), size = size, indexerSize = size, safeOffset = size,
            relativePath = storage.relativePath(row.id, name), completedAt = at, updatedAt = at,
        )
        dao.update(done)
        return done
    }

    /** [intent] sent as a tap sends it, with the sender's leave to start an activity, as the system's tap has (Android 14's mode). */
    private fun tap(intent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            val options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            intent.send(context, 0, null, null, null, null, options.toBundle())
        } else {
            intent.send()
        }
    }

    /** The words [activity]'s Compose views show now, as their semantics say them. */
    private fun words(activity: Activity): List<String> = onMain {
        views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }
            .flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
    }

    private fun views(view: View): List<View> = listOf(view) + ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { views(group.getChildAt(it)) } } ?: emptyList())

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    /** The show [show]'s Finished notification, as the manager holds it. */
    private fun shown(show: Int): StatusBarNotification? = active(DownloadNotifications.DONE_ID, DownloadNotice.showTag(show))

    private fun title(n: StatusBarNotification): String = n.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun text(n: StatusBarNotification): String = n.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private inline fun <reified A : Activity> resumed(): A? = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<A>().firstOrNull()
    }

    /** Finishes every [A] there is, in any stage. */
    private inline fun <reified A : Activity> finishAll() = instrumentation.runOnMainSync {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED).flatMap(monitor::getActivitiesInStage).filterIsInstance<A>().forEach { it.finish() }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
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

        /** Another invented show, whose notification is only built. */
        const val OTHER = 900_772

        /** The invented show whose notification's Play the test sends. */
        const val PLAYED = 900_773
        const val PLAYED_NAME = "Quiet Meridian"

        /** The invented film whose notification's Play the test sends. */
        const val FILM = 900_774

        /** The invented NZB opened on Import whose file's notification's Play the test sends. */
        const val IMPORTED_NZB = "download-notification-device-test-nzb"

        /** The device tests' film with chapters: six minutes at 10 fps, 0.7 MB. */
        const val MEDIA = "chapters.mkv"
        const val READY_MILLIS = 30_000L
        const val PAGE_MILLIS = 15_000L
        const val SEEDING_MILLIS = 60_000L
    }
}
