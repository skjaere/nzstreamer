package io.skjaere.usenet.debrid.liveremove

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.live.LiveRun
import io.skjaere.usenet.debrid.premiumize.PremiumizeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whether a play's `directdl` leaves anything in a Premiumize account (L235, C3: D1 took "adds nothing" from the API page
 * alone). `transfer/list` and `account/info`'s `space_used` before; `directdl` of the openly licensed film as the client
 * asks it; [settle] later, both again. Five real calls, at the client's pace (one every 500 ms). [run]'s report and
 * [summary] say whether a transfer appeared, whether one holds the film's hash and whether `space_used` changed: booleans,
 * never a transfer's id or name, the hash, a link or the space itself.
 */
internal class PremiumizeLeavesCheck(
    private val run: LiveRun,
    private val key: String,
    private val film: InfoHash,
    private val http: HttpClient,
    private val premiumize: PremiumizeService,
    private val baseUrl: String = PremiumizeService.BASE_URL,
    private val pace: Duration = PremiumizeService.CALL_INTERVAL,
    /** How long after `directdl` the account is read again. */
    private val settle: Duration = Duration.ofSeconds(5),
) {
    /** The answers, a line each. */
    val summary = CopyOnWriteArrayList<String>()

    suspend fun directdlLeaves() {
        try {
            leaves()
        } catch (e: AssertionError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only the kind: an exception's words can hold an address.
            stop("stopped by ${e.javaClass.simpleName}")
        }
    }

    private suspend fun leaves() {
        val before = transfers("transfer/list before directdl") ?: stop("transfer/list before directdl wasn't read: no directdl")
        val spaceBefore = space("account/info before directdl") ?: stop("account/info before directdl gave no space_used: no directdl")
        pause()
        run.timed("directdl of the film", said = { "${it.listing.files.size} files" }) { premiumize.prepare(film, run.largest) }
            ?: stop("directdl of the film gave no files: nothing to compare")
        delay(settle.toMillis())
        val after = transfers("transfer/list ${settle.seconds} s after directdl")
        val spaceAfter = space("account/info ${settle.seconds} s after directdl")
        val appeared = after?.let { (it.ids - before.ids).isNotEmpty() }
        val changed = spaceAfter?.let { it != spaceBefore }
        run.note(
            "after directdl: a transfer appeared: ${yes(appeared)}, one holds the film's hash: ${yes(after?.film)} (before: " +
                "${yes(before.film)}), space_used changed: ${yes(changed)}",
        )
        summary += "directdl of the film: files listed; ${settle.seconds} s later a transfer appeared: ${yes(appeared)}; one holds the " +
            "film's hash: ${yes(after?.film)} (before: ${yes(before.film)}); space_used changed: ${yes(changed)}"
    }

    /** `transfer/list`: the transfers' ids, and whether one's source holds the film's hash; null for a failed answer. */
    private suspend fun transfers(what: String): Transfers? {
        pause()
        val response = http.get("$baseUrl/transfer/list") { signed(key) }
        val transfers = Transfers.of(response.status.value, response.bodyAsText(), film)
        run.note("$what: ${transfers?.let { "read, a transfer holds the film's hash: ${yes(it.film)}" } ?: "not read"}")
        return transfers
    }

    /** `account/info`'s `space_used`, as its text; null for a failed answer or none given. */
    private suspend fun space(what: String): String? {
        pause()
        val response = http.get("$baseUrl/account/info") { signed(key) }
        val envelope = envelopeOf(response.bodyAsText())
        val space = (envelope?.get("space_used") as? JsonPrimitive)?.takeIf { response.status.value in 200..299 && ok(envelope) && !it.isString }?.contentOrNull
        run.note("$what: space_used ${if (space != null) "given" else "not given"}")
        return space
    }

    private suspend fun pause() = delay(pace.toMillis())

    private fun stop(why: String): Nothing {
        run.note("stopped: $why")
        summary += "failed: $why"
        throw AssertionError("$why (build/reports/debridLive/$REPORT.txt)")
    }

    /** `transfer/list`'s answer: the transfers' [ids] and whether one's `src` holds the film's hash ([film]). */
    internal class Transfers(val ids: Set<String>, val film: Boolean) {
        override fun toString() = "Transfers(${ids.size})"

        companion object {
            fun of(status: Int, text: String, film: InfoHash): Transfers? {
                val envelope = envelopeOf(text)?.takeIf { status in 200..299 && ok(it) } ?: return null
                val transfers = (envelope["transfers"] as? JsonArray)?.map { it as? JsonObject ?: return null } ?: return null
                val ids = transfers.map { (it["id"] as? JsonPrimitive)?.contentOrNull ?: return null }.toSet()
                val holds = transfers.any { (it["src"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.contains(film.hex) == true }
                return Transfers(ids, holds)
            }
        }
    }

    companion object {
        const val REPORT = "premiumize-leaves"

        /** Premiumize's success: `status` "success" (an error comes with HTTP 200 too). */
        private fun ok(envelope: JsonObject) = (envelope["status"] as? JsonPrimitive)?.contentOrNull == "success"
    }
}
