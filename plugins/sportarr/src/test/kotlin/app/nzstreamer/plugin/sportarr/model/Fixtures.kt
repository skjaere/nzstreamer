package app.nzstreamer.plugin.sportarr.model

import app.nzstreamer.plugin.sportarr.source.KeptLeague
import app.nzstreamer.plugin.sportarr.source.SportsList
import app.nzstreamer.plugin.sportarr.source.WireEvent
import app.nzstreamer.plugin.sportarr.source.event
import io.skjaere.usenet.plugin.WatchInfo
import java.time.Instant

// The model's tests read lists built here: invented clubs, fighters and places in real leagues, as the fixtures' lists
// name them.

val PREMIER = KeptLeague(1, "Premier League", "Soccer")
val FORMULA_1 = KeptLeague(2, "Formula 1", "Motorsport")
val MOTO_GP = KeptLeague(3, "MotoGP", "Motorsport")
val UFC = KeptLeague(4, "UFC", "Fighting")
val VELMORA = KeptLeague(5, "Velmora Wrestling", "Wrestling")
val BOXING = KeptLeague(6, "Kestrel Boxing", "Boxing")
val SKIING = KeptLeague(7, "FIS Alpine Ski World Cup", "Skiing")
val NBA = KeptLeague(8, "NBA", "Basketball")
val ENDURANCE = KeptLeague(9, "Kestrel Endurance Series", "Motorsport")

/** The model of a list of [leagues], each with its events, read at [readAt]: the events newest first, as a read keeps them. */
fun sports(vararg leagues: Pair<KeptLeague, List<WireEvent>>, readAt: Instant = at("2026-11-22T20:41")): Sports = Sports.of(
    SportsList(readAt, leagues.map { it.first }, leagues.flatMap { it.second }.sortedByDescending { it.eventDate }, cut = false),
)

/** An event of [league] as Sportarr lists it, Sportarr's part names ([parts]) on it as sent. */
fun on(league: KeptLeague, id: Int, start: Instant, title: String, round: String? = null, parts: List<String>? = null): WireEvent =
    event(id, start, league.id, title, sport = null, round = round).copy(eventTypePartNames = parts)

/** A UTC time written to the minute: "2026-11-22T15:00". */
fun at(time: String): Instant = Instant.parse("$time:00Z")

/**
 * The Grand Prix of Kestrel, round 20, as the weekend page shows it: two practices on Friday, the third and qualifying
 * on Saturday, the race on Sunday at 15:00 (UTC).
 */
fun kestrelWeekend(league: KeptLeague = FORMULA_1, firstId: Int = 1): List<WireEvent> = listOf(
    on(league, firstId, at("2026-11-20T12:30"), "Grand Prix of Kestrel - Practice 1", round = "20"),
    on(league, firstId + 1, at("2026-11-20T16:00"), "Grand Prix of Kestrel - Practice 2", round = "20"),
    on(league, firstId + 2, at("2026-11-21T11:30"), "Grand Prix of Kestrel - Practice 3", round = "20"),
    on(league, firstId + 3, at("2026-11-21T15:00"), "Grand Prix of Kestrel - Qualifying", round = "20"),
    on(league, firstId + 4, at("2026-11-22T15:00"), "Grand Prix of Kestrel - Race", round = "20"),
)

/** UFC 999 on Saturday at 23:00, with the numbered card's parts as Sportarr names them for UFC. */
fun ufc999(id: Int = 31): WireEvent =
    on(UFC, id, at("2026-11-21T23:00"), "UFC 999: Quessel vs. Halde", parts = listOf("Early Prelims", "Prelims", "Main Card"))

/** Watched, as the host keeps it: no position once watched. */
fun watched(lastPlayedAt: Instant? = null) = WatchInfo(0, 0, true, lastPlayedAt)

/** Played to [positionMs] of two hours, last at [lastPlayedAt]. */
fun played(positionMs: Long, lastPlayedAt: Instant) = WatchInfo(positionMs, 7_200_000, false, lastPlayedAt)
