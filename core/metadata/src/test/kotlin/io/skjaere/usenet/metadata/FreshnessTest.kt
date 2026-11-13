package io.skjaere.usenet.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreshnessTest {
    private val today = LocalDate.of(2026, 10, 13)

    @Test
    fun `a show whose next episode is due is asked about hourly, an ended one weekly, the rest daily`() {
        assertEquals(Duration.ofHours(1), Freshness.show("Returning Series", today, today))
        assertEquals(Duration.ofHours(1), Freshness.show("Returning Series", today.minusDays(3), today))
        assertEquals(Duration.ofHours(24), Freshness.show("Returning Series", today.plusDays(1), today))
        assertEquals(Duration.ofHours(24), Freshness.show("Returning Series", null, today))
        assertEquals(Duration.ofHours(24), Freshness.show(null, null, today))
        assertEquals(Duration.ofDays(7), Freshness.show("Ended", null, today))
        assertEquals(Duration.ofDays(7), Freshness.show("Canceled", null, today))
        assertTrue(Freshness.ended("Ended") && Freshness.ended("Canceled"))
        assertFalse(Freshness.ended("Returning Series") || Freshness.ended("In Production") || Freshness.ended(null))
    }

    @Test
    fun `a movie not out is asked about daily, a recent one weekly, an older one monthly`() {
        assertEquals(Duration.ofHours(24), Freshness.movie(today.plusDays(1), "Post Production", today))
        assertEquals(Duration.ofHours(24), Freshness.movie(null, "Planned", today))
        assertEquals(Duration.ofHours(24), Freshness.movie(null, null, today))
        // Out today, and out within 90 days.
        assertEquals(Duration.ofDays(7), Freshness.movie(today, "Released", today))
        assertEquals(Duration.ofDays(7), Freshness.movie(today.minusDays(89), "Released", today))
        assertEquals(Duration.ofDays(30), Freshness.movie(today.minusDays(90), "Released", today))
        assertEquals(Duration.ofDays(30), Freshness.movie(LocalDate.of(2019, 11, 8), "Released", today))
        // Released without a date: not upcoming.
        assertEquals(Duration.ofDays(7), Freshness.movie(null, "Released", today))
    }

    @Test
    fun `a next episode is due from its air day on`() {
        assertTrue(Freshness.nextEpisodeDue(today, today))
        assertTrue(Freshness.nextEpisodeDue(today.minusDays(1), today))
        assertFalse(Freshness.nextEpisodeDue(today.plusDays(1), today))
        assertFalse(Freshness.nextEpisodeDue(null, today))
    }

    private var now = Instant.parse("2026-10-13T12:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = now
    }

    /**
     * The lists' refresher trusts TmdbClient's answers for as long as [Freshness] says: the client
     * must never serve a cached answer older than that (unless offline, which the answer says).
     */
    @Test
    fun `TmdbClient never serves a cached answer older than the refresher's policy for it`() = runBlocking<Unit> {
        var body = ""
        var requests = 0
        val http = HttpClient(MockEngine {
            requests++
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(HttpTimeout); expectSuccess = false }
        val day = LocalDate.now(clock)
        val shows = listOf("Returning Series", "Ended", "Canceled", "In Production").flatMap { status ->
            listOf(null, day.minusDays(1), day, day.plusDays(1), day.plusDays(30)).map { status to it }
        }
        shows.forEachIndexed { i, (status, next) ->
            body = """{"id":$i,"name":"Avrel $i","status":"$status","seasons":[{"season_number":1,"episode_count":8}],""" +
                (next?.let { """"next_episode_to_air":{"season_number":1,"episode_number":5,"air_date":"$it"},""" } ?: "") +
                """"last_episode_to_air":{"season_number":1,"episode_number":4,"air_date":"2026-01-01"}}"""
            val policy = Freshness.show(status, next, day)
            val client = TmdbClient(http, { "0123456789abcdef0123456789abcdef" }, cache = InMemoryMetadataCache(clock), clock = clock)
            val start = now
            client.show(i)
            requests = 0
            now = start.plus(policy).plusSeconds(1)
            val aged = client.showAged(i)
            assertEquals(1, requests, "$status, next $next: a cached copy older than $policy served")
            assertEquals(Duration.ZERO, aged.age)
            now = start
        }
        val movies = listOf(null to "Planned", null to "Released", day.plusDays(10) to "Post Production", day to "Released", day.minusDays(60) to "Released", day.minusDays(400) to "Released")
        movies.forEachIndexed { i, (release, status) ->
            body = """{"id":$i,"title":"Ébrin $i","status":"$status"""" + (release?.let { ""","release_date":"$it"""" } ?: "") + "}"
            val policy = Freshness.movie(release, status, day)
            val client = TmdbClient(http, { "0123456789abcdef0123456789abcdef" }, cache = InMemoryMetadataCache(clock), clock = clock)
            val start = now
            client.movie(i)
            requests = 0
            now = start.plus(policy).plusSeconds(1)
            client.movieAged(i)
            assertEquals(1, requests, "$status, release $release: a cached copy older than $policy served")
            now = start
        }
    }
}
