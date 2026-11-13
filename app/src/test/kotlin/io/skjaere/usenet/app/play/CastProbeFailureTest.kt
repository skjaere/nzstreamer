package io.skjaere.usenet.app.play

import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.ProbeResult
import io.skjaere.usenet.core.UnreadableFile
import io.skjaere.usenet.core.UsenetError
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * What Import's cast of a file fails as, by its probe (L330): a file the player can't read in the words a play says it in,
 * never Media3's, on the cast's sheet and in a move from the player alike; anything else as before.
 */
class CastProbeFailureTest {
    private val film = "Copper.Crown.2019.mkv"

    @Test
    fun `a file the player can't read is said as a play says it`() {
        val unknown = castProbeFailure(ProbeResult.Failed(UsenetError.Unknown(null), UnreadableFile.UnknownFormat), film)
        assertEquals(AttemptFailure.Unreadable(UnreadableFile.UnknownFormat, "MKV"), unknown)
        assertEquals("The player can't read this MKV file", unknown?.text)
        assertEquals(
            "The player can't read this M2TS file: it may be damaged",
            castProbeFailure(ProbeResult.Failed(UsenetError.Unknown(null), UnreadableFile.Broken), "00001.m2ts")?.text,
        )
        assertEquals("Reading the release failed", castProbeFailure(ProbeResult.Failed(UsenetError.Unknown(null), UnreadableFile.ReaderCrashed), film)?.text)
    }

    @Test
    fun `any other probe failure is its error, and a file that plays is none`() {
        val slow = assertIs<AttemptFailure.Failed>(castProbeFailure(ProbeResult.Failed(UsenetError.Timeout("No track list within 20 s")), film))
        assertEquals(UsenetError.Timeout("No track list within 20 s"), slow.error)
        assertNull(castProbeFailure(ProbeResult.Playable(), film))
    }

    @Test
    fun `what a Cast device or this one can't play is said as before`() {
        assertEquals(
            listOf(
                "AVI file. Cast devices play MKV, MP4, WebM and MOV.", "No sound for Cast devices (DTS-HD)", "Video Cast devices can't show (VC-1)",
                "2160p over the device's 1080p", "No audio this device can play (TrueHD)", "No video this device can play: 2160p Dolby Vision profile 7",
            ),
            listOf(
                ProbeResult.NotForCast("avi"), ProbeResult.NoCastAudio(listOf("DTS-HD")), ProbeResult.NoCastVideo("VC-1"), ProbeResult.TooBigForCast(2160, 1080),
                ProbeResult.NoPlayableAudio(listOf("TrueHD")), ProbeResult.NoPlayableVideo(listOf("2160p Dolby Vision profile 7")),
            ).map { castProbeFailure(it, film)?.text },
        )
    }
}
