package io.skjaere.usenet.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import io.skjaere.usenet.releases.QualityPreference
import io.skjaere.usenet.releases.RankingPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsRepositoryTest {
    @TempDir
    lateinit var dir: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var time = 1_000L
    private val clock = Clock { time++ }

    /**
     * Reversible stand-in for the keystore cipher; [broken] simulates a lost key. It counts [opens]; with
     * a [gate] set, the next open counts the gate's first latch down and waits for its second.
     */
    private class FakeCipher : SecretCipher {
        var broken = false
        val opens = AtomicInteger()
        @Volatile var gate: Pair<CountDownLatch, CountDownLatch>? = null
        override fun seal(plain: ByteArray) = Sealed(Base64.getEncoder().encodeToString(plain.reversedArray()), "iv")
        override fun open(sealed: Sealed): ByteArray {
            opens.incrementAndGet()
            gate?.let { (entered, release) ->
                gate = null
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            check(!broken) { "key gone" }
            return Base64.getDecoder().decode(sealed.ciphertext).reversedArray()
        }
    }

    /**
     * [store], whose writes begun while [hold] is set are held once stored, until it completes: a set that returns late, or
     * never. A test that holds a write that doesn't stop for a cancel (a sign-out's, a save's) lets it go however the test
     * ends, or its runBlocking would wait for it for good. Such a test waits for its write ([watchFor]) with no time bound of
     * its own: a write to disk takes what it takes, seconds on a busy CI runner, and the test's limit (set in the build)
     * fails one that never lands.
     */
    private class HeldStore<T>(private val store: DataStore<T>) : DataStore<T> {
        @Volatile var hold: CompletableDeferred<Unit>? = null
        override val data: Flow<T> get() = store.data
        override suspend fun updateData(transform: suspend (t: T) -> T): T {
            val held = hold
            return store.updateData(transform).also { held?.await() }
        }
    }

    /**
     * Watches [flow] for a value [predicate] takes, and returns once the watch has its first value: a write begun after
     * that is seen. A test that waits for a write starts watching before it writes. A DataStore's data started while a
     * write is under way misses that write: it reads the old file under the new write's version, then drops the new value
     * as no newer. It would only see the next write, and a test with none waits for good.
     */
    private suspend fun <T> CoroutineScope.watchFor(flow: Flow<T>, predicate: (T) -> Boolean): Deferred<T> {
        val watching = CompletableDeferred<Unit>()
        return async(Dispatchers.IO) { flow.onEach { watching.complete(Unit) }.first(predicate) }.also { watching.await() }
    }

    private val cipher = FakeCipher()
    private val secretsFile by lazy {
        HeldStore(DataStoreFactory.create(JsonFileSerializer(SecretsFile.serializer(), SecretsFile()), scope = scope) { File(dir, "secrets.json") })
    }
    private val secrets by lazy { SecretStore(secretsFile, cipher) }
    // The app's own store, its migrations with it.
    private val settingsFile by lazy { HeldStore(settingsStore(scope) { File(dir, "settings.json") }) }
    private val repository by lazy {
        var ids = 0
        SettingsRepository(settingsFile, secrets, clock) { "id-${++ids}" }
    }

    @AfterEach
    fun stop() = scope.cancel()

    @Test
    fun `indexer keys live only in the secret store`() = runBlocking<Unit> {
        val id = repository.saveIndexer(null, " My indexer ", "https://indexer.example.org/api ", "SECRETKEY42")
        assertEquals("id-1", id)
        val saved = repository.indexers.first().single()
        assertEquals(IndexerRecord("id-1", "My indexer", "https://indexer.example.org/api", true, updatedAt = 1_000), saved)
        assertEquals("SECRETKEY42", repository.searchableIndexers().single().apiKey)
        assertFalse("SECRETKEY42" in File(dir, "settings.json").readText())
        assertFalse("SECRETKEY42" in File(dir, "secrets.json").readText())
        assertFalse("SECRETKEY42" in repository.searchableIndexers().toString())
    }

    @Test
    fun `editing without a new key keeps the old one`() = runBlocking<Unit> {
        val id = repository.saveIndexer(null, "A", "https://a.example/api", "K1")
        repository.saveIndexer(id, "A renamed", "https://a.example/api", apiKey = null)
        val indexer = repository.indexer(id)!!
        assertEquals("A renamed", indexer.record.name)
        assertEquals("K1", indexer.apiKey)
        assertEquals(1, repository.indexers.first().size)
    }

    /** The order is the one Search lists them in: editing one, or replacing it from the phone, must not move it. */
    @Test
    fun `saving an indexer again keeps its place`() = runBlocking<Unit> {
        val a = repository.saveIndexer(null, "A", "https://a.example/api", "K1")
        val b = repository.saveIndexer(null, "B", "https://b.example/api", "K2")
        val c = repository.saveIndexer(null, "C", "https://c.example/api", "K3")
        repository.saveIndexer(a, "A renamed", "https://a2.example/api", apiKey = null, enabled = false)
        assertEquals(listOf(a, b, c), repository.indexers.first().map { it.id })
        assertEquals("A renamed", repository.indexer(a)!!.record.name)
        assertEquals("K1", repository.indexer(a)!!.apiKey)
        // A new one still goes last.
        val d = repository.saveIndexer(null, "D", "https://d.example/api", "K4")
        assertEquals(listOf(a, b, c, d), repository.indexers.first().map { it.id })
    }

    @Test
    fun `deleting leaves a tombstone and removes the key`() = runBlocking<Unit> {
        val id = repository.saveIndexer(null, "A", "https://a.example/api", "K1")
        repository.deleteIndexer(id)
        assertEquals(emptyList(), repository.indexers.first())
        val tombstone = repository.settings.first().indexers.single()
        assertTrue(tombstone.deleted)
        assertTrue(tombstone.updatedAt > 1_000)
        assertNull(repository.indexer(id))
        assertFalse(File(dir, "secrets.json").readText().contains("indexer:$id"))
    }

    @Test
    fun `disabled indexers and indexers whose key can't be read are not searched`() = runBlocking<Unit> {
        val a = repository.saveIndexer(null, "A", "https://a.example/api", "K1")
        repository.saveIndexer(null, "B", "https://b.example/api", "K2")
        repository.setIndexerEnabled(a, false)
        assertEquals(listOf("B"), repository.searchableIndexers().map { it.record.name })
        cipher.broken = true
        assertEquals(emptyList(), repository.searchableIndexers())
    }

    /** A key the TV holds but can't read now is told from none: the phone setup says it must be typed again. */
    @Test
    fun `a key stored but unreadable is told from none`() = runBlocking<Unit> {
        val a = repository.saveIndexer(null, "A", "https://a.example/api", "K1")
        val none = repository.saveIndexer(null, "B", "https://b.example/api", null)
        assertTrue(repository.indexerKeyStored(a))
        assertFalse(repository.indexerKeyStored(none))
        assertFalse(repository.tmdbKeyStored())
        repository.setTmdbKey("abc123")
        assertTrue(repository.tmdbKeyStored())
        cipher.broken = true
        assertNull(repository.indexer(a)?.apiKey)
        assertNull(repository.tmdbKey())
        assertTrue(repository.indexerKeyStored(a), "a key that can't be read is still stored")
        assertTrue(repository.tmdbKeyStored())
        repository.deleteIndexer(a)
        assertFalse(repository.indexerKeyStored(a))
        repository.setTmdbKey(null)
        assertFalse(repository.tmdbKeyStored())
    }

    /** L194: torrent indexers are a list of their own, so nothing that reads the Usenet indexers counts them. */
    @Test
    fun `a settings file from before torrent indexers reads none`() = runBlocking<Unit> {
        val older = """{"schemaVersion":1,"indexers":[{"id":"a","name":"A","baseUrl":"https://a.example/api"}],"playback":{"quality":"Auto"}}"""
        val read = JsonFileSerializer(AppSettings.serializer(), AppSettings()).readFrom(older.byteInputStream())
        assertEquals(emptyList(), read.torrentIndexers)
        assertEquals(listOf("A"), read.activeIndexers.map { it.name })
        assertEquals(emptyList(), repository.torrentIndexers.first())
        assertEquals(emptyList(), repository.searchableTorrentIndexers())
    }

    @Test
    fun `a torrent indexer's key lives only in the secret store, under its own name`() = runBlocking<Unit> {
        val id = repository.saveTorrentIndexer(null, " Home Prowlarr ", "http://192.168.1.20:9696/1 ", " a3f9c2e81b7d4c60 ")
        assertEquals("id-1", id)
        assertEquals(TorrentIndexerRecord("id-1", "Home Prowlarr", "http://192.168.1.20:9696/1", updatedAt = 1_000), repository.torrentIndexers.first().single())
        assertEquals("torrentIndexer:id-1:apikey", repository.torrentIndexers.first().single().apiKeySecret)
        assertEquals("a3f9c2e81b7d4c60", repository.torrentIndexer(id)?.apiKey)
        assertEquals("a3f9c2e81b7d4c60", repository.searchableTorrentIndexers().single().apiKey)
        assertTrue("torrentIndexer:id-1:apikey" in File(dir, "secrets.json").readText())
        assertFalse("a3f9c2e81b7d4c60" in File(dir, "settings.json").readText())
        assertFalse("a3f9c2e81b7d4c60" in File(dir, "secrets.json").readText())
        assertFalse("a3f9c2e81b7d4c60" in repository.searchableTorrentIndexers().toString())
        // The Usenet indexers are another list: neither counts the other.
        assertEquals(emptyList(), repository.indexers.first())
        assertEquals(emptyList(), repository.searchableIndexers())
        repository.saveIndexer(null, "Usenet", "https://u.example/api", "K1")
        assertEquals(listOf("Home Prowlarr"), repository.torrentIndexers.first().map { it.name })
    }

    @Test
    fun `use with your debrid service is on until switched off, apart from use for searches`() = runBlocking<Unit> {
        val id = repository.saveTorrentIndexer(null, "Quarry", "http://192.168.1.20:9696/2", "K2")
        assertTrue(repository.torrentIndexer(id)!!.record.useWithService)
        repository.setUseWithService(id, false)
        val off = repository.torrentIndexer(id)!!.record
        assertFalse(off.useWithService)
        assertTrue(off.enabled, "still searched")
        assertTrue(off.updatedAt > 1_000)
        assertEquals(listOf(id), repository.searchableTorrentIndexers().map { it.record.id })
        repository.setTorrentIndexerEnabled(id, false)
        assertEquals(emptyList(), repository.searchableTorrentIndexers())
        assertFalse(repository.torrentIndexer(id)!!.record.useWithService, "switching searches off leaves the other switch")
        // Saving from the form keeps the key and its place, and takes the switches as given.
        val second = repository.saveTorrentIndexer(null, "Home Prowlarr", "http://192.168.1.20:9696/1", "K3")
        repository.saveTorrentIndexer(id, "Quarry", "http://192.168.1.20:9696/2", apiKey = null, enabled = true, useWithService = true)
        assertEquals(listOf(id, second), repository.torrentIndexers.first().map { it.id })
        assertEquals("K2", repository.torrentIndexer(id)?.apiKey)
        assertTrue(repository.torrentIndexer(id)!!.record.useWithService)
        assertEquals(listOf(id, second), repository.searchableTorrentIndexers().map { it.record.id })
        // A key that can't be read leaves the indexer out of searches, as an indexer's does.
        cipher.broken = true
        assertEquals(emptyList(), repository.searchableTorrentIndexers())
    }

    @Test
    fun `deleting a torrent indexer leaves a tombstone and removes its key`() = runBlocking<Unit> {
        val id = repository.saveTorrentIndexer(null, "Home Prowlarr", "http://192.168.1.20:9696/1", "K1")
        repository.deleteTorrentIndexer(id)
        assertEquals(emptyList(), repository.torrentIndexers.first())
        val tombstone = repository.settings.first().torrentIndexers.single()
        assertTrue(tombstone.deleted)
        assertFalse(tombstone.enabled)
        assertTrue(tombstone.updatedAt > 1_000)
        assertNull(repository.torrentIndexer(id))
        assertFalse(File(dir, "secrets.json").readText().contains("torrentIndexer:$id"))
    }

    /** The user's own debrid accounts (L196): what the service said whose account a key opens, for the cases below. */
    private val torBoxAccount = ServiceSignIn("quay.viewer@example.org", plan = "Standard", planUntil = 1_799_712_000_000)

    /** A sign-in that takes [taken] alone, as a service takes only the account's key; it counts what it was asked. */
    private class SignIns(private val taken: Set<String>, private val answer: ServiceSignIn) {
        val asked = mutableListOf<String>()
        suspend fun signIn(key: String): ServiceSignIn {
            asked += key
            if (key !in taken) throw IllegalStateException("refused")
            return answer
        }
    }

    @Test
    fun `a settings file from before service accounts reads none`() = runBlocking<Unit> {
        val older = """{"schemaVersion":1,"indexers":[{"id":"a","name":"A","baseUrl":"https://a.example/api"}],"torrentIndexers":[]}"""
        val read = JsonFileSerializer(AppSettings.serializer(), AppSettings()).readFrom(older.byteInputStream())
        assertEquals(emptyList(), read.services)
        assertEquals(emptyList(), read.activeServices)
        assertEquals(emptyList(), repository.services.first())
        assertEquals(emptyList(), repository.servicesWithKeys())
    }

    @Test
    fun `a service's key is tested first and kept only in the secret store, under its own name`() = runBlocking<Unit> {
        val signIns = SignIns(setOf("aaaaaaaa-0000-4000-8000-0000000000aa"), torBoxAccount)
        val id = repository.saveService("TorBox", " aaaaaaaa-0000-4000-8000-0000000000aa ", signIns::signIn)
        assertEquals(listOf("aaaaaaaa-0000-4000-8000-0000000000aa"), signIns.asked, "tested with the key as trimmed")
        assertEquals(
            ServiceAccount(id, "TorBox", accountName = "quay.viewer@example.org", plan = "Standard", planUntil = 1_799_712_000_000, updatedAt = 1_000, keySavedAt = 1_000),
            repository.services.first().single(),
        )
        assertEquals("service:$id:apikey", repository.services.first().single().apiKeySecret)
        assertEquals("aaaaaaaa-0000-4000-8000-0000000000aa", repository.service(id)?.apiKey)
        assertEquals(listOf("aaaaaaaa-0000-4000-8000-0000000000aa"), repository.servicesWithKeys().map { it.apiKey })
        assertTrue("service:$id:apikey" in File(dir, "secrets.json").readText())
        assertFalse("aaaaaaaa" in File(dir, "settings.json").readText())
        assertFalse("aaaaaaaa" in File(dir, "secrets.json").readText())
        // Neither the key nor the account's name in what a log line might print.
        val printed = repository.servicesWithKeys().toString() + repository.services.first().toString() + torBoxAccount.toString()
        assertFalse("aaaaaaaa" in printed, printed)
        assertFalse("quay.viewer" in printed, printed)
    }

    @Test
    fun `a key the service refuses is saved nowhere`() = runBlocking<Unit> {
        val signIns = SignIns(emptySet(), torBoxAccount)
        assertFailsWith<IllegalStateException> { repository.saveService("TorBox", "a-key-it-refuses", signIns::signIn) }
        assertEquals(listOf("a-key-it-refuses"), signIns.asked)
        assertEquals(emptyList(), repository.settings.first().services)
        assertFalse("service:" in (File(dir, "secrets.json").takeIf { it.exists() }?.readText() ?: ""))
        assertFailsWith<IllegalArgumentException> { repository.saveService("TorBox", "  ", signIns::signIn) }
        assertEquals(listOf("a-key-it-refuses"), signIns.asked, "an empty key is never sent")
    }

    @Test
    fun `one account per service, and saving one again takes the new key in its place`() = runBlocking<Unit> {
        val signIns = SignIns(setOf("TB-1", "TB-2", "PM-1"), torBoxAccount)
        val torBox = repository.saveService("TorBox", "TB-1", signIns::signIn)
        val premiumize = repository.saveService("Premiumize", "PM-1", SignIns(setOf("PM-1"), ServiceSignIn("customer 900000042", planUntil = 1_803_600_000_000))::signIn)
        repository.setServiceEnabled(torBox, false)
        assertEquals(torBox, repository.saveService("TorBox", "TB-2", signIns::signIn))
        assertEquals(listOf(torBox, premiumize), repository.services.first().map { it.id })
        assertEquals("TB-2", repository.service(torBox)?.apiKey)
        assertFalse(repository.service(torBox)!!.record.enabled, "still off, as it was")
        assertEquals("customer 900000042", repository.service(premiumize)!!.record.accountName)
        assertNull(repository.service(premiumize)!!.record.plan)
    }

    @Test
    fun `a new key replaces the old only once the service takes it, and a refused key is marked until then`() = runBlocking<Unit> {
        val signIns = SignIns(setOf("TB-1", "TB-NEW"), torBoxAccount)
        val id = repository.saveService("TorBox", "TB-1", signIns::signIn)
        repository.markKeyRefused(id)
        assertTrue(repository.service(id)!!.record.keyRefused)
        assertTrue(repository.service(id)!!.record.updatedAt > 1_000)
        assertFailsWith<IllegalStateException> { repository.replaceKey(id, "TB-WRONG", signIns::signIn) }
        assertEquals("TB-1", repository.service(id)?.apiKey, "the old key stays until a new one is taken")
        assertTrue(repository.service(id)!!.record.keyRefused)
        val renewed = SignIns(setOf("TB-NEW"), torBoxAccount.copy(plan = "Pro"))
        repository.replaceKey(id, " TB-NEW ", renewed::signIn)
        assertEquals(listOf("TB-NEW"), renewed.asked)
        val replaced = repository.service(id)!!
        assertEquals("TB-NEW", replaced.apiKey)
        assertFalse(replaced.record.keyRefused)
        assertEquals("Pro", replaced.record.plan)
        assertEquals(listOf(id), repository.services.first().map { it.id })
    }

    @Test
    fun `a test of the saved key keeps what the service says now`() = runBlocking<Unit> {
        val id = repository.saveService("TorBox", "TB-1", SignIns(setOf("TB-1"), torBoxAccount)::signIn)
        repository.updateSignIn(id, ServiceSignIn("quay.viewer@example.org", plan = "Pro", planUntil = 1_830_000_000_000))
        val record = repository.service(id)!!.record
        assertEquals("Pro", record.plan)
        assertEquals(1_830_000_000_000, record.planUntil)
        assertEquals("TB-1", repository.service(id)?.apiKey)
    }

    /**
     * A key refused once (a proxy's 403, a short outage) and then taken by its page's Test is the account's again: the
     * passed Test ends the mark, so the page no longer says the key was refused under "Signed in as".
     */
    @Test
    fun `a test the service passes ends a refused key's mark, its key kept`() = runBlocking<Unit> {
        val id = repository.saveService("TorBox", "TB-1", SignIns(setOf("TB-1"), torBoxAccount)::signIn)
        repository.markKeyRefused(id)
        repository.updateSignIn(id, torBoxAccount)
        val account = repository.service(id)!!
        assertFalse(account.record.keyRefused)
        assertEquals("TB-1", account.apiKey)
        assertEquals("quay.viewer@example.org", account.record.accountName)
    }

    @Test
    fun `switched off, a service keeps its key and its place`() = runBlocking<Unit> {
        val id = repository.saveService("TorBox", "TB-1", SignIns(setOf("TB-1"), torBoxAccount)::signIn)
        repository.setServiceEnabled(id, false)
        val off = repository.service(id)!!
        assertFalse(off.record.enabled)
        assertEquals("TB-1", off.apiKey)
        repository.setServiceEnabled(id, true)
        assertTrue(repository.service(id)!!.record.enabled)
    }

    @Test
    fun `signing out removes the key and the account, leaving a tombstone without its name`() = runBlocking<Unit> {
        val id = repository.saveService("TorBox", "TB-1", SignIns(setOf("TB-1"), torBoxAccount)::signIn)
        repository.signOut(id)
        assertEquals(emptyList(), repository.services.first())
        assertEquals(emptyList(), repository.servicesWithKeys())
        assertNull(repository.service(id))
        val tombstone = repository.settings.first().services.single()
        assertTrue(tombstone.deleted)
        assertFalse(tombstone.enabled)
        assertEquals("", tombstone.accountName)
        assertNull(tombstone.plan)
        assertNull(tombstone.planUntil)
        assertFalse("quay.viewer" in File(dir, "settings.json").readText())
        assertFalse(File(dir, "secrets.json").readText().contains("service:$id"))
        // Signed in again: a new account.
        val again = repository.saveService("TorBox", "TB-1", SignIns(setOf("TB-1"), torBoxAccount)::signIn)
        assertTrue(again != id)
        assertEquals(listOf(again), repository.services.first().map { it.id })
    }

    /**
     * L232: when the account's key was saved, which each record of an add keeps, so a record made under another key (one
     * that may open another account) is dropped with no call. Set at sign-in; a key written again moves it only when it
     * differs from the one stored, whether through Replace key or a second sign-in to the service; nothing else does.
     */
    @Test
    fun `when a service's key was saved changes with a different key, not with the same one`() = runBlocking<Unit> {
        val signIns = SignIns(setOf("TB-1", "TB-2"), torBoxAccount)
        suspend fun savedAt(id: String) = repository.service(id)!!.record.keySavedAt
        val id = repository.saveService("TorBox", "TB-1", signIns::signIn)
        val first = savedAt(id)
        assertTrue(first > 0, "set at sign-in: $first")
        repository.replaceKey(id, " TB-1 ", signIns::signIn)
        assertEquals(first, savedAt(id), "Replace key with the key it had")
        assertEquals(id, repository.saveService("TorBox", "TB-1", signIns::signIn))
        assertEquals(first, savedAt(id), "signed in again with the key it had")
        repository.replaceKey(id, "TB-2", signIns::signIn)
        val second = savedAt(id)
        assertTrue(second > first, "Replace key with another: $second after $first")
        assertEquals(id, repository.saveService("TorBox", "TB-1", signIns::signIn))
        val third = savedAt(id)
        assertTrue(third > second, "signed in again with another key: $third after $second")
        repository.updateSignIn(id, torBoxAccount.copy(plan = "Pro"))
        repository.setServiceEnabled(id, false)
        repository.markKeyRefused(id)
        repository.setRemoveAfterPlay(id, false)
        repository.moveService(id, by = 1)
        assertEquals(third, savedAt(id), "a Test, the switches, a refusal and a move leave it")
        // A refused key's mark ends with the same key taken again, still unmoved.
        repository.replaceKey(id, "TB-1", signIns::signIn)
        assertEquals(third, savedAt(id))
        assertFalse(repository.service(id)!!.record.keyRefused)
    }

    /**
     * EDL-2 (L232): an account's key and when it was saved are read as one. A key being replaced is written before the
     * time it was saved moves, and a read between the two would pair the new key with the old key's time: the old key's
     * adds then removed through the new key, which may open another account. A read waits for the write to end.
     */
    @Test
    fun `a service's key and when it was saved are read together, never the new key with the old one's time`() = runBlocking<Unit> {
        val signIns = SignIns(setOf("TB-1", "TB-2"), torBoxAccount)
        val id = repository.saveService("TorBox", "TB-1", signIns::signIn)
        val old = repository.service(id)!!.record.keySavedAt
        val held = CompletableDeferred<Unit>()
        secretsFile.hold = held
        val replacing = scope.async { repository.replaceKey(id, "TB-2", signIns::signIn) }
        val reads = try {
            // The new key stored, the time it was saved not moved yet. Each get starts a read of its own, so the first one
            // after the write lands sees the key, however long the write takes.
            while (secrets.get(ServiceAccount(id, "").apiKeySecret) != "TB-2") delay(10)
            secretsFile.hold = null
            scope.async { listOf(repository.servicesWithKeys().single(), repository.service(id)!!) }.also { delay(300) }
        } finally {
            held.complete(Unit)
        }
        replacing.await()
        reads.await().forEach { read ->
            assertTrue(read.apiKey == "TB-1" && read.record.keySavedAt == old || read.apiKey == "TB-2" && read.record.keySavedAt > old, "read as ${read.apiKey} saved at ${read.record.keySavedAt} (the old at $old)")
        }
    }

    /** L246, W83: Remove after playing is on until switched off, also for an account a settings file saved before it existed. */
    @Test
    fun `remove after playing is on until switched off, also in settings saved before it existed`() = runBlocking<Unit> {
        val older = """{"schemaVersion":2,"services":[{"id":"tb","kind":"TorBox","accountName":"quay.viewer@example.org"}]}"""
        val read = JsonFileSerializer(AppSettings.serializer(), AppSettings()).readFrom(older.byteInputStream()).services.single()
        assertTrue(read.removeAfterPlay)
        assertEquals(0L, read.keySavedAt, "no key saved since it was kept")
        val id = repository.saveService("TorBox", "TB-1", SignIns(setOf("TB-1"), torBoxAccount)::signIn)
        assertTrue(repository.service(id)!!.record.removeAfterPlay)
        repository.setRemoveAfterPlay(id, false)
        val off = repository.service(id)!!
        assertFalse(off.record.removeAfterPlay)
        assertTrue(off.record.enabled, "still on: only what its plays add stays")
        assertEquals("TB-1", off.apiKey)
        assertTrue(off.record.updatedAt > 1_000, "stamped")
        repository.setRemoveAfterPlay(id, true)
        assertTrue(repository.service(id)!!.record.removeAfterPlay)
        // Signed out of, nothing of it is switched.
        repository.signOut(id)
        repository.setRemoveAfterPlay(id, false)
        assertTrue(repository.settings.first().services.single().removeAfterPlay)
    }

    private val premiumizeAccount = ServiceSignIn("customer 900000042", planUntil = 1_803_600_000_000)

    private suspend fun serviceOrder() = repository.services.first().map { it.id }

    /**
     * L228, W85: an earlier version kept the services in the order they were signed in to and tried them A to Z; a file it
     * wrote is sorted A to Z once, as it is first read, and marked version 2 (3 since [DefaultReleasesToTry]), so nobody's
     * first service changes by itself.
     */
    @Test
    fun `a settings file from before the services' order reads them A to Z, once, as a later version`() = runBlocking<Unit> {
        File(dir, "settings.json").writeText(
            """{"schemaVersion":1,"indexers":[{"id":"a","name":"A","baseUrl":"https://a.example/api"}],""" +
                """"services":[{"id":"tb","kind":"TorBox","accountName":"quay.viewer@example.org"},""" +
                """{"id":"old","kind":"TorBox","enabled":false,"deleted":true},{"id":"pm","kind":"Premiumize","accountName":"customer 900000042"}]}""",
        )
        assertEquals(listOf("pm", "tb"), serviceOrder())
        assertEquals(3, repository.settings.first().schemaVersion)
        assertEquals(listOf("A"), repository.indexers.first().map { it.name }, "the rest as it was")
        assertTrue("\"schemaVersion\":3" in File(dir, "settings.json").readText(), "written back as version 3")
        // From then on the order is the user's: TorBox moved up stays first.
        repository.moveService("tb", by = -1)
        assertEquals(listOf("tb", "pm"), serviceOrder())
    }

    @Test
    fun `a version 2 file keeps its services' order`() = runBlocking<Unit> {
        File(dir, "settings.json").writeText(
            """{"schemaVersion":2,"services":[{"id":"tb","kind":"TorBox"},{"id":"pm","kind":"Premiumize"}]}""",
        )
        assertEquals(listOf("tb", "pm"), serviceOrder())
        assertEquals(3, repository.settings.first().schemaVersion)
    }

    @Test
    fun `a new settings file is version 3`() = runBlocking<Unit> {
        assertEquals(3, repository.settings.first().schemaVersion)
        repository.saveService("TorBox", "TB-1", SignIns(setOf("TB-1"), torBoxAccount)::signIn)
        assertTrue("\"schemaVersion\":3" in File(dir, "settings.json").readText())
    }

    /** L228: the services' order is the list's place, moved as Usenet providers are, tombstones keeping their places. */
    @Test
    fun `a service moves up and down by places, signed out ones keep their places, and a new one goes last`() = runBlocking<Unit> {
        val pm = repository.saveService("Premiumize", "PM-1", SignIns(setOf("PM-1"), premiumizeAccount)::signIn)
        val tb = repository.saveService("TorBox", "TB-1", SignIns(setOf("TB-1"), torBoxAccount)::signIn)
        assertEquals(listOf(pm, tb), serviceOrder())
        val before = repository.settings.first()
        // The first up, the last down and an id not saved change nothing.
        repository.moveService(pm, by = -1)
        repository.moveService(tb, by = 1)
        repository.moveService("unknown", by = -1)
        assertEquals(before, repository.settings.first())
        repository.moveService(tb, by = -1)
        assertEquals(listOf(tb, pm), serviceOrder())
        assertEquals(listOf(tb, pm), repository.servicesWithKeys().map { it.record.id }, "with their keys, in the order")
        assertTrue(repository.services.first().first().updatedAt > before.services.maxOf { it.updatedAt }, "the moved one stamped")
        repository.moveService(tb, by = 5)
        assertEquals(listOf(pm, tb), serviceOrder())

        // Signed out of: its tombstone keeps its place and isn't a place; signed in again, a new account, last.
        repository.signOut(pm)
        val again = repository.saveService("Premiumize", "PM-2", SignIns(setOf("PM-2"), premiumizeAccount)::signIn)
        assertEquals(listOf(tb, again), serviceOrder())
        repository.moveService(again, by = -1)
        assertEquals(listOf(again, tb), serviceOrder())
        assertEquals(listOf(pm, again, tb), repository.settings.first().services.map { it.id })
        // A tombstone can't be moved.
        repository.moveService(pm, by = 2)
        assertEquals(listOf(pm, again, tb), repository.settings.first().services.map { it.id })
    }

    /** The service keys the secret store holds, by name: each must be an account's that is still signed in. */
    private suspend fun serviceKeys(): Set<String> = secretsFile.data.first().entries.keys.filter { it.startsWith("service:") }.toSet()

    /**
     * Sign out's page may go (a TV's Back at once) while its writes run: they finish all the same, so the key the dialog
     * says is removed is, and no account is left with a key nothing shows.
     */
    @Test
    fun `a sign-out whose screen goes midway still removes the key`() = runBlocking<Unit> {
        val id = repository.saveService("TorBox", "TB-1", SignIns(setOf("TB-1"), torBoxAccount)::signIn)
        val held = CompletableDeferred<Unit>()
        settingsFile.hold = held
        val deleted = watchFor(repository.settings) { it.services.single().deleted }
        val out = launch(Dispatchers.IO) { repository.signOut(id) }
        try {
            deleted.await()
            out.cancel()
        } finally {
            held.complete(Unit)
        }
        out.join()
        assertNull(repository.service(id))
        assertEquals(emptySet(), serviceKeys())
    }

    /**
     * Save pressed again while the first Save writes (on a TV it stays enabled): the first, cancelled after its key was
     * stored, still keeps its account, and the second, signed in meanwhile, takes that account's place rather than adding
     * a second one. One account, holding the last key, and no key without its account.
     */
    @Test
    fun `a save cut short by a second press leaves one account and no key without it`() = runBlocking<Unit> {
        val taken = setOf("TB-1", "TB-2")
        val held = CompletableDeferred<Unit>()
        secretsFile.hold = held
        val keyStored = watchFor(secretsFile.data) { it.entries.keys.any { name -> name.startsWith("service:") } }
        val first = launch(Dispatchers.IO) { repository.saveService("TorBox", "TB-1", SignIns(taken, torBoxAccount)::signIn) }
        val second = try {
            keyStored.await()
            first.cancel()
            secretsFile.hold = null
            val signedIn = CompletableDeferred<Unit>()
            async(Dispatchers.IO) {
                repository.saveService("TorBox", "TB-2") { key -> SignIns(taken, torBoxAccount).signIn(key).also { signedIn.complete(Unit) } }
            }.also { signedIn.await() }
        } finally {
            held.complete(Unit)
        }
        val id = second.await()
        first.join()
        assertEquals(listOf(id), repository.services.first().map { it.id })
        assertEquals("TB-2", repository.service(id)?.apiKey)
        assertEquals(setOf("service:$id:apikey"), serviceKeys())
    }

    /** Replace key's new key, tested while the account was signed out of elsewhere: kept nowhere, as there's no account for it. */
    @Test
    fun `a new key for an account signed out of meanwhile is kept nowhere`() = runBlocking<Unit> {
        val id = repository.saveService("TorBox", "TB-1", SignIns(setOf("TB-1"), torBoxAccount)::signIn)
        repository.replaceKey(id, "TB-2") { _ -> repository.signOut(id); torBoxAccount }
        assertNull(repository.service(id))
        assertEquals(emptySet(), serviceKeys())
    }

    @Test
    fun `an account whose key can't be read is listed without it`() = runBlocking<Unit> {
        repository.saveService("TorBox", "TB-1", SignIns(setOf("TB-1"), torBoxAccount)::signIn)
        cipher.broken = true
        assertEquals(listOf(null), repository.servicesWithKeys().map { it.apiKey })
        assertEquals(listOf("TorBox"), repository.servicesWithKeys().map { it.record.kind })
    }

    @Test
    fun `tmdb key set and cleared`() = runBlocking<Unit> {
        assertFalse(repository.hasTmdbKey.first())
        repository.setTmdbKey("  abc123  ")
        assertEquals("abc123", repository.tmdbKey())
        assertTrue(repository.hasTmdbKey.first())
        repository.setTmdbKey(" ")
        assertNull(repository.tmdbKey())
    }

    @Test
    fun `a tmdb key that can't be decrypted reads as not set`() = runBlocking<Unit> {
        repository.setTmdbKey("abc123")
        cipher.broken = true
        assertNull(repository.tmdbKey())
        assertFalse(repository.hasTmdbKey.first())
    }

    @Test
    fun `preferences update with a timestamp`() = runBlocking<Unit> {
        repository.updatePlayback { it.copy(quality = QualityPreference.P1080.name, audioLanguages = listOf("de", "en")) }
        val playback = repository.playback.first()
        assertEquals(QualityPreference.P1080, playback.qualityPreference)
        assertEquals(listOf("de", "en"), playback.effectiveAudioLanguages("fr"))
        assertEquals(1_000, playback.updatedAt)
        assertEquals(listOf("de", "en"), PlaybackPrefs().effectiveAudioLanguages("de"))
        assertEquals(listOf("en"), PlaybackPrefs().effectiveAudioLanguages("en"))
    }

    @Test
    fun `looking up skip times online is off until turned on, also in settings saved before it existed`() = runBlocking<Unit> {
        assertFalse(repository.playback.first().lookUpSegmentsOnline)
        val older = """{"schemaVersion":1,"playback":{"quality":"Auto","autoPlayNext":true,"updatedAt":5}}"""
        assertFalse(JsonFileSerializer(AppSettings.serializer(), AppSettings()).readFrom(older.byteInputStream()).playback.lookUpSegmentsOnline)
        repository.updatePlayback { it.copy(lookUpSegmentsOnline = true) }
        assertTrue(repository.playback.first().lookUpSegmentsOnline)
    }

    @Test
    fun `matching the TV to the video's frame rate is off until turned on, also in settings saved before it existed`() = runBlocking<Unit> {
        assertFalse(repository.playback.first().matchFrameRate)
        val serializer = JsonFileSerializer(AppSettings.serializer(), AppSettings())
        val older = """{"schemaVersion":1,"playback":{"quality":"P1080","autoPlayNext":false,"lookUpSegmentsOnline":true,"updatedAt":5}}"""
        val read = serializer.readFrom(older.byteInputStream()).playback
        assertFalse(read.matchFrameRate)
        // The rest of the older file as it was.
        assertEquals(PlaybackPrefs(quality = "P1080", autoPlayNext = false, lookUpSegmentsOnline = true, updatedAt = 5), read)

        repository.updatePlayback { it.copy(matchFrameRate = true) }
        val saved = repository.playback.first()
        assertTrue(saved.matchFrameRate)
        assertEquals(1_000, saved.updatedAt)
        assertTrue(""""matchFrameRate":true""" in File(dir, "settings.json").readText())
        // A newer version's key beside it is ignored, and the file isn't taken for a corrupt one.
        val newer = """{"schemaVersion":1,"playback":{"matchFrameRate":true,"frameRateMatch":"Always","autoPlayNext":false}}"""
        val fromNewer = serializer.readFrom(newer.byteInputStream()).playback
        assertTrue(fromNewer.matchFrameRate)
        assertFalse(fromNewer.autoPlayNext)
        // Not a ranking preference: turned on mid-play, a prefetched search still ranks as the play does (TCR-1).
        assertEquals(read.rankingPrefs("en"), read.copy(matchFrameRate = true).rankingPrefs("en"))
    }

    @Test
    fun `the picture's size is Fit until chosen, also in settings saved before it existed`() = runBlocking<Unit> {
        val serializer = JsonFileSerializer(AppSettings.serializer(), AppSettings())
        val older = """{"schemaVersion":1,"playback":{"quality":"P1080","autoPlayNext":false,"lookUpSegmentsOnline":true,"updatedAt":5}}"""
        val read = serializer.readFrom(older.byteInputStream()).playback
        assertEquals(AspectMode.Fit to 1f, read.aspect to read.zoomScale)
        // The rest of the older file as it was.
        assertEquals(PlaybackPrefs(quality = "P1080", autoPlayNext = false, lookUpSegmentsOnline = true, updatedAt = 5), read)
        assertEquals(AspectMode.Fit, repository.playback.first().aspect)
        repository.updatePlayback { it.copy(aspectMode = AspectMode.Zoom.name, zoomScale = 1.6f) }
        val saved = repository.playback.first()
        assertEquals(AspectMode.Zoom to 1.6f, saved.aspect to saved.zoomScale)
        assertEquals(1_000, saved.updatedAt)
        assertTrue(""""aspectMode":"Zoom","zoomScale":1.6""" in File(dir, "settings.json").readText())
        // A mode only a newer version knows reads as Fit; the file isn't taken for a corrupt one.
        val newer = """{"schemaVersion":1,"playback":{"aspectMode":"Squeeze","zoomScale":2.0,"autoPlayNext":false}}"""
        val fromNewer = serializer.readFrom(newer.byteInputStream()).playback
        assertEquals(AspectMode.Fit, fromNewer.aspect)
        assertFalse(fromNewer.autoPlayNext)
    }

    @Test
    fun `a picture stretched out of shape, saved by an earlier version, reads as Fit, and the rest of its settings as they were`() = runBlocking<Unit> {
        // An earlier version's aspect button offered Stretch; this one only crops or zooms the picture, never changes its shape.
        File(dir, "settings.json").writeText(
            """{"schemaVersion":1,"playback":{"quality":"P1080","autoPlayNext":false,"aspectMode":"Stretch","zoomScale":1.0,"updatedAt":5}}""",
        )
        val playback = repository.playback.first()
        assertEquals(AspectMode.Fit, playback.aspect)
        assertEquals(PlaybackPrefs(quality = "P1080", autoPlayNext = false, aspectMode = "Stretch", updatedAt = 5), playback)
        assertEquals(listOf("Fit", "Fill", "FillBesideCamera", "Zoom"), AspectMode.entries.map { it.name })
    }

    @Test
    fun `releases to try are 10 until chosen, also in settings saved before the choice existed, and stay within 1 to 10`() = runBlocking<Unit> {
        val serializer = JsonFileSerializer(AppSettings.serializer(), AppSettings())
        val older = """{"schemaVersion":1,"playback":{"quality":"Auto","autoPlayNext":true,"updatedAt":5}}"""
        assertEquals(10, serializer.readFrom(older.byteInputStream()).playback.attempts)
        assertEquals(10, repository.playback.first().attempts)
        // Defaults are written too (encodeDefaults): a new file holds 10 from its first write.
        repository.updatePlayback { it.copy(autoPlayNext = false) }
        assertTrue(""""releasesToTry":10""" in File(dir, "settings.json").readText())
        repository.updatePlayback { it.copy(releasesToTry = 3) }
        assertEquals(3, repository.playback.first().attempts)
        assertTrue(""""releasesToTry":3""" in File(dir, "settings.json").readText())
        // A value no version offers is held to the choices' range rather than trusted.
        assertEquals(listOf(1, 1, 10), listOf(0, -4, 99).map { PlaybackPrefs(releasesToTry = it).attempts })
        assertEquals(listOf(1, 3, 5, 10), PlaybackPrefs.RELEASES_TO_TRY)
    }

    /**
     * Every field is written, defaults too (encodeDefaults), so a file saved while 3 was the default holds 3 as a choice
     * would: with a playback setting changed since (updatedAt), it stays 3. A file without the field (from before it, or
     * never saved) takes the default, now 10.
     */
    @Test
    fun `releases to try saved in an existing install's settings stay as they were, 3 included`() = runBlocking<Unit> {
        File(dir, "settings.json").writeText(
            """{"schemaVersion":2,"playback":{"quality":"Auto","autoPlayNext":false,"releasesToTry":3,"updatedAt":5}}""",
        )
        assertEquals(3, repository.playback.first().attempts)
        // Another setting saved writes it back as it was.
        repository.updatePlayback { it.copy(autoPlayNext = true) }
        assertEquals(3, repository.playback.first().releasesToTry)
        assertTrue(""""releasesToTry":3""" in File(dir, "settings.json").readText())
    }

    /**
     * Every change to a playback setting stamps playback's updatedAt, so a file with it still at 0 never had one chosen: its
     * 3 is the old default, not a choice, and it moves to the new default as the file is first read, written back as version
     * 3. A 3 with a playback setting chosen stays (above).
     */
    @Test
    fun `releases to try never chosen move from the old default 3 to 10`() = runBlocking<Unit> {
        File(dir, "settings.json").writeText(
            """{"schemaVersion":2,"playback":{"quality":"P1080","autoPlayNext":false,"releasesToTry":3,"updatedAt":0}}""",
        )
        assertEquals(PlaybackPrefs(quality = "P1080", autoPlayNext = false, releasesToTry = 10), repository.playback.first())
        assertEquals(3, repository.settings.first().schemaVersion)
        val written = File(dir, "settings.json").readText()
        assertTrue(""""releasesToTry":10""" in written && "\"schemaVersion\":3" in written, written)
        // From then on 3 is a choice like any other.
        repository.updatePlayback { it.copy(releasesToTry = 3) }
        assertEquals(3, repository.playback.first().attempts)
    }

    @Test
    fun `settings saved before releases to try existed take 10, and hold it once written back`() = runBlocking<Unit> {
        File(dir, "settings.json").writeText("""{"schemaVersion":2,"playback":{"quality":"Auto","autoPlayNext":false,"updatedAt":5}}""")
        assertEquals(10, repository.playback.first().attempts)
        repository.updatePlayback { it.copy(autoPlayNext = true) }
        assertTrue(""""releasesToTry":10""" in File(dir, "settings.json").readText())
    }

    @Test
    fun `the maximum bitrate is not set until chosen, also in settings saved before it existed, and ranks by it once set`() = runBlocking<Unit> {
        val serializer = JsonFileSerializer(AppSettings.serializer(), AppSettings())
        val older = """{"schemaVersion":1,"playback":{"quality":"P1080","audioLanguages":["de"],"autoPlayNext":false,"updatedAt":5}}"""
        val read = serializer.readFrom(older.byteInputStream()).playback
        assertNull(read.maxBitrate)
        // The rest of the older file as it was.
        assertEquals(PlaybackPrefs(quality = "P1080", audioLanguages = listOf("de"), autoPlayNext = false, updatedAt = 5), read)
        assertEquals(RankingPrefs(QualityPreference.P1080, listOf("de")), read.rankingPrefs("fr"))
        assertNull(repository.playback.first().maxBitrate)

        repository.updatePlayback { it.copy(maxBitrateMbps = 40) }
        val saved = repository.playback.first()
        assertEquals(40_000_000L, saved.maxBitrate)
        assertEquals(RankingPrefs(QualityPreference.Auto, listOf("fr", "en"), maxBitrate = 40_000_000), saved.rankingPrefs("fr"))
        assertTrue(""""maxBitrateMbps":40""" in File(dir, "settings.json").readText())
        repository.updatePlayback { it.copy(maxBitrateMbps = null) }
        assertNull(repository.playback.first().maxBitrate)
        // A value no version offers that can't be a speed reads as not set.
        assertEquals(listOf(null, null), listOf(0, -10).map { PlaybackPrefs(maxBitrateMbps = it).maxBitrate })
        // From 5 Mbps (W4), so a slow phone line has a value to be pointed at, to 200 (W102, L309), past a UHD disc copy's
        // estimate.
        assertEquals(listOf(5, 10, 15, 20, 30, 40, 60, 80, 100, 150, 200), PlaybackPrefs.MAX_BITRATES_MBPS)
    }

    /**
     * W52, L132: "Maximum bitrate when casting" is a setting of its own beside Maximum bitrate, not set until chosen, also
     * in settings saved before it, and read as Maximum bitrate is; the ranking gets both, and reads this one for a Cast
     * device alone.
     */
    @Test
    fun `the maximum bitrate when casting is not set until chosen, also in settings saved before it, and reads as the maximum bitrate does`() = runBlocking<Unit> {
        val serializer = JsonFileSerializer(AppSettings.serializer(), AppSettings())
        val older = """{"schemaVersion":1,"playback":{"quality":"P1080","autoPlayNext":false,"maxBitrateMbps":30,"updatedAt":5}}"""
        val read = serializer.readFrom(older.byteInputStream()).playback
        assertNull(read.castMaxBitrateMbps)
        assertNull(read.castMaxBitrate)
        // The rest of the older file as it was.
        assertEquals(PlaybackPrefs(quality = "P1080", autoPlayNext = false, maxBitrateMbps = 30, updatedAt = 5), read)
        assertEquals(RankingPrefs(QualityPreference.P1080, listOf("fr", "en"), maxBitrate = 30_000_000), read.rankingPrefs("fr"))
        assertNull(repository.playback.first().castMaxBitrate)

        repository.updatePlayback { it.copy(castMaxBitrateMbps = 40) }
        val saved = repository.playback.first()
        assertEquals(40_000_000L, saved.castMaxBitrate)
        // Apart from Maximum bitrate, which stays not set.
        assertNull(saved.maxBitrate)
        assertEquals(RankingPrefs(QualityPreference.Auto, listOf("fr", "en"), castMaxBitrate = 40_000_000), saved.rankingPrefs("fr"))
        assertTrue(""""castMaxBitrateMbps":40""" in File(dir, "settings.json").readText())
        repository.updatePlayback { it.copy(maxBitrateMbps = 30) }
        assertEquals(RankingPrefs(QualityPreference.Auto, listOf("fr", "en"), maxBitrate = 30_000_000, castMaxBitrate = 40_000_000), repository.playback.first().rankingPrefs("fr"))
        repository.updatePlayback { it.copy(castMaxBitrateMbps = null) }
        assertNull(repository.playback.first().castMaxBitrate)
        assertEquals(30_000_000L, repository.playback.first().maxBitrate)

        // As Maximum bitrate's: a value that can't be a speed (an edited file) reads as not set, and one no choice offers
        // as that many Mbps.
        assertEquals(listOf(null, null), listOf(0, -10).map { PlaybackPrefs(castMaxBitrateMbps = it).castMaxBitrate })
        assertEquals(25_000_000L, PlaybackPrefs(castMaxBitrateMbps = 25).castMaxBitrate)
        assertEquals(25_000_000L, PlaybackPrefs(maxBitrateMbps = 25).maxBitrate)
    }

    @Test
    fun `Fill beside camera is saved and read back like the other sizes, and settings saved before it existed still load`() = runBlocking<Unit> {
        val serializer = JsonFileSerializer(AppSettings.serializer(), AppSettings())
        repository.updatePlayback { it.copy(aspectMode = AspectMode.FillBesideCamera.name, zoomScale = 1f) }
        assertEquals(AspectMode.FillBesideCamera, repository.playback.first().aspect)
        assertTrue(""""aspectMode":"FillBesideCamera"""" in File(dir, "settings.json").readText())
        val saved = """{"schemaVersion":1,"playback":{"aspectMode":"FillBesideCamera","zoomScale":1.0,"autoPlayNext":false}}"""
        assertEquals(AspectMode.FillBesideCamera, serializer.readFrom(saved.byteInputStream()).playback.aspect)
        // Files from before the picture's size was saved, and from before this mode, read as they did.
        val older = """{"schemaVersion":1,"playback":{"quality":"Auto","autoPlayNext":true,"updatedAt":5}}"""
        assertEquals(AspectMode.Fit, serializer.readFrom(older.byteInputStream()).playback.aspect)
        val fill = """{"schemaVersion":1,"playback":{"aspectMode":"Fill","zoomScale":1.0}}"""
        assertEquals(AspectMode.Fill, serializer.readFrom(fill.byteInputStream()).playback.aspect)
    }

    @Test
    fun `skipping the indexer on the first-run screen is off until chosen, also in settings saved before it existed`() = runBlocking<Unit> {
        val older = """{"schemaVersion":1,"indexers":[],"providers":[{"id":"p","name":"Main","host":"news.example.net"}],"playback":{"quality":"Auto"}}"""
        val read = JsonFileSerializer(AppSettings.serializer(), AppSettings()).readFrom(older.byteInputStream())
        assertFalse(read.indexerSetupSkipped)
        assertEquals(listOf("news.example.net"), read.activeProviders.map { it.host })
        assertFalse(repository.indexerSetupSkipped.first())
        repository.setIndexerSetupSkipped(true)
        assertTrue(repository.indexerSetupSkipped.first())
        assertTrue(""""indexerSetupSkipped":true""" in File(dir, "settings.json").readText())
        repository.setIndexerSetupSkipped(false)
        assertFalse(repository.indexerSetupSkipped.first())
    }

    @Test
    fun `watched video is kept, up to 2 GB, until changed, also in settings saved before it existed`() = runBlocking<Unit> {
        val older = """{"schemaVersion":1,"playback":{"quality":"P1080","autoPlayNext":false,"updatedAt":5}}"""
        val read = JsonFileSerializer(AppSettings.serializer(), AppSettings()).readFrom(older.byteInputStream())
        assertEquals(CachePrefs(keepVideo = true, limitGb = 2), read.cache)
        assertEquals(2_000_000_000, read.cache.limitBytes)
        // The rest of the older file as it was.
        assertEquals(PlaybackPrefs(quality = "P1080", autoPlayNext = false, updatedAt = 5), read.playback)
        assertEquals(CachePrefs(), repository.cache.first())
        repository.updateCache { it.copy(keepVideo = false, limitGb = 5) }
        val saved = repository.cache.first()
        assertEquals(false to 5, saved.keepVideo to saved.limitGb)
        assertEquals(1_000, saved.updatedAt)
        assertTrue(""""cache":{"keepVideo":false,"limitGb":5,""" in File(dir, "settings.json").readText())
        // A limit only a newer version offers is kept; one below the smallest reads as the smallest.
        assertEquals(20_000_000_000, CachePrefs(limitGb = 20).limitBytes)
        assertEquals(1_000_000_000, CachePrefs(limitGb = 0).limitBytes)
        assertEquals(listOf(1, 2, 5, 10), CachePrefs.LIMITS_GB)
    }

    @Test
    fun `detailed logging follows the build until chosen, also in settings saved before it existed`() = runBlocking<Unit> {
        val older = """{"schemaVersion":1,"cache":{"keepVideo":false,"limitGb":5,"updatedAt":3}}"""
        val read = JsonFileSerializer(AppSettings.serializer(), AppSettings()).readFrom(older.byteInputStream())
        assertEquals(LogPrefs(), read.logs)
        assertNull(read.logs.detailed)
        // On in debug builds, off in release ones.
        assertTrue(read.logs.isDetailed(debugBuild = true))
        assertFalse(read.logs.isDetailed(debugBuild = false))
        // The rest of the older file as it was.
        assertEquals(CachePrefs(keepVideo = false, limitGb = 5, updatedAt = 3), read.cache)

        assertEquals(LogPrefs(), repository.logs.first())
        repository.updateLogs { it.copy(detailed = false) }
        val off = repository.logs.first()
        assertFalse(off.isDetailed(debugBuild = true))
        assertEquals(1_000, off.updatedAt)
        assertTrue(""""logs":{"detailed":false,""" in File(dir, "settings.json").readText())
        repository.updateLogs { it.copy(detailed = true) }
        assertTrue(repository.logs.first().isDetailed(debugBuild = false))
    }

    /**
     * L112: "Look for Cast devices" is off until chosen, and the Cast button's introduction not yet shown, also in settings
     * saved before them, so an update starts nothing of Google's Cast software by itself; once chosen, each is saved.
     */
    @Test
    fun `looking for Cast devices and the introduction are off until chosen, also in settings saved before them`() = runBlocking<Unit> {
        val older = """{"schemaVersion":1,"providers":[{"id":"p","name":"Main","host":"news.example.net"}],"playback":{"quality":"P1080","updatedAt":5}}"""
        val read = JsonFileSerializer(AppSettings.serializer(), AppSettings()).readFrom(older.byteInputStream())
        assertFalse(read.cast.look)
        assertFalse(read.cast.introShown)
        // The rest of the older file as it was.
        assertEquals("P1080", read.playback.quality)
        assertEquals(listOf("news.example.net"), read.activeProviders.map { it.host })
        assertEquals(CastPrefs(), repository.cast.first())

        repository.updateCast { it.copy(look = true) }
        assertEquals(CastPrefs(look = true, introShown = false, updatedAt = 1_000), repository.cast.first())
        assertTrue(""""look":true""" in File(dir, "settings.json").readText())
        repository.updateCast { it.copy(introShown = true) }
        assertEquals(CastPrefs(look = true, introShown = true, updatedAt = 1_001), repository.cast.first())
        // Turned off in Settings: it starts nothing at the next start, and the introduction stays shown.
        repository.updateCast { it.copy(look = false) }
        assertEquals(CastPrefs(look = false, introShown = true, updatedAt = 1_002), repository.cast.first())
    }

    /**
     * L128: what each Cast device has shown it plays is kept in the cast settings, on this device. Settings saved before
     * it read with none, and the rest of the file as it was; a codec's name only a newer version knows still reads, as
     * a name (an unknown enum would make the whole file unreadable and reset every setting).
     */
    @Test
    fun `what Cast devices have shown is kept, read as none from settings saved before it`() = runBlocking<Unit> {
        val older = """{"schemaVersion":1,"playback":{"quality":"P1080","updatedAt":5},"cast":{"look":true,"introShown":true,"updatedAt":7}}"""
        val read = JsonFileSerializer(AppSettings.serializer(), AppSettings()).readFrom(older.byteInputStream())
        assertEquals(CastPrefs(look = true, introShown = true, updatedAt = 7), read.cast)
        assertEquals(emptyList(), read.cast.devices)
        assertEquals("P1080", read.playback.quality)

        val den = CastDeviceMemory(
            id = "device-den", reportsVideo = true, shown2160 = listOf(CastVideoCodec("Hevc", tenBit = true)), hdrSdr = true,
            noPicture = listOf(CastVideoCodec("Av1")), seenAt = 42,
        )
        repository.updateCast { it.copy(look = true).remembering(den) }
        assertEquals(listOf(den), repository.cast.first().devices)
        assertTrue(""""devices":[{"id":"device-den",""" in File(dir, "settings.json").readText())
        // Turning Look for Cast devices off forgets nothing the devices taught.
        repository.updateCast { it.copy(look = false) }
        assertEquals(listOf(den), repository.cast.first().devices)

        val newer = """{"schemaVersion":1,"cast":{"devices":[{"id":"device-den","noPicture":[{"codec":"Vvc","tenBit":true}],"seenAt":9}]}}"""
        val later = JsonFileSerializer(AppSettings.serializer(), AppSettings()).readFrom(newer.byteInputStream())
        assertEquals(listOf(CastDeviceMemory("device-den", noPicture = listOf(CastVideoCodec("Vvc", tenBit = true)), seenAt = 9)), later.cast.devices)
    }

    @Test
    fun `settings read earlier are put back as they were`() = runBlocking<Unit> {
        val a = repository.saveProvider(ProviderRecord("", "Main", "news.example.net"), "pw")
        repository.saveIndexer(null, "A", "https://a.example/api", "K1")
        repository.saveIndexer(null, "B", "https://b.example/api", "K2")
        val saved = repository.settings.first()
        repository.deleteProvider(a)
        repository.indexers.first().forEach { repository.deleteIndexer(it.id) }
        repository.setIndexerSetupSkipped(true)
        repository.restore(saved)
        assertEquals(saved, repository.settings.first())
    }

    @Test
    fun `unknown fields from a newer version are ignored and garbage is a corruption`() = runBlocking<Unit> {
        val serializer = JsonFileSerializer(AppSettings.serializer(), AppSettings())
        val newer = """{"schemaVersion":2,"indexers":[],"futureThing":{"x":1}}"""
        assertEquals(2, serializer.readFrom(newer.byteInputStream()).schemaVersion)
        assertFailsWith<CorruptionException> { serializer.readFrom("not json".byteInputStream()) }
    }

    @Test
    fun `providers keep order and passwords stay secret`() = runBlocking<Unit> {
        val a = repository.saveProvider(ProviderRecord("", "", " news.example.net ", 563, "user"), "hunter2")
        val b = repository.saveProvider(ProviderRecord("", "Backup", "block.example.com"), "pw2")
        repository.saveProvider(ProviderRecord(a, "Main", "news.example.net", 563, "user", connections = 20), password = null)
        assertEquals(listOf("Main" to 20, "Backup" to 8), repository.providers.first().map { it.name to it.connections })
        assertEquals(listOf("hunter2", "pw2"), repository.usableProviders().map { it.password })
        assertFalse("hunter2" in File(dir, "settings.json").readText())
        assertFalse("hunter2" in repository.usableProviders().toString())
        repository.deleteProvider(b)
        assertEquals(listOf(a), repository.usableProviders().map { it.record.id })
    }

    @Test
    fun `a provider password that can't be decrypted is null and the provider is not used`() = runBlocking<Unit> {
        val a = repository.saveProvider(ProviderRecord("", "Main", "news.example.net", 563, "user"), "hunter2")
        repository.saveProvider(ProviderRecord("", "Open", "open.example.net", 119), "")
        assertEquals(listOf("hunter2", ""), repository.usableProviders().map { it.password })
        cipher.broken = true
        assertNull(repository.provider(a)!!.password)
        // One without a login needs no password (#131): it stays in use.
        assertEquals(listOf("Open"), repository.usableProviders().map { it.record.name })
    }

    // #131: the secrets are left out of device transfers, so a provider without a login arrives
    // with its sealed "" missing. It needs no password, so it stays usable; one with a login doesn't.
    @Test
    fun `a provider without a login stays usable when its empty password is missing`() = runBlocking<Unit> {
        val open = repository.saveProvider(ProviderRecord("", "Open", "open.example.net", 119), "")
        val main = repository.saveProvider(ProviderRecord("", "Main", "news.example.net", 563, "user"), "hunter2")
        secrets.put(ProviderRecord(open, "", "").passwordSecret, null)
        secrets.put(ProviderRecord(main, "", "").passwordSecret, null)
        assertEquals("", repository.provider(open)!!.password)
        assertNull(repository.provider(main)!!.password)
        assertEquals(listOf(open to ""), repository.usableProviders().map { it.record.id to it.password })
    }

    @Test
    fun `a provider saved without a name keeps the name blank`() = runBlocking<Unit> {
        val id = repository.saveProvider(ProviderRecord("", "  ", " news.example.net "), "pw")
        assertEquals("" to "news.example.net", repository.providers.first().single().let { it.name to it.host })
        assertEquals("", repository.provider(id)!!.record.name)
    }

    /** Saves providers [names] (named and hosted alike), in that order; returns their ids. */
    private suspend fun providers(vararg names: String): List<String> =
        names.map { repository.saveProvider(ProviderRecord("", it, "$it.example.net", username = "user"), "pw-$it") }

    private suspend fun order() = repository.providers.first().map { it.name }

    @Test
    fun `a provider moves up and down by places`() = runBlocking<Unit> {
        val (_, b, c) = providers("a", "b", "c")
        repository.moveProvider(c, by = -1)
        assertEquals(listOf("a", "c", "b"), order())
        repository.moveProvider(c, by = -1)
        assertEquals(listOf("c", "a", "b"), order())
        repository.moveProvider(c, by = 2)
        assertEquals(listOf("a", "b", "c"), order())
        repository.moveProvider(b, by = 1)
        assertEquals(listOf("a", "c", "b"), order())
        // The engine gets them in this order, as priority.
        assertEquals(listOf("a", "c", "b"), repository.usableProviders().map { it.record.name })
    }

    @Test
    fun `a provider moves to a place, as far as the list goes`() = runBlocking<Unit> {
        val (a, _, c) = providers("a", "b", "c")
        repository.moveProviderTo(a, 2)
        assertEquals(listOf("b", "c", "a"), order())
        repository.moveProviderTo(c, 0)
        assertEquals(listOf("c", "b", "a"), order())
        repository.moveProviderTo(c, 9)
        assertEquals(listOf("b", "a", "c"), order())
        repository.moveProvider(c, by = -9)
        assertEquals(listOf("c", "b", "a"), order())
    }

    @Test
    fun `a move stamps the moved provider, and moving the first up or the last down changes nothing`() = runBlocking<Unit> {
        val (a, b) = providers("a", "b")
        val before = repository.settings.first()
        repository.moveProvider(a, by = -1)
        repository.moveProvider(b, by = 1)
        repository.moveProviderTo(a, 0)
        repository.moveProvider("unknown", by = 1)
        assertEquals(before, repository.settings.first())
        repository.moveProvider(a, by = 1)
        val moved = repository.providers.first().first { it.id == a }
        assertTrue(moved.updatedAt > before.providers.maxOf { it.updatedAt })
    }

    @Test
    fun `deleted providers keep their place but don't count in moves`() = runBlocking<Unit> {
        val (a, b, c, d) = providers("a", "b", "c", "d")
        repository.deleteProvider(b)
        repository.deleteProvider(c)
        // a, (b), (c), d: one place up for d is past both tombstones.
        repository.moveProvider(d, by = -1)
        assertEquals(listOf("d", "a"), order())
        assertEquals(listOf(d, b, c, a), repository.settings.first().providers.map { it.id })
        assertEquals(listOf(false, true, true, false), repository.settings.first().providers.map { it.deleted })
        repository.moveProviderTo(d, 1)
        assertEquals(listOf("a", "d"), order())
        // A deleted provider can't be moved, nor be switched on again.
        repository.moveProvider(b, by = -1)
        repository.setProviderEnabled(b, true)
        assertEquals(listOf(a, b, c, d), repository.settings.first().providers.map { it.id })
        assertEquals(listOf(false, false), repository.settings.first().providers.filter { it.deleted }.map { it.enabled })
    }

    @Test
    fun `a provider switched off is kept but not used, and switched on again in its place`() = runBlocking<Unit> {
        val (a, b) = providers("a", "b")
        repository.setProviderEnabled(a, false)
        assertEquals(listOf(false, true), repository.providers.first().map { it.enabled })
        assertEquals(listOf("b"), repository.usableProviders().map { it.record.name })
        val stamped = repository.providers.first().first { it.id == a }.updatedAt
        assertTrue(stamped > repository.providers.first().first { it.id == b }.updatedAt)
        val before = repository.settings.first()
        repository.setProviderEnabled(a, false)
        assertEquals(before, repository.settings.first())
        repository.setProviderEnabled(a, true)
        assertEquals(listOf("a", "b"), repository.usableProviders().map { it.record.name })
    }

    @Test
    fun `usable providers are the enabled readable ones in list order`() = runBlocking<Unit> {
        val (a, b, c, d) = providers("a", "b", "c", "d")
        repository.setProviderEnabled(b, false)
        repository.deleteProvider(c)
        secrets.put(ProviderRecord(d, "", "").passwordSecret, null)
        repository.moveProvider(a, by = 3)
        assertEquals(listOf("b", "d", "a"), order())
        assertEquals(listOf(a to "pw-a"), repository.usableProviders().map { it.record.id to it.password })
        assertEquals(listOf("b" to "pw-b", "d" to null, "a" to "pw-a"), repository.providersWithPasswords.first().map { it.record.name to it.password })
    }

    @Test
    fun `the collection's tab, filters and sorts are the defaults in settings saved before them, the rest as it was`() = runBlocking<Unit> {
        // A whole file as the version before this one wrote it.
        val older = """{"schemaVersion":1,"indexers":[{"id":"i","name":"Indexer","baseUrl":"https://indexer.example.org/api","enabled":true,"updatedAt":3,"deleted":false}],""" +
            """"providers":[{"id":"p","name":"Main","host":"news.example.net","port":563,"username":"user","useTls":true,"connections":20,"enabled":true,"updatedAt":4,"deleted":false}],""" +
            """"playback":{"quality":"P1080","audioLanguages":["de"],"subtitlesWhenForeign":true,"autoPlayNext":false,"checkAllArticles":false,"lookUpSegmentsOnline":false,""" +
            """"aspectMode":"Fill","zoomScale":1.0,"updatedAt":5},"metadata":{"language":"es-ES","showArtwork":false,"updatedAt":6},""" +
            """"cache":{"keepVideo":false,"limitGb":5,"updatedAt":7},"logs":{"detailed":true,"updatedAt":8},"indexerSetupSkipped":true}"""
        val read = JsonFileSerializer(AppSettings.serializer(), AppSettings()).readFrom(older.byteInputStream())
        assertEquals(CollectionViewPrefs(side = "Shows", showFilter = "All", showSort = "RecentlyWatched", movieFilter = "All", movieSort = "RecentlyAdded", updatedAt = 0), read.collectionView)
        assertEquals(listOf("Main" to 20), read.activeProviders.map { it.name to it.connections })
        assertEquals(listOf("https://indexer.example.org/api"), read.activeIndexers.map { it.baseUrl })
        assertEquals(AspectMode.Fill to listOf("de"), read.playback.aspect to read.playback.audioLanguages)
        assertEquals(MetadataPrefs("es-ES", showArtwork = false, updatedAt = 6), read.metadata)
        assertEquals(CachePrefs(keepVideo = false, limitGb = 5, updatedAt = 7), read.cache)
        assertTrue(read.indexerSetupSkipped)
        assertEquals(CollectionViewPrefs(), repository.collectionView.first())
    }

    @Test
    fun `the collection's tab, filters and sorts are saved, and names only a newer version knows don't reset the settings`() = runBlocking<Unit> {
        repository.updateCollectionView { it.copy(side = "Movies", movieFilter = "InProgress", movieSort = "AZ") }
        assertEquals(CollectionViewPrefs(side = "Movies", movieFilter = "InProgress", movieSort = "AZ", updatedAt = 1_000), repository.collectionView.first())
        assertTrue(""""collectionView":{"side":"Movies","showFilter":"All",""" in File(dir, "settings.json").readText())
        repository.updateCollectionView { it.copy(showFilter = "Finished") }
        assertEquals(CollectionViewPrefs("Movies", "Finished", "RecentlyWatched", "InProgress", "AZ", updatedAt = 1_001), repository.collectionView.first())

        // Stored as names: the app reads one it doesn't know as its default; the file stays readable.
        val newer = """{"schemaVersion":1,"providers":[{"id":"p","name":"Main","host":"news.example.net"}],""" +
            """"collectionView":{"side":"Lists","showFilter":"Archived","showSort":"Rating","future":{"x":1}}}"""
        val read = JsonFileSerializer(AppSettings.serializer(), AppSettings()).readFrom(newer.byteInputStream())
        assertEquals(CollectionViewPrefs(side = "Lists", showFilter = "Archived", showSort = "Rating"), read.collectionView)
        assertEquals(listOf("news.example.net"), read.activeProviders.map { it.host })
    }

    /** Every TMDB request asks for the key: it is decrypted (a keystore call) once, not every time. */
    @Test
    fun `the tmdb key is decrypted once, and again once it is set`() = runBlocking<Unit> {
        repository.setTmdbKey("abc123")
        cipher.opens.set(0)
        assertEquals("abc123", repository.tmdbKey())
        assertEquals("abc123", repository.tmdbKey())
        assertEquals(1, cipher.opens.get())
        repository.setTmdbKey(" def456 ")
        assertEquals("def456", repository.tmdbKey())
        assertEquals("def456", repository.tmdbKey())
        assertEquals(2, cipher.opens.get())
        repository.setTmdbKey(null)
        assertNull(repository.tmdbKey())
        // A key that can't be read now isn't taken for none later.
        repository.setTmdbKey("abc123")
        cipher.broken = true
        assertNull(repository.tmdbKey())
        cipher.broken = false
        assertEquals("abc123", repository.tmdbKey())
    }

    /** A read that began before a new key was set may return the old one, but must not keep it. */
    @Test
    fun `a read racing a new tmdb key doesn't keep the old one`() = runBlocking<Unit> {
        repository.setTmdbKey("old")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        cipher.gate = entered to release
        val read = async(Dispatchers.IO) { repository.tmdbKey() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repository.setTmdbKey("new")
        release.countDown()
        assertEquals("old", read.await())
        assertEquals("new", repository.tmdbKey())
    }

    /**
     * A new key is told of (hasTmdbKey) once it is stored, before setTmdbKey returns, and the lists'
     * refresher asks TMDB at once: from then on the new key is read, also when the set never returns.
     */
    @Test
    fun `a new tmdb key is read once it is stored, before the set returns or if it never does`() = runBlocking<Unit> {
        repository.setTmdbKey("old")
        assertEquals("old", repository.tmdbKey())
        secretsFile.hold = CompletableDeferred()
        val stored = cipher.seal("new".encodeToByteArray())
        val told = watchFor(secretsFile.data) { stored in it.entries.values }
        val set = launch(Dispatchers.IO) { repository.setTmdbKey("new") }
        told.await()
        assertEquals("new", repository.tmdbKey())
        // The screen that set it went (its scope cancelled) before the set returned.
        set.cancelAndJoin()
        assertEquals("new", repository.tmdbKey())
    }
}
