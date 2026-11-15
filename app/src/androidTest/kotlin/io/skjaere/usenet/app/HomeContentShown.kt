package io.skjaere.usenet.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/**
 * Makes Home show its content rather than the first-run screen, for tests of what is behind it:
 * "Not now" on the indexer, which a provider must be saved for (debug builds save one, once their start's
 * seeding is done: [show] waits for it). [restore] puts the setting back as it was.
 */
class HomeContentShown(
    /** Done once the debug build's start has saved its dev settings ([AppContainer.devSettingsSaved]), or a test's stand-in. */
    private val seeded: Deferred<Unit> = InstrumentationRegistry.getInstrumentation().targetContext.container.devSettingsSaved,
) {
    private val settings = InstrumentationRegistry.getInstrumentation().targetContext.container.settings
    private var skipped: Boolean? = null

    fun show() = runBlocking {
        // Right after a fresh install the debug build's start saves its provider as the first test begins: a read before
        // that lands finds none, and the test would be skipped for it.
        withTimeout(SEEDING_MS) { seeded.await() }
        assumeTrue("no Usenet provider saved, so Home shows the first-run screen", settings.providers.first().isNotEmpty())
        skipped = settings.indexerSetupSkipped.first()
        settings.setIndexerSetupSkipped(true)
    }

    fun restore() = runBlocking {
        skipped?.let { settings.setIndexerSetupSkipped(it) }
        skipped = null
    }

    private companion object {
        /** The seeding's time at most: a few settings saved. */
        const val SEEDING_MS = 30_000L
    }
}
