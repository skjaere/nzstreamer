package io.skjaere.usenet.app.link

import io.skjaere.usenet.link.LinkMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvLinkPolicyTest {
    /** L4: only a TV, only while NZStreamer is in front there, and only with a phone paired or Pair a phone taking pairings. */
    @Test
    fun `listens on a TV in front with a phone paired or pairing`() {
        for (television in listOf(true, false)) for (inFront in listOf(true, false)) for (paired in listOf(true, false)) for (pairing in listOf(true, false)) {
            val expected = television && inFront && (paired || pairing)
            assertEquals(expected, TvLinkPolicy.listens(television, inFront, paired, pairing), "television $television, in front $inFront, paired $paired, pairing $pairing")
        }
    }

    @Test
    fun `nothing listens on a TV with no phone paired and no pairing`() {
        assertFalse(TvLinkPolicy.listens(television = true, inFront = true, phonesPaired = false, pairing = false))
    }

    /** L4a: a Remove a while ago keeps a TV in front listening with no phone paired, and nothing else. */
    @Test
    fun `a recent Remove keeps a TV in front listening, with no phone paired`() {
        for (television in listOf(true, false)) for (inFront in listOf(true, false)) for (paired in listOf(true, false)) for (pairing in listOf(true, false)) {
            for (removed in listOf(true, false)) {
                val expected = television && inFront && (paired || pairing || removed)
                assertEquals(
                    expected,
                    TvLinkPolicy.listens(television, inFront, paired, pairing, removedRecently = removed),
                    "television $television, in front $inFront, paired $paired, pairing $pairing, removed recently $removed",
                )
            }
        }
    }

    /** L4a: from a Remove for 30 days, by the TV's clock; a clock set back up to a day after it still counts. */
    @Test
    fun `a Remove counts for 30 days, and from a day before it`() {
        val at = 1_790_000_000_000L
        val day = 24 * 60 * 60 * 1_000L
        assertTrue(TvLinkPolicy.removedRecently(at, at), "at once")
        assertTrue(TvLinkPolicy.removedRecently(at, at + 1), "a moment after")
        assertTrue(TvLinkPolicy.removedRecently(at, at + 29 * day), "29 days after")
        assertTrue(TvLinkPolicy.removedRecently(at, at + 30 * day - 1), "the last moment of the 30 days")
        assertTrue(TvLinkPolicy.removedRecently(at, at - 1), "the clock set back a moment")
        assertTrue(TvLinkPolicy.removedRecently(at, at - day), "the clock set back a day")
    }

    /** L4a: after 30 days L4 alone applies again, and a clock far off either way ends it too. */
    @Test
    fun `a Remove 30 days ago, or a clock far off either way, counts no more`() {
        val at = 1_790_000_000_000L
        val day = 24 * 60 * 60 * 1_000L
        assertFalse(TvLinkPolicy.removedRecently(at, at + 30 * day), "30 days after")
        assertFalse(TvLinkPolicy.removedRecently(at, at + 400 * day), "a year after")
        assertFalse(TvLinkPolicy.removedRecently(at, at - day - 1), "the clock more than a day behind the Remove")
        assertFalse(TvLinkPolicy.removedRecently(at, at - 365 * day), "a year behind")
        assertFalse(TvLinkPolicy.removedRecently(at, Long.MAX_VALUE), "far in the future")
        assertFalse(TvLinkPolicy.removedRecently(Long.MAX_VALUE, 0), "a time far in the future, as a clock far ahead wrote it")
        assertFalse(TvLinkPolicy.removedRecently(Long.MIN_VALUE, Long.MAX_VALUE))
        assertFalse(TvLinkPolicy.removedRecently(null, at), "no Remove")
    }

    /** L15: Android 12 and older can't receive mDNS without the lock, the queries a TV answers among it. */
    @Test
    fun `a multicast lock below Android 13`() {
        for (sdk in 26..32) {
            assertTrue(TvLinkPolicy.multicastLock(sdk, wifi = true) { error("asked the extension on $sdk") }, "API $sdk")
        }
    }

    /** On 13 by the T extension's version, as Google says, not the SDK: from extensions 7 the system takes care of it. */
    @Test
    fun `on Android 13 by the T extension version`() {
        assertTrue(TvLinkPolicy.multicastLock(33, wifi = true) { 6 })
        assertFalse(TvLinkPolicy.multicastLock(33, wifi = true) { 7 })
        assertFalse(TvLinkPolicy.multicastLock(33, wifi = true) { 22 })
    }

    @Test
    fun `none from Android 14, whose every build has the extension`() {
        for (sdk in 34..37) assertFalse(TvLinkPolicy.multicastLock(sdk, wifi = true) { error("asked the extension on $sdk") }, "API $sdk")
    }

    /** A TV with no WifiManager (Ethernet only) takes none, whatever its version. */
    @Test
    fun `none without Wi-Fi`() {
        for (sdk in 26..36) assertFalse(TvLinkPolicy.multicastLock(sdk, wifi = false) { 0 }, "API $sdk")
    }

    /**
     * L30: an Unpair from a paired phone does what Remove on the TV does (the phone, its outbox and its links go), but it
     * notes no Remove (L4a): the phone knows already, so the TV needn't listen on for it.
     */
    @Test
    fun `an Unpair from a paired phone removes it, and notes no Remove`() {
        assertEquals(TvAnswer.Unpaired, TvLinkPolicy.answer(LinkMessage.Unpair))
    }

    /** L8: each command goes to the TV's plays, which answer it (TvPlays). */
    @Test
    fun `a command goes to the TV's plays`() {
        val commands = listOf(
            LinkMessage.Play(seq = 1), LinkMessage.Cancel(2, "s-1"), LinkMessage.Pause(3, "s-1"), LinkMessage.Resume(4, "s-1"),
            LinkMessage.SeekTo(5, "s-1", 60_000), LinkMessage.SeekBy(6, "s-1", 10_000), LinkMessage.Stop(7, "s-1"),
        )
        commands.forEach { command ->
            assertEquals(TvAnswer.Command(command as LinkMessage.Command), TvLinkPolicy.answer(command), "$command")
        }
    }

    /** L33: a FinalAck lets go the Finals it names, owed to the phone that sent it. */
    @Test
    fun `a FinalAck lets the Finals it names go`() {
        assertEquals(TvAnswer.Acked(listOf("f-1", "f-2")), TvLinkPolicy.answer(LinkMessage.FinalAck(listOf("f-1", "f-2"))))
    }

    @Test
    fun `anything else asks nothing of the TV`() {
        assertEquals(TvAnswer.Ignore, TvLinkPolicy.answer(LinkMessage.Ping))
    }
}
