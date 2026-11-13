package io.skjaere.usenet.app.debrid

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.search.sourceTag
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.newznab.IndexerOutcome
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.search.ResultGrouper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL

/**
 * The live device check (L212, W77, item 39), the lead's alone: run with the instrumentation argument `debridLive=true`,
 * which also leaves the debug build's seeding of your own accounts as it is (AppTestRunner), on phone34, with the Torznab
 * fake started with the openly licensed film's `--extra-hash` and `--extra-title`. The seeded torrent indexers are off for
 * the run and the fake's feed is saved in their place, so the search goes to the fake and the cache check to the real
 * services. If an account has the film cached, it plays two minutes of it and seeks once, past what the player holds, and
 * its marks (the source tag, "✓ Cached") and File details' Source rows are read; if every account says it isn't cached,
 * the run records the not-cached path and adds nothing. A check that couldn't settle it (a refused key, a service down,
 * past the 10 s bound) fails the run, naming which. Never the film's name, its hash, a key or a link in a message or a
 * line. Skipped without the argument.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DebridLiveDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val settings = container.settings
    private val live = InstrumentationRegistry.getArguments().getString("debridLive") == "true"

    /** The fake's feed, saved for the run, and the seeded torrent indexers switched off for it. */
    private var fake: String? = null
    private val switchedOff = mutableListOf<String>()

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeTrue("the live check runs with the instrumentation argument debridLive=true alone (the lead's)", live)
        DebridFakes.assumeTorznab()
        container.devSettingsSaved.await()
        assumeTrue("no debrid account seeded (local.properties' devTorBoxKey or devPremiumizeKey)", container.services.on().isNotEmpty())
        settings.torrentIndexers.first().filter { it.enabled }.forEach {
            settings.setTorrentIndexerEnabled(it.id, false)
            switchedOff += it.id
        }
        fake = settings.saveTorrentIndexer(null, "Live fake", DebridFakes.PROWLARR_URL, DebridFakes.TORZNAB_KEY)
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        if (!live) return@runBlocking
        onMainThread { container.playback.cancel() }
        fake?.let { settings.deleteTorrentIndexer(it) }
        switchedOff.forEach { settings.setTorrentIndexerEnabled(it, true) }
    }

    /** The film the fake lists beside its own invented ones (--extra-hash, --extra-title): its title's words and its hash. */
    private fun extraFilm(): Pair<String, String> {
        val feed = URL("${DebridFakes.PROWLARR_URL}/api?t=search&q=&apikey=${DebridFakes.TORZNAB_KEY}").readText()
        val items = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(feed).map { it.groupValues[1] }.mapNotNull { item ->
            val title = Regex("<title>(.*?)</title>").find(item)?.groupValues?.get(1)?.let(::unescaped) ?: return@mapNotNull null
            val hash = Regex("""name="infohash" value="([0-9a-fA-F]{40})"""").find(item)?.groupValues?.get(1) ?: return@mapNotNull null
            (title to hash.lowercase()).takeUnless { title.startsWith("Harbor.Lights") || title.startsWith("Copper.Crown") }
        }.toList()
        assertEquals("the Torznab fake lists one film beside its own (--extra-hash, --extra-title)", 1, items.size)
        return items.single()
    }

    @Test
    fun theOpenFilmIsCheckedOnTheRealServicesAndPlaysWhereOneHasItCached() = runBlocking<Unit> {
        val (title, hash) = extraFilm()
        val words = title.replace(Regex("[^A-Za-z0-9]+"), " ").trim()
        val indexer = container.indexerConfigs().single { it.id == fake }
        val outcomes = withTimeout(60_000) { container.checkedSearch.search(listOf(indexer), fresh = true) { SearchQuery.Text(words) }.toList() }
        val found = outcomes.filterIsInstance<IndexerOutcome.Checked>().flatMap { it.items }.firstOrNull { it.infoHash == hash }
        assertNotNull("the film's torrent was checked", found)
        val availability = found!!.availability
        if (availability !is Availability.Cached) {
            // Not checked (or not for the service) is no answer from the services: the lead must hear which it was.
            assertEquals("what the services said of the film", Availability.NotCached, availability)
            Log.i(TAG, "Live check: no account has the film cached: the not-cached path, nothing added")
            return@runBlocking
        }
        val grouped = ResultGrouper().group(listOf(found), container.rankingContext())
        val ranked = (grouped.sections.flatMap { s -> s.groups.flatMap { it.playable } } + grouped.hidden).single { it.item.infoHash == hash }
        // Its marks: where it plays from, by the service's name, and "✓ Cached".
        val tag = checkNotNull(sourceTag(ranked, container.serviceNames.first())) { "no source tag" }
        assertTrue("the tag names a service", tag.text in setOf("TorBox", "Premiumize"))
        assertTrue("Cached among its reasons", ReleaseReason.Cached in ranked.ranked.reasons)
        assertTrue("playable on this device", ranked.ranked.playable)

        onMainThread { container.playback.play(PlayRequest("Live film", null, listOf(ranked), FilePicker.Largest, WatchTarget(WatchKind.Release, "Live film"), rememberedFirst = false)) }
        val launch = launched()
        assertTrue(launch.uri.startsWith("debrid://"))
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            val from = waitFor("the film to play", 60_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying }?.currentPosition } }
            // Two minutes of the film played (a stall runs out the wait), then one seek to its middle, minutes past the
            // 50 s the player holds, so the CDN is opened again at a byte far in; the play goes on 5 s from there.
            waitFor("two minutes of the film", 240_000) { scenario.read { a -> a.player?.takeIf { it.currentPosition >= from + 120_000 } } }
            val to = scenario.read { it.player!!.duration } / 2
            scenario.onActivity { it.player?.seekTo(to) }
            waitFor("the play to go on from the seek", 60_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying && it.currentPosition >= to + 5_000 } } }
            // File details' Source: the fake's feed by its name, then the service in place of the providers.
            scenario.onActivity { a ->
                views(a.window.decorView).filterIsInstance<PlayerView>().single().showController()
                views(a.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == "File details" }.performClick()
            }
            val rows = waitFor("File details' Service row", 20_000) { scenario.read { a -> rows(a).takeIf { "Service" in it } } }
            assertEquals(listOf("Live fake"), rows["Indexer"])
            assertEquals(listOf(tag.text, "a cached torrent, streamed from the service"), rows["Service"])
            assertFalse("no providers", "Providers" in rows)
        }
        Log.i(TAG, "Live check: played two minutes from ${tag.text}, seeked once, marks and File details read")
    }

    private suspend fun launched(): PlayerLaunch = withTimeout(120_000) {
        merge(
            container.playback.launches,
            container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("nothing played: ${it.javaClass.simpleName}") },
        ).first()
    }

    private fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private suspend fun <T : Any> waitFor(what: String, timeoutMs: Long, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            delay(250)
        }
    }

    /** A feed's title as its XML escapes it, read back. */
    private fun unescaped(text: String) =
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    /** File details' rows by their labels: each one's value and note, as one node reads. */
    private fun rows(activity: PlayerActivity): Map<String, List<String>> =
        views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }
            .flatMap { subtree(it.semanticsOwner.rootSemanticsNode) }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.PaneTitle) == "File details" }?.let(::subtree).orEmpty()
            .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }?.takeIf { it.size >= 2 } }
            .associate { it.first() to it.drop(1) }

    private companion object {
        const val TAG = "DebridLive"
    }
}
