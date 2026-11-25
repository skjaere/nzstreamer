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
        OtherEvents -> Words.Choose.otherEvents(noun)
        OtherParts -> Words.Choose.OTHER_PARTS
        Highlights -> Words.Choose.HIGHLIGHTS
        Score -> Words.Choose.withScore(count)
        Result -> Words.Choose.withResult(count)
        Shows -> Words.Choose.PREVIEWS_AND_SHOWS
        RaceShow -> Words.Choose.preRaceShow(count)
        Partial -> Words.Choose.partsOf(noun)
        Packs -> Words.Choose.PACKS
        Older -> Words.Choose.OLDER
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
        return Words.Choose.leftOut(kinds.sumOf { it.value }, kinds.take(SHOWN).map { (kind, count) -> kind.words(noun, count) }, more = kinds.size > SHOWN)
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
        Family.TeamGame -> Words.Choose.GAME
        Family.Motorsport -> Words.Choose.SESSION
        Family.Generic -> Words.Choose.EVENT
        Family.FightCard -> when (event.cardType) {
            CardType.Matchup -> Words.Choose.FIGHT
            CardType.WeeklyShow -> Words.Choose.SHOW
            // A premium event's parts by their own words: Sportarr may label its pre-show "Countdown".
            CardType.Premium, null -> PARTS[part.id] ?: Words.Choose.CARD
            CardType.Numbered, CardType.FightNight ->
                part.label.trim().lowercase(Locale.ROOT).takeIf(PluginKeys::validNoun) ?: PARTS[part.id] ?: Words.Choose.CARD
        }
    }

    fun event(event: SportsEvent): String = when (event.family) {
        Family.TeamGame -> Words.Choose.GAME
        Family.Motorsport -> Words.Choose.SESSION
        Family.Generic -> Words.Choose.EVENT
        Family.FightCard -> when (event.cardType) {
            CardType.Matchup -> Words.Choose.FIGHT
            CardType.WeeklyShow -> Words.Choose.SHOW
            else -> Words.Choose.CARD
        }
    }

    /** A card part's own noun, where its label won't do: the plugin's own label for it, in lower case. */
    private val PARTS = mapOf(
        Parts.EARLY_PRELIMS to Words.Card.EARLY_PRELIMS,
        Parts.PRELIMS to Words.Card.PRELIMS,
        Parts.MAIN_CARD to Words.Card.MAIN_CARD,
        Parts.PRE_SHOW to Words.Card.PRE_SHOW,
        Parts.MAIN_SHOW to Words.Card.MAIN_SHOW,
    ).mapValues { it.value.lowercase(Locale.ROOT) }
}
