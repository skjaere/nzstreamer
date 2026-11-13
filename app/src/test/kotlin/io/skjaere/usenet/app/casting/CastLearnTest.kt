package io.skjaere.usenet.app.casting

import androidx.media3.common.MimeTypes
import io.skjaere.usenet.core.ProbedTrack
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.data.CastDeviceMemory
import io.skjaere.usenet.data.CastVideoCodec
import io.skjaere.usenet.releases.Hdr
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What a Cast device teaches of what it plays (L128, L131), from the media statuses of a play of ours there: that it
 * reports the picture's size at all, a 2160-line picture of the file's video, an HDR file shown as SDR, Dolby Vision
 * shown, and, on a device that reported pictures before, PLAYING 5 s with no picture of the file's video. A later picture
 * clears what silence said. Another sender's media teaches nothing, and nothing about sound is ever kept (W41). What it
 * learns is kept only on a change. The device and the files are invented.
 */
class CastLearnTest {
    private val hevc10 = ProbedTrack(MimeTypes.VIDEO_H265, "HEVC", width = 3840, height = 2160, bitDepth = 10)
    private val ac3 = ProbedTrack(MimeTypes.AUDIO_AC3, "AC-3", "en", channels = 6)
    private val film = ProbedTracks(listOf(hevc10), listOf(ac3))
    private val hdr10Film = ProbedTracks(listOf(hevc10.copy(hdr = Hdr.Hdr10)), listOf(ac3))

    private fun learn(tracks: ProbedTracks? = film) = CastLearn(DEVICE, LOAD, tracks)

    private fun status(at: Long, video: CastVideo? = null, state: CastPlayerState = CastPlayerState.Playing, load: String? = LOAD) =
        CastEvent.Status(state, null, load, 0, at, video)

    private val uhd = CastVideo(3840, 2160, CastHdr.Sdr)

    @Test
    fun `a 2160-line picture says it reports pictures and shows 4K of the file's video`() {
        val learned = assertNotNull(learn().heard(null, status(1_000, uhd), WALL))
        assertEquals(CastDeviceMemory(DEVICE, reportsVideo = true, shown2160 = listOf(CastVideoCodec("Hevc", tenBit = true)), seenAt = WALL), learned)
        // A film cropped to 2.40:1 is 4K as well; a 1080-line picture says only that it reports pictures.
        assertEquals(listOf(CastVideoCodec("Hevc", tenBit = true)), learn().heard(null, status(1_000, CastVideo(3840, 1600, CastHdr.Sdr)), WALL)?.shown2160)
        assertEquals(CastDeviceMemory(DEVICE, reportsVideo = true, seenAt = WALL), learn().heard(null, status(1_000, CastVideo(1920, 1080, CastHdr.Sdr)), WALL))
        // Kept beside what it showed of another video.
        val before = CastDeviceMemory(DEVICE, reportsVideo = true, shown2160 = listOf(CastVideoCodec("H264")), seenAt = 5)
        assertEquals(listOf(CastVideoCodec("H264"), CastVideoCodec("Hevc", tenBit = true)), learn().heard(before, status(1_000, uhd), WALL)?.shown2160)
    }

    /** Written only on a change: what it already knows, heard again, is nothing new. */
    @Test
    fun `what the device already showed teaches nothing new`() {
        val learning = learn()
        val learned = assertNotNull(learning.heard(null, status(1_000, uhd), WALL))
        assertNull(learning.heard(learned, status(2_000, uhd), WALL + 1_000))
        assertNull(learn().heard(learned, status(1_000, uhd), WALL + 9_000))
        // A status of no picture while it is paused or loading says nothing either.
        assertNull(learn().heard(learned, status(1_000, state = CastPlayerState.Paused), WALL))
        assertNull(learn().heard(learned, status(1_000, state = CastPlayerState.Buffering), WALL))
    }

    @Test
    fun `an HDR file shown as SDR says the device shows no HDR, until it shows some`() {
        val sdr = assertNotNull(learn(hdr10Film).heard(null, status(1_000, CastVideo(1920, 1080, CastHdr.Sdr)), WALL))
        assertEquals(true, sdr.hdrSdr)
        // An SDR file shown as SDR says nothing of HDR.
        assertEquals(false, learn().heard(null, status(1_000, CastVideo(1920, 1080, CastHdr.Sdr)), WALL)?.hdrSdr)
        // Of no known HDR type, nothing either way.
        assertNull(learn(hdr10Film).heard(sdr, status(1_000, CastVideo(1920, 1080, CastHdr.Unknown)), WALL))
        // Any HDR shown clears it.
        for (shown in listOf(CastHdr.Hdr10, CastHdr.Hdr, CastHdr.Dv)) {
            assertEquals(false, learn(hdr10Film).heard(sdr, status(1_000, CastVideo(1920, 1080, shown)), WALL)?.hdrSdr, "$shown")
        }
    }

    /**
     * CFS-6: a device without Dolby Vision shows a Dolby Vision file by its base layer. Profiles 7 and 8 carry an HDR10 or
     * HLG base, so SDR there says the device shows no HDR; profile 5's base is no HDR it could show, nor is one of no known
     * profile: SDR of those says nothing of HDR10, and nothing is learned against it.
     */
    @Test
    fun `a Dolby Vision file shown as SDR says no HDR only where its base is HDR the device could show`() {
        fun dolbyVision(profile: Int?) = ProbedTracks(listOf(hevc10.copy(mime = MimeTypes.VIDEO_DOLBY_VISION, label = "Dolby Vision", hdr = Hdr.DolbyVision, dolbyVisionProfile = profile)), listOf(ac3))
        val sdr = CastVideo(3840, 2160, CastHdr.Sdr)
        for (profile in listOf(7, 8)) assertEquals(true, learn(dolbyVision(profile)).heard(null, status(1_000, sdr), WALL)?.hdrSdr, "profile $profile")
        for (profile in listOf(5, null)) {
            val learned = assertNotNull(learn(dolbyVision(profile)).heard(null, status(1_000, sdr), WALL), "profile $profile")
            assertEquals(false, learned.hdrSdr, "profile $profile")
            assertEquals(true, learned.reportsVideo)
        }
    }

    @Test
    fun `Dolby Vision shown says the device shows it`() {
        val dolbyVision = ProbedTracks(listOf(ProbedTrack(MimeTypes.VIDEO_DOLBY_VISION, "Dolby Vision", width = 1920, height = 1080, bitDepth = 10, hdr = Hdr.DolbyVision, dolbyVisionProfile = 8)), listOf(ac3))
        assertEquals(true, learn(dolbyVision).heard(null, status(1_000, CastVideo(1920, 1080, CastHdr.Dv)), WALL)?.dolbyVision)
        assertEquals(false, learn(dolbyVision).heard(null, status(1_000, CastVideo(1920, 1080, CastHdr.Hdr10)), WALL)?.dolbyVision)
    }

    /**
     * PLAYING 5 s with no picture, on a device that has reported pictures before: the file's video, at its bit depth, gives
     * no picture there. One that never reported a picture is never marked wrong by its silence.
     */
    @Test
    fun `playing 5 s with no picture marks the file's video no picture, only on a device that reports pictures`() {
        val reports = CastDeviceMemory(DEVICE, reportsVideo = true, seenAt = 5)
        val learning = learn()
        assertNull(learning.heard(reports, status(10_000), WALL))
        assertNull(learning.tick(reports, 14_999, WALL))
        assertEquals(reports.copy(noPicture = listOf(CastVideoCodec("Hevc", tenBit = true)), seenAt = WALL), learning.tick(reports, 15_000, WALL))
        // Marked once.
        val marked = reports.copy(noPicture = listOf(CastVideoCodec("Hevc", tenBit = true)), seenAt = WALL)
        assertNull(learning.tick(marked, 20_000, WALL))
        // Never from a device that hasn't reported a picture, nor of a device it knows nothing of.
        val silent = learn()
        silent.heard(CastDeviceMemory(DEVICE, seenAt = 5), status(10_000), WALL)
        assertNull(silent.tick(CastDeviceMemory(DEVICE, seenAt = 5), 30_000, WALL))
        assertNull(silent.tick(null, 30_000, WALL))
        // Paused or loading on the way: the 5 s start again from the next PLAYING.
        val paused = learn()
        paused.heard(reports, status(10_000), WALL)
        paused.heard(reports, status(12_000, state = CastPlayerState.Buffering), WALL)
        paused.heard(reports, status(13_000), WALL)
        assertNull(paused.tick(reports, 17_999, WALL))
        assertNotNull(paused.tick(reports, 18_000, WALL))
        // A picture shown in this play: its silence later is no picture's.
        val showed = learn()
        assertNull(showed.heard(reports, status(10_000, CastVideo(1920, 1080, CastHdr.Sdr)), WALL), "nothing new of a device that reports pictures")
        showed.heard(reports, status(11_000), WALL)
        assertNull(showed.tick(reports, 30_000, WALL))
        // A file whose video the probe didn't read can't be marked.
        val unread = learn(null)
        unread.heard(reports, status(10_000), WALL)
        assertNull(unread.tick(reports, 30_000, WALL))
    }

    @Test
    fun `a picture of a video marked no picture clears it, and HDR shown clears SDR`() {
        val marked = CastDeviceMemory(DEVICE, reportsVideo = true, hdrSdr = true, noPicture = listOf(CastVideoCodec("Hevc", tenBit = true), CastVideoCodec("Vc1")), seenAt = 5)
        val cleared = assertNotNull(learn(hdr10Film).heard(marked, status(1_000, CastVideo(1920, 1080, CastHdr.Hdr10)), WALL))
        assertEquals(listOf(CastVideoCodec("Vc1")), cleared.noPicture)
        assertEquals(false, cleared.hdrSdr)
        assertEquals(WALL, cleared.seenAt)
    }

    @Test
    fun `another sender's media teaches nothing`() {
        val learning = learn()
        assertNull(learning.heard(null, status(1_000, uhd, load = "another-senders-load"), WALL))
        assertNull(learning.heard(null, status(1_000, uhd, load = null), WALL))
        val reports = CastDeviceMemory(DEVICE, reportsVideo = true, seenAt = 5)
        learning.heard(reports, status(10_000, load = "another-senders-load"), WALL)
        assertNull(learning.tick(reports, 30_000, WALL))
    }

    /** W41: a device's sound is never an input. What a file's audio is changes nothing learned, and nothing of sound is kept. */
    @Test
    fun `nothing about sound is ever learned or kept`() {
        val dts = ProbedTracks(listOf(hevc10), listOf(ProbedTrack(MimeTypes.AUDIO_DTS_HD, "DTS-HD", "en", channels = 8)))
        val silent = ProbedTracks(listOf(hevc10), emptyList())
        val learned = listOf(film, dts, silent).map { learn(it).heard(null, status(1_000, uhd), WALL) }
        assertEquals(1, learned.distinct().size)
        assertEquals(
            listOf("id", "reportsVideo", "shown2160", "hdrSdr", "dolbyVision", "noPicture", "seenAt"),
            (0 until CastDeviceMemory.serializer().descriptor.elementsCount).map { CastDeviceMemory.serializer().descriptor.getElementName(it) },
        )
    }

    private companion object {
        const val DEVICE = "device-kitchen"
        const val LOAD = "load-1"
        const val WALL = 1_762_300_000_000L
    }
}
