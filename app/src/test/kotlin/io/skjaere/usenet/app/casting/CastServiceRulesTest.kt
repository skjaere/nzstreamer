package io.skjaere.usenet.app.casting

import androidx.media3.common.MediaMetadata
import io.skjaere.usenet.app.casting.CastServiceRules.Ending
import io.skjaere.usenet.app.casting.CastServiceRules.Why
import io.skjaere.usenet.app.play.SessionMetadata
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import org.junit.jupiter.api.Test
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cast's service (W27, L58, L59, L63, L74, L85), as its rules decide it: when it runs and holds the foreground, what its
 * session says while the play starts, what ends a cast play and what else that end does, the phone's address as it goes,
 * and the wake lock while the server writes. The show, the device and the addresses are invented.
 */
class CastServiceRulesTest {
    private val episode = WatchTarget(WatchKind.Episode, "Harbor Lights S02E04", showTmdbId = 900002, season = 2, episode = 4)
    private val home = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 23))
    private val other = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 7))

    @Test
    fun `the foreground is held while Media3 asks for it or while a cast play is served`() {
        assertTrue(CastServiceRules.foreground(required = true, serving = false))
        // Paused past Media3's 10 minutes: it lets the foreground go, the service keeps it (K1).
        assertTrue(CastServiceRules.foreground(required = false, serving = true))
        assertFalse(CastServiceRules.foreground(required = false, serving = false))
    }

    @Test
    fun `the session is built only for a cast play this process started, never on a TV`() {
        assertTrue(CastServiceRules.builds(television = false, playing = true))
        // A sticky restart, another app's controller binding, a media button: nothing is built, and it stops.
        assertFalse(CastServiceRules.builds(television = false, playing = false))
        assertFalse(CastServiceRules.builds(television = true, playing = true))
        assertFalse(CastServiceRules.builds(television = true, playing = false))
    }

    /**
     * As the device's item will say it once it has the play (L86, L89, L120; CsPhoneNotificationC2): the show over its
     * episode's line, which names the device it plays on, the play's CastTo.name.
     */
    @Test
    fun `while a play starts the session says it as the play will be, with no address`() {
        val said = SessionMetadata.starting("Harbor Lights", "S02E04 · Low Water", episode, "NZStreamer")
        val item = CastServiceRules.placeholder(said, "Kitchen display", sdkInt = 34)
        assertNull(item.localConfiguration, "the placeholder has no URI")
        assertEquals(WatchKey.episode(900002, 2, 4), item.mediaId)
        assertEquals("Harbor Lights", item.mediaMetadata.title?.toString())
        assertEquals("S02E04 · Low Water · on Kitchen display", item.mediaMetadata.subtitle?.toString())
        // The line under the title on Android's notification, media card and lock screen (CsPhoneNotificationC2).
        assertEquals("S02E04 · Low Water · on Kitchen display", item.mediaMetadata.artist?.toString())
        assertEquals(MediaMetadata.MEDIA_TYPE_TV_SHOW, item.mediaMetadata.mediaType)
        // Up to Android 10 the device comes first (W62).
        assertEquals("On Kitchen display · S02E04 · Low Water", CastServiceRules.placeholder(said, "Kitchen display", sdkInt = 29).mediaMetadata.artist?.toString())
    }

    @Test
    fun `a play without TMDB starts as the app's name alone, never its release's`() {
        val raw = "Harbor.Lights.S02E04.1080p.WEB.h264-CAST"
        val said = SessionMetadata.starting(raw, null, WatchTarget(WatchKind.Release, raw), "NZStreamer")
        val item = CastServiceRules.placeholder(said, "Kitchen display", sdkInt = 34)
        assertEquals("NZStreamer", item.mediaMetadata.title?.toString())
        // The device alone under the app's name (L120).
        assertEquals("on Kitchen display", item.mediaMetadata.subtitle?.toString())
        assertEquals("on Kitchen display", item.mediaMetadata.artist?.toString())
        assertEquals(SessionMetadata.NO_KEY, item.mediaId)
        assertFalse(listOfNotNull(item.mediaId, item.mediaMetadata.title, item.mediaMetadata.subtitle).any { "Harbor" in it || "CAST" in it })
    }

    /**
     * L85: Android ends an app whose service it started for the foreground stops before it gets there. Another app may
     * start the exported service for the foreground with any action or none, and there is nothing to show: it passes
     * through the foreground before it stops.
     */
    @Test
    fun `a start with nothing to show passes through the foreground before the service stops`() {
        val due = ForegroundOwed().apply { created(); started(inForeground = false) }
        assertTrue(due.stopping(inForeground = false))
        assertFalse(due.stopping(inForeground = false), "once")
    }

    @Test
    fun `a play Media3 took into the foreground owes nothing as it stops`() {
        val due = ForegroundOwed().apply { created(); started(inForeground = false) }
        // Media3's notification, and its own start of the service for it, in the foreground already.
        due.seen(inForeground = true)
        due.started(inForeground = true)
        // The play over: Media3 has let the foreground go with the notification.
        assertFalse(due.stopping(inForeground = false))
        // Still in the foreground as it stops: nothing to pass through either.
        val held = ForegroundOwed().apply { created(); started(inForeground = false) }
        assertFalse(held.stopping(inForeground = true))
    }

    /**
     * An instance a controller kept bound across two plays: Media3 had it in the foreground for the first, and says so for
     * good. The second play's start, ended before Media3's notification, still owes the foreground.
     */
    @Test
    fun `a later start owes the foreground again, though the service was there for an earlier play`() {
        val due = ForegroundOwed().apply { created(); started(inForeground = false); seen(inForeground = true) }
        assertFalse(due.stopping(inForeground = false))
        // The next play's start, out of the foreground; it ends before Media3 posts anything.
        due.started(inForeground = false)
        due.seen(inForeground = false)
        assertTrue(due.stopping(inForeground = false))
    }

    /** Made by a start not yet delivered: a play that ends before it passes through the foreground too. */
    @Test
    fun `a service made for a start owes the foreground before the start is delivered`() {
        assertTrue(ForegroundOwed().apply { created() }.stopping(inForeground = false))
        assertFalse(ForegroundOwed().stopping(inForeground = false), "nothing started it")
    }

    @Test
    fun `the device's player takes over once its item is the one this play sent`() {
        assertFalse(CastServiceRules.handsOver(sent = null, current = null), "nothing sent yet")
        // A device taken over still has the other sender's item until ours loads (W32).
        assertFalse(CastServiceRules.handsOver(sent = null, current = "theirs"))
        assertFalse(CastServiceRules.handsOver(sent = "ours", current = "theirs"))
        assertFalse(CastServiceRules.handsOver(sent = "ours", current = null))
        assertTrue(CastServiceRules.handsOver(sent = "ours", current = "ours"))
    }

    @Test
    fun `a swipe-away stops the cast only while it is paused`() {
        assertTrue(CastServiceRules.stopsOnTaskRemoved(starting = false, playWhenReady = false))
        // Playing, loading or buffering on the device.
        assertFalse(CastServiceRules.stopsOnTaskRemoved(starting = false, playWhenReady = true))
        // The pipeline runs, or a device taken over still shows the other sender's paused media (W32).
        assertFalse(CastServiceRules.stopsOnTaskRemoved(starting = true, playWhenReady = false))
        assertFalse(CastServiceRules.stopsOnTaskRemoved(starting = true, playWhenReady = true))
    }

    @Test
    fun `what each end of a cast play does to the Cast session`() {
        // Ended with the session: the receiver stops.
        listOf(Why.StopCasting, Why.TaskRemovedPaused, Why.AddressChanged).forEach { assertEquals(Ending.EndSession, CastServiceRules.ending(it), "$it") }
        // The session lives on, the device still chosen; the next Play's tap starts the service again.
        listOf(Why.Finished, Why.Cancelled, Why.Failed, Why.LostStream).forEach { assertEquals(Ending.KeepSession, CastServiceRules.ending(it), "$it") }
        // The session went by itself, or another sender has the device: nothing more to end, nothing of theirs stopped.
        listOf(Why.SessionEnded, Why.Interrupted, Why.OtherDevice).forEach { assertEquals(Ending.SessionGone, CastServiceRules.ending(it), "$it") }
        assertEquals(Why.entries.toSet(), (Ending.entries.flatMap { e -> Why.entries.filter { CastServiceRules.ending(it) == e } }).toSet())
    }

    /**
     * L84, W32: another sender's load or stop ends only the play whose media it replaced; the session lives on, and a play
     * still starting takes the device back with its own load. Where the session ends, went or is another device's, every
     * play ends with it.
     */
    @Test
    fun `another sender's load ends only the play it interrupted`() {
        assertFalse(CastServiceRules.endsEvery(Why.Interrupted))
        listOf(Why.StopCasting, Why.TaskRemovedPaused, Why.AddressChanged, Why.SessionEnded, Why.OtherDevice).forEach { assertTrue(CastServiceRules.endsEvery(it), "$it") }
        listOf(Why.Finished, Why.Cancelled, Why.Failed, Why.LostStream).forEach { assertFalse(CastServiceRules.endsEvery(it), "$it") }
    }

    @Test
    fun `only a cast play that loaded has anything to stop on the device`() {
        assertTrue(CastServiceRules.stopsDevice(Why.Cancelled, loaded = true), "Cancel stops what it loaded (L82)")
        assertFalse(CastServiceRules.stopsDevice(Why.Cancelled, loaded = false))
        assertTrue(CastServiceRules.stopsDevice(Why.Failed, loaded = true))
        // Finished already; another sender's media isn't ours to stop; the session's end and Stop casting stop the receiver themselves.
        listOf(Why.Finished, Why.Interrupted, Why.SessionEnded, Why.OtherDevice, Why.StopCasting, Why.AddressChanged).forEach {
            assertFalse(CastServiceRules.stopsDevice(it, loaded = true), "$it")
        }
    }

    @Test
    fun `the device's events end the play that sent its media, and nothing else`() {
        fun status(state: CastPlayerState, idle: CastIdleReason?, id: String?) = CastEvent.Status(state, idle, id, positionMs = 1_930_000, at = 5_000)
        assertEquals(Why.Finished, CastServiceRules.ends(status(CastPlayerState.Idle, CastIdleReason.Finished, "ours"), sent = "ours"))
        assertEquals(Why.Interrupted, CastServiceRules.ends(status(CastPlayerState.Idle, CastIdleReason.Interrupted, "ours"), sent = "ours"))
        // Our LOAD on a device taken over interrupts the other sender's media (W32): not ours ending.
        assertNull(CastServiceRules.ends(status(CastPlayerState.Idle, CastIdleReason.Interrupted, "theirs"), sent = "ours"))
        assertNull(CastServiceRules.ends(status(CastPlayerState.Idle, CastIdleReason.Finished, "theirs"), sent = "ours"))
        assertNull(CastServiceRules.ends(status(CastPlayerState.Idle, CastIdleReason.Finished, "theirs"), sent = null))
        assertNull(CastServiceRules.ends(status(CastPlayerState.Playing, null, "ours"), sent = "ours"))
        assertNull(CastServiceRules.ends(status(CastPlayerState.Paused, null, "ours"), sent = "ours"))
        assertEquals(Why.SessionEnded, CastServiceRules.ends(CastEvent.Ended, sent = "ours"))
        assertEquals(Why.SessionEnded, CastServiceRules.ends(CastEvent.Ended, sent = null))
        assertNull(CastServiceRules.ends(CastEvent.Suspended, sent = "ours"))
        assertNull(CastServiceRules.ends(CastEvent.Started, sent = "ours"))
    }

    @Test
    fun `the play's device no longer the session's ends it, with nothing to end`() {
        val kitchen = CastLinkNow("route-kitchen", "Kitchen display", CastLink.Connected)
        assertNull(CastServiceRules.lost(kitchen, "route-kitchen"))
        assertNull(CastServiceRules.lost(kitchen.copy(link = CastLink.Reconnecting), "route-kitchen"), "a suspended session may come back (L91)")
        assertEquals(Why.SessionEnded, CastServiceRules.lost(null, "route-kitchen"))
        assertEquals(Why.OtherDevice, CastServiceRules.lost(CastLinkNow("route-den", "Den TV", CastLink.Connecting), "route-kitchen"))
    }

    @Test
    fun `the first address the flow has is bound, its leading null skipped`() {
        val watch = CastAddressWatch()
        assertNull(watch.address(null), "the flow's first value is always null")
        assertEquals(CastAddressWatch.Act.Bind(home), watch.address(home))
        watch.bound(home)
        assertNull(watch.address(home))
    }

    @Test
    fun `a bind that failed is tried again as the address comes again`() {
        val watch = CastAddressWatch()
        assertEquals(CastAddressWatch.Act.Bind(home), watch.address(home))
        assertNull(watch.address(null))
        assertEquals(CastAddressWatch.Act.Bind(home), watch.address(home))
    }

    /**
     * A bind that failed as the address went and came back within it: the flow, which says only changes, says nothing
     * more, so the address it has now is bound again, whenever the service tries.
     */
    @Test
    fun `a bind that failed is tried again at the address there is now`() {
        val watch = CastAddressWatch()
        assertEquals(CastAddressWatch.Act.Bind(home), watch.address(home))
        assertEquals(CastAddressWatch.Act.Bind(home), watch.bindAgain(), "nothing bound yet")
        watch.address(null)
        assertNull(watch.bindAgain(), "no address now: the flow says when it's back")
        watch.address(home)
        watch.bound(home)
        assertNull(watch.bindAgain(), "bound")
    }

    @Test
    fun `a later null is the Wi-Fi blipping, and the same address back changes nothing`() {
        val watch = CastAddressWatch().apply { address(home); bound(home) }
        assertNull(watch.address(null), "the Cast session suspends by itself (L91)")
        assertNull(watch.address(home))
    }

    @Test
    fun `another address stops the cast`() {
        val watch = CastAddressWatch().apply { address(home); bound(home) }
        assertEquals(CastAddressWatch.Act.Stop, watch.address(other))
        val blipped = CastAddressWatch().apply { address(home); bound(home); address(null) }
        assertEquals(CastAddressWatch.Act.Stop, blipped.address(other))
    }

    @Test
    fun `a listening socket lost while the address is current listens again at once`() {
        val watch = CastAddressWatch().apply { address(home); bound(home) }
        // A blip the flow merged into no change, or EMFILE, which never changes the address.
        assertEquals(CastAddressWatch.Act.Relisten, watch.listenerLost())
        assertNull(watch.address(home), "listening again already")
    }

    @Test
    fun `a listening socket lost with the address listens again when it comes back`() {
        val watch = CastAddressWatch().apply { address(home); bound(home); address(null) }
        assertNull(watch.listenerLost())
        assertEquals(CastAddressWatch.Act.Relisten, watch.address(home))
        assertNull(watch.address(home))
        assertNull(watch.address(null))
        assertNull(watch.address(home), "lost once, listening again once")
    }

    /**
     * Android destroys the sockets on an address it takes away at once, and tells the app of the address later: the
     * listener's loss can come first, the address flow still saying the address is there. Listening again at once fails
     * then; that is no lost stream yet, only once the address is back on the phone and listening there still fails.
     */
    @Test
    fun `listening again at once failing with the address gone waits for it to come back`() {
        val watch = CastAddressWatch().apply { address(home); bound(home) }
        assertEquals(CastAddressWatch.Act.Relisten, watch.listenerLost())
        assertNull(watch.relistenFailed(present = false), "the address went with the socket: no lost stream yet")
        assertTrue(watch.waiting)
        assertNull(watch.looked(present = false), "still gone")
        assertEquals(CastAddressWatch.Act.Relisten, watch.looked(present = true), "back on the phone")
        assertFalse(watch.waiting)
        assertNull(watch.looked(present = true), "listening again once")
        // Back, and still it can't listen there, once more too: the port taken.
        assertEquals(CastAddressWatch.Act.Relisten, watch.relistenFailed(present = true))
        assertEquals(CastAddressWatch.Act.Lost, watch.relistenFailed(present = true))
    }

    /**
     * The address can come back between a relisten that failed with it gone and the look that follows: the look finds it
     * there, though it wasn't as the relisten ran. So listening again is tried once more first; only that failing too,
     * the address still on the phone, is the lost stream.
     */
    @Test
    fun `listening again failing with the address on the phone is tried once more, then the stream is lost`() {
        val watch = CastAddressWatch().apply { address(home); bound(home) }
        assertEquals(CastAddressWatch.Act.Relisten, watch.listenerLost())
        assertEquals(CastAddressWatch.Act.Relisten, watch.relistenFailed(present = true), "once more: the address may be back just now")
        assertEquals(CastAddressWatch.Act.Lost, watch.relistenFailed(present = true), "its port taken")
        assertFalse(watch.waiting)
    }

    @Test
    fun `listening again that worked gives the next loss its own try once more`() {
        val watch = CastAddressWatch().apply { address(home); bound(home) }
        watch.listenerLost()
        assertEquals(CastAddressWatch.Act.Relisten, watch.relistenFailed(present = true))
        watch.relistened()
        assertEquals(CastAddressWatch.Act.Relisten, watch.listenerLost())
        assertEquals(CastAddressWatch.Act.Relisten, watch.relistenFailed(present = true), "a new loss, its own try once more")
        assertEquals(CastAddressWatch.Act.Lost, watch.relistenFailed(present = true))
    }

    @Test
    fun `the address flow bringing the address back ends the wait too`() {
        val watch = CastAddressWatch().apply { address(home); bound(home) }
        watch.listenerLost()
        watch.relistenFailed(present = false)
        assertNull(watch.address(null), "the flow says it went, at last")
        assertEquals(CastAddressWatch.Act.Relisten, watch.address(home))
        assertFalse(watch.waiting)
        assertNull(watch.looked(present = true))
    }

    @Test
    fun `a listening socket lost before anything was bound is nothing to listen to`() {
        assertNull(CastAddressWatch().listenerLost())
    }

    @Test
    fun `listening again failing ends the play with the lost stream's words`() {
        assertEquals(Ending.KeepSession, CastServiceRules.ending(Why.LostStream))
        assertEquals("Kitchen display lost the stream from this phone", lostTheStream("Kitchen display"))
    }

    @Test
    fun `the wake lock is held while the server writes, and let go 10 s after the last write`() {
        val wake = CastWakeLock(letGoMillis = 10_000, timeoutMillis = 600_000)
        assertNull(wake.tick(now = 0, sent = 0), "nothing written")
        assertEquals(CastWakeLock.Act.Acquire, wake.tick(now = 1_000, sent = 65_536))
        assertNull(wake.tick(now = 2_000, sent = 131_072))
        // A paused receiver holds its connection open, writing nothing.
        assertNull(wake.tick(now = 11_999, sent = 131_072))
        assertEquals(CastWakeLock.Act.Release, wake.tick(now = 12_000, sent = 131_072))
        assertNull(wake.tick(now = 60_000, sent = 131_072))
        // Played again: a new burst takes it again.
        assertEquals(CastWakeLock.Act.Acquire, wake.tick(now = 61_000, sent = 196_608))
    }

    @Test
    fun `a write begun takes the wake lock before its first bytes`() {
        val wake = CastWakeLock(letGoMillis = 10_000, timeoutMillis = 600_000)
        assertEquals(CastWakeLock.Act.Acquire, wake.began(now = 1_000))
        assertNull(wake.tick(now = 5_000, sent = 0))
        assertEquals(CastWakeLock.Act.Release, wake.tick(now = 11_000, sent = 0), "nothing written in 10 s")
    }

    @Test
    fun `the wake lock is taken again before its timeout while the writes go on`() {
        val wake = CastWakeLock(letGoMillis = 10_000, timeoutMillis = 600_000)
        assertEquals(CastWakeLock.Act.Acquire, wake.tick(now = 0, sent = 1))
        var sent = 1L
        var acquired = 0
        var t = 1_000L
        while (t <= 1_200_000) {
            sent += 65_536
            when (wake.tick(now = t, sent = sent)) {
                CastWakeLock.Act.Acquire -> acquired++
                CastWakeLock.Act.Release -> throw AssertionError("let go at $t while writing")
                null -> Unit
            }
            t += 1_000
        }
        // Re-armed at half its 10 minutes, so the timeout never runs out under a long write.
        assertEquals(4, acquired)
    }

    @Test
    fun `the timings are the design's`() {
        val timings = CastTimings()
        assertEquals(10_000, timings.wakeLetGoMillis)
        assertEquals(600_000, timings.wakeTimeoutMillis)
        assertEquals(600_000, timings.foregroundTimeoutMillis)
    }
}
