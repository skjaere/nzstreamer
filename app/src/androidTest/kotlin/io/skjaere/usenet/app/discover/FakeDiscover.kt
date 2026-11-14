package io.skjaere.usenet.app.discover

import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.discover.DiscoverPage
import io.skjaere.usenet.discover.DiscoverQuery
import io.skjaere.usenet.discover.DiscoverSource
import io.skjaere.usenet.discover.DiscoverTitle
import io.skjaere.usenet.discover.Genre
import io.skjaere.usenet.discover.Language
import io.skjaere.usenet.discover.Service
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.MediaType
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Discover's lists for the device tests, never TMDB's: by default every list has [pages] pages of [size] invented titles
 * (the test runner installs one as every device test's default, [AppTestRunner]); [answer] answers in its place, a
 * test's own. [install] makes Discover's screens read it until [close], which puts back what they read before.
 * [asked] is every step asked, in order.
 */
class FakeDiscover(
    private val pages: Int = 3,
    private val size: Int = 20,
    private val answer: (suspend (DiscoverQuery, Int) -> Aged<DiscoverPage>)? = null,
    private val genreList: (MediaType) -> List<Genre> = { GENRES },
    private val serviceList: () -> List<Service> = { SERVICES },
) : DiscoverSource, AutoCloseable {
    val asked = CopyOnWriteArrayList<Pair<DiscoverQuery, Int>>()
    private var before: ((AppContainer) -> DiscoverSource)? = null

    fun install(): FakeDiscover = apply {
        before = DiscoverLookup.source
        DiscoverLookup.source = { this }
    }

    override fun close() {
        before?.let { DiscoverLookup.source = it }
        before = null
    }

    override suspend fun page(query: DiscoverQuery, from: Int, refresh: Boolean): Aged<DiscoverPage> {
        asked += query to from
        return answer?.invoke(query, from) ?: Aged(page(query, from, pages, size = size), Duration.ZERO)
    }

    override suspend fun genres(type: MediaType): List<Genre> = genreList(type)

    override suspend fun services(): List<Service> = serviceList()

    override fun languages(locale: Locale): List<Language> = LANGUAGES

    companion object {
        /** Every device test's lists ([AppTestRunner]). */
        val DEFAULT = FakeDiscover()

        /** TMDB's genre ids, with their names: what the chips show. */
        val GENRES = listOf(Genre(18, "Drama"), Genre(35, "Comedy"), Genre(80, "Crime"), Genre(99, "Documentary"))
        val SERVICES = listOf(Service(9001, "Service A"), Service(9002, "Service B"))
        val LANGUAGES = listOf(Language("en", "English"), Language("ko", "Korean"), Language("fr", "French"))

        private val NAMES = listOf(
            "Harbor Lights", "Lantern Row", "The Quiet Orbit", "Tarnwick", "Quayborne", "Ferrous", "Kestrel Hall", "Pebblereach",
            "Drift Season", "The Long Field", "Paper Gulls", "Sevenreach", "Coldwick Ferry", "Signal Moss", "Amber Line", "Hollow Larches",
            "Tin Mitre", "A Slow Tide", "Under Brass", "The Ninth Lamp",
        )

        /**
         * [query]'s page [from] of [total]: [size] invented titles (at most 20), their ids apart for each list and page
         * (970 000 and up, far from the fake catalogue's), named from invented names, the page's number after each.
         */
        fun page(query: DiscoverQuery, from: Int, total: Int, capped: Boolean = false, size: Int = 20): DiscoverPage =
            DiscoverPage((0 until size).map { title(query.type, id(query.list, from, it), "${NAMES[it]} $from") }, from, total, capped)

        fun id(list: DiscoverList, page: Int, index: Int) = 970_000 + list.ordinal * 2_000 + page * 20 + index

        fun title(type: MediaType, id: Int, name: String, year: Int? = 2021) = DiscoverTitle(type, id, name, year, null, listOf(18))
    }
}
