package io.skjaere.usenet.app

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.search.GroupRow
import io.skjaere.usenet.app.search.SourceTag
import io.skjaere.usenet.app.search.groupChips
import io.skjaere.usenet.app.tv.setTvContent
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.search.ResultGroup
import io.skjaere.usenet.search.ResultGrouper
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A search result's row (#160), as Search lists a release group, on a phone and on a TV: OK or a tap on the row plays
 * the group's best release; "N releases ›" is a sibling at its end, which a remote reaches with Right (as it never
 * reached a button inside the row) and which opens the list of the group's releases. With a source tag and its "+1"
 * (L247, choice 5; D2PhoneSearchTags), at the boards' widths and font sizes: beside the episode where it fits, else leading
 * the chips; the episode and "pack" never cut; the tag and its count one piece for TalkBack. Invented release names.
 */
@RunWith(AndroidJUnit4::class)
class ResultRowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var played = 0
    private var listed = 0

    @After
    fun touch() {
        instrumentation.setInTouchMode(true)
    }

    private fun groupOf(releases: List<Pair<String, Long>>): ResultGroup {
        fun item(title: String, size: Long) = NewznabItem(
            indexerId = "row", guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        )
        val ranking = runBlocking { instrumentation.targetContext.container.rankingContext() }
        return ResultGrouper().group(releases.map { (title, size) -> item(title, size) }, ranking).sections.flatMap { it.groups }.first()
    }

    private val group: ResultGroup by lazy { groupOf(RELEASES) }

    /** The row's own area: it plays. */
    private val playArea = SemanticsMatcher("plays the group") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Play" }

    /** Its sibling, named for TalkBack with the group it lists. */
    private val releases = hasText("${RELEASES.size} releases ›") and hasContentDescription("${RELEASES.size} releases for S02E05") and hasClickAction()

    private fun focused(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()?.let { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" | ")
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" | ") { it.text }
    } ?: "(nothing)"

    private fun press(key: Int): String {
        instrumentation.sendKeyDownUpSync(key)
        compose.waitForIdle()
        return focused()
    }

    private fun row(formFactor: FormFactor) {
        val content = @androidx.compose.runtime.Composable {
            GroupCard { GroupRow(group, null, primary = true, onPlay = { played++ }, onReleases = { listed++ }) }
        }
        if (formFactor == FormFactor.Tv) compose.setTvContent { content() } else compose.setContent { AppTheme(FormFactor.Phone) { content() } }
        compose.waitForIdle()
    }

    @Test
    fun aTapOnThePhonesRowPlaysAndItsReleasesOpenTheList() {
        row(FormFactor.Phone)
        compose.onNode(playArea).performClick()
        compose.waitForIdle()
        assertEquals("a tap on the row", 1 to 0, played to listed)
        compose.onNode(releases).performClick()
        compose.waitForIdle()
        assertEquals("a tap on its releases", 1 to 1, played to listed)
    }

    @Test
    fun withARemoteOkPlaysAndRightReachesTheReleasesOnAPhone() {
        withTheRemote(FormFactor.Phone)
    }

    @Test
    fun withARemoteOkPlaysAndRightReachesTheReleasesOnATv() {
        withTheRemote(FormFactor.Tv)
    }

    private fun withTheRemote(formFactor: FormFactor) {
        instrumentation.setInTouchMode(false)
        row(formFactor)
        compose.onNode(playArea).requestFocus()
        compose.waitUntil(5_000) { compose.onAllNodes(playArea and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("OK on the row", 1 to 0, played to listed)

        assertEquals("Right from the row", "${RELEASES.size} releases for S02E05", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("OK on its releases", 1 to 1, played to listed)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Left from its releases: the row", 1, compose.onAllNodes(playArea and isFocused()).fetchSemanticsNodes().size)
    }

    /** The phone's width and font size the tagged row is drawn at: phone34 is 411 dp wide, so they are set, not its own. */
    private var width by mutableStateOf(390.dp)
    private var fontScale by mutableStateOf(1f)

    /** [group]'s row with the boards' tag, Premiumize and "+1", on a phone [width] wide at [fontScale], as Search draws it. */
    private fun tagged(group: ResultGroup, label: String? = null) {
        val context = instrumentation.targetContext
        compose.setContent {
            val scaled = remember(fontScale) { context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = this@ResultRowTest.fontScale }) }
            CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                AppTheme(FormFactor.Phone) {
                    Box(Modifier.width(width)) { GroupCard { GroupRow(group, null, primary = true, onPlay = {}, onReleases = {}, label = label, tag = TAG) } }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(matcher: SemanticsMatcher) = compose.onNode(matcher, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** Where the tag is: beside [title] on its line, or leading the chips under it. */
    private fun tagBeside(title: String, chip: String): Boolean {
        val words = bounds(hasText(title))
        val tag = bounds(hasContentDescription(TAG.spoken))
        val firstChip = bounds(hasText(chip))
        val beside = tag.top < words.bottom
        if (beside) {
            assertTrue("beside $title: $tag after $words", tag.left >= words.right)
            assertTrue("the chips under the line: $firstChip", firstChip.top >= words.bottom)
        } else {
            assertEquals("leading the chips, at the row's start: $tag, $words", words.left, tag.left, 1f)
            // The chips after it: on its line where they fit, else under it (D2PhoneSearchTags 2).
            assertTrue("the chips after it: $firstChip after $tag", firstChip.top >= tag.bottom || firstChip.left > tag.right && firstChip.top < tag.bottom)
        }
        return beside
    }

    /** Asserts [words]' text is shown whole: not cut at its end, nor wrapped. */
    private fun assertWhole(words: String) {
        val node = compose.onNode(hasText(words), useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val layout = results.single()
        assertTrue(
            "$words cut: ${layout.size} in ${node.boundsInRoot}, lines ${layout.lineCount}, the first ending at ${layout.getLineEnd(0)}",
            layout.lineCount == 1 && !layout.didOverflowHeight && layout.getLineEnd(0) == words.length && !layout.isLineEllipsized(0) &&
                layout.getLineRight(0) <= node.boundsInRoot.width + 1f,
        )
    }

    /** D2PhoneSearchTags 1, 2: S02E05's tag fits beside it on a 390 dp phone; on a 360 dp one, or at 1.3 times the font size, it leads its chips. */
    @Test
    fun anEpisodesTagSitsBesideItAt390dpAndLeadsItsChipsAt360dpOrALargerFont() {
        tagged(group)
        assertTrue("beside S02E05 at 390 dp", tagBeside("S02E05", groupChips(group.best!!).first().text))
        assertWhole("S02E05")
        width = 360.dp
        compose.waitForIdle()
        assertFalse("leading the chips at 360 dp", tagBeside("S02E05", groupChips(group.best!!).first().text))
        assertWhole("S02E05")
        width = 390.dp
        fontScale = 1.3f
        compose.waitForIdle()
        assertFalse("leading the chips at 1.3 times the font size", tagBeside("S02E05", groupChips(group.best!!).first().text))
        assertWhole("S02E05")
    }

    /** D2PhoneSearchTags 1: a season pack's row, "Full season" and "pack", has no room beside them on a 390 dp phone. */
    @Test
    fun aPacksTagLeadsItsChipsAt390dpAndThePackIsShownWhole() {
        val pack = groupOf(PACK)
        checkNotNull(pack.best) { "the pack plays nothing here: ${pack.kind} ${pack.hidden.map { it.ranked.reasons }}" }
        tagged(pack, label = "Full season")
        assertFalse("leading the chips", tagBeside("Full season", groupChips(pack.best!!).first().text))
        assertWhole("Full season")
        assertWhole("pack")
        width = 360.dp
        fontScale = 1.3f
        compose.waitForIdle()
        assertFalse(tagBeside("Full season", groupChips(pack.best!!).first().text))
        assertWhole("Full season")
        assertWhole("pack")
    }

    /**
     * L251: the tag and its "+1" are one piece for TalkBack, never "+1" alone, wherever the tag sits; the row reads its
     * episode, then the tag, then its chips.
     */
    @Test
    fun theTagAndItsCountAreReadAsOneAndTheRowReadsThemAfterTheEpisode() {
        tagged(group)
        repeat(2) {
            // What TalkBack reads, the merged tree: no "+1" nor "Premiumize" on their own, the tag one piece.
            assertTrue("a +1 read on its own", compose.onAllNodes(hasText("+1")).fetchSemanticsNodes().isEmpty())
            assertTrue("Premiumize read on its own", compose.onAllNodes(hasText("Premiumize")).fetchSemanticsNodes().isEmpty())
            val tag = compose.onNode(hasContentDescription(TAG.spoken), useUnmergedTree = true).fetchSemanticsNode()
            assertNull("the tag's words aren't its own", tag.config.getOrNull(SemanticsProperties.Text))
            val row = compose.onNode(playArea).fetchSemanticsNode()
            val spoken = row.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            assertTrue("$spoken", spoken.any { it.startsWith("S02E05, From Premiumize, also cached on 1 more service, 1080p, ") })
            // The same words with the tag leading the chips.
            width = 360.dp
            compose.waitForIdle()
        }
    }

    private companion object {
        /** The boards' tag: Premiumize, with one more service. */
        val TAG = SourceTag("Premiumize", "From Premiumize, also cached on 1 more service", dim = false, more = 1)

        val PACK = listOf(
            "Harbor.Lights.S02.1080p.HDTV.x264-NZST" to 21_000_000_000L,
            "Harbor.Lights.S02.720p.WEB-DL.DDP5.1.H.264-NZST" to 14_000_000_000L,
        )

        val RELEASES = listOf(
            "Harbor.Lights.S02E05.The.Long.Tide.1080p.WEB-DL.DDP5.1.H.264-NZST" to 3_200_000_000L,
            "Harbor.Lights.S02E05.The.Long.Tide.720p.WEB-DL.DDP5.1.H.264-NZST" to 1_400_000_000L,
            "Harbor.Lights.S02E05.The.Long.Tide.1080p.HDTV.x264-NZST" to 2_100_000_000L,
        )
    }
}
