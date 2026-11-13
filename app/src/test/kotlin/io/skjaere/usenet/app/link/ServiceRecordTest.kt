package io.skjaere.usenet.app.link

import io.skjaere.usenet.link.KeyId
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The TV's mDNS record (L27) and the names peers give (L28). The names are invented. */
class ServiceRecordTest {
    private val key = KeyId.of(Random(7).nextBytes(91))
    private val tvAddress = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20)) as Inet4Address

    private fun txt(vararg entries: Pair<String, String?>): Map<String, ByteArray?> = entries.associate { (k, v) -> k to v?.encodeToByteArray() }

    @Test
    fun `the record's TXT holds pv, id and v4, and pair only while pairing`() {
        val record = ServiceRecord.of("Living room TV", pv = 1, key = key, pairing = false, v4 = tvAddress)
        assertEquals("_nzstreamer._tcp", ServiceRecord.TYPE)
        assertEquals("Living room TV", record.name)
        assertEquals(mapOf("pv" to "1", "id" to key.short, "v4" to "192.168.1.20"), record.txt)
        assertEquals(16, record.txt.getValue("id").length)
        assertEquals(mapOf("pv" to "1", "id" to key.short, "pair" to "1", "v4" to "192.168.1.20"), ServiceRecord.of("Living room TV", 1, key, pairing = true, v4 = tvAddress).txt)
        assertEquals(mapOf("pv" to "1", "id" to key.short), ServiceRecord.of("Living room TV", 1, key, pairing = false, v4 = null).txt)
    }

    /** Nothing about what plays, or whether anything does: no title and no state, whatever the record is made from. */
    @Test
    fun `no title or state in the record`() {
        val keys = setOf("pv", "id", "pair", "v4")
        for (pairing in listOf(true, false)) {
            val record = ServiceRecord.of("Living room TV", 1, key, pairing, tvAddress)
            assertTrue(keys.containsAll(record.txt.keys), "${record.txt.keys}")
            assertTrue(record.txt.values.all { it == "1" || it == key.short || it == "192.168.1.20" }, "${record.txt}")
        }
    }

    /** An instance name is at most 63 bytes (DNS-SD): a longer one is cut at a character's boundary, never inside one. */
    @Test
    fun `a long name cut at a character boundary`() {
        // 2-byte ö, 3-byte 日, 4-byte 🌊 (a surrogate pair in a String).
        val names = listOf("Wohnzimmer-Fernseher im Erdgeschoss neben der großen Glastür öööö", "日本語のテレビ日本語のテレビ日本語のテレビ日本語のテレビ", "🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊")
        for (name in names) {
            val cut = ServiceRecord.of(name, 1, key, pairing = false, v4 = null).name
            val bytes = cut.encodeToByteArray()
            assertTrue(bytes.size <= 63, "${bytes.size} bytes")
            assertTrue(name.startsWith(cut), "a prefix: $cut")
            assertEquals(cut, bytes.decodeToString(), "whole characters")
            assertTrue(!Character.isHighSurrogate(cut.last()), "no half of a pair")
            // As long as it can be: the next character would not fit.
            val next = name.codePointAt(cut.length)
            assertTrue(bytes.size + String(Character.toChars(next)).encodeToByteArray().size > 63, "cut short: $cut")
        }
        assertEquals("Living room TV", ServiceRecord.instanceName("Living room TV"))
    }

    @Test
    fun `a record read back`() {
        val record = assertNotNull(ServiceRecord.read("Living room TV", txt("pv" to "1", "id" to key.short, "pair" to "1", "v4" to "192.168.1.20")))
        assertEquals("Living room TV", record.name)
        assertEquals(1, record.pv)
        assertEquals(key.short, record.id)
        assertTrue(record.pairing)
        assertEquals(tvAddress, record.v4)
        val quiet = assertNotNull(ServiceRecord.read("Living room TV", txt("pv" to "1", "id" to key.short)))
        assertTrue(!quiet.pairing && quiet.v4 == null)
    }

    /** The TV is told apart by its id (NSD renames a clash "Name (2)"): a record without one is someone else's. */
    @Test
    fun `a record without an id is ignored`() {
        assertNull(ServiceRecord.read("Living room TV", txt("pv" to "1", "v4" to "192.168.1.20")))
        for (bad in listOf("", "0123", key.short.uppercase(), key.hex, key.short.dropLast(1) + "g", key.short + " ")) {
            assertNull(ServiceRecord.read("Living room TV", txt("pv" to "1", "id" to bad)), "id \"$bad\"")
        }
        assertNull(ServiceRecord.read("Living room TV", txt("pv" to "1", "id" to null)))
    }

    /** What else a record says is taken only when it is what it should be: a version, a private IPv4 address, pair=1. */
    @Test
    fun `odd values in a record are left out`() {
        val record = assertNotNull(ServiceRecord.read("TV", txt("pv" to "one", "id" to key.short, "pair" to "yes", "v4" to "nas.local")))
        assertNull(record.pv)
        assertTrue(!record.pairing)
        assertNull(record.v4, "a name is never looked up")
        for (v4 in listOf("8.8.8.8", "127.0.0.1", "169.254.1.1", "100.64.0.1", "192.168.1", "192.168.1.256", "192.168.01.20", " 192.168.1.20")) {
            assertNull(assertNotNull(ServiceRecord.read("TV", txt("id" to key.short, "v4" to v4))).v4, "v4 $v4")
        }
        assertEquals(InetAddress.getByName("10.0.2.16"), assertNotNull(ServiceRecord.read("TV", txt("id" to key.short, "v4" to "10.0.2.16"))).v4)
    }

    /** L28: a peer's name is untrusted. Bidi overrides would turn it round on screen, line breaks would spill over a row. */
    @Test
    fun `a peer's name with bidi overrides or line breaks cleaned`() {
        fun read(name: String) = assertNotNull(ServiceRecord.read(name, txt("id" to key.short))).name
        assertEquals("Living room TV", read("Living‮ room TV"))
        assertEquals("Livingroom TV", read("Living\nroom\r TV"))
        assertEquals("Living room TV", read("⁦Living room TV⁩"))
        assertEquals("Livingroom", read("Living room "))
        assertEquals("Living room", read("Living​ \u0007room\u0000"))
    }

    @Test
    fun `an empty name shown as TV`() {
        fun read(name: String) = assertNotNull(ServiceRecord.read(name, txt("id" to key.short))).name
        assertEquals("TV", read(""))
        assertEquals("TV", read("‮\n​"))
        assertEquals("TV", read("   "))
    }

    /** At most 40 code points, a pair of surrogates counting as one. */
    @Test
    fun `a peer's name cut to 40 characters`() {
        assertEquals("a".repeat(40), PeerNames.clean("a".repeat(70), "TV"))
        val waves = PeerNames.clean("🌊".repeat(50), "TV")
        assertEquals(40, waves.codePointCount(0, waves.length))
        assertEquals("🌊".repeat(40), waves)
        assertEquals("Phone", PeerNames.clean(null, "Phone"))
        assertEquals("Galaxy phone", PeerNames.clean("  Galaxy phone ", "Phone"))
    }

    /** L28: this device's own name: the device name the user set, else its maker and model, at most 40 characters. */
    @Test
    fun `this device's own name`() {
        assertEquals("Living room TV", PeerNames.own("Living room TV", "NVIDIA", "SHIELD Android TV"))
        assertEquals("NVIDIA SHIELD Android TV", PeerNames.own(null, "NVIDIA", "SHIELD Android TV"))
        assertEquals("NVIDIA SHIELD Android TV", PeerNames.own("  ", "NVIDIA", "SHIELD Android TV"))
        assertEquals("x".repeat(40), PeerNames.own("x".repeat(64), "NVIDIA", "SHIELD"))
        assertEquals("Lounge", PeerNames.own("Lounge‮\n", "NVIDIA", "SHIELD"))
        assertEquals("TV", PeerNames.own(null, "", "", fallback = "TV"))
    }
}
