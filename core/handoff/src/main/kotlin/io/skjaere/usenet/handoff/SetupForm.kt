package io.skjaere.usenet.handoff

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** What reading a setup form gave: a form from the page ([SetupPost]), or a [SetupRejection] saying why not. */
sealed interface SetupParse

/**
 * A provider as a card holds it. Text left blank is "": an empty [password] keeps the saved one (the app's
 * same-server rule says when). [port] and [connections] are null when left blank, for the defaults.
 */
data class ProviderDraft(
    val name: String,
    val host: String,
    val port: Int?,
    val useTls: Boolean,
    val username: String,
    val password: String,
    val connections: Int?,
) {
    /** The port to use: the one sent, or the usual one for TLS or plain NNTP. */
    val portOrDefault: Int get() = port ?: if (useTls) TLS_PORT else PLAIN_PORT
    val connectionsOrDefault: Int get() = connections ?: CONNECTIONS

    // The host and username too: the logs name neither, and a login may have been typed into the host.
    override fun toString() =
        "ProviderDraft(name=${shown(name)}, host=${shown(host)}, port=$port, useTls=$useTls, username=${shown(username)}, " +
            "password=${shown(password)}, connections=$connections)"

    companion object {
        const val TLS_PORT = 563
        const val PLAIN_PORT = 119
        const val CONNECTIONS = 8
    }
}

/** An indexer as a card holds it; blanks are "". An empty [apiKey] keeps the saved one, as for a provider's password. */
data class IndexerDraft(val name: String, val url: String, val apiKey: String) {
    // The address too: a key may have been pasted into its query.
    override fun toString() = "IndexerDraft(name=${shown(name)}, url=${shown(url)}, apiKey=${shown(apiKey)})"
}

/**
 * A debrid service account as a card holds it: its service ([kind], a [ServiceChoice.value]; "" while none is chosen)
 * and the API key typed. An empty [key] keeps the saved one, for the same service only (the app's rule).
 */
data class ServiceDraft(val kind: String, val key: String) {
    override fun toString() = "ServiceDraft(kind=$kind, key=${shown(key)})"
}

/**
 * A torrent indexer as a card holds it: an indexer's fields, and whether it is used with the debrid service
 * ([useWithService], ticked by default). An empty [key] keeps the saved one, as an indexer's.
 */
data class TorrentIndexerDraft(val name: String, val url: String, val key: String, val useWithService: Boolean) {
    // The address too: a key may have been pasted into its query.
    override fun toString() = "TorrentIndexerDraft(name=${shown(name)}, url=${shown(url)}, key=${shown(key)}, useWithService=$useWithService)"
}

/**
 * Why a form was refused: the [field] at fault (null when it is the form as a whole, a name the page
 * doesn't have, the buttons' [SetupForm.INTENT] or the page's [SetupForm.VERSION]), and the [reason].
 * Never what was sent: a refusal may be shown or logged. [Reason.Characters] and [Reason.NotANumber]
 * are a field's problems, never a refusal: the field keeps its earlier value.
 */
data class SetupRejection(val field: SetupField?, val reason: Reason) : SetupParse {
    enum class Reason {
        /**
         * Not a form a browser would send: bad escapes, not UTF-8, a pair without "=", a checkbox's, a radio's, a button's
         * or the counter's odd value, a button or a choice the page doesn't have.
         */
        Malformed,
        TooManyFields,
        /** A name the page doesn't have: none but the open card's fields. */
        UnknownField,
        Repeated,
        TooLong,
        /** A character the field may not hold. */
        Characters,
        NotANumber,
    }
}

/**
 * Reads a setup page's form, as a browser sends it (application/x-www-form-urlencoded, UTF-8), into
 * a [SetupPost] or a [SetupRejection]. Strict: a form the page couldn't have produced is refused
 * rather than guessed at. Nothing here throws on what was sent, or puts any of it in a message.
 */
object SetupForm {
    /** Fields a body may have, empty ones included, before any is read. */
    const val MAX_FIELDS = 64

    /** The name the page's buttons send their action's value under. It is no [SetupField]: it belongs to no card. */
    const val INTENT = "do"

    /** The name the page's render counter goes under, in a hidden field: a form from an older page applies nothing. */
    const val VERSION = "v"

    /**
     * Reads a form from a page laid out as [layout] says: its render counter, the button pressed (by its
     * [SetupAction.value]) and the open card's fields. A form from an older page (another counter) reads as such, with
     * nothing of it kept, so what its page had isn't held against this one. A character or a number the TV can't hold
     * is a problem for its field, not a refusal: one bad field mustn't keep the phone from another card's buttons.
     * What no page sends (bad escapes, not UTF-8, unknown or repeated names, too many fields, a value past its field's
     * limit, an odd checkbox, a choice the open card doesn't offer, a counter or a button that isn't one, or one this page
     * doesn't have) is refused.
     */
    internal fun parse(body: ByteArray, layout: SetupLayout): SetupParse {
        val pairs = split(body) ?: return SetupRejection(null, SetupRejection.Reason.TooManyFields)
        val values = mutableMapOf<SetupField, String>()
        val problems = mutableMapOf<SetupField, SetupRejection.Reason>()
        val sent = mutableSetOf<SetupField>()
        var action: SetupAction? = null
        var version: Int? = null
        for ((start, end) in pairs) {
            if (start == end) continue
            val equals = (start until end).firstOrNull { body[it] == '='.code.toByte() }
                ?: return SetupRejection(null, SetupRejection.Reason.Malformed)
            val name = decode(body, start, equals) ?: return SetupRejection(null, SetupRejection.Reason.Malformed)
            val value = decode(body, equals + 1, end)
            when (name) {
                INTENT -> {
                    if (action != null) return SetupRejection(null, SetupRejection.Reason.Repeated)
                    action = value?.let(SetupAction::of) ?: return SetupRejection(null, SetupRejection.Reason.Malformed)
                }
                VERSION -> {
                    if (version != null) return SetupRejection(null, SetupRejection.Reason.Repeated)
                    // As the page writes it: no sign, no leading zero, within an Int.
                    version = value?.takeIf { it.length in 1..9 && it[0] in '1'..'9' && it.all { c -> c in '0'..'9' } }?.toInt()
                        ?: return SetupRejection(null, SetupRejection.Reason.Malformed)
                }
                else -> {
                    val field = SetupField.entries.firstOrNull { it.key == name } ?: return SetupRejection(null, SetupRejection.Reason.UnknownField)
                    if (!sent.add(field)) return SetupRejection(field, SetupRejection.Reason.Repeated)
                    val raw = value ?: return SetupRejection(field, SetupRejection.Reason.Malformed)
                    when (val reason = check(field, raw)) {
                        null -> values[field] = clean(field, raw)
                        SetupRejection.Reason.Characters, SetupRejection.Reason.NotANumber -> problems[field] = reason
                        else -> return SetupRejection(field, reason)
                    }
                }
            }
        }
        if (action == null || version == null) return SetupRejection(null, SetupRejection.Reason.Malformed)
        if (version != layout.version) return SetupPost(version, action)
        if (!sent.all { it in layout.fields }) return SetupRejection(null, SetupRejection.Reason.UnknownField)
        // A radio's value is one its card offers: any other (a service another card holds, say) is no form this page sends.
        values.entries.firstOrNull { (field, value) -> field.kind == SetupField.Kind.Choice && value !in layout.choices }
            ?.let { return SetupRejection(it.key, SetupRejection.Reason.Malformed) }
        if (!layout.offers(action)) return SetupRejection(null, SetupRejection.Reason.Malformed)
        return SetupPost(version, action, values, problems)
    }

    /** The [start, end) of each &-separated pair, or null past [MAX_FIELDS]. */
    private fun split(body: ByteArray): List<Pair<Int, Int>>? {
        val pairs = mutableListOf<Pair<Int, Int>>()
        var start = 0
        for (i in 0..body.size) {
            if (i == body.size || body[i] == '&'.code.toByte()) {
                if (pairs.size == MAX_FIELDS) return null
                pairs += start to i
                start = i + 1
            }
        }
        // An empty body is no fields, not one empty one.
        return if (body.isEmpty()) emptyList() else pairs
    }

    /** [body]'s bytes from [start] to [end], with + as a space and %XX escapes, as UTF-8; null when not exactly that. */
    private fun decode(body: ByteArray, start: Int, end: Int): String? {
        val bytes = ByteArray(end - start)
        var n = 0
        var i = start
        while (i < end) {
            val b = body[i]
            when (b) {
                '+'.code.toByte() -> bytes[n++] = ' '.code.toByte()
                '%'.code.toByte() -> {
                    if (i + 2 >= end) return null
                    val high = hex(body[i + 1]) ?: return null
                    val low = hex(body[i + 2]) ?: return null
                    bytes[n++] = (high * 16 + low).toByte()
                    i += 2
                }
                else -> bytes[n++] = b
            }
            i++
        }
        // REPORT, not the default REPLACE: bytes that aren't UTF-8 refuse the form rather than turn into U+FFFD.
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, 0, n)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun hex(b: Byte): Int? = when (val c = b.toInt().toChar()) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    /** Why [raw] can't be [field]'s value, or null when it can. */
    internal fun check(field: SetupField, raw: String): SetupRejection.Reason? {
        if (raw.length > field.maxLength) return SetupRejection.Reason.TooLong
        return when (field.kind) {
            SetupField.Kind.Text -> SetupRejection.Reason.Characters.takeIf { raw.any(::spoofs) }
            // It goes on an AUTHINFO PASS line: only what would end or garble the line is refused.
            SetupField.Kind.Password -> SetupRejection.Reason.Characters.takeIf { raw.any { it < ' ' || it == '\u007f' } }
            SetupField.Kind.Key -> SetupRejection.Reason.Characters.takeIf { raw.trim(' ').any { it !in '!'..'~' } }
            SetupField.Kind.Number -> SetupRejection.Reason.NotANumber.takeUnless { raw.trim(' ').let { it.length <= 9 && it.all { c -> c in '0'..'9' } } }
            SetupField.Kind.Checkbox -> SetupRejection.Reason.Malformed.takeUnless { raw == "on" }
            // A radio's value as the page writes one: a word in lower case. Whether its card offers it is the layout's.
            SetupField.Kind.Choice -> SetupRejection.Reason.Malformed.takeUnless { raw.isNotEmpty() && raw.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } }
        }
    }

    /**
     * A character that would break a line or let what the TV shows read as something else: controls
     * (C0, DEL and C1), the line and paragraph separators, bidi controls and zero-width characters.
     */
    internal fun spoofs(c: Char) =
        c < ' ' || c in '\u007f'..'\u009f' || c == ' ' || c == ' ' || c in '‪'..'‮' ||
            c in '⁦'..'⁩' || c in '​'..'‏' || c == '﻿'

    private fun clean(field: SetupField, raw: String) = when (field.kind) {
        SetupField.Kind.Password -> raw
        SetupField.Kind.Key, SetupField.Kind.Number -> raw.trim(' ')
        else -> raw.trim()
    }
}
