package io.skjaere.usenet.link

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * L33: the Finals a TV owes a phone, kept until the phone's FinalAck names them, for 30 days and 50 at most, the oldest
 * going first. Each goes out with its age as it is sent, worked out from when it was made by the TV's clock, never below
 * 0; written out and read back (a restart), it is owed as before. Each phone's are its own, kept with its row in
 * link.json, so a phone removed takes them with it (LinkStoreTest). The titles are invented.
 */
class OutboxTest {
    private val day = 24 * 60 * 60 * 1_000L
    private val start = 1_800_000_000_000L

    private fun final(id: String, positionMs: Long = 1_930_000) = LinkMessage.Final(
        id = id, session = "s-81", watchKey = "episode:4101:2:4", kind = FinalKind.Episode, title = "The Glass Orchard",
        showTmdbId = 4_101, season = 2, episode = 4, positionMs = positionMs, durationMs = 3_120_000, watched = false, ageMs = 0,
    )

    @Test
    fun `a Final added is owed, with its age as it is sent`() {
        val outbox = Outbox().add(final("f-1"), start).add(final("f-2"), start + 1_000)
        assertEquals(listOf(OutboxFinal(final("f-1"), start), OutboxFinal(final("f-2"), start + 1_000)), outbox.entries)
        assertEquals(listOf(final("f-1").copy(ageMs = 5_000), final("f-2").copy(ageMs = 4_000)), outbox.due(start + 5_000))
    }

    @Test
    fun `a clock set back since gives an age of 0, not below`() {
        val outbox = Outbox().add(final("f-1"), start)
        assertEquals(listOf(final("f-1").copy(ageMs = 0)), outbox.due(start - 60_000))
    }

    @Test
    fun `an ack lets the Finals it names go, and only those`() {
        val outbox = Outbox().add(final("f-1"), start).add(final("f-2"), start).add(final("f-3"), start)
        assertEquals(listOf("f-2"), outbox.ack(listOf("f-1", "f-3", "f-9")).entries.map { it.final.id })
        assertEquals(outbox, outbox.ack(emptyList()))
    }

    @Test
    fun `a Final is owed for 30 days at most`() {
        val outbox = Outbox().add(final("f-1"), start).add(final("f-2"), start + day)
        assertEquals(listOf("f-1", "f-2"), outbox.due(start + 30 * day - 1).map { it.id })
        assertEquals(listOf("f-2"), outbox.due(start + 30 * day).map { it.id })
        // And gone from what is kept as the next one is added.
        assertEquals(listOf("f-2", "f-3"), outbox.add(final("f-3"), start + 30 * day).entries.map { it.final.id })
    }

    @Test
    fun `50 at most are owed a phone, the oldest going first`() {
        var outbox = Outbox()
        repeat(Outbox.MAX + 3) { outbox = outbox.add(final("f-$it"), start + it) }
        assertEquals(Outbox.MAX, outbox.entries.size)
        assertEquals((3 until Outbox.MAX + 3).map { "f-$it" }, outbox.entries.map { it.final.id })
    }

    @Test
    fun `written out and read back, as after a restart, it is owed as before`() {
        val outbox = Outbox().add(final("f-1"), start).add(final("f-2", positionMs = 2_000_000), start + 1_000)
        val json = Json { ignoreUnknownKeys = true }
        val text = json.encodeToString(ListSerializer(OutboxFinal.serializer()), outbox.entries)
        val again = Outbox(json.decodeFromString(ListSerializer(OutboxFinal.serializer()), text))
        assertEquals(outbox, again)
        assertEquals(outbox.due(start + 9_000), again.due(start + 9_000))
    }
}
