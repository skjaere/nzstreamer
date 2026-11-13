package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import io.skjaere.usenet.app.debrid.KEY_FORM_PHONE_ROW
import io.skjaere.usenet.app.debrid.NO_SERVICE_LINE
import io.skjaere.usenet.app.debrid.PICKER_LINE
import io.skjaere.usenet.app.debrid.SERVICES_FOOTNOTE
import io.skjaere.usenet.app.debrid.USE_THIS_SERVICE
import io.skjaere.usenet.app.debrid.keyFootnote
import io.skjaere.usenet.app.debrid.shareLine
import io.skjaere.usenet.app.debrid.signOutText
import io.skjaere.usenet.app.debrid.signedInLine
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.app.setup.ADDRESS
import io.skjaere.usenet.app.setup.PHONE_SETUP_DONE
import io.skjaere.usenet.app.setup.PHONE_SETUP_REVIEW
import io.skjaere.usenet.app.setup.PHONE_SETUP_SAVE
import io.skjaere.usenet.app.setup.PHONE_SETUP_TITLE
import io.skjaere.usenet.app.setup.PhoneSetupPart
import io.skjaere.usenet.app.setup.maskKey
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.ui.TV_TYPING_TIP_PHONE
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/**
 * Signing in to a debrid service with a remote (L210; DbTvSignIn 1-4, DbTvSources 2-4) on a real TV: Add a service's
 * services found by the remote, the key form starting on its field with the typing tip and "Easier: Set up with your
 * phone" above it, the field taking the TV's own keyboard, Test and Save against the debrid fake; the phone row opening
 * Set up with your phone, whose page takes a TorBox card that Save keeps, Done then going back to Debrid services; and
 * Sign out's question starting on Cancel. The saved accounts are hidden meanwhile and put back as they were.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class ServiceSignInTvTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private val settings = container.settings
    private val remote = Remote(compose)
    private val home = HomeContentShown()
    private lateinit var saved: AppSettings
    private lateinit var nav: NavHostController
    private var endpointsBack: (() -> Unit)? = null
    private var fakeBack: (() -> Unit)? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeTv()
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        saved = settings.settings.first()
        // Hidden, not signed out of: that would remove their keys from the secret store.
        settings.restore(saved.copy(services = saved.services.map { it.copy(deleted = true) }))
        home.show()
        instrumentation.setInTouchMode(false)
        // As the app's activities are: the keyboard's insets reach the screens.
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
    }

    /** Each step whatever the one before it did: a debrid fake that stopped answering mustn't leave the TV's accounts away. */
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

    private fun keyboardUp(): Boolean = compose.runOnIdle {
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun showTheApp() {
        compose.setTvContent {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        remote.waitForHomeFocus()
    }

    /** Settings, Down to Debrid services by its title, OK: the list. */
    private fun openDebridServices(line: String) {
        compose.runOnIdle { nav.switchTab(SettingsRoute) }
        remote.waitForFocus("Usenet providers")
        val row = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Debrid services", max = 4)
        assertTrue("never reached Debrid services; at $row", row.startsWith("Debrid services"))
        assertTrue("its line: $row", line in row)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** From the empty list, Add a service, then Down to TorBox and OK: its key form, the remote on the field. */
    private fun openTorBoxsForm() {
        openDebridServices("None yet")
        waitFor(hasText(NO_SERVICE_LINE))
        try {
            compose.waitUntil(5_000) { remote.focused().let { it == "Add" || it.startsWith("Add a service") } }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the empty list didn't start on Add; the focus on \"${remote.focused()}\"", e)
        }
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        // DbTvSignIn 1: the two services, A to Z, the remote on the first; the lines under them the phone's with "this TV".
        waitFor(hasText(PICKER_LINE))
        remote.waitForFocus("Premiumize")
        assertTrue("one place at a time, from the TV's side", exists(hasText(shareLine(television = true))))
        val torBox = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "TorBox", max = 4)
        assertTrue("never reached TorBox; at $torBox", torBox.startsWith("TorBox"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Sign in to TorBox"))
        try {
            compose.waitUntil(5_000) { remote.isFocused(fieldGate("API key")) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the key form didn't start on its field; the focus on \"${remote.focused()}\"", e)
        }
    }

    /** DbTvSignIn 2-4: the field takes the TV's keyboard; Test signs in, Save keeps the key and goes back to the list. */
    @Test
    fun theKeyFieldTakesTheTvsKeyboardThenTestAndSaveSignIn() {
        DebridFakes.assumeDebrid()
        fakeBack = DebridFakes.holdTheFake()
        DebridFakes.control("reset")
        endpointsBack = DebridFakes.pointServicesAtTheFake()
        showTheApp()
        openTorBoxsForm()
        // The typing tip and the phone row above the field (DbTvSignIn 2).
        val tip = compose.onNode(hasText(TV_TYPING_TIP_PHONE)).fetchSemanticsNode().boundsInRoot
        val row = compose.onNode(hasText(KEY_FORM_PHONE_ROW)).fetchSemanticsNode().boundsInRoot
        val field = compose.onNode(fieldGate("API key")).fetchSemanticsNode().boundsInRoot
        assertTrue("the tip ($tip) above the phone row ($row) above the field ($field)", tip.bottom <= row.top && row.bottom <= field.top)
        assertTrue("where TorBox keeps the key", exists(hasText("In TorBox: Settings › API key")))
        assertTrue("the key's line, this TV's", exists(hasText(keyFootnote(ServiceKind.TorBox, television = true))))

        // OK on the field: the TV's keyboard (DbTvSignIn 3); the key typed; Back puts the keyboard away.
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        try {
            compose.waitUntil(5_000) { keyboardUp() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("OK on the key's field brought no keyboard; the focus on \"${remote.focused()}\"", e)
        }
        compose.onNode(hasSetTextAction() and isFocused()).performTextReplacement(DebridFakes.TORBOX_KEY)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { !keyboardUp() }
        compose.waitUntil(5_000) { remote.isFocused(fieldGate("API key")) }

        // DbTvSignIn 4: Down, OK on Test.
        val test = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Test", max = 3)
        assertTrue("never reached Test; at $test", test.startsWith("Test"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(signedInLine(Account(DebridFakes.TORBOX_ACCOUNT, "Standard", Instant.parse("2027-01-12T00:00:00Z")))))
        assertEquals(emptyList<Any>(), runBlocking { settings.services.first() })
        val save = remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("Right from Test: $save", save.startsWith("Save"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)

        // Back on Debrid services with TorBox listed.
        waitFor(hasText("TorBox") and hasText(DebridFakes.TORBOX_ACCOUNT, substring = true))
        runBlocking {
            val account = settings.servicesWithKeys().single()
            assertEquals("TorBox", account.record.kind)
            assertEquals(DebridFakes.TORBOX_KEY, account.apiKey)
        }
    }

    /** DbTvSignIn 5: Up from the field, "Easier: Set up with your phone" opens the page as it is. */
    @Test
    fun theKeyFormsPhoneRowOpensSetUpWithYourPhone() {
        showTheApp()
        openTorBoxsForm()
        val row = remote.press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("Up from the field: $row", row.startsWith(KEY_FORM_PHONE_ROW))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { !exists(hasText("Sign in to TorBox")) && exists(hasText(PHONE_SETUP_TITLE)) }
    }

    /** The status and page of a request to [url] as a phone's browser makes it: [form] posted, else a GET; a 303 followed. */
    private fun exchange(url: String, form: List<Pair<String, String>>?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2_000
            connection.readTimeout = 40_000
            if (form != null) {
                val body = form.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }.toByteArray()
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val location = connection.getHeaderField("Location")
            val page = (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            assertTrue("$status from the page", status == 200 || status == 303)
            if (status == 303 && location != null) exchange(URL(URL(url), location.substringBefore('#')).toString(), null) else page
        } finally {
            connection.disconnect()
        }
    }

    /**
     * DbTvSignIn 5-6 (L209): the key form's phone row opens Set up with your phone, whose page now has a Debrid service
     * card; a TorBox card sent from it (tested there, the debrid fake answering) shows on the review as new and tested from
     * the phone; Save keeps it, and Done goes back where the key form's own Save goes, past the form and Add a service, to
     * Debrid services with TorBox listed.
     */
    @Test
    fun aTorBoxCardFromThePhonesPageIsSavedAndDoneGoesBackToDebridServices() {
        DebridFakes.assumeDebrid()
        fakeBack = DebridFakes.holdTheFake()
        DebridFakes.control("reset")
        endpointsBack = DebridFakes.pointServicesAtTheFake()
        showTheApp()
        openTorBoxsForm()
        val row = remote.press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("Up from the field: $row", row.startsWith(KEY_FORM_PHONE_ROW))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        val addressNode = SemanticsMatcher.expectValue(PhoneSetupPart, ADDRESS)
        waitFor(addressNode)
        val address = "http://" + compose.onNode(addressNode).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }.replace("\n", "")

        // The phone's side: the page with its Debrid service section, a TorBox card added, tested and sent.
        var page = exchange(address, null)
        fun press(action: String, fields: List<Pair<String, String>>) {
            val version = Regex("""name="v" value="([0-9]+)"""").find(page)?.groupValues?.get(1) ?: error("no form on the page")
            page = exchange(address, listOf("v" to version, "do" to action) + fields)
        }
        assertTrue("the page's Debrid service section", "<h2>Debrid service</h2>" in page)
        val wizard = "Step 1 of 4" in page
        val torBox = listOf("s.kind" to "torbox", "s.key" to DebridFakes.TORBOX_KEY)
        press("add:service", emptyList())
        press("test", torBox)
        assertTrue("the service's test passed", "<p class=\"result ok\">✓ Signed in as ${DebridFakes.TORBOX_ACCOUNT}" in page)
        assertFalse("the key came back", DebridFakes.TORBOX_KEY in page)
        // The key isn't sent again: the card keeps what was typed (Typed · kept on the TV).
        val sent = torBox.map { if (it.first == "s.key") it.first to "" else it }
        if (wizard) {
            press("next", sent)
            // An indexer step with nothing saved starts with a blank card, which goes as it is.
            press("next", if ("name=\"i.url\"" in page) listOf("i.name" to "", "i.url" to "", "i.key" to "") else emptyList())
            press("next", listOf("t.key" to ""))
            assertTrue("Step 4 of 4" in page)
        }
        press("send", if (wizard) emptyList() else sent)

        // The TV's review: new, tested from the phone; Save, then Done on the same button.
        waitFor(hasText(PHONE_SETUP_REVIEW))
        assertTrue(exists(hasText("Debrid service · New")))
        assertTrue(exists(hasText("TorBox · API key ${maskKey(DebridFakes.TORBOX_KEY)}")))
        remote.waitForFocus(PHONE_SETUP_SAVE)
        // The review lets no OK through for its first second: one meant for what opened it.
        SystemClock.sleep(1_100)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus(PHONE_SETUP_DONE, timeoutMs = 20_000)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)

        // Debrid services with TorBox listed: the key form and Add a service closed, as the form's own Save leaves them.
        waitFor(hasText(SERVICES_FOOTNOTE))
        assertTrue(exists(hasText("TorBox")))
        assertFalse("the key form stayed", exists(hasText("Sign in to TorBox")))
        assertFalse("Add a service stayed", exists(hasText(PICKER_LINE)))
        runBlocking {
            val account = settings.servicesWithKeys().single()
            assertEquals("TorBox", account.record.kind)
            assertEquals(DebridFakes.TORBOX_KEY, account.apiKey)
        }
        // Back from the list leaves for Settings, as it does after the form's own Save.
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus("Debrid services")
    }

    /**
     * TorBox's page, Replace key, then the key form's phone row: TorBox removed on the phone's page and the review saved
     * signs it out, so Done, closing the key form, uncovers a page whose account is gone. It goes back too, to Debrid
     * services with the remote on Add, as the page's own Sign out does: never a page with nothing on it to focus.
     */
    @Test
    fun torBoxRemovedFromThePageItsReplaceKeyOpenedGoesBackToDebridServices() {
        runBlocking {
            // Saved as a key form saves it, the service's answer invented: nothing here asks a service.
            settings.saveService(ServiceKind.TorBox.name, "tv-test-key") { ServiceSignIn(DebridFakes.TORBOX_ACCOUNT, "Standard", Instant.parse("2027-01-12T00:00:00Z").toEpochMilli()) }
        }
        showTheApp()
        openDebridServices("TorBox · signed in")
        val torBox = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "TorBox", max = 3)
        assertTrue("never reached TorBox; at $torBox", torBox.startsWith("TorBox"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(USE_THIS_SERVICE))
        remote.waitForFocus("Test")
        val replace = remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("Right from Test: $replace", replace.startsWith("Replace key"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Replace TorBox key"))
        try {
            compose.waitUntil(5_000) { remote.isFocused(fieldGate("API key")) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the key form didn't start on its field; the focus on \"${remote.focused()}\"", e)
        }
        val row = remote.press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("Up from the field: $row", row.startsWith(KEY_FORM_PHONE_ROW))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        val addressNode = SemanticsMatcher.expectValue(PhoneSetupPart, ADDRESS)
        waitFor(addressNode)
        val address = "http://" + compose.onNode(addressNode).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }.replace("\n", "")

        // The phone's side: TorBox's card, the page's first service, removed, then sent.
        var page = exchange(address, null)
        fun press(action: String, fields: List<Pair<String, String>>) {
            val version = Regex("""name="v" value="([0-9]+)"""").find(page)?.groupValues?.get(1) ?: error("no form on the page")
            page = exchange(address, listOf("v" to version, "do" to action) + fields)
        }
        assertTrue("TorBox's card", "<p class=\"ct\">TorBox</p>" in page)
        val wizard = "Step 1 of 4" in page
        // A step's blank card, if it opened with one, sends nothing and goes as it is.
        press("drop:s1", emptyList())
        if (wizard) {
            press("next", emptyList())
            press("next", emptyList())
            press("next", listOf("t.key" to ""))
            assertTrue("Step 4 of 4" in page)
        }
        press("send", emptyList())

        // The TV's review: TorBox removed; Save signs it out, then Done on the same button.
        waitFor(hasText(PHONE_SETUP_REVIEW))
        assertTrue(exists(hasText("Debrid service · Removes “TorBox”")))
        remote.waitForFocus(PHONE_SETUP_SAVE)
        // The review lets no OK through for its first second: one meant for what opened it.
        SystemClock.sleep(1_100)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus(PHONE_SETUP_DONE, timeoutMs = 20_000)
        assertEquals(emptyList<Any>(), runBlocking { settings.services.first().filter { !it.deleted } })
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)

        // Debrid services, empty, the remote on Add: the key form closed by Done, TorBox's page by its account's going.
        waitFor(hasText(NO_SERVICE_LINE))
        try {
            compose.waitUntil(5_000) { remote.focused().let { it == "Add" || it.startsWith("Add a service") } }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Debrid services didn't start on Add; the focus on \"${remote.focused()}\"", e)
        }
        assertFalse("TorBox's page stayed", exists(hasText(USE_THIS_SERVICE)))
        // Back from the list leaves for Settings, as it does after the page's own Sign out.
        remote.press(KeyEvent.KEYCODE_BACK)
        remote.waitForFocus("Debrid services")
    }

    /** DbTvSources 3-4: TorBox's page; Sign out's question starts on Cancel, so two OKs don't sign out; Right, OK does. */
    @Test
    fun signOutsQuestionStartsOnCancel() {
        val id = runBlocking {
            // Saved as a key form saves it, the service's answer invented: nothing here asks a service.
            settings.saveService(ServiceKind.TorBox.name, "tv-test-key") { ServiceSignIn(DebridFakes.TORBOX_ACCOUNT, "Standard", Instant.parse("2027-01-12T00:00:00Z").toEpochMilli()) }
        }
        showTheApp()
        openDebridServices("TorBox · signed in")
        val torBox = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "TorBox", max = 3)
        assertTrue("never reached TorBox; at $torBox", torBox.startsWith("TorBox"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(USE_THIS_SERVICE))
        val signOut = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Sign out", max = 8)
        assertTrue("never reached Sign out; at $signOut", signOut.startsWith("Sign out"))

        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Sign out of TorBox?"))
        assertTrue(exists(hasText(signOutText(ServiceKind.TorBox, television = true))))
        remote.waitForFocus("Cancel")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { !exists(hasText("Sign out of TorBox?")) }
        assertEquals(listOf(id), runBlocking { settings.services.first().map { it.id } })

        val again = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Sign out", max = 8)
        assertTrue("never back on Sign out; at $again", again.startsWith("Sign out"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("Cancel")
        val confirm = remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("Right from Cancel: $confirm", confirm.startsWith("Sign out") && exists(hasText("Sign out") and isFocused() and hasAnyAncestor(isDialog())))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(NO_SERVICE_LINE))
        assertEquals(emptyList<Any>(), runBlocking { settings.services.first() })
    }
}
