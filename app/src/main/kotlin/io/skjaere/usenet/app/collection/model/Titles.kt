package io.skjaere.usenet.app.collection.model

/**
 * A collected title: "show:123" or "movie:456" (TMDB ids). The lists key their rows by [value], which
 * a Bundle can hold, the way Home's Up next keys a show's row ("show:<id>").
 */
@JvmInline
value class TitleKey(val value: String) {
    val isShow: Boolean get() = value.startsWith(SHOW)
    val tmdbId: Int get() = value.substringAfter(':').toInt()

    override fun toString() = value

    companion object {
        private const val SHOW = "show:"
        private const val MOVIE = "movie:"

        fun show(tmdbId: Int) = TitleKey(SHOW + tmdbId)
        fun movie(tmdbId: Int) = TitleKey(MOVIE + tmdbId)

        /** Null for anything but a show's or a movie's key. */
        fun parse(text: String): TitleKey? {
            val prefix = listOf(SHOW, MOVIE).firstOrNull { text.startsWith(it) } ?: return null
            return text.removePrefix(prefix).toIntOrNull()?.let { TitleKey(prefix + it) }
        }
    }
}

/** Collection › Shows or Movies. The settings keep these names; one they don't know reads as null (the default). */
enum class Side {
    Shows, Movies;

    companion object {
        fun byName(name: String) = entries.firstOrNull { it.name == name }
    }
}

enum class ShowFilter(val label: String) {
    All("All"), Watching("Watching"), NewEpisodes("New episodes"), NotStarted("Not started"), UpToDate("Up to date"), Finished("Finished");

    companion object {
        fun byName(name: String) = entries.firstOrNull { it.name == name }
    }
}

enum class ShowSort(val label: String) {
    RecentlyWatched("Recently watched"), NewEpisodes("New episodes"), NextToAir("Next to air"), RecentlyAdded("Recently added"), AZ("A–Z");

    companion object {
        fun byName(name: String) = entries.firstOrNull { it.name == name }
    }
}

enum class MovieFilter(val label: String) {
    All("All"), Unwatched("Unwatched"), InProgress("In progress"), Watched("Watched"), NotYetReleased("Not yet released");

    companion object {
        fun byName(name: String) = entries.firstOrNull { it.name == name }
    }
}

enum class MovieSort(val label: String) {
    RecentlyAdded("Recently added"), RecentlyWatched("Recently watched"), AZ("A–Z"), ReleaseDate("Release date");

    companion object {
        fun byName(name: String) = entries.firstOrNull { it.name == name }
    }
}

/** What is known of a title from TMDB: [Ready] once facts are stored; otherwise why not yet. */
enum class LoadState { Ready, Pending, Failed, NotFound, NoKey }

/** [Unknown]: started, but nothing known from TMDB yet to say how far. */
enum class ShowState { NotStarted, Watching, UpToDate, Finished, Unknown }

/** [Upcoming]: out after today, or no date announced. [Unknown]: nothing known from TMDB and never played. */
enum class MovieState { NotWatched, InProgress, Watched, Upcoming, Unknown }
