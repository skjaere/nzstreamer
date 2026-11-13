package io.skjaere.usenet.app

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Marks Android's "Viewing full screen" hint as seen while a test runs, and puts the setting back
 * after. Android shows it the first time an app hides the system bars, as the player does, in a
 * window of its own over the player that takes the window focus, and with it the keys, until the
 * user dismisses it: a test that drives the player by keys or watches its focus would see neither.
 */
class ImmersiveHintSeen : ExternalResource() {
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    /** The setting as the device had it; "null" while unset. */
    private var before: String? = null

    override fun before() {
        before = shell("settings get secure $SETTING")
        shell("settings put secure $SETTING confirmed")
    }

    override fun after() {
        when (val was = before) {
            null, "confirmed" -> Unit
            "null" -> shell("settings delete secure $SETTING")
            else -> shell("settings put secure $SETTING $was")
        }
    }

    /** Runs [command] as the shell user, waiting for it to finish; returns what it printed. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().decodeToString() }.trim()

    private companion object {
        /** Settings.Secure's apps whose hint was dismissed, or "confirmed" for all. */
        const val SETTING = "immersive_mode_confirmations"
    }
}
