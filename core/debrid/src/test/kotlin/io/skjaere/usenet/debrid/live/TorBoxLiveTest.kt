package io.skjaere.usenet.debrid.live

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.ServiceHttp
import io.skjaere.usenet.debrid.torbox.TorBoxService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.security.SecureRandom
import kotlin.test.assertNotNull

/**
 * TorBox with your own account (L212, W77, item 39), the lead's alone: enabled only by `-PdebridLive=true`, which has
 * the build put the key from local.properties (devTorBoxKey) in the test's environment. One call at a time, at the
 * client's pace: about a dozen API calls and three reads from the CDN. The account's test; a check of three hashes holding
 * the openly licensed film's (`-PdebridLiveHash`); if it is cached, prepare and link it, read 64 KB of it from a megabyte
 * in (again with If-Range when a strong ETag came), and ask for the link again after a minute; a key TorBox doesn't know,
 * for its code; mylist of a torrent id the account doesn't have; `add_only_if_cached` on a random hash that is no torrent,
 * with whether an entry appeared in the account's list (the app deletes nothing: the report says to delete it by hand, if one did);
 * whether the API and the CDN answer with TLS 1.2 forced. Every API answer's shape goes to
 * build/reports/debridLive/torbox.txt, without a key, a link, a hash or the account's e-mail. A key TorBox doesn't take
 * fails the class once the report is written; the rest is the report's to say.
 */
@EnabledIfSystemProperty(named = "debridLive", matches = "true")
class TorBoxLiveTest {
    private val run = LiveRun("TorBox")

    @Test
    fun `torbox answers as the client reads it`() = runBlocking<Unit> {
        val key = run.key
        assumeTrue(key != null, "devTorBoxKey isn't set in local.properties")
        val http = run.recording(ServiceHttp.defaultHttpClient())
        val torbox = TorBoxService(key!!, http, log = run.lines::add)
        val account = try {
            run.timed("test", said = { "plan ${it.plan ?: "none named"}, until ${if (it.planUntil != null) "given" else "none given"}" }) { torbox.test() }
                ?.also { theRest(torbox, http, key) }
        } finally {
            run.hostOf(TorBoxService.BASE_URL)?.let(run::tls12)
            run.write()
            http.close()
        }
        assertNotNull(account, "TorBox didn't take the key: build/reports/debridLive/torbox.txt says how")
    }

    /** What comes after the account's own test, which a refused key would leave moot. */
    private suspend fun theRest(torbox: TorBoxService, http: HttpClient, key: String) {
        val film = run.film
        if (film == null) run.note("no film's hash given (-PdebridLiveHash): nothing to prepare")
        val asked = listOfNotNull(film) + List(2) { run.noTorrent() }
        val cached = run.timed("check ${asked.size} hashes", said = { answer -> "${answer.count { it.value }} cached, the film ${if (film != null && answer[film] == true) "among them" else "not among them"}" }) {
            torbox.check(asked)
        }
        if (film != null && cached?.get(film) == true) {
            play(torbox, film)
        } else if (film != null) {
            run.note("the film isn't cached: the not-cached path, nothing prepared or added")
        }
        run.timed("test with a key TorBox doesn't know", said = { "taken (unexpected)" }) { TorBoxService(NOT_A_KEY, http, log = run.lines::add).test() }
        unknownTorrent(http, key)
        addOnlyIfCached(http, key)
    }

    /** The film prepared and linked as a play does it, its link read and asked again. */
    private suspend fun play(torbox: TorBoxService, film: InfoHash) {
        val prepared = run.timed("prepare the film", said = { "${it.listing.files.size} files, one picked: ${it.picked != null}, added: ${it.handle != null}" }) { torbox.prepare(film, run.largest) } ?: return
        val file = prepared.picked ?: return
        val link = run.timed("link", said = { "made" }) { torbox.link(prepared, file) } ?: return
        val read = run.cdn("a read of the link from a megabyte in", link.url)
        read?.etag?.takeUnless { it.startsWith("W/") }?.let { run.cdn("the same read with its ETag as If-Range", link.url, ifRange = it) }
        run.hostOf(link.url)?.let(run::tls12)
        run.askAgain(torbox, prepared, file, link.url, read)
    }

    /** mylist of a torrent id the account doesn't have: the client reads TorBox's answer as "not there". */
    private suspend fun unknownTorrent(http: HttpClient, key: String) {
        delay(TorBoxService.CALL_INTERVAL.toMillis())
        run.note("mylist of a torrent id the account doesn't have:")
        http.get("${TorBoxService.BASE_URL}/torrents/mylist?id=${100_000_000 + SecureRandom().nextInt(900_000_000)}&bypass_cache=true") { signed(key) }
    }

    /**
     * createtorrent with add_only_if_cached on a random hash that is no torrent (its answer's status and code in the
     * report), then whether the account's list gained an entry for it.
     */
    private suspend fun addOnlyIfCached(http: HttpClient, key: String) {
        val none = run.noTorrent()
        delay(TorBoxService.CALL_INTERVAL.toMillis())
        run.note("createtorrent with add_only_if_cached on a hash that is no torrent:")
        http.submitFormWithBinaryData(
            "${TorBoxService.BASE_URL}/torrents/createtorrent",
            formData {
                append("magnet", none.xtMagnet())
                append("add_only_if_cached", "true")
                append("seed", "3")
                append("allow_zip", "false")
                append("as_queued", "false")
            },
        ) { signed(key) }
        delay(TorBoxService.CALL_INTERVAL.toMillis())
        val list = http.get("${TorBoxService.BASE_URL}/torrents/mylist?bypass_cache=true") { signed(key) }.bodyAsText()
        run.note("an entry for it in the account's list: ${if (none.hex in list.lowercase()) "yes (delete it by hand: the app deletes nothing)" else "no"}")
    }

    /** As the client signs a call: the key as a bearer token, the app's user agent. */
    private fun HttpRequestBuilder.signed(key: String) {
        header(HttpHeaders.Authorization, "Bearer $key")
        header(HttpHeaders.UserAgent, ServiceHttp.USER_AGENT)
    }

    private companion object {
        const val NOT_A_KEY = "NZStreamerLiveCheckNotAKey0000"
    }
}
