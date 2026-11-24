package io.skjaere.usenet.app.plugins

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.ui.AppTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The crash the guard is for, on a device (L585, L619, L595): the real skeleton v1, installed and loaded from the app's
 * own storage, draws its crash page, which throws as it is first composed; Compose can't catch it, and in the app it
 * would end the process. Here the page is drawn by a recomposer of the test's own, whose failure the test takes, as the
 * app's crash log would on the crashing thread, and gives to the registry's crash guard. crash.json then names skeleton
 * v1, by the package of the frames that threw; and a fresh registry over the same files, as the next start's, keeps that
 * version off ("Turned off after a crash") without loading any of its code, and its slot shows the host's placeholder
 * (L590): the tab's label as its title, why it is off, what is kept, and Open Plugins.
 */
@RunWith(AndroidJUnit4::class)
class PluginCrashDeviceTest {
    private val plugins = PluginStateRule()
    private val compose = createComposeRule()

    /** The plugins' rule around the compose rule: everything removed once the page's activity has closed. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(plugins).around(compose)

    @Test
    fun theSkeletonsCrashPageIsBlamedOnItsVersionWhichIsOffAtTheNextStart() {
        val crashed = plugins.registry().apply { start(asked = false) }
        val instance = runBlocking {
            assertTrue("v1 installed", crashed.install(SkeletonBuild.V1, SkeletonBuild.V1.download(plugins.context)) is InstallResult.Installed)
            crashed.firstScreen()
            crashed.await(SKELETON, "running") { it.status is PluginStatus.Running }
            crashed.running(SKELETON)
        }

        // The page is drawn by a recomposer of the test's own, in a view of its own, so the failure ends that recomposer
        // alone and the test's composition goes on to draw the next start's slot. It joins a composition already drawn, so
        // it throws in a recomposition, which ends the recomposer's run with it, rather than in the view's first
        // composition, which the next layout would only try again.
        val thrownBy = CompletableDeferred<Throwable>()
        val pageScope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob() + CoroutineExceptionHandler { _, e -> thrownBy.complete(e) })
        val pageRecomposer = Recomposer(pageScope.coroutineContext)
        pageScope.launch { pageRecomposer.runRecomposeAndApplyChanges() }
        val crashPage = mutableStateOf(false)
        val slotRegistry = mutableStateOf<PluginRegistry?>(null)
        compose.setContent {
            Column {
                AndroidView(factory = { context ->
                    ComposeView(context).apply {
                        setParentCompositionContext(pageRecomposer)
                        setContent { if (crashPage.value) instance.plugin.Page(SKELETON_CRASH_PAGE, null, instance.host, NoNav) }
                    }
                })
                // The next start's slot, as the app's graph has it.
                slotRegistry.value?.let { registry ->
                    AppTheme {
                        val nav = rememberNavController()
                        NavHost(nav, startDestination = PluginTab1) { pluginScreens(nav, registry) }
                    }
                }
            }
        }
        compose.runOnUiThread { crashPage.value = true }
        val thrown = try {
            runBlocking { withTimeoutOrNull(PLUGIN_WAIT_MS) { thrownBy.await() } }
        } finally {
            pageRecomposer.cancel()
            pageScope.cancel()
        }
        assertNotNull("the crash page threw as it was first drawn", thrown)
        val pages = everyThrowable(thrown!!).filter { it is IllegalStateException && it.message == SKELETON_CRASH }
        assertEquals("the page's own exception: ${thrown.javaClass.name}", 1, pages.size)
        assertTrue("thrown by the skeleton's code", pages.single().stackTrace.any { it.className.startsWith(PluginIds.packageOf(SKELETON) + ".") })

        crashed.registry.crashGuard.blame(thrown)
        assertEquals("crash.json names skeleton v1", listOf(Named(SKELETON, 1, error = false)), crashJson(plugins.root))
        // That process is gone; the next start reads the same files.
        crashed.close()

        val next = plugins.registry().apply { start() }
        val off = runBlocking { next.await(SKELETON, "turned off after a crash") { it.status == PluginStatus.OffAfterCrash } }
        assertEquals("none of its code loaded", 0, next.loader.classLoadersMade)
        assertEquals("the registry's file keeps it off", StoredState.Crashed, next.store.readRegistry().plugins.single().state)
        assertNull("the guard's record applied", crashJson(plugins.root))

        // Its slot: the placeholder, by the tab's label the skeleton last gave and its list name, with Open Plugins.
        assertEquals("the skeleton's slot", 1, off.slot)
        assertEquals("the label it gave as it ran", "Skeleton", off.label)
        compose.runOnUiThread { slotRegistry.value = next.registry }
        compose.waitUntil(PLUGIN_WAIT_MS) { compose.onAllNodes(hasText(PluginPlaceholder.OPEN_PLUGINS)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Skeleton") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("Skeleton is turned off").assertIsDisplayed()
        compose.onNodeWithText("The Skeleton plugin was turned off after it crashed NZStreamer. What you watched is kept.").assertIsDisplayed()
        compose.onNodeWithText(PluginPlaceholder.OPEN_PLUGINS).assertIsDisplayed().assertHasClickAction()
    }
}
