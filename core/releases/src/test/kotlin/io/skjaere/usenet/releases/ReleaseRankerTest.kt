package io.skjaere.usenet.releases

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseRankerTest {

    private val ranker = ReleaseRanker()
    private val phone = StaticDeviceCapabilities(displayHeight = 1080)
    private val gb = 1_000_000_000L

    private fun release(name: String, size: Long = 2 * gb, passworded: Boolean = false, grabs: Int? = null, ageDays: Int? = null) =
        ReleaseFacts(id = name, name = name, sizeBytes = size, passworded = passworded, grabs = grabs, ageDays = ageDays)

    private fun rank(vararg releases: ReleaseFacts, context: RankingContext = RankingContext(phone)) =
        ranker.rank(releases.toList(), context)

    private fun order(vararg releases: ReleaseFacts, context: RankingContext = RankingContext(phone)) =
        rank(*releases, context = context).map { it.facts.name }

    private fun estimate(release: ReleaseFacts, runtimeMinutes: Int?) =
        rank(release, context = RankingContext(phone, runtimeMinutes = runtimeMinutes)).single().estimatedBitrate

    /** The indexer's password flag is often wrong, and a password may come with the release: it is tried, and says so. */
    @Test
    fun `a passworded release is tried where its quality puts it, with a warning and the score it would have without`() {
        val passworded = release("Harbor.Lights.S01E02.1080p.WEB-DL.H.264-A", passworded = true, grabs = 900)
        val ranked = rank(release("Harbor.Lights.S01E02.720p.HDTV.x264-B"), passworded)
        assertEquals(listOf(passworded.name, "Harbor.Lights.S01E02.720p.HDTV.x264-B"), ranked.map { it.facts.name })
        assertTrue(ranked.first().playable)
        assertEquals(Severity.Warning, ranked.first().reasons.single { it == ReleaseReason.Passworded }.severity)
        assertEquals(rank(passworded.copy(passworded = false)).single().score, ranked.first().score)
        assertEquals(listOf(true, true), ranked.map { it.playable })
    }

    @Test
    fun `hevc 10-bit is blocked without a main10 decoder`() {
        val noMain10 = phone.copy(videoTenBit = emptySet())
        val ranked = rank(
            release("Harbor.Lights.S01E02.1080p.WEB.x265.10bit-A"),
            release("Harbor.Lights.S01E02.720p.WEB.x264-B"),
            context = RankingContext(noMain10),
        )
        assertEquals(listOf(true, false), ranked.map { it.playable })
        assertEquals(ReleaseReason.VideoUnsupported("HEVC 10-bit"), ranked.last().reasons.first())
    }

    @Test
    fun `a resolution above the largest size the decoder plays is blocked`() {
        // A 4K TV with hardware HEVC but only a software AV1 decoder that tops out at 1080p.
        val tv = phone.copy(
            displayHeight = 2160,
            video = phone.video + VideoCodec.Av1,
            videoMaxHeight = mapOf(VideoCodec.Av1 to 1080, VideoCodec.Hevc to 2160),
        )
        val ranked = rank(
            release("Harbor.Lights.S01E02.2160p.WEB-DL.AV1-A"),
            release("Harbor.Lights.S01E02.1080p.WEB-DL.AV1-B"),
            release("Harbor.Lights.S01E02.2160p.WEB-DL.x265-C"),
            context = RankingContext(tv),
        )
        assertEquals(
            listOf("Harbor.Lights.S01E02.2160p.WEB-DL.x265-C", "Harbor.Lights.S01E02.1080p.WEB-DL.AV1-B", "Harbor.Lights.S01E02.2160p.WEB-DL.AV1-A"),
            ranked.map { it.facts.name },
        )
        assertEquals(listOf(true, true, false), ranked.map { it.playable })
        assertEquals(ReleaseReason.VideoUnsupported("2160p AV1"), ranked.last().reasons.first())
        assertEquals("2160p AV1: no decoder", ranked.last().reasons.first().text)
    }

    @Test
    fun `the size limit follows the bit depth`() {
        val tv = phone.copy(
            displayHeight = 2160,
            videoMaxHeight = mapOf(VideoCodec.Hevc to 2160),
            videoTenBitMaxHeight = mapOf(VideoCodec.Hevc to 1080),
        )
        val ranked = rank(
            release("Harbor.Lights.S01E02.2160p.WEB-DL.x265.10bit-A"),
            release("Harbor.Lights.S01E02.2160p.WEB-DL.x265-B"),
            release("Harbor.Lights.S01E02.1080p.WEB-DL.x265.10bit-C"),
            context = RankingContext(tv),
        )
        assertEquals(
            listOf("Harbor.Lights.S01E02.2160p.WEB-DL.x265-B" to true, "Harbor.Lights.S01E02.1080p.WEB-DL.x265.10bit-C" to true, "Harbor.Lights.S01E02.2160p.WEB-DL.x265.10bit-A" to false),
            ranked.map { it.facts.name to it.playable },
        )
        assertEquals(ReleaseReason.VideoUnsupported("2160p HEVC 10-bit"), ranked.last().reasons.first())
    }

    @Test
    fun `a release without a resolution or a decoder limit is not held to one`() {
        val tv = phone.copy(video = phone.video + VideoCodec.Av1, videoMaxHeight = mapOf(VideoCodec.Av1 to 1080))
        assertTrue(rank(release("Harbor.Lights.S01E02.WEB-DL.AV1-A"), context = RankingContext(tv)).single().playable)
        assertTrue(rank(release("Harbor.Lights.S01E02.2160p.WEB-DL.x264-B"), context = RankingContext(tv)).single().playable)
    }

    /** A 4K screen whose HEVC decoders play 2160p, in hardware only up to 1080p: the rest is a software decoder's. */
    private val softwareUhd = phone.copy(displayHeight = 2160, videoMaxHeight = mapOf(VideoCodec.Hevc to 2160), videoHardwareMaxHeight = mapOf(VideoCodec.Hevc to 1080))

    @Test
    fun `on a TV a release above 1080p plays only where a hardware decoder plays its size`() {
        val uhd = release("Harbor.Lights.S01E02.2160p.WEB-DL.x265-A")
        val fullHd = release("Harbor.Lights.S01E02.1080p.WEB-DL.x265-B")
        val ranked = rank(uhd, fullHd, context = RankingContext(softwareUhd, tv = true))
        assertEquals(listOf(fullHd.name to true, uhd.name to false), ranked.map { it.facts.name to it.playable })
        val reason = ranked.last().reasons.first()
        assertEquals(ReleaseReason.NoHardwareDecoder("2160p HEVC"), reason)
        assertEquals(Severity.Blocking to "2160p HEVC: no hardware decoder", reason.severity to reason.text)
        // A box whose hardware plays 2160p (a Shield): it plays, first.
        val hardwareUhd = softwareUhd.copy(videoHardwareMaxHeight = mapOf(VideoCodec.Hevc to 2160))
        assertEquals(listOf(uhd.name to true, fullHd.name to true), rank(uhd, fullHd, context = RankingContext(hardwareUhd, tv = true)).map { it.facts.name to it.playable })
    }

    @Test
    fun `at 1080p and below a software decoder still plays, on a TV and a phone`() {
        val softwareOnly = softwareUhd.copy(videoHardwareMaxHeight = mapOf(VideoCodec.Hevc to 0, VideoCodec.H264 to 0))
        listOf(true, false).forEach { tv ->
            val ranked = rank(release("Harbor.Lights.S01E02.1080p.WEB-DL.x265-A"), release("Harbor.Lights.S01E02.720p.WEB-DL.x264-B"), context = RankingContext(softwareOnly, tv = tv))
            assertEquals(listOf(true, true), ranked.map { it.playable }, "tv=$tv")
            assertTrue(ranked.none { r -> r.reasons.any { it is ReleaseReason.NoHardwareDecoder } }, "tv=$tv")
        }
    }

    @Test
    fun `the hardware limit follows the bit depth`() {
        val tv = phone.copy(displayHeight = 2160, videoHardwareMaxHeight = mapOf(VideoCodec.Hevc to 2160), videoTenBitHardwareMaxHeight = mapOf(VideoCodec.Hevc to 1080))
        val ranked = rank(
            release("Harbor.Lights.S01E02.2160p.WEB-DL.x265.10bit-A"),
            release("Harbor.Lights.S01E02.2160p.WEB-DL.x265-B"),
            context = RankingContext(tv, tv = true),
        )
        assertEquals(listOf("Harbor.Lights.S01E02.2160p.WEB-DL.x265-B" to true, "Harbor.Lights.S01E02.2160p.WEB-DL.x265.10bit-A" to false), ranked.map { it.facts.name to it.playable })
        assertEquals(ReleaseReason.NoHardwareDecoder("2160p HEVC 10-bit"), ranked.last().reasons.first())
    }

    @Test
    fun `a release has one video reason at most, and an unknown hardware limit or resolution holds it to none`() {
        fun videoReasons(name: String, device: StaticDeviceCapabilities) = rank(release(name), context = RankingContext(device, tv = true)).single()
            .reasons.filter { it is ReleaseReason.VideoUnsupported || it is ReleaseReason.NoHardwareDecoder }
        // Too big for any decoder, or no decoder at all: only that is said.
        val av1 = phone.copy(displayHeight = 2160, video = phone.video + VideoCodec.Av1, videoMaxHeight = mapOf(VideoCodec.Av1 to 1080), videoHardwareMaxHeight = mapOf(VideoCodec.Av1 to 0))
        assertEquals(listOf<ReleaseReason>(ReleaseReason.VideoUnsupported("2160p AV1")), videoReasons("Harbor.Lights.S01E02.2160p.WEB-DL.AV1-A", av1))
        assertEquals(listOf<ReleaseReason>(ReleaseReason.VideoUnsupported("HEVC 10-bit")), videoReasons("Harbor.Lights.S01E02.2160p.WEB-DL.x265.10bit-A", softwareUhd.copy(videoTenBit = emptySet())))
        // A decoder that hides its limits, and a name that doesn't say its resolution.
        assertEquals(emptyList<ReleaseReason>(), videoReasons("Harbor.Lights.S01E02.2160p.WEB-DL.x265-A", phone.copy(displayHeight = 2160)))
        assertEquals(emptyList<ReleaseReason>(), videoReasons("Harbor.Lights.S01E02.WEB-DL.x265-A", softwareUhd))
    }

    /** On phones and tablets "tried last", as their hardware marks are less sure and a hidden release can't be played at all. */
    @Test
    fun `on a phone a release with no hardware decoder for its size is tried after every one that fits`() {
        val phoneSoftwareUhd = softwareUhd.copy(displayHeight = 1080)
        val uhd = release("Harbor.Lights.S01E02.2160p.WEB-DL.x265-A", grabs = 900)
        val low = release("Harbor.Lights.S01E02.480p.HDTV.x264-B")
        // The lowest a release that fits scores: a camera copy, and one in a language not asked for.
        val cam = release("Harbor.Lights.S01E02.480p.HDCAM.x264-C")
        val germanCam = release("Harbor.Lights.S01E02.German.480p.HDCAM.x264-D")
        // Not held back by hardware: the 2160p release first.
        assertEquals(listOf(uhd.name, low.name, cam.name, germanCam.name), order(uhd, low, cam, germanCam, context = RankingContext(phone)))
        val ranked = rank(uhd, low, cam, germanCam, context = RankingContext(phoneSoftwareUhd))
        assertEquals(listOf(low.name, cam.name, germanCam.name, uhd.name).map { it to true }, ranked.map { it.facts.name to it.playable })
        val reason = ranked.last().reasons.filterIsInstance<ReleaseReason.NoHardwareDecoder>().single()
        assertEquals(Severity.Warning to "2160p HEVC: no hardware decoder", reason.severity to reason.text)
        assertTrue(ranked.first().reasons.none { it is ReleaseReason.NoHardwareDecoder })
        // A title with only that release still plays it.
        assertTrue(rank(uhd, context = RankingContext(phoneSoftwareUhd)).single().playable)
    }

    /** A 4K TV whose screen shows no HDR. */
    private val sdrTv = RankingContext(phone.copy(displayHeight = 2160, hdrTypes = emptySet()), tv = true)

    /** A step of resolution and a half (45): SDR at the same resolution or one lower goes first, two lower doesn't. */
    @Test
    fun `on a TV without HDR an HDR release ranks under SDR at its resolution and one lower, over two lower, and still plays`() {
        val hdr = release("Harbor.Lights.S01E02.2160p.WEB-DL.H265.HDR-A")
        val sdr2160 = release("Harbor.Lights.S01E02.2160p.WEB-DL.H265-B")
        val sdr1080 = release("Harbor.Lights.S01E02.1080p.WEB-DL.H265-C")
        val sdr720 = release("Harbor.Lights.S01E02.720p.WEB-DL.H265-D")
        assertEquals(listOf(sdr2160.name, sdr1080.name, hdr.name, sdr720.name), order(hdr, sdr2160, sdr1080, sdr720, context = sdrTv))
        // Over two steps lower by its score, not a tie the name breaks (the HDR names sort first).
        assertTrue(score(hdr, sdrTv) > score(sdr720, sdrTv), "${score(hdr, sdrTv)} against ${score(sdr720, sdrTv)}")
        // An HDR-only title still plays, saying why it may look washed out.
        val only = rank(hdr, context = sdrTv).single()
        assertTrue(only.playable)
        val reason = only.reasons.filterIsInstance<ReleaseReason.HdrOnSdrScreen>().single()
        assertEquals(Severity.Warning to "HDR on a screen without HDR", reason.severity to reason.text)
        // On a 1080p screen too: under SDR 1080p and 720p, over 576p.
        val sdr576 = release("Harbor.Lights.S01E02.576p.WEB-DL.H265-E")
        val hdr1080 = release("Harbor.Lights.S01E02.1080p.WEB-DL.H265.HDR-F")
        val fullHdTv = sdrTv.copy(device = phone.copy(hdrTypes = emptySet()))
        assertEquals(listOf(sdr1080.name, sdr720.name, hdr1080.name, sdr576.name), order(hdr1080, sdr1080, sdr720, sdr576, context = fullHdTv))
        assertTrue(score(hdr1080, fullHdTv) > score(sdr576, fullHdTv), "${score(hdr1080, fullHdTv)} against ${score(sdr576, fullHdTv)}")
    }

    private fun score(release: ReleaseFacts, context: RankingContext) = rank(release, context = context).single().score

    @Test
    fun `a screen with HDR, and a phone's, hold no HDR release back`() {
        val hdr = release("Harbor.Lights.S01E02.2160p.WEB-DL.H265.HDR-A")
        val sdr = release("Harbor.Lights.S01E02.2160p.WEB-DL.H265-B")
        fun scores(context: RankingContext) = rank(hdr, sdr, context = context).associate { it.facts.name to it.score }
        val hdrTv = sdrTv.copy(device = phone.copy(displayHeight = 2160, hdrTypes = setOf(Hdr.Hdr10)))
        // Phones and tablets map HDR to their own screens.
        val sdrPhone = sdrTv.copy(tv = false)
        listOf(hdrTv, sdrPhone).forEach { context ->
            assertEquals(scores(context).getValue(sdr.name), scores(context).getValue(hdr.name), "$context")
            assertTrue(rank(hdr, context = context).single().reasons.none { it is ReleaseReason.HdrOnSdrScreen })
        }
    }

    @Test
    fun `on an HDR10 screen Dolby Vision with an HDR10 base and HDR10+ fit, Dolby Vision alone doesn't, HLG fits any screen`() {
        val hdr10Tv = RankingContext(phone.copy(displayHeight = 2160, hdrTypes = setOf(Hdr.Hdr10), dolbyVision = true), tv = true)
        fun chip(name: String, context: RankingContext = hdr10Tv) = rank(release(name), context = context).single().reasons.filterIsInstance<ReleaseReason.HdrOnSdrScreen>().singleOrNull()
        assertNull(chip("Harbor.Lights.S01E02.2160p.WEB.H265.DV.HDR10-A"))
        assertNull(chip("Harbor.Lights.S01E02.2160p.WEB.H265.HDR10Plus-B"))
        assertNull(chip("Harbor.Lights.S01E02.2160p.WEB.H265.HDR-C"))
        val dolbyVisionOnly = chip("Harbor.Lights.S01E02.2160p.WEB.H265.DV-D")
        assertEquals(Severity.Warning to "Dolby Vision on a screen without it", dolbyVisionOnly?.severity to dolbyVisionOnly?.text)
        assertNull(chip("Harbor.Lights.S01E02.2160p.WEB.H265.DV-D", hdr10Tv.copy(device = (hdr10Tv.device as StaticDeviceCapabilities).copy(hdrTypes = setOf(Hdr.DolbyVision, Hdr.Hdr10)))))
        // HLG is made to look right on SDR.
        assertNull(chip("Harbor.Lights.S01E02.2160p.WEB.H265.HLG-E", sdrTv))
    }

    @Test
    fun `the preferred language still beats the HDR penalty`() {
        val englishThenGerman = sdrTv.copy(prefs = RankingPrefs(audioLanguages = listOf("en", "de")))
        val english = release("Harbor.Lights.S01E02.2160p.WEB-DL.H265.HDR-A")
        val german = release("Harbor.Lights.S01E02.German.2160p.WEB-DL.H265-B")
        assertEquals(english.name, order(english, german, context = englishThenGerman).first())
        // By its score, not a tie the name breaks.
        assertTrue(score(english, englishThenGerman) > score(german, englishThenGerman), "${score(english, englishThenGerman)} against ${score(german, englishThenGerman)}")
    }

    /**
     * Within a resolution and language the bitrate's bonus doesn't lift an HDR release over the SDR one its penalty put
     * it under, however close Proper and its source bring the two: at 40 Mbps over 45 minutes, 12 GB is ~36 Mbps, 2 GB ~6.
     */
    @Test
    fun `a higher bitrate doesn't lift an HDR release over SDR on a screen without HDR`() {
        val context = sdrTv.copy(prefs = RankingPrefs(maxBitrate = 40_000_000), runtimeMinutes = 45)
        val hdr = release("Harbor.Lights.S01E02.PROPER.2160p.WEB-DL.H265.HDR-A", size = 12 * gb)
        val sdr = release("Harbor.Lights.S01E02.2160p.HDTV.H265-B", grabs = 10)
        assertEquals(listOf(sdr.name, hdr.name), order(hdr, sdr, context = context))
    }

    @Test
    fun `dolby vision without hdr10 fallback is blocked, with fallback it plays`() {
        val ranked = rank(
            release("Harbor.Lights.S01E02.2160p.WEB.H265.DV-A"),
            release("Harbor.Lights.S01E02.2160p.WEB.H265.DV.HDR10-B"),
            context = RankingContext(phone.copy(displayHeight = 2160)),
        )
        assertEquals(listOf("Harbor.Lights.S01E02.2160p.WEB.H265.DV.HDR10-B", "Harbor.Lights.S01E02.2160p.WEB.H265.DV-A"), ranked.map { it.facts.name })
        assertTrue(ReleaseReason.DolbyVisionOnly in ranked.last().reasons)
        assertTrue(rank(release("Harbor.Lights.S01E02.2160p.WEB.H265.DV-A"), context = RankingContext(phone.copy(dolbyVision = true))).single().playable)
    }

    @Test
    fun `disc images and audio the device cannot decode are blocked`() {
        val ranked = rank(
            release("Copper.Crown.2019.COMPLETE.BLURAY-A", size = 40 * gb),
            release("Copper.Crown.2019.1080p.BluRay.TrueHD.7.1.x264-B"),
            release("Copper.Crown.2019.1080p.BluRay.DTS.AC3.x264-C"),
        )
        // Sorted by name: C (DTS + AC3), B (TrueHD only), A (disc image).
        assertEquals(listOf(true, false, false), ranked.sortedBy { it.facts.name }.map { it.playable })
        assertEquals("Copper.Crown.2019.1080p.BluRay.DTS.AC3.x264-C", ranked.first().facts.name)
    }

    @Test
    fun `auto quality prefers the screen resolution over higher and lower`() {
        assertEquals(
            listOf("Harbor.Lights.S01E02.1080p.WEB-DL.x264-B", "Harbor.Lights.S01E02.720p.WEB-DL.x264-C", "Harbor.Lights.S01E02.2160p.WEB-DL.x265-A"),
            order(
                release("Harbor.Lights.S01E02.2160p.WEB-DL.x265-A"),
                release("Harbor.Lights.S01E02.1080p.WEB-DL.x264-B"),
                release("Harbor.Lights.S01E02.720p.WEB-DL.x264-C"),
            ),
        )
    }

    @Test
    fun `explicit quality preference wins over the screen`() {
        val context = RankingContext(phone, RankingPrefs(quality = QualityPreference.P720))
        assertEquals(
            "Harbor.Lights.S01E02.720p.WEB-DL.x264-C",
            order(release("Harbor.Lights.S01E02.1080p.WEB-DL.x264-B"), release("Harbor.Lights.S01E02.720p.WEB-DL.x264-C"), context = context).first(),
        )
    }

    @Test
    fun `first preferred language beats one step of quality`() {
        val german = RankingContext(phone, RankingPrefs(audioLanguages = listOf("de", "en")))
        val ranked = rank(
            release("Harbor.Lights.S01E02.1080p.WEB.x264-A"),
            release("Harbor.Lights.S01E02.German.DL.720p.WEB.x264-B"),
            context = german,
        )
        assertEquals("Harbor.Lights.S01E02.German.DL.720p.WEB.x264-B", ranked.first().facts.name)
        assertTrue(ReleaseReason.Language("German DL") in ranked.first().reasons)

        val english = RankingContext(phone, RankingPrefs(audioLanguages = listOf("en")))
        assertEquals("Harbor.Lights.S01E02.1080p.WEB.x264-A", order(
            release("Harbor.Lights.S01E02.1080p.WEB.x264-A"),
            release("Harbor.Lights.S01E02.German.720p.WEB.x264-B"),
            context = english,
        ).first())
    }

    @Test
    fun `a release subbed in the preferred language does not beat its dub`() {
        val german = RankingContext(phone, RankingPrefs(audioLanguages = listOf("de", "en")))
        val ranked = rank(
            release("Moonwell.Harbor.2019.German.Subbed.1080p.BluRay.x264-A", grabs = 900),
            release("Moonwell.Harbor.2019.German.DL.1080p.BluRay.x264-B", grabs = 3),
            context = german,
        )
        assertEquals("Moonwell.Harbor.2019.German.DL.1080p.BluRay.x264-B", ranked.first().facts.name)
        assertTrue(ranked.last().reasons.none { it is ReleaseReason.Language })

        assertEquals("Harbor.Lights.S01E02.1080p.WEB.x264-B", order(
            release("Harbor.Lights.S01E02.German.Subbed.720p.WEB.x264-A"),
            release("Harbor.Lights.S01E02.1080p.WEB.x264-B"),
            context = german,
        ).first())
    }

    @Test
    fun `a subbed release keeps the original-language match`() {
        val english = RankingContext(phone, RankingPrefs(audioLanguages = listOf("en")))
        val ranked = rank(
            release("Harbor.Lights.S01E02.German.Subbed.1080p.WEB.x264-A"),
            release("Harbor.Lights.S01E02.1080p.WEB.x264-B"),
            context = english,
        )
        assertEquals(ranked.last().score, ranked.first().score)
    }

    @Test
    fun `a Korean film's untagged and subbed releases are Korean audio, not English`() {
        val english = RankingContext(phone, RankingPrefs(audioLanguages = listOf("en")), originalLanguage = "ko")
        val ranked = rank(
            release("Moonwell.Village.2021.KOREAN.SUBBED.1080p.BluRay.x264-A"),
            release("Moonwell.Village.2021.KOREAN.1080p.BluRay.x264-B"),
            release("Moonwell.Village.2021.1080p.BluRay.x264-C"),
            release("Moonwell.Village.2021.ENGLISH.720p.BluRay.x264-D"),
            context = english,
        )
        // The English dub is the only one in the preferred language: it beats a step of quality.
        assertEquals("Moonwell.Village.2021.ENGLISH.720p.BluRay.x264-D", ranked.first().facts.name)
        val spokenKorean = ranked.drop(1)
        assertEquals(1, spokenKorean.map { it.score }.distinct().size)
        assertTrue(spokenKorean.none { r -> r.reasons.any { it is ReleaseReason.Language } })
    }

    @Test
    fun `for a user who prefers Korean, a Korean film's untagged and subbed releases score as its Korean one`() {
        val korean = RankingContext(phone, RankingPrefs(audioLanguages = listOf("ko", "en")), originalLanguage = "ko")
        val ranked = rank(
            release("Moonwell.Village.2021.KOREAN.SUBBED.1080p.BluRay.x264-A"),
            release("Moonwell.Village.2021.KOREAN.1080p.BluRay.x264-B"),
            release("Moonwell.Village.2021.1080p.BluRay.x264-C"),
            release("Moonwell.Village.2021.ENGLISH.1080p.BluRay.x264-D"),
            context = korean,
        )
        assertEquals("Moonwell.Village.2021.ENGLISH.1080p.BluRay.x264-D", ranked.last().facts.name)
        assertEquals(1, ranked.dropLast(1).map { it.score }.distinct().size)
    }

    @Test
    fun `an English film's untagged release is English`() {
        val english = RankingContext(phone, RankingPrefs(audioLanguages = listOf("en")), originalLanguage = "en")
        val ranked = rank(
            release("Tin.Harbour.2018.1080p.WEB.x264-A"),
            release("Tin.Harbour.2018.ENGLISH.1080p.WEB.x264-B"),
            release("Tin.Harbour.2018.FRENCH.1080p.WEB.x264-C"),
            context = english,
        )
        assertEquals("Tin.Harbour.2018.FRENCH.1080p.WEB.x264-C", ranked.last().facts.name)
        assertEquals(ranked[0].score, ranked[1].score)
    }

    @Test
    fun `an English film subbed in German is English audio for a German user`() {
        val german = RankingContext(phone, RankingPrefs(audioLanguages = listOf("de", "en")), originalLanguage = "en")
        val ranked = rank(
            release("Tin.Harbour.2018.German.Subbed.1080p.WEB.x264-A"),
            release("Tin.Harbour.2018.ENGLISH.1080p.WEB.x264-B"),
            release("Tin.Harbour.2018.German.DL.1080p.WEB.x264-C"),
            release("Tin.Harbour.2018.FRENCH.1080p.WEB.x264-D"),
            context = german,
        ).associateBy { it.facts.name.substringAfterLast('-') }
        assertEquals(ranked.getValue("B").score, ranked.getValue("A").score)
        assertTrue(ranked.getValue("C").score > ranked.getValue("A").score)
        assertTrue(ranked.getValue("A").score > ranked.getValue("D").score)
    }

    @Test
    fun `multi-language and dual-language releases score as before, whatever the original language`() {
        val releases = arrayOf(release("Moonwell.Village.2021.MULTi.1080p.BluRay.x264-A"), release("Moonwell.Village.2021.German.DL.1080p.BluRay.x264-B"))
        listOf(listOf("en"), listOf("de", "en"), listOf("ko")).forEach { languages ->
            val prefs = RankingPrefs(audioLanguages = languages)
            fun scored(original: String?) = rank(*releases, context = RankingContext(phone, prefs, originalLanguage = original)).map { Triple(it.facts.name, it.score, it.reasons) }
            assertEquals(scored(null), scored("ko"))
        }
    }

    @Test
    fun `a bare DUBBED release of a foreign film is its English dub`() {
        val releases = arrayOf(
            release("Moonwell.Village.2021.DUBBED.1080p.BluRay.x264-A"),
            release("Moonwell.Village.2021.KOREAN.1080p.BluRay.x264-B"),
        )
        val english = rank(*releases, context = RankingContext(phone, RankingPrefs(audioLanguages = listOf("en")), originalLanguage = "ko"))
        assertEquals("Moonwell.Village.2021.DUBBED.1080p.BluRay.x264-A", english.first().facts.name)
        assertTrue(english.first().score > english.last().score)
        assertTrue(ReleaseReason.Language("English") in english.first().reasons)
        // A viewer who prefers Korean gets the Korean audio, not the dub.
        val korean = rank(*releases, context = RankingContext(phone, RankingPrefs(audioLanguages = listOf("ko", "en")), originalLanguage = "ko"))
        assertEquals("Moonwell.Village.2021.KOREAN.1080p.BluRay.x264-B", korean.first().facts.name)
        assertTrue(korean.first().score > korean.last().score)
    }

    @Test
    fun `Android's old language codes meet the ones TMDB and release names use`() {
        // A Norwegian device whose languages were never chosen: Android says "nb", TMDB calls the film's language "no".
        val norwegian = RankingContext(phone, RankingPrefs(audioLanguages = listOf("nb", "en")), originalLanguage = "no")
        val ranked = rank(
            release("Tin.Harbour.2018.1080p.WEB.x264-A"),
            release("Tin.Harbour.2018.ENGLISH.1080p.WEB.x264-B"),
            release("Tin.Harbour.2018.NORWEGIAN.1080p.WEB.x264-C"),
            context = norwegian,
        ).associateBy { it.facts.name.substringAfterLast('-') }
        assertEquals(ranked.getValue("C").score, ranked.getValue("A").score)
        assertTrue(ranked.getValue("A").score > ranked.getValue("B").score)
        assertTrue(ReleaseReason.Language("Norwegian") in ranked.getValue("C").reasons)
        // Hebrew ("iw", TMDB's "he") and Indonesian ("in", TMDB's "id") devices alike.
        listOf("iw" to "he", "in" to "id").forEach { (device, tmdb) ->
            val scores = rank(
                release("Tin.Harbour.2018.1080p.WEB.x264-A"),
                release("Tin.Harbour.2018.ENGLISH.1080p.WEB.x264-B"),
                context = RankingContext(phone, RankingPrefs(audioLanguages = listOf(device, "en")), originalLanguage = tmdb),
            ).associate { it.facts.name.substringAfterLast('-') to it.score }
            assertTrue(scores.getValue("A") > scores.getValue("B"), "$device: $scores")
        }
    }

    @Test
    fun `without the original language an untagged release is English, as before`() {
        val ranked = rank(
            release("Moonwell.Village.2021.KOREAN.1080p.BluRay.x264-A"),
            release("Moonwell.Village.2021.1080p.BluRay.x264-B"),
            context = RankingContext(phone, RankingPrefs(audioLanguages = listOf("en"))),
        )
        assertEquals("Moonwell.Village.2021.1080p.BluRay.x264-B", ranked.first().facts.name)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun `bitrate above measured throughput is penalised with a reason`() {
        val slow = RankingContext(phone, throughputBitsPerSecond = 20_000_000, runtimeMinutes = 45)
        // 12 GB over 45 min is ~36 Mbps; 2 GB is ~6 Mbps.
        val ranked = rank(
            release("Harbor.Lights.S01E02.1080p.BluRay.REMUX.AVC-A", size = 12 * gb),
            release("Harbor.Lights.S01E02.1080p.WEB-DL.x264-B", size = 2 * gb),
            context = slow,
        )
        assertEquals("Harbor.Lights.S01E02.1080p.WEB-DL.x264-B", ranked.first().facts.name)
        assertEquals(ReleaseReason.MayBuffer(36), ranked.last().reasons.first { it is ReleaseReason.MayBuffer })
    }

    @Test
    fun `a zero runtime estimates with the default runtime instead of dividing by zero`() {
        listOf(release("Harbor.Lights.S01E02.1080p.WEB-DL.x264-A"), release("Copper.Crown.2019.1080p.BluRay.x264-B")).forEach {
            assertNotNull(estimate(it, runtimeMinutes = null))
            assertEquals(estimate(it, runtimeMinutes = null), estimate(it, runtimeMinutes = 0))
        }
    }

    @Test
    fun `a negative runtime estimates with the default runtime`() {
        listOf(release("Harbor.Lights.S01E02.1080p.WEB-DL.x264-A"), release("Copper.Crown.2019.1080p.BluRay.x264-B")).forEach {
            assertEquals(estimate(it, runtimeMinutes = null), estimate(it, runtimeMinutes = -45))
        }
    }

    @Test
    fun `a runtime that overflows the minute count gets no estimate and no penalty`() {
        // Two episodes of Int.MAX_VALUE minutes wrap around to a negative minute count.
        val context = RankingContext(phone, throughputBitsPerSecond = 1, runtimeMinutes = Int.MAX_VALUE)
        val ranked = rank(release("Harbor.Lights.S01E01E02.1080p.WEB-DL.x264-A"), context = context).single()
        assertNull(ranked.estimatedBitrate)
        assertTrue(ranked.reasons.none { it is ReleaseReason.MayBuffer })
    }

    @Test
    fun `a season pack's bitrate is its size per episode of the season`() {
        // 30 GB over the season's 10 episodes of 45 minutes: 3 GB an episode, ~8.9 Mbps.
        val context = RankingContext(phone, runtimeMinutes = 45, seasonEpisodes = 10)
        val pack = rank(release("Harbor.Lights.S01.1080p.WEB-DL.x264-A", size = 30 * gb), context = context).single()
        assertEquals(30 * gb * 8 / (10 * 45 * 60L), pack.estimatedBitrate)
        // Without a runtime, an episode's default one.
        val untimed = rank(release("Harbor.Lights.S01.1080p.WEB-DL.x264-A", size = 30 * gb), context = RankingContext(phone, seasonEpisodes = 10)).single()
        assertEquals(30 * gb * 8 / (10 * 45 * 60L), untimed.estimatedBitrate)
    }

    @Test
    fun `a season pack without the season's episode count, or of several seasons, has no bitrate estimate`() {
        assertNull(rank(release("Harbor.Lights.S01.1080p.WEB-DL.x264-A", size = 30 * gb), context = RankingContext(phone, runtimeMinutes = 45)).single().estimatedBitrate)
        val seasons = RankingContext(phone, runtimeMinutes = 45, seasonEpisodes = 10)
        assertNull(rank(release("Harbor.Lights.S01-S03.1080p.WEB-DL.x264-A", size = 90 * gb), context = seasons).single().estimatedBitrate)
        assertNull(rank(release("Harbor.Lights.S01.1080p.WEB-DL.x264-A", size = 30 * gb), context = seasons.copy(seasonEpisodes = 0)).single().estimatedBitrate)
    }

    @Test
    fun `a single episode goes before a season pack of equal standing, but a better pack beats a worse single`() {
        val single = release("Harbor.Lights.S01E02.1080p.WEB-DL.x264-A")
        val pack = release("Harbor.Lights.S01.1080p.WEB-DL.x264-B", size = 30 * gb)
        val ranked = rank(pack, single)
        assertEquals(listOf(single.name, pack.name), ranked.map { it.facts.name })
        // A small cost for the bigger download, well under a step of quality.
        val cost = ranked[0].score - ranked[1].score
        assertTrue(cost > 0 && cost < 30, "$cost")
        assertEquals(listOf(pack.name, "Harbor.Lights.S01E02.720p.WEB-DL.x264-C"), order(release("Harbor.Lights.S01E02.720p.WEB-DL.x264-C"), pack))
    }

    /** At most 40 Mbps, an episode of 45 minutes: 12 GB is ~36 Mbps, 30 GB ~89 Mbps, 2 GB ~6 Mbps. */
    private val upTo40 = RankingContext(phone, RankingPrefs(maxBitrate = 40_000_000), runtimeMinutes = 45)

    @Test
    fun `with a maximum bitrate, a release above it ranks after every one that fits, says so, and stays playable`() {
        val remux = release("Harbor.Lights.S01E02.2160p.BluRay.REMUX.HEVC-A", size = 30 * gb, grabs = 900)
        val low = release("Harbor.Lights.S01E02.480p.HDTV.x264-B", size = gb / 2)
        val cam = release("Harbor.Lights.S01E02.480p.HDCAM.x264-C", size = gb / 2)
        // Not set: as before.
        assertEquals(listOf(remux.name, low.name, cam.name), order(remux, low, cam, context = upTo40.copy(prefs = RankingPrefs())))
        val ranked = rank(remux, low, cam, context = upTo40)
        assertEquals(listOf(low.name, cam.name, remux.name), ranked.map { it.facts.name })
        assertEquals(listOf(true, true, true), ranked.map { it.playable })
        val above = ranked.last().reasons.filterIsInstance<ReleaseReason.AboveMaxBitrate>().single()
        assertEquals(Severity.Warning to "Above your 40 Mbps", above.severity to above.text)
        assertTrue(ranked.dropLast(1).none { r -> r.reasons.any { it is ReleaseReason.AboveMaxBitrate } })
    }

    @Test
    fun `among releases that fit the maximum, a higher bitrate ranks higher`() {
        val remux = release("Harbor.Lights.S01E02.1080p.BluRay.REMUX.AVC-A", size = 12 * gb)
        val web = release("Harbor.Lights.S01E02.1080p.WEB-DL.x264-B", grabs = 50)
        // Not set: no bitrate preference, so the more grabbed WEB-DL goes first.
        assertEquals(listOf(web.name, remux.name), order(remux, web, context = upTo40.copy(prefs = RankingPrefs())))
        assertEquals(listOf(remux.name, web.name), order(remux, web, context = upTo40))
    }

    @Test
    fun `a higher bitrate never beats a step of quality or a preferred language`() {
        val big720 = release("Harbor.Lights.S01E02.720p.WEB-DL.x264-A", size = 13 * gb)
        val small1080 = release("Harbor.Lights.S01E02.1080p.WEB-DL.x264-B", size = gb / 4)
        assertEquals(listOf(small1080.name, big720.name), order(big720, small1080, context = upTo40))
        val german = upTo40.copy(prefs = RankingPrefs(audioLanguages = listOf("de", "en"), maxBitrate = 40_000_000))
        val dub = release("Harbor.Lights.S01E02.German.DL.720p.WEB.x264-C", size = gb / 4)
        val remux = release("Harbor.Lights.S01E02.1080p.BluRay.REMUX.AVC-D", size = 13 * gb)
        assertEquals(listOf(dub.name, remux.name), order(remux, dub, context = german))
    }

    /**
     * However close two releases of other resolutions or languages stand once source, Proper, grabs and a pack's cost count,
     * a higher bitrate doesn't put one above the other. At 10 Mbps, over 45 minutes: a 720p BluRay of about 8 Mbps stays
     * under a 1080p HDTV of about 3.6 Mbps, and an English 1080p WEB-DL of about 9 Mbps under an Italian one of about
     * 1.8 Mbps where Italian is preferred to English (30 against 15).
     */
    @Test
    fun `a higher bitrate doesn't reorder releases of other resolutions or languages, however close they stand`() {
        val upTo10 = upTo40.copy(prefs = RankingPrefs(maxBitrate = 10_000_000))
        val bluray720 = release("Harbor.Lights.S01E05.720p.BluRay.x264-A", size = 2_700_000_000)
        val hdtv1080 = release("Harbor.Lights.S01E05.1080p.HDTV.x264-B", size = 1_200_000_000)
        assertEquals(listOf(hdtv1080.name, bluray720.name), order(bluray720, hdtv1080, context = upTo10.copy(prefs = RankingPrefs())))
        assertEquals(listOf(hdtv1080.name, bluray720.name), order(bluray720, hdtv1080, context = upTo10))

        val languages = listOf("de", "fr", "it", "en")
        val italian = release("Harbor.Lights.S01E05.ITALIAN.1080p.WEB-DL.x264-C", size = 600_000_000)
        val english = release("Harbor.Lights.S01E05.1080p.WEB-DL.x264-D", size = 3_000_000_000)
        assertEquals(listOf(italian.name, english.name), order(english, italian, context = upTo10.copy(prefs = RankingPrefs(audioLanguages = languages))))
        assertEquals(listOf(italian.name, english.name), order(english, italian, context = upTo10.copy(prefs = RankingPrefs(audioLanguages = languages, maxBitrate = 10_000_000))))

        // Of one resolution and language, the higher bitrate still goes first, the 720p release staying under both.
        val web1080 = release("Harbor.Lights.S01E05.1080p.WEB-DL.x264-E", size = 1_000_000_000, grabs = 50)
        assertEquals(listOf(english.name, web1080.name, bluray720.name), order(bluray720, web1080, english, context = upTo10))
    }

    /** A pack of several seasons, however its name ranges them, has no estimate: TMDB's count is one season's. */
    @Test
    fun `a pack of several seasons has no bitrate estimate`() {
        val seasons = RankingContext(phone, runtimeMinutes = 45, seasonEpisodes = 10)
        listOf("Harbor.Lights.S01-S03.1080p.BluRay.x264-A", "Harbor.Lights.S01-03.1080p.BluRay.x264-A", "Harbor.Lights.S01.S02.S03.1080p.BluRay.x264-A").forEach { name ->
            assertNull(rank(release(name, size = 60 * gb), context = seasons).single().estimatedBitrate, name)
        }
    }

    @Test
    fun `a release with no bitrate estimate is neither held back nor helped by the maximum`() {
        // A season pack without the season's episode count.
        val pack = release("Harbor.Lights.S01.2160p.BluRay.REMUX.HEVC-A", size = 300 * gb)
        val withMax = rank(pack, context = upTo40).single()
        assertNull(withMax.estimatedBitrate)
        assertTrue(withMax.reasons.none { it is ReleaseReason.AboveMaxBitrate })
        assertEquals(rank(pack, context = upTo40.copy(prefs = RankingPrefs())).single().score, withMax.score)
    }

    @Test
    fun `unknown throughput does not penalise`() {
        val ranked = rank(release("Harbor.Lights.S01E02.1080p.BluRay.REMUX.AVC-A", size = 12 * gb))
        assertTrue(ranked.single().reasons.none { it is ReleaseReason.MayBuffer })
    }

    @Test
    fun `proper beats original and grabs break ties`() {
        assertEquals(
            listOf("Harbor.Lights.S01E02.PROPER.1080p.WEB.x264-B", "Harbor.Lights.S01E02.1080p.WEB.x264-C", "Harbor.Lights.S01E02.1080p.WEB.x264-A"),
            order(
                release("Harbor.Lights.S01E02.1080p.WEB.x264-A", grabs = 3),
                release("Harbor.Lights.S01E02.PROPER.1080p.WEB.x264-B", grabs = 3),
                release("Harbor.Lights.S01E02.1080p.WEB.x264-C", grabs = 400),
            ),
        )
    }

    @Test
    fun `cam sources rank below everything else that plays`() {
        assertEquals("Copper.Crown.2019.480p.DVDRip.XviD-B", order(
            release("Copper.Crown.2019.1080p.HDCAM.x264-A"),
            release("Copper.Crown.2019.480p.DVDRip.XviD-B"),
            context = RankingContext(phone.copy(video = phone.video + VideoCodec.Xvid)),
        ).first())
    }

    @Test
    fun `releases older than retention are blocked`() {
        val ranked = rank(
            release("Copper.Crown.2019.1080p.BluRay.x264-A", ageDays = 5000),
            context = RankingContext(phone, RankingPrefs(retentionDays = 4000)),
        )
        assertEquals(listOf<ReleaseReason>(ReleaseReason.BeyondRetention), ranked.single().reasons.filter { it.severity == Severity.Blocking })
    }

    @Test
    fun `playable release says so`() {
        assertEquals(ReleaseReason.PlaysOnDevice, rank(release("Harbor.Lights.S01E02.1080p.WEB.x264-A")).single().reasons.first { it.severity == Severity.Positive })
    }

    // For a Cast device (L126): its profile is the context's device, and what it can't play warns rather than hides.

    /**
     * A Cast device's profile as the app gives one (L128): every audio codec but DTS's and TrueHD, no VC-1, MPEG-2 or XviD,
     * no H.264 10-bit. Kitchen display is a model known to show HD, its HEVC and VP9 included.
     */
    private val kitchenDisplay = StaticDeviceCapabilities(
        displayHeight = 1080,
        video = setOf(VideoCodec.H264, VideoCodec.Hevc, VideoCodec.Vp9),
        videoTenBit = setOf(VideoCodec.Hevc, VideoCodec.Vp9),
        videoMaxHeight = mapOf(VideoCodec.H264 to 1080, VideoCodec.Hevc to 1080, VideoCodec.Vp9 to 1080),
        videoTenBitMaxHeight = mapOf(VideoCodec.Hevc to 1080, VideoCodec.Vp9 to 1080),
    )
    private val toKitchen = RankingContext(kitchenDisplay, target = RankingTarget.Cast("Kitchen display"))

    /** A 4K model's profile, as a Shield's or a Chromecast Ultra's: HEVC up to 2160p, and Dolby Vision not shown. */
    private val fourKCast = kitchenDisplay.copy(
        displayHeight = 2160,
        videoMaxHeight = mapOf(VideoCodec.H264 to 2160, VideoCodec.Hevc to 2160, VideoCodec.Vp9 to 2160),
        videoTenBitMaxHeight = mapOf(VideoCodec.Hevc to 2160, VideoCodec.Vp9 to 2160),
    )

    /** A TV with Google Cast built in, of a model the app doesn't know: no decoder limit known, 1080p until it shows 4K (W53). */
    private val denTv = RankingContext(
        StaticDeviceCapabilities(
            displayHeight = 1080,
            video = setOf(VideoCodec.H264, VideoCodec.Hevc, VideoCodec.Vp9, VideoCodec.Av1),
            videoTenBit = setOf(VideoCodec.Hevc, VideoCodec.Vp9, VideoCodec.Av1),
        ),
        target = RankingTarget.Cast("Den TV"),
    )

    @Test
    fun `a ranking is for this device unless it says otherwise`() {
        assertEquals(RankingTarget.Device, RankingContext(phone).target)
    }

    /** W51 (a): ranked last with a chip naming the device, castable by hand; the pre-check asks before it plays. */
    @Test
    fun `for a Cast device, audio it can't play is a warning in its name, tried after every release that fits, and still castable`() {
        val dts = release("Lantern.Field.2008.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY", size = 30 * gb, grabs = 900)
        val trueHd = release("Lantern.Field.2008.1080p.BluRay.REMUX.AVC.TrueHD.7.1-FERRY", size = 30 * gb, grabs = 900)
        // The lowest a release that fits scores: a camera copy, and one in a language not asked for.
        val cam = release("Lantern.Field.2008.480p.HDCAM.x264-C")
        val germanCam = release("Lantern.Field.2008.German.480p.HDCAM.x264-D")
        val ranked = rank(dts, trueHd, cam, germanCam, context = toKitchen)
        assertEquals(listOf(cam.name, germanCam.name), ranked.take(2).map { it.facts.name })
        assertEquals(setOf(dts.name, trueHd.name), ranked.drop(2).map { it.facts.name }.toSet())
        assertTrue(ranked.all { it.playable })
        fun chip(release: ReleaseFacts) = ranked.single { it.facts == release }.reasons.filterIsInstance<ReleaseReason.NoSoundOn>().single().let { it.severity to it.text }
        assertEquals(Severity.Warning to "DTS-HD MA audio: no sound on Kitchen display", chip(dts))
        assertEquals(Severity.Warning to "TrueHD audio: no sound on Kitchen display", chip(trueHd))
        assertTrue(ranked.none { r -> r.reasons.any { it.severity == Severity.Blocking } })
        // Ranked for this device, with the same decoders, each is hidden, as before.
        assertEquals(listOf(true, true, false, false), rank(dts, trueHd, cam, germanCam, context = toKitchen.copy(target = RankingTarget.Device)).map { it.playable })
    }

    /** AC-3 and E-AC-3 are fine on every device, and the first castable track is the one that plays. */
    @Test
    fun `for a Cast device, DTS beside AC-3 is fine, and neither AC-3 nor E-AC-3 is held back`() {
        listOf(
            "Copper.Crown.2019.1080p.BluRay.DTS.AC3.x264-C",
            "Copper.Crown.2019.1080p.BluRay.TrueHD.7.1.DD5.1.x264-C",
            "Copper.Crown.2019.1080p.WEB-DL.DDP5.1.H.264-C",
            "Copper.Crown.2019.1080p.BluRay.AC3.x264-C",
        ).forEach { name ->
            val cast = rank(release(name), context = toKitchen).single()
            assertTrue(cast.playable, name)
            assertTrue(cast.reasons.none { it.severity == Severity.Warning }, "$name: ${cast.reasons}")
            assertEquals(rank(release(name), context = toKitchen.copy(target = RankingTarget.Device)).single().score, cast.score, name)
        }
    }

    @Test
    fun `for a Cast device, video it doesn't show is a warning in its name, tried after every release that fits`() {
        val vc1 = release("Lantern.Field.2008.1080p.BluRay.REMUX.VC-1.DD5.1-LUMEN", size = 25 * gb, grabs = 900)
        val hi10p = release("Lantern.Field.2008.1080p.BluRay.x264.10bit-HALYARD", grabs = 900)
        val cam = release("Lantern.Field.2008.480p.HDCAM.x264-C")
        val ranked = rank(vc1, hi10p, cam, context = toKitchen)
        assertEquals(cam.name, ranked.first().facts.name)
        assertTrue(ranked.all { it.playable })
        fun chip(release: ReleaseFacts) = ranked.single { it.facts == release }.reasons.filterIsInstance<ReleaseReason.NoPictureOn>().single().let { it.severity to it.text }
        assertEquals(Severity.Warning to "VC-1: no picture on Kitchen display", chip(vc1))
        assertEquals(Severity.Warning to "H.264 10-bit: no picture on Kitchen display", chip(hi10p))
    }

    @Test
    fun `for a Cast device of an HD model, 4K is a warning in its name and goes last`() {
        val uhd = release("Lantern.Field.2008.2160p.WEB-DL.x265-A", grabs = 900)
        val p720 = release("Lantern.Field.2008.720p.WEB-DL.x264-B")
        val cam = release("Lantern.Field.2008.480p.HDCAM.x264-C")
        val ranked = rank(uhd, p720, cam, context = toKitchen)
        assertEquals(listOf(p720.name, cam.name, uhd.name), ranked.map { it.facts.name })
        assertTrue(ranked.last().playable)
        val reasons = ranked.last().reasons
        val reason = reasons.filterIsInstance<ReleaseReason.TooBigFor>().single()
        assertEquals(Severity.Warning to "4K: Kitchen display shows HD", reason.severity to reason.text)
        // The warning says why; neither "2160p is above this screen" nor "hasn't played 4K yet" beside it.
        assertTrue(reasons.none { it is ReleaseReason.AboveScreen || it is ReleaseReason.NotYet4k }, "$reasons")
    }

    /** W53 (a): an unknown Cast device aims at 1080p until it has shown 4K; 2160p isn't held back, only after 1080p. */
    @Test
    fun `for a Cast device not yet seen playing 4K, 2160p goes after 1080p with a plain chip, and not last`() {
        val uhd = release("Lantern.Field.2008.2160p.WEB-DL.x265-A", grabs = 900)
        val fullHd = release("Lantern.Field.2008.1080p.WEB-DL.x264-B")
        val cam = release("Lantern.Field.2008.480p.HDCAM.x264-C")
        val ranked = rank(uhd, fullHd, cam, context = denTv)
        assertEquals(listOf(fullHd.name, uhd.name, cam.name), ranked.map { it.facts.name })
        val reasons = ranked[1].reasons
        val reason = reasons.filterIsInstance<ReleaseReason.NotYet4k>().single()
        assertEquals(Severity.Neutral to "2160p: Den TV hasn’t played 4K yet", reason.severity to reason.text)
        assertTrue(reasons.none { it is ReleaseReason.AboveScreen || it.severity == Severity.Warning }, "$reasons")
        // Once it has shown 4K, 2160p goes first there, with no chip.
        val shown = denTv.copy(device = (denTv.device as StaticDeviceCapabilities).copy(displayHeight = 2160))
        val after = rank(uhd, fullHd, cam, context = shown)
        assertEquals(uhd.name, after.first().facts.name)
        assertTrue(after.first().reasons.none { it is ReleaseReason.NotYet4k || it is ReleaseReason.AboveScreen })
    }

    /**
     * CsPhoneRanked's Den TV: the releases that fit it first, 2160p after 1080p and 720p, then those it can't fully play
     * (the casting maximum's chip, the design's other warning, is the setting's). Lantern Field runs 118 minutes.
     */
    @Test
    fun `a list ranked for Den TV goes as the board draws it`() {
        val bluray = release("Lantern.Field.2008.1080p.BluRay.DD5.1.x264-ORCHID", size = 10_400_000_000, grabs = 412)
        val dl1080 = release("Lantern.Field.2008.German.DL.1080p.BluRay.x264-WATTWURM", size = 9_100_000_000, grabs = 296)
        val web = release("Lantern.Field.2008.1080p.WEB-DL.DDP5.1.H.264-KESTREL", size = 6_200_000_000, grabs = 238)
        val p720 = release("Lantern.Field.2008.720p.BluRay.x264-DRIFTWOOD", size = 4_400_000_000, grabs = 157)
        val dts = release("Lantern.Field.2008.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY", size = 31_800_000_000, grabs = 88)
        val vc1 = release("Lantern.Field.2008.1080p.BluRay.REMUX.VC-1.DD5.1-LUMEN", size = 24_700_000_000, grabs = 63)
        val dl2160 = release("Lantern.Field.2008.German.DL.2160p.UHD.BluRay.DDP5.1.x265-WATTWURM", size = 18_900_000_000, grabs = 52)
        val uhd = release("Lantern.Field.2008.2160p.UHD.BluRay.REMUX.HEVC.TrueHD.7.1.Atmos-FERRY", size = 58_300_000_000, grabs = 77)
        val iso = release("Lantern.Field.2008.COMPLETE.BLURAY-DRIFTWOOD", size = 44_100_000_000, grabs = 19)
        val ranked = rank(uhd, iso, vc1, dts, dl2160, p720, web, dl1080, bluray, context = denTv.copy(runtimeMinutes = 118))
        assertEquals(listOf(bluray, dl1080, web, p720, dl2160, dts, vc1, uhd, iso).map { it.name }, ranked.map { it.facts.name })
        assertEquals(listOf(true, true, true, true, true, true, true, true, false), ranked.map { it.playable })
        fun chips(release: ReleaseFacts) = ranked.single { it.facts == release }.reasons.filter { it.severity == Severity.Warning || it.severity == Severity.Neutral }.map { it.text }
        assertEquals(listOf("2160p: Den TV hasn’t played 4K yet"), chips(dl2160))
        assertEquals(listOf("DTS-HD MA audio: no sound on Den TV"), chips(dts))
        assertEquals(listOf("VC-1: no picture on Den TV"), chips(vc1))
        // Its sound is the warning; and as Den TV hasn't shown 4K, the plain chip too.
        assertEquals(listOf("TrueHD audio: no sound on Den TV", "2160p: Den TV hasn’t played 4K yet"), chips(uhd))
        // Only a disc image stays hidden.
        assertEquals(listOf<ReleaseReason>(ReleaseReason.DiscImage), ranked.last().reasons.filter { it.severity == Severity.Blocking })
    }

    @Test
    fun `for a Cast device, Dolby Vision without a base it shows is a warning in its name`() {
        val toFourK = toKitchen.copy(device = fourKCast)
        val dvOnly = release("Lantern.Field.2008.2160p.WEB.H265.DV-A", grabs = 900)
        val withBase = release("Lantern.Field.2008.2160p.WEB.H265.DV.HDR10-B")
        val ranked = rank(dvOnly, withBase, context = toFourK)
        assertEquals(listOf(withBase.name, dvOnly.name), ranked.map { it.facts.name })
        assertTrue(ranked.all { it.playable })
        val reason = ranked.last().reasons.filterIsInstance<ReleaseReason.DolbyVisionOnlyOn>().single()
        assertEquals(Severity.Warning to "Dolby Vision only: wrong colours on Kitchen display", reason.severity to reason.text)
        assertTrue(ranked.first().reasons.none { it.severity == Severity.Warning })
        // A device that has shown Dolby Vision plays it as any other.
        assertTrue(rank(dvOnly, context = toFourK.copy(device = fourKCast.copy(dolbyVision = true))).single().reasons.none { it.severity == Severity.Warning })
    }

    @Test
    fun `for a Cast device, a disc image and a release past retention stay hidden`() {
        val prefs = RankingPrefs(retentionDays = 4000)
        val ranked = rank(
            release("Copper.Crown.2019.COMPLETE.BLURAY-A", size = 40 * gb),
            release("Copper.Crown.2019.1080p.BluRay.x264-B", ageDays = 5000),
            context = toKitchen.copy(prefs = prefs),
        )
        assertEquals(listOf(false, false), ranked.map { it.playable })
        assertEquals(
            setOf<ReleaseReason>(ReleaseReason.DiscImage, ReleaseReason.BeyondRetention),
            ranked.flatMap { r -> r.reasons.filter { it.severity == Severity.Blocking } }.toSet(),
        )
    }

    /** The phone can't check the Cast device's player, so nothing says the release plays there. */
    @Test
    fun `for a Cast device nothing says the release plays on this device`() {
        val ranked = rank(release("Lantern.Field.2008.1080p.WEB-DL.x264-A"), context = toKitchen).single()
        assertTrue(ranked.playable)
        assertTrue(ranked.reasons.none { it == ReleaseReason.PlaysOnDevice }, "${ranked.reasons}")
    }

    /** A device that showed an HDR file as SDR (L128) has no HDR: HDR ranks lower there, as on a TV without it. */
    @Test
    fun `for a Cast device that showed HDR as SDR, an HDR release ranks lower, on a phone too`() {
        val sdrCast = toKitchen.copy(device = fourKCast.copy(hdrTypes = emptySet()))
        val hdr = release("Lantern.Field.2008.2160p.WEB-DL.H265.HDR-A", grabs = 900)
        val sdr = release("Lantern.Field.2008.2160p.WEB-DL.H265-B")
        // The app is a phone's: tv stays false for a cast.
        assertEquals(false, sdrCast.tv)
        val ranked = rank(hdr, sdr, context = sdrCast)
        assertEquals(listOf(sdr.name, hdr.name), ranked.map { it.facts.name })
        val reason = ranked.last().reasons.filterIsInstance<ReleaseReason.HdrOnSdrScreen>().single()
        assertEquals(Severity.Warning to "HDR on a screen without HDR", reason.severity to reason.text)
        // Not yet seen as SDR, HDR isn't held back.
        val toFourK = toKitchen.copy(device = fourKCast)
        assertEquals(score(sdr.copy(grabs = 900), toFourK), score(hdr, toFourK))
        assertTrue(rank(hdr, context = toFourK).single().reasons.none { it is ReleaseReason.HdrOnSdrScreen })
    }

    @Test
    fun `for a Cast device two reasons it can't play score as one`() {
        val both = release("Lantern.Field.2008.1080p.BluRay.REMUX.VC-1.DTS-HD.MA.5.1-LUMEN", size = 25 * gb, grabs = 63)
        val one = release("Lantern.Field.2008.1080p.BluRay.REMUX.VC-1.DD5.1-LUMEN", size = 25 * gb, grabs = 63)
        val ranked = rank(both, one, context = toKitchen).associateBy { it.facts.name }
        assertEquals(ranked.getValue(one.name).score, ranked.getValue(both.name).score)
        assertEquals(
            listOf("VC-1: no picture on Kitchen display", "DTS-HD MA audio: no sound on Kitchen display"),
            ranked.getValue(both.name).reasons.filter { it.severity == Severity.Warning }.map { it.text },
        )
        // As far under the releases that fit as one above the maximum bitrate.
        val fits = rank(release("Lantern.Field.2008.1080p.BluRay.REMUX.AVC.DD5.1-LUMEN", size = 25 * gb, grabs = 63), context = toKitchen).single()
        assertEquals(fits.score - 1_000.0, ranked.getValue(one.name).score, absoluteTolerance = 1e-9)
    }

    /** The phone can't know a Cast device's hardware decoders: their limit never holds a release back there. */
    @Test
    fun `for a Cast device no release is held back for want of a hardware decoder`() {
        val softwareUhdCast = fourKCast.copy(videoHardwareMaxHeight = mapOf(VideoCodec.Hevc to 1080))
        val uhd = release("Lantern.Field.2008.2160p.WEB-DL.x265-A")
        listOf(false, true).forEach { tv ->
            val ranked = rank(uhd, context = toKitchen.copy(device = softwareUhdCast, tv = tv)).single()
            assertTrue(ranked.playable, "tv=$tv")
            assertTrue(ranked.reasons.none { it is ReleaseReason.NoHardwareDecoder }, "tv=$tv: ${ranked.reasons}")
            assertEquals(score(uhd, toKitchen.copy(device = fourKCast, tv = tv)), ranked.score, "tv=$tv")
        }
    }

    // The maximum when casting (W52, L132): a setting of its own, read for a Cast device alone, where Maximum bitrate
    // counts too and the lower of the two holds.

    /** At most 40 Mbps when casting, a film of 118 minutes: 38.6 GB is ~44 Mbps, 31 GB ~35 Mbps, 10.4 GB ~12 Mbps. */
    private val castUpTo40 = toKitchen.copy(prefs = RankingPrefs(castMaxBitrate = 40_000_000), runtimeMinutes = 118)

    @Test
    fun `for a Cast device, a release above the maximum when casting is tried after every one that fits, says so, and stays castable`() {
        val flac = release("Lantern.Field.2008.1080p.BluRay.REMUX.AVC.FLAC.2.0-HALYARD", size = 38_600_000_000, grabs = 900)
        val web = release("Lantern.Field.2008.1080p.WEB-DL.DDP5.1.H.264-KESTREL", size = 6_200_000_000)
        val cam = release("Lantern.Field.2008.480p.HDCAM.x264-C")
        val notSet = castUpTo40.copy(prefs = RankingPrefs())
        // Not set: as before, the remux first.
        assertEquals(flac.name, order(flac, web, cam, context = notSet).first())
        val ranked = rank(flac, web, cam, context = castUpTo40)
        assertEquals(listOf(web.name, cam.name, flac.name), ranked.map { it.facts.name })
        assertTrue(ranked.all { it.playable })
        val above = ranked.last().reasons.filterIsInstance<ReleaseReason.AboveCastMax>().single()
        assertEquals(Severity.Warning to "Above your 40 Mbps for casting", above.severity to above.text)
        // Maximum bitrate isn't set, so its own chip isn't there.
        assertTrue(ranked.last().reasons.none { it is ReleaseReason.AboveMaxBitrate })
        assertTrue(ranked.dropLast(1).none { r -> r.reasons.any { it is ReleaseReason.AboveCastMax } })
        // As far under the releases that fit as one above Maximum bitrate.
        assertEquals(score(flac, notSet) - 1_000.0, ranked.last().score, absoluteTolerance = 1e-9)
    }

    /**
     * While casting, Maximum bitrate counts too, and the lower of the two holds, as the phone still downloads the film (W52):
     * a release above one says so in that setting's words, and one above both has both chips, scored once.
     */
    @Test
    fun `for a Cast device each maximum set holds, each chip in its own words, and one above both is scored once`() {
        val over35 = release("Lantern.Field.2008.1080p.BluRay.REMUX.AVC.DD5.1-A", size = 31 * gb)
        val over44 = release("Lantern.Field.2008.1080p.BluRay.REMUX.AVC.FLAC.2.0-B", size = 38_600_000_000)
        val fits = release("Lantern.Field.2008.1080p.BluRay.DD5.1.x264-C", size = 10_400_000_000)
        fun warnings(context: RankingContext) = rank(over35, over44, fits, context = context).associate { r ->
            r.facts.name to r.reasons.filter { it.severity == Severity.Warning }.map { it.text }
        }

        // The phone's 30 below casting's 40: 35 Mbps is above the phone's alone, 44 above both.
        val phone30 = castUpTo40.copy(prefs = RankingPrefs(maxBitrate = 30_000_000, castMaxBitrate = 40_000_000))
        val lower = warnings(phone30)
        assertEquals(listOf("Above your 30 Mbps"), lower[over35.name])
        assertEquals(listOf("Above your 30 Mbps", "Above your 40 Mbps for casting"), lower[over44.name])
        assertEquals(emptyList(), lower[fits.name])
        assertEquals(fits.name, order(over35, over44, fits, context = phone30).first())
        // Scored once: as far under where it stands with nothing set as a release above one maximum.
        val nothingSet = castUpTo40.copy(prefs = RankingPrefs())
        assertEquals(score(over44, nothingSet) - 1_000.0, score(over44, phone30), absoluteTolerance = 1e-9)

        // Casting's 30 below the phone's 40: 35 Mbps is above casting's alone.
        val cast30 = castUpTo40.copy(prefs = RankingPrefs(maxBitrate = 40_000_000, castMaxBitrate = 30_000_000))
        val castLower = warnings(cast30)
        assertEquals(listOf("Above your 30 Mbps for casting"), castLower[over35.name])
        assertEquals(listOf("Above your 40 Mbps", "Above your 30 Mbps for casting"), castLower[over44.name])
        assertEquals(score(over35, nothingSet) - 1_000.0, score(over35, cast30), absoluteTolerance = 1e-9)
    }

    /** This device's own ranking, on a phone or a TV, never reads the maximum when casting: no chip, no order, no bonus. */
    @Test
    fun `the maximum when casting counts for nothing in this device's own ranking`() {
        val flac = release("Lantern.Field.2008.1080p.BluRay.REMUX.AVC.FLAC.2.0-HALYARD", size = 38_600_000_000)
        val web = release("Lantern.Field.2008.1080p.WEB-DL.DDP5.1.H.264-KESTREL", size = 6_200_000_000, grabs = 50)
        listOf(false, true).forEach { tv ->
            val here = RankingContext(phone, RankingPrefs(castMaxBitrate = 40_000_000), runtimeMinutes = 118, tv = tv)
            val ranked = rank(flac, web, context = here)
            assertTrue(ranked.none { r -> r.reasons.any { it is ReleaseReason.AboveCastMax || it is ReleaseReason.AboveMaxBitrate } }, "tv=$tv")
            assertEquals(rank(flac, web, context = here.copy(prefs = RankingPrefs())), ranked, "tv=$tv")
        }
    }

    /**
     * Among the releases that fit, a higher bitrate ranks a little higher up to the lower maximum set (L132, changed): for a
     * Cast device the casting one gives the bonus as Maximum bitrate's does, the lower of the two where both are set. With
     * neither set there is no bonus, as for this device.
     */
    @Test
    fun `for a Cast device a higher bitrate ranks higher within the lower maximum set`() {
        val remux = release("Lantern.Field.2008.1080p.BluRay.REMUX.AVC.DD5.1-A", size = 31 * gb)
        val bluray = release("Lantern.Field.2008.1080p.BluRay.DD5.1.x264-B", size = 10_400_000_000, grabs = 412)
        val notSet = castUpTo40.copy(prefs = RankingPrefs())
        // Not set: no bitrate preference, so the more grabbed BluRay goes first.
        assertEquals(listOf(bluray.name, remux.name), order(remux, bluray, context = notSet))
        // 40 when casting: the remux within it first.
        assertEquals(listOf(remux.name, bluray.name), order(remux, bluray, context = castUpTo40))
        fun scored(prefs: RankingPrefs) = score(bluray, notSet.copy(prefs = prefs))
        assertTrue(scored(RankingPrefs(castMaxBitrate = 40_000_000)) > scored(RankingPrefs()))
        assertEquals(scored(RankingPrefs(maxBitrate = 40_000_000)), scored(RankingPrefs(castMaxBitrate = 40_000_000)), absoluteTolerance = 1e-9)
        // The lower set maximum: casting's where it is lower, the phone's where that is.
        assertEquals(scored(RankingPrefs(maxBitrate = 20_000_000)), scored(RankingPrefs(maxBitrate = 60_000_000, castMaxBitrate = 20_000_000)), absoluteTolerance = 1e-9)
        assertEquals(scored(RankingPrefs(maxBitrate = 20_000_000)), scored(RankingPrefs(maxBitrate = 20_000_000, castMaxBitrate = 60_000_000)), absoluteTolerance = 1e-9)
        assertTrue(scored(RankingPrefs(castMaxBitrate = 20_000_000)) > scored(RankingPrefs(castMaxBitrate = 40_000_000)))
    }

    /**
     * CsPhoneRanked's Kitchen display, with 40 Mbps set for casting: the releases that fit it first; then, each scored once,
     * those it can't fully play and the one above the casting maximum, in their usual order; the 4K remux with TrueHD and
     * above the maximum last of all, its device's reasons and the maximum scored apart. Lantern Field runs 118 minutes.
     */
    @Test
    fun `a list ranked for Kitchen display with 40 Mbps set for casting goes as the board draws it`() {
        val bluray = release("Lantern.Field.2008.1080p.BluRay.DD5.1.x264-ORCHID", size = 10_400_000_000, grabs = 412)
        val dl1080 = release("Lantern.Field.2008.German.DL.1080p.BluRay.x264-WATTWURM", size = 9_100_000_000, grabs = 296)
        val web = release("Lantern.Field.2008.1080p.WEB-DL.DDP5.1.H.264-KESTREL", size = 6_200_000_000, grabs = 238)
        val p720 = release("Lantern.Field.2008.720p.BluRay.x264-DRIFTWOOD", size = 4_400_000_000, grabs = 157)
        val dts = release("Lantern.Field.2008.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY", size = 31_800_000_000, grabs = 88)
        val vc1 = release("Lantern.Field.2008.1080p.BluRay.REMUX.VC-1.DD5.1-LUMEN", size = 24_700_000_000, grabs = 63)
        val flac = release("Lantern.Field.2008.1080p.BluRay.REMUX.AVC.FLAC.2.0-HALYARD", size = 38_600_000_000, grabs = 41)
        val dl2160 = release("Lantern.Field.2008.German.DL.2160p.UHD.BluRay.DDP5.1.x265-WATTWURM", size = 18_900_000_000, grabs = 52)
        val uhd = release("Lantern.Field.2008.2160p.UHD.BluRay.REMUX.HEVC.TrueHD.7.1.Atmos-FERRY", size = 58_300_000_000, grabs = 77)
        val iso = release("Lantern.Field.2008.COMPLETE.BLURAY-DRIFTWOOD", size = 44_100_000_000, grabs = 19)
        val ranked = rank(uhd, iso, vc1, flac, dts, dl2160, p720, web, dl1080, bluray, context = castUpTo40)
        assertEquals(listOf(bluray, dl1080, web, p720, dts, vc1, flac, dl2160, uhd, iso).map { it.name }, ranked.map { it.facts.name })
        assertEquals(List(9) { true } + false, ranked.map { it.playable })
        fun chips(release: ReleaseFacts) = ranked.single { it.facts == release }.reasons.filter { it.severity == Severity.Warning || it.severity == Severity.Neutral }.map { it.text }
        listOf(bluray, dl1080, web, p720).forEach { assertEquals(emptyList(), chips(it), it.name) }
        assertEquals(listOf("DTS-HD MA audio: no sound on Kitchen display"), chips(dts))
        assertEquals(listOf("VC-1: no picture on Kitchen display"), chips(vc1))
        assertEquals(listOf("Above your 40 Mbps for casting"), chips(flac))
        assertEquals(listOf("4K: Kitchen display shows HD"), chips(dl2160))
        // The design's order on its card: the device's reasons, then the maximum.
        assertEquals(listOf("4K: Kitchen display shows HD", "TrueHD audio: no sound on Kitchen display", "Above your 40 Mbps for casting"), chips(uhd))
        assertEquals(score(uhd, castUpTo40.copy(prefs = RankingPrefs())) - 1_000.0, ranked.single { it.facts == uhd }.score, absoluteTolerance = 1e-9)
    }

    /** A device's name reaches no log by a slip: what the ranking says prints without it (as CastTo does). */
    @Test
    fun `a ranking for a Cast device prints without its name`() {
        val ranked = rank(
            release("Lantern.Field.2008.2160p.UHD.BluRay.REMUX.HEVC.TrueHD.7.1.Atmos-FERRY"),
            release("Lantern.Field.2008.1080p.BluRay.REMUX.VC-1.DTS-HD.MA.5.1-LUMEN"),
            release("Lantern.Field.2008.1080p.WEB.H265.DV-A"),
            context = toKitchen,
        ) + rank(release("Lantern.Field.2008.2160p.WEB-DL.x265-A"), context = denTv)
        val reasons = ranked.flatMap { it.reasons }
        listOf(ReleaseReason.NoSoundOn::class, ReleaseReason.NoPictureOn::class, ReleaseReason.TooBigFor::class, ReleaseReason.DolbyVisionOnlyOn::class, ReleaseReason.NotYet4k::class)
            .forEach { kind -> assertTrue(reasons.any { kind.isInstance(it) }, kind.java.simpleName) }
        for (text in listOf(ranked.toString(), toKitchen.toString(), denTv.toString())) {
            assertTrue("Kitchen display" !in text && "Den TV" !in text, text)
        }
    }

    // Usenet and torrents in one list (L176, L177; W72: "prefer usenet", Open 1 (a)), for a Cast device too (L202 lifted, W73).

    /** [name] as a torrent, its check settled as [availability]: cached on one account unless said. */
    private fun torrent(name: String, size: Long = 2 * gb, grabs: Int? = null, availability: Availability = Availability.Cached(listOf("torbox-1"))) =
        ReleaseFacts(id = "btih:$name", name = name, sizeBytes = size, grabs = grabs, source = ReleaseFacts.TORRENT, availability = availability)

    /** The ids in ranked order: a torrent's is its name after "btih:", as the same name may come from both sources. */
    private fun ids(vararg releases: ReleaseFacts, context: RankingContext = RankingContext(phone)) = rank(*releases, context = context).map { it.facts.id }

    @Test
    fun `the same release from Usenet and as a cached torrent goes Usenet's first, whichever came first and however grabbed`() {
        val name = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD"
        val usenet = release(name, grabs = 90)
        val cached = torrent(name, grabs = 1_800)
        assertEquals(listOf(name, "btih:$name"), ids(cached, usenet))
        assertEquals(listOf(name, "btih:$name"), ids(usenet, cached))
    }

    @Test
    fun `a step of quality, a better source or a proper still puts a torrent first`() {
        val usenet720 = release("Quiet.Orbit.S02E05.720p.WEB-DL.DDP5.1.H.264-ORCHID", grabs = 400)
        val torrent1080 = torrent("Quiet.Orbit.S02E05.1080p.WEB-DL.DDP5.1.H.264-KESTREL", grabs = 12)
        assertEquals(listOf(torrent1080.id, usenet720.id), ids(usenet720, torrent1080))

        val usenet = release("Quiet.Orbit.S02E05.1080p.WEB-DL.DDP5.1.H.264-ORCHID", grabs = 400)
        val proper = torrent("Quiet.Orbit.S02E05.PROPER.1080p.WEB-DL.DDP5.1.H.264-KESTREL", grabs = 12)
        assertEquals(listOf(proper.id, usenet.id), ids(usenet, proper))

        val webrip = release("Quiet.Orbit.S02E05.1080p.WEBRip.x264-ORCHID", grabs = 400)
        val bluray = torrent("Quiet.Orbit.S02E05.1080p.BluRay.x264-KESTREL", grabs = 12)
        assertEquals(listOf(bluray.id, webrip.id), ids(webrip, bluray))
    }

    /**
     * Only a torrent that equals a Usenet release before the bitrate's bonus and the grabs' points is held under it: a
     * BluRay single scores a season pack's cost above a WEB-DL pack of its resolution (BluRay and WEB-DL score alike as
     * sources), and goes first.
     */
    @Test
    fun `a torrent that scores higher before the hold goes first`() {
        val pack = release("Quiet.Orbit.S02.1080p.WEB-DL.DDP5.1.H.264-ORCHID", size = 20 * gb, grabs = 900)
        val single = torrent("Quiet.Orbit.S02E05.1080p.BluRay.x264-KESTREL", grabs = 3)
        assertEquals(listOf(single.id, pack.id), ids(pack, single))
    }

    @Test
    fun `however many more grabs a torrent has, a Usenet release of its standing goes first`() {
        val usenet = release("Quiet.Orbit.S02E05.1080p.WEB-DL.DDP5.1.H.264-ORCHID", grabs = 400)
        val popular = torrent("Quiet.Orbit.S02E05.1080p.WEB-DL.DDP5.1.H.264-KESTREL", grabs = 9_000)
        assertEquals(listOf(usenet.id, popular.id), ids(popular, usenet))
    }

    /**
     * DDR-1: the hold compares the two releases' scores before their grabs as the same sum of the same parts, not one
     * taken back out of a total: a sum past 256 has its doubles twice as far apart, so (240 + 16.28) - 16.28 came back an
     * ulp over 240 and the torrent passed its equal. A just-aired episode with no Usenet grabs yet against a popular
     * torrent, and a list whose most grabbed Usenet release isn't the one the torrent equals.
     */
    @Test
    fun `a popular torrent stays under its equal on Usenet whatever its grabs come to`() {
        val name = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD"
        for (snatches in listOf(1_600, 1_800, 4_000, 19_999)) {
            assertEquals(listOf(name, "btih:$name"), ids(torrent(name, grabs = snatches), release(name)), "$snatches snatches against none")
        }
        val good = release(name, grabs = 90)
        val popular = release("Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-TIDE", grabs = 1_800)
        val same = torrent(name, grabs = 400)
        val ranked = ids(same, popular, good)
        assertTrue(ranked.indexOf(name) < ranked.indexOf("btih:$name"), "GOOD on Usenet before the same as a torrent: $ranked")
    }

    /**
     * DDR-2: torrents held under one Usenet release keep the order they have among themselves, as a list of torrents alone
     * ranks them: the better source first, and with a Maximum bitrate the higher bitrate, not the more snatched.
     */
    @Test
    fun `torrents held under a Usenet release keep their own order among themselves`() {
        val orchid = release("Quiet.Orbit.S02E05.1080p.WEB-DL.DDP5.1.H.264-ORCHID")
        val kestrel = torrent("Quiet.Orbit.S02E05.1080p.WEB-DL.DDP5.1.H.264-KESTREL", grabs = 20)
        val lumen = torrent("Quiet.Orbit.S02E05.1080p.WEBRip.x264-LUMEN", grabs = 300)
        assertEquals(listOf(kestrel.id, lumen.id), ids(lumen, kestrel), "as torrents alone")
        assertEquals(listOf(orchid.id, kestrel.id, lumen.id), ids(lumen, orchid, kestrel))

        val upTo40 = RankingContext(phone, RankingPrefs(maxBitrate = 40_000_000), runtimeMinutes = 45)
        val usenet6 = release("Quiet.Orbit.S02E05.1080p.WEB-DL.DDP5.1.H.264-ORCHID", size = 2_025_000_000)
        val torrent25 = torrent("Quiet.Orbit.S02E05.1080p.WEB-DL.DDP5.1.H.264-KESTREL", size = 8_437_500_000, grabs = 10)
        val torrent9 = torrent("Quiet.Orbit.S02E05.1080p.WEB-DL.DDP5.1.H.264-LUMEN", size = 3_037_500_000, grabs = 300)
        assertEquals(listOf(torrent25.id, torrent9.id), ids(torrent9, torrent25, context = upTo40), "as torrents alone")
        assertEquals(listOf(usenet6.id, torrent25.id, torrent9.id), ids(torrent9, usenet6, torrent25, context = upTo40))
    }

    /** With a Maximum bitrate of 40 Mbps, over 45 minutes: 8.4 GB is a 25 Mbps torrent, 2 GB a 6 Mbps Usenet release. */
    @Test
    fun `a higher bitrate doesn't lift a torrent over a Usenet release of its standing and source`() {
        val upTo40 = RankingContext(phone, RankingPrefs(maxBitrate = 40_000_000), runtimeMinutes = 45)
        val small = release("Quiet.Orbit.S02E05.1080p.WEB-DL.DDP5.1.H.264-ORCHID", size = 2_025_000_000)
        val big = torrent("Quiet.Orbit.S02E05.1080p.WEB-DL.DDP5.1.H.264-KESTREL", size = 8_437_500_000)
        assertEquals(listOf(small.id, big.id), ids(big, small, context = upTo40))
        // Both from Usenet, the higher bitrate goes first: the bonus the hold keeps a torrent from.
        val bigUsenet = release(big.name, size = big.sizeBytes)
        assertEquals(listOf(bigUsenet.id, small.id), ids(small, bigUsenet, context = upTo40))
    }

    /**
     * Grabs count within a source: the most grabbed torrent gets the points the most grabbed Usenet release gets, as the
     * indexers count snatches and grabs on scales of their own. A WEB-DL torrent of 50 grabs goes before a WEBRip of 9,000
     * on Usenet; counted as Usenet's, its grabs would leave it under.
     */
    @Test
    fun `grabs count within a source, the most grabbed torrent scoring as the most grabbed Usenet release`() {
        val webrip = release("Quiet.Orbit.S02E05.1080p.WEBRip.x264-ORCHID", grabs = 9_000)
        val webdl = torrent("Quiet.Orbit.S02E05.1080p.WEB-DL.DDP5.1.H.264-KESTREL", grabs = 50)
        assertEquals(listOf(webdl.id, webrip.id), ids(webrip, webdl))
        assertEquals(listOf(webrip.id, webdl.name), ids(webrip, release(webdl.name, grabs = 50)), "as Usenet's")
        // Among torrents, the more grabbed first.
        val more = torrent("Quiet.Orbit.S02E05.1080p.WEB-DL.DDP5.1.H.264-LUMEN", grabs = 300)
        assertEquals(listOf(more.id, webdl.id, webrip.id), ids(webrip, webdl, more))
    }

    @Test
    fun `a list of torrents alone ranks as the same releases from Usenet do`() {
        val names = listOf(
            "Quiet.Orbit.S02E05.1080p.WEB-DL.DDP5.1.H.264-ORCHID" to 40,
            "Quiet.Orbit.S02E05.1080p.WEB-DL.DDP5.1.H.264-KESTREL" to 900,
            "Quiet.Orbit.S02E05.720p.HDTV.x264-DRIFTWOOD" to 3_000,
            "Quiet.Orbit.S02E05.2160p.WEB.H265-FERRY" to 12,
            "Quiet.Orbit.S02E05.1080p.WEB-DL.DDP5.1.H.264-LUMEN" to 900,
        )
        val asTorrents = ids(*names.map { (name, grabs) -> torrent(name, grabs = grabs) }.toTypedArray()).map { it.removePrefix("btih:") }
        assertEquals(order(*names.map { (name, grabs) -> release(name, grabs = grabs) }.toTypedArray()), asTorrents)
    }

    @Test
    fun `a torrent not cached, not checked or not for the service is hidden in words that name no service, and cached is a plus`() {
        val name = "The.Glass.Orchard.2019.1080p.WEB-DL.DDP5.1.H.264-KESTREL"
        fun ranked(availability: Availability) = rank(torrent(name, availability = availability)).single()
        listOf(
            Availability.NotCached to "Not cached",
            Availability.NotChecked to "Not checked",
            Availability.NotForService to "Not used with your debrid service",
        ).forEach { (availability, words) ->
            val hidden = ranked(availability)
            assertEquals(false, hidden.playable, words)
            assertEquals(listOf(Severity.Blocking to words), hidden.reasons.filter { it.severity == Severity.Blocking }.map { it.severity to it.text })
        }
        val cached = ranked(Availability.Cached(listOf("premiumize-1", "torbox-1")))
        assertTrue(cached.playable)
        assertEquals(Severity.Positive, cached.reasons.single { it.text == "Cached" }.severity)
        // A Usenet release says nothing of a cache.
        assertTrue(rank(release(name)).single().reasons.none { it.text == "Cached" })
    }

    /**
     * L202 lifted (W73, C2): a debrid play is cast through the phone, so a cached torrent ranks for a Cast device as it does
     * for the phone: playable, held under a Usenet release of its standing, the device's own reasons, and Maximum bitrate
     * when casting holding for it as for any release.
     */
    @Test
    fun `a cached torrent ranks for Kitchen display as on the phone, under an equal Usenet release, and tried last above the casting maximum`() {
        val name = "Lantern.Field.2008.1080p.BluRay.x264-ORCHID"
        val usenet = release(name, grabs = 90)
        val cached = torrent(name, grabs = 1_800)
        val ranked = rank(cached, usenet, context = toKitchen)
        assertEquals(listOf(name, "btih:$name"), ranked.map { it.facts.id })
        assertEquals(ids(cached, usenet), ranked.map { it.facts.id }, "as the phone ranks them")
        assertTrue(ranked.all { it.playable })
        assertEquals(emptyList(), ranked.flatMap { r -> r.reasons.filter { it.severity == Severity.Blocking } })
        assertTrue(rank(torrent(name)).single().playable, "on this device it plays")

        // Above the maximum when casting: tried after every release that fits, says so, and stays castable.
        val remux = torrent("Lantern.Field.2008.1080p.BluRay.REMUX.AVC.FLAC.2.0-HALYARD", size = 38_600_000_000, grabs = 900)
        val web = release("Lantern.Field.2008.1080p.WEB-DL.DDP5.1.H.264-KESTREL", size = 6_200_000_000)
        val capped = rank(remux, web, context = castUpTo40)
        assertEquals(listOf(web.id, remux.id), capped.map { it.facts.id })
        assertTrue(capped.all { it.playable })
        assertEquals("Above your 40 Mbps for casting", capped.last().reasons.filterIsInstance<ReleaseReason.AboveCastMax>().single().text)
    }

    // Larger releases first on a TV (W101, L301-L308, L327, L333): there, with a maximum set or not, a release's bonus is
    // 60 x its bitrate over the top one of its standing and source; phones and Cast keep 25 x bitrate / maximum, only with
    // a maximum set.

    /** A 4K HDR TV (a Shield's screen), a film of 100 minutes in English: 80 GB is ~106.7 Mbps, 62 GB ~82.7, 24 GB 32. */
    private val fourKTv = RankingContext(phone.copy(displayHeight = 2160, hdrTypes = setOf(Hdr.Hdr10)), runtimeMinutes = 100, tv = true)

    /** A 1080p TV without HDR, a box whose decoders play 2160p too. */
    private val fullHdTv = fourKTv.copy(device = phone.copy(hdrTypes = emptySet()))

    private fun film(name: String, gigabytes: Int, grabs: Int) = release(name, size = gigabytes * gb, grabs = grabs)

    private fun remux(grabs: Int, gigabytes: Int = 80) = film("Copper.Crown.2019.2160p.BluRay.REMUX.HEVC-KESTREL", gigabytes, grabs)

    private fun encode(grabs: Int, proper: Boolean = false) =
        film("Copper.Crown.2019.${if (proper) "PROPER." else ""}2160p.BluRay.x265.DDP5.1-ORCHID", 24, grabs)

    /** L303's table at 60: an 80 GB remux against a 24 GB encode of its standing, each row's scores, where today grabs and Proper put the encode first. */
    @Test
    fun `on a TV a remux goes before a smaller encode of its standing whatever grabs and a Proper give the encode`() {
        listOf(
            Triple(remux(50), encode(2_000), 308.5 to 274.5),
            Triple(remux(5), encode(10_000), 303.9 to 278.0),
            Triple(remux(50), encode(2_000, proper = true), 308.5 to 299.5),
            Triple(remux(5), encode(10_000, proper = true), 303.9 to 303.0),
        ).forEach { (remux, encode, scores) ->
            val row = "remux ${remux.grabs} against ${encode.name} ${encode.grabs}"
            val ranked = rank(encode, remux, context = fourKTv)
            assertEquals(listOf(remux.name, encode.name), ranked.map { it.facts.name }, row)
            assertEquals(scores.first, ranked[0].score, absoluteTolerance = 0.05, message = row)
            assertEquals(scores.second, ranked[1].score, absoluteTolerance = 0.05, message = row)
        }
    }

    /** L303's last row: near-equal sizes still go by a Proper and grabs, an 80 GB remux in the list setting the top. */
    @Test
    fun `on a TV a Proper encode stays before a slightly larger encode of its standing`() {
        val proper = encode(300, proper = true)
        val larger = film("Copper.Crown.2019.2160p.BluRay.x265.DDP5.1-LUMEN", 30, 300)
        val ranked = order(larger, proper, remux(300), context = fourKTv)
        assertTrue(ranked.indexOf(proper.name) < ranked.indexOf(larger.name), "$ranked")
    }

    /**
     * 1-ranking D.7 example 2 (L302): on a 1080p TV without HDR the top is the standing's own, so the 2160p remux doesn't
     * shrink the 1080p releases' bonuses, and the 1080p remux goes first; no bonus lifts a 2160p release, its HDR's penalty
     * paid, over a 1080p one.
     */
    @Test
    fun `on a TV the top is its standing's, and no bonus lifts a release over another standing`() {
        val uhdRemux = film("Copper.Crown.2019.2160p.BluRay.REMUX.HEVC.HDR-KESTREL", 80, 50)
        val uhdEncode = film("Copper.Crown.2019.2160p.BluRay.x265.HDR.DDP5.1-ORCHID", 24, 2_000)
        val remux = film("Copper.Crown.2019.1080p.BluRay.REMUX.AVC-FERRY", 30, 20)
        val web = film("Copper.Crown.2019.1080p.WEB-DL.DDP5.1.H.264-TIDE", 8, 3_000)
        val ranked = rank(uhdEncode, web, uhdRemux, remux, context = fullHdTv)
        assertEquals(listOf(remux, web, uhdRemux, uhdEncode).map { it.name }, ranked.map { it.facts.name })
        listOf(306.6, 273.4, 223.5, 189.5).zip(ranked).forEach { (expected, r) ->
            assertEquals(expected, r.score, absoluteTolerance = 0.05, message = r.facts.name)
        }
    }

    /**
     * 1-ranking D.7 example 3 (L307): a disc copy whose name says nothing of a disc is the largest of its standing, so
     * first; the ranker can't see that it was remembered as one, and with it in the list or without, the remux goes
     * before the encode.
     */
    @Test
    fun `on a TV a disc copy named as a release goes first, and the remux before the encode with it or without`() {
        val disc = film("Copper.Crown.2019.2160p.UHD.BluRay.HEVC-DRIFT", 80, 8)
        val remux = remux(50, gigabytes = 62)
        val encode = encode(2_000)
        val ranked = rank(encode, remux, disc, context = fourKTv)
        assertEquals(listOf(disc.name, remux.name, encode.name), ranked.map { it.facts.name })
        listOf(304.8, 295.0, 274.5).zip(ranked).forEach { (expected, r) ->
            assertEquals(expected, r.score, absoluteTolerance = 0.05, message = r.facts.name)
        }
        assertEquals(listOf(remux.name, encode.name), order(encode, remux, context = fourKTv))
    }

    /**
     * 1-ranking D.7 example 5, the torrents' snatches swapped (L302, W72): each source has its own top, so the cached 80 GB
     * torrent remux is held just under the Usenet encode of its standing, the torrents among themselves go by size, where
     * today the more snatched encode goes first, and a torrent joining changes no Usenet release's score.
     */
    @Test
    fun `on a TV each source has its own top, and the torrents among themselves go by size`() {
        val usenetEncode = encode(2_000)
        val usenetRemux = remux(50, gigabytes = 62)
        val torrentRemux = torrent("Copper.Crown.2019.2160p.BluRay.REMUX.HEVC-FERRY", size = 80 * gb, grabs = 300)
        val torrentEncode = torrent("Copper.Crown.2019.2160p.BluRay.x265.DDP5.1-HALYARD", size = 20 * gb, grabs = 1_800)
        val ranked = rank(torrentEncode, usenetEncode, torrentRemux, usenetRemux, context = fourKTv)
        assertEquals(listOf(usenetRemux.id, usenetEncode.id, torrentRemux.id, torrentEncode.id), ranked.map { it.facts.id })
        val scores = ranked.associate { it.facts.id to it.score }
        assertEquals(scores.getValue(usenetEncode.id) - 0.001, scores.getValue(torrentRemux.id), absoluteTolerance = 1e-9)
        val usenetAlone = rank(usenetEncode, usenetRemux, context = fourKTv).associate { it.facts.id to it.score }
        assertEquals(usenetAlone, scores.filterKeys { it in usenetAlone })
    }

    /**
     * C2's list (L304, L333), a 4K TV with no maximum: the 1080p remux, squeezed under the 2160p WEB-DL, goes before the
     * 1080p WEB-DL that fits under it with its whole bonus, where today grabs put the WEB-DL first. The 1080p Proper encode
     * ties the 2160p WEB-DL before bonuses, so each is the other's ceiling with no room: both keep 240.0, and grabs put the
     * encode first, as today.
     */
    @Test
    fun `on a TV a release squeezed under another standing keeps its bitrate's place in its own`() {
        val uhdWeb = film("Copper.Crown.2019.2160p.WEB-DL.DDP5.1.H.265-WATTWURM", 15, 0)
        val properEncode = film("Copper.Crown.2019.PROPER.1080p.BluRay.x264-ORCHID", 12, 9)
        val remux = film("Copper.Crown.2019.1080p.BluRay.REMUX.AVC-FERRY", 30, 20)
        val web = film("Copper.Crown.2019.1080p.WEB-DL.DDP5.1.H.264-TIDE", 6, 2_000)
        val ranked = rank(web, remux, uhdWeb, properEncode, context = fourKTv)
        assertEquals(listOf(properEncode, uhdWeb, remux, web).map { it.name }, ranked.map { it.facts.name })
        val scores = ranked.associate { it.facts.name to it.score }
        assertEquals(240.0, scores.getValue(properEncode.name))
        assertEquals(240.0, scores.getValue(uhdWeb.name))
        assertEquals(240.0 - 0.001, scores.getValue(remux.name))
        assertEquals(238.5, scores.getValue(web.name), absoluteTolerance = 0.05)
    }

    /**
     * L327 and L333, 1-ranking D.5's list (a 4K TV, no maximum): both 1080p releases are squeezed under the 2160p WEB-DL to
     * exactly its score less BELOW, and the remux's whole bonus (276.6 against 246.0) puts it first, where today the
     * WEB-DL's grabs do. A release with no room under one of another standing it tied before bonuses keeps its own score.
     */
    @Test
    fun `on a TV releases of a standing squeezed under one release go by their whole bonuses`() {
        val uhdWeb = film("Copper.Crown.2019.2160p.WEB-DL.DDP5.1.H.265-WATTWURM", 15, 0)
        val remux = film("Copper.Crown.2019.1080p.BluRay.REMUX.AVC-FERRY", 30, 20)
        val web = film("Copper.Crown.2019.1080p.WEB-DL.DDP5.1.H.264-TIDE", 8, 10_000)
        val ranked = rank(web, remux, uhdWeb, context = fourKTv)
        assertEquals(listOf(uhdWeb, remux, web).map { it.name }, ranked.map { it.facts.name })
        assertEquals(listOf(300.0, 240.0 - 0.001, 240.0 - 0.001), ranked.map { it.score })

        val properEncode = film("Copper.Crown.2019.PROPER.1080p.BluRay.x264-ORCHID", 12, 9)
        val withProper = rank(web, remux, uhdWeb, properEncode, context = fourKTv).associate { it.facts.name to it.score }
        assertEquals(240.0, withProper.getValue(uhdWeb.name), "no room under the encode")
        assertEquals(240.0, withProper.getValue(properEncode.name), "no room under the 2160p WEB-DL")
    }

    /**
     * L333: a tie across standings goes by grabs and name, as today, never by the whole bonuses. On a 1080p TV without
     * HDR a 2160p WEB-DL and a 720p WEBRip tie at 200.0 before bonuses, each the other's ceiling; the 720p one's whole bonus
     * is the larger (alone in its standing, it is the top), as a larger 2160p release is the top of the other.
     */
    @Test
    fun `on a TV a tie across standings goes by grabs and name as today, not by the whole bonuses`() {
        val episodes = fullHdTv.copy(runtimeMinutes = 45)
        val larger = release("Harbor.Lights.S01E02.2160p.WEB-DL.DDP5.1.H.265-KESTREL", size = 9 * gb, grabs = 99)
        val uhd = release("Harbor.Lights.S01E02.2160p.WEB-DL.DDP5.1.H.265-ORCHID", size = 4 * gb, grabs = 0)
        val webrip = release("Harbor.Lights.S01E02.720p.WEBRip.x264-TIDE", size = gb, grabs = 0)
        val ranked = rank(webrip, uhd, larger, context = episodes)
        assertEquals(listOf(larger, uhd, webrip).map { it.name }, ranked.map { it.facts.name })
        assertEquals(listOf(200.0, 200.0), ranked.drop(1).map { it.score })
    }

    /**
     * L333, narrowed (RFR-1): a release moves past tied releases of its own standing only. On a 4K TV with no maximum three
     * releases tie at 240.0 before bonuses, each the others' ceiling with no room: grabs put the 1080p WEBRip (999) first,
     * the 2160p WEBRip (99) next and the 1080p BluRay (9) last, the two 1080p releases on both sides of the 2160p one. The
     * BluRay's whole bonus is the larger of its standing (12 GB against 3 GB), but taking the WEBRip's place would lift it
     * over the 2160p release it tied, by its bonus alone: the order stays as today.
     */
    @Test
    fun `on a TV a release passes no tied release of another standing to reach a place of its own standing`() {
        val bluRay = film("Copper.Crown.2019.PROPER.1080p.BluRay.x264-TIDE", 12, 9)
        val uhd = film("Copper.Crown.2019.2160p.WEBRip.x265-KESTREL", 15, 99)
        val webrip = film("Copper.Crown.2019.PROPER.1080p.WEBRip.x264-ORCHID", 3, 999)
        val ranked = rank(bluRay, uhd, webrip, context = fourKTv)
        assertEquals(listOf(webrip, uhd, bluRay).map { it.name }, ranked.map { it.facts.name })
        assertEquals(listOf(240.0, 240.0, 240.0), ranked.map { it.score })
    }

    /**
     * L333 on a phone with Maximum bitrate set: two 1080p releases squeezed under a 720p Proper go by their whole bonuses,
     * the larger first, where today their scores come out the same and grabs put the smaller first. With no maximum there
     * is no bonus, and the list keeps today's order. At 40 Mbps over 45 minutes: 12 GB is ~35.6 Mbps, 6 GB ~17.8.
     */
    @Test
    fun `on a phone with a maximum, releases of a standing squeezed under one release go by bitrate`() {
        val proper720 = release("Harbor.Lights.S01E02.PROPER.720p.WEB-DL.H.264-ORCHID", size = gb, grabs = 9_999)
        val remux = release("Harbor.Lights.S01E02.1080p.BluRay.REMUX.AVC-FERRY", size = 12 * gb, grabs = 0)
        val web = release("Harbor.Lights.S01E02.1080p.WEB-DL.H.264-TIDE", size = 6 * gb, grabs = 99)
        assertEquals(listOf(proper720, remux, web).map { it.name }, order(web, remux, proper720, context = upTo40))
        assertEquals(listOf(proper720, web, remux).map { it.name }, order(web, remux, proper720, context = upTo40.copy(prefs = RankingPrefs())))
    }

    /**
     * L302 with a maximum set on a TV: the bonus is relative to the top that fits, not to the maximum, so a maximum of 150
     * gives the order and scores none gives; a release above it (120 GB over 100 minutes, 160 Mbps) gets no bonus, is tried
     * last, and isn't the top.
     */
    @Test
    fun `on a TV a maximum set gives the order none gives, and a release above it doesn't set the top`() {
        val upTo150 = fourKTv.copy(prefs = RankingPrefs(maxBitrate = 150_000_000))
        val remux = remux(50)
        val encode = encode(2_000)
        val scores = rank(encode, remux, context = fourKTv).map { it.facts.name to it.score }
        assertEquals(listOf(remux.name, encode.name), scores.map { it.first })
        assertEquals(scores, rank(encode, remux, context = upTo150).map { it.facts.name to it.score })

        val above = film("Copper.Crown.2019.2160p.BluRay.REMUX.HEVC-HALYARD", 120, 50)
        val ranked = rank(above, encode, remux, context = upTo150)
        assertEquals(scores, ranked.dropLast(1).map { it.facts.name to it.score })
        assertEquals(above.name, ranked.last().facts.name)
        assertEquals(score(above, upTo150.copy(tv = false)), ranked.last().score)
    }

    /** L305: season packs with no episode count have no estimate, so on a TV no bonus: they keep the order grabs give them. */
    @Test
    fun `on a TV a standing with no estimates keeps today's order`() {
        val remuxPack = release("Harbor.Lights.S01.2160p.BluRay.REMUX.HEVC-KESTREL", size = 300 * gb, grabs = 20)
        val webPack = release("Harbor.Lights.S01.2160p.WEB-DL.DDP5.1.H.265-ORCHID", size = 60 * gb, grabs = 900)
        val packs = fourKTv.copy(runtimeMinutes = 50)
        val ranked = rank(remuxPack, webPack, context = packs)
        assertEquals(listOf(webPack.name, remuxPack.name), ranked.map { it.facts.name })
        assertEquals(rank(remuxPack, webPack, context = packs.copy(tv = false)).map { it.score }, ranked.map { it.score })
    }

    /**
     * L301: TVs only. A phone, and a Cast device (ranked on the phone; [RankingContext.target] guards it even with tv
     * set), keep today's rule: with no maximum no bonus, so grabs and a Proper put the encode first, as the WEB-DL and the
     * BluRay go first with nothing set in the cases of a maximum above.
     */
    @Test
    fun `on a phone or for a Cast device a remux and a smaller encode keep today's order`() {
        listOf(
            remux(50) to encode(2_000),
            remux(5) to encode(10_000),
            remux(50) to encode(2_000, proper = true),
            remux(5) to encode(10_000, proper = true),
        ).forEach { (remux, encode) ->
            listOf("phone" to fourKTv.copy(tv = false), "Cast" to fourKTv.copy(target = RankingTarget.Cast("Den TV"))).forEach { (whom, context) ->
                assertEquals(listOf(encode.name, remux.name), order(remux, encode, context = context), "$whom: remux ${remux.grabs} against ${encode.name} ${encode.grabs}")
            }
        }
    }
}
