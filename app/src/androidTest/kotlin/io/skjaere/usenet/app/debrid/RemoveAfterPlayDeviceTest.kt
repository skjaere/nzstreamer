package io.skjaere.usenet.app.debrid

import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remove after playing over the fakes (#318, L232-L234, L245; D2PhoneServiceSwitch), its grace cut to 2 s through the
 * records' seam ([DebridAdds.grace]), TorBox signed in on the debrid fake ([DebridSearchFixture], the Usenet indexer off):
 * a cached torrent a play added goes from the account a grace after the player's Back, one removal counted, and a Resume
 * after it adds it again and plays; one already in the account before the play (planted, as another app's) stays, and so
 * does what a play adds with Remove after playing off; Home for three graces and back removes nothing, adds nothing again
 * and plays on; the next episode of a pack within the grace adds nothing again; what a process that died left goes at the
 * next start's sweep; and an entry another device deletes mid-play is added again by the next refresh, the play going on.
 * The account is read as the app's key sees it, through the fake's own TorBox API; the titles and hashes are FakeTorrents'.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class RemoveAfterPlayDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val fixture = DebridSearchFixture()

    @Before
    fun setUp() {
        fixture.open()
        fixture.usenetIndexerOn(false)
        container.debridAdds.grace = GRACE_MS
        onMainThread { container.playback.withdraw() }
    }

    @After
    fun tearDown() {
        onMainThread { container.playback.cancel() }
        container.debridAdds.grace = DebridAdds.GRACE.toMillis()
        fixture.close()
    }

    private fun episode(n: Int) = EpisodePlay(
        showTmdbId = DebridSearchFixture.HARBOR_LIGHTS, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(),
        ids = TvIds(tvdb = 900001, tmdb = DebridSearchFixture.HARBOR_LIGHTS, imdb = "tt9000003"), season = 1, episode = n,
        episodeName = DebridSearchFixture.EPISODES[n - 1], airDate = fixture.today.minusDays(60L - 7 * (n - 1)), runtimeMinutes = 45,
    )

    /** The coordinator's launch of what the play picked; fails the test if the sheet shows a failure instead. */
    private suspend fun launched(): PlayerLaunch = withTimeout(120_000) {
        merge(
            container.playback.launches,
            container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("nothing played: $it") },
        ).first()
    }

    /** Plays S01E02 from GOOD, as Play's own pick does: its launch. */
    private suspend fun playGood(): PlayerLaunch {
        onMainThread { container.playback.playEpisode(episode(2)) }
        return launched().also { assertEquals(GOOD, it.release.title) }
    }

    @Test
    fun aTorrentAPlayAddedGoesAGraceAfterBackAndAResumeAddsItAgain() = runBlocking<Unit> {
        val launch = playGood()
        assertEquals("added once", 1, adds())
        playing(launch) { scenario -> back(scenario) }
        // Gone a grace after the player closed, and only then.
        val removed = waitFor("the add removed", timeoutMs = GRACE_MS + REMOVE_WITHIN_MS) { removals().takeIf { it > 0 } }
        assertEquals("one removal", 1, removed)
        assertFalse("the account no longer holds it", GOOD_HASH in account())

        // A Resume after the removal adds it again, once, and plays.
        val saved = waitFor("its progress") { runBlocking { container.library.state(episode(2).key).first() } }
        onMainThread { container.playback.resume(saved) }
        val resumed = launched()
        assertEquals(GOOD, resumed.release.title)
        assertEquals("added again", 2, adds())
        assertTrue(GOOD_HASH in account())
        playing(resumed) { }
    }

    /**
     * L232: a torrent the account held before the play (another app's) is the account's: found in the account's whole list
     * read before the add, it is used as it is, nothing is sent to add it, and it is never removed. No listing of it is kept
     * on the device first (EGX-1): the fake's ids start again at each reset, so a handle an earlier play kept could name the
     * planted entry and skip the read this case is about.
     */
    @Test
    fun aTorrentTheAccountHeldBeforeThePlayStays() = runBlocking<Unit> {
        container.debridListings.forget(InfoHash.of(GOOD_HASH))
        DebridFakes.control("plant?hash=$GOOD_HASH")
        val planted = account().getValue(GOOD_HASH)
        val pages = calls("torbox mylist, whole")
        val launch = playGood()
        assertTrue("the account's whole list read before the add", calls("torbox mylist, whole") > pages)
        assertEquals("no add sent", 0, calls("torbox createtorrent"))
        assertEquals("nothing added", 0, adds())
        playing(launch) { scenario -> back(scenario) }
        delay(3 * GRACE_MS + 1_000)
        assertEquals("nothing removed", 0, removals())
        assertEquals("the planted entry still there", planted, account()[GOOD_HASH])
    }

    /** L246, W83: Remove after playing off, what a play adds stays in the account, as in D1. */
    @Test
    fun withRemoveAfterPlayingOffItStays() = runBlocking<Unit> {
        container.settings.setRemoveAfterPlay(fixture.torBox, false)
        val launch = playGood()
        assertEquals("added", 1, adds())
        playing(launch) { scenario -> back(scenario) }
        delay(3 * GRACE_MS + 1_000)
        assertEquals("nothing removed", 0, removals())
        assertTrue("still in the account", GOOD_HASH in account())
    }

    /**
     * L234: the player's lease lasts from its launch until it closes, never ending at onStop: Home for three graces and back,
     * where the player made again waits paused (W106) and plays on as it is played.
     */
    @Test
    fun homeForThreeGracesAndBackRemovesNothingAndThePlayGoesOn() = runBlocking<Unit> {
        val launch = playGood()
        playing(launch) { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
            delay(3 * GRACE_MS)
            assertEquals("nothing removed while stopped", 0, removals())
            assertTrue("still in the account", GOOD_HASH in account())
            scenario.moveToState(Lifecycle.State.RESUMED)
            val again = waitFor("the player made again, paused", timeoutMs = 30_000) {
                scenario.read { a -> a.player?.takeIf { it.playbackState == Player.STATE_READY && !it.playWhenReady } }
            }
            val at = scenario.read { again.currentPosition }
            scenario.onActivity { again.play() }
            waitFor("the play to go on") { scenario.read { a -> a.player?.takeIf { it.isPlaying && it.currentPosition > at + 1_000 } } }
            assertEquals("nothing added again", 1, adds())
            assertEquals("nothing removed", 0, removals())
        }
    }

    /** L234, L245: the next episode of a pack within the grace plays from the add the episode before made, adding nothing again. */
    @Test
    fun theNextEpisodeOfAPackWithinTheGraceAddsNothingAgain() = runBlocking<Unit> {
        val ep2 = episode(2)
        val events = container.finder.find(ep2.request, container.indexerConfigs(), container.rankingContext(), null).toList()
        val result = events.filterIsInstance<FinderEvent.Joined>().lastOrNull()?.result ?: events.filterIsInstance<FinderEvent.Finished>().single().result
        val pack = result.playable.map { RankedItem(it.item, it.ranked) }.single { it.item.title == PACK }
        onMainThread { container.playback.play(PlayRequest(ep2.showName, ep2.subtitle, listOf(pack), FilePicker.Episode(1, 2), ep2.watch, rememberedFirst = false)) }
        val first = launched()
        assertEquals(PACK, first.release.title)
        assertEquals(1, adds())
        playing(first) { scenario ->
            // As the player's Next does: the next episode's play asked for, then the player closed.
            onMainThread { container.playback.playEpisode(episode(3), playing = first.release) }
            back(scenario)
        }
        val next = launched()
        assertEquals(PACK, next.release.title)
        assertEquals("Harbor.Lights.S01E03.1080p.WEB-DL.AAC2.0.H.264-TIDE.mkv", next.release.fileName)
        assertEquals("nothing added again", 1, adds())
        assertEquals("nothing removed", 0, removals())
        playing(next) { scenario -> back(scenario) }
        waitFor("removed a grace after the last player closed", timeoutMs = GRACE_MS + REMOVE_WITHIN_MS) { removals().takeIf { it > 0 } }
        assertFalse(PACK_HASH in account())
    }

    /** L245: what a process that died left is removed at the next start's sweep (the records' seam stands in a new process). */
    @Test
    fun anAddAProcessThatDiedLeftGoesAtTheNextStartsSweep() = runBlocking<Unit> {
        val launch = playGood()
        playing(launch) {
            // The process goes with its leases; the next one reads the records back and sweeps a grace after it starts.
            container.debridAdds.restart()
            delay(GRACE_MS / 2)
            assertEquals("not before the start's grace", 0, removals())
            waitFor("removed by the start's sweep", timeoutMs = GRACE_MS + REMOVE_WITHIN_MS) { removals().takeIf { it > 0 } }
            assertFalse(GOOD_HASH in account())
        }
    }

    /**
     * L234, L235: another device deletes the entry mid-play (TorBox's own call, as one with the same key would), the link
     * then refused and the CDN's links expired: the player's next refresh adds the torrent again (one add counted), the
     * listing kept by its new handle, and the play goes on; that add goes a grace after the player closes.
     */
    @Test
    fun anEntryAnotherDeviceDeletesMidPlayIsAddedAgainByTheNextRefreshAndThePlayGoesOn() = runBlocking<Unit> {
        val launch = playGood()
        val listing = (PlayUris.parse(launch.uri) as PlayUris.Target.Service).listingId
        val first = account().getValue(GOOD_HASH)
        playing(launch) { scenario ->
            val duration = scenario.read { it.player!!.duration }
            assumeTrue("the debrid fake's video runs ${duration / 1000} s: start it with the README's 90 s mock video, as a seek past the buffer needs 80 s", duration >= MIN_VIDEO_MS)
            scenario.onActivity { it.player?.pause() }
            deleteAsAnotherDevice(first)
            DebridFakes.control("deleted-links?on=false")
            DebridFakes.control("cdn-expire")
            // Past the 50 s the player holds: it opens the file again there, with a fresh link.
            val to = duration - 10_000
            scenario.onActivity { a ->
                a.player?.seekTo(to)
                a.player?.play()
            }
            waitFor("the play to go on from the seek", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying && it.currentPosition >= to } } }
            assertEquals("added again, once", 2, adds())
            val again = account().getValue(GOOD_HASH)
            assertNotEquals("a new entry", first, again)
            assertEquals("the listing kept by the new handle", again.toString(), container.debridListings.get(listing)?.prepared?.handle)
            back(scenario)
        }
        waitFor("the new entry removed", timeoutMs = GRACE_MS + REMOVE_WITHIN_MS) { removals().takeIf { it >= 2 } }
        assertFalse(GOOD_HASH in account())
    }

    // The player.

    /** [launch] in the player until it plays; [then] with it, the player closed after if [then] left it open. */
    private suspend fun playing(launch: PlayerLaunch, then: suspend (ActivityScenario<PlayerActivity>) -> Unit) {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            waitFor("the episode to play") { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            then(scenario)
        }
    }

    /** Back, as the user presses it, until the player closes: the controls, where they show, go first. */
    private suspend fun back(scenario: ActivityScenario<PlayerActivity>) {
        repeat(3) {
            if (scenario.state == Lifecycle.State.DESTROYED) return
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            val deadline = SystemClock.elapsedRealtime() + 2_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (scenario.state == Lifecycle.State.DESTROYED) return
                delay(100)
            }
        }
        throw AssertionError("Back didn't close the player")
    }

    private fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private suspend fun <T : Any> waitFor(what: String, timeoutMs: Long = 60_000, probe: suspend () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            delay(100)
        }
    }

    // The debrid fake.

    private fun counts() = JSONObject(DebridFakes.control("counts"))

    /** New entries the account got from adds. */
    private fun adds(): Int = counts().getInt("adds")

    /** How often the fake was asked [name] ("torbox createtorrent", "torbox mylist, whole": a page of the whole list). */
    private fun calls(name: String): Int = counts().getJSONObject("calls").optInt(name)

    /** Entries deletes took out of the account. */
    private fun removals(): Int = counts().getInt("removals")

    /** The fake's TorBox account as the app's key sees it, its whole list: each entry's id by its hash. */
    private fun account(): Map<String, Int> {
        val (status, body) = torbox("GET", "torrents/mylist?bypass_cache=true")
        // No torrents: TorBox's 404 under success true, as the fake answers a list with none.
        if (status == 404 && JSONObject(body).optString("error") == "ITEM_NOT_FOUND") return emptyMap()
        check(status == 200) { "the fake's mylist answered $status" }
        val data = JSONObject(body).getJSONArray("data")
        return (0 until data.length()).associate { i -> data.getJSONObject(i).let { it.getString("hash") to it.getInt("id") } }
    }

    /** A delete of entry [id], as another device with the same key sends it: TorBox's own call, for one torrent. */
    private fun deleteAsAnotherDevice(id: Int) {
        val (status, _) = torbox("POST", "torrents/controltorrent", """{"torrent_id":$id,"operation":"delete"}""")
        check(status == 200) { "the fake's controltorrent answered $status" }
    }

    /** One call of the fake's TorBox API ([path] under it), with its invented key: the status and the body. */
    private fun torbox(method: String, path: String, body: String? = null): Pair<Int, String> {
        val connection = URL("${DebridFakes.endpoint(ServiceKind.TorBox)}/$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer ${DebridFakes.TORBOX_KEY}")
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            return status to ((if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /** The grace through the records' seam: the device's 2 minutes cut to 2 s. */
        const val GRACE_MS = 2_000L

        /** How long past the grace a removal may take: the sweep's turn at the account's pace, and the fake's answer. */
        const val REMOVE_WITHIN_MS = 8_000L

        /** How long the fake's video must run for a seek to 10 s before its end to land past the player's 50 s buffer. */
        const val MIN_VIDEO_MS = 80_000L

        /** FakeTorrents' names and hashes (tools/mock-seed). */
        const val GOOD = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD"
        const val GOOD_HASH = "1bb5795cb9f72aa684193ddf405249b071b2ba5f"
        const val PACK = "Harbor.Lights.S01.1080p.WEB-DL.AAC2.0.H.264-TIDE"
        const val PACK_HASH = "b8e5ad63c94db5348740caabfbdca0083423ad21"
    }
}
