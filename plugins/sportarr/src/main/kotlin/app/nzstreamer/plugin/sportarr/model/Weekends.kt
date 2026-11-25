package app.nzstreamer.plugin.sportarr.model

import java.time.Duration
import java.util.Locale

/**
 * A motorsport weekend (L644): one round of one league's sessions, each its own event in Sportarr, grouped for display
 * alone. It has no item key and no watch state of its own; its page key is [key]. Its [title] is the sessions' shared stem
 * ("Grand Prix of Kestrel"), [round] the round as Sportarr writes it, [roundNumber] its number where it has one.
 */
data class Weekend(
    override val key: String,
    override val league: League,
    val title: String,
    val round: String?,
    val roundNumber: Int?,
    val sessions: List<SportsEvent>,
) : Listed {
    /** What marks the weekend watched: its race, or every race where a round has several; with no race, every session. */
    val headline: List<SportsEvent> = sessions.filter { it.session?.kind == SessionKind.Race }.ifEmpty { sessions }

    override val events: List<SportsEvent> get() = sessions
    override val items: List<Item> get() = sessions.flatMap { it.items }
}

/** What a motorsport session is, for its runtime and for which one is the race. */
enum class SessionKind { Practice, Qualifying, SprintQualifying, Sprint, WarmUp, Testing, Race }

/**
 * A motorsport session as its title names it: its [label], the session words taken from the title as written ("Practice
 * 1", "Qualifying"; "Race" for a title with none), its [kind], and its title's [stem], the title less those words and the
 * separators before them ("Grand Prix of Kestrel").
 */
data class Session(val label: String, val kind: SessionKind, val stem: String)

/** Whether a word list's entry takes the whole number after it as its own ("Half 2", "Practice 2"). */
enum class Numbering { None, Optional, Required }

/**
 * One session word (L646): its folded [words], the session [kind] they name, whether a number follows them
 * ([numbering]), the number the word carries itself ("FP2" is practice 2), and whether it is in another language than
 * English ([foreign]).
 */
class SessionPhrase(val words: List<String>, val kind: SessionKind, val numbering: Numbering, val number: Int?, val foreign: Boolean)

/**
 * The session words: one list, which a session's title, the families' step-down and the release name parser all read.
 * They were written for this plugin from how race weekends are run, named and broadcast, by writers who read no other
 * project's lists: English, and the German, French, Dutch, Spanish and Italian words those countries' broadcasts use.
 */
object Sessions {
    /** Each session word, as a name's words fold it. */
    val entries: List<SessionPhrase> = listOf(
        english("practice", SessionKind.Practice, Numbering.Optional),
        english("free practice", SessionKind.Practice, Numbering.Optional),
        english("fp", SessionKind.Practice, Numbering.Required),
        english("fp1", SessionKind.Practice, number = 1),
        english("fp2", SessionKind.Practice, number = 2),
        english("fp3", SessionKind.Practice, number = 3),
        english("fp4", SessionKind.Practice, number = 4),
        english("first practice", SessionKind.Practice, number = 1),
        english("1st practice", SessionKind.Practice, number = 1),
        english("second practice", SessionKind.Practice, number = 2),
        english("2nd practice", SessionKind.Practice, number = 2),
        english("third practice", SessionKind.Practice, number = 3),
        english("3rd practice", SessionKind.Practice, number = 3),
        // The last practice before qualifying: its number differs from series to series.
        english("final practice", SessionKind.Practice),
        english("qualifying", SessionKind.Qualifying, Numbering.Optional),
        english("quali", SessionKind.Qualifying, Numbering.Optional),
        english("qualification", SessionKind.Qualifying, Numbering.Optional),
        english("q1", SessionKind.Qualifying, number = 1),
        english("q2", SessionKind.Qualifying, number = 2),
        english("q3", SessionKind.Qualifying, number = 3),
        english("top 10 shootout", SessionKind.Qualifying),
        english("top ten shootout", SessionKind.Qualifying),
        english("superpole", SessionKind.Qualifying),
        english("hyperpole", SessionKind.Qualifying),
        english("sprint qualifying", SessionKind.SprintQualifying),
        english("sprint quali", SessionKind.SprintQualifying),
        english("sprint shootout", SessionKind.SprintQualifying),
        english("sprint", SessionKind.Sprint),
        english("sprint race", SessionKind.Sprint, Numbering.Optional),
        english("sprintrace", SessionKind.Sprint),
        english("superpole race", SessionKind.Sprint),
        english("race", SessionKind.Race, Numbering.Optional),
        english("feature race", SessionKind.Race),
        english("main race", SessionKind.Race),
        english("warm up", SessionKind.WarmUp),
        english("warmup", SessionKind.WarmUp),
        english("testing", SessionKind.Testing),
        english("pre season testing", SessionKind.Testing),
        english("preseason testing", SessionKind.Testing),
        english("winter testing", SessionKind.Testing),
        english("test day", SessionKind.Testing, Numbering.Optional),
        english("shakedown", SessionKind.Testing),
        // German.
        foreign("freies training", SessionKind.Practice, Numbering.Optional),
        foreign("training", SessionKind.Practice, Numbering.Optional),
        foreign("qualifikation", SessionKind.Qualifying),
        foreign("zeittraining", SessionKind.Qualifying),
        foreign("sprintqualifikation", SessionKind.SprintQualifying),
        foreign("sprint qualifikation", SessionKind.SprintQualifying),
        foreign("sprintrennen", SessionKind.Sprint),
        foreign("sprint rennen", SessionKind.Sprint),
        foreign("rennen", SessionKind.Race, Numbering.Optional),
        foreign("hauptrennen", SessionKind.Race),
        foreign("testfahrten", SessionKind.Testing),
        // French.
        foreign("essais libres", SessionKind.Practice, Numbering.Optional),
        foreign("el1", SessionKind.Practice, number = 1),
        foreign("el2", SessionKind.Practice, number = 2),
        foreign("el3", SessionKind.Practice, number = 3),
        foreign("qualifications", SessionKind.Qualifying),
        foreign("qualifications sprint", SessionKind.SprintQualifying),
        foreign("qualification sprint", SessionKind.SprintQualifying),
        foreign("course sprint", SessionKind.Sprint),
        foreign("course", SessionKind.Race),
        foreign("course principale", SessionKind.Race),
        foreign("essais hivernaux", SessionKind.Testing),
        // Dutch.
        foreign("vrije training", SessionKind.Practice, Numbering.Optional),
        foreign("vt1", SessionKind.Practice, number = 1),
        foreign("vt2", SessionKind.Practice, number = 2),
        foreign("vt3", SessionKind.Practice, number = 3),
        foreign("kwalificatie", SessionKind.Qualifying),
        foreign("sprintkwalificatie", SessionKind.SprintQualifying),
        foreign("sprint kwalificatie", SessionKind.SprintQualifying),
        // Spanish, its accents folded.
        foreign("entrenamientos libres", SessionKind.Practice, Numbering.Optional),
        foreign("entrenamiento libre", SessionKind.Practice, Numbering.Optional),
        foreign("libres", SessionKind.Practice, Numbering.Optional),
        foreign("practicas libres", SessionKind.Practice, Numbering.Optional),
        foreign("practica libre", SessionKind.Practice, Numbering.Optional),
        foreign("practica", SessionKind.Practice, Numbering.Optional),
        foreign("clasificacion", SessionKind.Qualifying),
        foreign("clasificacion sprint", SessionKind.SprintQualifying),
        foreign("carrera", SessionKind.Race),
        foreign("carrera sprint", SessionKind.Sprint),
        foreign("calentamiento", SessionKind.WarmUp),
        // Italian.
        foreign("prove libere", SessionKind.Practice, Numbering.Optional),
        foreign("pl1", SessionKind.Practice, number = 1),
        foreign("pl2", SessionKind.Practice, number = 2),
        foreign("pl3", SessionKind.Practice, number = 3),
        foreign("qualifiche", SessionKind.Qualifying),
        foreign("qualifica", SessionKind.Qualifying),
        foreign("qualifiche sprint", SessionKind.SprintQualifying),
        foreign("qualifica sprint", SessionKind.SprintQualifying),
        foreign("gara", SessionKind.Race, Numbering.Optional),
        foreign("gara sprint", SessionKind.Sprint),
    )

    /** Each session's words, longest first, so "sprint qualifying" is one session and not a sprint. */
    private val PHRASES: List<Pair<List<String>, SessionKind>> = entries.map { it.words to it.kind }.sortedByDescending { it.first.size }

    /** The session words, each a list of lower-case words. */
    val words: List<List<String>> = PHRASES.map { it.first }

    /**
     * [title]'s session: the session words at its end, each perhaps with its number ("Practice 1", "Race 2"), as written,
     * and the title before them, its separators (" - ", ":") dropped. A title that ends in none is the race.
     */
    fun read(title: String): Session {
        val tokens = WORD.findAll(title).toList()
        val kinds = mutableListOf<SessionKind>()
        var at = tokens.size
        while (at > 0) {
            // A number after a session word belongs to it.
            val end = if (tokens[at - 1].value.all(Char::isDigit)) at - 1 else at
            val phrase = PHRASES.firstOrNull { (words, _) ->
                end >= words.size && tokens.subList(end - words.size, end).map { it.value.lowercase(Locale.ROOT) } == words
            } ?: break
            kinds += phrase.second
            at = end - phrase.first.size
        }
        if (kinds.isEmpty()) return Session(RACE, SessionKind.Race, title.trim())
        val from = tokens[at].range.first
        val label = title.substring(from, tokens.last().range.last + 1)
        return Session(label, kindOf(kinds), title.substring(0, from).trimEnd { it.isWhitespace() || it in SEPARATORS }.trim())
    }

    /** Several session words name one session: "Sprint Race" is the sprint, "Sprint Qualifying" its qualifying. */
    private fun kindOf(kinds: List<SessionKind>): SessionKind = PRECEDENCE.first { it in kinds }

    private val PRECEDENCE = listOf(
        SessionKind.SprintQualifying, SessionKind.Sprint, SessionKind.Qualifying, SessionKind.WarmUp, SessionKind.Practice, SessionKind.Testing,
        SessionKind.Race,
    )

    private const val RACE = "Race"

    private fun english(words: String, kind: SessionKind, numbering: Numbering = Numbering.None, number: Int? = null) =
        SessionPhrase(words.split(' '), kind, numbering, number, foreign = false)

    private fun foreign(words: String, kind: SessionKind, numbering: Numbering = Numbering.None, number: Int? = null) =
        SessionPhrase(words.split(' '), kind, numbering, number, foreign = true)

    /** What may stand between a title's stem and its session words: " - ", ":", and the like. */
    private const val SEPARATORS = "-–:|(["
    private val WORD = Regex("""[\p{L}\p{N}]+""")
}

/**
 * Groups one league's sessions into weekends (L644): those whose rounds have the same number; those whose rounds hold no
 * number but the same text, trimmed and case folded; and the rest by the same stem with starts within four days of each
 * other. A round's number or text decides alone where a session has one, so two weekends never share a round; a session
 * with neither joins the first weekend whose every session it matches by stem, or starts one. Two leagues at one place
 * are two weekends.
 */
object Weekends {
    fun of(league: League, sessions: List<SportsEvent>): List<Weekend> {
        val inOrder = sessions.sortedWith(compareBy<SportsEvent> { it.start }.thenBy { it.key })
        val groups = mutableListOf<MutableList<SportsEvent>>()
        inOrder.filter { it.roundNumber != null }.groupBy { it.roundNumber }.values.forEach { groups += it.toMutableList() }
        inOrder.filter { it.roundNumber == null && Rounds.text(it.round) != null }.groupBy { Rounds.text(it.round) }.values
            .forEach { groups += it.toMutableList() }
        inOrder.filter { it.roundNumber == null && Rounds.text(it.round) == null }.forEach { session ->
            groups.firstOrNull { group -> group.all { sameStem(it, session) } }?.add(session) ?: groups.add(mutableListOf(session))
        }
        return groups.map { group -> weekend(league, group.sortedWith(compareBy<SportsEvent> { it.start }.thenBy { it.key })) }
            .sortedWith(compareBy<Weekend> { it.sessions.first().start }.thenBy { it.key })
    }

    private fun weekend(league: League, sessions: List<SportsEvent>): Weekend {
        val numbered = sessions.firstOrNull { it.roundNumber != null }
        val number = numbered?.roundNumber
        val key = if (number != null) "${league.id}:r$number" else "${league.id}:s${sessions.first().key}"
        val named = sessions.firstOrNull { it.session?.kind == SessionKind.Race } ?: sessions.first()
        return Weekend(key, league, named.session?.stem ?: named.title, (numbered ?: sessions.firstOrNull { it.round != null })?.round, number, sessions)
    }

    private fun sameStem(a: SportsEvent, b: SportsEvent): Boolean =
        stem(a) == stem(b) && Duration.between(a.start, b.start).abs() <= APART

    private fun stem(event: SportsEvent): String =
        (event.session?.stem ?: event.title).lowercase(Locale.ROOT).split(SPACES).filter { it.isNotEmpty() }.joinToString(" ")

    private val APART: Duration = Duration.ofDays(4)
    private val SPACES = Regex("""\s+""")
}
