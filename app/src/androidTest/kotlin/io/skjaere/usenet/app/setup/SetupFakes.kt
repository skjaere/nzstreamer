package io.skjaere.usenet.app.setup

import androidx.datastore.core.DataStore
import io.skjaere.usenet.app.settings.IndexerTest
import io.skjaere.usenet.app.settings.ProviderForm
import io.skjaere.usenet.app.settings.ProviderLogin
import io.skjaere.usenet.app.settings.ProviderTestResult
import io.skjaere.usenet.app.settings.ProviderTests
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.SecretCipher
import io.skjaere.usenet.data.SecretStore
import io.skjaere.usenet.data.SecretsFile
import io.skjaere.usenet.data.Sealed
import io.skjaere.usenet.data.SettingsRepository
import io.skjaere.usenet.handoff.IndexerDraft
import io.skjaere.usenet.handoff.ProviderDraft
import io.skjaere.usenet.handoff.SetupChange
import io.skjaere.usenet.handoff.SetupChanges
import io.skjaere.usenet.handoff.SetupEvent
import io.skjaere.usenet.handoff.SetupTester
import io.skjaere.usenet.metadata.KeyCheck
import io.skjaere.usenet.newznab.IndexerCaps
import io.skjaere.usenet.newznab.IndexerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone setup's page over fakes, for its screen tests: sessions whose events the test sends, tests that answer as
 * told, and settings of their own in memory, so nothing the device has saved is touched. Invented values throughout.
 */
class SetupFakes(private val lifetimeMillis: Long = 600_000) {
    val settings = SettingsRepository(MemoryStore(AppSettings()), SecretStore(MemoryStore(SecretsFile()), PlainCipher))
    val addresses = MutableStateFlow<Inet4Address?>(HOME)
    val sessions = CopyOnWriteArrayList<FakeSession>()
    @Volatile var provider: suspend (ProviderForm, String) -> ProviderTestResult = { f, password -> result(f, password, ok = true) }
    @Volatile var indexer: suspend (IndexerConfig) -> IndexerTest = { IndexerTest.Ok(IndexerCaps(serverTitle = "Example"), null) }
    @Volatile var tmdb: suspend (String) -> KeyCheck = { KeyCheck.Valid }
    val tested = CopyOnWriteArrayList<String>()

    val deps = PhoneSetupDeps(
        settings = settings,
        providerTests = ProviderTests(),
        addresses = addresses,
        openSession = { opening -> FakeSession(opening, sessions.size, android.os.SystemClock.elapsedRealtime() + lifetimeMillis).also { sessions += it } },
        testProvider = { f, password -> tested += "provider"; provider(f, password) },
        testIndexer = { tested += "indexer"; indexer(it) },
        validateTmdbKey = { tested += "tmdb"; tmdb(it) },
        now = android.os.SystemClock::elapsedRealtime,
    )

    val session: FakeSession get() = sessions.last()

    /** [changes] from the phone, on the open session, as its page hands them over at Send. */
    fun receive(changes: SetupChanges = ALL) {
        session.events(SetupEvent.Opened(PHONE))
        session.events(SetupEvent.Received(changes, PHONE))
    }

    /**
     * A session's page: an address of the real shape, its code made from [number], until [deadline]; [opening] is what
     * its server would open with (the mode, the seed, the tester).
     */
    class FakeSession(val opening: SetupOpening, number: Int, override val deadline: Long) : SetupSession {
        val ip: Inet4Address get() = opening.ip
        val tester: SetupTester get() = opening.tester
        val events: (SetupEvent) -> Unit get() = opening.onEvent
        private val code = CODE.dropLast(1) + "0123456789"[number % 10]
        override val url = "http://${ip.hostAddress}:41234/$code"
        override val display = "${ip.hostAddress}:41234/${code.chunked(4).joinToString("-")}"
        @Volatile var closed = false

        override fun close() {
            closed = true
        }
    }

    companion object {
        val HOME: Inet4Address = v4("192.168.1.23")
        val PHONE: Inet4Address = v4("192.168.1.40")
        const val CODE = "k7m2q9xd0a1b2c3d4e5f6g7h8j"
        const val PASSWORD = "invented-password-42"
        const val INDEXER_KEY = "INVENTEDINDEXERKEY0000111122223333"
        const val TMDB_KEY = "inventedtmdbkey000011112222333344"
        /** A new provider, a new indexer and a TMDB key, as a wizard's page sends them. */
        val ALL = SetupChanges(
            listOf(SetupChange(null, ProviderDraft("", "news.example.net", null, true, "alice", PASSWORD, null), removed = false)),
            listOf(SetupChange(null, IndexerDraft("", "https://indexer.example.org/api", INDEXER_KEY), removed = false)),
            TMDB_KEY,
        )

        fun v4(text: String): Inet4Address = InetAddress.getByAddress(text.split('.').map { it.toInt().toByte() }.toByteArray()) as Inet4Address

        fun result(f: ProviderForm, password: String, ok: Boolean) = ProviderTestResult(
            ProviderLogin.of(f.host, f.portValue ?: 563, f.username, f.useTls, password), ok,
            if (ok) "Connected and logged in to ${f.host.trim()}" else "The provider rejected the username or password.",
        )
    }
}

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
