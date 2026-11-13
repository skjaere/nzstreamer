package io.skjaere.usenet.link

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SelfSignedTest {
    private fun ecKeys(curve: String = "secp256r1"): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    private fun parse(der: ByteArray) =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    @Test
    fun `CertificateFactory parses it, its key is the pair's, and its signature verifies with that key`() {
        val keys = ecKeys()
        val parsed = parse(SelfSigned.certificate(keys).encoded)
        assertContentEquals(keys.public.encoded, parsed.publicKey.encoded)
        // Throws unless the signature is the key's own.
        parsed.verify(keys.public)
        assertEquals("SHA256withECDSA", parsed.sigAlgName)
        assertEquals(3, parsed.version)
        assertEquals("CN=NZStreamer", parsed.subjectX500Principal.name)
        assertEquals(parsed.subjectX500Principal, parsed.issuerX500Principal)
    }

    @Test
    fun `it is valid from 1970 with no end, so no clock can put it out of date`() {
        val parsed = parse(SelfSigned.certificate(ecKeys()).encoded)
        assertEquals(0L, parsed.notBefore.time)
        // 9999-12-31T23:59:59Z, RFC 5280's "no well-defined expiration date".
        assertEquals(253_402_300_799_000L, parsed.notAfter.time)
    }

    @Test
    fun `each certificate gets a random positive serial`() {
        val keys = ecKeys()
        val serials = List(20) { SelfSigned.certificate(keys).serialNumber }
        serials.forEach { assertTrue(it.signum() > 0, "serial $it isn't positive") }
        assertEquals(serials.size, serials.toSet().size, "two serials alike")
        assertNotEquals(BigInteger.ZERO, serials.first())
    }

    @Test
    fun `a serial given is the one it carries, a high first bit included`() {
        val serial = BigInteger("ff00000000000001", 16)
        assertEquals(serial, parse(SelfSigned.certificate(ecKeys(), serial).encoded).serialNumber)
    }

    @Test
    fun `only a P-256 key gets one`() {
        assertThrows<IllegalArgumentException> { SelfSigned.certificate(ecKeys("secp384r1")) }
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertThrows<IllegalArgumentException> { SelfSigned.certificate(rsa) }
    }
}
