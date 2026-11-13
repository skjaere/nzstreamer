package io.skjaere.usenet.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.skjaere.usenet.link.LinkKey
import io.skjaere.usenet.link.LinkTls
import io.skjaere.usenet.link.SelfSigned
import io.skjaere.usenet.link.SelfTest
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.IOException
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CancellationException
import javax.security.auth.x500.X500Principal

/**
 * The fallback identity's key as it is kept: its PKCS#8 sealed by [KeystoreCipher], and its certificate (Base64 DER),
 * which is public. Nothing else of a link identity is ever written: a keystore key stays in the keystore.
 */
@Serializable
data class SealedLinkKey(val key: Sealed, val certificate: String)

/**
 * This install's identity on the Play on TV link (L20): one key for both roles, a phone's and a TV's, which pairings
 * pin. By choice it is an AndroidKeyStore EC P-256 key, which can't be exported. A new one is checked by a handshake with
 * itself in memory ([LinkTls.selfTest]: no socket, so no other app can take a port and fail it), with one retry. When
 * the keystore can't make one this device's TLS can use, the identity is the fallback: a software P-256 key whose
 * PKCS#8 is sealed by a [KeystoreCipher] of its own alias ([sealed]), with a certificate from [SelfSigned]. [kind] says
 * which, for the app to record and log.
 */
class LinkIdentity private constructor(val key: LinkKey, val kind: Kind, val sealed: SealedLinkKey?) {
    enum class Kind { Keystore, Fallback }

    override fun toString() = "LinkIdentity($kind, ${key.id.short})"

    companion object {
        const val ALIAS = "nzstreamer-link"
        const val SEAL_ALIAS = "nzstreamer-link-seal"
        private const val TRIES = 2

        /**
         * A new identity: a keystore key made under [alias] that passes [selfTest], made again once if it doesn't, else
         * the fallback, sealed by [seal]. Keystore work is a binder call into the TEE: call this off the main thread.
         * Throws only when the fallback can't be sealed either, when the keystore is no use on this device at all.
         */
        fun create(
            alias: String = ALIAS,
            seal: SecretCipher = KeystoreCipher(SEAL_ALIAS),
            makeKeystoreKey: (String) -> LinkKey = KeystoreLinkKeys::make,
            deleteKeystoreKey: (String) -> Unit = KeystoreLinkKeys::delete,
            selfTest: (LinkKey) -> SelfTest = { LinkTls.selfTest(it) },
        ): LinkIdentity {
            repeat(TRIES) {
                val key = keystoreWork { makeKeystoreKey(alias) }
                if (key != null && selfTest(key) is SelfTest.Passed) return LinkIdentity(key, Kind.Keystore, null)
            }
            // No key this device's TLS can't use is left under the alias.
            keystoreWork { deleteKeystoreKey(alias) }
            return fallback(seal)
        }

        /** A keystore key made under [alias], replacing any there, unchecked: [create] is what checks one. */
        fun keystore(alias: String): LinkIdentity = LinkIdentity(KeystoreLinkKeys.make(alias), Kind.Keystore, null)

        /** The keystore [key] an identity made before, found again under its alias. */
        internal fun kept(key: LinkKey): LinkIdentity = LinkIdentity(key, Kind.Keystore, null)

        /** A new software key, its PKCS#8 sealed by [cipher]. */
        fun fallback(cipher: SecretCipher): LinkIdentity {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE)) }.generateKeyPair()
            val certificate = SelfSigned.certificate(pair)
            val pkcs8 = pair.private.encoded
            val sealed = try {
                SealedLinkKey(cipher.seal(pkcs8), Base64.getEncoder().encodeToString(certificate.encoded))
            } finally {
                pkcs8.fill(0)
            }
            return LinkIdentity(LinkKey(pair.private, certificate), Kind.Fallback, sealed)
        }

        /** The fallback [sealed] keeps, opened by [cipher]. Throws when it can't be: the keystore key that sealed it is gone. */
        fun open(sealed: SealedLinkKey, cipher: SecretCipher): LinkIdentity {
            val pkcs8 = cipher.open(sealed.key)
            val private = try {
                KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            } finally {
                pkcs8.fill(0)
            }
            val der = Base64.getDecoder().decode(sealed.certificate)
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            return LinkIdentity(LinkKey(private, certificate), Kind.Fallback, sealed)
        }

        /** [work]'s result, or null when the keystore failed it, in whatever way a device's keystore fails. */
        private fun <T> keystoreWork(work: () -> T): T? = try {
            work()
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IOException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            // ProviderException ("Keystore operation failed") and the like.
            null
        }

        internal const val CURVE = "secp256r1"
    }
}

/**
 * Where this install's identity comes from (L20): its keystore [alias], the [seal] of a fallback, the keystore's own
 * steps and the self-test. AndroidKeyStore's by default; tests stand in for them, and the TV role's test seam (L14) has
 * aliases of its own.
 */
class LinkKeys(
    val alias: String = LinkIdentity.ALIAS,
    val seal: SecretCipher = KeystoreCipher(LinkIdentity.SEAL_ALIAS),
    val make: (String) -> LinkKey = KeystoreLinkKeys::make,
    /** The key under an alias; null when there is none. Throws when the keystore can't be read now. */
    val load: (String) -> LinkKey? = KeystoreLinkKeys::load,
    val delete: (String) -> Unit = KeystoreLinkKeys::delete,
    val selfTest: (LinkKey) -> SelfTest = { LinkTls.selfTest(it) },
)

/** This install's identity as [LinkStore.identity] found it: [renewed] when the recorded one was lost and a new one made, its pairings cleared with it. */
class LoadedIdentity(val identity: LinkIdentity, val renewed: Boolean) {
    override fun toString() = "LoadedIdentity($identity, renewed=$renewed)"
}

/**
 * The keystore half of [LinkIdentity] (L20): an EC P-256 key for signing, which can't be exported, with digests NONE
 * (TLS hands the keystore its own digest to sign), SHA-256 and SHA-384, and the self-signed certificate the keystore
 * makes for it: CN=NZStreamer, a random serial, its default dates (no end checks them).
 */
internal object KeystoreLinkKeys {
    private const val PROVIDER = "AndroidKeyStore"

    fun make(alias: String): LinkKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(LinkIdentity.CURVE))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384)
            .setCertificateSubject(X500Principal("CN=NZStreamer"))
            .setCertificateSerialNumber(BigInteger(63, SecureRandom()).add(BigInteger.ONE))
            .build()
        val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
        val certificate = keyStore().getCertificate(alias) as? X509Certificate ?: throw GeneralSecurityException("no certificate")
        return LinkKey(pair.private, certificate)
    }

    /** The key [make] made under [alias], with its certificate; null when there is none. Throws when the keystore can't be read now. */
    fun load(alias: String): LinkKey? {
        val keyStore = keyStore()
        val private = keyStore.getKey(alias, null) as? PrivateKey ?: return null
        val certificate = keyStore.getCertificate(alias) as? X509Certificate ?: return null
        return LinkKey(private, certificate)
    }

    fun delete(alias: String) = keyStore().deleteEntry(alias)

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
}
