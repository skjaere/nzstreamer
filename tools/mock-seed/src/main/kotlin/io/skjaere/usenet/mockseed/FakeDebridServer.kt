package io.skjaere.usenet.mockseed

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The debrid fake (L189): a TorBox-shaped API at `/torbox/v1/api/…` and a Premiumize-shaped one at `/premiumize/api/…`,
 * over the torrents of [FakeTorrents], so the app's clients can be tried from an emulator (http://10.0.2.2:8097) with
 * invented keys and never a real service. Each answers as its service documents it: TorBox's envelope `{success, error,
 * detail, data}` with HTTP statuses, Premiumize's `{status: "error", code}` with HTTP 200, with the codes and statuses
 * the live check found the services giving (item 39: a key TorBox doesn't know is 401 BAD_TOKEN; a torrent it hasn't
 * cached, added only if cached, 404 DOWNLOAD_NOT_CACHED; an id `mylist` doesn't have, 404 ITEM_NOT_FOUND under
 * `success: true`; Premiumize's customer id is a string). Its links point at its own CDN, `/cdn/<token>/<name>`, which
 * serves the link's file from where a Range asks (206, `Content-Range`), the whole of it without one, and refuses a
 * request carrying an Authorization header (L172: never a key to a CDN); as the live check found the two CDNs, a TorBox
 * link's file comes with its type, `Accept-Ranges` and a strong `ETag` (`If-Range` honoured), a Premiumize link's as
 * `application/octet-stream` with neither. TorBox's `requestdl` gives a file's link again
 * while it lives, as the live check found it doing a minute later, and a new one once it has expired; Premiumize's
 * `directdl` gives new links with every answer. Each link keeps the CDN's faults as they were switched when it was made
 * (`/control/cdn?expire=<s>&whole=true&page=true&cut=<bytes>&slow=<kB/s>`: expired after that many seconds, then 403; a
 * 200 and the whole file to a ranged read; a page in place of the file; a body cut after that many bytes; served at that
 * pace), so a fresh link can be healthy where the one before failed; with `service=torbox` or `service=premiumize`, for
 * that service's links alone, the other's as they were (DEP-6: one service's CDN failing). `/control/cdn-expire` expires
 * every link made so far, and `/control/cdn-gone` makes every link answer 404, the file gone from the CDN. `/control/cdn-dip?silent=<ms>`
 * makes the bodies flowing go silent that long, a CDN's hiccup mid-play, then go on from where they were; one begun after
 * it isn't held (item 27).
 *
 * The TorBox account holds one entry per torrent, as the live check found it (D2's L232): an add of a hash it holds, as
 * an entry's hash or among its `alternative_hashes`, answers that entry and adds none. `controltorrent` deletes one entry
 * by its id and does nothing else (L243): a body naming `all`, which empties a real account, or without one positive id
 * is refused (400) and counted; an id the account doesn't hold answers HTTP 500 `DATABASE_ERROR`, as a second delete
 * got. After a delete, as the live check found TorBox (L235), `requestdl` of the deleted entry still makes a link, unless
 * `/control/deleted-links?on=false` (then 404 `ITEM_NOT_FOUND`, for a re-add), and links made before keep serving.
 * `/control/plant?hash=<hex>&alt=<hex>` puts in the account an entry no play added (a list shared with another app), with
 * an alternative hash if asked. `mylist` without an id pages by `offset` and `limit`, TorBox's 1,000 by default, and a
 * page with no torrents is answered as TorBox's collection documents a list with none: 404 `ITEM_NOT_FOUND` under
 * `success: true`, "No torrents found for this user.".
 *
 * Cached, in both services: [FakeTorrents.GOOD_S01E02], [FakeTorrents.PROPER_S01E01], the three-episode pack
 * [FakeTorrents.PACK_S01], the film [FakeTorrents.CROWN_WEB], [FakeTorrents.CROWN_RAR] (RAR volumes alone) and
 * [FakeTorrents.CROWN_FADES], which goes away once its first link is given. Every other torrent isn't. Every video file
 * is [media]'s `sample.mkv`, so its size is that file's.
 *
 * Faults are switched at `/control/…` (refused keys, a limit with or without a wait, down, slow, cache checks held until
 * let go; one call of one service answering HTTP 500, `fail?service=torbox&call=requestdl`, a call named as the counts
 * name it without its service, until `on=false`; `reset`), and
 * `/control/counts` says how often each call was made (the whole list's reads, `mylist` without an id, apart as well:
 * `torbox mylist, whole`) and how often a request carried what the app must never send (a
 * magnet with more than its hash, a key in Premiumize's query, TorBox's permalink, a delete naming `all`, ...), how many
 * entries adds made in the account (`adds`) and deletes took out (`removals`), and the CDN's answers by status and its
 * bodies by the byte each began at (`cdnFrom`); `reset` clears them, the account and what was deleted from it (#317).
 * Those are one set for every emulator reaching the fake, so a device test holds it while it runs (`take?by=<its id>`,
 * `give-back?by=`): another is refused (409) until it is given back or [leaseMs] after it was taken, so a run sharded
 * over two emulators takes turns and a test process that died lets go. It listens on the loopback address alone, and no
 * line it logs holds a key or a link's token. Development only.
 */
class FakeDebridServer(
    private val torboxKey: String,
    private val premiumizeKey: String,
    media: File,
    port: Int,
    private val log: (String) -> Unit = {},
    private val leaseMs: Long = LEASE_MS,
) : AutoCloseable {
    /** A cached torrent's files as the services list them: path and size. */
    private class Cached(val torrent: FakeTorrent, val files: List<Pair<String, Long>>) {
        val hash: String = requireNotNull(torrent.hash)
        val size: Long get() = files.sumOf { it.second }
    }

    /**
     * A torrent in the TorBox account: [files] empty while a queued download hasn't started; [alternatives], the other
     * hashes TorBox lists the entry under (`alternative_hashes`).
     */
    private class Added(val id: Int, val hash: String, val name: String, val files: List<Pair<String, Long>>, val alternatives: List<String> = emptyList()) {
        fun holds(asked: String) = hash == asked || asked in alternatives
    }

    private val video = File(media, VIDEO).also { require(it.isFile) { "${media.path} holds no $VIDEO: every torrent's video is that file" } }

    private val catalog: Map<String, Cached> = CACHED.associate { torrent ->
        val files = torrent.files.map { path ->
            path to when (path.substringAfterLast('.').lowercase()) {
                "mkv", "mp4" -> video.length()
                "nfo" -> 4_096L
                else -> RAR_VOLUME_BYTES
            }
        }
        requireNotNull(torrent.hash) to Cached(torrent, files)
    }

    /** Guards everything below. */
    private val lock = Any()
    private val calls = LinkedHashMap<String, Int>()
    private val problems = LinkedHashMap<String, Int>()
    private val faded = HashSet<String>()
    private val account = LinkedHashMap<Int, Added>()
    /** The entries deleted from the account, by id: `requestdl` still links them while [deletedLinks]. */
    private val deleted = HashMap<Int, Added>()
    private var nextId = FIRST_ID
    /** New entries an add made, and entries a delete took out. */
    private var adds = 0
    private var removals = 0
    /** The device test holding the fake, by the id it gave, and until when (System.nanoTime's milliseconds). */
    private var holder: String? = null
    private var heldUntil = 0L

    @Volatile private var refuseKeys = false
    @Volatile private var limited = false
    @Volatile private var limitWait: Long? = null
    @Volatile private var down = false
    @Volatile private var slowMs = 0L
    @Volatile private var deletedLinks = true
    /** While set, cache checks wait for it to be let go ([holdChecks]), [HOLD_MAX_MS] at most. */
    @Volatile private var checksHeld: java.util.concurrent.CountDownLatch? = null

    /** The CDN's faults, as a link made now keeps them. */
    private class CdnFaults(val expireMs: Long? = null, val whole: Boolean = false, val page: Boolean = false, val cutAfter: Long? = null, val slowKBps: Long? = null)

    /** Whose CDN a link is on: TorBox's says its ranges and gives a strong ETag, Premiumize's neither (item 39's live check). */
    private enum class Cdn { TorBox, Premiumize }

    /**
     * A link this fake gave: its file's [path], when it was made, whose [cdn] it is on, and the CDN's [faults] then;
     * [expired] once every link was.
     */
    private class CdnLink(val path: String, val madeAtMs: Long, val cdn: Cdn, val faults: CdnFaults) {
        @Volatile var expired = false

        /** Whether it has expired by [nowMs]: with every link made so far, or by its own time. */
        fun expiredAt(nowMs: Long) = expired || faults.expireMs?.let { nowMs - madeAtMs >= it } == true
    }

    /** Each CDN's faults, as a link made on it now keeps them. */
    @Volatile private var cdnFaults: Map<Cdn, CdnFaults> = Cdn.entries.associateWith { CdnFaults() }

    /** The calls failing with HTTP 500 (`fail`), as the counts name them: "torbox requestdl". */
    @Volatile private var failing: Set<String> = emptySet()

    @Volatile private var cdnGone = false

    /** A silence on the CDN: the bodies begun before [fromNs] send nothing until [untilNs] (System.nanoTime). */
    private class CdnDip(val fromNs: Long, val untilNs: Long)

    @Volatile private var cdnDip: CdnDip? = null

    /** Under [lock]: the links given, by token; how often the CDN answered each status; its bodies by the byte each began at. */
    private val cdnLinks = HashMap<String, CdnLink>()
    private val cdnAnswers = LinkedHashMap<String, Int>()
    private val cdnFrom = LinkedHashMap<String, Int>()
    /** Under [lock]: the token of the link TorBox gave last for each torrent's file, by torrent id and file id. */
    private val torboxLinks = HashMap<Pair<Int, Int>, String>()
    /** The video's strong ETag: its length and when it was written, as a file server's would be. */
    private val etag = "\"${video.length().toString(16)}-${video.lastModified().toString(16)}\""

    private val random = SecureRandom()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)

    val port: Int get() = server.address.port
    val address: InetAddress get() = server.address.address

    init {
        reset()
        server.createContext(TORBOX) { exchange -> exchange.use { torbox(it) } }
        server.createContext(PREMIUMIZE) { exchange -> exchange.use { premiumize(it) } }
        server.createContext("/control/") { exchange -> exchange.use { control(it) } }
        // Closed by itself: a body it cuts short can't be closed as a whole one.
        server.createContext("/cdn/") { exchange -> cdn(exchange) }
        // A slow answer mustn't hold the control path or another call up.
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    override fun close() {
        server.stop(0)
        (server.executor as? ExecutorService)?.shutdownNow()
    }

    // TorBox (L197): Bearer on every call but requestdl, which takes the key as token=.

    private fun torbox(exchange: HttpExchange) {
        val path = exchange.requestURI.path.removePrefix(TORBOX)
        val query = queryOf(exchange.requestURI.rawQuery)
        val call = TORBOX_CALLS[path] ?: return exchange.torboxError(404, "ENDPOINT_NOT_FOUND", "No such endpoint.")
        count(call)
        pause()
        if (down) return exchange.torboxError(503, null, "The fake is down.")
        if (call in failing) return exchange.torboxError(500, null, "The fake failed this call.")
        val key = if (path == "torrents/requestdl") query["token"] else bearer(exchange)
        if (key == null) return exchange.torboxError(401, "NO_AUTH", "No API key was given.")
        if (key != torboxKey || refuseKeys) return exchange.torboxError(401, "BAD_TOKEN", "The API key is invalid.")
        if (limited) {
            val wait = limitWait
            return if (wait != null) {
                exchange.torboxError(429, "COOLDOWN_LIMIT", "Too many requests.", mapOf("Retry-After" to wait.toString()))
            } else {
                exchange.torboxError(403, "MONTHLY_LIMIT", "The monthly limit was reached.")
            }
        }
        when (path) {
            "user/me" -> exchange.torboxData(
                mapOf(
                    "id" to 4242, "email" to ACCOUNT_EMAIL, "plan" to 3, "is_subscribed" to true,
                    "premium_expires_at" to PLAN_UNTIL.toString(), "cooldown_until" to null, "total_downloaded" to 0,
                ),
            )
            "torrents/checkcached" -> {
                waitIfHeld()
                checkcached(exchange, query)
            }
            "torrents/createtorrent" -> createtorrent(exchange)
            "torrents/mylist" -> mylist(exchange, query)
            "torrents/requestdl" -> requestdl(exchange, query)
            "torrents/controltorrent" -> controltorrent(exchange)
        }
    }

    private fun checkcached(exchange: HttpExchange, query: Map<String, String>) {
        val asked = if (exchange.requestMethod == "POST") {
            Regex("\"hashes\"\\s*:\\s*\\[([^]]*)]").find(exchange.body())?.groupValues?.get(1)
                ?.let { list -> Regex("\"([^\"]*)\"").findAll(list).map { it.groupValues[1] }.toList() }.orEmpty()
        } else {
            query["hash"]?.split(',').orEmpty()
        }
        asked.filterNot { HASH.matches(it) }.forEach { _ -> problem(NOT_A_HASH) }
        val files = query["list_files"] == "true"
        val found = asked.mapNotNull { cached(it) }.distinctBy { it.hash }
        val entries = found.map { c ->
            c.hash to linkedMapOf<String, Any?>("name" to c.torrent.title, "size" to c.size, "hash" to c.hash).apply {
                if (files) {
                    put(
                        "files",
                        c.files.mapIndexed { i, (path, size) ->
                            linkedMapOf("id" to i, "name" to path, "size" to size, "opensubtitles_hash" to null, "short_name" to path.substringAfterLast('/'), "mimetype" to mimeOf(path))
                        },
                    )
                }
            }
        }
        val data: Any = if (query["format"] == "list") entries.map { it.second } else entries.toMap()
        log("debrid fake: torbox checkcached: ${found.size} of ${asked.size} cached")
        exchange.torboxData(data, "Found ${found.size} cached torrents.")
    }

    private fun createtorrent(exchange: HttpExchange) {
        val form = multipartOf(exchange)
        val hash = hashOfMagnet(form["magnet"]) ?: return exchange.torboxError(400, "MISSING_REQUIRED_OPTION", "A magnet is required.")
        val cached = cached(hash)
        val added = synchronized(lock) {
            // One entry per torrent (L232): a hash the account holds, as an entry's own or an alternative, is that entry.
            account.values.firstOrNull { it.holds(hash) && it.hash !in faded }
                ?: when {
                    cached != null -> Added(nextId++, hash, cached.torrent.title, cached.files)
                    form["add_only_if_cached"] == "true" -> null
                    // Queued as a download that hasn't started: the fake downloads nothing, so it never gets files.
                    else -> Added(nextId++, hash, hash, emptyList())
                }?.also {
                    account[it.id] = it
                    adds++
                }
        }
        if (added == null) {
            log("debrid fake: torbox createtorrent: not cached, not added")
            return exchange.torboxError(404, "DOWNLOAD_NOT_CACHED", "The torrent isn't cached.")
        }
        log("debrid fake: torbox createtorrent: torrent ${added.id}")
        exchange.torboxData(mapOf("hash" to added.hash, "torrent_id" to added.id, "auth_id" to "fake-auth"), "Found Cached Torrent. Using Cached Download.")
    }

    private fun mylist(exchange: HttpExchange, query: Map<String, String>) {
        if (query["bypass_cache"] != "true") problem(MYLIST_CACHED)
        val listed = synchronized(lock) { account.values.filter { it.hash !in faded } }
        val id = query["id"]
        if (id == null) {
            count(MYLIST_WHOLE)
            // A page of the list, as TorBox's offset and limit ask: from the first entry, and 1,000 of them by default.
            val offset = query["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val limit = query["limit"]?.toIntOrNull()?.coerceAtLeast(0) ?: MYLIST_LIMIT
            val page = listed.drop(offset).take(limit)
            // None there: as TorBox's collection gives a list with no torrents (D2's ECS-1), not an empty array.
            if (page.isEmpty()) return exchange.torboxError(404, "ITEM_NOT_FOUND", "No torrents found for this user.", success = true)
            return exchange.torboxData(page.map(::listing))
        }
        // Not there: 404, yet under success true and an empty object, as the live check found TorBox answering.
        val torrent = listed.firstOrNull { it.id.toString() == id }
            ?: return exchange.torboxError(404, "ITEM_NOT_FOUND", "No such torrent.", success = true, data = emptyMap<String, Any?>())
        exchange.torboxData(listing(torrent))
    }

    private fun listing(torrent: Added) = linkedMapOf(
        "id" to torrent.id, "hash" to torrent.hash, "alternative_hashes" to torrent.alternatives, "name" to torrent.name,
        "size" to torrent.files.sumOf { it.second },
        "download_finished" to torrent.files.isNotEmpty(), "download_present" to torrent.files.isNotEmpty(),
        "files" to torrent.files.mapIndexed { i, (path, size) ->
            linkedMapOf("id" to i, "name" to path, "short_name" to path.substringAfterLast('/'), "size" to size, "mimetype" to mimeOf(path))
        },
    )

    private fun requestdl(exchange: HttpExchange, query: Map<String, String>) {
        if ("user_ip" in query) problem(USER_IP)
        // The permalink carries the key in a link a player would keep (L197): never what the app asks.
        if (query["redirect"] == "true") {
            problem(PERMALINK)
            return exchange.torboxError(400, "INVALID_OPTION", "This fake gives no permalink.")
        }
        val fileId = query["file_id"]?.toIntOrNull() ?: -1
        val (torrent, file) = synchronized(lock) {
            val id = query["torrent_id"]?.toIntOrNull()
            // A deleted entry's file still gets a link, as the live check found TorBox giving one after the delete (L235).
            val torrent = (account[id] ?: deleted[id]?.takeIf { deletedLinks })?.takeIf { it.hash !in faded }
            torrent to torrent?.files?.getOrNull(fileId)
        }
        if (torrent == null || file == null) return exchange.torboxError(404, "ITEM_NOT_FOUND", "No such file.")
        // The file's link again while it lives, as TorBox gave it a minute later in the live check; a new one once expired.
        val link = synchronized(lock) {
            torboxLinks[torrent.id to fileId]?.takeIf { cdnLinks[it]?.expiredAt(System.currentTimeMillis()) == false }
                ?: token(file.first, Cdn.TorBox).also { torboxLinks[torrent.id to fileId] = it }
        }.let { linkOf(exchange, it, file.first) }
        fadeIfDue(torrent.hash)
        log("debrid fake: torbox requestdl: link made")
        exchange.torboxData(link, "Download link generated.")
    }

    /**
     * Deletes one entry of the account by its id (L232, L243). A body naming `all` (which empties a real account) or
     * without one positive id is refused and counted; any operation but `delete` is refused; an id the account doesn't
     * hold answers HTTP 500 `DATABASE_ERROR`, as the live check found TorBox answering a second delete of the same id.
     */
    private fun controltorrent(exchange: HttpExchange) {
        val body = exchange.body()
        if (CONTROL_ALL_FIELD.containsMatchIn(body)) {
            problem(CONTROL_ALL)
            return exchange.torboxError(400, "INVALID_OPTION", "This fake never deletes every torrent.")
        }
        val id = CONTROL_ID_FIELD.find(body)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        if (id == null) {
            problem(CONTROL_NO_ID)
            return exchange.torboxError(400, "MISSING_REQUIRED_OPTION", "A torrent id is required.")
        }
        if (CONTROL_OPERATION_FIELD.find(body)?.groupValues?.get(1) != "delete") {
            return exchange.torboxError(400, "INVALID_OPTION", "This fake only deletes.")
        }
        val gone = synchronized(lock) {
            account.remove(id)?.also {
                deleted[id] = it
                removals++
            }
        }
        if (gone == null) return exchange.torboxError(500, "DATABASE_ERROR", "Something went wrong.")
        log("debrid fake: torbox controltorrent: torrent $id deleted")
        exchange.torboxData(null, "Torrent deleted.")
    }

    private fun HttpExchange.torboxData(data: Any?, detail: String = "OK.") =
        reply(json(linkedMapOf("success" to true, "error" to null, "detail" to detail, "data" to data)), "application/json")

    private fun HttpExchange.torboxError(
        status: Int,
        error: String?,
        detail: String,
        headers: Map<String, String> = emptyMap(),
        success: Boolean = false,
        data: Any? = null,
    ) {
        log("debrid fake: torbox ${requestURI.path.removePrefix(TORBOX)}: HTTP $status ${error ?: ""}".trimEnd())
        reply(json(linkedMapOf("success" to success, "error" to error, "detail" to detail, "data" to data)), "application/json", status, headers)
    }

    // Premiumize (L198): Bearer, never apikey=; every error an HTTP 200 with a code.

    private fun premiumize(exchange: HttpExchange) {
        val path = exchange.requestURI.path.removePrefix(PREMIUMIZE)
        val call = PREMIUMIZE_CALLS[path] ?: return exchange.premiumizeError("invalid_request", "No such endpoint.")
        count(call)
        pause()
        val form = queryPairs(exchange.requestURI.rawQuery) + if (exchange.requestMethod == "POST") queryPairs(exchange.body()) else emptyList()
        if (down) return exchange.premiumizeError("service_down", "The fake is down.")
        if (call in failing) {
            log("debrid fake: premiumize $path: HTTP 500")
            return exchange.reply(json(linkedMapOf("status" to "error", "message" to "The fake failed this call.")), "application/json", 500)
        }
        if (form.any { it.first == "apikey" }) {
            problem(APIKEY_PARAM)
            return exchange.premiumizeError("authentication_failed", "Not logged in.")
        }
        if (bearer(exchange) != premiumizeKey || refuseKeys) return exchange.premiumizeError("authentication_failed", "Not logged in.")
        if (limited) {
            return exchange.premiumizeError("account_limit_reached", "The account's limit was reached.", limitWait?.let { mapOf("Retry-After" to it.toString()) }.orEmpty())
        }
        when (path) {
            // The customer id as a string, as the live check found it.
            "account/info" -> exchange.premiumizeData(
                linkedMapOf("customer_id" to CUSTOMER_ID.toString(), "premium_until" to PLAN_UNTIL.epochSecond, "limit_used" to 0.02, "space_used" to 0),
            )
            "cache/check" -> {
                waitIfHeld()
                val items = form.filter { it.first == "items[]" }.map { cached(hashOfMagnet(it.second)) }
                log("debrid fake: premiumize cache/check: ${items.count { it != null }} of ${items.size} cached")
                exchange.premiumizeData(
                    linkedMapOf(
                        "response" to items.map { it != null }, "transcoded" to items.map { false },
                        "filename" to items.map { it?.torrent?.title ?: "" }, "filesize" to items.map { it?.size?.toString() ?: "" },
                    ),
                )
            }
            "transfer/directdl" -> {
                val hash = hashOfMagnet(form.lastOrNull { it.first == "src" }?.second)
                // A hash that is no torrent at all, as the live check found Premiumize answering one; an uncached torrent's
                // answer is the fake's own choice.
                if (FakeTorrents.all.none { it.hash == hash }) return exchange.premiumizeError("service_unsupported", "This source is not supported.")
                val cached = cached(hash) ?: return exchange.premiumizeError("not_found", "The file isn't cached.")
                val content = cached.files.map { (path, size) ->
                    linkedMapOf("path" to path, "size" to size, "link" to link(exchange, path, Cdn.Premiumize), "stream_link" to null, "transcode_status" to "not_applicable")
                }
                fadeIfDue(cached.hash)
                log("debrid fake: premiumize directdl: ${content.size} files")
                exchange.premiumizeData(linkedMapOf("location" to content.first()["link"], "filename" to cached.torrent.title, "filesize" to cached.size, "content" to content))
            }
        }
    }

    private fun HttpExchange.premiumizeData(fields: Map<String, Any?>) =
        reply(json(linkedMapOf<String, Any?>("status" to "success") + fields), "application/json")

    private fun HttpExchange.premiumizeError(code: String, message: String, headers: Map<String, String> = emptyMap()) {
        log("debrid fake: premiumize ${requestURI.path.removePrefix(PREMIUMIZE)}: $code")
        reply(json(linkedMapOf("status" to "error", "code" to code, "message" to message)), "application/json", 200, headers)
    }

    // The control path: faults, counts and a reset.

    private fun control(exchange: HttpExchange) {
        val query = queryOf(exchange.requestURI.rawQuery)
        val on = query["on"] == "true"
        when (exchange.requestURI.path.removePrefix("/control/")) {
            "refuse-keys" -> refuseKeys = on
            "limit" -> {
                limitWait = query["wait"]?.toLongOrNull()?.takeIf { on }
                limited = on
            }
            "down" -> down = on
            "deleted-links" -> deletedLinks = on
            "plant" -> return plant(exchange, query)
            "slow" -> slowMs = query["ms"]?.toLongOrNull() ?: 0
            "hold-check" -> holdChecks(on)
            "cdn" -> {
                val faults = CdnFaults(
                    expireMs = query["expire"]?.toLongOrNull()?.let { it * 1_000 }, whole = query["whole"] == "true", page = query["page"] == "true",
                    cutAfter = query["cut"]?.toLongOrNull(), slowKBps = query["slow"]?.toLongOrNull()?.takeIf { it > 0 },
                )
                // One service's links, the other's as they were; with none named, both.
                val cdns = query["service"]?.let { name -> listOf(Cdn.entries.firstOrNull { it.name.lowercase() == name } ?: return exchange.reply(NO_SUCH_SERVICE, "application/json", 400)) }
                    ?: Cdn.entries
                cdnFaults = cdnFaults + cdns.associateWith { faults }
            }
            "fail" -> {
                val call = "${query["service"]} ${query["call"]}"
                if (call !in TORBOX_CALLS.values && call !in PREMIUMIZE_CALLS.values) {
                    return exchange.reply("""{"error":"service= (torbox or premiumize) and call= (as the counts name it) name no call"}""", "application/json", 400)
                }
                failing = if (query["on"] == "false") failing - call else failing + call
            }
            "cdn-expire" -> synchronized(lock) { cdnLinks.values.forEach { it.expired = true } }
            "cdn-gone" -> cdnGone = on
            "cdn-dip" -> cdnDip = query["silent"]?.toLongOrNull()?.takeIf { it > 0 }?.let { ms ->
                val now = System.nanoTime()
                CdnDip(now, now + ms * 1_000_000)
            }
            "reset" -> reset()
            "take" -> {
                val by = query["by"]?.takeIf { it.isNotEmpty() } ?: return exchange.reply("""{"error":"by= names the test taking it"}""", "application/json", 400)
                if (!take(by)) return exchange.reply("""{"held":true}""", "application/json", 409)
            }
            "give-back" -> synchronized(lock) { if (holder == query["by"]) holder = null }
            "counts" -> return exchange.reply(
                synchronized(lock) {
                    json(
                        linkedMapOf(
                            "calls" to LinkedHashMap(calls), "problems" to LinkedHashMap(problems), "adds" to adds, "removals" to removals,
                            "cdn" to LinkedHashMap(cdnAnswers), "cdnFrom" to LinkedHashMap(cdnFrom),
                        ),
                    )
                },
                "application/json",
            )
            else -> return exchange.reply("""{"error":"no such control"}""", "application/json", 404)
        }
        log("debrid fake: control ${exchange.requestURI.path.removePrefix("/control/")}")
        exchange.reply("""{"ok":true}""", "application/json")
    }

    /**
     * Puts in the account an entry no play added, as a list shared with another app holds one (L232): the torrent of
     * `hash=`, with its files where the fake has it cached, and `alt=` among its alternative hashes if given; its id is
     * answered. A torrent the account holds isn't planted again (409): TorBox keeps one entry per torrent.
     */
    private fun plant(exchange: HttpExchange, query: Map<String, String>) {
        val hash = query["hash"]?.lowercase()?.takeIf { HASH.matches(it) }
        val alternative = query["alt"]?.lowercase()
        if (hash == null || (alternative != null && !HASH.matches(alternative))) {
            return exchange.reply("""{"error":"hash= (and alt=) take a v1 hash in 40 hex digits"}""", "application/json", 400)
        }
        val planted = synchronized(lock) {
            if (account.values.any { it.holds(hash) || (alternative != null && it.holds(alternative)) }) return@synchronized null
            val cached = catalog[hash]
            Added(nextId++, hash, cached?.torrent?.title ?: hash, cached?.files.orEmpty(), listOfNotNull(alternative)).also { account[it.id] = it }
        } ?: return exchange.reply("""{"error":"the account holds that torrent"}""", "application/json", 409)
        log("debrid fake: control plant: torrent ${planted.id}")
        exchange.reply(json(linkedMapOf("ok" to true, "torrent_id" to planted.id)), "application/json")
    }

    /** Whether [by] holds the fake now: it was free, its lease over, or [by]'s already (taken again, its lease renewed). */
    private fun take(by: String): Boolean = synchronized(lock) {
        val now = System.nanoTime() / 1_000_000
        if (holder != null && holder != by && now < heldUntil) return false
        holder = by
        heldUntil = now + leaseMs
        true
    }

    /**
     * Holds every cache check (TorBox's checkcached, Premiumize's cache/check) until let go, so a device test sees a
     * search's torrents being checked (L199); letting go answers those held. Nothing else waits.
     */
    private fun holdChecks(on: Boolean) {
        if (on) {
            if (checksHeld == null) checksHeld = java.util.concurrent.CountDownLatch(1)
        } else {
            checksHeld?.countDown()
            checksHeld = null
        }
    }

    /** A cache check's wait while checks are held: until let go, [HOLD_MAX_MS] at most, so one left held can't hang a run. */
    private fun waitIfHeld() {
        checksHeld?.await(HOLD_MAX_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun reset() {
        holdChecks(false)
        refuseKeys = false
        limited = false
        limitWait = null
        down = false
        slowMs = 0
        deletedLinks = true
        cdnFaults = Cdn.entries.associateWith { CdnFaults() }
        failing = emptySet()
        cdnGone = false
        cdnDip = null
        synchronized(lock) {
            (TORBOX_CALLS.values + MYLIST_WHOLE + PREMIUMIZE_CALLS.values).forEach { calls[it] = 0 }
            listOf(MAGNET_BEYOND_HASH, NOT_A_HASH, MYLIST_CACHED, PERMALINK, USER_IP, APIKEY_PARAM, CDN_AUTHORIZATION, CONTROL_ALL, CONTROL_NO_ID)
                .forEach { problems[it] = 0 }
            CDN_STATUSES.forEach { cdnAnswers["$it"] = 0 }
            cdnFrom.clear()
            faded.clear()
            account.clear()
            deleted.clear()
            nextId = FIRST_ID
            adds = 0
            removals = 0
            cdnLinks.clear()
            torboxLinks.clear()
        }
    }

    // Shared.

    private fun cached(hash: String?): Cached? = hash?.let { h -> catalog[h]?.takeIf { synchronized(lock) { h !in faded } } }

    private fun fadeIfDue(hash: String) {
        if (hash == FakeTorrents.CROWN_FADES.hash) synchronized(lock) { faded += hash }
    }

    /** A link on this server for [path]'s file: its own random token, the file's name; never a key. It keeps the CDN's faults as they are now. */
    private fun link(exchange: HttpExchange, path: String, cdn: Cdn): String = linkOf(exchange, token(path, cdn), path)

    /** A new link's token for [path]'s file on [cdn], kept with the CDN's faults as they are now. */
    private fun token(path: String, cdn: Cdn): String {
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        synchronized(lock) { cdnLinks[token] = CdnLink(path, System.currentTimeMillis(), cdn, cdnFaults.getValue(cdn)) }
        return token
    }

    /** The link of [token] to [path]'s file, on the address the request reached. */
    private fun linkOf(exchange: HttpExchange, token: String, path: String): String {
        val host = exchange.requestHeaders.getFirst("Host") ?: "127.0.0.1:$port"
        return "http://$host/cdn/$token/${URLEncoder.encode(path.substringAfterLast('/'), Charsets.UTF_8).replace("+", "%20")}"
    }

    /** The v1 hash of a magnet as the app sends it, `magnet:?xt=urn:btih:<hex>` alone; one holding more is counted. */
    private fun hashOfMagnet(magnet: String?): String? {
        val text = magnet ?: return null
        if (!XT_ONLY.matches(text)) problem(MAGNET_BEYOND_HASH)
        return Regex("xt=urn:btih:([0-9a-fA-F]{40})").find(text)?.groupValues?.get(1)?.lowercase()
    }

    private fun bearer(exchange: HttpExchange): String? =
        exchange.requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")

    private fun count(call: String) = synchronized(lock) { calls[call] = (calls[call] ?: 0) + 1 }

    private fun problem(name: String) = synchronized(lock) { problems[name] = (problems[name] ?: 0) + 1 }

    private fun pause() = slowMs.takeIf { it > 0 }?.let(Thread::sleep)

    private fun HttpExchange.body(): String = requestBody.readBytes().toString(Charsets.UTF_8)

    /** A multipart form's fields, as TorBox's createtorrent takes them; files aren't read. */
    private fun multipartOf(exchange: HttpExchange): Map<String, String> {
        val boundary = exchange.requestHeaders.getFirst("Content-Type")?.substringAfter("boundary=", "")?.trim('"')
            ?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        return exchange.body().split("--$boundary").mapNotNull { part ->
            val name = Regex("name=\"([^\"]*)\"").find(part.substringBefore("\r\n\r\n"))?.groupValues?.get(1) ?: return@mapNotNull null
            name to part.substringAfter("\r\n\r\n", "").removeSuffix("\r\n")
        }.toMap()
    }

    private fun mimeOf(path: String) = if (path.endsWith(".mkv")) "video/x-matroska" else "application/octet-stream"

    // The CDN (L172): a link's file by its Range, its faults as the link keeps them.

    private fun cdn(exchange: HttpExchange) {
        try {
            serveCdn(exchange)
        } catch (e: java.io.IOException) {
            // The reader went (a seek closes its read), or a body was cut on purpose.
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun serveCdn(exchange: HttpExchange) {
        val token = exchange.requestURI.path.removePrefix("/cdn/").substringBefore('/')
        if (exchange.requestHeaders.getFirst("Authorization") != null) {
            problem(CDN_AUTHORIZATION)
            return exchange.cdnAnswer(400, "text/plain", "A CDN link takes no Authorization header.")
        }
        val link = synchronized(lock) { cdnLinks[token] }
        if (link == null || cdnGone || link.path.substringAfterLast('.').lowercase() !in VIDEOS) return exchange.cdnAnswer(404, "text/plain", "No such file.")
        val faults = link.faults
        if (link.expiredAt(System.currentTimeMillis())) {
            return exchange.cdnAnswer(403, "text/plain", "This link has expired.")
        }
        if (faults.page) return exchange.cdnAnswer(200, "text/html", "<html><body>Sign in to download</body></html>")
        val size = video.length()
        val range = exchange.requestHeaders.getFirst("Range")?.let { RANGE.matchEntire(it.trim()) }
        val validated = link.cdn == Cdn.TorBox
        // With no ETag given, no If-Range can name the file.
        val sameFile = exchange.requestHeaders.getFirst("If-Range")?.let { validated && it == etag } ?: true
        val headers = exchange.responseHeaders
        if (validated) {
            headers.add("Accept-Ranges", "bytes")
            headers.add("ETag", etag)
        }
        val type = if (validated) mimeOf(link.path) else "application/octet-stream"
        if (range == null || faults.whole || !sameFile) return exchange.cdnBody(200, 0, size, type, faults)
        val from = range.groupValues[1].toLong()
        if (from >= size) {
            headers.add("Content-Range", "bytes */$size")
            return exchange.cdnAnswer(416, "text/plain", "Out of range.")
        }
        val until = (range.groupValues[2].takeIf { it.isNotEmpty() }?.toLong()?.coerceAtMost(size - 1) ?: (size - 1)) + 1
        headers.add("Content-Range", "bytes $from-${until - 1}/$size")
        exchange.cdnBody(206, from, until, type, faults)
    }

    /** [from] to [until] of the video with [status], as [type], cut and paced as [faults] say, silent through a dip. */
    private fun HttpExchange.cdnBody(status: Int, from: Long, until: Long, type: String, faults: CdnFaults) {
        val began = System.nanoTime()
        cdnCount(status, from)
        responseHeaders.add("Content-Type", type)
        sendResponseHeaders(status, until - from)
        val end = faults.cutAfter?.let { minOf(until, from + it) } ?: until
        val chunk = faults.slowKBps?.let { (it * 100).toInt() } ?: (64 * 1024)
        java.io.RandomAccessFile(video, "r").use { file ->
            file.seek(from)
            val buffer = ByteArray(chunk)
            var at = from
            while (at < end) {
                silentThroughADip(began)
                val n = file.read(buffer, 0, minOf(chunk.toLong(), end - at).toInt())
                if (n <= 0) break
                responseBody.write(buffer, 0, n)
                responseBody.flush()
                at += n
                // A tenth of a second's worth at a time.
                if (faults.slowKBps != null) Thread.sleep(100)
            }
        }
    }

    /** Waits out a dip that came while the body begun at [began] was flowing. */
    private fun silentThroughADip(began: Long) {
        val dip = cdnDip ?: return
        if (began >= dip.fromNs) return
        val left = dip.untilNs - System.nanoTime()
        if (left > 0) Thread.sleep(left / 1_000_000 + 1)
    }

    private fun HttpExchange.cdnAnswer(status: Int, type: String, text: String) {
        cdnCount(status, null)
        reply(text, type, status)
    }

    /** One answer of the CDN, counted by its status and by the byte its body began at ([from]; null for an answer with no body). */
    private fun cdnCount(status: Int, from: Long?) {
        synchronized(lock) {
            cdnAnswers["$status"] = (cdnAnswers["$status"] ?: 0) + 1
            from?.let { cdnFrom["$it"] = (cdnFrom["$it"] ?: 0) + 1 }
        }
        log("debrid fake: cdn $status" + (from?.let { " from $it" } ?: ""))
    }

    companion object {
        private const val TORBOX = "/torbox/v1/api/"
        private const val PREMIUMIZE = "/premiumize/api/"
        private const val VIDEO = "sample.mkv"
        private const val RAR_VOLUME_BYTES = 50_000_000L
        private const val FIRST_ID = 1000
        /** `mylist`'s page without a `limit`, as TorBox's OpenAPI 1.0.0 gives it. */
        private const val MYLIST_LIMIT = 1000
        private const val LEASE_MS = 5 * 60_000L
        private const val HOLD_MAX_MS = 2 * 60_000L
        const val ACCOUNT_EMAIL = "quay.viewer@example.org"
        const val CUSTOMER_ID = 900000042L
        val PLAN_UNTIL: Instant = Instant.parse("2027-01-12T00:00:00Z")

        private val CACHED = with(FakeTorrents) { listOf(GOOD_S01E02, PROPER_S01E01, PACK_S01, CROWN_WEB, CROWN_RAR, CROWN_FADES) }

        private val TORBOX_CALLS = listOf("user/me", "torrents/checkcached", "torrents/createtorrent", "torrents/mylist", "torrents/requestdl", "torrents/controltorrent")
            .associateWith { "torbox ${it.removePrefix("torrents/")}" }
        private val PREMIUMIZE_CALLS = listOf("account/info", "cache/check", "transfer/directdl").associateWith { "premiumize $it" }

        /** The whole list's reads (`mylist` without an id), counted apart from the reads of one torrent too (D2's EGX-1). */
        private const val MYLIST_WHOLE = "torbox mylist, whole"

        // What the app must never send, counted (L159, L197, L198).
        private const val MAGNET_BEYOND_HASH = "magnet beyond its hash"
        private const val NOT_A_HASH = "not a v1 hash"
        private const val MYLIST_CACHED = "mylist without bypass_cache"
        private const val PERMALINK = "redirect=true"
        private const val USER_IP = "user_ip"
        private const val APIKEY_PARAM = "apikey in the query"
        private const val CDN_AUTHORIZATION = "Authorization to the CDN"
        /** TorBox's `all` deletes every torrent in the account (L232); a delete names one torrent, by its id. */
        private const val CONTROL_ALL = "controltorrent naming all"
        private const val CONTROL_NO_ID = "controltorrent without one id"

        /** controltorrent's JSON fields, read as the fake reads checkcached's: `all` named at all, a number for the id. */
        private val CONTROL_ALL_FIELD = Regex(""""all"\s*:""")
        private val CONTROL_ID_FIELD = Regex(""""torrent_id"\s*:\s*(-?\d+)\s*[,}]""")
        private val CONTROL_OPERATION_FIELD = Regex(""""operation"\s*:\s*"([^"]*)"""")

        private const val NO_SUCH_SERVICE = """{"error":"service= is torbox or premiumize"}"""

        /** What the CDN answers with, counted. */
        private val CDN_STATUSES = listOf(200, 206, 400, 403, 404, 416)
        private val VIDEOS = setOf("mkv", "mp4")
        private val RANGE = Regex("""bytes=(\d+)-(\d*)""")

        private val HASH = Regex("[0-9a-f]{40}")
        private val XT_ONLY = Regex("magnet:\\?xt=urn:btih:[0-9a-f]{40}")

        /** A form or query's fields in order, a name given twice kept twice (Premiumize's `items[]`). */
        private fun queryPairs(raw: String?): List<Pair<String, String>> =
            raw.orEmpty().split('&').filter { it.isNotEmpty() }.map { pair ->
                val (k, v) = (pair.split('=', limit = 2) + "").take(2)
                URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
            }

        /** [value] as JSON: maps (in their order), lists, strings, numbers, booleans and null. */
        private fun json(value: Any?): String = when (value) {
            null -> "null"
            is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> json(k.toString()) + ":" + json(v) }
            is List<*> -> value.joinToString(",", "[", "]") { json(it) }
            else -> json(value.toString())
        }
    }
}
