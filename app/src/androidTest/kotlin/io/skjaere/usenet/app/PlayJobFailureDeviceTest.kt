package io.skjaere.usenet.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * A play whose job throws, here before its first release is fetched (the coordinator's NZB seam
 * failing as a full disk would), ends in the sheet's failure, in plain words: not in a sheet left
 * as it was and an exception that would crash the app (#117). The coordinator runs on a scope of
 * the test's, whose handler stands for the crash.
 */
@RunWith(AndroidJUnit4::class)
class PlayJobFailureDeviceTest {
    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val realSource = PlaybackCoordinator.nzbSource
    private val unhandled = CompletableDeferred<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> unhandled.complete(e) })

    @After
    fun restore() {
        PlaybackCoordinator.nzbSource = realSource
        scope.cancel()
    }

    @Test
    fun aPlayWhoseJobThrowsEndsInAFailure() = runBlocking<Unit> {
        PlaybackCoordinator.nzbSource = { _, _ -> throw IOException("No space left on device") }
        val coordinator = PlaybackCoordinator(container, scope)
        val title = "Lantern.Field.2026.1080p.WEB.h264-GRP"
        coordinator.play(PlayRequest("Lantern Field", "2026", listOf(candidate(title)), FilePicker.Largest, WatchTarget(WatchKind.Release, title)))
        val failed = withTimeout(10_000) {
            merge(
                coordinator.state.filterIsInstance<PlayState.Failed>(),
                flow<PlayState.Failed> { throw AssertionError("the play's failure went unhandled", unhandled.await()) },
            ).first()
        }
        assertEquals(listOf("Lantern Field" to "Something went wrong: No space left on device"), failed.failures)
        assertFalse(unhandled.isCompleted)
    }

    /**
     * A play's work runs under the name "play", which the engine's lock lines give its holder and its waiters (#273), and
     * its start is logged with how many releases it will try: in #273's log nothing timed the click.
     */
    @Test
    fun aPlayRunsUnderItsNameAndSaysHowManyReleasesItWillTry() = runBlocking<Unit> {
        val fetchedBy = CompletableDeferred<String?>()
        PlaybackCoordinator.nzbSource = { _, _ ->
            NzbSource {
                fetchedBy.complete(currentCoroutineContext()[CoroutineName]?.name)
                throw FetchException("HTTP 404")
            }
        }
        // The mock alone, as the other device tests set it: the play's first use of the engine builds for it, logging in nowhere.
        engineSetup("the mock on the engine") { mockOnEngine() }
        val coordinator = PlaybackCoordinator(container, scope)
        val title = "Lantern.Field.2026.1080p.WEB.h264-GRP"
        val since = LogBuffer.snapshot().lastOrNull()?.seq ?: 0
        coordinator.play(PlayRequest("Lantern Field", "2026", listOf(candidate(title)), FilePicker.Largest, WatchTarget(WatchKind.Release, title), chosen = true))
        assertEquals("play", withTimeout(30_000) { fetchedBy.await() })
        withTimeout(10_000) { coordinator.state.filterIsInstance<PlayState.Failed>().first() }
        val lines = LogBuffer.snapshot().filter { it.seq > since && it.tag == "PlaybackCoordinator" }.map { it.message }
        assertTrue("no start line among $lines", "play: Lantern Field: 1 release to try, chosen" in lines)
        assertFalse(unhandled.isCompleted)
    }

    private fun candidate(title: String) = RankedItem(
        NewznabItem(
            indexerId = "play-job-failure-device-test", guid = "lantern-field", title = title, sizeBytes = 690_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("play-job-failure-device-test/lantern-field", title, 690_000), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
    )
}
