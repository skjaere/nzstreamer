package io.skjaere.usenet.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tests' helper that makes Home show its content ([HomeContentShown]) reads the saved providers only once the debug
 * build's start has saved its own: right after a fresh install the seeding runs as the first test starts, and a read
 * before it lands found none, so the test was skipped (TvSmokeTest's skips on tv36). The seeding stands in here as a
 * deferred the test completes.
 */
@RunWith(AndroidJUnit4::class)
class HomeContentShownTest {
    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container

    @Test
    fun showReadsTheProvidersOnlyOnceTheDebugBuildsSeedingIsDone() = runBlocking<Unit> {
        // The app's own seeding first, so its provider is there whatever this test's place in the run.
        withTimeout(SEEDING_MS) { container.devSettingsSaved.await() }
        val seeded = CompletableDeferred<Unit>()
        val home = HomeContentShown(seeded)
        try {
            val shown = async(Dispatchers.IO) { home.show() }
            assertNull("show() read the providers before the seeding was done", withTimeoutOrNull(HELD_MS) { shown.await() })
            seeded.complete(Unit)
            withTimeout(SEEDING_MS) { shown.await() }
        } finally {
            home.restore()
        }
    }

    private companion object {
        const val SEEDING_MS = 30_000L

        /** Far longer than show()'s reads and write of the settings take. */
        const val HELD_MS = 2_000L
    }
}
