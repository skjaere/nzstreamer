package io.skjaere.usenet.app

import android.view.KeyEvent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.collection.chosenRequest
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayOutcome
import io.skjaere.usenet.app.play.PlaySheetHost
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.Prefetch
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A release of several videos that names none of them for the episode played asks which it is: two fixtures,
 * chapters.mkv and tracks.mkv, in one NZB as a season pack of an invented show would list them, over the real
 * coordinator, play sheet, settings, library and engine. The sheet says which episode it looks for and lists the videos,
 * largest first; the one picked plays. Remembered for the episode (as the player saves it with the progress), it plays
 * again without the question, chosen again or resumed. Next, from that release, asks again for the episode after, with no
 * grab, unless a file is remembered for it; Cancel and Back on the question stop the play. The NZB is served in the
 * indexer's place (those served are counted); its articles are the fixtures' on the mock NNTP server (10.0.2.2:1119).
 * No player opens: the launches are taken here.
 *
 * The saved providers are hidden for the test (tombstoned, their secrets untouched) and put back as they were, the
 * indexers switched off and on again, and the releases remembered as missing put back; the release remembered for each
 * episode played is forgotten again.
 */
@RunWith(AndroidJUnit4::class)
class FileChooserDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private val settings = container.settings
    private val library = container.library
    private val realSource = PlaybackCoordinator.nzbSource
    private lateinit var saved: AppSettings
    private lateinit var tried: List<TriedRelease>
    private lateinit var nzb: ByteArray
    /** How often the release's NZB was served, as a grab of it would be. */
    private val served = AtomicInteger()
    private val states = CopyOnWriteArrayList<PlayState>()
    private var watching: Job? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        nzb = twoVideos()
        saved = settings.settings.first()
        tried = library.triedReleaseEntries()
        // Hidden, not deleted: deleting would remove their passwords from the secret store.
        settings.restore(saved.copy(providers = saved.providers.map { it.copy(deleted = true) }))
        val mock = settings.saveProvider(ProviderRecord("", "Mock", "10.0.2.2", 1119, useTls = false, connections = 4), "")
        // Nothing is searched for: a play that falls back on a search finds nothing.
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        val outcome = engineSetup("the engine on the saved mock, the two videos' release prepared") {
            // The app's own provider sync gives the engine the mock just saved.
            container.engine.configuredProviders.first { it == listOf(mock) }
            container.preparer.prepare(nzb, TITLE)
        }
        assumeTrue(
            "the mock NNTP server lacks the fixtures' articles ($outcome); seed them again, see SkipSegmentsDeviceTest's KDoc",
            outcome is PrepareOutcome.Ready,
        )
        PlaybackCoordinator.nzbSource = { _, grab -> NzbSource { candidate -> if (candidate.id == KEY) nzb.also { served.incrementAndGet() } else grab.fetch(candidate) } }
        watching = CoroutineScope(Dispatchers.Default).launch { container.playback.state.collect { states += it } }
        compose.setContent { AppTheme { PlaySheetHost(container.playback, onSearch = {}, onProviders = {}) } }
    }

    @After
    fun restore() = runBlocking<Unit> {
        watching?.cancel()
        PlaybackCoordinator.nzbSource = realSource
        container.playback.cancel()
        // Skipped before anything was changed (the mock isn't reachable).
        if (!::saved.isInitialized) return@runBlocking
        for (n in 5..7) library.lastRelease(key(n))?.takeIf { it.guid == GUID }?.let { library.forgetRelease(key(n), it) }
        settings.providers.first().filter { p -> saved.providers.none { it.id == p.id } }.forEach { settings.deleteProvider(it.id) }
        settings.settings.first().providers.forEach { container.providerTests.forget(it.id) }
        settings.restore(saved)
        library.restoreTriedReleases(tried)
        // The app's own provider sync puts the engine back on the restored providers; let it before the next test.
        engineOnSavedProviders()
    }

    /**
     * The release names no file for S01E05: the sheet asks which it is, tracks.mkv (the larger) listed first, and the one
     * picked, chapters.mkv, plays. Remembered for S01E05, it plays again without the question, the release chosen again
     * and resumed, from the stored NZB.
     */
    @Test
    fun aReleaseThatNamesNoFileForTheEpisodeAsksWhichItIsAndReplayAndResumeDont() = runBlocking<Unit> {
        val outcome = container.playback.play(chosenRequest(episode(5), release()))
        val question = asked("Looking for S01E05 'Low Tide'")
        assertEquals(TITLE, question.release)
        assertEquals(listOf("tracks.mkv", "chapters.mkv"), question.files.map { it.name })
        assertTrue("the release's name on the sheet", exists(hasText(TITLE) and hasAnyAncestor(isDialog())))
        choose("chapters.mkv")
        val first = launched(5)
        assertEquals("chapters.mkv", first.release.fileName)
        assertEquals(PlayOutcome.Launched, withTimeout(5_000) { outcome.await() })
        assertEquals(1, served.get())
        remember(first)

        states.clear()
        container.playback.play(chosenRequest(episode(5), release()))
        assertEquals("chapters.mkv", launched(5).release.fileName)
        container.playback.resume(checkNotNull(library.state(key(5)).first()))
        assertEquals("chapters.mkv", launched(5).release.fileName)
        assertEquals("asked again", emptyList<PlayState>(), states.filterIsInstance<PlayState.ChoosingFile>())
        assertEquals("the NZB served again", 1, served.get())
    }

    /**
     * Next from that release (the card's search finds it there at once) asks again, for S01E06, with no grab; the file
     * picked plays from the same NZB. Remembered for S01E06, the card says it is ready and Next plays it without the question.
     */
    @Test
    fun nextFromThatReleaseAsksAgainWithNoGrabUnlessAFileIsRememberedForTheEpisode() = runBlocking<Unit> {
        container.playback.play(chosenRequest(episode(5), release()))
        asked("Looking for S01E05 'Low Tide'")
        choose("chapters.mkv")
        val first = launched(5)

        assertEquals(Prefetch.Ready(TITLE, 0, choose = true), container.playback.prefetchEpisode(episode(6), first.release).value)
        assertTrue(container.playback.holdsPrefetched(episode(6)))
        container.playback.playEpisode(episode(6), first.release)
        assertEquals(listOf("tracks.mkv", "chapters.mkv"), asked("Looking for S01E06 'High Water'").files.map { it.name })
        choose("tracks.mkv")
        val next = launched(6)
        assertEquals("tracks.mkv", next.release.fileName)
        assertEquals(first.release.nzbId, next.release.nzbId)
        assertEquals("NZBs served", 1, served.get())
        remember(next)

        // The card then says the release is ready, not that its file is to choose.
        val card = container.playback.prefetchEpisode(episode(6), first.release)
        assertEquals(Prefetch.Ready(TITLE, next.release.fileSize!!), withTimeout(5_000) { card.first { it is Prefetch.Ready && !it.choose } })
        states.clear()
        container.playback.playEpisode(episode(6), first.release)
        assertEquals("tracks.mkv", launched(6).release.fileName)
        assertEquals("asked again", emptyList<PlayState>(), states.filterIsInstance<PlayState.ChoosingFile>())
        assertEquals("NZBs served", 1, served.get())
    }

    /**
     * The release may not hold the episode asked for: "Not in this release" goes on without it. Next from that release, for
     * S01E06, then searches for the episode, as for one the release doesn't hold (no indexer is on here: no release), with
     * no grab; the release chosen by hand for S01E07 goes back to its list, failed for having no file for it.
     */
    @Test
    fun notInThisReleaseOnTheQuestionGoesOnWithoutTheRelease() = runBlocking<Unit> {
        container.playback.play(chosenRequest(episode(5), release()))
        asked("Looking for S01E05 'Low Tide'")
        choose("chapters.mkv")
        val first = launched(5)

        container.playback.playEpisode(episode(6), first.release)
        asked("Looking for S01E06 'High Water'")
        notInRelease()
        val searched = withTimeout(30_000) { container.playback.state.first { it is PlayState.NoRelease || it is PlayState.Failed } }
        assertTrue("didn't search: $searched", searched is PlayState.NoRelease && searched.subtitle == "S01E06 · High Water")
        assertEquals("NZBs served", 1, served.get())

        val chosen = container.playback.play(chosenRequest(episode(7), release()))
        asked("Looking for S01E07 'Slack Water'")
        notInRelease()
        val failed = withTimeout(10_000) { chosen.await() }
        assertTrue("$failed", failed is PlayOutcome.Failed && failed.reason(TITLE) == "No file for S01E07")
    }

    /** Cancel, and Back, on the question stop the play: nothing plays. */
    @Test
    fun cancelOrBackOnTheQuestionStopsThePlay() = runBlocking<Unit> {
        val cancelled = container.playback.play(chosenRequest(episode(7), release()))
        asked("Looking for S01E07 'Slack Water'")
        compose.onNode(hasText("Cancel") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        stopped(cancelled, "Cancel")
        compose.waitUntil(5_000) { container.playback.state.value == PlayState.Idle && !exists(hasText("Looking for", substring = true)) }

        val backed = container.playback.play(chosenRequest(episode(7), release()))
        asked("Looking for S01E07 'Slack Water'")
        compose.waitForIdle()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        stopped(backed, "Back")
        compose.waitUntil(5_000) { container.playback.state.value == PlayState.Idle && !exists(hasText("Looking for", substring = true)) }
        // No player was launched for it, after either.
        assertNull(withTimeoutOrNull(1_000) { container.playback.launches.first { it.watch.showTmdbId == SHOW } })
    }

    /**
     * [outcome], a play's, once it has stopped after [what] stopped it. The sheet goes away as its animation ends, which
     * moves on the test's clock: the test keeps in step with the screen meanwhile.
     */
    private suspend fun stopped(outcome: Deferred<PlayOutcome>, what: String) {
        compose.waitForIdle()
        val o = withTimeoutOrNull(5_000) { outcome.await() } ?: throw AssertionError("still playing 5 s after $what: ${container.playback.state.value}")
        assertEquals(PlayOutcome.Stopped, o)
    }

    /** One NZB of the two fixtures' files, as one release would list them. */
    private fun twoVideos(): ByteArray {
        fun files(asset: String) = instrumentation.context.assets.open(asset).use { it.readBytes() }.decodeToString()
            .let { it.substring(it.indexOf("<file"), it.lastIndexOf("</file>") + "</file>".length) }
        return listOf(files("chapters.nzb"), files("tracks.nzb"))
            .joinToString("\n", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<nzb xmlns=\"http://www.newzbin.com/DTD/2003/nzb\">\n", "\n</nzb>\n")
            .encodeToByteArray()
    }

    /** The release as a search would have found it: a season pack of the show. */
    private fun release() = RankedItem(
        NewznabItem(
            indexerId = INDEXER, guid = GUID, title = TITLE, sizeBytes = nzbSize, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = 1, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts(KEY, TITLE, nzbSize), ReleaseParser.parse(TITLE), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
    )

    private val nzbSize = 1_455_975L

    private fun episode(n: Int) = EpisodePlay(
        showTmdbId = SHOW, showName = "Tidewater Lane", showYear = 2023, aliases = emptyList(), ids = TvIds(tmdb = SHOW),
        season = 1, episode = n, episodeName = NAMES.getValue(n), airDate = null, runtimeMinutes = 6,
    )

    private fun key(n: Int) = WatchKey.episode(SHOW, 1, n)

    /** [launch]'s release remembered for its episode, as the player saves it with the progress: 10 s in, not in Continue watching. */
    private suspend fun remember(launch: PlayerLaunch) {
        val w = launch.watch
        val template = WatchState(key = launch.watchKey, kind = w.kind, showTmdbId = w.showTmdbId, season = w.season, episode = w.episode, title = w.title, updatedAt = 0)
        library.recordProgress(template, positionMs = 10_000, durationMs = 360_000, release = launch.release.toRemembered())
    }

    /** The question the sheet shows once it says it looks for [what]; a failure as soon as the sheet shows one. */
    private fun asked(what: String): PlayState.ChoosingFile {
        compose.waitUntil(60_000) {
            (container.playback.state.value as? PlayState.Failed)?.let { throw AssertionError("didn't ask: $it") }
            exists(hasText(what) and hasAnyAncestor(isDialog()))
        }
        return container.playback.state.value as PlayState.ChoosingFile
    }

    /** The question's "Not in this release", pressed. */
    private fun notInRelease() {
        compose.onNode(hasText("Not in this release") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
    }

    /** The question's row for the file [name]d, picked. */
    private fun choose(name: String) {
        compose.onNode(hasText(name) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
    }

    /** The coordinator's next launch of S01E0[episode], or a failure as soon as the play sheet shows one. */
    private suspend fun launched(episode: Int): PlayerLaunch = withTimeout(90_000) {
        merge(
            container.playback.launches.filter { it.watch.showTmdbId == SHOW && it.watch.episode == episode },
            container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("S01E0$episode didn't start: $it") },
        ).first()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess

    private companion object {
        /** An invented show, in an id no real or fake TMDB title has. */
        const val SHOW = 900090
        const val INDEXER = "file-chooser-device-test"
        const val GUID = "tl-s01-two-videos"
        const val KEY = "$INDEXER/$GUID"
        const val TITLE = "Tidewater.Lane.S01.720p.WEB.x264-FIXTURE"
        val NAMES = mapOf(5 to "Low Tide", 6 to "High Water", 7 to "Slack Water")
    }
}
