package io.skjaere.usenet.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.media3.common.Player
import io.skjaere.usenet.app.cast.CastRoute
import io.skjaere.usenet.app.casting.CastDeviceMedia
import io.skjaere.usenet.app.casting.CastFeed
import io.skjaere.usenet.app.casting.CastLauncher
import io.skjaere.usenet.app.casting.CastLinkNow
import io.skjaere.usenet.app.casting.CastVolume
import io.skjaere.usenet.app.link.PlayOn
import io.skjaere.usenet.data.PairedTv
import kotlinx.coroutines.flow.StateFlow

/**
 * Google Cast, as main reaches it (C1, L105): [castExtras], which every build answers with the cast package's GoogleCast
 * since C2. Main names nothing of Google's here and reaches Cast only through this, so Google's types stay in the cast
 * package, and a build without Google's software (one for F-Droid, say) could answer null.
 */
interface CastExtras {
    /**
     * The Cast session now (L77): the device's route and name and how the session is, null while there is none. Play on
     * chooses by it; reading it starts nothing of Google's.
     */
    val now: StateFlow<CastLinkNow?>

    /**
     * Connects to the Cast device at [route], as a tap on its row in the list does (L151): connecting at once, then
     * connected or not, as [now] says; the caller chooses it in Play on. For the player's Play & connect, whose list takes
     * the row's tap (L124): no list closes for it as it connects. On the main thread, once the list's group has looked
     * for devices; before, nothing connects.
     */
    fun connect(route: CastRoute)

    /**
     * Stop casting (L85): ends the session, the device's player stopped, and a cast play with it as Stop casting ends one,
     * a play still starting cancelled with its sheet. Any thread.
     */
    fun stop()

    /**
     * Disconnect (L119): this phone leaves the session while another phone's or app's film plays there, which goes on
     * playing; Play on goes back to This phone with the session. Any thread.
     */
    fun disconnect()

    /**
     * What the device in the session has now (L154): its player's state, whether it is one of this phone's loads, and
     * another sender's title and line, for the list's block alone; null before its first status, and with no session.
     */
    val media: StateFlow<CastDeviceMedia?>

    /**
     * The device's volume in the session (L118): the slider's, one for each session from its start, which reads the
     * session's own level after a drag and remembers a refusal for the session; null with no session.
     */
    val volume: StateFlow<CastVolume?>

    /**
     * Whether a cast play runs (L85): from its tap until it ends, as long as its service does. Its notification opens the
     * remote only then (L86).
     */
    val playing: StateFlow<Boolean>

    /**
     * The cast play on its device as the phone follows it (L91): from its LOAD, and after its end, with how it ended, until
     * the next play's LOAD; null before any. The bar, the remote and the Show page follow it (CastPlaying).
     */
    val feed: StateFlow<CastFeed?>

    /**
     * The device's player (L48): where the bar's and the remote's pause, play and seeks go (L91). Null before the first
     * session's start, which builds it; it then follows each session. On the main thread.
     */
    val player: Player?

    /**
     * Whether Google Play services is on this phone and up to date (L146), as Cast's gate checks it: package facts read
     * on the phone, which start nothing of Google's Cast software and send nothing. Off the main thread; never on a TV.
     */
    fun available(context: Context): Boolean

    /** What starts a cast play on its device (L65, L85): the service's start, the load, the stop. */
    fun launcher(context: Context): CastLauncher

    /**
     * The Play on list's Cast group (L78, L151), between its TVs and its rule. Its first composition is the list's
     * opening, where Google's Cast software starts once this phone looks for Cast devices (L111, L112), the first time and
     * each later one until it can; until then the group asks first, its Privacy opening the privacy page ([onPrivacy],
     * L138). Never on a [television]. A device at the address of one of [tvs], the TVs with NZStreamer this phone keeps,
     * says it also runs NZStreamer (L121). A device's tap goes to [onCastTapped] first, which takes it by answering true;
     * else the group connects it and chooses it in [playOn]. Its rows work from every place the list opens from.
     */
    @Composable
    fun SheetGroup(playOn: PlayOn, television: Boolean, tvs: List<PairedTv>, onPrivacy: () -> Unit, onCastTapped: ((CastRoute) -> Boolean)?)
}
