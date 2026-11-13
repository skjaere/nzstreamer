package io.skjaere.usenet.app.cast

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.media3.common.Player
import io.skjaere.usenet.app.CastExtras
import io.skjaere.usenet.app.casting.CastDeviceMedia
import io.skjaere.usenet.app.casting.CastFeed
import io.skjaere.usenet.app.casting.CastLauncher
import io.skjaere.usenet.app.casting.CastLinkNow
import io.skjaere.usenet.app.casting.CastVolume
import io.skjaere.usenet.app.link.PlayOn
import io.skjaere.usenet.data.PairedTv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * Google Cast in every build (C1; C2, L105): main's [CastExtras], over Google's Cast SDK, Media3's Cast player and
 * MediaRouter, which only this package may use (L66). Nothing of Google's starts until the Play on sheet opens with "Look
 * for Cast devices" chosen (L111).
 */
internal object GoogleCast : CastExtras {
    /** The process's Cast session (L48), on the main thread; made at its first read, which calls nothing of Google's. */
    val connection: CastConnection by lazy { CastConnection(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }

    override val now: StateFlow<CastLinkNow?> get() = connection.now

    override fun connect(route: CastRoute) = connection.connect(route, fromList = false)

    override fun stop() = CastPlays.endCast()

    override fun disconnect() = connection.disconnect()

    override val media: StateFlow<CastDeviceMedia?> get() = connection.media

    override val volume: StateFlow<CastVolume?> get() = connection.volume

    override val playing: StateFlow<Boolean> get() = CastPlays.playing

    override val feed: StateFlow<CastFeed?> get() = Casting.feed

    override val player: Player? get() = connection.made

    override fun available(context: Context) = CastGate.available(context)

    override fun launcher(context: Context): CastLauncher = CastSeams.launcher(context)

    @Composable
    override fun SheetGroup(playOn: PlayOn, television: Boolean, tvs: List<PairedTv>, onPrivacy: () -> Unit, onCastTapped: ((CastRoute) -> Boolean)?) =
        CastSheetGroup(playOn, television, tvs, onPrivacy, onCastTapped)
}
