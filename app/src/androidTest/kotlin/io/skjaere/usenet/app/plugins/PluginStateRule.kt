package io.skjaere.usenet.app.plugins

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.everyStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

/**
 * The plugins on the device, around each plugin device class or test (L622): every one of them uses this rule.
 * - Before it, no plugin is installed: not in the app's own registry, not in registry.json, no plugin's folder. A class
 *   that left one behind is named here, rather than its plugin found as a tab by AppNavTest, HomeLayoutTest and the
 *   rail's tests, which run over the same install.
 * - After it, whatever the outcome: every plugin removed through each registry that holds one (the app's, and each
 *   [registry] the test made), as Remove does, its instance let go; each made registry's process ended; every
 *   plugin:<id>: secret gone; and filesDir/plugins and cacheDir/plugins deleted, the registry's file, the crash guard's
 *   crash.json, loading and starts.json, and the downloads with them.
 * AppTestRunner deletes the same two folders as a process starts, before the app does, for a process the sharded runs
 * start again after one died.
 */
class PluginStateRule : TestRule {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val made = mutableListOf<DeviceRegistry>()

    /** The app's own registry: the plugins MainActivity loads, and the ones the test seam puts in slots. */
    val app: PluginRegistry get() = container.plugins

    /** filesDir/plugins: the plugins' code and data, registry.json and the crash guard's files. */
    val root: File get() = File(context.filesDir, PluginStore.DIR)

    private val cacheRoot: File get() = File(context.cacheDir, PluginStore.DIR)

    /** A fresh registry over the app's own plugin files, as the next process's would be; its files not read yet. */
    fun registry(): DeviceRegistry = DeviceRegistry(context, container.data.secrets.named()).also { made += it }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            try {
                noneInstalled(description)
                base.evaluate()
            } catch (e: Throwable) {
                try {
                    clear()
                } catch (after: Throwable) {
                    e.addSuppressed(after)
                }
                throw e
            }
            clear()
        }
    }

    private fun noneInstalled(description: Description) = runBlocking<Unit> {
        // The app's registry has read its files, and swept away what no plugin owns, before the test writes any.
        val listed = withTimeoutOrNull(PLUGIN_WAIT_MS) { app.plugins.first { it != null } }
            ?: throw AssertionError("The app's plugin registry never read its files")
        val stored = PluginStore(context.filesDir, context.cacheDir, container.data.secrets.named()).readRegistry().plugins.map { it.id }
        val folders = listOf(root, cacheRoot).flatMap { it.listFiles().orEmpty().asList() }.filter { it.isDirectory && it.name != DOWNLOADS }.map { it.name }
        val left = (listed.map { it.id } + stored + folders).distinct()
        assertTrue("Plugins installed before ${description.displayName}, left by a test or class before it: $left", left.isEmpty())
    }

    private fun clear() = runBlocking<Unit> {
        val registries = listOf(app) + made.map { it.registry }
        everyStep(
            *registries.map { registry -> suspend { removeAll(registry) } }.toTypedArray(),
            { made.forEach { it.close() } },
            { container.data.secrets.removeAll(PluginStore.SECRETS) },
            { delete(root, "filesDir/plugins") },
            { delete(cacheRoot, "cacheDir/plugins") },
        )
        made.clear()
    }

    /** Every plugin [registry] lists, removed through it as Remove does: its instance let go, its files and secrets deleted. */
    private suspend fun removeAll(registry: PluginRegistry) {
        // A registry whose files were never read holds nothing to let go, and its remove would wait for them.
        val listed = registry.plugins.value ?: return
        withTimeoutOrNull(PLUGIN_WAIT_MS) { listed.forEach { registry.remove(it.id) } }
            ?: throw AssertionError("Plugins ${listed.map { it.id }} not removed in time")
    }

    private fun delete(folder: File, name: String) {
        if (folder.exists() && !folder.deleteRecursively()) throw AssertionError("$name not deleted")
    }

    private companion object {
        /** cacheDir/plugins/.download: a download on its way, no plugin's folder. */
        const val DOWNLOADS = ".download"
    }
}
