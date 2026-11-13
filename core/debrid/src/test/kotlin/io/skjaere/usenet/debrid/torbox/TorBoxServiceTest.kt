package io.skjaere.usenet.debrid.torbox

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.skjaere.usenet.debrid.AddWatch
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TorBox's client (L197) against invented fixtures shaped from TorBox's documented answers: each
 * call's method, path, query, form and headers, and every documented code's bucket. Never a request to TorBox.
 */
class TorBoxServiceTest {
    private val clock = MutableClock()
    private val gate = ServiceGate(clock)
    private val lines = mutableListOf<String>()
    private val pack = InfoHash.of("d3adb33fd3adb33fd3adb33fd3adb33fd3adb33f")
    private val episode2 = "The.Glass.Orchard.S02.1080p.WEB-DL.H.264-KESTREL/The.Glass.Orchard.S02E02.1080p.WEB-DL.H.264-KESTREL.mkv"
    private val link = "https://cdn-07.example.net/d/9c1f2e/The.Glass.Orchard.S02E02.1080p.WEB-DL.H.264-KESTREL.mkv?sig=LINKSECRET77"

    private fun TestScope.torbox(api: FakeApi, known: String? = null) = TorBoxService(
        KEY, api.http, limit = ServiceRateLimit(Duration.ofMillis(250)) { testScheduler.currentTime }, gate = gate,
        knownTorrent = { known }, clock = clock, log = lines::add,
    )

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/torbox/$name")) { "missing fixture $name" }.readText()

    private fun HttpRequestData.call() = url.encodedPath.removePrefix("/v1/api/")

    /** TorBox's answers for the pack, as each call gets them. */
    private fun MockRequestHandleScope.torbox(request: HttpRequestData): HttpResponseData = when (request.call()) {
        "user/me" -> json(fixture("user-me.json"))
        "torrents/checkcached" -> json(fixture("checkcached-files.json"))
        "torrents/createtorrent" -> json(fixture("createtorrent.json"))
        "torrents/mylist" -> json(fixture("mylist.json"))
        "torrents/requestdl" -> json(fixture("requestdl.json"))
        else -> json("""{"success":false,"error":"ENDPOINT_NOT_FOUND"}""", HttpStatusCode.NotFound)
    }

    private fun error(code: String?, status: HttpStatusCode, detail: String = "Something went wrong.") =
        """{"success":false,"error":${code?.let { "\"$it\"" } ?: "null"},"detail":"$detail","data":null}"""

    private val pickEpisode2: suspend (ServiceListing) -> ServiceFile? = { listing -> listing.files.single { it.path == episode2 } }

    private suspend fun HttpRequestData.form(): Map<String, String> {
        val text = body.toByteArray().decodeToString()
        val boundary = requireNotNull(body.contentType?.parameter("boundary"))
        return text.split("--$boundary").mapNotNull { part ->
            val name = Regex("name=\"?([^\";\r\n]+)\"?").find(part.substringBefore("\r\n\r\n"))?.groupValues?.get(1) ?: return@mapNotNull null
            name to part.substringAfter("\r\n\r\n").removeSuffix("\r\n")
        }.toMap()
    }

    private fun HttpRequestData.hashesSent(): List<String> =
        Json.parseToJsonElement((body as TextContent).text).jsonObject.getValue("hashes").jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `test signs in with the bearer key and names the account, its plan and when it ends`() = runTest {
        val api = FakeApi(this) { torbox(it) }
        val account = torbox(api).test()
        assertEquals("quay.viewer@example.org", account.name)
        assertEquals("Standard", account.plan)
        assertEquals(Instant.parse("2027-01-12T00:00:00Z"), account.planUntil)

        val request = api.requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("https://api.torbox.app/v1/api/user/me", request.url.toString())
        assertEquals("Bearer $KEY", request.headers[HttpHeaders.Authorization])
        assertEquals(ServiceKind.TorBox, torbox(api).kind)
    }

    @Test
    fun `the plans are named as TorBox names them, and a missing field is no plan`() = runTest {
        val plans = listOf(0 to "Free", 1 to "Essential", 2 to "Pro", 3 to "Standard", 9 to null)
        plans.forEach { (number, name) ->
            val api = FakeApi(this) { json("""{"success":true,"data":{"email":"quay.viewer@example.org","plan":$number}}""") }
            assertEquals(name, torbox(api).test().plan, "plan $number")
        }
        val bare = FakeApi(this) { json("""{"success":true,"data":{"email":"quay.viewer@example.org"}}""") }
        val account = torbox(bare).test()
        assertNull(account.plan)
        assertNull(account.planUntil)
    }

    @Test
    fun `a cache entry no model reads is passed over, and fields it doesn't know are skipped`() = runTest {
        val other = InfoHash.of("ab".repeat(20))
        val api = FakeApi(this) {
            json(
                """{"success":true,"error":null,"detail":"Found.","later":{"x":1},"data":["odd",""" +
                    """{"name":"x","size":"not a number","hash":"${other.hex}"},{"name":"x","hash":"${pack.hex}","seeds":4,"files":null}]}""",
            )
        }
        // The string is no entry; the two objects are, whatever else they hold.
        assertEquals(mapOf(pack to true, other to true), torbox(api).check(listOf(pack, other)))
    }

    @Test
    fun `check posts the hashes as json, at most 100 a call, listing no files, and answers every one`() = runTest {
        val hashes = (0 until 250).map { InfoHash.of("%040x".format(it + 1)) }
        val cached = hashes.filterIndexed { i, _ -> i % 3 == 0 }.map { it.hex }.toSet()
        val api = FakeApi(this) { request ->
            val found = request.hashesSent().filter { it in cached }
            json(found.joinToString(",", """{"success":true,"error":null,"detail":"Found.","data":[""", "]}") { """{"name":"x","size":1,"hash":"$it"}""" })
        }
        val answer = torbox(api).check(hashes)

        assertEquals(listOf(100, 100, 50), api.requests.map { it.hashesSent().size })
        api.requests.forEach { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/api/torrents/checkcached", request.url.encodedPath)
            assertEquals(mapOf("format" to listOf("list"), "list_files" to listOf("false")), request.url.parameters.entries().associate { it.key to it.value })
            assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
            assertEquals("Bearer $KEY", request.headers[HttpHeaders.Authorization])
        }
        assertEquals(hashes.map { it.hex }, api.requests.flatMap { it.hashesSent() })
        assertEquals(hashes.toSet(), answer.keys)
        assertEquals(cached, answer.filterValues { it }.keys.map { it.hex }.toSet())
        assertContains(lines.first(), "debrid: TorBox checkcached 100 hashes: 34 cached")
    }

    @Test
    fun `a server that refuses the post is asked by get from then on`() = runTest {
        val cached = pack.hex
        val api = FakeApi(this) { request ->
            if (request.method == HttpMethod.Post) json("", HttpStatusCode.MethodNotAllowed)
            else json("""{"success":true,"data":[{"name":"x","size":1,"hash":"$cached"}]}""")
        }
        val other = InfoHash.of("ab".repeat(20))
        val service = torbox(api)
        assertEquals(mapOf(pack to true, other to false), service.check(listOf(pack, other)))
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Get), api.requests.map { it.method })
        val get = api.requests.last()
        assertEquals("/v1/api/torrents/checkcached", get.url.encodedPath)
        assertEquals(mapOf("hash" to listOf("${pack.hex},${other.hex}"), "format" to listOf("list")), get.url.parameters.entries().associate { it.key to it.value })
        assertEquals("Bearer $KEY", get.headers[HttpHeaders.Authorization])

        service.check(listOf(other))
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Get, HttpMethod.Get), api.requests.map { it.method }, "no POST again")
        // A hundred a GET too, the SDK page's limit.
        service.check((0 until 150).map { InfoHash.of("%040x".format(it + 1)) })
        assertEquals(listOf(100, 50), api.requests.drop(3).map { it.url.parameters["hash"]!!.split(',').size })
    }

    @Test
    fun `prepare lists the files with their folders and asks the pick before anything is added`() = runTest {
        var requestsAtPick = -1
        val api = FakeApi(this) { torbox(it) }
        val prepared = torbox(api).prepare(pack) { listing ->
            requestsAtPick = api.requests.size
            assertEquals(
                listOf(
                    ServiceFile(0, "The.Glass.Orchard.S02.1080p.WEB-DL.H.264-KESTREL/Sample/sample.mkv", 31457280),
                    ServiceFile(1, "The.Glass.Orchard.S02.1080p.WEB-DL.H.264-KESTREL/The.Glass.Orchard.S02E01.1080p.WEB-DL.H.264-KESTREL.mkv", 1468006400),
                    ServiceFile(2, episode2, 1468006401),
                    ServiceFile(3, "The.Glass.Orchard.S02.1080p.WEB-DL.H.264-KESTREL/The.Glass.Orchard.S02E03.1080p.WEB-DL.H.264-KESTREL.mkv", 1468006402),
                ),
                listing.files,
            )
            listing.files[2]
        }
        assertEquals(1, requestsAtPick, "only the cache check before the pick")

        val (check, add, mylist) = api.requests.toList()
        assertEquals(HttpMethod.Post, check.method)
        assertEquals("/v1/api/torrents/checkcached", check.url.encodedPath)
        assertEquals(mapOf("format" to listOf("list"), "list_files" to listOf("true")), check.url.parameters.entries().associate { it.key to it.value })
        assertEquals(listOf(pack.hex), check.hashesSent())

        assertEquals(HttpMethod.Post, add.method)
        assertEquals("/v1/api/torrents/createtorrent", add.url.encodedPath)
        assertTrue(add.url.parameters.isEmpty())
        assertEquals("Bearer $KEY", add.headers[HttpHeaders.Authorization])
        assertEquals(ContentType.MultiPart.FormData, add.body.contentType?.withoutParameters())
        assertEquals(
            mapOf("magnet" to "magnet:?xt=urn:btih:${pack.hex}", "add_only_if_cached" to "true", "seed" to "3", "allow_zip" to "false", "as_queued" to "false"),
            add.form(),
        )

        assertEquals(HttpMethod.Get, mylist.method)
        assertEquals("/v1/api/torrents/mylist", mylist.url.encodedPath)
        assertEquals(mapOf("id" to listOf("5021337"), "bypass_cache" to listOf("true")), mylist.url.parameters.entries().associate { it.key to it.value })
        assertEquals("Bearer $KEY", mylist.headers[HttpHeaders.Authorization])

        // Each file matched to TorBox's own by name and size, whatever order mylist lists them in.
        assertEquals(ServiceFile(2, episode2, 1468006401, id = "3"), prepared.picked)
        assertEquals(listOf("1", "2", "3", "4"), prepared.listing.files.map { it.id })
        assertEquals("5021337", prepared.handle)
        assertEquals(pack, prepared.hash)
    }

    @Test
    fun `nothing is added when the pick takes none`() = runTest {
        val api = FakeApi(this) { torbox(it) }
        val prepared = torbox(api).prepare(pack) { null }
        assertNull(prepared.picked)
        assertNull(prepared.handle)
        assertEquals(listOf("torrents/checkcached"), api.requests.map { it.call() })
    }

    @Test
    fun `a torrent the account still has isn't added again, and one it no longer has is`() = runTest {
        val still = FakeApi(this) { torbox(it) }
        val prepared = torbox(still, known = "5021337").prepare(pack, pickEpisode2)
        assertEquals(listOf("torrents/checkcached", "torrents/mylist"), still.requests.map { it.call() })
        assertEquals("5021337", still.requests.last().url.parameters["id"])
        assertEquals("3", prepared.picked?.id)

        val gone = FakeApi(this) { request ->
            if (request.call() == "torrents/mylist" && request.url.parameters["id"] == "777") json(error("ITEM_NOT_FOUND", HttpStatusCode.NotFound), HttpStatusCode.NotFound)
            else torbox(request)
        }
        assertEquals("5021337", torbox(gone, known = "777").prepare(pack, pickEpisode2).handle)
        assertEquals(listOf("torrents/checkcached", "torrents/mylist", "torrents/createtorrent", "torrents/mylist"), gone.requests.map { it.call() })
    }

    @Test
    fun `a torrent the service doesn't have cached is not cached, and nothing is added`() = runTest {
        val api = FakeApi(this) { json("""{"success":true,"error":null,"detail":"Found 0 cached torrents.","data":[]}""") }
        assertEquals(ServiceError.NotCached, assertFailsWith<ServiceException> { torbox(api).prepare(pack, pickEpisode2) }.error)
        assertEquals(1, api.requests.size)
        // TorBox's object form, and a null for none, read the same.
        val nulled = FakeApi(this) { json("""{"success":true,"data":null}""") }
        assertEquals(ServiceError.NotCached, assertFailsWith<ServiceException> { torbox(nulled).prepare(pack, pickEpisode2) }.error)
    }

    @Test
    fun `the picked file is matched by name and size alone, else it isn't there`() = runTest {
        val offByOne = FakeApi(this) { request ->
            if (request.call() == "torrents/mylist") json(fixture("mylist.json").replace("\"size\": 1468006401", "\"size\": 1468006409")) else torbox(request)
        }
        assertEquals(ServiceError.NotCached, assertFailsWith<ServiceException> { torbox(offByOne).prepare(pack, pickEpisode2) }.error)

        val empty = FakeApi(this) { request ->
            if (request.call() == "torrents/mylist") json("""{"success":true,"data":{"id":5021337,"hash":"${pack.hex}","files":[]}}""") else torbox(request)
        }
        assertEquals(ServiceError.NotCached, assertFailsWith<ServiceException> { torbox(empty).prepare(pack, pickEpisode2) }.error)

        val noId = FakeApi(this) { request ->
            if (request.call() == "torrents/createtorrent") json("""{"success":true,"data":{}}""") else torbox(request)
        }
        assertEquals(ServiceError.NotCached, assertFailsWith<ServiceException> { torbox(noId).prepare(pack, pickEpisode2) }.error)
    }

    @Test
    fun `link asks requestdl with the key as its token, redirect off and no bearer, and the link shows nowhere`() = runTest {
        val api = FakeApi(this) { torbox(it) }
        val service = torbox(api)
        val prepared = service.prepare(pack, pickEpisode2)
        val made = service.link(prepared, prepared.picked!!)
        assertEquals(link, made.url)
        assertEquals(clock.instant, made.madeAt)

        val request = api.requests.last()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/v1/api/torrents/requestdl", request.url.encodedPath)
        assertEquals(
            mapOf("token" to listOf(KEY), "torrent_id" to listOf("5021337"), "file_id" to listOf("3"), "redirect" to listOf("false")),
            request.url.parameters.entries().associate { it.key to it.value },
        )
        assertNull(request.headers[HttpHeaders.Authorization], "the key goes once, as the token")
        assertFalse("LINKSECRET77" in made.toString() || "cdn-07" in made.toString())
        assertFalse("LINKSECRET77" in prepared.toString())
        lines.forEach { assertFalse("LINKSECRET77" in it || KEY in it || pack.hex in it, it) }
        assertContains(lines.last(), "debrid: TorBox requestdl: link made")
    }

    @Test
    fun `a file whose id wasn't kept is looked up in mylist first`() = runTest {
        val api = FakeApi(this) { torbox(it) }
        val prepared = torbox(api).prepare(pack, pickEpisode2)
        val stored = io.skjaere.usenet.debrid.Prepared(prepared.hash, ServiceListing(prepared.listing.files.map { it.copy(id = null) }), prepared.picked?.copy(id = null), prepared.handle)
        torbox(api).link(stored, stored.listing.files[3])
        assertEquals(listOf("torrents/mylist", "torrents/requestdl"), api.requests.takeLast(2).map { it.call() })
        assertEquals("4", api.requests.last().url.parameters["file_id"])

        // Nothing added, nothing to link.
        val unadded = io.skjaere.usenet.debrid.Prepared(pack, prepared.listing, null)
        assertEquals(ServiceError.NotCached, assertFailsWith<ServiceException> { torbox(api).link(unadded, prepared.listing.files[2]) }.error)
    }

    @Test
    fun `a link the service no longer has is not cached`() = runTest {
        val api = FakeApi(this) { request ->
            if (request.call() == "torrents/requestdl") json(error("ITEM_NOT_FOUND", HttpStatusCode.NotFound), HttpStatusCode.NotFound) else torbox(request)
        }
        val service = torbox(api)
        val prepared = service.prepare(pack, pickEpisode2)
        assertEquals(ServiceError.NotCached, assertFailsWith<ServiceException> { service.link(prepared, prepared.picked!!) }.error)
    }

    /**
     * As the live check found TorBox (item 39): createtorrent told to add only a cached torrent answers one it hasn't cached
     * with 404 DOWNLOAD_NOT_CACHED. That is the torrent not there (L197), so the release is not cached and the account's
     * next release, or its next account, is tried; never a refusal of the release.
     */
    @Test
    fun `a torrent createtorrent says isn't cached is not cached, and the gate stays open`() = runTest {
        val notCached = """{"success":false,"error":"DOWNLOAD_NOT_CACHED","detail":"Not cached.","data":null}"""
        val api = FakeApi(this) { request ->
            if (request.call() == "torrents/createtorrent") json(notCached, HttpStatusCode.NotFound) else torbox(request)
        }
        assertEquals(ServiceError.NotCached, assertFailsWith<ServiceException> { torbox(api).prepare(pack, pickEpisode2) }.error)
        assertEquals(listOf("torrents/checkcached", "torrents/createtorrent"), api.requests.map { it.call() })
        assertContains(lines.last(), "debrid: TorBox createtorrent: DOWNLOAD_NOT_CACHED")
        assertNull(gate.closed())
        // The same code for a link: the torrent fell out of the cache under the account.
        assertEquals(ServiceError.NotCached, failureOf(HttpStatusCode.NotFound, "DOWNLOAD_NOT_CACHED", "torrents/requestdl"))
    }

    /** mylist's answer for an id the account doesn't have, as the live check found it: 404, ITEM_NOT_FOUND, success true. */
    @Test
    fun `mylist's not found with success true is a torrent the account no longer has`() = runTest {
        val api = FakeApi(this) { request ->
            if (request.call() == "torrents/mylist" && request.url.parameters["id"] == "777") {
                json("""{"success":true,"error":"ITEM_NOT_FOUND","detail":"No such torrent.","data":{}}""", HttpStatusCode.NotFound)
            } else {
                torbox(request)
            }
        }
        assertEquals("5021337", torbox(api, known = "777").prepare(pack, pickEpisode2).handle)
        assertEquals(listOf("torrents/checkcached", "torrents/mylist", "torrents/createtorrent", "torrents/mylist"), api.requests.map { it.call() })
    }

    private suspend fun TestScope.failureOf(status: HttpStatusCode, code: String?, at: String, headers: Array<Pair<String, String>> = emptyArray()): ServiceError {
        val api = FakeApi(this) { request ->
            if (request.call() == at) json(error(code, status), status, *headers) else torbox(request)
        }
        val service = torbox(api)
        return assertFailsWith<ServiceException> {
            when (at) {
                "user/me" -> service.test()
                "torrents/checkcached" -> service.check(listOf(pack))
                "torrents/requestdl" -> service.prepare(pack, pickEpisode2).let { service.link(it, it.picked!!) }
                else -> service.prepare(pack, pickEpisode2)
            }
        }.error
    }

    @Test
    fun `the limit codes are the account's limit, with a wait when the answer gives one, and close the gate`() = runTest {
        listOf("MONTHLY_LIMIT", "COOLDOWN_LIMIT", "ACTIVE_LIMIT").forEach { code ->
            gate.reset()
            assertEquals(ServiceError.Limit(), failureOf(HttpStatusCode.Forbidden, code, "torrents/checkcached"), code)
            assertIs<ServiceError.Limit>(gate.closed())
        }
        gate.reset()
        assertEquals(ServiceError.Limit(Duration.ofMinutes(10)), failureOf(HttpStatusCode.BadRequest, "COOLDOWN_LIMIT", "torrents/createtorrent", arrayOf("Retry-After" to "600")))
        gate.reset()
        assertEquals(ServiceError.Limit(Duration.ofMinutes(20)), failureOf(HttpStatusCode.TooManyRequests, null, "torrents/checkcached", arrayOf("Retry-After" to "1200")))
    }

    @Test
    fun `too large for the plan refuses that release alone and leaves the gate open`() = runTest {
        assertEquals(ServiceError.Refused(tooLarge = true), failureOf(HttpStatusCode.BadRequest, "DOWNLOAD_TOO_LARGE", "torrents/createtorrent"))
        assertNull(gate.closed())
        assertEquals(ServiceError.Refused(), failureOf(HttpStatusCode.Forbidden, "PLAN_RESTRICTED_FEATURE", "torrents/createtorrent"))
        assertNull(gate.closed(), "a code wins over its status: not a refused key")
    }

    @Test
    fun `a refused key is its bucket by status or by code, and closes the gate until reset`() = runTest {
        listOf(
            HttpStatusCode.Unauthorized to null, HttpStatusCode.Forbidden to null, HttpStatusCode.Forbidden to "BAD_TOKEN",
            HttpStatusCode.Unauthorized to "NO_AUTH", HttpStatusCode.OK to "AUTH_ERROR", HttpStatusCode.Forbidden to "SOMETHING_NEW",
        ).forEach { (status, code) ->
            gate.reset()
            assertEquals(ServiceError.KeyRefused, failureOf(status, code, "torrents/checkcached"), "$status $code")
            assertEquals(ServiceError.KeyRefused, gate.closed())
        }
        gate.reset()
        assertEquals(ServiceError.KeyRefused, failureOf(HttpStatusCode.Forbidden, "BAD_TOKEN", "user/me"))
    }

    /** L236: TorBox's table makes a code ending in ERROR the server's fault, so a 5xx under AUTH_ERROR is a hiccup. */
    @Test
    fun `auth error with a 5xx is down and leaves the gate open, and with any other status a refused key`() = runTest {
        listOf("user/me", "torrents/mylist", "torrents/requestdl").forEach { at ->
            listOf(HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway, HttpStatusCode.ServiceUnavailable).forEach { status ->
                assertEquals(ServiceError.Down, failureOf(status, "AUTH_ERROR", at), "$at $status")
                assertNull(gate.closed(), "$at $status: the account isn't marked refused")
            }
        }
        listOf("user/me", "torrents/mylist", "torrents/requestdl").forEach { at ->
            listOf(HttpStatusCode.Forbidden, HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized).forEach { status ->
                gate.reset()
                assertEquals(ServiceError.KeyRefused, failureOf(status, "AUTH_ERROR", at), "$at $status")
                assertEquals(ServiceError.KeyRefused, gate.closed(), "$at $status")
            }
        }
    }

    @Test
    fun `faults of its own and network errors are down`() = runTest {
        assertEquals(ServiceError.Down, failureOf(HttpStatusCode.InternalServerError, "DATABASE_ERROR", "torrents/checkcached"))
        assertEquals(ServiceError.Down, failureOf(HttpStatusCode.ServiceUnavailable, null, "torrents/createtorrent"))
        assertEquals(ServiceError.Down, failureOf(HttpStatusCode.BadGateway, null, "torrents/requestdl"))
        val api = FakeApi(this) { throw IOException("connection reset") }
        assertEquals(ServiceError.Down, assertFailsWith<ServiceException> { torbox(api).check(listOf(pack)) }.error)
        val html = FakeApi(this) { respond("<html>maintenance</html>", HttpStatusCode.OK) }
        assertEquals(ServiceError.Down, assertFailsWith<ServiceException> { torbox(html).test() }.error)
        assertNull(gate.closed())
    }

    @Test
    fun `an unknown code is down on a test or a check and refuses the release on prepare or link, its code logged`() = runTest {
        assertEquals(ServiceError.Down, failureOf(HttpStatusCode.BadRequest, "SOMETHING_NEW", "user/me"))
        assertEquals(ServiceError.Down, failureOf(HttpStatusCode.BadRequest, "SOMETHING_NEW", "torrents/checkcached"))
        assertContains(lines.last(), "SOMETHING_NEW")
        assertEquals(ServiceError.Refused(), failureOf(HttpStatusCode.BadRequest, "SOMETHING_NEW", "torrents/createtorrent"))
        assertEquals(ServiceError.Refused(), failureOf(HttpStatusCode.OK, "SOMETHING_NEW", "torrents/requestdl"))
        assertContains(lines.last(), "debrid: TorBox requestdl: SOMETHING_NEW")
        assertNull(gate.closed())
    }

    @Test
    fun `an answer over 2 MB is down`() = runTest {
        val big = """{"success":true,"data":[""" + (1..30_000).joinToString(",") { """{"name":"x","size":1,"hash":"${"%040x".format(it)}"}""" } + "]}"
        assertTrue(big.length > 2 * 1024 * 1024)
        val api = FakeApi(this) { json(big) }
        assertEquals(ServiceError.Down, assertFailsWith<ServiceException> { torbox(api).check(listOf(pack)) }.error)
    }

    @Test
    fun `a key planted in an error's detail never reaches the bucket's words or a line`() = runTest {
        val planted = "Your key $KEY was refused at https://api.torbox.app/v1/api/torrents/requestdl?token=$KEY"
        val api = FakeApi(this) { json(error("BAD_TOKEN", HttpStatusCode.Forbidden, planted), HttpStatusCode.Forbidden) }
        val refused = assertFailsWith<ServiceException> { torbox(api).test() }
        assertFalse(KEY in refused.message.orEmpty())
        assertNull(refused.cause)
        assertTrue(lines.isNotEmpty())
        lines.forEach { assertFalse(KEY in it || "refused at" in it, it) }
    }

    // D2's #318 (item 16; L232, L234): what an add is told to the app, the delete, and the re-add.

    /** An [AddWatch] that records what it is told, and the calls made by then ([seen]). */
    private class Watch(var wanted: Boolean = true, private val seen: () -> List<String> = { emptyList() }) : AddWatch {
        val said = mutableListOf<Pair<InfoHash, String>>()
        var callsWhenSaid: List<String>? = null

        override suspend fun wanted() = wanted

        override suspend fun added(hash: InfoHash, handle: String) {
            said += hash to handle
            callsWhenSaid = seen()
        }
    }

    private fun TestScope.torbox(api: FakeApi, watch: AddWatch?, known: String? = null) = TorBoxService(
        KEY, api.http, limit = ServiceRateLimit(Duration.ofMillis(250)) { testScheduler.currentTime }, gate = gate,
        knownTorrent = { known }, addWatch = watch, clock = clock, log = lines::add,
    )

    /**
     * mylist.json's torrent as an entry of the account's list, under [id], with [hash] (none when null) and [alternatives];
     * its files' ids moved on by [fileIdsBy], as a torrent added again gets new ones.
     */
    private fun entry(id: Long, hash: String? = pack.hex, alternatives: List<String> = emptyList(), fileIdsBy: Int = 0): JsonObject {
        val torrent = Json.parseToJsonElement(fixture("mylist.json")).jsonObject.getValue("data").jsonObject
        val files = torrent.getValue("files").jsonArray.map { file ->
            JsonObject(file.jsonObject + ("id" to JsonPrimitive(file.jsonObject.getValue("id").jsonPrimitive.int + fileIdsBy)))
        }
        return JsonObject(
            torrent + mapOf(
                "id" to JsonPrimitive(id), "hash" to (hash?.let(::JsonPrimitive) ?: JsonNull),
                "alternative_hashes" to JsonArray(alternatives.map(::JsonPrimitive)), "files" to JsonArray(files),
            ),
        )
    }

    /** Another torrent's entry, with no files. */
    private fun other(id: Long) = JsonObject(
        mapOf("id" to JsonPrimitive(id), "hash" to JsonPrimitive("%040x".format(id)), "alternative_hashes" to JsonArray(emptyList()), "files" to JsonArray(emptyList())),
    )

    private fun JsonObject.id() = getValue("id").jsonPrimitive.content

    /**
     * TorBox over an account whose list is [listed]: mylist pages it by offset and limit, a page with no torrents answered
     * as TorBox documents a list with none ([NO_TORRENTS], or an empty array when [emptyAsArray]), and finds an id in it
     * (or [added], once createtorrent answered its id); createtorrent answers [created]'s id; the rest as the pack's
     * fixtures.
     */
    private fun MockRequestHandleScope.account(
        request: HttpRequestData,
        listed: List<JsonObject>,
        created: Long = 5021337,
        added: JsonObject = entry(created),
        emptyAsArray: Boolean = false,
    ): HttpResponseData {
        if (request.call() == "torrents/createtorrent") {
            return json("""{"success":true,"error":null,"detail":"Found Cached Torrent. Using Cached Download.","data":{"hash":"${pack.hex}","torrent_id":$created,"auth_id":"x"}}""")
        }
        if (request.call() != "torrents/mylist") return torbox(request)
        val id = request.url.parameters["id"]
        if (id != null) {
            val found = listed.firstOrNull { it.id() == id } ?: added.takeIf { it.id() == id }
            return found?.let { json("""{"success":true,"error":null,"detail":"Torrent list retrieved successfully.","data":$it}""") }
                ?: json("""{"success":true,"error":"ITEM_NOT_FOUND","detail":"No such torrent.","data":{}}""", HttpStatusCode.NotFound)
        }
        val offset = request.url.parameters["offset"]!!.toInt()
        val limit = request.url.parameters["limit"]!!.toInt()
        val page = listed.drop(offset).take(limit)
        if (page.isEmpty() && !emptyAsArray) return json(NO_TORRENTS, HttpStatusCode.NotFound)
        return json("""{"success":true,"error":null,"detail":"Torrent list retrieved successfully.","data":${JsonArray(page)}}""")
    }

    /** The pack prepared earlier, as the app's listing store keeps it: TorBox's torrent 5021337, its files' ids 1 to 4. */
    private fun stored(): Prepared {
        val files = PACK_PATHS.indices.map { i -> ServiceFile(i, PACK_PATHS[i], PACK_SIZES[i], id = "${i + 1}") }
        return Prepared(pack, ServiceListing(files), files[2], handle = "5021337")
    }

    private fun HttpRequestData.page(): Map<String, List<String>> = url.parameters.entries().associate { it.key to it.value }

    private fun pagesAsked(api: FakeApi) = api.requests.filter { it.call() == "torrents/mylist" && it.url.parameters["id"] == null }

    @Test
    fun `torbox adds to the account and premiumize doesn't`() {
        assertTrue(ServiceKind.TorBox.addsToAccount)
        assertFalse(ServiceKind.Premiumize.addsToAccount)
    }

    @Test
    fun `before an add the whole list is read a hundred at a time, each page from the entry the one before ended on, and an add it didn't hold is said once, before its files are asked`() = runTest {
        val listed = (1L..150L).map(::other)
        lateinit var api: FakeApi
        val watch = Watch { api.requests.map { it.call() } }
        api = FakeApi(this) { account(it, listed) }
        val prepared = torbox(api, watch).prepare(pack, pickEpisode2)

        assertEquals("5021337", prepared.handle)
        assertEquals("3", prepared.picked?.id)
        assertEquals(listOf(pack to "5021337"), watch.said)
        assertEquals(listOf("torrents/checkcached", "torrents/mylist", "torrents/mylist", "torrents/createtorrent"), watch.callsWhenSaid, "said before mylist(id)")
        assertEquals(listOf("torrents/checkcached", "torrents/mylist", "torrents/mylist", "torrents/createtorrent", "torrents/mylist"), api.requests.map { it.call() })
        val (first, second) = pagesAsked(api)
        assertEquals(mapOf("bypass_cache" to listOf("true"), "offset" to listOf("0"), "limit" to listOf("101")), first.page())
        assertEquals(mapOf("bypass_cache" to listOf("true"), "offset" to listOf("100"), "limit" to listOf("101")), second.page())
        pagesAsked(api).forEach { assertEquals("Bearer $KEY", it.headers[HttpHeaders.Authorization]) }
        assertEquals("5021337", api.requests.last().url.parameters["id"])
        assertTrue(lines.any { it.startsWith("debrid: TorBox mylist, whole: 2 pages, ") }, "$lines")
    }

    /** L232: TorBox keeps one entry per torrent, so a hash the list holds, as its own or an alternative, is never this device's add. */
    @Test
    fun `a hash the whole list holds, as its own or as an alternative, is that entry, with nothing added or said`() = runTest {
        listOf(
            entry(7000001),
            entry(7000002, hash = "ab".repeat(20), alternatives = listOf("cd".repeat(20), pack.hex.uppercase())),
        ).forEach { held ->
            val watch = Watch()
            val api = FakeApi(this) { account(it, listOf(other(1), held)) }
            val prepared = torbox(api, watch).prepare(pack, pickEpisode2)
            assertEquals(held.id(), prepared.handle, "the play goes on from that entry")
            assertEquals("3", prepared.picked?.id)
            assertEquals(listOf("1", "2", "3", "4"), prepared.listing.files.map { it.id })
            assertEquals(emptyList(), watch.said)
            assertEquals(listOf("torrents/checkcached", "torrents/mylist"), api.requests.map { it.call() }, "no createtorrent")
        }
    }

    /** L232: TorBox may answer an alternative hash's add with the entry it holds; an id the list holds is never this device's add. */
    @Test
    fun `an add answered with an id the list already holds is not said`() = runTest {
        val watch = Watch()
        val api = FakeApi(this) { account(it, listOf(other(1), entry(5021337, hash = null))) }
        val prepared = torbox(api, watch).prepare(pack, pickEpisode2)
        assertEquals("5021337", prepared.handle)
        assertEquals(listOf("torrents/checkcached", "torrents/mylist", "torrents/createtorrent", "torrents/mylist"), api.requests.map { it.call() })
        assertEquals(emptyList(), watch.said)
    }

    /** Unsure means not ours (L232): a list that isn't read whole records nothing, and the add goes on as in D1. */
    @Test
    fun `a list past 10 pages, past 8 s, over the cap, failing or unreadable records nothing, and the add goes on`() = runTest {
        val shapes = listOf<Triple<String, Long, suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>>(
            Triple("11 pages", 10, { account(it, (1L..1_050L).map(::other)) }),
            Triple("1,001 entries, 10 full pages", 10, { account(it, (1L..1_001L).map(::other)) }),
            Triple("1.1 s a page", 8, { request ->
                if (request.call() == "torrents/mylist" && request.url.parameters["id"] == null) delay(1_100)
                account(request, (1L..950L).map(::other))
            }),
            Triple("a page failing", 1, { request ->
                if (request.call() == "torrents/mylist" && request.url.parameters["id"] == null) json(error("DATABASE_ERROR", HttpStatusCode.InternalServerError), HttpStatusCode.InternalServerError)
                else account(request, emptyList())
            }),
            Triple("a page over 2 MB", 1, { request ->
                if (request.call() == "torrents/mylist" && request.url.parameters["id"] == null) {
                    json("""{"success":true,"data":[""" + (1..40_000).joinToString(",") { """{"id":$it,"hash":"${"%040x".format(it)}","files":[]}""" } + "]}")
                } else {
                    account(request, emptyList())
                }
            }),
            Triple("no list in the answer", 1, { request ->
                if (request.call() == "torrents/mylist" && request.url.parameters["id"] == null) json("""{"success":true,"data":null}""") else account(request, emptyList())
            }),
            Triple("an entry without an id", 1, { request ->
                if (request.call() == "torrents/mylist" && request.url.parameters["id"] == null) json("""{"success":true,"data":[{"hash":"${"ab".repeat(20)}","files":[]}]}""")
                else account(request, emptyList())
            }),
        )
        shapes.forEach { (shape, pages, answer) ->
            lines.clear()
            val watch = Watch()
            val api = FakeApi(this, answer)
            val prepared = torbox(api, watch).prepare(pack, pickEpisode2)
            assertEquals("5021337", prepared.handle, shape)
            assertEquals(emptyList(), watch.said, shape)
            assertEquals(pages, pagesAsked(api).size.toLong(), "$shape: pages asked")
            assertEquals(1, api.requests.count { it.call() == "torrents/createtorrent" }, shape)
            assertTrue(lines.any { it.startsWith("debrid: TorBox mylist, whole: ") && "the add not recorded" in it }, "$shape: $lines")
        }
    }

    /**
     * ECS-1: TorBox answers a list read that finds no torrents with HTTP 404 under `success: true`, `ITEM_NOT_FOUND` ("No
     * torrents found for this user.", its collection's example for Get Torrent List). An empty account is read whole by
     * it, as by an empty array, so its first add is this device's; only that exact answer is, and only on the first page:
     * a later page finding none is a list that moved, and the same code under `success: false` is no list at all.
     */
    @Test
    fun `an empty account is read whole, as TorBox answers it or as an empty list, so its first add is said`() = runTest {
        listOf(false, true).forEach { asArray ->
            lines.clear()
            val watch = Watch()
            val api = FakeApi(this) { account(it, emptyList(), emptyAsArray = asArray) }
            assertEquals("5021337", torbox(api, watch).prepare(pack, pickEpisode2).handle)
            assertEquals(listOf(pack to "5021337"), watch.said, "as an array: $asArray")
            assertEquals(1, pagesAsked(api).size)
            assertTrue(lines.any { it.startsWith("debrid: TorBox mylist, whole: 1 page, ") }, "$lines")
        }

        // Emptied while it was read: the second page finds none.
        lines.clear()
        val listed = (1L..150L).map(::other).toMutableList()
        val emptied = Watch()
        val api = FakeApi(this) { request ->
            account(request, listed.toList()).also { if (request.call() == "torrents/mylist" && request.url.parameters["id"] == null) listed.clear() }
        }
        torbox(api, emptied).prepare(pack, pickEpisode2)
        assertEquals(emptyList(), emptied.said)
        assertTrue(lines.any { it.startsWith("debrid: TorBox mylist, whole: the list changed while it was read, the add not recorded, ") }, "$lines")

        // ITEM_NOT_FOUND under success false isn't TorBox's answer for a list with none.
        val unlike = Watch()
        val other = FakeApi(this) { request ->
            if (request.call() == "torrents/mylist" && request.url.parameters["id"] == null) json(error("ITEM_NOT_FOUND", HttpStatusCode.NotFound), HttpStatusCode.NotFound) else account(request, emptyList())
        }
        torbox(other, unlike).prepare(pack, pickEpisode2)
        assertEquals(emptyList(), unlike.said)
    }

    /** ECS-1: an account of whole pages (100, 300 entries) is read whole without asking a page past its end. */
    @Test
    fun `an account of whole pages is read whole with no page past its end`() = runTest {
        listOf(100L, 300L).forEach { count ->
            val watch = Watch()
            val api = FakeApi(this) { account(it, (1L..count).map(::other)) }
            torbox(api, watch).prepare(pack, pickEpisode2)
            assertEquals(listOf(pack to "5021337"), watch.said, "$count entries")
            assertEquals((count / 100).toInt(), pagesAsked(api).size, "$count entries")
        }
    }

    @Test
    fun `no list is read when no watch wants records`() = runTest {
        listOf(Watch(wanted = false), null).forEach { watch ->
            val api = FakeApi(this) { account(it, listOf(other(1))) }
            assertEquals("5021337", torbox(api, watch).prepare(pack, pickEpisode2).handle)
            assertEquals(listOf("torrents/checkcached", "torrents/createtorrent", "torrents/mylist"), api.requests.map { it.call() })
            assertTrue(watch?.said.orEmpty().isEmpty())
        }
    }

    /**
     * EDL-1: a list read a page at a time can move between its pages (an entry another app or the user deletes, one added):
     * each page begins with the entry the one before ended on, so a move across a page's start is seen, and a list seen
     * moving records nothing. A held torrent never slips between two pages to be taken for this device's add.
     */
    @Test
    fun `a list that moves while its pages are read records nothing, and a held torrent never slips between its pages`() = runTest {
        // The held entry's place among 150 others, and what happens to the list once its first page has been read.
        val shapes = listOf<Triple<String, Int, (MutableList<JsonObject>) -> Unit>>(
            Triple("an earlier entry deleted, the held one moving back past where the first page ended", 100, { list -> list.removeAt(0) }),
            Triple("an earlier entry deleted, the held one moving onto the next page's start", 101, { list -> list.removeAt(0) }),
            Triple("an entry added at the front", 140, { list -> list.add(0, other(9_000)) }),
        )
        shapes.forEach { (shape, at, move) ->
            lines.clear()
            val listed = (1L..150L).map(::other).toMutableList().apply { add(at, entry(5021337)) }
            var pages = 0
            val watch = Watch()
            val api = FakeApi(this) { request ->
                val answer = account(request, listed.toList())
                if (request.call() == "torrents/mylist" && request.url.parameters["id"] == null && ++pages == 1) move(listed)
                answer
            }
            val prepared = torbox(api, watch).prepare(pack, pickEpisode2)
            assertEquals("5021337", prepared.handle, shape)
            assertEquals(emptyList(), watch.said, "$shape: the held entry never this device's add")
            assertTrue(lines.any { it.startsWith("debrid: TorBox mylist, whole: the list changed while it was read, the add not recorded, ") }, "$shape: $lines")
            assertEquals(2, pagesAsked(api).size, shape)
        }
    }

    /**
     * EDL-1: the sweep's delete goes through the same client as a play's add: it waits until the list the add is decided by
     * has been read and the add made, so it never lands between that list's pages.
     */
    @Test
    fun `this client's own delete never lands between the pages an add is decided by`() = runTest {
        val listed = (1L..150L).map(::other)
        val firstPage = CompletableDeferred<Unit>()
        val api = FakeApi(this) { request ->
            if (request.call() == "torrents/mylist" && request.url.parameters["id"] == null && request.url.parameters["offset"] == "0") firstPage.await()
            if (request.call() == "torrents/controltorrent") json("""{"success":true,"error":null,"detail":"Torrent deleted.","data":null}""") else account(request, listed)
        }
        val watch = Watch()
        val service = torbox(api, watch)
        val add = async { service.prepare(pack, pickEpisode2) }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, pagesAsked(api).size, "the add's first page asked")
        val delete = async { service.remove("42") }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, api.requests.count { it.call() == "torrents/controltorrent" }, "not sent while the add's list is read")
        firstPage.complete(Unit)
        assertEquals("5021337", add.await().handle)
        delete.await()
        val calls = api.requests.map { it.call() }
        val deleted = calls.indexOf("torrents/controltorrent")
        assertTrue(deleted > calls.indexOf("torrents/createtorrent"), "$calls")
        assertTrue(api.requests.withIndex().filter { (_, it) -> it.call() == "torrents/mylist" && it.url.parameters["id"] == null }.all { it.index < deleted }, "$calls")
        assertEquals(listOf(pack to "5021337"), watch.said)
    }

    @Test
    fun `an entry the account still has under an alternative hash is the torrent's`() = runTest {
        val api = FakeApi(this) { account(it, listOf(entry(5021337, hash = "ab".repeat(20), alternatives = listOf(pack.hex)))) }
        val prepared = torbox(api, known = "5021337").prepare(pack, pickEpisode2)
        assertEquals("5021337", prepared.handle)
        assertEquals(listOf("torrents/checkcached", "torrents/mylist"), api.requests.map { it.call() }, "nothing added")
    }

    /**
     * EDL-2, ESW-1 (L232, L246): a client made with the app's watch deletes only while the watch still wants the account's
     * records, asked as the delete's turn comes: a delete that waited behind an add's whole list while Remove after
     * playing was switched off (or the key changed, or the account was signed out) is never sent.
     */
    @Test
    fun `a delete is sent only while its watch still wants the account's records, asked as its turn comes`() = runTest {
        val listed = (1L..150L).map(::other)
        val firstPage = CompletableDeferred<Unit>()
        val api = FakeApi(this) { request ->
            if (request.call() == "torrents/mylist" && request.url.parameters["id"] == null && request.url.parameters["offset"] == "0") firstPage.await()
            if (request.call() == "torrents/controltorrent") json("""{"success":true,"error":null,"detail":"Torrent deleted.","data":null}""") else account(request, listed)
        }
        val watch = Watch()
        val service = torbox(api, watch)
        val add = async { service.prepare(pack, pickEpisode2) }
        advanceTimeBy(1_000)
        runCurrent()
        val delete = async { service.remove("42") }
        advanceTimeBy(1_000)
        runCurrent()
        watch.wanted = false
        firstPage.complete(Unit)
        add.await()
        delete.await()
        assertEquals(0, api.requests.count { it.call() == "torrents/controltorrent" }, "${api.requests.map { it.call() }}")
        assertTrue(lines.any { it.startsWith("debrid: TorBox controltorrent: not asked, the account's adds no longer removed, ") }, "$lines")

        // Not wanted from the start: no call at all.
        val idle = FakeApi(this) { json("""{"success":true,"data":null}""") }
        torbox(idle, Watch(wanted = false)).remove("42")
        assertEquals(emptyList(), idle.requests.toList())
    }

    /** L232: one torrent by its id, from a type with no `all` (TorBox's `all` empties the account), its bytes pinned. */
    @Test
    fun `remove posts one torrent's delete as json, exactly these bytes, and success true is done`() = runTest {
        val api = FakeApi(this) { json("""{"success":true,"error":null,"detail":"Torrent deleted.","data":null}""") }
        torbox(api).remove("42")
        val request = api.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://api.torbox.app/v1/api/torrents/controltorrent", request.url.toString())
        assertEquals("Bearer $KEY", request.headers[HttpHeaders.Authorization])
        assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
        assertEquals("""{"torrent_id":42,"operation":"delete"}""", (request.body as TextContent).text)
        assertTrue(lines.single().startsWith("debrid: TorBox controltorrent: removed, "), "$lines")
    }

    @Test
    fun `remove sends nothing for an id that isn't a positive integer`() = runTest {
        val api = FakeApi(this) { json("""{"success":true,"data":null}""") }
        listOf("", " ", "abc", "0", "00", "-3", "+42", "4.2", " 42", "1e3", "all", "99999999999999999999").forEach { id ->
            assertFailsWith<IllegalArgumentException>(id) { torbox(api).remove(id) }
        }
        assertEquals(emptyList(), api.requests.toList())
    }

    /**
     * L232: only a 2xx with success true is done. Any other answer is checked against the whole list, as a second delete of
     * an id already gone answered HTTP 500 DATABASE_ERROR in the live check, so no code is taken as gone.
     */
    @Test
    fun `any other answer to a delete is done only when the whole list shows the id gone`() = runTest {
        val answers = listOf(
            HttpStatusCode.InternalServerError to error("DATABASE_ERROR", HttpStatusCode.InternalServerError),
            HttpStatusCode.OK to error(null, HttpStatusCode.OK),
            HttpStatusCode.NotFound to error("ITEM_NOT_FOUND", HttpStatusCode.NotFound),
            HttpStatusCode.BadGateway to "<html>bad gateway</html>",
        )
        answers.forEach { (status, body) ->
            listOf(listOf(other(1), other(43)) to true, listOf(other(1), other(42)) to false).forEach { (listed, gone) ->
                val api = FakeApi(this) { request -> if (request.call() == "torrents/controltorrent") json(body, status) else account(request, listed) }
                if (gone) {
                    torbox(api).remove("42")
                } else {
                    assertFailsWith<ServiceException>("$status, still listed") { torbox(api).remove("42") }
                }
                assertEquals(listOf("torrents/controltorrent", "torrents/mylist"), api.requests.map { it.call() }, "$status")
                assertEquals(1, api.requests.count { it.call() == "torrents/controltorrent" }, "never sent again")
            }
        }
    }

    /** ECS-1: a delete not confirmed, whose account's list then finds no torrents (TorBox's 404 for none): done. */
    @Test
    fun `a delete whose list then finds no torrents is done`() = runTest {
        val api = FakeApi(this) { request ->
            if (request.call() == "torrents/controltorrent") json(error("DATABASE_ERROR", HttpStatusCode.InternalServerError), HttpStatusCode.InternalServerError) else account(request, emptyList())
        }
        torbox(api).remove("42")
        assertEquals(listOf("torrents/controltorrent", "torrents/mylist"), api.requests.map { it.call() })
        assertTrue(lines.any { it.startsWith("debrid: TorBox mylist, whole: 1 page, the torrent gone: removed, ") }, "$lines")
    }

    @Test
    fun `a failed delete whose list can't be read whole is thrown, and nothing is sent while the gate is closed`() = runTest {
        val api = FakeApi(this) { request ->
            when {
                request.call() == "torrents/controltorrent" -> json(error("DATABASE_ERROR", HttpStatusCode.InternalServerError), HttpStatusCode.InternalServerError)
                else -> json(error(null, HttpStatusCode.ServiceUnavailable), HttpStatusCode.ServiceUnavailable)
            }
        }
        assertEquals(ServiceError.Down, assertFailsWith<ServiceException> { torbox(api).remove("42") }.error)
        val past = FakeApi(this) { request ->
            if (request.call() == "torrents/controltorrent") json(error("DATABASE_ERROR", HttpStatusCode.InternalServerError), HttpStatusCode.InternalServerError)
            else account(request, (1L..1_001L).map(::other))
        }
        assertFailsWith<ServiceException> { torbox(past).remove("4242") }

        gate.record(ServiceError.KeyRefused)
        val closed = FakeApi(this) { json("""{"success":true,"data":null}""") }
        assertEquals(ServiceError.KeyRefused, assertFailsWith<ServiceException> { torbox(closed).remove("42") }.error)
        assertEquals(emptyList(), closed.requests.toList())
    }

    /** L234: a link of a torrent the account no longer has adds it again, under the same rule, and hands out what changed. */
    @Test
    fun `a link the account no longer has adds the torrent again and hands out the new handle and file ids with it`() = runTest {
        lateinit var api: FakeApi
        val watch = Watch { api.requests.map { it.call() } }
        api = FakeApi(this) { request ->
            if (request.call() == "torrents/requestdl" && request.url.parameters["torrent_id"] == "5021337") {
                json(error("ITEM_NOT_FOUND", HttpStatusCode.NotFound), HttpStatusCode.NotFound)
            } else {
                account(request, listOf(other(1)), created = 6000001, added = entry(6000001, fileIdsBy = 10))
            }
        }
        val prepared = stored()
        val made = torbox(api, watch).link(prepared, prepared.picked!!)

        assertEquals(link, made.url)
        val again = assertNotNull(made.prepared, "the changed listing handed out")
        assertEquals("6000001", again.handle)
        assertEquals(ServiceFile(2, episode2, 1468006401, id = "13"), again.picked)
        assertEquals(listOf("11", "12", "13", "14"), again.listing.files.map { it.id })
        assertEquals(pack, again.hash)
        assertEquals(listOf(pack to "6000001"), watch.said)
        assertEquals(listOf("torrents/requestdl", "torrents/mylist", "torrents/createtorrent", "torrents/mylist", "torrents/requestdl"), api.requests.map { it.call() })
        val readd = api.requests.single { it.call() == "torrents/createtorrent" }
        assertEquals("true", readd.form()["add_only_if_cached"])
        assertEquals(mapOf("token" to listOf(KEY), "torrent_id" to listOf("6000001"), "file_id" to listOf("13"), "redirect" to listOf("false")), api.requests.last().page())

        // A link that works hands out nothing changed.
        val fresh = FakeApi(this) { torbox(it) }
        assertNull(torbox(fresh, watch).link(prepared, prepared.picked!!).prepared)
    }

    @Test
    fun `a re-add finds the hash held by another entry, or no longer cached, or the link refused again`() = runTest {
        val prepared = stored()
        fun MockRequestHandleScope.gone(request: HttpRequestData) =
            if (request.call() == "torrents/requestdl" && request.url.parameters["torrent_id"] == "5021337") json(error("ITEM_NOT_FOUND", HttpStatusCode.NotFound), HttpStatusCode.NotFound) else null

        // Held by an entry another device's play added since: that entry, nothing added, nothing said.
        val watch = Watch()
        val held = FakeApi(this) { request -> gone(request) ?: account(request, listOf(other(1), entry(7000001))) }
        val made = torbox(held, watch).link(prepared, prepared.picked!!)
        assertEquals("7000001", made.prepared?.handle)
        assertEquals(emptyList(), watch.said)
        assertEquals(0, held.requests.count { it.call() == "torrents/createtorrent" })

        // No longer cached: the add refused, so the link is not cached.
        val uncached = FakeApi(this) { request ->
            gone(request) ?: if (request.call() == "torrents/createtorrent") json(error("DOWNLOAD_NOT_CACHED", HttpStatusCode.NotFound), HttpStatusCode.NotFound) else account(request, emptyList())
        }
        assertEquals(ServiceError.NotCached, assertFailsWith<ServiceException> { torbox(uncached, Watch()).link(prepared, prepared.picked!!) }.error)

        // Refused again after the re-add: one re-add, then not cached.
        val again = FakeApi(this) { request ->
            if (request.call() == "torrents/requestdl") json(error("ITEM_NOT_FOUND", HttpStatusCode.NotFound), HttpStatusCode.NotFound) else account(request, emptyList(), created = 6000001)
        }
        assertEquals(ServiceError.NotCached, assertFailsWith<ServiceException> { torbox(again, Watch()).link(prepared, prepared.picked!!) }.error)
        assertEquals(1, again.requests.count { it.call() == "torrents/createtorrent" })
        assertEquals(2, again.requests.count { it.call() == "torrents/requestdl" })

        // Another refusal isn't a re-add.
        val refused = FakeApi(this) { request ->
            if (request.call() == "torrents/requestdl") json(error("BAD_TOKEN", HttpStatusCode.Forbidden), HttpStatusCode.Forbidden) else account(request, emptyList())
        }
        gate.reset()
        assertEquals(ServiceError.KeyRefused, assertFailsWith<ServiceException> { torbox(refused, Watch()).link(prepared, prepared.picked!!) }.error)
        assertEquals(listOf("torrents/requestdl"), refused.requests.map { it.call() })
    }

    /** L232: one line per call, naming no id or hash. */
    @Test
    fun `the whole list, the delete and the re-add log no id and no hash`() = runTest {
        val watch = Watch()
        val api = FakeApi(this) { request ->
            when {
                request.call() == "torrents/controltorrent" -> json(error("DATABASE_ERROR", HttpStatusCode.InternalServerError), HttpStatusCode.InternalServerError)
                request.call() == "torrents/requestdl" && request.url.parameters["torrent_id"] == "5021337" -> json(error("ITEM_NOT_FOUND", HttpStatusCode.NotFound), HttpStatusCode.NotFound)
                else -> account(request, listOf(other(7000009)), created = 6000001, added = entry(6000001, fileIdsBy = 10))
            }
        }
        val service = torbox(api, watch)
        service.prepare(pack, pickEpisode2)
        val stored = stored()
        assertEquals("6000001", service.link(stored, stored.picked!!).prepared?.handle)
        service.remove("6000001")
        assertTrue(lines.size >= 6, "$lines")
        lines.forEach { line ->
            assertTrue(line.startsWith("debrid: TorBox "), line)
            listOf("5021337", "6000001", "7000009", pack.hex, KEY, "LINKSECRET77").forEach { assertFalse(it in line, "\"$it\" in: $line") }
        }
        assertTrue(lines.any { it.startsWith("debrid: TorBox controltorrent: DATABASE_ERROR") }, "$lines")
        assertTrue(lines.any { it.startsWith("debrid: TorBox mylist, whole: 1 page, the torrent gone: removed, ") }, "$lines")
    }

    private companion object {
        const val KEY = "tbk-INVENTED-0123456789abcdef"

        /** TorBox's answer to a list read that finds no torrents, as its collection's Get Torrent List example gives it (HTTP 404). */
        const val NO_TORRENTS = """{"success": true, "error": "ITEM_NOT_FOUND", "detail": "No torrents found for this user.", "data": null}"""

        /** The pack's files as checkcached lists them, in their order. */
        val PACK_PATHS = listOf(
            "The.Glass.Orchard.S02.1080p.WEB-DL.H.264-KESTREL/Sample/sample.mkv",
            "The.Glass.Orchard.S02.1080p.WEB-DL.H.264-KESTREL/The.Glass.Orchard.S02E01.1080p.WEB-DL.H.264-KESTREL.mkv",
            "The.Glass.Orchard.S02.1080p.WEB-DL.H.264-KESTREL/The.Glass.Orchard.S02E02.1080p.WEB-DL.H.264-KESTREL.mkv",
            "The.Glass.Orchard.S02.1080p.WEB-DL.H.264-KESTREL/The.Glass.Orchard.S02E03.1080p.WEB-DL.H.264-KESTREL.mkv",
        )
        val PACK_SIZES = listOf(31457280L, 1468006400L, 1468006401L, 1468006402L)
    }
}
