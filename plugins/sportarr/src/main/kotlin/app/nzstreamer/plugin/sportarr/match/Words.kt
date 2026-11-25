package app.nzstreamer.plugin.sportarr.match

import app.nzstreamer.plugin.sportarr.model.Numbering
import app.nzstreamer.plugin.sportarr.model.Parts
import app.nzstreamer.plugin.sportarr.model.SessionKind
import app.nzstreamer.plugin.sportarr.model.Sessions

/** What a non-event word says a release is, which names the kind the shared rules leave it out as (L647). */
enum class NonEventKind {
    /** Highlights, recaps and condensed games: "highlights". */
    Cut,

    /** A motorsport programme before or after the race: "a pre-race show". */
    RaceShow,

    /** Any other programme about the event, or a cut no game is: "previews and shows". */
    Show,
}

/** A non-event word as its list names it ("pre-race", "highlights"), with its [kind]. */
data class NonEvent(val word: String, val kind: NonEventKind)

/**
 * A part word (L646) as its list names it ([word]), with the part id it names where it names one (L644). The others name
 * no one part: "Main Event Only" the main fight alone, "Full Event" every part in one release, "PPV" the part sold on its
 * own (a card's main card, a show's main show), "Post Show" a part no event of the plugin's has.
 */
enum class PartWord(val word: String, val id: String?) {
    EarlyPrelims("early prelims", Parts.EARLY_PRELIMS),
    Prelims("prelims", Parts.PRELIMS),
    MainCard("main card", Parts.MAIN_CARD),
    MainEventOnly("main event only", null),
    FullEvent("full event", null),
    Ppv("ppv", null),
    PreShow("pre-show", Parts.PRE_SHOW),
    MainShow("main show", Parts.MAIN_SHOW),
    PostShow("post show", null),
}

/** The words a number follows in a name (L646): "Round 20", "Week 10", "Game 1", "Night 2". */
enum class NumberWord { Round, Week, Matchday, Game, Leg, Race, Day, Part, Session, Night }

/**
 * A session word found in a name (L646): its [kind], the number it carries ("Practice 2", "FP2", "Race 2"), and whether
 * it is in another language than English ([foreign]: "Rennen", "Zeittraining").
 */
data class SessionWord(val kind: SessionKind, val number: Int?, val foreign: Boolean)

/** One entry of a word list: the folded [words] a name holds for it, in order; what they mean; and the number it takes. */
class Phrase<out T>(val words: List<String>, val value: T, val numbering: Numbering = Numbering.None)

/** A word list, its entries looked up by their first word, the longest first, so "sprint qualifying" wins over "sprint". */
class PhraseList<out T>(phrases: List<Phrase<T>>) {
    private val byFirst: Map<String, List<Phrase<T>>> =
        phrases.groupBy { it.words.first() }.mapValues { (_, entries) -> entries.sortedByDescending { it.words.size } }

    /** The entries whose first word is [word], the longest first. */
    fun startingWith(word: String): List<Phrase<T>> = byFirst[word].orEmpty()
}

/**
 * The words the sports name parser reads (L646): words that mark a release as something other than the event (a cut of it,
 * or a programme around it), parts of a game, packs, a card's or a show's parts, motorsport sessions ([Sessions]' list:
 * English, and the German, French, Dutch, Spanish and Italian ones, marked), and result words. Beside them: the tail's
 * quality words, the words a number follows, the side separators and the markers of another side of a club (a women's,
 * youth or reserve side, an age group).
 *
 * The cut, non-event, pack, part, game part, session and other-side lists were written for this parser from how sports are
 * organised, named and broadcast, by writers who read no other project's lists. The result words are the plugin's own,
 * written for it from how release names read.
 *
 * Each entry is written as a name's tokens fold it (lower case, no diacritics) and split where a name's words split, so
 * "Pre-Race", "Pre.Race" and "pre race" are the two words "pre race"; a glued form is its own entry ("prerace").
 */
object Words {
    // The constants first: the lists below are built from them as the object is made.

    /**
     * The tail's quality words (L646): where a name's identity ends. Sources, codecs, HDR and the audio words that carry no
     * channels; [isTailWord] adds the shapes ("1080p50", "60fps", "AAC2", "H.264").
     */
    private val TAIL: Set<String> = setOf(
        "4k", "uhd",
        "web", "webdl", "webrip", "hdtv", "pdtv", "sdtv", "dsr", "dvb", "satrip", "bluray", "bdrip", "brrip", "dvdrip", "hdrip",
        "h264", "h265", "x264", "x265", "hevc", "avc", "xvid", "av1", "vp9", "mpeg2",
        "hdr", "hdr10", "hdr10plus", "dovi", "hlg",
        "truehd", "atmos", "flac", "opus",
    )

    /** Audio words that may carry their channels glued on ("AAC2" of "AAC2.0", "DDP5" of "DDP5.1"). */
    private val AUDIO = listOf("eac3", "ac3", "aac", "ddp", "dd", "dts")

    /** What follows an "H" split from its codec at a dot ("H.264"). */
    private val CODEC_NUMBERS = setOf("264", "265")

    /** Standard definition, for the size floor's lower rate (L647). */
    private val STANDARD_DEFINITION = setOf("480p", "480i", "576p", "576i", "sd", "sdtv")

    /** The age groups a "U" names, written apart or glued to its age ("U 21", "U21"). */
    private val AGE_GROUPS = 15..23

    /** The ordinals a partial word may follow, in digits and spelled out ("1st quarter", "first quarter"). */
    private val ORDINALS = listOf("1st", "2nd", "3rd", "4th")
    private val SPELLED = listOf("first", "second", "third", "fourth")

    /**
     * The markers of another side of a club than its first team, each folded into the one marker it says: a women's side
     * ("Frauen"), a youth side ("Primavera"), a reserve or second team ("Second XI"), an age group spelled out ("Under 21").
     * "W" is the one single letter among them, as fixture and score listings write it after a club's name for its women's
     * side; another letter ("B") is none: names use such letters for much else.
     */
    private val SIBLINGS: List<Phrase<String>> =
        phrases(
            "women",
            "women", "womens", "ladies", "frauen", "feminin", "feminines", "femenino", "femenil", "femminile", "vrouwen",
            "w", "wfc", "feminine", "damen", "feminas", "femeni", "dames", "feminino",
        ) +
            phrases("youth", "youth", "primavera", "juvenil") +
            listOf(17, 18, 19, 20, 21, 23).flatMap { phrases("u$it", "under $it") } +
            phrases("reserves", "reserves", "reserve team", "b team", "second team", "2nd team", "second xi", "2nd xi")

    /**
     * Non-event words (L646): a cut of the event, or a programme around it. "Kickoff Show" and "Red Carpet" are ones only
     * outside a show: a show's are its pre-show ([showParts]).
     */
    val nonEvent: PhraseList<NonEvent> = PhraseList(nonEventEntries(inShow = false))

    /** Non-event words in a show's names, where "Kickoff Show" and "Red Carpet" name the pre-show part. */
    val nonEventInShows: PhraseList<NonEvent> = PhraseList(nonEventEntries(inShow = true))

    /**
     * Partial words (L646): a half, quarter, period, innings or inning of a game, its extra time or its penalty shootout,
     * never the game. A unit alone needs its number ("Quarter 3"): it keeps "Quarter Final" a game.
     */
    val partial: PhraseList<String> = PhraseList(
        numbered("half") + phrases("1st half", "1st half", "first half") + phrases("2nd half", "2nd half", "second half") +
            numbered("quarter") + ordinals("quarter", 4) +
            numbered("period") + ordinals("period", 3) +
            numbered("innings") + ordinals("innings", 2) +
            numbered("inning") +
            phrases("extra time", "extra time") +
            phrases("penalty shootout", "penalty shootout", "penalty shoot out"),
    )

    /**
     * Pack words (L646): several events in one release. A week or matchday with no sides is one too, which the parser reads.
     * A motorsport weekend's words are none of them: Motorsport takes a weekend's release for each of its sessions, the part
     * picker choosing the session's file, which a pack word, read first, would leave out.
     */
    val packs: PhraseList<String> = PhraseList(
        listOf(
            "complete season", "full season", "whole season", "entire season", "season pack", "seasonpack", "season complete",
            "full tournament", "complete tournament", "whole tournament", "entire tournament",
            "full competition", "complete competition",
            "full playoffs", "complete playoffs", "entire playoffs", "all playoff games",
            "full series", "complete series",
            "all games", "every game", "all races", "every race", "all fixtures", "all matchdays",
            "all shows", "all episodes", "every episode", "complete collection", "box set", "boxset", "bundle",
        ).flatMap { phrases(it, it) },
    )

    /** Part words (L646), outside a show: "Kickoff Show" and "Red Carpet" are programmes of their own there. */
    val parts: PhraseList<PartWord> = PhraseList(partEntries())

    /** Part words in a show's names, where "Kickoff Show" and "Red Carpet" are the pre-show. */
    val showParts: PhraseList<PartWord> =
        PhraseList(partEntries() + phrases(PartWord.PreShow, "kickoff show", "kick off show", "red carpet"))

    /**
     * Session words (L646): [Sessions]' list, each with its kind, those in another language than English marked foreign.
     * Beside them "Grand Prix" and "GP", the plugin's own, which name the race weekend, not one session of it ("Kestrel
     * Grand Prix Qualifying" is the qualifying): their kind is null.
     */
    val sessions: PhraseList<SessionEntry> = PhraseList(
        Sessions.entries.map { Phrase(it.words, SessionEntry(it.kind, it.foreign, it.number), it.numbering) } +
            listOf("grand prix", "gp").map { Phrase(it.split(' '), SessionEntry(null, foreign = false, number = null)) },
    )

    /** Club forms and words many clubs share (L648), which tell no side from another: "FC", "City", "United". */
    val clubWords: Set<String> = setOf(
        "fc", "afc", "cf", "sc", "ac", "cd", "fk", "club", "the", "city", "united", "town", "athletic", "rovers", "county",
    )

    /**
     * Club words names write that [clubWords] leaves out: "Utd" for United, "Football" of a club's name written out
     * ("Football Club" for FC). Beside a side's town they name no other club; a side whose own name holds one keeps it
     * among its distinctive words, as L648's list says nothing of them.
     */
    val clubSpellings: Set<String> = setOf("utd", "football")

    /** Result words (L646), as whole tokens: a name holding one is never offered (W167 a). */
    val results: Set<String> = setOf(
        "result", "results", "winner", "winners", "wins", "won", "beat", "beats", "defeats", "defeated", "victory", "retains", "ko", "tko",
    )

    /**
     * Sibling markers (L646), each folded into the one marker it says: a women's, youth or reserve side, or an age group
     * ("Under 21" and "U21" are "u21").
     */
    val siblings: PhraseList<String> = PhraseList(SIBLINGS + AGE_GROUPS.map { Phrase(listOf("u$it"), "u$it") })

    /** Every word of a sibling marker's, which is never a side's nickname ("Women", or "Team" of "Reserve Team"). */
    val siblingWords: Set<String> = SIBLINGS.flatMap { it.words }.toSet()

    /** The age groups a "U" names (U15 to U23). */
    val ageGroups: IntRange get() = AGE_GROUPS

    /** The words a number follows (L646), "R" and "Rd" for a round among them, and "Night" for a show's night (L650). */
    val numberWords: Map<String, NumberWord> = mapOf(
        "round" to NumberWord.Round, "rd" to NumberWord.Round, "r" to NumberWord.Round,
        "week" to NumberWord.Week,
        "matchday" to NumberWord.Matchday,
        "game" to NumberWord.Game, "leg" to NumberWord.Leg, "race" to NumberWord.Race, "day" to NumberWord.Day,
        "part" to NumberWord.Part, "session" to NumberWord.Session, "night" to NumberWord.Night,
    )

    /** What stands between a name's two sides (L646), in the order a name is split at them. */
    val sideSeparators: List<String> = listOf("vs", "v", "@", "at")

    /** Whether [word] begins a name's tail, [next] being the word after it. */
    fun isTailWord(word: String, next: String?): Boolean =
        word in TAIL || isResolution(word) || isFrameRate(word) || isAudio(word) || (word == "h" && next in CODEC_NUMBERS)

    /** Whether a tail word says standard definition: 480p, 576p, SD. */
    fun isStandardDefinition(word: String): Boolean =
        word in STANDARD_DEFINITION || (isResolution(word) && (word.startsWith("480") || word.startsWith("576")))

    /** "720p", "1080p", "1080p50", "1080i", "2160p": three or four digits, "p" or "i", perhaps a frame rate. */
    private fun isResolution(word: String): Boolean {
        val letter = word.indexOfFirst { it == 'p' || it == 'i' }
        return letter in 3..4 && word.take(letter).allDigits() && word.drop(letter + 1).let { it.isEmpty() || (it.length in 2..3 && it.allDigits()) }
    }

    /** "50fps", "60fps". */
    private fun isFrameRate(word: String): Boolean = word.endsWith("fps") && word.removeSuffix("fps").let { it.length in 2..3 && it.allDigits() }

    /** An audio word, perhaps with its channels' first digit glued on ("aac2", "ddp5"). */
    private fun isAudio(word: String): Boolean =
        AUDIO.any { word == it || (word.startsWith(it) && word.length - it.length in 1..2 && word.drop(it.length).allDigits()) }

    private fun String.allDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }

    private fun nonEventEntries(inShow: Boolean): List<Phrase<NonEvent>> {
        val cut = NonEventKind.Cut
        val show = NonEventKind.Show
        val raceShow = NonEventKind.RaceShow
        return listOfNotNull(
            // The event itself, shortened.
            phrases(NonEvent("highlights", cut), "highlights", "highlight"),
            phrases(NonEvent("condensed", cut), "condensed"),
            phrases(NonEvent("recap", cut), "recap", "recaps"),
            phrases(NonEvent("summary", cut), "summary"),
            phrases(NonEvent("key moments", cut), "key moments"),
            phrases(NonEvent("best bits", cut), "best bits"),
            phrases(NonEvent("all goals", cut), "all goals", "all the goals", "every goal"),
            phrases(NonEvent("all tries", cut), "all tries"),
            // Programmes around the event: these two only outside a show, whose pre-show they name.
            phrases(NonEvent("kickoff show", show), "kickoff show", "kick off show").takeUnless { inShow },
            phrases(NonEvent("red carpet", show), "red carpet").takeUnless { inShow },
            phrases(NonEvent("pre-game", show), "pre game", "pregame"),
            phrases(NonEvent("pre-match", show), "pre match", "prematch"),
            phrases(NonEvent("post-game", show), "post game", "postgame"),
            phrases(NonEvent("post-match", show), "post match", "postmatch"),
            phrases(NonEvent("post-fight", show), "post fight", "postfight"),
            // "Post Show" stays a part word, another part no event of the plugin's has: these words are read before the parts.
            phrases(NonEvent("after show", show), "after show", "aftershow"),
            phrases(NonEvent("halftime show", show), "halftime show", "half time show"),
            phrases(NonEvent("intermission", show), "intermission"),
            phrases(
                NonEvent("press conference", show),
                "press conference", "press conferences", "presser", "pressers", "press conf", "news conference", "news conferences",
            ),
            phrases(NonEvent("press event", show), "press event"),
            phrases(NonEvent("weigh-in", show), "weigh in", "weigh ins", "weighin", "weighins"),
            phrases(NonEvent("face-offs", show), "face offs", "faceoffs"),
            phrases(NonEvent("final face-off", show), "final face off", "final faceoff"),
            phrases(
                NonEvent("open workout", show),
                "open workout", "open workouts", "public workout", "public workouts", "media workout", "media workouts",
            ),
            phrases(NonEvent("media day", show), "media day", "media days"),
            phrases(NonEvent("media scrum", show), "media scrum", "media scrums"),
            phrases(NonEvent("meet and greet", show), "meet and greet"),
            phrases(NonEvent("interview", show), "interview", "interviews"),
            // "Q&A" as its tokens split it, and as a name may glue it.
            phrases(NonEvent("q and a", show), "q and a", "qanda"),
            phrases(NonEvent("preview", show), "preview", "previews"),
            phrases(NonEvent("review", show), "review", "reviews"),
            phrases(NonEvent("analysis", show), "analysis"),
            phrases(NonEvent("reaction", show), "reaction", "reactions"),
            phrases(NonEvent("predictions", show), "predictions", "prediction"),
            phrases(NonEvent("fight breakdown", show), "fight breakdown"),
            phrases(NonEvent("film breakdown", show), "film breakdown", "film room", "film study"),
            phrases(NonEvent("build-up", show), "build up", "buildup"),
            phrases(NonEvent("wrap-up", show), "wrap up", "wrapup"),
            phrases(NonEvent("debrief", show), "debrief"),
            phrases(NonEvent("countdown", show), "countdown"),
            phrases(NonEvent("studio show", show), "studio show"),
            phrases(NonEvent("talk show", show), "talk show", "talkshow", "chat show"),
            phrases(NonEvent("panel show", show), "panel show", "roundtable", "round table"),
            phrases(NonEvent("mailbag", show), "mailbag"),
            phrases(NonEvent("phone-in", show), "phone in", "phonein"),
            phrases(NonEvent("magazine", show), "magazine"),
            phrases(NonEvent("podcast", show), "podcast", "podcasts"),
            phrases(NonEvent("vlog", show), "vlog", "vlogs"),
            phrases(NonEvent("watch-along", show), "watch along", "watchalong"),
            phrases(NonEvent("watch party", show), "watch party", "watch parties"),
            phrases(NonEvent("documentary", show), "documentary", "documentaries"),
            phrases(NonEvent("docuseries", show), "docuseries", "docu series"),
            phrases(NonEvent("behind the scenes", show), "behind the scenes", "making of", "all access"),
            phrases(NonEvent("featurette", show), "featurette", "featurettes"),
            phrases(NonEvent("retrospective", show), "retrospective"),
            phrases(NonEvent("locker room", show), "locker room"),
            phrases(NonEvent("tunnel cam", show), "tunnel cam", "tunnelcam"),
            phrases(NonEvent("fan cam", show), "fan cam", "fancam"),
            phrases(NonEvent("micd up", show), "micd up", "miked up", "miced up"),
            phrases(NonEvent("training camp", show), "training camp"),
            phrases(NonEvent("sparring", show), "sparring"),
            phrases(NonEvent("bloopers", show), "bloopers", "outtakes"),
            phrases(NonEvent("bonus features", show), "bonus features", "special features"),
            phrases(NonEvent("trailer", show), "trailer", "trailers"),
            phrases(NonEvent("teaser", show), "teaser", "teasers"),
            phrases(NonEvent("promo", show), "promo", "promos"),
            phrases(NonEvent("hype video", show), "hype video", "hype videos"),
            phrases(NonEvent("compilation", show), "compilation", "compilations"),
            phrases(NonEvent("top plays", show), "top plays"),
            phrases(NonEvent("top moments", show), "top moments"),
            phrases(NonEvent("draw ceremony", show), "draw ceremony"),
            phrases(NonEvent("victory parade", show), "victory parade"),
            phrases(NonEvent("season launch", show), "season launch"),
            phrases(NonEvent("car launch", show), "car launch"),
            phrases(NonEvent("livery reveal", show), "livery reveal"),
            phrases(NonEvent("kit launch", show), "kit launch", "kit reveal"),
            // A race's own programmes, read before the session words: a race's build-up is never the race.
            phrases(NonEvent("pre-race", raceShow), "pre race", "prerace"),
            phrases(NonEvent("post-race", raceShow), "post race", "postrace"),
            phrases(NonEvent("post-qualifying", raceShow), "post qualifying", "post quali"),
            phrases(NonEvent("pre-sprint", raceShow), "pre sprint"),
            phrases(NonEvent("post-sprint", raceShow), "post sprint"),
            phrases(NonEvent("race build-up", raceShow), "race build up", "race buildup"),
            phrases(NonEvent("qualifying build-up", raceShow), "qualifying build up", "qualifying buildup", "quali build up"),
            phrases(NonEvent("sprint build-up", raceShow), "sprint build up", "sprint buildup"),
            phrases(NonEvent("race preview", raceShow), "race preview"),
            phrases(NonEvent("qualifying preview", raceShow), "qualifying preview"),
            phrases(NonEvent("race reaction", raceShow), "race reaction"),
            phrases(NonEvent("qualifying reaction", raceShow), "qualifying reaction"),
            phrases(NonEvent("sprint reaction", raceShow), "sprint reaction"),
            phrases(NonEvent("race analysis", raceShow), "race analysis"),
            phrases(NonEvent("qualifying analysis", raceShow), "qualifying analysis"),
            phrases(NonEvent("race review", raceShow), "race review"),
            phrases(NonEvent("grid walk", raceShow), "grid walk", "gridwalk"),
            phrases(NonEvent("drivers parade", raceShow), "drivers parade"),
        ).flatten()
    }

    /**
     * A card's or a show's part words, and the pre-show's. "Main Event" alone is none: it is also a wrestling programme's
     * name. "Post Fight Show" and "Post Fight Press Conference" are read as programmes first ("post fight" and "press
     * conference" among the non-event words), which leaves such a name out all the same. "Full PPV" stays the PPV part:
     * the pay-per-view broadcast in full is still the part sold on its own, never the whole event.
     */
    private fun partEntries(): List<Phrase<PartWord>> =
        phrases(PartWord.EarlyPrelims, "early prelims", "early prelim", "earlyprelims", "early preliminary card", "early preliminaries") +
            phrases(PartWord.Prelims, "prelims", "prelim", "preliminary card", "preliminaries", "undercard", "under card", "prelim card") +
            phrases(PartWord.MainCard, "main card", "maincard") +
            phrases(PartWord.MainEventOnly, "main event only") +
            phrases(
                PartWord.FullEvent,
                "full event", "full card", "full show",
                "full fight card", "complete event", "complete card", "complete show", "entire event", "entire card", "entire show",
                "whole event", "whole card", "whole show",
            ) +
            phrases(PartWord.Ppv, "ppv", "pay per view", "payperview") +
            phrases(PartWord.MainShow, "main show") +
            phrases(PartWord.PostShow, "post show", "postshow", "post fight show", "post fight press conference") +
            phrases(PartWord.PreShow, "pre show", "preshow", "pre fight show", "prefight show")

    /** A unit that needs its number after it ("Half 2"), read as "<unit> <n>". */
    private fun numbered(word: String): List<Phrase<String>> = listOf(Phrase(listOf(word), word, Numbering.Required))

    /** "1st quarter" and "first quarter" to "<last>th quarter", each read as "quarter <n>". */
    private fun ordinals(word: String, last: Int): List<Phrase<String>> =
        (1..last).flatMap { n -> listOf(ORDINALS[n - 1], SPELLED[n - 1]).map { ordinal -> Phrase(listOf(ordinal, word), "$word $n") } }

    private fun <T> phrases(value: T, vararg forms: String): List<Phrase<T>> = forms.map { Phrase(it.split(' '), value) }
}

/**
 * A session list's entry: its [kind] (null for the words that name the race weekend), whether it is [foreign], and the
 * number it carries itself ("FP2" is practice 2).
 */
data class SessionEntry(val kind: SessionKind?, val foreign: Boolean, val number: Int?)
