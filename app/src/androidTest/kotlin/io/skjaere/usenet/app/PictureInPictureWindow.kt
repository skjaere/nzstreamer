package io.skjaere.usenet.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.KeyguardManager
import android.app.UiAutomation
import android.hardware.display.DisplayManager
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

// Picture-in-picture as the user drives it, for the device tests of the player's window: Home (pressHome), the window
// once it has settled (awaitPinned), a tap on it and its menu's Expand or Close (expandFromMenu, closeFromMenu), the
// menu as AOSP's window manager shows it on the phone emulators. A screen asleep or locked lets nothing into the window
// (wakeAndUnlock). After each test none of ours may be left (assertNoPictureInPictureLeft).

private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

private const val TAG = "PictureInPicture"

/** The menu's buttons, by their ids in the system UI's window manager (AOSP's pip_menu.xml). */
private const val EXPAND = "com.android.systemui:id/expand_button"
private const val CLOSE = "com.android.systemui:id/dismiss"

/** Reads in a row, 100 ms apart, that must find the window's place unchanged before it counts as settled. */
private const val SETTLED = 5

/** How long a tap is given to bring up the menu before the window is tapped again: tapped again as the menu shows, a tap could press Expand. */
private const val TAP_AGAIN_MS = 4_000L

/** How long a press of a menu button is given before it is pressed again: one made as the menu fades in does nothing. */
private const val PRESS_AGAIN_MS = 1_000L

/** Runs [command] as the shell user and waits for it to finish; what it printed. */
internal fun UiAutomation.shell(command: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(executeShellCommand(command)).use { it.readBytes().decodeToString() }.trim()

/** Home, as its key does: the home screen comes to the front, and a player the app lets go into the window goes in. */
fun pressHome() {
    instrumentation.uiAutomation.shell("input keyevent KEYCODE_HOME")
}

/**
 * The screen on and unlocked, as the user has it while watching: Android lets nothing into the window over the lock screen.
 * [automation] runs the shell's commands.
 */
fun wakeAndUnlock(automation: UiAutomation = instrumentation.uiAutomation) {
    automation.shell("input keyevent KEYCODE_WAKEUP")
    automation.shell("wm dismiss-keyguard")
    until("the screen on and unlocked") { awake() }
}

/**
 * The screen a test left off or locked as its process died (W106's tests turn it off and on: a crash, or the sharded
 * runner's watchdog force-stopping it, before the test woke it) woken and unlocked, by the next process's runner before
 * its first test, through [automation] ([AppTestRunner]): whether it was off or locked.
 */
fun wakeLeft(automation: UiAutomation): Boolean {
    if (awake()) return false
    wakeAndUnlock(automation)
    return true
}

/** The screen on and unlocked. */
private fun awake(): Boolean {
    val context = instrumentation.targetContext
    return context.getSystemService(PowerManager::class.java).isInteractive && !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
}

/**
 * Waits for [activity] to be in picture-in-picture, then for its window to settle where it floats: the mode can come
 * before the window has shrunk to its place, and a player in landscape goes into the window as the screen turns back to
 * the home screen's portrait, so the place is read until it is the screen's lesser part, inside the screen as it is
 * turned now, and the same [SETTLED] times running. The place is where the system lists the window ([windowOnScreen]);
 * the turn can take a loaded emulator a while, so it is given [settleMs]. The window's bounds on the screen, in pixels.
 */
suspend fun awaitPinned(activity: Activity, timeoutMs: Long = 10_000, settleMs: Long = 30_000): Rect {
    waitFor("${activity.javaClass.simpleName} in picture-in-picture", timeoutMs) { onMain { activity.isInPictureInPictureMode }.takeIf { it } }
    var last: Rect? = null
    var same = 0
    return try {
        waitFor("${activity.javaClass.simpleName}'s window to settle", settleMs) {
            val now = windowOnScreen(activity)
            val screen = screenNow()
            same = if (now == last) same + 1 else 0
            last = now
            now.takeIf { same >= SETTLED && !it.isEmpty && screen.contains(it) && it.width().toLong() * it.height() * 2 < screen.width().toLong() * screen.height() }
        }.also { Log.i(TAG, "Settled at ${it.toShortString()}, ${if (floatingWindow() != null) "as the system lists it" else "as the activity has it"}") }
    } catch (e: AssertionError) {
        val mode = onMain { activity.isInPictureInPictureMode }
        val own = onMain { ownWindow(activity) }
        throw AssertionError(
            "${e.message}: last at ${last?.toShortString()}, the same ${same + 1} reads running, in the window $mode; " +
                "the activity's own view of it at ${own.toShortString()}, the screen ${screenNow().toShortString()}",
            e,
        )
    }
}

/** [activity]'s window on the screen now, in pixels: where it floats while in picture-in-picture ([windowOnScreen]). */
fun windowNow(activity: Activity): Rect = windowOnScreen(activity)

/**
 * The app's button that says [words] as the system lists it (the play sheet's Cancel, in the sheet's own window): the
 * clickable node that holds the words, in any window of the app's; null while none shows. Its place is [onScreen]; a
 * press is an ACTION_CLICK on it.
 */
fun appButton(words: String): AccessibilityNodeInfo? {
    val app = instrumentation.targetContext.packageName
    return everyWindowListed().windows.mapNotNull { it.root }.filter { it.packageName == app }
        .firstNotNullOfOrNull { root -> nodeIn(root) { it.text?.toString() == words || it.contentDescription?.toString() == words } }
        ?.let { node -> generateSequence(node) { it.parent }.firstOrNull { it.isClickable } }
}

/** This node's place on the screen, in pixels. */
fun AccessibilityNodeInfo.onScreen(): Rect = Rect().also(::getBoundsInScreen)

/** The first node in [node]'s tree that is [wanted], depth first. */
private fun nodeIn(node: AccessibilityNodeInfo, wanted: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
    if (wanted(node)) node else (0 until node.childCount).firstNotNullOfOrNull { i -> node.getChild(i)?.let { nodeIn(it, wanted) } }

/** Expands [activity]'s window as the user does: a tap on it, then its menu's Expand. Back full screen, the activity resumes. */
suspend fun expandFromMenu(activity: Activity) = pressInMenu(activity, EXPAND)

/** Closes [activity]'s window as the user does: a tap on it, then its menu's Close (the ✕). Android stops the activity first. */
suspend fun closeFromMenu(activity: Activity) = pressInMenu(activity, CLOSE)

/**
 * The check after a test of the window: no activity of ours is left in picture-in-picture. Android lets an app go into
 * the window by itself only while no other window floats, so one left behind would keep the next test's from opening;
 * it is closed here and the test fails. Any other activity of ours still alive is finished too, quietly: a player
 * stopped behind the home screen, as a closed window leaves one, would take the next test's launch (the player is
 * singleTask).
 */
fun assertNoPictureInPictureLeft() {
    val left = onMain { liveActivities() }
    if (left.isEmpty()) return
    val pinned = onMain { left.filter { it.isInPictureInPictureMode } }
    Log.w(TAG, "Left alive after the test, finished now: ${left.map { it.javaClass.simpleName }}; in the window: ${pinned.size}")
    instrumentation.runOnMainSync { left.forEach(Activity::finish) }
    until("the activities left to close", 10_000) { onMain { liveActivities().none { it in left } } }
    if (pinned.isNotEmpty()) throw AssertionError("left in picture-in-picture (closed now): ${pinned.map { it.javaClass.simpleName }}")
}

/**
 * Taps [activity]'s window until its menu shows, then presses the menu's [button] until the activity is out of
 * picture-in-picture. The menu hides itself after a few seconds, so the window is tapped again once the menu has stayed
 * away a while; never while the menu shows, where the window's middle is Expand. A press while the menu still fades in
 * does nothing (AOSP's PipMenuView), so the button is pressed again until it takes.
 */
private suspend fun pressInMenu(activity: Activity, button: String, timeoutMs: Long = 20_000) {
    awaitPinned(activity)
    var tappedAt = Long.MIN_VALUE / 2
    var pressedAt = Long.MIN_VALUE / 2
    waitFor("${button.substringAfter('/')} pressed in the window's menu", timeoutMs) {
        if (!onMain { activity.isInPictureInPictureMode }) return@waitFor true
        val now = SystemClock.elapsedRealtime()
        val shown = menuButton(button)
        if (shown != null && now - pressedAt >= PRESS_AGAIN_MS) {
            shown.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            pressedAt = now
        } else if (shown == null && now - tappedAt >= TAP_AGAIN_MS) {
            // Where the window is now: it can move as the screen turns or the keyboard comes and goes.
            val window = windowOnScreen(activity)
            instrumentation.uiAutomation.shell("input tap ${window.centerX()} ${window.centerY()}")
            tappedAt = now
        }
        null
    }
}

/** The window's menu's [id] button while the menu shows; null otherwise (the system lists the menu's window only while it shows). */
private fun menuButton(id: String): AccessibilityNodeInfo? =
    everyWindowListed().windows.firstNotNullOfOrNull { it.root?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull() }

/** The instrumentation's UiAutomation, set to list every window on the screen with its views' ids: by default it lists only the active one. */
private fun everyWindowListed(): UiAutomation {
    val automation = instrumentation.uiAutomation
    val wanted = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
    val info = automation.serviceInfo
    if ((info.flags and wanted) != wanted) {
        info.flags = info.flags or wanted
        automation.serviceInfo = info
    }
    return automation
}

/**
 * The screen as it is turned now, in pixels from its top left: from the display's own mode, as the process's
 * configuration, which Resources.getSystem() follows, becomes the window's once the app's only activity floats in it.
 */
private fun screenNow(): Rect {
    val display = instrumentation.targetContext.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    val (wide, high) = display.mode.let { it.physicalWidth to it.physicalHeight }
    val turned = display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270
    return if (turned) Rect(0, 0, high, wide) else Rect(0, 0, wide, high)
}

/**
 * [activity]'s window on the screen, in pixels: while it floats, where the system lists the picture-in-picture window,
 * which follows it as the screen turns, where the activity's own view of its place ([ownWindow]) can keep the place from
 * before the turn for seconds; its own view while nothing floats.
 */
private fun windowOnScreen(activity: Activity): Rect = floatingWindow() ?: onMain { ownWindow(activity) }

/** The picture-in-picture window as the system lists it, in pixels on the screen; null while none floats. */
private fun floatingWindow(): Rect? =
    everyWindowListed().windows.firstOrNull { it.isInPictureInPictureMode }?.let { window -> Rect().also(window::getBoundsInScreen) }

/** [activity]'s window on the screen as the activity's own views have it, in pixels (on the main thread). */
private fun ownWindow(activity: Activity): Rect {
    val decor = activity.window.decorView
    val at = IntArray(2).also(decor::getLocationOnScreen)
    return Rect(at[0], at[1], at[0] + decor.width, at[1] + decor.height)
}

/** Every activity of this process not yet destroyed (on the main thread, as the monitor wants). */
private fun liveActivities(): List<Activity> {
    val monitor = ActivityLifecycleMonitorRegistry.getInstance()
    return LIVE.flatMap(monitor::getActivitiesInStage).distinct()
}

private val LIVE = listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)

private fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    instrumentation.runOnMainSync { result = runCatching(block) }
    return result!!.getOrThrow()
}

/** Polls [probe] every 100 ms until it holds, for at most [timeoutMs]; for the rule's steps, which can't suspend. */
private fun until(what: String, timeoutMs: Long = 5_000, probe: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (!probe()) {
        if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
        SystemClock.sleep(100)
    }
}
