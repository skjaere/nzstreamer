package io.skjaere.usenet.link

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * A self-signed X.509 certificate for an EC P-256 key, written out in DER with the JDK alone: Android has no certificate
 * builder, and the app takes no BouncyCastle (L3). It is the fallback identity's (L20); a keystore key comes with the one
 * the keystore makes. A link checks nothing in it but its key and that the key signed it (L2): no CA, no name and no
 * dates, since a TV box's clock may be wrong. So it holds only what X.509 asks for: version 3 with no extensions,
 * CN=NZStreamer as subject and issuer, valid from 1970 with no end (RFC 5280's 99991231235959Z), and a random serial.
 */
object SelfSigned {
    /** ecdsa-with-SHA256 as an AlgorithmIdentifier: its OID, with no parameters (RFC 5758). */
    internal val ECDSA_SHA256: ByteArray = Der.sequence(Der.oid("1.2.840.10045.4.3.2"))

    private val NAME = Der.sequence(Der.set(Der.sequence(Der.oid("2.5.4.3"), Der.utf8("NZStreamer"))))

    // UTCTime for 1970; a year from 2050 on must be a GeneralizedTime (RFC 5280 4.1.2.5).
    private val VALIDITY = Der.sequence(Der.text(Der.UTC_TIME, "700101000000Z"), Der.text(Der.GENERALIZED_TIME, "99991231235959Z"))

    private val random by lazy { SecureRandom() }

    /** The certificate for [keys], an EC P-256 pair, signed by its own private key. */
    fun certificate(keys: KeyPair, serial: BigInteger = randomSerial()): X509Certificate {
        require(isP256(keys.public)) { "a link certificate is for an EC P-256 key" }
        val der = der(keys.public, keys.private, "SHA256withECDSA", ECDSA_SHA256, serial)
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    /** 1 to 2⁶³: positive, as RFC 5280 asks, and nine bytes at most where it allows twenty. */
    private fun randomSerial(): BigInteger = BigInteger(63, random).add(BigInteger.ONE)

    /**
     * The DER of a certificate for [subject] signed by [signer] with [signatureAlgorithm] (a JCA name), which
     * [algorithmIdentifier] names in the certificate. Only [certificate] makes link keys; the tests make the rest.
     */
    internal fun der(
        subject: PublicKey,
        signer: PrivateKey,
        signatureAlgorithm: String,
        algorithmIdentifier: ByteArray,
        serial: BigInteger,
    ): ByteArray {
        require(serial.signum() > 0) { "a serial is positive" }
        val tbs = Der.sequence(
            Der.explicit(0, Der.integer(BigInteger.valueOf(2))), // version 3
            Der.integer(serial),
            algorithmIdentifier,
            NAME, // issuer
            VALIDITY,
            NAME, // subject
            subject.encoded, // its SubjectPublicKeyInfo, as the JDK already encodes it
        )
        val signature = Signature.getInstance(signatureAlgorithm).run {
            initSign(signer)
            update(tbs)
            sign()
        }
        return Der.sequence(tbs, algorithmIdentifier, Der.bitString(signature))
    }
}

/** The few DER encodings a certificate needs (X.690): each value is its tag, its length and its content. */
internal object Der {
    private const val INTEGER = 0x02
    private const val BIT_STRING = 0x03
    private const val OBJECT_IDENTIFIER = 0x06
    private const val UTF8_STRING = 0x0c
    private const val SEQUENCE = 0x30
    private const val SET = 0x31
    const val UTC_TIME = 0x17
    const val GENERALIZED_TIME = 0x18

    val NULL = byteArrayOf(0x05, 0x00)

    fun sequence(vararg parts: ByteArray) = value(SEQUENCE, *parts)

    fun set(vararg parts: ByteArray) = value(SET, *parts)

    /** BigInteger's own bytes are DER's: two's complement, big-endian, as few as hold it. */
    fun integer(n: BigInteger) = value(INTEGER, n.toByteArray())

    fun utf8(text: String) = value(UTF8_STRING, text.toByteArray(Charsets.UTF_8))

    /** An ASCII string under [tag]: a time, here. */
    fun text(tag: Int, ascii: String) = value(tag, ascii.toByteArray(Charsets.US_ASCII))

    /** Whole bytes: the first content byte says no bits of the last are unused. */
    fun bitString(bytes: ByteArray) = value(BIT_STRING, byteArrayOf(0), bytes)

    /** [inner] under the context-specific, constructed tag [number] (an EXPLICIT [number]). */
    fun explicit(number: Int, inner: ByteArray) = value(0xa0 or number, inner)

    /** The first two arcs share a byte (40 × first + second); each arc is base 128, the high bit set on all but its last byte. */
    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        require(arcs.size >= 2 && arcs[0] in 0..2 && arcs.all { it >= 0 }) { "not an OID" }
        val content = ByteArrayOutputStream()
        (listOf(arcs[0] * 40 + arcs[1]) + arcs.drop(2)).forEach { arc ->
            var shift = (63 - arc.countLeadingZeroBits()) / 7 * 7
            while (shift > 0) {
                content.write(((arc shr shift) and 0x7f).toInt() or 0x80)
                shift -= 7
            }
            content.write((arc and 0x7f).toInt())
        }
        return value(OBJECT_IDENTIFIER, content.toByteArray())
    }

    /** [tag], the length of [parts] together (one byte below 128, else 0x80 + how many bytes it takes, then them), and [parts]. */
    private fun value(tag: Int, vararg parts: ByteArray): ByteArray {
        val length = parts.sumOf { it.size }
        val out = ByteArrayOutputStream(length + 6)
        out.write(tag)
        if (length < 0x80) {
            out.write(length)
        } else {
            val bytes = BigInteger.valueOf(length.toLong()).toByteArray().dropWhile { it == 0.toByte() }
            out.write(0x80 or bytes.size)
            bytes.forEach { out.write(it.toInt()) }
        }
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}
