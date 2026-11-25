package io.skjaere.usenet.app.plugins.sportarr

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.app.plugins.InstallRequest
import io.skjaere.usenet.app.plugins.InstallResult
import io.skjaere.usenet.app.plugins.InstalledPlugin
import io.skjaere.usenet.app.plugins.PluginDevSecrets
import io.skjaere.usenet.app.plugins.PluginMockSetup
import io.skjaere.usenet.app.plugins.PluginStateRule
import io.skjaere.usenet.app.plugins.PluginStatus
import io.skjaere.usenet.app.plugins.PluginStore
import io.skjaere.usenet.app.plugins.Sha256
import io.skjaere.usenet.app.plugins.await
import io.skjaere.usenet.data.ItemMark
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The Sportarr plugin's id, as its builds carry it (L639). */
const val SPORTARR = "sportarr"

/** Its entry class, made by its own loader. */
const val SPORTARR_ENTRY = "app.nzstreamer.plugin.sportarr.SportarrPlugin"

/**
 * The Sportarr plugin's debug build in the test APK's assets (L657): sportarr.apk, version 0.1.0 (versionCode 10000, its
 * version.txt's by the plugin convention), signed as the app under test is, so a debug run trusts it through the test
 * signer (L622).
 */
object SportarrBuild {
    const val ASSET = "sportarr.apk"
    const val NAME = "Sportarr"
    const val VERSION_CODE = 10_000L

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
 * catalogue at the fake's anchor ([catalogue]); and watch rows of the plugin's parts written for a test ([progress],
 * [watched]). [restore] puts back what the test changed, whatever failed: the switches off, the indexer and the provider
 * as they were, the watch rows as they were, the fake given back. The plugin itself, its files and its secrets go with
 * the rule. [assumeUp] skips a test whose fakes aren't up.
 */
class SportarrDevice(private val plugins: PluginStateRule, owner: String) {
    private val container = plugins.context.container
    private val rows = container.library
    private val by = "$owner-${UUID.randomUUID()}"
    private val undo = mutableListOf<String>()
    private var held = false
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
     * one set for every emulator. Given back by [restore]; the fake lets go by itself of a hold past its time.
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
    }

    /** The fake's anchor and now. */
    fun clock(): FakeClock {
        val clock = Json.parseToJsonElement(control("clock")).jsonObject
        return FakeClock(LocalDate.parse(clock.getValue("anchor").jsonPrimitive.content), Instant.parse(clock.getValue("now").jsonPrimitive.content))
    }

    /** The catalogue at the fake's anchor: the fixtures' titles, keys and starts, and the releases' verdicts. */
    val catalogue: SportsLibrary by lazy { SportsCatalogAssets.load(clock().anchor) }

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

    /** The plugin's own folder for its files (L645): list.json and view.json. */
    val dataDir: File get() = File(File(plugins.root, SPORTARR), "data")

    /** What the test changed put back, every step tried whatever the others do. */
    fun restore() = runBlocking {
        everyStep(
            { undo.asReversed().toList().forEach { control(it) } },
            { undo.clear() },
            { mock.restore() },
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

        /** A test's hold of the fake: longer than any one test, shorter than a run of them. */
        private const val HOLD_MS = 10 * 60_000L

        /** How long a test waits for another's turn at the fake. */
        private const val HOLD_WAIT_MS = 12 * 60_000L

        /** When the run's searching actions began, for [paceSearches]: the host's cap counts a minute's. */
        private val SEARCHES = ArrayDeque<Long>()
        private const val CAP_WINDOW_MS = 60_000L
        private const val ACTIONS_A_MINUTE = 3
        private const val PACE_SLACK_MS = 1_000L
    }
}

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
