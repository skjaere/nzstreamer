package io.skjaere.usenet.app.download

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.link.CastButtonLook
import io.skjaere.usenet.app.search.ReleaseList
import io.skjaere.usenet.app.search.ReleaseListMode
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Choose release in download mode (W112, L355, L386): "Download S02E04" with no Cast button; the download icon's line with
 * the space free and, on mobile data, that it starts on Wi-Fi; a release that won't fit greyed in its place with what it
 * needs, which a tap doesn't download; a season pack's share of the episode, its ⋮ offering Choose file (L403), greyed
 * "Needs Wi-Fi" on mobile data; a tap that downloads the release at once, once, its caller closing the list; and at twice
 * the font size nothing cut off. Invented releases of one episode and of a film, in a fixed order as a search would rank
 * them for downloading, with no ranker, server or account, as ReleaseListOrderDeviceTest builds them.
 */
@RunWith(AndroidJUnit4::class)
class DownloadReleaseListDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The releases a tap downloaded, by key, in order. */
    private val downloaded = mutableListOf<String>()
    private var choseFile = 0

    /** The packs whose Choose file a download list's menu chose, by key, in order. */
    private val chosenFiles = mutableListOf<String>()

    private fun download(free: Long, startsOnWifi: Boolean = false, close: () -> Unit = {}) =
        ReleaseListMode.Download(free, startsOnWifi, seasonEpisodes = 8, onChooseFile = { chosenFiles += it.key }) { release ->
            downloaded += release.key
            close()
        }

    /**
     * [group]'s list in [mode], at [fontScale]; a play's Choose file offered too, as a play's list of an episode offers it.
     * Laid out in a view of its own whose context has that font scale, so its card's menu, a popup with a window of its own
     * that takes its density from that context, is at that size too, as the page around it is.
     */
    private fun show(mode: ReleaseListMode, group: ResultGroup = episode(), title: String = "Harbor Lights", fontScale: Float = 1f) {
        this.fontScale = fontScale
        var shown by mutableStateOf(mode)
        showing = { shown = it }
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { base ->
                val scaled = ContextThemeWrapper(base, base.theme).apply {
                    applyOverrideConfiguration(Configuration(base.resources.configuration).apply { this.fontScale = fontScale })
                }
                ComposeView(scaled).apply {
                    setContent {
                        AppTheme { ReleaseList(group, title, onBack = {}, onChooseFile = { choseFile++ }, mode = shown, onPlay = { error("a play's tap in $shown") }) }
                    }
                }
            })
        }
        compose.waitUntil(10_000) { exists(hasText(group.playable.first().item.title)) }
    }

    private var showing: (ReleaseListMode) -> Unit = {}

    /** The font scale the case lays its list out at. */
    private var fontScale = 1f

    @Test
    fun theTitleAndTheLineSayItIsForDownloadingAndThereIsNoCastButton() {
        show(download(14 * GB))
        assertTrue("the title", exists(hasText("Download S02E04")))
        assertTrue("the subtitle", exists(hasText("Harbor Lights · 5 releases from 3 indexers")))
        assertTrue("how it is ranked", exists(hasText(RANKED)))
        assertTrue("the space free and what a tap does", exists(hasText("14.0 GB free on this device. A tap downloads the release.")))
        assertFalse("on Wi-Fi it says nothing of it", exists(hasText(WIFI)))
        assertFalse("no Cast button", exists(castButton))

        // A play's list of the same releases, as it always was: its Cast button, and no download line.
        showing(ReleaseListMode.Play)
        compose.waitUntil(5_000) { exists(castButton) }
        assertTrue("a play's title", exists(hasText("S02E04")))
        assertFalse("no download line in a play's list", exists(hasText(RANKED)))
    }

    @Test
    fun onMobileDataTheLineSaysItStartsOnWifi() {
        show(download(14 * GB, startsOnWifi = true))
        assertTrue("that it starts on Wi-Fi", exists(hasText(WIFI)))
        assertTrue("the space free still", exists(hasText("14.0 GB free on this device. A tap downloads the release.")))
    }

    @Test
    fun aReleaseThatWontFitIsGreyedInItsPlaceWithWhatItNeedsAndATapDoesntDownloadIt() {
        show(download(3 * GB))
        assertTrue("the line says why some are greyed", exists(hasText("3.0 GB free on this device. Releases that won't fit are greyed.")))
        assertTrue("its place, third", cardsShown().take(3) == listOf(KESTREL, PACK, ATMOS))
        assertTrue("what it needs", exists(hasText(ATMOS) and hasText("✕ Won't fit: needs about 3.4 GB")))
        assertFalse("not tappable", exists(hasText(ATMOS) and hasClickAction()))
        assertTrue("one that fits is", exists(hasText(KESTREL) and hasClickAction()))
        compose.onNode(hasText(ATMOS)).performClick()
        compose.waitForIdle()
        assertEquals("a tap on one that won't fit", emptyList<String>(), downloaded)
        // The pack's share of the episode fits, as its whole size wouldn't.
        assertTrue("the pack fits by its share", exists(hasText(PACK) and hasClickAction()))
    }

    /**
     * L403: a season pack that fits has its ⋮, whose Choose file opens the pack to choose the file to download (the list's
     * caller's); a single episode has none, nor a pack greyed as it won't fit. A play's list keeps its own Choose file.
     */
    @Test
    fun aSeasonPacksCardSaysWhatThisEpisodeComesToAndItsMenuOffersChooseFile() {
        show(download(14 * GB))
        assertTrue("the pack's share", exists(hasText(PACK) and hasText("Season pack · this episode about 1.4 GB")))
        assertFalse("no ⋮ for a single episode", exists(hasContentDescription("More for $KESTREL")))
        compose.onNode(hasContentDescription("More for $PACK")).performClick()
        compose.waitUntil(5_000) { exists(hasText("Choose file") and hasClickAction()) }
        assertTrue("offered, not greyed", exists(hasText("Choose file") and isEnabled()))
        assertFalse(exists(hasText(ReleaseListMode.Download.NEEDS_WIFI)))
        compose.onNode(hasText("Choose file") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals(listOf(key(PACK)), chosenFiles)
        assertEquals("Choose file downloads nothing", emptyList<String>(), downloaded)
        assertEquals("never the play's", 0, choseFile)

        // 1.0 GB free: the pack's share doesn't fit, its card greyed, and no ⋮.
        showing(download(GB))
        compose.waitUntil(5_000) { exists(hasText("✕ Won't fit: needs about 1.4 GB")) }
        assertFalse("no ⋮ on a greyed card", exists(hasContentDescription("More for $PACK")))

        // A play's list offers it there, and says nothing of the episode's share.
        showing(ReleaseListMode.Play)
        compose.waitUntil(5_000) { exists(hasContentDescription("More for $PACK")) }
        assertFalse("no share in a play's list", exists(hasText("Season pack · this episode about 1.4 GB")))
        compose.onNode(hasContentDescription("More for $PACK")).performClick()
        compose.waitUntil(5_000) { exists(hasText("Choose file") and hasClickAction()) }
        compose.onNode(hasText("Choose file") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals("the play's own", 1, choseFile)
        assertEquals(listOf(key(PACK)), chosenFiles)
    }

    /** W110, L403: on mobile data while downloads wait for Wi-Fi or Ethernet, the pack's Choose file is greyed, "Needs Wi-Fi", and opens nothing. */
    @Test
    fun onMobileDataThePacksChooseFileIsGreyedNeedsWifiAndOpensNothing() {
        show(download(14 * GB, startsOnWifi = true))
        compose.onNode(hasContentDescription("More for $PACK")).performClick()
        compose.waitUntil(5_000) { exists(hasText(ReleaseListMode.Download.NEEDS_WIFI)) }
        assertTrue("greyed, and read as dimmed", exists(hasText("Choose file") and hasText(ReleaseListMode.Download.NEEDS_WIFI) and isNotEnabled()))
        compose.onNode(hasText("Choose file")).performClick()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), chosenFiles)
        assertEquals(emptyList<String>(), downloaded)
    }

    @Test
    fun aTapDownloadsTheReleaseAtOnceAndItsCallerClosesTheList() {
        var open by mutableStateOf(true)
        val group = episode()
        val mode = download(14 * GB) { open = false }
        compose.setContent { AppTheme { if (open) ReleaseList(group, "Harbor Lights", onBack = {}, mode = mode) } }
        compose.waitUntil(10_000) { exists(hasText(PACK)) }
        compose.onNode(hasText(PACK) and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals(listOf(key(PACK)), downloaded)
        assertFalse("the list closed", exists(hasText(PACK)))
    }

    @Test
    fun aSecondTapBeforeTheListClosesDownloadsNothingMore() {
        show(download(14 * GB))
        compose.onNode(hasText(KESTREL) and hasClickAction()).performClick()
        compose.onNode(hasText(PACK) and hasClickAction()).performClick()
        compose.onNode(hasText(KESTREL) and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals(listOf(key(KESTREL)), downloaded)
    }

    @Test
    fun atTwiceTheFontSizeNothingIsCutOff() {
        show(download(3 * GB, startsOnWifi = true), fontScale = 2f)
        listOf("Download S02E04", "Harbor Lights · 5 releases from 3 indexers", RANKED, "3.0 GB free on this device. Releases that won't fit are greyed.", WIFI)
            .forEach(::assertWhole)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(PACK))
        compose.waitForIdle()
        assertWhole("Season pack · this episode about 1.4 GB")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(ATMOS))
        compose.waitForIdle()
        assertWhole("✕ Won't fit: needs about 3.4 GB")
        // The pack's greyed Choose file, its line under it.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("More for $PACK"))
        compose.onNode(hasContentDescription("More for $PACK")).performClick()
        compose.waitUntil(5_000) { exists(hasText(ReleaseListMode.Download.NEEDS_WIFI)) }
        listOf("Choose file", ReleaseListMode.Download.NEEDS_WIFI).forEach(::assertWhole)
    }

    /** A film's: its name in the title, its year in the subtitle. */
    @Test
    fun aFilmsListNamesItInTheTitleAndItsYearInTheSubtitleWholeAtTwiceTheFontSize() {
        show(download(14 * GB), group = film(), title = "Copper Crown", fontScale = 2f)
        listOf("Download Copper Crown", "2022 · 2 releases from 1 indexer").forEach(::assertWhole)
        assertFalse("no Cast button", exists(castButton))
    }

    private val castButton = SemanticsMatcher.keyIsDefined(CastButtonLook)

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** The releases' cards on the screen, top to bottom, by their names. */
    private fun cardsShown(): List<String> = compose.onAllNodes(hasText(PREFIX, substring = true)).fetchSemanticsNodes()
        .filter { it.layoutInfo.isPlaced && it.boundsInRoot.height > 0f }
        .sortedBy { it.positionInRoot.y }
        .mapNotNull { node -> RELEASES.map { it.first }.firstOrNull { name -> node.texts().contains(name) } }

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().map { it.text }

    private fun SemanticsNode.textLayout(): TextLayoutResult? {
        if (!config.contains(SemanticsActions.GetTextLayoutResult)) return null
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.singleOrNull()
    }

    /** [text] shown whole: every word of it laid out, none cut at the bottom or ellipsized, and on the screen sideways. */
    private fun assertWhole(text: String) {
        val node = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull { it.textLayout() != null }
            ?: throw AssertionError("no text '$text'")
        val layout = node.textLayout()!!
        // At the font size the case asked for: a window of its own (a menu's popup) lays out at its context's.
        assertEquals("'$text' laid out at font scale ${layout.layoutInput.density.fontScale}", fontScale, layout.layoutInput.density.fontScale)
        val last = layout.lineCount - 1
        assertFalse(
            "'$text' is cut: ${layout.lineCount} lines in ${layout.layoutInput.constraints}",
            layout.didOverflowHeight || layout.isLineEllipsized(last) || layout.getLineEnd(last) != text.length,
        )
        // In its window, which a menu's popup has of its own: inside the screen's width either way.
        val width = context.resources.displayMetrics.widthPixels
        val bounds = node.boundsInWindow
        assertTrue("'$text' ($bounds) leaves the screen (0-$width)", bounds.left >= 0f && bounds.right <= width + 1f)
    }

    private fun key(name: String) = "${RELEASES.first { it.first == name }.third}/$name"

    private fun item(name: String, size: Long, indexer: String, score: Double, season: Int?, episode: Int?) = RankedItem(
        NewznabItem(
            indexerId = indexer, guid = name, title = name, sizeBytes = size, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = season, episode = episode, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("$indexer/$name", name, size), ReleaseParser.parse(name), playable = true, score = score, reasons = emptyList(), estimatedBitrate = null),
    )

    /** S02E04's releases, in their order for downloading. */
    private fun episode() = ResultGroup(
        "harbor lights|ep:2:4", GroupKind.Episode, 2, listOf(4), null, null,
        RELEASES.mapIndexed { i, (name, size, indexer) -> item(name, size, indexer, 100.0 - i, 2, 4) }, emptyList(),
    )

    private fun film() = ResultGroup(
        "copper crown|2022", GroupKind.Movie, null, emptyList(), null, 2022,
        listOf(
            item("Copper.Crown.2022.1080p.WEB-DL.DDP5.1.H.264-KESTREL", 4_300_000_000, "dl-list-a", 100.0, null, null),
            item("Copper.Crown.2022.720p.WEB-DL.DDP5.1.H.264-KESTREL", 2_200_000_000, "dl-list-a", 99.0, null, null),
        ),
        emptyList(),
    )

    private companion object {
        const val GB = 1_000_000_000L
        const val RANKED = "Ranked for downloading: smaller first"
        const val WIFI = "Starts on Wi-Fi: this phone is on mobile data."
        const val PREFIX = "Harbor.Lights.S02"
        const val KESTREL = "Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.H.265-KESTREL"
        const val PACK = "Harbor.Lights.S02.1080p.WEB-DL.DDP5.1.H.264-DRIFTWOOD"
        const val ATMOS = "Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.Atmos.H.264-TIDEPOOL"

        /** Name, size and indexer, as ranked for downloading: an 11.2 GB pack of the season's 8 episodes comes to 1.4 GB here. */
        val RELEASES = listOf(
            Triple(KESTREL, 1_200_000_000L, "dl-list-a"),
            Triple(PACK, 11_200_000_000L, "dl-list-b"),
            Triple(ATMOS, 3_400_000_000L, "dl-list-a"),
            Triple("Harbor.Lights.S02E04.2160p.WEB-DL.DV.HDR10.DDP5.1.H.265-LUMEN", 7_900_000_000L, "dl-list-c"),
            Triple("Harbor.Lights.S02E04.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY", 9_800_000_000L, "dl-list-b"),
        )
    }
}
