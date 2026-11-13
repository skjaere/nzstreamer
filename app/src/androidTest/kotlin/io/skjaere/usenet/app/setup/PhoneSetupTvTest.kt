package io.skjaere.usenet.app.setup

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.DebridFakes
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.app.tv.Remote
import io.skjaere.usenet.app.tv.railItem
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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.net.URLEncoder

/**
 * "Set up with your phone" on a real TV (tv36), launched from the TV launcher: Settings › Set up with your phone opens
 * the page at the TV's own private IPv4 address; a provider (the mock NNTP server, which its test from the page needs)
 * added from this device and sent shows on the review, the port closed from then on; Back there, through Android 16's
 * own back dispatch, asks first, and Discard keeps nothing and leaves the page, no new code opened. A TorBox account's
 * card (L209, the debrid fake answering its test) sent the same way shows on the review as "Debrid service · New" over
 * "TorBox · API key •••• …".
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class PhoneSetupTvTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val remote = Remote(compose)
    private val home = HomeContentShown()

    @Before
    fun tv() {
        assumeTv()
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", listening("10.0.2.2", 1119))
        assumeTrue("the mock NNTP server's control API isn't reachable at 10.0.2.2:8081", runCatching { statMissing() }.isSuccess)
        home.show()
        instrumentation.setInTouchMode(false)
    }

    /** Has the mock NNTP server answer a provider test's STAT with 430, as PhoneSetupEndToEndTest does. */
    private fun statMissing() {
        val connection = URL("http://10.0.2.2:8081/mocks/stat").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 2_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"articleId":"<provider-test@usenet-streamer.invalid>","exists":false}""".encodeToByteArray()) }
            check(connection.responseCode in 200..299) { "the mock answered ${connection.responseCode}" }
        } finally {
            connection.disconnect()
        }
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        home.restore()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private val addressNode = SemanticsMatcher.expectValue(PhoneSetupPart, ADDRESS)

    private fun listening(host: String, port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 1_000) } }.isSuccess

    /** The status and page of a request to [url]: [form] posted urlencoded, else a GET. */
    private fun exchange(url: String, form: List<Pair<String, String>>?): Pair<Int, String> {
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
            // A form that is read is answered with a 303 back to the page, which a browser follows with a GET.
            if (status == 303 && location != null) exchange(URL(URL(url), location.substringBefore('#')).toString(), null) else status to page
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A provider added from the phone's page and tested, then sent: on the edit page at once, in the wizard (the TV
     * can't play yet) through its steps, the others left as they are. Send's status.
     */
    private fun sendThrough(address: String, provider: List<Pair<String, String>>): Int {
        var page = exchange("http://$address", null).second
        fun press(action: String, fields: List<Pair<String, String>>): String {
            val version = Regex("""name="v" value="([0-9]+)"""").find(page)?.groupValues?.get(1) ?: error("no form on the page")
            return exchange("http://$address", listOf("v" to version, "do" to action) + fields).second.also { page = it }
        }
        val wizard = page.contains("Step 1 of 4")
        press("add:provider", emptyList())
        press("test", provider)
        assertTrue("the provider's test passed", page.contains("<p class=\"result ok\">✓ "))
        if (wizard) {
            press("next", provider)
            assertTrue(page.contains("Step 2 of 4"))
            // An indexer step with nothing saved starts with a blank card, which goes as it is.
            press("next", if (page.contains("name=\"i.url\"")) listOf("i.name" to "", "i.url" to "", "i.key" to "") else emptyList())
            assertTrue(page.contains("Step 3 of 4"))
            press("next", listOf("t.key" to ""))
            assertTrue(page.contains("Step 4 of 4"))
            return exchange("http://$address", listOf("v" to Regex("""name="v" value="([0-9]+)"""").find(page)!!.groupValues[1], "do" to "send")).first
        }
        return exchange("http://$address", listOf("v" to Regex("""name="v" value="([0-9]+)"""").find(page)!!.groupValues[1], "do" to "send") + provider).first
    }

    /** The page from Settings: the address it shows, checked to be one of the TV's own private IPv4 ones. */
    private fun openThePage(): String {
        remote.waitForHomeFocus(timeoutMs = 10_000)
        compose.onNode(railItem("Settings")).requestFocus()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("Usenet providers")
        // Atop the card above the providers, Phones (Play on TV) between them.
        assertTrue("the top card's first row", remote.pressUntil(KeyEvent.KEYCODE_DPAD_UP, PHONE_SETUP_TITLE, max = 2).startsWith(PHONE_SETUP_TITLE))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { exists(addressNode) }
        return compose.onNode(addressNode).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }.replace("\n", "")
    }

    /** Back on the review, then the dialog's Discard: nothing kept, the page gone and no new code opened. */
    private fun discard(before: io.skjaere.usenet.data.AppSettings) {
        val settings = context.container.settings
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { exists(hasText(PHONE_SETUP_DISCARD_ASK)) }
        remote.waitForFocus("Keep")
        compose.onAllNodes(hasText("Discard") and hasClickAction()).onLast().requestFocus()
        remote.waitForFocus("Discard")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { exists(hasText(PHONE_SETUP_TITLE) and hasClickAction()) && !exists(hasText(PHONE_SETUP_REVIEW)) }
        assertTrue("nothing kept", before == runBlocking { settings.settings.first() })
    }

    /**
     * L209 on tv36: a TorBox card added from the phone's page and tested there (on the TV, the debrid fake answering),
     * then sent, shows on the review as "Debrid service · New" over "TorBox · API key •••• …", tested from the phone. The
     * TV's own saved accounts and torrent indexers are taken away for it and put back after, so its card is new on either
     * page; Discard keeps nothing.
     */
    @Test
    fun aTorBoxCardSentFromThePhoneShowsOnTheReview() {
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        DebridFakes.assumeDebrid()
        val settings = context.container.settings
        val kept = runBlocking { settings.settings.first() }
        val keys = runBlocking {
            kept.activeServices.mapNotNull { a -> settings.service(a.id)?.apiKey?.let { a.apiKeySecret to it } } +
                kept.activeTorrentIndexers.mapNotNull { r -> settings.torrentIndexer(r.id)?.apiKey?.let { r.apiKeySecret to it } }
        }
        val giveBack = DebridFakes.holdTheFake()
        val pointBack = DebridFakes.pointServicesAtTheFake()
        try {
            // Taken away once the fake is held, inside the try: the finally puts them back whatever happens from here.
            runBlocking {
                kept.activeServices.forEach { settings.signOut(it.id) }
                kept.activeTorrentIndexers.forEach { settings.deleteTorrentIndexer(it.id) }
            }
            DebridFakes.control("reset")
            val before = runBlocking { settings.settings.first() }
            val launch = context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)!!
            ActivityScenario.launch<MainActivity>(launch).use {
                val address = openThePage()
                var page = exchange("http://$address", null).second
                fun press(action: String, fields: List<Pair<String, String>>): String {
                    val version = Regex("""name="v" value="([0-9]+)"""").find(page)?.groupValues?.get(1) ?: error("no form on the page")
                    return exchange("http://$address", listOf("v" to version, "do" to action) + fields).second.also { page = it }
                }
                val wizard = page.contains("Step 1 of 4")
                assertTrue(page.contains("<h2>Debrid service</h2>"))
                val torbox = listOf("s.kind" to "torbox", "s.key" to DebridFakes.TORBOX_KEY)
                press("add:service", emptyList())
                press("test", torbox)
                assertTrue("the service's test passed", page.contains("<p class=\"result ok\">✓ Signed in as ${DebridFakes.TORBOX_ACCOUNT}"))
                assertFalse("the key came back", page.contains(DebridFakes.TORBOX_KEY))
                val sent = torbox.map { if (it.first == "s.key") it.first to "" else it }
                if (wizard) {
                    press("next", sent)
                    // An indexer step with nothing saved starts with a blank card, which goes as it is.
                    press("next", if (page.contains("name=\"i.url\"")) listOf("i.name" to "", "i.url" to "", "i.key" to "") else emptyList())
                    press("next", listOf("t.key" to ""))
                    assertTrue(page.contains("Step 4 of 4"))
                }
                val version = Regex("""name="v" value="([0-9]+)"""").find(page)!!.groupValues[1]
                assertEquals(200, exchange("http://$address", listOf("v" to version, "do" to "send") + if (wizard) emptyList() else sent).first)
                compose.waitUntil(10_000) { exists(hasText(PHONE_SETUP_REVIEW)) }
                assertTrue(exists(hasText("Debrid service · New")))
                assertTrue(exists(hasText("TorBox · API key ${maskKey(DebridFakes.TORBOX_KEY)}")))
                assertTrue(exists(hasText("✓ $PHONE_TESTED")))
                remote.waitForFocus(PHONE_SETUP_SAVE)
                discard(before)
            }
        } finally {
            // Each step whatever the one before it did: a fake that stopped answering mustn't keep the TV's accounts away.
            runBlocking {
                everyStep(
                    { pointBack() },
                    { giveBack() },
                    {
                        // As it was: the secrets first, then the records, as the end-to-end class puts them back.
                        keys.forEach { (name, value) -> context.container.data.secrets.put(name, value) }
                        settings.restore(kept)
                    },
                )
            }
        }
    }

    @Test
    fun fromSettingsThePagePostsAndBackOnTheReviewKeepsNothing() {
        val settings = context.container.settings
        val before = runBlocking { settings.settings.first() }
        val launch = context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)!!
        ActivityScenario.launch<MainActivity>(launch).use {
            remote.waitForHomeFocus(timeoutMs = 10_000)
            compose.onNode(railItem("Settings")).requestFocus()
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            remote.waitForFocus("Usenet providers")
            // Atop the card above the providers, Phones (Play on TV) between them.
            assertTrue("the top card's first row", remote.pressUntil(KeyEvent.KEYCODE_DPAD_UP, PHONE_SETUP_TITLE, max = 2).startsWith(PHONE_SETUP_TITLE))
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)

            compose.waitUntil(10_000) { exists(addressNode) }
            val address = compose.onNode(addressNode).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }.replace("\n", "")
            val host = address.substringBefore(':')
            val port = address.substringBefore('/').substringAfter(':').toInt()
            val ip = InetAddress.getByName(host)
            assertTrue("the page's address is private IPv4", ip is Inet4Address && ip.isSiteLocalAddress)
            val own = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            assertTrue("the page's address is one of the TV's own", ip in own)

            val sent = listOf("p.name" to "Invented (tv36 test)", "p.host" to "10.0.2.2", "p.port" to "1119", "p.user" to "", "p.pass" to "", "p.conns" to "2")
            assertEquals(200, sendThrough(address, sent))
            compose.waitUntil(10_000) { exists(hasText(PHONE_SETUP_REVIEW)) }
            assertTrue(exists(hasText("Usenet provider · New")))
            // Single use: the form that arrived closed the port.
            compose.waitUntil(5_000) { !listening(host, port) }
            remote.waitForFocus(PHONE_SETUP_SAVE)

            remote.press(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { exists(hasText(PHONE_SETUP_DISCARD_ASK)) }
            remote.waitForFocus("Keep")
            // The dialog's.
            compose.onAllNodes(hasText("Discard") and hasClickAction()).onLast().requestFocus()
            remote.waitForFocus("Discard")
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) { exists(hasText(PHONE_SETUP_TITLE) and hasClickAction()) && !exists(hasText(PHONE_SETUP_REVIEW)) }
            assertTrue("nothing kept", before == runBlocking { settings.settings.first() })
            // Gone, and no new code opened in its place.
            Thread.sleep(1_000)
            assertFalse(exists(addressNode))
            assertFalse(exists(hasText(PHONE_SETUP_DISCARD_ASK)))
        }
    }
}
