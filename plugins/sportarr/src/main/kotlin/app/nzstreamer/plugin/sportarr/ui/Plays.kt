package app.nzstreamer.plugin.sportarr.ui

import app.nzstreamer.plugin.sportarr.family.Matchers
import app.nzstreamer.plugin.sportarr.family.searchDate
import app.nzstreamer.plugin.sportarr.match.FamilyMatcher
import app.nzstreamer.plugin.sportarr.match.Nouns
import app.nzstreamer.plugin.sportarr.match.PartPicker
import app.nzstreamer.plugin.sportarr.match.Runner
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.Item
import app.nzstreamer.plugin.sportarr.model.Part
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.PluginPlay
import io.skjaere.usenet.plugin.PluginSearch
import io.skjaere.usenet.plugin.PluginWatch
import io.skjaere.usenet.plugin.SearchOffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/** What Choose release came to. */
internal sealed interface Chosen {
    /** The host's list opened over the page. */
    data object Opened : Chosen

    /** The indexers answered, with nothing for the part: the page keeps its line. */
    data object NoneFound : Chosen

    /** No indexer answered: [line], each one's words for why, for the page to show while it does. */
    data class Unanswered(val line: String) : Chosen
}

/**
 * How the pages and Up next play a part through NZStreamer (L647, L652, L653), its searches the user's indexers through
 * [search], the host's:
 * - Play hands the part's [PlayItem] to the host's [play], which plays the release that played it before, else asks the
 *   runner for releases as it plays, and only then: nothing is searched before.
 * - Choose release runs the runner, then opens the host's list with what it accepted and offered and the line of what it
 *   left out; with nothing at all it opens nothing.
 * - Search indexers opens the host's Search with the event's words and the note that indexer results can show scores.
 * - Mark watched and unwatched go to the host's [watch], under the part's key with the title its play stores.
 *
 * When Play or Choose release finds nothing for a part, the time is kept ([nothing]) for the plugin's run, so the page
 * says when it last found nothing until a release of the part plays; not when no indexer answered (each failed, was skipped
 * or paused, or none is on), which says nothing of the releases. Each item is made on [work], off the main thread, as
 * its matcher reads the whole list; a play and a mark go on in [scope], the host's, as the page may close meanwhile;
 * [clock] says when nothing was found.
 */
internal class Plays(
    private val play: PluginPlay,
    private val watch: PluginWatch,
    search: PluginSearch,
    private val scope: CoroutineScope,
    private val clock: () -> Instant,
    private val work: CoroutineContext = Dispatchers.Default,
) {
    private val runner = Runner(search, work)
    private val found = MutableStateFlow<Map<String, Instant>>(emptyMap())

    /** When Play or Choose release last found nothing for a part, by the part's key. */
    val nothing: StateFlow<Map<String, Instant>> = found.asStateFlow()

    /**
     * Plays [part] of [event] of [sports]. The host asks for its releases only when no release played it before, or that
     * one fails; an answer of the indexers that leaves it nothing to play or pick (the runner's note for its no-release
     * sheet) keeps the page's line. With no indexer answering, the host's sheet says why, and the page keeps nothing.
     */
    fun play(sports: Sports, event: SportsEvent, part: Part): Job = scope.launch {
        val (item, matcher) = withContext(work) { prepared(sports, event, part) }
        play.play(item) {
            val run = runner.run(matcher, event, part)
            if (run.releases.note != null && run.answered) noted(part)
            run.releases
        }
    }

    /**
     * Choose release for [part] of [event] of [sports]: what the runner finds, in the host's list over the page. With
     * nothing accepted or offered it opens nothing: the page keeps its line where the indexers answered, and shows why
     * where none did.
     */
    suspend fun choose(sports: Sports, event: SportsEvent, part: Part, nav: PluginNav): Chosen {
        val (item, matcher) = withContext(work) { prepared(sports, event, part) }
        val run = runner.run(matcher, event, part)
        val answer = run.releases
        if (answer.accepted.isEmpty() && answer.offered.isEmpty()) {
            if (!run.answered) return Chosen.Unanswered(Words.NoRelease.unanswered(answer.lines.map { it.indexer to it.words }))
            noted(part)
            return Chosen.NoneFound
        }
        nav.openReleases(item, answer.accepted, answer.offered, answer.leftOut)
        return Chosen.Opened
    }

    /** Search indexers for [part] of [event]: the host's Search with the event's words and the scores note; it searches nothing itself. */
    suspend fun search(sports: Sports, event: SportsEvent, part: Part, nav: PluginNav) {
        val words = withContext(work) { searchWords(Matchers(sports).of(event), event, part) }
        nav.openSearch(words, Words.SearchWarning.SCORES)
    }

    /** Marks [part] of [event] [watched] or not, under its key with the title its play stores. */
    fun mark(sports: Sports, event: SportsEvent, part: Part, watched: Boolean): Job = scope.launch {
        watch.mark(listOf(part.key to title(sports, event, part)), watched)
    }

    private fun noted(part: Part) = found.update { it + (part.key to clock()) }

    /** The item and the matcher its releases are found by, made together: the item's words and picker come from the matcher. */
    private fun prepared(sports: Sports, event: SportsEvent, part: Part): Pair<PlayItem, FamilyMatcher> {
        val matcher = Matchers(sports).of(event)
        return item(matcher, sports, event, part) to matcher
    }

    companion object {
        /**
         * [part] of [event] of [sports] as the host plays and keeps it (L644, L647): its key forever; the title Up next and
         * the player name it by ([title]); the league under it; the part's runtime, which its searches rank by; the noun of
         * Choose release's lines; Search indexers' words with the scores note for the no-release sheet; and the part picker
         * where the event has several parts or a session may come in a weekend's release, else the host's own choice of
         * file, as for a film.
         */
        fun item(sports: Sports, event: SportsEvent, part: Part): PlayItem = item(Matchers(sports).of(event), sports, event, part)

        private fun item(matcher: FamilyMatcher, sports: Sports, event: SportsEvent, part: Part) = PlayItem(
            key = part.key,
            title = title(sports, event, part),
            subtitle = event.league.name,
            runtimeMinutes = part.runtimeMinutes,
            noun = Nouns.item(event, part),
            search = SearchOffer(searchWords(matcher, event, part), Words.SearchWarning.SCORES),
            picker = if (event.family == Family.Motorsport || event.parts.size > 1) PartPicker.of(matcher, event, part) else null,
        )

        /**
         * The title the part's play stores: a weekend's session after the weekend's name ("Grand Prix of Kestrel · Race"), a
         * card's part after the card's ("UFC 999: Quessel vs. Halde · Main Card"), else the event's own.
         */
        fun title(sports: Sports, event: SportsEvent, part: Part): String = ListScreens.title(sports.weekendOf(event) ?: event, Item(event, part))

        /**
         * Search indexers' words for [part] of [event]: its family's first search that names no date, as a user would type
         * it. A game's two sides ("Lantern Bay Copper Crown"), a session's series and place ("Formula 1 Kestrel"), a card's
         * organisation and number ("UFC 999") or its two surnames, a show's or Generic's title words. Search shows every
         * date's releases, so the words leave the date out; with no such search, the event's title.
         */
        fun searchWords(matcher: FamilyMatcher, event: SportsEvent, part: Part): String {
            val queries = matcher.queries(event, part)
            val date = searchDate(event)
            return (queries.first + listOfNotNull(queries.then)).map(String::trim).firstOrNull { it.isNotEmpty() && date !in it } ?: event.title
        }
    }
}
