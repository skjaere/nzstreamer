package io.skjaere.usenet.app

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.settings.cacheSummary
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.CachePrefs
import io.skjaere.usenet.data.ProviderRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Settings › Cache over the real settings, engine and video cache: the seeded clip that
 * SkipSegmentsDeviceTest plays (`chapters.nzb`, from the mock NNTP server) fills the cache as it
 * plays, Clear empties it while it plays on, an engine rebuild during a play keeps the one cache, and
 * the app's own screens show what a play left, clear it, save the limit chosen and turn it off.
 * (The player is started as its own task, so the app is started after it, rather than a test
 * activity under it.)
 *
 * The saved providers are hidden for the test (tombstoned, their secrets untouched) behind one on the
 * mock, and the settings are put back as they were afterwards, the cache's included.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class CacheDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val settings = container.settings
    private val videoCache = container.videoCache
    private val home = HomeContentShown()
    private val realLookUp = PlayerActivity.lookUpSegments
    private lateinit var saved: AppSettings
    private lateinit var mock: String
    private lateinit var fixture: PreparedNzb

    @Before
    fun setUp() = runBlocking<Unit> {
        fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        saved = settings.settings.first()
        // Hidden, not deleted: deleting would remove their passwords from the secret store.
        settings.restore(saved.copy(providers = saved.providers.map { it.copy(deleted = true) }, cache = CachePrefs()))
        mock = settings.saveProvider(ProviderRecord("", "Mock", "10.0.2.2", 1119, useTls = false, connections = 4), "")
        // The app's own provider sync gives the engine the mock just saved.
        engineSetup("the engine on the saved mock") { container.engine.configuredProviders.first { it == listOf(mock) } }
        withTimeout(10_000) { while (videoCache.usageNow()?.let { it.on && it.chosenBytes == CachePrefs().limitBytes } != true) delay(50) }
        fixture = engineSetup("the chapters fixture prepared") { prepareFixture("chapters") }
        videoCache.clear()
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
    }

    @After
    fun restore() = runBlocking<Unit> {
        PlayerActivity.lookUpSegments = realLookUp
        instrumentation.setInTouchMode(true)
        home.restore()
        // Skipped before anything was changed (the mock isn't reachable).
        if (!::saved.isInitialized) return@runBlocking
        settings.providers.first().filter { p -> saved.providers.none { it.id == p.id } }.forEach { settings.deleteProvider(it.id) }
        settings.settings.first().providers.forEach { container.providerTests.forget(it.id) }
        settings.restore(saved)
        assertEquals(saved, settings.settings.first())
        // The app's own sync puts the engine and the video cache back on the restored settings; let it before the next test.
        engineOnSavedProviders()
        withTimeout(10_000) {
            while (videoCache.usageNow()?.let { it.on == saved.cache.keepVideo && it.chosenBytes == saved.cache.limitBytes } != true) delay(50)
        }
    }

    /** The fixture opened as an NZB file is: no launch, so no resume and no next episode. */
    private fun fileIntent() = Intent(context, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URI, UsenetUris.build(fixture.id, fixture.defaultPick!!.index))

    /** The first article of the clip, which a play from the start reads first. */
    private val firstArticle get() = fixture.metadata.orderedArchiveNzb.files.first().segments.first().articleId

    private fun used() = videoCache.usageNow()?.usedBytes ?: 0

    /** Plays the clip until it has played [ms] and has put something in the cache. */
    private suspend fun playUntil(scenario: ActivityScenario<PlayerActivity>, ms: Long) {
        waitFor("the player to know the duration", timeoutMs = 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
        waitFor("$ms ms played", timeoutMs = 30_000) { scenario.read { a -> a.player!!.currentPosition.takeIf { it >= ms } } }
        waitFor("the cache to hold the clip's start") { used().takeIf { it > 0 } }
    }

    /** Waits for the player to play on [ms] past where it is now, with no error. */
    private suspend fun playsOn(scenario: ActivityScenario<PlayerActivity>, ms: Long) {
        val from = scenario.read { it.player!!.currentPosition }
        waitFor("$ms ms more played from $from", timeoutMs = 30_000) {
            scenario.read { a ->
                assertNull("the player failed", a.player!!.playerError)
                a.player!!.currentPosition.takeIf { it >= from + ms }
            }
        }
    }

    @Test
    fun clearEmptiesTheCacheWhileThePlayerPlaysOnAndFetchesWhatItReadsAgain() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            playUntil(scenario, 3_000)
            val cache = checkNotNull(videoCache.forStreamer()) { "no video cache" }
            assertNotNull("the clip's start isn't in the cache", cache.get(firstArticle))
            val before = used()
            // What the Cache screen's Clear does (CacheViewModel.clearVideo), with the player in front.
            videoCache.clear()
            assertTrue("still ${used()} of $before bytes after Clear", used() < before)
            assertNull("the clip's start is still in the cache", cache.get(firstArticle))
            playsOn(scenario, 3_000)
            // Back at the start, the player reads it again: from the provider, into the cache.
            scenario.onActivity { it.player!!.seekTo(0) }
            playsOn(scenario, 2_000)
            waitFor("the clip's start to be cached again") { cache.get(firstArticle) }
            assertSame(cache, videoCache.forStreamer())
        }
    }

    /**
     * A provider setting changed during a play builds the engine's streamer again: the new one is lent
     * the same cache, which the old one's close left open, and the play goes on.
     */
    @Test
    fun theCacheSurvivesAnEngineRebuildDuringAPlay() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            playUntil(scenario, 3_000)
            val cache = checkNotNull(videoCache.forStreamer()) { "no video cache" }
            val streamer = engineSetup("the streamer") { container.engine.streamer() }
            val record = settings.providers.first().single { it.id == mock }
            settings.saveProvider(record.copy(connections = 3), password = null)
            waitFor("the streamer to be built again") { engineSetup("the streamer") { container.engine.streamer() }.takeIf { it !== streamer } }
            playsOn(scenario, 3_000)
            assertSame(cache, videoCache.forStreamer())
            assertNotNull("the cache was closed with the old streamer", cache.get(firstArticle))
            assertTrue(used() > 0)
        }
    }

    /** Starts the app as the launcher does, on Home, and opens Settings › Cache from the settings hub, which shows the cache's summary. */
    private fun openCacheScreen(): ActivityScenario<MainActivity> {
        home.show()
        val app = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        click(hasContentDescription("Settings"))
        compose.waitUntil(5_000) { exists(hasText("Cache") and hasText(cacheSummary(videoCache.usageNow()))) }
        click(hasText("Cache") and hasClickAction())
        compose.waitUntil(5_000) { exists(hasText("Keep watched video on this device")) }
        return app
    }

    @Test
    fun theScreenShowsWhatAPlayLeftAndClearsItAfterAskingAndTheLimitChosenIsSaved() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario -> playUntil(scenario, 2_000) }
        val usage = checkNotNull(videoCache.usageNow()) { "the video cache settings were never applied" }
        assertTrue(usage.usedBytes > 0)
        openCacheScreen().use {
            compose.waitUntil(5_000) { exists(hasText(cacheSummary(videoCache.usageNow()))) }

            click(hasContentDescription("Clear the video cache"))
            compose.waitUntil(5_000) { exists(hasText("Clear the video cache?")) }
            // The question's Clear: the screen's are read out by what they clear.
            click(hasText("Clear") and hasClickAction() and undescribed)
            compose.waitUntil(5_000) { used() == 0L }
            compose.waitUntil(5_000) { exists(hasText(cacheSummary(videoCache.usageNow()))) && !exists(hasText("Clear the video cache?")) }

            click(hasText("5 GB") and hasClickAction())
            compose.waitUntil(5_000) { runBlocking { settings.cache.first().limitGb } == 5 }
            compose.waitUntil(5_000) { videoCache.usageNow()?.chosenBytes == 5 * CachePrefs.GB }
            compose.waitUntil(5_000) { exists(hasText("5 GB") and isSelected()) }
            assertFalse(exists(hasText("2 GB") and isSelected()))
            click(hasContentDescription("Back"))
            compose.waitUntil(5_000) { exists(hasText("Cache") and hasText(cacheSummary(videoCache.usageNow()))) }
        }
    }

    @Test
    fun turnedOffItIsClearedAndTheHubSaysOff() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario -> playUntil(scenario, 2_000) }
        openCacheScreen().use {
            click(hasText("Keep watched video on this device") and hasClickAction())
            compose.waitUntil(5_000) { videoCache.usageNow()?.on == false }
            assertNull(videoCache.forStreamer())
            assertFalse(java.io.File(context.cacheDir, "segments").exists())
            assertFalse(runBlocking { settings.cache.first().keepVideo })
            // The size limits and the video's Clear go with it.
            compose.waitUntil(5_000) { !exists(hasContentDescription("Clear the video cache")) && !exists(hasText("5 GB")) }
            click(hasContentDescription("Back"))
            compose.waitUntil(5_000) { exists(hasText("Cache") and hasText("Off")) }
        }
    }

    private val undescribed = SemanticsMatcher("no content description") { it.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun click(matcher: SemanticsMatcher) {
        compose.waitUntil(5_000) { exists(matcher) }
        val node = compose.onNode(matcher)
        // Into view where it scrolls (not on Home or in a dialog).
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private suspend fun <T : Any> waitFor(what: String, timeoutMs: Long = 20_000, probe: suspend () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            delay(100)
        }
    }
}
