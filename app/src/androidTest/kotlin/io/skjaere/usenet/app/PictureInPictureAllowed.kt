package io.skjaere.usenet.app

import android.app.UiAutomation
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * The app's picture-in-picture switch as Android keeps it: the PICTURE_IN_PICTURE app-op, which Settings › Apps › Special
 * app access › Picture-in-picture turns off and on, and which Android reads before it lets the app into the window. Off,
 * the app never goes in, and Home stops the player as it always has. Read and set as the shell does (`appops`), which the
 * instrumentation may.
 */
object PictureInPictureAppOp {
    private const val OP = "PICTURE_IN_PICTURE"

    /** The app's own mode, as `appops get` prints it ("PICTURE_IN_PICTURE: ignore; rejectTime=..."). */
    private val OWN = Regex("""^$OP: (\w+)""")

    /** The op's default, which `appops get` prints for an app whose mode was never set ("Default mode: allow"). */
    private val DEFAULT = Regex("""^Default mode: (\w+)""")

    /** Allowed: the only mode Android lets into the window. */
    const val ALLOW = "allow"

    /** Refused, quietly: entering returns false, as with the app's switch off. */
    const val IGNORE = "ignore"

    /**
     * The app's mode: "allow", "ignore", "deny", "default" or "foreground"; null when `appops` gives none. [automation]
     * runs the shell's commands.
     */
    fun mode(automation: UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation): String? {
        val lines = automation.shell("appops get ${packageName()} $OP").lines()
        val found = lines.firstNotNullOfOrNull { OWN.find(it) } ?: lines.firstNotNullOfOrNull { DEFAULT.find(it) }
        return found?.groupValues?.get(1)
    }

    /** Sets the app's mode to [mode], a name [mode] gives. Set to the op's default ("allow"), it is forgotten, as if never set. */
    fun set(mode: String, automation: UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation) {
        automation.shell("appops set ${packageName()} $OP $mode")
    }

    private fun packageName() = InstrumentationRegistry.getInstrumentation().targetContext.packageName
}

/**
 * Lets the app into picture-in-picture while each test runs, the run refusing it otherwise ([AppTestRunner]): for the
 * window's own tests, and for those that leave a player where it must not go into the window (its setup under way, the
 * "Playback stopped" panel up, a TV's frame-rate wait), which would pass whatever the player did with the app refused.
 * Before each test the screen is woken and unlocked, as Android lets nothing into the window over the lock screen; after
 * it, none of ours may be left in the window ([assertNoPictureInPictureLeft]), and the app-op is put back as the test
 * found it.
 */
class PictureInPictureAllowed : ExternalResource() {
    /** The app's mode as the test found it; null when `appops` gave none, and then left allowed. */
    private var found: String? = null

    override fun before() {
        wakeAndUnlock()
        found = PictureInPictureAppOp.mode()
        PictureInPictureAppOp.set(PictureInPictureAppOp.ALLOW)
    }

    override fun after() {
        try {
            assertNoPictureInPictureLeft()
        } finally {
            found?.let { PictureInPictureAppOp.set(it) }
        }
    }
}
