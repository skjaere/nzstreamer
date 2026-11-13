package io.skjaere.usenet.app.cast

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.shell
import io.skjaere.usenet.app.wakeAndUnlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * The phone in a pocket (U3, CastServeEndToEndDeviceTest): unplugged, battery saver on, the app in the restricted standby
 * bucket, the screen off and Doze forced, each read back as it is entered. [restore] puts each back, the last first,
 * whatever happened, reads each back against the phone as the pocket found it, and says what isn't. Every command is the
 * shell's, on the test's own emulator, through UiAutomation.
 *
 * All of it outlives the app, so a test whose process dies in the pocket (a crash, or a runner's watchdog force-stopping
 * it) can't put it back itself. The pocket leaves a mark on the emulator as it is entered ([MARK], in the shell's own
 * folder, which the app's uninstall leaves alone), gone once everything is back as found; the next process's runner puts
 * back what a mark says was left ([putBackLeft]) before its first test.
 */
class Pocket(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val app = context.packageName

    /** What puts back each part entered, the last first. */
    private val back = ArrayDeque<Pair<String, () -> Unit>>()

    /** The phone as the pocket found it: plugged in or not, battery saver on or not, and the bucket it puts the app back in. */
    private var found: Found? = null

    private data class Found(val plugged: Boolean, val saver: Boolean, val bucket: Int)

    fun enter() {
        val bucket = shell("am get-standby-bucket $app").toIntOrNull()
        // Below active (exempt) can't be set back by the shell: active is the nearest.
        val bucketBack = bucket?.takeIf { it > ACTIVE } ?: ACTIVE
        found = Found(plugged(), power.isPowerSaveMode, bucketBack)
        // Marked before anything changes: a process that dies from here on leaves it for the next one's runner.
        shell("mkdir -p $MARK")
        shell("touch $MARK/$BUCKET$bucketBack")
        back.addFirst("the battery" to { shell("dumpsys battery reset") })
        shell("dumpsys battery unplug")
        waitUntil("unplugged") { !plugged() }
        // Battery saver can be set only while unplugged, which Android's power service may hear a moment after the battery
        // says so: asked until it takes. Put back before the battery is.
        back.addFirst("battery saver" to { shell("cmd power set-mode 0") })
        waitUntil("battery saver on") {
            shell("cmd power set-mode 1")
            power.isPowerSaveMode
        }
        back.addFirst("the standby bucket" to { shell("am set-standby-bucket $app $bucketBack") })
        shell("am set-standby-bucket $app restricted")
        assertEquals("the app in the restricted bucket", "$RESTRICTED", shell("am get-standby-bucket $app"))
        back.addFirst("the screen" to { wakeAndUnlock() })
        shell("input keyevent KEYCODE_SLEEP")
        waitUntil("the screen off") { !power.isInteractive }
        back.addFirst("Doze" to { shell("dumpsys deviceidle unforce") })
        var forced = shell("dumpsys deviceidle force-idle")
        if ("not enabled" in forced) {
            // Deep Doze off on this image: on for the test, and off again after.
            back.addFirst("deep Doze enabled" to { shell("dumpsys deviceidle disable deep") })
            shell("touch $MARK/$DEEP")
            shell("dumpsys deviceidle enable deep")
            forced = shell("dumpsys deviceidle force-idle")
        }
        assertTrue("Doze not forced: $forced", "forced" in forced)
        waitUntil("Doze") { power.isDeviceIdleMode }
    }

    /** Still in it, as entered. */
    fun still() {
        assertTrue("in Doze", power.isDeviceIdleMode)
        assertTrue("battery saver on", power.isPowerSaveMode)
        assertFalse("the screen off", power.isInteractive)
        assertFalse("unplugged", plugged())
        assertEquals("the app in the restricted bucket", "$RESTRICTED", shell("am get-standby-bucket $app"))
    }

    /**
     * Puts each part back, the last first, and reads each back: what couldn't be put back, or isn't as found. With all
     * of it back, the mark goes; otherwise it stays, for the next process's runner to try again.
     */
    fun restore(): List<String> {
        val failed = mutableListOf<String>()
        while (back.isNotEmpty()) {
            val (what, undo) = back.removeFirst()
            try {
                undo()
            } catch (e: Exception) {
                failed += "$what: ${e.javaClass.simpleName}"
            } catch (e: AssertionError) {
                failed += "$what: ${e.message}"
            }
        }
        failed += notAsFound()
        if (failed.isEmpty()) shell("rm -rf $MARK")
        return failed
    }

    /** What isn't as the pocket found it (none before it is entered), each given a few seconds to come back. */
    fun notAsFound(): List<String> {
        val was = found ?: return emptyList()
        return listOfNotNull(
            "Doze".takeUnless { settles { !power.isDeviceIdleMode } },
            "battery saver".takeUnless { settles { power.isPowerSaveMode == was.saver } },
            "the screen".takeUnless { settles { power.isInteractive } },
            "the battery".takeUnless { settles { plugged() == was.plugged } },
            "the standby bucket".takeUnless { settles { shell("am get-standby-bucket $app") == "${was.bucket}" } },
        )
    }

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.shell(command)

    private fun plugged(): Boolean =
        (context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    private fun waitUntil(what: String, probe: () -> Boolean) = assertTrue("timed out waiting for $what", settles(probe))

    /** Whether [probe] holds within [SETTLE_MS]. */
    private fun settles(probe: () -> Boolean): Boolean {
        val until = SystemClock.uptimeMillis() + SETTLE_MS
        while (!probe()) {
            if (SystemClock.uptimeMillis() > until) return false
            Thread.sleep(50)
        }
        return true
    }

    companion object {
        /** The mark a pocket leaves while entered: a folder of the shell's, its files saying what to put back. */
        private const val MARK = "/data/local/tmp/nzstreamer-pocket"
        private const val BUCKET = "bucket-"
        private const val DEEP = "deep-doze-enabled"

        /** UsageStatsManager's buckets: active, and restricted. */
        private const val ACTIVE = 10
        const val RESTRICTED = 45

        private const val SETTLE_MS = 5_000L

        /**
         * A pocket left by a test whose process died in it, put back as its mark says, the last part entered first, as
         * [restore] does (the next process's runner, before its first test, through [automation]): whether one was left.
         * It doesn't wait for the screen: a test that needs it waits itself.
         */
        fun putBackLeft(automation: UiAutomation, app: String): Boolean {
            val marks = automation.shell("ls $MARK").lines().map(String::trim)
            val bucket = marks.firstOrNull { it.startsWith(BUCKET) }?.removePrefix(BUCKET) ?: return false
            automation.shell("dumpsys deviceidle unforce")
            if (DEEP in marks) automation.shell("dumpsys deviceidle disable deep")
            automation.shell("input keyevent KEYCODE_WAKEUP")
            automation.shell("wm dismiss-keyguard")
            automation.shell("am set-standby-bucket $app $bucket")
            automation.shell("cmd power set-mode 0")
            automation.shell("dumpsys battery reset")
            automation.shell("rm -rf $MARK")
            return true
        }
    }
}
