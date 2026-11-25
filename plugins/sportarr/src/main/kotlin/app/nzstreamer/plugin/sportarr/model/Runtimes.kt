package app.nzstreamer.plugin.sportarr.model

import app.nzstreamer.plugin.sportarr.source.Families
import java.util.Locale

/**
 * How long a part runs, in minutes (L644): the plugin's one table. Each figure feeds the host's ranking (a search is given
 * its part's runtime) and the size floor; null is unknown, and gives neither. Real releases tune them.
 */
object Runtimes {
    /** A team game, by its sport's words folded as the families fold them; any other team game's is [OTHER_TEAM_GAME]. */
    fun teamGame(sport: String?): Int = TEAM_GAMES[Families.fold(sport)] ?: OTHER_TEAM_GAME

    /** A motorsport session, by its kind: testing, and the race of an endurance event ([endurance]), unknown. */
    fun session(kind: SessionKind, title: String): Int? = when (kind) {
        SessionKind.Practice -> 65
        SessionKind.Qualifying -> 70
        SessionKind.SprintQualifying -> 45
        SessionKind.Sprint -> 45
        SessionKind.WarmUp -> 30
        SessionKind.Testing -> null
        SessionKind.Race -> if (endurance(title)) null else 120
    }

    /** A fight card's part, by its id. */
    fun cardPart(id: String): Int? = when (id) {
        Parts.EARLY_PRELIMS -> 90
        Parts.PRELIMS -> 120
        Parts.MAIN_CARD -> 180
        Parts.PRE_SHOW -> 60
        Parts.MAIN_SHOW -> 240
        else -> null
    }

    /** A boxing matchup, a whole card. */
    const val MATCHUP = 240

    /** A weekly wrestling show. */
    const val WEEKLY_SHOW = 150

    const val OTHER_TEAM_GAME = 110

    /** An endurance event: a title with "Hours" after a number ("6 Hours of Oskelund"), or the word "Endurance". */
    fun endurance(title: String): Boolean {
        val words = title.lowercase(Locale.ROOT).split(NOT_A_WORD).filter { it.isNotEmpty() }
        return "endurance" in words || words.zipWithNext().any { (number, hours) -> number.all(Char::isDigit) && hours == "hours" }
    }

    private val NOT_A_WORD = Regex("""[^\p{L}\p{N}]+""")

    /**
     * The minutes the plugin gives a game of these sports, keyed by each sport's names as the families hold them, folded
     * ("Football" is American football in Sportarr's words).
     * Rugby sevens and 3x3 basketball are short games: their own figure, not their sport's, so the size floor fits them.
     */
    private val TEAM_GAMES: Map<String, Int> = buildMap {
        listOf("soccer", "association football", "futbol").forEach { put(it, 110) }
        listOf("football", "american football", "gridiron football").forEach { put(it, 190) }
        listOf("australian rules football", "australian football", "aussie rules").forEach { put(it, 150) }
        listOf("rugby union", "rugby").forEach { put(it, 110) }
        listOf("rugby sevens", "rugby 7s").forEach { put(it, 30) }
        put("rugby league", 100)
        put("basketball", 150)
        put("3x3 basketball", 30)
        listOf("ice hockey", "hockey").forEach { put(it, 150) }
        put("baseball", 180)
        put("cricket", 210)
    }
}
