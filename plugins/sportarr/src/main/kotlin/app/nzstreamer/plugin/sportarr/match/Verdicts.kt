package app.nzstreamer.plugin.sportarr.match

import app.nzstreamer.plugin.sportarr.model.CardType
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.Part
import app.nzstreamer.plugin.sportarr.model.Parts
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.ui.Words
import io.skjaere.usenet.plugin.PluginKeys
import java.util.Locale

/**
 * What the plugin makes of one release for one part (L647): Play may play it ([Accept]); it shows under "Possible
 * matches" with its reason and Play never plays it ([Offer]); or it is left out, counted by its kind and never named
 * ([Reject]). Play tries the accepted alone.
 */
sealed interface Verdict {
    /**
     * The part's release. [whole] says it is the part itself; false for one that holds others besides (a full event, a
     * weekend's sessions), whose file the part picker chooses.
     */
    data class Accept(val whole: Boolean = true) : Verdict

    /**
     * A possible match, with the [reason] Choose release shows in place of a rank: 1 to 60 characters, the host's rule,
     * checked here so a family's own tests find a reason the host would refuse.
     */
    data class Offer(val reason: String) : Verdict {
        init {
            require(PluginKeys.validReason(reason)) { "A possible match's reason is 1 to 60 characters" }
        }
    }

    /** Left out, as one of [kind]. */
    data class Reject(val kind: RejectKind) : Verdict
}

/**
 * Why a release was left out (L647), in the order the left-out line names kinds that left out as many: other events,
 * other parts, highlights, a score, a result word, previews and shows (a race's own shows with them), parts of a game,
 * packs, older releases.
 */
enum class RejectKind {
    /** Another event's: another game, session, card, fight, show or event; or a date that isn't this one's. */
    OtherEvents,

    /** Another part of this card. */
    OtherParts,

    /** Highlights, recaps and condensed games. */
    Highlights,

    /** A score in the name (L646). */
    Score,

    /** A result word in the name (L646). */
    Result,

    /** Previews, press conferences, interviews and any other programme about the event. */
    Shows,

    /** A motorsport programme before or after the race. */
    RaceShow,

    /** A half, quarter, period or innings alone, or one numbered part of a recording. */
    Partial,

    /** A pack of several events. */
    Packs,

    /** Posted over a day before the event started. */
    Older,
    ;

    /** The kind's words for [count] releases of an event whose noun is [noun]: "other games", "a name with a score". */
    fun words(noun: String, count: Int = 1): String = when (this) {
        OtherEvents -> Words.otherEvents(noun)
        OtherParts -> Words.OTHER_PARTS
        Highlights -> Words.HIGHLIGHTS
        Score -> Words.withScore(count)
        Result -> Words.withResult(count)
        Shows -> Words.PREVIEWS_AND_SHOWS
        RaceShow -> Words.preRaceShow(count)
        Partial -> Words.partsOf(noun)
        Packs -> Words.PACKS
        Older -> Words.OLDER
    }
}

/** The left-out line (L647). */
object LeftOut {
    /**
     * The line for what was left out, by kind ([counts]), of an event whose noun is [noun]: the kinds that left out the
     * most first, those with as many in [RejectKind]'s order, three at most and "and more" past them; none when nothing
     * was. It counts releases and never names one.
     */
    fun line(counts: Map<RejectKind, Int>, noun: String): String? {
        val kinds = counts.filterValues { it > 0 }.entries
            .sortedWith(compareByDescending<Map.Entry<RejectKind, Int>> { it.value }.thenBy { it.key.ordinal })
        if (kinds.isEmpty()) return null
        return Words.leftOut(kinds.sumOf { it.value }, kinds.take(SHOWN).map { (kind, count) -> kind.words(noun, count) }, more = kinds.size > SHOWN)
    }

    private const val SHOWN = 3
}

/**
 * The words that name what plays (L647). An item's ([PlayItem.noun][io.skjaere.usenet.plugin.PlayItem.noun], the size
 * floor's reason and the indexers' lines): "game", "session", a numbered card's or fight night's part by its label in
 * lower case ("main card"), "fight", "show", a premium event's "pre-show" or "main show", "event". An event's (the note,
 * and the kinds "other games" and "parts of a game"): "game", "session", "card" for every part of a card, "fight", "show",
 * "event".
 */
object Nouns {
    fun item(event: SportsEvent, part: Part): String = when (event.family) {
        Family.TeamGame -> GAME
        Family.Motorsport -> SESSION
        Family.Generic -> EVENT
        Family.FightCard -> when (event.cardType) {
            CardType.Matchup -> FIGHT
            CardType.WeeklyShow -> SHOW
            // A premium event's parts by their own words: Sportarr may label its pre-show "Countdown".
            CardType.Premium, null -> PARTS[part.id] ?: CARD
            CardType.Numbered, CardType.FightNight ->
                part.label.trim().lowercase(Locale.ROOT).takeIf(PluginKeys::validNoun) ?: PARTS[part.id] ?: CARD
        }
    }

    fun event(event: SportsEvent): String = when (event.family) {
        Family.TeamGame -> GAME
        Family.Motorsport -> SESSION
        Family.Generic -> EVENT
        Family.FightCard -> when (event.cardType) {
            CardType.Matchup -> FIGHT
            CardType.WeeklyShow -> SHOW
            else -> CARD
        }
    }

    private const val GAME = "game"
    private const val SESSION = "session"
    private const val CARD = "card"
    private const val FIGHT = "fight"
    private const val SHOW = "show"
    private const val EVENT = "event"

    /** A card part's own noun, where its label won't do. */
    private val PARTS = mapOf(
        Parts.EARLY_PRELIMS to "early prelims",
        Parts.PRELIMS to "prelims",
        Parts.MAIN_CARD to "main card",
        Parts.PRE_SHOW to "pre-show",
        Parts.MAIN_SHOW to "main show",
    )
}
