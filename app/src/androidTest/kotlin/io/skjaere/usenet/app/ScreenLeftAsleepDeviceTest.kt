package io.skjaere.usenet.app

import android.app.KeyguardManager
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen left off or locked by a test whose process died before it woke it again (W106's tests turn it off and on;
 * a crash, or the sharded runner's watchdog force-stopping it) is woken and unlocked by the next process's runner before
 * its first test ([wakeLeft]), as a pocket left is put back: the tests after it find their activities in front, not
 * stopped behind the lock screen. On the test's own emulator, through the shell; woken after whatever happens.
 */
@RunWith(AndroidJUnit4::class)
class ScreenLeftAsleepDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    @After
    fun wake() {
        wakeAndUnlock()
    }

    @Test
    fun aScreenLeftOffByAProcessThatDiedIsWokenBeforeTheNextOnesTests() = runBlocking<Unit> {
        instrumentation.uiAutomation.shell("input keyevent KEYCODE_SLEEP")
        waitFor("the screen off") { true.takeIf { !power.isInteractive } }
        // The process dies here, the screen off; the next one's runner, before its first test:
        val woken = wakeLeft(instrumentation.uiAutomation)
        assertTrue("the screen still off before the first test", power.isInteractive)
        assertFalse("the lock screen up before the first test", keyguard.isKeyguardLocked)
        assertTrue("the screen left off not said to be woken", woken)
        assertFalse("awake: the runner after finds nothing to wake", wakeLeft(instrumentation.uiAutomation))
    }
}
