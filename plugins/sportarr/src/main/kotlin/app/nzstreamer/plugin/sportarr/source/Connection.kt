package app.nzstreamer.plugin.sportarr.source

import io.skjaere.usenet.plugin.AddressSafety
import io.skjaere.usenet.plugin.PluginSecrets
import java.net.URI
import java.net.URISyntaxException

/**
 * The user's Sportarr as the plugin keeps it (L640): its address and its API key, two of the plugin's secrets in the
 * host's store (plugin:sportarr:url and plugin:sportarr:key), never anything in the plugin's folder. The address is kept
 * there too, as it names the user's own server on their network, and the store keeps it out of backups and device moves.
 * Saving sends nothing: only Test and a read of the list ask Sportarr anything.
 */
class Connection(private val secrets: PluginSecrets) {
    /** The address and key saved, or null until both are. */
    suspend fun saved(): Saved? {
        val address = address() ?: return null
        val key = key() ?: return null
        return Saved(address, key)
    }

    /** The address saved, or null; the setup form shows it. */
    suspend fun address(): String? = secrets.get(URL)?.takeIf { it.isNotBlank() }

    /** The key saved, or null; the setup form never shows it, only says it is saved and sends it on Test. */
    suspend fun key(): String? = secrets.get(KEY)?.takeIf { it.isNotBlank() }

    /**
     * Stores [address] and [key], each trimmed (a pasted key may bring a line break, which no header can carry); true when
     * either differs from what was stored, which reads the list again at once (L641).
     */
    suspend fun save(address: String, key: String): Boolean {
        val typed = address.trim()
        val given = key.trim()
        if (secrets.get(URL) == typed && secrets.get(KEY) == given) return false
        secrets.put(URL, typed)
        secrets.put(KEY, given)
        return true
    }

    companion object {
        /** The secrets' names, stored as plugin:sportarr:<name>. */
        const val URL = "url"
        const val KEY = "key"
    }
}

/** An address and key as saved. Its words never show the key or the address, should it reach a log line. */
class Saved(val address: String, val key: String) {
    override fun toString(): String = "Saved(address, key)"
}

/**
 * An address the user typed, read as L640 says: trimmed; http or https with a host, as the host judges an indexer's address
 * ([AddressSafety]); a path kept as Sportarr's URL base ("http://nas.lan:1867/sportarr"), one trailing slash dropped; a
 * query or a fragment refused, as the key goes in a header and nothing else may ride along. Every route is the base plus
 * /api/....
 */
class SportarrAddress private constructor(private val base: String, val hostPort: String) {
    /** Who the server is: Test's first question. */
    val status: String get() = "$base/api/system/status"

    /** Every league in the user's library. */
    val leagues: String get() = "$base/api/leagues"

    /** One page of a league's events, as its page in Sportarr lists them (L641). */
    fun events(leagueId: Int, page: Int): String = "$base/api/leagues/$leagueId/events?page=$page&pageSize=$PAGE_SIZE"

    // Never the address itself, should one reach a log line.
    override fun toString(): String = "SportarrAddress"

    companion object {
        /** Events a page: the most Sportarr gives, so six pages reach back to a big league's season start (C30). */
        const val PAGE_SIZE = 1000

        /** [typed] read, or null where it isn't an address Sportarr can be asked at; [safety] is the host's judgement. */
        fun read(typed: String, safety: (String) -> AddressSafety): SportarrAddress? {
            val address = typed.trim()
            if (safety(address) == AddressSafety.Invalid) return null
            val uri = try {
                URI(address)
            } catch (e: URISyntaxException) {
                return null
            }
            if (uri.rawQuery != null || uri.rawFragment != null) return null
            if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return SportarrAddress(address.removeSuffix("/"), if (uri.port == -1) host else "$host:${uri.port}")
        }

        /**
         * How the help line under the field judges [typed] (L640): the host's judgement, or Invalid where the plugin can't
         * read it as an address.
         */
        fun safety(typed: String, safety: (String) -> AddressSafety): AddressSafety =
            if (read(typed, safety) == null) AddressSafety.Invalid else safety(typed.trim())
    }
}
