package io.skjaere.usenet.app.debrid

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.ui.AppTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L199, W71 on a phone, over the fakes ([DebridSearchFixture]): Usenet's results show as they come, and a torrent
 * indexer's torrents join the list only once their cache check settles, never holding it. The debrid fake holds its cache
 * checks over its control path while the test looks, then lets them go (within the search's 10 s for a check).
 */
@RunWith(AndroidJUnit4::class)
class CheckedSearchDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fixture = DebridSearchFixture()

    @Before
    fun setUp() = fixture.open()

    @After
    fun restore() = fixture.close()

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun count(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().size

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) { condition() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never: $what", e)
        }
    }

    /** A pill whose words start with [name]'s, then " · ", as Search draws one per indexer. */
    private fun pill(name: String) = hasText("$name · ", substring = true)

    private val checking = hasText("checking", substring = true)

    /**
     * DbPhoneSearch frames 1-2: Search "harbor lights" shows Indexer A's results while Home Prowlarr's pill says it is
     * checking its torrents; the cached PROPER S01E01, a torrent alone, isn't in S01E01's list until its check comes, then
     * joins it, with the pill saying what Home Prowlarr found.
     */
    @Test
    fun searchShowsUsenetsResultsWhileTorrentsAreCheckedAndACachedOneJoinsAfter() {
        compose.setContent {
            AppTheme { AppNav(nav = rememberNavController()) }
        }
        val searchTab = hasText("Search") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        waitFor("the Search tab", 15_000) { exists(searchTab) }
        compose.onNode(searchTab).performClick()
        waitFor("the search field") { exists(hasSetTextAction()) }

        fixture.holdChecks(true)
        compose.onNode(hasSetTextAction()).performTextInput("harbor lights")
        compose.onNode(hasContentDescription("Search indexers for “harbor lights”") and hasClickAction()).performClick()
        waitFor("Home Prowlarr's pill saying its torrents are being checked", 30_000) { exists(pill("Home Prowlarr") and checking) }
        assertTrue("Indexer A's pill", exists(pill("Indexer A")))
        // Usenet's releases show meanwhile: S01E01's group, from Indexer A.
        val s01e01 = hasContentDescription("for S01E01", substring = true) and hasClickAction()
        assertTrue("S01E01's releases while the torrents are checked", exists(s01e01))
        compose.onNode(s01e01).performClick()
        waitFor("S01E01's list") { exists(hasText(BROKEN)) }
        assertTrue("the cached torrent shown before its check came", !exists(hasText(TIDE)))

        fixture.holdChecks(false)
        waitFor("the cached torrent joining S01E01's list") { exists(hasText(TIDE)) }
        assertTrue("Usenet's release still there", exists(hasText(BROKEN)))
        compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        waitFor("Home Prowlarr's pill once its torrents are checked") { exists(pill("Home Prowlarr")) && !exists(pill("Home Prowlarr") and checking) }
    }

    /**
     * Choose release for S01E02 on Harbor Lights' page: the list shows Usenet's releases once every indexer has answered,
     * while the torrents are checked, and the cached GOOD torrent joins it after, its name beside Usenet's.
     */
    @Test
    fun chooseReleaseShowsUsenetsReleasesWhileAHeldCheckRunsAndTheCachedTorrentJoinsAfter() {
        compose.setContent { AppTheme { ShowScreen(DebridSearchFixture.HARBOR_LIGHTS, onBack = {}) } }
        val episode = hasText(DebridSearchFixture.EPISODES[1]) and hasClickAction()
        waitFor("S01E02's row", 15_000) { exists(episode) }

        fixture.holdChecks(true)
        compose.onNode(episode).performTouchInput { longClick() }
        waitFor("the episode's menu") { exists(hasText("Choose release")) }
        compose.onNode(hasText("Choose release")).performClick()
        waitFor("the list, Usenet's releases in it", 30_000) { exists(hasText(GERMAN)) }
        assertEquals("GOOD from Usenet alone while the check is held", 1, count(hasText(GOOD)))

        fixture.holdChecks(false)
        waitFor("the cached GOOD torrent joining the list") { count(hasText(GOOD)) == 2 }
        assertEquals("Usenet's German release still there", 1, count(hasText(GERMAN)))
    }

    private companion object {
        /** Usenet's S01E01 PROPER (the 8089 indexer's), and the Torznab fake's cached PROPER of the same episode. */
        const val BROKEN = "Harbor.Lights.S01E01.PROPER.1080p.WEB-DL.AAC2.0.H.264-BROKEN"
        const val TIDE = "Harbor.Lights.S01E01.PROPER.1080p.WEB-DL.AAC2.0.H.264-TIDE"

        /** S01E02 on Usenet under both names, and as a cached torrent under the first. */
        const val GOOD = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD"
        const val GERMAN = "Harbor.Lights.S01E02.German.DL.720p.WEB.h264-GERGRP"
    }
}
