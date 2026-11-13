package io.skjaere.usenet.app

import android.view.KeyEvent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.search.ForPickedTitle
import io.skjaere.usenet.app.search.ReleaseList
import io.skjaere.usenet.app.search.TitleInfo
import io.skjaere.usenet.app.tv.Remote
import io.skjaere.usenet.app.tv.setTvContent
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/**
 * A release list's orders (W105): a press of Best match, Size or Age starts the list at that order's first release,
 * from wherever it was, a press of the order already chosen too (L321); a mark, or a release joining, keeps the place;
 * the order comes back with the place as the list shows again (L322), and only to that list, on Search only to its picked
 * title's (RFO-1); on a TV, OK on an order keeps the focus there and Down reaches the order's first release, at the TV's
 * size on a phone too (L325).
 * Twelve invented releases of one episode in a fixed order, as a search would rank them, with no ranker, server or
 * account (L332). The place is read from where the cards are on the screen.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseListOrderDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @After
    fun touch() {
        // The TV case's remote takes the device out of touch mode, which outlasts the test.
        instrumentation.setInTouchMode(true)
    }

    @Test
    fun aNewOrderStartsAtItsFirstRelease() {
        show()
        choose("Size")
        assertOnTop(KESTREL, "Size chosen")
        choose("Age")
        assertOnTop(SWELL, "Age chosen")
        choose("Best match")
        assertOnTop(WATTWURM, "Best match chosen")
        assertTrue("Best match's first release says so", exists(card(WATTWURM) and hasText("Best match")))
    }

    @Test
    fun aNewOrderChosenFromDownTheListStartsAtItsFirstRelease() {
        show()
        rows().performScrollToIndex(6)
        compose.waitForIdle()
        assertTrue("scrolled down: ${shown()}", shown().first() != WATTWURM)
        choose("Size")
        assertOnTop(KESTREL, "Size chosen from down the list")
    }

    @Test
    fun theOrderChosenPressedAgainGoesBackToItsFirstRelease() {
        show()
        rows().performScrollToIndex(6)
        compose.waitForIdle()
        choose("Best match")
        assertOnTop(WATTWURM, "Best match pressed again from down the list")
        choose("Size")
        rows().performScrollToIndex(6)
        compose.waitForIdle()
        assertTrue("scrolled down: ${shown()}", shown().first() != KESTREL)
        choose("Size")
        assertOnTop(KESTREL, "Size pressed again from down the list")
    }

    /** What isn't a press of an order leaves the place as it is: a release marked as failed, a release joining the list. */
    @Test
    fun aMarkOrAReleaseJoiningKeepsThePlace() {
        var group by mutableStateOf(releases())
        var failed by mutableStateOf(emptyMap<String, String>())
        compose.setContent { AppTheme { ReleaseList(group, "Harbor Lights", onBack = {}, failed = failed, onPlay = {}) } }
        compose.waitUntil(10_000) { exists(card(WATTWURM)) }
        choose("Size")
        rows().performScrollToIndex(5)
        compose.waitForIdle()
        val place = place()
        assertTrue("scrolled down in Size's order: ${shown()}", place.first != KESTREL)

        // As ChosenPlays marks a release chosen here that failed: the one under the top one.
        val marked = shown()[1]
        failed = mapOf(key(marked) to "No video file")
        compose.waitUntil(5_000) { exists(card(marked) and hasText("✕ No video file")) }
        assertEquals("the place after a mark", place, place())

        // As torrents joining, or a re-rank for a Cast device, give the list again: one more, the smallest.
        group = releases(extra = listOf(Release(OAR, 400_000_000, 45)))
        compose.waitUntil(5_000) { exists(hasText("Harbor Lights · 13 releases from 1 indexer")) }
        assertEquals("the place after a release joined", place, place())
    }

    /** The list shown again (a tab switch, the activity made again): the order it had, and the place it had in it. */
    @Test
    fun theOrderComesBackWithThePlace() {
        val restoration = StateRestorationTester(compose)
        val group = releases()
        restoration.setContent { AppTheme { ReleaseList(group, "Harbor Lights", onBack = {}, onPlay = {}) } }
        compose.waitUntil(10_000) { exists(card(WATTWURM)) }
        choose("Size")
        rows().performScrollToIndex(5)
        compose.waitForIdle()
        val place = place()
        assertTrue("scrolled down in Size's order: ${shown()}", place.first != KESTREL)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { cards().isNotEmpty() }
        assertEquals("the place, back in Size's order (${shown()})", place, place())
        // Only Best match's order ranks its rows.
        assertTrue("a rank shown, as in Best match's order: ${shown()}", compose.onAllNodes(anyCard and hasText("#", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    /**
     * A page's saved state outlives a list the page doesn't save (a chooser's, after the app was stopped in the
     * background): another episode's list shown there later opens as every list does, at Best match's first release, not
     * in the order and at the place the first one was saved with (RFO-1).
     */
    @Test
    fun anotherListShownWhereOneWasSavedStartsAtBestMatchsFirstRelease() {
        val restoration = StateRestorationTester(compose)
        var group by mutableStateOf(releases())
        // Up as the page is first made; not as it is made again, as a chooser's list isn't once the process has gone.
        var upAtFirst = true
        lateinit var up: MutableState<Boolean>
        restoration.setContent {
            AppTheme {
                up = remember { mutableStateOf(upAtFirst) }
                if (up.value) ReleaseList(group, "Harbor Lights", onBack = {}, onPlay = {})
            }
        }
        compose.waitUntil(10_000) { exists(card(WATTWURM)) }
        choose("Size")
        rows().performScrollToIndex(5)
        compose.waitForIdle()
        assertTrue("scrolled down in Size's order: ${shown()}", place().first != KESTREL)
        upAtFirst = false
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertTrue("no list as the page is made again: ${shown()}", cards().isEmpty())

        group = releases(episode = 5)
        up.value = true
        compose.waitUntil(10_000) { cards().isNotEmpty() }
        assertOnTop(WATTWURM.of(5), "another episode's list shown where one was saved")
        assertTrue("Best match chosen: its first release says so", exists(card(WATTWURM.of(5)) and hasText("Best match")))
    }

    /**
     * Search keys every picked title's groups alike ("known|ep:1:1"), and its results aren't saved: its list, shown again
     * with them, keeps its order and its place; another picked title's list, shown where one was saved and left unread
     * (no results, no list, once the process has gone), opens as every list does, at Best match's first release (RFO-1).
     * The other title is a show of the same name, its releases named as the first one's: titles are told by their TMDB ids.
     */
    @Test
    fun anotherPickedTitlesListShownWhereOneWasSavedStartsAtBestMatchsFirstRelease() {
        val restoration = StateRestorationTester(compose)
        val group = releases(groupKey = "known|ep:1:1")
        var title by mutableStateOf(HARBOR_LIGHTS)
        // Up as the page is made; not once the process has gone, as Search's list isn't without its results.
        var upAtFirst = true
        lateinit var up: MutableState<Boolean>
        restoration.setContent {
            AppTheme {
                up = remember { mutableStateOf(upAtFirst) }
                if (up.value) ForPickedTitle(title) { ReleaseList(group, title.name, onBack = {}, onPlay = {}) }
            }
        }
        compose.waitUntil(10_000) { exists(card(WATTWURM)) }
        choose("Size")
        rows().performScrollToIndex(5)
        compose.waitForIdle()
        val place = place()
        assertTrue("scrolled down in Size's order: ${shown()}", place.first != KESTREL)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { cards().isNotEmpty() }
        assertEquals("the picked title's list shown again: its place, in Size's order (${shown()})", place, place())

        upAtFirst = false
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertTrue("no list as the page is made again: ${shown()}", cards().isEmpty())
        title = HARBOR_LIGHTS_2024
        up.value = true
        compose.waitUntil(10_000) { cards().isNotEmpty() }
        assertOnTop(WATTWURM, "another picked title's list shown where one was saved")
        assertTrue("Best match chosen: its first release says so", exists(card(WATTWURM) and hasText("Best match")))
    }

    /** At a TV's size, with a remote: OK on an order keeps the focus on it, and Down reaches its first release. */
    @Test
    fun withARemoteOkOnAnOrderKeepsTheFocusThereAndDownReachesItsFirstRelease() {
        val remote = Remote(compose)
        val group = releases()
        compose.setTvContent { ReleaseList(group, "Harbor Lights", onBack = {}, onPlay = {}) }
        compose.waitUntil(10_000) { exists(card(WATTWURM)) }
        instrumentation.setInTouchMode(false)

        compose.onNode(chip("Size")).requestFocus()
        compose.waitForIdle()
        assertEquals("OK on Size", "Size", remote.press(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue("Size's order ranks nothing", !exists(card(WATTWURM) and hasText("Best match")))
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from Size: the largest release, not \"${remote.focused()}\"", remote.isFocused(card(KESTREL)))

        // Up from the releases has no set landing (L324): the next order is focused as a test can.
        compose.onNode(chip("Age")).requestFocus()
        compose.waitForIdle()
        assertEquals("OK on Age", "Age", remote.press(KeyEvent.KEYCODE_DPAD_CENTER))
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from Age: the newest release, not \"${remote.focused()}\"", remote.isFocused(card(SWELL)))

        compose.onNode(chip("Best match")).requestFocus()
        compose.waitForIdle()
        assertEquals("OK on Best match", "Best match", remote.press(KeyEvent.KEYCODE_DPAD_CENTER))
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from Best match: its first release, saying so, not \"${remote.focused()}\"", remote.isFocused(card(WATTWURM) and hasText("Best match")))
    }

    private fun show() {
        val group = releases()
        compose.setContent { AppTheme { ReleaseList(group, "Harbor Lights", onBack = {}, onPlay = {}) } }
        compose.waitUntil(10_000) { exists(card(WATTWURM)) }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** An order's chip, by its words: not Best match's first release, which says "Best match" too. */
    private fun chip(label: String) = hasText(label) and hasClickAction() and !anyCard

    private fun choose(label: String) {
        compose.onNode(chip(label)).performClick()
        compose.waitForIdle()
    }

    private fun rows() = compose.onNode(hasScrollToIndexAction())

    private fun card(name: String) = hasText(name) and hasClickAction()

    private val anyCard = hasText(PREFIX, substring = true) and hasClickAction()

    private fun SemanticsNode.release(): String = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }.first { it.startsWith(PREFIX) }

    /** The releases' cards on the screen, top to bottom, by their names. Bounds in the root are clipped: one out of view has none. */
    private fun cards(): List<SemanticsNode> = compose.onAllNodes(anyCard).fetchSemanticsNodes()
        .filter { it.layoutInfo.isPlaced && it.boundsInRoot.height > 0f }
        .sortedBy { it.positionInRoot.y }

    private fun shown(): List<String> = cards().map { it.release() }

    /** The release on top and how far down the screen its card starts (above the list's top when it is cut). */
    private fun place(): Pair<String, Float> = cards().first().let { it.release() to it.positionInRoot.y }

    /** [name]'s card on top of the list, whole: above every other card on the screen, and not cut at the list's top. */
    private fun assertOnTop(name: String, after: String) {
        val cards = cards()
        assertEquals("$after: the release on top (of ${cards.map { it.release() }})", name, cards.firstOrNull()?.release())
        val top = cards.first()
        assertEquals("$after: $name's card is cut at the list's top", top.positionInRoot.y, top.boundsInRoot.top, 1f)
    }

    private fun key(name: String) = "$INDEXER/$name"

    private class Release(val name: String, val size: Long, val days: Long)

    /** A release of the first episode's name as [episode]'s. */
    private fun String.of(episode: Int) = replace("S01E01", "S01E%02d".format(episode))

    /** The episode's releases as Best match ranks them, [extra] after; [episode]'s, named as the first's are; keyed as a search of the words keys them, else [groupKey]. */
    private fun releases(extra: List<Release> = emptyList(), episode: Int = 1, groupKey: String = "harbor lights|ep:1:$episode"): ResultGroup {
        val now = Instant.now()
        val items = (RELEASES + extra).mapIndexed { i, r ->
            val name = r.name.of(episode)
            RankedItem(
                NewznabItem(
                    indexerId = INDEXER, guid = name, title = name, sizeBytes = r.size, publishedAt = null, postedAt = now - Duration.ofDays(r.days),
                    categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
                    tvmazeId = null, season = 1, episode = episode, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
                ),
                RankedRelease(ReleaseFacts(key(name), name, r.size), ReleaseParser.parse(name), playable = true, score = 100.0 - i, reasons = emptyList(), estimatedBitrate = null),
            )
        }
        return ResultGroup(groupKey, GroupKind.Episode, 1, listOf(episode), null, null, items, emptyList())
    }

    private companion object {
        const val INDEXER = "release-list-order-device-test"
        const val PREFIX = "Harbor.Lights.S01E"
        const val WATTWURM = "Harbor.Lights.S01E01.1080p.WEB-DL.DDP5.1.H.264-WATTWURM"
        const val KESTREL = "Harbor.Lights.S01E01.2160p.BluRay.REMUX.HEVC.TrueHD.7.1-KESTREL"
        const val SWELL = "Harbor.Lights.S01E01.720p.WEB.h264-SWELL"
        const val OAR = "Harbor.Lights.S01E01.480p.WEB.x264-OAR"

        /** A show picked on Search, and another of its name (invented TMDB ids). */
        val HARBOR_LIGHTS = picked(tmdbId = 990_101, year = 2019)
        val HARBOR_LIGHTS_2024 = picked(tmdbId = 990_202, year = 2024)

        fun picked(tmdbId: Int, year: Int) = TitleInfo(
            MediaType.Tv, tmdbId, "Harbor Lights", year, "TV series · $year · 1 season", overview = null, posterPath = null, backdropPath = null,
            ids = TvIds(tmdb = tmdbId), runtimeMinutes = 45,
        )

        /**
         * Best match's order. By size: KESTREL (48 GB, ranked 10th), MOORING, QUAY, then WATTWURM, Best match, 4th, with
         * eight smaller after it, so a list kept on it is scrolled; by age, SWELL (a day old, ranked last) first. No two
         * orders put the same release at the same row, so a place read in one order is another release in the next.
         */
        val RELEASES = listOf(
            Release(WATTWURM, 3_200_000_000, 30),
            Release("Harbor.Lights.S01E01.1080p.WEB-DL.DDP5.1.H.264-LANTERN", 2_900_000_000, 31),
            Release("Harbor.Lights.S01E01.1080p.WEBRip.x264-TIDEWATER", 2_400_000_000, 29),
            Release("Harbor.Lights.S01E01.1080p.BluRay.x264-QUAY", 9_000_000_000, 200),
            Release("Harbor.Lights.S01E01.720p.WEB-DL.DDP5.1.H.264-BEACON", 1_600_000_000, 33),
            Release("Harbor.Lights.S01E01.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-MOORING", 18_000_000_000, 90),
            Release("Harbor.Lights.S01E01.720p.WEBRip.x264-GULL", 1_200_000_000, 60),
            Release("Harbor.Lights.S01E01.720p.HDTV.x264-PIER", 1_100_000_000, 35),
            Release("Harbor.Lights.S01E01.480p.WEB.x264-DRIFTWOOD", 700_000_000, 120),
            Release(KESTREL, 48_000_000_000, 400),
            Release("Harbor.Lights.S01E01.1080p.HDTV.x264-BUOY", 1_400_000_000, 32),
            Release(SWELL, 900_000_000, 1),
        )
    }
}
