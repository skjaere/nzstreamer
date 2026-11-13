package io.skjaere.usenet.debrid.liveremove

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceHttp
import io.skjaere.usenet.debrid.ServiceListing
import io.skjaere.usenet.debrid.live.LiveRun
import io.skjaere.usenet.debrid.torbox.TorBoxService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * What #318 needs TorBox's own answers for (L235, C4, C5), with your own key and the openly licensed film whose entry D1's
 * live check left in your list (W91: its delete is approved, and nothing else may be deleted). Four cases, each asked only
 * when the one before passed, at the client's pace (one call every 250 ms):
 *
 * 1. `mylist` (whole, `bypass_cache`), which must hold exactly one entry with the film's hash, else nothing more is asked;
 *    `createtorrent` of that hash as D1's client sends it (`add_only_if_cached`); `mylist` again. Its HTTP code and code,
 *    whether its `torrent_id` is the entry's, whether a second entry appeared, and the entry's `created_at` against the
 *    answer's Date header and against the first list's `created_at` (deltas only).
 * 2. `requestdl` for the entry's largest file, as the client asks it; the link opened from byte 0, a little read and the
 *    body left open. No link, or a link that isn't read, stops here: no delete.
 * 3. `controltorrent` delete of that entry's `torrent_id` alone, its body built from [DeleteOne], which has no `all`, and
 *    checked against the pinned bytes; sent only when the latest list holds exactly one entry with the film's hash and it
 *    is the entry held before ([deleteTarget]). Otherwise the case fails, and nothing is sent.
 * 4. After it: whether the body already flowing goes on (read on for a few seconds), a fresh ranged GET of the same link
 *    (its code), `requestdl` of the deleted entry (asked on its own, never through the client's link, which would add the
 *    torrent back since D2), `mylist` (is it gone), the same delete again only once the list shows
 *    the entry gone (its code), and `checkcached` of the film (still cached?).
 *
 * Eleven real calls when every case passes (nine to the API, two to the CDN). [run]'s report says each answer: field
 * names, HTTP codes, TorBox's codes, booleans, counts and time deltas; never the key, the link, the CDN's host, an id, the
 * hash or a name. [summary] says the answers to the four cases in a line each.
 */
internal class TorBoxRemoveCheck(
    private val run: LiveRun,
    private val key: String,
    private val film: InfoHash,
    /** The client the calls go through: [run]'s recording, the budget's guard, and [answers]. */
    private val http: HttpClient,
    private val torbox: TorBoxService,
    private val links: LinkReader,
    private val answers: LastAnswers,
    private val baseUrl: String = TorBoxService.BASE_URL,
    private val pace: Duration = TorBoxService.CALL_INTERVAL,
    /** How long the body already flowing is read on after the delete. */
    private val readOn: Duration = Duration.ofSeconds(6),
    private val tick: Duration = LinkReader.TICK,
) : AutoCloseable {
    private val said = ConcurrentHashMap<Int, String>()

    /** Whether case 1 passed: the film's one entry held, `createtorrent` asked, the list read again. */
    @Volatile
    var created = false
        private set

    /** Whether case 2 passed: a link made and its body left flowing. */
    @Volatile
    var linked = false
        private set

    /** Whether case 3 sent the delete (whatever it answered). */
    @Volatile
    var deleteSent = false
        private set

    private var held: Entry? = null
    private var latest: Listed? = null
    private var prepared: Prepared? = null
    private var link: String? = null
    private var flowing: LinkReader.Open? = null
    private var deleteAnswered = 0L

    /** The four cases' answers, a line each; a case not reached says so. */
    val summary: List<String>
        get() = (1..4).map { said[it] ?: "$it. not asked: a case before it didn't pass" }

    suspend fun createOnHeld() = case(1) {
        val before = list("the list before createtorrent")
        val holding = before?.holding(film).orEmpty()
        if (before == null || !before.complete || holding.size != 1) stop(1, "${listSaid(before, holding.size)}: no createtorrent, no delete")
        val entry = holding.single()
        held = entry
        latest = before
        val made = createtorrent()
        run.note(
            "createtorrent of the held hash: $made, a torrent_id given: ${yes(made.torrentId != null)}, the held entry's: " +
                "${yes(made.torrentId?.let { it == entry.id })}, the answer's hash the film's: ${yes(made.filmHash)}, a Date header: ${yes(made.date != null)}",
        )
        val after = list("the list after createtorrent") ?: stop(1, "createtorrent $made; the list after it wasn't read: no delete")
        latest = after
        val holdingAfter = after.holding(film)
        val heldAfter = after.entries.firstOrNull { it.id == entry.id }
        val returned = made.torrentId?.let { id -> after.entries.firstOrNull { it.id == id } }
        // The entry createtorrent's answer names; the held one when it names none listed.
        val named = returned ?: heldAfter
        run.note(
            "the list after: entries with the film's hash: ${holdingAfter.size} (a second one: ${yes(holdingAfter.size > 1)}), " +
                "the held entry listed: ${yes(heldAfter != null)}, the returned id listed: ${yes(returned != null)}",
        )
        run.note(
            "the held entry after createtorrent: created_at moved ${deltaOf(heldAfter?.createdAt, entry.createdAt)}, " +
                "updated_at moved ${deltaOf(heldAfter?.updatedAt, entry.updatedAt)}",
        )
        run.note(
            "${if (returned != null) "the returned id's entry" else "the held entry"}: created_at less createtorrent's Date " +
                "${deltaOf(named?.createdAt, made.date)}, less the first list's created_at ${deltaOf(named?.createdAt, entry.createdAt)}",
        )
        said[1] = "1. createtorrent (add_only_if_cached) of the hash the list holds: $made; the same torrent_id: " +
            "${yes(made.torrentId?.let { it == entry.id })}; a second entry: ${yes(holdingAfter.size > 1)}; the entry's created_at " +
            "less the answer's Date: ${deltaOf(named?.createdAt, made.date)}, less the first list's created_at: " +
            "${deltaOf(named?.createdAt, entry.createdAt)}"
        created = true
    }

    suspend fun linkBeforeDelete() = case(2) {
        if (!created) stop(2, "case 1 didn't pass: no link, no delete")
        val entry = latest?.entries?.firstOrNull { it.id == held?.id } ?: stop(2, "the held entry isn't in the latest list: no delete")
        val main = entry.files.filter { it.id != null }.maxByOrNull { it.size } ?: stop(2, "the entry lists no file with an id: no delete")
        val file = ServiceFile(0, main.name, main.size, id = main.id.toString())
        val asked = Prepared(film, ServiceListing(listOf(file)), file, handle = entry.id.toString()).also { prepared = it }
        pause()
        val made = run.timed("requestdl for the entry's largest file", said = { "a link" }) { torbox.link(asked, file) }
            ?: stop(2, "requestdl for the entry's largest file: ${answers.of("GET", REQUESTDL) ?: "no answer"}, no link: no delete")
        link = made.url
        val opened = links.open(made.url)
        val open = opened.getOrNull()
        run.note("${openSaid("the link opened from byte 0", opened)}, its body left open")
        if (open == null || open.status !in 200..299 || open.read == 0) {
            open?.close()
            stop(2, "the link wasn't read (${openSaid("opened from byte 0", opened)}): no delete")
        }
        flowing = open
        said[2] = "2. requestdl for the entry's largest file: a link; ${openSaid("opened from byte 0", opened)}, its body left open"
        linked = true
    }

    suspend fun deleteHeld() = case(3) {
        if (!linked) stop(3, "case 2 didn't pass: no delete")
        val id = when (val target = deleteTarget(latest, held, film)) {
            is DeleteTarget.None -> stop(3, "no delete: ${target.why}")
            is DeleteTarget.One -> target.id
        }
        val body = deleteBody(id)
        deleteSent = true
        val answer = try {
            controltorrent(body)
        } finally {
            deleteAnswered = System.nanoTime()
        }
        run.note("controltorrent delete of the held entry alone: $answer")
        said[3] = "3. controltorrent delete of that entry's torrent_id alone (a body with no all): $answer"
    }

    suspend fun afterDelete() = case(4) {
        if (!deleteSent) stop(4, "no delete was sent: nothing after it to see")
        val from = msSince(deleteAnswered)
        val flow = flowing?.let { open -> open.readOn(readOn, tick).also { open.close() } }
        flowing = null
        val flowSaid = when {
            flow == null -> "no body was open"
            flow.ended == null -> "kept delivering: yes, ${mib(flow.bytes)} in ${flow.afterMs} ms"
            else -> "kept delivering: no, ${mib(flow.bytes)} then ${flow.ended} at ${flow.afterMs} ms"
        }
        run.note("the body already flowing, read on from $from ms after the delete's answer: $flowSaid")
        val again = link?.let { links.open(it) }
        again?.getOrNull()?.close()
        val againSaid = again?.let { result -> result.getOrNull()?.let { "HTTP ${it.status}" } ?: result.exceptionOrNull()?.javaClass?.simpleName } ?: "no link to ask"
        run.note("a fresh ranged GET of the same link: $againSaid")
        pause()
        answers.forget("GET", REQUESTDL)
        val linkAfter = prepared?.let { asked ->
            val start = System.nanoTime()
            val answer = requestdl(asked)
            run.note("requestdl of the deleted entry's file: ${if (answer.status in 200..299 && answer.code == null) "a link (still made)" else "$answer"}, ${msSince(start)} ms")
            answer.toString()
        } ?: "not asked"
        val id = held!!.id
        val after = list("the list after the delete")
        val gone = after != null && after.complete && after.entries.none { it.id == id }
        run.note("after the delete: the entry gone from the list: ${if (after == null) "not read" else yes(gone)}, entries with the film's hash: ${after?.holding(film)?.size ?: "not read"}")
        val second = if (gone) controltorrent(deleteBody(id)).toString() else "not sent: the list doesn't show the entry gone"
        run.note("the same delete again: $second")
        pause()
        val cached = run.timed("checkcached of the film after the delete", said = { if (it[film] == true) "still cached" else "not cached" }) { torbox.check(listOf(film)) }
        said[4] = "4. after the delete: the body already flowing $flowSaid; a fresh ranged GET of the link: $againSaid; requestdl of " +
            "the deleted entry: $linkAfter; mylist: the entry gone: ${if (after == null) "not read" else yes(gone)}; the same delete " +
            "again: $second; checkcached: still cached: ${cached?.let { yes(it[film] == true) } ?: "not answered"}"
    }

    /** The body left flowing, if a case stopped before reading it on. */
    override fun close() {
        flowing?.close()
        flowing = null
    }

    /** The whole list, its one page of up to [PAGE] entries, `bypass_cache` as a check of a change needs. */
    private suspend fun list(what: String): Listed? {
        pause()
        val response = http.get("$baseUrl/torrents/mylist?bypass_cache=true&offset=0&limit=$PAGE") { signed(key) }
        val listed = Listed.of(response.status.value, response.bodyAsText())
        run.note("$what: ${listed?.let { "read${if (it.complete) "" else ", maybe not whole"}, entries with the film's hash: ${it.holding(film).size}" } ?: "not read"}")
        return listed
    }

    /** `createtorrent` exactly as D1's client sends it (`TorBoxService.createtorrent`). */
    private suspend fun createtorrent(): Created {
        pause()
        val response = http.submitFormWithBinaryData(
            "$baseUrl/torrents/createtorrent",
            formData {
                append("magnet", film.xtMagnet())
                append("add_only_if_cached", "true")
                append("seed", "3")
                append("allow_zip", "false")
                append("as_queued", "false")
            },
        ) { signed(key) }
        val envelope = envelopeOf(response.bodyAsText())
        val data = envelope?.get("data") as? JsonObject
        return Created(
            Said(response.status.value, codeOf(envelope)),
            torrentId = (data?.get("torrent_id") as? JsonPrimitive)?.longOrNull,
            filmHash = (data?.get("hash") as? JsonPrimitive)?.contentOrNull?.let { InfoHash.parse(it) == film },
            date = dateOf(response.headers[HttpHeaders.Date]),
        )
    }

    /**
     * `requestdl` for [asked]'s file as the client sends it (the key as `token=`, no bearer, redirect off), but on its own:
     * since D2's L234 the client's link adds back a torrent the account no longer has, and nothing may be added after the
     * delete.
     */
    private suspend fun requestdl(asked: Prepared): Said {
        val response = http.get("$baseUrl/torrents/requestdl") {
            url.parameters.append("token", key)
            url.parameters.append("torrent_id", asked.handle!!)
            url.parameters.append("file_id", asked.picked!!.id!!)
            url.parameters.append("redirect", "false")
            header(HttpHeaders.UserAgent, ServiceHttp.USER_AGENT)
        }
        return Said(response.status.value, codeOf(envelopeOf(response.bodyAsText())))
    }

    /** `controltorrent` with [body], which [deleteBody] made. */
    private suspend fun controltorrent(body: String): Said {
        pause()
        val response = http.post("$baseUrl/torrents/controltorrent") {
            signed(key)
            setBody(TextContent(body, ContentType.Application.Json))
        }
        return Said(response.status.value, codeOf(envelopeOf(response.bodyAsText())))
    }

    private suspend fun pause() = delay(pace.toMillis())

    /** [body], or the case's stop with the kind of what broke it: an exception's words can hold an address. */
    private suspend fun case(number: Int, body: suspend () -> Unit) {
        try {
            body()
        } catch (e: AssertionError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stop(number, "stopped by ${e.javaClass.simpleName}${if (number == 3 && deleteSent) " after the delete was sent" else ""}")
        }
    }

    /** The case [number] failed for [why]: said in the report and the summary, and the case's test fails. */
    private fun stop(number: Int, why: String): Nothing {
        run.note("case $number: $why")
        said[number] = "$number. failed: $why"
        throw AssertionError("case $number: $why (build/reports/debridLive/$REPORT.txt)")
    }

    private fun listSaid(listed: Listed?, holding: Int) = when {
        listed == null -> "the list before wasn't read"
        !listed.complete -> "the list before may not be whole"
        else -> "the list before has $holding entries with the film's hash, not one"
    }

    private fun openSaid(what: String, opened: Result<LinkReader.Open>): String = opened.fold(
        onSuccess = { "$what: HTTP ${it.status}, a Content-Range from byte 0: ${yes(it.fromStart)}, ${it.read} bytes read" },
        onFailure = { "$what: ${it.javaClass.simpleName}" },
    )

    /** createtorrent's answer, as case 1 says it. */
    private class Created(val said: Said, val torrentId: Long?, val filmHash: Boolean?, val date: Instant?) {
        override fun toString() = said.toString()
    }

    companion object {
        const val REPORT = "torbox-remove"

        /** mylist's page, as its OpenAPI gives it: a list this long may have more, and is never taken as whole. */
        const val PAGE = 1000

        private const val REQUESTDL = "/torrents/requestdl"
    }
}

/** One entry of the account's list: never its id, hash or names in a report; its [toString] says none of them. */
internal class Entry(val id: Long, val hash: InfoHash?, val createdAt: Instant?, val updatedAt: Instant?, val files: List<EntryFile>) {
    override fun toString() = "Entry(${files.size} files)"
}

internal class EntryFile(val id: Long?, val name: String, val size: Long) {
    override fun toString() = "EntryFile"
}

/** The account's list as `mylist` gave it; [complete] when every entry was read and the page can't have had more. */
internal class Listed(val entries: List<Entry>, val complete: Boolean) {
    fun holding(hash: InfoHash) = entries.filter { it.hash == hash }

    companion object {
        /** The list in a successful answer [text] with [status]; null for any other answer. */
        fun of(status: Int, text: String): Listed? {
            if (status !in 200..299) return null
            val envelope = envelopeOf(text) ?: return null
            if ((envelope["success"] as? JsonPrimitive)?.booleanOrNull != true) return null
            val data = envelope["data"] as? JsonArray ?: return null
            val entries = data.mapNotNull { element ->
                val wire = runCatching { READ.decodeFromJsonElement(EntryWire.serializer(), element) }.getOrNull()
                wire?.id?.let { id ->
                    Entry(id, InfoHash.parse(wire.hash), instantOf(wire.createdAt), instantOf(wire.updatedAt), wire.files.map { EntryFile(it.id, it.name, it.size) })
                }
            }
            return Listed(entries, complete = entries.size == data.size && data.size < TorBoxRemoveCheck.PAGE)
        }
    }
}

/** What case 3 may delete: [One] entry by its id, or [None] and why not. */
internal sealed interface DeleteTarget {
    class One(val id: Long) : DeleteTarget {
        override fun toString() = "One"
    }

    class None(val why: String) : DeleteTarget
}

/**
 * The one entry the delete may name: in the [latest] list read whole, the only entry with the [film]'s hash, and the very
 * entry [held] before `createtorrent` (D1's, whose delete was approved). Anything else is [DeleteTarget.None].
 */
internal fun deleteTarget(latest: Listed?, held: Entry?, film: InfoHash): DeleteTarget {
    held ?: return DeleteTarget.None("no entry was held before createtorrent")
    latest ?: return DeleteTarget.None("the latest list wasn't read")
    if (!latest.complete) return DeleteTarget.None("the latest list may not be whole")
    val holding = latest.holding(film)
    if (holding.size != 1) return DeleteTarget.None("${holding.size} entries in the latest list have the film's hash, not one")
    val entry = holding.single()
    if (entry.id != held.id) return DeleteTarget.None("the entry with the film's hash isn't the one held before createtorrent")
    if (entry.hash != film || entry.id <= 0) return DeleteTarget.None("the entry isn't the film's")
    return DeleteTarget.One(entry.id)
}

/**
 * The delete's body for torrent [id]: built from [DeleteOne], which has no `all` (TorBox's `all` deletes every torrent in
 * the account), and checked against the pinned bytes before it can be sent.
 */
internal fun deleteBody(id: Long): String {
    require(id > 0) { "no torrent id: nothing may be sent" }
    val body = Json.encodeToString(DeleteOne.serializer(), DeleteOne(torrentId = id, operation = "delete"))
    check(body == "{\"torrent_id\":$id,\"operation\":\"delete\"}") { "the delete's body isn't the pinned one: not sent" }
    return body
}

/** controltorrent's body for one torrent: its id and the operation. No `all`, by type. */
@Serializable
internal class DeleteOne(@SerialName("torrent_id") val torrentId: Long, val operation: String)

@Serializable
private class EntryWire(
    val id: Long? = null,
    val hash: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val files: List<EntryFileWire> = emptyList(),
)

@Serializable
private class EntryFileWire(val id: Long? = null, val name: String = "", val size: Long = 0)
