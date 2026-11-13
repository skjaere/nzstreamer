package io.skjaere.usenet.app.settings

import androidx.datastore.core.DataStore
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.SecretCipher
import io.skjaere.usenet.data.SecretStore
import io.skjaere.usenet.data.SecretsFile
import io.skjaere.usenet.data.Sealed
import io.skjaere.usenet.data.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

/**
 * A real SettingsRepository over stores in memory, its secrets sealed by a stand-in for the keystore on [secrets] (a
 * test's own dispatcher keeps them on its clock), for JVM tests of what saves settings. [cipher]: one that can be told
 * which secrets it can't read, as a device that lost its keystore key.
 */
internal fun memorySettings(
    secrets: CoroutineDispatcher = Dispatchers.IO,
    cipher: SecretCipher = PlainCipher,
    /** Awaited before each read of the settings, for a test that holds a read part way. */
    beforeRead: (suspend () -> Unit)? = null,
): SettingsRepository = SettingsRepository(MemoryStore(AppSettings(), beforeRead), SecretStore(MemoryStore(SecretsFile()), cipher, secrets))

/** A stand-in for the keystore that can't open the secrets in [unreadable], by their values: as after a device move. */
internal class TestCipher : SecretCipher {
    val unreadable: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    override fun seal(plain: ByteArray) = PlainCipher.seal(plain)

    override fun open(sealed: Sealed): ByteArray = PlainCipher.open(sealed).also { check(it.decodeToString() !in unreadable) { "key gone" } }
}

private class MemoryStore<T>(initial: T, beforeRead: (suspend () -> Unit)? = null) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()
    override val data: Flow<T> = if (beforeRead == null) state else flow { beforeRead(); emitAll(state) }
    override suspend fun updateData(transform: suspend (t: T) -> T): T = mutex.withLock { transform(state.value).also { state.value = it } }
}

private object PlainCipher : SecretCipher {
    override fun seal(plain: ByteArray) = Sealed(Base64.getEncoder().encodeToString(plain), "iv")
    override fun open(sealed: Sealed): ByteArray = Base64.getDecoder().decode(sealed.ciphertext)
}
