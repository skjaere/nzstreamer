package io.skjaere.usenet.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * Makes Home show its content rather than the first-run screen, for tests of what is behind it:
 * "Not now" on the indexer, which a provider must be saved for (debug builds save one). [restore]
 * puts the setting back as it was.
 */
class HomeContentShown {
    private val settings = InstrumentationRegistry.getInstrumentation().targetContext.container.settings
    private var skipped: Boolean? = null

    fun show() = runBlocking {
        assumeTrue("no Usenet provider saved, so Home shows the first-run screen", settings.providers.first().isNotEmpty())
        skipped = settings.indexerSetupSkipped.first()
        settings.setIndexerSetupSkipped(true)
    }

    fun restore() = runBlocking {
        skipped?.let { settings.setIndexerSetupSkipped(it) }
        skipped = null
    }
}
