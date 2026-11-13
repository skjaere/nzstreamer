package io.skjaere.usenet.data

import androidx.datastore.core.DataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.link.Handshake
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkKey
import io.skjaere.usenet.link.LinkTls
import io.skjaere.usenet.link.PeerPolicy
import io.skjaere.usenet.link.SelfTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * This install's link identity on a real AndroidKeyStore (L20, item 9): made once and found again by a later store over
 * the same file, made anew (flagged, its pairings gone) when its keystore key is deleted, the fallback forced by the
 * self-test's seam and still handshaking, and link.json holding no key but the fallback's sealed one. Its aliases and
 * its file are its own, cleared before and after each case. The names are invented.
 */
@RunWith(AndroidJUnit4::class)
class LinkIdentityDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "link-identity-test")
    private val file get() = File(dir, "link.json")
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun clean() {
        dir.deleteRecursively()
        deleteAliases()
    }

    @After
    fun cleanUp() {
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
        deleteAliases()
    }

    private fun deleteAliases() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        listOf(ALIAS, SEAL_ALIAS, PEER_ALIAS).forEach(keyStore::deleteEntry)
    }

    /** A store over the test's link.json, as a new process would open it: the one before it is gone. */
    private fun store(): LinkStore {
        scopes.forEach { it.cancel() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return LinkStore(DataStoreFactory.create(JsonFileSerializer(LinkFile.serializer(), LinkFile()), scope = scope) { file })
    }

    /** The real keystore under the test's aliases; [fallback] makes the self-test fail, as on a device whose TLS can't use a keystore key. */
    private fun keys(fallback: Boolean = false) = LinkKeys(
        alias = ALIAS,
        seal = KeystoreCipher(SEAL_ALIAS),
        selfTest = if (fallback) ({ SelfTest.Failed }) else ({ LinkTls.selfTest(it) }),
    )

    @Test
    fun theSameIdentityAcrossInstances() = runBlocking<Unit> {
        val made = store().identity(keys())
        assertFalse(made.renewed)
        assertEquals(LinkIdentity.Kind.Keystore, made.identity.kind)
        val again = store().identity(keys())
        assertFalse("a later store renewed it", again.renewed)
        assertEquals(made.identity.key.id, again.identity.key.id)
        assertEquals(LinkIdentity.Kind.Keystore, again.identity.kind)
        // The keystore's own: it can't be exported, and this device's TLS uses it.
        assertNull(again.identity.key.privateKey.encoded)
        assertTrue(LinkTls.selfTest(again.identity.key) is SelfTest.Passed)
    }

    @Test
    fun aDeletedAliasGivesANewOneFlaggedAndThePairingsGone() = runBlocking<Unit> {
        val first = store()
        val made = first.identity(keys()).identity
        first.addTv(KeyId.of(Random.nextBytes(91)), "Living room TV", "192.168.1.20", 28_462)
        first.addPhone(KeyId.of(Random.nextBytes(91)), "Galaxy phone", "192.168.1.57")
        // As a restore or a reset leaves it: the record, without its key.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)

        val next = store()
        val renewed = next.identity(keys())
        assertTrue("not flagged", renewed.renewed)
        assertNotEquals(made.key.id, renewed.identity.key.id)
        val kept = next.file.first()
        assertEquals(renewed.identity.key.id.hex, kept.identity?.id)
        assertEquals(emptyList<PairedTv>(), kept.tvs)
        assertEquals(emptyList<PairedPhone>(), kept.phones)
    }

    @Test
    fun theFallbackForcedByASeamHandshakes() = runBlocking<Unit> {
        val made = store().identity(keys(fallback = true)).identity
        assertEquals(LinkIdentity.Kind.Fallback, made.kind)
        // Found again from its sealed key, by a later store.
        val opened = store().identity(keys(fallback = true))
        assertFalse(opened.renewed)
        assertEquals(LinkIdentity.Kind.Fallback, opened.identity.kind)
        assertEquals(made.key.id, opened.identity.key.id)
        // And it handshakes, pinned both ways, with a keystore identity over loopback.
        loopback(opened.identity.key, LinkIdentity.keystore(PEER_ALIAS).key)
    }

    @Test
    fun linkJsonHoldsNoKeyButTheSealedFallback() = runBlocking<Unit> {
        // A keystore identity: nothing of its key in the file but its id.
        val keystore = store().identity(keys()).identity
        val written = Json.parseToJsonElement(file.readText()).jsonObject.getValue("identity").jsonObject
        assertEquals(setOf("kind", "id", "sealed"), written.keys)
        assertEquals(JsonNull, written["sealed"])
        val text = file.readText()
        assertFalse("the certificate is in link.json", b64(keystore.key.certificate.encoded) in text)
        assertFalse("the public key is in link.json", b64(keystore.key.certificate.publicKey.encoded) in text)

        // A fallback: its private key sealed, never in the clear, its certificate (public) beside it.
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
        val fallback = store().identity(keys(fallback = true)).identity
        val pkcs8 = requireNotNull(fallback.key.privateKey.encoded) { "a fallback's key can't be read" }
        val sealedText = file.readText()
        assertFalse("the private key is in link.json", b64(pkcs8) in sealedText)
        assertFalse("the private key is in link.json", pkcs8.joinToString("") { "%02x".format(it) } in sealedText)
        val sealed = requireNotNull(store().file.first().identity?.sealed) { "no sealed key in link.json" }
        assertFalse(Base64.getDecoder().decode(sealed.key.ciphertext).contentEquals(pkcs8))
        assertEquals(b64(fallback.key.certificate.encoded), sealed.certificate)
    }

    /** A pinned mutual handshake over loopback: [server] as the TV, [client] as the phone. */
    private fun loopback(server: LinkKey, client: LinkKey) {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { listener ->
            val served = CompletableFuture<Handshake>()
            thread(isDaemon = true) {
                try {
                    val accepted = listener.accept().apply { soTimeout = 10_000 }
                    served.complete(LinkTls.server(accepted, server, PeerPolicy.Pinned(client.id)))
                } catch (e: Throwable) {
                    served.completeExceptionally(e)
                }
            }
            val socket = Socket().apply {
                connect(InetSocketAddress(loopback, listener.localPort), 2_000)
                soTimeout = 10_000
            }
            val mine = LinkTls.client(socket, client, PeerPolicy.Pinned(server.id))
            val theirs = served.get(10, TimeUnit.SECONDS)
            try {
                assertTrue("the phone's end: $mine", mine is Handshake.Accepted)
                assertTrue("the TV's end: $theirs", theirs is Handshake.Accepted)
            } finally {
                (mine as? Handshake.Accepted)?.socket?.close()
                (theirs as? Handshake.Accepted)?.socket?.close()
                socket.close()
            }
        }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val ALIAS = "nzstreamer-link-identity-test"
        const val SEAL_ALIAS = "nzstreamer-link-identity-test-seal"
        const val PEER_ALIAS = "nzstreamer-link-identity-test-peer"
    }
}
