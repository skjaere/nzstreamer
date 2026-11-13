package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.PAIR_PHONE_TITLE
import io.skjaere.usenet.app.link.PAIR_WAITING
import io.skjaere.usenet.app.link.PHONES_TITLE
import io.skjaere.usenet.app.link.TvListener
import io.skjaere.usenet.link.KeyId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import java.net.InetSocketAddress

/**
 * The app on a real TV as the user reaches Settings › Phones and Pair a phone with the remote: launched from the TV
 * launcher, the rail's Settings, then Up from Usenet providers (where the tab starts) to Phones.
 */
class LinkPages(private val compose: ComposeTestRule) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    val remote = Remote(compose)
    private val host get() = context.container.tvLink

    fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch<MainActivity>(context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)!!).also {
            remote.waitForHomeFocus(timeoutMs = 10_000)
        }

    fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** Settings, the focus on Phones, one Up from where the tab starts; what the row says. */
    fun settingsOnPhones(): String {
        compose.onNode(railItem("Settings")).requestFocus()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus("Usenet providers")
        val row = remote.press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("the row above the providers is Phones; focus on \"$row\"", row.startsWith(PHONES_TITLE))
        return row
    }

    /** Settings › Phones, its focus on Pair a phone. */
    fun phones() {
        settingsOnPhones()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.waitForFocus(PAIR_PHONE_TITLE)
    }

    /** Settings › Phones › Pair a phone, waiting for a phone. */
    fun pairPage() {
        phones()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { exists(hasText(PAIR_WAITING)) }
    }

    /** Where the TV's listener is, once it listens. */
    fun listening(): TvListener.Listening {
        compose.waitUntil(10_000) { host.state.value is TvListener.Listening }
        return host.state.value as TvListener.Listening
    }

    fun address(): InetSocketAddress = listening().let { InetSocketAddress(it.address, it.port) }

    /** The TV's key, which a phone pins once paired. */
    fun tvKey(): KeyId = runBlocking { context.container.data.link.identity().identity.key.id }

    /** No listener: no phone paired and no pairing, as each case begins, so the next one binds with the timings set. */
    fun awaitOff() {
        compose.waitUntil(10_000) { host.state.value == TvListener.Off }
    }
}
