package app.nzstreamer.plugin.sportarr.family

import app.nzstreamer.plugin.sportarr.match.FamilyMatcher
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.SportsEvent

/**
 * The families' matchers over one kept list (L643): an event's is its family's, which the runner searches and judges with
 * and the part picker reads file names by. Each reads the list for the events a name could name instead.
 */
class Matchers(sports: Sports) {
    private val teamGames = TeamMatch(sports)

    /** [event]'s family's matcher; null for a family whose rules the plugin doesn't have yet. */
    fun of(event: SportsEvent): FamilyMatcher? = when (event.family) {
        Family.TeamGame -> teamGames
        Family.Motorsport, Family.FightCard, Family.Generic -> null
    }
}
