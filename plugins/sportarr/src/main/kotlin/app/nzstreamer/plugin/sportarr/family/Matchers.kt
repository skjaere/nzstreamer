package app.nzstreamer.plugin.sportarr.family

import app.nzstreamer.plugin.sportarr.match.FamilyMatcher
import app.nzstreamer.plugin.sportarr.match.SportsName
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The families' matchers over one kept list (L643): an event's is its family's, which the runner searches and judges with
 * and the part picker reads file names by. Each reads the list for the events a name could name instead.
 */
class Matchers(sports: Sports) {
    private val teamGames = TeamMatch(sports)
    private val motorsport = Motorsport(sports)
    private val fightCards = FightCard(sports)

    /** [event]'s family's matcher: every sport without rules of its own is Generic's. */
    fun of(event: SportsEvent): FamilyMatcher = when (event.family) {
        Family.TeamGame -> teamGames
        Family.Motorsport -> motorsport
        Family.FightCard -> fightCards
        Family.Generic -> Generic
    }
}

/** [event]'s start as a UTC date. */
internal fun utcDate(event: SportsEvent): LocalDate = event.start.atOffset(ZoneOffset.UTC).toLocalDate()

/**
 * The day a search names [event] by, as three numbers as names write them ("2026 11 21"): its broadcast date as written,
 * else its UTC date.
 */
internal fun searchDate(event: SportsEvent): String {
    val date = event.broadcastDate ?: utcDate(event)
    return "${date.year} ${date.monthValue.toString().padStart(2, '0')} ${date.dayOfMonth.toString().padStart(2, '0')}"
}

/** The years [event] may be named by: its season's, and those of its UTC start and its broadcast date. */
internal fun eventYears(event: SportsEvent): Set<Int> =
    (event.seasonYears + listOfNotNull(utcDate(event).year, event.broadcastDate?.year)).toSet()

/** Every year a name holds: a year of its own, a date's, a season span's, a running number's or an episode's. */
internal fun SportsName.yearsNamed(): List<Int> =
    years + dates.flatMap { date -> date.readings.map { it.year } } + seasons.flatMap { listOf(it.first, it.second) } + indexes.map { it.year }
