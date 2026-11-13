package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.SlowStream
import io.skjaere.usenet.app.play.VideoTiming
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.PlaybackPrefs
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.releases.ReleaseFacts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The slow-stream banner during a cast (W52, W64, L134; CsPhoneSlowCast), as the cast play the bar and the remote follow
 * shows it: from Casting's counts each second, what reached Kitchen display against what the film needs, which part is
 * slow, and the hint by the maximums set; in the missing-parts prompt's slot, after it (W37); Try a smaller release where
 * the play has one, asked of the coordinator from where it plays at the dip's rate; gone as the play ends there. On the
 * test's clock; the film is invented: 52 minutes of 31 Mbps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastPlayingSlowTest {
    private val kitchen = CastTo("route:kitchen", "Kitchen display")
    private val length = 3_120_000L
    private val hash = "c0ffee11c0ffee22c0ffee33c0ffee44c0ffee55"
    private val size = 12_090_000_000L
    private val bitrate = 31_000_000L
    private val release = SavedRelease("fake", "harbor-lights", "Harbor.Lights.S02E04.1080p.WEB-DL.H.264-CAST", null, 0, "nzb-hl", "hl.mkv", size)
    private val launch = PlayerLaunch(
        UsenetUris.build("nzb-hl", 0, release.fileName, release.fileSize), "Harbor Lights", "S02E04 · Low Water", WatchKey.episode(4_101, 2, 4),
        WatchTarget(WatchKind.Episode, WatchTitle.episode("Harbor Lights", 2, 4), showTmdbId = 4_101, season = 2, episode = 4), release,
        video = VideoTiming(fps = 24.0, durationMs = length), cast = kitchen,
    )

    /** The same episode played from a debrid service: a debrid play's launch (W73). */
    private val fromService = launch.copy(
        uri = PlayUris.service("0f3e9a1c5b7d2e4f6a8b0c1d2e3f4a5b", 0),
        release = release.copy(guid = "btih:$hash", nzbId = "btih:$hash", source = ReleaseFacts.TORRENT),
    )

    /** A second of missing articles [atMs] into the film: 3,875 bytes a millisecond. */
    private fun gapAt(atMs: Long) = atMs * 3_875 until (atMs + 1_000) * 3_875

    private inner class Fixture(private val test: TestScope, private val film: PlayerLaunch = launch) {
        val feed = MutableStateFlow<CastFeed?>(null)
        val link = MutableStateFlow<CastLinkNow?>(CastLinkNow(kitchen.route, kitchen.name, CastLink.Connected))

        /** The maximums set, and whether the play has a smaller release to try. */
        var prefs = PlaybackPrefs(castMaxBitrateMbps = 40)
        var smaller = true

        /** What Try a smaller release asked for: where it plays, the rate read and what the file needs. */
        val tried = mutableListOf<Triple<Long, Long, Long>>()
        val playing = CastPlaying(
            test.backgroundScope,
            CastPlayingDeps(
                feed = feed, link = link, pause = {}, resume = {}, seekTo = {}, stop = {}, clock = { test.testScheduler.currentTime },
                canTryAnother = { true }, playbackPrefs = { prefs }, canTrySmaller = { _, _, _ -> smaller },
                trySmaller = { l, at, measured, needs ->
                    assertEquals(film, l)
                    tried += Triple(at, measured, needs)
                },
            ),
        )

        val banner: CastSlowBanner? get() = playing.slow.value

        /** Where the device is, the film sent past it, and what the server counted so far. */
        var position = 1_900_000L
        var aheadMs = 15_000L
        var sent = 0L
        var source = 0L
        var peer = 0L
        var gaps: List<LongRange> = emptyList()
        var load = "load-1"

        /**
         * [seconds] of the device playing as Casting counts each, or standing [buffering]: [rate] reaching it, the answers
         * waiting [sourceMs] and [peerMs] a second (on the providers, by default).
         */
        fun counts(seconds: Int, rate: Long = 19_000_000, sourceMs: Long = 600, peerMs: Long = 50, end: CastEnd? = null, buffering: Boolean = false) {
            repeat(seconds) {
                val played = if (buffering) 0L else 1_000L
                position += played
                aheadMs += rate * 1_000 / bitrate - played
                sent += rate / 8
                source += sourceMs
                peer += peerMs
                val state = if (buffering) CastPlayerState.Buffering else CastPlayerState.Playing
                feed.value = CastFeed(
                    load, film, state, position, length, size = size, gaps = gaps, passed = gaps, end = end, sent = sent,
                    ahead = aheadMs * size / length, sourceWaitMs = source, peerWaitMs = peer, countedAt = test.testScheduler.currentTime,
                )
                test.advanceTimeBy(1_000)
                test.runCurrent()
            }
        }
    }

    /** The Wi-Fi short: the server held in its writes, until the device stands buffering for what can't reach it. */
    @Test
    fun `a slow stream there shows the banner, with which part is slow and the hint by the maximums set`() = runTest {
        val f = Fixture(this)
        f.counts(16, sourceMs = 50, peerMs = 600)
        assertNull(f.banner, "within the first PLAYING's 5 s and the 10 s window, and the device not yet short")
        f.counts(1, sourceMs = 50, peerMs = 600, buffering = true)
        val banner = assertNotNull(f.banner)
        assertEquals("19 of 31 Mbps", banner.shown.numbers)
        assertEquals(
            "The Wi-Fi to Kitchen display is the slow part. Lowering Maximum bitrate when casting to 15 Mbps (Settings › Quality and languages) " +
                "picks smaller releases next time.",
            banner.shown.hint,
        )
        assertTrue(banner.shown.spoken.startsWith("${SlowStream.HEADLINE}. 19 of 31 megabits per second. The Wi-Fi to Kitchen display"))
        assertTrue(banner.trySmaller, "a smaller release to try")
    }

    /** L220 (choice 1): a debrid play's cast slow at its source names the user's debrid service, and no service by name. */
    @Test
    fun `a debrid cast slow at its source says the debrid service is the slow part`() = runTest {
        val f = Fixture(this, fromService)
        f.counts(17)
        val banner = assertNotNull(f.banner)
        assertEquals(
            "Your debrid service is the slow part. Lowering Maximum bitrate when casting to 15 Mbps (Settings › Quality and languages) picks smaller releases next time.",
            banner.shown.hint,
        )
        assertTrue("Your debrid service is the slow part" in banner.shown.spoken, banner.shown.spoken)
        assertFalse("providers" in banner.shown.spoken)
    }

    /** One at a time, the missing-parts prompt first (W37, L134): the banner waits under it, and shows as it goes. */
    @Test
    fun `the missing-parts prompt comes first, the banner after it`() = runTest {
        val f = Fixture(this)
        f.counts(17)
        assertNotNull(f.banner)
        f.gaps = listOf(gapAt(1_950_000), gapAt(2_000_000), gapAt(2_400_000))
        f.counts(1)
        assertNotNull(f.playing.missing.value, "three missing parts ask")
        assertNull(f.banner, "the prompt has the slot")
        f.playing.keepWatching()
        f.counts(1)
        assertEquals("19 of 31 Mbps", f.banner?.shown?.numbers, "the same dip, its numbers as they came")
    }

    @Test
    fun `Try a smaller release asks the coordinator from where it plays at the dip's rate, and the banner goes`() = runTest {
        val f = Fixture(this)
        f.counts(17)
        assertNotNull(f.banner)
        f.playing.trySmaller()
        assertEquals(listOf(Triple(f.position + 1_000, 19_000_000L, bitrate)), f.tried, "where it plays now, a second after the last count")
        assertNull(f.banner)
        f.counts(5)
        assertNull(f.banner, "answered: it shows no more")
        f.playing.trySmaller()
        assertEquals(1, f.tried.size, "nothing more to answer")
    }

    @Test
    fun `with no smaller release the banner has no button, and asks for none`() = runTest {
        val f = Fixture(this)
        f.smaller = false
        f.prefs = PlaybackPrefs()
        f.counts(16, sourceMs = 50, peerMs = 600)
        f.counts(1, sourceMs = 50, peerMs = 600, buffering = true)
        val banner = assertNotNull(f.banner)
        assertFalse(banner.trySmaller)
        assertEquals(
            "The Wi-Fi to Kitchen display is the slow part. Setting Maximum bitrate when casting to 15 Mbps (Settings › Quality and languages) " +
                "picks smaller releases next time.",
            banner.shown.hint,
        )
        f.playing.trySmaller()
        assertTrue(f.tried.isEmpty())
    }

    /** L136: the dips each play had, for its end's log line: its own, and the one before it that the next LOAD took over. */
    @Test
    fun `the dips a play had are counted for its end's line`() = runTest {
        val f = Fixture(this)
        f.counts(17)
        assertEquals(1, f.playing.dips("load-1"))
        assertEquals(0, f.playing.dips("load-2"))
        f.load = "load-2"
        f.counts(3)
        assertEquals(1, f.playing.dips("load-1"), "the one before, as the next LOAD's took over")
        assertEquals(0, f.playing.dips("load-2"))
    }

    @Test
    fun `the banner goes as the play ends there`() = runTest {
        val f = Fixture(this)
        f.counts(17)
        assertEquals(
            "Your providers are the slow part. Lowering Maximum bitrate when casting to 15 Mbps (Settings › Quality and languages) picks smaller releases next time.",
            f.banner?.shown?.hint,
        )
        f.counts(1, end = CastEnd(CastEnd.How.Stopped, f.position))
        assertNull(f.banner)
    }
}
