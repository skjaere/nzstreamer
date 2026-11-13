package io.skjaere.usenet.app.collection

import io.skjaere.usenet.app.casting.CastModel
import io.skjaere.usenet.app.play.PlayOutcome
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.RankingTarget
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseChooserTest {
    private val e3 = Episode(1, 3, null, null, null, null, null)
    private val e4 = Episode(1, 4, null, null, null, null, null)

    private val broken = release("hl-s01e03-broken", "Harbor.Lights.S01E03.PROPER.1080p.WEB-DL.AAC2.0.H.264-BROKEN")
    private val good = release("hl-s01e03-good", "Harbor.Lights.S01E03.1080p.WEB-DL.AAC2.0.H.264-GOOD")

    private fun group(e: Episode, vararg playable: RankedItem) = ResultGroup(
        key = "S%02dE%02d".format(e.season, e.episode), kind = GroupKind.Episode, season = e.season, episodes = listOf(e.episode),
        airDate = null, year = null, playable = playable.toList(), hidden = emptyList(),
    )

    private fun release(guid: String, title: String) = RankedItem(
        NewznabItem(
            indexerId = "fake", guid = guid, title = title, sizeBytes = 1_000_000_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = 1, episode = 3, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("fake/$guid", title, 1_000_000_000), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
    )

    /** The play sheet's failure for [item] played alone, which failed for [reason]. */
    private fun failed(item: RankedItem, reason: String) =
        PlayOutcome.Failed(PlayState.Failed("Harbor Lights", "S01E03", listOf(item.item.title to reason), notTried = 0))

    @Test
    fun `backing out while it searches stops the search and keeps its list from showing up later`() = runTest {
        val chooser = ReleaseChooser<Episode>(backgroundScope)
        val found = CompletableDeferred<ResultGroup>()
        var cancelled = false
        chooser.choose(e3) {
            try {
                found.await()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        runCurrent()
        chooser.close()
        runCurrent()
        found.complete(group(e3))
        runCurrent()
        assertTrue(cancelled, "the search kept running")
        assertEquals(ChooseState.None, chooser.state.value)
    }

    @Test
    fun `choosing another episode drops the first search, also when it finishes last`() = runTest {
        val chooser = ReleaseChooser<Episode>(backgroundScope)
        val e3Found = CompletableDeferred<ResultGroup>()
        val e4Found = CompletableDeferred<ResultGroup>()
        chooser.choose(e3) { e3Found.await() }
        runCurrent()
        chooser.choose(e4) { e4Found.await() }
        runCurrent()
        e4Found.complete(group(e4))
        runCurrent()
        e3Found.complete(group(e3))
        runCurrent()
        assertEquals(ChooseState.Ready(e4, group(e4)), chooser.state.value)
    }

    @Test
    fun `an earlier search doesn't reopen the list after a release was picked`() = runTest {
        val chooser = ReleaseChooser<Episode>(backgroundScope)
        val e3Found = CompletableDeferred<ResultGroup>()
        chooser.choose(e3) { e3Found.await() }
        runCurrent()
        chooser.close()
        chooser.choose(e4) { group(e4) }
        runCurrent()
        assertEquals(ChooseState.Ready(e4, group(e4)), chooser.state.value)
        // A release was picked: playback starts and the list closes.
        chooser.close()
        e3Found.complete(group(e3))
        runCurrent()
        assertEquals(ChooseState.None, chooser.state.value)
    }

    @Test
    fun `a search that finishes without noticing it was dropped doesn't show its list`() = runTest {
        val chooser = ReleaseChooser<Episode>(backgroundScope)
        val found = CompletableDeferred<ResultGroup>()
        chooser.choose(e3) { withContext(NonCancellable) { found.await() } }
        runCurrent()
        chooser.close()
        found.complete(group(e3))
        runCurrent()
        assertEquals(ChooseState.None, chooser.state.value)
    }

    @Test
    fun `the list shows what the search found for the episode`() = runTest {
        val chooser = ReleaseChooser<Episode>(backgroundScope)
        val found = CompletableDeferred<ResultGroup>()
        chooser.choose(e3) { found.await() }
        assertEquals(ChooseState.Searching(e3), chooser.state.value)
        found.complete(group(e3))
        runCurrent()
        assertEquals(ChooseState.Ready(e3, group(e3)), chooser.state.value)
    }

    @Test
    fun `a chosen release that fails to start is marked with why, and the list stays for another to be picked`() = runTest {
        val chooser = ReleaseChooser<Episode>(backgroundScope)
        chooser.choose(e3) { group(e3, broken, good) }
        runCurrent()
        val outcome = CompletableDeferred<PlayOutcome>()
        chooser.play(broken) { outcome }
        runCurrent()
        // Under the play sheet while it plays.
        assertEquals(ChooseState.Ready(e3, group(e3, broken, good)), chooser.state.value)
        outcome.complete(failed(broken, INCOMPLETE))
        runCurrent()
        assertEquals(ChooseState.Ready(e3, group(e3, broken, good)), chooser.state.value)
        assertEquals(mapOf(broken.key to INCOMPLETE), chooser.failed.value)

        // Chosen again, it fails again: its mark says the latest.
        chooser.play(broken) { CompletableDeferred(failed(broken, "Passworded")) }
        runCurrent()
        assertEquals(mapOf(broken.key to "Passworded"), chooser.failed.value)
    }

    @Test
    fun `the list closes as the player launches, and what it marked goes with it`() = runTest {
        val chooser = ReleaseChooser<Episode>(backgroundScope)
        chooser.choose(e3) { group(e3, broken, good) }
        runCurrent()
        chooser.play(broken) { CompletableDeferred(failed(broken, INCOMPLETE)) }
        runCurrent()
        val launched = CompletableDeferred<PlayOutcome>()
        chooser.play(good) { launched }
        runCurrent()
        assertEquals(ChooseState.Ready(e3, group(e3, broken, good)), chooser.state.value)
        launched.complete(PlayOutcome.Launched)
        runCurrent()
        assertEquals(ChooseState.None, chooser.state.value)
        assertEquals(emptyMap(), chooser.failed.value)
    }

    @Test
    fun `a play stopped before it starts (Cancel, another play) leaves the list as it was`() = runTest {
        val chooser = ReleaseChooser<Episode>(backgroundScope)
        chooser.choose(e3) { group(e3, broken, good) }
        runCurrent()
        chooser.play(broken) { CompletableDeferred(PlayOutcome.Stopped) }
        runCurrent()
        assertEquals(ChooseState.Ready(e3, group(e3, broken, good)), chooser.state.value)
        assertEquals(emptyMap(), chooser.failed.value)
    }

    @Test
    fun `a play still running as its list closes neither marks nor closes a list opened since`() = runTest {
        val chooser = ReleaseChooser<Episode>(backgroundScope)
        chooser.choose(e3) { group(e3, broken, good) }
        runCurrent()
        val first = CompletableDeferred<PlayOutcome>()
        val second = CompletableDeferred<PlayOutcome>()
        chooser.play(broken) { first }
        chooser.play(good) { second }
        runCurrent()
        // The one replaced by the second choice: its failure isn't the list's any more.
        first.complete(failed(broken, INCOMPLETE))
        runCurrent()
        assertEquals(emptyMap(), chooser.failed.value)
        // Back to the page, then another episode's list.
        chooser.close()
        chooser.choose(e4) { group(e4) }
        runCurrent()
        second.complete(PlayOutcome.Launched)
        runCurrent()
        assertEquals(ChooseState.Ready(e4, group(e4)), chooser.state.value)
    }

    private val here = RankingContext(StaticDeviceCapabilities())
    private val kitchen = here.copy(device = CastModel.Chromecast.profile(null), target = RankingTarget.Cast("Kitchen display"))

    /** The same two releases as ranked for [by]: GOOD first for Kitchen display, BROKEN first here. */
    private fun ranked(by: RankingContext) = if (by.target == here.target) group(e3, broken, good) else group(e3, good, broken)

    /**
     * L127: a list says whom it was ranked for, and ranks the releases it holds again as another device is chosen, with no
     * search; what it marked stays. As This phone is chosen again, it ranks for this phone.
     */
    @Test
    fun `a list ranks its releases again as another device is chosen, with no search`() = runTest {
        val rankings = MutableStateFlow(here)
        val chooser = ReleaseChooser<Episode>(backgroundScope, rankings, StandardTestDispatcher(testScheduler))
        var searches = 0
        chooser.choose(e3) {
            searches++
            rankedBy(here, ::ranked)
            ranked(here)
        }
        runCurrent()
        assertEquals(ChooseState.Ready(e3, group(e3, broken, good)), chooser.state.value)
        chooser.play(broken) { CompletableDeferred(failed(broken, INCOMPLETE)) }
        runCurrent()

        rankings.value = kitchen
        runCurrent()
        assertEquals(ChooseState.Ready(e3, group(e3, good, broken), rankedFor = "Kitchen display"), chooser.state.value)
        assertEquals(mapOf(broken.key to INCOMPLETE), chooser.failed.value, "its marks stay")
        rankings.value = here
        runCurrent()
        assertEquals(ChooseState.Ready(e3, group(e3, broken, good)), chooser.state.value)
        assertEquals(1, searches, "searched again")
    }

    /** A list found for a Cast device says so; ranking again never reopens a list closed, nor reranks one chosen since. */
    @Test
    fun `a list closed or chosen again is never ranked again as an older one`() = runTest {
        val rankings = MutableStateFlow(kitchen)
        val chooser = ReleaseChooser<Episode>(backgroundScope, rankings, StandardTestDispatcher(testScheduler))
        chooser.choose(e3) {
            rankedBy(kitchen, ::ranked)
            ranked(kitchen)
        }
        runCurrent()
        assertEquals(ChooseState.Ready(e3, group(e3, good, broken), rankedFor = "Kitchen display"), chooser.state.value)
        chooser.close()
        rankings.value = here
        runCurrent()
        assertEquals(ChooseState.None, chooser.state.value)
        // A list found without saying how: it stays as it is.
        chooser.choose(e4) { group(e4) }
        runCurrent()
        rankings.value = kitchen
        runCurrent()
        assertEquals(ChooseState.Ready(e4, group(e4)), chooser.state.value)
    }

    /**
     * W71: a list shows once every indexer has answered, without the torrents still being checked; one that joins after is
     * in it as the list is ranked then, for the device chosen meanwhile, and what the list marked stays.
     */
    @Test
    fun `a list shows once every indexer has answered, and a torrent checked after joins it, ranked as the list is`() = runTest {
        val rankings = MutableStateFlow(here)
        val chooser = ReleaseChooser<Episode>(backgroundScope, rankings, StandardTestDispatcher(testScheduler))
        val checked = CompletableDeferred<Unit>()
        chooser.chooseAsFound(e3) {
            flow {
                rankedBy(here) { group(e3, broken) }
                emit(group(e3, broken))
                checked.await()
                rankedBy(here, ::ranked)
                emit(ranked(here))
            }
        }
        runCurrent()
        assertEquals(ChooseState.Ready(e3, group(e3, broken)), chooser.state.value)
        chooser.play(broken) { CompletableDeferred(failed(broken, INCOMPLETE)) }
        rankings.value = kitchen
        runCurrent()
        assertEquals(ChooseState.Ready(e3, group(e3, broken), rankedFor = "Kitchen display"), chooser.state.value)

        checked.complete(Unit)
        runCurrent()
        assertEquals(ChooseState.Ready(e3, group(e3, good, broken), rankedFor = "Kitchen display"), chooser.state.value)
        assertEquals(mapOf(broken.key to INCOMPLETE), chooser.failed.value, "its marks stay")
    }

    private companion object {
        const val INCOMPLETE = "Incomplete on your providers · 1 of 20 checked articles missing"
    }
}
