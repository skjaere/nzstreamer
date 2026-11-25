package io.skjaere.usenet.app.plugins.sportarr

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.plugins.InstallRequest
import io.skjaere.usenet.app.plugins.InstallResult
import io.skjaere.usenet.app.plugins.InstalledPlugin
import io.skjaere.usenet.app.plugins.PluginDevSecrets
import io.skjaere.usenet.app.plugins.PluginListServer
import io.skjaere.usenet.app.plugins.PluginMockSetup
import io.skjaere.usenet.app.plugins.PluginStateRule
import io.skjaere.usenet.app.plugins.PluginStatus
import io.skjaere.usenet.app.plugins.PluginStore
import io.skjaere.usenet.app.plugins.Sha256
import io.skjaere.usenet.app.plugins.await
import io.skjaere.usenet.data.ItemMark
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.abs

/** The Sportarr plugin's id, as its builds carry it (L639). */
const val SPORTARR = "sportarr"

/** Its entry class, made by its own loader. */
const val SPORTARR_ENTRY = "app.nzstreamer.plugin.sportarr.SportarrPlugin"

/**
 * The Sportarr plugin's debug build in the test APK's assets (L657): sportarr.apk, version 0.1.0 (versionCode 10000, its
 * version.txt's by the plugin convention), signed as the app under test is, so a debug run trusts it through the test
 * signer (L622). Its name and summary are its listing.json's, as the release's list gives them.
 */
object SportarrBuild {
    const val ASSET = "sportarr.apk"
    const val NAME = "Sportarr"
    const val SUMMARY = "The leagues, teams and sessions you follow in your own Sportarr, in a Sports tab."
    const val VERSION_NAME = "0.1.0"
    const val VERSION_CODE = 10_000L

    /** The build as a plugin list offers it: its id, its listing's name and summary, its version as built. */
    val listing = PluginListServer.Asset(ASSET, SPORTARR, NAME, SUMMARY, VERSION_NAME, VERSION_CODE)

    /** The build's file as the test APK's assets hold it. */
    fun bytes(): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets.open(ASSET).use { it.readBytes() }

    /** The build as a download leaves it in cacheDir/plugins/.download: a writable file nothing has checked yet. */
    fun download(context: Context): File {
        val file = File(File(File(context.cacheDir, PluginStore.DIR), DOWNLOADS), ASSET)
        file.parentFile!!.mkdirs()
        InstrumentationRegistry.getInstrumentation().context.assets.open(ASSET).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    /** What the plugin list would say of this build: its id, name and version, and its file's [sha256]. */
    fun request(sha256: String) = InstallRequest(SPORTARR, NAME, VERSION_CODE, sha256)

    private const val DOWNLOADS = ".download"
}

/**
 * The real plugin installed in the app's own registry, as the Plugins page's installer does after a download (L657): its
 * hosts are the app's, with its http, search, play and watch. Never a [PluginStateRule.registry], whose hosts have none of
 * them. The rule removes it after the test, with its files and secrets. Never while the runner left the plugins' dev
 * seeding on (W168 a): the install would store the debug build's own Sportarr address and key on the device.
 */
suspend fun PluginStateRule.installSportarr(): InstallResult {
    check(!PluginDevSecrets.seeding) {
        "The runner left the plugins' dev seeding on: an install would store local.properties' values"
    }
    val download = SportarrBuild.download(context)
    return app.install(download, SportarrBuild.request(Sha256.of(download)))
}

/**
 * The sports fakes from an emulator (L655): the fake Sportarr (10.0.2.2:8098) with its invented key, and the sports
 * indexer (10.0.2.2:8094) with the device tests' fake indexer's key, one `mock-seed sports` process the run's lead starts
 * on the emulator host.
 */
object SportsFakes {
    const val HOST = "10.0.2.2"
    const val SPORTARR_PORT = 8098
    const val INDEXER_PORT = 8094

    /** The fake Sportarr's address and key, as a user types them into the setup. */
    const val URL = "http://$HOST:$SPORTARR_PORT"
    const val KEY = "fake-sportarr-key"

    /** In every value of the fields the plugin must never show (L653): scores, statuses, images, venues, files. */
    const val CANARY = "CANARY"

    fun reachable(port: Int): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress(HOST, port), 2_000) } }.isSuccess
}

/** The fake Sportarr's clock (`/control/clock`): the [anchor] every start and release date counts from, and its [now]. */
data class FakeClock(val anchor: LocalDate, val now: Instant)

/**
 * A plugin device class's Sportarr (L657), inside its [plugins] rule: the real plugin installed through the app's own
 * registry and running ([install]), the fake Sportarr's address and key in its secrets ([connect]); the sports indexer as
 * the one indexer on and, for a play, the mock as the one provider ([indexers]); the fake held for the test ([hold], its
 * lease, as the debrid fake's: the phones share it); its switches set by [switch] and its clock read ([clock]); the
 * catalogue at the fake's anchor ([catalogue]); watch rows of the plugin's parts written for a test ([progress],
 * [watched]); and a part's play, its launch taken from the coordinator ([launchOf]) and played in the player
 * ([playAndKeep]). [restore] puts back what the test changed, whatever failed: the switches off, the indexer and the
 * provider as they were, the watch rows as they were, the fake given back. The plugin itself, its files and its secrets
 * go with the rule. [assumeUp] skips a test whose fakes aren't up.
 */
class SportarrDevice(private val plugins: PluginStateRule, owner: String) {
    private val container = plugins.context.container
    private val rows = container.library
    private val by = "$owner-${UUID.randomUUID()}"
    private val undo = mutableListOf<String>()
    private var held = false
    private var keeping: HoldKeeper? = null
    private var indexed = false
    private val mock = PluginMockSetup(INDEXER_ID, INDEXER_NAME, SportsFakes.INDEXER_PORT)

    /** Skips the test where the fake Sportarr, and where asked the sports indexer or the mock, isn't up. */
    fun assumeUp(indexer: Boolean = false, plays: Boolean = false) {
        assumeTrue(
            "the fake Sportarr isn't reachable at ${SportsFakes.HOST}:${SportsFakes.SPORTARR_PORT} (mock-seed sports)",
            SportsFakes.reachable(SportsFakes.SPORTARR_PORT),
        )
        if (indexer || plays) mock.assumeUp(plays)
    }

    /**
     * Holds the fake Sportarr for this test, waiting while another emulator's test holds it: its switches and counts are
     * one set for every emulator. Taken, it is reset: every switch off and the counts at nought, whatever a test before
     * left (one stopped by the run's watchdog, or that crashed, never ran its restore). The hold is short and kept up while
     * the test runs, so one whose process died lets go before the watchdog takes the next for hung. Given back by
     * [restore].
     */
    fun hold() {
        val until = System.nanoTime() + HOLD_WAIT_MS * 1_000_000
        while (true) {
            val (status, _) = ask("take?by=$by&ms=$HOLD_MS")
            if (status == 200) break
            check(status == 409 && System.nanoTime() < until) {
                "the fake Sportarr was held by another test for ${HOLD_WAIT_MS / 1000} s (or answered $status)"
            }
            Thread.sleep(250)
        }
        held = true
        control("reset")
        keeping = HoldKeeper(RENEW_MS) { ask("take?by=$by&ms=$HOLD_MS") }
    }

    /** The fake's anchor and now. */
    fun clock(): FakeClock {
        val clock = Json.parseToJsonElement(control("clock")).jsonObject
        return FakeClock(LocalDate.parse(clock.getValue("anchor").jsonPrimitive.content), Instant.parse(clock.getValue("now").jsonPrimitive.content))
    }

    /**
     * The catalogue at the fake's anchor: the fixtures' titles, keys and starts, and the releases' verdicts. The classes
     * read the days from its events, so they hold from the anchor's evening into the next day in the device's zone; past
     * a day either way the fixtures' days are too far from what the list shows, and the run says so at once.
     */
    val catalogue: SportsLibrary by lazy {
        val anchor = clock().anchor
        val today = LocalDate.now(ZoneId.systemDefault())
        check(abs(ChronoUnit.DAYS.between(anchor, today)) <= 1) {
            "the fake Sportarr's anchor is $anchor and the device's date $today: start mock-seed sports again with --anchor $today"
        }
        SportsCatalogAssets.load(anchor)
    }

    /**
     * Turns the fake's switch [name] on with [params] (`fail` with "status=500&route=events"), to be turned off again by
     * [restore], or sooner by [off].
     */
    fun switch(name: String, params: String = "") {
        check(held) { "a switch of the fake Sportarr changed without holding it" }
        control(if (params.isEmpty()) name else "$name?$params")
        undo += offPath(name, params)
    }

    /** Turns the switch [name] off, as [restore] would. */
    fun off(name: String, params: String = "") {
        val path = offPath(name, params)
        control(path)
        undo.remove(path)
    }

    /**
     * Sportarr's version as the fake's status answers it with the key: four parts, as a release build's. Read from the fake
     * rather than written here, so no test holds a dotted quad.
     */
    fun version(): String {
        val connection = URL("${SportsFakes.URL}/api/system/status").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("X-Api-Key", SportsFakes.KEY)
        try {
            check(connection.responseCode == 200) { "the fake Sportarr's status answered ${connection.responseCode}" }
            val status = Json.parseToJsonElement(connection.inputStream.use { it.readBytes().decodeToString() }).jsonObject
            return status.getValue("version").jsonPrimitive.content
        } finally {
            connection.disconnect()
        }
    }

    /** The fake's requests by route and page since its last reset, and those without the key or with it in the query. */
    fun counts(): JsonObject = Json.parseToJsonElement(control("counts")).jsonObject

    /**
     * The real plugin installed through the app's registry and running, with the fake Sportarr's address and key in its
     * secrets where [connected] (the setup's first screen where not). MainActivity's first creation is asked first, as
     * its first screen asks the plugins to load.
     */
    fun install(connected: Boolean = true): InstalledPlugin = runBlocking {
        container.plugins.firstActivity()
        val result = plugins.installSportarr()
        assertTrue("installed in the first slot: $result", (result as? InstallResult.Installed)?.slot == 1)
        if (connected) connect()
        plugins.app.await(SPORTARR, "running") { it.status is PluginStatus.Running }
    }

    /** The fake Sportarr's address and key in the plugin's secrets, as a Save stores them (L640). */
    suspend fun connect() {
        container.data.secrets.put(secret("url"), SportsFakes.URL)
        container.data.secrets.put(secret("key"), SportsFakes.KEY)
    }

    /** The sports indexer as the one indexer on, and where [plays], the mock as the one provider. */
    fun indexers(plays: Boolean) = runBlocking {
        indexed = true
        mock.setUp(plays)
    }

    /** [part]'s watch row, as the player saves it, [positionMs] into [durationMs], under [title]; put back by [restore]. */
    fun progress(part: String, title: String, positionMs: Long, durationMs: Long, release: RememberedRelease? = null) = runBlocking {
        val key = WatchKey.item(SPORTARR, part)
        mock.forget(key)
        rows.recordProgress(WatchState(key, WatchKind.Item, title = title, updatedAt = 0), positionMs, durationMs, release)
    }

    /** [part] marked watched, as Mark watched marks it, under [title]; put back by [restore]. */
    fun watched(part: String, title: String) = runBlocking {
        val key = WatchKey.item(SPORTARR, part)
        mock.forget(key)
        rows.markItems(listOf(ItemMark(key, title)), watched = true)
    }

    /** [part]'s watch row to be put back as it is now by [restore], as a test's play or mark makes or changes it. */
    fun forget(part: String) = runBlocking { mock.forget(WatchKey.item(SPORTARR, part)) }

    /** [part]'s watch row as the library has it now. */
    fun state(part: String): WatchState? = runBlocking { rows.state(WatchKey.item(SPORTARR, part)).first() }

    /**
     * The coordinator's next launch for [part], taken here so no player opens on its own (the test's activity isn't
     * MainActivity, which would open it); a failure as soon as the host's sheet shows one.
     */
    fun launchOf(part: String): PlayerLaunch = runBlocking {
        val key = WatchKey.item(SPORTARR, part)
        withTimeout(LAUNCH_WAIT_MS) {
            merge(
                container.playback.launches.filter { it.watchKey == key },
                container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("didn't start: $it") },
            ).first()
        }
    }

    /**
     * [launch] in the player (PlayerActivity, streaming from the mock), started from the test's activity [from] as the
     * app starts one, over the app's own screens, which come back as it closes: stopped three quarters in, past Up next's
     * minute and before watched, and closed. Then the row it saved for [part] as it closed, checked: a plugin's item, by
     * the launch's title, where it stopped, with the release and the file that played. Never through ActivityScenario,
     * whose launch clears the test's own task and the test's activity with it.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun playAndKeep(part: String, launch: PlayerLaunch, from: Activity): WatchState {
        val pressedAt = System.currentTimeMillis() - 60_000
        onMain { from.startActivity(PlayerActivity.intent(from, launch)) }
        val player = poll("the player in front") {
            onMain { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>().firstOrNull() }
        }
        val stoppedAt = try {
            val duration = poll("the player to be ready") {
                onMain { player.player?.takeIf { it.playbackState == Player.STATE_READY }?.duration?.takeIf { it > 0 } }
            }
            val target = duration * 3 / 4
            assertTrue("the sample clip runs $duration ms: too short to be in progress", target >= LibraryRepository.MIN_RESUME_MS)
            onMain {
                player.player!!.seekTo(target)
                player.player!!.pause()
            }
            poll("the player at ${target / 1000} s") { onMain { player.player?.currentPosition?.takeIf { abs(it - target) < 2_000 } } }
        } finally {
            if (!onMain { player.isDestroyed || player.isFinishing }) onMain { player.finish() }
            poll("the player to close") { onMain { player.isDestroyed.takeIf { it } } }
        }
        val saved = poll("the player's save as it closed") { state(part)?.takeIf { it.lastPlayedAt >= pressedAt } }
        assertEquals("kept as a plugin's item", WatchKind.Item, saved.kind)
        assertEquals("by its title", launch.watch.title, saved.title)
        assertTrue("where it stopped (${saved.positionMs} ms, $stoppedAt ms)", abs(saved.positionMs - stoppedAt) < 5_000)
        assertFalse("in progress, not watched", saved.watched)
        assertEquals("the release that played it", launch.release.nzbId, saved.release?.nzbId)
        assertEquals("its file", launch.release.fileName, saved.release?.fileName)
        return saved
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> poll(what: String, timeoutMs: Long = PLAY_WAIT_MS, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    /** The plugin's own folder for its files (L645): list.json and view.json. */
    val dataDir: File get() = File(File(plugins.root, SPORTARR), "data")

    /** What the test changed put back, every step tried whatever the others do. */
    fun restore() = runBlocking {
        everyStep(
            { undo.asReversed().toList().forEach { control(it) } },
            { undo.clear() },
            { mock.restore() },
            { keeping?.stop(STOP_WAIT_MS) },
            { keeping = null },
            { if (held) control("give-back?by=$by") },
            { held = false },
        )
    }

    /**
     * Before an action whose plugin asks the indexers anew (Play with no release played before, Choose release): the host
     * pauses a plugin's searches past ten a minute (its soft cap), and each such action asks up to three. So at most three
     * of them in any minute, across the run's classes, which share one process and one cap: one past that waits out the
     * oldest's minute.
     */
    fun paceSearches() = synchronized(SEARCHES) {
        val now = SystemClock.elapsedRealtime()
        SEARCHES.removeAll { now - it > CAP_WINDOW_MS }
        if (SEARCHES.size >= ACTIONS_A_MINUTE) SystemClock.sleep(CAP_WINDOW_MS - (now - SEARCHES.first()) + PACE_SLACK_MS)
        SEARCHES += SystemClock.elapsedRealtime()
    }

    private fun offPath(name: String, params: String): String {
        val id = params.split('&').firstOrNull { it.startsWith("id=") }
        return "$name?${listOfNotNull(id, "on=false").joinToString("&")}"
    }

    private fun secret(name: String) = "${PluginStore.SECRETS}$SPORTARR:$name"

    private fun control(path: String): String {
        val (status, body) = ask(path)
        check(status == 200) { "the fake Sportarr's control ${path.substringBefore('?')} answered $status" }
        return body
    }

    private fun ask(path: String): Pair<Int, String> {
        val connection = URL("${SportsFakes.URL}/control/$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        try {
            val status = connection.responseCode
            return status to ((if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** The sports indexer as the tests save it. */
        const val INDEXER_ID = "sportarr-device-test"
        const val INDEXER_NAME = "Sports (device tests)"

        /**
         * A test's hold of the fake, kept up every [RENEW_MS] while it runs: shorter than the sharded run's watchdog (four
         * minutes with no output), so the hold of a test whose process died runs out before the next waits that long.
         */
        private const val HOLD_MS = 2 * 60_000L
        private const val RENEW_MS = 30_000L

        /** How long [restore] waits for a renewal under way: an ask ends within its connect and read timeouts. */
        private const val STOP_WAIT_MS = 11_000L

        /** How long a test waits for another's turn at the fake. */
        private const val HOLD_WAIT_MS = 12 * 60_000L

        /** A play's searches of the sports indexer, its stages one after the other, and the NZB's fetch. */
        private const val LAUNCH_WAIT_MS = 90_000L

        /** The player's opening of a release on the mock, a seek in it, and its save as it closes. */
        private const val PLAY_WAIT_MS = 60_000L

        /** When the run's searching actions began, for [paceSearches]: the host's cap counts a minute's. */
        private val SEARCHES = ArrayDeque<Long>()
        private const val CAP_WINDOW_MS = 60_000L
        private const val ACTIONS_A_MINUTE = 3
        private const val PACE_SLACK_MS = 1_000L
    }
}

/**
 * Keeps a hold of the fake Sportarr up: [renew] every [everyMs] on a daemon thread of its own until [stop]. A renewal that
 * fails (the fake not answering for a moment) stays on this thread, as an exception left on any thread ends the whole
 * instrumentation process and every class after the test; the hold lapses instead, and the next renewal tries again.
 */
internal class HoldKeeper(private val everyMs: Long, private val renew: () -> Unit) {
    @Volatile
    private var stopped = false

    private val thread = thread(isDaemon = true, name = "fake-sportarr-hold") {
        while (!stopped) {
            try {
                Thread.sleep(everyMs)
            } catch (e: InterruptedException) {
                break // Given back.
            }
            if (stopped) break
            try {
                renew()
            } catch (e: Exception) {
                // Lapses unless the next renewal reaches the fake.
            }
        }
    }

    /** Whether its thread still runs. */
    val alive: Boolean get() = thread.isAlive

    /**
     * Stops the renewals, waiting up to [waitMs] for one under way: an interrupt doesn't end a request, and one landing
     * after the give-back would hold the fake for this test again.
     */
    fun stop(waitMs: Long) {
        stopped = true
        thread.interrupt()
        thread.join(waitMs)
    }
}

/**
 * Waits for the test's activity to be back in front after the player closed, and says whether its content is gone.
 * PlayerActivity's landscape lock turns a phone's screen, which can make the test's activity again as the screen turns
 * back, without the content the test set. Settled is one [activity] resumed in [orientation], the one it had before the
 * play, for a moment. A TV's screen doesn't turn.
 */
fun settledAfterThePlayer(activity: () -> ComponentActivity, orientation: Int): Boolean {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = SystemClock.elapsedRealtime() + SETTLE_WAIT_MS
    var seen: ComponentActivity? = null
    var since = SystemClock.elapsedRealtime()
    while (true) {
        // Between one making and the next there is no activity to read, or only a destroyed one.
        var saw = "no activity"
        val now = try {
            activity()
        } catch (e: Exception) {
            saw = "no activity: $e"
            null
        }
        var resumed = false
        var empty = false
        now?.let { a ->
            instrumentation.runOnMainSync {
                resumed = !a.isDestroyed && a.lifecycle.currentState == Lifecycle.State.RESUMED && a.resources.configuration.orientation == orientation
                empty = a.findViewById<ViewGroup>(android.R.id.content).childCount == 0
                saw = "${a.lifecycle.currentState}, orientation ${a.resources.configuration.orientation} (before $orientation), destroyed ${a.isDestroyed}, " +
                    "content ${if (empty) "gone" else "there"}, ${if (a === seen) "the same" else "another"} activity"
            }
        }
        if (!resumed || now !== seen) {
            seen = now
            since = SystemClock.elapsedRealtime()
        } else if (SystemClock.elapsedRealtime() - since >= SETTLED_MS) {
            return empty
        }
        check(SystemClock.elapsedRealtime() < deadline) { "the test's activity never settled in front after the player: last $saw" }
        Thread.sleep(100)
    }
}

/** How long the test's activity may take to come back after the player, and how long it stays the same to settle. */
private const val SETTLE_WAIT_MS = 30_000L
private const val SETTLED_MS = 1_500L

/**
 * Every text and description on [compose]'s screens, its sheets and menus too, that holds a canary value of the fake
 * Sportarr's (L653): its scores, statuses, images, venues and files. None is ever shown.
 */
fun ComposeTestRule.canaries(): List<String> {
    val out = mutableListOf<String>()
    fun walk(node: SemanticsNode) {
        val words = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text, node.config.getOrNull(SemanticsProperties.StateDescription))
        out += words.filter { it.contains(SportsFakes.CANARY, ignoreCase = true) || it.contains("canary.invalid") }
        node.children.forEach(::walk)
    }
    onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
    return out
}

/** The words on [compose]'s screens, each once, for a failure's message: what showed in place of what was waited for. */
fun ComposeTestRule.screenWords(max: Int = 600): String {
    val out = linkedSetOf<String>()
    fun walk(node: SemanticsNode) {
        out += node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        node.children.forEach(::walk)
    }
    onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
    return out.joinToString(" | ").take(max)
}
