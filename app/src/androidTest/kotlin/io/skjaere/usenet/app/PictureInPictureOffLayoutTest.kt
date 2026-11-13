package io.skjaere.usenet.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.play.PictureInPictureOff
import io.skjaere.usenet.app.play.WindowOff
import io.skjaere.usenet.app.ui.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PipPlayerOff's words under the player's top bar, in the room PlayerActivity gives them (the screen less 48 dp at each
 * side): on an 844 dp wide landscape phone one line beside Open settings; on a 640 dp wide one two even lines,
 * "Picture-in-picture is off for" over "NZStreamer in Android’s settings", the pill hugging them rather than leaving room
 * before its button.
 */
@RunWith(AndroidJUnit4::class)
class PictureInPictureOffLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    /** On a [screen] dp wide landscape phone; the test's own activity is a portrait one, narrower. */
    private fun show(screen: Dp) {
        compose.setContent {
            AppTheme {
                Box(Modifier.wrapContentSize(unbounded = true).requiredSize(screen - 96.dp, 200.dp)) {
                    PictureInPictureOff(WindowOff.words("NZStreamer"), onOpenSettings = {}, modifier = Modifier.align(Alignment.TopCenter).testTag("pill"))
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun TextLayoutResult.lines() = (0 until lineCount).map { layoutInput.text.substring(getLineStart(it), getLineEnd(it)).trim() }

    @Test
    fun onAWidePhoneTheWordsAreOneLine() {
        show(844.dp)
        val words = compose.onNodeWithText(WORDS).textLayout()
        assertEquals(listOf(WORDS), words.lines())
    }

    @Test
    fun onA640DpPhoneTheyAreTwoEvenLinesThePillHugs() {
        show(640.dp)
        val words = compose.onNodeWithText(WORDS).textLayout()
        assertEquals(listOf("Picture-in-picture is off for", "NZStreamer in Android’s settings"), words.lines())
        val widest = (0 until words.lineCount).maxOf { words.getLineRight(it) - words.getLineLeft(it) }
        assertTrue("the words ${words.size.width} px wide, their widest line $widest px", words.size.width - widest < 2f)
        val room = (640 - 96).dp
        val pill = compose.onNodeWithTag("pill").getBoundsInRoot()
        assertTrue("the pill ${pill.right - pill.left} wide in $room", pill.right - pill.left < room - 100.dp)
    }

    private companion object {
        const val WORDS = "Picture-in-picture is off for NZStreamer in Android’s settings"
    }
}
