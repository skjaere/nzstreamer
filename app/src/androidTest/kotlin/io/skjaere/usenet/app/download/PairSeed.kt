package io.skjaere.usenet.app.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.seedDevDebrid
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.core.TrackProbe
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NzbDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A release the two-phase pairs download from the mock NNTP server, its articles posted there beforehand with
 * tools/mock-seed (the README's device tests): its NZB and its file's SHA-256, androidTest assets ([nzb],
 * [sha256Asset]); the release's invented name ([release]); the file's exact [size]; and what each row is a download of
 * ([target]). Of a release of several files, one of them: the asset lists each file's SHA-256 by its name, as sha256sum
 * does, and [file] names this one's. The device doesn't have the files: each is told by its SHA-256 alone.
 */
class PairFixture(
    val nzb: String,
    val sha256Asset: String,
    val release: String,
    val size: Long,
    val target: DownloadTarget,
    val file: String? = null,
) {
    /** The NZB's bytes, as an indexer would hand them over. */
    fun nzbBytes(): ByteArray = asset(nzb)

    /** The file's SHA-256, in lowercase hex: the asset's one, or its line for [file]. */
    val sha256: String by lazy {
        val listed = asset(sha256Asset).decodeToString()
        if (file == null) {
            listed.trim()
        } else {
            listed.lines().firstOrNull { it.substringAfter("  ") == file }?.substringBefore(' ') ?: throw AssertionError("$sha256Asset lists no $file")
        }
    }

    private fun asset(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    companion object {
        /**
         * 48 MB (10^6) of seeded bytes as Harbor Lights S03E07, an invented release apart from the fake indexer's
         * catalog, in 64 articles of 750,000 bytes (app/src/androidTest/fixtures/pair-download.py makes it). An
         * episode, so Resolve's picker finds the file by its name, S03E07, as it would a real one: every row is a
         * download of that episode, as an episode's picker takes no file named for another. Seeded bytes, not a video:
         * Resolve runs no track probe for it, which no player could read.
         */
        val HARBOR_LIGHTS = PairFixture(
            nzb = "pair-download.nzb",
            sha256Asset = "pair-download.sha256",
            release = "Harbor.Lights.S03E07.1080p.WEB.h264-KESTREL",
            size = 48_000_000,
            target = DownloadTarget.episode(HARBOR_LIGHTS_SHOW, "Harbor Lights", 3, 7, seasonEpisodes = 10, episodeName = "The Breakwater"),
        )

        /**
         * An invented season pack of Harbor Lights' season 3 holding two episodes, S03E07 ([PACK_E07]) and S03E08
         * ([PACK_E08]), each a generated video of a minute (H.264 and AAC in Matroska, a test pattern and a tone, about
         * 24 MB) in 32 articles, both in one NZB (app/src/androidTest/fixtures/pair-pack.sh makes and posts them). Real
         * videos, so the app's own track probe reads them in the app's own process, as it would a real pack's.
         */
        const val PACK = "Harbor.Lights.S03.1080p.WEB.h264-KESTREL"

        val PACK_E07 = PairFixture(
            nzb = "pair-pack.nzb",
            sha256Asset = "pair-pack.sha256",
            release = PACK,
            size = 23_759_835,
            target = DownloadTarget.episode(HARBOR_LIGHTS_SHOW, "Harbor Lights", 3, 7, seasonEpisodes = 10, episodeName = "The Breakwater"),
            file = "Harbor.Lights.S03E07.1080p.WEB.h264-KESTREL.mkv",
        )

        val PACK_E08 = PairFixture(
            nzb = "pair-pack.nzb",
            sha256Asset = "pair-pack.sha256",
            release = PACK,
            size = 23_702_833,
            target = DownloadTarget.episode(HARBOR_LIGHTS_SHOW, "Harbor Lights", 3, 8, seasonEpisodes = 10, episodeName = "Slack Tide"),
            file = "Harbor.Lights.S03E08.1080p.WEB.h264-KESTREL.mkv",
        )

        private const val HARBOR_LIGHTS_SHOW = 900_973
    }
}

/**
 * The pairs' first half (L367, item 25): a Start class calls [seed], in the app's own process, and returns; the pairs'
 * runner then opens the app as a user does. What it leaves:
 * - the app's downloads emptied first: every row, in the crash-safe order, and whatever else is in both `downloads/`
 *   folders (the debug seams' files and the trace among them); nothing else of the app's data;
 * - the mock NNTP server as the only Usenet provider (10.0.2.2:1119, no TLS, no login, 4 connections), saved through
 *   the settings as a user saves one, the others hidden (their passwords kept), and the engine on it;
 * - Only on Wi-Fi or Ethernet and Only while charging off, so nothing but a validated network holds the job;
 * - a debrid account signed out of and a torrent indexer removed, invented, so the debug build's debrid seeding never
 *   saves the developer's in the app's own processes between the phases (L182);
 * - for the service host (L336), the override (`.host`), written before the rows so the boot receiver's switch reads it
 *   as the queue changes;
 * - [Plan.rows] downloads of [Plan.fixture], each queued through the runner (its folder and marker, L346), then
 *   resolved by the app's own Resolve as a host resolves it: the full check, which asks the mock for every article the
 *   NZB names, and the prepare, with no track probe (seeded bytes), its NZB served in the indexer's place through the
 *   coordinator's grab seam. So its copy (no password), release, file, exact size and oneVideo are kept as Resolve
 *   keeps them. Then each is Queued again, waiting its turn;
 * - the debug throttle (`.throttle`) at [Plan.throttle].
 *
 * The rows are left Queued, not Running or Resolving (L389): the instrumentation's own end stops the app's process as
 * the user's stop would, and reconcile would hold a row it left under way before the pair's step. A host walking the
 * queue reopens each from its copy (DownloadResolver's reopen): no grab, no indexer. Should the copy no longer open,
 * Resolve falls back on the candidate, whose indexer was never saved: the row fails with no grab.
 *
 * A pair whose rows are of another kind seeds them in the same frame, with its own read-back ([SeasonPackSeed]: a
 * season pack's copy and two rows of its season, which the host resolves).
 *
 * It reads back what it seeded and fails only where the seeding didn't take; what it found before and left after go out
 * as [PairRecord]'s lines. The settings stay as it left them for the Check: the pairs' runner installs the app afresh
 * for each pair.
 */
object PairSeed {
    /** What a Start seeds: [rows] downloads of [fixture], the writer held to [throttle] bytes a second, the service as host or not. */
    data class Plan(
        val fixture: PairFixture = PairFixture.HARBOR_LIGHTS,
        val rows: Int = 1,
        val throttle: Long = THROTTLE,
        val serviceHost: Boolean = false,
    )

    /**
     * The pairs' pace, 1 MB a second: the 48 MB file in about 48 s, so the runner sees a transfer past a quarter (at
     * the second progress record, about 20 s in) well before its end, and its step comes with most of the file still to
     * come.
     */
    const val THROTTLE = 1_000_000L

    /** The mock NNTP server, as the emulator reaches its host. */
    const val MOCK_HOST = "10.0.2.2"
    const val MOCK_PORT = 1119

    /** The candidates' indexer: never saved, so nothing but the grab seam ever serves it. */
    const val INDEXER_ID = "pair-indexer"

    private val INDEXER = IndexerConfig(INDEXER_ID, "Pair indexer (never asked)", "http://indexer.invalid/api", "pair-key")

    /** The placeholders' key: invented, and never sent. */
    private const val PLACEHOLDER_KEY = "pair-placeholder-key"

    /** A debrid account and a torrent indexer as the debug build encodes its own (DevServices, DevIndexers): invented. */
    private const val INVENTED_SERVICES = "TorBox\tpair-invented-key"
    private const val INVENTED_TORRENT_INDEXERS = "Pair torrents\thttp://indexer.invalid/torznab\tpair-invented-key"

    private const val SETUP_MILLIS = 30_000L
    private const val RESOLVE_MILLIS = 120_000L

    /** Seeds [plan] as the object says; the rows as seeded, in the queue's order. */
    suspend fun seed(plan: Plan = Plan()): List<Download> {
        require(plan.rows >= 1) { "A pair needs a download" }
        val nzb = plan.fixture.nzbBytes()
        return seed(
            listOf(plan.fixture), plan.throttle, plan.serviceHost,
            queue = { container -> (0 until plan.rows).map { n -> resolved(container, plan.fixture, n, nzb) } },
            readBack = { container, _ -> resolvedRows(container, plan.fixture.size, nzb) },
        )
    }

    /**
     * What every pair's Start seeds, as the object says, around the rows [queue] seeds (with whatever else they need): the
     * downloads emptied first, the mock alone, the switches off, the debrid seeding held off and the service host's override
     * where [serviceHost]; after the rows, the throttle at [throttle]. Read back after: what [readBack] finds didn't take
     * of the rows, and what didn't of the rest. The record compares each row's file with its episode's of [fixtures].
     */
    internal suspend fun seed(
        fixtures: List<PairFixture>,
        throttle: Long,
        serviceHost: Boolean,
        queue: suspend (AppContainer) -> List<Download>,
        readBack: suspend (AppContainer, List<Download>) -> List<String>,
    ): List<Download> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = context.container
        assumeFalse("downloads never run on a TV (L369)", container.television)
        // What the boundary before left, before any of it goes. Reconciled first: the app's start does that anyway.
        PairRecord.record("found", fixtures, strict = false)
        if (!reachable()) throw AssertionError("the mock NNTP server isn't reachable at $MOCK_HOST:$MOCK_PORT")
        // The debug build's own seeding (its provider, while none was ever saved) first, so it never lands after the mock.
        withTimeout(SETUP_MILLIS) { container.devSettingsSaved.await() }
        empty(context, container)
        val mock = mockAlone(container)
        debridHeldOff(container)
        val folder = File(context.filesDir, DownloadStorage.DIR).apply { mkdirs() }
        if (serviceHost) File(folder, DownloadDebug.HOST).writeText("service")
        val rows = queue(container)
        File(folder, DownloadDebug.THROTTLE).writeText("$throttle")
        val problems = readBack(container, rows) + readBackAround(container, rows, throttle, serviceHost, mock)
        PairRecord.record("seeded", fixtures, strict = false)
        if (problems.isNotEmpty()) throw AssertionError("the seeding didn't take: ${problems.joinToString("; ")}")
        return rows
    }

    /** Every row and file of the downloads gone, the debug seams' files and the trace with them: nothing else of the app's. */
    private suspend fun empty(context: Context, container: AppContainer) {
        val dao = container.data.downloads
        dao.all().first().forEach { row ->
            try {
                container.downloadStorage.delete(row.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An id that names no folder: the folders go below, the row here.
                dao.delete(row.id)
            }
        }
        // One whose volume was away stays Deleting: its files go below too.
        dao.all().first().forEach { dao.delete(it.id) }
        listOfNotNull(File(context.filesDir, DownloadStorage.DIR), volume(context)?.let { File(it, DownloadStorage.DIR) })
            .forEach { dir -> dir.listFiles().orEmpty().forEach { it.deleteRecursively() } }
    }

    /**
     * The mock saved as the only provider, as a user saves one, the others hidden rather than deleted (deleting would
     * drop their passwords): a hidden one still counts as saved, so the debug build never seeds its own again. Wi-Fi
     * only and charging only off. Returns the mock's id once the engine is on it alone.
     */
    private suspend fun mockAlone(container: AppContainer): String {
        val settings = container.settings
        val saved = settings.settings.first()
        settings.restore(saved.copy(providers = saved.providers.map { it.copy(deleted = true) }))
        val mock = settings.saveProvider(ProviderRecord("", "Mock (pairs)", MOCK_HOST, MOCK_PORT, useTls = false, connections = 4), "")
        settings.setDownloadsUnmeteredOnly(false)
        settings.setDownloadsWhileCharging(false)
        // The app's own provider sync gives the engine what was just saved.
        engineSetup("the engine on the pairs' mock alone") { container.engine.configuredProviders.first { it == listOf(mock) } }
        return mock
    }

    /**
     * The debug build's debrid seeding held off in the app's own processes between the phases (L182): the runner's open,
     * JobScheduler's runs and the service's sticky restart aren't the instrumentation's, so the test runner's switch doesn't
     * reach them, and on the fresh install each pair has they would save the developer's accounts and torrent indexers.
     * The seeding saves only while none was ever saved, so an account signed out of and a torrent indexer removed, both
     * invented and never asked, leave the records that keep it from ever doing so.
     */
    private suspend fun debridHeldOff(container: AppContainer) {
        val settings = container.settings
        val saved = settings.settings.first()
        if (saved.services.isEmpty()) settings.signOut(settings.saveService(ServiceKind.TorBox.name, PLACEHOLDER_KEY) { ServiceSignIn(accountName = "") })
        if (saved.torrentIndexers.isEmpty()) {
            settings.deleteTorrentIndexer(settings.saveTorrentIndexer(null, "Pair placeholder", "http://indexer.invalid/torznab", PLACEHOLDER_KEY))
        }
    }

    /**
     * The [n]-th download of [fixture], queued through the runner and resolved by the app's own Resolve, its NZB
     * ([nzb]) served by the grab seam for its own guid alone; then Queued again, as it waits its turn.
     */
    private suspend fun resolved(container: AppContainer, fixture: PairFixture, n: Int, nzb: ByteArray): Download {
        val dao = container.data.downloads
        val guid = "pair-${n + 1}"
        val candidate = DownloadCandidate(INDEXER_ID, guid, fixture.release, size = fixture.size)
        // Beside the rows before it, all of the one episode, where a user's Download would have the first.
        val row = container.downloads.enqueue(fixture.target, listOf(candidate), onePerTitle = false)
            ?: throw AssertionError("not queued: the runner runs nothing here")
        val real = PlaybackCoordinator.fetchNzb
        PlaybackCoordinator.fetchNzb = { _, _, item -> if (item.guid == guid) NzbDownload(nzb) else throw IllegalStateException("not the pair's release") }
        val resolution = try {
            // Seeded bytes, which no player reads: nothing to probe.
            val resolver = DownloadResolver.of(container, container.downloadStorage, indexers = { listOf(INDEXER) }, probe = TrackProbe.None)
            withTimeoutOrNull(RESOLVE_MILLIS) { resolver.resolve(row.id) }
        } finally {
            PlaybackCoordinator.fetchNzb = real
        }
        if (resolution !is DownloadResolver.Resolution.Ready) {
            val now = dao.get(row.id)
            val how = resolution?.let { it::class.simpleName } ?: "not within ${RESOLVE_MILLIS / 1000} s"
            throw AssertionError("download ${row.id} not resolved on the mock ($how: ${now?.state}, ${now?.reason}): is the fixture seeded there?")
        }
        // Queued, as a row waits its turn: one left Resolving would be judged by the instrumentation's own end (L389).
        dao.setState(row.id, DownloadState.Queued, null, System.currentTimeMillis())
        return dao.get(row.id) ?: throw AssertionError("download ${row.id} is gone")
    }

    /**
     * What didn't take of the table's rows, each a download of the fixture of [size] bytes resolved ([resolved]) and waiting
     * its turn, its own NZB [nzb].
     */
    private suspend fun resolvedRows(container: AppContainer, size: Long, nzb: ByteArray): List<String> {
        val problems = mutableListOf<String>()
        fun expect(ok: Boolean, what: () -> String) {
            if (!ok) problems += what()
        }
        val storage = container.downloadStorage
        container.data.downloads.all().first().forEach { row ->
            expect(row.state == DownloadState.Queued && row.reason == null) { "download ${row.id} is ${row.state} (${row.reason}), not Queued" }
            expect(row.release != null && row.fileName != null) { "download ${row.id} keeps no release or file" }
            expect(row.size == size) { "download ${row.id} is kept at ${row.size} bytes, not $size" }
            expect(row.safeOffset == 0L && row.relativePath == null && row.gaps.isEmpty()) { "download ${row.id} has bytes written already" }
            expect(File(storage.bookkeeping(row.id), DownloadStorage.MARKER).isFile) { "download ${row.id} has no marker" }
            val copy = storage.source(row.id)
            expect(copy != null && copy.nzb.contentEquals(nzb) && copy.password == null) { "download ${row.id}'s own NZB isn't the fixture's, with no password" }
            expect(storage.grab(row.id) == null) { "download ${row.id} keeps a grab's NZB beside its own" }
        }
        return problems
    }

    /**
     * What didn't take of the rest, read back from the table, the seams, the settings and the engine: the table [rows]
     * alone, all of them able to run in their order, the writer held to [throttle], the [serviceHost] override, the [mock]
     * alone, the switches off, and the debug build's debrid seeding held off.
     */
    private suspend fun readBackAround(container: AppContainer, rows: List<Download>, throttle: Long, serviceHost: Boolean, mock: String): List<String> {
        val problems = mutableListOf<String>()
        fun expect(ok: Boolean, what: () -> String) {
            if (!ok) problems += what()
        }
        val all = container.data.downloads.all().first()
        expect(all.map { it.id } == rows.map { it.id }) { "the table holds ${all.size} rows, not the ${rows.size} seeded" }
        val runnable = container.downloads.runnable().map { it.id }
        expect(runnable == rows.map { it.id }) { "${runnable.size} downloads can run, not the ${rows.size} seeded" }
        val debug = container.downloadDebug
        expect(debug.throttle == throttle) { "the throttle reads ${debug.throttle}, not $throttle" }
        expect(debug.serviceHost == serviceHost) { "the host override is ${if (debug.serviceHost) "there" else "missing"}" }
        val usable = container.settings.usableProviders().map { it.record.id }
        expect(usable == listOf(mock)) { "${usable.size} providers usable, not the mock alone" }
        expect(container.engine.configuredProviders.value == listOf(mock)) { "the engine isn't on the mock alone" }
        val prefs = container.settings.downloads.first()
        expect(!prefs.unmeteredOnly && !prefs.whileCharging) { "Only on Wi-Fi ${prefs.unmeteredOnly}, Only while charging ${prefs.whileCharging}" }
        // The debug build's debrid seeding as the app's next process runs it (L182): invented entries here, never asked, so a
        // seeding that would run saves these, not the developer's.
        seedDevDebrid(container.settings, INVENTED_SERVICES, INVENTED_TORRENT_INDEXERS, enabled = true, shown = true)
        val after = container.settings.settings.first()
        expect(after.activeServices.isEmpty() && after.activeTorrentIndexers.isEmpty()) {
            "the debug build's debrid seeding would save ${after.activeServices.size} account(s) and ${after.activeTorrentIndexers.size} torrent indexer(s) in the app's next process"
        }
        return problems
    }

    private fun reachable(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(MOCK_HOST, MOCK_PORT), 2_000) }
        true
    } catch (e: Exception) {
        false
    }

    /** The app's folder on the primary volume, as DownloadStorage finds it; null while it isn't there. */
    internal fun volume(context: Context): File? = context.getExternalFilesDirs(null).firstOrNull()?.takeIf { it.isDirectory }
}

/**
 * Runs a pair's class only where its run names it (`-e class`, as the pairs' runner passes each phase and a run by name
 * passes its classes), on a phone, and skips it anywhere else: a whole suite's run, a shard, a filter by package or
 * pattern. A Check passes only after the step the runner takes between the phases, and a Start leaves the app seeded for
 * its Check (the mock alone as the provider, the switches off, the seams' files, rows queued): run unnamed, the one would
 * fail and the other leave its seeding to the classes after it. Neither half runs on a TV (L369).
 */
class PairRun : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val named = InstrumentationRegistry.getArguments().getString("class").orEmpty().split(',').map { it.substringBefore('#').trim() }
            assumeTrue("${description.testClass.simpleName} runs only when its run names it: a two-phase pair (L367)", description.className in named)
            assumeFalse("downloads never run on a TV (L369)", InstrumentationRegistry.getInstrumentation().targetContext.container.television)
            base.evaluate()
        }
    }
}

/**
 * What the pairs record (L367, item 25): each line goes out through the instrumentation's status (key [KEY], one bundle
 * per line, code [CODE]), so the run's own output keeps it beside the phase's result. Ids, offsets, sizes, times, codes
 * and causes only: no title, release, host or a cause's words.
 * - the process (its pid and start, the boot's time) and the package's install and update times;
 * - the settings that rule a download (the providers' count, whether the mock alone, the switches, notifications), the
 *   host, the override, the throttle, the boot receiver's state, and the network;
 * - the trace (`files/downloads/.trace`), line by line as the app wrote it, those from this process's start on marked
 *   (this process's own start reconciles first, and may judge a row the boundary before left under way);
 * - each row's state, stop words, safe offset and size; each file's size, and whether its SHA-256 is its episode's
 *   fixture's;
 * - what both `downloads/` folders hold, by name and size, and the season packs' copies (`.packs/`), each by its folder's
 *   first 8 hex digits, with which of its files it has;
 * - Android's records of the app's ended processes (getHistoricalProcessExitReasons, 20 at most), each with how the
 *   app's reconcile reads it (DownloadExit.byUser), a description only where it is the system's own plain words;
 * - the pending jobs of every namespace: id, user-initiated or not, network, persisted, charging, bytes, pending
 *   reason.
 *
 * It asserts nothing of what it reads; [record] fails only for a part it couldn't read. [traceLines], [parse],
 * [processStart] and [bootTime] are what a Check asserts with ([PairCheck]).
 */
object PairRecord {
    /** The instrumentation status's key for a line. */
    const val KEY = "pair"

    /**
     * The status code of a line: 2, an instrumentation's "in progress", which the pairs' runner and Android's own
     * result parsers pass over, so a line never counts as a test.
     */
    const val CODE = 2

    /** Says [line], on one line. */
    fun say(line: String) {
        val one = line.replace('\n', ' ').replace('\r', ' ')
        InstrumentationRegistry.getInstrumentation().sendStatus(CODE, Bundle().apply { putString(KEY, one) })
    }

    /**
     * Says everything the object lists, after a "phase [phase]" line, the app's start in this process reconciled first,
     * each row's file compared with its episode's of [fixtures]. With [strict], fails at the end for each part it couldn't
     * read; never for what it read.
     */
    suspend fun record(phase: String, fixtures: List<PairFixture> = listOf(PairFixture.HARBOR_LIGHTS), strict: Boolean = true) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = context.container
        val unread = mutableListOf<String>()
        suspend fun part(name: String, read: suspend () -> Unit) {
            try {
                read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("$name not read: ${e::class.simpleName}")
                unread += name
            }
        }
        say("phase $phase")
        part("process") { process(context) }
        // The app's start launched it already; waited for, so the rows below are as it left them.
        part("reconcile") { container.downloads.reconcile() }
        part("settings") { settings(context, container) }
        part("trace") { trace(context) }
        part("rows") { rows(container, fixtures) }
        part("folders") { folders(context) }
        part("packs") { packs(context) }
        part("exits") { exits(context) }
        part("jobs") { jobs(context) }
        if (strict && unread.isNotEmpty()) throw AssertionError("not read: ${unread.joinToString()}")
    }

    /** A trace line read back: its time, its event (start, schedule, reconcile, reopen, progress, done) and its fields. */
    data class Traced(val at: Long, val event: String, val fields: Map<String, String>) {
        /** The field [name]'s value; fails for a line without it. */
        operator fun get(name: String): String = fields[name] ?: throw AssertionError("the trace's $event line at $at has no $name")
    }

    /** The trace's lines as the app wrote them; empty with no trace. */
    fun traceLines(context: Context): List<String> =
        File(File(context.filesDir, DownloadStorage.DIR), DownloadDebug.TRACE).takeIf { it.isFile }?.readLines().orEmpty()

    /** [line] read back, or null for a line of another shape than DownloadTrace's. */
    fun parse(line: String): Traced? {
        if (!TRACE_LINE.matches(line)) return null
        val words = line.split(' ')
        return Traced(words[0].toLong(), words[1], words.drop(2).associate { it.substringBefore('=') to it.substringAfter('=') })
    }

    /** When this process started, in wall-clock milliseconds, as the trace times its lines. */
    fun processStart(): Long = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())

    /** When the device last booted, in wall-clock milliseconds. */
    fun bootTime(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun process(context: Context) {
        say("process pid=${Process.myPid()} started-at=${processStart()} boot-at=${bootTime()} now=${System.currentTimeMillis()} sdk=${Build.VERSION.SDK_INT}")
        // The flags' overload is Android 13's; this one serves every level.
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        say("package installed-at=${info.firstInstallTime} updated-at=${info.lastUpdateTime}")
    }

    private suspend fun settings(context: Context, container: AppContainer) {
        val usable = container.settings.usableProviders()
        val mockOnly = usable.isNotEmpty() && usable.all { it.record.host == PairSeed.MOCK_HOST && it.record.port == PairSeed.MOCK_PORT }
        val prefs = container.settings.downloads.first()
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        say("settings providers=${usable.size} mock-only=$mockOnly wifi-only=${prefs.unmeteredOnly} charging=${prefs.whileCharging} notifications=$notifications")
        val debug = container.downloadDebug
        val host = DownloadHostRules.hostFor(Build.VERSION.SDK_INT, debug.serviceHost).name.lowercase()
        val receiver = when (context.packageManager.getComponentEnabledSetting(ComponentName(context, DownloadBootReceiver::class.java))) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "default"
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enabled"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "disabled"
            else -> "other"
        }
        say("host $host override=${debug.serviceHost} throttle=${debug.throttle ?: "none"} boot-receiver=$receiver")
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val transport = when {
            network == null -> "none"
            network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            network.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        say(
            "network transport=$transport validated=${network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true} " +
                "not-metered=${network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true}",
        )
    }

    private fun trace(context: Context) {
        val lines = traceLines(context)
        val since = processStart()
        say("trace lines=${lines.size}")
        lines.forEach { line ->
            val traced = parse(line)
            say(if (traced == null) "trace (a line of another shape, ${line.length} chars)" else "trace $line${if (traced.at >= since) " [this process]" else ""}")
        }
    }

    private suspend fun rows(container: AppContainer, fixtures: List<PairFixture>) {
        val storage = container.downloadStorage
        val rows = container.data.downloads.all().first()
        if (rows.isEmpty()) say("rows none")
        rows.forEach { row ->
            if (!ID.matches(row.id)) {
                say("row (an id of another shape) state=${row.state}")
                return@forEach
            }
            val folder = storage.bookkeeping(row.id)
            say(
                "row id=${row.id} state=${row.state} reason=${row.reason ?: "none"} offset=${row.safeOffset} size=${row.size ?: "none"} " +
                    "indexer-size=${row.indexerSize} gaps=${row.gaps.size} tried=${row.tried.size} position=${row.position} " +
                    "release=${row.release != null} one-video=${row.oneVideo} file=${row.relativePath != null} updated-at=${row.updatedAt} " +
                    "completed-at=${row.completedAt ?: "none"} marker=${File(folder, DownloadStorage.MARKER).isFile} " +
                    "copy=${File(folder, DownloadStorage.NZB).isFile} password=${if (File(folder, DownloadStorage.PASSWORD).isFile) "set" else "none"} " +
                    "grab=${File(folder, DownloadStorage.GRAB_KEY).isFile}",
            )
            val video = storage.videoFolder(row.id)
            val files = video?.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
            when {
                video == null -> say("file id=${row.id} volume=away")
                files.isEmpty() -> say("file id=${row.id} none")
            }
            val fixture = fixtures.firstOrNull { it.target.watchKey == row.watchKey } ?: fixtures.first()
            files.forEach { file ->
                val matches = file.length() == fixture.size && DownloadFixtures.sha256(file) == fixture.sha256
                say("file id=${row.id} name=${name(file.name)} bytes=${file.length()} sha256-matches=$matches fixture-bytes=${fixture.size}")
            }
        }
    }

    private fun folders(context: Context) {
        listOf("files" to File(context.filesDir, DownloadStorage.DIR), "volume" to PairSeed.volume(context)?.let { File(it, DownloadStorage.DIR) })
            .forEach { (where, dir) ->
                val entries = dir?.listFiles().orEmpty().sortedBy { it.name }.map { "${name(it.name)}${if (it.isDirectory) "/" else ":${it.length()}"}" }
                say("folder $where ${if (dir == null) "away" else entries.joinToString(" ").ifEmpty { "empty" }}")
            }
    }

    /** The season packs' copies, each by its folder's first 8 hex digits (never its key), and which of its files it has. */
    private fun packs(context: Context) {
        val copies = File(context.filesDir, DownloadNzbs.PATH).listFiles().orEmpty().sortedBy { it.name }
        if (copies.isEmpty()) say("packs none")
        copies.forEach { copy ->
            val has = { file: String -> File(copy, file).isFile }
            val name = copy.name.takeIf { HEX.matches(it) }?.take(8) ?: "(a name of another shape)"
            say("pack $name meta=${has(DownloadNzbs.META)} nzb=${has(DownloadNzbs.NZB)} password=${if (has(DownloadNzbs.PASSWORD)) "set" else "none"}")
        }
    }

    private fun exits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            say("exits none kept below Android 11")
            return
        }
        val exits = context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
        // As the runner's lastExit reads it, beside each record.
        @Suppress("DEPRECATION")
        val updated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        if (exits.isEmpty()) say("exits none")
        exits.forEach { say(exitLine(it, updated, context.packageName)) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitLine(exit: ApplicationExitInfo, updated: Long, pkg: String): String {
        val name = exit.processName
        val process = when {
            name == pkg -> "main"
            name.startsWith("$pkg:") -> name.substringAfter(':')
            else -> "other"
        }
        val byUser = DownloadExit(exit.reason, exit.timestamp, exit.description, updated).byUser
        return "exit pid=${exit.pid} at=${exit.timestamp} reason=${exit.reason} ${REASONS[exit.reason] ?: "?"} status=${exit.status} " +
            "importance=${exit.importance} pss-kb=${exit.pss} rss-kb=${exit.rss} process=$process by-user=$byUser description=${description(exit, pkg)}"
    }

    /**
     * An exit's description where it is the system's own plain words ("remove task", "stop app"), the package's name
     * put as <pkg>; left out for a crash, an ANR or a failed start, whose description can carry the app's own words,
     * and for any that isn't plain words.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun description(exit: ApplicationExitInfo, pkg: String): String {
        val words = exit.description ?: return "none"
        if (exit.reason in OWN_WORDS) return "(left out: it can carry the app's own words)"
        return words.replace(pkg, "<pkg>").takeIf(PLAIN::matches) ?: "(left out: not plain words)"
    }

    private fun jobs(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val all = scheduler.pendingJobsInAllNamespaces
            if (all.values.all { it.isEmpty() }) say("jobs none")
            all.forEach { (namespace, jobs) ->
                val own = if (namespace == null) scheduler else scheduler.forNamespace(namespace)
                jobs.forEach { say(jobLine(namespace, it, own.getPendingJobReason(it.id))) }
            }
        } else {
            val jobs = scheduler.allPendingJobs
            if (jobs.isEmpty()) say("jobs none")
            jobs.forEach { say(jobLine(null, it, null)) }
        }
    }

    private fun jobLine(namespace: String?, job: JobInfo, reason: Int?): String {
        val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            job.requiredNetwork?.let { request ->
                listOfNotNull(
                    "internet".takeIf { request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) },
                    "validated".takeIf { request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) },
                    "not-metered".takeIf { request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) },
                ).joinToString("+").ifEmpty { "any" }
            } ?: "none"
        } else {
            "?"
        }
        val userInitiated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && job.isUserInitiated
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) "${job.estimatedNetworkDownloadBytes}" else "?"
        return "job namespace=${namespace ?: "default"} id=${job.id} service=${job.service.className.substringAfterLast('.')} " +
            "user-initiated=$userInitiated persisted=${job.isPersisted} network=$network charging=${job.isRequireCharging} bytes=$bytes " +
            "pending-reason=${reason?.let { "$it ${PENDING[it] ?: "?"}" } ?: "?"}"
    }

    /** A file's name in the downloads' folders: an id, `v.<ext>`, a seam's dot-file; any other shape left out. */
    private fun name(name: String): String = name.takeIf(NAME::matches) ?: "(a name of another shape)"

    private const val MAX_EXITS = 20

    private const val ID_SHAPE = "[A-Za-z0-9][A-Za-z0-9._~-]*"

    /** A download's id, as DownloadStorage takes one. */
    private val ID = Regex(ID_SHAPE)

    private val NAME = Regex("[A-Za-z0-9._~-]{1,64}")

    /** A season pack's copy's folder: the SHA-256 of its release's key, in hex. */
    private val HEX = Regex("[0-9a-f]{64}")

    private fun words(all: Iterable<String>) = all.joinToString("|", "(", ")")

    /** DownloadTrace's lines, after their time: what [parse] reads back, each of their words from the code's own. */
    private val TRACE_LINE = Regex(
        "\\d+ (start host=${words(DownloadHost.entries.map { it.name.lowercase() })} cause=${words(DownloadTrace.StartCause.entries.map { it.word })}" +
            "|schedule cause=${words(DownloadHostRules.ScheduleCause.entries.map { it.name })} result=${words(DownloadTrace.ScheduleResult.entries.map { it.word })}" +
            "|reconcile id=$ID_SHAPE decision=${words(DownloadTrace.Decision.entries.map { it.word })} exit=(none|\\d+( exit-at=\\d+)?)" +
            "|reopen id=$ID_SHAPE offset=\\d+" +
            "|progress id=$ID_SHAPE offset=\\d+ size=\\d+" +
            "|done id=$ID_SHAPE size=\\d+)",
    )

    /** The system's plain words, the package's name put as <pkg> first. */
    private val PLAIN = Regex("[A-Za-z0-9 _:,=()#<>/-]{0,120}")

    /** The reasons whose description can carry the app's own words: a crash's message, an ANR's, a failed start's. */
    private val OWN_WORDS = setOf(4, 5, 6, 7)

    /** ApplicationExitInfo's REASON_ codes, by number, as Android 14 has them. */
    private val REASONS = mapOf(
        0 to "UNKNOWN", 1 to "EXIT_SELF", 2 to "SIGNALED", 3 to "LOW_MEMORY", 4 to "CRASH", 5 to "CRASH_NATIVE", 6 to "ANR",
        7 to "INITIALIZATION_FAILURE", 8 to "PERMISSION_CHANGE", 9 to "EXCESSIVE_RESOURCE_USAGE", 10 to "USER_REQUESTED",
        11 to "USER_STOPPED", 12 to "DEPENDENCY_DIED", 13 to "OTHER", 14 to "FREEZER", 15 to "PACKAGE_STATE_CHANGE",
        16 to "PACKAGE_UPDATED",
    )

    /** JobScheduler's PENDING_JOB_REASON_ codes, read only on Android 14 and later, which has them. */
    @SuppressLint("InlinedApi")
    private val PENDING = mapOf(
        JobScheduler.PENDING_JOB_REASON_EXECUTING to "EXECUTING",
        JobScheduler.PENDING_JOB_REASON_INVALID_JOB_ID to "INVALID_JOB_ID",
        JobScheduler.PENDING_JOB_REASON_UNDEFINED to "UNDEFINED",
        JobScheduler.PENDING_JOB_REASON_APP to "APP",
        JobScheduler.PENDING_JOB_REASON_APP_STANDBY to "APP_STANDBY",
        JobScheduler.PENDING_JOB_REASON_BACKGROUND_RESTRICTION to "BACKGROUND_RESTRICTION",
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW to "CONSTRAINT_BATTERY_NOT_LOW",
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CHARGING to "CONSTRAINT_CHARGING",
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY to "CONSTRAINT_CONNECTIVITY",
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONTENT_TRIGGER to "CONSTRAINT_CONTENT_TRIGGER",
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEVICE_IDLE to "CONSTRAINT_DEVICE_IDLE",
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY to "CONSTRAINT_MINIMUM_LATENCY",
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_PREFETCH to "CONSTRAINT_PREFETCH",
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_STORAGE_NOT_LOW to "CONSTRAINT_STORAGE_NOT_LOW",
        JobScheduler.PENDING_JOB_REASON_DEVICE_STATE to "DEVICE_STATE",
        JobScheduler.PENDING_JOB_REASON_JOB_SCHEDULER_OPTIMIZATION to "JOB_SCHEDULER_OPTIMIZATION",
        JobScheduler.PENDING_JOB_REASON_QUOTA to "QUOTA",
        JobScheduler.PENDING_JOB_REASON_USER to "USER",
    )
}
