package io.skjaere.usenet.app.download

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Android's notification ask at the first Download (L358, item 11; DlPhoneNotification frames 1 and 2): on Android 13 and
 * later only, where downloads show, once; either answer lets the download start, and so does an activity gone before the
 * answer. Asked and answered here through fakes of the activity's launcher and the saved setting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationAskTest {
    /** The saved "asked" setting, Android's launcher and its answer, faked. */
    private class Fakes(var sdk: Int = 33, var shown: Boolean = true, var granted: Boolean = false, var asked: Boolean = false) {
        var launches = 0
        var launchFails = false
        lateinit var ask: NotificationAsk

        fun make() = NotificationAsk(
            sdk = sdk, shown = { shown }, granted = { granted }, asked = { asked }, markAsked = { asked = true },
            launch = {
                if (launchFails) throw IllegalStateException("the activity is gone")
                launches++
            },
        ).also { ask = it }
    }

    @Test
    fun `due on Android 13 and later only, where downloads show, until asked, and not once allowed`() {
        assertTrue(NotificationAsk.due(sdk = 33, shown = true, asked = false, granted = false))
        assertTrue(NotificationAsk.due(sdk = 36, shown = true, asked = false, granted = false))
        assertFalse(NotificationAsk.due(sdk = 32, shown = true, asked = false, granted = false), "Android 12 asks nothing")
        assertFalse(NotificationAsk.due(sdk = 26, shown = true, asked = false, granted = false))
        assertFalse(NotificationAsk.due(sdk = 33, shown = false, asked = false, granted = false), "never where downloads don't show")
        assertFalse(NotificationAsk.due(sdk = 33, shown = true, asked = true, granted = false), "once")
        assertFalse(NotificationAsk.due(sdk = 33, shown = true, asked = false, granted = true), "allowed already")
    }

    @Test
    fun `whether it is due reads the device, where downloads show, the saved setting and the permission now`() = runTest {
        val fakes = Fakes()
        val ask = fakes.make()
        assertTrue(ask.due(), "the Download sheet's line shows")
        fakes.granted = true
        assertFalse(ask.due(), "allowed since")
        fakes.granted = false
        fakes.asked = true
        assertFalse(ask.due(), "asked before")
        assertFalse(Fakes(sdk = 32).make().due())
        assertFalse(Fakes(shown = false).make().due())
        assertEquals(0, fakes.launches, "nothing asked of Android")
    }

    @Test
    fun `the first Download asks once, saved before Android's dialog, and goes on with either answer`() = runTest {
        val fakes = Fakes()
        val ask = fakes.make()
        val first = async { ask.beforeDownload() }
        runCurrent()
        assertEquals(1, fakes.launches)
        assertTrue(fakes.asked, "saved as asked before the answer, so a death meanwhile never asks again")
        assertFalse(first.isCompleted, "waits for the answer")
        // A second Download while Android asks waits for the same answer.
        val second = async { ask.beforeDownload() }
        runCurrent()
        assertEquals(1, fakes.launches)
        ask.answered()
        runCurrent()
        assertTrue(first.isCompleted && second.isCompleted)
        // Refused or allowed, it was asked: the next Download starts at once.
        ask.beforeDownload()
        assertEquals(1, fakes.launches)
    }

    @Test
    fun `nothing is asked below Android 13, where downloads don't show, or once allowed`() = runTest {
        listOf(Fakes(sdk = 32), Fakes(shown = false), Fakes(granted = true), Fakes(asked = true)).forEach { fakes ->
            fakes.make().beforeDownload()
            assertEquals(0, fakes.launches)
        }
        assertFalse(Fakes(sdk = 32).also { it.make().beforeDownload() }.asked, "not saved as asked when nothing was asked")
    }

    @Test
    fun `an activity gone before Android answers lets the download start`() = runTest {
        val fakes = Fakes()
        val ask = fakes.make()
        val waiting = async { ask.beforeDownload() }
        runCurrent()
        // The activity ends with the dialog up: its end answers for it.
        ask.answered()
        runCurrent()
        assertTrue(waiting.isCompleted)
        // One whose launcher can't launch any more goes on at once.
        val gone = Fakes().apply { launchFails = true }
        gone.make().beforeDownload()
        assertEquals(0, gone.launches)
    }
}
