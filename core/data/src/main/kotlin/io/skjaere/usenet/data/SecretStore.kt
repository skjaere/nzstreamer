package io.skjaere.usenet.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.security.InvalidKeyException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class Sealed(val ciphertext: String, val iv: String)

@Serializable
data class SecretsFile(val entries: Map<String, Sealed> = emptyMap())

interface SecretCipher {
    fun seal(plain: ByteArray): Sealed
    fun open(sealed: Sealed): ByteArray
}

/**
 * AES-256-GCM with a key generated inside AndroidKeyStore: the key can't be exported, so the
 * secrets file is useless off this device. The app's backup and data extraction rules keep the
 * file out of backups and device-to-device transfers; one that arrives anyway reads as absent
 * (see [SecretStore]).
 */
class KeystoreCipher(private val alias: String = "usenet-secrets") : SecretCipher {
    // Loaded on first use, so constructing this (at app start, on the main thread) does no keystore work.
    private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    // Looked up once rather than on every call. Locked so two first uses can't each generate a key
    // under the alias, the second replacing the key the first one sealed with.
    private var cachedKey: SecretKey? = null

    @Synchronized
    private fun key(): SecretKey = cachedKey ?: (
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        ).also { cachedKey = it }

    @Synchronized
    private fun forget(key: SecretKey) {
        if (cachedKey === key) cachedKey = null
    }

    /**
     * A cipher set up by [initWith] with the key. A cached key the keystore has since lost fails init;
     * it is then looked up again, and regenerated if gone, as an uncached lookup would.
     */
    private fun initCipher(initWith: Cipher.(SecretKey) -> Unit): Cipher {
        val key = key()
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { initWith(key) }
        } catch (e: InvalidKeyException) {
            forget(key)
            Cipher.getInstance(TRANSFORMATION).apply { initWith(key()) }
        }
    }

    override fun seal(plain: ByteArray): Sealed {
        val cipher = initCipher { init(Cipher.ENCRYPT_MODE, it) }
        return Sealed(b64.encodeToString(cipher.doFinal(plain)), b64.encodeToString(cipher.iv))
    }

    override fun open(sealed: Sealed): ByteArray {
        val cipher = initCipher { init(Cipher.DECRYPT_MODE, it, GCMParameterSpec(128, b64d.decode(sealed.iv))) }
        return cipher.doFinal(b64d.decode(sealed.ciphertext))
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val b64: Base64.Encoder = Base64.getEncoder()
        val b64d: Base64.Decoder = Base64.getDecoder()
    }
}

/**
 * Encrypted key/value secrets. A secret that can't be decrypted (the keystore key was lost,
 * e.g. after a restore onto another device) reads as absent, so the user is asked again.
 * Main-safe: the cipher's keystore calls (binder round trips into the TEE) run on [ioDispatcher].
 */
class SecretStore(
    private val file: DataStore<SecretsFile>,
    private val cipher: SecretCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun get(name: String): String? = sealed(name)?.let { open(it) }

    /** [name]'s secret as stored: what a caller that keeps [open]'s answer tells a new one by. */
    internal suspend fun sealed(name: String): Sealed? = file.data.first().entries[name]

    /** null removes the secret. */
    suspend fun put(name: String, value: String?) {
        val sealed = value?.let { withContext(ioDispatcher) { cipher.seal(it.encodeToByteArray()) } }
        file.updateData { current ->
            current.copy(entries = if (sealed == null) current.entries - name else current.entries + (name to sealed))
        }
    }

    /** Whether [get] would return the secret: one that can't be decrypted is absent here too. */
    fun has(name: String): Flow<Boolean> =
        file.data.map { it.entries[name] }.distinctUntilChanged().map { sealed -> sealed?.let { open(it) } != null }

    /** Null when it can't be decrypted. */
    internal suspend fun open(sealed: Sealed): String? =
        withContext(ioDispatcher) { runCatching { cipher.open(sealed).decodeToString() }.getOrNull() }
}
