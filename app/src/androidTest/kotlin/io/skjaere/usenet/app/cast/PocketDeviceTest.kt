package io.skjaere.usenet.app.cast

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A phone left in a pocket by a test whose process died in it (a crash, or the sharded runner's watchdog force-stopping
 * it) is put back by the next process's runner before its first test, as the pocket's mark says ([Pocket.putBackLeft]):
 * Doze, battery saver, the standby bucket, the screen and the battery as the pocket found them, and the mark gone. On the
 * test's own emulator, through the shell; put back after whatever happens.
 */
@RunWith(AndroidJUnit4::class)
class PocketDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val pocket = Pocket(context)

    @Before
    fun setUp() {
        assumeFalse("a TV never casts (tv.TvNoCastTest)", context.container.television)
    }

    @After
    fun restore() {
        assertEquals("the phone not put back as it was", emptyList<String>(), pocket.restore())
    }

    @Test
    fun aPocketLeftByAProcessThatDiedInItIsPutBackBeforeTheNextOnesTests() {
        pocket.enter()
        pocket.still()
        // The process dies here, in the pocket; the next one's runner, before its first test:
        assertTrue("a pocket was left", Pocket.putBackLeft(instrumentation.uiAutomation, context.packageName))
        assertEquals("not back as found", emptyList<String>(), pocket.notAsFound())
        assertFalse("its mark gone: the runner after finds none", Pocket.putBackLeft(instrumentation.uiAutomation, context.packageName))
    }
}
