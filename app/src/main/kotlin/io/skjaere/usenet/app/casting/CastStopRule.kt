package io.skjaere.usenet.app.casting

/**
 * What the connected device's button does (W49, L119): Stop casting, which ends the session and closes Google's player on
 * the device, for everyone; or Disconnect, which takes only this phone off and leaves the device playing.
 */
enum class CastStop { StopCasting, Disconnect }

/**
 * Stop casting or Disconnect (W49, L119). This phone's own cast play there ([casting], from its tap until it ends; or the
 * device's [media] one of this phone's loads), or nothing playing there (no media heard, or the device's player idle):
 * Stop casting. The film comes from this phone, so stopping it stops it for everyone (a recorded deviation from Sender
 * stops cast A). Another phone's or app's media playing, paused or buffering there: Disconnect.
 */
object CastStopRule {
    fun of(casting: Boolean, media: CastDeviceMedia?): CastStop = when {
        casting || media == null || media.ours || !media.playing -> CastStop.StopCasting
        else -> CastStop.Disconnect
    }
}
