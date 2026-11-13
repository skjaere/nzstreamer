package io.skjaere.usenet.app.settings

import io.skjaere.usenet.app.settings.ThisDevice.Row
import io.skjaere.usenet.logging.redactSecrets
import io.skjaere.usenet.player.DecoderSummary
import io.skjaere.usenet.player.DisplayFacts
import io.skjaere.usenet.player.DisplayModes
import io.skjaere.usenet.player.DolbyVisionProfile
import io.skjaere.usenet.player.MatchContentFrameRate
import io.skjaere.usenet.player.ModeSize
import io.skjaere.usenet.player.NativeFacts
import io.skjaere.usenet.player.VideoDecoding
import io.skjaere.usenet.player.tv.DeviceMemory
import io.skjaere.usenet.releases.AudioCodec
import io.skjaere.usenet.releases.Hdr
import io.skjaere.usenet.releases.VideoCodec
import io.skjaere.yenc.RapidYencKernel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings › Playback › This device ([ThisDevice]): its sections in the approved mockups' order and words (TdTvFull,
 * TdPhoneFull), and the log header's lines from the same facts. The Shield's facts are those its library tests read; the
 * phone's are an invented flagship's.
 */
class ThisDeviceTest {
    private val saved = Locale.getDefault()

    // The page's decimals follow the default locale, the header's never do; pin one so the page's are predictable.
    @BeforeEach
    fun pin() {
        Locale.setDefault(Locale.US)
    }

    @AfterEach
    fun unpin() {
        Locale.setDefault(saved)
    }

    private val mib = 1024L * 1024
    private val uhd = ModeSize(3840, 2160)

    private val shieldMemory = DeviceMemory(
        lowRam = false, memoryClassMb = 192, totalRamBytes = 3_023_620L * 1024, process64Bit = true,
        abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"), heapLimitBytes = 512 * mib,
    )
    private val shieldDecoders = DecoderSummary(
        video = listOf(
            VideoDecoding(VideoCodec.H264, false, 2160, 2160),
            VideoDecoding(VideoCodec.Hevc, false, 2160, 2160),
            VideoDecoding(VideoCodec.Hevc, true, 2160, 2160),
            VideoDecoding(VideoCodec.Vp9, false, 2160, 2160),
            VideoDecoding(VideoCodec.Vp9, true, 1080, 0),
            VideoDecoding(VideoCodec.Xvid, false, 576, 1080),
            VideoDecoding(VideoCodec.Vc1, false, 2160, 1080),
            VideoDecoding(VideoCodec.Mpeg2, false, 1080, 2160),
        ),
        dolbyVision = true,
        // As the summary keeps them: in the order of their codecs strings.
        dolbyVisionProfiles = listOf(DolbyVisionProfile(9, 9), DolbyVisionProfile(4, 9), DolbyVisionProfile(5, 9), DolbyVisionProfile(8, 9)),
        noDecoder = listOf(VideoCodec.Av1),
        audioDecoded = listOf(AudioCodec.Aac, AudioCodec.Flac, AudioCodec.Opus, AudioCodec.Mp3, AudioCodec.Pcm),
        audioPassedThrough = listOf(AudioCodec.Ac3, AudioCodec.Eac3),
    )
    private val shieldRates = listOf(23.976025f, 24f, 25f, 29.97003f, 30f, 50f, 59.94006f, 60f)

    private val shield = ThisDevice(
        manufacturer = "NVIDIA", model = "SHIELD Android TV", androidRelease = "11", sdk = 30, television = true,
        abis64 = listOf("arm64-v8a"), abis32 = listOf("armeabi-v7a", "armeabi"),
        memory = shieldMemory,
        display = DisplayFacts(tv = true, output = uhd, fromTv = true, largest = uhd, window = ModeSize(1920, 1080), hdr = setOf(Hdr.DolbyVision, Hdr.Hdr10)),
        refreshRate = 59.94006f,
        modes = DisplayModes(uhd, shieldRates, matchContentFrameRate = null),
        decoders = shieldDecoders,
        native = NativeFacts.Loaded("1.1.1", RapidYencKernel.NEON, RapidYencKernel.GENERIC),
        settled = true,
    )

    private val panel = ModeSize(1440, 3120)
    private val phone = ThisDevice(
        manufacturer = "example", model = "QZ-7701", androidRelease = "15", sdk = 35, television = false,
        abis64 = listOf("arm64-v8a"), abis32 = emptyList(),
        memory = DeviceMemory(lowRam = false, memoryClassMb = 256, totalRamBytes = 8_589_934_592, process64Bit = true, abis = listOf("arm64-v8a"), heapLimitBytes = 512 * mib),
        display = DisplayFacts(tv = false, output = panel, fromTv = false, largest = panel, window = panel, hdr = setOf(Hdr.Hdr10, Hdr.Hdr10Plus, Hdr.Hlg)),
        refreshRate = 120f,
        modes = DisplayModes(panel, listOf(60f, 120f), MatchContentFrameRate.OnlyWhenSeamless),
        decoders = DecoderSummary(
            video = listOf(
                VideoDecoding(VideoCodec.H264, false, 2160, 2160),
                VideoDecoding(VideoCodec.Hevc, false, 2160, 2160),
                VideoDecoding(VideoCodec.Hevc, true, 2160, 2160),
                VideoDecoding(VideoCodec.Av1, false, 2160, 2160),
                VideoDecoding(VideoCodec.Av1, true, 2160, 2160),
                VideoDecoding(VideoCodec.Vp9, false, 2160, 2160),
                VideoDecoding(VideoCodec.Vp9, true, 2160, 2160),
                VideoDecoding(VideoCodec.Xvid, false, 1080, 0),
            ),
            dolbyVision = true,
            dolbyVisionProfiles = listOf(DolbyVisionProfile(8, 6)),
            noDecoder = listOf(VideoCodec.Vc1, VideoCodec.Mpeg2),
            audioDecoded = listOf(AudioCodec.Aac, AudioCodec.Ac3, AudioCodec.Eac3, AudioCodec.Flac, AudioCodec.Opus, AudioCodec.Mp3, AudioCodec.Pcm),
            audioPassedThrough = emptyList(),
        ),
        native = NativeFacts.Loaded("1.1.1", RapidYencKernel.NEON, RapidYencKernel.ARMCRC),
        settled = true,
    )

    /** A 1.5 GB low-RAM box on a 4K TV: the buffer's middle tier. */
    private val lowRamBox = shield.copy(
        memory = shieldMemory.copy(lowRam = true, totalRamBytes = 1_536L * mib, memoryClassMb = 192, heapLimitBytes = 192 * mib),
    )

    private fun ThisDevice.section(title: String) = sections.single { it.title == title }.rows
    private fun ThisDevice.row(title: String, label: String) = section(title).single { it.label == label }

    @Test
    fun `the sections are Device, Display, Video, Audio and yEnc decoding, as the mockups have them`() {
        assertEquals(listOf("Device", "Display", "Video", "Audio", "yEnc decoding"), shield.sections.map { it.title })
        assertEquals(listOf("Device", "Display", "Video", "Audio", "yEnc decoding"), phone.sections.map { it.title })
    }

    @Test
    fun `the Shield's Device card`() {
        assertEquals(
            listOf(
                Row("Model", "NVIDIA SHIELD Android TV"),
                Row("Android", "11 (API 30)"),
                Row("Kind", "TV"),
                Row("Process", "64-bit · arm64-v8a", "the device also runs armeabi-v7a, armeabi"),
                Row("Memory", "2.9 GB RAM", "heap 512 MB (class 192 MB) · not a low-RAM device"),
                Row("Play budget", "player buffer 85 MB · streams 51 MB ahead", "images: the image loader’s default"),
            ),
            shield.section("Device"),
        )
    }

    @Test
    fun `the Shield's Display card, Match frame rate a TV's only`() {
        assertEquals(
            listOf(
                Row("Output", "3840 × 2160 · 59.94 Hz"),
                Row("Largest mode", "3840 × 2160"),
                Row("Auto quality", "2160p and below"),
                Row("Refresh rates", "23.976, 24, 25, 29.97, 30, 50, 59.94, 60 Hz", "at 3840 × 2160"),
                Row("HDR", "Dolby Vision, HDR10", "just after the TV wakes, Dolby Vision can be missing for a few seconds", hdr = listOf(Hdr.DolbyVision, Hdr.Hdr10)),
                Row("Match frame rate", "Android 11 doesn’t tell apps this setting", MATCH_NOTE),
            ),
            shield.section("Display"),
        )
    }

    /**
     * On a TV the Match frame rate row points to the app's own switch, on either side of Android 12, beside the system's
     * value: the page says what the device is, never what the setting is (FrTvThisDevice).
     */
    @Test
    fun `on a TV Match frame rate points to the app's own switch, not its value`() {
        assertEquals(MATCH_NOTE, shield.row("Display", "Match frame rate").note)
        val google = shield.copy(sdk = 34, androidRelease = "14", modes = shield.modes!!.copy(matchContentFrameRate = MatchContentFrameRate.Never))
        assertEquals(Row("Match frame rate", "Never", MATCH_NOTE), google.row("Display", "Match frame rate"))
        assertEquals("the app can switch the TV itself: Quality and languages › Match the TV to the video’s frame rate", MATCH_NOTE)
        // The Over HDMI row follows a receiver switched on or off while the app runs.
        assertEquals("passed through to the TV or receiver, as it is now: switching a receiver on or off updates this", HDMI_NOTE)
    }

    @Test
    fun `the Shield's Video card, a row per codec in the codecs' order`() {
        assertEquals(
            listOf(
                Row("H.264", "up to 2160p · hardware"),
                Row("HEVC", "up to 2160p · hardware", "10-bit up to 2160p, hardware"),
                Row("VP9", "up to 2160p · hardware", "10-bit up to 1080p, software"),
                Row("XviD", "up to 576p · hardware"),
                Row("VC-1", "up to 2160p · hardware up to 1080p"),
                Row("MPEG-2", "up to 1080p · hardware"),
                Row("Dolby Vision", "profiles 4, 5, 8, 9", "dvhe.04, dvhe.05, dvhe.08, dvav.09"),
                Row("No decoder", "AV1"),
            ),
            shield.section("Video"),
        )
    }

    @Test
    fun `the Shield's Audio and yEnc cards, the generic CRC noted as slower`() {
        assertEquals(
            listOf(
                Row("Decoded", "AAC, FLAC, Opus, MP3, PCM"),
                Row("Over HDMI", "DD, DD+", HDMI_NOTE),
            ),
            shield.section("Audio"),
        )
        assertEquals(
            listOf(
                Row("rapidyenc", "1.1.1"),
                Row("Decode", "NEON"),
                Row("CRC32", "generic code", warning = "slower: this processor’s faster instructions weren’t found"),
            ),
            shield.section("yEnc decoding"),
        )
    }

    @Test
    fun `a phone's page, its screen's own rates, no Match frame rate, Over HDMI only when something passes`() {
        assertEquals(
            listOf(
                Row("Model", "example QZ-7701"),
                Row("Android", "15 (API 35)"),
                Row("Kind", "Phone or tablet"),
                Row("Process", "64-bit · arm64-v8a"),
                Row("Memory", "8.0 GB RAM", "heap 512 MB (class 256 MB) · not a low-RAM device"),
                Row("Play budget", "player buffer 85 MB · streams 51 MB ahead", "images: the image loader’s default"),
            ),
            phone.section("Device"),
        )
        assertEquals(
            listOf(
                Row("Output", "1440 × 3120 · 120 Hz"),
                Row("Largest mode", "1440 × 3120"),
                Row("Auto quality", "1440p and below"),
                Row("Refresh rates", "60, 120 Hz", "the screen’s own rates"),
                Row("HDR", "HDR10, HDR10+, HLG", hdr = listOf(Hdr.Hdr10, Hdr.Hdr10Plus, Hdr.Hlg)),
            ),
            phone.section("Display"),
        )
        assertEquals(
            listOf(
                Row("H.264", "up to 2160p · hardware"),
                Row("HEVC", "up to 2160p · hardware", "10-bit up to 2160p, hardware"),
                Row("AV1", "up to 2160p · hardware", "10-bit up to 2160p, hardware"),
                Row("VP9", "up to 2160p · hardware", "10-bit up to 2160p, hardware"),
                Row("XviD", "up to 1080p · software"),
                Row("Dolby Vision", "profile 8", "dvhe.08"),
                Row("No decoder", "VC-1, MPEG-2"),
            ),
            phone.section("Video"),
        )
        assertEquals(listOf(Row("Decoded", "AAC, DD, DD+, FLAC, Opus, MP3, PCM")), phone.section("Audio"))
        assertEquals(listOf(Row("rapidyenc", "1.1.1"), Row("Decode", "NEON"), Row("CRC32", "ARM CRC32")), phone.section("yEnc decoding"))
        // A phone that passes audio over HDMI says so; a TV that passes none says that too.
        val passing = phone.copy(decoders = phone.decoders!!.copy(audioPassedThrough = listOf(AudioCodec.Ac3)))
        assertEquals(Row("Over HDMI", "DD", HDMI_NOTE), passing.row("Audio", "Over HDMI"))
        val none = shield.copy(decoders = shieldDecoders.copy(audioDecoded = emptyList(), audioPassedThrough = emptyList()))
        assertEquals(
            listOf(Row("Decoded", "none"), Row("Over HDMI", "none", HDMI_NOTE)),
            none.section("Audio"),
        )
    }

    @Test
    fun `the Kind row says the device's kind, and the layouts when a debug build shows the other's`() {
        assertEquals("Phone or tablet, showing the TV’s layouts", phone.copy(tvLayouts = true).row("Device", "Kind").value)
        assertEquals("TV, showing the phone’s layouts", shield.copy(tvLayouts = false).row("Device", "Kind").value)
        assertEquals("TV", shield.copy(tvLayouts = true).row("Device", "Kind").value)
    }

    @Test
    fun `a 32-bit process names its own ABI and the device's others`() {
        val thirtyTwo = shield.copy(memory = shieldMemory.copy(process64Bit = false))
        assertEquals(Row("Process", "32-bit · armeabi-v7a", "the device also runs arm64-v8a, armeabi"), thirtyTwo.row("Device", "Process"))
    }

    /** 85 MB is a sixth of the Shield's 512 MB heap, 51 MB a tenth; a low-RAM box's tiers (TV-ME), images in its few MB. */
    @Test
    fun `the play budget is the player's and the engine's`() {
        assertEquals(Row("Play budget", "player buffer 85 MB · streams 51 MB ahead", "images: the image loader’s default"), shield.row("Device", "Play budget"))
        assertEquals(Row("Memory", "1.5 GB RAM", "heap 192 MB (class 192 MB) · a low-RAM device"), lowRamBox.row("Device", "Memory"))
        assertEquals(Row("Play budget", "player buffer 80 MB · streams 12 MB ahead", "images: 12 MB"), lowRamBox.row("Device", "Play budget"))
        val hd = lowRamBox.copy(display = shield.display!!.copy(output = ModeSize(1920, 1080), largest = ModeSize(1920, 1080)))
        assertEquals(Row("Play budget", "player buffer 64 MB · streams 12 MB ahead", "images: 12 MB"), hd.row("Device", "Play budget"))
        val oneGb = lowRamBox.copy(memory = lowRamBox.memory!!.copy(totalRamBytes = 1_024L * mib))
        assertEquals(Row("Play budget", "player buffer 48 MB · streams 8 MB ahead", "images: 8 MB"), oneGb.row("Device", "Play budget"))
        assertEquals("Budget: player buffer 85 MB, streams 51 MB ahead, images the image loader's default", shield.headerLines.single { it.startsWith("Budget: ") })
        assertEquals("Budget: player buffer 48 MB, streams 8 MB ahead, images 8 MB", oneGb.headerLines.single { it.startsWith("Budget: ") })
    }

    @Test
    fun `a codec's sizes in each form`() {
        fun video(vararg decodings: VideoDecoding) = shield.copy(decoders = shieldDecoders.copy(video = decodings.toList())).section("Video").dropLast(2)
        assertEquals(
            listOf(
                Row("H.264", "up to 2160p · hardware"),
                Row("HEVC", "up to 2160p · hardware up to 1080p", "10-bit up to 1080p, software"),
                Row("AV1", "size unknown · hardware unknown", "10-bit below 480p, hardware unknown"),
                Row("VP9", "below 480p · software"),
                Row("XviD", "size unknown · hardware up to 720p"),
            ),
            video(
                VideoDecoding(VideoCodec.H264, false, 2160, 2160),
                VideoDecoding(VideoCodec.Hevc, false, 2160, 1080),
                VideoDecoding(VideoCodec.Hevc, true, 1080, 0),
                VideoDecoding(VideoCodec.Av1, false, null, null),
                VideoDecoding(VideoCodec.Av1, true, 0, null),
                VideoDecoding(VideoCodec.Vp9, false, 0, 0),
                VideoDecoding(VideoCodec.Xvid, false, null, 720),
            ),
        )
        // The hardware's height at least the largest: "hardware", however much larger.
        assertEquals(listOf(Row("H.264", "up to 1080p · hardware")), video(VideoDecoding(VideoCodec.H264, false, 1080, 2160)))
    }

    @Test
    fun `Dolby Vision without profiles, or without a decoder`() {
        val unlisted = shield.copy(decoders = shieldDecoders.copy(dolbyVisionProfiles = emptyList()))
        assertEquals(Row("Dolby Vision", "a decoder, no profiles listed"), unlisted.row("Video", "Dolby Vision"))
        val none = shield.copy(decoders = shieldDecoders.copy(dolbyVision = false, dolbyVisionProfiles = emptyList()))
        assertEquals(listOf("H.264", "HEVC", "VP9", "XviD", "VC-1", "MPEG-2", "No decoder"), none.section("Video").map { it.label })
        assertEquals(Row("No decoder", "AV1, Dolby Vision"), none.row("Video", "No decoder"))
        // Every codec decoded, Dolby Vision too: no No decoder row.
        assertEquals(listOf("H.264", "Dolby Vision"), shield.copy(decoders = shieldDecoders.copy(video = shieldDecoders.video.take(1), noDecoder = emptyList())).section("Video").map { it.label })
    }

    @Test
    fun `HDR none, or can't be read, and no wake note on a TV without Dolby Vision`() {
        val display = shield.display!!
        assertEquals(Row("HDR", "None"), shield.copy(display = display.copy(hdr = emptySet())).row("Display", "HDR"))
        assertEquals(Row("HDR", "Can’t be read"), shield.copy(display = display.copy(hdr = null)).row("Display", "HDR"))
        assertEquals(Row("HDR", "HDR10", hdr = listOf(Hdr.Hdr10)), shield.copy(display = display.copy(hdr = setOf(Hdr.Hdr10))).row("Display", "HDR"))
    }

    /** DisplayManager's MATCH_CONTENT_FRAMERATE_*: Always, Only when seamless, Never, or Not told; below Android 12 apps aren't told. */
    @Test
    fun `the match preference's words, on the page and in the header`() {
        fun on(match: MatchContentFrameRate?, sdk: Int = 34, release: String = "14") =
            shield.copy(sdk = sdk, androidRelease = release, modes = shield.modes!!.copy(matchContentFrameRate = match))
        listOf(
            MatchContentFrameRate.Always to ("Always" to "always"),
            MatchContentFrameRate.OnlyWhenSeamless to ("Only when seamless" to "only when seamless"),
            MatchContentFrameRate.Never to ("Never" to "never"),
            MatchContentFrameRate.Unknown to ("Not told" to "not told"),
        ).forEach { (match, words) ->
            val (page, header) = words
            assertEquals(Row("Match frame rate", page, MATCH_NOTE), on(match).row("Display", "Match frame rate"))
            assertTrue(on(match).headerLines.single { it.startsWith("Display modes: ") }.endsWith("; match content frame rate: $header"), "$match")
        }
        assertEquals(Row("Match frame rate", "Android 11 doesn’t tell apps this setting", MATCH_NOTE), shield.row("Display", "Match frame rate"))
        assertEquals(Row("Match frame rate", "Android 8.0.0 doesn’t tell apps this setting", MATCH_NOTE), on(null, sdk = 26, release = "8.0.0").row("Display", "Match frame rate"))
    }

    @Test
    fun `each kernel in words, and a library that didn't load in one row`() {
        val words = mapOf(
            RapidYencKernel.NEON to "NEON", RapidYencKernel.ARMCRC to "ARM CRC32", RapidYencKernel.ARMPMULL to "ARM PMULL",
            RapidYencKernel.SSE2 to "SSE2", RapidYencKernel.SSSE3 to "SSSE3", RapidYencKernel.AVX to "AVX", RapidYencKernel.AVX2 to "AVX2",
            RapidYencKernel.VBMI2 to "VBMI2", RapidYencKernel.PCLMUL to "PCLMUL", RapidYencKernel.VPCLMUL to "VPCLMUL",
            RapidYencKernel.RVV to "RISC-V vector", RapidYencKernel.ZBC to "RISC-V Zbc", RapidYencKernel.GENERIC to "generic code",
            RapidYencKernel.UNKNOWN to "unknown",
        )
        assertEquals(RapidYencKernel.entries.toSet(), words.keys)
        words.forEach { (kernel, said) ->
            val device = shield.copy(native = NativeFacts.Loaded("1.1.1", kernel, kernel))
            val slower = "slower: this processor’s faster instructions weren’t found".takeIf { kernel == RapidYencKernel.GENERIC }
            assertEquals(listOf(Row("rapidyenc", "1.1.1"), Row("Decode", said, warning = slower), Row("CRC32", said, warning = slower)), device.section("yEnc decoding"))
            assertEquals("Native: rapidyenc 1.1.1, decode $said, CRC $said", device.headerLines.single { it.startsWith("Native: ") })
        }
        val failed = shield.copy(native = NativeFacts.Failed("dlopen failed: library \"librapidyenc.so\" not found"))
        assertEquals(listOf(Row("rapidyenc", "Didn’t load", "dlopen failed: library \"librapidyenc.so\" not found", failed = true)), failed.section("yEnc decoding"))
        assertEquals("Native: rapidyenc didn't load: dlopen failed: library \"librapidyenc.so\" not found", failed.headerLines.single { it.startsWith("Native: ") })
    }

    /** Before the facts are read the page shows "…" (as Cache's sizes do); once every read is done, what none gave can't be read. */
    @Test
    fun `facts not read yet show an ellipsis, then can't be read`() {
        val reading = ThisDevice(manufacturer = "NVIDIA", model = "SHIELD Android TV", androidRelease = "11", sdk = 30, television = true)
        assertEquals(
            listOf(
                Row("Model", "NVIDIA SHIELD Android TV"), Row("Android", "11 (API 30)"), Row("Kind", "TV"),
                Row("Process", "…"), Row("Memory", "…"), Row("Play budget", "…"),
            ),
            reading.section("Device"),
        )
        assertEquals(
            listOf(
                Row("Output", "…"), Row("Largest mode", "…"), Row("Auto quality", "…"), Row("Refresh rates", "…"), Row("HDR", "…"),
                Row("Match frame rate", "Android 11 doesn’t tell apps this setting", MATCH_NOTE),
            ),
            reading.section("Display"),
        )
        assertEquals(listOf(Row("Decoders", "…")), reading.section("Video"))
        assertEquals(listOf(Row("Decoded", "…"), Row("Over HDMI", "…")), reading.section("Audio"))
        assertEquals(listOf(Row("rapidyenc", "…"), Row("Decode", "…"), Row("CRC32", "…")), reading.section("yEnc decoding"))
        val unread = reading.copy(settled = true)
        assertEquals(Row("Memory", "Can’t be read"), unread.row("Device", "Memory"))
        assertEquals(Row("rapidyenc", "Can’t be read"), unread.row("yEnc decoding", "rapidyenc"))
        // The header leaves out what isn't known, as it leaves out the memory.
        assertEquals(listOf("Kind: TV"), reading.headerLines)
        assertEquals(emptyList(), reading.copy(television = null).headerLines)
    }

    @Test
    fun `the header's lines, the existing ones unchanged and the four new ones after the Memory line`() {
        assertEquals(
            listOf(
                "NZStreamer 0.1.0 (7)",
                "Android 11 (API 30) on NVIDIA SHIELD Android TV",
                "Memory: 2.9 GB RAM, heap 512 MB (class 192 MB), 64-bit process on arm64-v8a, armeabi-v7a, armeabi",
                "Kind: TV",
                "Budget: player buffer 85 MB, streams 51 MB ahead, images the image loader's default",
                "Display modes: 23.976, 24, 25, 29.97, 30, 50, 59.94, 60 Hz at 3840x2160; match content frame rate: not told below Android 12",
                "Native: rapidyenc 1.1.1, decode NEON, CRC generic code",
                "Display: 3840x2160 output (the TV's display-size property), largest mode 3840x2160: 2160p; HDR: Dolby Vision, HDR10",
                "Video: H.264 2160p (hardware 2160p), HEVC 2160p (hardware 2160p), HEVC 10-bit 2160p (hardware 2160p), VP9 2160p (hardware 2160p), " +
                    "VP9 10-bit 1080p (no hardware), XviD 576p (hardware 1080p), VC-1 2160p (hardware 1080p), MPEG-2 1080p (hardware 2160p); " +
                    "Dolby Vision dvav.09.09, dvhe.04.09, dvhe.05.09, dvhe.08.09; no decoder for AV1",
                "Audio: AAC, FLAC, Opus, MP3, PCM decoded; DD, DD+ passed through over HDMI",
            ),
            shield.header("0.1.0", 7, debugBuild = false).lines(),
        )
        assertEquals(
            listOf(
                "Kind: phone or tablet",
                "Budget: player buffer 85 MB, streams 51 MB ahead, images the image loader's default",
                "Display modes: 60, 120 Hz at 1440x3120; match content frame rate: only when seamless",
                "Native: rapidyenc 1.1.1, decode NEON, CRC ARM CRC32",
            ),
            phone.headerLines.take(4),
        )
    }

    /** The header's numbers are the same in every locale: a log read elsewhere says 23.976, never 23,976. */
    @Test
    fun `the header's numbers ignore the locale, the page's follow it`() {
        Locale.setDefault(Locale.GERMANY)
        assertTrue(shield.headerLines.contains("Display modes: 23.976, 24, 25, 29.97, 30, 50, 59.94, 60 Hz at 3840x2160; match content frame rate: not told below Android 12"))
        assertTrue(shield.header("0.1.0", 7, debugBuild = false).contains("Memory: 2.9 GB RAM"))
        assertEquals("23,976, 24, 25, 29,97, 30, 50, 59,94, 60 Hz", shield.row("Display", "Refresh rates").value)
        assertEquals("2,9 GB RAM", shield.row("Device", "Memory").value)
    }

    /**
     * The rule: what the page says is also in a shared log's header. Each row's words that state a fact are found
     * as words of their own in the header lines that say its card's facts ([CARRIED_BY]; a codec's row in that codec's
     * parts of the Video line, by its label too); the words that only join or explain them, and the page's own way of
     * saying a header's fact ([SAID_AS]), are set aside first.
     */
    @Test
    fun `every fact on the page is in a header line`() {
        val devices = mapOf(
            "the Shield" to shield, "a phone" to phone, "a low-RAM box" to lowRamBox,
            "a 32-bit Shield" to shield.copy(memory = shieldMemory.copy(process64Bit = false)),
            "a box whose library didn't load" to shield.copy(native = NativeFacts.Failed(JNA_FAILURE)),
            "a TV without HDR or Dolby Vision" to shield.copy(
                display = shield.display!!.copy(hdr = emptySet()), decoders = shieldDecoders.copy(dolbyVision = false, dolbyVisionProfiles = emptyList()),
            ),
            "a TV whose HDR can't be read" to shield.copy(display = shield.display!!.copy(hdr = null), modes = shield.modes!!.copy(matchContentFrameRate = MatchContentFrameRate.Always), sdk = 34, androidRelease = "14"),
        )
        devices.forEach { (name, device) ->
            val header = device.header("0.1.0", 7, debugBuild = false).lines()
            // Not passed for want of rows: every card has some.
            assertEquals(5, device.sections.count { it.rows.isNotEmpty() }, name)
            val missing = device.sections.flatMap { section ->
                section.rows.flatMap { row ->
                    val lines = carrying(header, section.title, row.label)
                    val said = listOfNotNull(row.label.takeIf { section.title == "Video" && it != "No decoder" }, row.value, row.note, row.warning)
                    said.flatMap(::factWords).filterNot { word -> lines.any { says(it, word) } }.map { "${section.title} › ${row.label}: \"$it\"" }
                }
            }
            assertEquals(emptyList(), missing, "$name: page facts not in their header lines:\n${header.joinToString("\n")}")
        }
    }

    /** The redaction pass runs over every line logged: none of these may lose a word to it (no name=value, no a:b@c). */
    @Test
    fun `the redaction pass leaves every header line whole`() {
        val lines = listOf(shield, phone, lowRamBox, shield.copy(native = NativeFacts.Failed(JNA_FAILURE)))
            .flatMap { it.header("0.1.0", 7, debugBuild = true).lines() }
        assertEquals(40, lines.size)
        lines.forEach { assertEquals(it, redactSecrets(it)) }
    }

    @Test
    fun `the settings hub's line names the model and the Android version`() {
        assertEquals("NVIDIA SHIELD Android TV · Android 11", thisDeviceLine("NVIDIA", "SHIELD Android TV", "11"))
        assertEquals("example QZ-7701 · Android 15", thisDeviceLine("example", "QZ-7701", "15"))
        assertEquals("unknown device · Android 8.0.0", thisDeviceLine("", "", "8.0.0"))
    }

    /** "The same facts go into the log each time the app starts": what the header has that the log doesn't get, once. */
    @Test
    fun `at start the log gets the header's lines the loader doesn't log, each once`() {
        val header = shield.header("0.1.0", 7, debugBuild = false)
        val loaderLines = header.lines().filter { it.startsWith("Display: ") || it.startsWith("Video: ") || it.startsWith("Audio: ") }
        assertEquals(
            listOf(
                "NZStreamer 0.1.0 (7)",
                "Android 11 (API 30) on NVIDIA SHIELD Android TV",
                "Memory: 2.9 GB RAM, heap 512 MB (class 192 MB), 64-bit process on arm64-v8a, armeabi-v7a, armeabi",
                "Kind: TV",
                "Budget: player buffer 85 MB, streams 51 MB ahead, images the image loader's default",
                "Display modes: 23.976, 24, 25, 29.97, 30, 50, 59.94, 60 Hz at 3840x2160; match content frame rate: not told below Android 12",
                "Native: rapidyenc 1.1.1, decode NEON, CRC generic code",
            ),
            headerLinesToLog(header, loaderLines),
        )
        assertEquals(listOf("A", "B"), headerLinesToLog("A\nB\nA\n\nC", listOf("C")))
    }

    private companion object {
        /** On a TV, under Match frame rate: where the app's own switch is (the mockup's words). */
        const val MATCH_NOTE = "the app can switch the TV itself: Quality and languages › Match the TV to the video’s frame rate"

        /** Under Over HDMI: the route as it is now, followed as a receiver is switched on or off. */
        const val HDMI_NOTE = "passed through to the TV or receiver, as it is now: switching a receiver on or off updates this"

        /** JNA's failure to load rapidyenc, as NativeFacts keeps it: its lines (one for each place JNA tried) joined into one. */
        const val JNA_FAILURE = "Unable to load library 'rapidyenc': dlopen failed: library \"librapidyenc.so\" not found; " +
            "Native library (android-arm/librapidyenc.so) not found in resource path (.)"

        /**
         * The header lines that say each card's facts, by how they start: the Device card's in the Android, Memory, Kind
         * and Budget lines, and so on.
         */
        val CARRIED_BY = mapOf(
            "Device" to listOf("Android ", "Memory: ", "Kind: ", "Budget: "),
            "Display" to listOf("Display: ", "Display modes: "),
            "Video" to listOf("Video: "),
            "Audio" to listOf("Audio: "),
            "yEnc decoding" to listOf("Native: "),
        )

        /** The [header]'s lines that say a row's facts: its card's ([CARRIED_BY]); for a codec's row, that codec's parts of the Video line. */
        fun carrying(header: List<String>, section: String, label: String): List<String> {
            val lines = header.filter { line -> CARRIED_BY.getValue(section).any(line::startsWith) }
            if (section != "Video" || VideoCodec.entries.none { it.label == label }) return lines
            // "Video: H.264 2160p (hardware 2160p), HEVC 2160p (…), HEVC 10-bit 2160p (…), …; Dolby Vision …; no decoder for …"
            return lines.flatMap { it.removePrefix("Video: ").split("; ").first().split(", ") }.filter { it.startsWith("$label ") }
        }

        /**
         * Whether [line] says [word] as a word of its own, never inside another: "60" isn't said by "2160p", nor "low" by
         * "below". A Dolby Vision profile's code ("dvhe.08") is said by its codecs string, the code and a level ("dvhe.08.09").
         */
        fun says(line: String, word: String): Boolean {
            val level = if (DOLBY_VISION_CODE.matches(word)) """(\.\d{2})?""" else ""
            return Regex("""(?<![\w.+-])${Regex.escape(word)}$level(?![\w.+-])""", RegexOption.IGNORE_CASE).containsMatchIn(line)
        }

        val DOLBY_VISION_CODE = Regex("""[a-z0-9]{4}\.\d{2}""")

        /**
         * How the page says a header's fact in its own words, and the words that only explain; by the header's words, or
         * none. Dolby Vision's profile numbers go: the row's note names each by its code, which the header has.
         */
        val SAID_AS = listOf(
            "not a low-RAM device" to "", "a low-RAM device" to "low RAM", "and below" to "", "up to" to "", "software" to "no hardware",
            "Can't be read" to "unknown", """Android \S+ doesn't tell apps this setting""" to "not told", "the screen's own rates" to "",
            "just after the TV wakes, Dolby Vision can be missing for a few seconds" to "",
            "passed through to the TV or receiver, as it is now: switching a receiver on or off updates this" to "passed through",
            // A pointer to the app's own switch, not a fact of the device.
            "the app can switch the TV itself: Quality and languages › Match the TV to the video's frame rate" to "",
            "slower: this processor's faster instructions weren't found" to "", "a decoder, no profiles listed" to "no profiles listed",
            "the device also runs" to "", """^profiles? [\d, ]+$""" to "",
        ).map { (page, header) -> Regex(page, RegexOption.IGNORE_CASE) to header }

        /** The words of [text] that state a fact, after [SAID_AS]: numbers, names, kinds; a size as the header writes it ("3840x2160"). */
        fun factWords(text: String): List<String> {
            val plain = SAID_AS.fold(text.replace('’', '\'')) { said, (page, header) -> page.replace(said, header) }
            return plain.replace(Regex("""(\d) × (\d)"""), "$1x$2").split(Regex("""[\s,·×()]+""")).map { it.trim(':', ';') }.filter { it.isNotEmpty() }
        }
    }
}
