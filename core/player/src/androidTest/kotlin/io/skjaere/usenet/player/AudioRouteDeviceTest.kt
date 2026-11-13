package io.skjaere.usenet.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The HDMI audio route as the app follows it ([AudioRouteWatcher]), through Media3's own receiver on the device it runs
 * on, a receiver switched on standing in as Media3's override of what it read: the route at registration is the decoder
 * probe's and no change, a new set of encodings is one, and a channel count alone none. And a TV's player is made to have
 * Media3 select its tracks again when the route changes, a phone's isn't.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class AudioRouteDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val heard = CopyOnWriteArrayList<Passthrough>()
    private lateinit var watcher: AudioRouteWatcher

    private val dd = Passthrough(setOf(C.ENCODING_AC3, C.ENCODING_E_AC3))
    private val withTrueHd = Passthrough(setOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DOLBY_TRUEHD))

    @Before
    fun build() = instrumentation.runOnMainSync { watcher = AudioRouteWatcher(context) { heard += it } }

    @After
    fun stop() = instrumentation.runOnMainSync { watcher.stop() }

    /** Started on the main thread, as the app starts it; the platform's answers to the registration let settle. */
    private fun start(): Passthrough {
        var route: Passthrough? = null
        instrumentation.runOnMainSync { route = watcher.start() }
        // The device callback's current list and the sticky HDMI broadcast come after the registration returns.
        SystemClock.sleep(SETTLE_MS)
        instrumentation.waitForIdleSync()
        return route!!.also { Log.i(TAG, "route at registration: ${it.said}") }
    }

    private fun override(encodings: IntArray, channels: Int) =
        instrumentation.runOnMainSync { watcher.override(AudioCapabilities(encodings, channels)) }

    @Test
    fun theRouteAtRegistrationIsTheDecoderProbesAndNoChange() {
        val route = start()
        assertEquals(MediaCodecDeviceCapabilities(context).passthrough, route)
        assertEquals(emptyList<Passthrough>(), heard.toList())
    }

    @Test
    fun aNewSetOfEncodingsIsOneChange() {
        val route = start()
        override(intArrayOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DOLBY_TRUEHD), 8)
        instrumentation.waitForIdleSync()
        assertEquals(listOfNotNull(withTrueHd.takeIf { it != route }), heard.toList())
    }

    @Test
    fun aChannelCountAloneIsNoChange() {
        val route = start()
        override(intArrayOf(C.ENCODING_AC3, C.ENCODING_E_AC3), 8)
        override(intArrayOf(C.ENCODING_AC3, C.ENCODING_E_AC3), 6)
        instrumentation.waitForIdleSync()
        assertEquals(listOfNotNull(dd.takeIf { it != route }), heard.toList())
    }

    /**
     * Atmos (E-AC-3 JOC) coming back after a TV's HDMI re-sync passes no other codec through, but the switch's wait asks
     * for all of what passed through before: the route as heard follows it.
     */
    @Test
    fun anAtmosOnlyChangeIsFollowedForTheSwitchsWait() {
        start()
        override(intArrayOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DOLBY_TRUEHD), 8)
        override(intArrayOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC, C.ENCODING_DOLBY_TRUEHD), 8)
        instrumentation.waitForIdleSync()
        var heardLast: Set<Int>? = null
        instrumentation.runOnMainSync { heardLast = watcher.route?.encodings }
        assertEquals(setOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC, C.ENCODING_DOLBY_TRUEHD), heardLast)
        assertEquals(heardLast, heard.last().encodings)
    }

    /**
     * What a TV's player asks of Media3: to select its tracks again when its renderers' capabilities change, as the audio
     * route's do (the flag, kept as the player's parameters are set again); a phone's isn't asked. That Media3 then moves a
     * play to passthrough as a receiver is switched on mid-film is its own, a check on the Shield.
     */
    @Test
    fun aTvsPlayerAsksMedia3ToSelectAgainOnACapabilityChangeAndAPhonesDoesNot() {
        val nothing = DataSource.Factory { error("nothing is read") }
        val follows = mutableListOf<Boolean>()
        instrumentation.runOnMainSync {
            val tv = PlayerFactory.create(context, nothing, DefaultLoadControl(), followAudioRoute = true)
            val phone = PlayerFactory.create(context, nothing, DefaultLoadControl())
            try {
                follows += follows(tv)
                follows += follows(phone)
                // Kept as the player's languages and plans are set on it, as PlayerActivity sets them.
                tv.trackSelectionParameters = tv.trackSelectionParameters.buildUpon().setPreferredAudioLanguages("en").build()
                follows += follows(tv)
            } finally {
                tv.release()
                phone.release()
            }
        }
        assertEquals(listOf(true, false, true), follows)
    }

    private fun follows(player: ExoPlayer) = (player.trackSelector as DefaultTrackSelector).parameters.allowInvalidateSelectionsOnRendererCapabilitiesChange

    private companion object {
        const val TAG = "AudioRouteDeviceTest"
        const val SETTLE_MS = 1_000L
    }
}
