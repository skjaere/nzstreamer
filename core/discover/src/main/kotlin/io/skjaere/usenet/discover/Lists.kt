package io.skjaere.usenet.discover

import io.skjaere.usenet.metadata.MediaType
import java.time.LocalDate

/**
 * The lists of titles TMDB keeps that Discover shows, by the app's own names (never text from TMDB): a closed set, so
 * whatever serves them only ever answers for these.
 */
enum class DiscoverList(val title: String) {
    Trending("Trending"),
    Popular("Popular"),
    TopRated("Top rated"),
    AiringToday("Airing today"),
    OnTheAir("On the air"),
    NowPlaying("Now playing"),
    Upcoming("Upcoming"),
    ;

    /** A row's heading: Trending's says its period ("Trending this week"). */
    fun heading(period: Period): String = if (this == Trending) "$title ${period.phrase}" else title

    /**
     * The filters this list offers on [type]'s side, in the order their pills show: Trending's period; the genre and
     * the original language; for movies the year, and a service but not on Trending (TMDB's trending takes no filters,
     * so they are applied to its pages here, and a title's services aren't in them).
     */
    fun filters(type: MediaType): List<Filter> = buildList {
        if (this@DiscoverList == Trending) add(Filter.Period)
        add(Filter.Genre)
        add(Filter.Language)
        if (type == MediaType.Movie) {
            add(Filter.Year)
            if (this@DiscoverList != Trending) add(Filter.Service)
        }
    }

    companion object {
        /** The lists on [type]'s side, in the order the tab shows them. */
        fun on(type: MediaType): List<DiscoverList> = when (type) {
            MediaType.Tv -> listOf(Trending, Popular, TopRated, AiringToday, OnTheAir)
            MediaType.Movie -> listOf(Trending, Popular, TopRated, NowPlaying, Upcoming)
        }
    }
}

/** Trending's period: TMDB's day or week. [label] for its pill, [phrase] for a heading. */
enum class Period(val label: String, val phrase: String) {
    Today("Today", "today"),
    Week("This week", "this week"),
}

/** The kinds of filter, one choice each. */
enum class Filter { Period, Genre, Language, Year, Service }

/** A movie's release year, as the year pill offers it. */
enum class YearFilter(val label: String) {
    Any("Any year"),
    ThisYear("This year"),
    LastYear("Last year"),
    Twenties("2020s"),
    Tens("2010s"),
    Noughties("2000s"),
    Nineties("1990s"),
    Older("Older"),
    ;

    /** The years it takes, counted from [today]'s year: null for any year. */
    fun years(today: LocalDate): YearSpan? = when (this) {
        Any -> null
        ThisYear -> YearSpan(today.year, today.year)
        LastYear -> YearSpan(today.year - 1, today.year - 1)
        Twenties -> YearSpan(2020, 2029)
        Tens -> YearSpan(2010, 2019)
        Noughties -> YearSpan(2000, 2009)
        Nineties -> YearSpan(1990, 1999)
        Older -> YearSpan(null, 1989)
    }
}

/** Release years [from] to [to], both included; no [from] for every year up to [to]. */
data class YearSpan(val from: Int?, val to: Int) {
    /** A title without a year is in none. */
    operator fun contains(year: Int?): Boolean = year != null && (from == null || year >= from) && year <= to
}

/**
 * What a list is narrowed to, one choice each. Shows take only Trending's period, the genre and the original language
 * (a design decision: "we dont need the year and service selectors in tv shows"); movies add the release year and a
 * streaming service (not on Trending, which TMDB can't filter by service). [DiscoverList.filters] says which a list offers.
 */
data class DiscoverFilters(
    val period: Period = Period.Week,
    /** A TMDB genre id from the side's own genres ([DiscoverSource.genres]): shows' and movies' differ. */
    val genre: Int? = null,
    /** TMDB's original_language code as it is: "cn" is Cantonese, a filter of its own beside "zh". */
    val language: String? = null,
    val year: YearFilter = YearFilter.Any,
    /** A streaming service's TMDB id ([DiscoverSource.services]): titles on its subscription in the device's region. */
    val service: Int? = null,
) {
    /** Whether a filter narrows the list (Trending's period doesn't: it picks which of TMDB's lists). */
    val narrows: Boolean get() = genre != null || language != null || year != YearFilter.Any || service != null

    /** These filters as [list] on [type]'s side takes them: the ones it doesn't offer put back to none. */
    fun on(type: MediaType, list: DiscoverList): DiscoverFilters {
        val offered = list.filters(type)
        return DiscoverFilters(
            period = if (Filter.Period in offered) period else Period.Week,
            genre = genre.takeIf { Filter.Genre in offered },
            language = language.takeIf { Filter.Language in offered },
            year = if (Filter.Year in offered) year else YearFilter.Any,
            service = service.takeIf { Filter.Service in offered },
        )
    }
}

/** A list on one side, with its filters: only those it offers ([DiscoverFilters.on]), so a show list never has a year or a service. */
data class DiscoverQuery(val type: MediaType, val list: DiscoverList, val filters: DiscoverFilters = DiscoverFilters()) {
    init {
        require(list in DiscoverList.on(type)) { "${list.title} is not on the $type side" }
        require(filters == filters.on(type, list)) { "${list.title} on the $type side doesn't take $filters" }
    }
}
