package io.skjaere.usenet.app.screens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.app.container
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchSnapshot
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
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
    private val library = context.container.library

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

    @Test
    fun aClosedStageLeavesTheWatchRowsOfTheShowsItMarkedAsTheyWere() = runBlocking<Unit> {
        context.container.devSettingsSaved.await()
        val titles = ScreensTitles()
        val episodes = listOf(ScreensStage.KELPMOOR, ScreensStage.QUILLON, ScreensStage.GULLENKILN).map(titles::show).flatMap { show ->
            show.seasons.flatMap { season -> (1..season.episodeCount).map { WatchKey.episode(show.tmdbId, season.number, it) } }
        }
        val kelpmoor = titles.show(ScreensStage.KELPMOOR)
        val runtime = kelpmoor.episodeRuntimeMinutes!! * 60_000L
        fun played(number: Int, at: Long) = runBlocking {
            val template = WatchState(
                WatchKey.episode(kelpmoor.tmdbId, 1, number), WatchKind.Episode, showTmdbId = kelpmoor.tmdbId, season = 1, episode = number,
                title = WatchTitle.episode(kelpmoor.name, 1, number), updatedAt = 0,
            )
            library.recordProgress(template, at, runtime)
        }
        // A row the device had already: the second episode stopped five minutes in, which the stage marks watched.
        val had = WatchKey.episode(kelpmoor.tmdbId, 1, 2)
        played(2, 5 * 60_000L)
        val before = episodes.associateWith { library.state(it).first() }
        try {
            val stage = ScreensStage(titles, "http://${ScreensStage.HOST}:8093", "screens-stage-test")
            stage.open()
            try {
                assertEquals("marked by the stage", true, library.state(had).first()?.watched)
                // As a screenshot's play does: a stamp on an episode the stage marked, and a row of its own on one it didn't.
                played(4, 90_000L)
                played(5, 90_000L)
            } finally {
                stage.close()
            }
            episodes.forEach { key ->
                val after = library.state(key).first()
                when (val was = before.getValue(key)) {
                    null -> assertTrue("$key: none before, none after but a tombstone; $after", after == null || after.deleted)
                    else -> assertEquals(key, was, after?.copy(updatedAt = was.updatedAt))
                }
            }
        } finally {
            // The row this test made, removed as the stage removes its own.
            library.state(had).first()?.let { library.undoMarks(WatchSnapshot(emptyList(), listOf(had), it.updatedAt)) }
        }
    }
}
