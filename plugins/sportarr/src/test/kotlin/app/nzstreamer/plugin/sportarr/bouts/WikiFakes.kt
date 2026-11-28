package app.nzstreamer.plugin.sportarr.bouts

import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.source.ListRead
import app.nzstreamer.plugin.sportarr.source.ListReader
import app.nzstreamer.plugin.sportarr.source.LoopbackHttp
import app.nzstreamer.plugin.sportarr.source.SportarrClient
import app.nzstreamer.plugin.sportarr.source.loopbackSafety
import io.skjaere.usenet.mockseed.FakeSportarrServer
import io.skjaere.usenet.mockseed.FakeWikipediaServer
import io.skjaere.usenet.mockseed.SportsCatalog
import io.skjaere.usenet.plugin.PluginHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Semaphore
import kotlin.test.assertEquals

/**
 * Mock-seed's fake Wikipedia (L-B12) and the fake Sportarr whose control path holds its switches, in this JVM on ephemeral
 * loopback ports, dated from [anchor], for the tests that read fight cards against them: the catalogue's UFC events as a
 * refresh reads them at [noon], a session's [Cards] over a folder of a test's own, reading through [http] (the host's
 * rules, its own User-Agent), the switches set ([control]) and the requests the fake Wikipedia answered ([asked]).
 */
internal class WikiFakes(val anchor: LocalDate) : AutoCloseable {
    /** The tests' time: the anchor's noon, after UFC 999's start and before the fight night's. */
    val noon: Instant = anchor.atTime(12, 0).toInstant(ZoneOffset.UTC)
    val catalog: SportsCatalog = SportsCatalog.load()

    /** Released each time a request starts waiting out the fake's hold. */
    val holding = Semaphore(0)
    val wiki = FakeWikipediaServer(catalog, anchor, port = 0, onHold = { holding.release() })
    val sportarr = FakeSportarrServer(catalog, anchor, port = 0, wiki = wiki)
    val http = LoopbackHttp()

    /** The fake Wikipedia's address, as the plugin's base. */
    val base = "http://127.0.0.1:${wiki.port}"

    /** The catalogue's two UFC events, by id, as a refresh reads them from the fake Sportarr at [noon]. */
    val events: Map<Int, SportsEvent> by lazy {
        val client = SportarrClient(http, "http://127.0.0.1:${sportarr.port}", FakeSportarrServer.KEY, ::loopbackSafety)
        val list = (runBlocking { ListReader(client, noon, ZoneOffset.UTC).read() } as ListRead.Read).list
        Sports.of(list).events.filter { it.id == UFC_999 || it.id == FIGHT_NIGHT }.associateBy { it.id }
    }

    override fun close() {
        sportarr.close()
        wiki.close()
    }

    /** One of the fake Sportarr's control paths ("wiki-form?event=1901&form=pre", "reset"), which must answer 200. */
    fun control(path: String) {
        val connection = URI("http://127.0.0.1:${sportarr.port}/control/$path").toURL().openConnection() as HttpURLConnection
        try {
            assertEquals(200, connection.responseCode, path)
        } finally {
            connection.disconnect()
        }
    }

    /** A request the fake Wikipedia answered: its path as sent, its query, the form its title was answered in, its status. */
    data class Asked(val path: String, val query: String?, val form: String?, val status: Int?)

    /** The fake Wikipedia's requests since its last reset, in order. */
    fun asked(): List<Asked> = wiki.counts().getValue("requests").jsonArray.map { it.jsonObject }.map { request ->
        fun text(name: String) = request.getValue(name).takeUnless { it is JsonNull }?.jsonPrimitive?.content
        Asked(text("path")!!, text("query"), text("form"), text("status")?.toInt())
    }

    /**
     * A session's cards in [scope] (whose thread is the page's), over [folder]'s data and cache folders, reading from
     * [base] through [http], the time [clock]'s.
     */
    fun cards(scope: CoroutineScope, folder: File, clock: () -> Instant = { noon }, http: PluginHttp = this.http, base: String = this.base): Cards {
        val reads = WikipediaReads(http, base, clock, IndexFile(File(folder, "cache")))
        return Cards(scope, reads, KeptCards(File(folder, "data")), CardsOption(File(folder, "data")), clock)
    }

    companion object {
        /** The catalogue's UFC 999, the day before the anchor, and its fight night six days after it. */
        const val UFC_999 = 1901
        const val FIGHT_NIGHT = 1902
    }
}

/** What the page of [event] says once it has opened and its read has ended. */
internal suspend fun Cards.open(event: SportsEvent): CardState? {
    opened(event).join()
    return state(event).first()
}

/** Try again on [event]'s page, and what it says once that read has ended. */
internal suspend fun Cards.again(event: SportsEvent): CardState? {
    tryAgain(event).join()
    return state(event).first()
}
