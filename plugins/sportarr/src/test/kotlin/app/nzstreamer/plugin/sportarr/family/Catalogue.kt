package app.nzstreamer.plugin.sportarr.family

import app.nzstreamer.plugin.sportarr.match.FamilyMatcher
import app.nzstreamer.plugin.sportarr.match.Nouns
import app.nzstreamer.plugin.sportarr.match.SharedRules
import app.nzstreamer.plugin.sportarr.match.Verdict
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.source.KeptLeague
import app.nzstreamer.plugin.sportarr.source.Membership
import app.nzstreamer.plugin.sportarr.source.SportsList
import app.nzstreamer.plugin.sportarr.source.WireEvent
import app.nzstreamer.plugin.sportarr.source.WireLeague
import io.skjaere.usenet.mockseed.SportsCatalog
import io.skjaere.usenet.mockseed.SportsLeague
import io.skjaere.usenet.mockseed.SportsRelease
import io.skjaere.usenet.mockseed.SportsVerdict
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import io.skjaere.usenet.mockseed.SportsEvent as CatalogueEvent

/**
 * The sports fakes' catalogue (mock-seed's, on the tests' classpath) at one anchor, as the plugin keeps its library after
 * a read: the leagues switched on, each league's page as Sportarr's route gives it, and L642's membership. Its release
 * rows say what the judge should make of each name, so a family's tests can hold it to them.
 */
object Catalogue {
    /** The anchor, a Sunday: the fixtures' game and race are the day before, Saturday 21 November 2026. */
    val anchor: LocalDate = LocalDate.of(2026, 11, 22)

    private val catalog = SportsCatalog.load()

    val releases: List<SportsRelease> by lazy { catalog.releases(anchor) }

    val sports: Sports by lazy {
        val leagues = catalog.leagues(anchor).filter { it.monitored }
        val events = leagues.flatMap { league ->
            // A league's page: its filtered events, or every one it stores where it lists them all.
            val page = league.events.filter { league.teamless || league.keepAllEvents == true || it.listed }
            Membership.events(league.wire(), page.map { it.wire(league) })
        }
        Sports.of(
            SportsList(
                readAt = anchor.atTime(LocalTime.of(20, 41)).toInstant(ZoneOffset.UTC),
                leagues = leagues.map { KeptLeague(it.id, it.name, it.sport, it.alternateName) },
                events = events.sortedByDescending { it.eventDate },
                cut = false,
            ),
        )
    }

    /** The listed event with Sportarr's [id]. */
    fun event(id: Int): SportsEvent = sports.events.single { it.id == id }

    /** Every verdict a release row gives for a listed event of [family], with its release. */
    fun verdicts(family: Family): List<Pair<SportsRelease, SportsVerdict>> = releases
        .flatMap { release -> release.verdicts.map { release to it } }
        .filter { (_, verdict) -> sports.events.any { it.id == verdict.eventId && it.family == family } }

    /**
     * What the shared rules and [matcher] make of [release] for [verdict]'s event and part, in the words the row writes it
     * ("accept", "offer: <reason>", "reject: <kind>"); null where the two agree.
     */
    fun disagreement(matcher: FamilyMatcher, release: SportsRelease, verdict: SportsVerdict): String? {
        val event = event(verdict.eventId)
        val part = event.parts.single { it.id == verdict.part }
        val said = when (val judged = SharedRules.judge(matcher, event, part, release.name, release.postedAt, release.sizeBytes)) {
            is Verdict.Accept -> "accept"
            is Verdict.Offer -> "offer: ${judged.reason}"
            is Verdict.Reject -> "reject: ${judged.kind.words(Nouns.event(event))}"
        }
        val expected = when (verdict.verdict) {
            SportsVerdict.Verdict.Accept -> "accept"
            SportsVerdict.Verdict.Offer -> "offer: ${verdict.reason}"
            SportsVerdict.Verdict.Reject -> "reject: ${verdict.kind}"
        }
        return "${release.id} for ${event.id} ${part.id}: $said, the catalogue says $expected".takeIf { said != expected }
    }

    private fun SportsLeague.wire() =
        WireLeague(id, externalId, name, sport, monitored, monitorType, monitoredEventCount = null, keepAllEvents = keepAllEvents, alternateName = alternateName)

    private fun CatalogueEvent.wire(league: SportsLeague) = WireEvent(
        id = id, externalId = externalId, title = title, sport = league.sport, leagueId = leagueId, leagueName = league.name, season = season,
        eventDate = start, broadcastDate = broadcastDate, broadcastTimezone = broadcastTimezone, monitored = monitored, hasFile = hasFile,
        round = round, homeTeamName = homeTeamName, awayTeamName = awayTeamName, eventTypePartNames = partNames,
    )
}
