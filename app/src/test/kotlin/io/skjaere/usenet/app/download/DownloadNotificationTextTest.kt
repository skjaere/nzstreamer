package io.skjaere.usenet.app.download

import androidx.core.app.NotificationCompat
import io.skjaere.usenet.app.download.DownloadNotice.Action
import io.skjaere.usenet.app.download.DownloadNotice.Kind
import io.skjaere.usenet.app.download.DownloadNotice.Progress
import io.skjaere.usenet.app.download.DownloadNotice.Tap
import io.skjaere.usenet.app.play.IMPORTED
import io.skjaere.usenet.app.play.IMPORT_GONE
import io.skjaere.usenet.app.play.ReleaseName
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.releases.ReleaseKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The download notifications as the app posts them (L358, L380, L387, C4; DlPhoneNotification): the ongoing one, from the
 * runner's state, with its words by state, how far it is, what else is queued and its actions; the status one a host's end
 * leaves; Finished and Failed; one Finished notification per show (L437, L445); nothing of any on a secure lock screen;
 * never a release's name; and the ongoing one updated at most once a second, on the test's virtual clock. The show, the
 * film and the release are invented.
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

    /**
     * L399, L434: a download waiting on its indexer's limit says the limit's fixed words alone, the ongoing one and the
     * status one a host's end leaves, with no bar and no action (only Resume in the app goes on with it); never the
     * indexer, which only the Downloads page names from the row's limit wait.
     */
    @Test
    fun `waiting on its indexer's limit, it says the limit's words alone and never the indexer`() {
        val row = episode(DownloadState.Waiting, reason = DownloadHostRules.LIMIT_REACHED)
            .copy(limitWait = DownloadColumns.text(LimitWait("idx-plover", LimitWait.Kind.Search, 1, 2)))
        listOf(ongoing(item(row)), assertNotNull(DownloadNotices.status(listOf(item(row))))).forEach { notice ->
            assertEquals("Harbor Lights · S02E04", notice.title)
            assertEquals("Your indexer's limit is reached", notice.text)
            assertNull(notice.progress)
            assertEquals(emptyList(), notice.actions)
            listOf(notice.title, notice.text, notice.subText.orEmpty()).forEach { line ->
                listOf("plover", "idx-", "search limit", "download limit").forEach { assertFalse(it in line, "\"$line\" holds \"$it\"") }
            }
        }
    }

    /**
     * L471, L474: a debrid download's notification says its waits and failures as its row keeps them: the stream's wait
     * with how far it is ("Waits while something streams · 0.6 of 1.3 GB", the bar and the queue's Pause, as the provider
     * out of reach is said while the writer backs off); the account's waits their words alone; its failures in their
     * words. While debrid is hidden (L422), a failed one's debrid reason reads "Not available in this version", a Usenet
     * reason as it is. No word names a service, and none shows on a secure lock screen.
     */
    @Test
    fun `a debrid download's waits and failures are said in their words, and hidden its debrid failure is not available`() {
        val torrent = episode(DownloadState.Running, reason = DownloadHostRules.WAITS_FOR_STREAM).copy(size = 1_300_000_000, indexerSize = 1_300_000_000)
        val streaming = ongoing(item(torrent, written = 600_000_000))
        assertEquals("Waits while something streams · 0.6 of 1.3 GB", streaming.text)
        assertEquals(Progress(462, indeterminate = false), streaming.progress)
        assertEquals(listOf(Action.Pause, Action.Cancel), streaming.actions)
        assertEquals("Can't reach your debrid service · 0.6 of 1.3 GB", ongoing(item(torrent.copy(reason = DownloadHostRules.CANT_REACH_SERVICE), written = 600_000_000)).text)
        DownloadHostRules.SERVICE_WAITS.forEach { reason ->
            val notice = ongoing(item(torrent.copy(state = DownloadState.Waiting, reason = reason)))
            assertEquals(reason, notice.text)
            assertNull(notice.progress, reason)
        }
        val failures = listOf(
            DownloadHostRules.SERVICE_KEPT_FAILING, DownloadHostRules.CANT_RESUME, DownloadResolver.NO_LONGER_CACHED, "Removed by the debrid service",
            "Too large for your debrid plan", "The debrid service won’t play this release", "No longer available from the service",
        )
        failures.forEach { reason ->
            val row = episode(DownloadState.Failed, reason = reason, tried = 1)
            assertEquals("Couldn't download it. $reason.", DownloadNotices.failed(row, shown = true).text)
            assertEquals("Couldn't download it. Not available in this version.", DownloadNotices.failed(row, shown = false).text, reason)
        }
        assertEquals("Couldn't download it. Won't fit.", DownloadNotices.failed(episode(DownloadState.Failed, reason = DownloadResolver.WONT_FIT, tried = 1), shown = false).text)
        assertEquals(
            "Couldn't download it. Your provider is missing parts of this release.",
            DownloadNotices.failed(episode(DownloadState.Failed, reason = DownloadResolver.INCOMPLETE, tried = 1), shown = false).text,
        )
        val notices = listOf(streaming) + failures.map { DownloadNotices.failed(episode(DownloadState.Failed, reason = it, tried = 1), shown = true) }
        notices.forEach { notice ->
            assertEquals(NotificationCompat.VISIBILITY_SECRET, notice.visibility)
            listOf(notice.title, notice.text).forEach { line -> ServiceKind.entries.forEach { assertFalse(it.displayName in line, line) } }
        }
    }

    /**
     * L472: a file downloaded from Import is named in its notifications as its row names it, "Quiet Meridian · S01E02",
     * its title alone where its name says neither episode nor year, never its file's or its NZB's whole name; finished it
     * is a notification of its own with Play, never a show's; failed it offers no Choose release (nothing is chosen for it,
     * L454), a tap opening the Downloads page, where Try again is. Still on no secure lock screen.
     */
    @Test
    fun `a file from Import is named as its row names it, with no Choose release when it fails`() {
        val running = ongoing(item(imported(DownloadState.Running)))
        assertEquals("Quiet Meridian · S01E02", running.title)
        val done = DownloadNotices.finished(imported(DownloadState.Done))
        assertEquals("Quiet Meridian · S01E02", done.title)
        assertEquals(listOf(Action.Play), done.actions)
        assertNull(done.show)
        val failed = DownloadNotices.failed(imported(DownloadState.Failed, reason = IMPORT_GONE))
        assertEquals("Quiet Meridian · S01E02", failed.title)
        assertEquals("Couldn't download it. $IMPORT_GONE.", failed.text)
        assertEquals(emptyList(), failed.actions)
        assertEquals(Tap.Downloads, failed.tap)
        assertEquals("Copper Crown · 2019", DownloadNotices.finished(imported(DownloadState.Done, "Copper.Crown.2019.1080p.WEB.h264-WREN.mkv")).title)
        // Its name saying neither: its title alone.
        assertEquals(ReleaseName("quiet meridian extras.mkv").title, DownloadNotices.finished(imported(DownloadState.Done, "quiet meridian extras.mkv")).title)
        listOf(running, done, failed).forEach { notice ->
            assertEquals(NotificationCompat.VISIBILITY_SECRET, notice.visibility)
            listOf(notice.title, notice.text, notice.subText.orEmpty()).forEach { line ->
                listOf("WREN", "1080p", "Quiet.Meridian", ".mkv").forEach { assertFalse(it in line, "${notice.kind}: \"$line\" holds \"$it\"") }
            }
        }
        // Its finished one is its own, as a film's: no show's notification gathers it.
        val watch = DownloadNotices.Watch()
        watch.next(listOf(imported(DownloadState.Running)))
        assertEquals(listOf(DownloadNotices.Change.Post(done)), watch.next(listOf(imported(DownloadState.Done))))
    }

    @Test
    fun `nothing of any shows on a secure lock screen`() {
        val notices = listOf(
            ongoing(item(episode(DownloadState.Running))),
            assertNotNull(DownloadNotices.status(listOf(item(episode(DownloadState.Paused))))),
            DownloadNotices.tapToResume(episode(DownloadState.Waiting)),
            DownloadNotices.finished(episode(DownloadState.Done)),
            DownloadNotices.failed(episode(DownloadState.Failed, reason = DownloadResolver.INCOMPLETE, tried = 3)),
            // The show's one (L437, L445), of one episode and of several.
            assertNotNull(DownloadNotices.show(listOf(episode(DownloadState.Done)), SHOW)),
            assertNotNull(DownloadNotices.show(season(1 to DownloadState.Done, 5 to DownloadState.Done, 6 to DownloadState.Running), SHOW)),
        )
        notices.forEach { assertEquals(NotificationCompat.VISIBILITY_SECRET, it.visibility, "${it.kind} ${it.title}") }
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
    fun `finished films and failed ones are posted as a download comes to them, and withdrawn as it leaves them`() {
        val watch = DownloadNotices.Watch()
        // What the process finds at its start was there before: nothing is said of it again.
        assertEquals(emptyList(), watch.next(listOf(film(DownloadState.Done, id = "old"), film(DownloadState.Running))))
        assertEquals(emptyList(), watch.next(listOf(film(DownloadState.Done, id = "old"), film(DownloadState.Running))))
        val done = watch.next(listOf(film(DownloadState.Done, id = "old"), film(DownloadState.Done), episode(DownloadState.Running, id = "b")))
        // A film keeps its own (L437), by its download's id.
        assertEquals(listOf(DownloadNotices.Change.Post(DownloadNotices.finished(film(DownloadState.Done)))), done)
        assertEquals("a", DownloadNotices.finished(film(DownloadState.Done)).tag)
        assertNull(DownloadNotices.finished(film(DownloadState.Done)).show)
        val failed = watch.next(listOf(film(DownloadState.Done, id = "old"), film(DownloadState.Done), episode(DownloadState.Failed, id = "b", reason = DownloadResolver.INCOMPLETE, tried = 3)))
        // A failed episode keeps its own too, with Choose release.
        val failure = DownloadNotices.failed(episode(DownloadState.Failed, id = "b", reason = DownloadResolver.INCOMPLETE, tried = 3))
        assertEquals(listOf(DownloadNotices.Change.Post(failure)), failed)
        assertEquals("b", failure.tag)
        // Tried again, removed (hidden at once, L379) or gone: what was said of it goes.
        val left = watch.next(listOf(film(DownloadState.Deleting), episode(DownloadState.Queued, id = "b")))
        assertEquals(setOf(DownloadNotices.Change.Withdraw("a"), DownloadNotices.Change.Withdraw("b"), DownloadNotices.Change.Withdraw("old")), left.toSet())
        assertEquals(emptyList(), watch.next(emptyList()))
    }

    /**
     * L437: a show's episodes finishing are one Finished notification, tagged by the show: "Harbor Lights · 3 of 5
     * downloaded" over the episodes and their size, Play playing the show's next and a tap opening its downloads, both
     * naming the show's latest finished download alone (L445).
     */
    @Test
    fun `a show's finished episodes are one notification, tagged by the show, in the drawn words`() {
        val first = assertNotNull(DownloadNotices.show(season(1 to DownloadState.Done, 5 to DownloadState.Running, 6 to DownloadState.Queued, 7 to DownloadState.Queued, 8 to DownloadState.Queued), SHOW))
        assertEquals(Kind.Finished, first.kind)
        assertEquals(DownloadNotice.FINISHED_CHANNEL, first.channel)
        assertEquals("Harbor Lights · 1 of 5 downloaded", first.title)
        assertEquals("S02E01 · 1.4 GB", first.text)
        assertNull(first.subText)
        assertEquals(listOf(Action.PlayShow), first.actions)
        assertEquals("Play", Action.PlayShow.label)
        assertEquals(Tap.Show, first.tap)
        assertEquals(DownloadNotice.Icon.Downloaded, first.icon)
        assertTrue(first.onlyAlertOnce)
        assertTrue(first.autoCancel)
        assertEquals(SHOW, first.show)
        assertEquals(DownloadNotice.showTag(SHOW), first.tag)
        assertEquals("e1", first.rowId)
        val third = assertNotNull(DownloadNotices.show(season(1 to DownloadState.Done, 5 to DownloadState.Done, 6 to DownloadState.Done, 7 to DownloadState.Running, 8 to DownloadState.Queued), SHOW))
        assertEquals("Harbor Lights · 3 of 5 downloaded", third.title)
        assertEquals("S02E01, S02E05, S02E06 · 4.2 GB", third.text)
        // Its intents name the latest finished, never the show.
        assertEquals("e6", third.rowId)
        assertEquals(first.tag, third.tag)
        // Every one of them finished: what the last post said, read from the rows.
        val all = assertNotNull(DownloadNotices.show(season(1 to DownloadState.Done, 5 to DownloadState.Done, 6 to DownloadState.Done, 7 to DownloadState.Done, 8 to DownloadState.Done), SHOW))
        assertEquals("Harbor Lights · 5 of 5 downloaded", all.title)
        assertEquals("5 episodes · 7.0 GB", all.text)
        // None finished, or another show's: nothing to say.
        assertNull(DownloadNotices.show(season(1 to DownloadState.Running, 5 to DownloadState.Queued), SHOW))
        assertNull(DownloadNotices.show(season(1 to DownloadState.Done), SHOW + 1))
        listOf(first, third, all).forEach { notice ->
            listOf(notice.title, notice.text, notice.tag.orEmpty(), notice.rowId).forEach { line ->
                listOf("KESTREL", "1080p", "Harbor.Lights", "v.mkv", "idx").forEach { assertFalse(it in line, "\"$line\" holds \"$it\"") }
            }
        }
    }

    @Test
    fun `past three episodes, how many and their size`() {
        val rows = season(
            1 to DownloadState.Done, 2 to DownloadState.Done, 3 to DownloadState.Done, 4 to DownloadState.Done, 5 to DownloadState.Done, 6 to DownloadState.Done,
            7 to DownloadState.Running, 8 to DownloadState.Queued,
        )
        val notice = assertNotNull(DownloadNotices.show(rows, SHOW))
        assertEquals("Harbor Lights · 6 of 8 downloaded", notice.title)
        assertEquals("6 episodes · 8.4 GB", notice.text)
    }

    @Test
    fun `one episode of a show alone says it as a finished one does, and is still the show's`() {
        val notice = assertNotNull(DownloadNotices.show(listOf(episode(DownloadState.Done)), SHOW))
        assertEquals("Harbor Lights · S02E04", notice.title)
        assertEquals("Downloaded · 1.2 GB", notice.text)
        assertEquals(listOf(Action.PlayShow), notice.actions)
        assertEquals(Tap.Show, notice.tap)
        assertEquals(DownloadNotice.showTag(SHOW), notice.tag)
        assertEquals("Downloaded · 2 parts missing · 1.2 GB", DownloadNotices.show(listOf(episode(DownloadState.Done, gaps = 2)), SHOW)?.text)
        // Queued again after its queue was empty: the new one alone, not the five before it.
        val again = season(1 to DownloadState.Done, 5 to DownloadState.Done, 6 to DownloadState.Done, 7 to DownloadState.Done, 8 to DownloadState.Done) +
            seasonEpisode(9, DownloadState.Done, createdAt = 500, completedAt = 600)
        val alone = assertNotNull(DownloadNotices.show(again, SHOW))
        assertEquals("Harbor Lights · S02E09", alone.title)
        assertEquals("e9", alone.rowId)
    }

    @Test
    fun `the count after a process start is read from the rows, the episodes finished before it counted`() {
        val watch = DownloadNotices.Watch()
        // The process starts with two finished and three to go: nothing said of what was there.
        assertEquals(emptyList(), watch.next(season(1 to DownloadState.Done, 5 to DownloadState.Done, 6 to DownloadState.Running, 7 to DownloadState.Queued, 8 to DownloadState.Queued)))
        val changes = watch.next(season(1 to DownloadState.Done, 5 to DownloadState.Done, 6 to DownloadState.Done, 7 to DownloadState.Running, 8 to DownloadState.Queued))
        val notice = assertIs<DownloadNotices.Change.Post>(changes.single()).notice
        assertEquals("Harbor Lights · 3 of 5 downloaded", notice.title)
    }

    @Test
    fun `the show's notification is updated in place as its episodes finish, and as one leaves, and withdrawn once none is finished`() {
        val watch = DownloadNotices.Watch()
        assertEquals(emptyList(), watch.next(season(1 to DownloadState.Running, 5 to DownloadState.Queued, 6 to DownloadState.Queued, 7 to DownloadState.Queued, 8 to DownloadState.Queued)))
        val first = assertIs<DownloadNotices.Change.Post>(
            watch.next(season(1 to DownloadState.Done, 5 to DownloadState.Running, 6 to DownloadState.Queued, 7 to DownloadState.Queued, 8 to DownloadState.Queued)).single(),
        ).notice
        assertEquals("Harbor Lights · 1 of 5 downloaded", first.title)
        // The next one: posted again under the same tag, which Android updates in place with no sound (only the first alerts).
        val second = assertIs<DownloadNotices.Change.Post>(
            watch.next(season(1 to DownloadState.Done, 5 to DownloadState.Done, 6 to DownloadState.Running, 7 to DownloadState.Queued, 8 to DownloadState.Queued)).single(),
        ).notice
        assertEquals("Harbor Lights · 2 of 5 downloaded", second.title)
        assertEquals("S02E01, S02E05 · 2.7 GB", second.text)
        assertEquals(first.tag, second.tag)
        assertTrue(second.onlyAlertOnce)
        // One removed (hidden at once, L379): said again where it still shows, never posted anew.
        val removed = season(1 to DownloadState.Deleting, 5 to DownloadState.Done, 6 to DownloadState.Running, 7 to DownloadState.Queued, 8 to DownloadState.Queued)
        val update = assertIs<DownloadNotices.Change.Update>(watch.next(removed).single()).notice
        assertEquals("Harbor Lights · 1 of 4 downloaded", update.title)
        assertEquals("S02E05 · 1.3 GB", update.text)
        assertEquals("e5", update.rowId)
        assertEquals(first.tag, update.tag)
        // The show's last finished one gone: taken back.
        val none = season(6 to DownloadState.Running, 7 to DownloadState.Queued, 8 to DownloadState.Queued)
        assertEquals(listOf(DownloadNotices.Change.Withdraw(DownloadNotice.showTag(SHOW))), watch.next(none))
        assertEquals(emptyList(), watch.next(none))
    }

    /**
     * L437: an episode removed, then brought back by its Undo, is counted again in the show's notification, said where it
     * still shows and never posted anew; one that was the show's last finished brings back nothing (its notification was
     * taken back, as a film's is). A film's or a failed episode's Undo says nothing again.
     */
    @Test
    fun `an episode brought back by its Undo is counted again in the show's notification, never posted anew`() {
        val watch = DownloadNotices.Watch()
        watch.next(season(1 to DownloadState.Running, 5 to DownloadState.Queued, 6 to DownloadState.Queued))
        watch.next(season(1 to DownloadState.Done, 5 to DownloadState.Running, 6 to DownloadState.Queued))
        watch.next(season(1 to DownloadState.Done, 5 to DownloadState.Done, 6 to DownloadState.Running))
        val removed = assertIs<DownloadNotices.Change.Update>(watch.next(season(1 to DownloadState.Done, 5 to DownloadState.Deleting, 6 to DownloadState.Running)).single()).notice
        assertEquals("Harbor Lights · 1 of 2 downloaded", removed.title)
        // Its Undo: counted again, as before it was removed.
        val back = assertIs<DownloadNotices.Change.Update>(watch.next(season(1 to DownloadState.Done, 5 to DownloadState.Done, 6 to DownloadState.Running)).single()).notice
        assertEquals("Harbor Lights · 2 of 3 downloaded", back.title)
        assertEquals("S02E01, S02E05 · 2.7 GB", back.text)
        assertEquals(DownloadNotice.showTag(SHOW), back.tag)
        // The show's only finished one removed, then brought back: said again where it shows, which it no longer does.
        assertEquals(
            listOf(DownloadNotices.Change.Withdraw(DownloadNotice.showTag(SHOW))),
            watch.next(season(1 to DownloadState.Deleting, 5 to DownloadState.Deleting, 6 to DownloadState.Running)),
        )
        val again = assertIs<DownloadNotices.Change.Update>(watch.next(season(1 to DownloadState.Done, 5 to DownloadState.Deleting, 6 to DownloadState.Running)).single()).notice
        assertEquals("Harbor Lights · 1 of 2 downloaded", again.title)

        // A film's and a failed episode's Undo: nothing said again.
        val other = DownloadNotices.Watch()
        val failed = episode(DownloadState.Failed, id = "b", reason = DownloadResolver.INCOMPLETE, tried = 3)
        other.next(listOf(film(DownloadState.Done), failed))
        assertEquals(
            setOf(DownloadNotices.Change.Withdraw("a"), DownloadNotices.Change.Withdraw("b")),
            other.next(listOf(film(DownloadState.Deleting), failed.copy(state = DownloadState.Deleting))).toSet(),
        )
        assertEquals(emptyList(), other.next(listOf(film(DownloadState.Done), failed)))
    }

    private fun ongoing(vararg items: DownloadRunner.Item): DownloadNotice = assertNotNull(DownloadNotices.ongoing(items.toList()))

    /** [file] of an NZB opened on Import (L423), 1.3 GB, as its download's row keeps it: titled by the file, no TMDB ids. */
    private fun imported(state: DownloadState, file: String = "Quiet.Meridian.S01E02.1080p.WEB.h264-WREN.mkv", reason: String? = null) = Download(
        id = "imp", watchKey = WatchKey.release("nzb-qm", 1), kind = DownloadKind.Release, title = file, subtitle = "Quiet.Meridian.S01.1080p.WEB.h264-WREN",
        state = state, reason = reason, position = 0, candidates = listOf(DownloadCandidate(IMPORTED, "nzb-qm", "Quiet.Meridian.S01.1080p.WEB.h264-WREN", size = 1_300_000_000)),
        tried = listOf(ReleaseKey.of(IMPORTED, "nzb-qm")).takeIf { state == DownloadState.Failed }.orEmpty(), fileName = file, size = 1_300_000_000,
        indexerSize = 1_300_000_000, safeOffset = if (state == DownloadState.Done) 1_300_000_000 else HALF, relativePath = "downloads/imp/v.mkv",
        createdAt = 1, completedAt = 2L.takeIf { state == DownloadState.Done }, updatedAt = 1,
    )

    /** Copper Crown, a film, 1.2 GB, as [episode] has it otherwise. */
    private fun film(state: DownloadState, id: String = "a") = episode(state, id).copy(
        kind = DownloadKind.Movie, watchKey = WatchKey.movie(900003), title = "Copper Crown", subtitle = null, showTmdbId = null, season = null,
        episode = null, episodeName = null, movieTmdbId = 900003,
    )

    /**
     * Episodes of Harbor Lights' season 2, each [Pair] its number and state, all queued together (a season's Download) and
     * finished in the order given: S02E01 1.4 GB, S02E05 1.3 GB, S02E06 1.5 GB, the others 1.4 GB.
     */
    private fun season(vararg episodes: Pair<Int, DownloadState>): List<Download> =
        episodes.mapIndexed { i, (number, state) -> seasonEpisode(number, state, createdAt = 10, completedAt = 20L + i) }

    private fun seasonEpisode(number: Int, state: DownloadState, createdAt: Long, completedAt: Long): Download {
        val size = when (number) {
            5 -> 1_300_000_000L
            6 -> 1_500_000_000L
            else -> 1_400_000_000L
        }
        return episode(state, id = "e$number").copy(
            watchKey = WatchKey.episode(SHOW, 2, number), subtitle = "S02E0$number", episode = number, episodeName = null, size = size, indexerSize = size,
            safeOffset = if (state == DownloadState.Done) size else 0, createdAt = createdAt, completedAt = completedAt.takeIf { state == DownloadState.Done },
            updatedAt = completedAt,
        )
    }

    private fun item(row: Download, written: Long = row.safeOffset, bytesPerSecond: Long? = null) = DownloadRunner.Item(row, written, bytesPerSecond)

    /** Harbor Lights S02E04, 1.2 GB, half way: its release named as a release is, which no notification says. */
    private fun episode(state: DownloadState, id: String = "a", reason: String? = null, gaps: Int = 0, tried: Int = 0) = Download(
        id = id, watchKey = WatchKey.episode(SHOW, 2, 4), kind = DownloadKind.Episode, title = "Harbor Lights", subtitle = "S02E04 · Low Water",
        showTmdbId = SHOW, season = 2, episode = 4, episodeName = "Low Water", state = state, reason = reason, position = 0,
        candidates = listOf(DownloadCandidate("idx", "g1", RELEASE, size = 1_200_000_000)), tried = (1..tried).map { "idx:g$it" },
        release = RememberedRelease("idx", "g1", RELEASE, fileIndex = 0, fileName = "$RELEASE.mkv", fileSize = 1_200_000_000),
        fileName = "$RELEASE.mkv", size = 1_200_000_000, indexerSize = 1_190_000_000, safeOffset = HALF,
        gaps = (0 until gaps).map { DownloadGap(it * 10_000_000L, it * 10_000_000L + 749_999) }, relativePath = "downloads/$id/v.mkv",
        createdAt = 1, updatedAt = 1,
    )

    private companion object {
        const val SHOW = 900002
        const val RELEASE = "Harbor.Lights.S02E04.1080p.WEB-DL.H.265-KESTREL"
        const val HALF = 500_000_000L

        /** 700 MB to go: 2 min. */
        const val RATE = 5_833_334L
    }
}
