package io.skjaere.usenet.handoff

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The secret in a setup page's address: 128 random bits as 26 characters of lowercase Crockford
 * base32, which has no i, l, o or u, so a code read off the TV and typed by hand has nothing to
 * mistake. Never printed: [toString] hides it, and nothing may log [value] or [grouped].
 */
class SetupToken private constructor(
    /** The 26 characters, as they go in the QR code's address. */
    val value: String,
) {
    /** For reading off the TV: in fours, joined by "-", which [matches] ignores (and a browser keeps in a typed path). */
    val grouped: String get() = value.chunked(4).joinToString("-")

    /**
     * Whether [candidate], typed or scanned, is this token: after [normalise], compared in constant
     * time, so how long a wrong guess takes says nothing about how much of it was right.
     */
    fun matches(candidate: String): Boolean =
        MessageDigest.isEqual(normalise(candidate).toByteArray(Charsets.UTF_8), value.toByteArray(Charsets.UTF_8))

    override fun toString() = "SetupToken(hidden)"

    companion object {
        private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"
        private const val BYTES = 16
        const val LENGTH = 26

        private val random by lazy { SecureRandom() }

        fun generate(): SetupToken = of(ByteArray(BYTES).also(random::nextBytes))

        /** The token for [bytes] (16 of them), read from the first bit on, five bits a character; the last one is padded with zeros. */
        internal fun of(bytes: ByteArray): SetupToken {
            require(bytes.size == BYTES) { "a token is $BYTES bytes" }
            val chars = StringBuilder(LENGTH)
            var buffer = 0
            var bits = 0
            for (b in bytes) {
                buffer = (buffer shl 8) or (b.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    chars.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                    bits -= 5
                }
                buffer = buffer and ((1 shl bits) - 1)
            }
            if (bits > 0) chars.append(ALPHABET[(buffer shl (5 - bits)) and 31])
            return SetupToken(chars.toString())
        }

        /**
         * [text] as a token is compared: lowercase, without the "-" and spaces a person may group it
         * with, and with the letters base32 leaves out read as the digits they look like (o as 0, i and
         * l as 1).
         */
        fun normalise(text: String): String = buildString(text.length) {
            for (c in text) {
                // ASCII only: no other script's letter may fold into one of the alphabet's.
                when (val lower = if (c in 'A'..'Z') c + ('a' - 'A') else c) {
                    '-', ' ' -> Unit
                    'o' -> append('0')
                    'i', 'l' -> append('1')
                    else -> append(lower)
                }
            }
        }

        /** Whether [text] looks like a token once normalised: 26 base32 characters, right or wrong. */
        fun isTokenShaped(text: String): Boolean = normalise(text).let { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }
}
