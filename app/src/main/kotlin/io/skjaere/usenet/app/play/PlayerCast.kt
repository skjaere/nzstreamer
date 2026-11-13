package io.skjaere.usenet.app.play

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.skjaere.usenet.app.cast.CastRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.PlayOnDeps
import io.skjaere.usenet.app.link.PlayOnList
import io.skjaere.usenet.app.link.SHEET_TITLE
import io.skjaere.usenet.app.link.SheetPlace

/**
 * The player's Cast button (W44, L115, L124; CsPhonePlayer): in its top bar right of ⓘ, as Google asks of a player, on
 * phones and tablets alone ([shows]); a TV's player has none (L110). It opens Play on's list as the player's own side
 * panel ([PlayOnPanel]). The top bar's layout in dp, as PlayerActivity lays it out: the titles get what the back arrow,
 * the buttons, the bar's padding and their own margins leave ([titleRoom]), 48 dp less with the Cast button (572 to 524
 * dp on an 844 dp wide phone).
 */
internal object PlayerCast {
    /** The top bar's padding at its start and its end, inside the system bars' and the cutout's insets. */
    const val BAR_START_DP = 8
    const val BAR_END_DP = 12

    /** Each of its buttons, the back arrow's too: 48 dp to touch. */
    const val BUTTON_DP = 48

    /** The titles' margins, after the back arrow and before the buttons. */
    const val TITLES_START_DP = 4
    const val TITLES_END_DP = 8

    /** Whether the player shows the Cast button: on a phone or a tablet, never on a [television]. */
    fun shows(television: Boolean): Boolean = !television

    /**
     * The titles' room in a top bar [width] dp wide, the insets at its sides ([safeStart], [safeEnd]) taken off: past the
     * back arrow, the picture-in-picture button where the player offers the window ([pictureInPicture]), the aspect
     * button, the audio and subtitles button, ⓘ, and the Cast button where it shows ([cast]).
     */
    fun titleRoom(width: Int, pictureInPicture: Boolean, cast: Boolean, safeStart: Int = 0, safeEnd: Int = 0): Int {
        val buttons = BACK_AND_ALWAYS + (if (pictureInPicture) 1 else 0) + (if (cast) 1 else 0)
        return width - safeStart - safeEnd - BAR_START_DP - BAR_END_DP - TITLES_START_DP - TITLES_END_DP - buttons * BUTTON_DP
    }

    /** The back arrow, the aspect button, the audio and subtitles button and ⓘ, there on every player. */
    private const val BACK_AND_ALWAYS = 4
}

/**
 * Play on's list over the player (L124, L151; CsPhonePlayer 2): a side panel as the player's others are, with the same
 * rows as everywhere, opened from the player ([SheetPlace.Player]), so your TVs are greyed and say they play from the
 * title's page (L116). Over its title, a Cast session's block (L117). Back, or a tap beside it, closes it ([onClose]).
 * [onAddTv] and [onPrivacy] open those in the app's screens; a Cast row's tap goes to [onCastTapped] first (L151); This
 * phone chosen there, as everywhere, the panel hears it just before ([onChoosing]) and closes, as the sheet does (L148).
 * Kept inside [safeArea] as the player's other panels are.
 */
@Composable
internal fun PlayOnPanel(
    onClose: () -> Unit,
    onAddTv: () -> Unit,
    onPrivacy: () -> Unit,
    safeArea: WindowInsets,
    onCastTapped: ((CastRoute) -> Boolean)? = null,
    onChoosing: () -> Unit = onClose,
) {
    val context = LocalContext.current
    val deps = remember { PlayOnDeps.of(context.container) }
    SidePanel(SHEET_TITLE, onClose, safeArea, titled = false) {
        PlayOnList(deps, SheetPlace.Player, onAddTv, onPrivacy, Modifier, onCastTapped, onChoosing)
    }
}
