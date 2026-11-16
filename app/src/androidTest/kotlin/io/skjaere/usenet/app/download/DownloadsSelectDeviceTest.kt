package io.skjaere.usenet.app.download

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.GB
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.QM
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.assertWhole
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.episode
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.exists
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.film
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.scrolledTo
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.show
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitFor
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitTill
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Select mode on the Downloads page (item 19, L379), over the app's runner and storage, the page's rows seeded through the
 * app's own storage (Harbor Lights' six episodes, two of Quiet Meridian's and Copper Crown, one running, two queued, one
 * failed), the space strip reading the rows as Android would count their files.
 * - Select, two rows, Delete with what they give back: the Undo bar's words, the rows hidden and the strip counting them
 *   gone at once; Undo brings them back.
 * - A running and a queued one selected are cancelled, giving back only what was set aside; Undo queues them again.
 * - Once the bar has gone, the files are gone, and the rows.
 * - At twice the font size, nothing in select mode is cut off.
 *
 * Every row seeded is deleted after, with its folders. Titles invented.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsSelectDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val seeds = DownloadsPageSeeds(context, container)
    private lateinit var harbor: List<Download>
    private lateinit var copper: Download
    private lateinit var running: Download
    private lateinit var queued: Download

    @Before
    fun setUp() {
        seeds.before()
        val sizes = listOf(1_700_000_000L, 1_700_000_000, 1_700_000_000, 2_100_000_000, 2_000_000_000, 2_000_000_000)
        harbor = sizes.mapIndexed { i, size -> seeds.seed(episode(if (i < 3) 1 else 2, i % 3 + 1, DownloadState.Done, size = size), bytes = if (i == 0) FILE_BYTES else 0) }
        (4..5).forEach { seeds.seed(episode(1, it, DownloadState.Done, show = QM, title = "Quiet Meridian", size = 1_800_000_000)) }
        copper = seeds.seed(film(gaps = 2), bytes = FILE_BYTES)
        running = seeds.seed(episode(2, 4, DownloadState.Running, size = 3_100_000_000, file = true, written = 1_302_000_000, position = 0))
        queued = seeds.seed(episode(2, 5, DownloadState.Queued, size = 1_200_000_000, position = 1))
        seeds.seed(episode(2, 6, DownloadState.Queued, size = 1_200_000_000, position = 2))
        seeds.seed(episode(1, 3, DownloadState.Failed, show = QM, title = "Quiet Meridian", reason = DownloadResolver.INCOMPLETE, tried = listOf("a", "b", "c"), position = 3))
    }

    @After
    fun tearDown() = seeds.after()

    @Test
    fun deleteGivesBackWhatTheSelectedHoldAndUndoBringsThemBack() {
        show()
        compose.waitFor(hasText(STRIP))
        tapSelect()
        compose.waitFor(hasText("0 selected"))
        assertTrue(compose.exists(hasText("Select all (7)")))
        tapRow("6 episodes · 11.2 GB")
        tapRow("2022 · 1 h 52 min · 5.5 GB")
        compose.waitFor(hasText("2 selected"))
        assertTrue(compose.exists(hasText("Delete · 16.7 GB")))
        compose.onNode(hasText("Delete · 16.7 GB") and hasClickAction()).performClick()

        compose.waitFor(hasText("Deleted Harbor Lights (6 episodes) and Copper Crown · 16.7 GB"))
        compose.waitFor(hasText("Downloads 6.7 GB · 57.7 GB free"))
        val removed = harbor + copper
        compose.waitTill("hidden at once") { removed.all { seeds.row(it.id)?.state == DownloadState.Deleting } }
        assertFalse(compose.scrolledTo(hasText("6 episodes · 11.2 GB")))
        assertTrue("its file still there while Undo lasts", file(copper).isFile)

        compose.onNode(hasText("Undo") and hasClickAction()).performClick()
        compose.waitTill("brought back") { removed.all { seeds.row(it.id)?.state == DownloadState.Done } }
        compose.waitFor(hasText(STRIP))
        assertTrue(compose.scrolledTo(hasText("6 episodes · 11.2 GB")))
    }

    @Test
    fun aRunningAndAQueuedOneSelectedAreCancelledGivingBackOnlyWhatWasSetAside() {
        show()
        compose.waitFor(hasText(STRIP))
        tapSelect()
        tapRow("S02E04 · Low Water")
        tapRow("S02E05 · The Long Tide")
        compose.waitFor(hasText("2 selected"))
        // The running one's whole 3.1 GB was set aside before its first byte; the queued one's, nothing yet.
        compose.onNode(hasText("Delete · 3.1 GB") and hasClickAction()).performClick()
        compose.waitFor(hasText("Deleted Harbor Lights (2 episodes) · 3.1 GB"))
        compose.waitTill("both hidden") { listOf(running, queued).all { seeds.row(it.id)?.state == DownloadState.Deleting } }
        compose.onNode(hasText("Undo") and hasClickAction()).performClick()
        // The one stopped as it ran goes back to the queue, to go on from where it was.
        compose.waitTill("queued again") { listOf(running, queued).all { seeds.row(it.id)?.state == DownloadState.Queued } }
        assertEquals(1_302_000_000L, seeds.row(running.id)?.safeOffset)
    }

    @Test
    fun onceTheBarHasGoneTheFilesAreGone() {
        show(undoMillis = 2_000)
        compose.waitFor(hasText(STRIP))
        tapSelect()
        tapRow("2022 · 1 h 52 min · 5.5 GB")
        compose.onNode(hasText("Delete · 5.5 GB") and hasClickAction()).performClick()
        compose.waitFor(hasText("Deleted Copper Crown · 5.5 GB"))
        val video = file(copper)
        val bookkeeping = container.downloadStorage.bookkeeping(copper.id)
        assertTrue(video.isFile)
        compose.waitTill("the bar gone", timeoutMs = 10_000) { !compose.exists(hasText("Deleted Copper Crown · 5.5 GB")) }
        compose.waitTill("its row gone after the bar", timeoutMs = 10_000) { seeds.row(copper.id) == null }
        assertFalse("its file gone", video.exists())
        assertFalse("its folder gone", video.parentFile!!.exists())
        assertFalse("its bookkeeping gone", bookkeeping.exists())
        assertNotNull("the rest kept", seeds.row(harbor[0].id))
        assertTrue(file(harbor[0]).isFile)
    }

    @Test
    fun atTwiceTheFontSizeNothingInSelectModeIsCutOff() {
        show(fontScale = 2f)
        compose.waitFor(hasText(STRIP))
        tapSelect()
        tapRow("6 episodes · 11.2 GB")
        tapRow("2022 · 1 h 52 min · 5.5 GB")
        compose.waitFor(hasText("2 selected"))
        val width = context.resources.displayMetrics.widthPixels
        listOf("2 selected", "Delete · 16.7 GB", "Select all (7)", "Harbor Lights", "6 episodes · 11.2 GB", "Next: S01E01", "Copper Crown").forEach {
            compose.assertWhole(it, width)
        }
        compose.assertWhole("2022 · 1 h 52 min · 5.5 GB", width)
        compose.assertWhole("Downloaded · 2 parts missing", width)
        compose.assertWhole("✕ Your provider is missing parts of this release", width)
    }

    private fun show(fontScale: Float = 1f, undoMillis: Long? = null) {
        // The space as Android would count it: the files made at their whole size, those being deleted still there.
        val space = suspend { DownloadSpace.of(ALLOCATABLE, ALLOCATABLE, seeds.mine()) }
        val deps = if (undoMillis == null) seeds.deps(space = space) else seeds.deps(space = space, undoMillis = undoMillis)
        compose.show(context, fontScale) { DownloadsScreen(deps, onBack = {}, onSettings = {}, onShow = {}, onOpenMovie = {}) }
    }

    /** Select in the bar: its words, or its icon at a large font size. */
    private fun tapSelect() {
        compose.onNode((hasText(DownloadsPage.SELECT) or hasContentDescription(DownloadsPage.SELECT)) and hasClickAction()).performClick()
    }

    /** Taps the row that says [line], scrolled to first. */
    private fun tapRow(line: String) {
        assertTrue("'$line' shown", compose.scrolledTo(hasText(line)))
        compose.onNode(hasText(line) and hasClickAction()).performClick()
    }

    private fun file(row: Download) = container.downloadStorage.file(row)!!

    private companion object {
        /** 23.4 GB held, 2.4 GB still to set aside for the queued two, 1 GB kept free: 41.0 GB free. */
        const val ALLOCATABLE = 44_400_000_000L
        const val STRIP = "Downloads 23.4 GB · 41.0 GB free"
        const val FILE_BYTES = 64 * 1024
    }
}
