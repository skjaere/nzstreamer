package io.skjaere.usenet.debrid

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.skjaere.usenet.debrid.premiumize.PremiumizeService
import io.skjaere.usenet.debrid.torbox.TorBoxService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The clients' log lines (L208): one a call, naming the service and the call, and what it came to by count or code;
 * never the torrent's hash, a release's or file's name, the account's name, a key, a link or a service's own words. Over
 * invented answers that plant a marker in each of those, every call made, those that succeed and those that fail.
 */
class DebridLogLinesTest {
    private val clock = MutableClock()
    private val lines = mutableListOf<String>()

    /** TorBox's and Premiumize's fixtures with a marker in place of each thing a line mustn't hold. */
    private fun fixture(service: String, name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$service/$name")) { "missing fixture $name" }.readText()
            .replace("d3adb33fd3adb33fd3adb33fd3adb33fd3adb33f", HASH.hex)
            .replace("The.Glass.Orchard", "Quiet.Orbit.$RELEASE")
            .replace("quay.viewer@example.org", ACCOUNT)
            .replace("900000042", CUSTOMER)
            .replace("LINKSECRET", LINK)

    private fun MockRequestHandleScope.torbox(request: HttpRequestData, refusing: Boolean): HttpResponseData = when (request.url.encodedPath.removePrefix("/v1/api/")) {
        "user/me" -> json(fixture("torbox", "user-me.json"))
        "torrents/checkcached" -> json(fixture("torbox", "checkcached-files.json"))
        "torrents/createtorrent" -> json(fixture("torbox", "createtorrent.json"))
        "torrents/mylist" -> json(fixture("torbox", "mylist.json"))
        "torrents/requestdl" -> if (refusing) {
            json("""{"success":false,"error":"BAD_TOKEN","detail":"$DETAIL for $ACCOUNT","data":null}""", HttpStatusCode.Forbidden)
        } else {
            json(fixture("torbox", "requestdl.json"))
        }
        else -> json("""{"success":false,"error":"ENDPOINT_NOT_FOUND","detail":"$DETAIL"}""", HttpStatusCode.NotFound)
    }

    private fun MockRequestHandleScope.premiumize(request: HttpRequestData, refusing: Boolean): HttpResponseData = when (request.url.encodedPath.removePrefix("/api/")) {
        "account/info" -> json(fixture("premiumize", "account-info.json"))
        "cache/check" -> json("""{"status":"success","response":[true,false],"transcoded":[false,false],"filename":["$RELEASE",""],"filesize":["1",""]}""")
        "transfer/directdl" -> if (refusing) {
            json("""{"status":"error","code":"permission_denied","message":"$DETAIL for $ACCOUNT"}""")
        } else {
            json(fixture("premiumize", "directdl.json"))
        }
        else -> json("""{"status":"error","code":"invalid_request","message":"$DETAIL"}""")
    }

    private fun assertNothingPlanted() {
        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            assertTrue(line.startsWith("debrid: TorBox ") || line.startsWith("debrid: Premiumize "), line)
            listOf(HASH.hex, RELEASE, "Quiet.Orbit", ".mkv", ACCOUNT, CUSTOMER, KEY, LINK, "cdn-", "https:", DETAIL).forEach { planted ->
                assertFalse(planted in line, "\"$planted\" in: $line")
            }
        }
    }

    @Test
    fun `torbox's lines name the service and the call, never the torrent, a name, the account, the key or a link`() = runTest {
        var refusing = false
        val api = FakeApi(this) { request -> torbox(request, refusing) }
        val torbox = TorBoxService(KEY, api.http, limit = ServiceRateLimit(Duration.ofMillis(250)) { testScheduler.currentTime }, gate = ServiceGate(clock), clock = clock, log = lines::add)
        torbox.test()
        torbox.check(listOf(HASH, OTHER))
        val prepared = torbox.prepare(HASH) { listing -> listing.files.first { it.path.endsWith("S02E02.1080p.WEB-DL.H.264-KESTREL.mkv") } }
        torbox.link(prepared, prepared.picked!!)
        refusing = true
        assertFailsWith<ServiceException> { torbox.link(prepared, prepared.picked!!) }
        assertTrue(lines.any { "requestdl" in it && "BAD_TOKEN" in it }, "$lines")
        assertNothingPlanted()
    }

    @Test
    fun `premiumize's lines name the service and the call, never the torrent, a name, the account, the key or a link`() = runTest {
        var refusing = false
        val api = FakeApi(this) { request -> premiumize(request, refusing) }
        val premiumize = PremiumizeService(KEY, api.http, limit = ServiceRateLimit(Duration.ofMillis(500)) { testScheduler.currentTime }, gate = ServiceGate(clock), clock = clock, log = lines::add)
        premiumize.test()
        premiumize.check(listOf(HASH, OTHER))
        val prepared = premiumize.prepare(HASH) { listing -> listing.files.first { it.path.endsWith("S02E02.1080p.WEB-DL.H.264-KESTREL.mkv") } }
        premiumize.link(prepared, prepared.picked!!)
        premiumize.link(prepared, prepared.picked!!)
        refusing = true
        assertFailsWith<ServiceException> { premiumize.link(prepared, prepared.picked!!) }
        assertTrue(lines.any { "directdl" in it && "permission_denied" in it }, "$lines")
        assertNothingPlanted()
    }

    private companion object {
        val HASH = InfoHash.of("c0ffee11c0ffee22c0ffee33c0ffee44c0ffee55")
        val OTHER = InfoHash.of("0ddba11000000000000000000000000000000001")
        const val RELEASE = "MARKERRELEASE"
        const val ACCOUNT = "marker.viewer@example.org"
        const val CUSTOMER = "777000111"
        const val KEY = "MARKERKEY0123"
        const val LINK = "MARKERLINK"
        const val DETAIL = "MARKERDETAIL"
    }
}
