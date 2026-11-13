package io.skjaere.usenet.newznab

import java.net.URLDecoder

/**
 * A torrent's v1 info hash as Torznab feeds carry it (L159, L168): 40 hex digits, lower-cased here. Feeds give it in an
 * `infohash` attribute, or inside a magnet's `xt=urn:btih:`, as 40 hex digits or as 32 base32 characters (BEP 9). A
 * hybrid magnet holds a v2 hash (`urn:btmh:`) beside it; a v2-only magnet holds none, and no debrid service is asked
 * about one. Nothing else of a magnet is read: its trackers (`tr=`, where a private tracker's passkey is) and its name
 * (`dn=`) are never kept.
 */
internal object TorrentHashes {
    private val HEX = Regex("[0-9a-fA-F]{40}")
    private val BASE32 = Regex("[A-Za-z2-7]{32}")
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val BTIH = "urn:btih:"

    /** [text] as a v1 info hash: 40 hex digits, lower-cased; null for anything else. */
    fun hex(text: String?): String? = text?.trim()?.takeIf { HEX.matches(it) }?.lowercase()

    /** The v1 info hash of the magnet [uri]; null when it isn't a magnet or holds none. */
    fun ofMagnet(uri: String?): String? {
        val text = uri?.trim() ?: return null
        if (!text.startsWith("magnet:?", ignoreCase = true)) return null
        return text.substring("magnet:?".length).split('&').asSequence()
            // BEP 9 numbers several exact topics as xt.1, xt.2, ...
            .filter { it.substringBefore('=').lowercase().let { name -> name == "xt" || name.startsWith("xt.") } }
            .mapNotNull { decoded(it.substringAfter('=', "")) }
            .filter { it.startsWith(BTIH, ignoreCase = true) }
            .firstNotNullOfOrNull { topic -> topic.substring(BTIH.length).let { hex(it) ?: base32ToHex(it) } }
    }

    /**
     * The hash of the first of [attribute] (an `infohash`) and the magnets among [places] that has one. Prowlarr and
     * Jackett can copy into the attribute whatever follows a magnet's first topic (L237): 40 hex or 32 base32 is a v1
     * hash; a v2 one (64 hex, or the multihash `1220…`) is none, and a hybrid magnet beside it may still give the v1 hash.
     */
    fun of(attribute: String?, places: List<String?>): String? =
        attribute?.trim()?.let { hex(it) ?: base32ToHex(it) } ?: places.firstNotNullOfOrNull(::ofMagnet)

    // The charset-name overload: the Charset one needs Android API 33. A malformed escape makes the topic unreadable.
    private fun decoded(value: String): String? =
        try {
            URLDecoder.decode(value, Charsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            null
        }

    /** 32 base32 characters (RFC 4648, either case) as the 20 bytes' 40 hex digits; null for anything else. */
    private fun base32ToHex(text: String): String? {
        if (!BASE32.matches(text)) return null
        val hex = StringBuilder(40)
        var buffer = 0
        var bits = 0
        for (c in text.uppercase()) {
            buffer = (buffer shl 5) or BASE32_ALPHABET.indexOf(c)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                val byte = (buffer shr bits) and 0xff
                hex.append(HEX_DIGITS[byte shr 4]).append(HEX_DIGITS[byte and 0xf])
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return hex.toString()
    }

    private const val HEX_DIGITS = "0123456789abcdef"
}
