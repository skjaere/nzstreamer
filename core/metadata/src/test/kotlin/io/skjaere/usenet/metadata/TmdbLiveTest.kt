package io.skjaere.usenet.metadata

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Against the real API; skipped unless TMDB_KEY is set (see build.gradle.kts). */
class TmdbLiveTest {
    private val key = System.getenv("TMDB_KEY")

    @Test
    fun `real key validates and a search returns shows with seasons`() = runBlocking<Unit> {
        assumeTrue(!key.isNullOrBlank(), "TMDB_KEY not set")
        val client = TmdbClient(TmdbClient.defaultHttpClient(), { key })
        assertEquals(KeyCheck.Valid, client.validate(key))
        val show = client.searchTitles("ocean").first { it.type == MediaType.Tv }
        val details = client.show(show.tmdbId)
        assertTrue(details.seasons.isNotEmpty(), "no seasons for ${details.name}")
        client.season(show.tmdbId, details.seasons.first { !it.isSpecials }.number)
    }
}
