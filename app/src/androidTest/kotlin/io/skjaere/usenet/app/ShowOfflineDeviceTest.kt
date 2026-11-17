package io.skjaere.usenet.app

import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.download.DownloadFixtures
import io.skjaere.usenet.app.download.DownloadJobService
import io.skjaere.usenet.app.download.DownloadNotifications
import io.skjaere.usenet.app.download.DownloadsPageSeeds
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.assertWhole
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.show
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import io.skjaere.usenet.player.PlayUris
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * L439: the Show page offline, set through the container's test override (L438; the device's Wi-Fi
 * is never touched), for the invented Harbor Lights, whose season 1's eight episodes have aired. S01E01 to S01E03 are
 * watched; S01E02 to S01E04 are downloaded, S01E04 with the device tests' chapters.mkv as its file. Offline, the line under
 * the bar stays as the list scrolls; the episodes not downloaded are greyed, read as dimmed, and a tap on one says why
 * in a bar that goes by itself; "Download 5 episodes" greys; an episode's ⋮ keeps Mark watched and greys Choose release
 * and Download; the header's Play greys once the next episode isn't downloaded; a downloaded episode plays its file
 * through the coordinator's hook; the show's line opens its downloads. With no page from TMDB (its seam refusing), the
 * show's downloads come under the error and open its downloads, online too. At twice the text size the line and the bar
 * are whole.
 *
 * TMDB's seam answers for the show; the app's indexers are off while a case runs, so nothing could be searched for.
 * Put back after each, whatever happened: the offline override, the player finished, the rows and their files deleted,
 * the downloads' jobs cancelled, the library's marks and the settings as they were.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ShowOfflineDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The player hides the system bars: Android's hint about that would take its window's focus. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val storage = container.downloadStorage
    private val seeds = DownloadsPageSeeds(context, container)
    private var tmdb: FakeTmdb? = null
    private val realFetch = PlaybackCoordinator.fetchNzb
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realLookUp = PlayerActivity.lookUpSegments
    private val grabs = AtomicInteger()
    private val made = mutableListOf<Download>()
    private val opened = CopyOnWriteArrayList<Int>()
    private val today = LocalDate.now()
    private var saved: AppSettings? = null

    private val names = listOf("Brine", "The Breakwater", "Fog Signal", "Low Water", "The Long Tide", "Salt and Iron", "Slack Water", "Undertow")

    private val harbor = ShowDetails(
        HL, "Harbor Lights", null, 2021, null, null, null, "Returning Series", true, 6, listOf(SeasonSummary(1, null, 8, today.minusDays(70), null)),
        lastEpisode = EpisodeSummary(1, 8, names[7], today.minusDays(21)), nextEpisode = null, ids = ExternalIds(),
    )

    private val season = Season(HL, 1, null, null, names.mapIndexed { i, name -> Episode(1, i + 1, name, null, today.minusDays(70L - 7 * i), 6, null) })

    @Before
    fun setUp() {
        assertFalse("a phone", container.television)
        runBlocking {
            // The debug build's start saves its dev settings: before the test reads or takes any.
            withTimeout(SEEDING_MILLIS) { container.devSettingsSaved.await() }
            saved = container.settings.settings.first()
            container.settings.indexers.first().filter { it.enabled }.forEach { container.settings.setIndexerEnabled(it.id, false) }
        }
        // Strict: anything else the app asked TMDB is refused, never sent.
        tmdb = FakeTmdb(shows = mapOf(HL to harbor), seasons = { id, n -> season.takeIf { id == HL && n == 1 } }, strict = true)
        PlaybackCoordinator.fetchNzb = { _, _, _ ->
            grabs.incrementAndGet()
            throw FetchException("Not to be fetched", stopsAll = true)
        }
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        seeds.before()
        // S01E02 and S01E03 as finished rows the play hook takes (no file: they are only marked); S01E04 with its file.
        listOf(2, 3).forEach { n ->
            val row = DownloadsPageSeeds.episode(1, n, DownloadState.Done, show = HL, title = "Harbor Lights")
            seeds.seed(row.copy(release = RememberedRelease(INDEXER, row.id, "Harbor.Lights.S01E0$n.720p.WEB.h264-KESTREL", null, 0, "nzb-${row.id}")))
        }
        seed(episode(4))
        runBlocking { container.library.markEpisodes((1..3).map(::ref), watched = true) }
        container.offlineOverride.value = true
        await("the container offline", 5_000) { container.offline.value.takeIf { it } }
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
        container.offlineOverride.value = null
        back { finishPlayers() }
        try {
            tmdb?.close()
        } catch (e: AssertionError) {
            // What the app asked of other titles was refused, never sent.
        }
        PlaybackCoordinator.fetchNzb = realFetch
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlayerActivity.lookUpSegments = realLookUp
        container.playback.cancel()
        back { seeds.after() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) back { DownloadJobService.scheduler(context).cancelAll() }
        val manager = context.getSystemService(NotificationManager::class.java)
        made.forEach { row ->
            back { storage.delete(row.id) }
            back { manager.cancel(row.id, DownloadNotifications.DONE_ID) }
        }
        back {
            // Unwatched again, out of Up next, the release the player kept for the one played forgotten.
            container.library.markEpisodes((1..8).map(::ref), watched = false)
            made.forEach { row -> row.release?.let { container.library.forgetRelease(row.watchKey, it) } }
        }
        saved?.let { back { container.settings.restore(it) } }
        failures.firstOrNull()?.let { first -> failures.drop(1).forEach(first::addSuppressed); throw first }
    }

    private fun ref(n: Int) = EpisodeRef(HL, 1, n, WatchTitle.episode("Harbor Lights", 1, n))

    /** Harbor Lights' page at [fontScale], the show's downloads recorded as opened; [shown] once that shows. */
    private fun showPage(fontScale: Float = 1f, shown: SemanticsMatcher = hasText("Play S01E04", substring = true)) {
        compose.show(context, fontScale) { ShowScreen(HL, onBack = {}, onDownloadsShow = { opened += it }) }
        compose.waitUntil(15_000) { exists(shown) }
    }

    @Test
    fun theLineStaysAsTheListScrollsAndWhatIsntDownloadedIsGreyedItsTapSayingWhy() {
        showPage()
        val line = hasText(OFFLINE_LINE)
        compose.waitUntil(5_000) { exists(line) }
        val before = compose.onNode(line).fetchSemanticsNode().boundsInRoot
        // Under the bar, above the page's list.
        val header = compose.onNode(hasText("Play S01E04", substring = true)).fetchSemanticsNode().boundsInRoot
        assertTrue("the line at $before, Play at $header", before.bottom <= header.top)
        scrollTo(row(names[7]))
        assertEquals("the line where it was", before, compose.onNode(line).fetchSemanticsNode().boundsInRoot)
        // Greyed and read as dimmed: those not downloaded; the downloaded ones as they were.
        listOf(1, 5, 6, 7, 8).forEach { n -> assertTrue("S01E0$n dimmed", dimmed(n)) }
        listOf(2, 3, 4).forEach { n -> assertFalse("S01E0$n dimmed", dimmed(n)) }
        // A tap says why, and plays nothing; the bar goes by itself.
        watchingStates { states ->
            scrollTo(row(names[4]))
            compose.onNode(row(names[4])).performClick()
            val bar = hasText("Harbor Lights S01E05 isn't downloaded. It plays once you're online.")
            compose.waitUntil(5_000) { exists(bar) }
            val shownAt = SystemClock.elapsedRealtime()
            compose.waitUntil(15_000) { !exists(bar) }
            assertTrue("the bar went after ${SystemClock.elapsedRealtime() - shownAt} ms", SystemClock.elapsedRealtime() - shownAt >= 2_000)
            assertTrue("the play sheet showed $states", states.all { it == PlayState.Idle })
        }
        // The season's Download greys, as its sheet searches first.
        scrollTo(seasonDownload)
        compose.onNode(seasonDownload).assertIsNotEnabled()
        assertEquals("indexer grabs", 0, grabs.get())
    }

    @Test
    fun anEpisodesMenuOfflineKeepsMarkWatchedAndGreysChooseReleaseAndDownload() {
        showPage()
        fun item(words: String) = hasText(words) and hasAnyAncestor(isPopup())
        fun menu(n: Int) {
            val more = hasContentDescription("More for episode $n")
            scrollTo(more)
            compose.onNode(more).performClick()
            compose.waitUntil(5_000) { exists(item("Choose release")) }
        }
        menu(5)
        compose.onNode(item("Choose release")).assertIsNotEnabled()
        compose.onNode(item("Download")).assertIsNotEnabled()
        compose.onNode(item("Mark watched")).assertIsEnabled().performClick()
        await("S01E05 marked watched") { runBlocking { container.library.state(WatchKey.episode(HL, 1, 5)).first() }?.takeIf { it.watched } }
        // A downloaded one's: its download's own entry kept.
        menu(4)
        compose.onNode(item("Choose release")).assertIsNotEnabled()
        compose.onNode(item("Mark watched")).assertIsEnabled()
        compose.onNode(item("Remove download")).assertIsEnabled()
    }

    @Test
    fun theHeadersPlayGreysOnceTheNextEpisodeIsntDownloaded() {
        showPage()
        compose.onNode(play("Play S01E04")).assertIsEnabled()
        runBlocking { container.library.markEpisodes(listOf(ref(4)), watched = true) }
        compose.waitUntil(10_000) { exists(play("Play S01E05")) }
        compose.onNode(play("Play S01E05")).assertIsNotEnabled()
        // Online, as it was.
        container.offlineOverride.value = false
        compose.waitUntil(5_000) { !exists(hasText(OFFLINE_LINE)) }
        compose.onNode(play("Play S01E05")).assertIsEnabled()
        assertFalse(dimmed(5))
        scrollTo(seasonDownload)
        compose.onNode(seasonDownload).assertIsEnabled()
    }

    @Test
    fun aDownloadedEpisodePlaysItsFileAndTheShowsLineOpensItsDownloads() {
        showPage()
        val line = hasContentDescription("3 downloaded · ", substring = true)
        compose.waitUntil(10_000) { exists(line) }
        compose.onNode(line).performClick()
        assertEquals(listOf(HL), opened.toList())
        val row = made.single()
        scrollTo(row(names[3]))
        val launch = launched(row) { compose.onNode(row(names[3])).performClick() }
        assertEquals(WatchKey.episode(HL, 1, 4), launch.watchKey)
        playsTheFile(launch)
    }

    @Test
    fun withNoPageFromTmdbTheShowsDownloadsComeUnderTheErrorOnlineToo() {
        val fake = TmdbLookup.showAged
        TmdbLookup.showAged = { c, id -> if (id == HL) throw TmdbException(TmdbError.Network("Unable to resolve host")) else fake(c, id) }
        try {
            val card = hasText("Harbor Lights") and hasText("Next: S01E04")
            showPage(shown = card)
            assertTrue(exists(hasText("Try again")))
            assertTrue(exists(hasText(OFFLINE_LINE)))
            // Its header, then the show's row as the Downloads page draws it, under the error.
            val header = compose.onNode(hasText("DOWNLOADED")).fetchSemanticsNode().boundsInRoot
            val error = compose.onNode(hasText("Try again")).fetchSemanticsNode().boundsInRoot
            val row = compose.onNode(card).fetchSemanticsNode().boundsInRoot
            assertTrue("the error at $error, the header at $header, the row at $row", error.bottom <= header.top && header.bottom <= row.top)
            assertTrue(exists(hasText("3 episodes · ", substring = true)))
            compose.onNode(card).performClick()
            assertEquals(listOf(HL), opened.toList())
            // Online the page fails the same: the section stays, without the line.
            container.offlineOverride.value = false
            compose.waitUntil(5_000) { !exists(hasText(OFFLINE_LINE)) }
            assertTrue(exists(card))
        } finally {
            TmdbLookup.showAged = fake
        }
    }

    @Test
    fun atTwiceTheTextSizeTheLineAndTheBarAreWhole() {
        showPage(fontScale = 2f)
        val width = context.resources.displayMetrics.widthPixels
        compose.waitUntil(5_000) { exists(hasText(OFFLINE_LINE)) }
        compose.assertWhole(OFFLINE_LINE, width)
        scrollTo(row(names[4]))
        compose.onNode(row(names[4])).performClick()
        val words = "Harbor Lights S01E05 isn't downloaded. It plays once you're online."
        compose.waitUntil(5_000) { exists(hasText(words)) }
        compose.assertWhole(words, width)
    }

    /** An episode's row (its playing part, not its ⋮), by its name. */
    private fun row(name: String) = hasText(name) and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick)

    private fun play(label: String) = hasText(label, substring = true) and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick)

    private val seasonDownload = hasText("Download 5 episodes")

    /** The page's own list, not the season chips' row, which scrolls too. */
    private val pageList = hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.waitForIdle()
        compose.onNode(pageList).performScrollToNode(matcher)
    }

    /** Whether S01E0[n]'s row is read as dimmed. */
    private fun dimmed(n: Int): Boolean {
        scrollTo(row(names[n - 1]))
        return compose.onNode(row(names[n - 1])).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) != null
    }

    /** [block] given the play sheet's states as they come, from the state now on. */
    private fun <T> watchingStates(block: (List<PlayState>) -> T): T {
        val seen = CopyOnWriteArrayList<PlayState>()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { container.playback.state.collect { seen += it } }
        try {
            await("the play sheet's state watched") { seen.firstOrNull() }
            return block(seen)
        } finally {
            scope.cancel()
        }
    }

    /** What [tap] launches, checked as a download's play: [row]'s file by its URI, the sheet never past Idle, nothing grabbed. */
    private fun launched(row: Download, tap: () -> Unit): PlayerLaunch = watchingStates { states ->
        tap()
        val launch = runBlocking { withTimeout(LAUNCH_MILLIS) { container.playback.launches.filter { it.watchKey == row.watchKey }.first() } }
        assertEquals(PlayUris.download(row.id), launch.uri)
        assertNull("no request behind it", launch.requestId)
        assertTrue("the play sheet showed $states", states.all { it == PlayState.Idle })
        assertEquals("indexer grabs", 0, grabs.get())
        launch
    }

    /** Opens the player on [launch], as MainActivity does, and waits for it to play the download's file. */
    private fun playsTheFile(launch: PlayerLaunch) {
        val startedAt = System.currentTimeMillis()
        instrumentation.runOnMainSync { compose.activity.startActivity(PlayerActivity.intent(context, launch)) }
        val player = await("the player ready on the file", READY_MILLIS) { onMain { player()?.takeIf { it.player?.playbackState == Player.STATE_READY } } }
        assertEquals(launch.uri, onMain { player.player!!.currentMediaItem?.localConfiguration?.uri?.toString() })
        onMain { player.finish() }
        // The player saves where it was as it closes: let it, before the test's end takes the marks back.
        await("the player's save on closing") { runBlocking { container.library.state(launch.watchKey).first() }?.takeIf { it.lastPlayedAt >= startedAt } }
    }

    /** [row] as a finished download is left: chapters.mkv written as its file and renamed done, then its row, Done, with its marker. */
    private fun seed(row: Download): Download = runBlocking {
        val bytes = DownloadFixtures.media(MEDIA)
        val name = checkNotNull(row.fileName)
        storage.open(row.id, name, bytes.size.toLong()).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        storage.finish(row.id, name)
        val size = bytes.size.toLong()
        val done = row.copy(release = checkNotNull(row.release).copy(fileSize = size), size = size, indexerSize = size, safeOffset = size, relativePath = storage.relativePath(row.id, name))
        made += done
        storage.add(done)
        await("download ${done.id} among the finished ones") { container.downloads.done.value[done.watchKey]?.takeIf { it.id == done.id } }
        done
    }

    private fun episode(number: Int): Download {
        val name = "Harbor.Lights.S01E0$number.720p.WEB.h264-KESTREL.mkv"
        return Download(
            id = Download.newId(), watchKey = WatchKey.episode(HL, 1, number), kind = DownloadKind.Episode, title = "Harbor Lights",
            subtitle = "S01E0$number · ${names[number - 1]}", showTmdbId = HL, season = 1, episode = number, seasonEpisodes = names.size,
            episodeName = names[number - 1], state = DownloadState.Done, position = -1_000L + number,
            release = RememberedRelease(INDEXER, "hl-s01e0$number", name.removeSuffix(".mkv"), null, 0, "nzb-hl-$number", name), fileName = name,
            createdAt = 1, completedAt = 2, updatedAt = 2,
        )
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** What [probe] gives once it gives something, asked every 100 ms for [timeoutMs] at most. */
    private fun <T : Any> await(what: String, timeoutMs: Long = 30_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    private fun player(): PlayerActivity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>().firstOrNull()

    private fun finishPlayers() = instrumentation.runOnMainSync {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED).flatMap(monitor::getActivitiesInStage)
            .filterIsInstance<PlayerActivity>().forEach { it.finish() }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private companion object {
        /** An invented show; nothing real has the id. */
        const val HL = 990_831
        const val INDEXER = "show-offline-device-test"
        const val OFFLINE_LINE = "Offline · only downloaded episodes play"

        /** The device tests' film with chapters: six minutes at 10 fps, 0.7 MB. */
        const val MEDIA = "chapters.mkv"

        const val LAUNCH_MILLIS = 15_000L
        const val SEEDING_MILLIS = 60_000L
        const val READY_MILLIS = 30_000L
    }
}
