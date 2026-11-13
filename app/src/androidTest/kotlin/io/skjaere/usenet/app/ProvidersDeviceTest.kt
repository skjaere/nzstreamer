package io.skjaere.usenet.app

import android.view.KeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.settings.NoUsableProvider
import io.skjaere.usenet.app.settings.ProvidersScreen
import io.skjaere.usenet.app.ui.AppColors
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.player.tv.DeviceMemory
import io.skjaere.usenet.player.tv.PlaybackBudgets
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Settings › Usenet providers over the real settings and the app's own provider sync: two
 * providers on the mock NNTP server under different names ("Mock A" first), moved, switched off,
 * deleted and added on the screen, each step saved and taken up by the engine in the new order.
 * The saved providers are hidden for the test (tombstoned, their secrets untouched) and put back
 * as they were afterwards; the test's own providers and their secrets are deleted.
 */
@RunWith(AndroidJUnit4::class)
class ProvidersDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private val settings = container.settings
    private val home = HomeContentShown()
    private val realSource = PlaybackCoordinator.nzbSource
    private lateinit var saved: AppSettings
    private lateinit var a: String
    private lateinit var b: String

    @Before
    fun setUp() = runBlocking<Unit> {
        fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        // A provider's Test asks for an article no server has; the mock answers such a STAT with 500
        // unless it is told the article is missing (mock server issue M1).
        assumeTrue("the mock NNTP server's control API isn't reachable at 10.0.2.2:8081", runCatching { statMissing(PROBE_ARTICLE) }.isSuccess)
        saved = settings.settings.first()
        // Hidden, not deleted: deleting would remove their passwords from the secret store.
        settings.restore(saved.copy(providers = saved.providers.map { it.copy(deleted = true) }))
        a = settings.saveProvider(mock("Mock A"), "")
        b = settings.saveProvider(mock("Mock B"), "")
        home.show()
    }

    @After
    fun restore() = runBlocking<Unit> {
        instrumentation.setInTouchMode(true)
        PlaybackCoordinator.nzbSource = realSource
        container.playback.cancel()
        home.restore()
        // Skipped before anything was changed (the mock isn't reachable).
        if (!::saved.isInitialized) return@runBlocking
        settings.providers.first().filter { p -> saved.providers.none { it.id == p.id } }.forEach { settings.deleteProvider(it.id) }
        settings.settings.first().providers.forEach { container.providerTests.forget(it.id) }
        settings.restore(saved)
        assertEquals(saved, settings.settings.first())
        // The app's own provider sync puts the engine back on the restored providers; let it before the next test.
        engineOnSavedProviders()
    }

    private fun mock(name: String) = ProviderRecord("", name, "10.0.2.2", 1119, useTls = false, connections = 2)

    /** Has the mock NNTP server answer STAT for [articleId] with 430, through its control API. */
    private fun statMissing(articleId: String) {
        val connection = java.net.URL("http://10.0.2.2:8081/mocks/stat").openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 2_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"articleId":"$articleId","exists":false}""".encodeToByteArray()) }
            check(connection.responseCode in 200..299) { "the mock answered ${connection.responseCode}" }
        } finally {
            connection.disconnect()
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun count(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().size

    private fun click(matcher: SemanticsMatcher) {
        compose.waitUntil(5_000) { exists(matcher) }
        compose.onNode(matcher).performClick()
    }

    /** The saved providers (names and switches) in order, as the list shows them. */
    private fun savedProviders() = runBlocking { settings.providers.first().map { it.name to it.enabled } }

    /** Waits until the providers are saved as [expected] and the engine has been given [engine] (ids), in that order. */
    private fun awaitSaved(expected: List<Pair<String, Boolean>>, engine: List<String>) {
        compose.waitUntil(5_000) { savedProviders() == expected }
        compose.waitUntil(10_000) { container.engine.configuredProviders.value == engine }
    }

    /** A field of the provider screen, by its label: they are in this order. */
    private fun field(label: String) =
        compose.onAllNodes(hasSetTextAction())[listOf("Name", "Host", "Port", "Username", "Password", "Connections").indexOf(label)]

    /**
     * The engine the app's provider sync builds holds the device's read-ahead cap. The other device
     * tests set the engine up themselves, without one, so none of them streams with it.
     */
    @Test
    fun theEngineTheAppBuildsHoldsTheDevicesReadAheadCap() = runBlocking<Unit> {
        engineSetup("the engine on the two mocks") { container.engine.configuredProviders.first { it == listOf(a, b) } }
        val cap = PlaybackBudgets.streamBytes(DeviceMemory.of(instrumentation.targetContext))
        assertEquals(cap, engineSetup("the streamer") { container.engine.streamer() }.getReadAheadBytes())
    }

    @Test
    fun providersAreMovedSwitchedOffDeletedAndAddedAndTheEngineFollowsEachStep() {
        compose.setContent { AppTheme { AppNav() } }
        click(hasContentDescription("Settings"))
        compose.waitUntil(5_000) { exists(hasText("2 providers · 4 connections")) }
        click(hasText("Usenet providers") and hasClickAction())
        compose.waitUntil(5_000) { exists(hasText("Articles are fetched from the first provider; missing ones are tried on the next.")) }
        awaitSaved(listOf("Mock A" to true, "Mock B" to true), engine = listOf(a, b))

        click(hasContentDescription("Move Mock B up"))
        awaitSaved(listOf("Mock B" to true, "Mock A" to true), engine = listOf(b, a))

        click(hasContentDescription("Use Mock A"))
        awaitSaved(listOf("Mock B" to true, "Mock A" to false), engine = listOf(b))
        compose.waitUntil(5_000) { exists(hasText("Off")) }

        click(hasText("Mock A") and hasClickAction())
        compose.waitUntil(5_000) { exists(hasText("Edit · Mock A")) }
        click(hasText("Delete provider") and hasClickAction())
        compose.waitUntil(5_000) { exists(hasText("Delete Mock A?")) }
        click(hasText("Delete") and hasClickAction())
        compose.waitUntil(5_000) { exists(hasText("Usenet providers")) && !exists(hasText("Mock A")) }
        awaitSaved(listOf("Mock B" to true), engine = listOf(b))
        runBlocking {
            assertTrue("no tombstone for Mock A", settings.settings.first().providers.single { it.id == a }.deleted)
            assertNull("Mock A's password is still stored", container.data.secrets.get(ProviderRecord(a, "", "").passwordSecret))
        }

        click(hasText("Add") and hasClickAction())
        compose.waitUntil(5_000) { exists(hasText("Add provider")) }
        field("Name").performTextReplacement("Mock C")
        // A login typed into the host is turned away, saying why: it would reach the engine, and the logs, with the host.
        field("Host").performTextReplacement("alice:pw@10.0.2.2")
        val loginInHost = hasText("Only the server's name: the username and password go in their own fields.")
        compose.waitUntil(5_000) { exists(loginInHost) }
        compose.onNode(hasText("Save") and hasClickAction()).assertIsNotEnabled()
        field("Host").performTextReplacement("10.0.2.2")
        compose.waitUntil(5_000) { !exists(loginInHost) }
        click(hasText("Use TLS"))
        field("Port").performTextReplacement("1119")
        field("Connections").performTextReplacement("2")
        click(hasText("Save") and hasClickAction())
        compose.waitUntil(5_000) { exists(hasText("Mock C")) && exists(hasText("Articles are fetched from the first provider; missing ones are tried on the next.")) }
        val c = runBlocking { settings.providers.first().last().id }
        awaitSaved(listOf("Mock B" to true, "Mock C" to true), engine = listOf(b, c))
        compose.waitUntil(5_000) { exists(hasText("10.0.2.2:1119 · 2 connections")) }

        click(hasText("Test all") and hasClickAction())
        compose.waitUntil(30_000) { count(hasText("Connected")) == 2 }
    }

    private fun SemanticsNode.words(): String =
        (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString(" | ")

    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().singleOrNull()

    private fun focused(): String = focusedNode()?.words() ?: "(nothing)"

    private fun press(key: Int): String {
        instrumentation.sendKeyDownUpSync(key)
        compose.waitForIdle()
        return focused()
    }

    @Test
    fun aRemoteReachesEveryRowSwitchAndMoveAndMovesARowAgainWithOk() {
        instrumentation.setInTouchMode(false)
        compose.setContent { AppTheme { ProvidersScreen(onBack = {}, onEdit = {}) } }
        compose.waitUntil(5_000) { exists(hasText("Mock B")) }
        val seen = mutableListOf<String>()
        // Along the focused line to its end, and back to where it started.
        fun walkRight() {
            var moved = 0
            repeat(3) {
                val before = focused()
                val after = press(KeyEvent.KEYCODE_DPAD_RIGHT)
                if (after != before) {
                    seen += after
                    moved++
                }
            }
            repeat(moved) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
        }
        // In non-touch mode the window can focus the first control (Back) on its own before the
        // first key: start from there, or the first DOWN skips the top bar.
        compose.waitForIdle()
        if (focusedNode() != null) {
            seen += focused()
            walkRight()
        }
        // Down the screen, along the top bar and each row.
        repeat(6) {
            seen += press(KeyEvent.KEYCODE_DPAD_DOWN)
            walkRight()
        }
        listOf("Back", "Add", "Mock A", "Use Mock A", "Move Mock A down", "Mock B", "Use Mock B", "Move Mock B up", "Test all").forEach { words ->
            assertTrue("never focused: $words; focus went ${seen.joinToString(" → ")}", seen.any { words in it })
        }

        // A third one, to move twice: the focus stays on the arrow pressed while it is there, so OK
        // moves it again (S9-2), and goes to the other arrow once there is no moving further.
        runBlocking { settings.saveProvider(mock("Mock C"), "") }
        compose.waitUntil(5_000) { exists(hasContentDescription("Move Mock C up")) }
        compose.onNode(hasContentDescription("Move Mock C up")).requestFocus()
        compose.waitUntil(5_000) { "Move Mock C up" in focused() }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { savedProviders().map { it.first } == listOf("Mock A", "Mock C", "Mock B") }
        compose.waitForIdle()
        assertEquals("Move Mock C up", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { savedProviders().map { it.first } == listOf("Mock C", "Mock A", "Mock B") }
        compose.waitUntil(5_000) { focused() == "Move Mock C down" }
        // The row is a live region: TalkBack reads it out in its new place.
        val row = compose.onNode(hasText("Mock C") and hasClickAction()).fetchSemanticsNode()
        assertEquals("Position 1 of 3", row.config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(LiveRegionMode.Polite, row.config.getOrNull(SemanticsProperties.LiveRegion))
    }

    /** Focuses [matcher]'s node, as the remote would have. */
    private fun focus(matcher: SemanticsMatcher) {
        compose.waitUntil(5_000) { exists(matcher) }
        compose.onNode(matcher).requestFocus()
        compose.waitUntil(5_000) { isFocused(matcher) }
    }

    private fun isFocused(matcher: SemanticsMatcher) = exists(matcher and isFocused())

    /** A provider's row (not its switch or arrows), by its name. */
    private fun row(name: String) = hasText(name) and hasClickAction()

    /**
     * The remote keeps its place (S9-11): Test all keeps the focus while it runs and after, and
     * when the list shows again after a provider's screen, the focus is back on that provider's row
     * (left with Back or saved), on the one in its place once it is deleted, on the one just added,
     * or on Add when nothing was added.
     */
    @Test
    fun aRemoteKeepsItsPlaceAfterTestAllAndAfterAProvidersScreen() {
        instrumentation.setInTouchMode(false)
        compose.setContent { AppTheme { AppNav() } }
        click(hasContentDescription("Settings"))
        click(hasText("Usenet providers") and hasClickAction())
        compose.waitUntil(5_000) { exists(row("Mock B")) }
        instrumentation.setInTouchMode(false)

        focus(hasText("Test all") and hasClickAction())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(30_000) { count(hasText("Connected")) == 2 }
        assertEquals("Test all", focused())

        focus(row("Mock B"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Edit · Mock B")) }
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { isFocused(row("Mock B")) }

        focus(row("Mock A"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Edit · Mock A")) }
        focus(hasText("Save") and hasClickAction())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { isFocused(row("Mock A")) }

        // Mock A, first, is deleted: Mock B takes its place, and the focus.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Edit · Mock A")) }
        focus(hasText("Delete provider") and hasClickAction())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        focus(hasText("Delete") and hasClickAction())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { !exists(row("Mock A")) && isFocused(row("Mock B")) }

        val add = hasText("Add") and hasClickAction()
        focus(add)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Add provider")) }
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { exists(row("Mock B")) && isFocused(add) }

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Add provider")) }
        field("Name").performTextReplacement("Mock C")
        field("Host").performTextReplacement("10.0.2.2")
        focus(hasText("Save") and hasClickAction())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { isFocused(row("Mock C")) }
    }

    /** How many pixels on screen, in and just around [matcher]'s node, are [color]. */
    private fun pixelsAround(matcher: SemanticsMatcher, color: Color): Int {
        compose.waitForIdle()
        val bounds = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot
        val margin = with(compose.density) { 6.dp.toPx() }
        val image = compose.onRoot().captureToImage().toPixelMap()
        val xs = (bounds.left - margin).toInt().coerceAtLeast(0) until (bounds.right + margin).toInt().coerceAtMost(image.width)
        val ys = (bounds.top - margin).toInt().coerceAtLeast(0) until (bounds.bottom + margin).toInt().coerceAtMost(image.height)
        fun same(a: Color) = abs(a.red - color.red) < 0.03f && abs(a.green - color.green) < 0.03f && abs(a.blue - color.blue) < 0.03f
        return xs.sumOf { x -> ys.count { y -> same(image[x, y]) } }
    }

    /**
     * Every control of the list shows the remote's focus as its rows and arrows do, in the accent
     * outline (S9-12), not only in Material's faint state layer: the switch (one switched off, so
     * that its track isn't in the accent), Add and Test all. A move arrow, outlined already, shows
     * that the outline is seen.
     */
    @Test
    fun theSwitchAddAndTestAllShowTheFocusInTheAccentOutline() {
        runBlocking { settings.setProviderEnabled(b, false) }
        instrumentation.setInTouchMode(false)
        compose.setContent { AppTheme { ProvidersScreen(onBack = {}, onEdit = {}) } }
        compose.waitUntil(5_000) { exists(hasText("Off")) }
        val accent = AppColors().accent
        val seen = listOf(
            "Move Mock B up" to hasContentDescription("Move Mock B up"), "Add" to (hasText("Add") and hasClickAction()),
            "Use Mock B" to hasContentDescription("Use Mock B"), "Test all" to (hasText("Test all") and hasClickAction()),
        ).map { (name, control) ->
            val edge = compose.onNode(control).fetchSemanticsNode().boundsInRoot.height.toInt()
            val unfocused = pixelsAround(control, accent)
            focus(control)
            Triple(name, unfocused to pixelsAround(control, accent), edge)
        }
        // An outline has more of them than the control is tall; none shows unfocused.
        val missing = seen.filterNot { (_, counts, edge) -> counts.first < edge && counts.second > edge }
        assertTrue(
            "accent pixels around each control, unfocused and focused: " + seen.joinToString { (name, counts, edge) -> "$name ${counts.first} → ${counts.second} (over $edge)" },
            missing.isEmpty(),
        )
    }

    /**
     * The move arrows are 48 dp targets, as the app's other buttons are (S9-13): on a row in the
     * middle, one above the other, each has its own 48 dp, none of it shared with the other.
     */
    @Test
    fun theMoveArrowsAre48dpTargetsThatDoNotOverlap() {
        runBlocking { settings.saveProvider(mock("Mock C"), "") }
        compose.setContent { AppTheme { ProvidersScreen(onBack = {}, onEdit = {}) } }
        compose.waitUntil(5_000) { exists(hasContentDescription("Move Mock B up")) && exists(hasContentDescription("Move Mock B down")) }
        val (up, down) = listOf("up", "down").map { compose.onNode(hasContentDescription("Move Mock B $it")).fetchSemanticsNode().touchBoundsInRoot }
        val min = with(compose.density) { 48.dp.toPx() } - 1
        listOf("up" to up, "down" to down).forEach { (way, target) ->
            assertTrue("Move $way's target is ${target.width} × ${target.height} px, under 48 dp ($min px)", target.width >= min && target.height >= min)
        }
        assertTrue("the targets overlap: Move up's ends at ${up.bottom} px, Move down's starts at ${down.top} px", up.bottom <= down.top + 1)
    }

    /** Import with every provider switched off says so at once, and its button opens the providers list (#159). */
    @Test
    fun importWithEveryProviderSwitchedOffSaysSoAndOpensTheList() {
        runBlocking {
            settings.setProviderEnabled(a, false)
            settings.setProviderEnabled(b, false)
        }
        val nzb = instrumentation.context.assets.open("chapters.nzb").use { it.readBytes() }
        val id = container.nzbHandoff.put(Result.success(nzb to "Lantern Field"))
        compose.setContent { AppTheme { AppNav(openedNzbs = flowOf(id)) } }
        compose.waitUntil(5_000) { exists(hasText("Every Usenet provider is switched off. Lantern Field opens as soon as one is switched on.")) }
        click(hasText("Open Usenet providers") and hasClickAction())
        compose.waitUntil(5_000) { exists(hasText("Articles are fetched from the first provider; missing ones are tried on the next.")) }
        assertEquals(2, count(hasText("Off")))

        // Switched on again: back on Import, the file opens.
        click(hasContentDescription("Use Mock A"))
        click(hasContentDescription("Back"))
        compose.waitUntil(30_000) { exists(hasText("chapters.mkv")) }
    }

    /**
     * Play with every provider switched off says so at once, as Import does, without grabbing a
     * release's NZB, and its button opens the providers list (S9-5).
     */
    @Test
    fun aPlayWithEveryProviderSwitchedOffSaysSoWithoutAGrabAndOpensTheList() {
        runBlocking {
            settings.setProviderEnabled(a, false)
            settings.setProviderEnabled(b, false)
        }
        val grabs = AtomicInteger()
        PlaybackCoordinator.nzbSource = { _, _ -> NzbSource { grabs.incrementAndGet(); throw FetchException("Not to be fetched") } }
        compose.setContent { AppTheme { AppNav() } }
        val title = "Lantern.Field.2026.1080p.WEB.h264-GRP"
        container.playback.play(PlayRequest("Lantern Field", "2026", listOf(candidate(title)), FilePicker.Largest, WatchTarget(WatchKind.Release, title)))
        compose.waitUntil(10_000) { container.playback.state.value is PlayState.Failed }
        assertEquals(NoUsableProvider.AllOff, (container.playback.state.value as PlayState.Failed).noProvider)
        assertEquals("no release's NZB may be fetched", 0, grabs.get())
        compose.waitUntil(5_000) { exists(hasText("Couldn't start playback. Every Usenet provider is switched off.")) }
        click(hasText(NoUsableProvider.ACTION) and hasClickAction())
        compose.waitUntil(5_000) { exists(hasText("Articles are fetched from the first provider; missing ones are tried on the next.")) }
        assertEquals(PlayState.Idle, container.playback.state.value)
    }

    private fun candidate(title: String) = RankedItem(
        NewznabItem(
            indexerId = "providers-device-test", guid = "lantern-field", title = title, sizeBytes = 690_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("providers-device-test/lantern-field", title, 690_000), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
    )

    private companion object {
        /** What UsenetEngine.testProvider STATs after logging in. */
        const val PROBE_ARTICLE = "<provider-test@usenet-streamer.invalid>"
    }
}
