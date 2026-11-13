package io.skjaere.usenet.app.link

import io.skjaere.usenet.link.KeyId
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The TV's mDNS record (L27), type [TYPE]. Its instance name is the TV's own (L28), cut to 63 bytes at a character's
 * boundary. Its TXT says nothing of what plays, or whether anything does: `pv`, the newest protocol version; `id`, 16 hex
 * digits of the TV's key's SHA-256, by which a phone tells TVs apart (NSD renames a name that clashes, "Name (2)"), and
 * never what is pinned; `pair=1` only while Pair a phone takes pairings; and `v4`, the address the TV listens on, for a
 * phone whose resolve gives one IPv6 address on old Android. Nothing else.
 */
data class ServiceRecord(val name: String, val pv: Int?, val id: String, val pairing: Boolean, val v4: Inet4Address?) {
    val txt: Map<String, String>
        get() = buildMap {
            pv?.let { put(PV, it.toString()) }
            put(ID, id)
            if (pairing) put(PAIR, "1")
            v4?.let { put(V4, it.hostAddress!!) }
        }

    companion object {
        const val TYPE = "_nzstreamer._tcp"

        /** The longest instance name DNS-SD has, in UTF-8 bytes. */
        const val MAX_NAME_BYTES = 63

        private const val PV = "pv"
        private const val ID = "id"
        private const val PAIR = "pair"
        private const val V4 = "v4"
        private val ID_DIGITS = Regex("[0-9a-f]{16}")
        private val VERSION = Regex("[1-9][0-9]{0,3}")
        private val PART = "(0|[1-9][0-9]{0,2})"
        private val DOTTED = Regex("$PART\\.$PART\\.$PART\\.$PART")

        /** The TV's record: [name] its own, cut to fit; [key] the TV's; [v4] the address it listens on. */
        fun of(name: String, pv: Int, key: KeyId, pairing: Boolean, v4: Inet4Address?) = ServiceRecord(instanceName(name), pv, key.short, pairing, v4)

        /** [name] cut to [MAX_NAME_BYTES] bytes of UTF-8, at the end of a character (a code point), never inside one. */
        fun instanceName(name: String): String {
            var bytes = 0
            var end = 0
            while (end < name.length) {
                val point = name.codePointAt(end)
                val size = when {
                    point < 0x80 -> 1
                    point < 0x800 -> 2
                    point < 0x10000 -> 3
                    else -> 4
                }
                if (bytes + size > MAX_NAME_BYTES) break
                bytes += size
                end += Character.charCount(point)
            }
            return name.substring(0, end)
        }

        /**
         * A record discovery found, from its instance [name] and [txt]; null without a well-formed `id`, which makes it
         * someone else's. The name is a peer's, cleaned ("TV" for an empty one); what else the record says is taken only
         * when it is what it should be: a version, `pair=1`, a private IPv4 address written out (never a name to look up).
         */
        fun read(name: String, txt: Map<String, ByteArray?>): ServiceRecord? {
            val id = txt[ID]?.decodeToString()?.takeIf(ID_DIGITS::matches) ?: return null
            val pv = txt[PV]?.decodeToString()?.takeIf(VERSION::matches)?.toInt()
            val pairing = txt[PAIR]?.decodeToString() == "1"
            val v4 = txt[V4]?.decodeToString()?.let(::privateIpv4)
            return ServiceRecord(PeerNames.clean(name, "TV"), pv, id, pairing, v4)
        }

        /** [text] as an RFC 1918 IPv4 address written as four numbers; null for anything else. */
        private fun privateIpv4(text: String): Inet4Address? {
            val parts = DOTTED.matchEntire(text)?.groupValues?.drop(1)?.map { it.toInt() } ?: return null
            if (parts.any { it > 255 }) return null
            val address = InetAddress.getByAddress(parts.map { it.toByte() }.toByteArray()) as Inet4Address
            return address.takeIf { it.isSiteLocalAddress }
        }
    }
}
