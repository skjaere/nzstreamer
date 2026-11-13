package io.skjaere.usenet.app.play

import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.SearchStage

/**
 * The play sheet's line while the finder searches for an episode: the search under way, what every search has found so
 * far and how many of the [indexers] have answered. The season's packs are looked for at the same time as the episode, so
 * an indexer has answered that search once it answered both; a later one (by air date, by name) counts its own answers.
 * A release more than one search found (the season's search lists its episodes too) is found once.
 */
internal class SearchProgress(private val indexers: Int) {
    private var current: SearchStage? = null
    private val answered = mutableMapOf<SearchStage, MutableSet<String>>()
    private val found = mutableSetOf<String>()

    /** The line after [event]; null for the finder's result. */
    fun after(event: FinderEvent): String? = when (event) {
        is FinderEvent.StageStarted -> {
            // The pack search starts alongside the episode's, which the line goes on with.
            if (event.stage != SearchStage.SeasonPack || current == null) current = event.stage
            line(counts = false)
        }
        is FinderEvent.IndexerAnswered -> {
            answered.getOrPut(event.stage) { mutableSetOf() } += event.report.indexer.id
            found += event.acceptedKeys
            line(counts = true)
        }
        is FinderEvent.Finished, is FinderEvent.Joined -> null
    }

    private fun line(counts: Boolean): String {
        val stage = current ?: SearchStage.Episode
        val what = when (stage) {
            SearchStage.Episode -> "Searching $indexers indexer${if (indexers == 1) "" else "s"} for the episode and season packs"
            SearchStage.AirDate -> "Trying a search by air date"
            SearchStage.SeasonPack -> "Looking for season packs"
            SearchStage.ByName -> "Trying a search by name"
        }
        if (!counts) return what
        val done = when (stage) {
            SearchStage.Episode -> answeredTo(SearchStage.Episode).intersect(answeredTo(SearchStage.SeasonPack)).size
            else -> answeredTo(stage).size
        }
        return "$what · ${found.size} found · $done of $indexers answered"
    }

    private fun answeredTo(stage: SearchStage): Set<String> = answered[stage].orEmpty()
}
