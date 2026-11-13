package io.skjaere.usenet.app.collection

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.collection.model.LetterIndex
import io.skjaere.usenet.app.ui.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * The letter rail's gesture loop outlives a recomposition (it starts again only with new letters): a
 * tap after the screen changed goes where the screen now says (the list's header a note longer), and
 * the bubble goes when the letters change under the finger.
 */
@RunWith(AndroidJUnit4::class)
class LetterRailDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val all = listOf('#') + ('A'..'Z')
    private var letters by mutableStateOf(index('A', 'Q', 'T'))
    /** What the screen adds to a letter's first row: the items before the rows, a note come or gone. */
    private var header by mutableStateOf(3)
    private var scrubbing by mutableStateOf<Char?>(null)
    private val jumps: MutableList<Pair<Char, Int>> = Collections.synchronizedList(mutableListOf())

    private fun index(vararg with: Char) = LetterIndex(all, with.withIndex().associate { (i, c) -> c to i * 10 }, with.associateWith { 10 })

    /** The rail, its jumps told with the header as the screen had it when it last composed. */
    private fun rail() {
        compose.setContent {
            AppTheme {
                val items = header
                Box(Modifier.height(520.dp)) {
                    LetterRail(letters, onLetter = { jumps += it to items }, onDrag = { scrubbing = it }, onOpenGrid = {})
                }
            }
        }
    }

    private fun at(letter: Char, height: Int) = (all.indexOf(letter) + 0.5f) / all.size * height

    @Test
    fun aTapAfterTheScreenChangedGoesWhereTheScreenNowSays() {
        rail()
        val rail = compose.onNodeWithContentDescription("Jump to letter")
        rail.performTouchInput { click(Offset(centerX, at('Q', height))) }
        compose.waitForIdle()
        assertEquals('Q' to 3, jumps.last())
        // "TMDB is busy" under the header line: one more item before the rows.
        header = 4
        compose.waitForIdle()
        rail.performTouchInput { click(Offset(centerX, at('T', height))) }
        compose.waitForIdle()
        assertEquals('T' to 4, jumps.last())
    }

    @Test
    fun theBubbleGoesWhenTheLettersChangeUnderTheFinger() {
        rail()
        val rail = compose.onNodeWithContentDescription("Jump to letter")
        rail.performTouchInput { down(Offset(centerX, at('Q', height))) }
        compose.waitForIdle()
        assertEquals('Q', scrubbing)
        // A title gone from the filter (its facts in) as the finger rests: the loop starts again.
        letters = index('A', 'Q')
        compose.waitForIdle()
        assertNull("the bubble stays on screen", scrubbing)
        rail.performTouchInput { up() }
    }
}
