package io.skjaere.usenet.search

import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.ParsedRelease
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.ReleaseRanker
import io.skjaere.usenet.releases.Severity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class RankedItem(val item: NewznabItem, val ranked: RankedRelease) {
    val key: String get() = item.key
    val parsed: ParsedRelease get() = ranked.parsed
}

enum class GroupKind { Episode, SeasonPack, Movie, Other }

/** Releases of one episode, one season pack or one movie; [playable] is ranked best first. */
data class ResultGroup(
    /** Unique within one [GroupedResults]: the section's key and the episode/pack/movie, e.g. "harbor lights|ep:2:5". */
    val key: String,
    val kind: GroupKind,
    val season: Int?,
    val episodes: List<Int>,
    val airDate: LocalDate?,
    val year: Int?,
    val playable: List<RankedItem>,
    val hidden: List<RankedItem>,
) {
    val best: RankedItem? get() = playable.firstOrNull()
    val size: Int get() = playable.size + hidden.size

    /** "S02E05", "S02E05–E06", "Season 2", "2026-10-12", "2019" or the release's title. */
    val label: String
        get() = when (kind) {
            GroupKind.Episode -> airDate?.toString() ?: ("S%02dE%02d".format(season ?: 0, episodes.first()) +
                if (episodes.size > 1) "–E%02d".format(episodes.last()) else "")
            GroupKind.SeasonPack -> "Season $season"
            GroupKind.Movie -> year?.toString() ?: "Movie"
            GroupKind.Other -> (playable + hidden).first().item.title
        }
}

/** All groups of one title ("Harbor Lights", "Harbor Lights (1998)"). */
data class ResultSection(val key: String, val title: String, val year: Int?, val groups: List<ResultGroup>) {
    val hiddenCount: Int get() = groups.sumOf { it.hidden.size }
}

data class GroupedResults(val sections: List<ResultSection>) {
    val hidden: List<RankedItem> get() = sections.flatMap { s -> s.groups.flatMap { it.hidden } }
    val playableCount: Int get() = sections.sumOf { s -> s.groups.sumOf { it.playable.size } }
    val isEmpty: Boolean get() = sections.isEmpty()

    /** The group with this [ResultGroup.key], e.g. the one the user opened. */
    fun group(key: String): ResultGroup? = sections.flatMap { it.groups }.firstOrNull { it.key == key }

    /** With a picked movie (a [ResultGrouper.KnownTitle] that isn't TV), the group its hero card plays and lists. */
    val movieGroup: ResultGroup? get() = sections.firstOrNull()?.groups?.firstOrNull { it.kind == GroupKind.Movie }

    /** "HEVC 10-bit, DD+ audio": the commonest reasons releases were hidden. */
    fun hiddenReasons(limit: Int = 2): List<String> = hidden.hiddenReasons(limit)
}

/** "HEVC 10-bit, Not cached": the commonest reasons [this], hidden releases, were hidden, the commonest first. */
fun List<RankedItem>.hiddenReasons(limit: Int = 2): List<String> =
    flatMap { r -> r.ranked.reasons.filter { it.severity == Severity.Blocking }.map { it.text.substringBefore(": no decoder") } }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(limit).map { it.key }

/**
 * Turns raw search results into titles → episodes/packs/movies → ranked releases.
 * With a [title] (the user picked a TMDB suggestion) every result belongs to that title, and a
 * picked movie's releases form one group, whatever year (or none) they are tagged with;
 * otherwise titles come from the parsed release names (name + year).
 */
class ResultGrouper(
    private val ranker: ReleaseRanker = ReleaseRanker(),
    private val now: () -> Instant = Instant::now,
) {
    /**
     * The title picked; its releases without a language tag speak its [originalLanguage] (ISO 639-1, from TMDB), and their
     * bitrates are estimated over its [runtimeMinutes] (TMDB's: a film's, or an episode's of a show).
     */
    data class KnownTitle(val name: String, val year: Int?, val isTv: Boolean, val originalLanguage: String? = null, val runtimeMinutes: Int? = null)

    /**
     * [items] as titles, groups and ranked releases; a release more than one indexer listed counts once ([oneOfEach], by
     * [indexerOrder]: the indexers' ids in Settings' order).
     */
    fun group(items: List<NewznabItem>, context: RankingContext, title: KnownTitle? = null, indexerOrder: List<String> = emptyList()): GroupedResults {
        val ranking = context.copy(
            originalLanguage = title?.originalLanguage ?: context.originalLanguage,
            runtimeMinutes = title?.runtimeMinutes ?: context.runtimeMinutes,
        )
        val unique = items.oneOfEach(indexerOrder)
        val parsed = unique.associateWith { ReleaseParser.parse(it.title) }
        val movie = title?.takeUnless { it.isTv }

        val sections = unique.groupBy { item ->
            if (title != null) "known" else sectionKey(parsed.getValue(item))
        }.map { (sectionKey, sectionItems) ->
            val first = parsed.getValue(sectionItems.first())
            val groups = sectionItems.groupBy { groupKey(parsed.getValue(it), movie) }
                .map { (groupKey, groupItems) -> buildGroup("$sectionKey|$groupKey", kindOf(groupKey), groupItems, parsed, ranking, movie) }
                .sortedWith(groupOrder)
            ResultSection(
                key = sectionKey,
                title = title?.name ?: first.title.ifBlank { sectionItems.first().title },
                year = title?.year ?: sectionItems.firstNotNullOfOrNull { parsed.getValue(it).year },
                groups = groups,
            )
        }
        // Titles with something playable first, then by their best release.
        return GroupedResults(
            sections.sortedWith(
                compareByDescending<ResultSection> { s -> s.groups.any { it.best != null } }
                    .thenByDescending { s -> s.groups.mapNotNull { it.best?.ranked?.score }.maxOrNull() ?: Double.MIN_VALUE },
            ),
        )
    }

    private fun sectionKey(p: ParsedRelease) = p.normalizedTitle + (p.year?.let { " ($it)" } ?: "")

    private fun groupKey(p: ParsedRelease, movie: KnownTitle?): String = when {
        p.airDate != null -> "date:${p.airDate}"
        p.isEpisode -> "ep:${p.seasons.firstOrNull()}:${p.episodes.joinToString(",")}"
        p.isSeasonPack -> "pack:${p.seasons.joinToString(",")}"
        // TitleMatcher accepts the neighbouring year, and some releases name none: all are the picked movie.
        movie != null -> "movie:known"
        p.year != null -> "movie:${p.year}"
        else -> "other:${p.normalizedTitle}:${p.raw}"
    }

    private fun kindOf(groupKey: String): GroupKind = when {
        groupKey.startsWith("date:") || groupKey.startsWith("ep:") -> GroupKind.Episode
        groupKey.startsWith("pack:") -> GroupKind.SeasonPack
        groupKey.startsWith("movie:") -> GroupKind.Movie
        else -> GroupKind.Other
    }

    private fun buildGroup(key: String, kind: GroupKind, items: List<NewznabItem>, parsed: Map<NewznabItem, ParsedRelease>, context: RankingContext, movie: KnownTitle?): ResultGroup {
        val p = parsed.getValue(items.first())
        val byId = items.associateBy { it.key }
        val ranked = ranker.rank(items.map { it.toFacts() }, context).map { RankedItem(byId.getValue(it.facts.id), it) }
        return ResultGroup(
            key = key, kind = kind, season = p.seasons.firstOrNull(), episodes = p.episodes, airDate = p.airDate,
            year = if (kind == GroupKind.Movie && movie != null) movie.year else p.year,
            playable = ranked.filter { it.ranked.playable }, hidden = ranked.filterNot { it.ranked.playable },
        )
    }

    /** Newest episode first; a season's pack after its episodes; movies and others by best score. */
    private val groupOrder = compareByDescending<ResultGroup> { it.season ?: -1 }
        .thenBy { it.kind == GroupKind.SeasonPack }
        .thenByDescending { it.airDate ?: LocalDate.MIN }
        .thenByDescending { it.episodes.lastOrNull() ?: -1 }
        .thenByDescending { it.best?.ranked?.score ?: Double.MIN_VALUE }

    private fun NewznabItem.toFacts() = ReleaseFacts(
        id = key,
        name = title,
        sizeBytes = sizeBytes,
        passworded = passworded,
        grabs = grabs,
        ageDays = postedAt?.let { Duration.between(it, now()).toDays().toInt() },
        mediaInfo = mediaInfo,
        source = if (infoHash != null) ReleaseFacts.TORRENT else ReleaseFacts.USENET,
        availability = availability,
    )
}

/**
 * One of each release ([NewznabItem.key]), in the order each first came. A torrent listed by several indexers is one release
 * (L200), as its service caches it: the listing kept is that of the first indexer in [indexerOrder] (Settings' order, the
 * indexers' ids) whose "Use with your debrid service" is on, so it plays where it can; with none on, the first in that
 * order. An indexer not in [indexerOrder] comes after those that are, in the order they came. Each copy is checked on its
 * own, and two can settle apart (one's check ran past its bound, the other's said cached): a copy a service said it has
 * goes before the rest, so the release still plays (DDC-3).
 */
fun List<NewznabItem>.oneOfEach(indexerOrder: List<String> = emptyList()): List<NewznabItem> {
    val place = indexerOrder.withIndex().associate { (i, id) -> id to i }
    return groupBy { it.key }.values.map { copies ->
        copies.withIndex().minWith(
            compareBy<IndexedValue<NewznabItem>> { it.value.availability == Availability.NotForService }
                .thenBy { it.value.availability !is Availability.Cached }
                .thenBy { place[it.value.indexerId] ?: Int.MAX_VALUE }
                .thenBy { it.index },
        ).value
    }
}
