package io.skjaere.usenet.app.screens

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.concurrent.thread

/**
 * Saves the README screenshots: each screen as a PNG taken by `screencap` (which has the player's video in it, as a
 * Compose capture wouldn't), and beside it a .txt with every word on the screen, read from the accessibility tree of
 * every window (the app's, its dialogs and the system bars), so each word can be checked against the invented names.
 * Both go to [DIR], which outlives the app's uninstall at the end of a Gradle run; tools/screens/capture.sh pulls them.
 */
class ScreenShots {
    private val automation: UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation.also { a ->
        a.serviceInfo = a.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    }

    init {
        shell("mkdir -p $DIR")
    }

    /** [name].png and [name].txt: the screen as it is now, and its words. */
    fun save(name: String) {
        require(NAME.matches(name)) { "a screen's name is lowercase words and dashes: $name" }
        // The accessibility tree follows the screen a frame or two behind.
        SystemClock.sleep(SETTLE_MS)
        shell("screencap -p $DIR/$name.png")
        write("$DIR/$name.txt", words().joinToString("\n", postfix = "\n"))
    }

    /**
     * Every word shown, window by window from the top: each node's text, its description, state and hint, once, in the
     * order the tree has them. Nodes off the screen aren't counted.
     */
    fun words(): List<String> {
        val lines = mutableListOf<String>()
        automation.windows.sortedByDescending { it.layer }.forEach { window ->
            val root = window.root ?: return@forEach
            lines += "# window: ${window.title ?: "(untitled)"}"
            walk(root) { node ->
                listOfNotNull(node.text, node.contentDescription, node.stateDescription, node.hintText)
                    .map { it.toString().trim() }.filter { it.isNotEmpty() }.distinct()
                    .forEach { lines += it }
            }
        }
        return lines
    }

    private fun walk(node: AccessibilityNodeInfo, each: (AccessibilityNodeInfo) -> Unit) {
        if (!node.isVisibleToUser) return
        each(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, each) }
    }

    /** Runs [command] as the shell user and waits for it to finish; what it printed. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    /** Writes [text] to [path] as the shell user, through tee's input: the test's own process can't write there. */
    private fun write(path: String, text: String) {
        val (out, input) = automation.executeShellCommandRw("tee $path")
        val drain = thread { ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() } }
        ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write(text.encodeToByteArray()) }
        drain.join(10_000)
    }

    companion object {
        /** Where the screens go on the device: the shell's, kept over the uninstall. */
        const val DIR = "/data/local/tmp/readme-screens"
        private val NAME = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        private const val SETTLE_MS = 400L
    }
}
