package io.skjaere.usenet.data

import androidx.datastore.core.DataStoreFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecretStoreTest {
    @TempDir
    lateinit var dir: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fakeMain = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()
    private val fakeIo = Executors.newSingleThreadExecutor { Thread(it, "fake-io") }.asCoroutineDispatcher()

    /** Reversible stand-in for the keystore cipher that records the thread each call ran on. */
    private class RecordingCipher : SecretCipher {
        val threads: MutableList<String> = Collections.synchronizedList(mutableListOf())

        // Coroutine debug mode suffixes thread names with " @coroutine#n".
        private val thread get() = Thread.currentThread().name.substringBefore(" @")

        override fun seal(plain: ByteArray): Sealed {
            threads += "seal@$thread"
            return Sealed(String(plain.reversedArray()), "iv")
        }
        override fun open(sealed: Sealed): ByteArray {
            threads += "open@$thread"
            return sealed.ciphertext.reversed().encodeToByteArray()
        }
    }

    private val cipher = RecordingCipher()

    private fun store(ioDispatcher: CoroutineDispatcher? = null): SecretStore {
        val file = DataStoreFactory.create(JsonFileSerializer(SecretsFile.serializer(), SecretsFile()), scope = scope) { File(dir, "secrets.json") }
        return if (ioDispatcher == null) SecretStore(file, cipher) else SecretStore(file, cipher, ioDispatcher)
    }

    @AfterEach
    fun stop() {
        scope.cancel()
        fakeMain.close()
        fakeIo.close()
    }

    @Test
    fun `keystore crypto stays off the calling (main) thread`() = runBlocking<Unit> {
        val secrets = store()
        val read = withContext(fakeMain) {
            secrets.put("tmdb:key", "abc123")
            secrets.get("tmdb:key")
        }
        assertEquals("abc123", read)
        assertEquals(listOf("seal", "open"), cipher.threads.map { it.substringBefore('@') })
        assertTrue(cipher.threads.none { it.endsWith("@fake-main") }, "cipher ran on the calling thread: ${cipher.threads}")
    }

    @Test
    fun `keystore crypto runs on the injected io dispatcher`() = runBlocking<Unit> {
        val secrets = store(fakeIo)
        withContext(fakeMain) {
            secrets.put("indexer:a", "K1")
            assertEquals("K1", secrets.get("indexer:a"))
            secrets.put("indexer:a", null)
            assertEquals(null, secrets.get("indexer:a"))
        }
        assertEquals(listOf("seal@fake-io", "open@fake-io"), cipher.threads)
    }

    @Test
    fun `has decrypts the secret, on the injected io dispatcher`() = runBlocking<Unit> {
        val secrets = store(fakeIo)
        withContext(fakeMain) {
            secrets.put("tmdb:key", "abc123")
            assertTrue(secrets.has("tmdb:key").first())
        }
        assertEquals(listOf("seal@fake-io", "open@fake-io"), cipher.threads)
    }
}
