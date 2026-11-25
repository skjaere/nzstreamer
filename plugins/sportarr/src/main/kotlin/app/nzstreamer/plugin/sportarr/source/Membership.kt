package app.nzstreamer.plugin.sportarr.source

/**
 * What the user follows (L642, W163 a), as Sportarr's league pages show it: Sportarr's subscriptions are the source of
 * truth, and the plugin keeps no follow list of its own.
 *
 * A league counts when it is switched on (monitored): Sportarr never syncs one that isn't, and a league switched off
 * through "no teams" is off. In a league that counts, an event counts as the league's route gives it, monitored or not
 * (games switched off by hand still show), whatever the league's scope, with two exceptions:
 * - scope None with at least one monitored event: only monitored events. That is how Sportarr adds a promotion for one
 *   followed fighter (C25). None with no monitored event is the route's answer.
 * - Keep every game: only events that are monitored or have a file. The route then returns every game it stores, with no
 *   team filter, and the rest are stored "so you can find a one-off game".
 *
 * Events with no league never reach a league's route, so they are never listed (L600). A field Sportarr doesn't promise
 * that is missing or can't be read leaves the route's answer as it is.
 */
object Membership {
    /** Whether [league] is read at all. */
    fun counts(league: WireLeague): Boolean = league.monitored

    /** Of [answer], every event [league]'s route gave in this read, those that count, in their order. */
    fun events(league: WireLeague, answer: List<WireEvent>): List<WireEvent> {
        val scopeNone = league.monitorType?.trim().equals(SCOPE_NONE, ignoreCase = true)
        val oneFollowed = (league.monitoredEventCount ?: 0) > 0 || answer.any { it.monitored }
        return answer
            .filter { !(scopeNone && oneFollowed) || it.monitored }
            .filter { league.keepAllEvents != true || it.monitored || it.hasFile }
    }

    private const val SCOPE_NONE = "None"
}
