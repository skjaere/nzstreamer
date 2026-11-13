package io.skjaere.usenet.debrid.torbox

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.content.TextContent
import io.ktor.http.takeFrom
import io.skjaere.usenet.debrid.Account
import io.skjaere.usenet.debrid.AddWatch
import io.skjaere.usenet.debrid.Answer
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.Failed
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.debrid.ServiceCalls
import io.skjaere.usenet.debrid.ServiceError
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceGate
import io.skjaere.usenet.debrid.ServiceHttp
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.debrid.ServiceLink
import io.skjaere.usenet.debrid.ServiceListing
import io.skjaere.usenet.debrid.ServiceRateLimit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * TorBox (L197; checked against its OpenAPI 1.0.0 and SDK page, 2026-11-04): `Authorization: Bearer <key>` on every call
 * but `requestdl`, which takes the key as `token=` as TorBox requires it, and is never asked for its permalink
 * (`redirect=true`, which carries the key) or given `user_ip`.
 *
 * - [test]: `GET /user/me`, the account's e-mail address, its plan and when it ends.
 * - [check]: `POST /torrents/checkcached?format=list&list_files=false` with the hashes as JSON, at most [BATCH] a call; a
 *   hash absent from its `data` isn't cached. A server that refuses the POST (405) is asked by
 *   `GET ?hash=a,b,…&format=list` from then on, for this client's life (the app keeps one per account).
 * - [prepare]: `checkcached` again for the one hash with its files (the listing [prepare]'s pick chooses from, before
 *   anything is added, L179); then, unless the account still has it ([knownTorrent], confirmed by `mylist`), an add
 *   (below); then `GET /torrents/mylist?id=…&bypass_cache=true` for TorBox's own file ids, each matched by name and size,
 *   never fuzzily.
 * - An add (#318, L232): while [addWatch] wants records, the account's whole list first (`GET /torrents/mylist`, pages
 *   of [PAGE] by `offset` and `limit`, `bypass_cache=true`, at most [MAX_PAGES] pages within [WHOLE_WITHIN]; each page
 *   asked with the next one's first entry, which must begin that page, so a list that moves while it is read is seen).
 *   TorBox keeps one entry per torrent, so an entry listing the hash, as its own or among its `alternative_hashes`, is
 *   used as it is: nothing is added and nothing told. Else `POST /torrents/createtorrent` with the xt-only magnet, added
 *   only if cached, not seeded, not zipped, not queued; its answer's id is told to [addWatch], before anything else is
 *   asked of it, only when the list holds no entry of that id (TorBox may answer an alternative hash with the entry it
 *   holds). Unsure means not ours: a list that isn't read whole tells nothing, and the add goes on, its torrent staying
 *   in the account as every add did before D2. The read and the add, and each delete with the list read to confirm it,
 *   are this client's one at a time, so its own delete never lands between the pages an add is decided by.
 * - [link]: `GET /torrents/requestdl?token=…&torrent_id=…&file_id=…&redirect=false`: `data` is the CDN link, held in
 *   memory only. A torrent the account no longer has (L234) is added again, as a first add is, and asked again once.
 * - [remove]: `POST /torrents/controltorrent` with `{"torrent_id":<id>,"operation":"delete"}`, from a type that has no
 *   `all` (which deletes every torrent in the account); nothing is sent for a handle that isn't one positive integer,
 *   nor once [addWatch] no longer wants the account's records, asked as the delete's turn comes. Only a 2xx with
 *   `success: true` is done; any other answer is done only when the whole list, read then, no longer holds the id: a
 *   second delete of an id already gone answered HTTP 500 `DATABASE_ERROR` in the live check.
 *
 * Errors: TorBox's envelope `{success, error, detail, data}`, read leniently. Its limit codes are the account's limit;
 * a size over the plan's refuses that release alone; an auth code (`AUTH_ERROR` but under a 5xx, which is down), or HTTP
 * 401 or 403 under no code it knows, is a refused key; 5xx, timeouts and network errors are down; a torrent that isn't there, or that TorBox says it hasn't
 * cached when told to add only a cached one, is not cached. An unknown code is down on [test] and [check] and refuses
 * the release on [prepare] and [link]; the log line has its code, never `detail`.
 */
class TorBoxService(
    private val key: String,
    http: HttpClient,
    private val baseUrl: String = BASE_URL,
    limit: ServiceRateLimit = ServiceRateLimit(CALL_INTERVAL),
    gate: ServiceGate = ServiceGate(),
    /** The torrent id the app's listing store knows for a hash in this account, if any. */
    private val knownTorrent: suspend (InfoHash) -> String? = { null },
    /** Told of the adds this device provably made to the account (L232); none for a key not saved yet. */
    private val addWatch: AddWatch? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val log: (String) -> Unit = { DEFAULT_LOG.info(it) },
) : DebridService {
    override val kind = ServiceKind.TorBox

    private val calls = ServiceCalls(kind, http, limit, gate, log, clock)

    /**
     * What the account's whole list decides, one at a time (L232, EDL-1): an add's read and the add, and a delete with the
     * read that confirms it. A delete landing between two pages of an add's read would move the entries after it back one
     * place, and a held torrent could slip between the pages, to be taken for this device's add.
     */
    private val listed = Mutex()

    /** Set once the server refused a POST to `checkcached`: GET from then on. */
    @Volatile
    private var checkByGet = false

    /** Which call an answer is to: an unknown code is down on a test or a check, and refuses the release otherwise. */
    private enum class Call(val unknown: ServiceError) {
        Test(ServiceError.Down),
        Check(ServiceError.Down),
        Prepare(ServiceError.Refused()),
        Link(ServiceError.Refused()),
        WholeList(ServiceError.Down),
        Remove(ServiceError.Down),
    }

    override suspend fun test(): Account =
        calls.call("user/me", gated = false, said = { "signed in" }, request = { api("user/me") }) { answer ->
            val me = decode(UserWire.serializer(), data(answer, Call.Test)) ?: throw Failed(ServiceError.Down, "no account in the answer")
            val name = me.email?.takeIf { it.isNotBlank() } ?: throw Failed(ServiceError.Down, "no account in the answer")
            Account(name, PLANS[me.plan], instantOf(me.premiumExpiresAt))
        }

    override suspend fun check(hashes: List<InfoHash>): Map<InfoHash, Boolean> {
        val cached = HashSet<InfoHash>()
        hashes.distinct().chunked(BATCH).forEach { batch -> cached += checkcached(batch, files = false, Call.Check).keys }
        return hashes.associateWith { it in cached }
    }

    override suspend fun prepare(hash: InfoHash, pick: suspend (ServiceListing) -> ServiceFile?): Prepared {
        val cached = checkcached(listOf(hash), files = true, Call.Prepare)[hash] ?: notCached()
        val listing = ServiceListing(cached.files.mapIndexed { index, file -> ServiceFile(index, file.name, file.size) })
        if (listing.files.isEmpty()) notCached()
        val picked = pick(listing) ?: return Prepared(hash, listing, null)
        require(listing.files.getOrNull(picked.index) == picked) { "The pick must be a file of the listing" }

        // The account may still have it from an earlier play (L184): then nothing is added.
        val (id, torrent) = knownTorrent(hash)?.let { id -> mylist(id)?.takeIf { it.isOf(hash) }?.let { id to it } } ?: add(hash)
        val ids = listing.files.map { file -> file.copy(id = torrent.idOf(file)) }
        val withId = ids[picked.index].takeIf { it.id != null } ?: notCached()
        return Prepared(hash, ServiceListing(ids), withId, handle = id)
    }

    override suspend fun link(prepared: Prepared, file: ServiceFile): ServiceLink {
        val torrentId = prepared.handle ?: notCached()
        val fileId = (prepared.listing.files.getOrNull(file.index)?.takeIf { it.path == file.path && it.size == file.size }?.id ?: file.id)
            ?: mylist(torrentId)?.takeIf { it.isOf(prepared.hash) }?.idOf(file)
            ?: notCached()
        return try {
            ServiceLink(requestdl(torrentId, fileId), clock.instant())
        } catch (e: ServiceException) {
            if (e.error != ServiceError.NotCached) throw e
            // The account no longer has it (L234): removed a grace after a play, here or by another device, or by the user.
            // Added again under a first add's rule, asked once more, and the release as it now is handed out with the link.
            val (againId, torrent) = add(prepared.hash)
            val files = prepared.listing.files.map { it.copy(id = torrent.idOf(it)) }
            val again = Prepared(prepared.hash, ServiceListing(files), prepared.picked?.let { it.copy(id = torrent.idOf(it)) }, handle = againId)
            ServiceLink(requestdl(againId, torrent.idOf(file) ?: notCached()), clock.instant(), prepared = again)
        }
    }

    override suspend fun remove(handle: String) {
        // TorBox's `all` deletes every torrent in the account (L232): the body names one torrent, by an id that is one
        // positive integer, from a type that has no `all`, and its bytes are those pinned before anything is sent.
        val id = handle.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull()?.takeIf { it > 0 }
        requireNotNull(id) { "Not one torrent's id: nothing is sent" }
        val body = ServiceHttp.json.encodeToString(ControlWire.serializer(), ControlWire(id, operation = "delete"))
        check(body == "{\"torrent_id\":$id,\"operation\":\"delete\"}") { "The delete's body isn't the pinned one: nothing is sent" }
        listed.withLock {
            // Asked again as the delete's turn comes (EDL-2): it may have waited behind an add's whole list while Remove
            // after playing was switched off, the key changed or the account was signed out of. Then nothing is sent.
            val watch = addWatch
            if (watch != null) {
                val started = calls.now()
                if (!watching("controltorrent", otherwise = false) { watch.wanted() }) {
                    calls.note("controltorrent", "not asked, the account's adds no longer removed", started)
                    return
                }
            }
            try {
                calls.call("controltorrent", said = { "removed" }, request = {
                    method = HttpMethod.Post
                    api("torrents/controltorrent")
                    setBody(TextContent(body, ContentType.Application.Json))
                }) { answer -> data(answer, Call.Remove) }
            } catch (e: ServiceException) {
                // Only a 2xx with success true is done. A second delete of an id already gone answered HTTP 500 DATABASE_ERROR
                // in the live check, so no error is taken as gone: only the whole list, read now, can say the torrent is.
                val whole = wholeList("the delete not confirmed") { entries ->
                    if (entries.none { it.id == id }) "the torrent gone: removed" else "the torrent still listed"
                }
                if (whole == null || whole.any { it.id == id }) throw e
            }
        }
    }

    /** The CDN link to file [fileId] of torrent [torrentId]. */
    private suspend fun requestdl(torrentId: String, fileId: String): String =
        calls.call("requestdl", said = { "link made" }, request = {
            // The key as the token TorBox requires here, and nothing else of it: no Authorization header.
            url.takeFrom(URLBuilder(baseUrl).apply {
                appendPathSegments("torrents", "requestdl")
                parameters.append("token", key)
                parameters.append("torrent_id", torrentId)
                parameters.append("file_id", fileId)
                parameters.append("redirect", "false")
            })
        }) { answer ->
            (data(answer, Call.Link) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                ?: throw Failed(ServiceError.Down, "no link in the answer")
        }

    /**
     * The torrent of [hash] in the account, by its id, with its files: the entry the account's whole list already holds it
     * as, or else added (L232). The add is told to [addWatch] only when that list was read whole, just before it, and
     * holds no entry of the id the add answered with; no list is read while the watch wants no records. The read and the
     * add are [listed]'s: no delete of this client's comes between them.
     */
    private suspend fun add(hash: InfoHash): Pair<String, TorrentWire> {
        val watch = addWatch?.takeIf { watching("mylist, whole", otherwise = false) { it.wanted() } }
        val id = if (watch == null) {
            createtorrent(hash) { "added" }
        } else {
            listed.withLock {
                val before = wholeList("the add not recorded") { entries -> "the torrent listed: nothing added".takeIf { entries.any { it.holds(hash) } } }
                before?.firstOrNull { it.holds(hash) }?.let { held -> return held.id.toString() to held }
                val listedIds = before?.mapTo(HashSet()) { it.id.toString() }
                createtorrent(hash) { id ->
                    when {
                        listedIds == null -> "added"
                        id in listedIds -> "added, an entry already listed"
                        else -> "added, this device's"
                    }
                }.also { id -> if (listedIds != null && id !in listedIds) watching("createtorrent", otherwise = Unit) { watch.added(hash, id) } }
            }
        }
        return id to (mylist(id)?.takeIf { it.isOf(hash) } ?: notCached())
    }

    /**
     * The account's whole list (L232), or null when it can't be read whole: a page that fails (an answer over the 2 MB
     * cap among them) or that no model reads, an entry without an id, a page longer than asked, a list that moved while
     * it was read, more than [MAX_PAGES] pages, or longer than [WHOLE_WITHIN]. Each page waits its turn at the account's
     * pace. One line says how it went: [found], what the caller makes of a whole one; [unsure], what is left undone
     * without one.
     */
    private suspend fun wholeList(unsure: String, found: (List<TorrentWire>) -> String?): List<TorrentWire>? {
        val started = calls.now()
        val read = try {
            withTimeoutOrNull(WHOLE_WITHIN.toMillis()) { pages() } ?: Pages.Unsure("past ${WHOLE_WITHIN.seconds} s")
        } catch (e: ServiceException) {
            Pages.Unsure("not read (${e.error})")
        }
        return when (read) {
            is Pages.Unsure -> {
                calls.note(WHOLE, "${read.why}, $unsure", started)
                null
            }
            is Pages.Whole -> {
                calls.note(WHOLE, listOfNotNull(if (read.count == 1) "1 page" else "${read.count} pages", found(read.entries)).joinToString(", "), started)
                read.entries
            }
        }
    }

    /**
     * The list's pages, [PAGE] entries each, from the first until one with no more after it (the last), [MAX_PAGES] at
     * most. Each page is asked with one entry more, the next page's first, which that page must begin with: an entry
     * deleted before it, or one added, moves the list by a place while it is read (EDL-1), and the next page then begins
     * with another entry, or none. A held torrent could slip between two pages so; a list seen moving isn't whole. A page
     * with no torrents is TorBox's 404 for a list with none ([noTorrents]): an empty account, on the first page.
     */
    private suspend fun pages(): Pages {
        val entries = ArrayList<TorrentWire>()
        var ended: TorrentWire? = null
        for (count in 1..MAX_PAGES) {
            val page = calls.call(WHOLE, quiet = true, request = {
                api("torrents/mylist", "bypass_cache" to "true", "offset" to "${(count - 1) * PAGE}", "limit" to "${PAGE + 1}")
            }) { answer ->
                if (noTorrents(answer)) return@call emptyList()
                val data = data(answer, Call.WholeList) as? JsonArray ?: throw Failed(ServiceError.Down, "no list in the answer")
                data.map { ServiceHttp.json.decodeFromJsonElement(TorrentWire.serializer(), it) }
            }
            when {
                // A list paged otherwise than asked can't be known whole.
                page.size > PAGE + 1 -> return Pages.Unsure("a page longer than asked")
                page.any { it.id == null } -> return Pages.Unsure("an entry without an id")
                ended != null && page.firstOrNull()?.id != ended.id -> return Pages.Unsure(MOVED)
            }
            entries += page.take(PAGE)
            if (page.size <= PAGE) {
                // An entry read twice is a list that moved too.
                return if (entries.distinctBy { it.id }.size < entries.size) Pages.Unsure(MOVED) else Pages.Whole(entries, count)
            }
            ended = page.last()
        }
        return Pages.Unsure("past $MAX_PAGES pages")
    }

    /**
     * Whether [answer] is TorBox's to a list read that finds no torrents (ECS-1): HTTP 404 under `success: true` with
     * `ITEM_NOT_FOUND`, "No torrents found for this user." in its collection's example for Get Torrent List. That alone:
     * the same code under `success: false`, or any other 404, is no list.
     */
    private fun noTorrents(answer: Answer): Boolean {
        if (answer.status != NOT_FOUND) return false
        val envelope = runCatching { ServiceHttp.json.parseToJsonElement(answer.text()) as? JsonObject }.getOrNull() ?: return false
        return (envelope["success"] as? JsonPrimitive)?.booleanOrNull == true && (envelope["error"] as? JsonPrimitive)?.contentOrNull == "ITEM_NOT_FOUND"
    }

    /** How a whole-list read went: the [Whole] list in [Whole.count] pages, or why it can't be known whole. */
    private sealed interface Pages {
        class Whole(val entries: List<TorrentWire>, val count: Int) : Pages
        class Unsure(val why: String) : Pages
    }

    /**
     * [ask] of the app's [AddWatch]. A failure of the app's own is logged by its kind and taken as [otherwise]: an add it
     * can't record stays in the account, as an unsure one does, and the play goes on.
     */
    private suspend fun <T> watching(what: String, otherwise: T, ask: suspend () -> T): T = try {
        ask()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("debrid: ${kind.displayName} $what: the app's records failed (${e::class.simpleName})")
        otherwise
    }

    /** The cached ones of [batch], by hash, with their files when asked for [files]: by POST, else by GET (L197). */
    private suspend fun checkcached(batch: List<InfoHash>, files: Boolean, call: Call): Map<InfoHash, CachedWire> {
        val what = if (files) "checkcached, with files" else "checkcached ${batch.size} ${if (batch.size == 1) "hash" else "hashes"}"
        val said: (Map<InfoHash, CachedWire>?) -> String = { found -> found?.let { "${it.size} cached" } ?: "HTTP 405, asking by GET from now on" }
        if (!checkByGet) {
            val posted = calls.call(what, said = said, request = {
                method = HttpMethod.Post
                api("torrents/checkcached", "format" to "list", "list_files" to files.toString())
                setBody(TextContent(ServiceHttp.json.encodeToString(HashesWire.serializer(), HashesWire(batch.map { it.hex })), ContentType.Application.Json))
            }) { answer -> if (answer.status == METHOD_NOT_ALLOWED) null else cachedOf(answer, batch, call) }
            if (posted != null) return posted
            checkByGet = true
        }
        val query = listOfNotNull("hash" to batch.joinToString(",") { it.hex }, "format" to "list", ("list_files" to "true").takeIf { files })
        return calls.call<Map<InfoHash, CachedWire>>(what, said = { "${it.size} cached" }, request = { api("torrents/checkcached", *query.toTypedArray()) }) { answer ->
            cachedOf(answer, batch, call)
        }
    }

    private fun cachedOf(answer: Answer, batch: List<InfoHash>, call: Call): Map<InfoHash, CachedWire> {
        val entries = when (val data = data(answer, call)) {
            is JsonArray -> data
            // The object form, keyed by hash, as `format=object` gives it.
            is JsonObject -> JsonArray(data.values.toList())
            else -> JsonArray(emptyList())
        }
        val asked = batch.toSet()
        // An entry no model reads is passed over, not the whole answer.
        return entries.mapNotNull { runCatching { decode(CachedWire.serializer(), it) }.getOrNull() }
            .mapNotNull { entry -> InfoHash.parse(entry.hash)?.takeIf { it in asked }?.let { it to entry } }
            .toMap()
    }

    /** Adds the torrent of [hash], only if it is cached, and gives its id in the account; [said] words the line by that id. */
    private suspend fun createtorrent(hash: InfoHash, said: (String) -> String): String =
        calls.call("createtorrent", said = said, request = {
            method = HttpMethod.Post
            api("torrents/createtorrent")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        // The exact topic alone (L159): no tracker, no name.
                        append("magnet", hash.xtMagnet())
                        append("add_only_if_cached", "true")
                        // 3: don't seed.
                        append("seed", "3")
                        append("allow_zip", "false")
                        append("as_queued", "false")
                    },
                ),
            )
        }) { answer ->
            decode(CreatedWire.serializer(), data(answer, Call.Prepare))?.torrentId?.toString() ?: throw Failed(ServiceError.NotCached, "no torrent in the answer")
        }

    /** The torrent [id] in the account, with its files; null when the account doesn't have it. */
    private suspend fun mylist(id: String): TorrentWire? =
        calls.call("mylist", said = { it?.let { torrent -> "${torrent.files.size} files" } ?: "not there" }, request = {
            api("torrents/mylist", "id" to id, "bypass_cache" to "true")
        }) { answer ->
            val data = try {
                data(answer, Call.Prepare)
            } catch (e: Failed) {
                if (e.error == ServiceError.NotCached) return@call null
                throw e
            }
            decode(TorrentWire.serializer(), data)
        }

    /** A call to [path] under the API, its query [params], with the key as a bearer token. */
    private fun HttpRequestBuilder.api(path: String, vararg params: Pair<String, String>) {
        url.takeFrom(URLBuilder(baseUrl).apply {
            appendPathSegments(path.split('/'))
            params.forEach { (name, value) -> parameters.append(name, value) }
        })
        header(HttpHeaders.Authorization, "Bearer $key")
    }

    /** An answer's `data` when it succeeded; else the [Failed] its code or status means for [call]. */
    private fun data(answer: Answer, call: Call): JsonElement? {
        val envelope = runCatching { ServiceHttp.json.parseToJsonElement(answer.text()) as? JsonObject }.getOrNull()
        val code = (envelope?.get("error") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val success = (envelope?.get("success") as? JsonPrimitive)?.booleanOrNull
        if (answer.status in 200..299 && success == true) return envelope?.get("data")?.takeUnless { it is JsonNull }
        if (answer.status in 200..299 && envelope == null) throw Failed(ServiceError.Down, "an answer no model reads")
        val error = code?.let { bucketOf(it, answer, call) } ?: when (answer.status) {
            401, 403 -> ServiceError.KeyRefused
            in 500..599 -> ServiceError.Down
            else -> call.unknown
        }
        throw Failed(error, ServiceCalls.codeOf(code) ?: "HTTP ${answer.status}")
    }

    /** What TorBox's [code] means; null for one it doesn't document, which its status then decides. */
    private fun bucketOf(code: String, answer: Answer, call: Call): ServiceError? = when (code) {
        // debridav's limit set (L197): the account's limit, for the answer's wait when it gives one.
        "MONTHLY_LIMIT", "COOLDOWN_LIMIT", "ACTIVE_LIMIT" ->
            ServiceError.Limit(ServiceCalls.retryAfter(answer.headers[HttpHeaders.RetryAfter], clock.instant()))
        // One torrent over the plan's size (Free 10 GiB, Essential and Standard 200 GiB, Pro 500 GiB): that release
        // alone, never the account for an hour.
        "DOWNLOAD_TOO_LARGE" -> ServiceError.Refused(tooLarge = true)
        "PLAN_RESTRICTED_FEATURE" -> ServiceError.Refused()
        "NO_AUTH", "BAD_TOKEN" -> ServiceError.KeyRefused
        // TorBox's table makes a code ending in ERROR the server's fault (L236): under a 5xx it is a hiccup, which mustn't
        // lock the account until a new key is saved.
        "AUTH_ERROR" -> if (answer.status in 500..599) ServiceError.Down else ServiceError.KeyRefused
        // The torrent or file isn't in the account (any more); or, told to add only a cached torrent, TorBox hasn't cached
        // it (404, as the live check found it, item 39).
        "ITEM_NOT_FOUND", "DOWNLOAD_NOT_CACHED" -> if (call == Call.Prepare || call == Call.Link) ServiceError.NotCached else null
        else -> null
    }

    private fun <T> decode(serializer: KSerializer<T>, data: JsonElement?): T? =
        data?.let { ServiceHttp.json.decodeFromJsonElement(serializer, it) }

    /** Whether this entry is [hash]'s torrent: it lists the hash, or names none. */
    private fun TorrentWire.isOf(hash: InfoHash) = this.hash == null || holds(hash)

    /** Whether this entry lists [hash], as its own or among its alternative hashes (L232). */
    private fun TorrentWire.holds(hash: InfoHash) = InfoHash.parse(this.hash) == hash || alternativeHashes.any { InfoHash.parse(it) == hash }

    /** TorBox's id of [file]: the one of its name and size, its own path first; null when none or more than one is. */
    private fun TorrentWire.idOf(file: ServiceFile): String? {
        val same = files.filter { it.size == file.size && (it.name == file.path || it.name.substringAfterLast('/') == file.name || it.shortName == file.name) }
        return (same.firstOrNull { it.name == file.path } ?: same.singleOrNull())?.id?.toString()
    }

    private fun notCached(): Nothing = throw ServiceException(ServiceError.NotCached)

    companion object {
        const val BASE_URL = "https://api.torbox.app/v1/api"

        /** 240 calls a minute against TorBox's 300 (L175). */
        val CALL_INTERVAL: Duration = Duration.ofMillis(250)

        /** Hashes a cache check asks at once: the SDK page's limit for a GET, kept for a POST too. */
        const val BATCH = 100

        /**
         * The whole list's page (L232): 100 entries, not TorBox's 1,000, as each entry carries its files and a page over the
         * 2 MB cap is the whole read lost. Each is asked with the next page's first entry too (EDL-1).
         */
        const val PAGE = 100

        /** The whole list's pages at most: an account past 1,000 entries keeps what its plays add. */
        const val MAX_PAGES = 10

        /** How long a whole list may take: each page waits its turn at the account's pace, and holds its other calls behind it. */
        val WHOLE_WITHIN: Duration = Duration.ofSeconds(8)

        private const val WHOLE = "mylist, whole"
        private const val MOVED = "the list changed while it was read"

        private const val METHOD_NOT_ALLOWED = 405
        private const val NOT_FOUND = 404
        private val DEFAULT_LOG = LoggerFactory.getLogger(DebridService::class.java)

        /** TorBox's plans by number, as its `user/me` gives them. */
        private val PLANS = mapOf(0 to "Free", 1 to "Essential", 2 to "Pro", 3 to "Standard")

        private fun instantOf(text: String?): Instant? {
            val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }.getOrNull()
        }
    }
}

@Serializable
private class HashesWire(val hashes: List<String>)

@Serializable
private class UserWire(
    val email: String? = null,
    val plan: Int? = null,
    @SerialName("premium_expires_at") val premiumExpiresAt: String? = null,
)

@Serializable
private class CachedWire(val hash: String? = null, val files: List<CachedFileWire> = emptyList())

@Serializable
private class CachedFileWire(val name: String = "", val size: Long = 0)

@Serializable
private class CreatedWire(@SerialName("torrent_id") val torrentId: Long? = null)

@Serializable
private class TorrentWire(
    val id: Long? = null,
    val hash: String? = null,
    @SerialName("alternative_hashes") val alternativeHashes: List<String?> = emptyList(),
    val files: List<TorrentFileWire> = emptyList(),
)

/** controltorrent's body for one torrent (L232): its id and the operation. No `all`, by type: TorBox's `all` empties the account. */
@Serializable
private class ControlWire(@SerialName("torrent_id") val torrentId: Long, val operation: String)

@Serializable
private class TorrentFileWire(
    val id: Long? = null,
    val name: String = "",
    @SerialName("short_name") val shortName: String? = null,
    val size: Long = 0,
)
