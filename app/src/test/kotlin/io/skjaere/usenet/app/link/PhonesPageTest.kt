package io.skjaere.usenet.app.link

import io.skjaere.usenet.data.PairedPhone
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

/** Settings › Phones' words (PtTvSettings): the hub's line, and each paired phone's. The names are invented. */
class PhonesPageTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val now = ZonedDateTime.of(2026, 11, 1, 20, 30, 0, 0, zone).toInstant().toEpochMilli()
    private val paired = ZonedDateTime.of(2026, 11, 1, 9, 15, 0, 0, zone).toInstant().toEpochMilli()

    private fun phone(name: String, lastSeen: Long? = null, pairedAt: Long = paired) = PairedPhone("a".repeat(64), name, pairedAt, lastSeen, "192.168.1.57")

    @Test
    fun `the hub's line names the phones`() {
        assertEquals("Play on this TV from your phone", phonesLine(emptyList()))
        assertEquals("1 phone · Galaxy phone", phonesLine(listOf(phone("Galaxy phone"))))
        assertEquals("2 phones · Galaxy phone, Pixel tablet", phonesLine(listOf(phone("Galaxy phone"), phone("Pixel tablet"))))
        assertEquals(
            "5 phones · Galaxy phone, Pixel tablet, Lounge phone and 2 more",
            phonesLine(listOf("Galaxy phone", "Pixel tablet", "Lounge phone", "Hall phone", "Attic phone").map { phone(it) }),
        )
    }

    @Test
    fun `seen now while its link is up`() {
        assertEquals("Paired 1 Nov · Seen now", phoneLine(phone("Galaxy phone", lastSeen = now - 3_600_000), connected = true, now = now, zone = zone))
    }

    @Test
    fun `else when it was last seen`() {
        fun line(lastSeen: Long?) = phoneLine(phone("Galaxy phone", lastSeen), connected = false, now = now, zone = zone)
        assertEquals("Paired 1 Nov", line(null))
        assertEquals("Paired 1 Nov · Last seen just now", line(now - 20_000))
        assertEquals("Paired 1 Nov · Last seen 5 min ago", line(now - 5 * 60_000))
        assertEquals("Paired 1 Nov · Last seen 3 h ago", line(now - 3 * 3_600_000))
        val earlier = ZonedDateTime.of(2026, 10, 30, 22, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Paired 1 Nov · Last seen 30 Oct", line(earlier))
    }

    @Test
    fun `a pairing in another year says the year`() {
        val longAgo = ZonedDateTime.of(2025, 12, 24, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Paired 24 Dec 2025 · Last seen 24 Dec 2025", phoneLine(phone("Galaxy phone", lastSeen = longAgo, pairedAt = longAgo), connected = false, now = now, zone = zone))
    }
}
