@file:androidx.annotation.OptIn(UnstableApi::class)

package io.skjaere.usenet.app.cast

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * The Cast session's player (L48), in a seam (L73): Media3's RemoteCastPlayer, which follows each session Google's
 * software starts by itself, with the app's own items ([CastItems], L62), or a device test's fake. CastConnection builds
 * it once in the process, as the first session starts after the gate, and never releases it: its release ends the
 * session. Never a CastPlayer, which would hand a cast back to a local player of its own (K8).
 */
object CastPlayers {
    @VisibleForTesting
    @Volatile
    var make: (Context) -> Player = { RemoteCastPlayer.Builder(it).setMediaItemConverter(CastItems).build() }
}
