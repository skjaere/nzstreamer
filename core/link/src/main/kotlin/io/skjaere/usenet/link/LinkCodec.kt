package io.skjaere.usenet.link

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** Why a frame's bytes aren't a message; the connection closes. All a caller learns: never the bytes (L13). */
enum class BadFrame {
    /** Not UTF-8. */
    NotUtf8,

    /** Not JSON. */
    NotJson,

    /**
     * Brackets nested deeper than [LinkCodec.MAX_DEPTH], as no message is: kotlinx-serialization's reader recurses on an
     * array, and a frame of them would overflow its stack.
     */
    TooDeep,

    /** JSON, but not an object with its type in a string `t`. */
    NoType,

    /** A type it knows, with a field it can't read as that type has it: the wrong kind of value, or one missing. */
    BadFields,
}

/** What a frame's bytes read as. */
sealed interface Decoded {
    class Read(val value: LinkMessage) : Decoded {
        override fun toString() = "Read"
    }

    data class Bad(val reason: BadFrame) : Decoded
}

/**
 * [LinkMessage]s as frames' bytes: UTF-8 JSON with the type in `t` (L5). Fields a newer peer adds are ignored, and its
 * types and enum values read as `Unknown` (L26).
 *
 * Reading never throws and never says what it read: kotlinx-serialization's exceptions quote their input ("JSON input:
 * …"), and a frame may hold a title, so they are caught here and only a [BadFrame] goes on (L13).
 */
object LinkCodec {
    private val json = Json {
        classDiscriminator = TYPE
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            polymorphic(LinkMessage::class) {
                defaultDeserializer { LinkMessage.Unknown.serializer() }
            }
        }
    }

    /** [message]'s bytes, for [LinkFrames.encode]; null when they wouldn't fit in a frame. */
    fun encode(message: LinkMessage): ByteArray? =
        json.encodeToString(LinkMessage.serializer(), message).encodeToByteArray().takeIf { it.size <= LinkFrames.MAX_PAYLOAD }

    fun decode(payload: ByteArray): Decoded {
        val text = try {
            // Strict: newDecoder() reports a malformed byte where decodeToString() would put in U+FFFD.
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString()
        } catch (_: CharacterCodingException) {
            return Decoded.Bad(BadFrame.NotUtf8)
        }
        if (tooDeep(text)) return Decoded.Bad(BadFrame.TooDeep)
        val tree = try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return Decoded.Bad(BadFrame.NotJson)
        } catch (_: IllegalArgumentException) {
            return Decoded.Bad(BadFrame.NotJson)
        }
        val type = ((tree as? JsonObject)?.get(TYPE) as? JsonPrimitive)?.takeIf { it.isString }
        if (type == null) return Decoded.Bad(BadFrame.NoType)
        return try {
            Decoded.Read(json.decodeFromJsonElement(LinkMessage.serializer(), tree))
        } catch (_: SerializationException) {
            Decoded.Bad(BadFrame.BadFields)
        } catch (_: IllegalArgumentException) {
            // NumberFormatException among them, for a number too big for its field.
            Decoded.Bad(BadFrame.BadFields)
        }
    }

    /** Objects and arrays nested in a message at most, its own object counted: a few more than v1's deepest. */
    const val MAX_DEPTH = 32

    /** Whether [text] opens more than [MAX_DEPTH] brackets at once, outside its strings. */
    private fun tooDeep(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            when {
                escaped -> escaped = false
                inString -> when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                c == '"' -> inString = true
                c == '{' || c == '[' -> if (++depth > MAX_DEPTH) return true
                c == '}' || c == ']' -> depth--
            }
        }
        return false
    }

    private const val TYPE = "t"
}
