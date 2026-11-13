package io.skjaere.usenet.app

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry

/**
 * [code] pressed on a remote, as a TV's HDMI-CEC remote sends it (from the virtual device, its source HDMI). A text
 * field moves the focus on a remote's arrows; on a keyboard's, which is what an instrumentation key is, it moves its
 * cursor instead. Android 14's emulators take it; the TV emulator's Android 16 doesn't deliver it.
 */
fun sendRemoteKey(code: Int) {
    val now = SystemClock.uptimeMillis()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
        instrumentation.sendKeySync(KeyEvent(now, now, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_HDMI))
    }
}
