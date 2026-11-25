package app.nzstreamer.plugin.sportarr.ui

import app.nzstreamer.plugin.sportarr.model.Count
import app.nzstreamer.plugin.sportarr.source.Failure
import app.nzstreamer.plugin.sportarr.source.TestAnswer
import app.nzstreamer.plugin.sportarr.source.TestSportarr
import io.skjaere.usenet.mockseed.FakeSportarrServer
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plugin's words (L652, L660): every word a design draws is in [Words] once, in its screen's group, and its declaration
 * names that design; every other word there is marked Undrawn. Straight apostrophes throughout, as the host's strings have
 * them, and nothing Sportarr sends besides an event's title, league, round and times (its canary values, L653).
 *
 * The comparison with the designs themselves is a review's, as they aren't in the repository; the words below are theirs.
 */
class WordsTest {
    @Test
    fun `every drawn word is in Words once, in its screen's group`() {
        val fields = constants()
        val wrong = CONSTANTS.mapNotNull { shown ->
            val holding = fields.filter { it.value == shown.text }
            when {
                holding.size != 1 -> "\"${shown.text}\" is held ${holding.size} times: ${holding.map { it.key }}"
                holding.single().group != shown.group -> "\"${shown.text}\" is ${holding.single().key}, not in ${shown.group}"
                else -> null
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `every word Words holds is drawn or marked undrawn here, none left over`() {
        val listed = CONSTANTS.map { it.text }.toSet()
        val unlisted = constants().filter { it.value !in listed }.map { "${it.key} = \"${it.value}\"" }
        assertTrue(unlisted.isEmpty(), "not in this test's lists: $unlisted")
        val templates = TEMPLATES.map { "${it.group}.${it.name}" }.toSet()
        val untested = functions().filter { it !in templates }
        assertTrue(untested.isEmpty(), "functions this test never calls: $untested")
    }

    @Test
    fun `each template gives the drawn words`() {
        val wrong = TEMPLATES.filter { it.actual != it.expected }.map { "${it.group}.${it.name}: \"${it.actual}\", not \"${it.expected}\"" }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `the left-out lines the designs draw come from the kinds' own words`() {
        assertEquals(
            "6 left out: highlights, a name with a score, other games. Their names aren't shown.",
            Words.Choose.leftOut(6, listOf(Words.Choose.HIGHLIGHTS, Words.Choose.withScore(1), Words.Choose.otherEvents(Words.Choose.GAME)), more = false),
        )
        assertEquals(
            "5 left out: other sessions, a pre-race show, highlights. Their names aren't shown.",
            Words.Choose.leftOut(5, listOf(Words.Choose.otherEvents(Words.Choose.SESSION), Words.Choose.preRaceShow(1), Words.Choose.HIGHLIGHTS), more = false),
        )
    }

    @Test
    fun `each declaration names the design that draws it, or says it is undrawn`() {
        val comments = comments(source("Words.kt"))
        val fields = constants()
        // A word held more than once, or nowhere, is the first test's to name; here, the one with its words.
        val rows = CONSTANTS.map { shown -> (fields.singleOrNull { it.value == shown.text }?.key ?: "${shown.group}.\"${shown.text}\"") to shown.design } +
            TEMPLATES.map { "${it.group}.${it.name}" to it.design }
        val wrong = rows.distinct().mapNotNull { (key, design) ->
            val comment = comments[key] ?: return@mapNotNull "$key has no comment"
            val mark = design ?: UNDRAWN
            if (mark in comment) null else "$key's comment doesn't say $mark: $comment"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `straight apostrophes only, in every word and in the files`() {
        val curly = listOf('\u2019', '\u2018')
        val words = constants().map { it.value } + TEMPLATES.map { it.actual }
        assertTrue(words.none { word -> curly.any { it in word } }, words.filter { word -> curly.any { it in word } }.toString())
        listOf("Words.kt", "Times.kt").forEach { name ->
            val text = source(name)
            assertTrue(curly.none { it in text }, "$name holds a curly apostrophe")
        }
    }

    @Test
    fun `no word holds a canary value`() {
        val canaries = TestSportarr.CANARY_VALUES + FakeSportarrServer.CANARY
        val words = constants().map { it.value } + TEMPLATES.map { it.actual }
        val leaked = words.filter { word -> canaries.any { word.contains(it, ignoreCase = true) } }
        assertTrue(leaked.isEmpty(), "canary values: $leaked")
    }

    /** A word drawn in [design] (null: undrawn), held in [group]. */
    private class Shown(val group: String, val design: String?, val text: String)

    /** A template's words for some values: [actual], which should be [expected]. */
    private class Template(val group: String, val name: String, val design: String?, val actual: String, val expected: String)

    /** A constant of Words: "Group.NAME" and its words. */
    private class Field(val group: String, val name: String, val value: String) {
        val key get() = "$group.$name"
    }

    private fun groups(): List<Class<*>> = listOf(Words::class.java) + Words::class.java.declaredClasses

    private fun constants(): List<Field> = groups().flatMap { group ->
        group.declaredFields.filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && it.type == String::class.java }
            .map { Field(group.simpleName, it.name, it.get(null) as String) }
    }

    private fun functions(): List<String> = groups().flatMap { group ->
        group.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }.map { "${group.simpleName}.${it.name}" }
    }.distinct()

    private fun source(name: String): String {
        val file = File(SOURCES, name)
        assertTrue(file.isFile, "${file.absolutePath} isn't there: the test runs in the plugin's folder")
        return file.readText()
    }

    /**
     * Each declaration's comment in Words' [text], by "Group.name": the comment right above a const or fun, in the group
     * whose object it stands in ("Words" for the file's own).
     */
    private fun comments(text: String): Map<String, String> {
        val lines = text.lines()
        val found = mutableMapOf<String, String>()
        var group = "Words"
        lines.forEachIndexed { i, line ->
            OBJECT.find(line)?.let { group = it.groupValues[1] }
            val declared = DECLARATION.find(line) ?: return@forEachIndexed
            val owner = if (declared.groupValues[1].length == 4) "Words" else group
            val above = lines.subList(0, i).takeLastWhile { it.trim().let { l -> l.startsWith("/**") || l.startsWith("*") } }
            found["$owner.${declared.groupValues[2]}"] = above.joinToString(" ") { it.trim() }
        }
        return found
    }

    private companion object {
        const val UNDRAWN = "Undrawn"
        val SOURCES = File("src/main/kotlin/app/nzstreamer/plugin/sportarr/ui")
        val OBJECT = Regex("""^ {4}object (\w+)""")
        val DECLARATION = Regex("""^( {4}| {8})(?:const val|fun) (\w+)""")

        const val SETUP = "Setup"
        const val LIST = "SportsList"
        const val STATES = "ListStates"
        const val GAME = "Game"
        const val WEEKEND = "Weekend"
        const val CARD = "Card"
        const val GENERIC = "Generic"
        const val CHOOSE = "Choose"
        const val NOTHING = "NoRelease"
        const val SEARCH = "SearchWarning"

        val CONSTANTS = listOf(
            Shown(SETUP, "SpPhoneSetup", "Sports"),
            Shown(SETUP, "SpPhoneSetup", "Sportarr settings"),
            Shown(SETUP, "SpPhoneSetup", "Sports lists what you follow in your Sportarr: the leagues you switched on there, with your teams and the sessions you chose."),
            Shown(SETUP, "SpPhoneSetup", "Sportarr address"),
            Shown(SETUP, "SpPhoneSetup", "http://sportarr.home:1867"),
            Shown(SETUP, "SpPhoneSetup", "Where you open Sportarr in a browser, with its port."),
            Shown(SETUP, "SpPhoneSetup", "Plain http on your local network."),
            Shown(SETUP, "SpPhoneSetup", "⚠ Not encrypted: your API key would be readable on its way over the internet. Use https:// if your Sportarr has it."),
            Shown(SETUP, "SpPhoneSetup", "API key"),
            Shown(SETUP, "SpPhoneSetup", "In Sportarr: Settings › General › Security. Stored encrypted on this device."),
            Shown(SETUP, "SpPhoneSetup", "Show"),
            Shown(SETUP, null, "Hide"),
            Shown(SETUP, "SpPhoneSetup", "Saved"),
            Shown(SETUP, "SpPhoneSetup", "Change"),
            Shown(SETUP, "SpPhoneSetup", "Paste the new key"),
            Shown(SETUP, "SpPhoneSetup", "Test"),
            Shown(SETUP, "SpPhoneSetup", "Testing…"),
            Shown(SETUP, "SpPhoneSetup", "Save"),
            Shown(SETUP, "SpPhoneSetup", "Sports only reads from Sportarr. It never changes, searches or downloads anything there. To change what shows here, change it in Sportarr."),
            Shown(SETUP, "SpPhoneSetup", "Sportarr refused this API key. Copy it again from Settings › General › Security in Sportarr."),
            Shown(SETUP, "SpPhoneSetup", "This address answers, but it isn't Sportarr."),
            Shown(SETUP, null, "Sports can't read this address. Type it as you open Sportarr in a browser, starting with http:// or https://."),

            Shown(LIST, "SpPhoneList", "Recent"),
            Shown(LIST, "SpPhoneList", "Upcoming"),
            Shown(LIST, "SpPhoneList", "More"),
            Shown(LIST, "SpPhoneList", "Show watched"),
            Shown(LIST, null, "Hide watched"),
            Shown(LIST, "SpPhoneList", "Refresh"),
            Shown(LIST, "SpPhoneList", "All"),
            Shown(LIST, "SpPhoneList", "All leagues"),
            Shown(LIST, "SpPhoneList", "Up next"),
            Shown(LIST, "SpPhoneList", "Show fewer"),
            Shown(LIST, "SpPhoneList", "You pick the release"),
            Shown(LIST, "SpPhoneList", "✓"),

            Shown(STATES, "SpPhoneListStates", "Try again"),
            Shown(STATES, null, "Can't reach Sportarr"),
            Shown(STATES, null, "Sportarr refused the API key"),
            Shown(STATES, "SpPhoneListStates", "Your Sportarr follows nothing yet"),
            Shown(STATES, "SpPhoneListStates", "Sports lists the leagues you switched on in Sportarr, with the teams and sessions you chose there. Switch one on in Sportarr, then Refresh."),
            Shown(STATES, "SpPhoneListStates", "Events with no league in Sportarr don't show here: give them one there."),
            Shown(STATES, "SpPhoneListStates", "Nothing aired in the last 60 days"),
            Shown(STATES, null, "Nothing in the next 31 days"),
            Shown(STATES, "SpPhoneListStates", "Everything here is watched"),
            Shown(STATES, "SpPhoneListStates", "Nothing matches these filters"),
            Shown(STATES, "SpPhoneListStates", "Clear filters"),
            Shown(STATES, "SpPhoneListStates", "Sports keeps the newest 5,000 events: older ones aren't shown."),

            Shown(GAME, "SpPhoneGame", "Not watched"),
            Shown(GAME, "SpPhoneGame", "✓ Watched"),
            Shown(GAME, "SpPhoneGame", "Play"),
            Shown(GAME, "SpPhoneGame", "Resume"),
            Shown(GAME, "SpPhoneGame", "Play again"),
            Shown(GAME, "SpPhoneGame", "Choose release"),
            Shown(GAME, "SpPhoneGame", "Search indexers"),
            Shown(GAME, "SpPhoneGame", "Mark watched"),
            Shown(GAME, "SpPhoneGame", "Mark unwatched"),
            Shown(GAME, "SpPhoneGame", "Play, Choose release and Search indexers show once it has started. Releases often appear a few hours after the end."),
            Shown(GAME, "SpPhoneGame", "Play finds the best release on your indexers that names both teams and the date; the one that played before goes first. Details from your Sportarr."),
            Shown(GAME, null, "Searching your indexers…"),
            Shown(GAME, null, "This event is no longer in your Sportarr's list."),
            Shown(GAME, null, "Back"),

            Shown(WEEKEND, "SpPhoneWeekend", "Race"),
            Shown(WEEKEND, "SpPhoneWeekend", "Tap a session to play the best release, or ⋮ to choose one yourself. Details from your Sportarr."),
            Shown(WEEKEND, "SpTvWeekend", "Press OK on a session to play the best release, or ⋮ to choose one yourself."),

            Shown(CARD, "SpPhoneCard", "Early Prelims"),
            Shown(CARD, "SpPhoneCard", "Prelims"),
            Shown(CARD, "SpPhoneCard", "Main Card"),
            Shown(CARD, null, "Pre-Show"),
            Shown(CARD, null, "Main Show"),
            Shown(CARD, "SpPhoneCard", "The parts play once the card has started. Releases often appear a few hours after the end."),
            Shown(CARD, "SpPhoneCard", "Tap a part to play the best release, or ⋮ to choose one yourself. Details from your Sportarr."),
            Shown(CARD, null, "Press OK on a part to play the best release, or ⋮ to choose one yourself."),

            Shown(GENERIC, null, "NZStreamer can't tell this sport's releases apart for sure, so you pick one; the one you played goes first next time. Details from your Sportarr."),

            Shown(CHOOSE, "SpPhoneChoose", "game"),
            Shown(CHOOSE, "SpTvChoose", "session"),
            Shown(CHOOSE, null, "card"),
            Shown(CHOOSE, null, "fight"),
            Shown(CHOOSE, null, "show"),
            Shown(CHOOSE, null, "event"),
            Shown(CHOOSE, "SpPhoneChoose", "No date in the name"),
            Shown(CHOOSE, "SpPhoneChoose", "Team codes only"),
            Shown(CHOOSE, null, "Only one team named"),
            Shown(CHOOSE, null, "No game number"),
            Shown(CHOOSE, null, "A week number, no date"),
            Shown(CHOOSE, null, "One day of the match"),
            Shown(CHOOSE, "SpTvChoose", "Only a running number, no place"),
            Shown(CHOOSE, null, "No place or round in the name"),
            Shown(CHOOSE, "SpTvChoose", "Session not named in English"),
            Shown(CHOOSE, null, "Endurance race: you pick"),
            Shown(CHOOSE, null, "Testing: you pick"),
            Shown(CHOOSE, null, "No race number in the name"),
            Shown(CHOOSE, null, "Main event only"),
            Shown(CHOOSE, null, "Picked by league and date"),
            Shown(CHOOSE, null, "Picked by the title's words"),
            Shown(CHOOSE, null, "other parts"),
            Shown(CHOOSE, "SpPhoneChoose", "highlights"),
            Shown(CHOOSE, null, "previews and shows"),
            Shown(CHOOSE, null, "packs"),
            Shown(CHOOSE, null, "older releases"),

            Shown(NOTHING, "SpPhoneNothing", "No results"),

            Shown(SEARCH, "SpPhoneSearchWarning", "Indexer results can show scores."),
        )

        val TEMPLATES = listOf(
            Template("Words", "line", "SpPhoneList", Words.line("Premier League", null, "1 h 5 min left"), "Premier League · 1 h 5 min left"),

            Template(SETUP, "connected", "SpPhoneSetup", Words.Setup.connected("4.1.12"), "Connected · Sportarr 4.1.12"),
            Template(
                SETUP, "unreachable", "SpPhoneSetup", Words.Setup.unreachable("sportarr.home:1867", tv = false),
                "Couldn't reach sportarr.home:1867. Check that Sportarr is running and that this phone is on its network.",
            ),
            Template(
                SETUP, "unreachable", "SpPhoneSetup", Words.Setup.unreachable("sportarr.home:1867", tv = true),
                "Couldn't reach sportarr.home:1867. Check that Sportarr is running and that this TV is on its network.",
            ),
            Template(
                SETUP, "tooOld", "SpPhoneSetup", Words.Setup.tooOld("4.0.998"),
                "This Sportarr is 4.0.998. Sports needs 4.1.1 or newer: update Sportarr, then Test again.",
            ),
            Template(
                SETUP, "tooOld", "SpPhoneSetup", Words.Setup.tooOld(null),
                "This Sportarr sends no version. Sports needs 4.1.1 or newer: update Sportarr, then Test again.",
            ),
            Template(SETUP, "serverError", null, Words.Setup.serverError(500), "Sportarr answered with an error (500). Try again in a moment."),
            Template(SETUP, "answer", "SpPhoneSetup", Words.Setup.answer(TestAnswer.Connected("4.1.12"), tv = false), "Connected · Sportarr 4.1.12"),
            Template(
                SETUP, "answer", "SpPhoneSetup", Words.Setup.answer(TestAnswer.Unreachable("sportarr.home:1867"), tv = true),
                "Couldn't reach sportarr.home:1867. Check that Sportarr is running and that this TV is on its network.",
            ),
            Template(SETUP, "answer", "SpPhoneSetup", Words.Setup.answer(TestAnswer.Refused, tv = false), Words.Setup.REFUSED),
            Template(SETUP, "answer", "SpPhoneSetup", Words.Setup.answer(TestAnswer.TooOld("4.0.998"), tv = false), Words.Setup.tooOld("4.0.998")),
            Template(SETUP, "answer", "SpPhoneSetup", Words.Setup.answer(TestAnswer.NotSportarr, tv = false), Words.Setup.NOT_SPORTARR),
            Template(SETUP, "answer", "SpPhoneSetup", Words.Setup.answer(TestAnswer.ServerError(503), tv = false), Words.Setup.serverError(503)),
            Template(SETUP, "answer", "SpPhoneSetup", Words.Setup.answer(TestAnswer.InvalidAddress, tv = false), Words.Setup.INVALID_ADDRESS),

            Template(LIST, "play", "SpPhoneList", Words.SportsList.play("Race"), "Play Race"),
            Template(LIST, "watched", "SpPhoneList", Words.SportsList.watched(Count(3, 5)), "3 of 5 watched"),
            Template(LIST, "round", "SpPhoneList", Words.SportsList.round(20), "Round 20"),
            Template(LIST, "sessions", "SpPhoneList", Words.SportsList.sessions(2), "2 sessions"),
            Template(LIST, "sessions", "SpPhoneList", Words.SportsList.sessions(1), "1 session"),
            Template(LIST, "first", "SpPhoneList", Words.SportsList.first("Practice 1"), "Practice 1 first"),
            Template(LIST, "more", "SpPhoneList", Words.SportsList.more(3), "+3 more"),

            Template(STATES, "cantReach", "SpPhoneListStates", Words.ListStates.cantReach("3 h ago"), "Can't reach Sportarr · list from 3 h ago"),
            Template(
                STATES, "cantReachWhy", null, Words.ListStates.cantReachWhy(Failure.NoAnswer, tv = false),
                "Sportarr didn't answer. Check that Sportarr is running and that this phone is on its network.",
            ),
            Template(
                STATES, "cantReachWhy", null, Words.ListStates.cantReachWhy(Failure.NoAnswer, tv = true),
                "Sportarr didn't answer. Check that Sportarr is running and that this TV is on its network.",
            ),
            Template(STATES, "cantReachWhy", null, Words.ListStates.cantReachWhy(Failure.ErrorStatus, tv = false), "Sportarr answered with an error. Try again in a moment."),
            Template(
                STATES, "cantReachWhy", null, Words.ListStates.cantReachWhy(Failure.Unreadable, tv = false),
                "This address answers, but not as Sportarr does. Test it in Sportarr settings.",
            ),
            Template(STATES, "cantReachWhy", null, Words.ListStates.cantReachWhy(Failure.TooLarge, tv = false), "Sportarr sent more than Sports can read at once."),
            Template(STATES, "refused", null, Words.ListStates.refused("3 h ago"), "Sportarr refused the API key · list from 3 h ago"),
            Template(
                STATES, "nothingRecent", "SpPhoneListStates", Words.ListStates.nothingRecent("23 Sep", 6),
                "Your Sportarr's leagues have nothing between 23 Sep and today. Upcoming has 6 events.",
            ),
            Template(
                STATES, "nothingRecent", "SpPhoneListStates", Words.ListStates.nothingRecent("23 Sep", 1),
                "Your Sportarr's leagues have nothing between 23 Sep and today. Upcoming has 1 event.",
            ),
            Template(
                STATES, "nothingRecent", "SpPhoneListStates", Words.ListStates.nothingRecent("23 Sep", 0),
                "Your Sportarr's leagues have nothing between 23 Sep and today. Upcoming has nothing either.",
            ),

            Template(WEEKEND, "moreFor", "SpPhoneWeekend", Words.Weekend.moreFor("Practice 3"), "More for Practice 3"),

            Template(CHOOSE, "small", null, Words.Choose.small("game"), "Small for a whole game"),
            Template(
                CHOOSE, "leftOut", "SpPhoneChoose", Words.Choose.leftOut(6, listOf("highlights", "a name with a score", "other games"), more = false),
                "6 left out: highlights, a name with a score, other games. Their names aren't shown.",
            ),
            Template(
                CHOOSE, "leftOut", "SpPhoneChoose", Words.Choose.leftOut(9, listOf("other cards", "other parts", "packs"), more = true),
                "9 left out: other cards, other parts, packs and more. Their names aren't shown.",
            ),
            Template(CHOOSE, "otherEvents", "SpPhoneChoose", Words.Choose.otherEvents("game"), "other games"),
            Template(CHOOSE, "otherEvents", "SpPhoneChoose", Words.Choose.otherEvents("session"), "other sessions"),
            Template(CHOOSE, "withScore", "SpPhoneChoose", Words.Choose.withScore(1), "a name with a score"),
            Template(CHOOSE, "withScore", "SpPhoneChoose", Words.Choose.withScore(2), "names with a score"),
            Template(CHOOSE, "withResult", null, Words.Choose.withResult(1), "a name with a result"),
            Template(CHOOSE, "withResult", null, Words.Choose.withResult(3), "names with a result"),
            Template(CHOOSE, "preRaceShow", "SpTvChoose", Words.Choose.preRaceShow(1), "a pre-race show"),
            Template(CHOOSE, "preRaceShow", "SpTvChoose", Words.Choose.preRaceShow(2), "pre-race shows"),
            Template(CHOOSE, "partsOf", null, Words.Choose.partsOf("game"), "parts of a game"),
            Template(CHOOSE, "partsOf", null, Words.Choose.partsOf("event"), "parts of an event"),
            Template(CHOOSE, "noFile", null, Words.Choose.noFile("Main Card"), "No file for the Main Card in this release"),

            Template(NOTHING, "noneFor", "SpPhoneNothing", Words.NoRelease.noneFor(6, "game"), "6 results, none for this game"),
            Template(NOTHING, "noneFor", "SpPhoneNothing", Words.NoRelease.noneFor(1, "game"), "1 result, none for this game"),
            Template(NOTHING, "note", "SpPhoneNothing", Words.NoRelease.note("game"), "Releases often appear a few hours after a game ends. Try Play again later."),
            Template(NOTHING, "note", "SpPhoneNothing", Words.NoRelease.note("event"), "Releases often appear a few hours after an event ends. Try Play again later."),
            Template(
                NOTHING, "kept", "SpPhoneNothing", Words.NoRelease.kept("20:41", "game"),
                "No release at 20:41. Releases often appear a few hours after a game ends.",
            ),
            Template(
                NOTHING, "kept", "SpPhoneNothing", Words.NoRelease.kept("20:41", "session"),
                "No release at 20:41. Releases often appear a few hours after a session ends.",
            ),
        )
    }
}
