package io.skjaere.usenet.debrid

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class InfoHashTest {
    // An invented hash and its base32 form (RFC 4648), as some magnets carry a v1 hash.
    private val hex = "c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00"
    private val base32 = "YD764AGA77XABQH75YAMB77OADAP73QA"

    @Test
    fun `forty hex digits are a hash, lower-cased`() {
        assertEquals(hex, InfoHash.parse(hex)?.hex)
        assertEquals(hex, InfoHash.parse(hex.uppercase())?.hex)
        assertEquals(hex, InfoHash.parse("  $hex ")?.hex, "spaces around it")
    }

    @Test
    fun `thirty-two base32 characters give the hash they hold, in either case`() {
        assertEquals(hex, InfoHash.parse(base32)?.hex)
        assertEquals(hex, InfoHash.parse(base32.lowercase())?.hex)
        assertEquals("1bb5795cb9f72aa684193ddf405249b071b2ba5f", InfoHash.parse("DO2XSXFZ64VKNBAZHXPUAUSJWBY3FOS7")?.hex)
    }

    @Test
    fun `anything else is refused`() {
        listOf(
            hex.dropLast(1), // 39
            hex + "0", // 41
            hex.dropLast(1) + "g", // not hex
            base32.dropLast(1) + "1", // 1 isn't base32
            base32.dropLast(1),
            "1220" + "ab".repeat(32), // a v2 multihash
            "ab".repeat(32), // a bare v2 hash
            "magnet:?xt=urn:btih:$hex", // a magnet, not a hash
            "",
        ).forEach { assertNull(InfoHash.parse(it), it) }
        assertNull(InfoHash.parse(null))
        assertFailsWith<IllegalArgumentException> { InfoHash.of("ab".repeat(32)) }
    }

    @Test
    fun `the magnet a service is sent holds the exact topic alone`() {
        val magnet = InfoHash.of(base32).xtMagnet()
        assertEquals("magnet:?xt=urn:btih:$hex", magnet)
        assertFalse('&' in magnet, "no tracker, no name")
    }

    @Test
    fun `it is stored as its hex, and a stored value that isn't a hash doesn't read`() {
        val list = ListSerializer(InfoHash.serializer())
        assertEquals("[\"$hex\"]", Json.encodeToString(list, listOf(InfoHash.of(hex))))
        assertEquals(listOf(InfoHash.of(hex)), Json.decodeFromString(list, "[\"${hex.uppercase()}\"]"))
        assertFailsWith<SerializationException> { Json.decodeFromString(list, "[\"not a hash\"]") }
    }
}
