package io.skjaere.usenet.app.debrid

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.UsableRoutes
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.releases.ReleaseFacts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DbPhoneReleases on a phone, over the fakes ([DebridSearchFixture] with Quarry, its switch off): Choose release for Harbor
 * Lights S01E02 lists Usenet's and the torrents' releases in one order, each saying where it plays from after its rank;
 * at equal standing Usenet's GOOD before the same release on TorBox, which says it is cached; the uncached one hidden,
 * counted in the hidden line with its reason, tagged "Torrent". With Home Prowlarr's "Use with your debrid service" off
 * too, every torrent is hidden as not used with the service; with the service off, the list is as today: no tags. With
 * both services (D2PhoneReleaseTags), the tag names the first in the order, with "+1", and follows a move; Search's row
 * follows a failure a play remembers, which its next play passes over, while the release list's keeps every account.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseMarksDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fixture = DebridSearchFixture(quarry = true)
    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container

    @Before
    fun setUp() = fixture.open()

    @After
    fun restore() = fixture.close()

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) { condition() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never: $what", e)
        }
    }

    private val episode = hasText(DebridSearchFixture.EPISODES[1]) and hasClickAction()

    /** Choose release for S01E02, from its row's menu: its list, once every indexer has answered. */
    private fun chooseRelease() {
        waitFor("S01E02's row", 15_000) { exists(episode) }
        compose.onNode(episode).performTouchInput { longClick() }
        waitFor("the episode's menu") { exists(hasText("Choose release")) }
        compose.onNode(hasText("Choose release")).performClick()
        waitFor("S01E02's list", 40_000) { exists(hasText(GERMAN)) }
    }

    private fun back() {
        compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        waitFor("the show's page") { exists(episode) }
    }

    /** A release's card in the list, by its name and what its tag says. */
    private fun card(name: String, from: String) = hasText(name) and hasContentDescription(from) and hasClickAction()

    private fun top(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot.top

    /** Scrolls the list to [matcher]'s card and says whether it is there. */
    private fun scrolledTo(matcher: SemanticsMatcher): Boolean = runCatching {
        compose.onNode(hasScrollAction()).performScrollToNode(matcher)
        exists(matcher)
    }.getOrDefault(false)

    @Test
    fun aListSaysWhereEachReleasePlaysFromUsenetFirstAtEqualStandingAndTheUncachedHidden() {
        compose.setContent { AppTheme { ShowScreen(DebridSearchFixture.HARBOR_LIGHTS, onBack = {}) } }
        chooseRelease()
        val usenet = card(GOOD, "From Usenet") and hasText("Best match")
        val torbox = card(GOOD, "From TorBox") and hasText("#2") and hasText("✓ Cached")
        // The torrents join once their checks have come.
        waitFor("the same release cached on TorBox, #2, tagged TorBox, ✓ Cached") { exists(torbox) }
        assertTrue("Usenet's GOOD, Best match, tagged Usenet", exists(usenet))
        assertTrue("Usenet's first", top(usenet) < top(torbox))
        assertTrue("the German release from Usenet", exists(card(GERMAN, "From Usenet")))

        // The uncached one: hidden, its reason on the line, tagged Torrent, never playable.
        val line = hasText("1 hidden · not playable (Not cached)") and hasClickAction()
        // Quarry's copies show hidden as not used with the service until Home Prowlarr's, checked, take their places.
        waitFor("the hidden line, its reason named, once every check has come") { scrolledTo(line) }
        compose.onNode(line).performClick()
        val hidden = hasText(UNCACHED) and hasContentDescription("Torrent, not playable") and hasText("⚠ Not cached")
        assertTrue("the uncached torrent, hidden and greyed", scrolledTo(hidden))
        assertFalse("a hidden release plays", exists(hidden and hasClickAction()))
        back()

        // Home Prowlarr's switch off too: none of the torrents goes to the service, so each is hidden as not used with it.
        fixture.prowlarrWithService(false)
        chooseRelease()
        val notUsed = hasText("3 hidden · not playable (Not used with your debrid service)") and hasClickAction()
        waitFor("the hidden line, its reason named, once the torrents have joined") { scrolledTo(notUsed) }
        compose.onNode(notUsed).performClick()
        assertTrue(
            "the cached release hidden as not used with the service",
            scrolledTo(hasText(GOOD) and hasContentDescription("Torrent, not playable") and hasText("⚠ Not used with your debrid service")),
        )
        assertFalse("a release says TorBox", exists(hasContentDescription("From TorBox")))
        back()

        // The service off: torrent indexers aren't searched, and the list is as today, with no tags.
        fixture.serviceOn(false)
        chooseRelease()
        assertTrue("Usenet's GOOD", exists(hasText(GOOD) and hasText("Best match")))
        assertFalse("a tag with no service on", exists(hasContentDescription("From Usenet")))
        assertEquals("GOOD once, from Usenet", 1, compose.onAllNodes(hasText(GOOD) and hasClickAction()).fetchSemanticsNodes().size)
    }

    /**
     * D2PhoneReleaseTags 1-3 (L247, W84): Premiumize signed in too and moved first ([DebridSearchFixture.premiumizeFirst],
     * as every device's order starts, A to Z), both having the release cached: its card says it plays from Premiumize, with
     * "+1", read as one ("From Premiumize, also cached on 1 more service"), and no "+1" is read on its own. Once TorBox is
     * moved up, the same card, at the same rank, says TorBox "+1": the ranking reads only whether a torrent is cached.
     */
    @Test
    fun withBothServicesTheTagNamesTheFirstInTheOrderWithItsCountAndFollowsAMove() {
        fixture.premiumizeFirst()
        compose.setContent { AppTheme { ShowScreen(DebridSearchFixture.HARBOR_LIGHTS, onBack = {}) } }
        chooseRelease()
        val usenet = card(GOOD, "From Usenet") and hasText("Best match")
        val premiumize = card(GOOD, "From Premiumize, also cached on 1 more service") and hasText("#2") and hasText("✓ Cached")
        waitFor("the cached release, #2, from Premiumize and one more service", 20_000) { exists(premiumize) }
        assertTrue("Usenet's first", top(usenet) < top(premiumize))
        // What TalkBack reads, the merged tree: the tag and its count one piece, no "+1" on its own.
        assertTrue("a +1 read on its own", compose.onAllNodes(hasText("+1")).fetchSemanticsNodes().isEmpty())

        runBlocking { container.settings.moveService(fixture.torBox, -1) }
        val torBox = card(GOOD, "From TorBox, also cached on 1 more service") and hasText("#2") and hasText("✓ Cached")
        waitFor("the tag following the move") { exists(torBox) }
        assertFalse("still from Premiumize", exists(hasContentDescription("From Premiumize, also cached on 1 more service")))
        assertTrue("Usenet's still first", top(usenet) < top(torBox))
        assertTrue("the German release from Usenet", exists(card(GERMAN, "From Usenet")))
    }

    /**
     * L247, COT-1: Search's S01E02 row, with Usenet's indexer off so its best is the torrent both services have cached,
     * says it plays from Premiumize with "+1". A play from the row then fails there, as Preparing remembers it, Search
     * still shown under the player: the row names TorBox alone, without a search again, as its next play tries TorBox.
     * The release list's play is chosen and tries every account, so its card still names Premiumize with "+1".
     */
    @Test
    fun aSearchRowFollowsAFailureAPlayRemembersAndItsReleaseListKeepsEveryAccount() {
        val premiumize = fixture.premiumizeFirst()
        fixture.usenetIndexerOn(false)
        compose.setContent { AppTheme { AppNav(nav = rememberNavController()) } }
        val searchTab = hasText("Search") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        waitFor("the Search tab", 15_000) { exists(searchTab) }
        compose.onNode(searchTab).performClick()
        waitFor("the search field") { exists(hasSetTextAction()) }
        compose.onNode(hasSetTextAction()).performTextInput("harbor lights")
        compose.onNode(hasContentDescription("Search indexers for “harbor lights”") and hasClickAction()).performClick()
        val fromPremiumize = hasContentDescription("S01E02, From Premiumize, also cached on 1 more service,", substring = true)
        waitFor("S01E02's row from Premiumize, with one more", 40_000) { exists(fromPremiumize) }
        val good = PlayCandidate(
            "btih:$GOOD_HASH", GOOD, null, source = ReleaseFacts.TORRENT, routes = listOf(premiumize, fixture.torBox).map(UsableRoutes::serviceRoute),
        )
        runBlocking { container.serviceMisses.record(good, premiumize, AttemptFailure.NoLongerCached) }
        try {
            waitFor("the row naming TorBox, which its next play tries") { exists(hasContentDescription("S01E02, From TorBox,", substring = true)) }
            assertFalse("Premiumize still named", exists(fromPremiumize))
            assertFalse("one more counted", exists(hasContentDescription("S01E02, From TorBox, also cached", substring = true)))
            // The release list's play is chosen: its card names the first in the order, with its count.
            compose.onNode(hasContentDescription("for S01E02", substring = true) and hasClickAction()).performClick()
            waitFor("S01E02's list, its card from Premiumize with one more", 20_000) { exists(card(GOOD, "From Premiumize, also cached on 1 more service")) }
        } finally {
            runBlocking { container.serviceMisses.forget(good, premiumize) }
        }
    }

    private companion object {
        /** FakeTorrents' GOOD_S01E02. */
        const val GOOD_HASH = "1bb5795cb9f72aa684193ddf405249b071b2ba5f"

        /** S01E02 on Usenet under both names, and on the Torznab fake as a cached torrent under the first and an uncached one. */
        const val GOOD = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD"
        const val GERMAN = "Harbor.Lights.S01E02.German.DL.720p.WEB.h264-GERGRP"
        const val UNCACHED = "Harbor.Lights.S01E02.720p.HDTV.x264-DRIFT"
    }
}
