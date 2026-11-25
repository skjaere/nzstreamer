package app.nzstreamer.plugin.sportarr.ui

import app.nzstreamer.plugin.sportarr.model.Count
import app.nzstreamer.plugin.sportarr.source.Failure
import app.nzstreamer.plugin.sportarr.source.TestAnswer

/**
 * Every word the plugin shows (L652, L660), in one file: strings in Kotlin, as a plugin carries no resources (L580), with
 * straight apostrophes as the host's strings have them. Each group holds one screen's words, phone and TV together. A word
 * drawn in an approved design names that design and its frame ("SpPhoneSetup 3") or its notes; a word marked Undrawn is the
 * plugin's own, for a state or case no design shows. The formats of dates, times and lengths are [Times]', written with
 * the words and the day and month names in [Dates].
 *
 * No word here is a score, a result, a status or anything else of an event's that Sportarr sends besides its title, league,
 * round and times (L653): the plugin shows those as Sportarr gives them.
 */
internal object Words {
    /** SpPhoneList 1: a line's parts with " · " between them, a missing one left out: "Premier League · 1 h 5 min left". */
    fun line(vararg parts: String?): String = parts.filterNotNull().joinToString(" · ")

    /** The tab's first screen and the settings page: the same form (L640). */
    object Setup {
        /** SpPhoneSetup 1: the tab's name (W164 a) in the bar and the rail, and its own page's title; SpTvSetup 1. */
        const val TAB = "Sports"

        /** SpPhoneSetup 5: the settings page's title, the same form as the tab's first screen; also in the tab's ⋮ (SpPhoneList 6). */
        const val SETTINGS_TITLE = "Sportarr settings"

        /** SpPhoneSetup 1: under the tab's title until a Save, what the tab shows. */
        const val INTRO =
            "Sports lists what you follow in your Sportarr: the leagues you switched on there, with your teams and the sessions you chose."

        /** SpPhoneSetup 1: the address field's label. */
        const val ADDRESS = "Sportarr address"

        /** SpPhoneSetup 1: the address field's placeholder. */
        const val ADDRESS_PLACEHOLDER = "http://sportarr.home:1867"

        /** SpPhoneSetup 1: the address's help while it is empty, or encrypted. */
        const val ADDRESS_HELP = "Where you open Sportarr in a browser, with its port."

        /** SpPhoneSetup 2: the address's help for plain http on the local network, the indexer form's words. */
        const val LOCAL_HTTP = "Plain http on your local network."

        /** SpPhoneSetup's notes: the address's help for plain http over the internet. Save still saves with it shown. */
        const val PUBLIC_HTTP =
            "⚠ Not encrypted: your API key would be readable on its way over the internet. Use https:// if your Sportarr has it."

        /** SpPhoneSetup 1: the key field's label. */
        const val KEY = "API key"

        /** SpPhoneSetup 1: the key's help. */
        const val KEY_HELP = "In Sportarr: Settings › General › Security. Stored encrypted on this device."

        /** SpPhoneSetup 1: in the key's field, which shows the key typed; SpTvSetup 1. */
        const val SHOW = "Show"

        /** Undrawn: Show's word while the key shows, as the host's own key fields have it. */
        const val HIDE = "Hide"

        /** SpPhoneSetup 5: the key field once a key is saved, which it never shows. */
        const val SAVED = "Saved"

        /** SpPhoneSetup 5: in the saved key's field, which it empties for a new key; SpTvSetup 4. */
        const val CHANGE = "Change"

        /** SpPhoneSetup 6: the emptied key field's placeholder after Change. */
        const val PASTE_NEW_KEY = "Paste the new key"

        /** SpPhoneSetup 1: asks Sportarr who it is, with the key. */
        const val TEST = "Test"

        /** SpPhoneSetup 2: Test while it runs. */
        const val TESTING = "Testing…"

        /** SpPhoneSetup 1: stores the address and the key. */
        const val SAVE = "Save"

        /** SpPhoneSetup 1: under the form, what Sports does with the key. */
        const val READ_ONLY =
            "Sports only reads from Sportarr. It never changes, searches or downloads anything there. To change what shows here, change it in Sportarr."

        /** SpPhoneSetup 3: Test passed, with the version as Sportarr sent it, all four of its parts: "Connected · Sportarr <version>". */
        fun connected(version: String): String = "Connected · Sportarr $version"

        /**
         * SpPhoneSetup 4: no answer from [hostPort] as typed: "Couldn't reach sportarr.home:1867. Check that Sportarr is running
         * and that this phone is on its network."; "this TV" on a TV (SpTvSetup).
         */
        fun unreachable(hostPort: String, tv: Boolean): String =
            "Couldn't reach $hostPort. Check that Sportarr is running and that this ${if (tv) "TV" else "phone"} is on its network."

        /** SpPhoneSetup's notes: Sportarr answered 401 or 403; SpTvSetup 3. */
        const val REFUSED = "Sportarr refused this API key. Copy it again from Settings › General › Security in Sportarr."

        /**
         * SpPhoneSetup's notes: a Sportarr older than 4.1.1, by the version it sent: "This Sportarr is 4.0.998. Sports needs 4.1.1
         * or newer: update Sportarr, then Test again." Undrawn: the line for one that sent no version.
         */
        fun tooOld(version: String?): String {
            val which = version?.let { "This Sportarr is $it." } ?: "This Sportarr sends no version."
            return "$which Sports needs 4.1.1 or newer: update Sportarr, then Test again."
        }

        /** SpPhoneSetup's notes: something answers at the address, but not Sportarr. */
        const val NOT_SPORTARR = "This address answers, but it isn't Sportarr."

        /** Undrawn: Sportarr answered with its own error, a 5xx: "Sportarr answered with an error (500). Try again in a moment." */
        fun serverError(status: Int): String = "Sportarr answered with an error ($status). Try again in a moment."

        /**
         * Undrawn: an address that can't be read as one Sportarr can be asked at (L640): no http:// or https://, or a ? or #
         * part, which a copy of the browser's bar can bring.
         */
        const val INVALID_ADDRESS = "Sports can't read this address. Type it starting with http:// or https://, with nothing after a ? or #."

        /**
         * Test's [answer] as its line under Test and Save (SpPhoneSetup 3 and 4, and the notes' answers), on a TV where [tv]. The ✓ or
         * ⚠ before it is the kit's result line's.
         */
        fun answer(answer: TestAnswer, tv: Boolean): String = when (answer) {
            is TestAnswer.Connected -> connected(answer.version)
            is TestAnswer.Unreachable -> unreachable(answer.hostPort, tv)
            TestAnswer.Refused -> REFUSED
            is TestAnswer.TooOld -> tooOld(answer.version)
            TestAnswer.NotSportarr -> NOT_SPORTARR
            is TestAnswer.ServerError -> serverError(answer.status)
            TestAnswer.InvalidAddress -> INVALID_ADDRESS
        }
    }

    /** The tab's list: its title line, the chips, Up next, the days and their rows. */
    object SportsList {
        /** SpPhoneList 1: the side with what has started, newest first; SpTvList 1. */
        const val RECENT = "Recent"

        /** SpPhoneList 5: the side with what is still to start, soonest first. */
        const val UPCOMING = "Upcoming"

        /** SpPhoneList 6: the title line's ⋮, as TalkBack reads it. */
        const val MORE = "More"

        /** SpPhoneList 6: in the ⋮, while watched events are hidden. */
        const val SHOW_WATCHED = "Show watched"

        /** Undrawn: in the ⋮ in Show watched's place, once watched events show. */
        const val HIDE_WATCHED = "Hide watched"

        /** SpPhoneList 6: in the ⋮, a read of the list now. */
        const val REFRESH = "Refresh"

        /** SpPhoneList 1: the first sport chip, and every sport. */
        const val ALL = "All"

        /** SpPhoneList 4: the first league chip once a sport is picked. */
        const val ALL_LEAGUES = "All leagues"

        /** SpPhoneList 1: the heading over what to go on with. */
        const val UP_NEXT = "Up next"

        /** SpPhoneList 1: an Up next row's play button as TalkBack reads it; SpPhoneWeekend 2's top button, "Play Race". */
        fun play(what: String): String = "Play $what"

        /** SpPhoneList 1: a card's or weekend's count: "3 of 5 watched". */
        fun watched(count: Count): String = "${count.watched} of ${count.of} watched"

        /** SpPhoneList 1: a weekend's round, only where it has a number (L644): "Round 20". */
        fun round(number: Int): String = "Round $number"

        /** SpPhoneList 4: a weekend's sessions before one is watched: "2 sessions". Undrawn: "1 session". */
        fun sessions(count: Int): String = if (count == 1) "1 session" else "$count sessions"

        /** SpPhoneList 5: on Upcoming, the session a weekend starts with: "Practice 1 first". */
        fun first(session: String): String = "$session first"

        /** SpPhoneList 2: after a league's first five on a day: "+3 more". */
        fun more(count: Int): String = "+$count more"

        /** SpPhoneList 3: at the end of a league's day opened in place. */
        const val SHOW_FEWER = "Show fewer"

        /** SpPhoneList 2: a sport the plugin can't match well, whose releases the user picks (L651). */
        const val YOU_PICK = "You pick the release"

        /** SpPhoneList 7: a watched event's mark, and a watched part's on its label. */
        const val TICK = "✓"
    }

    /** The list's states (L641), each with one way on. The host's placeholder, for a plugin turned off or that can't start, isn't here. */
    object ListStates {
        /** SpPhoneListStates 2: over the kept list when a read failed: "Can't reach Sportarr · list from 3 h ago". */
        fun cantReach(age: String): String = "Can't reach Sportarr · list from $age"

        /** SpPhoneListStates 2: on that line, a read now; SpTvListStates 2. */
        const val TRY_AGAIN = "Try again"

        /** Undrawn: the card's title when a read failed and no list is kept. */
        const val CANT_REACH = "Can't reach Sportarr"

        /** Undrawn: that card's line, by [why] the read failed; "this TV" on a TV where [tv]. */
        fun cantReachWhy(why: Failure, tv: Boolean): String = when (why) {
            Failure.NoAnswer ->
                "Sportarr didn't answer. Check that Sportarr is running and that this ${if (tv) "TV" else "phone"} is on its network."
            Failure.ErrorStatus -> "Sportarr answered with an error. Try again in a moment."
            Failure.Unreadable -> "This address answers, but not as Sportarr does. Test it in Sportarr settings."
            Failure.TooLarge -> "Sportarr sent more than Sports can read at once."
        }

        /** Undrawn: over the kept list when Sportarr refused the key, changed there: "Sportarr refused the API key · list from 3 h ago". */
        fun refused(age: String): String = "Sportarr refused the API key · list from $age"

        /** Undrawn: the card's title when Sportarr refused the key and no list is kept. */
        const val REFUSED = "Sportarr refused the API key"

        /** SpPhoneListStates 3: Sportarr answered, but no league is switched on there. */
        const val NOTHING_FOLLOWED = "Your Sportarr follows nothing yet"

        /** SpPhoneListStates 3: that card's line. */
        const val NOTHING_FOLLOWED_LINE =
            "Sports lists the leagues you switched on in Sportarr, with the teams and sessions you chose there. Switch one on in Sportarr, then Refresh."

        /** SpPhoneListStates 3: under that card (L600, L642). */
        const val NO_LEAGUE = "Events with no league in Sportarr don't show here: give them one there."

        /** SpPhoneListStates 4: leagues switched on, but nothing in Recent. */
        const val NOTHING_RECENT = "Nothing aired in the last 60 days"

        /**
         * SpPhoneListStates 4: that card's line, from the first day Recent reaches and with what Upcoming has: "Your Sportarr's
         * leagues have nothing between 23 Sep and today. Upcoming has 6 events." Undrawn: one event, and none.
         */
        fun nothingRecent(since: String, upcoming: Int): String = "Your Sportarr's leagues have nothing between $since and today. " +
            when (upcoming) {
                0 -> "Upcoming has nothing either."
                1 -> "Upcoming has 1 event."
                else -> "Upcoming has $upcoming events."
            }

        /** Undrawn: nothing in Upcoming; its button is Recent. */
        const val NOTHING_UPCOMING = "Nothing in the next 31 days"

        /** SpPhoneListStates' notes: everything on the side is watched and hidden; its button is Show watched. */
        const val ALL_WATCHED = "Everything here is watched"

        /** SpPhoneListStates' notes: a sport and league picked with nothing in them, Discover's words. */
        const val NOTHING_MATCHES = "Nothing matches these filters"

        /** SpPhoneListStates' notes: that card's button, Discover's words. */
        const val CLEAR_FILTERS = "Clear filters"

        /** SpPhoneListStates' notes: the end of Recent when the cap or a league's page budget cut events (L641). */
        const val CUT = "Sports keeps the newest 5,000 events: older ones aren't shown."
    }

    /** A game's page, and what every page has: the Movie page's look without poster or overview. */
    object Game {
        /** SpPhoneGame 1: the status of an event not played. */
        const val NOT_WATCHED = "Not watched"

        /** SpPhoneGame 4: the status of a watched event, card or weekend (SpPhoneWeekend 3, SpPhoneCard 2). */
        const val WATCHED = "✓ Watched"

        /** SpPhoneGame 1: plays the best release. */
        const val PLAY = "Play"

        /** SpPhoneGame 2: goes on from where the play stopped, with the release that played. */
        const val RESUME = "Resume"

        /** SpPhoneGame 4: plays a watched event from the start. */
        const val PLAY_AGAIN = "Play again"

        /** SpPhoneGame 1: the host's release list for the part; in a session's or part's ⋮ (SpPhoneWeekend 1). */
        const val CHOOSE_RELEASE = "Choose release"

        /** SpPhoneGame 1: the host's Search with the event's words and a warning line (SpPhoneSearchWarning). */
        const val SEARCH_INDEXERS = "Search indexers"

        /** SpPhoneGame 1: marks the part watched. */
        const val MARK_WATCHED = "Mark watched"

        /** SpPhoneGame 4: marks a watched part not watched. */
        const val MARK_UNWATCHED = "Mark unwatched"

        /** SpPhoneGame 3: in the buttons' place before the start; on a TV it holds the focus (SpTvGame 2). */
        const val NOT_STARTED =
            "Play, Choose release and Search indexers show once it has started. Releases often appear a few hours after the end."

        /** SpPhoneGame 1: the page's footnote, on a TV too (SpTvGame 1). */
        const val FOOTNOTE =
            "Play finds the best release on your indexers that names both teams and the date; the one that played before goes first. Details from your Sportarr."

        /** Undrawn: the footnote of a one-part page that isn't a team game's, a boxing matchup's or a weekly show's. */
        const val EVENT_FOOTNOTE =
            "Play finds the best release on your indexers that names this event and its date; the one that played before goes first. Details from your Sportarr."

        /** Undrawn: Choose release while it searches, which takes no second press. */
        const val SEARCHING = "Searching your indexers…"

        /** Undrawn: a page whose event left the list after a read (L652). */
        const val GONE = "This event is no longer in your Sportarr's list."

        /** Undrawn: that page's way back. */
        const val BACK = "Back"
    }

    /** A motorsport weekend's page (L644): one round's sessions. */
    object Weekend {
        /** SpPhoneWeekend 1: a session whose title holds no session word is the race. */
        const val RACE = "Race"

        /** SpPhoneWeekend 1: a session's or part's ⋮, as TalkBack reads it: "More for Practice 3". */
        fun moreFor(name: String): String = "More for $name"

        /** SpPhoneWeekend 1: the page's footnote. */
        const val FOOTNOTE = "Tap a session to play the best release, or ⋮ to choose one yourself. Details from your Sportarr."

        /** SpTvWeekend 1: the left pane's line on a TV. */
        const val TV_FOOTNOTE = "Press OK on a session to play the best release, or ⋮ to choose one yourself."

        /** Undrawn: under the sessions before the first starts, as a card's page says it (SpPhoneCard 3); on a TV it holds the focus. */
        const val NOT_STARTED = "The sessions play once each has started. Releases often appear a few hours after the end."
    }

    /** A fight card's page and its parts (L644). */
    object Card {
        /** SpPhoneCard 1: a numbered card's first part. */
        const val EARLY_PRELIMS = "Early Prelims"

        /** SpPhoneCard 1: a numbered card's or fight night's prelims. */
        const val PRELIMS = "Prelims"

        /** SpPhoneCard 1: a numbered card's or fight night's main part, its headline. */
        const val MAIN_CARD = "Main Card"

        /** Undrawn: a premium event's first part, where Sportarr's names don't fit. */
        const val PRE_SHOW = "Pre-Show"

        /** Undrawn: a premium event's main part, its headline, where Sportarr's names don't fit. */
        const val MAIN_SHOW = "Main Show"

        /** SpPhoneCard 3: under the parts before the card starts. */
        const val NOT_STARTED = "The parts play once the card has started. Releases often appear a few hours after the end."

        /** SpPhoneCard 1: the page's footnote. */
        const val FOOTNOTE = "Tap a part to play the best release, or ⋮ to choose one yourself. Details from your Sportarr."

        /** Undrawn: the left pane's line on a TV, as a weekend's. */
        const val TV_FOOTNOTE = "Press OK on a part to play the best release, or ⋮ to choose one yourself."
    }

    /** Undrawn: the page of a sport the plugin has no rules for (L651), whose releases the user picks. */
    object Generic {
        /** Undrawn: the page's footnote. */
        const val FOOTNOTE =
            "NZStreamer can't tell this sport's releases apart for sure, so you pick one; the one you played goes first next time. Details from your Sportarr."

        /** Undrawn: in the buttons' place before the start, the game's line without Play (SpPhoneGame 3); on a TV it holds the focus. */
        const val NOT_STARTED = "Choose release and Search indexers show once it has started. Releases often appear a few hours after the end."
    }

    /**
     * What Choose release says of a part's releases (L647): the nouns that name what plays, why a release is a possible
     * match, and what was left out. An item's noun is "game", "session", a card part's label in lower case ("main card"),
     * "fight", "show" or "event"; an event's is "game", "session", "card", "fight", "show" or "event".
     */
    object Choose {
        /** SpPhoneChoose 3: a team game's noun, "Nothing names this game for sure yet". */
        const val GAME = "game"

        /** SpTvChoose 2: a motorsport session's noun, "if it's the right session". */
        const val SESSION = "session"

        /** Undrawn: a fight card's noun, for every part. */
        const val CARD = "card"

        /** Undrawn: a boxing matchup's noun. */
        const val FIGHT = "fight"

        /** Undrawn: a weekly show's noun. */
        const val SHOW = "show"

        /** Undrawn: the noun of a sport the plugin has no rules for. */
        const val EVENT = "event"

        /** SpPhoneChoose 2: both teams, no date, and they meet once in the list. */
        const val NO_DATE = "No date in the name"

        /** SpPhoneChoose 2: two upper-case codes in place of the teams' names. */
        const val TEAM_CODES = "Team codes only"

        /** Undrawn: one team, the date and the league. */
        const val ONE_TEAM = "Only one team named"

        /** Undrawn: a double header's name with no game number. */
        const val NO_GAME_NUMBER = "No game number"

        /** Undrawn: a week number where the game's round is unknown. */
        const val WEEK_NO_DATE = "A week number, no date"

        /** Undrawn: a cricket match's day. */
        const val ONE_DAY = "One day of the match"

        /** SpTvChoose 2: the series, the year and the session, a running number (2026x20) in place of the place. */
        const val RUNNING_NUMBER = "Only a running number, no place"

        /** Undrawn: the series, the year and the session, and neither the place nor the round. */
        const val NO_PLACE = "No place or round in the name"

        /** SpTvChoose 2: the session's word in another language than English. */
        const val NOT_IN_ENGLISH = "Session not named in English"

        /** Undrawn: an endurance race, which has no runtime to check a release by. */
        const val ENDURANCE = "Endurance race: you pick"

        /** Undrawn: a testing session. */
        const val TESTING = "Testing: you pick"

        /** Undrawn: a round with several races, and a name that says none's number. */
        const val NO_RACE_NUMBER = "No race number in the name"

        /** Undrawn: a card's main fight alone, not its main part. */
        const val MAIN_EVENT_ONLY = "Main event only"

        /** Undrawn: the league's word and the event's date, and nothing else of the event's. */
        const val BY_LEAGUE_AND_DATE = "Picked by league and date"

        /** Undrawn: most of the event's title and its year or date, posted after it started, for a sport with no rules. */
        const val BY_TITLE = "Picked by the title's words"

        /** Undrawn: a possible match too small for the part's runtime: "Small for a whole game". */
        fun small(noun: String): String = "Small for a whole $noun"

        /**
         * SpPhoneChoose 2: the list's last line, counting what was left out by kind and never naming one: "6 left out:
         * highlights, a name with a score, other games. Their names aren't shown." Undrawn: "and more" past three kinds.
         */
        fun leftOut(count: Int, kinds: List<String>, more: Boolean): String =
            "$count left out: ${kinds.joinToString(", ")}${if (more) " and more" else ""}. Their names aren't shown."

        /** SpPhoneChoose 2: another event's releases: "other games"; "other sessions" (SpTvChoose 2). Undrawn: the others. */
        fun otherEvents(noun: String): String = "other ${noun}s"

        /** Undrawn: another part of the same card. */
        const val OTHER_PARTS = "other parts"

        /** SpPhoneChoose 2: highlights, recaps and condensed games. */
        const val HIGHLIGHTS = "highlights"

        /** SpPhoneChoose 2: "a name with a score". Undrawn: "names with a score". */
        fun withScore(count: Int): String = if (count == 1) "a name with a score" else "names with a score"

        /** Undrawn: "a name with a result", "names with a result" (L646). */
        fun withResult(count: Int): String = if (count == 1) "a name with a result" else "names with a result"

        /** Undrawn: previews, press conferences, weigh-ins and other programmes about the event. */
        const val PREVIEWS_AND_SHOWS = "previews and shows"

        /** SpTvChoose 2: a motorsport programme before or after the race: "a pre-race show". Undrawn: "pre-race shows". */
        fun preRaceShow(count: Int): String = if (count == 1) "a pre-race show" else "pre-race shows"

        /** Undrawn: a half, quarter, period or innings alone: "parts of a game". */
        fun partsOf(noun: String): String = "parts of ${a(noun)} $noun"

        /** Undrawn: a matchday's, week's or season's pack. */
        const val PACKS = "packs"

        /** Undrawn: posted more than a day before the event started. */
        const val OLDER = "older releases"

        /** Undrawn: the play's failure where a release holds no file for the part: "No file for the Main Card in this release". */
        fun noFile(label: String): String = "No file for the $label in this release"
    }

    /** What NZStreamer's no-release sheet shows of the plugin's (SpPhoneNothing), and the page's line after it. */
    object NoRelease {
        /** SpPhoneNothing 1: an indexer whose releases were none of the item's: "6 results, none for this game". */
        fun noneFor(results: Int, noun: String): String = "${if (results == 1) "1 result" else "$results results"}, none for this $noun"

        /** SpPhoneNothing 1: an indexer that answered with nothing. */
        const val NO_RESULTS = "No results"

        /** SpPhoneNothing 1: the sheet's note: "Releases often appear a few hours after a game ends. Try Play again later." */
        fun note(noun: String): String = "${often(noun)} Try Play again later."

        /**
         * SpPhoneNothing 2: under the page's buttons once Play or Choose release found nothing, at [time]: "No release at 20:41.
         * Releases often appear a few hours after a game ends."
         */
        fun kept(time: String, noun: String): String = "No release at $time. ${often(noun)}"

        /**
         * Undrawn: under the page's buttons once Choose release found nothing because no indexer answered, each indexer's
         * name and the host's words for why ("No indexer answered. Indexer A: Paused: too many searches from Sportarr."),
         * as Search says it with none switched on.
         */
        fun unanswered(lines: List<Pair<String, String>>): String =
            if (lines.isEmpty()) NO_INDEXERS else "No indexer answered. ${lines.joinToString(" · ") { (indexer, words) -> "$indexer: $words" }}."

        /** Undrawn: Choose release with no indexer switched on, in Search's words. */
        const val NO_INDEXERS = "No indexers are switched on."

        private fun often(noun: String): String = "Releases often appear a few hours after ${a(noun)} $noun ends."
    }

    /** Search indexers from an event (L653). */
    object SearchWarning {
        /** SpPhoneSearchWarning 1: the note over the host's Search, and over the no-release sheet's Search. */
        const val SCORES = "Indexer results can show scores."
    }

    /**
     * The words the dates, times and lengths are made of, and the day and month names, in English whatever the device's
     * language, as the rest of NZStreamer writes them: [Times] puts them together.
     */
    object Dates {
        /** SpPhoneList 1: today's header, on both sides. */
        const val TODAY = "Today"

        /** SpPhoneList 1: yesterday's header on Recent. */
        const val YESTERDAY = "Yesterday"

        /** SpPhoneList 5: tomorrow's header on Upcoming. */
        const val TOMORROW = "Tomorrow"

        /** SpPhoneGame 3: when an event not started yet starts, "Starts Sat 15:00". */
        const val STARTS = "Starts"

        /** Undrawn: an event that starts later today, "Starts today 15:00". */
        const val STARTS_TODAY = "today"

        /** SpPhoneList 1: what is left of a play, "1:04:38 left" on a row, "1 h 5 min left" in Up next. */
        const val LEFT = "left"

        /** SpPhoneList 1: hours, in Up next's "1 h 5 min left" and the list's age, "3 h ago". */
        const val HOURS = "h"

        /** SpPhoneList 1: minutes, in Up next's "1 h 5 min left". */
        const val MINUTES = "min"

        /** SpPhoneListStates 2: the kept list's age, "list from 3 h ago". */
        const val AGO = "ago"

        /** Undrawn: a list read the day before, as Discover says its lists' age. */
        const val YESTERDAY_AGO = "yesterday"

        /** Undrawn: a list read days before, "4 days ago". */
        const val DAYS_AGO = "days ago"

        /** SpPhoneList 1: the days' names, Monday first as java.time numbers them: "Fri 20 Nov". */
        @JvmField val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        /** SpPhoneList 1: the months' names, January first: "Fri 20 Nov". */
        @JvmField val MONTH_NAMES = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    }

    /** The article before [noun]: "an event", "a game". */
    private fun a(noun: String): String = if (noun.firstOrNull()?.lowercaseChar() in VOWELS) "an" else "a"

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')
}
