package app.nzstreamer.plugin.sportarr.ui

import app.nzstreamer.plugin.sportarr.model.Count

/**
 * The plugin's words. Strings live in Kotlin, as a plugin carries no resources (L580, L660), with straight apostrophes as
 * the host's strings have them.
 */
internal object Words {
    /** The tab's label (W164 a), and the title of its own page. */
    const val TAB = "Sports"

    /** The title of the plugin's settings page, the same form as the tab's first screen. */
    const val SETTINGS_TITLE = "Sportarr settings"

    /** Under the tab's title until a Save: what the tab shows. */
    const val SETUP_INTRO =
        "Sports lists what you follow in your Sportarr: the leagues you switched on there, with your teams and the sessions you chose."

    const val ADDRESS = "Sportarr address"
    const val ADDRESS_PLACEHOLDER = "http://sportarr.home:1867"
    const val ADDRESS_HELP = "Where you open Sportarr in a browser, with its port."

    const val KEY = "API key"
    const val KEY_HELP = "In Sportarr: Settings › General › Security. Stored encrypted on this device."

    /** A card's or weekend's count: "2 of 4 watched". */
    fun watched(count: Count): String = "${count.watched} of ${count.of} watched"

    /** After a league's first five on a day: "+3 more". */
    fun more(count: Int): String = "+$count more"

    // What Choose release and Play's no-release sheet say of a part's releases (L647). An item's noun is "game", "session",
    // a card part's label ("main card"), "fight", "show", "pre-show", "main show" or "event"; an event's is "game",
    // "session", "card", "fight", "show" or "event".

    /**
     * Choose release's last line, counting what was left out by kind and never naming one: "6 left out: highlights, a name
     * with a score, other games. Their names aren't shown."
     */
    fun leftOut(count: Int, kinds: List<String>, more: Boolean): String =
        "$count left out: ${kinds.joinToString(", ")}${if (more) " and more" else ""}. Their names aren't shown."

    /** An indexer's line on the no-release sheet when what it found was none of the item's: "6 results, none for this game". */
    fun noneFor(results: Int, noun: String): String = "${if (results == 1) "1 result" else "$results results"}, none for this $noun"

    /** An indexer's line when it answered with nothing. */
    const val NO_RESULTS = "No results"

    /** The no-release sheet's note: "Releases often appear a few hours after a game ends. Try Play again later." */
    fun note(noun: String): String = "Releases often appear a few hours after ${a(noun)} $noun ends. Try Play again later."

    /** A possible match too small for the part's runtime: "Small for a whole game". */
    fun small(noun: String): String = "Small for a whole $noun"

    /** The play's failure when a release holds no file for the part: "No file for the Main Card in this release". */
    fun noFile(label: String): String = "No file for the $label in this release"

    // Why a release is a possible match rather than Play's (L648-L651), 60 characters at most, the host's rule.

    /** Both teams, no date, and they meet once in the list. */
    const val NO_DATE = "No date in the name"

    /** Two upper-case codes in place of the teams' names. */
    const val TEAM_CODES = "Team codes only"

    /** One team, the date and the league. */
    const val ONE_TEAM = "Only one team named"

    /** A double header's name with no game number. */
    const val NO_GAME_NUMBER = "No game number"

    /** A week number where the game's round is unknown. */
    const val WEEK_NO_DATE = "A week number, no date"

    /** A cricket match's day. */
    const val ONE_DAY = "One day of the match"

    /** The series, the year and the session, a running number (2026x20) in place of the place. */
    const val RUNNING_NUMBER = "Only a running number, no place"

    /** The series, the year and the session, and neither the place nor the round. */
    const val NO_PLACE = "No place or round in the name"

    /** The session's word in another language than English. */
    const val NOT_IN_ENGLISH = "Session not named in English"

    /** An endurance race, which has no runtime to check a release by. */
    const val ENDURANCE = "Endurance race: you pick"

    /** A testing session. */
    const val TESTING = "Testing: you pick"

    /** A round with several races, and a name that says none's number. */
    const val NO_RACE_NUMBER = "No race number in the name"

    // The kinds a release is left out as, in the left-out line (L647).

    /** Another event's release: "other games", "other sessions", "other cards". */
    fun otherEvents(noun: String): String = "other ${noun}s"

    const val OTHER_PARTS = "other parts"
    const val HIGHLIGHTS = "highlights"

    fun withScore(count: Int): String = if (count == 1) "a name with a score" else "names with a score"

    fun withResult(count: Int): String = if (count == 1) "a name with a result" else "names with a result"

    const val PREVIEWS_AND_SHOWS = "previews and shows"

    /** A motorsport programme before or after the race. */
    fun preRaceShow(count: Int): String = if (count == 1) "a pre-race show" else "pre-race shows"

    /** A half, quarter, period or innings alone: "parts of a game". */
    fun partsOf(noun: String): String = "parts of ${a(noun)} $noun"

    const val PACKS = "packs"
    const val OLDER = "older releases"

    /** The article before [noun]: "an event", "a game". */
    private fun a(noun: String): String = if (noun.firstOrNull()?.lowercaseChar() in VOWELS) "an" else "a"

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')
}
