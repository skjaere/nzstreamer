package io.skjaere.usenet.app.plugins

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 digests in lower-case hex, as the plugin list, the registry and tools/beta-cert.sha256 write them. */
internal object Sha256 {
    fun of(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** [file]'s digest, read through once. */
    fun of(file: File): String = file.inputStream().use(::of)

    fun of(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return hex(digest.digest())
    }

    fun hex(digest: ByteArray): String = digest.joinToString("") { "%02x".format(it) }
}
