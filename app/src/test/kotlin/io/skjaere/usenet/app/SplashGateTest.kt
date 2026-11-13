package io.skjaere.usenet.app

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How long MainActivity keeps the splash up: until the settings its first screen shows by are read, two seconds at most. */
@OptIn(ExperimentalCoroutinesApi::class)
class SplashGateTest {
    @AfterEach
    fun main() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the splash stays until the first screen is ready`() {
        val gate = SplashGate(startedAt = 1_000)
        assertTrue(gate.keepOnScreen(1_000))
        assertTrue(gate.keepOnScreen(1_800))
        gate.ready()
        assertFalse(gate.keepOnScreen(1_801))
        assertFalse(gate.timedOut)
    }

    /** Whichever screen shows: a .nzb opened from another app puts Import over Home at once, a restore brings back any. */
    @Test
    fun `the splash goes once the settings are read, not before`() = runTest {
        val gate = SplashGate(startedAt = 1_000)
        val settings = MutableStateFlow<String?>(null)
        launch { gate.readyOnRead(settings) }
        runCurrent()
        assertTrue(gate.keepOnScreen(1_100), "let go before the settings were read")
        settings.value = "read"
        runCurrent()
        assertFalse(gate.keepOnScreen(1_200), "held after the settings were read")
        assertFalse(gate.timedOut)
    }

    @Test
    fun `a first screen that never gets ready holds the splash two seconds at most`() {
        val gate = SplashGate(startedAt = 1_000)
        assertTrue(gate.keepOnScreen(2_999))
        assertFalse(gate.keepOnScreen(3_000))
        assertTrue(gate.timedOut)
        // Let go for good: the screen getting ready after all changes nothing.
        gate.ready()
        assertFalse(gate.keepOnScreen(3_001))
    }

    /**
     * After the process died Android brings the activity back with its saved screens and shows its splash, nothing
     * read yet. Its view model is new, as is the gate: it holds as a first start's does (a configuration change keeps
     * the view model, and the gate with it, which SplashDeviceTest checks).
     */
    @Test
    fun `an activity restored after the process died holds the splash until the settings are read`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val settings = MutableStateFlow<String?>(null)
        val models = ViewModelStore()
        val gate = ViewModelProvider(models, viewModelFactory { initializer { SplashViewModel(settings, startedAt = 1_000) } })[SplashViewModel::class.java].gate
        runCurrent()
        assertTrue(gate.keepOnScreen(1_100), "a restore let the first frame through before the settings were read")
        settings.value = "read"
        runCurrent()
        assertFalse(gate.keepOnScreen(1_200), "held after the settings were read")
        models.clear()
    }
}
