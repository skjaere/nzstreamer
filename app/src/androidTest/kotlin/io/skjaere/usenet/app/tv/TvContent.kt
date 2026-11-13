package io.skjaere.usenet.app.tv

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.then
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.text.TextLayoutResult
import io.skjaere.usenet.app.collection.TvCollectionKey
import io.skjaere.usenet.app.home.TvHomeKey
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FieldGateLabel
import io.skjaere.usenet.app.ui.FormFactor

/** A TV's screen: 960 x 540 dp, as a 1080p TV at 320 dpi shows the app. */
val TV_SIZE = DpSize(960.dp, 540.dp)

private const val TV_ROOT = "tv-root"

/**
 * [content] as a TV shows it, on a phone emulator: the TV's size and the TV's theme and layouts, at
 * the [fontScale] it reads (the system's text size setting), which may change.
 */
fun ComposeContentTestRule.setTvContent(fontScale: () -> Float = { 1f }, content: @Composable () -> Unit) = setContent {
    DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale()) then DeviceConfigurationOverride.ForcedSize(TV_SIZE)) {
        Box(Modifier.fillMaxSize().testTag(TV_ROOT)) {
            AppTheme(FormFactor.Tv) { content() }
        }
    }
}

/** The TV's screen, in its pixels. */
fun ComposeTestRule.tvScreen(): Rect = onNode(hasTestTag(TV_ROOT)).fetchSemanticsNode().boundsInRoot

/**
 * The keyboard's window on the screen, in pixels, as the system lists it to accessibility; null while there is none
 * (no keyboard up, or one the system doesn't list).
 */
fun keyboardWindowOnScreen(): Rect? {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val info = automation.serviceInfo
    if (info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
    }
    val window = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
    val r = android.graphics.Rect()
    window.getBoundsInScreen(r)
    return Rect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()).takeIf { it.width > 0 && it.height > 0 }
}

/** A field's gate on a TV, by the field's label: the remote's focus lands there, and OK opens the field. */
fun fieldGate(label: String): SemanticsMatcher = SemanticsMatcher.expectValue(FieldGateLabel, label)

/** Search's field focused on a TV: its gate, or the field itself while typing (the keyboard up). */
val searchFieldFocused: SemanticsMatcher = (fieldGate("Search") or hasSetTextAction()) and isFocused()

/** A search result's row, the area of it that plays its group's best release (its "N releases ›" beside it lists them). */
val playsAGroup: SemanticsMatcher = SemanticsMatcher("plays a release group") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Play" }

/** TV Home's content shown (TvHomeContent's list). */
val tvHome: SemanticsMatcher = SemanticsMatcher.keyIsDefined(TvHomeKey)

/** An element inside TV Home's content. */
val inTvHome: SemanticsMatcher = hasAnyAncestor(tvHome)

/** An element inside the TV's Collection content (its grid, its Undo bar). */
val inTvCollection: SemanticsMatcher = hasAnyAncestor(SemanticsMatcher.keyIsDefined(TvCollectionKey))

/** A rail item, by its tab's name. */
fun railItem(label: String): SemanticsMatcher = hasContentDescription(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

/** What a node is called: its description, else its words, as [Remote.focused] says it. */
fun SemanticsNode.label(): String =
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" | ")
        ?: config.getOrNull(SemanticsProperties.Text)?.joinToString(" | ") { it.text }.orEmpty()

/** What a node says: its description and its text. */
fun SemanticsNode.words(): String =
    (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString(" | ")

/** A remote on [compose]'s screen: keys pressed one at a time, the focus read after each. */
class Remote(private val compose: ComposeTestRule) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** What has the focus: its description, else its words; "(nothing)" when nothing has. A menu or a dialog's focus, over the screen's, first. */
    fun focused(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()?.label() ?: "(nothing)"

    fun press(key: Int): String {
        instrumentation.sendKeyDownUpSync(key)
        compose.waitForIdle()
        return focused()
    }

    /** Presses [key] until [words] has the focus, at most [max] times. */
    fun pressUntil(key: Int, words: String, max: Int = 20): String {
        var now = focused()
        repeat(max) {
            if (words in now) return now
            now = press(key)
        }
        return now
    }

    fun waitForFocus(words: String, timeoutMs: Long = 5_000) {
        try {
            compose.waitUntil(timeoutMs) { words in focused() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("focus never on \"$words\"; it is on \"${focused()}\"", e)
        }
    }

    fun isFocused(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher and isFocused()).fetchSemanticsNodes().isNotEmpty()

    /**
     * TV Home's first focus, whatever it is: an Up next card, a poster, the empty card's button, as what earlier tests
     * left in the library has it (#143). The focus inside Home's content, not on the rail.
     */
    fun waitForHomeFocus(timeoutMs: Long = 5_000) {
        try {
            compose.waitUntil(timeoutMs) { isFocused(inTvHome) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the focus never went into Home's content; it is on \"${focused()}\"", e)
        }
    }

    /**
     * The Collection tab's first focus, whatever it is: the switch, an empty side's button, the first poster, as what
     * earlier tests left in the library has it (#143). The focus inside the Collection's content, not on the rail.
     */
    fun waitForCollectionFocus(timeoutMs: Long = 5_000) {
        try {
            compose.waitUntil(timeoutMs) { isFocused(inTvCollection) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the focus never went into the Collection's content; it is on \"${focused()}\"", e)
        }
    }
}

/**
 * Every node with text or that takes the focus, on the TV's screen, whose bounds leave its safe
 * area (48 dp from the sides, 27 dp from the top and bottom), with where it is in dp.
 */
fun ComposeTestRule.outsideTheSafeArea(): List<String> {
    val root = onNode(hasTestTag(TV_ROOT)).fetchSemanticsNode()
    return outsideTheSafeArea(root, root.boundsInRoot.width / TV_SIZE.width.value)
}

/**
 * Each text on the TV's screen (by its words), and whether all of it is inside the safe area as the screen is now. Its
 * whole box counts, not just the part a scrolling container leaves showing, which is all [outsideTheSafeArea] can
 * see: a text scrolled out of view, or partly, is not shown in full.
 */
fun ComposeTestRule.textsShownInFull(): Map<String, Boolean> {
    val root = onNode(hasTestTag(TV_ROOT), useUnmergedTree = true).fetchSemanticsNode()
    val box = root.boundsInRoot
    val dp = box.width / TV_SIZE.width.value
    val safe = Rect(box.left + 48 * dp, box.top + 27 * dp, box.right - 48 * dp, box.bottom - 27 * dp)
    val out = mutableMapOf<String, Boolean>()
    fun walk(node: SemanticsNode) {
        if (node.config.contains(SemanticsProperties.Text)) {
            val b = Rect(node.positionInRoot, node.size.toSize())
            val inside = b.left >= safe.left - dp / 2 && b.top >= safe.top - dp / 2 && b.right <= safe.right + dp / 2 && b.bottom <= safe.bottom + dp / 2
            out[node.words()] = out[node.words()] == true || inside
        }
        node.children.forEach(::walk)
    }
    walk(root)
    return out
}

/**
 * Each text on the TV's screen that is cut, by its words and how: clipped (laid out taller than the room it was given,
 * so its last lines don't show) or ended in an ellipsis. A text's box can be inside the safe area ([textsShownInFull])
 * and its words cut all the same. [intended] picks the texts made to end in an ellipsis when there is no room (an
 * overview, an episode's name), which are left out of that half: clipped, they still count.
 */
fun ComposeTestRule.textsCut(intended: (String) -> Boolean = { false }): List<String> {
    val root = onNode(hasTestTag(TV_ROOT), useUnmergedTree = true).fetchSemanticsNode()
    val out = mutableListOf<String>()
    fun walk(node: SemanticsNode) {
        node.textLayout()?.let { layout ->
            val words = node.words()
            val clipped = layout.size.height < layout.multiParagraph.height - 1
            val ellipsized = layout.lineCount > 0 && layout.isLineEllipsized(layout.lineCount - 1)
            if (clipped) out += "\"$words\" clipped (${layout.lineCount} lines in ${layout.size})"
            else if (ellipsized && !intended(words)) out += "\"$words\" ends in an ellipsis (${layout.lineCount} lines)"
        }
        node.children.forEach(::walk)
    }
    walk(root)
    return out
}

private fun SemanticsNode.textLayout(): TextLayoutResult? {
    if (!config.contains(SemanticsActions.GetTextLayoutResult)) return null
    val results = mutableListOf<TextLayoutResult>()
    config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
    return results.singleOrNull()
}

/** The same for a window of its own that fills the screen, as a sheet's or a dialog's does, by [node] in it. */
fun windowOutsideTheSafeArea(node: SemanticsNode): List<String> {
    val root = generateSequence(node) { it.parent }.last()
    return outsideTheSafeArea(root, root.layoutInfo.density.density)
}

/** What is outside the safe area of [root], whose dp is [dp] px. */
private fun outsideTheSafeArea(root: SemanticsNode, dp: Float): List<String> {
    val box = root.boundsInRoot
    val safe = Rect(box.left + 48 * dp, box.top + 27 * dp, box.right - 48 * dp, box.bottom - 27 * dp)
    val out = mutableListOf<String>()
    fun walk(node: SemanticsNode) {
        val b = node.boundsInRoot
        val shows = node.config.contains(SemanticsProperties.Text) || node.config.contains(SemanticsProperties.Focused)
        // A tolerance of half a dp for rounding at the scaled density.
        if (shows && b.width > 0 && b.height > 0 &&
            (b.left < safe.left - dp / 2 || b.top < safe.top - dp / 2 || b.right > safe.right + dp / 2 || b.bottom > safe.bottom + dp / 2)
        ) {
            out += "\"${node.words()}\" at %.0f,%.0f–%.0f,%.0f dp".format((b.left - box.left) / dp, (b.top - box.top) / dp, (b.right - box.left) / dp, (b.bottom - box.top) / dp)
        }
        node.children.forEach(::walk)
    }
    walk(root)
    return out
}
