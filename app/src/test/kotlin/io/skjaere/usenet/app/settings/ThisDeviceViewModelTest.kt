package io.skjaere.usenet.app.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.skjaere.usenet.app.settings.ThisDevice.Row
import io.skjaere.usenet.player.DecoderSummary
import io.skjaere.usenet.player.DisplayFacts
import io.skjaere.usenet.player.ModeSize
import io.skjaere.usenet.player.NativeFacts
import io.skjaere.usenet.player.tv.DeviceMemory
import io.skjaere.usenet.releases.AudioCodec
import io.skjaere.usenet.releases.Hdr
import io.skjaere.yenc.RapidYencKernel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * This device's reading ([ThisDeviceViewModel]) over fake reads: what each read finds is shown as it lands, and one read
 * that fails leaves the others' facts to be read and shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThisDeviceViewModelTest {
    @AfterEach
    fun main() = Dispatchers.resetMain()

    private val uhd = ModeSize(3840, 2160)

    /** An invented 4K box's facts, as the container would keep them once read; each fake read below fills one in. */
    private inner class Reads {
        var display: DisplayFacts? = null
        var memory: DeviceMemory? = null
        var native: NativeFacts? = null

        /** What the TV or receiver takes over HDMI, as the decoder loader has it now. */
        var passedThrough = listOf(AudioCodec.Ac3, AudioCodec.Eac3)

        /** How many times the display was read: once a read. */
        var displayReads = 0

        /** DisplayManager's word that the display changed. */
        val displayChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** The decoder loader's word that the HDMI audio route changed (DeviceCapabilitiesLoader.audioChanges). */
        val audioChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** The display and decoders' probe: the box's display, unless a test makes it fail. */
        var probe: suspend () -> Unit = { display = boxDisplay() }

        fun boxDisplay(hdr: Set<Hdr> = setOf(Hdr.DolbyVision, Hdr.Hdr10)) =
            DisplayFacts(tv = true, output = uhd, fromTv = true, largest = uhd, window = ModeSize(1920, 1080), hdr = hdr)

        val deps = ThisDeviceDeps(
            facts = { rate, tvLayouts, settled ->
                ThisDevice(
                    "Acme", "Box 4K", "14", 34, television = true, memory = memory, display = display, refreshRate = rate,
                    // The probe reads the display and the decoders together: none of either until it has run.
                    decoders = display?.let { DecoderSummary(emptyList(), false, emptyList(), emptyList(), listOf(AudioCodec.Aac), passedThrough) },
                    native = native, tvLayouts = tvLayouts, settled = settled,
                )
            },
            readDisplay = {
                displayReads++
                59.94006f
            },
            probe = { probe() },
            memory = {
                memory = DeviceMemory(
                    lowRam = false, memoryClassMb = 192, totalRamBytes = 3L shl 30, process64Bit = true, abis = listOf("arm64-v8a"), heapLimitBytes = 512L shl 20,
                )
            },
            native = { native = NativeFacts.Loaded("1.1.1", RapidYencKernel.NEON, RapidYencKernel.ARMPMULL) },
            displayChanges = displayChanged,
            audioChanges = audioChanged,
        )
    }

    private val store = ViewModelStore()

    private fun TestScope.vm(reads: Reads): ThisDeviceViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return ViewModelProvider(store, viewModelFactory { initializer { ThisDeviceViewModel(reads.deps, tvLayouts = true) } })[ThisDeviceViewModel::class.java]
    }

    private fun ThisDevice.rows(title: String) = sections.single { it.title == title }.rows

    /**
     * The page reads as it is made, as it opens: not on its first resume, which comes only once the screens' cross-fade
     * is over (the page's facts are wanted within 400 ms of opening).
     */
    @Test
    fun `made, it reads every fact without waiting for a resume`() = runTest {
        val vm = vm(Reads())

        advanceUntilIdle()

        val facts = vm.facts.value
        assertTrue(facts.settled)
        assertEquals(59.94006f, facts.refreshRate)
        assertEquals(listOf(Row("rapidyenc", "1.1.1"), Row("Decode", "NEON"), Row("CRC32", "ARM PMULL")), facts.rows("yEnc decoding"))
        store.clear()
    }

    /** Its first resume finds the facts just read; each later one (back from the system's display settings) reads again. */
    @Test
    fun `the first resume doesn't read again, a later one does`() = runTest {
        val reads = Reads()
        val vm = vm(reads)
        advanceUntilIdle()

        vm.resumed()
        advanceUntilIdle()
        assertEquals(1, reads.displayReads)

        vm.resumed()
        advanceUntilIdle()
        assertEquals(2, reads.displayReads)
        store.clear()
    }

    /** Followed while the page is in front: a TV woken a moment ago gets Dolby Vision back, and the page says so. */
    @Test
    fun `a change of the display while followed is read and shown, and not once no longer followed`() = runTest {
        val reads = Reads()
        reads.probe = { reads.display = reads.boxDisplay(hdr = setOf(Hdr.Hdr10)) }
        val vm = vm(reads)
        val following = launch { vm.followDisplay() }
        advanceUntilIdle()
        assertEquals(listOf(Hdr.Hdr10), vm.facts.value.rows("Display").single { it.label == "HDR" }.hdr)

        reads.probe = { reads.display = reads.boxDisplay() }
        reads.displayChanged.emit(Unit)
        advanceUntilIdle()
        assertEquals(listOf(Hdr.DolbyVision, Hdr.Hdr10), vm.facts.value.rows("Display").single { it.label == "HDR" }.hdr)
        assertEquals(2, reads.displayReads)

        // Paused: no longer followed.
        following.cancel()
        reads.displayChanged.tryEmit(Unit)
        advanceUntilIdle()
        assertEquals(2, reads.displayReads)
        store.clear()
    }

    /** Followed while the page is in front: a receiver switched on while it shows is read and shown, not once no longer followed. */
    @Test
    fun `a change of the audio route while followed is read and shown, and not once no longer followed`() = runTest {
        val reads = Reads()
        val vm = vm(reads)
        val following = launch { vm.followDisplay() }
        advanceUntilIdle()
        assertEquals("DD, DD+", vm.facts.value.rows("Audio").single { it.label == "Over HDMI" }.value)

        reads.passedThrough = listOf(AudioCodec.Ac3, AudioCodec.Eac3, AudioCodec.TrueHd)
        reads.audioChanged.emit(Unit)
        advanceUntilIdle()
        assertEquals("DD, DD+, TrueHD", vm.facts.value.rows("Audio").single { it.label == "Over HDMI" }.value)
        assertEquals(2, reads.displayReads)

        // Paused: no longer followed.
        following.cancel()
        reads.audioChanged.tryEmit(Unit)
        advanceUntilIdle()
        assertEquals(2, reads.displayReads)
        store.clear()
    }

    /**
     * A box whose codec service is gone: the decoder probe throws (MediaCodecList's "cannot get MediaCodecList"). Only the
     * decoders and the display can't be read; the memory and rapidyenc are still read and shown, not called unreadable.
     */
    @Test
    fun `a decoder probe that fails leaves the memory and rapidyenc to be read`() = runTest {
        val reads = Reads()
        reads.probe = { throw RuntimeException("cannot get MediaCodecList") }
        val vm = vm(reads)

        vm.read()
        advanceUntilIdle()

        val facts = vm.facts.value
        assertTrue(facts.settled)
        assertEquals(listOf(Row("rapidyenc", "1.1.1"), Row("Decode", "NEON"), Row("CRC32", "ARM PMULL")), facts.rows("yEnc decoding"))
        assertEquals("3.0 GB RAM", facts.rows("Device").single { it.label == "Memory" }.value)
        assertEquals(listOf(Row("Decoders", "Can’t be read")), facts.rows("Video"))
        store.clear()
    }
}
