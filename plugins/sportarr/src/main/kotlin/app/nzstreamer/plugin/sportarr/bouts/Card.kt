package app.nzstreamer.plugin.sportarr.bouts

import kotlinx.serialization.Serializable

/**
 * A UFC event's fight card as the plugin keeps it (L-B3): the Wikipedia [article] it was read from, by its title, and the
 * event's [parts] that have bouts, in running order. Nothing of a result is in it (W6): no winner, method, round, time or
 * note, and each pair's order says nothing of who won (L-B4).
 */
@Serializable
data class BoutCard(val article: String, val parts: List<BoutPart>)

/** One of the event's parts by its [id] ("prelims", as the event's own parts name it), with its [bouts] in running order. */
@Serializable
data class BoutPart(val id: String, val bouts: List<Bout>)

/**
 * A bout: its [weight] class as Wikipedia names it, the two fighters in L-B4's order ([first], [second]), whether it is a
 * [titleFight], and whether it is the [mainEvent], the card's last.
 */
@Serializable
data class Bout(val weight: String, val first: String, val second: String, val titleFight: Boolean, val mainEvent: Boolean)
