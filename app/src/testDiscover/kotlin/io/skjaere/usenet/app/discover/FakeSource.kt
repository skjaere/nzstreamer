package io.skjaere.usenet.app.discover

import io.skjaere.usenet.discover.DiscoverPage
import io.skjaere.usenet.discover.DiscoverQuery
import io.skjaere.usenet.discover.DiscoverSource
import io.skjaere.usenet.discover.DiscoverTitle
import io.skjaere.usenet.discover.Genre
import io.skjaere.usenet.discover.Language
import io.skjaere.usenet.discover.Service
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.MediaType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Duration
import java.util.Locale

/**
 * Discover's lists for the view models' tests: by default every list has [pages] pages of 20 invented titles, each
 * page's own; [answer] answers in its place. [asked] is every step asked, in order.
 */
class FakeSource(private val pages: Int = 3) : DiscoverSource {
    val asked = mutableListOf<Pair<DiscoverQuery, Int>>()
    var answer: suspend (DiscoverQuery, Int) -> Aged<DiscoverPage> = { query, from -> Aged(page(query.type, from, pages), Duration.ZERO) }
    var genreAnswer: suspend (MediaType) -> List<Genre> = { listOf(Genre(18, "Drama"), Genre(35, "Comedy")) }
    var serviceAnswer: suspend () -> List<Service> = { listOf(Service(8, "Service A"), Service(9, "Service B")) }
    val genresAsked = mutableListOf<MediaType>()

    override suspend fun page(query: DiscoverQuery, from: Int, refresh: Boolean): Aged<DiscoverPage> {
        asked += query to from
        return answer(query, from)
    }

    override suspend fun genres(type: MediaType): List<Genre> {
        genresAsked += type
        return genreAnswer(type)
    }

    override suspend fun services(): List<Service> = serviceAnswer()

    override fun languages(locale: Locale): List<Language> = listOf(Language("en", "English"), Language("ko", "Korean"))

    companion object {
        /** TMDB's page [from] of [total]: 20 invented titles of [type], their ids [from] × 100 + 1 on. */
        fun page(type: MediaType, from: Int, total: Int, capped: Boolean = false): DiscoverPage =
            DiscoverPage((1..20).map { title(type, from * 100 + it) }, from, total, capped)

        fun title(type: MediaType, id: Int, year: Int? = 2020) = DiscoverTitle(type, id, "Invented $id", year, "/p$id.jpg")
    }
}

/**
 * The view models' deps over [source], with the key, artwork, language and collection as flows a test sets: [key] null
 * leaves the key unread; each [setKey] is a key stored, as the settings' flow tells of each.
 */
class Fixture(
    val source: FakeSource = FakeSource(),
    key: Boolean? = true,
    knownKey: Boolean? = null,
) {
    private val keys = MutableSharedFlow<Boolean>(replay = 1)
    val artwork = MutableStateFlow(true)
    val language = MutableStateFlow<String?>(null)
    val shows = MutableStateFlow<Set<Int>>(emptySet())
    val movies = MutableStateFlow<Set<Int>>(emptySet())
    val deps = DiscoverDeps(
        hasTmdbKey = keys,
        showArtwork = artwork, language = language, collectedShows = shows, collectedMovies = movies,
        source = source, knownKey = knownKey, locale = { Locale.ENGLISH },
    )

    init {
        key?.let(::setKey)
    }

    fun setKey(key: Boolean) {
        keys.tryEmit(key)
    }
}
