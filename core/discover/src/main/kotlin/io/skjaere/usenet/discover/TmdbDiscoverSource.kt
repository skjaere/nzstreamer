package io.skjaere.usenet.discover

import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.PagedWire
import io.skjaere.usenet.metadata.TmdbClient
import io.skjaere.usenet.metadata.TmdbException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.Locale

/** In every build that has this module, for the app's release guard (checkNoDiscover) to look for; never in a Play build. */
const val DISCOVER_CANARY = "nzs.discover.canary.core"

/**
 * Discover's lists from TMDB, through [tmdb]: the user's key, the TMDB language, the metadata cache (Settings' sizes
 * and Clear count it), the offline copy and the errors. Titles are never adult ones (asked for without, and any
 * marked so left out); people on Trending are left out too.
 *
 * Days are [clock]'s (the device's zone): Airing today and On the air are asked for in its timezone, and Now playing,
 * Upcoming and Airing today, whose lists are today's, are never kept past midnight. The region is the device's
 * [country] (ISO 3166-1), for what is in cinemas there and the streaming services there; none when it has no country.
 *
 * Every path asked for is a whole string here, never put together, so the release guard can find each one.
 */
class TmdbDiscoverSource(
    private val tmdb: TmdbClient,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val country: () -> String = { Locale.getDefault().country },
) : DiscoverSource {
    /** The module's canary, read by its entry point so it stays in every build the source is in. */
    internal val canary: String = DISCOVER_CANARY

    override suspend fun page(query: DiscoverQuery, from: Int, refresh: Boolean): Aged<DiscoverPage> {
        require(from in 1..DiscoverPage.MAX_PAGES) { "page $from is outside 1 to ${DiscoverPage.MAX_PAGES}" }
        val call = call(query)
        if (query.list != DiscoverList.Trending || !query.filters.narrows) return read(call, query.type, from, refresh)
        // TMDB's trending takes no filters: its pages are filtered here, as many as a step reads.
        val years = query.filters.year.years(LocalDate.now(clock))
        var step: Aged<DiscoverPage>? = null
        var page = from
        while (true) {
            val read = try {
                read(call, query.type, page, refresh)
            } catch (e: TmdbException) {
                // A later page failing keeps what the pages before it matched; it is the next step's first.
                return step ?: throw e
            }
            val matching = read.value.items.filter { it.matches(query.filters, years) }
            step = step?.let { s ->
                Aged(
                    DiscoverPage(s.value.items.plusNew(matching), page, read.value.totalPages, read.value.capped),
                    maxOf(s.age, read.age), s.offline || read.offline, s.rateLimited || read.rateLimited,
                )
            } ?: read.map { it.copy(items = matching) }
            // A copy TMDB gave earlier: it can't be reached, or says to slow down, so nothing more is asked of it now.
            if (read.offline || read.rateLimited) return step
            if (step.value.items.size >= SCREENFUL || page - from + 1 >= TRENDING_READS || page >= read.value.totalPages) return step
            page++
        }
    }

    override suspend fun genres(type: MediaType): List<Genre> {
        val path = if (type == MediaType.Movie) "genre/movie/list" else "genre/tv/list"
        return tmdb.fetch(path, emptyMap(), GenresWire.serializer(), CHOICES).value.genres.map { Genre(it.id, it.name) }
    }

    override suspend fun services(): List<Service> {
        val region = region() ?: return emptyList()
        val providers = tmdb.fetch("watch/providers/movie", mapOf("watch_region" to region), ProvidersWire.serializer(), CHOICES).value.results
        // TMDB lists every service it knows there; its display priority puts the ones most use first.
        return providers.sortedWith(compareBy({ it.displayPriorities[region] ?: Int.MAX_VALUE }, { it.displayPriority }, { it.name }))
            .take(SERVICES)
            .map { Service(it.id, it.name) }
    }

    override fun languages(locale: Locale): List<Language> = commonLanguages(locale)

    /** What TMDB is asked for [query]'s pages, and how long its answer is kept. */
    private class Call(val path: String, val params: Map<String, String>, val life: Duration, val untilMidnight: Boolean = false)

    private fun call(query: DiscoverQuery): Call {
        val movie = query.type == MediaType.Movie
        val filters = query.filters
        val base = buildMap {
            put("include_adult", "false")
            if (movie) put("include_video", "false")
        }
        if (query.list == DiscoverList.Trending) {
            val path = when {
                movie && filters.period == Period.Today -> "trending/movie/day"
                movie -> "trending/movie/week"
                filters.period == Period.Today -> "trending/tv/day"
                else -> "trending/tv/week"
            }
            return Call(path, base, if (filters.period == Period.Today) Duration.ofHours(1) else Duration.ofHours(6))
        }
        return if (filters.narrows) discover(query, base) else own(query, base)
    }

    /** A list's own endpoint, without filters. */
    private fun own(query: DiscoverQuery, base: Map<String, String>): Call = when (query.type) {
        MediaType.Tv -> when (query.list) {
            DiscoverList.Popular -> Call("tv/popular", base, POPULAR)
            DiscoverList.TopRated -> Call("tv/top_rated", base, TOP_RATED)
            DiscoverList.AiringToday -> Call("tv/airing_today", base + timezone(), Duration.ofHours(1), untilMidnight = true)
            DiscoverList.OnTheAir -> Call("tv/on_the_air", base + timezone(), Duration.ofHours(6))
            else -> error("${query.list} is not a list of shows")
        }
        MediaType.Movie -> when (query.list) {
            DiscoverList.Popular -> Call("movie/popular", base, POPULAR)
            DiscoverList.TopRated -> Call("movie/top_rated", base, TOP_RATED)
            DiscoverList.NowPlaying -> Call("movie/now_playing", base + regionParam(region()), Duration.ofHours(12), untilMidnight = true)
            DiscoverList.Upcoming -> Call("movie/upcoming", base + regionParam(region()), Duration.ofHours(12), untilMidnight = true)
            else -> error("${query.list} is not a list of movies")
        }
    }

    /**
     * A filtered list: TMDB's discover asked for what the list's own endpoint gives (TMDB's fixed lists are discover
     * calls of its own), with the filters. Its days are in what is asked, so a copy from yesterday is never today's.
     */
    private fun discover(query: DiscoverQuery, base: Map<String, String>): Call {
        val today = LocalDate.now(clock)
        val filters = query.filters
        val region = region()
        val list: Map<String, String> = when (query.list) {
            DiscoverList.Popular -> mapOf("sort_by" to "popularity.desc")
            DiscoverList.TopRated -> mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "$MIN_VOTES")
            DiscoverList.AiringToday -> mapOf("sort_by" to "popularity.desc", "air_date.gte" to "$today", "air_date.lte" to "$today") + timezone()
            DiscoverList.OnTheAir -> mapOf("sort_by" to "popularity.desc", "air_date.gte" to "$today", "air_date.lte" to "${today.plusDays(7)}") + timezone()
            // In cinemas or coming to them, in the region.
            DiscoverList.NowPlaying -> releasedIn(today.minusWeeks(6), today) + regionParam(region)
            DiscoverList.Upcoming -> releasedIn(today.plusDays(1), today.plusWeeks(4)) + regionParam(region)
            DiscoverList.Trending -> error("Trending has no discover call")
        }
        val narrowed = buildMap {
            filters.genre?.let { put("with_genres", "$it") }
            filters.language?.let { put("with_original_language", it) }
            filters.year.years(today)?.let { span ->
                if (span.from == span.to) {
                    put("primary_release_year", "${span.to}")
                } else {
                    span.from?.let { put("primary_release_date.gte", "$it-01-01") }
                    put("primary_release_date.lte", "${span.to}-12-31")
                }
            }
            filters.service?.let {
                put("with_watch_providers", "$it")
                region?.let { r -> put("watch_region", r) }
                put("with_watch_monetization_types", "flatrate")
            }
        }
        val path = if (query.type == MediaType.Movie) "discover/movie" else "discover/tv"
        return Call(path, base + list + narrowed, Duration.ofHours(6))
    }

    private fun releasedIn(from: LocalDate, to: LocalDate) =
        mapOf("sort_by" to "popularity.desc", "with_release_type" to "2|3", "release_date.gte" to "$from", "release_date.lte" to "$to")

    private fun timezone() = mapOf("timezone" to clock.zone.id)

    private fun regionParam(region: String?): Map<String, String> = region?.let { mapOf("region" to it) }.orEmpty()

    /** The device's country if TMDB takes it as a region (two letters; not a UN area such as "419"). */
    private fun region(): String? = country().trim().uppercase(Locale.ROOT).takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }

    private suspend fun read(call: Call, type: MediaType, page: Int, refresh: Boolean): Aged<DiscoverPage> {
        val maxAge = when {
            refresh -> Duration.ZERO
            call.untilMidnight -> minOf(call.life, Duration.between(LocalDate.now(clock).atStartOfDay(clock.zone).toInstant(), clock.instant()))
            else -> call.life
        }
        return tmdb.fetch(call.path, call.params + ("page" to "$page"), PagedWire.serializer(ListItemWire.serializer()), maxAge, staleOnRateLimit = true)
            .map { wire ->
                val titles = wire.results.mapNotNull { it.toTitle(type) }.distinctBy { it.type to it.tmdbId }
                DiscoverPage(titles, page, minOf(wire.totalPages, DiscoverPage.MAX_PAGES), capped = wire.totalPages > DiscoverPage.MAX_PAGES)
            }
    }

    private fun DiscoverTitle.matches(filters: DiscoverFilters, years: YearSpan?) =
        (filters.genre == null || filters.genre in genreIds) &&
            (filters.language == null || filters.language == originalLanguage) &&
            (years == null || year in years)

    private companion object {
        val POPULAR: Duration = Duration.ofHours(6)
        val TOP_RATED: Duration = Duration.ofHours(24)
        /** Genres and services. */
        val CHOICES: Duration = Duration.ofDays(7)
        /** TMDB's own top-rated lists count only titles with this many votes. */
        const val MIN_VOTES = 200
        /** A screenful of posters, which a step of filtered Trending reads on for. */
        const val SCREENFUL = 20
        /** At most this many of Trending's pages read in one step. */
        const val TRENDING_READS = 5
        const val SERVICES = 20
    }
}

/** A list's title as the side asks it ([type]); null for an adult title, one without a name, or one of another type (Trending's people). */
private fun ListItemWire.toTitle(type: MediaType): DiscoverTitle? {
    if (adult) return null
    if (mediaType != null && mediaType != if (type == MediaType.Movie) "movie" else "tv") return null
    val movie = type == MediaType.Movie
    val name = (if (movie) title else name)?.ifBlank { null } ?: return null
    val date = if (movie) releaseDate else firstAirDate
    return DiscoverTitle(type, id, name, date?.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull(), posterPath, genreIds, originalLanguage?.ifBlank { null })
}
