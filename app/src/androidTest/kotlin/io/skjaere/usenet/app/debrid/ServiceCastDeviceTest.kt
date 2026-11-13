package io.skjaere.usenet.app.debrid

import android.app.Activity
import android.os.SystemClock
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.R
import io.skjaere.usenet.app.cast.CastFakes
import io.skjaere.usenet.app.cast.CastItems
import io.skjaere.usenet.app.cast.CastLoader
import io.skjaere.usenet.app.cast.CastSeams
import io.skjaere.usenet.app.cast.CastService
import io.skjaere.usenet.app.cast.Casting
import io.skjaere.usenet.app.cast.FakeConnector
import io.skjaere.usenet.app.cast.FakeLauncher
import io.skjaere.usenet.app.cast.FakeRemotePlayer
import io.skjaere.usenet.app.cast.FakeRoutes
import io.skjaere.usenet.app.cast.GoogleCast
import io.skjaere.usenet.app.cast.StandInReceiver
import io.skjaere.usenet.app.cast.castRoute
import io.skjaere.usenet.app.casting.CAST_SERVICE
import io.skjaere.usenet.app.casting.CastEvent
import io.skjaere.usenet.app.casting.CastEventHub
import io.skjaere.usenet.app.casting.CastFailKind
import io.skjaere.usenet.app.casting.CastFeed
import io.skjaere.usenet.app.casting.CastIdleReason
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastPlayerState
import io.skjaere.usenet.app.casting.startingOn
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.app.link.CastButtonLook
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.overlayNodes
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayMiniBar
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlaySheetHost
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.read
import io.skjaere.usenet.app.shell
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.views
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankingTarget
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.Severity
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A debrid play cast through the phone (W73; L217-L221; D2PhoneCastWords, the CsPhone boards standing), over the fakes
 * ([DebridSearchFixture]: TorBox signed in on the debrid fake, its CDN serving the README's 90 s mock video, the Usenet
 * indexer off) and a fake Cast session whose device player's LOAD a stand-in receiver in the test's process fetches
 * ([StandInReceiver], its own connections as the Shield's loader made them, then its media player over HTTP), through the
 * real cast service, its server, the real load ([Casting]) and the relay (ServiceByteSource), at the emulator's Wi-Fi
 * address:
 * (a) a cached torrent picked from a list ranked for Kitchen display plays to its end there, the sheet showing Preparing,
 *     Opening, Checking audio and video, then Starting on Kitchen display;
 * (b) nothing the stand-in got, the LOAD and every answer's headers, names the fake's address or its CDN's path, the key or
 *     the torrent, and the fake counted nothing the app must never send;
 * (c) the links expired mid-cast, a seek's read asks for one more link, and it plays on;
 * (d) a CDN that answers a ranged read with the whole file fails the seek's read once, the service asked nothing more, and
 *     the cast ends "Couldn't read the release from your debrid service";
 * (e) the service refusing the key as the head is opened: no LOAD, the card "✕ TorBox refused your API key";
 * (f) the player's Cast row during a debrid play moves the film there (Play & connect, the device's side a fake launcher);
 * (g) Resume of a torrent to Kitchen display plays the remembered torrent, with no search;
 * (h) a film the player moves to Kitchen display through the real load (L234), its grace cut to 2 s: what its play added
 *     stays in the account while it plays there, the player closed, and goes a grace after the cast ends.
 * The compose rule's clock drives every composition here (the sheet's, the player's), so every wait is on it. The show,
 * its releases, the account and the device are invented; the media synthetic.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ServiceCastDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val fixture = DebridSearchFixture()
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val kitchen = castRoute("Kitchen display")
    private val device = CastTo(kitchen.id, kitchen.name, kitchen.deviceId, kitchen.model)
    private val events = CastEventHub()
    private val receiver = StandInReceiver()

    /** Where the device's part runs beside the test: following its media player, never the test's own thread. */
    private val beside = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var remote: FakeRemotePlayer? = null
    private var player: ExoPlayer? = null

    /** Every answer's headers the stand-in's media player got. */
    private val playerHeaders = CopyOnWriteArrayList<Map<String, List<String>>>()

    /** A cast's load waits on this while it is set, before it begins ([held] counts those waiting). */
    @Volatile private var hold: CompletableDeferred<Unit>? = null
    private val held = AtomicInteger()

    /** Every sheet the coordinator showed, in order: collected as each is set, none passed over. */
    private val seen = CopyOnWriteArrayList<PlayState>()
    private var watching: Job? = null

    @Before
    fun setUp() {
        assumeFalse("a TV never casts (tv.TvNoCastTest)", container.television)
        fixture.open()
        fixture.usenetIndexerOn(false)
        CastFakes.started(routes, connector, CopyOnWriteArrayList())
        CastSeams.loader = { app ->
            CastLoader { launch, serving ->
                hold?.let {
                    held.incrementAndGet()
                    it.await()
                }
                Casting.with(app).load(launch, serving)
            }
        }
        CastSeams.events = { events }
        Casting.artOf = { _, _ -> null }
        // Nothing left over from another test for this one to take.
        onMainThread { container.playback.withdraw() }
        watching = CoroutineScope(Dispatchers.Unconfined).launch { container.playback.state.collect { seen += it } }
        compose.setContent {
            AppTheme {
                Column {
                    PlayMiniBar(container.playback)
                    PlaySheetHost(container.playback, onSearch = {}, onProviders = {})
                }
            }
        }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        everyStep(
            { hold?.complete(Unit) },
            { beside.cancel() },
            { receiver.close() },
            { player?.let { onMainThread { it.release() } } },
            { watching?.cancel() },
            { onMainThread { container.playback.cancel() } },
            { alive().forEach { activity -> onMainThread { activity.finish() } } },
            { container.playOn.choose(PlayChoice.Phone) },
            { CastFakes.reset() },
            { fixture.close() },
            { waitFor("the cast service gone") { !serviceRunning() } },
        )
    }

    /** (a) W73, L217: the torrent's own steps on the sheet, then the device's; the relay serves it to the end there. */
    @Test
    fun aCachedTorrentPickedFromAListRankedForKitchenDisplayPlaysToItsEndThere() {
        connectKitchen()
        val item = castGood()
        // The sheet as the device starts it: the torrent's three steps done, then the device's own.
        val starting = state() as PlayState.Running
        assertEquals(ServiceSource.STEPS, starting.sourceSteps)
        ServiceSource.STEPS.forEach { step -> assertTrue("$step done: ${starting.steps[step]}", starting.steps[step] is StepStatus.Done) }
        assertTrue("the device's step running: ${starting.castStep}", starting.castStep is StepStatus.Running)
        listOf("Preparing", "Opening", "Checking audio and video", startingOn(kitchen.name)).forEach { words -> waitFor(words) { exists(hasText(words)) } }

        val url = standIn(item)
        val film = headAndCues()
        val exo = playThere(url, item)
        // To its end: a seek near it, then FINISHED there. The play ends, and the service and its server go.
        val length = onMainThread { exo.duration }
        onMainThread { exo.seekTo(length - TAIL_MS) }
        waitFor("the film played to its end there", 60_000) { onMainThread { exo.playbackState } == Player.STATE_ENDED }
        tell(CastPlayerState.Idle, item.mediaId, length, CastIdleReason.Finished)
        waitFor("the cast service stopped") { !serviceRunning() }
        assertEquals("the head read the whole file", runBlocking { fileSize() }, film.size.toLong())
        assertEquals("nothing but 206", List(receiver.statuses.size) { 206 }, receiver.statuses.toList())
        assertTrue("more than two of its connections at once: ${receiver.mostOpen}", receiver.mostOpen <= 2)
    }

    /** (b) L217, L221: what a receiver gets of the phone holds nothing of the service's: not its CDN, its key or the torrent. */
    @Test
    fun nothingTheDeviceGetsNamesTheServicesCdnKeyOrTorrent() {
        connectKitchen()
        val item = castGood()
        val load = onMainThread { CastItems.toMediaQueueItem(item).toJson().toString() }
        assertNamesNothingOfTheService("the LOAD", load)
        val url = standIn(item)
        headAndCues()
        val exo = playThere(url, item)
        waitFor("the stand-in's player read a while", 20_000) { onMainThread { exo.currentPosition } > 4_000 }
        assertTrue("the stand-in's own answers' headers seen", receiver.headers.size >= 2)
        assertTrue("its player's answers' headers seen", playerHeaders.isNotEmpty())
        (receiver.headers.map { it.toString() } + playerHeaders.map { it.toString() }).forEach { assertNamesNothingOfTheService("an answer's headers", it) }
        assertEquals("nothing the app must never send", emptyMap<String, Int>(), problems().filterValues { it > 0 })
    }

    /** (c) L217, W81: a link expired mid-cast is asked for once more at the next read, and the cast plays on. */
    @Test
    fun linksExpiredMidCastAskForOneMoreAtTheNextReadAndItPlaysOn() {
        connectKitchen()
        val item = castGood()
        val url = standIn(item)
        headAndCues()
        val exo = playThere(url, item)
        waitFor("the stand-in's player a few seconds in", 20_000) { onMainThread { exo.currentPosition } > 3_000 }
        val before = calls(REQUESTDL)
        DebridFakes.control("cdn-expire")
        // A seek's read back to the start, on a new connection: no stream the server parks starts there or before, so the
        // relay opens one, and its expired link gets one fresh link. The same bytes.
        val seek = receiver.ask(SEEK_AT) ?: throw AssertionError("the seek's request unanswered")
        assertEquals(206, seek.status)
        seek.read(SEEK_READ)
        receiver.cut(seek)
        assertEquals("one more link", before + 1, calls(REQUESTDL))
        // The device's own seek plays on, to the end, with no other link asked for.
        val length = onMainThread { exo.duration }
        onMainThread { exo.seekTo(length - TAIL_MS) }
        waitFor("played on to its end there", 60_000) { onMainThread { exo.playbackState } == Player.STATE_ENDED }
        assertEquals("no other link", before + 1, calls(REQUESTDL))
        tell(CastPlayerState.Idle, item.mediaId, length, CastIdleReason.Finished)
        waitFor("the cast service stopped") { !serviceRunning() }
    }

    /**
     * (d) L217, L220: the CDN answering a ranged read with the whole file (its links made so before the play) is no working
     * link, final at once: the head from byte 0 reads, the seek's read fails, the read asked again fails with no call of
     * the service's or the CDN's, and the device gives up, the cast ending in the debrid service's words.
     */
    @Test
    fun aCdnIgnoringRangesFailsTheSeeksReadOnceAndTheCastEndsInTheDebridServicesWords() {
        connectKitchen()
        DebridFakes.control("cdn?whole=true")
        val item = castGood()
        standIn(item)
        val head = receiver.ask(0) ?: throw AssertionError("the head's request unanswered")
        assertEquals(206, head.status)
        head.read(BURST)
        val links = calls(REQUESTDL)
        val whole = cdnAnswers(200)
        // The seek's read: the end's index, which the CDN answers with the whole file. Its headers, then the server's close.
        val tail = (head.total - CUES_BACK) / ALIGN * ALIGN
        val cues = receiver.ask(tail) ?: throw AssertionError("the seek's request unanswered")
        assertTrue("the seek's read failed", cues.endsHere())
        assertEquals("the CDN asked once for the seek", whole + 1, cdnAnswers(200))
        // Asked again 0.3 s later, as the receiver does: refused at once, nothing asked of the service or its CDN.
        frames(300)
        receiver.ask(tail)?.let { again -> assertTrue("the read asked again failed", again.endsHere()) }
        assertEquals("no link asked for", links, calls(REQUESTDL))
        assertEquals("the CDN not asked again", whole + 1, cdnAnswers(200))
        // The device gives up on what it couldn't read (a network error), before it played.
        onMainThread { events.tell(CastEvent.MediaError(MEDIA_NETWORK)) }
        val failed = await("the cast failed") { state() as? PlayState.Failed }
        assertEquals(CastFailKind.CouldNotPlay, failed.cast?.kind)
        assertEquals(CAST_SERVICE, failed.cast?.failure?.reason)
        assertTrue("the release's fault: Try another release leaves it out", failed.cast?.failure?.providers == true)
        waitFor("its card") { exists(hasText("✕ $CAST_SERVICE")) }
    }

    /**
     * (e) L218 (C1): the key refused as the head's link is asked for again (the one held expired): the load fails there,
     * before anything goes to the device, its card in the refusal's words, which name the service from its data alone.
     */
    @Test
    fun aKeyRefusedAtTheHeadSendsNoLoadAndTheCardSaysSo() {
        connectKitchen()
        val loads = remote!!.loads.size
        hold = CompletableDeferred()
        pickGood()
        waitFor("the cast's load, held before it begins", 120_000) { held.get() == 1 }
        DebridFakes.control("cdn-expire")
        DebridFakes.control("refuse-keys?on=true")
        hold!!.complete(Unit)
        val failed = await("the cast failed", 60_000) { state() as? PlayState.Failed }
        assertEquals(CastFailKind.CouldNotPlay, failed.cast?.kind)
        assertEquals("TorBox refused your API key", failed.cast?.failure?.reason)
        assertTrue(failed.cast?.failure?.providers == true)
        waitFor("its card") { exists(hasText("✕ TorBox refused your API key")) }
        assertEquals("no LOAD", loads, remote!!.loads.size)
    }

    /** (f) L202 lifted, L222: the player's Cast row during a debrid play pauses it and moves it there, the player then closing. */
    @Test
    fun thePlayersCastRowMovesADebridFilmThere() {
        val launcher = FakeLauncher()
        CastSeams.launcher = { launcher }
        routes.found(kitchen)
        container.playOn.choose(PlayChoice.Phone)
        onMainThread { container.playback.playEpisode(episode(2)) }
        val launch = await("the torrent's launch here", 120_000) { runBlocking { withTimeoutOrNull(100) { container.playback.launches.first() } } }
        assertTrue(launch.uri, launch.uri.startsWith("debrid://"))
        assertEquals(GOOD, launch.release.title)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            await("the film to play", 60_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            await("the film a few seconds in", 30_000) { scenario.read { a -> a.player?.takeIf { it.currentPosition > 3_000 } } }
            tapRow(scenario, kitchen.name)
            await("the film paused, moving") { scenario.read { a -> moment(a)?.takeIf { it == "Moving to ${kitchen.name}…" && a.player?.playWhenReady == false } } }
            connector.started(kitchen)
            val loaded = await("the launch loaded on the device") { launcher.loads.firstOrNull() }
            assertEquals("the same debrid play, there", launch.uri, loaded.uri)
            assertEquals(kitchen.name, loaded.cast?.name)
            onMainThread { Casting.follows(CastFeed("service-cast-load", loaded, CastPlayerState.Playing, loaded.startPositionMs ?: 0, 60_000)) }
            await("the remote's play") { container.remotePlaying.now.value }
            launcher.plays()
            waitFor("the player closed") { players().all { it.isFinishing || it.isDestroyed } }
        }
        assertEquals("nothing stopped", 0, launcher.stops.get())
    }

    /** (g) L202 lifted (C2): Resume to Kitchen display plays the torrent the episode played from, from where it stopped, with no search. */
    @Test
    fun resumeToKitchenDisplayPlaysTheRememberedTorrentWithNoSearch() {
        connectKitchen()
        val ep = episode(2)
        val torrent = RememberedRelease(
            "debrid-cast-indexer", "btih:$GOOD_HASH", GOOD, fileIndex = 0, nzbId = "btih:$GOOD_HASH", fileName = "$GOOD.mkv", source = ReleaseFacts.TORRENT,
        )
        val state = runBlocking {
            container.library.recordProgress(
                WatchState(ep.key, WatchKind.Episode, showTmdbId = DebridSearchFixture.HARBOR_LIGHTS, season = 1, episode = 2, title = WatchTitle.episode("Harbor Lights", 1, 2), updatedAt = 0),
                RESUME_AT, RESUME_LENGTH, torrent,
            )
            container.library.state(ep.key).first()!!
        }
        val loads = remote!!.loads.size
        seen.clear()
        onMainThread { container.playback.resume(state, PlayOrigin(cast = device)) }
        await("the LOAD", 120_000) { remote!!.loads.drop(loads).lastOrNull() }
        val feed = Casting.feed.value ?: throw AssertionError("no play followed there")
        assertEquals("the remembered torrent", GOOD, feed.launch.release.title)
        assertEquals(ReleaseFacts.TORRENT, feed.launch.release.source)
        assertEquals("from where it stopped", RESUME_AT, feed.positionMs)
        assertFalse("Resume searched", seen.any { it is PlayState.Searching && it.line == "Searching your indexers…" })
    }

    /**
     * (h) L234, W82: the cast's lease, taken as its load begins, holds what the play added while it plays there, so the
     * player's close on the move isn't a stop; the grace begins as the cast ends. Its grace cut to 2 s through the records'
     * seam, Kitchen display connected before the play, which plays here as the coordinator's own play does.
     */
    @Test
    fun aFilmMovedThereKeepsItsAddWhileItCastsAndLosesItAGraceAfterTheCastEnds() {
        container.debridAdds.grace = MOVE_GRACE_MS
        try {
            connectKitchen()
            // Its row in the player's list, the session connected already: the move connects nothing more (L124).
            routes.found(kitchen)
            onMainThread { container.playback.playEpisode(episode(2)) }
            val launch = await("the torrent's launch here", 120_000) { runBlocking { withTimeoutOrNull(100) { container.playback.launches.first() } } }
            assertEquals(GOOD, launch.release.title)
            assertEquals("added once", 1, counts().getInt("adds"))
            val loads = remote!!.loads.size
            ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
                await("the film to play", 60_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
                await("the film a few seconds in", 30_000) { scenario.read { a -> a.player?.takeIf { it.currentPosition > 3_000 } } }
                tapRow(scenario, kitchen.name)
                val item = await("the move's LOAD", 120_000) { remote!!.loads.drop(loads).lastOrNull() }
                val url = standIn(item)
                headAndCues()
                val exo = playThere(url, item)
                waitFor("the player closed") { players().all { it.isFinishing || it.isDestroyed } }
                // Three graces with the player gone and the film playing there: nothing removed.
                frames(3 * MOVE_GRACE_MS)
                assertEquals("nothing removed while it casts", 0, counts().getInt("removals"))
                assertTrue("still in the account", GOOD_HASH in accountHashes())
                // To its end there: the cast ends, and a grace later the add goes.
                val length = onMainThread { exo.duration }
                onMainThread { exo.seekTo(length - TAIL_MS) }
                waitFor("the film played to its end there", 60_000) { onMainThread { exo.playbackState } == Player.STATE_ENDED }
                tell(CastPlayerState.Idle, item.mediaId, length, CastIdleReason.Finished)
                waitFor("the cast service stopped") { !serviceRunning() }
            }
            waitFor("removed a grace after the cast ended", MOVE_GRACE_MS + 10_000) { counts().getInt("removals") == 1 }
            assertFalse("gone from the account", GOOD_HASH in accountHashes())
        } finally {
            container.debridAdds.grace = io.skjaere.usenet.app.debrid.DebridAdds.GRACE.toMillis()
        }
    }

    // The play and the device.

    /** Kitchen display connected and chosen in Play on, with no cast service left over. */
    private fun connectKitchen() {
        CastFakes.connected(kitchen, connector)
        remote = onMainThread { GoogleCast.connection.player(context) } as FakeRemotePlayer
        container.playOn.choose(PlayChoice.Cast(kitchen.id, kitchen.name))
        waitFor("Kitchen display connected") { container.playOn.target.value == PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts) }
        waitFor("no cast service left over") { !serviceRunning() }
    }

    private fun episode(n: Int) = EpisodePlay(
        showTmdbId = DebridSearchFixture.HARBOR_LIGHTS, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(),
        ids = TvIds(tvdb = 900001, tmdb = DebridSearchFixture.HARBOR_LIGHTS, imdb = "tt9000003"), season = 1, episode = n,
        episodeName = DebridSearchFixture.EPISODES[n - 1], airDate = fixture.today.minusDays(60L - 7 * (n - 1)), runtimeMinutes = 45,
    )

    /**
     * S01E02's GOOD from a search ranked for Kitchen display, as its release list ranks it: playable there (L202 lifted),
     * picked by hand and played through the router, Kitchen display chosen.
     */
    private fun pickGood() {
        val ep = episode(2)
        val good = runBlocking {
            val ranking = container.rankingContext()
            assertTrue("ranked for Kitchen display: ${ranking.target}", ranking.target is RankingTarget.Cast)
            val found = container.finder.find(ep.request, container.indexerConfigs(), ranking, null).toList()
            val result = found.filterIsInstance<FinderEvent.Joined>().lastOrNull()?.result ?: found.filterIsInstance<FinderEvent.Finished>().single().result
            result.playable.map { RankedItem(it.item, it.ranked) }.single { it.item.title == GOOD }
        }
        assertTrue("nothing keeps it from Kitchen display", good.ranked.reasons.none { it.severity == Severity.Blocking })
        onMainThread { container.playRouter.chosen(PlayRequest(ep.showName, ep.subtitle, listOf(good), FilePicker.Episode(1, 2), ep.watch, chosen = true)) }
    }

    /** [pickGood], and the LOAD it sends Kitchen display. */
    private fun castGood(): MediaItem {
        val loads = remote!!.loads.size
        pickGood()
        return await("the LOAD", 120_000) { remote!!.loads.drop(loads).lastOrNull() }
    }

    /** The stand-in at the address [item]'s LOAD carries: the phone's own, its server's token path. */
    private fun standIn(item: MediaItem): String {
        val url = onMainThread { CastItems.toMediaQueueItem(item).media?.contentUrl } ?: throw AssertionError("no address in the LOAD")
        assertNamesNothingOfTheService("the LOAD's address", url)
        receiver.at(url)
        return url
    }

    /**
     * T1, T2: `bytes=0-` first, read to the end, whose bytes each later answer is read against; then the Cues at the end,
     * on a new connection: one more GET through the relay, at its byte. The file's bytes.
     */
    private fun headAndCues(): ByteArray {
        val head = receiver.ask(0) ?: throw AssertionError("the head's request unanswered")
        assertEquals(206, head.status)
        assertEquals("bytes 0-${head.total - 1}/${head.total}", head.header("content-range"))
        assertEquals("video/x-matroska", head.header("content-type"))
        val film = head.toEnd().bytes()
        receiver.knows(film)
        val cues = receiver.ask(film.lastAt(CUES) / ALIGN * ALIGN) ?: throw AssertionError("the tail's request unanswered")
        assertEquals(206, cues.status)
        cues.toEnd()
        return film
    }

    /**
     * The stand-in's media player plays [url] from its start, over HTTP, as a receiver's does; the session's player follows
     * it, and the device says it plays [item]: the load played, the play sheet goes.
     */
    private fun playThere(url: String, item: MediaItem): ExoPlayer {
        val exo = play(url)
        waitFor("the stand-in's player playing", 30_000) { onMainThread { exo.isPlaying } }
        val length = onMainThread { exo.duration }
        remote!!.lasting(length)
        remote!!.playing()
        tell(CastPlayerState.Playing, item.mediaId, onMainThread { exo.currentPosition })
        waitFor("the play sheet closed: the device plays it") { state() == PlayState.Idle }
        follow(exo, item.mediaId)
        return exo
    }

    /** The stand-in's media player on [url], playing, silent, every answer's headers kept: diagnostics off, as every player the app builds. */
    private fun play(url: String): ExoPlayer = onMainThread {
        val headers = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                playerHeaders += source.responseHeaders
            }
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) = Unit
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        }
        ExoPlayer.Builder(context)
            .setUsePlatformDiagnostics(false)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultHttpDataSource.Factory().setUserAgent(StandInReceiver.SHIELD_AGENT).setTransferListener(headers)))
            .build()
            .apply {
                volume = 0f
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                play()
            }
    }.also { player = it }

    /**
     * The device as the session's player says it, while [exo] plays [id]: its place, which Media3's player moves on with no
     * event, and an answer to each of the phone's status asks (L103), as the receiver gives.
     */
    private fun follow(exo: ExoPlayer, id: String): Job = beside.launch {
        var asked = connector.media.asked.get()
        while (true) {
            remote?.progressed(onMainThread { exo.currentPosition })
            val now = connector.media.asked.get()
            if (now != asked) {
                asked = now
                onMainThread { events.tell(statusOf(exo, id)) }
            }
            delay(FOLLOW_MS)
        }
    }

    /** What the receiver says of [exo]'s play of [id]: playing, paused, buffering, or done. On the main thread, the player's. */
    private fun statusOf(exo: ExoPlayer, id: String): CastEvent.Status {
        val at = exo.currentPosition
        val now = SystemClock.elapsedRealtime()
        return when {
            exo.playbackState == Player.STATE_ENDED -> CastEvent.Status(CastPlayerState.Idle, CastIdleReason.Finished, id, at, now)
            exo.isPlaying -> CastEvent.Status(CastPlayerState.Playing, null, id, at, now)
            !exo.playWhenReady -> CastEvent.Status(CastPlayerState.Paused, null, id, at, now)
            else -> CastEvent.Status(CastPlayerState.Buffering, null, id, at, now)
        }
    }

    /** The device says it is [state] in [id] at [positionMs] ([idle]: why it is idle). */
    private fun tell(state: CastPlayerState, id: String, positionMs: Long, idle: CastIdleReason? = null) =
        onMainThread { events.tell(CastEvent.Status(state, idle, id, positionMs, SystemClock.elapsedRealtime())) }

    // The player's Cast row (as PlayerCastDeviceTest reaches it).

    /** The Cast device [name]'s row tapped in the player's list, opened from its button with the controls up. */
    private fun tapRow(scenario: ActivityScenario<PlayerActivity>, name: String) {
        scenario.onActivity { a ->
            playerView(a).controllerShowTimeoutMs = 0
            playerView(a).showController()
        }
        await("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
        await("the Cast button") { scenario.read { a -> castNode(a) } }
        scenario.onActivity { a -> castNode(a)!!.config[SemanticsActions.OnClick].action!!.invoke() }
        await("$name's row") { scenario.read { a -> castRow(a, name) } }
        scenario.onActivity { a -> castRow(a, name)!!.config[SemanticsActions.OnClick].action!!.invoke() }
    }

    private fun castNode(activity: Activity): SemanticsNode? = overlayNodes(activity).firstOrNull { it.config.getOrNull(CastButtonLook) != null }

    private fun castRow(activity: Activity, name: String): SemanticsNode? = overlayNodes(activity).firstOrNull { n ->
        n.config.getOrNull(SemanticsActions.OnClick) != null && n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("$name, Cast device") }
    }

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    /** The player's moment label's words while it shows. */
    private fun moment(activity: Activity): String? = activity.findViewById<TextView>(R.id.moment_label)?.takeIf { it.isShown }?.text?.toString()

    private fun players(): List<PlayerActivity> = alive().filterIsInstance<PlayerActivity>()

    /** The app's players and MainActivity instances alive. */
    private fun alive(): List<Activity> = onMainThread {
        ALIVE.flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.filter { it is PlayerActivity || it is MainActivity }.distinct()
    }

    // The fake's counts, the sheet and the waits.

    private fun counts() = JSONObject(DebridFakes.control("counts"))

    /** The hashes the fake's TorBox account holds, its whole list as the app's key reads it. */
    private fun accountHashes(): Set<String> {
        val connection = java.net.URL("${DebridFakes.endpoint(io.skjaere.usenet.debrid.ServiceKind.TorBox)}/torrents/mylist?bypass_cache=true").openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("Authorization", "Bearer ${DebridFakes.TORBOX_KEY}")
        try {
            // No torrents: TorBox's 404 under success true, as the fake answers a list with none.
            if (connection.responseCode == 404) {
                val error = JSONObject(connection.errorStream.use { it.readBytes().decodeToString() }).optString("error")
                if (error == "ITEM_NOT_FOUND") return emptySet()
            }
            check(connection.responseCode == 200) { "the fake's mylist answered ${connection.responseCode}" }
            val data = JSONObject(connection.inputStream.use { it.readBytes().decodeToString() }).getJSONArray("data")
            return (0 until data.length()).map { data.getJSONObject(it).getString("hash") }.toSet()
        } finally {
            connection.disconnect()
        }
    }

    private fun calls(name: String) = counts().getJSONObject("calls").optInt(name)

    private fun cdnAnswers(status: Int) = counts().getJSONObject("cdn").optInt("$status")

    private fun problems(): Map<String, Int> = counts().getJSONObject("problems").let { p -> p.keys().asSequence().associateWith { p.getInt(it) } }

    /** The sample's size as the service lists it: the listing kept for the play. */
    private suspend fun fileSize(): Long? = container.debridListings.find(io.skjaere.usenet.debrid.InfoHash.parse(GOOD_HASH)!!)?.prepared?.listing?.files?.single()?.size

    private fun assertNamesNothingOfTheService(what: String, words: String) {
        listOf("10.0.2.2:${DebridFakes.DEBRID_PORT}", "/cdn/", "/torbox/", DebridFakes.TORBOX_KEY, GOOD_HASH, "magnet:").forEach { banned ->
            assertFalse("$what names $banned", banned in words)
        }
    }

    private fun state(): PlayState = container.playback.state.value

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** Android's record of the cast service, read and never printed: it names the app's process. */
    private fun serviceRunning(): Boolean =
        instrumentation.uiAutomation.shell("dumpsys activity services ${context.packageName}/${CastService::class.java.name}").let { "ServiceRecord{" in it && "CastService" in it }

    /** [millis] of the rule's clock, which drives the compositions: never a sleep. */
    private fun frames(millis: Long) {
        val until = SystemClock.uptimeMillis() + millis
        compose.waitUntil(millis + 5_000) { SystemClock.uptimeMillis() > until }
    }

    /** Waits on the rule's clock until [done]. */
    private fun waitFor(what: String, millis: Long = 20_000, done: () -> Boolean) = compose.waitUntil("$what, in ${millis / 1_000} s", millis, done)

    /** Waits on the rule's clock for [probe] to give something. */
    private fun <T : Any> await(what: String, millis: Long = 20_000, probe: () -> T?): T {
        var found: T? = null
        compose.waitUntil("$what, in ${millis / 1_000} s", millis) { probe()?.also { found = it } != null }
        return found!!
    }

    /** Where [part] last starts in these bytes. */
    private fun ByteArray.lastAt(part: ByteArray): Long =
        (size - part.size downTo 0).first { at -> part.indices.all { this[at + it] == part[it] } }.toLong()

    private companion object {
        /** FakeTorrents' S01E02 TorBox has cached (tools/mock-seed). */
        const val GOOD = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD"
        const val GOOD_HASH = "1bb5795cb9f72aa684193ddf405249b071b2ba5f"
        const val REQUESTDL = "torbox requestdl"

        /** Matroska's Cues element id: the file's last, its index. */
        val CUES = byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B)

        /** The receiver asks from 32 KiB boundaries; (c)'s seek reads from the start, before any stream the server parks. */
        const val ALIGN = 32_768L
        const val SEEK_AT = 0L
        const val SEEK_READ = 128L * 1024
        const val BURST = 256L * 1024

        /** How far before the file's end (d)'s seek reads: in its last cluster, near the Cues. */
        const val CUES_BACK = 96L * 1024

        /** Where the stand-in seeks to see the end: this long before it. */
        const val TAIL_MS = 8_000L
        const val FOLLOW_MS = 200L

        /** The receiver's detailed code for a network error (MEDIA_NETWORK). */
        const val MEDIA_NETWORK = 103

        /** (h)'s grace through the records' seam: the device's 2 minutes cut to 2 s. */
        const val MOVE_GRACE_MS = 2_000L

        /** Where (g)'s episode stopped, of the sample's 90 s: past the minute under which a play starts again from its beginning. */
        const val RESUME_AT = 70_000L
        const val RESUME_LENGTH = 90_000L

        val ALIVE = arrayOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
    }
}
