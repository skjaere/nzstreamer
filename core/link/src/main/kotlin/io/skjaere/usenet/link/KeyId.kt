package io.skjaere.usenet.link

import java.security.MessageDigest
import java.security.PublicKey

/**
 * A key's id: the SHA-256 of its SubjectPublicKeyInfo, the bytes a certificate's `publicKey.encoded` gives (L2). A
 * pairing pins it, the TV keeps its phones by it and the phone its TVs, and [short] is the TV's `id` in its mDNS record
 * (L27). It names a public key, so it is no secret: a peer's certificate shows as much.
 */
class KeyId private constructor(private val digest: ByteArray) {
    /** All 64 hex digits, lowercase. */
    val hex: String get() = digest.joinToString("") { "%02x".format(it) }

    /** The first 16 hex digits (64 bits): enough to tell a household's TVs apart in mDNS. Never what is pinned. */
    val short: String get() = hex.substring(0, SHORT)

    override fun equals(other: Any?) = other is KeyId && MessageDigest.isEqual(digest, other.digest)

    override fun hashCode() = digest.contentHashCode()

    override fun toString() = "KeyId($short)"

    companion object {
        private const val SHORT = 16

        /** The id of the key whose SubjectPublicKeyInfo is [spki]. */
        fun of(spki: ByteArray): KeyId = KeyId(MessageDigest.getInstance("SHA-256").digest(spki))

        fun of(key: PublicKey): KeyId = of(key.encoded)

        /** The id [hex] spells, as [KeyId.hex] gives it and a store keeps it: 64 lowercase hex digits; null for anything else. */
        fun parse(hex: String): KeyId? {
            if (hex.length != DIGITS) return null
            val digest = ByteArray(DIGITS / 2)
            for (i in digest.indices) {
                val high = HEX.indexOf(hex[i * 2])
                val low = HEX.indexOf(hex[i * 2 + 1])
                if (high < 0 || low < 0) return null
                digest[i] = ((high shl 4) or low).toByte()
            }
            return KeyId(digest)
        }

        private const val DIGITS = 64
        private const val HEX = "0123456789abcdef"
    }
}
