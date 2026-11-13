package io.skjaere.usenet.app

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.settings.ThisDeviceScreen
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.player.AudioRouteWatcher
import io.skjaere.usenet.player.MediaCodecDeviceCapabilities
import io.skjaere.usenet.player.Passthrough
import io.skjaere.usenet.releases.AudioCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The HDMI audio route followed by the app as it runs (TV-7's L9): a receiver switched on after the app started, which
 * Media3's override of what its receiver read stands in for, reaches the ranking every search and play asks for, the log
 * header's Audio line and This device's Over HDMI row while the page shows, with one line in the log. The app's own
 * watcher, started by the container; the route is put back as the platform reads it afterwards.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class AudioRouteFollowDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private var original: Passthrough? = null

    private val withTrueHd = Passthrough(setOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DOLBY_TRUEHD))

    /** The container's watcher, once the start has registered it (after the decoder probe). */
    private fun watcher(): AudioRouteWatcher {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            container.audioRoute?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("the app never started following the audio route")
    }

    private fun route(): Passthrough = (runBlocking { container.rankingContext().device } as MediaCodecDeviceCapabilities).passthrough

    @After
    fun putBack() {
        val was = original ?: return
        val real = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
        instrumentation.runOnMainSync { container.audioRoute?.override(real) }
        // A route lost counts once it has held 2 s.
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline && container.device.latest?.passthrough != was) Thread.sleep(100)
        assertEquals("the route as the platform reads it, after the test", was, container.device.latest?.passthrough)
    }

    @Test
    fun aRouteChangeReachesTheRankingTheHeaderAndThisDevice() {
        val watcher = watcher()
        val before = route().also { original = it }
        assumeTrue("this device already passes DD, DD+ and TrueHD through", before != withTrueHd)
        compose.setContent { AppTheme { ThisDeviceScreen(onBack = {}) } }
        compose.waitUntil(5_000) { exists(hasText("rapidyenc")) && !exists(hasText("…")) }

        // A receiver switched on: Media3's receiver reads DD, DD+ and TrueHD over HDMI.
        instrumentation.runOnMainSync { watcher.override(AudioCapabilities(intArrayOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DOLBY_TRUEHD), 8)) }

        // A gain counts at once.
        assertEquals(withTrueHd, route())
        assertTrue("the ranking still can't play TrueHD", runBlocking { container.rankingContext().device.canDecode(AudioCodec.TrueHd) })
        val audio = container.logHeader.lines().single { it.startsWith("Audio: ") }
        assertTrue(audio, audio.endsWith("; DD, DD+, TrueHD passed through over HDMI"))
        val line = "Audio route changed: DD, DD+, TrueHD passed through over HDMI (was ${before.said})"
        assertTrue("\"$line\" isn't in the log", LogBuffer.snapshot().any { it.message == line })
        Log.i(TAG, "logged: $line")
        val shown = listOf("Over HDMI", "DD, DD+, TrueHD", "passed through to the TV or receiver, as it is now: switching a receiver on or off updates this")
        try {
            compose.waitUntil(5_000) { rows()["Over HDMI"] == shown }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the route changed, This device still says ${rows()["Over HDMI"]}", e)
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Each row as TalkBack reads it, one merged node: its words, label first, by its label. */
    private fun rows(): Map<String, List<String>> = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
        .mapNotNull { node -> node.config[SemanticsProperties.Text].map { it.text }.takeIf { it.size >= 2 } }
        .associateBy { it.first() }

    private companion object {
        const val TAG = "AudioRouteFollow"
    }
}
