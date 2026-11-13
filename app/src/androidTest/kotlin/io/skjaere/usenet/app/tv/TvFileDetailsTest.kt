package io.skjaere.usenet.app.tv

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.memoryFile
import io.skjaere.usenet.app.platformDisplayRow
import io.skjaere.usenet.app.play.FileDetailsSection
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * File details on a real TV (tv36): from the top bar, Up from play/pause to Audio and subtitles and Right to ⓘ, OK; Down
 * through the sections to the last; Back to ⓘ. The display row reads tv36's own display. tv36's Android 16 doesn't move
 * the focus for an instrumentation's arrow keys, so the focus is moved as they would: the views' focus search in the top
 * bar, the panel's FocusManager in it. The file is core:player's hevc-level62.mkv (which tv36 plays), from memory, as an
 * invented film's release, played over and over ([memoryFile]: it is a second long, and the player leaves at a play's
 * end).
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class TvFileDetailsTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realDataSources = PlayerActivity.dataSources
    private val film = WatchTarget(WatchKind.Movie, TITLE, movieTmdbId = FILM)

    @Before
    fun setUp() {
        assumeTv()
        val bytes = instrumentation.context.assets.open("hevc-level62.mkv").use { it.readBytes() }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
        PlayerActivity.dataSources = { memoryFile(bytes) }
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlayerActivity.dataSources = realDataSources
        instrumentation.setInTouchMode(true)
        kotlinx.coroutines.runBlocking {
            // Out of Up next and unwatched, once the player's last save is in.
            kotlinx.coroutines.delay(500)
            container.library.recordProgress(WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = TITLE, updatedAt = 0), 0, 1_000)
            container.library.markMovies(listOf(MovieMark(FILM, TITLE)), watched = false)
        }
    }

    private fun launch(): PlayerLaunch {
        val saved = SavedRelease(INDEXER, "wren-harbour", RELEASE, null, 0, NZB_ID, "$RELEASE.mkv", 3_000)
        return PlayerLaunch(UsenetUris.build(NZB_ID, 0, saved.fileName, saved.fileSize), TITLE, "2026", film.key(NZB_ID, 0), film, saved)
    }

    @Test
    fun fromTheTopBarDownToTheEndAndBackToTheButton() {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch())).use { scenario ->
            waitFor("the player to know the file's tracks", 30_000) { scenario.read { a -> a.player?.currentTracks?.takeUnless { it.isEmpty } } }
            scenario.onActivity { a ->
                playerView(a).controllerShowTimeoutMs = 0
                playerView(a).showController()
            }
            waitFor("the controls, play/pause focused") { scenario.read { a -> (playerView(a).isControllerFullyVisible && a.currentFocus?.id == androidx.media3.ui.R.id.exo_play_pause).takeIf { it } } }
            // Up, then Right, as the views' focus search goes.
            scenario.onActivity { a ->
                val up = a.currentFocus!!.focusSearch(View.FOCUS_UP)
                assertSame("Up from play/pause", button(a, "Audio and subtitles"), up)
                up.requestFocus()
                val right = up.focusSearch(View.FOCUS_RIGHT)
                assertSame("Right from Audio and subtitles", button(a, "File details"), right)
                right.requestFocus()
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("the panel, on its first section") { scenario.read { a -> focusedSection(a)?.takeIf { it == "Release" } } }
            listOf("File", "Video", "On this device", "Source").forEach { section ->
                scenario.onActivity { a -> a.panelFocus!!.moveFocus(FocusDirection.Down) }
                waitFor<String>("$section focused, not ${scenario.read(::focusedSection)}") { scenario.read { a -> focusedSection(a)?.takeIf { it == section } } }
            }
            // The last keeps it.
            scenario.onActivity { a -> a.panelFocus!!.moveFocus(FocusDirection.Down) }
            Thread.sleep(300)
            assertEquals("Source", scenario.read(::focusedSection))
            // This TV's own display, as the platform says it.
            assertEquals(platformDisplayRow(context), scenario.read { a -> rows(a)["Display"] })
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the panel to close, ⓘ focused") { scenario.read { a -> (pane(a) == null && a.currentFocus === button(a, "File details")).takeIf { it } } }
        }
    }

    private fun composeRoots(activity: Activity) = views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }

    private fun pane(activity: Activity): SemanticsNode? =
        composeRoots(activity).flatMap { subtree(it.semanticsOwner.rootSemanticsNode) }.firstOrNull { it.config.getOrNull(SemanticsProperties.PaneTitle) == "File details" }

    private fun rows(activity: Activity): Map<String, List<String>> =
        pane(activity)?.let(::subtree).orEmpty().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }?.takeIf { it.size >= 2 } }
            .associate { it.first() to it.drop(1) }

    private fun focusedSection(activity: Activity): String? =
        composeRoots(activity).flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true && it.config.contains(FileDetailsSection) }?.config?.get(FileDetailsSection)

    private fun button(activity: Activity, description: String) =
        views(activity.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == description }

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    private fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 20_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    private companion object {
        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 900034
        const val TITLE = "Wren Harbour"
        const val RELEASE = "Wren.Harbour.2026.1080p.WEB-DL.H.265-NZST"
        const val INDEXER = "tv-file-details-test"
        const val NZB_ID = "f11e05"
    }
}
