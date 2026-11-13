package io.skjaere.usenet.app.play

import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.app.casting.CastPrecheck
import io.skjaere.usenet.app.debrid.DebridListingStore
import io.skjaere.usenet.app.debrid.ServiceFiles
import io.skjaere.usenet.app.debrid.ServiceTarget
import io.skjaere.usenet.app.casting.CastProfiles
import io.skjaere.usenet.app.casting.CastVerdict
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.PlaybackTarget
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.core.ProbeResult
import io.skjaere.usenet.core.ProbedTrack
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.core.ProbedVideo
import io.skjaere.usenet.core.TrackProbe
import io.skjaere.usenet.core.UsenetTarget
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceListing
import io.skjaere.usenet.releases.ReleaseFacts
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A cast's probe (L57, L129): a file of a type a Cast device can't play is refused before the phone's own probe reads it;
 * any other is judged by the device's verdict on the tracks the probe read ([CastVerdict]), not by this phone's decoders,
 * whose own verdict no longer applies to a cast. The device, the show and the tracks are invented.
 */
class CastProbeTest {
    /** Where a play would keep what plays: nothing here keeps it. */
    @TempDir
    lateinit var nzbDir: File

    private val probed = mutableListOf<String>()

    private companion object {
        /** An invented listing id, as the listing store makes them. */
        const val LISTING = "0f3e9a1c5b7d2e4f6a8b0c1d2e3f4a5b"
    }
    private val video = ProbedVideo(fps = 23.976, durationMs = 52 * 60_000L, dolbyVision = false)
    private val h264 = ProbedTrack("video/avc", "H.264", width = 1920, height = 1080, bitDepth = 8)
    private val ac3 = ProbedTrack("audio/ac3", "AC-3", "en", channels = 6)
    private val dtsHd = ProbedTrack("audio/vnd.dts.hd", "DTS-HD", "en", channels = 8)
    private val film = ProbedTracks(listOf(h264), listOf(ac3))

    /** This phone's probe: what it reads of each file, by its name, and that it read it. */
    private var inner = TrackProbe { target ->
        probed += target.file.name
        ProbeResult.Playable(video, film)
    }

    private fun target(name: String, wasPlaying: Boolean = false): PlaybackTarget {
        val nzb = PreparedNzb(
            "nzb-1", "Harbor Lights S02E04",
            ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(emptyList())),
            listOf(FileCandidate(0, StreamableFile(name, 1_400_000_000, 0, 0, 0, 0))),
        )
        return UsenetTarget(PlayCandidate("idx/1", "Harbor Lights S02E04", "idx", wasPlaying = wasPlaying), ByteArray(0), nzb, nzb.candidates.single(), PreparedNzbStore(nzbDir))
    }

    /** The verdict of a play to Kitchen display, a first Chromecast, as the play's job asks it. */
    private val kitchen = CastProfiles(emptyList()).of("device-kitchen", "Chromecast")
    private val judged = mutableListOf<ProbedTracks>()
    private val judge = { tracks: ProbedTracks -> judged += tracks; CastVerdict.miss(tracks, kitchen) }

    private fun probe(name: String, judge: (ProbedTracks) -> ProbeResult.CastMiss? = this.judge): ProbeResult = runBlocking { CastProbe(inner, judge).probe(target(name)) }

    /** What a hand pick's question was asked ([asked]: the miss and the tracks), and the answer it gives ([answer]). */
    private val asked = mutableListOf<Pair<ProbeResult.CastMiss, ProbedTracks>>()
    /** Whether this phone plays the file, as each question was told it. */
    private val here = mutableListOf<Boolean>()
    private var answer = CastPrecheck.Button.CastAnyway

    private fun picked(name: String): ProbeResult = runBlocking {
        CastProbe(inner, judge) { _, miss, tracks, plays -> asked += miss to tracks; here += plays; answer }.probe(target(name))
    }

    @Test
    fun `a file a Cast device can't play is refused, and neither the phone's probe nor the verdict reads it`() {
        for ((name, extension) in listOf("Harbor.Lights.S02E04.avi" to "avi", "a.TS" to "ts", "a.m2ts" to "m2ts", "a.wmv" to "wmv", "a.mpg" to "mpg", "a.mpeg" to "mpeg")) {
            assertEquals(ProbeResult.NotForCast(extension), probe(name), name)
        }
        assertEquals(emptyList(), probed)
        assertEquals(emptyList(), judged)
    }

    @Test
    fun `a file a Cast device takes is judged by the device's verdict on its tracks`() {
        assertEquals(ProbeResult.Playable(video, film), probe("Harbor.Lights.S02E04.mkv"))
        assertEquals(listOf(film), judged)
        inner = TrackProbe { ProbeResult.Playable(video, ProbedTracks(listOf(h264), listOf(dtsHd))) }
        assertEquals(ProbeResult.NoCastAudio(listOf("DTS-HD")), probe("dir/Copper.Crown.MP4"))
        inner = TrackProbe { ProbeResult.Playable(video, ProbedTracks(listOf(h264.copy(mime = "video/hevc", label = "HEVC")), listOf(ac3))) }
        assertEquals(ProbeResult.NoCastVideo("HEVC"), probe("a.mkv"))
        inner = TrackProbe { ProbeResult.Playable(video, ProbedTracks(listOf(h264.copy(width = 3840, height = 2160)), listOf(ac3))) }
        assertEquals(ProbeResult.TooBigForCast(2160, 1080), probe("a.webm"))
    }

    /**
     * This phone's decoders are no Cast device's: a file whose audio or video this phone can't play may cast, its device's
     * verdict deciding (L129). What the probe read of its video goes with it, as with any playable file.
     */
    @Test
    fun `this phone's own verdict doesn't fail a cast whose verdict passes`() {
        val silentHere = ProbedTracks(listOf(h264), listOf(ProbedTrack("audio/eac3", "E-AC-3", "en", channels = 6)))
        inner = TrackProbe { ProbeResult.NoPlayableAudio(listOf("E-AC-3 (en)"), silentHere, video) }
        assertEquals(ProbeResult.Playable(video, silentHere), probe("a.mkv"))
        val darkHere = ProbedTracks(listOf(h264.copy(bitDepth = 10, mime = "video/hevc", label = "HEVC")), listOf(ac3))
        inner = TrackProbe { ProbeResult.NoPlayableVideo(listOf("1080p HEVC 10-bit"), darkHere, video) }
        val ultra = CastProfiles(emptyList()).of("device-den", "Chromecast Ultra")
        assertEquals(ProbeResult.Playable(video, darkHere), probe("a.mkv") { CastVerdict.miss(it, ultra) })
        // Kitchen display shows no HEVC: the device's verdict says so.
        assertEquals(ProbeResult.NoCastVideo("HEVC 10-bit"), probe("a.mkv"))
        // A probe that read no tracks can't be judged: its own verdict stands.
        inner = TrackProbe { ProbeResult.NoPlayableAudio(listOf("Opus (en)")) }
        assertEquals(ProbeResult.NoPlayableAudio(listOf("Opus (en)")), probe("a.webm"))
        inner = TrackProbe { ProbeResult.Playable(video) }
        assertEquals(ProbeResult.Playable(video), probe("a.mkv"))
        // A failure to read the file is the probe's, as before.
        val failed = ProbeResult.Failed(io.skjaere.usenet.core.UsenetError.Timeout("No track list within 20 s"))
        inner = TrackProbe { failed }
        assertEquals(failed, probe("a.mkv"))
    }

    /**
     * W3, L84 (CFR-2): the way back, after the others failed, to the release still playing on the device (Cast anyway's
     * too): it plays there already, so neither the verdict nor the question holds it back, and nothing is sent again.
     */
    @Test
    fun `the release still playing there is gone back to whatever the verdict, and nothing is asked`() {
        val silent = ProbedTracks(listOf(h264), listOf(dtsHd))
        inner = TrackProbe { ProbeResult.NoPlayableAudio(listOf("DTS-HD (en)"), silent, video) }
        answer = CastPrecheck.Button.Cancel
        val asking = CastProbe(inner, judge) { _, miss, tracks, _ -> asked += miss to tracks; answer }
        assertEquals(ProbeResult.Playable(video, silent), runBlocking { asking.probe(target("a.mkv", wasPlaying = true)) })
        assertEquals(ProbeResult.Playable(video, silent), runBlocking { CastProbe(inner, judge).probe(target("a.mkv", wasPlaying = true)) })
        assertEquals(emptyList(), asked)
        assertEquals(emptyList(), judged)
        // The same file not playing there fails the verdict.
        assertEquals(ProbeResult.NoCastAudio(listOf("DTS-HD")), probe("a.mkv"))
    }

    /** Cast anyway (L130, L149) judges nothing: the device is sent the file whatever it may not play, but never a type it can't open. */
    @Test
    fun `Cast anyway's probe sends the file whatever the verdict, but still refuses the type`() {
        inner = TrackProbe { ProbeResult.NoPlayableAudio(listOf("DTS-HD (en)"), ProbedTracks(listOf(h264), listOf(dtsHd)), video) }
        assertEquals(ProbeResult.Playable(video, ProbedTracks(listOf(h264), listOf(dtsHd))), probe("a.mkv") { null })
        assertEquals(ProbeResult.NotForCast("avi"), probe("a.avi") { null })
    }

    /**
     * L130: a release picked by hand that the device can't fully play asks first, the play waiting on the answer. Cast
     * anyway sends the device the file all the same; Play on this phone has this phone's own verdict on it, as for a play
     * here; Play a compatible release fails it as the verdict says, the play going on with other releases. Nothing is asked
     * of a file the device plays, nor of a type it can't open.
     */
    @Test
    fun `a release picked by hand asks before casting, and goes as it is answered`() {
        val silent = ProbedTracks(listOf(h264), listOf(dtsHd))
        inner = TrackProbe { ProbeResult.NoPlayableAudio(listOf("DTS-HD (en)"), silent, video) }
        answer = CastPrecheck.Button.CastAnyway
        assertEquals(ProbeResult.Playable(video, silent), picked("Lantern.Field.mkv"))
        assertEquals(listOf<Pair<ProbeResult.CastMiss, ProbedTracks>>(ProbeResult.NoCastAudio(listOf("DTS-HD")) to silent), asked)
        // This phone can't play its sound either: its own verdict stands, as for any play here.
        answer = CastPrecheck.Button.PlayHere
        assertEquals(ProbeResult.NoPlayableAudio(listOf("DTS-HD (en)"), silent, video), picked("Lantern.Field.mkv"))
        val dark = ProbedTracks(listOf(h264.copy(mime = "video/hevc", label = "HEVC")), listOf(ac3))
        inner = TrackProbe { ProbeResult.Playable(video, dark) }
        assertEquals(ProbeResult.Playable(video, dark), picked("Lantern.Field.mkv"))
        answer = CastPrecheck.Button.Compatible
        assertEquals(ProbeResult.NoCastVideo("HEVC"), picked("Lantern.Field.mkv"))
        answer = CastPrecheck.Button.Cancel
        assertEquals(ProbeResult.NoCastVideo("HEVC"), picked("Lantern.Field.mkv"))
        assertEquals(5, asked.size)
        // Nothing to ask of what it plays, or of a type it can't open.
        inner = TrackProbe { ProbeResult.Playable(video, film) }
        assertEquals(ProbeResult.Playable(video, film), picked("Lantern.Field.mkv"))
        assertEquals(ProbeResult.NotForCast("avi"), picked("Lantern.Field.avi"))
        assertEquals(5, asked.size)
        // CFU-1: each question hears whether this phone plays the file, by its own probe: the DTS file not, the HEVC one so.
        assertEquals(listOf(false, false, true, true, true), here)
    }

    /** A file a debrid service lists, as a debrid play's attempt names it (W73): its listing kept as [LISTING]. */
    private fun serviceTarget(name: String): ServiceTarget {
        val hash = InfoHash.parse("c0ffee11c0ffee22c0ffee33c0ffee44c0ffee55")!!
        val listing = ServiceListing(listOf(ServiceFile(0, "Harbor.Lights.S02/$name", 1_400_000_000, id = "7")))
        val files = ServiceFiles(hash, "Harbor.Lights.S02.1080p.WEB-DL.H.264-TIDE", listing)
        val candidate = PlayCandidate("btih:${hash.hex}", files.title, "prowlarr", source = ReleaseFacts.TORRENT, routes = listOf("service:tb"))
        return ServiceTarget(candidate, files, files.files.single(), LISTING, "tb", DebridListingStore(File(nzbDir, "listings.json")))
    }

    /**
     * L202 lifted (W73): a debrid play's cast runs the same pre-check as a Usenet play's: its type refused by the name before
     * a byte is read, and the device's verdict on the tracks this phone's probe read through the service (its target's URI
     * the listing's, never a link).
     */
    @Test
    fun `a file from a debrid service probes for a cast as any`() {
        assertEquals(ProbeResult.NotForCast("avi"), runBlocking { CastProbe(inner, judge).probe(serviceTarget("Harbor.Lights.S02E04.avi")) })
        assertEquals(emptyList(), probed)
        val uris = mutableListOf<String>()
        inner = TrackProbe { target ->
            uris += target.uri
            ProbeResult.Playable(video, ProbedTracks(listOf(h264), listOf(dtsHd)))
        }
        assertEquals(ProbeResult.NoCastAudio(listOf("DTS-HD")), runBlocking { CastProbe(inner, judge).probe(serviceTarget("Harbor.Lights.S02E04.mkv")) })
        inner = TrackProbe { target ->
            uris += target.uri
            ProbeResult.Playable(video, film)
        }
        assertEquals(ProbeResult.Playable(video, film), runBlocking { CastProbe(inner, judge).probe(serviceTarget("Harbor.Lights.S02E04.mkv")) })
        assertEquals(listOf(film.copy(audio = listOf(dtsHd)), film), judged)
        assertEquals(List(2) { "debrid://$LISTING/file/0" }, uris)
    }

    /**
     * play_pipeline logs each failure's text, so the verdict's kinds and their failures name no device (L129): for a play to
     * a device named with a marker, neither holds it; the sheet's notice alone names it.
     */
    @Test
    fun `nothing the verdict fails says the device's name, but the notice`() {
        val marker = "Marker-7f3k9q"
        val to = CastTo("route:$marker", marker, device = "device-$marker", model = "Chromecast")
        val profile = CastProfiles(emptyList()).of(to.device, to.model)
        val files = listOf(
            ProbedTracks(listOf(h264), listOf(dtsHd)),
            ProbedTracks(listOf(h264.copy(mime = "video/wvc1", label = "VC-1")), listOf(ac3)),
            ProbedTracks(listOf(h264.copy(width = 3840, height = 2160)), listOf(ac3)),
        )
        val misses = files.map { tracks ->
            inner = TrackProbe { ProbeResult.Playable(video, tracks) }
            assertIs<ProbeResult.CastMiss>(probe("Harbor.Lights.S02E04.mkv") { CastVerdict.miss(it, profile) })
        }
        assertEquals(3, misses.map { it::class }.distinct().size)
        misses.forEach { miss ->
            listOf("$miss", miss.failure.text, "${miss.failure}").forEach { assertFalse(marker in it, it) }
            assertTrue(marker in shortFailure(miss.failure, to), shortFailure(miss.failure, to))
        }
    }
}
