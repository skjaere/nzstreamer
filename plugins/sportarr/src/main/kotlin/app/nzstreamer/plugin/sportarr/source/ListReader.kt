package app.nzstreamer.plugin.sportarr.source

import io.skjaere.usenet.plugin.PluginHttpException.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext

/**
 * One read of what the user follows (L641, L642): `GET /api/leagues` once, then, for each league that counts, in the order
 * Sportarr gives them, its events a page at a time (1000 a page). One request at a time, nothing else asked: no calendar,
 * no event by id, no showAll, no season, no event stream.
 *
 * A league's walk stops at the first of: a page whose oldest event starts before the window, while every page so far ran
 * newest first; the last page (its number reaching totalPages, or no records); six pages, the budget. A page out of
 * newest-first order, or starting after the page before ended, turns that check off: the walk then reads on to the last
 * page or the budget, and keeps what falls in the window. A plain array (a server before 4.1.10, which ignores paging) is
 * the whole league at once.
 *
 * The window runs from the start of the local day 60 days before [now] to the end of the local day 31 days after it, in
 * [zone], each start compared as UTC. Of what counts in it, the newest 5,000 by start are kept. The read commits all or
 * nothing: any request without a 200 and a body that reads fails it, a 401 or 403 as Refused; only a league that answers
 * 404 (removed between the two reads) is left out. Bodies are decoded by [decoder], and the events sorted and cut, on
 * [work], off the caller's thread (the host's scope runs on the main one).
 */
class ListReader(
    private val client: SportarrClient,
    private val now: Instant,
    private val zone: ZoneId,
    private val decoder: WireDecoder = Wire,
    private val work: CoroutineContext = Dispatchers.Default,
) {
    private val windowStart: Instant = now.atZone(zone).toLocalDate().minusDays(DAYS_BACK).atStartOfDay(zone).toInstant()
    private val windowEnd: Instant = now.atZone(zone).toLocalDate().plusDays(DAYS_AHEAD + 1).atStartOfDay(zone).toInstant()
    private var requests = 0

    /** The read, all or nothing. Its log line says counts, the time it took and a failure's kind alone. */
    suspend fun read(): ListRead {
        val started = System.nanoTime()
        val read = readAll()
        val ms = (System.nanoTime() - started) / 1_000_000
        when (read) {
            is ListRead.Read -> log.info(
                "Sportarr list read: {} leagues, {} events{}, {} requests in {} ms",
                read.list.leagues.size, read.list.events.size, if (read.list.cut) " (cut)" else "", requests, ms,
            )
            is ListRead.Failed -> log.info("Sportarr list not read: {} after {} requests in {} ms", read.why, requests, ms)
            ListRead.Refused -> log.info("Sportarr list not read: the key was refused after {} requests in {} ms", requests, ms)
        }
        return read
    }

    private suspend fun readAll(): ListRead {
        val body = when (val step = body(client.leagues())) {
            is Step.Body -> step.body
            is Step.Stop -> return step.read
            Step.Removed -> return ListRead.Failed(Failure.ErrorStatus)
        }
        val leagues = withContext(work) { decoder.leagues(body) } ?: return ListRead.Failed(Failure.Unreadable)
        val kept = mutableListOf<KeptLeague>()
        val events = mutableListOf<WireEvent>()
        var cut = false
        for (league in leagues.filter(Membership::counts)) {
            val walked = when (val walk = walk(league)) {
                is Walk.Events -> walk
                is Walk.Stop -> return walk.read
                Walk.Removed -> continue
            }
            kept += KeptLeague(league.id, league.name, league.sport, league.alternateName)
            events += withContext(work) {
                Membership.events(league, walked.events).filter { it.eventDate >= windowStart && it.eventDate < windowEnd }
                    .distinctBy { it.id }
                    // Each kept under the league whose route listed it, which the model groups the list by.
                    .map { if (it.leagueId == league.id) it else it.copy(leagueId = league.id) }
            }
            cut = cut || walked.cut
        }
        val newest = withContext(work) {
            events.sortedWith(compareByDescending<WireEvent> { it.eventDate }.thenByDescending { it.id })
        }
        return ListRead.Read(SportsList(now, kept, newest.take(CAP), cut || newest.size > CAP))
    }

    /** One league's events, walked page by page as the class says. */
    private suspend fun walk(league: WireLeague): Walk {
        val events = mutableListOf<WireEvent>()
        var newestFirst = true
        var endOfLast: Instant? = null
        for (page in 1..PAGE_BUDGET) {
            val body = when (val step = body(client.events(league.id, page))) {
                is Step.Body -> step.body
                is Step.Stop -> return Walk.Stop(step.read)
                Step.Removed -> return Walk.Removed
            }
            val envelope = when (val read = withContext(work) { decoder.events(body) }) {
                null -> return Walk.Stop(ListRead.Failed(Failure.Unreadable))
                is WireEvents.Whole -> return Walk.Events(read.events, cut = false)
                is WireEvents.Paged -> read.page
            }
            val records = envelope.records
            events += records
            if (records.isEmpty() || page >= envelope.totalPages) return Walk.Events(events, cut = false)
            val ordered = records.zipWithNext().all { (newer, older) -> newer.eventDate >= older.eventDate }
            val follows = endOfLast?.let { records.first().eventDate <= it } ?: true
            newestFirst = newestFirst && ordered && follows
            if (newestFirst && records.last().eventDate < windowStart) return Walk.Events(events, cut = false)
            endOfLast = records.last().eventDate
        }
        // The budget spent with pages left: what they hold isn't read.
        return Walk.Events(events, cut = true)
    }

    /** A request's answer as a body to decode, or how it ends the read or the league. */
    private fun body(reply: Reply): Step {
        requests++
        return when (reply) {
            is Reply.NoAnswer -> Step.Stop(ListRead.Failed(if (reply.kind == Kind.TooLarge) Failure.TooLarge else Failure.NoAnswer))
            is Reply.Answered -> when (reply.answer.status) {
                200 -> Step.Body(reply.answer.body)
                401, 403 -> Step.Stop(ListRead.Refused)
                404 -> Step.Removed
                else -> Step.Stop(ListRead.Failed(Failure.ErrorStatus))
            }
        }
    }

    private sealed interface Step {
        class Body(val body: ByteArray) : Step

        class Stop(val read: ListRead) : Step

        data object Removed : Step
    }

    private sealed interface Walk {
        class Events(val events: List<WireEvent>, val cut: Boolean) : Walk

        class Stop(val read: ListRead) : Walk

        data object Removed : Walk
    }

    companion object {
        /** Pages read of one league at most (C30): with 1000 a page, enough to reach a big league's season start. */
        const val PAGE_BUDGET = 6

        /** Events kept at most, the newest by start. */
        const val CAP = 5000

        /** The window, in local days (L641). */
        const val DAYS_BACK = 60L
        const val DAYS_AHEAD = 31L

        private val log = LoggerFactory.getLogger(ListReader::class.java)
    }
}

/** How a read ended: a list to commit, the key refused, or a failure that keeps the last list. */
sealed interface ListRead {
    data class Read(val list: SportsList) : ListRead

    data object Refused : ListRead

    data class Failed(val why: Failure) : ListRead
}

/** Why a read failed, for its log line; the list says "Can't reach Sportarr" for each. */
enum class Failure {
    /** No answer, or none in time. */
    NoAnswer,

    /** A status other than 200, 401, 403 or a league's 404. */
    ErrorStatus,

    /** A body that isn't what the route answers. */
    Unreadable,

    /** A body over its bound. */
    TooLarge,
}
