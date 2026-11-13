package io.skjaere.usenet.app.setup

import io.skjaere.usenet.app.debrid.kindOf
import io.skjaere.usenet.data.IndexerWithKey
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ProviderWithPassword
import io.skjaere.usenet.data.ServiceWithKey
import io.skjaere.usenet.data.TorrentIndexerWithKey
import io.skjaere.usenet.handoff.ProviderDraft
import io.skjaere.usenet.newznab.IndexerEndpoint
import java.net.URI
import java.net.URISyntaxException

/**
 * When a password or key saved on the TV may go with what the phone's page holds (a provider's, an indexer's, a debrid
 * account's or a torrent indexer's): for a test from the page, on the review and at Save alike, so the three can't
 * drift. Whoever holds the code chooses where a test or a save sends the secret, so it goes only to the server it was
 * saved for. It is only ever the card's own record's: the TV binds a card to its record itself, never by anything the
 * page names. A secret typed this session for the card is the owner's own and needs no rule; one an earlier session's
 * page typed (Send again) the page holds only while its card stays on the server it was sent for, since the new code's
 * owner may be another device.
 */
object SavedSecretRule {
    /**
     * Whether [draft] is still [saved]'s server, so its saved password may go with it: the same host (ASCII only, and
     * compared in ASCII case: String's own ignoreCase folds other letters too, so "ı" would pass for "i"), the same
     * port, TLS as it was and the same username. The name and connections don't count. Whether the saved password can
     * be read is the caller's to say.
     */
    fun sameProvider(saved: ProviderRecord, draft: ProviderDraft): Boolean {
        val (was, now) = saved.host.trim() to draft.host.trim()
        return ascii(was) && ascii(now) && was.lowercase() == now.lowercase() && draft.portOrDefault == saved.port &&
            draft.useTls == saved.useTls && draft.username.trim() == saved.username.trim()
    }

    /**
     * The address [sent] stands for on the card of the indexer saved at [savedUrl]: the saved one whole, hidden parts
     * and all, while the card holds it as the page showed it; else what was sent.
     */
    fun indexerAddress(savedUrl: String, sent: String): String = if (sent.trim() == shownAddress(savedUrl).url) savedUrl else sent.trim()

    /**
     * Whether the key saved for the indexer at [savedUrl] may go to [sent]: it is the address as the page showed it, or
     * the same endpoint read the same way by the client and java.net.URI ([IndexerEndpoint]), never from https down to
     * plain http.
     */
    fun sameIndexer(savedUrl: String, sent: String): Boolean {
        if (sent.trim() == shownAddress(savedUrl).url) return true
        val was = IndexerEndpoint.of(savedUrl) ?: return false
        return IndexerEndpoint.of(sent)?.takesKeyOf(was) == true
    }

    /**
     * The saved password a card left empty stands for: [saved]'s, the record the TV bound the card to as it is now
     * (null for a new card, or a record gone), while [draft] is still its server ([sameProvider]) and it can be read.
     */
    fun password(saved: ProviderWithPassword?, draft: ProviderDraft): SavedUse {
        val password = saved?.password
        return when {
            // Nothing to keep from anyone.
            saved == null || password == "" -> SavedUse.No(SavedRefusal.NoneSaved)
            !sameProvider(saved.record, draft) -> SavedUse.No(SavedRefusal.ServerChanged)
            password == null -> SavedUse.No(SavedRefusal.Unreadable)
            else -> SavedUse.Use(password)
        }
    }

    /**
     * The same for an indexer's key: [saved]'s while [sent] is still its address ([sameIndexer]) and the key can be
     * read; [stored] tells a key that can't be read from none.
     */
    fun key(saved: IndexerWithKey?, stored: Boolean, sent: String): SavedUse = key(saved?.record?.baseUrl, saved?.apiKey, stored, sent)

    /** The same for a torrent indexer's key (L209): its saved address's, by [sameIndexer], as an indexer's. */
    fun torrentIndexerKey(saved: TorrentIndexerWithKey?, stored: Boolean, sent: String): SavedUse = key(saved?.record?.baseUrl, saved?.apiKey, stored, sent)

    /**
     * The same for a debrid account's key (L209): [saved]'s while the card is still of its service ([kind], as the page's
     * radio sends it) and the key can be read. An account of a service this version doesn't know is no card's.
     */
    fun serviceKey(saved: ServiceWithKey?, stored: Boolean, kind: String): SavedUse {
        val key = saved?.apiKey
        return when {
            saved == null || key == "" || key == null && !stored -> SavedUse.No(SavedRefusal.NoneSaved)
            kindOf(saved.record)?.choice != kind -> SavedUse.No(SavedRefusal.ServerChanged)
            key == null -> SavedUse.No(SavedRefusal.Unreadable)
            else -> SavedUse.Use(key)
        }
    }

    /** A key saved for the address [savedUrl] (null: no record), as [key] says of an indexer's. */
    private fun key(savedUrl: String?, savedKey: String?, stored: Boolean, sent: String): SavedUse = when {
        savedUrl == null || savedKey == "" || savedKey == null && !stored -> SavedUse.No(SavedRefusal.NoneSaved)
        !sameIndexer(savedUrl, sent) -> SavedUse.No(SavedRefusal.ServerChanged)
        savedKey == null -> SavedUse.No(SavedRefusal.Unreadable)
        else -> SavedUse.Use(savedKey)
    }

    private fun ascii(text: String) = text.isNotEmpty() && text.all { it.code < 0x80 }
}

/** What [SavedSecretRule] says of a card's saved secret: the one to use, or why there is none to. */
sealed interface SavedUse {
    data class Use(val secret: String) : SavedUse {
        override fun toString() = "Use(secret)"
    }

    data class No(val why: SavedRefusal) : SavedUse
}

/** Why a card's saved secret can't go with it: none is saved, the server isn't the one it was saved for, or it can't be read here. */
enum class SavedRefusal { NoneSaved, ServerChanged, Unreadable }

/** A saved indexer's address as a page may show it ([url]), and whether the saved one has more ([hiddenParts]). */
data class ShownAddress(val url: String, val hiddenParts: Boolean)

/**
 * [baseUrl] as a page may show it: its scheme, host, port and path. A login, a query or a fragment may hold a password
 * or a key (an address pasted with "?apikey=" works, as requests keep the query), so none is shown; the address then
 * says it has parts kept on the TV. One that can't be read shows nothing and keeps it all.
 */
fun shownAddress(baseUrl: String): ShownAddress {
    val uri = try {
        URI(baseUrl.trim())
    } catch (_: URISyntaxException) {
        null
    }
    val host = uri?.host
    if (uri?.scheme == null || host == null) return ShownAddress("", hiddenParts = true)
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return ShownAddress("${uri.scheme}://$host$port${uri.rawPath.orEmpty()}", uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null)
}
