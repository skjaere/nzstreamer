package app.nzstreamer.plugin.sportarr.family

import app.nzstreamer.plugin.sportarr.match.FamilyMatcher
import app.nzstreamer.plugin.sportarr.match.IndexForm
import app.nzstreamer.plugin.sportarr.match.NameContext
import app.nzstreamer.plugin.sportarr.match.NumberWord
import app.nzstreamer.plugin.sportarr.match.Queries
import app.nzstreamer.plugin.sportarr.match.RejectKind
import app.nzstreamer.plugin.sportarr.match.SportsName
import app.nzstreamer.plugin.sportarr.match.Token
import app.nzstreamer.plugin.sportarr.match.Tokens
import app.nzstreamer.plugin.sportarr.match.Verdict
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.League
import app.nzstreamer.plugin.sportarr.model.Part
import app.nzstreamer.plugin.sportarr.model.Runtimes
import app.nzstreamer.plugin.sportarr.model.Session
import app.nzstreamer.plugin.sportarr.model.SessionKind
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.source.Families
import app.nzstreamer.plugin.sportarr.ui.Words
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import app.nzstreamer.plugin.sportarr.match.Words as NameWords

/**
 * Motorsport sessions (L649), each practice, qualifying, sprint and race its own event in Sportarr. A release name is a
 * session's when it holds a form of the series (and neither another followed series' form nor this one's with another
 * number, "Formula 2" at a Formula 1 weekend), the season's year (as a word, or in a running number, 2026x07, or an
 * episode, S2026E34), the place or the round's number, and the session's words, practice N to N. A name with no session
 * word is the race, once the race has started; a weekend's pack is every session's, the part picker choosing its file.
 *
 * A name with all but the place and the round, or the session in another language, is a possible match; so are an
 * endurance race and testing, which no runtime checks. Another round, session, race number or listed place leaves a name
 * out as another session's; where two rounds are held at one place, the name's date or round tells them apart. The
 * series' later forms (its initials, its third name on) are read in names but never searched for, so a play makes three
 * searches at most.
 *
 * It reads the kept list ([sports]) for the other followed series, the round's other sessions and the league's other places.
 */
class Motorsport(private val sports: Sports) : FamilyMatcher {
    /** Each league's series forms, as written and as a name's words fold them. */
    private val formsOf = ConcurrentHashMap<League, Forms>()

    /** The followed motorsport leagues: those whose sport is motorsport, and those with a motorsport event listed. */
    private val leagues: List<League> by lazy {
        (sports.leagues.filter { Families.ofSport(it.sport) == Family.Motorsport } + sports.events.filter { it.family == Family.Motorsport }.map { it.league })
            .distinctBy { it.id }
    }

    /** Each league's places, one per weekend, as their words. */
    private val places: Map<Int, List<List<String>>> by lazy {
        sports.events.filter { it.family == Family.Motorsport }.groupBy { it.league.id }
            .mapValues { (_, sessions) -> sessions.map(::placeWords).filter { it.isNotEmpty() }.distinct() }
    }

    /** Each league's places, as their words, with the weekends held at each: two rounds at one place share it. */
    private val weekendsAt: Map<Pair<Int, List<String>>, Set<String>> by lazy {
        sports.events.filter { it.family == Family.Motorsport }.groupBy { it.league.id to placeWords(it) }
            .mapValues { (_, sessions) -> sessions.mapTo(HashSet(), ::weekendKey) }
    }

    override fun context(event: SportsEvent): NameContext =
        NameContext(title = event.title, league = event.league.name, series = seriesOf(event.league).written, years = eventYears(event))

    /**
     * Stage 1: the series' first two forms, each with the place ("Formula 1 Kestrel", "Formula1 Kestrel"); stage 2: the
     * first form with the round's number ("Formula 1 20"). Never the year or the session, which names glue or translate.
     */
    override fun queries(event: SportsEvent, part: Part): Queries {
        val forms = seriesOf(event.league).written
        val first = forms.firstOrNull() ?: return Queries(emptyList())
        val place = placeTokens(event).joinToString(" ") { it.written }
        return Queries(forms.take(2).map { "$it $place".trim() }, event.roundNumber?.let { "$first $it" })
    }

    /**
     * Where another round of the league is held at the same place, its sessions of this one's kind: a date in a name that
     * is one of theirs is theirs (L647 rule 5). None where the place is held once, as the place then names the round.
     */
    override fun related(event: SportsEvent): List<SportsEvent> {
        if (!placeShared(event)) return emptyList()
        val place = placeWords(event)
        return sports.events.filter { other ->
            other.family == Family.Motorsport && other.league.id == event.league.id && weekendKey(other) != weekendKey(event) &&
                other.session?.kind == event.session?.kind && placeWords(other) == place
        }
    }

    override fun judge(event: SportsEvent, part: Part, name: SportsName, posted: Instant?, size: Long?): Verdict {
        val session = event.session ?: return OTHER_SESSION
        val words = name.identity.map { it.word }
        val ours = seriesOf(event.league).folded.flatMap { occurrences(words, it) }
        val another = anotherSeries(event.league, words, ours) || anotherNumber(event.league, words) || anotherClass(event.league, words, ours)
        if (ours.isEmpty() || another) return OTHER_SESSION
        if (!seasonYear(name, event)) return OTHER_SESSION
        val round = event.roundNumber
        val rounds = name.numbered(NumberWord.Round)
        if (round != null && rounds.any { it != round }) return OTHER_SESSION
        // The place is read outside the series' own words: "Kestrel" in "Kestrel Endurance Series" names no place.
        val outside = words.filterIndexed { at, _ -> ours.none { at in it } }.toSet()
        val place = placeWords(event)
        val atPlace = place.isNotEmpty() && outside.containsAll(place)
        val elsewhere = places[event.league.id].orEmpty().filter { it != place }
            .any { other -> outside.containsAll(other) && !(atPlace && place.containsAll(other)) }
        if (elsewhere) return OTHER_SESSION
        // A place held by two rounds names neither alone: the name's date, which the shared rules held to this round's, or
        // its round's number must.
        val placed = (atPlace && (!placeShared(event) || name.dates.isNotEmpty())) || (round != null && round in rounds)
        val verdict = sessionVerdict(event, session, name, posted, placed)
        return when {
            verdict !is Verdict.Accept -> verdict
            session.kind == SessionKind.Testing -> Verdict.Offer(Words.TESTING)
            session.kind == SessionKind.Race && Runtimes.endurance(event.title) -> Verdict.Offer(Words.ENDURANCE)
            else -> verdict
        }
    }

    /**
     * The session [name] says, for [event]'s [session], the series, the year and the round already its: a weekend's pack
     * (several session words, or the weekend's) is every session's; no session word is the race, once it started; else the
     * same session, practice N to N, a race's number where its round has several. [placed]: the place or the round stands
     * in the name.
     */
    private fun sessionVerdict(event: SportsEvent, session: Session, name: SportsName, posted: Instant?, placed: Boolean): Verdict {
        val said = name.sessions.distinctBy { it.kind to it.number }
        if (said.size > 1 || WEEKEND.any(name::has)) return if (placed) Verdict.Accept(whole = false) else OTHER_SESSION
        val weekend = sports.weekendOf(event)?.sessions.orEmpty()
        val word = said.singleOrNull()
        val noRaceNumber = if (word == null) {
            if (session.kind != SessionKind.Race || posted?.isBefore(event.start) == true || !placed) return OTHER_SESSION
            weekend.count { it.session?.kind == SessionKind.Race } > 1
        } else {
            if (word.kind != session.kind) return OTHER_SESSION
            // While a session is the only one of its kind in its round, a number on one side alone tells nothing: a "Race"
            // takes "Race 1", and "Free Practice" names the one "Practice 1".
            val alone = weekend.count { it.session?.kind == session.kind } <= 1
            val number = number(session)
            val same = word.number == number || ((number == null || word.number == null) && alone)
            val unnumberedRace = session.kind == SessionKind.Race && !alone && word.number == null
            if (!same && !unnumberedRace) return OTHER_SESSION
            !same
        }
        return when {
            !placed -> Verdict.Offer(if (name.indexes.any { it.form == IndexForm.Cross }) Words.RUNNING_NUMBER else Words.NO_PLACE)
            word?.foreign == true -> Verdict.Offer(Words.NOT_IN_ENGLISH)
            noRaceNumber -> Verdict.Offer(Words.NO_RACE_NUMBER)
            else -> Verdict.Accept()
        }
    }

    /** Another followed series' form in [words], standing outside every one of [league]'s own ([ours]): "F1 Academy" at F1's. */
    private fun anotherSeries(league: League, words: List<String>, ours: List<IntRange>): Boolean =
        leagues.filter { it.id != league.id }.flatMap { seriesOf(it).folded }.any { form ->
            occurrences(words, form).any { at -> ours.none { at.first >= it.first && at.last <= it.last } }
        }

    /** A form of [league]'s with another number of one or two digits in its number's place: "Formula 2", "F2". */
    private fun anotherNumber(league: League, words: List<String>): Boolean = seriesOf(league).folded.any { form ->
        val (letters, number) = numbered(form.last()) ?: return@any false
        (0..words.size - form.size).any { at ->
            form.indices.all { it == form.lastIndex || words[at + it] == form[it] } &&
                numbered(words[at + form.lastIndex])?.let { (l, n) -> l == letters && n.length <= 2 && n.trimStart('0') != number.trimStart('0') } == true
        }
    }

    /**
     * A class run beside [league]'s series, under its banner: outside the series' own forms ([ours]), a word of two or more
     * letters and a one- or two-digit number whose letters begin a word of the series' forms ("Moto2" or "Moto3" in a
     * MotoGP name), a word a number follows aside ("Round19", "Race2").
     */
    private fun anotherClass(league: League, words: List<String>, ours: List<IntRange>): Boolean {
        val formWords = seriesOf(league).folded.flatten().toSet()
        return words.withIndex().any { (at, word) ->
            if (word in formWords || ours.any { at in it }) return@any false
            val (letters, number) = numbered(word) ?: return@any false
            val numberWord = letters in NameWords.numberWords || NameWords.sessions.startingWith(letters).isNotEmpty()
            letters.length >= 2 && number.length <= 2 && !numberWord && formWords.any { it.startsWith(letters) }
        }
    }

    /** Whether another weekend of [event]'s league is held at its place. */
    private fun placeShared(event: SportsEvent): Boolean = (weekendsAt[event.league.id to placeWords(event)]?.size ?: 0) > 1

    /** The weekend [event] is a session of, or the event itself where it is none's. */
    private fun weekendKey(event: SportsEvent): String = sports.weekendOf(event)?.key ?: event.key

    /** The season's year in the name: a year, a running number's or an episode's, a season span's, or a date's. */
    private fun seasonYear(name: SportsName, event: SportsEvent): Boolean {
        val years = name.years + name.indexes.map { it.year } + name.seasons.flatMap { listOf(it.first, it.second) } +
            name.dates.flatMap { date -> date.readings.map { it.year } }
        return years.any { it in event.seasonYears }
    }

    private fun seriesOf(league: League): Forms = formsOf.getOrPut(league) {
        val written = forms(league.name, league.alternateNames)
        Forms(written, written.map(::words))
    }

    /** A league's series forms as written, and as a name's words fold them. */
    private class Forms(val written: List<String>, val folded: List<List<String>>)

    companion object {
        /**
         * [league]'s series forms (L649), in order: its name; its twin where a word stands beside a number ("Formula 1",
         * "Formula1"); each of its other names with its own twin; then the initials of each of those of two or more words,
         * numbers kept ("F1"). None twice. No series list of the plugin's own: the forms come from Sportarr's names alone.
         */
        fun forms(league: League): List<String> = forms(league.name, league.alternateNames)

        private fun forms(name: String, others: List<String>): List<String> {
            val named = (listOf(name) + others).map(String::trim).filter(String::isNotEmpty).flatMap { listOfNotNull(it, twin(it)) }
            return (named + named.mapNotNull(::initials)).distinctBy(::words).filter { words(it).isNotEmpty() }
        }

        /** "Formula 1" as "Formula1", "Formula1" as "Formula 1"; null for a name with no word beside a number. */
        private fun twin(name: String): String? {
            val written = Tokens.of(name).map { it.written }
            val joined = mutableListOf<String>()
            written.forEach { word ->
                val last = joined.lastOrNull()
                if (last != null && word.all(Char::isDigit) && last.last().isLetter()) joined[joined.lastIndex] = last + word else joined += word
            }
            if (joined != written) return joined.joinToString(" ")
            val spaced = written.map { word -> numbered(word)?.takeIf { it.first.isNotEmpty() }?.let { (letters, number) -> "$letters $number" } ?: word }
            return spaced.joinToString(" ").takeIf { spaced != written }
        }

        /** A name of two or more words as its initials, numbers kept whole: "Formula 1" is "F1". */
        private fun initials(name: String): String? {
            val written = Tokens.of(name).map { it.written }
            if (written.size < 2) return null
            return written.joinToString("") { word -> if (word.all(Char::isDigit)) word else word.first().uppercase() }
        }

        /** A word as its letters and the number glued after them ("Formula1", "F1", "1"); null where it ends in no number. */
        private fun numbered(word: String): Pair<String, String>? {
            val at = word.indexOfFirst(Char::isDigit)
            if (at < 0 || !word.substring(at).all(Char::isDigit) || !word.substring(0, at).all(Char::isLetter)) return null
            return word.substring(0, at) to word.substring(at)
        }

        /** The place's words as written: the weekend's stem less "Grand Prix", "GP", "E-Prix", "Rally", "of" and "the". */
        private fun placeTokens(event: SportsEvent): List<Token> {
            val tokens = Tokens.of(event.session?.stem ?: event.title)
            val place = mutableListOf<Token>()
            var at = 0
            while (at < tokens.size) {
                val skipped = NOT_PLACE.firstOrNull { phrase -> phrase.indices.all { tokens.getOrNull(at + it)?.word == phrase[it] } }
                if (skipped != null) at += skipped.size else place += tokens[at++]
            }
            return place
        }

        private fun placeWords(event: SportsEvent): List<String> = placeTokens(event).map { it.word }

        /** The session's number, as its label says it ("Practice 2"); none for one without ("Race"). */
        private fun number(session: Session): Int? = SportsName.read(session.label).sessions.firstOrNull { it.kind == session.kind }?.number

        /** Where [phrase] stands among [words], as ranges of their places. */
        private fun occurrences(words: List<String>, phrase: List<String>): List<IntRange> =
            if (phrase.isEmpty()) emptyList() else (0..words.size - phrase.size).filter { at -> phrase.indices.all { words[at + it] == phrase[it] } }
                .map { it until it + phrase.size }

        private fun words(text: String): List<String> = Tokens.of(text).map { it.word }

        private val OTHER_SESSION = Verdict.Reject(RejectKind.OtherEvents)

        /** The words that are no place in a weekend's title, folded. */
        private val NOT_PLACE = listOf(listOf("grand", "prix"), listOf("e", "prix"), listOf("eprix"), listOf("gp"), listOf("rally"), listOf("of"), listOf("the"))

        /** A weekend's pack, which several session words say too. */
        private val WEEKEND = listOf("full weekend", "all sessions", "weekend")
    }
}
