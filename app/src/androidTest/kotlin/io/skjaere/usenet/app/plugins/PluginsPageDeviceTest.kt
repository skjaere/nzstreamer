package io.skjaere.usenet.app.plugins

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.pluginTabsFollow
import io.skjaere.usenet.app.plugins.PluginListServer.Listed
import io.skjaere.usenet.app.settings.PluginsPage
import io.skjaere.usenet.app.settings.PluginsScreen
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Settings › Plugins on a phone (W157, L620, L621, L633), the app's own registry, list and installer behind it, the
 * skeleton's real builds listed and served by a list of the test's own on this device's loopback address
 * ([PluginListServer], through PluginSource's test source): the hub row and its lines; Install through Downloading and
 * Checking to Installed; Cancel mid-download keeping nothing; each refusal's words and Try again; Remove's question and
 * what goes; the offline line with the list kept; and the page at 1.3 times the text size. A card's Settings and Open
 * come with the plugin slots' pages, which this page doesn't have yet.
 */
@RunWith(AndroidJUnit4::class)
class PluginsPageDeviceTest {
    @get:Rule(order = 0)
    val plugins = PluginStateRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context = plugins.context
    private val container = context.container
    private val server = PluginListServer()
    private val home = HomeContentShown()
    // Unconfined: a state's collector runs as it is set, so a short one (Checking) is seen too.
    private val watching = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Where a new plugin's tab goes in this build: after Discover where the build has it, else after Home. */
    private val after = pluginTabsFollow(FormFactor.Phone)
    private val installedLine = "Installed. Skeleton is now a tab after $after."

    @Before
    fun serveTheList() {
        PluginSource.override = server.source
        // As MainActivity's first creation does: the plugins' code loads from now on, as an install's does.
        container.plugins.firstActivity()
    }

    @After
    fun putBackTheSource() {
        // The runner's source for the whole run: an address that answers nothing (L622).
        PluginSource.override = PluginSource.CLOSED
        watching.cancel()
        server.close()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = PLUGIN_WAIT_MS) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}; the registry lists ${describe(container.plugins.plugins.value)}", e)
        }
    }

    private fun button(words: String) = hasText(words) and hasClickAction()

    private fun press(words: String) {
        waitFor(button(words))
        compose.onNode(button(words)).performScrollTo().performClick()
    }

    /** A button of Remove's question, whose window shows over the page's. */
    private fun answer(words: String) {
        waitFor(button(words))
        compose.onAllNodes(button(words)).onLast().performClick()
    }

    /** The page alone, at [fontScale], over the app's own registry, list and installer, as Settings opens it. */
    private fun showThePage(fontScale: Float = 1f) {
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = fontScale })
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(scaled)) { AppTheme { PluginsScreen(focus = null, onBack = {}) } } }
    }

    /** Check again, and the list it read shown: its line no longer "Checking…". */
    private fun checkAgain() {
        val before = container.pluginList.state.value.readAt to container.pluginList.state.value.failed
        press(PluginsPage.CHECK_AGAIN)
        compose.waitUntil(PLUGIN_WAIT_MS) { container.pluginList.state.value.let { !it.checking && (it.readAt to it.failed) != before } }
        waitFor(button(PluginsPage.CHECK_AGAIN))
    }

    @Test
    fun theHubRowSaysNoneYetThenThePluginsNameThenWhyItIsOff() {
        server.list(Listed(SkeletonBuild.V1, "1.0.0"))
        home.show()
        try {
            compose.setContent { AppTheme { AppNav() } }
            waitFor(hasContentDescription("Settings"))
            compose.onNode(hasContentDescription("Settings")).performClick()
            val row = hasText("Plugins") and hasClickAction()
            compose.onNode(row).performScrollTo()
            waitFor(row and hasText("None yet"))
            fun top(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().positionInRoot.y
            assertTrue("first in App, above Logs", top(row) < top(hasText("Logs") and hasClickAction()) && top(hasText("APP")) < top(row))

            compose.onNode(row).performClick()
            waitFor(hasText(PluginsPage.INTRO))
            press("Install")
            waitFor(hasText(installedLine))
            compose.onNode(hasContentDescription("Back")).performClick()
            waitFor(row and hasText("Skeleton"))

            // The list withdraws the version: the row says so, with its dot.
            server.list(Listed(SkeletonBuild.V1, "1.0.0"), withdrawn = setOf(1))
            compose.onNode(row).performScrollTo().performClick()
            waitFor(hasText(PluginsPage.INTRO))
            checkAgain()
            waitFor(hasText("Version 1 was withdrawn, so it's off until a fixed version is out."))
            compose.onNode(hasContentDescription("Back")).performClick()
            waitFor(row and hasText("Skeleton · withdrawn, off"))
        } finally {
            home.restore()
        }
    }

    @Test
    fun installGoesThroughDownloadingAndCheckingToInstalled() {
        val seen = CopyOnWriteArrayList<String>()
        watching.launch { container.pluginInstaller.states.collect { states -> states[SKELETON]?.let { seen += it.javaClass.simpleName } } }
        server.list(Listed(SkeletonBuild.V1, "1.0.0"))
        server.holdDownloads()
        showThePage()
        waitFor(hasText("Version 1 · ", substring = true) and hasText("MB", substring = true))
        waitFor(hasText("The plugin platform's own example, with invented words."))
        press("Install")
        // Held half-way: Downloading… over its bar, Cancel in Install's place.
        waitFor(hasText(PluginsPage.DOWNLOADING))
        waitFor(button("Cancel"))
        assertFalse("Install gone", exists(button("Install")))
        compose.waitUntil(PLUGIN_WAIT_MS) { (container.pluginInstaller.states.value[SKELETON] as? InstallState.Downloading)?.percent in 40..60 }

        server.release()
        waitFor(hasText(installedLine))
        compose.waitUntil(PLUGIN_WAIT_MS) { "Installed" in seen }
        assertEquals("downloading, checked, installed", listOf("Downloading", "Checking", "Installed"), seen.distinct())
        assertTrue("its tab's Remove", exists(button("Remove")))
        assertFalse("Open Skeleton comes with the plugin slots' pages", exists(button("Open Skeleton")))
        assertFalse("Settings likewise", exists(button("Settings")))
        runBlocking { plugins.app.await(SKELETON, "running") { it.status is PluginStatus.Running } }
    }

    @Test
    fun cancelMidDownloadKeepsNothing() {
        server.list(Listed(SkeletonBuild.V1, "1.0.0"))
        server.holdDownloads()
        showThePage()
        press("Install")
        waitFor(hasText(PluginsPage.DOWNLOADING))
        press("Cancel")
        waitFor(button("Install"))
        val downloads = File(File(context.cacheDir, PluginStore.DIR), PluginStore.DOWNLOADS)
        compose.waitUntil(PLUGIN_WAIT_MS) { downloads.listFiles().isNullOrEmpty() }
        assertTrue("no plugin", container.plugins.plugins.value.orEmpty().isEmpty())
        assertFalse("nothing stored", File(plugins.root, SKELETON).exists())
        assertNull("no line left", container.pluginInstaller.states.value[SKELETON])
        server.release()
    }

    @Test
    fun eachRefusalSaysWhyKeepsNothingAndTryAgainInstalls() {
        server.list(Listed(SkeletonBuild.Foreign, "2.0.0"))
        showThePage()
        press("Install")
        waitFor(hasText("It isn't signed by NZStreamer, so nothing was installed."))
        waitFor(button("Try again"))
        assertTrue("nothing kept", container.plugins.plugins.value.orEmpty().isEmpty() && !File(plugins.root, SKELETON).exists())

        // The list's checksum isn't the file's.
        server.list(Listed(SkeletonBuild.V1, "1.0.0", sha256 = "0".repeat(64)))
        checkAgain()
        press("Try again")
        waitFor(hasText("The file doesn't match the list, so nothing was installed."))

        // Listed as made for this NZStreamer, the file stamped for the next plugin API.
        server.list(Listed(SkeletonBuild.Newer, "1.0.0", api = "1.0"))
        checkAgain()
        press("Try again")
        waitFor(hasText("It was made for another NZStreamer version, so nothing was installed."))

        // The file isn't there.
        server.list(Listed(SkeletonBuild.V1, "1.0.0", served = false))
        checkAgain()
        press("Try again")
        waitFor(hasText("Couldn't download it. Check your connection."))
        assertTrue("nothing kept", container.plugins.plugins.value.orEmpty().isEmpty() && !File(plugins.root, SKELETON).exists())

        server.list(Listed(SkeletonBuild.V1, "1.0.0"))
        press("Try again")
        waitFor(hasText(installedLine))
    }

    @Test
    fun removeAsksFirstThenTakesTheTabItsFilesAndItsKeys() {
        server.list(Listed(SkeletonBuild.V1, "1.0.0"))
        showThePage()
        press("Install")
        waitFor(hasText(installedLine))
        val instance = runBlocking { plugins.app.await(SKELETON, "running") { it.status is PluginStatus.Running } }.status as PluginStatus.Running
        runBlocking { instance.instance.host.secrets.put("key", "kestrel-test-key") }

        press("Remove")
        waitFor(hasText("Remove Skeleton?"))
        assertTrue(
            exists(hasText("The Skeleton tab goes, with its settings and saved keys. What you watched stays marked, and Up next keeps what you were watching.")),
        )
        answer("Cancel")
        compose.waitUntil(5_000) { !exists(hasText("Remove Skeleton?")) }
        assertEquals("kept", listOf(SKELETON), container.plugins.plugins.value.orEmpty().map { it.id })

        press("Remove")
        waitFor(hasText("Remove Skeleton?"))
        answer("Remove")
        waitFor(button("Install"))
        assertTrue("no plugin", container.plugins.plugins.value.orEmpty().isEmpty())
        assertFalse("its code and data gone", File(plugins.root, SKELETON).exists())
        assertFalse("its cache gone", File(File(context.cacheDir, PluginStore.DIR), SKELETON).exists())
        assertNull("its key gone", runBlocking { container.data.secrets.get("${PluginStore.SECRETS}$SKELETON:key") })
        assertTrue("the version line again", exists(hasText("Version 1 · ", substring = true)))
    }

    @Test
    fun offlineTheListKeptShowsWithItsAge() {
        server.list(Listed(SkeletonBuild.V1, "1.0.0"))
        showThePage()
        waitFor(button("Install"))
        waitFor(hasText("From NZStreamer's plugin list on GitHub · checked today, ", substring = true))
        server.close()
        checkAgain()
        waitFor(hasText("Couldn't reach GitHub. This list is from today."))
        assertTrue("the list kept", exists(button("Install")) && exists(hasText("Skeleton")))
    }

    /** At 1.3 times the text size, a card, the offline line and the source line are whole, on screen and never cut. */
    @Test
    fun thePageAtLargeTextIsWhole() {
        server.list(Listed(SkeletonBuild.V1, "1.0.0"))
        showThePage(fontScale = 1.3f)
        waitFor(button("Install"))
        server.close()
        checkAgain()
        waitFor(hasText("Couldn't reach GitHub. This list is from today."))
        val root = compose.onAllNodes(isRoot()).fetchSemanticsNodes().first()
        val problems = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            val words = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            if (words != null) {
                val layout = node.textLayout()
                // Laid out taller than the room it was given, so its last lines don't show, or ended in an ellipsis.
                val clipped = layout != null && layout.size.height < layout.multiParagraph.height - 1
                val ellipsized = layout != null && layout.lineCount > 0 && layout.isLineEllipsized(layout.lineCount - 1)
                if (clipped || ellipsized) problems += "\"$words\" cut"
                if (node.boundsInRoot.right > root.boundsInRoot.right + 1 || node.boundsInRoot.left < root.boundsInRoot.left - 1) problems += "\"$words\" off the screen's side"
            }
            node.children.forEach(::walk)
        }
        walk(compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().first())
        assertEquals("at 1.3x", emptyList<String>(), problems)
    }

    private fun SemanticsNode.textLayout(): TextLayoutResult? {
        if (!config.contains(SemanticsActions.GetTextLayoutResult)) return null
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.singleOrNull()
    }
}
