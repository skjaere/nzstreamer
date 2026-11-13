package io.skjaere.usenet.app.refresh

import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.data.MovieSummary
import io.skjaere.usenet.data.RefreshCandidate
import io.skjaere.usenet.data.ShowSummary
import io.skjaere.usenet.metadata.Freshness
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the refresher is doing, for the lists' "TMDB unreachable" line. */
sealed interface RefreshStatus {
    data object Idle : RefreshStatus

    /** [left] titles due or being fetched. */
    data class Running(val left: Int) : RefreshStatus

    /** Not asking TMDB until [until] (epoch ms); null until something changes (a new key, the app coming back). */
    data class Paused(val reason: Reason, val until: Long?) : RefreshStatus

    enum class Reason { Offline, RateLimited, NoKey, InvalidKey }
}

/** Which title the refresher asks TMDB about next. */
object RefreshPlanner {
    private val RECENTLY_WATCHED = Duration.ofDays(30).toMillis()

    fun due(c: RefreshCandidate, now: Long) = (c.staleAt ?: 0) <= now && (c.retryAt ?: 0) <= now

    /**
     * The titles due now, most relevant first: watched within 30 days (most recent first), then those
     * on screen ([visible], in screen order), then those never answered (the newest first), then the
     * stalest. Titles in flight, fresh, or waiting to retry are left out.
     */
    fun order(candidates: Map<TitleKey, RefreshCandidate>, now: Long, visible: List<TitleKey>, inFlight: Set<TitleKey>): List<TitleKey> {
        val onScreen = visible.withIndex().associate { (i, key) -> key to i }
        fun tier(key: TitleKey, c: RefreshCandidate): Int = when {
            (c.lastWatchedAt ?: 0) > 0 && c.lastWatchedAt!! >= now - RECENTLY_WATCHED -> 0
            key in onScreen -> 1
            (c.fetchedAt ?: 0) == 0L -> 2
            else -> 3
        }
        return candidates.entries.filter { (key, c) -> key !in inFlight && due(c, now) }
            .map { (key, c) -> Triple(key, c, tier(key, c)) }
            .sortedWith(
                compareBy<Triple<TitleKey, RefreshCandidate, Int>> { it.third }.thenBy { (key, c, tier) ->
                    when (tier) {
                        0 -> -(c.lastWatchedAt ?: 0)
                        1 -> onScreen.getValue(key).toLong()
                        2 -> -c.addedAt
                        else -> c.staleAt ?: 0
                    }
                }.thenBy { it.first.value },
            )
            .map { it.first }
    }

    /** When the first title not in flight becomes due, if any is still to become due. */
    fun nextWake(candidates: Map<TitleKey, RefreshCandidate>, now: Long, inFlight: Set<TitleKey>): Long? =
        candidates.entries.filter { it.key !in inFlight }.map { (_, c) -> maxOf(c.staleAt ?: 0, c.retryAt ?: 0) }.filter { it > now }.minOrNull()
}

/** When stored facts are due again, by [Freshness] (the policy TmdbClient's cache keeps too). */
object Staleness {
    /** Never due again sooner: an answer at the very edge of its freshness would otherwise be asked for in a loop. */
    val MIN_RECHECK: Duration = Duration.ofMinutes(5)
    val NOT_FOUND_RETRY: Duration = Duration.ofDays(7)
    /** After answers couldn't be stored (a full disk). */
    val WRITE_RETRY: Duration = Duration.ofMinutes(1)

    /** Due after its policy, or from the start of its next episode's air day (in [zone]) if that comes first. */
    fun staleAt(s: ShowSummary, fetchedAt: Long, now: Long, zone: ZoneId): Long =
        staleAt(fetchedAt, policy(s, now, zone), s.next?.airDay?.let(LocalDate::ofEpochDay), now, zone)

    /** Due after its policy, or from the start of its release day if that comes first. */
    fun staleAt(m: MovieSummary, fetchedAt: Long, now: Long, zone: ZoneId): Long =
        staleAt(fetchedAt, policy(m, now, zone), m.releaseDay?.let(LocalDate::ofEpochDay), now, zone)

    fun policy(s: ShowSummary, now: Long, zone: ZoneId): Duration = Freshness.show(s.status, s.next?.airDay?.let(LocalDate::ofEpochDay), day(now, zone))

    fun policy(m: MovieSummary, now: Long, zone: ZoneId): Duration = Freshness.movie(m.releaseDay?.let(LocalDate::ofEpochDay), m.status, day(now, zone))

    /** After the [failures]th failure in a row: 15 min, 1 h, 6 h, then a day. */
    fun retryAfter(failures: Int): Duration = when {
        failures <= 1 -> Duration.ofMinutes(15)
        failures == 2 -> Duration.ofHours(1)
        failures == 3 -> Duration.ofHours(6)
        else -> Duration.ofDays(1)
    }

    /** The [times]th pause in a row: rate limited from 10 s, offline from 30 s, doubling to 2 and 15 minutes. */
    fun pause(reason: RefreshStatus.Reason, times: Int): Duration {
        val (first, most) = when (reason) {
            RefreshStatus.Reason.RateLimited -> Duration.ofSeconds(10) to Duration.ofMinutes(2)
            else -> Duration.ofSeconds(30) to Duration.ofMinutes(15)
        }
        return minOf(first.multipliedBy(1L shl (times - 1).coerceIn(0, 20)), most)
    }

    private fun staleAt(fetchedAt: Long, policy: Duration, day: LocalDate?, now: Long, zone: ZoneId): Long {
        val dayStarts = day?.takeIf { it.isAfter(day(now, zone)) }?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE
        return maxOf(minOf(fetchedAt + policy.toMillis(), dayStarts), now + MIN_RECHECK.toMillis())
    }

    private fun day(now: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
}
