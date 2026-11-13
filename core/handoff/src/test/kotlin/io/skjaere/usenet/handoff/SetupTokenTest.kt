package io.skjaere.usenet.handoff

import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SetupTokenTest {
    private val alphabet = "0123456789abcdefghjkmnpqrstvwxyz"

    /** 128 bits as 26 base32 digits, worked out another way: the number, shifted to fill 130 bits. */
    private fun expected(bytes: ByteArray): String {
        var n = BigInteger(1, bytes).shiftLeft(2)
        val digits = CharArray(26)
        for (i in 25 downTo 0) {
            digits[i] = alphabet[n.and(BigInteger.valueOf(31)).toInt()]
            n = n.shiftRight(5)
        }
        return String(digits)
    }

    @Test
    fun `a token is 128 bits as 26 characters of lowercase Crockford base32`() {
        assertEquals("0".repeat(26), SetupToken.of(ByteArray(16)).value)
        assertEquals("z".repeat(25) + "w", SetupToken.of(ByteArray(16) { -1 }).value)
        val random = Random(7)
        repeat(50) {
            val bytes = random.nextBytes(16)
            assertEquals(expected(bytes), SetupToken.of(bytes).value)
        }
    }

    @Test
    fun `generated tokens are random and well formed`() {
        val tokens = List(20) { SetupToken.generate().value }
        tokens.forEach { token ->
            assertEquals(26, token.length, token.length.toString())
            assertTrue(token.all { it in alphabet }, "outside the alphabet")
            assertTrue(SetupToken.isTokenShaped(token))
        }
        assertEquals(tokens.size, tokens.toSet().size, "two tokens alike")
    }

    @Test
    fun `the display form groups the token in fours`() {
        val token = SetupToken.of(ByteArray(16) { (it * 17).toByte() })
        val grouped = token.grouped
        assertEquals(listOf(4, 4, 4, 4, 4, 4, 2), grouped.split('-').map { it.length })
        assertEquals(token.value, grouped.replace("-", ""))
    }

    @Test
    fun `normalising forgives case, separators and the letters base32 leaves out`() {
        assertEquals("k7m2q9xd0111", SetupToken.normalise("K7M2-Q9XD o1Il"))
        assertEquals("abc", SetupToken.normalise(" a-b c "))
    }

    @Test
    fun `token-shaped means 26 base32 characters after normalising`() {
        val token = SetupToken.of(ByteArray(16) { (it * 31 + 5).toByte() })
        assertTrue(SetupToken.isTokenShaped(token.value))
        assertTrue(SetupToken.isTokenShaped(token.grouped.uppercase()))
        assertFalse(SetupToken.isTokenShaped(token.value.drop(1)))
        assertFalse(SetupToken.isTokenShaped(token.value + "0"))
        assertFalse(SetupToken.isTokenShaped("u" + token.value.drop(1)), "u is not in the alphabet")
        assertFalse(SetupToken.isTokenShaped("é" + token.value.drop(1)))
        assertFalse(SetupToken.isTokenShaped("favicon.ico"))
        assertFalse(SetupToken.isTokenShaped(""))
    }

    @Test
    fun `matches the token however it was typed, and nothing else`() {
        // 0 and 1 in it, so the typed o, i and l have something to stand for.
        val token = SetupToken.of(ByteArray(16) { if (it % 2 == 0) 0 else 0x21 })
        assertTrue(token.value.contains('0') && token.value.contains('1'), token.grouped)
        assertTrue(token.matches(token.value))
        assertTrue(token.matches(token.grouped))
        assertTrue(token.matches(token.grouped.uppercase()))
        assertTrue(token.matches(token.value.replace('0', 'o').replace('1', 'l')))
        assertTrue(token.matches(token.value.replace('1', 'I')))
        assertTrue(token.matches(token.grouped.replace('-', ' ')))
        assertFalse(token.matches(token.value.dropLast(1)))
        assertFalse(token.matches(token.value + "0"))
        assertFalse(token.matches(""))
        val other = SetupToken.generate()
        assertNotEquals(token.value, other.value)
        assertFalse(token.matches(other.value))
    }

    @Test
    fun `a token never prints its value`() {
        val token = SetupToken.generate()
        assertFalse(token.toString().contains(token.value), "toString shows the token")
        assertFalse(token.toString().contains(token.grouped), "toString shows the token")
    }
}
