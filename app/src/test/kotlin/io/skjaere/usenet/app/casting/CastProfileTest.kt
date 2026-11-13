package io.skjaere.usenet.app.casting

import io.skjaere.usenet.data.CastDeviceMemory
import io.skjaere.usenet.data.CastPrefs
import io.skjaere.usenet.data.CastVideoCodec
import io.skjaere.usenet.releases.AudioCodec
import io.skjaere.usenet.releases.Hdr
import io.skjaere.usenet.releases.VideoCodec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L128: a Cast device's profile, as the ranking reads it. Its model's row first (by the model name its route gives), then
 * what the device itself has shown, kept on the phone by its Cast id for at most 20 devices. AC-3 and E-AC-3 play on every
 * device, DTS and TrueHD on none, and nothing about sound is ever learned (W41). The device ids and names are invented.
 */
class CastProfileTest {
    private val none = CastProfiles(emptyList())

    /** Every video the profile shows, at 8 and at 10 bits, each with the largest height it shows of it. */
    private fun CastProfile.shows(): Map<Pair<VideoCodec, Boolean>, Int?> =
        VideoCodec.entries.flatMap { codec -> listOf(false, true).map { codec to it } }
            .filter { (codec, tenBit) -> canDecode(codec, tenBit) }
            .associateWith { (codec, tenBit) -> maxHeight(codec, tenBit) }

    @Test
    fun `a Chromecast of the first three generations shows H-264 up to 1080p, without HDR`() {
        val profile = none.of("device-kitchen", "Chromecast")
        assertEquals(mapOf((VideoCodec.H264 to false) to 1080), profile.shows())
        assertEquals(1080, profile.displayHeight)
        assertEquals(emptySet(), profile.hdrTypes)
        assertFalse(profile.dolbyVision)
    }

    @Test
    fun `a Chromecast Ultra shows HEVC and VP9 up to 2160p at 8 and 10 bits, H-264 up to 1080p, HDR10 and Dolby Vision`() {
        val profile = none.of("device-kitchen", "Chromecast Ultra")
        assertEquals(
            mapOf(
                (VideoCodec.H264 to false) to 1080,
                (VideoCodec.Hevc to false) to 2160, (VideoCodec.Hevc to true) to 2160,
                (VideoCodec.Vp9 to false) to 2160, (VideoCodec.Vp9 to true) to 2160,
            ),
            profile.shows(),
        )
        assertEquals(2160, profile.displayHeight)
        assertEquals(setOf(Hdr.Hdr10, Hdr.DolbyVision), profile.hdrTypes)
        assertTrue(profile.dolbyVision)
    }

    /** W53: the Shield counts as 4K from the start; mDNS said "SHIELD Android TV" (O4 confirms Android's string). */
    @Test
    fun `a Shield, in any case, shows 4K, VP9 at 10 bits only up to 1080p, no AV1, every HDR, and Dolby Vision only once shown`() {
        listOf("SHIELD Android TV", "shield android tv").forEach { model ->
            val profile = none.of("device-living-room", model)
            assertEquals(
                mapOf(
                    (VideoCodec.H264 to false) to 2160,
                    (VideoCodec.Hevc to false) to 2160, (VideoCodec.Hevc to true) to 2160,
                    (VideoCodec.Vp9 to false) to 2160, (VideoCodec.Vp9 to true) to 1080,
                ),
                profile.shows(), model,
            )
            assertEquals(2160, profile.displayHeight, model)
            assertEquals(Hdr.entries.toSet(), profile.hdrTypes, model)
            assertFalse(profile.dolbyVision, model)
        }
    }

    /** A TV with Google Cast built in or a newer Chromecast, whose model names are unverified: nothing demoted but the sure. */
    @Test
    fun `any other model, or none, plays H-264, HEVC, VP9 and AV1 with no known limit, every HDR, and aims at 1080p`() {
        listOf("BRAVIA 4K VH2", "Chromecast with Google TV", "Google TV Streamer", "Chromecast 2", " ", null).forEach { model ->
            val profile = none.of("device-den", model)
            assertEquals(
                mapOf(
                    (VideoCodec.H264 to false) to null,
                    (VideoCodec.Hevc to false) to null, (VideoCodec.Hevc to true) to null,
                    (VideoCodec.Av1 to false) to null, (VideoCodec.Av1 to true) to null,
                    (VideoCodec.Vp9 to false) to null, (VideoCodec.Vp9 to true) to null,
                ),
                profile.shows(), "$model",
            )
            assertEquals(1080, profile.displayHeight, "$model")
            assertEquals(Hdr.entries.toSet(), profile.hdrTypes, "$model")
            assertFalse(profile.dolbyVision, "$model")
        }
    }

    /** AC-3 and E-AC-3 are fine on every device; DTS and TrueHD play on none; no AAC or Opus preference. */
    @Test
    fun `every device plays AAC, AC-3, E-AC-3, Opus, MP3, FLAC and PCM, and no DTS or TrueHD`() {
        val plays = setOf(AudioCodec.Aac, AudioCodec.Ac3, AudioCodec.Eac3, AudioCodec.Opus, AudioCodec.Mp3, AudioCodec.Flac, AudioCodec.Pcm)
        for (profile in everyRow()) {
            assertEquals(plays, AudioCodec.entries.filter { profile.canDecode(it) }.toSet(), "$profile")
        }
    }

    @Test
    fun `no device shows VC-1, MPEG-2, XviD or H-264 at 10 bits, and none has a hardware limit`() {
        for (profile in everyRow()) {
            for (codec in listOf(VideoCodec.Vc1, VideoCodec.Mpeg2, VideoCodec.Xvid)) {
                assertFalse(profile.canDecode(codec, tenBit = false) || profile.canDecode(codec, tenBit = true), "$codec on $profile")
            }
            assertFalse(profile.canDecode(VideoCodec.H264, tenBit = true), "$profile")
            for (codec in VideoCodec.entries) for (tenBit in listOf(false, true)) assertNull(profile.hardwareMaxHeight(codec, tenBit), "$codec on $profile")
        }
    }

    private fun everyRow() = listOf("Chromecast", "Chromecast Ultra", "SHIELD Android TV", "BRAVIA 4K VH2", null).map { none.of("device-den", it) }

    // What a device has shown, kept by its Cast id: it wins over its model's row.

    /** W53: Den TV's "2160p: Den TV hasn't played 4K yet" goes once it has. */
    @Test
    fun `a device of an unknown model aims at 1080p until it has shown a 4K picture, then at 2160p`() {
        val before = CastProfiles(listOf(CastDeviceMemory("device-den", reportsVideo = true, seenAt = 1))).of("device-den", "BRAVIA 4K VH2")
        assertEquals(1080, before.displayHeight)
        val shown = CastProfiles(listOf(CastDeviceMemory("device-den", reportsVideo = true, shown2160 = listOf(CastVideoCodec("Hevc", tenBit = true)), seenAt = 2)))
        assertEquals(2160, shown.of("device-den", "BRAVIA 4K VH2").displayHeight)
        // Another device, or none named, learns nothing from it.
        assertEquals(1080, shown.of("device-kitchen", "BRAVIA 4K VH2").displayHeight)
        assertEquals(1080, shown.of(null, "BRAVIA 4K VH2").displayHeight)
    }

    @Test
    fun `a 4K picture shown lifts that video's limit to 2160p, and only that one's`() {
        val profiles = CastProfiles(listOf(CastDeviceMemory("device-living-room", shown2160 = listOf(CastVideoCodec("Vp9", tenBit = true)), seenAt = 1)))
        val shield = profiles.of("device-living-room", "SHIELD Android TV")
        assertEquals(2160, shield.maxHeight(VideoCodec.Vp9, tenBit = true))
        assertEquals(2160, shield.maxHeight(VideoCodec.Hevc, tenBit = true))
        val ultra = CastProfiles(listOf(CastDeviceMemory("device-kitchen", shown2160 = listOf(CastVideoCodec("H264")), seenAt = 1))).of("device-kitchen", "Chromecast Ultra")
        assertEquals(2160, ultra.maxHeight(VideoCodec.H264, tenBit = false))
        // A picture of a video its row says it doesn't show: it shows it.
        val old = CastProfiles(listOf(CastDeviceMemory("device-kitchen", shown2160 = listOf(CastVideoCodec("Hevc")), seenAt = 1))).of("device-kitchen", "Chromecast")
        assertEquals(2160, old.maxHeight(VideoCodec.Hevc, tenBit = false))
        assertFalse(old.canDecode(VideoCodec.Hevc, tenBit = true))
        // No limit known stays none.
        val other = CastProfiles(listOf(CastDeviceMemory("device-den", shown2160 = listOf(CastVideoCodec("Hevc")), seenAt = 1))).of("device-den", null)
        assertNull(other.maxHeight(VideoCodec.Hevc, tenBit = false))
        assertTrue(other.canDecode(VideoCodec.Hevc, tenBit = false))
    }

    @Test
    fun `a device that showed an HDR file as SDR has no HDR`() {
        val profiles = CastProfiles(listOf(CastDeviceMemory("device-living-room", reportsVideo = true, hdrSdr = true, seenAt = 1)))
        assertEquals(emptySet(), profiles.of("device-living-room", "SHIELD Android TV").hdrTypes)
        assertEquals(emptySet(), profiles.of("device-living-room", "Chromecast Ultra").hdrTypes)
        assertEquals(Hdr.entries.toSet(), profiles.of("device-den", "SHIELD Android TV").hdrTypes)
    }

    @Test
    fun `a device that showed Dolby Vision has it`() {
        val profiles = CastProfiles(listOf(CastDeviceMemory("device-living-room", reportsVideo = true, dolbyVision = true, seenAt = 1)))
        val shield = profiles.of("device-living-room", "SHIELD Android TV")
        assertTrue(shield.dolbyVision)
        assertTrue(Hdr.DolbyVision in shield.hdrTypes)
        assertFalse(profiles.of("device-den", "SHIELD Android TV").dolbyVision)
    }

    @Test
    fun `a video a device played with no picture shows on it no more, at that bit depth only, and on that device only`() {
        val profiles = CastProfiles(listOf(CastDeviceMemory("device-den", reportsVideo = true, noPicture = listOf(CastVideoCodec("Hevc", tenBit = true)), seenAt = 1)))
        val den = profiles.of("device-den", "BRAVIA 4K VH2")
        assertFalse(den.canDecode(VideoCodec.Hevc, tenBit = true))
        assertNull(den.maxHeight(VideoCodec.Hevc, tenBit = true))
        assertTrue(den.canDecode(VideoCodec.Hevc, tenBit = false))
        assertTrue(profiles.of("device-kitchen", "BRAVIA 4K VH2").canDecode(VideoCodec.Hevc, tenBit = true))
        // The same said of a 4K picture shown: no picture wins, as the newer fact (a later picture clears it, item 45).
        val both = CastProfiles(listOf(CastDeviceMemory("device-den", shown2160 = listOf(CastVideoCodec("Hevc", tenBit = true)), noPicture = listOf(CastVideoCodec("Hevc", tenBit = true)), seenAt = 1)))
        assertFalse(both.of("device-den", null).canDecode(VideoCodec.Hevc, tenBit = true))
    }

    /** A name only a newer version knows reads, and teaches this one nothing. */
    @Test
    fun `a video by a name this version doesn't know is passed over`() {
        val profiles = CastProfiles(listOf(CastDeviceMemory("device-den", shown2160 = listOf(CastVideoCodec("Vvc")), noPicture = listOf(CastVideoCodec("Vvc", tenBit = true)), seenAt = 1)))
        assertEquals(none.of("device-den", "BRAVIA 4K VH2"), profiles.of("device-den", "BRAVIA 4K VH2"))
    }

    /** W41: a device's silence is never an input; nothing it shows changes what it plays of the sound. */
    @Test
    fun `nothing a device has shown changes its sound`() {
        val everything = CastDeviceMemory(
            "device-den", reportsVideo = true, shown2160 = listOf(CastVideoCodec("Hevc")), hdrSdr = true, dolbyVision = true,
            noPicture = listOf(CastVideoCodec("Av1")), seenAt = 1,
        )
        val learned = CastProfiles(listOf(everything)).of("device-den", "SHIELD Android TV")
        val row = none.of("device-den", "SHIELD Android TV")
        assertEquals(AudioCodec.entries.filter { row.canDecode(it) }, AudioCodec.entries.filter { learned.canDecode(it) })
    }

    /** The same facts give the same profile, so a list ranked for a device isn't ranked again for when it was last seen. */
    @Test
    fun `a device's profile is the same whenever it was seen`() {
        val at = { seenAt: Long -> CastProfiles(listOf(CastDeviceMemory("device-den", reportsVideo = true, shown2160 = listOf(CastVideoCodec("Hevc")), seenAt = seenAt))) }
        assertEquals(at(1).of("device-den", null), at(2).of("device-den", null))
    }

    /** At most 20 devices are kept: the 21st drops the one seen longest ago; a device seen again takes its place anew. */
    @Test
    fun `the 21st device drops the one seen longest ago`() {
        val twenty = (1..20).fold(CastPrefs()) { prefs, n -> prefs.remembering(CastDeviceMemory("device-$n", seenAt = n * 10L)) }
        assertEquals(20, twenty.devices.size)
        assertEquals(CastPrefs.MAX_DEVICES, twenty.devices.size)
        val more = twenty.remembering(CastDeviceMemory("device-21", seenAt = 210))
        assertEquals(20, more.devices.size)
        assertEquals((2..21).map { "device-$it" }.toSet(), more.devices.map { it.id }.toSet())
        // device-2 seen again: kept once, with what it showed now, and device-3 is then the one seen longest ago.
        val again = more.remembering(CastDeviceMemory("device-2", reportsVideo = true, seenAt = 220)).remembering(CastDeviceMemory("device-22", seenAt = 230))
        assertEquals(20, again.devices.size)
        assertEquals(listOf(CastDeviceMemory("device-2", reportsVideo = true, seenAt = 220)), again.devices.filter { it.id == "device-2" })
        assertFalse(again.devices.any { it.id == "device-3" })
        // A device written at the same moment as the oldest still goes in.
        val tied = twenty.remembering(CastDeviceMemory("device-new", seenAt = 10))
        assertTrue(tied.devices.any { it.id == "device-new" })
        assertEquals(20, tied.devices.size)
    }
}
