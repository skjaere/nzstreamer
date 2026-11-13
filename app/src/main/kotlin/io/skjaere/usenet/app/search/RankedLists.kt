package io.skjaere.usenet.app.search

import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.RankingTarget
import io.skjaere.usenet.releases.ReleaseRanker
import io.skjaere.usenet.search.EpisodeRequest
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup

/** The Cast device a list ranked by [this] is ranked for, as its "Ranked for" line names it (L127); null for this device. */
val RankingContext.rankedFor: String? get() = (target as? RankingTarget.Cast)?.name

/**
 * Whether [this] ranks for whom [other] does (L127): this device, however its screen reads now, or one Cast device as one
 * profile has it. A list ranked by one needn't rank again for the other.
 */
fun RankingContext.ranksAs(other: RankingContext): Boolean = target == other.target && (target == RankingTarget.Device || device == other.device)

/**
 * The same releases ranked again by [context] (another device's, L127): no indexer asked, the group's place kept, each
 * playable or hidden as the ranking now says.
 */
fun ResultGroup.rankedAgain(context: RankingContext, ranker: ReleaseRanker = ReleaseRanker()): ResultGroup {
    val ranked = (playable + hidden).rankedAgain(context, ranker)
    return copy(playable = ranked.filter { it.ranked.playable }, hidden = ranked.filterNot { it.ranked.playable })
}

/** These releases ranked again by [context], in its order, each playable or not as the ranking now says. */
fun List<RankedItem>.rankedAgain(context: RankingContext, ranker: ReleaseRanker = ReleaseRanker()): List<RankedItem> {
    val held = associateBy { it.ranked.facts.id }
    return ranker.rank(held.values.map { it.ranked.facts }, context).map { RankedItem(held.getValue(it.facts.id).item, it) }
}

/**
 * [this], an episode's search's context, as the episode finder ranks its releases: by the episode's runtime, the show's
 * language and the season's size where the request knows them (EpisodeReleaseFinder.find's ranking).
 */
fun RankingContext.forEpisode(request: EpisodeRequest): RankingContext = copy(
    runtimeMinutes = request.runtimeMinutes ?: runtimeMinutes,
    originalLanguage = request.show.originalLanguage ?: originalLanguage,
    seasonEpisodes = request.seasonEpisodes ?: seasonEpisodes,
)

/**
 * A release picked by hand from [this] group's list: its other playable releases, in the list's order (L152), as ranked
 * for whom the list showed them; what the pre-check's Play a compatible release plays.
 */
fun ResultGroup.alternativesTo(item: RankedItem): List<RankedItem> = playable.filter { it.key != item.key }
