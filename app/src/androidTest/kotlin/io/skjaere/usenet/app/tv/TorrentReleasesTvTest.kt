package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.search.ReleaseList
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.ResultGroup
import io.skjaere.usenet.search.ResultGrouper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DbTvReleases on a real TV: one release list, Usenet and torrents, with TorBox signed in. Each release says where it
 * plays from after its rank ("Usenet", "TorBox"), a cached one "✓ Cached"; the remote goes down the list in its order,
 * Usenet's first at equal standing, to the hidden line, which names its reasons and opens with OK: the hidden torrents
 * tagged "Torrent", their reasons in words that name no service. The releases and account are invented; no service is
 * asked (the account is saved with invented sign-in facts). The saved accounts are hidden meanwhile, then put back.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class TorrentReleasesTvTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val settings = instrumentation.targetContext.container.settings
    private val remote = Remote(compose)
    private lateinit var saved: AppSettings
    private var account: String? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeTv()
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        saved = settings.settings.first()
        settings.restore(saved.copy(services = saved.services.map { it.copy(deleted = true) }))
        account = settings.saveService(ServiceKind.TorBox.name, "invented-torrent-releases-key") { ServiceSignIn("quay.viewer@example.org") }
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() = runBlocking<Unit> {
        instrumentation.setInTouchMode(true)
        if (!::saved.isInitialized) return@runBlocking
        // Its key goes with it (restore only puts back the settings document).
        account?.let { settings.signOut(it) }
        settings.restore(saved)
    }

    /** Harbor Lights S02E04's releases as the list ranks them for a 1080p screen: two from Usenet, four torrents, KESTREL cached on [ids]. */
    private fun releases(vararg ids: String): ResultGroup {
        fun item(title: String, grabs: Int, hash: String? = null, availability: Availability? = null) = NewznabItem(
            indexerId = if (hash == null) "indexer-a" else "prowlarr", guid = hash?.let { "btih:$it" } ?: title, title = title, sizeBytes = 3_200_000_000,
            publishedAt = null, postedAt = null, categories = emptyList(), grabs = grabs, passworded = false, files = null, tvdbId = null, tmdbId = null,
            imdbId = null, tvmazeId = null, season = 2, episode = 4, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
            infoHash = hash, availability = availability,
        )
        val items = listOf(
            item(KESTREL, 96, "4f0c2d1e3b5a69788796a5b4c3d2e1f0a9b8c7d6", Availability.Cached(ids.toList())),
            item(WATTWURM, 640),
            item(DRIFTWOOD, 205),
            item(FERRY, 37, "8a9b0c1d2e3f405162738495a6b7c8d9e0f1a2b3", Availability.NotCached),
            item(DRIFT, 12, "d2e3f405162738495a6b7c8d9e0f1a2b3c4d5e6f", Availability.NotCached),
            item(HALYARD, 9, "c1d2e3f405162738495a6b7c8d9e0f1a2b3c4d5e", Availability.NotForService),
        )
        return ResultGrouper().group(items, RankingContext(StaticDeviceCapabilities(displayHeight = 1080), tv = true)).sections.single().groups.single()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** A release's card, by its rank and what its tag says. */
    private fun card(rank: String, from: String, name: String) = hasText(rank) and hasContentDescription(from) and hasText(name) and hasClickAction()

    @Test
    fun theRemoteGoesDownTheTaggedReleasesToTheHiddenLineWhichOpensWithOk() {
        val group = releases(account!!)
        compose.setContent { AppTheme(FormFactor.Tv) { ReleaseList(group, "Harbor Lights", onBack = {}, onPlay = {}) } }
        val best = card("Best match", "From Usenet", WATTWURM)
        compose.waitUntil(10_000) { exists(best) }
        compose.onNode(best).requestFocus()
        compose.waitForIdle()
        assertTrue("Best match, from Usenet, focused", remote.isFocused(best))

        // One Down at a time, in the list's order: the cached torrent at its rank, saying so, then Usenet's 720p.
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("#2, from TorBox, cached: ${remote.focused()}", remote.isFocused(card("#2", "From TorBox", KESTREL) and hasText("✓ Cached")))
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("#3, from Usenet: ${remote.focused()}", remote.isFocused(card("#3", "From Usenet", DRIFTWOOD)))
        val line = hasText("3 hidden · not playable (Not cached, Not used with your debrid service)") and hasClickAction()
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("the hidden line, its reasons named: ${remote.focused()}", remote.isFocused(line))
        assertFalse("a hidden release shown before OK", exists(hasText(FERRY)))

        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText(FERRY)) }
        assertTrue("the line says Hide", exists(line and hasText("Hide")))
        assertTrue(
            "the uncached torrent, tagged Torrent, not cached",
            exists(hasText(FERRY) and hasContentDescription("Torrent, not playable") and hasText("⚠ Not cached")),
        )
        assertTrue(
            "the torrent not used with the service, tagged Torrent",
            exists(hasText(HALYARD) and hasContentDescription("Torrent, not playable") and hasText("⚠ Not used with your debrid service")),
        )
        assertFalse("a hidden release takes the focus", exists(hasText(FERRY) and hasClickAction()))
    }

    /**
     * D2PhoneReleaseTags on the TV's cards, which share the phone's layout (L247): Premiumize signed in after TorBox, so
     * last; the release both have cached says "TorBox" with "+1", one piece the remote's focus reads as one, never "+1"
     * alone. Premiumize's account is the test's own, with invented sign-in facts, signed out after.
     */
    @Test
    fun aReleaseOnBothServicesSaysTheFirstWithItsCountReadAsOne() {
        val premiumize = runBlocking { settings.saveService(ServiceKind.Premiumize.name, "invented-torrent-releases-pm-key") { ServiceSignIn("customer 900000042") } }
        try {
            val group = releases(account!!, premiumize)
            compose.setContent { AppTheme(FormFactor.Tv) { ReleaseList(group, "Harbor Lights", onBack = {}, onPlay = {}) } }
            val best = card("Best match", "From Usenet", WATTWURM)
            compose.waitUntil(10_000) { exists(best) }
            compose.onNode(best).requestFocus()
            compose.waitForIdle()
            remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
            val both = card("#2", "From TorBox, also cached on 1 more service", KESTREL) and hasText("✓ Cached")
            assertTrue("#2, from TorBox and one more service: ${remote.focused()}", remote.isFocused(both))
            assertTrue("read as one: ${remote.focused()}", "From TorBox, also cached on 1 more service" in remote.focused())
            // What TalkBack reads, the merged tree: no "+1" on its own.
            assertTrue("a +1 read on its own", compose.onAllNodes(hasText("+1")).fetchSemanticsNodes().isEmpty())
        } finally {
            runBlocking { settings.signOut(premiumize) }
        }
    }

    private companion object {
        const val WATTWURM = "Harbor.Lights.S02E04.1080p.WEB-DL.DDP5.1.H.264-WATTWURM"
        const val KESTREL = "Harbor.Lights.S02E04.1080p.WEB-DL.DDP5.1.H.264-KESTREL"
        const val DRIFTWOOD = "Harbor.Lights.S02E04.720p.WEB-DL.DDP5.1.H.264-DRIFTWOOD"
        const val FERRY = "Harbor.Lights.S02E04.1080p.WEB.H264-FERRY"
        const val DRIFT = "Harbor.Lights.S02E04.720p.HDTV.x264-DRIFT"
        const val HALYARD = "Harbor.Lights.S02E04.1080p.BluRay.x264-HALYARD"
    }
}
