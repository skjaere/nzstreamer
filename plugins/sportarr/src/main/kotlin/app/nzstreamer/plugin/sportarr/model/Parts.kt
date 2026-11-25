package app.nzstreamer.plugin.sportarr.model

import java.util.Locale

/**
 * An event's parts (L644). Their ids are permanent, as the keys the host keeps a part's watch state under are made of
 * them: `full` for every one-part event (a game, a session, a matchup, a weekly show, a Generic event); `early-prelims`,
 * `prelims` and `main-card` for a numbered card; `prelims` and `main-card` for a fight night; `pre-show` and `main-show`
 * for a premium event.
 *
 * Sportarr sends part names on fighting sports alone, and for a promotion it doesn't recognise it sends the numbered card's
 * or the fight night's list whatever the event is. So its names are taken, in its order and words, only where every name
 * that maps to a part ([WORDS]) gives one of the card type's own ids, once each, and the type's main part is among them;
 * a name that maps to none (a post show) is dropped. Otherwise the type's own list. A matchup and a weekly show are always
 * one part. An id never comes from a list that doesn't fit the type.
 */
object Parts {
    const val FULL = "full"
    const val EARLY_PRELIMS = "early-prelims"
    const val PRELIMS = "prelims"
    const val MAIN_CARD = "main-card"
    const val PRE_SHOW = "pre-show"
    const val MAIN_SHOW = "main-show"

    /**
     * The parts of the event under [eventKey], titled [title], of [family]: a fight card's by its [cardType] and Sportarr's
     * part [names]; a motorsport [session]'s one, labelled by its words; any other one part, labelled by the title. Each
     * part's runtime is [Runtimes]', [sport] naming a team game's.
     */
    fun of(
        eventKey: String,
        title: String,
        family: Family,
        cardType: CardType?,
        session: Session?,
        sport: String?,
        names: List<String>?,
    ): List<Part> = when (family) {
        Family.TeamGame -> listOf(Part(Keys.part(eventKey, FULL), FULL, title, Runtimes.teamGame(sport)))
        Family.Motorsport -> listOf(Part(Keys.part(eventKey, FULL), FULL, session?.label ?: title, session?.let { Runtimes.session(it.kind, title) }))
        Family.Generic -> listOf(Part(Keys.part(eventKey, FULL), FULL, title, null))
        Family.FightCard -> when (val type = cardType ?: CardType.Premium) {
            CardType.Matchup -> listOf(Part(Keys.part(eventKey, FULL), FULL, title, Runtimes.MATCHUP))
            CardType.WeeklyShow -> listOf(Part(Keys.part(eventKey, FULL), FULL, title, Runtimes.WEEKLY_SHOW))
            else -> cardParts(type, names).map { (id, label) -> Part(Keys.part(eventKey, id), id, label, Runtimes.cardPart(id)) }
        }
    }

    /** The part that marks an event watched: its main card, its main show, else the whole event. */
    fun headline(parts: List<Part>): Part =
        parts.firstOrNull { it.id == MAIN_CARD } ?: parts.firstOrNull { it.id == MAIN_SHOW } ?: parts.firstOrNull { it.id == FULL } ?: parts.last()

    /** A numbered card's, fight night's or premium event's parts as (id, label): Sportarr's [names] where they fit, else its own. */
    private fun cardParts(type: CardType, names: List<String>?): List<Pair<String, String>> {
        val own = OWN.getValue(type)
        val sent = names.orEmpty().mapNotNull { name -> WORDS[fold(name)]?.let { it to name.trim() } }
        val ids = sent.map { it.first }
        val fits = sent.isNotEmpty() &&
            ids.all { id -> own.any { it.first == id } } &&
            ids.distinct().size == ids.size &&
            MAIN.getValue(type) in ids
        return if (fits) sent else own
    }

    /** A name as the words compare it: lower case, letters and digits alone ("Pre-Show" and "pre show" are one). */
    private fun fold(name: String): String = name.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    /**
     * Each type's own parts, in card order, with the plugin's own labels: what an event has where Sportarr's part names
     * don't fit its type.
     */
    private val OWN: Map<CardType, List<Pair<String, String>>> = mapOf(
        CardType.Numbered to listOf(EARLY_PRELIMS to "Early Prelims", PRELIMS to "Prelims", MAIN_CARD to "Main Card"),
        CardType.FightNight to listOf(PRELIMS to "Prelims", MAIN_CARD to "Main Card"),
        CardType.Premium to listOf(PRE_SHOW to "Pre-Show", MAIN_SHOW to "Main Show"),
    )

    /** The part each type can't be without, among Sportarr's part names as among its own. */
    private val MAIN: Map<CardType, String> = mapOf(CardType.Numbered to MAIN_CARD, CardType.FightNight to MAIN_CARD, CardType.Premium to MAIN_SHOW)

    /**
     * The part names Sportarr's API sends (an event's eventTypePartNames), folded, each with the id of the part it names:
     * how the plugin reads the API's parts.
     */
    private val WORDS: Map<String, String> = buildMap {
        listOf("early prelims").forEach { put(fold(it), EARLY_PRELIMS) }
        listOf("prelims", "preliminary card", "lead card").forEach { put(fold(it), PRELIMS) }
        listOf("main card", "main event").forEach { put(fold(it), MAIN_CARD) }
        listOf("countdown", "pre-show", "pre show", "kickoff", "zero hour", "buy-in").forEach { put(fold(it), PRE_SHOW) }
        listOf("main show").forEach { put(fold(it), MAIN_SHOW) }
    }
}
