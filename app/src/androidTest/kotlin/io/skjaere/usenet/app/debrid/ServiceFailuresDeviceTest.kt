package io.skjaere.usenet.app.debrid

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlaySheetHost
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.toList
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A debrid service's failures in a play, over the fakes' control path (L189, L204; DbTvPlaySheet 2's phone twin): with
 * TorBox's limit reached and a 20-minute wait, release 1, cached on TorBox, fails at Preparing, the notice says so without
 * naming the service, and release 2, Usenet's, plays from the mock; with every release failing, the failure sheet lists
 * each in its short words, a refused key's naming its service alone. With both services on and the release cached on
 * both (DEP-6, D2PhonePlaySheetRetry), a failure at Checking audio and video or at Opening on the first in the order
 * tries the next before any other release: the notice names no service, Preparing runs again there, and it plays. The
 * plays are the coordinator's, of releases in the order a list gave them, up to the player's launch.
 */
@RunWith(AndroidJUnit4::class)
class ServiceFailuresDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val fixture = DebridSearchFixture()

    /** Every sheet the coordinator showed, in order: collected as each is set, none passed over. */
    private val seen = CopyOnWriteArrayList<PlayState>()
    private var watching: Job? = null
    private var premiumize: String? = null

    @Before
    fun setUp() {
        fixture.open()
        onMainThread { container.playback.withdraw() }
        watching = CoroutineScope(Dispatchers.Unconfined).launch { container.playback.state.collect { seen += it } }
    }

    @After
    fun tearDown() {
        watching?.cancel()
        onMainThread { container.playback.cancel() }
        runBlocking { premiumize?.let { container.settings.signOut(it) } }
        fixture.close()
    }

    private val episode = EpisodePlay(
        showTmdbId = DebridSearchFixture.HARBOR_LIGHTS, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(),
        ids = TvIds(tvdb = 900001, tmdb = DebridSearchFixture.HARBOR_LIGHTS, imdb = "tt9000003"), season = 1, episode = 2,
        episodeName = DebridSearchFixture.EPISODES[1], airDate = fixture.today.minusDays(53), runtimeMinutes = 45,
    )

    /** [candidates] played as a list's automatic pick plays them, in their order. */
    private fun play(candidates: List<RankedItem>) = onMainThread {
        container.playback.play(
            PlayRequest(episode.showName, episode.subtitle, candidates, FilePicker.Episode(1, 2), episode.watch, rememberedFirst = false),
        )
    }

    /** S01E02's releases as the search lists them, once every check has come. */
    private suspend fun found(): List<RankedItem> {
        val events = container.finder.find(episode.request, container.indexerConfigs(), container.rankingContext(), null).toList()
        val result = events.filterIsInstance<FinderEvent.Joined>().lastOrNull()?.result ?: events.filterIsInstance<FinderEvent.Finished>().single().result
        return result.playable.map { RankedItem(it.item, it.ranked) }
    }

    /** A torrent of [hash], named [title], cached on the [accounts], as a list would hold it. */
    private fun cached(hash: String, title: String, vararg accounts: String) = RankedItem(
        NewznabItem(
            indexerId = "home-prowlarr", guid = "btih:$hash", title = title, sizeBytes = 1_468_006_400, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null,
            season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(), infoHash = hash,
            availability = Availability.Cached(accounts.toList()),
        ),
        RankedRelease(ReleaseFacts("btih:$hash", title, 1_468_006_400, source = ReleaseFacts.TORRENT), ReleaseParser.parse(title), playable = true, score = 0.0, reasons = emptyList(), estimatedBitrate = null),
    )

    private suspend fun launched(): PlayerLaunch = withTimeout(120_000) {
        merge(
            container.playback.launches,
            container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("nothing played: $it") },
        ).first()
    }

    private suspend fun failed(): PlayState.Failed = withTimeout(60_000) { container.playback.state.filter { it is PlayState.Failed }.first() as PlayState.Failed }

    /** The account [launch]'s listing came from: the one the release played from. */
    private suspend fun playedFrom(launch: PlayerLaunch): String? =
        (PlayUris.parse(launch.uri) as? PlayUris.Target.Service)?.let { container.debridListings.get(it.listingId)?.account }

    /**
     * The sheets [seen] of one release tried on its next service (DEP-6): the first in the order, [first], failing at
     * [failedAt] in [words]; then the notice "<words> · trying your next debrid service" with the steps after Preparing
     * waiting again; then Preparing running on [next], the tag naming it now; all in the release's one attempt.
     */
    private fun assertTriedOnTheNext(failedAt: PipelineStep, first: String, words: String, next: String) {
        val running = seen.filterIsInstance<PlayState.Running>()
        val at = running.indexOfFirst { it.steps[failedAt] is StepStatus.Failed }
        assertTrue("never failed at $failedAt: ${running.map { it.tag?.text to it.steps }}", at >= 0)
        assertEquals(first, running[at].tag?.text)
        assertEquals(words, (running[at].steps.getValue(failedAt) as StepStatus.Failed).failure.text)
        val after = running.drop(at)
        val notice = "$words · trying your next debrid service"
        val retried = after.first { it.noticeNextService }
        assertEquals(notice, retried.notice)
        assertEquals(setOf(PipelineStep.Prepare), retried.steps.keys)
        val preparing = after.first { it.tag?.text == next && it.steps[PipelineStep.Prepare] is StepStatus.Running }
        assertEquals(notice, preparing.notice)
        assertEquals("the steps after Preparing waiting again", setOf(PipelineStep.Prepare), preparing.steps.keys)
        assertTrue("one attempt: ${running.map { it.attempt to it.of }}", running.all { it.attempt == 1 && it.of == 1 })
        assertTrue("never another release's notice", running.none { it.notice?.contains("trying release") == true })
    }

    @Test
    fun aLimitOnTheServiceMovesOnWithoutNamingItAndTheUsenetReleasePlays() = runBlocking<Unit> {
        assumeTrue("the mock NNTP server isn't reachable at 10.0.2.2:1119", runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 1119), 2_000) } }.isSuccess)
        engineSetup("the mock on the engine") { mockOnEngine() }
        val releases = found()
        val torrent = releases.first { it.item.infoHash != null && it.item.title == GOOD }
        // Usenet's GOOD, and the next Usenet release after it.
        val usenet = releases.filter { it.item.infoHash == null }.take(2)
        assertEquals(GOOD, usenet.first().item.title)
        assertEquals(2, usenet.size)
        // TorBox's limit, with its wait, once the search's checks have come: the play's Preparing meets it.
        DebridFakes.control("limit?on=true&wait=1200")
        play(listOf(torrent) + usenet)
        val launch = launched()
        assertEquals(GOOD, launch.release.title)
        assertTrue(launch.uri, launch.uri.startsWith("usenet://"))
        val second = seen.filterIsInstance<PlayState.Running>().first { it.attempt == 2 }
        assertEquals("The debrid service’s limit was reached · trying release 2 of 3", second.notice)
        assertEquals("Usenet", second.tag?.text)
    }

    @Test
    fun everyReleaseFailingShowsTheFailureSheetWithTheShortWords() = runBlocking<Unit> {
        premiumize = container.settings.saveService(ServiceKind.Premiumize.name, DebridFakes.PREMIUMIZE_KEY) { ServiceSignIn(DebridFakes.PREMIUMIZE_ACCOUNT) }
        val torbox = container.services.on().single { it.kind == ServiceKind.TorBox.name }.id
        val releases = listOf(cached(GOOD_HASH, GOOD, torbox), cached(PACK_HASH, PACK, premiumize!!))
        compose.setContent { AppTheme { PlaySheetHost(container.playback, onSearch = {}, onProviders = {}) } }

        // Both services down: each release fails in the same neutral words.
        DebridFakes.control("down?on=true")
        play(releases)
        assertEquals(listOf(GOOD to DOWN, PACK to DOWN), failed().failures)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("✕ $DOWN")).fetchSemanticsNodes().size == 2 }

        // Both keys refused: each names its own service, the one failure that does.
        DebridFakes.control("down?on=false")
        DebridFakes.control("refuse-keys?on=true")
        // Closed, so the sheet waited on next is this play's.
        onMainThread { container.playback.dismiss() }
        play(releases)
        assertEquals(listOf(GOOD to "TorBox refused your API key", PACK to "Premiumize refused your API key"), failed().failures)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("✕ TorBox refused your API key")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("✕ Premiumize refused your API key")).assertExists()
    }

    /**
     * DEP-6, the probe's path: Premiumize signed in first, its CDN giving a page for the file: Checking audio and video
     * fails with no working link, the notice says so and that the next service is tried, Preparing runs again on TorBox,
     * and the release plays from TorBox.
     */
    @Test
    fun aFailedProbeOnTheFirstServiceTriesTheNextBeforeAnotherRelease() = runBlocking<Unit> {
        val premiumizeFirst = fixture.premiumizeFirst()
        assertEquals(listOf(premiumizeFirst, fixture.torBox), container.services.on().map { it.id })
        DebridFakes.control("cdn?service=premiumize&page=true")
        play(listOf(cached(GOOD_HASH, GOOD, fixture.torBox, premiumizeFirst)))
        val launch = launched()
        assertEquals(GOOD, launch.release.title)
        assertEquals(fixture.torBox, playedFrom(launch))
        assertTriedOnTheNext(PipelineStep.ProbeTracks, "Premiumize", "No longer available from the service", "TorBox")
        val counts = DebridFakes.control("counts")
        assertTrue(counts, "\"torbox createtorrent\":1" in counts)
        // TorBox's add, made by the next service's try, is this device's to remove as a first account's is (L232): kept
        // under the listing that plays.
        val listing = (PlayUris.parse(launch.uri) as PlayUris.Target.Service).listingId
        assertEquals(listOf(listing), container.debridAdds.records().filter { it.account == fixture.torBox }.map { it.listingId })
    }

    /**
     * DEP-6, Opening's path: TorBox moved first, its requestdl answering HTTP 500: Opening fails as the service stopped
     * answering, the notice says so and that the next service is tried, Preparing runs again on Premiumize, and the release
     * plays from Premiumize.
     */
    @Test
    fun aFailedOpeningOnTheFirstServiceTriesTheNextBeforeAnotherRelease() = runBlocking<Unit> {
        val premiumizeAccount = fixture.premiumizeFirst()
        container.settings.moveService(fixture.torBox, -1)
        assertEquals(listOf(fixture.torBox, premiumizeAccount), container.services.on().map { it.id })
        DebridFakes.control("fail?service=torbox&call=requestdl")
        play(listOf(cached(GOOD_HASH, GOOD, premiumizeAccount, fixture.torBox)))
        val launch = launched()
        assertEquals(GOOD, launch.release.title)
        assertEquals(premiumizeAccount, playedFrom(launch))
        assertTriedOnTheNext(PipelineStep.Open, "TorBox", DOWN, "Premiumize")
        val counts = DebridFakes.control("counts")
        assertTrue(counts, "\"torbox requestdl\":1" in counts)
        assertTrue(counts, "\"premiumize transfer/directdl\":1" in counts)
    }

    private companion object {
        /** FakeTorrents' and FakeCatalog's names and hashes (tools/mock-seed). */
        const val GOOD = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD"
        const val GOOD_HASH = "1bb5795cb9f72aa684193ddf405249b071b2ba5f"
        const val PACK = "Harbor.Lights.S01.1080p.WEB-DL.AAC2.0.H.264-TIDE"
        const val PACK_HASH = "b8e5ad63c94db5348740caabfbdca0083423ad21"
        const val DOWN = "The debrid service stopped answering"
    }
}
