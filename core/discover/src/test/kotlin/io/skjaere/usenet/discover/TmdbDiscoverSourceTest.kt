package io.skjaere.usenet.discover

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.skjaere.usenet.discover.DiscoverList.AiringToday
import io.skjaere.usenet.discover.DiscoverList.NowPlaying
import io.skjaere.usenet.discover.DiscoverList.OnTheAir
import io.skjaere.usenet.discover.DiscoverList.Popular
import io.skjaere.usenet.discover.DiscoverList.TopRated
import io.skjaere.usenet.discover.DiscoverList.Trending
import io.skjaere.usenet.discover.DiscoverList.Upcoming
import io.skjaere.usenet.featurecheck.Discover as ReleaseGuard
import io.skjaere.usenet.metadata.InMemoryMetadataCache
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.MediaType.Movie
import io.skjaere.usenet.metadata.MediaType.Tv
import io.skjaere.usenet.metadata.TmdbClient
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Discover's lists from TMDB, against a MockEngine with invented titles. Days are Paris's. */
class TmdbDiscoverSourceTest {
    private val v3Key = "0123456789abcdef0123456789abcdef"

    /** Thursday 22 October 2026, 12:00 in Paris. */
    private var now = Instant.parse("2026-10-22T10:00:00Z")
    private fun clockIn(zone: ZoneId): Clock = object : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId?) = clockIn(zone!!)
        override fun instant() = now
    }
    private val clock = clockIn(ZoneId.of("Europe/Paris"))

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/tmdb/$name")).readText()

    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private var failNetwork = false
    /** TMDB unreachable for this page on (the ones before it answered). */
    private var failFrom = Int.MAX_VALUE
    /** TMDB's list moving between two requests: page 2 starts with page 1's first title, a drama. */
    private var moved = false
    /** TMDB saying to slow down (429). */
    private var limit = false
    /** Page 1 of these paths is a fixture; every other page is [generated]. */
    private val pageOne = mapOf("trending/tv/week" to "trending-tv-week.json", "movie/popular" to "movie-popular.json")

    private val http = HttpClient(MockEngine { request ->
        requests += request
        val path = request.url.encodedPath.removePrefix("/3/")
        val page = request.url.parameters["page"]?.toInt() ?: 1
        if (failNetwork || page >= failFrom) throw IOException("unreachable")
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        if (limit) {
            respond("""{"status_code":25,"status_message":"Your request count (41) is over the allowed limit of (40)."}""", HttpStatusCode.TooManyRequests, json)
        } else {
            val body = when {
                path == "genre/tv/list" -> fixture("genre-tv-list.json")
                path == "genre/movie/list" -> fixture("genre-movie-list.json")
                path == "watch/providers/movie" -> providers()
                page == 1 && path in pageOne -> fixture(pageOne.getValue(path))
                else -> generated(path, page)
            }
            respond(body, HttpStatusCode.OK, json)
        }
    }) {
        install(HttpTimeout)
        expectSuccess = false
    }

    /**
     * TMDB's page [page] of [path]: 20 invented titles of the path's type, without media_type (as every list but
     * Trending has them), released 2010 to 2019; three dramas (genre 18), seventeen comedies (35), all in English.
     */
    private fun generated(path: String, page: Int): String {
        val movie = "movie" in path
        val items = (0 until 20).joinToString(",") { i ->
            val id = 930000 + (if (moved && page == 2 && i == 0) 1 else page) * 100 + i
            val genre = if (i % 7 == 0) 18 else 35
            val date = "${2010 + i % 10}-05-01"
            if (movie) """{"id":$id,"title":"Film $page-$i","original_language":"en","release_date":"$date","genre_ids":[$genre]}"""
            else """{"id":$id,"name":"Show $page-$i","original_language":"en","first_air_date":"$date","genre_ids":[$genre]}"""
        }
        return """{"page":$page,"results":[$items],"total_pages":$GENERATED_PAGES,"total_results":${GENERATED_PAGES * 20}}"""
    }

    /** 25 invented services in FR, TMDB's display priority there the reverse of their numbers; one more only in CA. */
    private fun providers(): String {
        val listed = (1..25).joinToString(",") { i ->
            """{"provider_id":${100 + i},"provider_name":"Service $i","logo_path":"/s$i.jpg","display_priority":$i,"display_priorities":{"FR":${26 - i},"CA":$i}}"""
        }
        val elsewhere = """{"provider_id":199,"provider_name":"Service Elsewhere","display_priority":0,"display_priorities":{"CA":0}}"""
        return """{"results":[$elsewhere,$listed]}"""
    }

    private var key: String? = v3Key
    private var language = "en-US"
    private var country = "FR"

    private fun source(clock: Clock = this.clock) =
        TmdbDiscoverSource(TmdbClient(http, { key }, { language }, InMemoryMetadataCache(clock), clock), clock, country = { country })

    private val source = source()

    private fun path() = requests.last().url.encodedPath.removePrefix("/3/")
    private fun sent(): Map<String, String> = requests.last().url.parameters.let { p -> p.names().associateWith { p[it]!! } } - setOf("api_key", "language")

    private val drama = DiscoverFilters(genre = 18)

    @Test
    fun `each side has its lists, in the tab's order, with their headings`() {
        assertEquals(listOf(Trending, Popular, TopRated, AiringToday, OnTheAir), DiscoverList.on(Tv))
        assertEquals(listOf(Trending, Popular, TopRated, NowPlaying, Upcoming), DiscoverList.on(Movie))
        assertEquals(
            listOf("Trending this week", "Trending today", "Top rated", "On the air"),
            listOf(Trending.heading(Period.Week), Trending.heading(Period.Today), TopRated.heading(Period.Week), OnTheAir.heading(Period.Today)),
        )
        assertEquals(listOf("Today", "This week"), Period.entries.map { it.label })
    }

    /** A design decision: "we dont need the year and service selectors in tv shows". */
    @Test
    fun `shows offer the period on Trending, the genre and the language, movies add the year and a service except on Trending`() {
        assertEquals(listOf(Filter.Period, Filter.Genre, Filter.Language), Trending.filters(Tv))
        DiscoverList.on(Tv).minus(Trending).forEach { assertEquals(listOf(Filter.Genre, Filter.Language), it.filters(Tv), it.title) }
        assertEquals(listOf(Filter.Period, Filter.Genre, Filter.Language, Filter.Year), Trending.filters(Movie))
        DiscoverList.on(Movie).minus(Trending).forEach { assertEquals(listOf(Filter.Genre, Filter.Language, Filter.Year, Filter.Service), it.filters(Movie), it.title) }
    }

    @Test
    fun `a show list never takes a year or a service, nor a list another side's`() {
        val all = DiscoverFilters(period = Period.Today, genre = 18, language = "ko", year = YearFilter.Tens, service = 108)
        assertEquals(DiscoverFilters(period = Period.Today, genre = 18, language = "ko"), all.on(Tv, Trending))
        assertEquals(DiscoverFilters(genre = 18, language = "ko"), all.on(Tv, Popular))
        assertEquals(DiscoverFilters(period = Period.Today, genre = 18, language = "ko", year = YearFilter.Tens), all.on(Movie, Trending))
        assertEquals(all.copy(period = Period.Week), all.on(Movie, Upcoming))
        assertFailsWith<IllegalArgumentException> { DiscoverQuery(Tv, Popular, DiscoverFilters(year = YearFilter.Tens)) }
        assertFailsWith<IllegalArgumentException> { DiscoverQuery(Tv, OnTheAir, DiscoverFilters(service = 108)) }
        assertFailsWith<IllegalArgumentException> { DiscoverQuery(Movie, Trending, DiscoverFilters(service = 108)) }
        assertFailsWith<IllegalArgumentException> { DiscoverQuery(Movie, Popular, DiscoverFilters(period = Period.Today)) }
        assertFailsWith<IllegalArgumentException> { DiscoverQuery(Tv, NowPlaying) }
        assertFailsWith<IllegalArgumentException> { DiscoverQuery(Movie, AiringToday) }
        // Trending's period picks the list; only the others narrow it.
        assertFalse(DiscoverFilters(period = Period.Today).narrows)
        listOf(DiscoverFilters(genre = 18), DiscoverFilters(language = "cn"), DiscoverFilters(year = YearFilter.Older), DiscoverFilters(service = 108)).forEach { assertTrue(it.narrows, "$it") }
    }

    @Test
    fun `the year pill's years count from this year`() {
        val today = LocalDate.of(2026, 10, 22)
        assertEquals(listOf("Any year", "This year", "Last year", "2020s", "2010s", "2000s", "1990s", "Older"), YearFilter.entries.map { it.label })
        assertNull(YearFilter.Any.years(today))
        assertEquals(YearSpan(2026, 2026), YearFilter.ThisYear.years(today))
        assertEquals(YearSpan(2025, 2025), YearFilter.LastYear.years(today))
        assertEquals(
            listOf(YearSpan(2020, 2029), YearSpan(2010, 2019), YearSpan(2000, 2009), YearSpan(1990, 1999), YearSpan(null, 1989)),
            listOf(YearFilter.Twenties, YearFilter.Tens, YearFilter.Noughties, YearFilter.Nineties, YearFilter.Older).map { it.years(today) },
        )
        assertTrue(1950 in YearSpan(null, 1989))
        assertTrue(2019 in YearSpan(2010, 2019))
        assertFalse(2020 in YearSpan(2010, 2019))
        assertFalse(2009 in YearSpan(2010, 2019))
        assertFalse(null in YearSpan(null, 1989))
    }

    @Test
    fun `a list without filters asks for its own endpoint`() = runBlocking<Unit> {
        val endpoints = listOf(
            DiscoverQuery(Tv, Trending) to "trending/tv/week",
            DiscoverQuery(Tv, Trending, DiscoverFilters(period = Period.Today)) to "trending/tv/day",
            DiscoverQuery(Tv, Popular) to "tv/popular",
            DiscoverQuery(Tv, TopRated) to "tv/top_rated",
            DiscoverQuery(Tv, AiringToday) to "tv/airing_today",
            DiscoverQuery(Tv, OnTheAir) to "tv/on_the_air",
            DiscoverQuery(Movie, Trending) to "trending/movie/week",
            DiscoverQuery(Movie, Trending, DiscoverFilters(period = Period.Today)) to "trending/movie/day",
            DiscoverQuery(Movie, Popular) to "movie/popular",
            DiscoverQuery(Movie, TopRated) to "movie/top_rated",
            DiscoverQuery(Movie, NowPlaying) to "movie/now_playing",
            DiscoverQuery(Movie, Upcoming) to "movie/upcoming",
        )
        endpoints.forEach { (query, path) ->
            source.page(query, from = 2)
            val expected = buildMap {
                put("include_adult", "false")
                if (query.type == Movie) put("include_video", "false")
                put("page", "2")
                if (query.list == NowPlaying || query.list == Upcoming) put("region", "FR")
                if (query.list == AiringToday || query.list == OnTheAir) put("timezone", "Europe/Paris")
            }
            assertEquals(path to expected, path() to sent(), "$query")
        }
    }

    @Test
    fun `a filtered list asks TMDB's discover for the same list with the filter`() = runBlocking<Unit> {
        suspend fun asked(query: DiscoverQuery): Map<String, String> {
            source.page(query)
            assertEquals(if (query.type == Movie) "discover/movie" else "discover/tv", path(), "$query")
            return sent()
        }
        val movies = mapOf("include_adult" to "false", "include_video" to "false", "page" to "1")
        val shows = mapOf("include_adult" to "false", "page" to "1")
        assertEquals(movies + mapOf("sort_by" to "popularity.desc", "with_genres" to "18"), asked(DiscoverQuery(Movie, Popular, drama)))
        assertEquals(shows + mapOf("sort_by" to "popularity.desc", "with_genres" to "18"), asked(DiscoverQuery(Tv, Popular, drama)))
        // TMDB's own code, as it is: Cantonese.
        assertEquals(
            shows + mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "200", "with_original_language" to "cn"),
            asked(DiscoverQuery(Tv, TopRated, DiscoverFilters(language = "cn"))),
        )
        assertEquals(
            shows + mapOf("sort_by" to "popularity.desc", "air_date.gte" to "2026-10-22", "air_date.lte" to "2026-10-22", "timezone" to "Europe/Paris", "with_genres" to "18"),
            asked(DiscoverQuery(Tv, AiringToday, drama)),
        )
        assertEquals(
            shows + mapOf("sort_by" to "popularity.desc", "air_date.gte" to "2026-10-22", "air_date.lte" to "2026-10-29", "timezone" to "Europe/Paris", "with_genres" to "18"),
            asked(DiscoverQuery(Tv, OnTheAir, drama)),
        )
        // In cinemas in the region in the last six weeks; coming in the next four.
        assertEquals(
            movies + mapOf(
                "sort_by" to "popularity.desc", "with_release_type" to "2|3", "release_date.gte" to "2026-09-10", "release_date.lte" to "2026-10-22",
                "region" to "FR", "primary_release_year" to "2026",
            ),
            asked(DiscoverQuery(Movie, NowPlaying, DiscoverFilters(year = YearFilter.ThisYear))),
        )
        assertEquals(
            movies + mapOf(
                "sort_by" to "popularity.desc", "with_release_type" to "2|3", "release_date.gte" to "2026-10-23", "release_date.lte" to "2026-11-19",
                "region" to "FR", "with_watch_providers" to "108", "watch_region" to "FR", "with_watch_monetization_types" to "flatrate",
            ),
            asked(DiscoverQuery(Movie, Upcoming, DiscoverFilters(service = 108))),
        )
        assertEquals(
            movies + mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "200", "primary_release_date.gte" to "2010-01-01", "primary_release_date.lte" to "2019-12-31"),
            asked(DiscoverQuery(Movie, TopRated, DiscoverFilters(year = YearFilter.Tens))),
        )
        assertEquals(
            movies + mapOf("sort_by" to "popularity.desc", "primary_release_date.lte" to "1989-12-31", "with_watch_providers" to "108", "watch_region" to "FR", "with_watch_monetization_types" to "flatrate"),
            asked(DiscoverQuery(Movie, Popular, DiscoverFilters(year = YearFilter.Older, service = 108))),
        )
    }

    @Test
    fun `trending leaves out people, adult titles and nameless ones, and has each title once`() = runBlocking<Unit> {
        val page = source.page(DiscoverQuery(Tv, Trending)).value
        assertEquals(
            listOf(
                DiscoverTitle(Tv, 910001, "Harbor Lights", 2021, "/hl.jpg", listOf(18, 9648), "en"),
                DiscoverTitle(Tv, 910002, "Lantern Row", 2024, "/lr.jpg", listOf(18), "ko"),
                DiscoverTitle(Tv, 910005, "Saltmarsh", null, null, emptyList(), "cn"),
            ),
            page.items,
        )
    }

    @Test
    fun `a list's titles are of the type asked for`() = runBlocking<Unit> {
        val answer = source.page(DiscoverQuery(Movie, Popular))
        assertEquals(
            DiscoverPage(
                listOf(
                    DiscoverTitle(Movie, 920001, "Copper Crown", 2019, "/cc.jpg", listOf(18, 36), "de"),
                    DiscoverTitle(Movie, 920002, "Lantern Field", 2027, null, listOf(35), "cn"),
                ),
                page = 1, totalPages = 3, capped = false,
            ),
            answer.value,
        )
        assertTrue(answer.value.hasMore)
        assertEquals(Duration.ZERO to false, answer.age to answer.offline)
    }

    @Test
    fun `a list stops at 25 pages`() = runBlocking<Unit> {
        val trending = source.page(DiscoverQuery(Tv, Trending)).value
        assertEquals(25 to true, trending.totalPages to trending.capped)
        val last = source.page(DiscoverQuery(Movie, Popular, drama), from = 25).value
        assertEquals(Triple(25, 25, false), Triple(last.page, last.totalPages, last.hasMore))
        assertEquals(2, requests.size)
        listOf(0, 26).forEach { assertFailsWith<IllegalArgumentException> { source.page(DiscoverQuery(Movie, Popular), from = it) } }
        assertEquals(2, requests.size)
    }

    @Test
    fun `trending with filters is filtered here, reading on until a screenful matches or five pages were read`() = runBlocking<Unit> {
        val dramas = DiscoverQuery(Movie, Trending, drama)
        // Three dramas a page: five pages give fifteen, and the next step goes on from page 6.
        val first = source.page(dramas).value
        assertEquals(15 to 5, first.items.size to first.page)
        assertTrue(first.items.all { 18 in it.genreIds })
        assertEquals((1..5).map { "$it" }, requests.map { it.url.parameters["page"] })
        assertTrue(requests.all { it.url.encodedPath == "/3/trending/movie/week" && it.url.parameters["with_genres"] == null })
        val second = source.page(dramas, from = first.page + 1).value
        assertEquals(15 to 10, second.items.size to second.page)
        assertEquals(30, first.items.plusNew(second.items).size)
        // Seventeen comedies a page: two pages fill a screen.
        val comedies = source.page(DiscoverQuery(Movie, Trending, DiscoverFilters(genre = 35))).value
        assertEquals(34 to 2, comedies.items.size to comedies.page)
        // By original language and release year too: nothing in Korean, nothing from this year, every one from the 2010s.
        assertEquals(0 to 5, source.page(DiscoverQuery(Movie, Trending, DiscoverFilters(language = "ko"))).value.let { it.items.size to it.page })
        assertEquals(0 to 5, source.page(DiscoverQuery(Movie, Trending, DiscoverFilters(year = YearFilter.ThisYear))).value.let { it.items.size to it.page })
        assertEquals(20 to 1, source.page(DiscoverQuery(Movie, Trending, DiscoverFilters(year = YearFilter.Tens))).value.let { it.items.size to it.page })
        // Shows' trending the same way; never past the last page.
        val shows = source.page(DiscoverQuery(Tv, Trending, drama), from = 24).value
        assertEquals(Triple(6, 25, false), Triple(shows.items.size, shows.page, shows.hasMore))
    }

    @Test
    fun `a step of filtered trending has each title once, though TMDB's pages share one`() = runBlocking<Unit> {
        moved = true
        // The tab's row shows a step as it comes, its cards keyed by title: a title twice would break the row.
        val step = source.page(DiscoverQuery(Movie, Trending, drama)).value
        assertEquals(14 to 5, step.items.size to step.page)
        assertEquals(step.items.distinctBy { it.type to it.tmdbId }, step.items)
    }

    @Test
    fun `each list is kept as long as it is likely to stay the same`() = runBlocking<Unit> {
        val kept = listOf(
            DiscoverQuery(Tv, Trending, DiscoverFilters(period = Period.Today)) to Duration.ofHours(1),
            DiscoverQuery(Movie, Trending) to Duration.ofHours(6),
            DiscoverQuery(Tv, Popular) to Duration.ofHours(6),
            DiscoverQuery(Movie, Popular) to Duration.ofHours(6),
            DiscoverQuery(Tv, TopRated) to Duration.ofHours(24),
            DiscoverQuery(Movie, TopRated) to Duration.ofHours(24),
            DiscoverQuery(Tv, OnTheAir) to Duration.ofHours(6),
            DiscoverQuery(Movie, Popular, drama) to Duration.ofHours(6),
            DiscoverQuery(Tv, TopRated, DiscoverFilters(language = "ja")) to Duration.ofHours(6),
        )
        kept.forEach { (query, life) ->
            val source = source()
            val start = now
            requests.clear()
            source.page(query)
            now = start.plus(life).minusSeconds(60)
            source.page(query)
            assertEquals(1, requests.size, "$query within $life")
            now = start.plus(life).plusSeconds(60)
            source.page(query)
            assertEquals(2, requests.size, "$query after $life")
        }
        // A refresh asks again whatever is kept.
        requests.clear()
        source.page(DiscoverQuery(Tv, TopRated))
        source.page(DiscoverQuery(Tv, TopRated), refresh = true)
        assertEquals(2, requests.size)
    }

    /** Their lists are today's, so a copy from before midnight is yesterday's; the other lists' are good for longer. */
    @Test
    fun `now playing, upcoming and airing today are asked for again after midnight`() = runBlocking<Unit> {
        val today = listOf(DiscoverQuery(Movie, NowPlaying), DiscoverQuery(Movie, Upcoming), DiscoverQuery(Tv, AiringToday))
        val longer = listOf(DiscoverQuery(Tv, OnTheAir), DiscoverQuery(Movie, Trending, DiscoverFilters(period = Period.Today)))
        // 23:30 in Paris.
        now = Instant.parse("2026-10-22T21:30:00Z")
        (today + longer).forEach { source.page(it) }
        now = Instant.parse("2026-10-22T21:50:00Z")
        (today + longer).forEach { source.page(it) }
        assertEquals(5, requests.size)
        // 00:10.
        now = Instant.parse("2026-10-22T22:10:00Z")
        (today + longer).forEach { source.page(it) }
        assertEquals(listOf("movie/now_playing", "movie/upcoming", "tv/airing_today"), requests.drop(5).map { it.url.encodedPath.removePrefix("/3/") })
    }

    @Test
    fun `the region and the timezone are the device's`() = runBlocking<Unit> {
        country = "CA"
        source.page(DiscoverQuery(Movie, NowPlaying))
        assertEquals("CA", sent()["region"])
        // A language without a country, or a region TMDB doesn't take ("419", Latin America): no region, and no services.
        listOf("", "419").forEach { c ->
            country = c
            source.page(DiscoverQuery(Movie, Upcoming, drama))
            assertNull(sent()["region"], "country \"$c\"")
            val before = requests.size
            assertEquals(emptyList(), source.services())
            assertEquals(before, requests.size)
        }
        source(clockIn(ZoneId.of("Asia/Tokyo"))).page(DiscoverQuery(Tv, AiringToday))
        assertEquals("Asia/Tokyo", sent()["timezone"])
    }

    @Test
    fun `genres are each side's own, in the TMDB language, kept a week`() = runBlocking<Unit> {
        assertEquals(listOf(Genre(10759, "Action & Adventure"), Genre(18, "Drama"), Genre(10765, "Sci-Fi & Fantasy")), source.genres(Tv))
        assertEquals("genre/tv/list" to "en-US", path() to requests.last().url.parameters["language"])
        assertEquals(listOf(Genre(28, "Action"), Genre(18, "Drama"), Genre(35, "Comedy")), source.genres(Movie))
        assertEquals("genre/movie/list", path())
        val start = now
        now = start.plus(Duration.ofDays(7)).minusSeconds(60)
        source.genres(Tv)
        assertEquals(2, requests.size)
        now = start.plus(Duration.ofDays(7)).plusSeconds(60)
        source.genres(Tv)
        assertEquals(3, requests.size)
        language = "es-ES"
        source.genres(Tv)
        assertEquals("es-ES", requests.last().url.parameters["language"])
    }

    @Test
    fun `services are the region's top 20 by TMDB's display priority, by name only, kept a week`() = runBlocking<Unit> {
        assertEquals((25 downTo 6).map { Service(100 + it, "Service $it") }, source.services())
        assertEquals("watch/providers/movie" to mapOf("watch_region" to "FR"), path() to sent())
        val start = now
        now = start.plus(Duration.ofDays(7)).minusSeconds(60)
        source.services()
        assertEquals(1, requests.size)
        now = start.plus(Duration.ofDays(7)).plusSeconds(60)
        source.services()
        assertEquals(2, requests.size)
    }

    @Test
    fun `the languages are twenty common ones, named in the device's language`() {
        val english = source.languages(Locale.ENGLISH)
        assertEquals(20, english.size)
        assertEquals(english.sortedBy { it.name }, english)
        val names = english.associate { it.code to it.name }
        assertEquals("Japanese" to "Korean", names["ja"] to names["ko"])
        // TMDB's own code for Cantonese, beside Chinese.
        assertEquals("Cantonese" to "Chinese", names["cn"] to names["zh"])
        val german = source.languages(Locale.GERMAN)
        assertEquals(names.keys, german.map { it.code }.toSet())
        assertEquals("Japanisch", german.single { it.code == "ja" }.name)
    }

    @Test
    fun `a list shows the copy TMDB gave last while it says to slow down, or can't be reached`() = runBlocking<Unit> {
        val popular = DiscoverQuery(Movie, Popular)
        source.page(popular)
        now = now.plus(Duration.ofHours(7))
        limit = true
        val limited = source.page(popular)
        assertEquals(Triple(Duration.ofHours(7), true, false), Triple(limited.age, limited.rateLimited, limited.offline))
        assertEquals("Copper Crown", limited.value.items.first().title)
        // Nothing kept: TMDB's answer.
        assertIs<TmdbError.RateLimited>(assertFailsWith<TmdbException> { source.page(DiscoverQuery(Tv, Popular)) }.error)
        limit = false
        failNetwork = true
        val offline = source.page(popular)
        assertEquals(true to false, offline.offline to offline.rateLimited)
    }

    @Test
    fun `filtered trending stops at a copy TMDB gave last, asking nothing more while it says to slow down or can't be reached`() = runBlocking<Unit> {
        val trending = DiscoverQuery(Movie, Trending)
        val dramas = DiscoverQuery(Movie, Trending, drama)
        // Page 1 kept by the unfiltered list (the tab's row): three dramas on it.
        source.page(trending)
        now = now.plus(Duration.ofHours(7))
        failNetwork = true
        requests.clear()
        val offline = source.page(dramas)
        assertEquals(Triple(3, 1, true), Triple(offline.value.items.size, offline.value.page, offline.offline))
        assertEquals(1, requests.size, "nothing asked past the copy while offline")
        failNetwork = false
        limit = true
        requests.clear()
        val limited = source.page(dramas)
        assertEquals(Triple(3, 1, true), Triple(limited.value.items.size, limited.value.page, limited.rateLimited))
        assertEquals(1, requests.size, "nothing more sent after TMDB said to slow down")
    }

    @Test
    fun `filtered trending keeps the titles of the pages read when a later one fails, and the next step goes on from it`() = runBlocking<Unit> {
        val dramas = DiscoverQuery(Movie, Trending, drama)
        failFrom = 3
        val step = source.page(dramas)
        assertEquals(Triple(6, 2, false), Triple(step.value.items.size, step.value.page, step.offline))
        // The page that failed is the next step's first: its failure is the step's.
        assertIs<TmdbError.Network>(assertFailsWith<TmdbException> { source.page(dramas, from = step.value.page + 1) }.error)
    }

    @Test
    fun `without a key nothing is sent`() = runBlocking<Unit> {
        key = null
        assertIs<TmdbError.NoKey>(assertFailsWith<TmdbException> { source.page(DiscoverQuery(Tv, Trending)) }.error)
        assertIs<TmdbError.NoKey>(assertFailsWith<TmdbException> { source.page(DiscoverQuery(Movie, Trending, drama)) }.error)
        assertIs<TmdbError.NoKey>(assertFailsWith<TmdbException> { source.genres(Movie) }.error)
        assertIs<TmdbError.NoKey>(assertFailsWith<TmdbException> { source.services() }.error)
        assertEquals(0, requests.size)
    }

    @Test
    fun `pages put together keep each title once`() {
        val a = DiscoverTitle(Tv, 1, "Alder Coast", 2020, null)
        val b = DiscoverTitle(Movie, 1, "Birch Hollow", 2021, null)
        val c = DiscoverTitle(Tv, 2, "Cedar Point", null, null)
        assertEquals(listOf(a, b, c), listOf(a, b).plusNew(listOf(b.copy(title = "Birch Hollow again"), c, a, c)))
    }

    /**
     * Every path the source asks TMDB for is a whole literal in its classes (never put together from parts), each holds
     * one of the markers the app's release guard (checkNoDiscover) fails on, read from the guard's own list, and each
     * marker is in a path asked for. The guard's positive control finds every marker in the debug build, so no path can
     * escape the release check: a new path fails here until the guard looks for it.
     */
    @Test
    fun `every path asked for is a literal the release guard looks for`() = runBlocking<Unit> {
        MediaType.entries.forEach { type ->
            DiscoverList.on(type).forEach { list ->
                val periods = if (list == Trending) Period.entries else listOf(Period.Week)
                periods.forEach { period ->
                    source.page(DiscoverQuery(type, list, DiscoverFilters(period = period)))
                    source.page(DiscoverQuery(type, list, DiscoverFilters(period = period, genre = 18)))
                }
            }
            source.genres(type)
        }
        source.services()
        val paths = requests.map { it.url.encodedPath.removePrefix("/3/") }.toSortedSet()
        assertEquals(
            sortedSetOf(
                "trending/tv/day", "trending/tv/week", "trending/movie/day", "trending/movie/week",
                "tv/popular", "tv/top_rated", "tv/airing_today", "tv/on_the_air",
                "movie/popular", "movie/top_rated", "movie/now_playing", "movie/upcoming",
                "discover/tv", "discover/movie", "genre/tv/list", "genre/movie/list", "watch/providers/movie",
            ),
            paths,
        )
        val classes = classFiles()
        paths.forEach { path -> assertTrue(classes.any { it.hasUtf8(path) }, "\"$path\" is not a literal in io/skjaere/usenet/discover") }
        paths.forEach { path -> assertTrue(ReleaseGuard.listPaths.any { it in path }, "none of the release guard's markers in \"$path\"") }
        ReleaseGuard.listPaths.forEach { marker -> assertTrue(paths.any { marker in it }, "the release guard's \"$marker\" in no path asked for") }
    }

    /** The module's compiled classes, as the tests run them. */
    private fun classFiles(): List<ByteArray> {
        val root = File(TmdbDiscoverSource::class.java.protectionDomain.codeSource.location.toURI())
        return root.resolve("io/skjaere/usenet/discover").walk().filter { it.extension == "class" }.map { it.readBytes() }.toList()
    }

    /** Whether a class file's constant pool has [text] as a whole string (a CONSTANT_Utf8: tag 1, its length, its bytes). */
    private fun ByteArray.hasUtf8(text: String): Boolean {
        val bytes = text.toByteArray()
        val entry = byteArrayOf(1, (bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes
        return (0..size - entry.size).any { i -> entry.indices.all { this[i + it] == entry[it] } }
    }

    private companion object {
        const val GENERATED_PAGES = 40
    }
}
