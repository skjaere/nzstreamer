package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.SettingsRoute
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.DebridFakes
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.debrid.REMOVE_AFTER_PLAYING
import io.skjaere.usenet.app.debrid.REMOVE_AFTER_PLAYING_HELP
import io.skjaere.usenet.app.debrid.SERVICES_FOOTNOTE
import io.skjaere.usenet.app.debrid.TORRENT_INDEXERS_LINE
import io.skjaere.usenet.app.debrid.USE_THIS_SERVICE
import io.skjaere.usenet.app.debrid.USE_THIS_SERVICE_HELP
import io.skjaere.usenet.app.debrid.USE_WITH_SERVICE
import io.skjaere.usenet.app.debrid.debridServicesHubLine
import io.skjaere.usenet.app.debrid.linksLine
import io.skjaere.usenet.app.debrid.serviceRowLine
import io.skjaere.usenet.app.debrid.serviceSwitchSpoken
import io.skjaere.usenet.app.debrid.signOutText
import io.skjaere.usenet.app.debrid.signedInLine
import io.skjaere.usenet.app.debrid.torrentIndexerRowLine
import io.skjaere.usenet.app.debrid.torrentIndexersFootnote
import io.skjaere.usenet.app.debrid.torrentIndexersHubLine
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.debrid.Account
import io.skjaere.usenet.debrid.ServiceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Settings › Sources with the debrid rows on a real TV (L210; DbTvSources 1-6), by the remote in the TV shell: Debrid
 * services and Torrent indexers below Indexers with their lines, two Downs from Usenet providers; Debrid services with
 * both services signed in, no Add, the remote on its first row; TorBox's page starting on Test, which signs in (the debrid
 * fake answering), its switch and lines, and Sign out's question on Cancel; Remove after playing two Downs from Test on
 * TorBox's page and not on Premiumize's (D2TvServiceSwitch); Torrent indexers starting on + Add, each row's line, and a
 * private tracker's form whose Test connection (the Torznab fake answering) says Torrents and Movies, TV, its "Use with
 * your debrid service" off as saved. Two accounts and three torrent indexers of the test's own, with
 * invented sign-in facts and addresses; the saved ones hidden meanwhile and put back as they were.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class DebridSourcesTvTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val settings = instrumentation.targetContext.container.settings
    private val remote = Remote(compose)
    private val home = HomeContentShown()
    private lateinit var saved: AppSettings
    private lateinit var nav: NavHostController
    private var endpointsBack: (() -> Unit)? = null
    private var fakeBack: (() -> Unit)? = null

    /** The plan's end the accounts are saved with, as the debrid fake's TorBox says it. */
    private val planUntil = Instant.parse("2027-01-12T00:00:00Z")

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeTv()
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        saved = settings.settings.first()
        // Hidden, not signed out of or deleted: that would remove their keys from the secret store.
        settings.restore(saved.copy(services = saved.services.map { it.copy(deleted = true) }, torrentIndexers = saved.torrentIndexers.map { it.copy(deleted = true) }))
        // Saved as the key form saves them, the services' answers invented: nothing here asks a service to save them.
        // Premiumize first: the order is the one they are stored in (L228), here as every device's starts, A to Z.
        val premiumize = settings.saveService(ServiceKind.Premiumize.name, DebridFakes.PREMIUMIZE_KEY) { ServiceSignIn(DebridFakes.PREMIUMIZE_ACCOUNT, null, planUntil.toEpochMilli()) }
        settings.saveService(ServiceKind.TorBox.name, DebridFakes.TORBOX_KEY) { ServiceSignIn(DebridFakes.TORBOX_ACCOUNT, "Standard", planUntil.toEpochMilli()) }
        settings.setServiceEnabled(premiumize, false)
        settings.saveTorrentIndexer(PROWLARR, "Home Prowlarr", "http://192.168.1.20:9696/1", "home-prowlarr-key", enabled = true, useWithService = true)
        settings.saveTorrentIndexer(JACKETT, "Jackett", "http://jackett.home.lan:9117/api/v2.0/indexers/all/results/torznab", "jackett-key", enabled = false, useWithService = true)
        // The private tracker's Test reaches the Torznab fake's Prowlarr feed.
        settings.saveTorrentIndexer(QUARRY, "Quarry", DebridFakes.PROWLARR_URL, DebridFakes.TORZNAB_KEY, enabled = true, useWithService = false)
        home.show()
        instrumentation.setInTouchMode(false)
        compose.setTvContent {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        remote.waitForHomeFocus()
    }

    /** Each step whatever the one before it did: a debrid fake that stopped answering mustn't leave the TV's sources away. */
    @After
    fun restore() = runBlocking<Unit> {
        everyStep(
            // Touch mode outlasts the test's process.
            { instrumentation.setInTouchMode(true) },
            { fakeBack?.invoke() },
            { endpointsBack?.invoke() },
            { home.restore() },
            {
                if (::saved.isInitialized) {
                    settings.services.first().filter { s -> saved.services.none { it.id == s.id } }.forEach { settings.signOut(it.id) }
                    listOf(PROWLARR, JACKETT, QUARRY).forEach { settings.deleteTorrentIndexer(it) }
                    settings.restore(saved)
                    assertEquals(saved, settings.settings.first())
                }
            },
        )
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}; the focus on \"${remote.focused()}\"", e)
        }
    }

    /** Settings, the remote on Usenet providers where it starts. */
    private fun openSettings() {
        compose.runOnIdle { nav.switchTab(SettingsRoute) }
        remote.waitForFocus("Usenet providers")
    }

    /**
     * DbTvSources 1: Debrid services two Downs from Usenet providers, past Indexers, its line naming both services and the
     * one off; Torrent indexers one Down below it, three with one off, searched as a service is on.
     */
    @Test
    fun bothRowsSitBelowIndexersWithTheirLines() {
        openSettings()
        val services = runBlocking { debridServicesHubLine(settings.servicesWithKeys()).text }
        val torrents = runBlocking { torrentIndexersHubLine(settings.torrentIndexers.first(), serviceOn = true).text }
        assertEquals("Premiumize, TorBox · 1 off", services)
        assertEquals("3 torrent indexers · 1 off", torrents)
        assertTrue(remote.press(KeyEvent.KEYCODE_DPAD_DOWN).startsWith("Indexers"))
        val row = remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("two Downs from Usenet providers: $row", row.startsWith("Debrid services") && services in row)
        val next = remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("one Down further: $next", next.startsWith("Torrent indexers") && torrents in next)
    }

    /**
     * D2TvServiceOrder 1-3 (L229): Debrid services as it opens, the remote on Premiumize's row (signed in first, so first);
     * Down to TorBox's row, Right onto its Move up (its only arrow); OK moves TorBox first, and the remote goes with it, onto
     * its Move down, as on Usenet providers. Left goes back to the row, and Back to Settings, whose row names them in the new
     * order.
     */
    @Test
    fun theRemoteMovesTorBoxUpAndGoesWithItOntoItsMoveDown() {
        openSettings()
        assertTrue(remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Debrid services", max = 3).startsWith("Debrid services"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(SERVICES_FOOTNOTE))
        remote.waitForFocus("Premiumize")
        assertTrue("its place read after it: ${remote.focused()}", remote.focused().endsWith("position 1 of 2"))
        val torBox = remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from Premiumize: $torBox", torBox.startsWith("TorBox") && torBox.endsWith("position 2 of 2"))
        assertEquals("Right from TorBox's row", "Move TorBox up", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { runBlocking { settings.services.first().map { it.kind } } == listOf("TorBox", "Premiumize") }
        remote.waitForFocus("Move TorBox down")
        val row = remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left from the arrow: $row", row.startsWith("TorBox") && row.endsWith("position 1 of 2"))
        // Back on Settings, its row names them in the new order.
        remote.press(KeyEvent.KEYCODE_BACK)
        waitFor(hasText("TorBox, Premiumize · 1 off"))
    }

    /**
     * DbTvSources 2-4: Debrid services with both signed in has no Add, the remote on its first row, Down on TorBox; its
     * page starts on Test, which signs in and says whose account and until when; "Use this service" and the links line
     * below; Sign out's question starts on Cancel, and OK there keeps the account.
     */
    @Test
    fun debridServicesThenTorBoxsPageThenSignOutsQuestion() {
        DebridFakes.assumeDebrid()
        fakeBack = DebridFakes.holdTheFake()
        DebridFakes.control("reset")
        endpointsBack = DebridFakes.pointServicesAtTheFake()
        openSettings()
        assertTrue(remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Debrid services", max = 3).startsWith("Debrid services"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)

        // DbTvSources 2.
        waitFor(hasText(SERVICES_FOOTNOTE))
        val accounts = runBlocking { settings.servicesWithKeys() }.associateBy { it.record.kind }
        remote.waitForFocus("Premiumize")
        assertFalse("Add with every service signed in", exists(hasText("Add")))
        assertTrue("Premiumize's line", exists(hasText(serviceRowLine(accounts.getValue("Premiumize")).text)))
        val torBox = remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from Premiumize: $torBox", torBox.startsWith("TorBox"))
        assertTrue("TorBox's line", exists(hasText(serviceRowLine(accounts.getValue("TorBox")).text)))

        // DbTvSources 3: OK on TorBox, the remote on Test; OK on it signs in.
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(USE_THIS_SERVICE))
        remote.waitForFocus("Test")
        assertTrue(exists(hasText(DebridFakes.TORBOX_ACCOUNT)))
        assertTrue(exists(hasText(USE_THIS_SERVICE_HELP)))
        assertTrue(exists(hasText(linksLine(ServiceKind.TorBox))))
        assertTrue("Replace key beside Test", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT).startsWith("Replace key"))
        assertTrue(remote.press(KeyEvent.KEYCODE_DPAD_LEFT).startsWith("Test"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(signedInLine(Account(DebridFakes.TORBOX_ACCOUNT, "Standard", planUntil))))

        // DbTvSources 4: Sign out's question, Cancel first; OK on it keeps the account.
        val signOut = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Sign out", max = 8)
        assertTrue("never reached Sign out; at $signOut", signOut.startsWith("Sign out"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Sign out of TorBox?"))
        assertTrue(exists(hasText(signOutText(ServiceKind.TorBox, television = true))))
        remote.waitForFocus("Cancel")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { !exists(hasText("Sign out of TorBox?")) }
        assertEquals(listOf("Premiumize", "TorBox"), runBlocking { settings.services.first().map { it.kind }.sorted() })
    }

    /**
     * D2TvServiceSwitch 1-2 (L246, W83): Premiumize's page, as it opens with the remote on Test, has no Remove after
     * playing, so Down from Use this service goes on to Sign out; back on the list, TorBox's page has it two Downs from
     * Test, under Use this service, on as saved and read as its words and its line (L251); OK switches it off, and again
     * on, the account keeping each.
     */
    @Test
    fun removeAfterPlayingIsTwoDownsFromTestOnTorBoxsPageAndNotOnPremiumizes() {
        val torBoxId = runBlocking { settings.services.first().single { it.kind == "TorBox" }.id }
        openSettings()
        assertTrue(remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Debrid services", max = 3).startsWith("Debrid services"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(SERVICES_FOOTNOTE))
        remote.waitForFocus("Premiumize")

        // D2TvServiceSwitch 2: Premiumize's page, the remote on Test, Use this service its one switch.
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(USE_THIS_SERVICE))
        remote.waitForFocus("Test")
        assertFalse("Remove after playing on Premiumize's page", exists(hasText(REMOVE_AFTER_PLAYING)))
        val use = remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from Test: $use", use.startsWith(USE_THIS_SERVICE))
        val next = remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from Use this service: $next", next.startsWith("Sign out"))
        remote.press(KeyEvent.KEYCODE_BACK)

        // D2TvServiceSwitch 1: TorBox's page, the switch two Downs from Test.
        waitFor(hasText(SERVICES_FOOTNOTE))
        val row = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "TorBox", max = 3)
        assertTrue("never reached TorBox's row; at $row", row.startsWith("TorBox"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(REMOVE_AFTER_PLAYING))
        remote.waitForFocus("Test")
        assertTrue(remote.press(KeyEvent.KEYCODE_DPAD_DOWN).startsWith(USE_THIS_SERVICE))
        assertEquals("two Downs from Test", serviceSwitchSpoken(REMOVE_AFTER_PLAYING, REMOVE_AFTER_PLAYING_HELP), remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        val switch = hasText(REMOVE_AFTER_PLAYING) and hasText(REMOVE_AFTER_PLAYING_HELP)
        assertTrue("on, as saved", exists(switch and isOn()))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(switch and isOff())
        compose.waitUntil(5_000) { runBlocking { settings.service(torBoxId)?.record?.removeAfterPlay == false } }
        assertTrue("the remote stays on it", remote.isFocused(switch))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(switch and isOn())
        compose.waitUntil(5_000) { runBlocking { settings.service(torBoxId)?.record?.removeAfterPlay == true } }
    }

    /**
     * DbTvSources 5-6: Torrent indexers starting on + Add, its line and footnote, each row's line below ("off", "not used
     * with your debrid service"); OK on Quarry, Test connection says it is a Prowlarr feed of torrents searched in Movies
     * and TV, and Down from it reaches "Use with your debrid service", off as saved.
     */
    @Test
    fun torrentIndexersThenAPrivateTrackersForm() {
        DebridFakes.assumeTorznab()
        openSettings()
        assertTrue(remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Torrent indexers", max = 4).startsWith("Torrent indexers"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)

        // DbTvSources 5.
        waitFor(hasText(TORRENT_INDEXERS_LINE))
        remote.waitForFocus("+ Add")
        assertTrue(exists(hasText(torrentIndexersFootnote(television = true))))
        val records = runBlocking { settings.torrentIndexers.first() }.associateBy { it.name }
        for (name in listOf("Home Prowlarr", "Jackett", "Quarry")) {
            val row = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, name, max = 3)
            val line = torrentIndexerRowLine(records.getValue(name))
            assertTrue("$name's row: $row", row.startsWith(name) && line in row)
        }

        // DbTvSources 6: OK on Quarry; Test connection; Down to the switch, off.
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Remove torrent indexer"))
        val test = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Test connection", max = 12)
        assertTrue("never reached Test connection; at $test", test.startsWith("Test connection"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Connected · Prowlarr"), timeoutMs = 30_000)
        listOf("Results", "Torrents", "Searches in", "Movies, TV").forEach { assertTrue("$it shown", exists(hasText(it))) }
        val switch = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, USE_WITH_SERVICE, max = 8)
        assertTrue("never reached the switch; at $switch", switch.startsWith(USE_WITH_SERVICE))
        assertTrue("off, as saved", exists(hasText(USE_WITH_SERVICE) and isOff()))
    }

    private companion object {
        const val PROWLARR = "tv-test-home-prowlarr"
        const val JACKETT = "tv-test-jackett"
        const val QUARRY = "tv-test-quarry"
    }
}
