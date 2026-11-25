package app.nzstreamer.plugin.sportarr.match

import app.nzstreamer.plugin.sportarr.model.Part
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import io.skjaere.usenet.plugin.Found
import io.skjaere.usenet.plugin.IndexerLine
import io.skjaere.usenet.plugin.PluginSearch
import io.skjaere.usenet.plugin.ReleaseHandle
import io.skjaere.usenet.plugin.ReleaseInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A family for the shared rules', the runner's and the picker's tests, until the real ones: it reads a name against the
 * event's title, league and sides, searches the queries it is given, keeps every name it is asked to judge, and gives each
 * the verdict [verdict] says (Accept by default). [related] are the listed events a date in a name could also name.
 */
class TestFamily(
    private val first: List<String> = listOf("Lantern Bay Copper Crown", "Premier League 2026 11 21"),
    private val then: String? = "Copper Lantern",
    private val related: (SportsEvent) -> List<SportsEvent> = { emptyList() },
    private val verdict: (SportsName) -> Verdict = { Verdict.Accept() },
) : FamilyMatcher {
    /** The names its own rules were asked about, in order: the shared rules left the rest out first. */
    val judged: MutableList<String> = CopyOnWriteArrayList()

    override fun context(event: SportsEvent): NameContext =
        NameContext(title = event.title, league = event.league.name, sides = listOfNotNull(event.homeTeamName, event.awayTeamName))

    override fun queries(event: SportsEvent, part: Part): Queries = Queries(first, then)

    override fun related(event: SportsEvent): List<SportsEvent> = related.invoke(event)

    override fun judge(event: SportsEvent, part: Part, name: SportsName, posted: Instant?, size: Long?): Verdict {
        judged += name.name
        return verdict(name)
    }
}

/** A handle the tests can tell apart: the release's name and which answer gave it. */
class TestHandle(private val label: String) : ReleaseHandle {
    override fun toString(): String = label
}

/** A release as the host gives it: [handle] says which answer it came from. */
fun release(
    name: String,
    handle: String = name,
    indexer: String = "Indexer A",
    size: Long = 6_000_000_000,
    posted: Instant? = null,
): ReleaseInfo = ReleaseInfo(name, size, posted, indexer, playable = true, whyNot = null, handle = TestHandle(handle))

/** An indexer's line as the host words it once it answered with [found] releases. */
fun answered(indexer: String, found: Int): IndexerLine = IndexerLine(indexer, IndexerLine.State.Found, found, "$found found")

/**
 * The host's search for the tests: each query answered with what [answer] gives for its words, after a first answer that
 * says every indexer is still searching, as the host's flow begins. It keeps what it was asked, with the runtime, and
 * counts the searches running at once.
 */
class CountingSearch(private val answer: suspend (words: String) -> Found) : PluginSearch {
    data class Asked(val words: String, val runtimeMinutes: Int?)

    val asked: MutableList<Asked> = CopyOnWriteArrayList()
    private val running = AtomicInteger()

    /** The most searches that ran at once. */
    @Volatile
    var mostAtOnce = 0
        private set

    /** The searches running now. */
    val runningNow: Int get() = running.get()

    override fun search(words: String, runtimeMinutes: Int?, fresh: Boolean): Flow<Found> = flow {
        asked += Asked(words, runtimeMinutes)
        val now = running.incrementAndGet()
        mostAtOnce = maxOf(mostAtOnce, now)
        try {
            emit(Found(listOf(IndexerLine("Indexer A", IndexerLine.State.Searching, 0, "Searching…")), emptyList()))
            emit(answer(words))
        } finally {
            running.decrementAndGet()
        }
    }
}
