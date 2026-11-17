package io.skjaere.usenet.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L410, L438: the offline signal Home and the Show page read, from whether the device's default network is validated, on
 * virtual time. Offline once the network has been unvalidated for three seconds; online again at once; a shorter dip, as
 * Android reads a switch between two networks, never shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSignalTest {
    /** Everything the signal said, from its first value on. */
    private fun TestScope.said(signal: OfflineSignal): List<Boolean> {
        val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { signal.offline.collect { values += it } }
        return values
    }

    @Test
    fun `offline three seconds after the network goes, and online again at once`() = runTest {
        val validated = MutableStateFlow(true)
        val signal = OfflineSignal(validated, backgroundScope)
        val values = said(signal)
        runCurrent()
        assertFalse(signal.offline.value)
        validated.value = false
        advanceTimeBy(OfflineSignal.AFTER_MILLIS - 1)
        runCurrent()
        assertFalse(signal.offline.value, "still online a moment before three seconds")
        advanceTimeBy(1)
        runCurrent()
        assertTrue(signal.offline.value, "offline at three seconds")
        validated.value = true
        runCurrent()
        assertFalse(signal.offline.value, "online again at once")
        assertEquals(listOf(false, true, false), values)
    }

    @Test
    fun `a one second dip never shows`() = runTest {
        val validated = MutableStateFlow(true)
        val signal = OfflineSignal(validated, backgroundScope)
        val values = said(signal)
        runCurrent()
        validated.value = false
        advanceTimeBy(1_000)
        validated.value = true
        // Long after: the dip's three seconds would have run out by now.
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(false), values)
        // Two dips in a row count from the second's start.
        validated.value = false
        advanceTimeBy(2_000)
        validated.value = true
        runCurrent()
        validated.value = false
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(false), values)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(false, true), values)
    }

    @Test
    fun `a network already gone as the app starts reads as offline after the same three seconds`() = runTest {
        val validated = MutableStateFlow(false)
        val signal = OfflineSignal(validated, backgroundScope)
        runCurrent()
        assertFalse(signal.offline.value)
        advanceTimeBy(OfflineSignal.AFTER_MILLIS)
        runCurrent()
        assertTrue(signal.offline.value)
    }
}
