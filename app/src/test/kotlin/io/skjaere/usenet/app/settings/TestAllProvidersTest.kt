package io.skjaere.usenet.app.settings

import androidx.datastore.core.DataStore
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ProviderWithPassword
import io.skjaere.usenet.data.SecretCipher
import io.skjaere.usenet.data.SecretStore
import io.skjaere.usenet.data.SecretsFile
import io.skjaere.usenet.data.Sealed
import io.skjaere.usenet.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The providers list's "Test all" over the real settings: the list stays open under a provider's
 * screen, so a provider can be deleted, switched off or edited while the others are tested (S9-8).
 */
class TestAllProvidersTest {
    private class MemoryStore<T>(initial: T) : DataStore<T> {
        private val state = MutableStateFlow(initial)
        private val mutex = Mutex()
        override val data: Flow<T> = state
        override suspend fun updateData(transform: suspend (t: T) -> T): T = mutex.withLock { transform(state.value).also { state.value = it } }
    }

    private object PlainCipher : SecretCipher {
        override fun seal(plain: ByteArray) = Sealed(Base64.getEncoder().encodeToString(plain), "iv")
        override fun open(sealed: Sealed): ByteArray = Base64.getDecoder().decode(sealed.ciphertext)
    }

    private val settings = SettingsRepository(MemoryStore(AppSettings()), SecretStore(MemoryStore(SecretsFile()), PlainCipher))
    private val tests = ProviderTests()
    private val testing = MutableStateFlow<String?>(null)

    /** Who was tested, as "name:password". */
    private val tested = mutableListOf<String>()

    private suspend fun save(name: String, password: String = "pw-$name") =
        settings.saveProvider(ProviderRecord("", name, "news.example.net", 563, "user-$name"), password)

    /** As the provider screen's Delete does. */
    private suspend fun delete(id: String) {
        settings.deleteProvider(id)
        tests.forget(id)
    }

    /** Test all, where testing a provider first runs what [meanwhile] says for it, as the user would while it is tested. */
    private suspend fun testAll(meanwhile: suspend (ProviderWithPassword) -> Unit = {}) =
        testEnabledProviders(settings, tests, testing) { p ->
            tested += "${p.record.name}:${p.password}"
            meanwhile(p)
            ProviderTestResult(ProviderLogin.of(p.record, p.password.orEmpty()), ok = true, message = "Connected")
        }

    @Test
    fun `providers deleted or switched off while others are tested aren't tested, nor given a result`() = runBlocking<Unit> {
        val a = save("A")
        val b = save("B")
        val c = save("C")
        testAll { p ->
            if (p.record.id == a) {
                delete(c)
                settings.setProviderEnabled(b, false)
            }
        }
        assertEquals(listOf("A:pw-A"), tested)
        assertEquals(setOf(a), tests.results.value.keys)
        assertNull(testing.value)
    }

    @Test
    fun `a provider deleted while it is tested keeps no result`() = runBlocking<Unit> {
        val a = save("A")
        val b = save("B")
        val c = save("C")
        testAll { p -> if (p.record.id == b) delete(b) }
        assertEquals(listOf("A:pw-A", "B:pw-B", "C:pw-C"), tested)
        assertEquals(setOf(a, c), tests.results.value.keys)
    }

    @Test
    fun `a provider edited while others are tested is tested as it is saved then`() = runBlocking<Unit> {
        val a = save("A")
        val b = save("B")
        testAll { p -> if (p.record.id == a) settings.saveProvider(settings.provider(b)!!.record, "new") }
        assertEquals(listOf("A:pw-A", "B:new"), tested)
        assertEquals(setOf(a, b), tests.results.value.keys)
    }
}
