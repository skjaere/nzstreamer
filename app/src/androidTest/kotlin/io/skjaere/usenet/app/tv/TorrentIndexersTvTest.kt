package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasProgressBarRangeInfo
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
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.debrid.TORRENT_INDEXERS_LINE
import io.skjaere.usenet.app.debrid.USE_WITH_SERVICE
import io.skjaere.usenet.app.debrid.torrentIndexersFootnote
import io.skjaere.usenet.app.setup.PHONE_SETUP_TITLE
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.ui.TV_TYPING_TIP_PHONE
import io.skjaere.usenet.data.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Settings › Torrent indexers with a remote (L210; DbTvSources 1, 5, 6), in the TV shell as a TV shows it: the row found
 * by its title below Indexers, the list starting on + Add as Indexers' does, its rows below, and a private tracker's form,
 * which starts with the typing tip and the phone row as the indexer form does, its "Use with your debrid service" a
 * switch OK turns, its Test connection keeping the focus while it runs. One torrent indexer of the test's own, Quarry (an
 * invented address, never asked; for its Test, a silent port on this device), the saved ones hidden meanwhile and put
 * back as they were.
 */
@RunWith(AndroidJUnit4::class)
class TorrentIndexersTvTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val settings = instrumentation.targetContext.container.settings
    private val remote = Remote(compose)
    private val home = HomeContentShown()
    private lateinit var saved: AppSettings
    private lateinit var nav: NavHostController

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        saved = settings.settings.first()
        // Hidden, not deleted: deleting would remove their keys from the secret store. The service accounts too, so the
        // hub's line says none is searched without a service.
        settings.restore(saved.copy(torrentIndexers = saved.torrentIndexers.map { it.copy(deleted = true) }, services = saved.services.map { it.copy(deleted = true) }))
        settings.saveTorrentIndexer(QUARRY, "Quarry", "http://192.168.1.20:9696/3", "quarry-test-key", enabled = true, useWithService = false)
        home.show()
        instrumentation.setInTouchMode(false)
        compose.setTvContent {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        remote.waitForHomeFocus()
    }

    @After
    fun restore() = runBlocking<Unit> {
        // Touch mode outlasts the test's process.
        instrumentation.setInTouchMode(true)
        home.restore()
        if (!::saved.isInitialized) return@runBlocking
        settings.deleteTorrentIndexer(QUARRY)
        settings.restore(saved)
        assertEquals(saved, settings.settings.first())
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** Settings, then Down to Torrent indexers by its title, and OK: the list. */
    private fun openTheList() {
        compose.runOnIdle { nav.switchTab(SettingsRoute) }
        remote.waitForFocus("Usenet providers")
        val row = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Torrent indexers", max = 4)
        assertTrue("never reached Torrent indexers; at $row", row.startsWith("Torrent indexers"))
        assertTrue("its line: $row", "1 torrent indexer · not searched without a service" in row)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText(TORRENT_INDEXERS_LINE)) }
    }

    /**
     * DbTvSources 1 and 5: the row below Indexers, the remote passing Indexers on its way Down from Usenet providers (with
     * whatever row comes between them, as Debrid services will); the list's first focus on + Add, its rows below.
     */
    @Test
    fun theRowIsFoundByItsTitleAndTheListStartsOnAdd() {
        compose.runOnIdle { nav.switchTab(SettingsRoute) }
        remote.waitForFocus("Usenet providers")
        val passed = mutableListOf<String>()
        while (passed.size < 4 && passed.lastOrNull()?.startsWith("Torrent indexers") != true) passed += remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        val indexers = passed.indexOfFirst { it.startsWith("Indexers") }
        assertTrue("Down from Usenet providers: $passed", indexers >= 0 && passed.last().startsWith("Torrent indexers") && indexers < passed.lastIndex)
        repeat(passed.size) { remote.press(KeyEvent.KEYCODE_DPAD_UP) }
        openTheList()
        remote.waitForFocus("+ Add")
        // By what the device is (L36), as the walk's Phones row: this TV on tv36, this phone where the shell runs on a phone.
        val television = instrumentation.targetContext.container.television
        assertTrue("the footnote names this device", exists(hasText(torrentIndexersFootnote(television))))
        val row = remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from + Add: $row", row.startsWith("Quarry"))
        assertTrue("the row's line: $row", "192.168.1.20 · not used with your debrid service" in row)
    }

    /**
     * DbTvSources 6: the form starts with the typing tip and "Set up with your phone" above its fields, the remote on the
     * phone row; Down reaches the debrid switch, which OK turns, the focus staying on it.
     */
    @Test
    fun theFormStartsWithTheTipAndThePhoneRowAndItsSwitchTakesOk() {
        openTheList()
        remote.waitForFocus("+ Add")
        assertTrue(remote.press(KeyEvent.KEYCODE_DPAD_DOWN).startsWith("Quarry"))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Remove torrent indexer")) }
        assertTrue("the typing tip, leading to the phone", exists(hasText(TV_TYPING_TIP_PHONE)))
        remote.waitForFocus(PHONE_SETUP_TITLE)
        val tip = compose.onNode(hasText(TV_TYPING_TIP_PHONE)).fetchSemanticsNode().boundsInRoot
        val name = compose.onNode(fieldGate("Name")).fetchSemanticsNode().boundsInRoot
        assertTrue("the tip ($tip) above the first field ($name)", tip.bottom <= name.top)

        val switch = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, USE_WITH_SERVICE, max = 12)
        assertTrue("never reached the switch; at $switch", switch.startsWith(USE_WITH_SERVICE))
        assertTrue("off, as saved", exists(hasText(USE_WITH_SERVICE) and isOff()))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText(USE_WITH_SERVICE) and isOn()) }
        assertTrue("the focus stays on the switch; it is on ${remote.focused()}", remote.focused().startsWith(USE_WITH_SERVICE))
    }

    /**
     * DbTvSources 6's walk: Test connection keeps the remote's focus while its test runs, as the key form's Test does (a
     * button disabled meanwhile drops it on a field above). Quarry's address here is a port on this device that never
     * answers, so the test is still running when the focus is read; closed, the test fails, and the focus is still there.
     */
    @Test
    fun testConnectionKeepsTheFocusWhileItRuns() {
        // Android's loopback address is ::1: the address the form is given is this socket's own.
        val silent = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        try {
            runBlocking { settings.saveTorrentIndexer(QUARRY, "Quarry", "http://127.0.0.1:${silent.localPort}", "quarry-test-key", enabled = true, useWithService = false) }
            openTheList()
            remote.waitForFocus("+ Add")
            assertTrue(remote.press(KeyEvent.KEYCODE_DPAD_DOWN).startsWith("Quarry"))
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { exists(hasText("Remove torrent indexer")) }
            remote.waitForFocus(PHONE_SETUP_TITLE)
            val button = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Test connection", max = 12)
            assertTrue("never reached Test connection; at $button", button.startsWith("Test connection"))
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            val running = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)
            compose.waitUntil(5_000) { exists(running) }
            compose.waitForIdle()
            assertTrue("the focus left Test connection while it ran: it is on ${remote.focused()}", remote.focused().startsWith("Test connection"))
            silent.close()
            compose.waitUntil(30_000) { !exists(running) }
            compose.waitForIdle()
            assertTrue("the focus left Test connection once it ended: it is on ${remote.focused()}", remote.focused().startsWith("Test connection"))
        } finally {
            silent.close()
        }
    }

    private companion object {
        const val QUARRY = "tv-test-quarry"
    }
}
