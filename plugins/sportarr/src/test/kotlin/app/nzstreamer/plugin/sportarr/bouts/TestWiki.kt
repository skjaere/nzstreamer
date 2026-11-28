package app.nzstreamer.plugin.sportarr.bouts

import app.nzstreamer.plugin.sportarr.bouts.Fixtures.article
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.dateCell
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.model.UFC
import app.nzstreamer.plugin.sportarr.model.at
import app.nzstreamer.plugin.sportarr.model.on
import app.nzstreamer.plugin.sportarr.model.sports
import app.nzstreamer.plugin.sportarr.source.RecordingHttp
import app.nzstreamer.plugin.sportarr.source.answer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/** A loopback base the recording HTTP answers for; nothing listens there. */
const val WIKI_BASE = "http://127.0.0.1:1868"

/** The kept cards' tests' time: Sunday 22 November 2026 at noon, UTC. */
val WIKI_START: Instant = Instant.parse("2026-11-22T12:00:00Z")

/** A UFC event of the test catalogue, as the model builds it. */
fun ufcEvent(id: Int, title: String, start: Instant): SportsEvent = sports(UFC to listOf(on(UFC, id, start, title))).events.single()

/** UFC 999, the day before the tests' time: after its start. */
val UFC_999_EVENT: SportsEvent = ufcEvent(31, "UFC 999: Quessel vs. Halde", at("2026-11-21T23:00"))

/** A fight night in six days: before its start. */
val FIGHT_NIGHT_EVENT: SportsEvent = ufcEvent(32, "UFC Fight Night: Brisk vs. Loam", at("2026-11-28T01:00"))

/** The fight night's article: its canonical title, its date, and the fixture card's table. */
fun fightNightPage(form: Form = Form.Pre): String =
    article(form = form, canonical = "https://en.wikipedia.org/wiki/UFC_Fight_Night:_Brisk_vs._Loam", date = dateCell("November 28, 2026", "2026-11-28"))

/**
 * A wiki the test changes as it goes: its [pages] by path (any other a 404), every request answered [status] instead
 * where set, each answer after [latency] ms of the test's time.
 */
class TestWiki {
    val pages = HashMap<String, String>(mapOf("/wiki/UFC_999" to article(), "/wiki/UFC_Fight_Night:_Brisk_vs._Loam" to fightNightPage()))
    var status: Int? = null
    var latency = 0L

    val http = RecordingHttp { request ->
        if (latency > 0) delay(latency)
        val path = request.url.removePrefix(WIKI_BASE)
        status?.let { answer(it) } ?: pages[path]?.let { answer(200, it) } ?: answer(404)
    }

    /** The paths asked, in order. */
    val asked: List<String> get() = http.requests.map { it.url.removePrefix(WIKI_BASE) }
}

/** A dispatcher that runs nothing until the test says: what it is given waits in [queue]. Single-threaded tests only. */
class ManualDispatcher : CoroutineDispatcher() {
    val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue += block
    }

    /** Runs what waits, and what that gives it, until nothing does. */
    fun runAll() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }
}
