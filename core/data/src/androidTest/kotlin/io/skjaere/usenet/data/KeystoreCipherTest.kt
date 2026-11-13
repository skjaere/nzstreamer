package io.skjaere.usenet.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class KeystoreCipherTest {
    @Test
    fun sealsAndOpensWithAFreshIvEachTime() {
        val cipher = KeystoreCipher("usenet-secrets-test")
        val plain = "SECRETKEY42".encodeToByteArray()
        val a = cipher.seal(plain)
        val b = cipher.seal(plain)
        assertNotEquals(a.iv, b.iv)
        assertFalse("SECRETKEY42" in a.ciphertext)
        assertArrayEquals(plain, cipher.open(a))
        assertArrayEquals(plain, KeystoreCipher("usenet-secrets-test").open(b))
    }

    @Test
    fun aKeyLostWhileCachedIsReplacedAsBefore() {
        val alias = "usenet-secrets-test-lost"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cipher = KeystoreCipher(alias)
        val old = cipher.seal("OLD".encodeToByteArray())
        keyStore.deleteEntry(alias)
        assertThrows(GeneralSecurityException::class.java) { cipher.open(old) }
        val new = cipher.seal("NEW".encodeToByteArray())
        assertArrayEquals("NEW".encodeToByteArray(), cipher.open(new))
        // The cached key is gone when sealing: a new one is generated and stored under the alias.
        keyStore.deleteEntry(alias)
        val newer = cipher.seal("NEWER".encodeToByteArray())
        assertArrayEquals("NEWER".encodeToByteArray(), KeystoreCipher(alias).open(newer))
        keyStore.deleteEntry(alias)
    }
}
