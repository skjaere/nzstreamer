package io.skjaere.usenet.mockseed

import io.skjaere.usenet.debrid.AddWatch
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.ServiceError
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceHttp
import io.skjaere.usenet.debrid.premiumize.PremiumizeService
import io.skjaere.usenet.debrid.torbox.TorBoxService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The debrid fake (L189, items 15 and 31): TorBox- and Premiumize-shaped API answers over [FakeTorrents], read here as raw
 * HTTP and JSON, as the documented shapes are the point, and the Range CDN behind its links with its faults. Its clock is
 * the real one, moved on by a test where a link's age or a lease is the point ([skew]), so no case waits for them.
 */
class FakeDebridServerTest {
    @TempDir
    lateinit var media: File

    private val video by lazy { File(media, "sample.mkv").apply { writeBytes(ByteArray(123_456) { it.toByte() }) } }
    private val lines = mutableListOf<String>()

    /** How far a case has moved the fake's clock on past the real one, in milliseconds. */
    private val skew = AtomicLong()
    private val server by lazy {
        video
        FakeDebridServer(TORBOX_KEY, PREMIUMIZE_KEY, media, port = 0, log = { lines += it }, now = { System.nanoTime() / 1_000_000 + skew.get() })
    }
    private val http = HttpClient.newHttpClient()

    @AfterEach
    fun stop() = server.close()

    private fun url(path: String) = URI("http://127.0.0.1:${server.port}$path")

    private fun send(request: HttpRequest.Builder): HttpResponse<String> = http.send(request.build(), HttpResponse.BodyHandlers.ofString())

    private fun get(path: String, bearer: String? = null): HttpResponse<String> =
        send(HttpRequest.newBuilder(url(path)).GET().apply { bearer?.let { header("Authorization", "Bearer $it") } })

    private fun post(path: String, body: String, type: String, bearer: String? = null): HttpResponse<String> =
        send(HttpRequest.newBuilder(url(path)).POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", type).apply { bearer?.let { header("Authorization", "Bearer $it") } })

    private fun torbox(path: String) = get("/torbox/v1/api/$path", TORBOX_KEY)

    private fun checkcached(hashes: List<String?>, query: String = "format=list&list_files=false") =
        post("/torbox/v1/api/torrents/checkcached?$query", hashes.joinToString(",", """{"hashes":[""", "]}") { "\"$it\"" }, "application/json", TORBOX_KEY)

    private fun createtorrent(magnet: String, onlyIfCached: Boolean = true): HttpResponse<String> {
        val boundary = "fakeboundary42"
        val fields = mapOf("magnet" to magnet, "add_only_if_cached" to onlyIfCached.toString(), "seed" to "3", "allow_zip" to "false", "as_queued" to "false")
        val body = fields.entries.joinToString("") { (name, value) -> "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n" } + "--$boundary--\r\n"
        return post("/torbox/v1/api/torrents/createtorrent", body, "multipart/form-data; boundary=$boundary", TORBOX_KEY)
    }

    private fun idOf(response: HttpResponse<String>) = Regex("\"torrent_id\":(\\d+)").find(response.body())!!.groupValues[1]

    private fun controltorrent(body: String) = post("/torbox/v1/api/torrents/controltorrent", body, "application/json", TORBOX_KEY)

    /** A delete as the app sends it (L232): one torrent's id and the operation, nothing else. */
    private fun delete(id: String) = controltorrent("""{"torrent_id":$id,"operation":"delete"}""")

    private fun requestdl(id: String, file: Int = 0) = get("/torbox/v1/api/torrents/requestdl?token=$TORBOX_KEY&torrent_id=$id&file_id=$file&redirect=false")

    /** The account's list without an id: every entry, or the page [query] asks for; none for TorBox's 404 for a list with none. */
    private fun entries(query: String = ""): List<JsonObject> {
        val answer = torbox("torrents/mylist?bypass_cache=true$query")
        if (answer.statusCode() == 404 && answer.body() == NO_TORRENTS) return emptyList()
        return Json.parseToJsonElement(answer.body()).jsonObject.getValue("data").jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.id() = getValue("id").jsonPrimitive.content

    /** A count of `/control/counts` by its path: "adds", "removals", "problems/<name>", "cdnFrom/<byte>" (0 when absent). */
    private fun count(path: String): Int {
        var at: JsonElement = Json.parseToJsonElement(counts())
        path.split('/').forEach { key -> at = (at as JsonObject)[key] ?: return 0 }
        return at.jsonPrimitive.int
    }

    private fun premiumize(path: String, form: List<Pair<String, String>>, bearer: String? = PREMIUMIZE_KEY) =
        post("/premiumize/api/$path", form.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }, "application/x-www-form-urlencoded", bearer)

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private fun xt(t: FakeTorrent) = "magnet:?xt=urn:btih:${t.hash}"
    private fun control(path: String) = get("/control/$path")
    private fun counts() = control("counts").body()

    @Test
    fun `torbox answers its account to the bearer key alone`() {
        val me = torbox("user/me")
        assertEquals(200, me.statusCode())
        assertContains(me.body(), "\"success\":true")
        assertContains(me.body(), "\"email\":\"quay.viewer@example.org\"")
        assertContains(me.body(), "\"plan\":3")
        assertContains(me.body(), "\"premium_expires_at\":\"2027-01-12T00:00:00Z\"")

        assertEquals(401, get("/torbox/v1/api/user/me").statusCode(), "no key")
        assertContains(get("/torbox/v1/api/user/me").body(), "\"error\":\"NO_AUTH\"")
        assertEquals(401, get("/torbox/v1/api/user/me?token=$TORBOX_KEY").statusCode(), "the key in the query is for requestdl only")
        // A key TorBox doesn't know: 401 BAD_TOKEN, as the live check found it (item 39).
        val wrong = get("/torbox/v1/api/user/me", "another-key")
        assertEquals(401, wrong.statusCode())
        assertContains(wrong.body(), "\"error\":\"BAD_TOKEN\"")
    }

    @Test
    fun `torbox's checkcached lists the cached ones among those asked, by post or get, with their files when asked`() {
        val list = checkcached(listOf(FakeTorrents.GOOD_S01E02.hash, FakeTorrents.HDTV_S01E02.hash))
        assertEquals(200, list.statusCode())
        assertContains(list.body(), "\"hash\":\"${FakeTorrents.GOOD_S01E02.hash}\"")
        assertFalse(FakeTorrents.HDTV_S01E02.hash!! in list.body(), "an uncached one is absent")
        assertFalse("\"files\"" in list.body(), "list_files=false")

        // Each file as the live check found TorBox listing it: its id, name, size and the rest.
        val withFiles = checkcached(listOf(FakeTorrents.GOOD_S01E02.hash), "format=list&list_files=true").body()
        val name = "${FakeTorrents.GOOD_S01E02.title}.mkv"
        assertContains(
            withFiles,
            """"files":[{"id":0,"name":"$name","size":${video.length()},"opensubtitles_hash":null,"short_name":"$name","mimetype":"video/x-matroska"}]""",
        )

        val byGet = torbox("torrents/checkcached?hash=${FakeTorrents.CROWN_WEB.hash},${FakeTorrents.CROWN_REMUX.hash}&format=object").body()
        assertContains(byGet, """"data":{"${FakeTorrents.CROWN_WEB.hash}":{""")
        assertFalse(FakeTorrents.CROWN_REMUX.hash!! in byGet)

        assertEquals("""{"success":true,"error":null,"detail":"Found 0 cached torrents.","data":[]}""", checkcached(listOf(FakeTorrents.HDTV_S01E01.hash)).body())
    }

    @Test
    fun `the pack lists its three episodes in their folder, and the rar'd one only its volumes`() {
        val pack = checkcached(listOf(FakeTorrents.PACK_S01.hash), "format=list&list_files=true").body()
        FakeTorrents.PACK_S01.files.forEach { assertContains(pack, "\"name\":\"$it\"") }
        assertEquals(3, Regex("\"name\":\"[^\"]*\\.mkv\"").findAll(pack).count())
        val rar = checkcached(listOf(FakeTorrents.CROWN_RAR.hash), "format=list&list_files=true").body()
        assertContains(rar, ".rar\"")
        assertContains(rar, ".r00\"")
        assertFalse(".mkv" in rar)
    }

    @Test
    fun `createtorrent adds a cached torrent once and refuses an uncached one when told to add only cached ones`() {
        val added = createtorrent(xt(FakeTorrents.PACK_S01))
        assertEquals(200, added.statusCode())
        val id = Regex("\"torrent_id\":(\\d+)").find(added.body())!!.groupValues[1]
        assertEquals(id, Regex("\"torrent_id\":(\\d+)").find(createtorrent(xt(FakeTorrents.PACK_S01)).body())!!.groupValues[1], "the same torrent")

        // As the live check found TorBox answering one it hasn't cached (item 39).
        val refused = createtorrent(xt(FakeTorrents.HDTV_S01E02))
        assertEquals(404, refused.statusCode())
        assertContains(refused.body(), "\"success\":false")
        assertContains(refused.body(), "\"error\":\"DOWNLOAD_NOT_CACHED\"")
        assertContains(refused.body(), "\"data\":null")

        // Without the flag an uncached torrent is queued, as a download that hasn't started: no files yet.
        val queued = Regex("\"torrent_id\":(\\d+)").find(createtorrent(xt(FakeTorrents.HDTV_S01E02), onlyIfCached = false).body())!!.groupValues[1]
        assertContains(torbox("torrents/mylist?id=$queued&bypass_cache=true").body(), "\"files\":[]")
    }

    @Test
    fun `mylist gives a torrent in the account with each file's id, name and size`() {
        val id = Regex("\"torrent_id\":(\\d+)").find(createtorrent(xt(FakeTorrents.PACK_S01)).body())!!.groupValues[1]
        val mylist = torbox("torrents/mylist?id=$id&bypass_cache=true")
        assertEquals(200, mylist.statusCode())
        val body = mylist.body()
        assertContains(body, "\"hash\":\"${FakeTorrents.PACK_S01.hash}\"")
        FakeTorrents.PACK_S01.files.forEachIndexed { i, path ->
            assertContains(body, """{"id":$i,"name":"$path","short_name":"${path.substringAfterLast('/')}","size":${video.length()}""")
        }
        // An id the account doesn't have, as the live check found TorBox answering it: 404, yet success true.
        val unknown = torbox("torrents/mylist?id=99999&bypass_cache=true")
        assertEquals(404, unknown.statusCode())
        assertContains(unknown.body(), "\"success\":true,\"error\":\"ITEM_NOT_FOUND\"")
        assertContains(unknown.body(), "\"data\":{}")
        assertContains(counts(), "\"mylist without bypass_cache\":0")
        torbox("torrents/mylist?id=$id")
        assertContains(counts(), "\"mylist without bypass_cache\":1")
    }

    @Test
    fun `requestdl gives a link on this server to the key in its query, never to a bearer header`() {
        val id = Regex("\"torrent_id\":(\\d+)").find(createtorrent(xt(FakeTorrents.CROWN_WEB)).body())!!.groupValues[1]
        val link = get("/torbox/v1/api/torrents/requestdl?token=$TORBOX_KEY&torrent_id=$id&file_id=0&redirect=false")
        assertEquals(200, link.statusCode())
        assertTrue(
            Regex(""""data":"http://127\.0\.0\.1:${server.port}/cdn/[0-9a-f]{32}/Copper\.Crown\.2019\.1080p\.WEB-DL\.DDP5\.1\.H\.264-TIDE\.mkv"""").containsMatchIn(link.body()),
            link.body(),
        )
        assertEquals(401, get("/torbox/v1/api/torrents/requestdl?torrent_id=$id&file_id=0&redirect=false", TORBOX_KEY).statusCode())
        assertEquals(401, get("/torbox/v1/api/torrents/requestdl?token=another-key&torrent_id=$id&file_id=0&redirect=false").statusCode())
        assertEquals(404, get("/torbox/v1/api/torrents/requestdl?token=$TORBOX_KEY&torrent_id=$id&file_id=7&redirect=false").statusCode())

        // The permalink (redirect=true) carries the key in a link a player keeps: refused, and counted.
        assertEquals(400, get("/torbox/v1/api/torrents/requestdl?token=$TORBOX_KEY&torrent_id=$id&file_id=0&redirect=true").statusCode())
        assertContains(counts(), "\"redirect=true\":1")
    }

    /**
     * As the live check found TorBox (item 39): requestdl a minute later gave the same link. The fake gives a file's link
     * again while it lives, and a new one once it has expired (every link made so far, or by its own time).
     */
    @Test
    fun `torbox's requestdl gives a file's link again while it lives, and a new one once it has expired`() {
        val id = Regex("\"torrent_id\":(\\d+)").find(createtorrent(xt(FakeTorrents.PACK_S01)).body())!!.groupValues[1]
        fun linkTo(file: Int) =
            Regex(""""data":"([^"]+)"""").find(get("/torbox/v1/api/torrents/requestdl?token=$TORBOX_KEY&torrent_id=$id&file_id=$file&redirect=false").body())!!.groupValues[1]
        val first = linkTo(0)
        assertEquals(first, linkTo(0), "the same link while it lives")
        assertFalse(first == linkTo(1), "another file's its own")

        control("cdn-expire")
        val second = linkTo(0)
        assertFalse(first == second, "a new one once every link made so far expired")
        assertEquals(206, cdn(second, "Range" to "bytes=0-").statusCode())

        control("cdn?expire=1")
        control("cdn-expire")
        val third = linkTo(0)
        assertEquals(third, linkTo(0))
        Thread.sleep(1_100)
        assertFalse(third == linkTo(0), "a new one once it expired by its own time")
        assertContains(counts(), "\"torbox requestdl\":7")
    }

    @Test
    fun `premiumize answers its account, the cache check and directdl as documented, errors with HTTP 200`() {
        val info = send(HttpRequest.newBuilder(url("/premiumize/api/account/info")).GET().header("Authorization", "Bearer $PREMIUMIZE_KEY"))
        assertContains(info.body(), "\"status\":\"success\"")
        // The customer id as a string, as the live check found Premiumize giving it.
        assertContains(info.body(), "\"customer_id\":\"900000042\"")
        assertContains(info.body(), "\"premium_until\":${Instant.parse("2027-01-12T00:00:00Z").epochSecond}")

        val check = premiumize("cache/check", listOf("items[]" to xt(FakeTorrents.GOOD_S01E02), "items[]" to xt(FakeTorrents.HDTV_S01E02), "items[]" to xt(FakeTorrents.PACK_S01)))
        assertContains(check.body(), "\"response\":[true,false,true]")

        val direct = premiumize("transfer/directdl", listOf("src" to xt(FakeTorrents.PACK_S01))).body()
        assertContains(direct, "\"status\":\"success\"")
        FakeTorrents.PACK_S01.files.forEach { path ->
            assertTrue(Regex("""\{"path":"${Regex.escape(path)}","size":${video.length()},"link":"http://127\.0\.0\.1:${server.port}/cdn/[0-9a-f]{32}/[^"]+"""").containsMatchIn(direct), direct)
        }

        val uncached = premiumize("transfer/directdl", listOf("src" to xt(FakeTorrents.CROWN_REMUX)))
        assertEquals(200, uncached.statusCode())
        assertContains(uncached.body(), "\"status\":\"error\"")
        assertContains(uncached.body(), "\"code\":\"not_found\"")
        // A hash that is no torrent at all, as the live check found Premiumize answering one.
        val noTorrent = premiumize("transfer/directdl", listOf("src" to "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"))
        assertEquals(200, noTorrent.statusCode())
        assertContains(noTorrent.body(), "\"code\":\"service_unsupported\"")

        val wrong = premiumize("account/info", emptyList(), bearer = "another-key")
        assertEquals(200, wrong.statusCode())
        assertContains(wrong.body(), "\"code\":\"authentication_failed\"")
        // The key in the query is legacy: never what the app sends (L198). Refused, and counted.
        val legacy = post("/premiumize/api/cache/check?apikey=$PREMIUMIZE_KEY", "items[]=${enc(xt(FakeTorrents.GOOD_S01E02))}", "application/x-www-form-urlencoded")
        assertContains(legacy.body(), "\"code\":\"authentication_failed\"")
        assertContains(counts(), "\"apikey in the query\":1")
    }

    @Test
    fun `one torrent goes away after its first link`() {
        val fades = FakeTorrents.CROWN_FADES
        val id = Regex("\"torrent_id\":(\\d+)").find(createtorrent(xt(fades)).body())!!.groupValues[1]
        val first = "/torbox/v1/api/torrents/requestdl?token=$TORBOX_KEY&torrent_id=$id&file_id=0&redirect=false"
        assertEquals(200, get(first).statusCode())
        assertEquals(404, get(first).statusCode())
        assertFalse(fades.hash!! in checkcached(listOf(fades.hash)).body())
        assertEquals(404, createtorrent(xt(fades)).statusCode())

        // On Premiumize, the first directdl gives the links; the next finds nothing.
        control("reset")
        assertContains(premiumize("transfer/directdl", listOf("src" to xt(fades))).body(), "\"status\":\"success\"")
        assertContains(premiumize("transfer/directdl", listOf("src" to xt(fades))).body(), "\"code\":\"not_found\"")
    }

    @Test
    fun `refused keys, a limit with or without a wait, down and slow are switched over the control path`() {
        control("refuse-keys?on=true")
        assertContains(torbox("user/me").body(), "\"error\":\"BAD_TOKEN\"")
        assertContains(premiumize("account/info", emptyList()).body(), "\"code\":\"authentication_failed\"")
        control("refuse-keys?on=false")
        assertEquals(200, torbox("user/me").statusCode())

        control("limit?on=true&wait=1200")
        val limited = torbox("user/me")
        assertEquals(429, limited.statusCode())
        assertEquals("1200", limited.headers().firstValue("Retry-After").orElse(null))
        val pmLimited = premiumize("cache/check", listOf("items[]" to xt(FakeTorrents.GOOD_S01E02)))
        assertContains(pmLimited.body(), "\"code\":\"account_limit_reached\"")
        assertEquals("1200", pmLimited.headers().firstValue("Retry-After").orElse(null))
        control("limit?on=true")
        assertContains(torbox("user/me").body(), "\"error\":\"MONTHLY_LIMIT\"")
        control("limit?on=false")

        control("down?on=true")
        assertEquals(503, torbox("user/me").statusCode())
        assertContains(premiumize("account/info", emptyList()).body(), "\"code\":\"service_down\"")
        control("down?on=false")

        control("slow?ms=300")
        val started = System.nanoTime()
        torbox("user/me")
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 300)
        control("reset")
        assertContains(torbox("user/me").body(), "\"success\":true")
    }

    /**
     * L199's device case: a cache check held over the control path (TorBox's checkcached, Premiumize's cache/check), so a
     * search's torrents stay "being checked" until it is let go; a reset lets it go too. Nothing else waits.
     */
    @Test
    fun `a held cache check answers once it is let go, and nothing else waits for it`() {
        assertEquals(200, control("hold-check?on=true").statusCode())
        val torbox = http.sendAsync(
            HttpRequest.newBuilder(url("/torbox/v1/api/torrents/checkcached?format=list&list_files=false"))
                .POST(HttpRequest.BodyPublishers.ofString("""{"hashes":["${FakeTorrents.GOOD_S01E02.hash}"]}"""))
                .header("Content-Type", "application/json").header("Authorization", "Bearer $TORBOX_KEY").build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val premiumize = http.sendAsync(
            HttpRequest.newBuilder(url("/premiumize/api/cache/check"))
                .POST(HttpRequest.BodyPublishers.ofString("items%5B%5D=${enc(xt(FakeTorrents.GOOD_S01E02))}"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Authorization", "Bearer $PREMIUMIZE_KEY").build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        Thread.sleep(500)
        assertFalse(torbox.isDone || premiumize.isDone, "a held check answered")
        assertEquals(200, torbox("user/me").statusCode(), "the account's answer waited too")
        assertEquals(200, control("hold-check?on=false").statusCode())
        assertContains(torbox.get().body(), FakeTorrents.GOOD_S01E02.title)
        assertContains(premiumize.get().body(), "\"response\":[true]")

        control("hold-check?on=true")
        val held = http.sendAsync(
            HttpRequest.newBuilder(url("/torbox/v1/api/torrents/checkcached?hash=${FakeTorrents.PACK_S01.hash}&format=list"))
                .GET().header("Authorization", "Bearer $TORBOX_KEY").build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        Thread.sleep(300)
        assertFalse(held.isDone)
        control("reset")
        assertEquals(200, held.get().statusCode())
    }

    /**
     * Its switches and counts are one set for every emulator reaching it, so a device test holds the fake while it runs
     * (a sharded run's two emulators take turns): another is refused until the holder gives it back, or the holder's
     * lease runs out (a test process that died). A reset leaves the holder as it is.
     */
    @Test
    fun `one test at a time holds the fake, until it gives it back or its lease runs out`() {
        video
        FakeDebridServer(TORBOX_KEY, PREMIUMIZE_KEY, media, port = 0, leaseMs = 400).use { fake ->
            fun ask(path: String) = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${fake.port}/control/$path")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()
            assertEquals(200, ask("take?by=phone34-test"))
            assertEquals(200, ask("take?by=phone34-test"), "its holder takes it again")
            assertEquals(409, ask("take?by=phone34b-test"))
            assertEquals(200, ask("reset"))
            assertEquals(200, ask("give-back?by=phone34b-test"))
            assertEquals(409, ask("take?by=phone34b-test"), "given back by one that didn't hold it")
            assertEquals(200, ask("give-back?by=phone34-test"))
            assertEquals(200, ask("take?by=phone34b-test"))
            Thread.sleep(600)
            assertEquals(200, ask("take?by=tv36-test"), "the lease ran out")
            assertEquals(400, ask("take"), "a holder names itself")
        }
    }

    @Test
    fun `every call is counted, and a magnet holding more than its hash is counted as such`() {
        torbox("user/me")
        checkcached(listOf(FakeTorrents.GOOD_S01E02.hash))
        createtorrent(xt(FakeTorrents.GOOD_S01E02))
        premiumize("cache/check", listOf("items[]" to xt(FakeTorrents.GOOD_S01E02)))
        premiumize("transfer/directdl", listOf("src" to xt(FakeTorrents.GOOD_S01E02)))
        val clean = counts()
        listOf("torbox user/me", "torbox checkcached", "torbox createtorrent", "premiumize cache/check", "premiumize transfer/directdl")
            .forEach { assertContains(clean, "\"$it\":1") }
        assertContains(clean, "\"magnet beyond its hash\":0")

        createtorrent(xt(FakeTorrents.GOOD_S01E02) + "&dn=Harbor.Lights&tr=udp%3A%2F%2Ftracker.example.org%3A6969")
        premiumize("cache/check", listOf("items[]" to xt(FakeTorrents.GOOD_S01E02) + "&tr=udp%3A%2F%2Ftracker.example.org%3A6969"))
        assertContains(counts(), "\"magnet beyond its hash\":2")

        control("reset")
        assertContains(counts(), "\"torbox user/me\":0")
    }

    @Test
    fun `no line it logs holds a key or a link's token`() {
        val id = Regex("\"torrent_id\":(\\d+)").find(createtorrent(xt(FakeTorrents.CROWN_WEB)).body())!!.groupValues[1]
        val link = get("/torbox/v1/api/torrents/requestdl?token=$TORBOX_KEY&torrent_id=$id&file_id=0&redirect=false").body()
        val token = Regex("/cdn/([0-9a-f]{32})/").find(link)!!.groupValues[1]
        premiumize("transfer/directdl", listOf("src" to xt(FakeTorrents.CROWN_WEB)))
        get("/torbox/v1/api/user/me", "another-key")
        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            assertFalse(TORBOX_KEY in line || PREMIUMIZE_KEY in line || "another-key" in line || token in line, line)
        }
    }

    @Test
    fun `the command serves on the loopback address with its keys and the media's video, and needs that video`() {
        debrid(mapOf("port" to "0", "torbox-key" to "tb-k", "premiumize-key" to "pm-k", "media" to media.path.also { video }), log = {}).use { fake ->
            assertTrue(fake.address.isLoopbackAddress)
            val me = send(HttpRequest.newBuilder(URI("http://127.0.0.1:${fake.port}/torbox/v1/api/user/me")).GET().header("Authorization", "Bearer tb-k"))
            assertEquals(200, me.statusCode())
        }
        val empty = File(media, "empty").apply { mkdirs() }
        assertFailsWith<IllegalArgumentException> { debrid(mapOf("port" to "0", "media" to empty.path), log = {}).close() }
    }

    /** The app's own TorBox client against the fake, as a device run will use them together (L189). */
    @Test
    fun `the app's torbox client checks, prepares and links against it, sending only what it should`() = runBlocking<Unit> {
        val torbox = TorBoxService(TORBOX_KEY, ServiceHttp.defaultHttpClient(), baseUrl = "http://127.0.0.1:${server.port}/torbox/v1/api", log = {})
        val good = InfoHash.of(FakeTorrents.GOOD_S01E02.hash!!)
        val uncached = InfoHash.of(FakeTorrents.HDTV_S01E02.hash!!)
        assertEquals(mapOf(good to true, uncached to false), torbox.check(listOf(good, uncached)))
        assertEquals("quay.viewer@example.org", torbox.test().name)

        val pack = InfoHash.of(FakeTorrents.PACK_S01.hash!!)
        val prepared = torbox.prepare(pack) { listing -> listing.files.single { "S01E02" in it.name } }
        assertEquals(FakeTorrents.PACK_S01.files[1], prepared.picked?.path)
        assertEquals(video.length(), prepared.picked?.size)
        val link = torbox.link(prepared, prepared.picked!!)
        assertTrue(link.url.startsWith("http://127.0.0.1:${server.port}/cdn/"), "a link on the fake")
        assertTrue(link.url.endsWith("/Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-TIDE.mkv"))

        // The same release again, with no listing store to say the account has it: added again, the torrent it has given.
        torbox.prepare(pack) { it.files.first() }.let { assertEquals(prepared.handle, it.handle) }
        val counts = counts()
        assertContains(counts, "\"torbox createtorrent\":2")
        listOf("magnet beyond its hash", "not a v1 hash", "mylist without bypass_cache", "redirect=true", "user_ip").forEach { assertContains(counts, "\"$it\":0") }
        assertEquals(ServiceError.NotCached, assertFailsWith<ServiceException> { torbox.prepare(uncached) { it.files.first() } }.error)
    }

    /**
     * D2's #318 (item 16) against the fake's account: the app's TorBox client says only an add the whole list didn't hold,
     * removes it, takes a second delete's DATABASE_ERROR as done once the list shows it gone, and adds it again for a link
     * the account refuses; an entry another app put there is used, never said and never removed.
     */
    @Test
    fun `the app's torbox client says only its own add, removes it, and adds it again for a link`() = runBlocking<Unit> {
        val said = mutableListOf<Pair<InfoHash, String>>()
        val watch = object : AddWatch {
            override suspend fun wanted() = true
            override suspend fun added(hash: InfoHash, handle: String) {
                said += hash to handle
            }
        }
        val torbox = TorBoxService(TORBOX_KEY, ServiceHttp.defaultHttpClient(), baseUrl = "http://127.0.0.1:${server.port}/torbox/v1/api", addWatch = watch, log = {})

        val planted = idOf(control("plant?hash=${FakeTorrents.CROWN_WEB.hash}"))
        val shared = torbox.prepare(InfoHash.of(FakeTorrents.CROWN_WEB.hash!!)) { it.files.first() }
        assertEquals(planted, shared.handle, "the shared list's entry")
        assertEquals(emptyList(), said)
        assertEquals(0, count("calls/torbox createtorrent"))

        val pack = InfoHash.of(FakeTorrents.PACK_S01.hash!!)
        val ours = torbox.prepare(pack) { it.files.first() }
        val handle = requireNotNull(ours.handle)
        assertEquals(listOf(pack to handle), said)
        assertEquals(1, count("adds"))

        torbox.remove(handle)
        torbox.remove(handle)
        assertEquals(1, count("removals"))
        assertEquals(listOf(planted), entries().map { it.id() }, "the planted entry stays")

        // As the live check found TorBox: the deleted entry still links. Refused, it is added again, and said again.
        assertNull(torbox.link(ours, ours.picked!!).prepared)
        control("deleted-links?on=false")
        val again = assertNotNull(torbox.link(ours, ours.picked!!).prepared)
        assertFalse(again.handle == handle)
        assertEquals(listOf(pack to handle, pack to again.handle!!), said)
        assertEquals(2, count("adds"))
        listOf("controltorrent naming all", "controltorrent without one id", "mylist without bypass_cache", "magnet beyond its hash").forEach { assertEquals(0, count("problems/$it"), it) }
    }

    /** The app's own Premiumize client against the fake: nothing added, the first link the listing's, a fresh one asked. */
    @Test
    fun `the app's premiumize client checks, prepares and links against it, sending only what it should`() = runBlocking<Unit> {
        val premiumize = PremiumizeService(PREMIUMIZE_KEY, ServiceHttp.defaultHttpClient(), baseUrl = "http://127.0.0.1:${server.port}/premiumize/api", log = {})
        val good = InfoHash.of(FakeTorrents.GOOD_S01E02.hash!!)
        val uncached = InfoHash.of(FakeTorrents.HDTV_S01E02.hash!!)
        assertEquals(mapOf(good to true, uncached to false), premiumize.check(listOf(good, uncached)))
        assertEquals("customer 900000042", premiumize.test().name)

        val pack = InfoHash.of(FakeTorrents.PACK_S01.hash!!)
        val prepared = premiumize.prepare(pack) { listing -> listing.files.single { "S01E03" in it.name } }
        assertEquals(FakeTorrents.PACK_S01.files[2], prepared.picked?.path)
        val first = premiumize.link(prepared, prepared.picked!!)
        val fresh = premiumize.link(prepared, prepared.picked!!)
        listOf(first, fresh).forEach { assertTrue(it.url.matches(Regex("http://127\\.0\\.0\\.1:${server.port}/cdn/[0-9a-f]{32}/Harbor\\.Lights\\.S01E03\\..*\\.mkv")), it.url) }
        assertFalse(first.url == fresh.url, "a fresh link")

        val counts = counts()
        assertContains(counts, "\"premiumize transfer/directdl\":2")
        listOf("magnet beyond its hash", "apikey in the query").forEach { assertContains(counts, "\"$it\":0") }
        assertEquals(ServiceError.NotCached, assertFailsWith<ServiceException> { premiumize.prepare(uncached) { it.files.first() } }.error)
    }

    // The account (#318, D2's item 15, L243): one entry per torrent, the delete, what is left after one, the shared list.

    /** As the live check found TorBox (L232): an add of a hash the account holds answers that entry, adding no second. */
    @Test
    fun `an add of a hash the account holds answers its entry, with no second one`() {
        val first = createtorrent(xt(FakeTorrents.PACK_S01))
        val again = createtorrent(xt(FakeTorrents.PACK_S01))
        assertEquals(200, again.statusCode())
        assertEquals(idOf(first), idOf(again))
        assertEquals(listOf(idOf(first)), entries().filter { it.getValue("hash").jsonPrimitive.content == FakeTorrents.PACK_S01.hash }.map { it.id() })
        assertEquals(1, count("adds"), "one new entry")
    }

    @Test
    fun `controltorrent deletes one torrent by its id, out of mylist, and a second delete of it is a database error`() {
        val id = idOf(createtorrent(xt(FakeTorrents.PACK_S01)))
        val kept = idOf(createtorrent(xt(FakeTorrents.CROWN_WEB)))
        val deleted = delete(id)
        assertEquals(200, deleted.statusCode())
        assertEquals("""{"success":true,"error":null,"detail":"Torrent deleted.","data":null}""", deleted.body())
        assertEquals(listOf(kept), entries().map { it.id() })
        assertEquals(404, torbox("torrents/mylist?id=$id&bypass_cache=true").statusCode())

        // As the live check found TorBox answering the same delete again: HTTP 500 DATABASE_ERROR, not ITEM_NOT_FOUND.
        val second = delete(id)
        assertEquals(500, second.statusCode())
        assertContains(second.body(), "\"success\":false,\"error\":\"DATABASE_ERROR\"")
        assertEquals(500, delete("99999").statusCode(), "an id the account never held")
        assertEquals(1, count("removals"))
        assertEquals(3, count("calls/torbox controltorrent"))
    }

    /** TorBox's `all` empties the account (L232): never what the app sends. Refused and counted, as is a delete without one id. */
    @Test
    fun `controltorrent refuses all, any other operation and a body without one id, counting what the app must never send`() {
        val id = idOf(createtorrent(xt(FakeTorrents.PACK_S01)))
        listOf("""{"torrent_id":$id,"operation":"delete","all":false}""", """{"operation":"delete","all":true}""").forEach { body ->
            val refused = controltorrent(body)
            assertEquals(400, refused.statusCode(), body)
            assertContains(refused.body(), "\"success\":false")
        }
        assertEquals(2, count("problems/controltorrent naming all"))
        listOf("""{"operation":"delete"}""", """{"torrent_id":0,"operation":"delete"}""", """{"torrent_id":-4,"operation":"delete"}""", """{"torrent_id":"$id","operation":"delete"}""").forEach { body ->
            assertEquals(400, controltorrent(body).statusCode(), body)
        }
        assertEquals(4, count("problems/controltorrent without one id"))
        listOf("pause", "resume", "reannounce").forEach { operation ->
            val other = controltorrent("""{"torrent_id":$id,"operation":"$operation"}""")
            assertEquals(400, other.statusCode(), operation)
            assertContains(other.body(), "\"error\":\"INVALID_OPTION\"")
        }
        assertEquals(listOf(id), entries().map { it.id() }, "nothing deleted")
        assertEquals(0, count("removals"))
    }

    /**
     * As the live check found TorBox after a delete (L235): `requestdl` of the deleted entry still made a link, and a link
     * made before kept serving. `/control/deleted-links?on=false` has `requestdl` refuse a deleted entry, for the re-add.
     */
    @Test
    fun `a deleted torrent's file still gets a link until told not to, and links made before keep serving`() {
        val id = idOf(createtorrent(xt(FakeTorrents.CROWN_WEB)))
        val before = Regex(""""data":"([^"]+)"""").find(requestdl(id).body())!!.groupValues[1]
        assertEquals(200, delete(id).statusCode())

        val after = requestdl(id)
        assertEquals(200, after.statusCode())
        assertTrue(Regex(""""data":"http://127\.0\.0\.1:${server.port}/cdn/[0-9a-f]{32}/[^"]+"""").containsMatchIn(after.body()), after.body())
        assertEquals(206, cdn(before, "Range" to "bytes=0-").statusCode(), "a link made before the delete")

        assertEquals(200, control("deleted-links?on=false").statusCode())
        val refused = requestdl(id)
        assertEquals(404, refused.statusCode())
        assertContains(refused.body(), "\"error\":\"ITEM_NOT_FOUND\"")
        assertEquals(206, cdn(before, "Range" to "bytes=0-").statusCode(), "still serving")
        control("deleted-links?on=true")
        assertEquals(200, requestdl(id).statusCode())
        control("reset")
        assertEquals(404, requestdl(id).statusCode(), "a reset forgets what was deleted")
    }

    /**
     * The shared list (L232, L243): an entry no play added, with an alternative hash if asked, as the live check found
     * entries holding one or two; an add of that alternative answers with the planted entry's id, adding nothing.
     */
    @Test
    fun `a planted entry is in the account though no play added it, and an add of its alternative hash answers its id`() {
        val planted = control("plant?hash=${FakeTorrents.CROWN_WEB.hash}&alt=$ALTERNATIVE")
        assertEquals(200, planted.statusCode())
        val id = idOf(planted)
        val entry = entries().single()
        assertEquals(id, entry.id())
        assertEquals(FakeTorrents.CROWN_WEB.hash, entry.getValue("hash").jsonPrimitive.content)
        assertEquals(listOf(ALTERNATIVE), entry.getValue("alternative_hashes").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Copper.Crown.2019.1080p.WEB-DL.DDP5.1.H.264-TIDE.mkv", entry.getValue("files").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(0, count("adds"), "planted, not added")

        assertEquals(id, idOf(createtorrent("magnet:?xt=urn:btih:$ALTERNATIVE")))
        assertEquals(id, idOf(createtorrent(xt(FakeTorrents.CROWN_WEB))))
        assertEquals(1, entries().size)
        assertEquals(0, count("adds"))
        assertEquals(200, requestdl(id).statusCode(), "it plays as any entry")

        // One without an alternative lists none; a hash the account holds isn't planted twice; a plant names a v1 hash.
        val bare = idOf(control("plant?hash=${FakeTorrents.PACK_S01.hash}"))
        assertEquals(emptyList(), entries().single { it.id() == bare }.getValue("alternative_hashes").jsonArray.toList())
        assertEquals(409, control("plant?hash=${FakeTorrents.PACK_S01.hash}").statusCode())
        assertEquals(409, control("plant?hash=$ALTERNATIVE").statusCode(), "held as an alternative")
        assertEquals(400, control("plant").statusCode())
        assertEquals(400, control("plant?hash=xyz").statusCode())
        assertEquals(400, control("plant?hash=${FakeTorrents.GOOD_S01E02.hash}&alt=xyz").statusCode())
        assertEquals(2, entries().size)
    }

    /** TorBox's `offset` and `limit` (OpenAPI 1.0.0; 1,000 by default), as the app reads a whole list a hundred at a time. */
    @Test
    fun `mylist without an id pages by offset and limit, each entry with its alternative hashes`() {
        val ids = listOf(FakeTorrents.GOOD_S01E02, FakeTorrents.PROPER_S01E01, FakeTorrents.PACK_S01).map { idOf(createtorrent(xt(it))) } +
            idOf(control("plant?hash=${FakeTorrents.CROWN_WEB.hash}&alt=$ALTERNATIVE"))
        assertEquals(ids, entries().map { it.id() })
        assertEquals(ids.take(2), entries("&offset=0&limit=2").map { it.id() })
        assertEquals(ids.drop(2), entries("&offset=2&limit=2").map { it.id() })
        // A page with no torrents, as TorBox's collection gives a list with none (ECS-1): 404 under success true.
        val past = torbox("torrents/mylist?bypass_cache=true&offset=4&limit=2")
        assertEquals(404, past.statusCode())
        assertEquals(NO_TORRENTS, past.body())
        assertEquals(ids.drop(1), entries("&offset=1").map { it.id() })
        assertEquals(ids.take(3), entries("&limit=3").map { it.id() })
        entries().forEach { assertTrue("alternative_hashes" in it, "$it") }
    }

    /**
     * EGX-1: the reads of the whole list (mylist without an id: each page of the app's provenance read) are counted apart
     * from the reads of one torrent, so a device test can say the app read the list before it would add.
     */
    @Test
    fun `the whole list's reads are counted apart from one torrent's`() {
        val id = idOf(createtorrent(xt(FakeTorrents.PACK_S01)))
        torbox("torrents/mylist?id=$id&bypass_cache=true")
        entries("&offset=0&limit=101")
        entries("&offset=100&limit=101")
        assertEquals(2, count("calls/torbox mylist, whole"), "a page with none counted too")
        assertEquals(3, count("calls/torbox mylist"))
        control("reset")
        assertContains(counts(), "\"torbox mylist, whole\":0")
    }

    /** #317: what a device test reads back, and a reset that leaves nothing of the test before. */
    @Test
    fun `the counts say the adds, the removals and where the cdn's answers began, and a reset clears them and the account`() {
        val pack = idOf(createtorrent(xt(FakeTorrents.PACK_S01)))
        createtorrent(xt(FakeTorrents.PACK_S01))
        val film = idOf(createtorrent(xt(FakeTorrents.CROWN_WEB)))
        createtorrent(xt(FakeTorrents.HDTV_S01E02))
        assertEquals(2, count("adds"), "two new entries; the second add of the pack and an uncached one add none")

        val link = Regex(""""data":"([^"]+)"""").find(requestdl(film).body())!!.groupValues[1]
        cdn(link, "Range" to "bytes=1000-")
        cdn(link, "Range" to "bytes=1000-1999")
        cdn(link)
        cdn(link, "Range" to "bytes=${video.length()}-")
        assertEquals(2, count("cdnFrom/1000"))
        assertEquals(1, count("cdnFrom/0"), "a whole file from its first byte")
        assertEquals(0, count("cdnFrom/${video.length()}"), "an answer with no body began nowhere")

        delete(pack)
        delete(pack)
        assertEquals(1, count("removals"))

        control("reset")
        assertEquals(0, count("adds"))
        assertEquals(0, count("removals"))
        assertEquals(0, count("cdnFrom/1000"))
        assertEquals(404, torbox("torrents/mylist?bypass_cache=true").statusCode(), "an account with no torrents")
        assertEquals(emptyList(), entries())
        assertEquals(idOf(createtorrent(xt(FakeTorrents.PACK_S01))), "1000", "the ids start again")
    }

    // The CDN behind the links (item 31): Range, a strong ETag, and faults per link.

    /** A fresh link to the film's video, as requestdl gives it. */
    private fun cdnLink(): String {
        val id = Regex("\"torrent_id\":(\\d+)").find(createtorrent(xt(FakeTorrents.CROWN_WEB)).body())!!.groupValues[1]
        val body = get("/torbox/v1/api/torrents/requestdl?token=$TORBOX_KEY&torrent_id=$id&file_id=0&redirect=false").body()
        return Regex(""""data":"([^"]+)"""").find(body)!!.groupValues[1]
    }

    /** A link to the film's video made now: Premiumize's directdl gives new ones with every answer, as the live check found. */
    private fun freshLink(): String =
        Regex(""""link":"([^"]+)"""").find(premiumize("transfer/directdl", listOf("src" to xt(FakeTorrents.CROWN_WEB))).body())!!.groupValues[1]

    private fun cdn(link: String, vararg headers: Pair<String, String>): HttpResponse<ByteArray> =
        http.send(HttpRequest.newBuilder(URI(link)).GET().apply { headers.forEach { (k, v) -> header(k, v) } }.build(), HttpResponse.BodyHandlers.ofByteArray())

    @Test
    fun `the cdn serves a link's file from where its range asks, with a strong etag, and whole without one`() {
        val link = cdnLink()
        val part = cdn(link, "Range" to "bytes=1000-")
        assertEquals(206, part.statusCode())
        assertEquals("bytes 1000-${video.length() - 1}/${video.length()}", part.headers().firstValue("Content-Range").get())
        assertEquals("${video.length() - 1000}", part.headers().firstValue("Content-Length").get())
        assertEquals("bytes", part.headers().firstValue("Accept-Ranges").get())
        assertEquals("video/x-matroska", part.headers().firstValue("Content-Type").get())
        val etag = part.headers().firstValue("ETag").get()
        assertTrue(etag.startsWith("\"") && etag.endsWith("\""), "a strong ETag: $etag")
        assertTrue(video.readBytes().copyOfRange(1000, video.length().toInt()).contentEquals(part.body()))

        val bounded = cdn(link, "Range" to "bytes=10-19")
        assertEquals(206, bounded.statusCode())
        assertEquals("bytes 10-19/${video.length()}", bounded.headers().firstValue("Content-Range").get())
        assertTrue(video.readBytes().copyOfRange(10, 20).contentEquals(bounded.body()))

        val whole = cdn(link)
        assertEquals(200, whole.statusCode())
        assertTrue(video.readBytes().contentEquals(whole.body()))
        // If-Range: the part while the file is the one the ETag names, else the whole of it.
        assertEquals(206, cdn(link, "Range" to "bytes=5-", "If-Range" to etag).statusCode())
        assertEquals(200, cdn(link, "Range" to "bytes=5-", "If-Range" to "\"another\"").statusCode())
        val past = cdn(link, "Range" to "bytes=${video.length()}-")
        assertEquals(416, past.statusCode())
        assertEquals("bytes */${video.length()}", past.headers().firstValue("Content-Range").get())
    }

    /**
     * Premiumize's links as the live check found its CDN serving them: from where the range asks (206, Content-Range), as
     * application/octet-stream, with no ETag and no Accept-Ranges.
     */
    @Test
    fun `premiumize's links are served from where the range asks, with no etag`() {
        val part = cdn(freshLink(), "Range" to "bytes=1000-")
        assertEquals(206, part.statusCode())
        assertEquals("bytes 1000-${video.length() - 1}/${video.length()}", part.headers().firstValue("Content-Range").get())
        assertEquals("application/octet-stream", part.headers().firstValue("Content-Type").get())
        assertFalse(part.headers().firstValue("ETag").isPresent, "no ETag")
        assertFalse(part.headers().firstValue("Accept-Ranges").isPresent, "ranges not said")
        assertTrue(video.readBytes().copyOfRange(1000, video.length().toInt()).contentEquals(part.body()))
    }

    @Test
    fun `the cdn refuses a bearer header and a link it never gave, and counts what it answered`() {
        val link = cdnLink()
        assertEquals(400, cdn(link, "Authorization" to "Bearer $TORBOX_KEY").statusCode())
        assertContains(counts(), "\"Authorization to the CDN\":1")
        assertEquals(404, cdn(link.replace(Regex("/cdn/[0-9a-f]{32}/"), "/cdn/0123456789abcdef0123456789abcdef/")).statusCode())
        cdn(link, "Range" to "bytes=0-")
        cdn(link)
        val counts = counts()
        assertContains(counts, "\"cdn\":{")
        assertContains(counts, "\"206\":1")
        assertContains(counts, "\"200\":1")
        assertContains(counts, "\"404\":1")
        control("reset")
        assertContains(counts(), "\"Authorization to the CDN\":0")
    }

    @Test
    fun `faults switched over the control path hold for the links made after, each its own`() {
        control("cdn?expire=1")
        val expiring = freshLink()
        assertEquals(206, cdn(expiring, "Range" to "bytes=0-").statusCode())
        control("cdn?whole=true")
        val whole = freshLink()
        control("cdn?page=true")
        val page = freshLink()
        control("cdn?cut=1000")
        val cut = freshLink()
        control("cdn?slow=100")
        val slow = freshLink()
        control("cdn")
        val healthy = freshLink()

        Thread.sleep(1_100)
        assertEquals(403, cdn(expiring, "Range" to "bytes=0-").statusCode(), "expired after its second")
        val all = cdn(whole, "Range" to "bytes=1000-")
        assertEquals(200, all.statusCode(), "a 200 to a range")
        assertEquals(video.length().toInt(), all.body().size)
        val html = cdn(page, "Range" to "bytes=1000-")
        assertEquals(200, html.statusCode())
        assertTrue(html.headers().firstValue("Content-Type").get().startsWith("text/html"))
        assertFailsWith<java.io.IOException>("cut after 1000 bytes") { cdn(cut, "Range" to "bytes=0-") }
        val started = System.nanoTime()
        assertEquals(50_000, cdn(slow, "Range" to "bytes=0-49999").body().size)
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 400, "50 kB at 100 kB/s")
        assertEquals(206, cdn(healthy, "Range" to "bytes=0-").statusCode())
        assertEquals(403, cdn(expiring, "Range" to "bytes=0-").statusCode(), "a link keeps its own")
    }

    /**
     * DEP-6 (L243): a CDN fault switched for one service holds for the links that service gives after it, the other
     * service's links as they were switched; with no service named, for both, as before.
     */
    @Test
    fun `a cdn fault switched for one service holds for that service's links alone`() {
        assertContains(control("cdn?service=premiumize&page=true").body(), "\"ok\":true")
        val page = cdn(freshLink(), "Range" to "bytes=1000-")
        assertEquals(200, page.statusCode())
        assertTrue(page.headers().firstValue("Content-Type").get().startsWith("text/html"), "Premiumize's link gives a page")
        assertEquals(206, cdn(cdnLink(), "Range" to "bytes=1000-").statusCode(), "TorBox's serves the file")

        // TorBox's switched now: Premiumize's keeps its own. (TorBox gives a file's link again while it lives: a new one is made.)
        control("cdn?service=torbox&whole=true")
        control("cdn-expire")
        val whole = cdn(cdnLink(), "Range" to "bytes=1000-")
        assertEquals(200, whole.statusCode(), "TorBox's link: a 200 to a range")
        assertEquals(video.length().toInt(), whole.body().size)
        assertTrue(cdn(freshLink(), "Range" to "bytes=1000-").headers().firstValue("Content-Type").get().startsWith("text/html"))

        // None named: both, as before.
        control("cdn")
        control("cdn-expire")
        assertEquals(206, cdn(freshLink(), "Range" to "bytes=1000-").statusCode())
        assertEquals(206, cdn(cdnLink(), "Range" to "bytes=1000-").statusCode())
        assertEquals(400, control("cdn?service=realdebrid&page=true").statusCode(), "a service the fake isn't")
    }

    /**
     * DEP-6 (L243): one call of one service failing, HTTP 500, as the service having stopped answering: TorBox's requestdl
     * here, every other call and the other service as they were; the app's own client reads it as the service down. A call
     * is named as the counts name it, without its service. Switched off by `on=false`, and by a reset.
     */
    @Test
    fun `a call's fault for one service answers HTTP 500 for that call alone, until switched off`() = runBlocking<Unit> {
        assertContains(control("fail?service=torbox&call=requestdl").body(), "\"ok\":true")
        val id = idOf(createtorrent(xt(FakeTorrents.CROWN_WEB)))
        assertEquals(200, torbox("torrents/mylist?bypass_cache=true&id=$id").statusCode())
        val failed = requestdl(id)
        assertEquals(500, failed.statusCode())
        assertContains(failed.body(), "\"success\":false")
        assertEquals(1, count("calls/torbox requestdl"), "counted as asked")
        assertContains(premiumize("transfer/directdl", listOf("src" to xt(FakeTorrents.CROWN_WEB))).body(), "\"status\":\"success\"")

        val torbox = TorBoxService(TORBOX_KEY, ServiceHttp.defaultHttpClient(), baseUrl = "http://127.0.0.1:${server.port}/torbox/v1/api", log = {})
        val prepared = torbox.prepare(InfoHash.of(FakeTorrents.CROWN_WEB.hash!!)) { it.files.first() }
        assertEquals(ServiceError.Down, assertFailsWith<ServiceException> { torbox.link(prepared, prepared.picked!!) }.error)

        control("fail?service=premiumize&call=transfer/directdl")
        assertEquals(500, premiumize("transfer/directdl", listOf("src" to xt(FakeTorrents.CROWN_WEB))).statusCode())
        assertContains(premiumize("cache/check", listOf("items[]" to xt(FakeTorrents.CROWN_WEB))).body(), "\"response\":[true]")
        control("fail?service=torbox&call=requestdl&on=false")
        assertEquals(200, requestdl(id).statusCode())
        assertEquals(500, premiumize("transfer/directdl", listOf("src" to xt(FakeTorrents.CROWN_WEB))).statusCode(), "Premiumize's still fails")
        control("reset")
        assertContains(premiumize("transfer/directdl", listOf("src" to xt(FakeTorrents.CROWN_WEB))).body(), "\"status\":\"success\"")
        assertEquals(400, control("fail?service=torbox&call=everything").statusCode(), "a call the service hasn't")
        assertEquals(400, control("fail?call=requestdl").statusCode(), "no service named")
    }

    /**
     * Item 27: a silence on the CDN (5-measure A5): the bodies flowing go quiet for its time and then go on from where they
     * were, while one begun after it comes at once.
     */
    @Test
    fun `a dip silences the bodies already flowing for its time, and not one begun after it`() {
        control("cdn?slow=100")
        val flowing = freshLink()
        val later = freshLink()
        val body = http.send(HttpRequest.newBuilder(URI(flowing)).header("Range", "bytes=0-").GET().build(), HttpResponse.BodyHandlers.ofInputStream()).body()
        val buffer = ByteArray(1_000)
        var total = 0
        // Flowing: 10 kB a tenth of a second.
        while (total < 20_000) total += body.read(buffer).also { assertTrue(it > 0) }
        assertContains(control("cdn-dip?silent=1500").body(), "\"ok\":true")
        val began = System.nanoTime()
        val fresh = cdn(later, "Range" to "bytes=0-9999")
        assertEquals(206, fresh.statusCode())
        assertTrue((System.nanoTime() - began) / 1_000_000 < 700, "a body begun after the dip isn't silenced")
        // The flowing one: what was on its way still comes, then nothing until the silence ends.
        var longest = 0L
        var last = System.nanoTime()
        while (true) {
            val n = body.read(buffer)
            if (n < 0) break
            val now = System.nanoTime()
            longest = maxOf(longest, now - last)
            last = now
            total += n
        }
        assertEquals(video.length().toInt(), total, "the whole body, from where it was")
        assertTrue(longest / 1_000_000 >= 1_200, "silent for the dip: the longest wait was ${longest / 1_000_000} ms")
    }

    @Test
    fun `every link made so far expires at once, and a file can go away from the cdn`() {
        val link = cdnLink()
        assertEquals(206, cdn(link, "Range" to "bytes=0-").statusCode())
        control("cdn-expire")
        assertEquals(403, cdn(link, "Range" to "bytes=0-").statusCode())
        val fresh = cdnLink()
        assertEquals(206, cdn(fresh, "Range" to "bytes=0-").statusCode(), "one made after is healthy")
        control("cdn-gone?on=true")
        assertEquals(404, cdn(fresh, "Range" to "bytes=0-").statusCode())
        assertEquals(404, cdn(cdnLink(), "Range" to "bytes=0-").statusCode())
        control("cdn-gone?on=false")
        assertEquals(206, cdn(fresh, "Range" to "bytes=0-").statusCode())
    }

    // The controls a debrid download's tests need (L419, L424, L476): a body closed at its link's expiry, the bodies open at
    // once, a part from another byte or with another total, one service's torrent gone, a longer hold, the sample's digest.

    /** [link]'s body from [range], as it begins to flow. */
    private fun body(link: String, range: String = "bytes=0-"): InputStream =
        http.send(HttpRequest.newBuilder(URI(link)).header("Range", range).GET().build(), HttpResponse.BodyHandlers.ofInputStream()).body()

    /** The first [n] bytes of [body]. */
    private fun readFirst(body: InputStream, n: Int) {
        var read = 0
        val buffer = ByteArray(1_000)
        while (read < n) read += body.read(buffer, 0, minOf(buffer.size, n - read)).also { assertTrue(it > 0, "the body ended at $read") }
    }

    /** What is left of [body]: how many bytes came before its end, and whether it ended closed short of its length. */
    private fun drain(body: InputStream): Pair<Int, Boolean> {
        val buffer = ByteArray(8_192)
        var read = 0
        return try {
            while (true) {
                val n = body.read(buffer)
                if (n < 0) break
                read += n
            }
            read to false
        } catch (e: IOException) {
            read to true
        }
    }

    /**
     * L419: a body whose link expires while it flows is closed there with `expireCuts=true`, as a CDN ending a body at its
     * link's expiry would: by the link's own time, and by every link expired at once. Without it a body flows on past its
     * link's expiry, as before: expiry is read as a request starts.
     */
    @Test
    fun `a body whose link expires while it flows is closed there with expireCuts, and flows on without it`() {
        // Paced (10 kB a tenth of a second), so the links expire while their bodies flow.
        control("cdn?expire=60&slow=100&expireCuts=true")
        val cuts = freshLink()
        control("cdn?expire=60&slow=100")
        val flows = freshLink()
        val cut = body(cuts)
        val whole = body(flows)
        readFirst(cut, 20_000)
        readFirst(whole, 20_000)
        skew.addAndGet(60_000)
        val (cutRest, closedShort) = drain(cut)
        assertTrue(closedShort, "closed short of its length")
        assertTrue(20_000 + cutRest < video.length(), "the body ended at ${20_000 + cutRest} of ${video.length()} bytes")
        assertEquals(video.length().toInt() to false, drain(whole).let { (rest, short) -> 20_000 + rest to short }, "flowed on past its link's expiry")
        listOf(cuts, flows).forEach { assertEquals(403, cdn(it, "Range" to "bytes=0-").statusCode(), "expired, asked again") }

        // Every link made so far expired at once (cdn-expire): a body flowing on one made with expireCuts is closed there too.
        control("cdn?slow=100&expireCuts=true")
        val flowing = body(freshLink())
        readFirst(flowing, 20_000)
        control("cdn-expire")
        val (rest, short) = drain(flowing)
        assertTrue(short && 20_000 + rest < video.length(), "closed at cdn-expire: ${20_000 + rest} bytes, short $short")
    }

    /**
     * L419's one connection: the counts say the CDN's bodies open now (`cdnOpen`) and the most open at once since the last
     * reset (`cdnOpenMax`). A body counts from its answer until its last bytes go, or until it stops short, so a reader
     * that opens its next body once it has had the last one's end never finds two open; an answer with no file in it
     * (a refusal, a page) is none.
     */
    @Test
    fun `the counts say the cdn's bodies open now and the most open at once since the reset`() {
        assertEquals(0, count("cdnOpen"))
        assertEquals(0, count("cdnOpenMax"))
        // One after another, each read to its end: never two at once.
        repeat(3) { cdn(freshLink(), "Range" to "bytes=1000-").also { assertEquals(206, it.statusCode()) } }
        assertEquals(0 to 1, count("cdnOpen") to count("cdnOpenMax"))
        control("cdn?cut=1000")
        val cut = freshLink()
        assertFailsWith<IOException>("cut short") { cdn(cut, "Range" to "bytes=0-") }
        control("cdn?page=true")
        assertEquals(200, cdn(freshLink(), "Range" to "bytes=0-").statusCode())
        control("cdn-expire")
        assertEquals(403, cdn(cut, "Range" to "bytes=0-").statusCode())
        assertEquals(0 to 1, count("cdnOpen") to count("cdnOpenMax"), "a body cut short is closed; a page and a refusal are none")

        // Two flowing at once.
        control("cdn?slow=100")
        val first = body(freshLink())
        val second = body(freshLink())
        readFirst(first, 10_000)
        readFirst(second, 10_000)
        assertEquals(2 to 2, count("cdnOpen") to count("cdnOpenMax"))
        listOf(first, second).forEach { assertEquals(video.length().toInt() - 10_000 to false, drain(it)) }
        assertEquals(0 to 2, count("cdnOpen") to count("cdnOpenMax"))

        // A reset starts the most from what is open then: none here.
        control("reset")
        assertEquals(0 to 0, count("cdnOpen") to count("cdnOpenMax"))
        cdn(freshLink(), "Range" to "bytes=0-")
        assertEquals(1, count("cdnOpenMax"))
    }

    /**
     * L419's 206 rule: `shift=<bytes>` answers a ranged read with a 206 from another byte, its Content-Range naming the
     * part sent; `total=<bytes>` names a total in the Content-Range that isn't the file's, the part's bytes and length as
     * asked. A read with no range is answered whole, as before; a link keeps the faults it was made with.
     */
    @Test
    fun `a part from another byte, or a content range with another total, as a link made after the switch keeps it`() {
        val size = video.length()
        val bytes = video.readBytes()
        val before = freshLink()
        control("cdn?shift=500")
        val shifted = freshLink()
        val part = cdn(shifted, "Range" to "bytes=1000-")
        assertEquals(206, part.statusCode())
        assertEquals("bytes 1500-${size - 1}/$size", part.headers().firstValue("Content-Range").get())
        assertEquals("${size - 1500}", part.headers().firstValue("Content-Length").get())
        assertTrue(bytes.copyOfRange(1500, size.toInt()).contentEquals(part.body()), "the part it names")
        assertEquals("bytes 510-519/$size", cdn(shifted, "Range" to "bytes=10-19").headers().firstValue("Content-Range").get())
        assertEquals(200, cdn(shifted).statusCode(), "no range: the whole file")
        assertEquals("bytes 1000-${size - 1}/$size", cdn(before, "Range" to "bytes=1000-").headers().firstValue("Content-Range").get(), "made before")

        control("cdn?shift=-500")
        val back = cdn(freshLink(), "Range" to "bytes=1000-")
        assertEquals("bytes 500-${size - 1}/$size", back.headers().firstValue("Content-Range").get())
        assertTrue(bytes.copyOfRange(500, size.toInt()).contentEquals(back.body()))

        control("cdn?total=999")
        val total = cdn(freshLink(), "Range" to "bytes=1000-")
        assertEquals(206, total.statusCode())
        assertEquals("bytes 1000-${size - 1}/999", total.headers().firstValue("Content-Range").get())
        assertEquals("${size - 1000}", total.headers().firstValue("Content-Length").get())
        assertTrue(bytes.copyOfRange(1000, size.toInt()).contentEquals(total.body()), "the part asked for")
        assertEquals(416, cdn(freshLink(), "Range" to "bytes=$size-").statusCode(), "past the end, as before")
        control("cdn")
        assertEquals("bytes 1000-${size - 1}/$size", cdn(freshLink(), "Range" to "bytes=1000-").headers().firstValue("Content-Range").get())
    }

    /**
     * L424's next account: `fade?hash=<hex>&service=torbox` takes a torrent from one service alone, out of its cache and
     * account, its links refused there as the first-link fade has them, while the other service still has it cached; with
     * no service named, from both. Links made before keep serving; a reset brings it back.
     */
    @Test
    fun `a torrent fades from one service alone, the other still giving it`() {
        val good = FakeTorrents.GOOD_S01E02
        val id = idOf(createtorrent(xt(good)))
        val before = Regex(""""data":"([^"]+)"""").find(requestdl(id).body())!!.groupValues[1]
        assertEquals(200, control("fade?hash=${good.hash}&service=torbox").statusCode())

        assertFalse(good.hash!! in checkcached(listOf(good.hash)).body(), "TorBox no longer has it cached")
        assertEquals(404, requestdl(id).statusCode(), "nor links it")
        assertEquals(404, torbox("torrents/mylist?id=$id&bypass_cache=true").statusCode())
        assertEquals(emptyList(), entries())
        assertContains(createtorrent(xt(good)).body(), "\"error\":\"DOWNLOAD_NOT_CACHED\"")
        assertContains(premiumize("cache/check", listOf("items[]" to xt(good))).body(), "\"response\":[true]")
        assertContains(premiumize("transfer/directdl", listOf("src" to xt(good))).body(), "\"status\":\"success\"")
        assertEquals(206, cdn(before, "Range" to "bytes=0-").statusCode(), "a link made before keeps serving")
        assertContains(checkcached(listOf(FakeTorrents.PACK_S01.hash)).body(), FakeTorrents.PACK_S01.hash!!, message = "another torrent as it was")

        control("fade?hash=${FakeTorrents.PACK_S01.hash}&service=premiumize")
        assertContains(premiumize("cache/check", listOf("items[]" to xt(FakeTorrents.PACK_S01))).body(), "\"response\":[false]")
        assertContains(premiumize("transfer/directdl", listOf("src" to xt(FakeTorrents.PACK_S01))).body(), "\"code\":\"not_found\"")
        assertContains(checkcached(listOf(FakeTorrents.PACK_S01.hash)).body(), FakeTorrents.PACK_S01.hash!!)

        control("fade?hash=${FakeTorrents.CROWN_WEB.hash}")
        assertFalse(FakeTorrents.CROWN_WEB.hash!! in checkcached(listOf(FakeTorrents.CROWN_WEB.hash)).body())
        assertContains(premiumize("cache/check", listOf("items[]" to xt(FakeTorrents.CROWN_WEB))).body(), "\"response\":[false]")

        assertEquals(400, control("fade?hash=xyz&service=torbox").statusCode())
        assertEquals(400, control("fade?service=torbox").statusCode())
        assertEquals(400, control("fade?hash=${good.hash}&service=realdebrid").statusCode())
        control("reset")
        assertContains(checkcached(listOf(good.hash)).body(), good.hash!!)
        assertContains(premiumize("cache/check", listOf("items[]" to xt(FakeTorrents.PACK_S01))).body(), "\"response\":[true]")
    }

    /**
     * L476's pair holds the fake from its Start to its Check, longer than the lease a device test takes: `take?by=&ms=`
     * holds it that long, half an hour at most, so a pair that dies lets go. The hold given back ends it at once.
     */
    @Test
    fun `a hold asked for longer than the lease keeps the fake that long, and no longer`() {
        assertEquals(400, control("take?by=pair&ms=0").statusCode())
        assertEquals(400, control("take?by=pair&ms=-5").statusCode())
        assertEquals(400, control("take?by=pair&ms=soon").statusCode())
        assertEquals(400, control("take?by=pair&ms=${30 * 60_000 + 1}").statusCode(), "past half an hour")
        assertEquals(200, control("take?by=other").statusCode(), "nothing taken by those refused")
        control("give-back?by=other")

        assertEquals(200, control("take?by=pair&ms=${12 * 60_000}").statusCode())
        skew.addAndGet(5 * 60_000L + 1)
        assertEquals(409, control("take?by=other").statusCode(), "held past the lease a test takes")
        // A second short of its end (the real clock goes on too), then past it.
        skew.addAndGet(7 * 60_000L - 1_001)
        assertEquals(409, control("take?by=other").statusCode())
        skew.addAndGet(1_000)
        assertEquals(200, control("take?by=other").statusCode(), "its hold ran out")

        assertEquals(200, control("take?by=other&ms=${30 * 60_000}").statusCode(), "its holder takes it again, for longer")
        control("give-back?by=other")
        assertEquals(200, control("take?by=pair").statusCode(), "given back")
    }

    /** L476's Check compares the downloaded file with the sample every video file is: `/control/media` says its size and SHA-256. */
    @Test
    fun `the media control says the sample's size and sha-256`() {
        val media = Json.parseToJsonElement(control("media").body()).jsonObject
        assertEquals(video.length(), media.getValue("size").jsonPrimitive.content.toLong())
        val sha = MessageDigest.getInstance("SHA-256").digest(video.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(sha, media.getValue("sha256").jsonPrimitive.content)
    }

    private companion object {
        const val TORBOX_KEY = "tb-fake-key-0001"
        const val PREMIUMIZE_KEY = "pm-fake-key-0002"

        /** An invented alternative hash, as a v2 torrent's truncated one would be. */
        const val ALTERNATIVE = "5eed5eed5eed5eed5eed5eed5eed5eed5eed5eed"

        /** TorBox's answer to a list read that finds no torrents, as its collection's Get Torrent List example gives it (HTTP 404). */
        const val NO_TORRENTS = """{"success":true,"error":"ITEM_NOT_FOUND","detail":"No torrents found for this user.","data":null}"""
    }
}
