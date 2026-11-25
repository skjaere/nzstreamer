package app.nzstreamer.plugin.sportarr.source

import io.skjaere.usenet.plugin.AddressSafety
import io.skjaere.usenet.plugin.HttpAnswer
import io.skjaere.usenet.plugin.PluginHttp
import io.skjaere.usenet.plugin.PluginHttpException
import io.skjaere.usenet.plugin.PluginSecrets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [PluginHttp] that records every request and answers it with [answer], which may throw a [PluginHttpException] as the
 * host does for a request with no answer. It also counts how many requests were in flight at once.
 */
class RecordingHttp(private val answer: suspend (Request) -> HttpAnswer) : PluginHttp {
    data class Request(val url: String, val headers: Map<String, String>, val maxBytes: Int)

    val requests: MutableList<Request> = CopyOnWriteArrayList()
    private val inFlight = AtomicInteger()

    /** The most requests in flight at once. */
    @Volatile
    var mostAtOnce = 0
        private set

    override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Int): HttpAnswer {
        val request = Request(url, headers, maxBytes)
        requests += request
        val now = inFlight.incrementAndGet()
        mostAtOnce = maxOf(mostAtOnce, now)
        try {
            return answer(request)
        } finally {
            inFlight.decrementAndGet()
        }
    }
}

fun answer(status: Int, body: String = ""): HttpAnswer = HttpAnswer(status, emptyMap(), body.toByteArray())

fun noAnswer(kind: PluginHttpException.Kind): Nothing = throw PluginHttpException(kind, "No answer")

/** The host's judgement of an address for the tests: https encrypted, http local here (the tests use loopback), else Invalid. */
fun loopbackSafety(url: String): AddressSafety {
    val uri = try {
        URI(url.trim())
    } catch (e: Exception) {
        return AddressSafety.Invalid
    }
    if (uri.host.isNullOrEmpty()) return AddressSafety.Invalid
    return when (uri.scheme?.lowercase()) {
        "https" -> AddressSafety.Encrypted
        "http" -> AddressSafety.LocalHttp
        else -> AddressSafety.Invalid
    }
}

/** The plugin's secrets in a map, as the host keeps them by name. */
class MapSecrets(initial: Map<String, String> = emptyMap()) : PluginSecrets {
    val stored = MutableStateFlow(initial)
    var puts = 0
        private set

    override suspend fun get(name: String): String? = stored.value[name]

    override suspend fun put(name: String, value: String?) {
        puts++
        stored.value = if (value == null) stored.value - name else stored.value + (name to value)
    }

    override fun has(name: String): Flow<Boolean> = stored.map { name in it }
}
