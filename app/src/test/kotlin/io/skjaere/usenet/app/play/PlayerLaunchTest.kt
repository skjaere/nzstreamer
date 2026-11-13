package io.skjaere.usenet.app.play

import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.core.ProbedTrack
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.core.ProbedVideo
import io.skjaere.usenet.core.UsenetTarget
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.releases.Hdr
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The launch is kept in the player's intent, which outlives the process: it must decode across versions. */
class PlayerLaunchTest {
    /** Where a play would keep its NZB: nothing here keeps one. */
    @TempDir
    lateinit var nzbDir: File

    @Test
    fun `a launch saved before the retry fields decodes without them`() {
        val saved = """{"uri":"usenet://nzb/abc/0","title":"Harbor Lights","subtitle":"S02E05 · The Long Tide","watchKey":"episode:900002:2:5",""" +
            """"watch":{"kind":"Episode","title":"Harbor Lights S02E05","showTmdbId":900002,"season":2,"episode":5},""" +
            """"release":{"indexerId":"fake","guid":"hl-s02e05","title":"Harbor.Lights.S02E05.1080p.WEB.h264-GRP","downloadTemplate":null,"fileIndex":0,"nzbId":"abc"}}"""
        val launch = PlayerLaunch.decode(saved)
        assertEquals(WatchKind.Episode, launch.watch.kind)
        assertEquals("hl-s02e05", launch.release.guid)
        // Nor the file's name: it opens and is remembered by its position, as before (#179).
        assertNull(launch.release.fileName)
        assertNull(launch.release.toRemembered().fileName)
        assertNull(launch.requestId)
        assertNull(launch.startPositionMs)
        assertEquals(emptyList(), launch.replaces)
        // Nor the video's facts: the TV's frame-rate match leaves the display's mode as it is.
        assertNull(launch.video)
        // Nor a Cast device: it plays here.
        assertNull(launch.cast)
        // Nor the file's tracks.
        assertNull(launch.tracks)
    }

    /**
     * L181: where the release played from goes with the launch, and with what Resume remembers of it; none is Usenet, so
     * a launch kept before it was reads as a Usenet play's. Nothing plays from another source yet.
     */
    @Test
    fun `a launch from before has no source, and one with it keeps it`() {
        val saved = """{"uri":"usenet://nzb/abc/0","title":"Harbor Lights","subtitle":"S02E05 · The Long Tide","watchKey":"episode:900002:2:5",""" +
            """"watch":{"kind":"Episode","title":"Harbor Lights S02E05","showTmdbId":900002,"season":2,"episode":5},""" +
            """"release":{"indexerId":"fake","guid":"hl-s02e05","title":"Harbor.Lights.S02E05.1080p.WEB.h264-GRP","downloadTemplate":null,"fileIndex":0,""" +
            """"nzbId":"abc","fileName":"Harbor.Lights.S02E05.1080p.mkv","fileSize":1400000000}}"""
        val before = PlayerLaunch.decode(saved).release
        assertNull(before.source)
        assertNull(before.toRemembered().source)
        assertEquals(RememberedRelease("fake", "hl-s02e05", before.title, null, 0, "abc", "Harbor.Lights.S02E05.1080p.mkv", 1_400_000_000), before.toRemembered())
        val watch = WatchTarget(WatchKind.Episode, "Harbor Lights S02E05", showTmdbId = 900002, season = 2, episode = 5)
        val request = PlayRequest("Harbor Lights", "S02E05", emptyList(), watch = watch)
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val release = SavedRelease("home-prowlarr", "btih:$hash", "Harbor.Lights.S02E05.1080p.WEB.h264-GRP", null, 1, "btih:$hash", "Harbor.Lights.S02E05.1080p.mkv", 1_400_000_000, source = "torrent")
        val launch = launchOf(request, release, PlayUris.service("l7f3a9", 1), "play-1", retry = null)
        assertEquals(launch, PlayerLaunch.decode(launch.encode()))
        assertEquals("torrent", PlayerLaunch.decode(launch.encode()).release.source)
        assertEquals("torrent", launch.release.toRemembered().source)
    }

    /**
     * L129: what the probe read of the file's tracks goes with the launch, for Play & connect's verdict in the player, also
     * after the intent outlived the process: each kind in the file's order, with every fact the probe gives.
     */
    @Test
    fun `a launch carries the file's tracks the probe read`() {
        val watch = WatchTarget(WatchKind.Episode, "Harbor Lights S02E05", showTmdbId = 900002, season = 2, episode = 5)
        val request = PlayRequest("Harbor Lights", "S02E05", emptyList(), watch = watch)
        val release = SavedRelease("fake", "hl-s02e05", "Harbor.Lights.S02E05.2160p.WEB-DL.DV.H.265-GRP", null, 0, "abc")
        val tracks = ProbedTracks(
            video = listOf(ProbedTrack("video/dolby-vision", "Dolby Vision", width = 3840, height = 2160, bitDepth = 10, hdr = Hdr.DolbyVision, dolbyVisionProfile = 8)),
            audio = listOf(ProbedTrack("audio/vnd.dts.hd", "DTS-HD", "de", channels = 8), ProbedTrack("audio/ac3", "AC-3", "en", default = true, channels = 6)),
            text = listOf(ProbedTrack("application/x-subrip", "SRT", "en", default = true, forced = true), ProbedTrack(null, "unknown")),
        )
        val launch = usenetLaunch(request, release, "play-1", retry = null, tracks = tracks)
        assertEquals(tracks, launch.tracks)
        assertEquals(launch, PlayerLaunch.decode(launch.encode()))
        assertNull(usenetLaunch(request, release, "play-2", retry = null).tracks)
    }

    /** A launch a newer version kept may name an HDR format this one doesn't know: that track reads with none, the rest as it was. */
    @Test
    fun `a track's HDR this version doesn't know reads as none`() {
        val saved = """{"uri":"usenet://nzb/abc/0","title":"Copper Crown","subtitle":null,"watchKey":"movie:900010","watch":{"kind":"Movie","title":"Copper Crown","movieTmdbId":900010},""" +
            """"release":{"indexerId":"fake","guid":"cc","title":"Copper.Crown.2024.2160p","downloadTemplate":null,"fileIndex":0,"nzbId":"abc"},""" +
            """"tracks":{"video":[{"mime":"video/hevc","label":"HEVC","width":3840,"height":2160,"bitDepth":10,"hdr":"Hdr12"}],"audio":[{"mime":"audio/eac3","label":"E-AC-3","language":"en","channels":6,"atmos":true}]}}"""
        val tracks = PlayerLaunch.decode(saved).tracks
        assertEquals(
            ProbedTracks(video = listOf(ProbedTrack("video/hevc", "HEVC", width = 3840, height = 2160, bitDepth = 10)), audio = listOf(ProbedTrack("audio/eac3", "E-AC-3", "en", channels = 6))),
            tracks,
        )
    }

    /** A cast's launch keeps its device (L81), so a retry from its intent's copy stays there; its name prints nowhere. */
    @Test
    fun `a launch keeps the Cast device it plays on`() {
        val watch = WatchTarget(WatchKind.Episode, "Harbor Lights S02E05", showTmdbId = 900002, season = 2, episode = 5)
        val request = PlayRequest("Harbor Lights", "S02E05", emptyList(), watch = watch)
        val release = SavedRelease("fake", "hl-s02e05", "Harbor.Lights.S02E05.1080p.WEB.h264-GRP", null, 0, "abc")
        val launch = usenetLaunch(request, release, "play-1", retry = null, origin = PlayOrigin(cast = CastTo("route:kitchen", "Kitchen display")))
        assertEquals(launch, PlayerLaunch.decode(launch.encode()))
        assertEquals(CastTo("route:kitchen", "Kitchen display"), PlayerLaunch.decode(launch.encode()).cast)
    }

    /** L153: the device's Cast id and model go with a cast's launch, so its job finds the device's profile after a retry too. */
    @Test
    fun `a cast's launch keeps the device's id and model`() {
        val watch = WatchTarget(WatchKind.Episode, "Harbor Lights S02E05", showTmdbId = 900002, season = 2, episode = 5)
        val request = PlayRequest("Harbor Lights", "S02E05", emptyList(), watch = watch)
        val release = SavedRelease("fake", "hl-s02e05", "Harbor.Lights.S02E05.1080p.WEB.h264-GRP", null, 0, "abc")
        val kitchen = CastTo("route:kitchen", "Kitchen display", device = "device-kitchen", model = "Chromecast Ultra")
        val launch = usenetLaunch(request, release, "play-1", retry = null, origin = PlayOrigin(cast = kitchen))
        assertEquals(kitchen, PlayerLaunch.decode(launch.encode()).cast)
        assertEquals(launch, PlayerLaunch.decode(launch.encode()))
    }

    /** A cast's launch kept before L153 (the intent outlives the process) decodes with no id or model: its device is unknown. */
    @Test
    fun `a cast's launch from before the device's facts decodes without them`() {
        val saved = """{"uri":"usenet://nzb/abc/0","title":"Harbor Lights","subtitle":"S02E05 · The Long Tide","watchKey":"episode:900002:2:5",""" +
            """"watch":{"kind":"Episode","title":"Harbor Lights S02E05","showTmdbId":900002,"season":2,"episode":5},""" +
            """"release":{"indexerId":"fake","guid":"hl-s02e05","title":"Harbor.Lights.S02E05.1080p.WEB.h264-GRP","downloadTemplate":null,"fileIndex":0,"nzbId":"abc"},""" +
            """"cast":{"route":"route:kitchen","name":"Kitchen display"}}"""
        val cast = PlayerLaunch.decode(saved).cast
        assertEquals(CastTo("route:kitchen", "Kitchen display"), cast)
        assertNull(cast?.device)
        assertNull(cast?.model)
    }

    /**
     * L147: a file Import opened plays, here or cast, with a launch of its own: named as its NZB is, its release Import's
     * (the NZB's id in place of an indexer's guid), its watch the release itself, the file by name, and [PlayerLaunch.imported]
     * kept through the intent, so its progress is never saved. A launch from before reads as no import.
     */
    @Test
    fun `an imported file's launch says so, and one from before isn't`() {
        val file = FileCandidate(2, StreamableFile("Copper.Crown.2026.1080p.mkv", 1_400_000_000, 0, 0, 0, 0))
        val request = importRequest("Copper.Crown.2026.1080p.WEB-DL")
        assertTrue(request.imported)
        assertEquals(WatchTarget(WatchKind.Release, "Copper.Crown.2026.1080p.WEB-DL"), request.watch)
        val release = importedRelease("c0ffee42", file, "Copper.Crown.2026.1080p.WEB-DL")
        assertEquals(SavedRelease(IMPORTED, "c0ffee42", "Copper.Crown.2026.1080p.WEB-DL", null, 2, "c0ffee42", file.name, file.size), release)
        // Played here (Play on this phone after a cast that sent nothing), the coordinator's own launch of it: its URI the
        // file of the NZB Import keeps.
        val launch = importedLaunch("c0ffee42", file, request, "play-1")
        assertTrue(launch.imported)
        assertEquals(release, launch.release)
        assertEquals(WatchKey.release("c0ffee42", 2), launch.watchKey)
        assertEquals(UsenetUris.Target("c0ffee42", 2, file.name, file.size), UsenetUris.parse(launch.uri))
        assertNull(launch.cast)
        assertEquals(launch, PlayerLaunch.decode(launch.encode()))
        // Cast, its launch keeps the device and the import through the intent too.
        val cast = launch.copy(cast = CastTo("route:kitchen", "Kitchen display"))
        assertEquals(cast, PlayerLaunch.decode(cast.encode()))
        // Any other play's launch, and one kept before imports could cast, is none.
        val watch = WatchTarget(WatchKind.Episode, "Harbor Lights S02E05", showTmdbId = 900002, season = 2, episode = 5)
        assertFalse(usenetLaunch(PlayRequest("Harbor Lights", "S02E05", emptyList(), watch = watch), release, "play-2", retry = null).imported)
        val saved = """{"uri":"usenet://nzb/abc/0","title":"Copper Crown","subtitle":null,"watchKey":"movie:900010","watch":{"kind":"Movie","title":"Copper Crown","movieTmdbId":900010},""" +
            """"release":{"indexerId":"fake","guid":"cc","title":"Copper.Crown.2024.1080p","downloadTemplate":null,"fileIndex":0,"nzbId":"abc"}}"""
        assertFalse(PlayerLaunch.decode(saved).imported)
    }

    @Test
    fun `a launch keeps its request and start position`() {
        val saved = PlayerLaunch.decode(
            """{"uri":"usenet://nzb/abc/0","title":"Copper Crown","subtitle":null,"watchKey":"movie:900010","watch":{"kind":"Movie","title":"Copper Crown","movieTmdbId":900010},""" +
                """"release":{"indexerId":"fake","guid":"cc","title":"Copper.Crown.2024.1080p","downloadTemplate":null,"fileIndex":0,"nzbId":"abc"}}""",
        )
        val launch = saved.copy(requestId = "play-1", startPositionMs = 1_234_000, replaces = listOf("release:def:0"))
        assertEquals(launch, PlayerLaunch.decode(launch.encode()))
    }

    /**
     * The launch names the file that plays, in its URI and in the release it remembers: the player
     * opens it by name once the NZB is prepared again, and Resume finds it so, wherever the release
     * lists it then (#179). The URI is the one the play's target gives ([io.skjaere.usenet.core.PlaybackTarget.uri]).
     */
    @Test
    fun `a launch names the file that plays, in its URI and in the release it remembers`() {
        val watch = WatchTarget(WatchKind.Episode, "Harbor Lights S02E05", showTmdbId = 900002, season = 2, episode = 5)
        val request = PlayRequest("Harbor Lights", "S02E05", emptyList(), watch = watch)
        val name = "Harbor Lights S02E05 & 100% more+.mkv"
        val episodes = (1..4).map { "Harbor Lights S02E0$it.mkv" to 1_000_000_000L + it } + (name to 1_000_005_000L)
        val prepared = PreparedNzb(
            "abc123", "Harbor.Lights.S02.1080p.WEB.h264-GRP",
            ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(emptyList())),
            episodes.mapIndexed { i, (file, size) -> FileCandidate(i, StreamableFile(file, size, i, 0, 0, 0)) },
        )
        val target = UsenetTarget(PlayCandidate("fake/hl-s02-pack", prepared.title, "fake"), ByteArray(0), prepared, prepared.candidates[4], PreparedNzbStore(nzbDir))
        val release = savedRelease(Replay.candidate(RememberedRelease("fake", "hl-s02-pack", prepared.title, null, 0)).item, target)
        val launch = launchOf(request, release, target.uri, "play-1", retry = null)
        assertEquals(UsenetUris.Target("abc123", 4, name, 1_000_005_000), UsenetUris.parse(launch.uri))
        assertEquals(RememberedRelease("fake", "hl-s02-pack", release.title, null, 4, "abc123", name, 1_000_005_000), launch.release.toRemembered())
        assertEquals(launch, PlayerLaunch.decode(launch.encode()))
    }

    /**
     * A launch plays the URI its target gave, whatever its release says: another source's names its own listing and the
     * file's place there, and is kept as it is through the intent.
     */
    @Test
    fun `a launch plays its target's URI`() {
        val watch = WatchTarget(WatchKind.Episode, "Harbor Lights S02E05", showTmdbId = 900002, season = 2, episode = 5)
        val request = PlayRequest("Harbor Lights", "S02E05", emptyList(), watch = watch)
        val release = SavedRelease("fake", "hl-s02e05", "Harbor.Lights.S02E05.1080p.WEB.h264-GRP", null, 1, "l7f3a9", "Harbor.Lights.S02E05.1080p.mkv", 1_400_000_000)
        val launch = launchOf(request, release, PlayUris.service("l7f3a9", 1), "play-1", retry = null)
        assertEquals("debrid://l7f3a9/file/1", launch.uri)
        assertEquals(launch, PlayerLaunch.decode(launch.encode()))
        // The rest as any play's.
        assertEquals(WatchKey.episode(900002, 2, 5), launch.watchKey)
        assertEquals(release, launch.release)
    }

    /**
     * What the track probe read of the video (its frame rate, length and Dolby Vision) goes with the launch, so the TV's
     * frame-rate match plans before the player exists, also after the intent outlived the process.
     */
    @Test
    fun `a launch carries what the track probe read of the video`() {
        val watch = WatchTarget(WatchKind.Episode, "Harbor Lights S02E05", showTmdbId = 900002, season = 2, episode = 5)
        val request = PlayRequest("Harbor Lights", "S02E05", emptyList(), watch = watch)
        val release = SavedRelease("fake", "hl-s02e05", "Harbor.Lights.S02E05.1080p.WEB.h264-GRP", null, 0, "abc")
        val launch = usenetLaunch(request, release, "play-1", retry = null, video = ProbedVideo(24_000.0 / 1001, 2_640_000, dolbyVision = true))
        assertEquals(VideoTiming(24_000.0 / 1001, 2_640_000, dolbyVision = true), launch.video)
        assertEquals(launch, PlayerLaunch.decode(launch.encode()))
        assertNull(usenetLaunch(request, release, "play-2", retry = null).video)
        // A rate that isn't a number stays out: the launch's JSON can't hold one, and the intent must still encode.
        val odd = usenetLaunch(request, release, "play-3", retry = null, video = ProbedVideo(Double.NaN, durationMs = null, dolbyVision = false))
        assertEquals(VideoTiming(fps = null, durationMs = null, dolbyVision = false), odd.video)
        assertEquals(odd, PlayerLaunch.decode(odd.encode()))
    }
}
