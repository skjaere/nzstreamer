package io.skjaere.usenet.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

internal val storeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** DataStore serializer for a kotlinx-serializable value stored as JSON. */
internal class JsonFileSerializer<T>(
    private val serializer: KSerializer<T>,
    override val defaultValue: T,
) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T = try {
        storeJson.decodeFromString(serializer, input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable ${serializer.descriptor.serialName}", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("Unreadable ${serializer.descriptor.serialName}", e)
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(storeJson.encodeToString(serializer, t).encodeToByteArray())
    }
}
