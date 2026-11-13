package io.skjaere.usenet.player

import androidx.media3.common.C
import io.skjaere.usenet.releases.AudioCodec
import io.skjaere.usenet.releases.Hdr
import io.skjaere.usenet.releases.RankingContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceCapabilitiesLoaderTest {
    private val fakeMain = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()
    private val fakeDefault = Executors.newSingleThreadExecutor { Thread(it, "fake-default") }.asCoroutineDispatcher()
    private val logged = mutableListOf<String>()

    private val uhd = ModeSize(3840, 2160)
    private fun display(hdr: Set<Hdr>, output: ModeSize = uhd, window: ModeSize = ModeSize(1920, 1080)) = DisplayFacts(true, output, false, uhd, window, hdr)
    private fun decoders(route: Passthrough = Passthrough.NONE, listed: Map<String, List<Decoder>> = emptyMap()) =
        MediaCodecDeviceCapabilities(listed, emptyMap(), route, display(emptySet()))

    @AfterEach
    fun stop() {
        fakeMain.close()
        fakeDefault.close()
    }

    @Test
    fun `get from the main thread probes on the default dispatcher`() = runBlocking<Unit> {
        val probedOn = mutableListOf<String>()
        val loader = DeviceCapabilitiesLoader(fakeDefault, { display(setOf(Hdr.Hdr10)) }, logged::add) {
            decoders().also { probedOn += Thread.currentThread().name }
        }

        val capabilities = withContext(fakeMain) { loader.get() }

        assertEquals(1, probedOn.size)
        assertTrue(probedOn.single().startsWith("fake-default"), "probed on ${probedOn.single()}")
        assertEquals(setOf(Hdr.Hdr10), capabilities.hdrTypes)
    }

    @Test
    fun `probes the decoders once and reads the display on every call`() = runBlocking<Unit> {
        var probes = 0
        val outputs = ArrayDeque(listOf(uhd, ModeSize(1920, 1080), ModeSize(1920, 1080)))
        val loader = DeviceCapabilitiesLoader(fakeDefault, { display(emptySet(), output = outputs.removeFirst()) }, logged::add) {
            probes++
            decoders()
        }
        assertNull(loader.latest)

        // The box's output set to 1080p between two searches.
        val heights = List(3) { withContext(fakeMain) { loader.get().displayHeight } }

        assertEquals(1, probes)
        assertEquals(listOf(2160, 1080, 1080), heights)
        // What the log header says: the last call's facts, never read for it.
        assertEquals(1080, loader.latest?.displayHeight)
    }

    /**
     * Auto-advance's prefetched search is handed to the play only when the two rank alike (EpisodeFindHandoff compares
     * their ranking contexts): the display read twice unchanged gives equal capabilities, so equal contexts; a change
     * doesn't.
     */
    @Test
    fun `an unchanged display gives equal capabilities, so a prefetched search is reused`() = runBlocking<Unit> {
        val reads = ArrayDeque(listOf(setOf(Hdr.Hdr10), setOf(Hdr.Hdr10), emptySet()))
        val loader = DeviceCapabilitiesLoader(fakeDefault, { display(reads.removeFirst()) }, logged::add) { decoders() }

        val (prefetch, play, changed) = List(3) { RankingContext(loader.get()) }

        assertEquals(prefetch, play)
        assertEquals(prefetch.hashCode(), play.hashCode())
        assertNotEquals(play, changed)
    }

    /**
     * The player turns a phone to landscape, and a search after it back: the window turns, the display doesn't, so no
     * line is logged again and a search prefetched in either is still reused.
     */
    @Test
    fun `a turned window logs no line and changes no capabilities`() = runBlocking<Unit> {
        val windows = ArrayDeque(listOf(ModeSize(1080, 2400), ModeSize(2400, 1080), ModeSize(1080, 2400)))
        val loader = DeviceCapabilitiesLoader(fakeDefault, { display(emptySet(), window = windows.removeFirst()) }, logged::add) { decoders() }

        val seen = List(3) { loader.get() }

        assertEquals(1, logged.count { it.startsWith("Display:") }, "$logged")
        assertEquals(seen.first(), seen[1])
    }

    /**
     * A Shield's TV reports HDR10 without Dolby Vision for about 8 s after it wakes: a ranking then sees that, and the
     * next one the screen as it is again, not what one read at the wrong moment left for the rest of the process.
     */
    @Test
    fun `a TV's HDR after it wakes reads fresh on the next call, and a line is logged for each change only`() = runBlocking<Unit> {
        val dolbyVision = setOf(Hdr.DolbyVision, Hdr.Hdr10)
        val reads = ArrayDeque(listOf(dolbyVision, setOf(Hdr.Hdr10), dolbyVision, dolbyVision))
        val loader = DeviceCapabilitiesLoader(fakeDefault, { display(reads.removeFirst()) }, logged::add) { decoders() }

        val seen = List(4) { loader.get().hdrTypes }

        assertEquals(listOf(dolbyVision, setOf(Hdr.Hdr10), dolbyVision, dolbyVision), seen)
        assertEquals(
            listOf(
                *decoders().decoderLines.toTypedArray(),
                "Display: 3840x2160 output, largest mode 3840x2160: 2160p; HDR: Dolby Vision, HDR10",
                "Display: 3840x2160 output, largest mode 3840x2160: 2160p; HDR: HDR10",
                "Display: 3840x2160 output, largest mode 3840x2160: 2160p; HDR: Dolby Vision, HDR10",
            ),
            logged,
        )
    }

    private val dd = Passthrough(setOf(C.ENCODING_AC3, C.ENCODING_E_AC3))
    private val withTrueHd = Passthrough(setOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DOLBY_TRUEHD))

    /** A loader whose decoders read [route] as the probe ran, a lost route waiting out its hold on the test's clock. */
    private fun TestScope.loader(route: Passthrough = dd, probe: () -> MediaCodecDeviceCapabilities = { decoders(route) }) =
        DeviceCapabilitiesLoader(fakeDefault, { display(setOf(Hdr.Hdr10)) }, logged::add, backgroundScope, probe)

    /** How many times [loader] said the route changed. */
    private fun TestScope.changes(loader: DeviceCapabilitiesLoader): MutableList<Unit> =
        mutableListOf<Unit>().also { heard -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { loader.audioChanges.collect { heard += it } } }

    private fun routeLines() = logged.filter { it.startsWith("Audio route") }

    /**
     * TCR-1: Media3's receiver answers its registration with the route it read, and a TV's EDID read again gives the same:
     * equal, so nothing is logged or said, and a search prefetched before is still handed to the play.
     */
    @Test
    fun `the same route reported again changes nothing`() = runTest {
        val loader = loader()
        val changed = changes(loader)
        val prefetch = RankingContext(loader.get())
        loader.audioRouteChanged(Passthrough(setOf(C.ENCODING_E_AC3, C.ENCODING_AC3)))
        advanceTimeBy(5_000)
        val play = RankingContext(loader.get())
        assertEquals(prefetch, play)
        assertEquals(prefetch.hashCode(), play.hashCode())
        assertEquals(emptyList(), routeLines())
        assertEquals(0, changed.size)
    }

    /** A receiver switched on after the app started: the next ranking takes TrueHD, and the header and This device say so at once. */
    @Test
    fun `a route gained counts at once, with one line in the header's words`() = runTest {
        val loader = loader()
        val changed = changes(loader)
        val before = RankingContext(loader.get())
        assertFalse(before.device.canDecode(AudioCodec.TrueHd))

        loader.audioRouteChanged(withTrueHd)

        // Without a ranking: the header and This device read what the last one answered.
        assertEquals(listOf(AudioCodec.Ac3, AudioCodec.Eac3, AudioCodec.TrueHd), loader.latest?.decoderSummary?.audioPassedThrough)
        assertEquals("Audio: none decoded; DD, DD+, TrueHD passed through over HDMI", loader.latest?.decoderLines?.get(1))
        assertEquals(listOf("Audio route changed: DD, DD+, TrueHD passed through over HDMI (was DD, DD+)"), routeLines())
        assertEquals(1, changed.size)
        val after = RankingContext(loader.get())
        assertTrue(after.device.canDecode(AudioCodec.TrueHd))
        assertNotEquals(before, after)
    }

    /** A TV that sleeps, a receiver switched off: the route shrinks, but only once that has held 2 s. */
    @Test
    fun `a route lost counts once it has held 2 s, and back again it equals the first`() = runTest {
        val loader = loader(route = withTrueHd)
        val changed = changes(loader)
        val first = RankingContext(loader.get())

        loader.audioRouteChanged(dd)
        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(loader.get().canDecode(AudioCodec.TrueHd), "a loss counted before it held")
        assertEquals(emptyList(), routeLines())

        advanceTimeBy(2)
        runCurrent()
        assertFalse(loader.get().canDecode(AudioCodec.TrueHd))
        assertEquals(listOf(AudioCodec.Ac3, AudioCodec.Eac3), loader.latest?.decoderSummary?.audioPassedThrough)
        assertEquals(listOf("Audio route changed: DD, DD+ passed through over HDMI (was DD, DD+, TrueHD)"), routeLines())
        assertEquals(1, changed.size)

        loader.audioRouteChanged(withTrueHd)
        assertEquals(first, RankingContext(loader.get()))
        assertEquals(2, changed.size)
    }

    /**
     * A route that both loses and gains (a DTS soundbar swapped for a Dolby receiver, or a re-sync reporting a part of the
     * route): it loses, so it waits out the hold as a loss does, and the first route reported again within it undoes it.
     */
    @Test
    fun `a route that loses and gains counts as a loss`() = runTest {
        val dts = Passthrough(setOf(C.ENCODING_DTS))
        val loader = loader(route = dts)
        val changed = changes(loader)
        val first = RankingContext(loader.get())

        loader.audioRouteChanged(dd)
        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(loader.get().canDecode(AudioCodec.Dts), "a change that loses DTS counted before it held")
        assertFalse(loader.get().canDecode(AudioCodec.Ac3))
        assertEquals(emptyList(), routeLines())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("Audio route changed: DD, DD+ passed through over HDMI (was DTS, DTS-HD, DTS-HD MA, DTS:X)"), routeLines())
        assertEquals(1, changed.size)

        // From DD, DD+ back to DTS: a loss again, undone within its hold.
        loader.audioRouteChanged(dts)
        advanceTimeBy(1_000)
        loader.audioRouteChanged(dd)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, routeLines().size)
        assertNotEquals(first, RankingContext(loader.get()))
    }

    /** A refresh-rate switch re-syncs HDMI, and the route may drop for a moment: no line, no change, the contexts equal. */
    @Test
    fun `a route lost and back within 2 s changes nothing`() = runTest {
        val loader = loader(route = withTrueHd)
        val changed = changes(loader)
        val before = RankingContext(loader.get())
        loader.audioRouteChanged(Passthrough.NONE)
        advanceTimeBy(1_000)
        loader.audioRouteChanged(dd)
        advanceTimeBy(1_500)
        loader.audioRouteChanged(withTrueHd)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(before, RankingContext(loader.get()))
        assertEquals(emptyList(), routeLines())
        assertEquals(0, changed.size)
    }

    /**
     * Atmos (E-AC-3 JOC) turned on or off at the TV or receiver, or DTS-HD reported beside DTS: nothing the ranking asks
     * changes, so nothing is logged or said, and a search prefetched before is still handed to the play (TCR-1).
     */
    @Test
    fun `a route that passes the same codecs through changes nothing`() = runTest {
        listOf(
            dd to Passthrough(setOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC)),
            Passthrough(setOf(C.ENCODING_DTS)) to Passthrough(setOf(C.ENCODING_DTS, C.ENCODING_DTS_HD)),
        ).forEach { (probed, more) ->
            val loader = loader(route = probed)
            val changed = changes(loader)
            val before = RankingContext(loader.get())
            loader.audioRouteChanged(more)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(before, RankingContext(loader.get()))
            loader.audioRouteChanged(probed)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(before, RankingContext(loader.get()))
            assertEquals(emptyList(), routeLines())
            assertEquals(0, changed.size)
        }
    }

    /**
     * A TV's switch of its refresh rate re-syncs its HDMI for as long as the TV takes, up to the switch's cap, which can
     * be longer than the 2 s hold (DisplayModeSwitcherTest's re-sync drops the route for 3 s): a route lost meanwhile is
     * judged as the switch ends. Back by then, nothing is said; still lost, it counts then, once.
     */
    @Test
    fun `a route lost during a switch is judged as the switch ends`() = runTest {
        val loader = loader(route = withTrueHd)
        val changed = changes(loader)
        val before = RankingContext(loader.get())
        val switch = launch { loader.whileSwitching { delay(7_000) } }
        runCurrent()
        loader.audioRouteChanged(Passthrough.NONE)
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(loader.get().canDecode(AudioCodec.TrueHd), "a switch's re-sync counted as a loss")
        loader.audioRouteChanged(withTrueHd)
        switch.join()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(before, RankingContext(loader.get()))
        assertEquals(emptyList(), routeLines())
        assertEquals(0, changed.size)

        val second = launch { loader.whileSwitching { delay(7_000) } }
        runCurrent()
        loader.audioRouteChanged(dd)
        advanceTimeBy(6_999)
        runCurrent()
        assertEquals(emptyList(), routeLines())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("Audio route changed: DD, DD+ passed through over HDMI (was DD, DD+, TrueHD)"), routeLines())
        assertEquals(1, changed.size)
        second.join()
    }

    @Test
    fun `a route with nothing passed through is said as none`() = runTest {
        val loader = loader()
        loader.get()
        loader.audioRouteChanged(Passthrough.NONE)
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(listOf("Audio route changed: none passed through over HDMI (was DD, DD+)"), routeLines())
        loader.audioRouteChanged(dd)
        assertEquals("Audio route changed: DD, DD+ passed through over HDMI (was none)", routeLines().last())
    }

    /** The decoders are scanned once a process: a route change moves them to the new route, nothing more. */
    @Test
    fun `a route change neither probes the decoders again nor works their summary out again`() = runTest {
        var probes = 0
        var asked = 0
        val avc = mapOf("video/avc" to listOf(Decoder(true, emptyMap()) { _, h -> asked++; h <= 2160 }))
        val loader = loader { probes++; decoders(dd, avc) }
        loader.get()
        loader.latest?.decoderSummary
        val askedOnce = asked
        loader.audioRouteChanged(withTrueHd)
        loader.get()
        assertEquals(listOf(AudioCodec.Ac3, AudioCodec.Eac3, AudioCodec.TrueHd), loader.latest?.decoderSummary?.audioPassedThrough)
        assertEquals(1, probes)
        assertEquals(askedOnce, asked)
    }

    /** Media3's receiver may answer before the first ranking has probed the decoders: the probe reads the route itself. */
    @Test
    fun `a route reported before the decoders are probed is left to the probe`() = runTest {
        val loader = loader(route = dd)
        loader.audioRouteChanged(withTrueHd)
        advanceTimeBy(5_000)
        assertFalse(loader.get().canDecode(AudioCodec.TrueHd))
        assertEquals(emptyList(), routeLines())
    }
}
