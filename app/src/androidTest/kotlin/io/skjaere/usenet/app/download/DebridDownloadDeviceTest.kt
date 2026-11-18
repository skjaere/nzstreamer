package io.skjaere.usenet.app.download

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.DebridFakes
import io.skjaere.usenet.app.debrid.DebridSearchFixture
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.exists
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.show
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitFor
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitTill
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.read
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.StreamTraffic
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.releases.ReleaseFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Debrid downloads in the app, over the fakes (L422, L459, L460, L475): the debrid fake's TorBox account the only source
 * (no Usenet provider usable, every Usenet indexer off) and the Torznab fake's Prowlarr feed the only indexer, as
 * [FakeAccountOnly] sets them; TMDB's seam strict, answering for Harbor Lights alone (the fakes' catalog's ids).
 * - The Show page's ⋮ on S01E02, Download: the sheet's best match a torrent the account has cached, "✓ Cached"; Download
 *   queues it, and the host Android schedules resolves it on the account and writes it with the debrid writer to Done, its
 *   file the fake's sample byte for byte (its SHA-256 /control/media's). Then, offline by the container's override, the
 *   episode's row plays its file through the hook, asking nothing of the fakes; and the show's downloads say "From the
 *   debrid service" under its row (L463).
 * - A torrent row a process left once its file was begun, its listing's copy in its folder: a cut mid-transfer opens again
 *   at the byte reached, with one more link asked of the account.
 * - Its account's key refused: it waits "Check your debrid service", and stays so as the app marks the key; a key saved
 *   again queues it, and it goes on to Done (L474).
 * - A stream opened meanwhile (the test's own meter): its row on the Downloads page says "Waits while something streams",
 *   nothing is asked while it lasts, and a fresh link follows 10 s after it ends (L471); M-wait reported.
 * - A downloaded file played meanwhile opens no meter: the download goes on beside it, never waiting (L471).
 *
 * Kept apart (L464): the fakes' invented account alone, saved so the debug build's seeding never saves another; before and
 * after each case every download row deleted and the downloads' jobs cancelled, so no job Android runs later resolves a
 * torrent row with no fake to point at; the services pointed at the debrid fake for the case and back after; the account
 * signed out at the end. The episode and its releases are invented.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DebridDownloadDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its window's focus. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val fakes = FakeAccountOnly(torznab = true, providers = false, tmdb = false)
    private var tmdb: FakeTmdb? = null
    private val watching = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeFalse("downloads never run on a TV (L369)", container.television)
        // The debug build's start saves its dev settings: before the case takes any.
        withTimeout(SEEDING_MILLIS) { container.devSettingsSaved.await() }
        apart()
        fakes.open()
        assertTrue("no Usenet provider usable", container.settings.usableProviders().isEmpty())
        // Whatever network the emulator calls its Wi-Fi, and not on a charger only: put back with the settings.
        container.settings.setDownloadsUnmeteredOnly(false)
        container.settings.setDownloadsWhileCharging(false)
        tmdb = FakeTmdb(shows = mapOf(SHOW to harborLights), seasons = { id, n -> season.takeIf { id == SHOW && n == 1 } }, strict = true)
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        everyStep(
            { watching.forEach { it.cancel() } },
            { container.offlineOverride.value = null },
            { container.playback.cancel() },
            // Nor does a class after this one find the episode's play remembered.
            { DebridSearchFixture.forgetPlays(container.library) },
            { apart() },
            { fakes.close() },
            { tmdb?.close() },
        )
    }

    /** No download row left of any test, and none of the downloads' jobs pending (L464). */
    private suspend fun apart() {
        container.data.downloads.all().first().forEach { container.downloadStorage.delete(it.id) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) DownloadJobService.scheduler(context).cancelAll()
    }

    /** The downloads of the case's episode, from the table now. */
    private suspend fun mine(): List<Download> = container.data.downloads.all().first().filter { it.watchKey == WatchKey.episode(SHOW, 1, 2) }

    private fun counts() = JSONObject(DebridFakes.control("counts"))

    /** The calls each service's API was asked, by name, as the debrid fake counts them. */
    private fun calls(): Map<String, Int> = counts().getJSONObject("calls").let { json -> json.keys().asSequence().associateWith { json.getInt(it) } }

    /** The bytes each CDN body began at, and how many began there. */
    private fun cdnFrom(): Map<Long, Int> = counts().getJSONObject("cdnFrom").let { json -> json.keys().asSequence().associate { it.toLong() to json.getInt(it) } }

    /** The fake's sample, every cached torrent's video: its size and SHA-256. */
    private fun media(): Pair<Long, String> = JSONObject(DebridFakes.control("media")).let { it.getLong("size") to it.getString("sha256") }

    /** [row]'s file, whole: the fake's sample, byte for byte. */
    private fun assertWhole(row: Download) {
        val file = container.downloadStorage.file(row) ?: throw AssertionError("no file for download ${row.id}")
        val (size, sha256) = media()
        assertEquals(size, file.length())
        assertEquals("the file is the fake's sample", sha256, DownloadFixtures.sha256(file))
    }

    /** The coordinator's launches for [watchKey] from now on. */
    private fun watchLaunches(watchKey: String): Map<String, PlayerLaunch> {
        val seen = ConcurrentHashMap<String, PlayerLaunch>()
        val scope = CoroutineScope(Dispatchers.Default).also { watching += it }
        scope.launch { container.playback.launches.filter { it.watchKey == watchKey }.collect { seen[it.uri] = it } }
        return seen
    }

    /** Download [row] waited for to its end: Done, else the case fails with what it ended as. */
    private suspend fun done(row: Download): Download = waitFor("download ${row.id} done", DOWNLOAD_MILLIS) {
        val now = runBlocking { container.data.downloads.get(row.id) }
        if (now?.state == DownloadState.Failed) throw AssertionError("the download failed: ${now.reason}, ${now.tried.size} tried")
        now?.takeIf { it.state == DownloadState.Done }
    }

    /**
     * L459, L460: the Show page's ⋮ Download over the fakes, to a file played offline. The sheet's best match is a cached
     * torrent, tagged as the lists tag it; Download queues it with its source, and the host resolves it on the account and
     * the debrid writer writes it whole. Offline, set by the container's override (no Wi-Fi switched), the episode's row
     * plays the file through the hook with nothing asked of the fakes.
     */
    @Test
    fun fromTheShowPagesMenuACachedTorrentDownloadsWholeAndPlaysOffline() = runBlocking<Unit> {
        val room = container.downloadStorage.space()
        assumeTrue("no room on this emulator for the torrent's listed 1.5 GB: $room", room?.fits(LISTED) == true)
        // The Show page, and the show's downloads once the file is written.
        val downloads = mutableStateOf(false)
        compose.show(context) { if (downloads.value) DownloadsShowScreen(SHOW, onBack = {}, onOpenShow = {}) else ShowScreen(SHOW, onBack = {}) }
        val more = hasContentDescription("More for episode 2")
        compose.waitFor(more, 15_000)
        compose.onNode(more).performClick()
        val download = hasText("Download") and hasClickAction() and hasAnyAncestor(isPopup())
        compose.waitFor(download)
        compose.onNode(download).performClick()

        // The sheet's pick, a torrent the account has cached, once the account has answered for its hash.
        compose.waitTill("the sheet's pick", SEARCH_MILLIS) { compose.exists(hasText(DownloadSheetModel.BEST)) }
        compose.waitFor(hasContentDescription("From TorBox"), SEARCH_MILLIS)
        assertTrue("the best match cached on the account", compose.exists(hasText("✓ Cached")))
        assertTrue("the best match one of the episode's cached torrents", CACHED.any { compose.exists(hasText(it)) })
        compose.onNode(hasText(DownloadSheetModel.TITLE) and hasClickAction()).performClick()
        compose.waitTill("the sheet to close") { !compose.exists(hasText(DownloadSheetModel.BEST)) }

        // Queued with its source, run by the host Android schedules, resolved on the account and written whole.
        val queued = waitFor("the download queued") { runBlocking { mine() }.singleOrNull() }
        assertEquals(ReleaseFacts.TORRENT, queued.candidates.first().source)
        val done = done(queued)
        assertEquals(ReleaseFacts.TORRENT, done.release?.source)
        assertTrue("its release one of the cached torrents: ${done.release?.title}", done.release?.title in CACHED)
        assertWhole(done)
        assertEquals("no Usenet grab", emptyList<String>(), done.tried.filterNot { it.startsWith("btih:") })

        // L463: the show's downloads say where it came from, under its row; then back to the Show page.
        compose.runOnIdle { downloads.value = true }
        compose.waitFor(hasText(DownloadsPage.FROM_DEBRID, substring = true) and hasText(EPISODES[1], substring = true))
        compose.runOnIdle { downloads.value = false }

        // Offline, by the override: the row still plays, from its file, through the hook.
        container.offlineOverride.value = true
        waitFor("the app offline") { if (runBlocking { container.offline.first() }) Unit else null }
        val before = calls()
        val launches = watchLaunches(done.watchKey)
        val row = hasText(EPISODES[1]) and hasClickAction()
        compose.waitFor(row)
        compose.onNode(row).performClick()
        val launch = waitFor("the file's play", LAUNCH_MILLIS) { launches.values.firstOrNull() }
        assertEquals(PlayUris.download(done.id), launch.uri)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            waitFor("the player to know the duration", PLAYER_MILLIS) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
            waitFor("the player playing the file", PLAYER_MILLIS) { scenario.read { it.player?.takeIf { p -> p.playbackState == Player.STATE_READY && p.currentPosition > 0 } } }
            assertEquals(launch.uri, scenario.read { it.player?.currentMediaItem?.localConfiguration?.uri?.toString() })
        }
        assertEquals("nothing asked of the fakes while it played", before, calls())
    }

    /**
     * L419, L460: a torrent row left once its file was begun, its listing's copy in its folder, opens again from the copy
     * with no call of Resolve's; the debrid writer's first body cut part way by the CDN opens again at the byte reached,
     * with one more link asked of the account, and the file comes out whole.
     */
    @Test
    fun aCutMidTransferOpensAgainAtItsOffsetWithOneMoreLink() = runBlocking<Unit> {
        val row = seededTorrent()
        // Every body cut after CUT bytes: the sample's first, then its rest whole. The links made before expired.
        DebridFakes.control("cdn?cut=$CUT")
        DebridFakes.control("cdn-expire")
        val before = calls()

        val walk = walk()
        val done = done(row)
        walk.join()
        assertWhole(done)
        val after = calls()
        assertEquals("a link for the first body and one more for the rest", before.getValue(REQUESTDL) + 2, after.getValue(REQUESTDL))
        assertEquals("nothing else asked of the account: no prepare, no list", before - REQUESTDL, after - REQUESTDL)
        assertEquals(mapOf(0L to 1, CUT to 1), cdnFrom())
        assertEquals("one body open at once", 1, counts().getInt("cdnOpenMax"))
    }


    /**
     * L474: the account's key refused as the torrent is written: the row waits "Check your debrid service", the walk
     * ending, and stays so, at its offset, as the app marks the key refused (the app's own mark is no save of the user's).
     * A key saved again (the same, taken by the service now) queues it again, and it goes on to Done, whole.
     */
    @Test
    fun aRefusedKeyWaitsAsTheAppMarksItAndANewKeySavedGoesOn() = runBlocking<Unit> {
        val row = seededTorrent()
        DebridFakes.control("refuse-keys?on=true")
        walk().join()
        val waiting = rowNow(row.id)
        assertEquals(DownloadState.Waiting, waiting.state)
        assertEquals(DownloadHostRules.CHECK_SERVICE, waiting.reason)
        waitFor("the key marked refused") { runBlocking { container.settings.services.first() }.firstOrNull { it.id == fakes.accountId && it.keyRefused } }
        // The app's own mark sets nothing going: the row waits on, at its offset.
        delay(SETTLE_MILLIS)
        val still = rowNow(row.id)
        assertEquals(DownloadState.Waiting, still.state)
        assertEquals(DownloadHostRules.CHECK_SERVICE, still.reason)
        assertEquals(waiting.safeOffset, still.safeOffset)

        DebridFakes.control("refuse-keys?on=false")
        container.settings.saveService(ServiceKind.TorBox.name, DebridFakes.TORBOX_KEY) { ServiceSignIn(DebridFakes.TORBOX_ACCOUNT) }
        waitFor("queued again by the key saved") { rowNow(row.id).takeIf { it.state != DownloadState.Waiting } }
        walk()
        assertWhole(done(row))
    }

    /**
     * L471 (W123): a stream opened meanwhile (the test's own meter on the container's StreamTraffic, as a play's or a cast's
     * stream opens one) holds the download where it is: its row on the Downloads page says "Waits while something
     * streams" with how far it is and Pause, no link is asked and no body begun while the stream lasts, and once it ends
     * the download goes on with a fresh link, 10 s later, to Done, whole. It reports M-wait as the page sees it.
     */
    @Test
    fun aStreamOpenedMeanwhileHoldsTheDownloadOnItsPageUntilItEnds() = runBlocking<Unit> {
        val row = seededTorrent()
        val seeds = DownloadsPageSeeds(context, container)
        seeds.adopt(row)
        compose.show(context) { DownloadsScreen(seeds.deps(), onBack = {}, onSettings = {}, onShow = {}, onOpenMovie = {}) }
        DebridFakes.control("cdn?slow=$SLOW_KBPS")
        DebridFakes.control("cdn-expire")
        walk()
        waitFor("1 MB written", DOWNLOAD_MILLIS) { written(row.id).takeIf { it >= 1_000_000 } }
        var meter: StreamTraffic.Meter? = null
        try {
            val opened = SystemClock.elapsedRealtime()
            meter = container.streamTraffic.open()
            waitFor("the row waiting for the stream", 10_000) { rowNow(row.id).takeIf { it.reason == DownloadHostRules.WAITS_FOR_STREAM } }
            val said = SystemClock.elapsedRealtime()
            compose.waitFor(hasText("${DownloadHostRules.WAITS_FOR_STREAM} · ", substring = true))
            assertTrue("the queue's Pause on its row", compose.exists(hasContentDescription(DownloadsPage.PAUSE)))
            waitFor("the body let go at the fake", 5_000) { counts().getInt("cdnOpen").takeIf { it == 0 } }
            val links = calls().getValue(REQUESTDL)
            val bodies = cdnFrom()
            val held = written(row.id)
            delay(HELD_MILLIS)
            assertEquals("no link asked while it streams", links, calls().getValue(REQUESTDL))
            assertEquals("no body begun while it streams", bodies, cdnFrom())
            assertEquals("nothing written while it streams", held, written(row.id))
            assertEquals(DownloadState.Running, rowNow(row.id).state)

            meter.close()
            val ended = SystemClock.elapsedRealtime()
            meter = null
            waitFor("a fresh link", 30_000) { calls().getValue(REQUESTDL).takeIf { it > links } }
            val next = SystemClock.elapsedRealtime()
            assertTrue("went on ${next - ended} ms after the stream ended", next - ended >= ServiceDownloadWriter.QUIET.inWholeMilliseconds - POLL_SLACK_MILLIS)
            val done = done(row)
            assertWhole(done)
            assertEquals("one link per stream start", links + 1, calls().getValue(REQUESTDL))
            assertEquals("one body open at once", 1, counts().getInt("cdnOpenMax"))
            compose.waitTill("the wait's words gone") { !compose.exists(hasText(DownloadHostRules.WAITS_FOR_STREAM, substring = true)) }
            report(M_WAIT, "page case: meter open to the row's words ${said - opened} ms; the meter's close to the next link ${next - ended} ms (polled every 50 ms)")
        } finally {
            meter?.close()
        }
    }

    /**
     * L471: a downloaded file played here meanwhile opens no meter, so the debrid download goes on beside it, never
     * waiting: the play's file is another episode's download, played through the hook's launch in the player.
     */
    @Test
    fun aDownloadedFilePlayedMeanwhileHoldsNothing() = runBlocking<Unit> {
        val played = finishedEpisode()
        val row = seededTorrent()
        DebridFakes.control("cdn?slow=$PLAYED_KBPS")
        DebridFakes.control("cdn-expire")
        val reasons = ConcurrentHashMap.newKeySet<String>()
        var streamed = false
        val scope = CoroutineScope(Dispatchers.Default).also { watching += it }
        scope.launch { container.data.downloads.all().collect { rows -> rows.firstOrNull { it.id == row.id }?.reason?.let(reasons::add) } }
        scope.launch { container.streamTraffic.streaming.collect { if (it) streamed = true } }
        walk()
        waitFor("1 MB written", DOWNLOAD_MILLIS) { written(row.id).takeIf { it >= 1_000_000 } }
        val launch = checkNotNull(DownloadLaunch.of(played))
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            waitFor("the player playing the file", PLAYER_MILLIS) { scenario.read { it.player?.takeIf { p -> p.playbackState == Player.STATE_READY && p.currentPosition > 0 } } }
            val before = written(row.id)
            delay(HELD_MILLIS)
            assertTrue("the download went on beside the play", written(row.id) > before)
        }
        assertWhole(done(row))
        assertFalse("no meter opened by the file's play", streamed)
        assertFalse("never waited: $reasons", DownloadHostRules.WAITS_FOR_STREAM in reasons)
        assertEquals("one link: never stopped", 1, calls().getValue(REQUESTDL))
    }

    /**
     * A torrent row as Resolve leaves one, the case's own: S01E02's torrent prepared on the fake account (the app's own
     * client), its listing's copy in its folder, its release and file the row's, nothing written yet.
     */
    private suspend fun seededTorrent(): Download {
        val account = fakes.accountId
        val service = container.services.gated(account) ?: throw AssertionError("the fake account isn't on")
        val prepared = service.prepare(InfoHash.of(GOOD_HASH)) { listing -> listing.files.firstOrNull { it.path.endsWith(".mkv") } }
        val file = prepared.picked ?: throw AssertionError("no video in the torrent's listing")
        val entry = container.debridListings.put(account, GOOD, prepared)
        val now = System.currentTimeMillis()
        val key = "btih:$GOOD_HASH"
        val row = Download(
            id = Download.newId(), watchKey = WatchKey.episode(SHOW, 1, 2), kind = DownloadKind.Episode, title = "Harbor Lights", showTmdbId = SHOW,
            season = 1, episode = 2, seasonEpisodes = EPISODES.size, episodeName = EPISODES[1], position = 0,
            candidates = listOf(DownloadCandidate("fake-prowlarr", key, GOOD, size = file.size, source = ReleaseFacts.TORRENT)), tried = listOf(key),
            release = RememberedRelease("fake-prowlarr", key, GOOD, null, file.index, key, file.name, file.size, source = ReleaseFacts.TORRENT),
            fileName = file.name, size = file.size, indexerSize = file.size, createdAt = now, updatedAt = now,
        )
        container.downloadStorage.add(row)
        container.downloadStorage.keepListing(row.id, entry)
        return row
    }

    /** S01E01 downloaded, finished: chapters.mkv written as its file, its row Done, among the runner's finished ones. */
    private suspend fun finishedEpisode(): Download {
        val bytes = DownloadFixtures.media(PLAYED)
        val name = "Harbor.Lights.S01E01.720p.WEB.h264-KESTREL.mkv"
        val storage = container.downloadStorage
        val id = Download.newId()
        storage.open(id, name, bytes.size.toLong()).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        storage.finish(id, name)
        val size = bytes.size.toLong()
        val row = Download(
            id = id, watchKey = WatchKey.episode(SHOW, 1, 1), kind = DownloadKind.Episode, title = "Harbor Lights", showTmdbId = SHOW, season = 1, episode = 1,
            seasonEpisodes = EPISODES.size, episodeName = EPISODES[0], state = DownloadState.Done, position = -1,
            release = RememberedRelease("fake-prowlarr", "hl-s01e01-kestrel", name.removeSuffix(".mkv"), null, 0, "nzb-hl-s01e01", name, size),
            fileName = name, size = size, indexerSize = size, safeOffset = size, relativePath = storage.relativePath(id, name), createdAt = 1, completedAt = 2,
            updatedAt = 2,
        )
        storage.add(row)
        waitFor("the finished episode among the runner's") { container.downloads.done.value[row.watchKey]?.takeIf { it.id == id } }
        return row
    }

    /** The walk, as a host runs it, in the case's own scope. */
    private fun walk(): Job = CoroutineScope(Dispatchers.Default).also { watching += it }.launch { container.downloads.run(DownloadHost.Service) }

    /** Download [id] from the table now. */
    private fun rowNow(id: String): Download = runBlocking { container.data.downloads.get(id) } ?: throw AssertionError("download $id gone")

    /** What the runner says of download [id]'s file written now: the live write's bytes while it is written. */
    private fun written(id: String): Long = container.downloads.state.value.firstOrNull { it.row.id == id }?.written ?: 0

    /** Says [line] under [key] through the instrumentation's status, as the pairs do: the run's output keeps it. */
    private fun report(key: String, line: String) {
        instrumentation.sendStatus(STATUS, Bundle().apply { putString(key, line) })
    }

    private val today = LocalDate.now()

    /** Harbor Lights as the fakes' catalog numbers it: nothing real has these ids. */
    private val harborLights = ShowDetails(
        SHOW, "Harbor Lights", null, 2021, null, null, null, "Returning Series", true, 45, listOf(SeasonSummary(1, null, EPISODES.size, today.minusDays(60), null)),
        lastEpisode = EpisodeSummary(1, EPISODES.size, EPISODES.last(), today.minusDays(46)), nextEpisode = null, ids = ExternalIds(tvdb = 900001, imdb = "tt9000003"),
    )

    private val season = Season(SHOW, 1, null, null, EPISODES.mapIndexed { i, name -> Episode(1, i + 1, name, null, today.minusDays(60L - 7 * i), 45, null) })

    private companion object {
        /** The fakes' Harbor Lights, as TMDB would number it. */
        const val SHOW = 900002

        /** Invented episode names. */
        val EPISODES = listOf("Pilot Light", "Salt Road", "Night Ferry")

        /** S01E02 as the Torznab fake lists it and the debrid fake has it cached, and its hash. */
        const val GOOD = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD"
        const val GOOD_HASH = "1bb5795cb9f72aa684193ddf405249b071b2ba5f"

        /** The episode's torrents the fake account has cached: the single episode, and the season in a folder. */
        val CACHED = listOf(GOOD, "Harbor.Lights.S01.1080p.WEB-DL.AAC2.0.H.264-TIDE")

        /** What the Torznab fake lists the single episode's torrent as, and the room it needs on the device. */
        const val LISTED = 1_600_000_000L

        /** TorBox's call for a file's link, as the debrid fake counts it. */
        const val REQUESTDL = "torbox requestdl"

        /** Where the CDN cuts the first body: a little over half of the fake's sample. */
        const val CUT = 5_000_000L

        /** The fake CDN's pace for a download that must still run while a case acts: the sample in about 11 s. */
        const val SLOW_KBPS = 800

        /** The fake CDN's pace while a downloaded file plays beside the download: the sample in about half a minute. */
        const val PLAYED_KBPS = 300

        /** How long a case watches nothing move while it is held, or the download go on beside a play. */
        const val HELD_MILLIS = 3_000L

        /** How long a case lets the app's own watches settle before it reads what they left. */
        const val SETTLE_MILLIS = 2_000L

        /** The polls' slack in a measured wait. */
        const val POLL_SLACK_MILLIS = 200L

        /** The asset a finished download's file is written from: a short film with chapters. */
        const val PLAYED = "chapters.mkv"

        /** The instrumentation status's key for M-wait, and its result code (as the writer's device class reports it). */
        const val M_WAIT = "M-wait"
        const val STATUS = 2

        const val SEEDING_MILLIS = 60_000L
        const val SEARCH_MILLIS = 60_000L
        const val DOWNLOAD_MILLIS = 180_000L
        const val LAUNCH_MILLIS = 15_000L
        const val PLAYER_MILLIS = 60_000L
    }
}
