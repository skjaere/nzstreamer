package app.nzstreamer.plugin.sportarr.family

import app.nzstreamer.plugin.sportarr.match.FamilyMatcher
import app.nzstreamer.plugin.sportarr.match.NameContext
import app.nzstreamer.plugin.sportarr.match.NumberWord
import app.nzstreamer.plugin.sportarr.match.PartWord
import app.nzstreamer.plugin.sportarr.match.Queries
import app.nzstreamer.plugin.sportarr.match.RejectKind
import app.nzstreamer.plugin.sportarr.match.SportsName
import app.nzstreamer.plugin.sportarr.match.Token
import app.nzstreamer.plugin.sportarr.match.Tokens
import app.nzstreamer.plugin.sportarr.match.Verdict
import app.nzstreamer.plugin.sportarr.model.CardType
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.Part
import app.nzstreamer.plugin.sportarr.model.Parts
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.source.Families
import app.nzstreamer.plugin.sportarr.ui.Words
import java.time.Instant

/**
 * Fight cards (L650): MMA, boxing and wrestling, each card judged by the type its list gave it (L643), and then part by
 * part.
 * - A numbered card ("UFC 999") is its organisation word and its number as whole tokens, never with "Fight Night"; a date,
 *   where a name has one, the shared rules hold to the card's.
 * - A fight night is its organisation word, "Fight Night" or both surnames, and its date; the number in its title, where
 *   it has one, the name's.
 * - A boxing matchup is both surnames and its date.
 * - A weekly show is its title's words and its own date: the same show airs every week, so a name with no date is no one
 *   week's.
 * - A premium event is the organisation's or league's word, every word of its title, its year or edition (its edition or
 *   date where another edition of its title is listed), and its night where its title has one.
 *
 * Then the parts: a name's part words name its part (pre-show words a premium event's pre-show); one with none, or with
 * PPV, is the card's main part and never a prelim; a full event is every part's, the picker choosing the part's file.
 *
 * What names a card less surely is a possible match: both surnames with no date, the main event alone, and, as for every
 * family, a name with the league's word and the card's date that fails the rest. Another card's number or night, another
 * listed card's surnames or title words, or another part, leave a name out.
 *
 * It reads the kept list ([sports]) for the other listed cards, whose names a name could hold instead, and for a show's
 * other nights, whose dates are theirs.
 */
class FightCard(private val sports: Sports) : FamilyMatcher {
    /** Every listed fight card, read from its title once, by its key. */
    private val cards: Map<String, Card> by lazy {
        sports.events.filter { it.family == Family.FightCard }.associate { it.key to Card(it) }
    }

    override fun context(event: SportsEvent): NameContext {
        val card = card(event)
        return NameContext(
            title = event.title,
            league = event.league.name,
            sides = card.sides,
            series = event.league.alternateNames,
            // Only a numbered card's and a fight night's organisation has a card number after it.
            organisations = listOfNotNull(card.organisation?.written).takeIf { card.type in CARD_NUMBERS }.orEmpty(),
            show = card.type == CardType.Premium,
            years = eventYears(event),
        )
    }

    /**
     * Every part of a card is searched by the card's words, so the host's cache answers a second part's play:
     * - a numbered card: "<org> <number>" and "<org> <yyyy mm dd>", then "<org> <surname> <surname>";
     * - a fight night: "<org> <surname> <surname>" (with no sides, "<org> Fight Night <number>") and "<org> <yyyy mm dd>";
     * - a matchup: "<surname> <surname>";
     * - a weekly show: "<title> <yyyy mm dd>", then "<title>";
     * - a premium event: its title's words, five at most.
     */
    override fun queries(event: SportsEvent, part: Part): Queries {
        val card = card(event)
        val date = searchDate(event)
        val surnames = card.surnames.takeIf { it.size == 2 }?.joinToString(" ") { it.written }
        val title = card.words.take(TITLE_WORDS).joinToString(" ") { it.written }.ifEmpty { null }
        val org = card.organisation?.written
        return when {
            card.type == CardType.WeeklyShow -> Queries(listOfNotNull(title?.let { "$it $date" }), title)
            card.type == CardType.Numbered && org != null ->
                Queries(listOfNotNull(card.numberWritten?.let { "$org $it" }, "$org $date"), surnames?.let { "$org $it" })
            // A fight night with no sides in its title is searched by its own words.
            card.type == CardType.FightNight && org != null ->
                Queries(listOf("$org ${surnames ?: listOfNotNull(FIGHT_NIGHT_WRITTEN, card.numberWritten).joinToString(" ")}", "$org $date"))
            card.type == CardType.Matchup -> Queries(listOfNotNull(surnames ?: title))
            else -> Queries(listOfNotNull(title))
        }
    }

    /** The card's other listed nights, by the same words in the same league: a date in a name that is one of theirs is theirs. */
    override fun related(event: SportsEvent): List<SportsEvent> {
        val card = card(event)
        if (card.identity.isEmpty()) return emptyList()
        return cards.values.filter { it.event.key != event.key && it.event.league.id == event.league.id && it.identity == card.identity }
            .map { it.event }
    }

    override fun judge(event: SportsEvent, part: Part, name: SportsName, posted: Instant?, size: Long?): Verdict {
        val card = card(event)
        val words = name.identity.mapTo(HashSet()) { it.word }
        if (anotherCard(card, name, words)) return OTHER_CARD
        // L647 rule 6: a name with the league's word and the card's date that fails the rest is offered, not left out.
        val named = identity(card, name, words)
            ?: if (name.dates.isNotEmpty() && leagueNamed(card, name, words)) Verdict.Offer(Words.BY_LEAGUE_AND_DATE) else return OTHER_CARD
        val parts = parts(card, part, name)
        return if (parts is Verdict.Reject || named is Verdict.Accept) parts else named
    }

    /**
     * Whether [name] names another card: a numbered card's other number, or a fight night; a fight night's other number,
     * or a numbered card; a premium event's other night; or another listed card's surnames or title words, by words this
     * card's own don't hold.
     */
    private fun anotherCard(card: Card, name: SportsName, words: Set<String>): Boolean {
        val nights = name.numbered(NumberWord.Night)
        val own = when (card.type) {
            CardType.Numbered -> name.cardNumbers.any { it != card.number } || name.has(FIGHT_NIGHT)
            CardType.FightNight -> name.cardNumbers.isNotEmpty() || (card.number != null && nights.any { it != card.number })
            // A title with no night is the first.
            CardType.Premium -> nights.any { it != (card.night ?: 1) } || otherEdition(card, name)
            CardType.Matchup, CardType.WeeklyShow -> false
        }
        return own || cards.values.any { other ->
            other.event.key != card.event.key && other.identity.isNotEmpty() && words.containsAll(other.identity) &&
                !card.identity.containsAll(other.identity)
        }
    }

    /**
     * A premium event's other edition: right after the title's word its edition number follows ("Crown" of "Velmora Iron
     * Crown 37"), another number than its own, a year's or a date's aside ("Velmora.Iron.Crown.38").
     */
    private fun otherEdition(card: Card, name: SportsName): Boolean {
        val dated = name.dates.flatMap { it.places }.toSet()
        val identity = name.identity
        return card.editionsAfter.any { (word, edition) ->
            (0 until identity.size - 1).any { at ->
                val next = identity[at + 1]
                identity[at].word == word && next.isNumber && next.word.length < YEAR_DIGITS && at + 1 !in dated && next.word.toInt() != edition
            }
        }
    }

    /** What [name] says of [card]'s identity by its type: Accept, a possible match with its reason, or null for neither. */
    private fun identity(card: Card, name: SportsName, words: Set<String>): Verdict? {
        val dated = name.dates.isNotEmpty()
        val organisation = card.organisation?.word?.let { it in words } == true
        val surnames = card.surnames.size == 2 && card.surnames.all { it.word in words }
        return when (card.type) {
            CardType.Numbered -> when {
                card.number != null && card.number in name.cardNumbers -> SURE
                organisation && surnames && !dated -> Verdict.Offer(Words.NO_DATE)
                else -> null
            }
            CardType.FightNight -> {
                val fightNight = name.has(FIGHT_NIGHT)
                val numbered = fightNight && card.number != null && card.number in name.numbered(NumberWord.Night)
                when {
                    !organisation -> null
                    dated && (fightNight || surnames) -> SURE
                    surnames || numbered -> Verdict.Offer(Words.NO_DATE)
                    else -> null
                }
            }
            CardType.Matchup -> when {
                !surnames -> null
                dated -> SURE
                else -> Verdict.Offer(Words.NO_DATE)
            }
            CardType.WeeklyShow -> SURE.takeIf { dated && card.titleIn(words) }
            CardType.Premium -> {
                // Where another edition of the same title is listed, the year names neither: the edition's number or the
                // date, which the shared rules held to this one's, must.
                val yearNames = name.yearsNamed().any { it in eventYears(card.event) } && !(card.numbered && related(card.event).isNotEmpty())
                val edition = card.editions.any(name::hasNumber) || dated || yearNames
                val night = card.night == null || card.night in name.numbered(NumberWord.Night)
                SURE.takeIf { leagueNamed(card, name, words) && card.titleIn(words) && edition && night }
            }
        }
    }

    /**
     * What [name]'s part words make of it for [part]: a full event is every part's, and the part alone only where the
     * card has no other; the main event alone a possible match for the main part; no part word, or PPV alone, the main
     * part; else the parts its words name, the part alone only when they name no other.
     */
    private fun parts(card: Card, part: Part, name: SportsName): Verdict {
        val words = name.partNamed
        val headline = card.event.headline.id
        return when {
            PartWord.FullEvent in words -> Verdict.Accept(whole = card.event.parts.size == 1)
            PartWord.MainEventOnly in words -> if (part.id == headline) Verdict.Offer(Words.MAIN_EVENT_ONLY) else OTHER_PART
            words.isEmpty() -> if (part.id == headline) SURE else OTHER_PART
            else -> {
                val named = words.map { partOf(card.type, it) }
                if (part.id in named) Verdict.Accept(whole = named.all { it == part.id }) else OTHER_PART
            }
        }
    }

    /** Whether the league stands in [name]: the card's organisation word, the league's name or one of its other names. */
    private fun leagueNamed(card: Card, name: SportsName, words: Set<String>): Boolean =
        card.organisation?.word?.let { it in words } == true || (listOf(card.event.league.name) + card.event.league.alternateNames).any(name::has)

    private fun card(event: SportsEvent): Card = cards[event.key]?.takeIf { it.event == event } ?: Card(event)

    /**
     * One card as its title gives it: its [type]; its [organisation], the word a numbered card's number or a fight night's
     * words follow ("UFC"), else a premium event's league's first word; its [number]; its two [sides] as written and the
     * last word of each, its [surnames]; its title's distinctive [words] (no number, date word, article or "vs", nor the
     * "Night" of a "Night 2"); a premium event's [night]; and its numbers but the night's, an edition's ([editions]).
     * [identity] is what another card's names would have to hold to be this one's: its surnames, or a show's title words.
     */
    private class Card(val event: SportsEvent) {
        val type: CardType = event.cardType ?: CardType.Premium
        private val tokens = Tokens.of(event.title)
        private val leagueWord: Token? = Tokens.of(event.league.name).firstOrNull()

        /** Where a card's own word stands: the title's first word, and the league's first word wherever it is (L643). */
        private val leads = tokens.indices.filter { it == 0 || tokens[it].word == leagueWord?.word }
        private val numberedAt = leads.firstOrNull { tokens.getOrNull(it + 1)?.isNumber == true }
        private val fightNightAt = leads.firstOrNull { tokens.getOrNull(it + 1)?.word == "fight" && tokens.getOrNull(it + 2)?.word == "night" }

        val organisation: Token? = when (type) {
            CardType.Numbered -> numberedAt?.let(tokens::get) ?: leagueWord
            CardType.FightNight -> fightNightAt?.let(tokens::get) ?: leagueWord
            CardType.Premium -> leagueWord
            CardType.Matchup, CardType.WeeklyShow -> null
        }

        val numberWritten: String? = when (type) {
            CardType.Numbered -> numberedAt?.let { tokens[it + 1].written }
            CardType.FightNight -> fightNightAt?.let { tokens.getOrNull(it + 3) }?.takeIf { it.isNumber }?.written
            else -> null
        }
        val number: Int? = numberWritten?.toIntOrNull()

        val sides: List<String> = Families.sides(event.title)?.let { (home, away) ->
            // A title may name more than the sides: the card before a colon, a note after a dash or a bracket.
            listOf(home.substringAfterLast(": "), NOTES.fold(away) { side, note -> side.substringBefore(note) }).map(String::trim)
        }?.takeIf { sides -> sides.all(String::isNotEmpty) }.orEmpty()

        val surnames: List<Token> = sides.mapNotNull { side -> Tokens.of(side).lastOrNull { it.hasLetter } }

        private val nightAt = tokens.indices.firstOrNull { tokens[it].word == NIGHT && tokens.getOrNull(it + 1)?.isNumber == true }
        val night: Int? = nightAt?.let { tokens[it + 1].word.toIntOrNull() }.takeIf { type == CardType.Premium }

        val words: List<Token> = tokens.filterIndexed { at, token ->
            !token.isNumber && token.word !in FILLER && token.word !in Families.MONTHS && !(nightAt != null && at == nightAt)
        }.distinctBy { it.word }

        val editions: Set<Int> =
            tokens.filterIndexed { at, token -> token.isNumber && at != nightAt?.plus(1) }.mapNotNull { it.word.toIntOrNull() }.toSet()

        /** Each edition number with the title's word right before it: ("crown", 37) of "Velmora Iron Crown 37". */
        val editionsAfter: List<Pair<String, Int>> = tokens.indices
            .filter { at -> at > 0 && tokens[at].isNumber && at != nightAt?.plus(1) && tokens[at - 1].hasLetter }
            .mapNotNull { at -> tokens[at].word.toIntOrNull()?.let { tokens[at - 1].word to it } }

        /** Whether its title numbers an edition by more than a year. */
        val numbered: Boolean = editions.any { it.toString().length < YEAR_DIGITS }

        val identity: Set<String> = when (type) {
            CardType.WeeklyShow, CardType.Premium -> words.map { it.word }.toSet()
            else -> surnames.takeIf { it.size == 2 }?.map { it.word }?.toSet().orEmpty()
        }

        /** Whether every one of the title's distinctive words stands in a name's [present] words. */
        fun titleIn(present: Set<String>): Boolean = words.isNotEmpty() && words.all { it.word in present }
    }

    private companion object {
        val SURE = Verdict.Accept()
        val OTHER_CARD = Verdict.Reject(RejectKind.OtherEvents)
        val OTHER_PART = Verdict.Reject(RejectKind.OtherParts)

        /** The types whose names a card's number, after the organisation word, may stand in. */
        val CARD_NUMBERS = setOf(CardType.Numbered, CardType.FightNight)

        const val FIGHT_NIGHT = "fight night"
        const val FIGHT_NIGHT_WRITTEN = "Fight Night"
        const val NIGHT = "night"

        /** A year's digits: a number of four after a title's word is its year, never another edition. */
        const val YEAR_DIGITS = 4

        /** A premium event's search: its title's first five words. */
        const val TITLE_WORDS = 5

        /** What follows a side in a title: " - Heavyweight", " (Title Fight)". */
        val NOTES = listOf(" - ", " – ", " (", " | ")

        /** Words a title holds that names may leave out or write otherwise. */
        val FILLER = setOf("a", "an", "the", "of", "and", "vs", "v", "at", "@")

        /**
         * What each part word names of a card of [type]: one of its parts, or null where it names none of them. The ids
         * are the parts' own, which Parts gives them from the part names Sportarr's API sends or the plugin's own labels.
         */
        fun partOf(type: CardType, word: PartWord): String? = when (type) {
            CardType.Numbered -> when (word) {
                PartWord.EarlyPrelims -> Parts.EARLY_PRELIMS
                PartWord.Prelims -> Parts.PRELIMS
                PartWord.MainCard, PartWord.Ppv -> Parts.MAIN_CARD
                else -> null
            }
            CardType.FightNight -> when (word) {
                PartWord.Prelims -> Parts.PRELIMS
                PartWord.MainCard, PartWord.Ppv -> Parts.MAIN_CARD
                else -> null
            }
            CardType.Premium -> when (word) {
                PartWord.PreShow -> Parts.PRE_SHOW
                PartWord.MainShow, PartWord.MainCard, PartWord.Ppv -> Parts.MAIN_SHOW
                else -> null
            }
            CardType.Matchup, CardType.WeeklyShow -> when (word) {
                PartWord.MainCard, PartWord.MainShow, PartWord.Ppv -> Parts.FULL
                else -> null
            }
        }
    }
}
