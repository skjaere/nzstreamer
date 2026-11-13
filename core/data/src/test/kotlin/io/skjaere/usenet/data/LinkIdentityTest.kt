package io.skjaere.usenet.data

import androidx.datastore.core.DataStoreFactory
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkKey
import io.skjaere.usenet.link.LinkTls
import io.skjaere.usenet.link.SelfSigned
import io.skjaere.usenet.link.SelfTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which identity [LinkIdentity.create] chooses, with the keystore and the self-test stood in for; the fallback's
 * sealing; and the identity [LinkStore] makes once, records and finds again, or makes anew, its pairings cleared, when
 * the recorded one is lost (L20).
 */
class LinkIdentityTest {
    @TempDir
    lateinit var dir: File

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterEach
    fun stop() = scopes.forEach { it.cancel() }

    /** Reversible, and not the plain bytes: a stand-in for the keystore cipher. */
    private class ReversingCipher : SecretCipher {
        override fun seal(plain: ByteArray) = Sealed(Base64.getEncoder().encodeToString(plain.reversedArray()), "iv")

        override fun open(sealed: Sealed): ByteArray = Base64.getDecoder().decode(sealed.ciphertext).reversedArray()
    }

    private fun softwareKey(): LinkKey {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return LinkKey(pair.private, SelfSigned.certificate(pair))
    }

    /** A keystore that makes [makes] in turn (null: it throws), and what was asked of it. */
    private inner class FakeKeystore(vararg makes: LinkKey?) {
        private val queue = ArrayDeque(makes.toList())
        val made = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        fun make(alias: String): LinkKey {
            made += alias
            return queue.removeFirst() ?: throw ProviderException("Keystore operation failed")
        }

        fun delete(alias: String) {
            deleted += alias
        }
    }

    private val cipher = ReversingCipher()

    private fun create(keystore: FakeKeystore, passes: (LinkKey) -> Boolean) = LinkIdentity.create(
        alias = "nzstreamer-link",
        seal = cipher,
        makeKeystoreKey = keystore::make,
        deleteKeystoreKey = keystore::delete,
        selfTest = { if (passes(it)) SelfTest.Passed("TLSv1.3", "TLS_AES_128_GCM_SHA256") else SelfTest.Failed },
    )

    @Test
    fun `a keystore key that passes the self-test is the identity`() {
        val key = softwareKey()
        val keystore = FakeKeystore(key)
        val identity = create(keystore) { true }
        assertEquals(LinkIdentity.Kind.Keystore, identity.kind)
        assertEquals(key.id, identity.key.id)
        assertNull(identity.sealed, "a keystore key has nothing to keep")
        assertEquals(listOf("nzstreamer-link"), keystore.made)
        assertEquals(emptyList(), keystore.deleted)
    }

    @Test
    fun `a key that fails is made once more, and the second kept when it passes`() {
        val bad = softwareKey()
        val good = softwareKey()
        val keystore = FakeKeystore(bad, good)
        val identity = create(keystore) { it === good }
        assertEquals(LinkIdentity.Kind.Keystore, identity.kind)
        assertEquals(good.id, identity.key.id)
        assertEquals(2, keystore.made.size)
    }

    @Test
    fun `two keys that fail, or a keystore that throws, give the fallback, and nothing is left under the alias`() {
        for (keystore in listOf(FakeKeystore(softwareKey(), softwareKey()), FakeKeystore(null, null))) {
            val identity = create(keystore) { false }
            assertEquals(LinkIdentity.Kind.Fallback, identity.kind)
            assertEquals(2, keystore.made.size, "one retry")
            assertEquals(listOf("nzstreamer-link"), keystore.deleted)
            assertNotNull(identity.sealed)
            // The fallback is a link key this TLS can use.
            assertIs<SelfTest.Passed>(LinkTls.selfTest(identity.key))
        }
    }

    @Test
    fun `the fallback keeps its key sealed, and opens to the same key`() {
        val identity = LinkIdentity.fallback(cipher)
        val sealed = assertNotNull(identity.sealed)
        val pkcs8 = identity.key.privateKey.encoded
        assertFalse(Base64.getDecoder().decode(sealed.key.ciphertext).contentEquals(pkcs8), "kept in the clear")
        val opened = LinkIdentity.open(sealed, cipher)
        assertEquals(LinkIdentity.Kind.Fallback, opened.kind)
        assertEquals(identity.key.id, opened.key.id)
        assertContentEquals(pkcs8, opened.key.privateKey.encoded)
        assertIs<SelfTest.Passed>(LinkTls.selfTest(opened.key))
    }

    /** A keystore by alias, as AndroidKeyStore keeps keys; [unreadable] makes a load throw, as a keystore that can't be read now does. */
    private inner class Keystore {
        val keys = mutableMapOf<String, LinkKey>()
        var made = 0
        var unreadable = false

        fun make(alias: String): LinkKey = softwareKey().also {
            made++
            keys[alias] = it
        }

        fun load(alias: String): LinkKey? {
            if (unreadable) throw KeyStoreException("can't be read now")
            return keys[alias]
        }

        fun linkKeys(seal: SecretCipher = cipher, passes: (LinkKey) -> Boolean = { true }) = LinkKeys(
            alias = "nzstreamer-link",
            seal = seal,
            make = ::make,
            load = ::load,
            delete = { keys.remove(it) },
            selfTest = { if (passes(it)) SelfTest.Passed("TLSv1.3", "TLS_AES_128_GCM_SHA256") else SelfTest.Failed },
        )
    }

    /**
     * A store over link.json in the temp directory; each one stands for a process of its own, the one before it gone:
     * waited for, since DataStore lets go of the file only as its scope's work ends, and a store opened on it before that
     * is refused ("multiple DataStores active for the same file").
     */
    private fun store(): LinkStore {
        runBlocking { scopes.forEach { it.coroutineContext.job.cancelAndJoin() } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return LinkStore(DataStoreFactory.create(JsonFileSerializer(LinkFile.serializer(), LinkFile()), scope = scope) { File(dir, "link.json") })
    }

    /** A cipher whose keystore key can go: [lost], it opens nothing, as a seal key gone with a restore. */
    private class LosableCipher : SecretCipher {
        var lost = false
        private val inner = ReversingCipher()

        override fun seal(plain: ByteArray) = inner.seal(plain)

        override fun open(sealed: Sealed): ByteArray = if (lost) throw javax.crypto.AEADBadTagException("lost") else inner.open(sealed)
    }

    private suspend fun pairSome(store: LinkStore) {
        store.addTv(KeyId.of(Random.nextBytes(91)), "Living room TV", "192.168.1.20", 28_462)
        store.addPhone(KeyId.of(Random.nextBytes(91)), "Galaxy phone", "192.168.1.57")
    }

    @Test
    fun `the identity is made once and recorded with its kind, and a new process finds the same one`() = runBlocking<Unit> {
        val keystore = Keystore()
        val first = store()
        val made = first.identity(keystore.linkKeys())
        assertFalse(made.renewed)
        assertEquals(LinkIdentity.Kind.Keystore, made.identity.kind)
        assertEquals(made, first.identity(keystore.linkKeys()), "made again in the same process")
        assertEquals(IdentityRecord(LinkIdentity.Kind.Keystore, made.identity.key.id.hex), first.file.first().identity)

        val again = store().identity(keystore.linkKeys())
        assertFalse(again.renewed)
        assertEquals(made.identity.key.id, again.identity.key.id)
        assertEquals(LinkIdentity.Kind.Keystore, again.identity.kind)
        assertEquals(1, keystore.made, "a key made again")
    }

    @Test
    fun `the self-test chooses the fallback, recorded sealed, and a new process opens it again`() = runBlocking<Unit> {
        val keystore = Keystore()
        val made = store().identity(keystore.linkKeys(passes = { false })).identity
        assertEquals(LinkIdentity.Kind.Fallback, made.kind)
        val record = assertNotNull(store().file.first().identity)
        assertEquals(LinkIdentity.Kind.Fallback, record.kind)
        assertEquals(made.sealed, record.sealed)

        val again = store().identity(keystore.linkKeys(passes = { false }))
        assertFalse(again.renewed)
        assertEquals(LinkIdentity.Kind.Fallback, again.identity.kind)
        assertEquals(made.key.id, again.identity.key.id)
        assertIs<SelfTest.Passed>(LinkTls.selfTest(again.identity.key))
    }

    @Test
    fun `a lost keystore key makes a new identity, flagged, and clears the pairings`() = runBlocking<Unit> {
        val keystore = Keystore()
        val first = store()
        val made = first.identity(keystore.linkKeys()).identity
        pairSome(first)
        // A restore, or a reset: the record is there, the key isn't.
        keystore.keys.clear()
        val next = store()
        val renewed = next.identity(keystore.linkKeys())
        assertTrue(renewed.renewed)
        assertNotEquals(made.key.id, renewed.identity.key.id)
        val file = next.file.first()
        assertEquals(renewed.identity.key.id.hex, file.identity?.id)
        assertEquals(emptyList(), file.tvs)
        assertEquals(emptyList(), file.phones)
    }

    /**
     * L4a: a new identity clears the last Remove's time with the phones: no phone paired with the new key could have been
     * removed, so the TV needn't listen on for one.
     */
    @Test
    fun `a new identity clears the last Remove's time with the phones`() = runBlocking<Unit> {
        val keystore = Keystore()
        val first = store()
        first.identity(keystore.linkKeys())
        pairSome(first)
        // As Remove on the TV left it (LinkStoreTest has the Remove itself).
        first.setRemovedAt(1_790_000_000_000)
        // Kept while the identity is: the same one in a new process.
        assertNotNull(store().also { it.identity(keystore.linkKeys()) }.file.first().removedAt)

        keystore.keys.clear()
        val next = store()
        assertTrue(next.identity(keystore.linkKeys()).renewed)
        val file = next.file.first()
        assertEquals(emptyList(), file.phones)
        assertEquals(null, file.removedAt)
    }

    @Test
    fun `another key under the alias is a lost one too`() = runBlocking<Unit> {
        val keystore = Keystore()
        val first = store()
        val made = first.identity(keystore.linkKeys()).identity
        pairSome(first)
        keystore.keys["nzstreamer-link"] = softwareKey()
        val renewed = store().identity(keystore.linkKeys())
        assertTrue(renewed.renewed)
        assertNotEquals(made.key.id, renewed.identity.key.id)
        assertEquals(emptyList(), store().file.first().tvs)
    }

    @Test
    fun `a fallback whose seal is lost makes a new identity, flagged, and clears the pairings`() = runBlocking<Unit> {
        val keystore = Keystore()
        val seal = LosableCipher()
        val first = store()
        val made = first.identity(keystore.linkKeys(seal, passes = { false })).identity
        pairSome(first)
        seal.lost = true
        val next = store()
        val renewed = next.identity(keystore.linkKeys(seal, passes = { false }))
        assertTrue(renewed.renewed)
        assertNotEquals(made.key.id, renewed.identity.key.id)
        assertEquals(emptyList(), next.file.first().phones)
    }

    @Test
    fun `a keystore that can't be read now changes nothing`() = runBlocking<Unit> {
        val keystore = Keystore()
        val first = store()
        val made = first.identity(keystore.linkKeys()).identity
        pairSome(first)
        keystore.unreadable = true
        val next = store()
        assertFailsWith<KeyStoreException> { next.identity(keystore.linkKeys()) }
        val file = next.file.first()
        assertEquals(made.key.id.hex, file.identity?.id)
        assertEquals(1, file.tvs.size)
        assertEquals(1, file.phones.size)
        // Readable again: the same identity.
        keystore.unreadable = false
        assertEquals(made.key.id, next.identity(keystore.linkKeys()).identity.key.id)
    }
}
