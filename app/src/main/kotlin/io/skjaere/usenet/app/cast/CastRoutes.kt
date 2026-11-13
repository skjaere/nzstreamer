package io.skjaere.usenet.app.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import io.skjaere.usenet.app.casting.CAST_ALSO_RUNS
import io.skjaere.usenet.app.casting.CAST_GROUP_LINE
import io.skjaere.usenet.app.casting.CAST_SPEAKER_LINE
import io.skjaere.usenet.app.casting.CastDeviceFacts
import io.skjaere.usenet.app.casting.CastDeviceKind
import io.skjaere.usenet.data.PairedTv

/**
 * A route MediaRouter knows, as plain values (L78): its [id] (what a tap selects), its [name], the device's own
 * [description] ("Casting Default Media Receiver" while another sender uses it), whether it is the phone's own route
 * ([isDefault]) or [bluetooth], [enabled], whether it [matches] Cast's selector, and what the Cast device says it is: one
 * that shows video ([videoOut]), a speaker [group], and its [deviceId], which its session names. Then the device's facts
 * (L153): its [model], its IPv4 [address], and whether its route says its volume is fixed ([fixedVolume]); and whether
 * it plays sound ([audioOut], L122: a speaker plays nothing else). It prints without its name or its facts.
 */
data class CastRoute(
    val id: String,
    val name: String,
    val description: String?,
    val isDefault: Boolean,
    val bluetooth: Boolean,
    val enabled: Boolean,
    val matches: Boolean,
    val videoOut: Boolean,
    val group: Boolean,
    val deviceId: String?,
    val model: String? = null,
    val address: String? = null,
    val fixedVolume: Boolean = false,
    val audioOut: Boolean = false,
) {
    /** A group, whatever its members show; else a device that shows video, or a speaker. */
    val kind: CastDeviceKind get() = when {
        group -> CastDeviceKind.Group
        videoOut -> CastDeviceKind.Video
        else -> CastDeviceKind.Speaker
    }

    /** What the session with this device carries of it (L153). */
    val facts: CastDeviceFacts get() = CastDeviceFacts(deviceId, model, address, kind, fixedVolume)

    override fun toString() = "CastRoute(${if (videoOut) "video" else "audio"}${if (group) ", group" else ""})"
}

/** The routes there are, in a seam (L73): MediaRouter's on a phone, a device test's own. */
fun interface CastRouteSource {
    /** Looks for routes until closed (an active scan, while the sheet shows), telling [found] each change on the main thread. */
    fun watch(found: (List<CastRoute>) -> Unit): AutoCloseable
}

/**
 * The Cast group's devices (L78, L122; W50): each Cast device, enabled and matching Cast's selector, that shows a
 * picture, plays sound or is a speaker group; never the phone's own route, a Bluetooth one, or one that plays neither.
 * The screens first, then the speakers, then the speaker groups, each by name: none is hidden for what it is (Terms
 * 3.4.5), though a speaker plays only the sound. (MediaRouter's own `isDefaultOrBluetooth()` is its library's alone, so
 * the two are read apart.)
 */
object CastRows {
    fun of(routes: List<CastRoute>): List<CastRoute> =
        routes.filter { !it.isDefault && !it.bluetooth && it.enabled && it.matches && (it.videoOut || it.audioOut || it.group) }
            .sortedWith(compareBy<CastRoute> { it.kind.ordinal }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id })

    /**
     * [route]'s own line under its name, as the group lists it: its own text while it is busy ("Casting Default Media
     * Receiver", L121); at the address of one of your [tvs] with NZStreamer, that it also runs NZStreamer, with the TV's
     * name where the two differ (the Shield, in both groups); a speaker's or a speaker group's, that it plays only the
     * sound (W50); a screen's, none.
     */
    fun line(route: CastRoute, tvs: List<PairedTv>): String? {
        route.description?.takeIf { it.isNotBlank() }?.let { return it }
        val tv = route.address?.let { address -> tvs.firstOrNull { it.address == address } }
        if (tv != null) return if (tv.name == route.name) CAST_ALSO_RUNS else "$CAST_ALSO_RUNS: ${tv.name}"
        return when (route.kind) {
            CastDeviceKind.Video -> null
            CastDeviceKind.Speaker -> CAST_SPEAKER_LINE
            CastDeviceKind.Group -> CAST_GROUP_LINE
        }
    }
}

/** MediaRouter's routes for the Default Media Receiver (L78), scanned for actively while watched; on the main thread. */
internal class MediaRouterSource(context: Context) : CastRouteSource {
    private val router = MediaRouter.getInstance(context.applicationContext)
    private val selector = castSelector()

    override fun watch(found: (List<CastRoute>) -> Unit): AutoCloseable {
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = found(read())

            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = found(read())

            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = found(read())
        }
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        found(read())
        return AutoCloseable { router.removeCallback(callback) }
    }

    private fun read(): List<CastRoute> = router.routes.map { route ->
        val device = CastDevice.getFromBundle(route.extras)
        CastRoute(
            id = route.id, name = route.name, description = route.description, isDefault = route.isDefault, bluetooth = route.isBluetooth,
            enabled = route.isEnabled, matches = route.matchesSelector(selector),
            videoOut = device?.hasCapability(CastDevice.CAPABILITY_VIDEO_OUT) == true,
            group = device?.hasCapability(CastDevice.CAPABILITY_MULTIZONE_GROUP) == true, deviceId = device?.deviceId,
            model = device?.modelName, address = device?.inetAddress?.hostAddress,
            fixedVolume = route.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED,
            audioOut = device?.hasCapability(CastDevice.CAPABILITY_AUDIO_OUT) == true,
        )
    }
}

/** MediaRouter's selector for devices that run the Default Media Receiver (L78). */
internal fun castSelector(): MediaRouteSelector =
    MediaRouteSelector.Builder().addControlCategory(CastMediaControlIntent.categoryForCast(RECEIVER_ID)).build()
