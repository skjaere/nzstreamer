package io.skjaere.usenet.app.collection

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.collection.model.Side
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.collection.model.TitleRow
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadMarkLook
import io.skjaere.usenet.app.download.Downloaded
import io.skjaere.usenet.app.download.DownloadedTitles
import io.skjaere.usenet.app.download.DownloadsPageSeeds
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.assertWhole
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.show
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * L407, L408: the Collection's Downloaded chip and marks, over a small library of its own (CollectionHarness: four shows and
 * three films, invented names) and the app's runner, whose finished downloads the cases seed through the app's storage as
 * the Downloads page's tests do, each with the release Resolve keeps (so the play hook takes it), and delete after with
 * their folders. The chip comes second, after All, counting the titles with a download; each such row has its mark after
 * its title, a show's with its count, and TalkBack hears it at the end of the row's sentence; under the chip the header
 * counts them and "Downloads ›" opens the Downloads page. A download still to finish, or one the hook can't take, isn't
 * counted.
 */
@RunWith(AndroidJUnit4::class)
class CollectionDownloadsDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val seeds = DownloadsPageSeeds(context, container)
    private var harness: CollectionHarness? = null
    private lateinit var vm: CollectionViewModel
    private var opened = 0

    @Before
    fun setUp() {
        seeds.before()
    }

    @After
    fun tearDown() {
        seeds.after()
        harness?.close()
    }

    private fun harness(): CollectionHarness =
        CollectionHarness(context, shows = 4, movies = 3, downloaded = CollectionDeps.downloaded(container, shown = true)).also { harness = it; it.seed() }

    /** [row] with the release Resolve keeps once its file is written, so the play hook's launch takes it. */
    private fun played(row: Download) = row.copy(release = RememberedRelease("dl-page-a", row.id, "Collection.Marks.S01E01.1080p.WEB-DL-KESTREL", null, 0, "nzb-${row.id}"))

    /** Episodes 1 to [episodes] of the harness's show [i], finished. */
    private fun episodes(h: CollectionHarness, i: Int, episodes: Int) = (1..episodes).map { e ->
        seeds.seed(played(DownloadsPageSeeds.episode(1, e, DownloadState.Done, show = h.fixture.showId(i), title = h.fixture.showName(i))))
    }

    /** The harness's film [j], finished. */
    private fun film(h: CollectionHarness, j: Int): Download {
        val id = h.fixture.movieId(j)
        return seeds.seed(played(DownloadsPageSeeds.film().copy(watchKey = WatchKey.movie(id), movieTmdbId = id, title = h.fixture.movieName(j))))
    }

    /** Until the runner's finished downloads hold the test's [count]. */
    private fun finished(count: Int) {
        compose.waitUntil(10_000) { container.downloads.done.value.values.count { seeds.isMine(it.id) } == count }
    }

    private fun show(h: CollectionHarness, fontScale: Float = 1f) {
        compose.show(context, fontScale) {
            vm = viewModel { CollectionViewModel(h.deps, createSavedStateHandle()) }
            CollectionScreen(onShow = {}, onMovie = {}, onAdd = {}, onMetadata = {}, vm = vm, onDownloads = { opened++ })
        }
        compose.waitUntil(10_000) { vm.ui.value?.view?.rows?.isNotEmpty() == true }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun node(matcher: SemanticsMatcher): SemanticsNode = compose.onNode(matcher).fetchSemanticsNode()

    private fun said(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    /** A row of the list by its title's key, as the list shows it now. */
    private fun row(key: TitleKey): TitleRow = vm.ui.value!!.view.rows.single { it.key == key }

    /** The rows' marks, in the unmerged tree: the row's own sentence says them. */
    private fun marks(): List<SemanticsNode> = compose.onAllNodes(SemanticsMatcher.keyIsDefined(DownloadMarkLook), useUnmergedTree = true).fetchSemanticsNodes()

    @Test
    fun theDownloadedChipCountsTheTitlesWithADownloadTheirRowsAreMarkedAndItsHeaderOpensTheDownloadsPage() {
        val h = harness()
        val f = h.fixture
        episodes(h, 1, 3)
        episodes(h, 2, 6)
        // Neither counts: one still in the queue, and one finished whose release wasn't kept (the hook has no launch for it).
        seeds.seed(DownloadsPageSeeds.episode(1, 1, DownloadState.Queued, show = f.showId(3), title = f.showName(3)))
        seeds.seed(DownloadsPageSeeds.episode(1, 2, DownloadState.Done, show = f.showId(3), title = f.showName(3)))
        val copper = film(h, 1)
        finished(11)
        show(h)

        val chip = hasContentDescription("Downloaded, 2 shows")
        compose.waitUntil(10_000) { exists(chip) }
        // Second, after All and before Watching.
        val all = node(hasContentDescription("All, 4 shows")).boundsInRoot
        val downloaded = node(chip).boundsInRoot
        val watching = node(hasContentDescription("Watching, ", substring = true)).boundsInRoot
        assertTrue("All at $all, Downloaded at $downloaded, Watching at $watching", all.right <= downloaded.left && downloaded.right <= watching.left)
        assertEquals(listOf("All", "Downloaded"), vm.ui.value!!.view.chips.map { it.name }.take(2))

        // The rows' marks, and TalkBack's sentences: a show's count of episodes at the end; none for the third show's.
        mapOf(1 to "3 episodes downloaded.", 2 to "6 episodes downloaded.").forEach { (i, words) ->
            val row = row(TitleKey.show(f.showId(i)))
            val sentence = said(node(hasContentDescription(row.a11y, substring = true)))
            assertTrue("$i: $sentence", sentence.startsWith(row.a11y) && sentence.endsWith(" $words"))
        }
        listOf(0, 3).forEach { i ->
            val sentence = said(node(hasContentDescription(row(TitleKey.show(f.showId(i))).a11y, substring = true)))
            assertFalse("$i: $sentence", sentence.contains("downloaded", ignoreCase = true))
        }
        assertEquals("a mark on the two shows' rows", 2, marks().size)

        // Under the chip: the two shows, the header counting their nine episodes, and "Downloads ›", a button, to the page.
        compose.onNode(chip).performClick()
        compose.waitUntil(5_000) { vm.ui.value?.filterName == "Downloaded" }
        assertEquals(setOf(f.showName(1), f.showName(2)), vm.ui.value!!.view.rows.map { it.text.name }.toSet())
        compose.onNode(hasText("2 shows · 9 episodes downloaded")).assertIsDisplayed()
        val link = hasText("Downloads ›") and hasClickAction()
        val button = node(link)
        assertEquals(Role.Button, button.config.getOrNull(SemanticsProperties.Role))
        assertEquals("Downloads", said(button))
        compose.onNode(link).performClick()
        assertEquals(1, opened)

        // The films: one downloaded, marked with no number, "Downloaded." said; under its chip, "1 movie downloaded".
        compose.onNode(hasText("Movies") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { vm.ui.value?.side == Side.Movies }
        val films = hasContentDescription("Downloaded, 1 movie")
        compose.waitUntil(5_000) { exists(films) }
        val film = row(TitleKey.movie(copper.movieTmdbId!!))
        assertEquals(DownloadedTitles.said(film.a11y, Downloaded(1, 0), show = false), said(node(hasContentDescription(film.a11y, substring = true))))
        assertEquals(1, marks().size)
        compose.onNode(films).performClick()
        compose.waitUntil(5_000) { vm.ui.value?.filterName == "Downloaded" }
        compose.onNode(hasText("1 movie downloaded")).assertIsDisplayed()

        // Its download removed, the chip goes from under its filter, which reads as All and is saved so (L408).
        runBlocking { container.downloadStorage.delete(copper.id) }
        compose.waitUntil(10_000) { vm.ui.value?.filterName == "All" && !exists(hasContentDescription("Downloaded, ", substring = true)) }
        compose.waitUntil(5_000) { h.prefs.value.movieFilter == "All" }
        assertEquals(3, vm.ui.value!!.view.rows.size)
        assertEquals(0, marks().size)
    }

    /** At twice the text size the mark keeps its 18 dp and stays beside the title, centred on it; the header's words are whole. */
    @Test
    fun atTwiceTheTextSizeTheMarkStaysBesideTheTitleAndTheHeaderIsWhole() {
        val h = harness()
        val f = h.fixture
        episodes(h, 1, 3)
        finished(3)
        show(h, fontScale = 2f)
        compose.waitUntil(10_000) { marks().isNotEmpty() }
        val mark = marks().single()
        val title = compose.onAllNodes(hasText(f.showName(1)), useUnmergedTree = true).fetchSemanticsNodes().single()
        val count = compose.onAllNodes(hasText("3"), useUnmergedTree = true).fetchSemanticsNodes().single()
        val dp = mark.layoutInfo.density.density
        val (m, t, c) = Triple(mark.boundsInRoot, title.boundsInRoot, count.boundsInRoot)
        assertTrue("the mark ${m.width / dp} dp wide", abs(m.width / dp - 18f) <= 1f)
        assertTrue("the mark at $m, the title at $t", m.left >= t.right - 1f && m.center.y in t.top..t.bottom)
        assertTrue("the count at $c, the mark at $m", c.left >= m.right - 1f && m.center.y in c.top..c.bottom)
        val width = context.resources.displayMetrics.widthPixels
        assertTrue("the count at $c leaves the screen", c.right <= width + 1f)
        compose.onNode(hasContentDescription("Downloaded, 1 show")).performClick()
        compose.waitUntil(5_000) { vm.ui.value?.filterName == "Downloaded" }
        compose.assertWhole("1 show · 3 episodes downloaded", width)
        compose.assertWhole("Downloads ›", width)
        compose.assertWhole(f.showName(1), width)
    }
}
