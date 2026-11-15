package io.skjaere.usenet.app.download

import androidx.core.app.NotificationCompat
import io.skjaere.usenet.app.download.DownloadNotice.Action
import io.skjaere.usenet.app.download.DownloadNotice.Kind
import io.skjaere.usenet.app.download.DownloadNotice.Progress
import io.skjaere.usenet.app.download.DownloadNotice.Tap
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The download notifications as the app posts them (L358, L380, L387, C4; DlPhoneNotification): the ongoing one, from the
 * runner's state, with its words by state, how far it is, what else is queued and its actions; the status one a host's end
 * leaves; Finished and Failed; nothing of any on a secure lock screen; never a release's name; and the ongoing one
 * updated at most once a second, on the test's virtual clock. The show, the film and the release are invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadNotificationTextTest {
    @Test
    fun `the ongoing one says the show and episode, how far it is, how long is left and what else is queued`() {
        val notice = ongoing(item(episode(DownloadState.Running), bytesPerSecond = RATE), item(episode(DownloadState.Queued, id = "b")), item(episode(DownloadState.Queued, id = "c")))
        assertEquals(Kind.Ongoing, notice.kind)
        assertEquals(DownloadNotice.DOWNLOADS_CHANNEL, notice.channel)
        assertEquals("Harbor Lights · S02E04", notice.title)
        assertEquals("Downloading · 0.5 of 1.2 GB · 2 min left", notice.text)
        assertEquals("2 more queued", notice.subText)
        assertEquals(Progress(permille = 417, indeterminate = false), notice.progress)
        assertEquals(listOf(Action.Pause, Action.Cancel), notice.actions)
        assertEquals(Tap.Downloads, notice.tap)
        assertEquals(NotificationCompat.CATEGORY_PROGRESS, notice.category)
        assertTrue(notice.ongoing)
        assertTrue(notice.onlyAlertOnce)
        assertEquals(DownloadNotice.Icon.Download, notice.icon)
        assertEquals("a", notice.rowId)
    }

    @Test
    fun `the running one is said first, a film by its title, and nothing more queued says nothing`() {
        val film = episode(DownloadState.Running, id = "f").copy(kind = DownloadKind.Movie, title = "Copper Crown", season = null, episode = null, watchKey = WatchKey.movie(900003))
        val notice = ongoing(item(episode(DownloadState.Queued)), item(film))
        assertEquals("Copper Crown", notice.title)
        assertEquals("f", notice.rowId)
        assertEquals("1 more queued", notice.subText)
        assertNull(ongoing(item(film)).subText)
        // Finished and failed ones are past: neither is queued, nor said as the one under way.
        assertNull(ongoing(item(film), item(episode(DownloadState.Failed, id = "x")), item(episode(DownloadState.Done, id = "y"))).subText)
        assertNull(DownloadNotices.ongoing(listOf(item(episode(DownloadState.Done)), item(episode(DownloadState.Failed, id = "b")))))
        assertNull(DownloadNotices.ongoing(emptyList()))
    }

    @Test
    fun `each state's words, bar and actions, as drawn`() {
        fun check(row: Download, text: String, progress: Progress?, vararg actions: Action) {
            val notice = ongoing(item(row))
            assertEquals(text, notice.text, "${row.state} ${row.reason}")
            assertEquals(progress, notice.progress, "${row.state} ${row.reason}")
            assertEquals(actions.toList(), notice.actions, "${row.state} ${row.reason}")
        }
        val half = Progress(417, indeterminate = false)
        val spinning = Progress(0, indeterminate = true)
        check(episode(DownloadState.Paused), "Paused · 0.5 of 1.2 GB", half, Action.Resume, Action.Cancel)
        check(episode(DownloadState.Waiting, reason = DownloadHostRules.WAITING_FOR_WIFI), "Waiting for Wi-Fi · 0.5 of 1.2 GB", half, Action.Cancel)
        check(episode(DownloadState.Waiting, reason = DownloadHostRules.WAITING_TO_CHARGE), "Waiting to charge · 0.5 of 1.2 GB", half, Action.Cancel)
        check(episode(DownloadState.Resolving), "Finding releases…", spinning, Action.Cancel)
        check(
            episode(DownloadState.Resolving, reason = "Trying release 2 of 3 · Checking availability"), "Trying release 2 of 3 · Checking availability", spinning,
            Action.Pause, Action.Cancel,
        )
        check(episode(DownloadState.Queued), "Queued", spinning, Action.Cancel)
        check(episode(DownloadState.Stopped, reason = DownloadHostRules.STOPPED_BY_ANDROID), "Stopped by Android", null, Action.Resume)
        // The waits a host ends on: what is wrong, and a tap that opens Downloads.
        listOf(DownloadHostRules.CANT_REACH_PROVIDER, DownloadHostRules.CHECK_PROVIDER, DownloadHostRules.CANT_REACH_INDEXER, DownloadHostRules.STORAGE_UNAVAILABLE).forEach {
            check(episode(DownloadState.Waiting, reason = it), it, null)
        }
        // The writer backing off: why, how far, and the queue's Pause.
        check(
            episode(DownloadState.Running, reason = DownloadHostRules.CANT_REACH_PROVIDER), "Can't reach your Usenet provider · 0.5 of 1.2 GB", half,
            Action.Pause, Action.Cancel,
        )
        // Before Resolve knows the size, the indexer's; with none, the bar turns.
        check(episode(DownloadState.Running).copy(size = null, indexerSize = 0), "Downloading", spinning, Action.Pause, Action.Cancel)
    }

    @Test
    fun `the status one a host's end leaves says what holds the queue or waits, and the actions for it`() {
        val paused = assertNotNull(DownloadNotices.status(listOf(item(episode(DownloadState.Queued, id = "q")), item(episode(DownloadState.Paused)))))
        assertEquals(Kind.Status, paused.kind)
        assertEquals(DownloadNotice.DOWNLOADS_CHANNEL, paused.channel)
        assertEquals("a", paused.rowId)
        assertEquals("Paused · 0.5 of 1.2 GB", paused.text)
        assertEquals("1 more queued", paused.subText)
        assertEquals(listOf(Action.Resume, Action.Cancel), paused.actions)
        assertFalse(paused.ongoing, "it can be swiped away")
        val wifi = assertNotNull(DownloadNotices.status(listOf(item(episode(DownloadState.Waiting, reason = DownloadHostRules.WAITING_FOR_WIFI)))))
        assertEquals("Waiting for Wi-Fi · 0.5 of 1.2 GB", wifi.text)
        assertEquals(listOf(Action.Cancel), wifi.actions)
        val stopped = assertNotNull(DownloadNotices.status(listOf(item(episode(DownloadState.Stopped, reason = DownloadHostRules.STOPPED_BY_ANDROID)))))
        assertEquals("Stopped by Android", stopped.text)
        assertEquals(listOf(Action.Resume), stopped.actions)
        listOf(DownloadHostRules.CANT_REACH_PROVIDER, DownloadHostRules.CHECK_PROVIDER, DownloadHostRules.CANT_REACH_INDEXER).forEach { reason ->
            val waits = assertNotNull(DownloadNotices.status(listOf(item(episode(DownloadState.Waiting, reason = reason)))), reason)
            assertEquals(reason, waits.text)
            assertEquals(emptyList(), waits.actions, reason)
            assertEquals(Tap.Downloads, waits.tap, reason)
        }
        // Nothing left to download, or only what failed: nothing to say.
        assertNull(DownloadNotices.status(listOf(item(episode(DownloadState.Done)), item(episode(DownloadState.Failed, id = "b")))))
        assertNull(DownloadNotices.status(emptyList()))
    }

    @Test
    fun `the service's ended wait says it is paused with no connection, a tap resuming it`() {
        val notice = DownloadNotices.tapToResume(episode(DownloadState.Waiting, reason = DownloadHostRules.WAITING_FOR_WIFI))
        assertEquals(Kind.Status, notice.kind)
        assertEquals("Harbor Lights · S02E04", notice.title)
        assertEquals("Paused: no connection · Tap to resume", notice.text)
        assertEquals(Tap.Resume, notice.tap)
    }

    @Test
    fun `a finished one says its size, or its parts missing, with Play, in Finished downloads`() {
        val done = DownloadNotices.finished(episode(DownloadState.Done))
        assertEquals(Kind.Finished, done.kind)
        assertEquals(DownloadNotice.FINISHED_CHANNEL, done.channel)
        assertEquals("Harbor Lights · S02E04", done.title)
        assertEquals("Downloaded · 1.2 GB", done.text)
        assertEquals(listOf(Action.Play), done.actions)
        assertEquals(Tap.Downloads, done.tap)
        assertEquals(DownloadNotice.Icon.Downloaded, done.icon)
        assertTrue(done.autoCancel)
        assertFalse(done.ongoing)
        assertNull(done.progress)
        assertEquals("Downloaded · 2 parts missing · 1.2 GB", DownloadNotices.finished(episode(DownloadState.Done, gaps = 2)).text)
    }

    @Test
    fun `a failed one says why in technical words, with Choose release`() {
        val failed = DownloadNotices.failed(episode(DownloadState.Failed, reason = DownloadResolver.INCOMPLETE, tried = 3))
        assertEquals(Kind.Failed, failed.kind)
        assertEquals(DownloadNotice.FINISHED_CHANNEL, failed.channel)
        assertEquals("Harbor Lights · S02E04", failed.title)
        assertEquals("Couldn't download it. Your provider is missing parts of all 3 releases tried.", failed.text)
        assertEquals(listOf(Action.ChooseRelease), failed.actions)
        assertEquals(Tap.Downloads, failed.tap)
        assertTrue(failed.autoCancel)
        assertEquals("Couldn't download it. Your provider is missing parts of this release.", DownloadNotices.failed(episode(DownloadState.Failed, reason = DownloadResolver.INCOMPLETE, tried = 1)).text)
        assertEquals("Couldn't download it. Won't fit.", DownloadNotices.failed(episode(DownloadState.Failed, reason = DownloadResolver.WONT_FIT, tried = 1)).text)
        assertEquals("Couldn't download it. Storage is full.", DownloadNotices.failed(episode(DownloadState.Failed, reason = "Storage is full", tried = 1)).text)
    }

    @Test
    fun `nothing of any shows on a secure lock screen`() {
        val notices = listOf(
            ongoing(item(episode(DownloadState.Running))),
            assertNotNull(DownloadNotices.status(listOf(item(episode(DownloadState.Paused))))),
            DownloadNotices.tapToResume(episode(DownloadState.Waiting)),
            DownloadNotices.finished(episode(DownloadState.Done)),
            DownloadNotices.failed(episode(DownloadState.Failed, reason = DownloadResolver.INCOMPLETE, tried = 3)),
        )
        notices.forEach { assertEquals(NotificationCompat.VISIBILITY_SECRET, it.visibility, "${it.kind}") }
    }

    @Test
    fun `no notification names the release`() {
        val rows = DownloadState.entries.flatMap { state ->
            listOf(null, DownloadResolver.INCOMPLETE, DownloadHostRules.WAITING_FOR_WIFI).map { episode(state, reason = it, tried = 2) }
        }
        val notices = rows.flatMap { row ->
            listOfNotNull(
                DownloadNotices.ongoing(listOf(item(row, bytesPerSecond = RATE), item(episode(DownloadState.Queued, id = "b")))),
                DownloadNotices.status(listOf(item(row))),
                DownloadNotices.tapToResume(row),
                DownloadNotices.finished(row),
                DownloadNotices.failed(row),
            )
        }
        assertTrue(notices.size > 20)
        notices.forEach { notice ->
            listOf(notice.title, notice.text, notice.subText.orEmpty()).forEach { line ->
                listOf("KESTREL", "1080p", "Harbor.Lights", "v.mkv", "idx").forEach { assertFalse(it in line, "${notice.kind}: \"$line\" holds \"$it\"") }
            }
        }
    }

    @Test
    fun `the ongoing one is posted at most once a second, the latest state each time`() = runTest {
        val state = MutableStateFlow(listOf(item(episode(DownloadState.Running), written = 0)))
        val posted = mutableListOf<Pair<Long, String?>>()
        val pacing = launch { DownloadNotices.paced(state) { notice -> posted += testScheduler.currentTime to notice?.text } }
        runCurrent()
        // A write's progress every 100 ms for 2.5 s.
        repeat(25) { step ->
            advanceTimeBy(100)
            state.value = listOf(item(episode(DownloadState.Running), written = (step + 1) * 20_000_000L))
            runCurrent()
        }
        advanceTimeBy(1_000)
        runCurrent()
        pacing.cancel()
        val times = posted.map { it.first }
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), times, "$posted")
        // Each post the state as it was then; the last the last state.
        assertEquals("Downloading · 0.0 of 1.2 GB", posted.first().second)
        assertEquals("Downloading · 0.5 of 1.2 GB", posted.last().second)
    }

    @Test
    fun `the same notice twice is posted once`() = runTest {
        val state = MutableStateFlow(listOf(item(episode(DownloadState.Paused))))
        var posts = 0
        val pacing = launch { DownloadNotices.paced(state) { posts++ } }
        runCurrent()
        repeat(5) { step ->
            advanceTimeBy(2_000)
            // The row saved again, and its bytes a second gone: the same words, bar and actions.
            state.value = listOf(item(episode(DownloadState.Paused).copy(updatedAt = step + 2L), bytesPerSecond = null))
            runCurrent()
        }
        pacing.cancel()
        assertEquals(1, posts)
    }

    @Test
    fun `finished and failed ones are posted as a download comes to them, and withdrawn as it leaves them`() {
        val watch = DownloadNotices.Watch()
        // What the process finds at its start was there before: nothing is said of it again.
        assertEquals(emptyList(), watch.next(listOf(episode(DownloadState.Done, id = "old"), episode(DownloadState.Running))))
        assertEquals(emptyList(), watch.next(listOf(episode(DownloadState.Done, id = "old"), episode(DownloadState.Running))))
        val done = watch.next(listOf(episode(DownloadState.Done, id = "old"), episode(DownloadState.Done), episode(DownloadState.Running, id = "b")))
        assertEquals(listOf(DownloadNotices.Change.Post(DownloadNotices.finished(episode(DownloadState.Done)))), done)
        val failed = watch.next(listOf(episode(DownloadState.Done, id = "old"), episode(DownloadState.Done), episode(DownloadState.Failed, id = "b", reason = DownloadResolver.INCOMPLETE, tried = 3)))
        assertEquals(listOf(DownloadNotices.Change.Post(DownloadNotices.failed(episode(DownloadState.Failed, id = "b", reason = DownloadResolver.INCOMPLETE, tried = 3)))), failed)
        // Tried again, removed (hidden at once, L379) or gone: what was said of it goes.
        val left = watch.next(listOf(episode(DownloadState.Deleting), episode(DownloadState.Queued, id = "b")))
        assertEquals(setOf(DownloadNotices.Change.Withdraw("a"), DownloadNotices.Change.Withdraw("b"), DownloadNotices.Change.Withdraw("old")), left.toSet())
        assertEquals(emptyList(), watch.next(emptyList()))
    }

    private fun ongoing(vararg items: DownloadRunner.Item): DownloadNotice = assertNotNull(DownloadNotices.ongoing(items.toList()))

    private fun item(row: Download, written: Long = row.safeOffset, bytesPerSecond: Long? = null) = DownloadRunner.Item(row, written, bytesPerSecond)

    /** Harbor Lights S02E04, 1.2 GB, half way: its release named as a release is, which no notification says. */
    private fun episode(state: DownloadState, id: String = "a", reason: String? = null, gaps: Int = 0, tried: Int = 0) = Download(
        id = id, watchKey = WatchKey.episode(900002, 2, 4), kind = DownloadKind.Episode, title = "Harbor Lights", subtitle = "S02E04 · Low Water",
        showTmdbId = 900002, season = 2, episode = 4, episodeName = "Low Water", state = state, reason = reason, position = 0,
        candidates = listOf(DownloadCandidate("idx", "g1", RELEASE, size = 1_200_000_000)), tried = (1..tried).map { "idx:g$it" },
        release = RememberedRelease("idx", "g1", RELEASE, fileIndex = 0, fileName = "$RELEASE.mkv", fileSize = 1_200_000_000),
        fileName = "$RELEASE.mkv", size = 1_200_000_000, indexerSize = 1_190_000_000, safeOffset = HALF,
        gaps = (0 until gaps).map { DownloadGap(it * 10_000_000L, it * 10_000_000L + 749_999) }, relativePath = "downloads/$id/v.mkv",
        createdAt = 1, updatedAt = 1,
    )

    private companion object {
        const val RELEASE = "Harbor.Lights.S02E04.1080p.WEB-DL.H.265-KESTREL"
        const val HALF = 500_000_000L

        /** 700 MB to go: 2 min. */
        const val RATE = 5_833_334L
    }
}
