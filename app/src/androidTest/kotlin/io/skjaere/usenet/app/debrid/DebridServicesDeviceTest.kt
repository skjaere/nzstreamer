package io.skjaere.usenet.app.debrid

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.SettingsRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.debrid.Account
import io.skjaere.usenet.debrid.ServiceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Settings › Debrid services on a phone (L196; DbPhoneServiceAdd) over the real settings, the clients pointed at the debrid
 * fake (10.0.2.2:8097, mock-seed debrid) through ServiceEndpoints: TorBox signed in by its API key after a wrong key kept
 * nothing; Premiumize with Show, signed in later and so last (L228), and Add gone; a service's page turning it off, Replace key, a key the
 * service refuses later marking it, and Sign out confirmed; TorBox's Remove after playing off and on again (L246). The saved
 * accounts are hidden for the test (tombstoned, their keys untouched) and put back as they were; the test's own are signed
 * out of, their keys removed.
 */
@RunWith(AndroidJUnit4::class)
class DebridServicesDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val settings = container.settings
    private val home = HomeContentShown()
    private lateinit var saved: AppSettings
    private lateinit var nav: NavHostController
    private var endpointsBack: (() -> Unit)? = null
    private var fakeBack: (() -> Unit)? = null

    /** The fake's plan end, as this device's zone shows it. */
    private val planUntil = Instant.parse("2027-01-12T00:00:00Z")
    private val torBoxSignedIn = signedInLine(Account(DebridFakes.TORBOX_ACCOUNT, "Standard", planUntil))
    private val premiumizeSignedIn = signedInLine(Account(DebridFakes.PREMIUMIZE_ACCOUNT, null, planUntil))
    private val torBoxRefused = "TorBox refused this key. Check you copied all of it."

    @Before
    fun setUp() = runBlocking<Unit> {
        DebridFakes.assumeDebrid()
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        fakeBack = DebridFakes.holdTheFake()
        DebridFakes.control("reset")
        endpointsBack = DebridFakes.pointServicesAtTheFake()
        saved = settings.settings.first()
        // Hidden, not signed out of: that would remove their keys from the secret store.
        settings.restore(saved.copy(services = saved.services.map { it.copy(deleted = true) }))
        home.show()
    }

    @After
    fun restore() = runBlocking<Unit> {
        try {
            endpointsBack?.invoke()
            home.restore()
            // Skipped before anything was changed (the fake isn't reachable).
            if (!::saved.isInitialized) return@runBlocking
            settings.services.first().filter { s -> saved.services.none { it.id == s.id } }.forEach { settings.signOut(it.id) }
            settings.restore(saved)
            assertEquals(saved, settings.settings.first())
            DebridFakes.control("reset")
        } finally {
            fakeBack?.invoke()
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}", e)
        }
    }

    /** Scrolled to first where it is in a scrolling column (a dialog's buttons and the first run's foot aren't). */
    private fun click(matcher: SemanticsMatcher) {
        val node = compose.onNode(matcher)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private val keyField = hasSetTextAction()

    /** The bar's Add, as Usenet providers' (a chip with the + and the word). */
    private val addChip = hasText("Add") and hasClickAction()

    /** How often the fake was asked [call] ("torbox user/me"), and how often it saw what the app must never send. */
    private fun calls(call: String): Int = JSONObject(DebridFakes.control("counts")).getJSONObject("calls").getInt(call)

    private fun assertNothingSentThatMustNotBe() {
        val problems = JSONObject(DebridFakes.control("counts")).getJSONObject("problems")
        problems.keys().forEach { assertEquals("the fake saw: $it", 0, problems.getInt(it)) }
    }

    private fun openDebridServices() {
        compose.setContent {
            AppTheme {
                nav = rememberNavController()
                AppNav(nav = nav)
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { nav.navigate(SettingsRoute) }
        waitFor(hasText("Debrid services") and hasClickAction())
        click(hasText("Debrid services") and hasClickAction())
    }

    /** TorBox signed in as the key form does it, before the screens show. */
    private fun signedInToTorBox(): String = runBlocking {
        settings.saveService(ServiceKind.TorBox.name, DebridFakes.TORBOX_KEY) { key -> container.services.trial(ServiceKind.TorBox, key).test().signIn() }
    }

    /** DbPhoneServiceAdd 1-3, close-up 3: Add a service, TorBox's form, a wrong key refused and kept nowhere, then its own. */
    @Test
    fun aServiceIsSignedInByItsKeyAndAWrongKeyKeepsNothing() {
        compose.setContent {
            AppTheme {
                nav = rememberNavController()
                AppNav(nav = nav)
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { nav.navigate(SettingsRoute) }
        // DbPhoneSources: the row after Indexers, with none yet.
        waitFor(hasText("Debrid services") and hasText("None yet") and hasClickAction())
        click(hasText("Debrid services") and hasClickAction())
        waitFor(hasText(NO_SERVICE_LINE))
        assertTrue("the empty list's footnote", exists(hasText(SEARCHED_ONLY_WHILE_ON)))
        // DbPhoneServiceAdd 1: Add as Usenet providers have it, a chip in the bar.
        assertTrue("the bar's Add chip", exists(addChip))
        click(hasText("Add a service") and hasClickAction())

        // DbPhoneServiceAdd 2: both services, A to Z, as plain names.
        waitFor(hasText(PICKER_LINE))
        val premiumize = compose.onNode(hasText("Premiumize") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val torBox = compose.onNode(hasText("TorBox") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("Premiumize ($premiumize) above TorBox ($torBox)", premiumize.bottom <= torBox.top)
        assertTrue("what goes to a service", exists(hasText(HASH_LINE)))
        click(hasText("TorBox") and hasClickAction())

        waitFor(hasText("Sign in to TorBox"))
        assertTrue("where TorBox keeps the key", exists(hasText("In TorBox: Settings › API key")))
        assertTrue("what the key opens", exists(hasText(keyFootnote(ServiceKind.TorBox, television = false))))
        assertTrue("the field read as hidden", exists(keyField and hasContentDescription("API key, hidden")))
        compose.onNode(keyField).performTextReplacement("not-the-fake-key")
        click(hasText("Test") and hasClickAction())
        waitFor(hasText(torBoxRefused))
        // Save signs in again, and keeps nothing of a key the service refused.
        val before = calls("torbox user/me")
        click(hasText("Save") and hasClickAction())
        compose.waitUntil(10_000) { calls("torbox user/me") > before && exists(hasText(torBoxRefused)) }
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), runBlocking { settings.services.first() })
        assertTrue("still on the form", exists(hasText("Sign in to TorBox")))

        compose.onNode(keyField).performTextReplacement(DebridFakes.TORBOX_KEY)
        assertFalse("a changed key clears the line", exists(hasText(torBoxRefused)))
        click(hasText("Test") and hasClickAction())
        waitFor(hasText(torBoxSignedIn))
        click(hasText("Save") and hasClickAction())

        // DbPhoneServiceAdd 5's TorBox row, back on Debrid services.
        waitFor(hasText(SERVICES_FOOTNOTE))
        val row = serviceRowLine(runBlocking { settings.servicesWithKeys() }.single())
        assertEquals(DebridFakes.TORBOX_ACCOUNT, row.text.substringBefore(" · "))
        assertTrue("its row: ${row.text}", exists(hasText("TorBox") and hasText(row.text) and hasClickAction()))
        runBlocking {
            val account = settings.servicesWithKeys().single()
            assertEquals("TorBox", account.record.kind)
            assertEquals(DebridFakes.TORBOX_ACCOUNT, account.record.accountName)
            assertEquals("Standard", account.record.plan)
            assertEquals(DebridFakes.TORBOX_KEY, account.apiKey)
        }
        compose.runOnIdle { nav.popBackStack() }
        waitFor(hasText("Debrid services") and hasText("TorBox · signed in"))
        assertNothingSentThatMustNotBe()
    }

    /**
     * DbPhoneServiceAdd 4, 5: Premiumize's form with Show; signed in after TorBox, it goes last (L228, D2PhoneServiceOrder:
     * a service signed in later goes last), and Add is gone once both are signed in.
     */
    @Test
    fun premiumizeWithShowThenItGoesLastAndAddIsGone() {
        signedInToTorBox()
        openDebridServices()
        waitFor(hasText("TorBox") and hasClickAction() and hasText(DebridFakes.TORBOX_ACCOUNT, substring = true))
        click(addChip)
        waitFor(hasText(PICKER_LINE))
        assertFalse("TorBox is signed in already", exists(hasText("TorBox") and hasClickAction()))
        click(hasText("Premiumize") and hasClickAction())

        waitFor(hasText("Sign in to Premiumize"))
        assertTrue("where Premiumize keeps the key", exists(hasText("In Premiumize: Account › API key")))
        compose.onNode(keyField).performTextReplacement(DebridFakes.PREMIUMIZE_KEY)
        assertTrue("hidden as typed", exists(keyField and hasContentDescription("API key, hidden")))
        click(hasContentDescription("Show API key") and hasClickAction())
        waitFor(hasContentDescription("Hide API key") and hasClickAction())
        assertTrue("shown, the field isn't read as hidden", exists(keyField and hasContentDescription("API key")))
        click(hasText("Test") and hasClickAction())
        waitFor(hasText(premiumizeSignedIn))
        click(hasText("Save") and hasClickAction())

        waitFor(hasText("Premiumize") and hasClickAction() and hasText(DebridFakes.PREMIUMIZE_ACCOUNT, substring = true))
        val premiumize = compose.onNode(hasText("Premiumize") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val torBox = compose.onNode(hasText("TorBox") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("signed in later, last: TorBox ($torBox) above Premiumize ($premiumize)", torBox.bottom <= premiumize.top)
        assertFalse("Add is gone once both are signed in", exists(addChip))
        assertEquals(listOf("TorBox", "Premiumize"), runBlocking { settings.services.first().map { it.kind } })
        assertEquals(DebridFakes.PREMIUMIZE_KEY, runBlocking { settings.servicesWithKeys().single { it.record.kind == "Premiumize" }.apiKey })
        assertNothingSentThatMustNotBe()
    }

    /**
     * DbPhoneServiceAdd 6, 7, close-ups 5 and 6: TorBox's page, its Test, the switch turned off (the row says so), Replace
     * key (a wrong one keeps the old key), a key the service refuses later marking the account, and Sign out confirmed.
     */
    @Test
    fun itsPageTurnsItOffReplacesItsKeyAndSignsOut() {
        val id = signedInToTorBox()
        openDebridServices()
        click(hasText("TorBox") and hasClickAction())
        waitFor(hasText(USE_THIS_SERVICE))
        val facts = accountFacts(runBlocking { settings.service(id)!!.record })
        facts.forEach { (label, value) -> assertTrue("the page's $label", exists(hasText(label)) && exists(hasText(value))) }
        assertEquals("Standard", facts[1].second.substringBefore(" · "))
        assertTrue("the links line", exists(hasText(linksLine(ServiceKind.TorBox))))
        click(hasText("Test") and hasClickAction())
        waitFor(hasText(torBoxSignedIn))

        // Off: still signed in, not used.
        click(hasText(USE_THIS_SERVICE) and isToggleable())
        waitFor(hasText(USE_THIS_SERVICE) and isOff())
        compose.waitUntil(5_000) { runBlocking { settings.service(id)?.record?.enabled == false } }
        compose.runOnIdle { nav.popBackStack() }
        waitFor(hasText("TorBox") and hasText("${DebridFakes.TORBOX_ACCOUNT} · off") and hasClickAction())

        // Replace key: the same form, empty; a wrong key keeps the old one.
        click(hasText("TorBox") and hasClickAction())
        click(hasText("Replace key") and hasClickAction())
        waitFor(hasText("Replace TorBox key"))
        assertTrue("the empty field's words", exists(hasText(REPLACE_KEY_PLACEHOLDER)))
        compose.onNode(keyField).performTextReplacement("a-key-reset-elsewhere")
        click(hasText("Save") and hasClickAction())
        waitFor(hasText(torBoxRefused))
        assertEquals(DebridFakes.TORBOX_KEY, runBlocking { settings.service(id)?.apiKey })
        compose.onNode(keyField).performTextReplacement(DebridFakes.TORBOX_KEY)
        click(hasText("Save") and hasClickAction())
        compose.waitUntil(10_000) { !exists(hasText("Replace TorBox key")) && exists(hasText(USE_THIS_SERVICE)) }
        assertEquals(listOf(id), runBlocking { settings.services.first().map { it.id } })

        // Close-up 5: the key reset on the service's site, found by a call: the account is marked until a new key.
        DebridFakes.control("refuse-keys?on=true")
        click(hasText("Test") and hasClickAction())
        waitFor(hasText(torBoxRefused))
        waitFor(hasText("TorBox refused your API key. Replace it."))
        assertTrue(runBlocking { settings.service(id)!!.record.keyRefused })
        DebridFakes.control("refuse-keys?on=false")

        // DbPhoneServiceAdd 7: Sign out, confirmed first.
        click(hasText("Sign out") and hasClickAction())
        waitFor(hasText("Sign out of TorBox?"))
        assertTrue(exists(hasText(signOutText(ServiceKind.TorBox, television = false))))
        click(hasText("Sign out") and hasClickAction() and hasAnyAncestor(isDialog()))
        waitFor(hasText(NO_SERVICE_LINE))
        runBlocking {
            assertEquals(emptyList<Any>(), settings.services.first())
            assertNull(settings.service(id))
            assertTrue(settings.settings.first().services.single { it.id == id }.deleted)
        }
        assertNothingSentThatMustNotBe()
    }

    /**
     * Close-up 5's mark ends once the service takes the saved key again: refused once (the fake refusing every key a
     * moment, as a proxy's 403 or an outage's 401 would), then the page's Test passes, and the page no longer says the key
     * was refused under "Signed in as"; the account is on again.
     */
    @Test
    fun aKeyRefusedOnceIsTheAccountsAgainWhenItsTestPasses() {
        val id = signedInToTorBox()
        openDebridServices()
        click(hasText("TorBox") and hasClickAction())
        waitFor(hasText(USE_THIS_SERVICE))
        DebridFakes.control("refuse-keys?on=true")
        click(hasText("Test") and hasClickAction())
        waitFor(hasText(TORBOX_KEY_REFUSED))
        DebridFakes.control("refuse-keys?on=false")
        click(hasText("Test") and hasClickAction())
        waitFor(hasText(torBoxSignedIn))
        try {
            compose.waitUntil(10_000) { !exists(hasText(TORBOX_KEY_REFUSED)) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("still said under a passed Test: $TORBOX_KEY_REFUSED", e)
        }
        runBlocking {
            assertFalse(settings.service(id)!!.record.keyRefused)
            assertEquals(listOf(id), container.services.on().map { it.id })
        }
        assertNothingSentThatMustNotBe()
    }

    /**
     * D2PhoneServiceSwitch 1 (L246, W83): TorBox's page has Remove after playing under Use this service, on as the account
     * was saved, read by TalkBack as its words and then its line (L251); switched off, the account keeps it off and stays
     * on, and switched on again it keeps that.
     */
    @Test
    fun removeAfterPlayingTurnsOffAndOnAgainOnTorBoxsPage() {
        val id = signedInToTorBox()
        openDebridServices()
        click(hasText("TorBox") and hasClickAction())
        waitFor(hasText(USE_THIS_SERVICE))
        val switch = hasText(REMOVE_AFTER_PLAYING) and hasText(REMOVE_AFTER_PLAYING_HELP) and isToggleable()
        waitFor(switch and isOn())
        assertTrue("read as its words, then its line", exists(switch and hasContentDescription(serviceSwitchSpoken(REMOVE_AFTER_PLAYING, REMOVE_AFTER_PLAYING_HELP))))
        val use = compose.onNode(hasText(USE_THIS_SERVICE) and isToggleable()).fetchSemanticsNode().boundsInRoot
        val remove = compose.onNode(switch).fetchSemanticsNode().boundsInRoot
        assertTrue("under Use this service ($use): $remove", use.bottom <= remove.top)

        click(switch)
        waitFor(switch and isOff())
        compose.waitUntil(5_000) { runBlocking { settings.service(id)?.record?.removeAfterPlay == false } }
        assertTrue("the service stays on", runBlocking { settings.service(id)!!.record.enabled })
        click(switch)
        waitFor(switch and isOn())
        compose.waitUntil(5_000) { runBlocking { settings.service(id)?.record?.removeAfterPlay == true } }
        assertNothingSentThatMustNotBe()
    }

    /** Replace key starts the page afresh: a Test's line about the key it replaced doesn't stay under the new key's account. */
    @Test
    fun aReplacedKeyLeavesNoTestLineOfTheOldOne() {
        signedInToTorBox()
        openDebridServices()
        click(hasText("TorBox") and hasClickAction())
        waitFor(hasText(USE_THIS_SERVICE))
        DebridFakes.control("refuse-keys?on=true")
        click(hasText("Test") and hasClickAction())
        waitFor(hasText(torBoxRefused))
        DebridFakes.control("refuse-keys?on=false")
        click(hasText("Replace key") and hasClickAction())
        waitFor(hasText("Replace TorBox key"))
        compose.onNode(keyField).performTextReplacement(DebridFakes.TORBOX_KEY)
        click(hasText("Save") and hasClickAction())
        compose.waitUntil(10_000) { !exists(hasText("Replace TorBox key")) && exists(hasText(USE_THIS_SERVICE)) }
        compose.waitForIdle()
        assertFalse("the replaced key's Test line stayed", exists(hasText(torBoxRefused)))
        assertFalse("the mark ended with the key saved again", exists(hasText(TORBOX_KEY_REFUSED)))
        assertNothingSentThatMustNotBe()
    }

    /**
     * D2PhoneServiceOrder 1-2 (L228, L229): both signed in, Premiumize first (signed in first here, as every device's
     * order starts, A to Z); each row its place and its one arrow; Move TorBox up puts it first in the settings, on the
     * list and in Settings' row ("TorBox, Premiumize · signed in"), its row a polite live region read in its new place.
     */
    @Test
    fun moveTorBoxUpPutsItFirstOnTheListAndInSettingsRow() {
        runBlocking {
            settings.saveService(ServiceKind.Premiumize.name, DebridFakes.PREMIUMIZE_KEY) { ServiceSignIn(DebridFakes.PREMIUMIZE_ACCOUNT, null, planUntil.toEpochMilli()) }
            settings.saveService(ServiceKind.TorBox.name, DebridFakes.TORBOX_KEY) { ServiceSignIn(DebridFakes.TORBOX_ACCOUNT, "Standard", planUntil.toEpochMilli()) }
        }
        openDebridServices()
        waitFor(hasText(SERVICES_FOOTNOTE))
        fun top(name: String) = compose.onNode(hasText(name) and hasClickAction()).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Premiumize first", top("Premiumize") < top("TorBox"))
        // The first can only go down, the last only up.
        assertTrue(exists(hasContentDescription("Move Premiumize down")))
        assertFalse(exists(hasContentDescription("Move Premiumize up")))
        assertTrue(exists(hasContentDescription("Move TorBox up")))
        assertFalse(exists(hasContentDescription("Move TorBox down")))

        click(hasContentDescription("Move TorBox up"))
        compose.waitUntil(10_000) { runBlocking { settings.services.first().map { it.kind } } == listOf("TorBox", "Premiumize") }
        val spoken = serviceRows(runBlocking { settings.servicesWithKeys() }).first().spoken
        assertTrue(spoken, spoken.startsWith("TorBox, ${DebridFakes.TORBOX_ACCOUNT}, until ") && spoken.endsWith(", position 1 of 2"))
        waitFor(hasContentDescription(spoken) and hasClickAction())
        compose.waitForIdle()
        assertTrue("TorBox first now", top("TorBox") < top("Premiumize"))
        assertTrue(exists(hasContentDescription("Move TorBox down")))
        assertFalse(exists(hasContentDescription("Move TorBox up")))
        // The row is a live region: TalkBack reads it out in its new place.
        val row = compose.onNode(hasContentDescription(spoken) and hasClickAction()).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, row.config.getOrNull(SemanticsProperties.LiveRegion))

        compose.runOnIdle { nav.popBackStack() }
        waitFor(hasText("Debrid services") and hasText("TorBox, Premiumize · signed in"))
        assertNothingSentThatMustNotBe()
    }

    private companion object {
        /** The page's and the row's line for a key the service refused (close-up 5). */
        const val TORBOX_KEY_REFUSED = "TorBox refused your API key. Replace it."
    }
}
