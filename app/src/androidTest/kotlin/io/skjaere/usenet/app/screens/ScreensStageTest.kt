package io.skjaere.usenet.app.screens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.app.container
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ScreensStage] puts the app back as it found it, as far as it says it does: also when it fails part way through
 * opening. Run with the instrumentation argument `screens=true` alone, as the screenshots are (tools/screens/capture.sh
 * runs it before them): the stage refuses a device that holds accounts on another host, as a developer's may.
 */
@RunWith(AndroidJUnit4::class)
class ScreensStageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = context.container.settings

    @Before
    fun screensOnly() {
        assumeTrue("with the instrumentation argument screens=true alone", InstrumentationRegistry.getArguments().getString("screens") == "true")
    }

    @Test
    fun aStageThatFailsPartWayThroughOpeningPutsBackWhatItChanged() = runBlocking<Unit> {
        context.container.devSettingsSaved.await()
        val saved = settings.settings.first()
        val key = settings.tmdbKey()
        val lookup = TmdbLookup.showAged
        val loader = SingletonImageLoader.get(context)
        // An indexer that isn't on the emulator's host: the stage saves it with the rest, then refuses it.
        val stage = ScreensStage(ScreensTitles(), "http://localhost:8093", "screens-stage-test")
        try {
            val failure = assertThrows(IllegalStateException::class.java) { stage.open() }
            assertTrue(failure.message!!, "indexer" in failure.message!!)
            assertEquals("the settings as they were", saved, settings.settings.first())
            assertEquals("the TMDB key as it was", key, settings.tmdbKey())
            assertSame("TMDB's own lookups back", lookup, TmdbLookup.showAged)
            assertSame("the app's image loader back", loader, SingletonImageLoader.get(context))
        } finally {
            // What a stage left behind, so a failure here leaves nothing for the next test.
            if (saved.indexers.none { it.id == ScreensStage.INDEXER_ID }) settings.deleteIndexer(ScreensStage.INDEXER_ID)
            settings.restore(saved)
            settings.setTmdbKey(key)
        }
    }
}
