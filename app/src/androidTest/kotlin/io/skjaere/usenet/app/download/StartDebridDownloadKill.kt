package io.skjaere.usenet.app.download

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.DebridFakes
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.core.TrackProbe
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.releases.ReleaseFacts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The first half of the DebridDownloadKill pair (L464, L476): one download of the debrid fake's cached torrent of the
 * fakes' Harbor Lights S01E02, seeded as [DebridKillSeed] says, the user-initiated job its host; then it returns. The
 * pairs' runner then opens the app as a user does, waits for the transfer past a quarter, kills the app's process
 * (`kill -9`), waits for a host start after it and done, and runs [CheckDebridDownloadKill]. The app's own processes
 * reach the debrid fake through `.debrid` alone; nothing of the pair reads the mock NNTP server, which the runner wants up
 * for any pair.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class StartDebridDownloadKill {
    /** Only where its run names it, on a phone ([PairRun]). */
    @get:Rule
    val pair = PairRun()

    @Test
    fun seedsOneQueuedTorrentDownloadWithItsAddHeld() = runBlocking<Unit> {
        DebridKillSeed.seed()
    }
}

/**
 * What the DebridDownloadKill pair seeds (L464, L476), beside [PairSeed]'s frame (its downloads emptied, its debrid
 * seeding held off) but with no Usenet provider:
 * - the downloads emptied ([PairSeed.empty]), then `.debrid` written, a stale one deleted first: from here on every app
 *   process the runner's open or a job starts points the services' clients at the debrid fake, which no test runner does
 *   for them. Written before any account or row, so whatever the seeding leaves, nothing of it ever reaches a service;
 * - after the debug start's own seeding, every Usenet provider hidden as the mock's pairs hide the others, and none
 *   saved: a hidden one still counts as saved, so no app process the runner starts saves the developer's. Only on Wi-Fi
 *   or Ethernet and Only while charging off; every Usenet indexer off;
 * - the debrid fake held for the pair, by [HOLDER] for [HOLD_MS], as its Check runs minutes on in another process, and
 *   reset; its TorBox account saved by the fake's invented key, Remove after playing on (the default), so the debug
 *   build's seeding never saves another; and [PairSeed.debridHeldOff]'s torrent indexer placeholder, so it never saves a
 *   torrent indexer either;
 * - one download of [GOOD]'s torrent ([fixture]): no poster or still path, so nothing of it asks TMDB (the app's own
 *   processes save the debug build's TMDB key at a start with none, so TMDB can't be off there), queued through the
 *   runner and resolved by the app's own Resolve in this process: prepared on the account, its add proved this device's
 *   and kept for Remove after playing, held by the download's lease; its listing's copy in its folder; the row taking the
 *   release. No track probe, so every body the fake's CDN serves is the writer's. Then Queued again, waiting its turn, as
 *   [PairSeed]'s rows wait (L389);
 * - the writer held to [THROTTLE]: the fake's sample (8,791,696 bytes) in about 220 s, so a quarter is past about a
 *   minute after the app's open, the rest after the kill takes about 160 s, inside the runner's 300 s for a host start and
 *   done, and the first sweep of the process the job comes back in, a grace (2 minutes) after its start, falls inside it.
 *
 * It reads back what it seeded and fails only where the seeding didn't take, giving the fake back then; what it found
 * before and left after go out as [PairRecord]'s lines.
 */
object DebridKillSeed {
    /** The fake's Harbor Lights S01E02, cached on its TorBox account, and its v1 info hash (tools/mock-seed's FakeTorrents). */
    const val GOOD = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD"
    private const val GOOD_HASH = "1bb5795cb9f72aa684193ddf405249b071b2ba5f"

    /** The torrent's release key: the row's one candidate and try. */
    const val KEY = "btih:$GOOD_HASH"

    /** Who holds the debrid fake through the pair: its Check gives it back by the same id. */
    const val HOLDER = "pair-DebridDownloadKill"

    /** The pair's hold on the fake: the whole pair, with the runner's waits, well within the fake's half hour at most. */
    const val HOLD_MS = 15 * 60_000L

    /** The pair's pace, 40 kB a second: see the object. */
    const val THROTTLE = 40_000L

    /** The candidate's indexer: never saved, so nothing ever searches it. */
    private const val INDEXER_ID = "pair-torrents"

    /** The fakes' Harbor Lights, as their catalog numbers it: no poster or still path. */
    private val TARGET = DownloadTarget.episode(900_002, "Harbor Lights", 1, 2, seasonEpisodes = 3, episodeName = "Salt Road")

    private const val SETUP_MILLIS = 30_000L
    private const val RESOLVE_MILLIS = 60_000L

    /** The fake's sample, every cached torrent's video: its size and SHA-256, as `/control/media` says them. */
    fun media(): Pair<Long, String> = JSONObject(DebridFakes.control("media")).let { it.getLong("size") to it.getString("sha256") }

    /** The pair's download as [PairCheck] and [PairRecord] read it: the fake's sample, of [media]'s size and SHA-256. */
    fun fixture(media: Pair<Long, String>): PairFixture = PairFixture.torrent(GOOD, media.first, media.second, TARGET)

    /** The debrid fake's counts now. */
    fun counts(): JSONObject = JSONObject(DebridFakes.control("counts"))

    /** Seeds what the object says; the row as seeded. */
    suspend fun seed(): Download {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = context.container
        assumeFalse("downloads never run on a TV (L369)", container.television)
        if (!DebridGate.shown) throw AssertionError("debrid is hidden in this build: the pair downloads a torrent")
        if (!DebridFakes.reachable(DebridFakes.DEBRID_PORT)) throw AssertionError("the debrid fake isn't reachable at ${DebridFakes.HOST}:${DebridFakes.DEBRID_PORT}")
        val fixture = fixture(media())
        PairRecord.record("found", listOf(fixture), strict = false)
        // The debug build's own seeding first, so it never lands after the pair's settings.
        withTimeout(SETUP_MILLIS) { container.devSettingsSaved.await() }
        PairSeed.empty(context, container)
        val folder = File(context.filesDir, DownloadStorage.DIR).apply { mkdirs() }
        // L464: a stale one deleted (the emptying took it too), then written, before any account or row.
        File(folder, DownloadDebug.DEBRID).apply {
            delete()
            writeText("fake")
        }
        noUsenet(container)
        DebridFakes.holdTheFake(HOLDER, HOLD_MS)
        val pointBack = DebridFakes.pointServicesAtTheFake()
        try {
            DebridFakes.control("reset")
            val account = container.settings.saveService(ServiceKind.TorBox.name, DebridFakes.TORBOX_KEY) { ServiceSignIn(DebridFakes.TORBOX_ACCOUNT) }
            PairSeed.debridHeldOff(container)
            val row = resolved(container, fixture)
            File(folder, DownloadDebug.THROTTLE).writeText("$THROTTLE")
            val problems = readBack(container, row, account, fixture)
            PairRecord.record("seeded", listOf(fixture), strict = false)
            PairRecord.say("fake requestdl=${calls()["torbox requestdl"] ?: 0} adds=${counts().getInt("adds")}")
            if (problems.isNotEmpty()) throw AssertionError("the seeding didn't take: ${problems.joinToString("; ")}")
            return row
        } catch (e: Throwable) {
            // No Check comes after a Start that failed: the fake goes back now, not at the hold's end. `.debrid` stays, so a
            // job the system runs meanwhile points at the fake, never at a service.
            runCatching { DebridFakes.control("reset") }
            runCatching { DebridFakes.giveBack(HOLDER) }
            throw e
        } finally {
            pointBack()
        }
    }

    /**
     * Every Usenet provider hidden as the mock's pairs hide all but the mock, rather than deleted (deleting would drop their
     * passwords), and none saved; every Usenet indexer off; Only on Wi-Fi and Only while charging off.
     */
    private suspend fun noUsenet(container: AppContainer) {
        val settings = container.settings
        val saved = settings.settings.first()
        settings.restore(saved.copy(providers = saved.providers.map { it.copy(deleted = true) }))
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        settings.setDownloadsUnmeteredOnly(false)
        settings.setDownloadsWhileCharging(false)
    }

    /**
     * The download of [fixture], queued through the runner and resolved by the app's own Resolve on the fake's account, as
     * a host resolves it, with no track probe; then Queued again, as it waits its turn.
     */
    private suspend fun resolved(container: AppContainer, fixture: PairFixture): Download {
        val dao = container.data.downloads
        val candidate = DownloadCandidate(INDEXER_ID, KEY, GOOD, size = fixture.size, source = ReleaseFacts.TORRENT)
        val row = container.downloads.enqueue(fixture.target, listOf(candidate), onePerTitle = false)
            ?: throw AssertionError("not queued: the runner runs nothing here")
        val resolver = DownloadResolver.of(container, container.downloadStorage, indexers = { emptyList() }, probe = TrackProbe.None)
        val resolution = withTimeoutOrNull(RESOLVE_MILLIS) { resolver.resolve(row.id) }
        if (resolution !is DownloadResolver.Resolution.Ready.Service) {
            val now = dao.get(row.id)
            val how = resolution?.let { it::class.simpleName } ?: "not within ${RESOLVE_MILLIS / 1000} s"
            throw AssertionError("download ${row.id} not resolved on the debrid fake ($how: ${now?.state}, ${now?.reason})")
        }
        // Queued, as a row waits its turn: one left Resolving would be judged by the instrumentation's own end (L389).
        dao.setState(row.id, DownloadState.Queued, null, System.currentTimeMillis())
        return dao.get(row.id) ?: throw AssertionError("download ${row.id} is gone")
    }

    /**
     * What didn't take: the table [row] alone, resolved to the torrent and waiting its turn, its listing's copy kept and
     * its add recorded as this device's; the seams; no Usenet provider or indexer, the switches off; the fake's [account]
     * the only service account on, Remove after playing on, and the debug build's seeding saving nothing more.
     */
    private suspend fun readBack(container: AppContainer, row: Download, account: String, fixture: PairFixture): List<String> {
        val problems = mutableListOf<String>()
        fun expect(ok: Boolean, what: () -> String) {
            if (!ok) problems += what()
        }
        val storage = container.downloadStorage
        val all = container.data.downloads.all().first()
        expect(all.map { it.id } == listOf(row.id)) { "the table holds ${all.size} rows, not the one seeded" }
        val runnable = container.downloads.runnable().map { it.id }
        expect(runnable == listOf(row.id)) { "${runnable.size} downloads can run, not the one seeded" }
        expect(row.state == DownloadState.Queued && row.reason == null) { "download ${row.id} is ${row.state} (${row.reason}), not Queued" }
        expect(row.release?.source == ReleaseFacts.TORRENT && row.release?.guid == KEY && row.tried == listOf(KEY)) { "download ${row.id} isn't resolved to the torrent" }
        expect(row.fileName != null && row.size == fixture.size) { "download ${row.id} is kept at ${row.size} bytes, not ${fixture.size}" }
        expect(row.safeOffset == 0L && row.relativePath == null && row.gaps.isEmpty()) { "download ${row.id} has bytes written already" }
        expect(File(storage.bookkeeping(row.id), DownloadStorage.MARKER).isFile) { "download ${row.id} has no marker" }
        val copy = storage.listing(row.id)
        expect(copy != null && copy.account == account && copy.prepared.hash.hex == GOOD_HASH) { "download ${row.id} keeps no listing's copy of the torrent on the account" }
        expect(storage.source(row.id) == null && storage.grab(row.id) == null) { "download ${row.id} keeps an NZB" }
        val records = container.debridAdds.records()
        expect(records.size == 1 && records.single().account == account && records.single().listingId == copy?.id) { "${records.size} add(s) recorded as this device's, not the torrent's" }
        val debug = container.downloadDebug
        expect(debug.throttle == THROTTLE) { "the throttle reads ${debug.throttle}, not $THROTTLE" }
        expect(debug.debrid) { "the debrid fake's file is missing" }
        expect(!debug.serviceHost) { "the host override is there" }
        expect(container.settings.usableProviders().isEmpty()) { "a Usenet provider is usable" }
        val asked = container.indexerConfigs().size
        expect(asked == 0) { "$asked indexer(s) a search could ask" }
        val prefs = container.settings.downloads.first()
        expect(!prefs.unmeteredOnly && !prefs.whileCharging) { "Only on Wi-Fi ${prefs.unmeteredOnly}, Only while charging ${prefs.whileCharging}" }
        val on = container.settings.settings.first().activeServices
        expect(on.map { it.id } == listOf(account) && on.single().enabled && on.single().removeAfterPlay && !on.single().keyRefused) {
            "the fake's account isn't the only one, on, with Remove after playing"
        }
        val counts = counts()
        expect(counts.getInt("adds") == 1 && counts.getInt("removals") == 0) { "the fake's account added ${counts.getInt("adds")} and removed ${counts.getInt("removals")}" }
        // Last: the seeding as the app's next process runs it, with invented entries, saves nothing more.
        val after = PairSeed.afterSeeding(container)
        expect(after.activeServices.map { it.id } == listOf(account) && after.activeTorrentIndexers.isEmpty()) {
            "the debug build's debrid seeding would save ${after.activeServices.size - 1} more account(s) and ${after.activeTorrentIndexers.size} torrent indexer(s) in the app's next process"
        }
        return problems
    }

    /** The calls the fake's services were asked, by name. */
    private fun calls(): Map<String, Int> = counts().getJSONObject("calls").let { json -> json.keys().asSequence().associateWith { json.getInt(it) } }
}
