package io.skjaere.usenet.debrid

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A torrent's v1 info hash, [hex]: 40 lower-case hex digits, all a service is ever told of a torrent (L159). Never a
 * `.torrent`, a tracker, a name, the indexer or a details link.
 */
@Serializable(with = InfoHash.Serializer::class)
@JvmInline
value class InfoHash private constructor(val hex: String) {
    /** The magnet a service is sent where it takes one: the exact topic alone, `magnet:?xt=urn:btih:<hex>`. */
    fun xtMagnet(): String = "magnet:?xt=urn:btih:$hex"

    override fun toString() = hex

    companion object {
        /** [text] as a v1 hash: 40 hex digits, or 32 base32 characters as some magnets carry it; null for anything else. */
        fun parse(text: String?): InfoHash? {
            val value = text?.trim() ?: return null
            return when {
                HEX.matches(value) -> InfoHash(value.lowercase())
                BASE32.matches(value) -> InfoHash(base32ToHex(value.uppercase()))
                else -> null
            }
        }

        /** [text] as [parse] reads it; IllegalArgumentException for anything else. */
        fun of(text: String): InfoHash = requireNotNull(parse(text)) { "Not a v1 info hash" }

        private val HEX = Regex("[0-9a-fA-F]{40}")
        private val BASE32 = Regex("[A-Za-z2-7]{32}")
        private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private const val HEX_DIGITS = "0123456789abcdef"

        // core:newznab reads magnets with a copy of this (its TorrentHashes is internal there): 32 characters of 5 bits
        // are the hash's 20 bytes.
        private fun base32ToHex(text: String): String {
            val hex = StringBuilder(40)
            var buffer = 0
            var bits = 0
            for (c in text) {
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
    }

    /** As its [hex]; a stored value that isn't a v1 hash doesn't read. */
    internal object Serializer : KSerializer<InfoHash> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("io.skjaere.usenet.debrid.InfoHash", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: InfoHash) = encoder.encodeString(value.hex)

        override fun deserialize(decoder: Decoder): InfoHash =
            parse(decoder.decodeString()) ?: throw SerializationException("Not a v1 info hash")
    }
}
