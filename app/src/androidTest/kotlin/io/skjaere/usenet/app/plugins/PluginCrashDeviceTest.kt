package io.skjaere.usenet.app.plugins

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
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
 * would end the process. Here the compose rule's recomposer hands it to the test, which gives it to the registry's crash
 * guard as the app's crash log would on the crashing thread. crash.json then names skeleton v1, by the package of the
 * frames that threw; and a fresh registry over the same files, as the next start's, keeps that version off ("Turned off
 * after a crash") without loading any of its code.
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

        // The page joins a composition that is already drawn, so it throws in a recomposition, which the rule's recomposer
        // hands to the test, rather than in setContent's own composition, which the next layout would only try again.
        val crashPage = mutableStateOf(false)
        compose.setContent {
            if (crashPage.value) instance.plugin.Page(SKELETON_CRASH_PAGE, null, instance.host, NoNav)
        }
        compose.runOnUiThread { crashPage.value = true }
        val thrown = try {
            compose.waitForIdle()
            null
        } catch (e: Throwable) {
            e
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
        runBlocking { next.await(SKELETON, "turned off after a crash") { it.status == PluginStatus.OffAfterCrash } }
        assertEquals("none of its code loaded", 0, next.loader.classLoadersMade)
        assertEquals("the registry's file keeps it off", StoredState.Crashed, next.store.readRegistry().plugins.single().state)
        assertNull("the guard's record applied", crashJson(plugins.root))
    }
}
