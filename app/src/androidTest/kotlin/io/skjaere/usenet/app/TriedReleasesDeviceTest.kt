package io.skjaere.usenet.app

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.collection.model.shortDate
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.search.ReleaseList
import io.skjaere.usenet.app.settings.CacheScreen
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ReleaseChecks
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.EpisodeRequest
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import io.skjaere.usenet.search.ShowRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The releases found missing on the providers, over the real coordinator, settings and library: the
 * fake indexer's (10.0.2.2:8089) Harbor Lights S01E01, whose top release ("BROKEN") lacks an article
 * on the mock NNTP server (10.0.2.2:1119). A first play grabs and checks it, remembers it as missing
 * on the mock, and plays the next release; the next play skips it without a grab. A provider added
 * since is one it wasn't checked on, so the next play tries it again and remembers it as missing on
 * both. The release list marks what is remembered; Settings › Cache counts it, and Clear forgets it
 * after asking. No player opens: the launches are taken here.
 *
 * The saved providers are hidden for the test (tombstoned, their secrets untouched) and put back as
 * they were; the test's own providers and indexer are deleted with their secrets, and the releases
 * remembered before the test are put back in place of what it left.
 */
@RunWith(AndroidJUnit4::class)
class TriedReleasesDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private val settings = container.settings
    private val library = container.library
    private val realFetch = PlaybackCoordinator.fetchNzb
    /** The releases the coordinator grabbed, by guid. */
    private val grabbed = CopyOnWriteArrayList<String>()
    private val states = CopyOnWriteArrayList<PlayState>()
    private val watching = CoroutineScope(Dispatchers.Default)
    private lateinit var saved: AppSettings
    private lateinit var remembered: List<TriedRelease>
    private lateinit var mock: String

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        saved = settings.settings.first()
        remembered = library.triedReleaseEntries()
        library.clearTriedReleases()
        // Hidden, not deleted: deleting would remove their passwords from the secret store.
        settings.restore(saved.copy(providers = saved.providers.map { it.copy(deleted = true) }))
        mock = settings.saveProvider(ProviderRecord("", "Mock", "10.0.2.2", 1119, useTls = false, connections = 4), "")
        settings.saveIndexer(FAKE_INDEXER, "Fake (tried releases test)", "http://10.0.2.2:8089", "fake-indexer-key")
        engineOn(mock)
        PlaybackCoordinator.fetchNzb = { c, indexer, item -> grabbed += item.guid; realFetch(c, indexer, item) }
        watching.launch { container.playback.state.collect { states += it } }
    }

    @After
    fun restore() = runBlocking<Unit> {
        watching.cancel()
        PlaybackCoordinator.fetchNzb = realFetch
        container.playback.cancel()
        // Skipped before anything was changed (a fake isn't reachable).
        if (!::saved.isInitialized) return@runBlocking
        settings.providers.first().filter { p -> saved.providers.none { it.id == p.id } }.forEach { settings.deleteProvider(it.id) }
        settings.settings.first().providers.forEach { container.providerTests.forget(it.id) }
        if (saved.indexers.none { it.id == FAKE_INDEXER } && settings.indexer(FAKE_INDEXER) != null) settings.deleteIndexer(FAKE_INDEXER)
        settings.restore(saved)
        assertEquals(saved, settings.settings.first())
        library.restoreTriedReleases(remembered)
        // The app's own provider sync puts the engine back on the restored providers; let it before the next test.
        engineOnSavedProviders()
    }

    @Test
    fun aReleaseFoundMissingIsRememberedAndTheNextPlaySkipsItWithoutAGrab() = runBlocking<Unit> {
        val (broken, good) = episodeReleases()
        container.playback.play(request(broken, good))
        assertEquals(good.key, played().release.key)
        assertEquals(listOf(BROKEN, GOOD), grabbed)
        assertEquals(listOf(provider(mock)), library.triedReleases(listOf(broken.key)).values.single().missingOn.map { it.provider })

        settled()
        grabbed.clear()
        states.clear()
        container.playback.play(request(broken, good))
        assertEquals(good.key, played().release.key)
        assertEquals("the broken release was grabbed again", listOf(GOOD), grabbed)
        val running = states.filterIsInstance<PlayState.Running>()
        assertTrue("$running", running.isNotEmpty() && running.all { it.release == good.item.title && it.of == 1 })
        assertTrue("$running", running.any { it.notice == "Skipped 1 release found incomplete before" })
    }

    /** The same mock under another provider: an account the release wasn't checked on, which may have it. */
    @Test
    fun aProviderAddedSinceIsTriedAndTheReleaseRememberedAsMissingOnItToo() = runBlocking<Unit> {
        val (broken, good) = episodeReleases()
        // As a first play on the mock alone leaves it.
        library.recordMissing(FAKE_INDEXER, BROKEN, listOf(provider(mock)))
        val second = settings.saveProvider(ProviderRecord("", "Mock B", "10.0.2.2", 1119, useTls = false, connections = 2), "")
        engineOn(mock, second)
        container.playback.play(request(broken, good))
        assertEquals(good.key, played().release.key)
        assertEquals(listOf(BROKEN, GOOD), grabbed)
        assertEquals(setOf(provider(mock), provider(second)), library.triedReleases(listOf(broken.key)).values.single().missingOn.map { it.provider }.toSet())
    }

    /**
     * A provider that can't serve (here one refusing connections, on a port nothing listens on) is left out once the engine
     * checks it, which it does as it first builds its streamer: in a new process, or with the providers just changed, as
     * here (TR-11). A play looks the release up after that check, so the one remembered as missing on the mock alone is
     * skipped without a grab, rather than tried for a provider that won't be asked.
     */
    @Test
    fun aProviderThatCantServeIsLeftOutBeforeThePlayLooksAReleaseUp() = runBlocking<Unit> {
        val (broken, good) = episodeReleases()
        library.recordMissing(FAKE_INDEXER, BROKEN, listOf(provider(mock)))
        val dead = settings.saveProvider(ProviderRecord("", "Refusing", "10.0.2.2", 1, useTls = false, connections = 2), "")
        engineOn(mock, dead)
        container.playback.play(request(broken, good))
        assertEquals(good.key, played().release.key)
        assertEquals("the broken release was grabbed", listOf(GOOD), grabbed)
        assertEquals(setOf(dead), container.engine.leftOut.value.keys)
    }

    /**
     * A false "missing" (a provider's bad day): the good release remembered as missing on the mock. Chosen all the same, it
     * plays, so it isn't missing there: the next play that isn't chosen (Resume, the movie page's Play of the release that
     * played) grabs and plays it rather than skipping it.
     */
    @Test
    fun aReleaseChosenThoughFoundMissingThatPlaysIsTriedByTheNextPlay() = runBlocking<Unit> {
        val (_, good) = episodeReleases()
        library.recordMissing(FAKE_INDEXER, GOOD, listOf(provider(mock)))
        container.playback.play(request(good).copy(chosen = true))
        assertEquals(good.key, played().release.key)
        // Grabbed and tried, not skipped: the coordinator hands the play's lookups the request's choice (TR-12).
        assertEquals(listOf(GOOD), grabbed)
        assertEquals(emptyMap<String, TriedRelease>(), library.triedReleases(listOf(good.key)))

        settled()
        grabbed.clear()
        container.playback.play(request(good))
        assertEquals(good.key, played().release.key)
        assertEquals(listOf(GOOD), grabbed)
    }

    @Test
    fun theReleaseListMarksTheReleasesFoundIncomplete() = runBlocking<Unit> {
        library.recordMissing(FAKE_INDEXER, BROKEN, listOf(provider(mock)))
        library.recordMissing(FAKE_INDEXER, "nzb-lacks-a-volume", emptyList(), incomplete = true)
        val items = listOf(item(BROKEN, "Harbor.Lights.S01E01.PROPER.1080p.WEB-DL.AAC2.0.H.264-BROKEN"), item("nzb-lacks-a-volume", "Harbor.Lights.S01E01.720p.WEB.h264-VOL"), item(GOOD, "Harbor.Lights.S01E01.1080p.WEB-DL.AAC2.0.H.264-GOOD"))
        val group = ResultGroup("harbor lights|ep:1:1", GroupKind.Episode, 1, listOf(1), null, null, items, emptyList())
        compose.setContent { AppTheme { ReleaseList(group, "Harbor Lights", onBack = {}, onPlay = {}) } }
        val today = LocalDate.now()
        val checked = library.triedReleases(listOf("$FAKE_INDEXER/$BROKEN")).values.single().updatedAt
        val chip = "Missing on your providers · " + shortDate(Instant.ofEpochMilli(checked).atZone(ZoneId.systemDefault()).toLocalDate(), today)
        compose.waitUntil(10_000) { exists(hasText(chip)) && exists(hasText("Incomplete NZB")) }
        assertEquals(1, compose.onAllNodes(hasText(chip)).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasText("Incomplete NZB")).fetchSemanticsNodes().size)
    }

    /**
     * The list stays up under the play sheet, and while the player plays: a release found missing meanwhile (a Play from the
     * list, a gap found playing) is marked when the user is back on it, and one that played no longer is.
     */
    @Test
    fun theReleaseListMarksAReleaseFoundIncompleteWhileItShows() = runBlocking<Unit> {
        val items = listOf(item(BROKEN, "Harbor.Lights.S01E01.PROPER.1080p.WEB-DL.AAC2.0.H.264-BROKEN"), item(GOOD, "Harbor.Lights.S01E01.1080p.WEB-DL.AAC2.0.H.264-GOOD"))
        val group = ResultGroup("harbor lights|ep:1:1", GroupKind.Episode, 1, listOf(1), null, null, items, emptyList())
        compose.setContent { AppTheme { ReleaseList(group, "Harbor Lights", onBack = {}, onPlay = {}) } }
        compose.waitUntil(10_000) { exists(hasText("Best match")) }
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasText("Missing on your providers", substring = true)).fetchSemanticsNodes().isEmpty())
        library.recordMissing(FAKE_INDEXER, BROKEN, listOf(provider(mock)))
        compose.waitUntil(10_000) { exists(hasText("Missing on your providers", substring = true)) }
        assertEquals(1, compose.onAllNodes(hasText("Missing on your providers", substring = true)).fetchSemanticsNodes().size)
        library.recordFound(FAKE_INDEXER, BROKEN, listOf(provider(mock)))
        compose.waitUntil(10_000) { !exists(hasText("Missing on your providers", substring = true)) }
    }

    @Test
    fun theCacheScreenCountsTheReleasesFoundIncompleteAndClearForgetsThemAfterAsking() = runBlocking<Unit> {
        library.recordMissing(FAKE_INDEXER, BROKEN, listOf(provider(mock)))
        library.recordMissing(FAKE_INDEXER, "nzb-lacks-a-volume", emptyList(), incomplete = true)
        compose.setContent { AppTheme { CacheScreen(onBack = {}) } }
        val row = hasText("Releases found incomplete")
        compose.waitUntil(10_000) { exists(row and hasText("2 releases")) }
        compose.onNode(hasContentDescription("Clear the releases found incomplete")).performScrollTo().performClick()
        compose.waitUntil(5_000) { exists(hasText("Forget the releases found incomplete?")) }
        compose.onNode(hasText("Clear") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { exists(row and hasText("None")) }
        assertEquals(0, library.triedReleaseCount())
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** Until the play sheet is idle again after a launch, and the states collected here have caught up with it. */
    private suspend fun settled() = withTimeout(10_000) {
        container.playback.state.first { it == PlayState.Idle }
        while (states.lastOrNull() != PlayState.Idle) delay(20)
    }

    private fun provider(id: String) = ReleaseChecks.provider(id, "10.0.2.2", 1119)

    /** Waits for the app's provider sync to put the engine on the providers [ids], in that order. */
    private suspend fun engineOn(vararg ids: String) {
        engineSetup("the engine on the providers ${ids.joinToString()}") { container.engine.configuredProviders.first { it == ids.toList() } }
    }

    /**
     * The broken and the good one of S01E01's releases this device plays, from the fake indexer, which ranks them: the
     * broken one, the one flagged passworded (tried all the same), the good one, then the season pack, whose standing is
     * the good one's but for a pack's cost.
     */
    private suspend fun episodeReleases(): Pair<RankedItem, RankedItem> {
        val indexer = IndexerConfig(FAKE_INDEXER, "Fake (tried releases test)", "http://10.0.2.2:8089", "fake-indexer-key")
        val show = ShowRef("Harbor Lights", 2021, ids = TvIds(tvdb = 900001, tmdb = 900002, imdb = "tt9000003"))
        val found = container.finder.find(EpisodeRequest(show, 1, 1, runtimeMinutes = 45), listOf(indexer), container.rankingContext())
            .filterIsInstance<FinderEvent.Finished>().last().result
        val playable = found.playable.map { RankedItem(it.item, it.ranked) }
        assertEquals(listOf(BROKEN, PASSWD, GOOD, PACK), playable.map { it.item.guid })
        return playable.single { it.item.guid == BROKEN } to playable.single { it.item.guid == GOOD }
    }

    /** As Play on the episode would ask, played as a release (no TMDB): no remembered release goes first. */
    private fun request(vararg candidates: RankedItem) =
        PlayRequest("Harbor Lights", "S01E01", candidates.toList(), FilePicker.Episode(1, 1), WatchTarget(WatchKind.Release, candidates.first().item.title))

    /** The coordinator's next launch, or a failure as soon as the play sheet shows one. */
    private suspend fun played(): PlayerLaunch = withTimeout(90_000) {
        merge(
            container.playback.launches,
            container.playback.state.filter { it is PlayState.Failed }.map { throw AssertionError("didn't start: $it") },
        ).first()
    }

    private fun item(guid: String, title: String) = RankedItem(
        NewznabItem(
            indexerId = FAKE_INDEXER, guid = guid, title = title, sizeBytes = 1_000_000_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = 1, episode = 1, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("$FAKE_INDEXER/$guid", title, 1_000_000_000), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
    )

    private fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess

    private companion object {
        const val FAKE_INDEXER = "tried-releases-device-test"
        const val BROKEN = "hl-s01e01-broken"
        const val GOOD = "hl-s01e01-good"
        const val PACK = "hl-s01-pack"
        const val PASSWD = "hl-s01e01-passwd"
    }
}
