package io.skjaere.usenet.app

import android.Manifest
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.view.KeyEvent
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit

// The player's media session as what is outside the app finds it, for its device tests: a Bluetooth headset's or the
// Assistant's keys (mediaKey), the sessions Android lists (ourSessions, mediaButtonSessionIsOurs), and a controller in the
// test's own process (controllerOf). None of them prints what Android lists.

private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

/** A media key pressed and let go as a Bluetooth headset's comes: through Android to the session it hands media keys to, never through the player's window. */
fun mediaKey(keyCode: Int) {
    val audio = instrumentation.targetContext.getSystemService(AudioManager::class.java)
    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
}

/** The app's media sessions Android lists as active (it lists every app's to the shell, whose permission the test takes for the read). */
fun ourSessions(): Int {
    val automation = instrumentation.uiAutomation
    automation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL)
    return try {
        val sessions = instrumentation.targetContext.getSystemService(MediaSessionManager::class.java)
        sessions.getActiveSessions(null).count { it.packageName == instrumentation.targetContext.packageName }
    } finally {
        automation.dropShellPermissionIdentity()
    }
}

/** Whether the session Android hands media keys to (a headset's, the Assistant's, the window's) is the app's: `dumpsys media_session` names it, read here and never printed. */
fun mediaButtonSessionIsOurs(): Boolean {
    val app = instrumentation.targetContext.packageName
    return instrumentation.uiAutomation.shell("dumpsys media_session").lines()
        .filter { it.trim().startsWith("Media button session is") }
        .any { "$app/" in it }
}

/** A Media3 controller of [token] in the test's process, as another app's would be: made and used on the main thread, released by the caller. */
fun controllerOf(token: SessionToken): MediaController =
    onMainThread { MediaController.Builder(instrumentation.targetContext, token).buildAsync() }.get(10, TimeUnit.SECONDS)

/** [block] on the main thread, which a controller and the player want. */
fun <T> onMainThread(block: () -> T): T {
    var result: Result<T>? = null
    instrumentation.runOnMainSync { result = runCatching(block) }
    return result!!.getOrThrow()
}
