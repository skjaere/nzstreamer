package io.skjaere.usenet.app.discover

import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.tv.PosterTone
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.discover.DiscoverQuery
import io.skjaere.usenet.discover.DiscoverTitle
import io.skjaere.usenet.discover.Filter
import io.skjaere.usenet.discover.Genre
import io.skjaere.usenet.discover.YearFilter
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.TmdbError
import java.time.Duration

/*
 * What Discover's screens show, phones' and TVs' alike: its cards, the tab's rows and the See all page, and their words.
 */

/**
 * A title on one of Discover's lists as its card shows it. [collected] travels with the title, not through a mapping the
 * screen remembers, so the card's mark changes as the title is added to the collection on its page.
 */
data class DiscoverCard(val title: DiscoverTitle, val collected: Boolean) {
    /** The collection's key for the title ("show:1"): what the cards are keyed by and the collection matched by. */
    val key: String get() = title.key().value

    /** Under the title: "✓ In collection" for a collected one (in the Good tone), else its year. */
    val line: String? get() = if (collected) IN_COLLECTION else title.year?.toString()
    val tone: PosterTone get() = if (collected) PosterTone.Good else PosterTone.Muted

    /** The mark shows whole: on a TV's 98 dp card it takes a second line at a large text size, where a year never needs one. */
    val lineLines: Int get() = if (collected) 2 else 1

    /** What TalkBack hears of the card: "Harbor Lights, 2021, show, in your collection". */
    val spoken: String get() = listOfNotNull(title.title, title.year?.toString(), kindOf(title.type), IN_YOUR_COLLECTION.takeIf { collected }).joinToString(", ")
}

internal fun DiscoverTitle.key(): TitleKey = if (type == MediaType.Tv) TitleKey.show(tmdbId) else TitleKey.movie(tmdbId)

/** [titles] as cards, each marked whether [collected] has it. */
internal fun cards(titles: List<DiscoverTitle>, collected: Set<TitleKey>): List<DiscoverCard> = titles.map { DiscoverCard(it, it.key() in collected) }

internal fun kindOf(type: MediaType): String = if (type == MediaType.Tv) "show" else "movie"

/** The switch's words for a side. */
internal fun sideName(type: MediaType): String = if (type == MediaType.Tv) SHOWS else MOVIES

/** Whether Discover can ask TMDB: no key (nothing is sent), a key TMDB refused, or a key. */
enum class KeyState { Missing, Refused, Ready }

/** A row on the tab: TMDB being asked (blank outlines), its cards, or why it has none, with Try again. */
sealed interface RowView {
    data object Loading : RowView
    data class Cards(val cards: List<DiscoverCard>) : RowView
    data class Failed(val message: String) : RowView
}

data class DiscoverRow(val list: DiscoverList, val heading: String, val view: RowView)

/**
 * The Discover tab as it shows: [key] null while the settings aren't read (neither the no-key card nor rows, so neither
 * flashes); the side with its genres and the one picked ([genre], null for all); the side's rows, none without a key;
 * [offline] the age of the oldest copy shown while TMDB couldn't be reached.
 */
data class DiscoverUi(
    val key: KeyState?,
    val side: MediaType,
    val genres: List<Genre>,
    val genre: Int?,
    val rows: List<DiscoverRow>,
    val artwork: Boolean,
    val offline: Duration? = null,
) {
    /** Every row failed (offline with nothing kept, TMDB down): one card for the page in their place, rather than five. */
    val allFailed: Boolean get() = rows.isNotEmpty() && rows.all { it.view is RowView.Failed }

    /** What the rows' failure says, when they all failed. */
    val failure: String? get() = if (allFailed) (rows.first().view as RowView.Failed).message else null

    /** A genre picked that no row has a title in: one card for the page, with Clear filters. */
    val nothingMatches: Boolean get() = genre != null && rows.isNotEmpty() && rows.all { (it.view as? RowView.Cards)?.cards?.isEmpty() == true }

    /** The rows to show: one with no titles (none in the genre) is left out. */
    val shownRows: List<DiscoverRow> get() = rows.filterNot { (it.view as? RowView.Cards)?.cards?.isEmpty() == true }
}

/** The See all page's titles: TMDB being asked for the first step, shown, none (with [ListView.Empty.canClear]), or why not. */
sealed interface ListView {
    data object Loading : ListView
    data object Shown : ListView
    data class Empty(val canClear: Boolean) : ListView
    data class Failed(val message: String) : ListView
}

/**
 * Under the titles: nothing, the next step being asked, its failure (with Try again), or the end at the cap: the first 500
 * shown ([Capped]), or for Trending filtered here the first 500 searched ([Searched]), of which only the matches show.
 */
sealed interface Footer {
    data object None : Footer
    data object Loading : Footer
    data class Failed(val message: String) : Footer
    data object Capped : Footer
    data object Searched : Footer
}

/** A filter's choice, by [id] (a period's or year's name, a genre's or service's TMDB id, a language's code; "" for any). */
data class FilterOption(val id: String, val label: String, val selected: Boolean)

/** A filter's pill: what is chosen ([label]) and what can be. */
data class FilterPill(val filter: Filter, val label: String, val options: List<FilterOption>)

/**
 * A list's See all page as it shows: its [title] ("Trending · Shows"), [pills] for the filters it takes (no year or
 * service for shows), the titles so far as [cards], what they are ([summary]), and the [footer] under them.
 */
data class DiscoverListUi(
    val key: KeyState?,
    val query: DiscoverQuery,
    val title: String,
    val cards: List<DiscoverCard>,
    val view: ListView,
    val footer: Footer,
    val pills: List<FilterPill>,
    val summary: String,
    val artwork: Boolean,
    val offline: Duration? = null,
) {
    /** A service pill shows: TMDB's streaming services are JustWatch's data, which says so. */
    val justWatch: Boolean get() = pills.any { it.filter == Filter.Service }
}

/** A list's See all title: "Trending · Shows". */
internal fun listTitle(query: DiscoverQuery): String = "${query.list.title} · ${sideName(query.type)}"

/**
 * What a See all page shows, in a line: "Drama trending this week · 3 already in your collection", "Top rated in Comedy
 * · Korean · 2010s · on Service A". [genre], [language] and [service] name the filters' choices; [collected] is how many
 * of the titles shown are in the collection.
 */
internal fun summary(query: DiscoverQuery, genre: String?, language: String?, service: String?, collected: Int): String {
    val f = query.filters
    val head = if (query.list == DiscoverList.Trending) {
        listOfNotNull(genre, "trending ${f.period.phrase}").joinToString(" ").replaceFirstChar { it.uppercaseChar() }
    } else {
        listOfNotNull(query.list.title, genre?.let { "in $it" }).joinToString(" ")
    }
    val narrowed = listOfNotNull(language, f.year.takeIf { it != YearFilter.Any }?.label, service?.let { "on $it" })
    val owned = listOfNotNull("$collected already in your collection".takeIf { collected > 0 })
    return (listOf(head) + narrowed + owned).joinToString(" · ")
}

/** What TalkBack hears of a row's See all: which list it opens ("See all, Trending this week"), as each row has one. */
internal fun seeAllSaid(heading: String): String = "$SEE_ALL, $heading"

/** Why a list has nothing to show, in words. A refused key and no key are the page's, never a row's. */
internal fun failureText(error: TmdbError): String = if (error is TmdbError.RateLimited) BUSY else LOAD_FAILED

/** "Offline · lists from 3 h ago": the age of the copies shown while TMDB can't be reached. */
internal fun offlineLine(age: Duration): String = OFFLINE_PREFIX + ago(age)

internal fun ago(age: Duration): String {
    val minutes = age.toMinutes()
    val hours = age.toHours()
    val days = age.toDays()
    return when {
        minutes < 60 -> "${minutes.coerceAtLeast(1)} min ago"
        hours < 24 -> "$hours h ago"
        days == 1L -> "yesterday"
        else -> "$days days ago"
    }
}

/**
 * Whether a list's next step is due: the last title shown ([lastShown], null before one is) within a TMDB page of the
 * [count] there are, so the step lands before the user reaches the end.
 */
internal fun nearEnd(lastShown: Int?, count: Int): Boolean = lastShown != null && count > 0 && lastShown >= count - PAGE_AHEAD

/** A TMDB page of titles. */
internal const val PAGE_AHEAD = 20

/** What Discover says; the tests match these. */
const val DISCOVER = "Discover"
const val SHOWS = "Shows"
const val MOVIES = "Movies"
const val IN_COLLECTION = "✓ In collection"
const val IN_YOUR_COLLECTION = "in your collection"
const val SEE_ALL = "See all"
const val ALL_GENRES = "All genres"
const val GENRE_PILL = "Genre"
const val ANY_LANGUAGE = "Any language"
const val ANY_SERVICE = "Any service"
const val NO_KEY_TITLE = "Discover needs a TMDB key"
const val NO_KEY_LINE = "Trending and popular lists come from TMDB with your own API key. Nothing is sent to TMDB until you add one."
const val ADD_KEY = "Add a TMDB key"
const val REFUSED_TITLE = "TMDB didn't accept your key"
const val REFUSED_LINE = "Check it in Settings › Metadata, or add a new one."
const val CHECK_KEY = "Check your TMDB key"
const val LOAD_FAILED = "Couldn't load from TMDB"
const val BUSY = "TMDB is busy: it is asked again in a moment"
const val ASKING_AGAIN = "Asking TMDB again…"
const val OFFLINE_PREFIX = "Offline · lists from "
const val NOTHING_MATCHES = "Nothing matches these filters"
const val CLEAR_FILTERS = "Clear filters"
const val LOADING_MORE = "Loading more…"
const val SHOWING_FIRST = "Showing the first 500"
const val SEARCHED_FIRST = "Searched the first 500 trending titles"
const val JUSTWATCH = "Streaming services from JustWatch"
const val FOOTNOTE = "Lists from TMDB. A title opens its page, where Play searches your indexers."
