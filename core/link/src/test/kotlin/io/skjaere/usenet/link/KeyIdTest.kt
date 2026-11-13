package io.skjaere.usenet.link

import org.junit.jupiter.api.Test
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A key's id (L2): the SHA-256 of its SubjectPublicKeyInfo, fixed for v1, since pairings pin it and a TV's record carries
 * its first 16 digits; and as a store keeps it, its 64 hex digits, read back to the same id, and nothing else read as one.
 */
class KeyIdTest {
    @Test
    fun `a known-answer vector fixes the id and its short form`() {
        // PairingTest's TV key; the digest worked out apart from this code (Python's hashlib).
        val spki = Base64.getDecoder().decode(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0U+2D+z3w9W98HcVVYj5x3JACnayYHxh+CefaOJU3DREhNihSdCV1lLEMtNrW5f/5YvvrTWeZWlaP4ntQ9dMaw==",
        )
        val id = KeyId.of(spki)
        assertEquals("0f31095b2512829578941e3f318071b6d8f666a4f5ff1377a170131a8ca464fc", id.hex)
        assertEquals("0f31095b25128295", id.short)
        // A certificate's key gives the same id: its encoded form is the SubjectPublicKeyInfo.
        assertEquals(id, KeyId.of(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))))
    }

    @Test
    fun `an id reads back from its hex, and nothing else reads as one`() {
        val id = KeyId.of(LinkTestPeers.linkKey().certificate.publicKey)
        assertEquals(id, KeyId.parse(id.hex))
        assertEquals(id.hex, KeyId.parse(id.hex)?.hex)
        for (bad in listOf("", id.short, id.hex.uppercase(), id.hex + "0", id.hex.dropLast(1), id.hex.dropLast(1) + "g", " " + id.hex.drop(1))) {
            assertNull(KeyId.parse(bad), "\"$bad\" read as an id")
        }
    }
}
