package io.skjaere.usenet.debrid.premiumize

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.skjaere.usenet.debrid.FakeApi
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.MutableClock
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.debrid.ServiceError
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceGate
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.debrid.ServiceListing
import io.skjaere.usenet.debrid.ServiceRateLimit
import io.skjaere.usenet.debrid.json
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Premiumize's client (L198) against invented fixtures shaped from Premiumize's documented answers:
 * Bearer and never `apikey=`, the forms it posts, its errors arriving with HTTP 200, and every documented code's
 * bucket. Never a request to Premiumize.
 */
class PremiumizeServiceTest {
    private val clock = MutableClock()
    private val gate = ServiceGate(clock)
    private val lines = mutableListOf<String>()
    private val pack = InfoHash.of("d3adb33fd3adb33fd3adb33fd3adb33fd3adb33f")
    private val episode2 = "The.Glass.Orchard.S02.1080p.WEB-DL.H.264-KESTREL/The.Glass.Orchard.S02E02.1080p.WEB-DL.H.264-KESTREL.mkv"

    private fun TestScope.premiumize(api: FakeApi) = PremiumizeService(
        KEY, api.http, limit = ServiceRateLimit(Duration.ofMillis(500)) { testScheduler.currentTime }, gate = gate, clock = clock, log = lines::add,
    )

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/premiumize/$name")) { "missing fixture $name" }.readText()

    private fun HttpRequestData.call() = url.encodedPath.removePrefix("/api/")

    private fun MockRequestHandleScope.premiumize(request: HttpRequestData): HttpResponseData = when (request.call()) {
        "account/info" -> json(fixture("account-info.json"))
        "transfer/directdl" -> json(fixture("directdl.json"))
        "cache/check" -> json(cacheAnswer(request.form().filter { it.first == "items[]" }.map { true }))
        else -> json(error("invalid_request"))
    }

    private fun cacheAnswer(cached: List<Boolean>) =
        """{"status":"success","response":${cached.joinToString(",", "[", "]")},"transcoded":${cached.map { false }},""" +
            """"filename":${cached.map { "\"x\"" }},"filesize":${cached.map { "\"1\"" }}}"""

    private fun error(code: String?, message: String = "Something went wrong.") =
        """{"status":"error","message":"$message"${code?.let { ""","code":"$it"""" } ?: ""}}"""

    private fun HttpRequestData.form(): List<Pair<String, String>> = (body as FormDataContent).formData.entries().flatMap { (name, values) -> values.map { name to it } }

    private val pickEpisode2: suspend (ServiceListing) -> ServiceFile? = { listing -> listing.files.single { it.path == episode2 } }

    private fun HttpRequestData.assertBearerAlone() {
        assertEquals("Bearer $KEY", headers[HttpHeaders.Authorization])
        assertFalse("apikey" in url.parameters.names(), "never apikey= in the query (L198)")
        if (body is FormDataContent) assertFalse(form().any { it.first == "apikey" }, "nor in the form")
    }

    @Test
    fun `test signs in with the bearer key and names the account by its customer number, with its plan's end`() = runTest {
        val api = FakeApi(this) { premiumize(it) }
        val account = premiumize(api).test()
        assertEquals("customer 900000042", account.name)
        assertNull(account.plan)
        assertEquals(Instant.parse("2027-01-12T00:00:00Z"), account.planUntil)

        val request = api.requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("https://www.premiumize.me/api/account/info", request.url.toString())
        request.assertBearerAlone()
        assertEquals(ServiceKind.Premiumize, premiumize(api).kind)
    }

    @Test
    fun `an account answer with its number as text, and without a plan's end, reads`() = runTest {
        val api = FakeApi(this) { json("""{"status":"success","customer_id":"900000042","premium_until":false}""") }
        val account = premiumize(api).test()
        assertEquals("customer 900000042", account.name)
        assertNull(account.planUntil)
    }

    @Test
    fun `check posts the xt-only magnets as items, at most 100 a call, and reads the parallel answer`() = runTest {
        val hashes = (0 until 250).map { InfoHash.of("%040x".format(it + 1)) }
        val cached = hashes.filterIndexed { i, _ -> i % 4 == 0 }.map { it.xtMagnet() }.toSet()
        val api = FakeApi(this) { request -> json(cacheAnswer(request.form().map { it.second in cached })) }
        val answer = premiumize(api).check(hashes)

        assertEquals(listOf(100, 100, 50), api.requests.map { it.form().size })
        api.requests.forEach { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("https://www.premiumize.me/api/cache/check", request.url.toString())
            assertEquals(ContentType.Application.FormUrlEncoded, request.body.contentType?.withoutParameters())
            assertTrue(request.form().all { it.first == "items[]" })
            request.assertBearerAlone()
        }
        assertEquals(hashes.map { "magnet:?xt=urn:btih:${it.hex}" }, api.requests.flatMap { r -> r.form().map { it.second } })
        assertEquals(hashes.toSet(), answer.keys)
        assertEquals(cached, answer.filterValues { it }.keys.map { it.xtMagnet() }.toSet())
        assertContains(lines.first(), "debrid: Premiumize cache/check 100 hashes: 25 cached")
    }

    @Test
    fun `an answer that isn't parallel to the items asked is the service's fault`() = runTest {
        val api = FakeApi(this) { json(cacheAnswer(listOf(true))) }
        assertEquals(ServiceError.Down, assertFailsWith<ServiceException> { premiumize(api).check(listOf(pack, InfoHash.of("ab".repeat(20)))) }.error)
    }

    @Test
    fun `prepare asks directdl with the xt-only magnet, lists its content and adds nothing`() = runTest {
        val api = FakeApi(this) { premiumize(it) }
        var listed: ServiceListing? = null
        val prepared = premiumize(api).prepare(pack) { listing -> listed = listing; listing.files[2] }

        val request = api.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://www.premiumize.me/api/transfer/directdl", request.url.toString())
        assertEquals(listOf("src" to "magnet:?xt=urn:btih:${pack.hex}"), request.form())
        request.assertBearerAlone()

        assertEquals(
            listOf(
                ServiceFile(0, "The.Glass.Orchard.S02.1080p.WEB-DL.H.264-KESTREL/Sample/sample.mkv", 31457280),
                ServiceFile(1, "The.Glass.Orchard.S02.1080p.WEB-DL.H.264-KESTREL/The.Glass.Orchard.S02E01.1080p.WEB-DL.H.264-KESTREL.mkv", 1468006400),
                ServiceFile(2, episode2, 1468006401),
                ServiceFile(3, "The.Glass.Orchard.S02.1080p.WEB-DL.H.264-KESTREL/The.Glass.Orchard.S02E03.1080p.WEB-DL.H.264-KESTREL.mkv", 1468006402),
            ),
            listed?.files,
        )
        assertEquals(listed, prepared.listing)
        assertEquals(ServiceFile(2, episode2, 1468006401), prepared.picked)
        assertNull(prepared.handle, "nothing in the account")
        assertEquals(pack, prepared.hash)
    }

    @Test
    fun `the links stay in memory in the prepared release, never in its stored form`() = runTest {
        val api = FakeApi(this) { premiumize(it) }
        val prepared = premiumize(api).prepare(pack, pickEpisode2)
        val stored = Json.encodeToString(Prepared.serializer(), prepared)
        assertFalse("LINKSECRET" in stored || "cdn-12" in stored || "ZIPSECRET" in stored, stored)
        assertFalse("LINKSECRET" in prepared.toString())
        // Read back, it is the same release, its links gone.
        assertEquals(prepared, Json.decodeFromString(Prepared.serializer(), stored))
        lines.forEach { assertFalse("LINKSECRET" in it || KEY in it || pack.hex in it, it) }
    }

    @Test
    fun `the first link is the listing's, without a call, and a fresh one asks directdl again for the file by path and size`() = runTest {
        val api = FakeApi(this) { premiumize(it) }
        val service = premiumize(api)
        val prepared = service.prepare(pack, pickEpisode2)
        val madeAt = clock.instant
        clock.instant = clock.instant.plus(Duration.ofMinutes(5))

        val first = service.link(prepared, prepared.picked!!)
        assertEquals("https://cdn-12.example.net/dl/a3/The.Glass.Orchard.S02E02.mkv?sig=LINKSECRET02", first.url)
        assertEquals(madeAt, first.madeAt, "made when the listing came")
        assertEquals(1, api.requests.size)

        val fresh = service.link(prepared, prepared.picked!!)
        assertEquals(2, api.requests.size)
        assertEquals(listOf("src" to "magnet:?xt=urn:btih:${pack.hex}"), api.requests.last().form())
        assertEquals("https://cdn-12.example.net/dl/a3/The.Glass.Orchard.S02E02.mkv?sig=LINKSECRET02", fresh.url)
        assertEquals(clock.instant, fresh.madeAt)
        assertFalse("LINKSECRET" in fresh.toString())

        // Read back from the store after the process was gone: every link is asked afresh.
        val stored = Json.decodeFromString(Prepared.serializer(), Json.encodeToString(Prepared.serializer(), prepared))
        service.link(stored, stored.listing.files[3])
        assertEquals(3, api.requests.size)
    }

    @Test
    fun `a file the service no longer lists by its path and size is not cached`() = runTest {
        var calls = 0
        val api = FakeApi(this) { request ->
            if (calls++ == 0) premiumize(request) else json(fixture("directdl.json").replace("\"size\": 1468006401", "\"size\": 1468006409"))
        }
        val service = premiumize(api)
        val prepared = service.prepare(pack, pickEpisode2)
        service.link(prepared, prepared.picked!!)
        assertEquals(ServiceError.NotCached, assertFailsWith<ServiceException> { service.link(prepared, prepared.picked!!) }.error)
    }

    @Test
    fun `nothing is picked, nothing to link`() = runTest {
        val api = FakeApi(this) { premiumize(it) }
        val prepared = premiumize(api).prepare(pack) { null }
        assertNull(prepared.picked)
        assertEquals(1, api.requests.size)
    }

    @Test
    fun `an uncached torrent is not cached, as an empty content or as not_found`() = runTest {
        val empty = FakeApi(this) { json("""{"status":"success","content":[]}""") }
        assertEquals(ServiceError.NotCached, assertFailsWith<ServiceException> { premiumize(empty).prepare(pack, pickEpisode2) }.error)
        val missing = FakeApi(this) { json(error("not_found")) }
        assertEquals(ServiceError.NotCached, assertFailsWith<ServiceException> { premiumize(missing).prepare(pack, pickEpisode2) }.error)
        assertNull(gate.closed())
    }

    private suspend fun TestScope.failureOf(code: String?, at: String, status: HttpStatusCode = HttpStatusCode.OK, vararg headers: Pair<String, String>): ServiceError {
        val api = FakeApi(this) { request -> if (request.call() == at) json(error(code), status, *headers) else premiumize(request) }
        val service = premiumize(api)
        return assertFailsWith<ServiceException> {
            when (at) {
                "account/info" -> service.test()
                "cache/check" -> service.check(listOf(pack))
                else -> service.prepare(pack, pickEpisode2)
            }
        }.error
    }

    @Test
    fun `every code the API page lists is its bucket, with HTTP 200`() = runTest {
        val buckets = mapOf(
            "authentication_failed" to ServiceError.KeyRefused,
            "permission_denied" to ServiceError.Refused(),
            "not_found" to ServiceError.NotCached,
            "account_limit_reached" to ServiceError.Limit(),
            "service_limit_reached" to ServiceError.Limit(),
            "service_unsupported" to ServiceError.Refused(),
            "invalid_request" to ServiceError.Refused(),
            "permanent_error" to ServiceError.Refused(),
            "service_down" to ServiceError.Down,
            "transient_error" to ServiceError.Down,
            "semi_permanent_error" to ServiceError.Down,
            "link_generation_failed" to ServiceError.Down,
            "unknown_error" to ServiceError.Down,
        )
        buckets.forEach { (code, bucket) ->
            gate.reset()
            assertEquals(bucket, failureOf(code, "transfer/directdl"), code)
        }
    }

    @Test
    fun `permission denied is never a refused key, on a test or a play, and leaves the gate open`() = runTest {
        assertEquals(ServiceError.Refused(), failureOf("permission_denied", "account/info"))
        assertNull(gate.closed())
        assertEquals(ServiceError.Refused(), failureOf("permission_denied", "transfer/directdl"))
        assertNull(gate.closed())
        assertEquals(ServiceError.KeyRefused, failureOf("authentication_failed", "account/info"))
        assertEquals(ServiceError.KeyRefused, gate.closed())
    }

    @Test
    fun `the rate limit holds the account's calls 60 s, then is its limit`() = runTest {
        var first = true
        val api = FakeApi(this) { request ->
            if (request.call() == "cache/check" && first) { first = false; json(error("rate_limit_reached")) } else premiumize(request)
        }
        val service = premiumize(api)
        assertEquals(ServiceError.Limit(Duration.ofSeconds(60)), assertFailsWith<ServiceException> { service.check(listOf(pack)) }.error)
        assertEquals(ServiceError.Limit(Duration.ofSeconds(60)), gate.closed())
        // The account's next call (a key's test, which no closed gate holds) waits out the limiter's 60 s.
        service.test()
        assertEquals(listOf(0L, 60_000L), api.times.toList())
    }

    @Test
    fun `a limit with a wait is closed for that wait`() = runTest {
        assertEquals(ServiceError.Limit(Duration.ofMinutes(20)), failureOf("account_limit_reached", "cache/check", HttpStatusCode.OK, "Retry-After" to "1200"))
    }

    @Test
    fun `an unknown code is down on a test or a check and refuses the release on a play, its code logged and never its message`() = runTest {
        assertEquals(ServiceError.Down, failureOf("brand_new_code", "account/info"))
        assertEquals(ServiceError.Down, failureOf("brand_new_code", "cache/check"))
        assertEquals(ServiceError.Refused(), failureOf("brand_new_code", "transfer/directdl"))
        assertContains(lines.last(), "debrid: Premiumize directdl: brand_new_code")
        assertEquals(ServiceError.Refused(), failureOf(null, "transfer/directdl"), "an error without a code")
        lines.forEach { assertFalse("Something went wrong" in it, it) }
    }

    @Test
    fun `faults of its own, a refused status and network errors are their buckets`() = runTest {
        assertEquals(ServiceError.Down, failureOf(null, "cache/check", HttpStatusCode.ServiceUnavailable))
        assertEquals(ServiceError.KeyRefused, failureOf(null, "cache/check", HttpStatusCode.Unauthorized))
        gate.reset()
        val api = FakeApi(this) { throw IOException("connection reset") }
        assertEquals(ServiceError.Down, assertFailsWith<ServiceException> { premiumize(api).prepare(pack, pickEpisode2) }.error)
        val html = FakeApi(this) { respond("<html>maintenance</html>", HttpStatusCode.OK) }
        assertEquals(ServiceError.Down, assertFailsWith<ServiceException> { premiumize(html).test() }.error)
    }

    @Test
    fun `deprecated fields absent or odd read fine`() = runTest {
        val api = FakeApi(this) { request ->
            when (request.call()) {
                "cache/check" -> json("""{"status":"success","response":[true],"transcoded":"no longer"}""")
                else -> json("""{"status":"success","content":[{"path":"$episode2","size":1468006401,"link":"https://cdn-12.example.net/x"}]}""")
            }
        }
        val service = premiumize(api)
        assertEquals(mapOf(pack to true), service.check(listOf(pack)))
        assertEquals(episode2, service.prepare(pack, pickEpisode2).picked?.path)
    }

    @Test
    fun `a key planted in an error's message never reaches the bucket's words or a line`() = runTest {
        val api = FakeApi(this) { json(error("authentication_failed", "The key $KEY isn't valid")) }
        val refused = assertFailsWith<ServiceException> { premiumize(api).test() }
        assertFalse(KEY in refused.message.orEmpty())
        assertTrue(lines.isNotEmpty())
        lines.forEach { assertFalse(KEY in it, it) }
    }

    /** D2's live check (L235): `directdl` adds no transfer to the account, so there is nothing of a play's to remove. */
    @Test
    fun `premiumize adds nothing to the account, so a remove makes no call`() = runTest {
        val api = FakeApi(this) { premiumize(it) }
        assertFalse(premiumize(api).kind.addsToAccount)
        premiumize(api).remove("42")
        assertEquals(emptyList(), api.requests.toList())
        assertEquals(emptyList(), lines.toList())
    }

    private companion object {
        const val KEY = "pmk-INVENTED-fedcba9876543210"
    }
}
