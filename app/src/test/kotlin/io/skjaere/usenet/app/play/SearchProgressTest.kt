package io.skjaere.usenet.app.play

import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.IndexerReport
import io.skjaere.usenet.search.SearchStage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SearchProgressTest {
    private val a = IndexerConfig("a", "Indexer A", "https://a.example/api", "key-a")
    private val b = IndexerConfig("b", "Indexer B", "https://b.example/api", "key-b")

    private fun answered(stage: SearchStage, indexer: IndexerConfig, accepted: Int = 0) = answered(stage, indexer, *Array(accepted) { "${stage.name}$it" })

    /** [indexer]'s answer to [stage], accepting the releases [keys] names. */
    private fun answered(stage: SearchStage, indexer: IndexerConfig, vararg keys: String) =
        FinderEvent.IndexerAnswered(stage, IndexerReport(indexer, "TVDB id", found = keys.size, accepted = keys.size), keys.map { "${indexer.id}/$it" })

    /** The season's search lists the season's episodes too: a release both searches accepted is found once. */
    @Test
    fun `a release both searches accepted is counted once`() {
        val progress = SearchProgress(indexers = 1)
        progress.after(FinderEvent.StageStarted(SearchStage.Episode, 1))
        progress.after(FinderEvent.StageStarted(SearchStage.SeasonPack, 1))
        assertEquals("Searching 1 indexer for the episode and season packs · 3 found · 0 of 1 answered", progress.after(answered(SearchStage.Episode, a, "e1", "e2", "e3")))
        assertEquals("Searching 1 indexer for the episode and season packs · 4 found · 1 of 1 answered", progress.after(answered(SearchStage.SeasonPack, a, "e1", "e2", "e3", "pack")))
        // Another indexer's release of the same guid is another release.
        assertEquals("Searching 1 indexer for the episode and season packs · 5 found · 1 of 1 answered", progress.after(answered(SearchStage.SeasonPack, b, "e1")))
    }

    @Test
    fun `the season's packs are looked for with the episode, and an indexer has answered once it answered both`() {
        val progress = SearchProgress(indexers = 2)
        assertEquals("Searching 2 indexers for the episode and season packs", progress.after(FinderEvent.StageStarted(SearchStage.Episode, 2)))
        // The pack search starting alongside doesn't change the line.
        assertEquals("Searching 2 indexers for the episode and season packs", progress.after(FinderEvent.StageStarted(SearchStage.SeasonPack, 2)))
        assertEquals("Searching 2 indexers for the episode and season packs · 2 found · 0 of 2 answered", progress.after(answered(SearchStage.Episode, a, 2)))
        assertEquals("Searching 2 indexers for the episode and season packs · 3 found · 1 of 2 answered", progress.after(answered(SearchStage.SeasonPack, a, 1)))
        assertEquals("Searching 2 indexers for the episode and season packs · 3 found · 1 of 2 answered", progress.after(answered(SearchStage.SeasonPack, b)))
        assertEquals("Searching 2 indexers for the episode and season packs · 3 found · 2 of 2 answered", progress.after(answered(SearchStage.Episode, b)))
    }

    @Test
    fun `a later search counts its own answers, and what every search found`() {
        val progress = SearchProgress(indexers = 1)
        progress.after(FinderEvent.StageStarted(SearchStage.Episode, 1))
        progress.after(FinderEvent.StageStarted(SearchStage.SeasonPack, 1))
        progress.after(answered(SearchStage.Episode, a))
        assertEquals("Trying a search by air date", progress.after(FinderEvent.StageStarted(SearchStage.AirDate, 1)))
        // The pack search, still out as the air-date search began, adds what it found.
        assertEquals("Trying a search by air date · 1 found · 0 of 1 answered", progress.after(answered(SearchStage.SeasonPack, a, 1)))
        assertEquals("Trying a search by air date · 1 found · 1 of 1 answered", progress.after(answered(SearchStage.AirDate, a)))
        assertEquals("Trying a search by name", progress.after(FinderEvent.StageStarted(SearchStage.ByName, 1)))
        assertEquals("Trying a search by name · 1 found · 1 of 1 answered", progress.after(answered(SearchStage.ByName, a)))
    }
}
