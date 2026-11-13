package io.skjaere.usenet.debrid.live

import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.ServiceHttp
import io.skjaere.usenet.debrid.premiumize.PremiumizeService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import kotlin.test.assertNotNull

/**
 * Premiumize with your own account (L212, W77, item 39), the lead's alone: enabled only by `-PdebridLive=true`, which
 * has the build put the key from local.properties (devPremiumizeKey) in the test's environment. One call at a time, at the
 * client's pace: a handful of API calls and three reads from the CDN. The account's test; a check of three hashes holding
 * the openly licensed film's (`-PdebridLiveHash`); if it is cached, prepare it (directdl, which adds nothing to the
 * account), read 64 KB of its link from a megabyte in (again with If-Range when a strong ETag came), and ask for the link
 * again after a minute (directdl again); directdl of a random hash that is no torrent, for what an uncached one answers;
 * a key Premiumize doesn't know, for its code; whether the API and the CDN answer with TLS 1.2 forced. Every API answer's
 * shape goes to build/reports/debridLive/premiumize.txt, without a key, a link, a hash or the customer id. A key Premiumize
 * doesn't take fails the class once the report is written; the rest is the report's to say.
 */
@EnabledIfSystemProperty(named = "debridLive", matches = "true")
class PremiumizeLiveTest {
    private val run = LiveRun("Premiumize")

    @Test
    fun `premiumize answers as the client reads it`() = runBlocking<Unit> {
        val key = run.key
        assumeTrue(key != null, "devPremiumizeKey isn't set in local.properties")
        val http = run.recording(ServiceHttp.defaultHttpClient())
        val premiumize = PremiumizeService(key!!, http, log = run.lines::add)
        val account = try {
            run.timed("test", said = { "until ${if (it.planUntil != null) "given" else "none given"}" }) { premiumize.test() }?.also {
                val film = run.film
                if (film == null) run.note("no film's hash given (-PdebridLiveHash): nothing to prepare")
                val asked = listOfNotNull(film) + List(2) { run.noTorrent() }
                val cached = run.timed("check ${asked.size} hashes", said = { answer -> "${answer.count { it.value }} cached, the film ${if (film != null && answer[film] == true) "among them" else "not among them"}" }) {
                    premiumize.check(asked)
                }
                if (film != null && cached?.get(film) == true) {
                    play(premiumize, film)
                } else if (film != null) {
                    run.note("the film isn't cached: the not-cached path, nothing prepared")
                }
                run.timed("directdl of a hash that is no torrent", said = { "${it.listing.files.size} files (unexpected)" }) { premiumize.prepare(run.noTorrent(), run.largest) }
                run.timed("test with a key Premiumize doesn't know", said = { "taken (unexpected)" }) { PremiumizeService(NOT_A_KEY, http, log = run.lines::add).test() }
            }
        } finally {
            run.hostOf(PremiumizeService.BASE_URL)?.let(run::tls12)
            run.write()
            http.close()
        }
        assertNotNull(account, "Premiumize didn't take the key: build/reports/debridLive/premiumize.txt says how")
    }

    /** The film prepared and linked as a play does it, its link read and asked again. */
    private suspend fun play(premiumize: PremiumizeService, film: InfoHash) {
        val prepared = run.timed("prepare the film", said = { "${it.listing.files.size} files, one picked: ${it.picked != null}" }) { premiumize.prepare(film, run.largest) } ?: return
        val file = prepared.picked ?: return
        val link = run.timed("link (the one directdl gave)", said = { "made" }) { premiumize.link(prepared, file) } ?: return
        val read = run.cdn("a read of the link from a megabyte in", link.url)
        read?.etag?.takeUnless { it.startsWith("W/") }?.let { run.cdn("the same read with its ETag as If-Range", link.url, ifRange = it) }
        run.hostOf(link.url)?.let(run::tls12)
        run.askAgain(premiumize, prepared, file, link.url, read)
    }

    private companion object {
        const val NOT_A_KEY = "NZStreamerLiveCheckNotAKey0000"
    }
}
