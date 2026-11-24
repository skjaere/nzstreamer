package io.skjaere.usenet.app.plugins

import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineOnSavedProviders
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.data.WatchSnapshot
import io.skjaere.usenet.data.WatchState
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The mock's two halves for a plugin's searches and plays, as the user would set them up: the shared fake indexer
 * (10.0.2.2:8089, its Copper Crown releases) as the one indexer on, named [indexerName]; and, where a test plays, the mock
 * NNTP server (10.0.2.2:1119) as the one provider, the engine on it. [restore] puts back every watch row [forget] was
 * told of as it was before the test, and after a [setUp] the settings, the engine and the releases remembered as tried. The saved providers
 * are hidden for the test, not deleted, as deleting would remove their passwords; the test's own provider and indexer are
 * deleted with their secrets.
 */
class PluginMockSetup(private val indexerId: String, private val indexerName: String) {
    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val settings = container.settings
    private val library = container.library
    private var saved: AppSettings? = null
    private var tried: List<TriedRelease> = emptyList()
    private val rows = mutableMapOf<String, WatchState?>()

    /** Skips the test where the fake indexer, and for a play the mock NNTP server, isn't up. */
    fun assumeUp(plays: Boolean) {
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        if (plays) assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
    }

    /** The fake indexer as the one indexer on, and where [plays], the mock as the one provider. */
    suspend fun setUp(plays: Boolean) {
        val before = settings.settings.first()
        saved = before
        tried = library.triedReleaseEntries()
        if (plays) {
            settings.restore(before.copy(providers = before.providers.map { it.copy(deleted = true) }))
            val mock = settings.saveProvider(ProviderRecord("", "Mock", "10.0.2.2", 1119, useTls = false, connections = 4), "")
            engineSetup("the engine on the saved mock") { container.engine.configuredProviders.first { it == listOf(mock) } }
        }
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        settings.saveIndexer(indexerId, indexerName, "http://10.0.2.2:8089", "fake-indexer-key")
    }

    /** [key]'s watch row as it is now, to be put back so by [restore]: the test's plays and marks make or change it. */
    suspend fun forget(key: String) {
        if (key !in rows) rows[key] = library.state(key).first()
    }

    suspend fun restore() {
        rows.forEach { (key, was) -> putBack(key, was) }
        rows.clear()
        val before = saved ?: return
        saved = null
        settings.providers.first().filter { p -> before.providers.none { it.id == p.id } }.forEach { settings.deleteProvider(it.id) }
        settings.settings.first().providers.forEach { container.providerTests.forget(it.id) }
        if (before.indexers.none { it.id == indexerId } && settings.indexer(indexerId) != null) settings.deleteIndexer(indexerId)
        settings.restore(before)
        library.restoreTriedReleases(tried)
        engineOnSavedProviders()
    }

    /** [key]'s row back to [was]: none (a tombstone, as a row taken back is kept) where it had none. */
    private suspend fun putBack(key: String, was: WatchState?) {
        val now = library.state(key).first() ?: return
        // The library's undo of a mark: the row as the snapshot had it, or a tombstone for one the mark made.
        val snapshot = if (was == null) WatchSnapshot(emptyList(), listOf(key), now.updatedAt) else WatchSnapshot(listOf(was), emptyList(), now.updatedAt)
        library.undoMarks(snapshot)
    }

    private fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess
}
