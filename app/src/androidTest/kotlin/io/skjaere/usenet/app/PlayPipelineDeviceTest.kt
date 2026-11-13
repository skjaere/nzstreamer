package io.skjaere.usenet.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.EngineAvailabilityBackend
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.PipelineEvent
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.PlayPipeline
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.core.UsenetSource
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabClient
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabException
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.player.ExoTrackProbe
import io.skjaere.usenet.player.MediaCodecDeviceCapabilities
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseRanker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Search → rank → fetch → sample check → prepare → track probe, on the device, against the
 * fake indexer (`mock-seed indexer`, 10.0.2.2:8089) and the mock NNTP server it seeded.
 */
@RunWith(AndroidJUnit4::class)
class PlayPipelineDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val indexer = IndexerConfig("fake", "Fake", "http://10.0.2.2:8089", "fake-indexer-key")
    private val client = NewznabClient(NewznabClient.defaultHttpClient())
    private val capabilities by lazy { MediaCodecDeviceCapabilities(context) }

    @Before
    fun requireFakes() {
        fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        runBlocking {
            engineSetup("the mock on the engine") { mockOnEngine() }
        }
    }

    /** [accept] stands in for 3g's release validation (right show, right episode or a pack). */
    private suspend fun play(
        query: SearchQuery,
        picker: FilePicker = FilePicker.Largest,
        accept: (NewznabItem) -> Boolean = { true },
    ): Pair<List<PipelineEvent>, List<String>> {
        val items = client.search(indexer, query).items.filter(accept).associateBy { it.guid }
        val ranked = ReleaseRanker().rank(
            items.values.map { ReleaseFacts(it.guid, it.title, it.sizeBytes, it.passworded, it.grabs, mediaInfo = it.mediaInfo) },
            RankingContext(capabilities),
        )
        val playable = ranked.filter { it.playable }
        Log.i(TAG, "ranked: " + ranked.joinToString { "${it.facts.id}${if (it.playable) "" else " ✕ " + it.reasons.first().text}" })

        val source = NzbSource { candidate -> fetch(items.getValue(candidate.id)) }
        val usenet = UsenetSource(
            source = source,
            availability = EngineAvailabilityBackend(container.engine),
            preparer = container.preparer,
            store = container.store,
        )
        val pipeline = PlayPipeline({ usenet }, probe = ExoTrackProbe(context, container.dataSourceFactory))
        val events = pipeline.play(playable.map { PlayCandidate(it.facts.id, it.facts.name) }, picker)
            .onEach { event ->
                when (event) {
                    is PipelineEvent.Step -> if (event.status !is StepStatus.Running) Log.i(TAG, "${event.candidate.id} ${event.step}: ${event.status}")
                    else -> Log.i(TAG, event.toString())
                }
            }
            .toList()
        return events to playable.map { it.facts.id }
    }

    private suspend fun fetch(item: NewznabItem): ByteArray = try {
        client.nzb(indexer, item)
    } catch (e: NewznabException) {
        throw FetchException(e.error.message, stopsAll = e.error is NewznabError.DownloadLimitReached || e.error is NewznabError.Auth)
    }

    private fun failures(events: List<PipelineEvent>) =
        (events.last() as? PipelineEvent.Succeeded)?.failedAttempts.orEmpty().map { it.first.id to it.second }

    /**
     * The release the indexer flags as passworded is tried where its quality puts it, second, and its archive isn't
     * encrypted: it plays, without a password. HEVC 10-bit never reaches the pipeline on this emulator.
     */
    @Test
    fun brokenTopReleaseFallsBackToTheOneFlaggedPasswordedWhichOpensWithoutAPassword() = runBlocking {
        val (events, playable) = play(SearchQuery.Tv(ids = TvIds(tvdb = 900001), season = 1, episode = 1))
        assertEquals(listOf("hl-s01e01-broken", "hl-s01e01-passwd", "hl-s01e01-good"), playable)
        val success = events.last() as? PipelineEvent.Succeeded ?: error("did not play: ${events.last()}")
        assertEquals("hl-s01e01-passwd", success.target.candidate.id)
        val (failedId, failure) = failures(events).single()
        assertEquals("hl-s01e01-broken", failedId)
        assertTrue(failure.toString(), failure is AttemptFailure.MissingArticles)
    }

    @Test
    fun seasonPackPlaysTheRequestedEpisode() = runBlocking {
        val (events, _) = play(SearchQuery.Tv(ids = TvIds(tvdb = 900001), season = 1), FilePicker.Episode(1, 3)) { item ->
            val parsed = io.skjaere.usenet.releases.ReleaseParser.parse(item.title)
            parsed.isSeasonPack || 3 in parsed.episodes
        }
        val success = events.last() as? PipelineEvent.Succeeded ?: error("did not play: ${events.last()}")
        assertEquals("Harbor.Lights.S01E03.1080p.WEB-DL.AAC2.0.H.264-PACK.mkv", success.target.file.name)
    }

    @Test
    fun mislabelledAudioIsCaughtByTheProbe() = runBlocking {
        assumeTrue("device decodes E-AC-3; nothing to catch", !capabilities.canDecode(io.skjaere.usenet.releases.AudioCodec.Eac3))
        val (events, playable) = play(SearchQuery.Movie(imdb = "tt9000012"))
        assertEquals("cc-2019-eac3", playable.first())
        val success = events.last() as? PipelineEvent.Succeeded ?: error("did not play: ${events.last()}")
        assertEquals("cc-2019-good", success.target.candidate.id)
        val failure = failures(events).single().second
        assertTrue(failure.text, failure is AttemptFailure.NoPlayableAudio && "E-AC-3" in failure.text)
    }

    @Test
    fun episodeFinderFeedsThePipeline() = runBlocking {
        val search = io.skjaere.usenet.newznab.MultiIndexerSearch(client, io.skjaere.usenet.newznab.CapsCache(client))
        val finder = io.skjaere.usenet.search.EpisodeReleaseFinder(search)
        val show = io.skjaere.usenet.search.ShowRef("Harbor Lights", 2021, ids = TvIds(tvdb = 900001, tmdb = 900002, imdb = "tt9000003"))

        suspend fun playEpisode(episode: Int): Pair<io.skjaere.usenet.search.FindResult, PipelineEvent> {
            val request = io.skjaere.usenet.search.EpisodeRequest(show, season = 1, episode = episode, runtimeMinutes = 45)
            val found = (finder.find(request, listOf(indexer), RankingContext(capabilities)).toList().last()
                as io.skjaere.usenet.search.FinderEvent.Finished).result
            Log.i(TAG, "E$episode: playable=${found.playable.map { it.item.guid }} hidden=${found.hidden.map { it.item.guid }} " +
                found.stages.joinToString { "${it.stage}: ${it.accepted}/${it.found}" })
            val items = found.playable.associateBy { it.item.guid }
            val usenet = UsenetSource(
                source = NzbSource { candidate -> fetch(items.getValue(candidate.id).item) },
                availability = EngineAvailabilityBackend(container.engine),
                preparer = container.preparer,
                store = container.store,
            )
            val pipeline = PlayPipeline({ usenet }, probe = ExoTrackProbe(context, container.dataSourceFactory))
            val last = pipeline.play(found.playable.map { PlayCandidate(it.item.guid, it.item.title) }, FilePicker.Episode(1, episode)).toList().last()
            return found to last
        }

        val (e1, e1Played) = playEpisode(1)
        assertEquals(listOf("hl-s01e01-tenbit"), e1.hidden.map { it.item.guid })
        assertEquals("hl-s01e01-passwd", (e1Played as PipelineEvent.Succeeded).target.candidate.id)

        val (e3, e3Played) = playEpisode(3)
        assertEquals(listOf(io.skjaere.usenet.search.SearchStage.Episode, io.skjaere.usenet.search.SearchStage.SeasonPack), e3.stages.map { it.stage })
        assertEquals("Harbor.Lights.S01E03.1080p.WEB-DL.AAC2.0.H.264-PACK.mkv", (e3Played as PipelineEvent.Succeeded).target.file.name)
    }

    private companion object {
        const val TAG = "PlayPipelineDeviceTest"
    }
}
