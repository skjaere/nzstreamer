package io.skjaere.usenet.app.tv

import android.app.Activity
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.GapFixtures
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.LocalNntp
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.link.PairedPhones
import io.skjaere.usenet.app.link.softwareKey
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.AutoAdvance
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.NowPlaying
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.RemotePlay
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.StartedFrom
import io.skjaere.usenet.app.play.StoppedPlay
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.IndexerRecord
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.link.FinalKind
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Pause
import io.skjaere.usenet.link.LinkMessage.Play
import io.skjaere.usenet.link.LinkMessage.Resume
import io.skjaere.usenet.link.LinkMessage.SeekBy
import io.skjaere.usenet.link.LinkMessage.SeekTo
import io.skjaere.usenet.link.LinkMessage.Stop
import io.skjaere.usenet.link.Playback
import io.skjaere.usenet.newznab.TvIds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A phone's Play on a real TV (tv36, L8, L18, L32, L40): from Home it starts the movie as from the TV's own Movie page,
 * started from the phone; a second phone's Play while it plays hands over, the first's place saved; one while "Playback
 * stopped" shows hands over too; Pause, Resume, SeekTo and SeekBy act once each, also while the player is still being
 * made; Stop leaves the player, and the phone gets where it stopped, a Final, until it acks it (L33). And without the
 * mock NNTP server, the next episode the player hands on by itself (auto-advance) stays in the phone's session, the
 * episode before's Final reaching the phone.
 *
 * Nothing needs the mock (L40): the films are the device tests' own media, posted on a server inside the test's process
 * ([LocalNntp]), each the release that played it before, its NZB in the store, which the Movie page's Play plays alone.
 * One indexer is enabled, at an address nothing answers, every other switched off, so the TV can play; none is ever asked
 * ([PlaybackCoordinator.fetchNzb] counts no grab). The phones and the titles are invented.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class TvIncomingPlayTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    /** The player hides the system bars: Android's hint about that would take its window. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val pages = LinkPages(compose)
    private val home = HomeContentShown()
    private val phones = PairedPhones()
    private val linked = mutableListOf<LinkedPhone>()
    private val local = LocalNntp()
    private val grabs = AtomicInteger()
    private val realFetch = PlaybackCoordinator.fetchNzb
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realRead = PlayerActivity.readPlayback
    private val realSources = PlayerActivity.dataSources
    private val realNext = PlayerActivity.nextEpisode
    private val realTimings = PlayerActivity.timings
    private lateinit var saved: AppSettings

    /** Past [assumeTv]: on a phone the class is skipped, and there is nothing to put back. */
    private var set = false

    @Before
    fun setUp() {
        assumeTv()
        set = true
        phones.clear()
        home.show()
        instrumentation.setInTouchMode(false)
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        // No switch of the TV's frame rate to wait on.
        PlayerActivity.readPlayback = { realRead(it).copy(matchFrameRate = false) }
        PlaybackCoordinator.fetchNzb = { c, indexer, item -> grabs.incrementAndGet(); realFetch(c, indexer, item) }
        runBlocking {
            saved = container.settings.settings.first()
            // One indexer at an address nothing answers, the rest switched off: the TV can play, and asks none.
            container.settings.restore(saved.copy(indexers = saved.indexers.map { it.copy(enabled = false) } + IndexerRecord(INDEXER, "Unanswered", "http://10.0.2.2:9")))
            engineSetup("the engine on the test's own NNTP server") {
                mockOnEngine()
                container.engine.configure(EngineSettings(listOf(local.provider)))
            }
        }
    }

    @After
    fun tearDown() {
        if (!set) return
        linked.forEach { it.close() }
        instrumentation.setInTouchMode(true)
        PlaybackCoordinator.fetchNzb = realFetch
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.readPlayback = realRead
        PlayerActivity.dataSources = realSources
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.timings = realTimings
        container.playback.cancel()
        live<PlayerActivity>(Stage.RESUMED).forEach { onMainThread { it.finish() } }
        local.close()
        phones.restore()
        home.restore()
        runBlocking {
            if (::saved.isInitialized) container.settings.restore(saved)
            listOf(BELL to "Harbor Bell", ORCHARD to "The Glass Orchard").forEach { (film, title) ->
                container.library.recordProgress(WatchState(WatchKey.movie(film), WatchKind.Movie, movieTmdbId = film, title = title, updatedAt = 0), 0, DURATION_MS)
            }
        }
    }

    private inline fun <reified A : Activity> live(stage: Stage): List<A> =
        onMainThread { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).filterIsInstance<A>() }

    private fun phone(name: String) = LinkedPhone(name, pages, phones).also { linked += it }

    /** [title] (TMDB id [film]) as its Movie page knows it. */
    private fun movie(film: Int, title: String) = Play.Movie(film, title, 2026)

    /**
     * [title]'s release, the device tests' chapters.mkv (6 minutes) posted on the test's server under [tag], prepared and
     * in the store, as the release that played the film before: the Movie page's Play plays it alone, with no search.
     */
    private suspend fun film(film: Int, title: String, tag: String) {
        val fixtures = GapFixtures(local, INDEXER, WatchTarget(WatchKind.Movie, title, movieTmdbId = film))
        val release = fixtures.release("chapters.mkv", fixtures.media("chapters.mkv"), PART, tag, RELEASE) { emptySet() }
        val remembered = fixtures.saved(tag, release.prepared).toRemembered()
        container.library.recordProgress(WatchState(WatchKey.movie(film), WatchKind.Movie, movieTmdbId = film, title = title, updatedAt = 0), 0, DURATION_MS, remembered)
    }

    /** What the player in front says it plays, once [done] holds. */
    private suspend fun now(what: String, timeoutMs: Long = 45_000, done: (NowPlaying) -> Boolean): NowPlaying =
        waitFor(what, timeoutMs) { container.playerInFront.now.value?.takeIf(done) }

    /** The player of [session], playing. */
    private suspend fun playing(session: String): NowPlaying = now("the phone's play playing") { it.remote?.session == session && it.state == Playback.Playing }

    /** The player in front, as the test reads it on the main thread. */
    private fun position(): Long = live<PlayerActivity>(Stage.RESUMED).single().let { player -> onMainThread { player.player!!.currentPosition } }

    /**
     * [launch]'s player, once it plays, started over the app's screens ([main]) as the play sheet starts one. As on a TV,
     * they come back as it leaves, so the app stays in front and the TV listening (L31). A player started alone would leave
     * none of the app's behind it: a second after it left (InFront) the TV would stop listening and close the phone's link,
     * before the Stopped it says 1.5 s after (StatusPump), and at times before the Final the player makes as it leaves went
     * out or the phone's FinalAck came in.
     */
    private suspend fun play(main: ActivityScenario<MainActivity>, launch: PlayerLaunch): PlayerActivity {
        main.onActivity { PlayerActivity.start(it, launch) }
        return waitFor("S01E01 playing", timeoutMs = 30_000) {
            live<PlayerActivity>(Stage.RESUMED).singleOrNull()?.takeIf { player -> onMainThread { player.player?.isPlaying == true } }
        }
    }

    @Test
    fun aPlayFromHomeStartsTheMovieAsStartedFromThePhone() = runBlocking<Unit> {
        film(ORCHARD, "The Glass Orchard", "orchard-1")
        pages.launch().use {
            val galaxy = phone("Invented phone")
            val session = galaxy.session(galaxy.ask(Play(1, movie = movie(ORCHARD, "The Glass Orchard"))))
            // The sheet's line, "Started from Invented phone", for this play (the sheet itself: PlaySheetTvTest).
            assertEquals(StartedFrom("Invented phone"), container.playback.startedFrom.value)
            val now = playing(session)
            assertEquals(RemotePlay(session, galaxy.key.id.hex, "Invented phone"), now.remote)
            assertEquals("The Glass Orchard", now.title)
            assertEquals(WatchKey.movie(ORCHARD), now.watchKey)
            assertEquals("no indexer asked", 0, grabs.get())
        }
    }

    @Test
    fun aSecondPlayWhileItPlaysHandsOverAndTheFirstsPlaceIsSaved() = runBlocking<Unit> {
        film(BELL, "Harbor Bell", "bell-2")
        film(ORCHARD, "The Glass Orchard", "orchard-2")
        pages.launch().use {
            val galaxy = phone("Invented phone")
            val first = galaxy.session(galaxy.ask(Play(1, movie = movie(BELL, "Harbor Bell"))))
            playing(first)
            val firstPlayer = live<PlayerActivity>(Stage.RESUMED).single()
            // Past the minute a place is kept for Resume from.
            assertEquals(LinkMessage.Ack(2, first), galaxy.ask(SeekTo(2, first, 120_000)))
            now("past two minutes") { it.remote?.session == first && it.positionMs >= 121_000 }
            val before = SystemClock.elapsedRealtime()
            val tablet = phone("Invented tablet")
            val second = tablet.session(tablet.ask(Play(1, movie = movie(ORCHARD, "The Glass Orchard"))))
            val now = playing(second)
            assertEquals("The Glass Orchard", now.title)
            waitFor("the first player gone") { firstPlayer.isDestroyed.takeIf { it } }
            // Its place saved as it left, and named on the new play's sheet.
            val row = container.library.state(WatchKey.movie(BELL)).first()!!
            assertTrue("Harbor Bell saved at ${row.positionMs} ms", row.positionMs >= 121_000)
            val stopped = container.playback.startedFrom.value?.stopped
            assertEquals("Harbor Bell", stopped?.title)
            assertTrue("stopped at ${stopped?.positionMs}", (stopped?.positionMs ?: 0) >= 121_000)
            assertTrue("Resume goes on from there", stopped?.resumable == true)
            assertEquals(StartedFrom("Invented tablet", stopped), container.playback.startedFrom.value)
            assertTrue("handed over at once", SystemClock.elapsedRealtime() - before < 45_000)
            assertEquals(0, grabs.get())
        }
    }

    /** A stream that fails as it opens, for good: the player shows "Playback stopped". */
    private class Failing : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long = throw FileNotFoundException("the test's stream")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw FileNotFoundException("the test's stream")
        override fun getUri(): Uri? = null
        override fun close() = Unit
    }

    @Test
    fun aPlayWhilePlaybackStoppedShowsHandsOver() = runBlocking<Unit> {
        film(BELL, "Harbor Bell", "bell-3")
        film(ORCHARD, "The Glass Orchard", "orchard-3")
        pages.launch().use {
            PlayerActivity.dataSources = { DataSource.Factory { Failing() } }
            val galaxy = phone("Invented phone")
            val first = galaxy.session(galaxy.ask(Play(1, movie = movie(BELL, "Harbor Bell"))))
            val failed = now("Playback stopped") { it.remote?.session == first && it.state == Playback.Failed }
            assertTrue("the panel's words: ${failed.failure}", failed.failure?.isNotEmpty() == true)
            PlayerActivity.dataSources = realSources
            val tablet = phone("Invented tablet")
            val second = tablet.session(tablet.ask(Play(1, movie = movie(ORCHARD, "The Glass Orchard"))))
            assertEquals("The Glass Orchard", playing(second).title)
            // It stopped as it opened: the new play's sheet has nothing to say of it.
            assertEquals(StartedFrom("Invented tablet"), container.playback.startedFrom.value)
        }
    }

    @Test
    fun pauseResumeSeekToAndSeekByActOnceEach() = runBlocking<Unit> {
        film(BELL, "Harbor Bell", "bell-4")
        pages.launch().use {
            val galaxy = phone("Invented phone")
            val session = galaxy.session(galaxy.ask(Play(1, movie = movie(BELL, "Harbor Bell"))))
            playing(session)
            assertEquals(LinkMessage.Ack(2, session), galaxy.ask(Pause(2, session)))
            now("paused") { it.state == Playback.Paused }
            assertEquals(LinkMessage.Ack(3, session), galaxy.ask(Resume(3, session)))
            now("playing again") { it.state == Playback.Playing }
            assertEquals(LinkMessage.Ack(4, session), galaxy.ask(SeekTo(4, session, 120_000)))
            waitFor("at 2:00") { position().takeIf { it in 120_000..125_000 } }
            assertEquals(LinkMessage.Ack(5, session), galaxy.ask(SeekBy(5, session, 30_000)))
            // One step of the controls' burst: it seeks once, 30 s on, as its window closes.
            waitFor("at 2:30") { position().takeIf { it >= 150_000 } }
            val at = position()
            assertTrue("30 s on, once: at $at ms", at in 150_000..158_000)
            // Another session's commands do nothing here.
            assertEquals(LinkMessage.Rejected(6, io.skjaere.usenet.link.Rejection.NotCurrent), galaxy.ask(Pause(6, "another")))
            now("still playing") { it.state == Playback.Playing }
        }
    }

    /**
     * A phone's Pause and seeks that reach the player before it is made (its setup held, as a TV's frame-rate switch holds
     * it, its handle up and nothing playing yet) are acked and taken as it is made: it starts paused, where they said.
     */
    @Test
    fun aPauseAndSeeksWhileThePlayerIsBeingMadeTakeEffectAsItIs() = runBlocking<Unit> {
        film(BELL, "Harbor Bell", "bell-6")
        val setup = CompletableDeferred<Unit>()
        PlayerActivity.readPlayback = { setup.await(); realRead(it).copy(matchFrameRate = false) }
        pages.launch().use {
            val galaxy = phone("Invented phone")
            val session = galaxy.session(galaxy.ask(Play(1, movie = movie(BELL, "Harbor Bell"))))
            waitFor("the player started, not yet made") { container.playerInFront.handle.value?.takeIf { it.remote?.session == session } }
            assertEquals(LinkMessage.Ack(2, session), galaxy.ask(Pause(2, session)))
            assertEquals(LinkMessage.Ack(3, session), galaxy.ask(SeekTo(3, session, 120_000)))
            assertEquals(LinkMessage.Ack(4, session), galaxy.ask(SeekBy(4, session, 30_000)))
            setup.complete(Unit)
            now("paused at 2:30, as asked") { it.remote?.session == session && it.state == Playback.Paused && it.positionMs >= 145_000 }
            delay(2_000)
            assertEquals("still paused", Playback.Paused, container.playerInFront.now.value?.state)
            val at = position()
            assertTrue("at $at ms", at in 145_000..155_000)
        }
    }

    /** The Finals owed to [phone] now (link.json's outbox). */
    private fun owed(phone: LinkedPhone): List<LinkMessage.Final> = runBlocking { container.data.link.owed(phone.key.id) }

    /** The Finals [phone] heard, in order. */
    private fun finals(phone: LinkedPhone): List<LinkMessage.Final> = phone.heard.filterIsInstance<LinkMessage.Final>()

    @Test
    fun stopLeavesThePlayer() = runBlocking<Unit> {
        film(BELL, "Harbor Bell", "bell-5")
        pages.launch().use {
            val galaxy = phone("Invented phone")
            val session = galaxy.session(galaxy.ask(Play(1, movie = movie(BELL, "Harbor Bell"))))
            playing(session)
            now("two seconds in") { it.remote?.session == session && it.positionMs >= 2_000 }
            val player = live<PlayerActivity>(Stage.RESUMED).single()
            assertEquals(LinkMessage.Ack(2, session), galaxy.ask(Stop(2, session)))
            waitFor("the player gone") { player.isDestroyed.takeIf { it } }
            assertNull("nothing in front says it plays", container.playerInFront.now.value)
            assertNull(container.playerInFront.handle.value)

            // Where it stopped, for the phone's progress (W5, L33): a Final in the outbox, sent on the phone's link at once.
            val final = waitFor("the Final on the phone's link") { finals(galaxy).firstOrNull() }
            assertEquals(session, final.session)
            assertEquals(WatchKey.movie(BELL), final.watchKey)
            assertEquals(FinalKind.Movie to BELL, final.kind to final.movieTmdbId)
            assertTrue("stopped at ${final.positionMs} ms", final.positionMs >= 2_000 && !final.watched)
            assertEquals(listOf(final.id), owed(galaxy).map { it.id })
            // Acked: let go.
            galaxy.link.send(LinkMessage.FinalAck(listOf(final.id)))
            waitFor("the outbox empty") { owed(galaxy).takeIf { it.isEmpty() } }
            delay(1_000)
            assertEquals("one Final for the one play", 1, finals(galaxy).size)
        }
    }

    private fun episode(n: Int) = EpisodePlay(
        showTmdbId = SHOW, showName = "Harbor Lights", showYear = 2026, aliases = emptyList(), ids = TvIds(tmdb = SHOW), season = 1, episode = n,
        episodeName = "Episode $n", airDate = null, runtimeMinutes = 1,
    )

    /** A season pack of two episodes, the device tests' dip.mkv (20 s) each, posted on the test's server and prepared. */
    private suspend fun pack(): PreparedNzb {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("dip.mkv").use { it.readBytes() }
        val files = (1..2).map { n -> local.post("Harbor.Lights.S01E0$n.mkv", bytes, PART, "harbor-lights-s01e0$n") }
        val nzb = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<nzb xmlns=\"http://www.newzbin.com/DTD/2003/nzb\">\n")
            files.forEachIndexed { i, posted ->
                append("<file poster=\"test@local\" date=\"1791985721\" subject=\"[${i + 1}/2] - &quot;${posted.name}&quot; yEnc (1/${posted.ids.size})\">")
                append("<groups><group>alt.binaries.test</group></groups><segments>")
                posted.ids.forEachIndexed { j, id -> append("<segment bytes=\"${posted.sizes[j]}\" number=\"${j + 1}\">$id</segment>") }
                append("</segments></file>\n")
            }
            append("</nzb>\n")
        }.encodeToByteArray()
        val outcome = engineSetup("the season pack prepared") { container.preparer.prepare(nzb, PACK) }
        val prepared = (outcome as? PrepareOutcome.Ready)?.nzb ?: throw AssertionError("the pack didn't prepare: $outcome")
        container.store.put(prepared, nzb)
        return prepared
    }

    /**
     * Without the mock, the hand-over the player makes by itself (auto-advance, through switchTo) keeps the phone's
     * session: the next episode's launch carries it, from its own place. The next episode is in the release playing (a
     * season pack), so it plays from there with no search; the card counts down a second. The episode before's Final
     * reaches the phone (L33): one, in the session, for that episode, where the card handed over.
     */
    @Test
    fun autoAdvanceKeepsThePhonesSession() = runBlocking<Unit> {
        val prepared = pack()
        val e1 = prepared.candidates.single { "S01E01" in it.name }
        // The phone paired before the play, and linked once the player plays.
        val key = softwareKey()
        phones.add("Invented phone", key.id)
        val session = RemotePlay("s-auto", key.id.hex, "Invented phone")
        val launch = PlayerLaunch(
            uri = UsenetUris.build(prepared.id, e1.index, e1.name, e1.size), title = "Harbor Lights", subtitle = "S01E01 · Episode 1",
            watchKey = WatchKey.episode(SHOW, 1, 1), watch = episode(1).watch,
            release = SavedRelease(INDEXER, "harbor-lights-s01", PACK, null, e1.index, prepared.id, e1.name, e1.size), remote = session,
        )
        PlayerActivity.nextEpisode = { _, playing -> episode(2).takeIf { playing == AutoAdvance.Episode(SHOW, 1, 1) } }
        PlayerActivity.readPlayback = { realRead(it).copy(autoPlayNext = true, matchFrameRate = false) }
        PlayerActivity.timings = PlayerActivity.Timings(countdownMs = 1_000)
        // Nothing left over from another test for this one to take.
        container.playback.withdraw()
        pages.launch().use { main ->
            val player = play(main, launch)
            val galaxy = LinkedPhone("Invented phone", pages, phones, key, pair = false).also { linked += it }
            onMainThread { player.player!!.seekTo(12_000) }
            val next = withTimeout(60_000) { container.playback.launches.first() }
            assertEquals(WatchKey.episode(SHOW, 1, 2), next.watchKey)
            assertEquals("the phone's session, handed on", session, next.remote)
            assertNull("from the next episode's own place", next.startPositionMs)
            assertEquals(0, grabs.get())
            // The first episode's Final, in the session, owed until the phone acks it: where the card handed over, 10 s
            // before the end (past 90 % of any episode over 100 s; this one is 20 s), watched by the 90 % rule.
            val final = waitFor("the first episode's Final on the phone's link") { finals(galaxy).firstOrNull() }
            assertEquals("s-auto" to WatchKey.episode(SHOW, 1, 1), final.session to final.watchKey)
            assertEquals(FinalKind.Episode, final.kind)
            assertEquals(Triple(SHOW, 1, 1), Triple(final.showTmdbId, final.season, final.episode))
            assertTrue("handed over after 12 s: ${final.positionMs} of ${final.durationMs} ms", final.positionMs >= 12_000 && final.durationMs in 19_000..21_000)
            assertEquals(final.positionMs >= final.durationMs * 0.9, final.watched)
            galaxy.link.send(LinkMessage.FinalAck(listOf(final.id)))
            waitFor("the outbox empty") { owed(galaxy).takeIf { it.isEmpty() } }
            assertEquals("one Final for the episode", 1, finals(galaxy).count { it.watchKey == WatchKey.episode(SHOW, 1, 1) })
        }
    }

    /**
     * The end of a phone's episode with the next-episode card waiting for Play now (auto-play off): the player stays, and
     * so does the phone's session. The phone hears it paused at the end, never stopped, until the player leaves; then
     * stopped (L33, L34).
     */
    @Test
    fun theEndOfAPhonesEpisodeWithTheCardWaitingKeepsItsSessionUntilThePlayerLeaves() = runBlocking<Unit> {
        val prepared = pack()
        val e1 = prepared.candidates.single { "S01E01" in it.name }
        val key = softwareKey()
        phones.add("Invented phone", key.id)
        val session = RemotePlay("s-end", key.id.hex, "Invented phone")
        val launch = PlayerLaunch(
            uri = UsenetUris.build(prepared.id, e1.index, e1.name, e1.size), title = "Harbor Lights", subtitle = "S01E01 · Episode 1",
            watchKey = WatchKey.episode(SHOW, 1, 1), watch = episode(1).watch,
            release = SavedRelease(INDEXER, "harbor-lights-s01", PACK, null, e1.index, prepared.id, e1.name, e1.size), remote = session,
        )
        PlayerActivity.nextEpisode = { _, playing -> episode(2).takeIf { playing == AutoAdvance.Episode(SHOW, 1, 1) } }
        PlayerActivity.readPlayback = { realRead(it).copy(autoPlayNext = false, matchFrameRate = false) }
        container.playback.withdraw()
        pages.launch().use { main ->
            val player = play(main, launch)
            val galaxy = LinkedPhone("Invented phone", pages, phones, key, pair = false).also { linked += it }
            onMainThread { player.player!!.seekTo(17_000) }
            waitFor("at the end, the card waiting", timeoutMs = 20_000) {
                onMainThread { player.player?.playbackState == androidx.media3.common.Player.STATE_ENDED }.takeIf { it }
            }
            // Past the player's next word and the pump's stop grace: the session goes on.
            delay(NOW_AND_GRACE_MS)
            val atEnd = now("paused at the end") { it.remote == session }
            assertEquals(Playback.Paused, atEnd.state)
            val heard = galaxy.statuses().filter { it.session == "s-end" }.map { it.state }
            assertTrue("the phone heard its play paused at the end, never stopped: $heard", Playback.Paused in heard && Playback.Stopped !in heard)
            // The player gone, Home back: the session is over, and the phone hears so.
            onMainThread { player.finish() }
            waitFor("the player gone") { player.isDestroyed.takeIf { it } }
            val stopped = waitFor("the phone told its play stopped") { galaxy.statuses().lastOrNull()?.takeIf { it.session == "s-end" && it.state == Playback.Stopped } }
            assertEquals(WatchKey.episode(SHOW, 1, 1), stopped.watchKey)
        }
    }

    private companion object {
        /** The player says what it plays each second, and the TV tells a phone its session stopped 1.5 s after (StatusPump). */
        const val NOW_AND_GRACE_MS = 3_500L

        /** Invented films' TMDB ids; nothing real has them. */
        const val BELL = 900041
        const val ORCHARD = 900042
        const val SHOW = 900043
        const val INDEXER = "tv-incoming-play-test"
        const val RELEASE = "Harbor.Bell.2026.1080p.WEB-DL.H.264-LINK"
        const val PACK = "Harbor.Lights.S01.1080p.WEB-DL.H.264-LINK"
        const val PART = 32_000
        const val DURATION_MS = 360_000L
    }
}
