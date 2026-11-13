package io.skjaere.usenet.app.setup

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.tv.Remote
import io.skjaere.usenet.app.tv.setTvContent
import io.skjaere.usenet.handoff.SetupLimits
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL

/**
 * The phone setup's session lives only while its page is in front, over the real server on this device's private
 * address: another page over it, or the activity stopping, closes the port; coming back opens a new code, the old one
 * gone; a code past its lifetime (shortened here) ends with New code focused.
 */
@RunWith(AndroidJUnit4::class)
class PhoneSetupLifecycleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private val limits = PhoneSetupDeps.limits
    private lateinit var nav: NavHostController

    @Serializable
    private data object SetupPage

    @Serializable
    private data object OtherPage

    @Before
    fun privateAddress() {
        val own = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.any { it is Inet4Address && it.isSiteLocalAddress }
        assumeTrue("this device has no private IPv4 address for the page", own)
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        PhoneSetupDeps.limits = limits
        instrumentation.setInTouchMode(true)
    }

    private fun show() {
        compose.setTvContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = SetupPage) {
                composable<SetupPage> { PhoneSetupScreen(onDone = {}, onBack = {}) }
                composable<OtherPage> { Text("Another page") }
            }
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private val addressNode = SemanticsMatcher.expectValue(PhoneSetupPart, ADDRESS)

    /** The page's address as shown ("ip:port/" and the code in fours), once there is one. */
    private fun shown(): String {
        compose.waitUntil(10_000) { exists(addressNode) }
        return compose.onNode(addressNode).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }.replace("\n", "")
    }

    private fun authority(address: String) = address.substringBefore('/').let { it.substringBefore(':') to it.substringAfter(':').toInt() }

    private fun listening(address: String): Boolean {
        val (host, port) = authority(address)
        return runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 1_000) } }.isSuccess
    }

    /** The page's answer to a GET of [address], as a phone's browser asks for it. */
    private fun status(address: String): Int {
        val connection = URL("http://$address").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 2_000
            connection.readTimeout = 5_000
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun waitClosed(address: String) = compose.waitUntil(5_000) { !listening(address) }

    @Test
    fun anotherPageOverItClosesThePortAndBackOpensANewCode() {
        show()
        val first = shown()
        assertEquals("the page, from this device's private address", 200, status(first))
        compose.runOnIdle { nav.navigate(OtherPage) }
        compose.waitUntil(5_000) { exists(hasText("Another page")) }
        waitClosed(first)
        compose.runOnIdle { nav.popBackStack() }
        compose.waitUntil(10_000) { exists(addressNode) && shown() != first }
        val second = shown()
        assertNotEquals(first, second)
        assertEquals(200, status(second))
        // The old code is gone: its port refused, or on the same port by chance, not found there.
        if (authority(first) != authority(second)) assertTrue(!listening(first)) else assertEquals(404, status(first))
    }

    @Test
    fun theActivityStoppingClosesThePortAndStartingAgainOpensANewCode() {
        show()
        val first = shown()
        assertTrue(listening(first))
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitClosed(first)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { exists(addressNode) && shown() != first }
        assertEquals(200, status(shown()))
    }

    @Test
    fun aCodePastItsLifetimeEndsWithNewCodeFocused() {
        // The server keeps the code's time: unopened, it lasts this long.
        PhoneSetupDeps.limits = SetupLimits(unopenedMillis = 2_000)
        show()
        val first = shown()
        compose.waitUntil(8_000) { exists(hasText(PHONE_SETUP_EXPIRED)) }
        waitClosed(first)
        remote.waitForFocus("New code")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { exists(addressNode) }
        assertNotEquals(first, shown())
    }
}
