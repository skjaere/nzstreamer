package app.nzstreamer.plugin.sportarr.source

import io.skjaere.usenet.plugin.AddressSafety
import io.skjaere.usenet.plugin.HttpAnswer
import io.skjaere.usenet.plugin.PluginHttp
import io.skjaere.usenet.plugin.PluginHttpException
import io.skjaere.usenet.plugin.PluginHttpException.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext

/**
 * Sportarr's HTTP API as the plugin asks it (L640, L641): GETs through the host's client, the key in X-Api-Key on every
 * request and never in an address, each body read no further than its bound. It reads three things with the key, and
 * nothing else: who the server is, the leagues, and their events. It sends nothing until asked: [test], or a read of the
 * list.
 *
 * [typedAddress] is read as [SportarrAddress] says, [safety] being the host's judgement of an address; [work] decodes
 * answers off the caller's thread, which is the main one in the host's scope (L641).
 */
class SportarrClient(
    private val http: PluginHttp,
    typedAddress: String,
    private val key: String,
    safety: (String) -> AddressSafety,
    private val work: CoroutineContext = Dispatchers.Default,
) {
    /** The address read, or null where it can't be: then Test answers InvalidAddress, and a read gets no answer. */
    val address: SportarrAddress? = SportarrAddress.read(typedAddress, safety)

    /**
     * Test (L640): the status, then the leagues, and the first answer that settles it, in L640's order. Its log line says
     * the answer's kind alone.
     */
    suspend fun test(): TestAnswer = answerOfTest().also { log.info("Sportarr Test: {}", it.javaClass.simpleName) }

    /** Every league in the library, at most 4 MiB. */
    suspend fun leagues(): Reply = get(address?.leagues, LEAGUES_BYTES)

    /** Page [page] of league [leagueId]'s events, 1000 a page, at most 8 MiB. */
    suspend fun events(leagueId: Int, page: Int): Reply = get(address?.events(leagueId, page), PAGE_BYTES)

    private suspend fun answerOfTest(): TestAnswer {
        val address = address ?: return TestAnswer.InvalidAddress
        val version = when (val reply = get(address.status, STATUS_BYTES)) {
            is Reply.NoAnswer -> return noAnswer(reply.kind, address)
            is Reply.Answered -> {
                if (reply.answer.status != 200) return refusedOrError(reply.answer.status)
                val status = withContext(work) { Wire.status(reply.answer.body) }
                if (!status?.appName.equals(SPORTARR, ignoreCase = true)) return TestAnswer.NotSportarr
                val version = status?.version
                if (version == null || !SportarrVersion.supported(version)) return TestAnswer.TooOld(version)
                version
            }
        }
        // The Integration API's own proof of a key: an authenticated read of the leagues.
        return when (val reply = get(address.leagues, LEAGUES_BYTES)) {
            is Reply.NoAnswer -> noAnswer(reply.kind, address)
            is Reply.Answered -> when {
                reply.answer.status != 200 -> refusedOrError(reply.answer.status)
                withContext(work) { Wire.leagues(reply.answer.body) } == null -> TestAnswer.NotSportarr
                else -> TestAnswer.Connected(version)
            }
        }
    }

    /** A status other than 200: the key refused, Sportarr's own error, or something that isn't Sportarr. */
    private fun refusedOrError(status: Int): TestAnswer = when (status) {
        401, 403 -> TestAnswer.Refused
        in 500..599 -> TestAnswer.ServerError(status)
        else -> TestAnswer.NotSportarr
    }

    /** A request with no answer: a redirect elsewhere or a body too large isn't Sportarr; any other is the network's. */
    private fun noAnswer(kind: Kind, address: SportarrAddress): TestAnswer = when (kind) {
        Kind.TooLarge, Kind.RedirectRefused -> TestAnswer.NotSportarr
        Kind.InvalidAddress -> TestAnswer.InvalidAddress
        else -> TestAnswer.Unreachable(address.hostPort)
    }

    private suspend fun get(url: String?, maxBytes: Int): Reply {
        url ?: return Reply.NoAnswer(Kind.InvalidAddress)
        return try {
            Reply.Answered(http.get(url, mapOf(API_KEY_HEADER to key), maxBytes))
        } catch (e: PluginHttpException) {
            Reply.NoAnswer(e.kind)
        }
    }

    companion object {
        const val API_KEY_HEADER = "X-Api-Key"

        /** L641's bounds: the status, the leagues, and one page of a league's events. */
        const val STATUS_BYTES = 64 * 1024
        const val LEAGUES_BYTES = 4 * 1024 * 1024
        const val PAGE_BYTES = 8 * 1024 * 1024

        private const val SPORTARR = "Sportarr"
        private val log = LoggerFactory.getLogger(SportarrClient::class.java)
    }
}

/** What a request got: an answer of any status, or none, for the host's [Kind] of reason. */
sealed interface Reply {
    class Answered(val answer: HttpAnswer) : Reply

    data class NoAnswer(val kind: Kind) : Reply
}

/** Test's answers (L640), each a line under Test and Save. */
sealed interface TestAnswer {
    /** "✓ Connected · Sportarr <version>", the version in its four parts as Sportarr sent it. */
    data class Connected(val version: String) : TestAnswer

    /** Sportarr answered 401 or 403: the key was refused. */
    data object Refused : TestAnswer

    /** Sportarr older than 4.1.1, the first with its Integration API; [version] as sent, null when it sent none. */
    data class TooOld(val version: String?) : TestAnswer

    /** Something answered, but not Sportarr: a 404, a page, other JSON, a redirect elsewhere, a body too large. */
    data object NotSportarr : TestAnswer

    /** No answer, or none in time, from [hostPort], the address's host and port as typed. */
    data class Unreachable(val hostPort: String) : TestAnswer

    /** Sportarr answered with its own error, a 5xx. */
    data class ServerError(val status: Int) : TestAnswer

    /** The address can't be read: not http or https with a host, or with a query or a fragment. */
    data object InvalidAddress : TestAnswer
}

/** Which Sportarr the plugin works with: 4.1.1 or newer (L611, L640). */
internal object SportarrVersion {
    private val FLOOR = listOf(4, 1, 1).map { it.toBigInteger() }

    /**
     * Whether [version]'s first three dot-separated parts, each read by its leading digits, reach 4.1.1. What follows them
     * (a build number, "-<commit>", "-local") is ignored; a version without three readable parts is too old.
     */
    fun supported(version: String): Boolean {
        val parts = version.trim().split('.')
        if (parts.size < 3) return false
        val numbers = parts.take(3).map { part ->
            part.takeWhile { it in '0'..'9' }.takeIf { it.isNotEmpty() }?.toBigInteger() ?: return false
        }
        val differs = numbers.zip(FLOOR).firstOrNull { (read, floor) -> read != floor } ?: return true
        return differs.first > differs.second
    }
}
