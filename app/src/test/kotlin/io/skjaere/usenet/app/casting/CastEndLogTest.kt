package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.link.TvArt
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.serve.ServeCounters
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * L136: the log's one line as a cast play ends, which M2 reads in Settings › Logs: how it ended, how long it ran, what the
 * phone's server sent the device and at what rate, how long the server waited on the providers and on the Wi-Fi to the
 * device, the slow-stream dips, and the maximum when casting. Counts alone: never the title, the device's name or model,
 * an address, a token or a link. The show and the device are invented.
 */
class CastEndLogTest {
    private val launch = PlayerLaunch(
        UsenetUris.build("nzb-hl", 0, "hl.mkv", 2_104_000_000), "Harbor Lights", "S02E04 · Low Water", WatchKey.episode(4_101, 2, 4),
        WatchTarget(WatchKind.Episode, WatchTitle.episode("Harbor Lights", 2, 4), showTmdbId = 4_101, season = 2, episode = 4),
        SavedRelease("fake", "harbor-lights", "Harbor.Lights.S02E04.1080p.WEB-DL.H.264-CAST", null, 0, "nzb-hl", "hl.mkv", 2_104_000_000),
        cast = CastTo("route:kitchen", "Kitchen display"),
    )

    private fun ended(how: CastEnd.How) = CastFeed("load-1", launch, CastPlayerState.Idle, 2_460_000, 3_120_000, end = CastEnd(how, 2_460_000), size = 2_104_000_000)

    /** 41 minutes, 2,104 MB sent: 6.8 Mbps on average; 3.2 s waiting on the providers, 41.4 s on the device. */
    private val counters = ServeCounters(requests = 4, partials = 4, bytesSent = 2_104_000_000, sourceWaitMillis = 3_200, peerWaitMillis = 41_400)

    @Test
    fun `the line says how it ended, what was sent and at what rate, where the time went, the dips and the maximum`() {
        assertEquals(
            "cast: ended (watched) after 41 min: 2104 MB sent, 6.8 Mbps; waits: providers 3 s, Wi-Fi 41 s; dips 1; maximum when casting 40 Mbps",
            castEndLine(ended(CastEnd.How.Watched), counters, elapsedMs = 2_460_000, dips = 1, castMaxMbps = 40),
        )
        assertEquals(
            "cast: ended (stopped) after 0 min: 0 MB sent, 0.0 Mbps; waits: providers 0 s, Wi-Fi 0 s; dips 0; maximum when casting not set",
            castEndLine(ended(CastEnd.How.Stopped), ServeCounters(), elapsedMs = 20_000, dips = 0, castMaxMbps = null),
        )
        val hows = CastEnd.How.entries.map { castEndLine(ended(it), counters, 2_460_000, 0, null).substringAfter("(").substringBefore(")") }
        assertEquals(listOf("stopped", "watched", "long pause", "failed", "replaced"), hows)
    }

    @Test
    fun `nothing in it names the play, the device or where it was served`() {
        val marked = launch.copy(
            uri = UsenetUris.build("nzb-MARKER-TOKEN", 0, "MARKER-FILE.mkv", 2_104_000_000),
            title = "MARKER-TITLE", subtitle = "MARKER-SUBTITLE",
            release = launch.release.copy(title = "MARKER-RELEASE", guid = "MARKER-GUID", fileName = "MARKER-FILE.mkv"),
            cast = CastTo("MARKER-ROUTE", "MARKER-NAME", device = "MARKER-DEVICE", model = "MARKER-MODEL"),
        )
        val feed = ended(CastEnd.How.Watched).copy(launch = marked, art = TvArt("/MARKER-ART.jpg"))
        val line = castEndLine(feed, counters, elapsedMs = 2_460_000, dips = 2, castMaxMbps = 40)
        assertFalse("MARKER" in line, line)
        assertFalse("http" in line || "://" in line, line)
        assertFalse(Regex("""\d+\.\d+\.\d+\.\d+""").containsMatchIn(line), "no address: $line")
    }
}
