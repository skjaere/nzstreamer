package io.skjaere.usenet.app.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.PlaybackException
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.player.LinkFailure
import io.skjaere.usenet.player.LinkUnavailable
import io.skjaere.usenet.player.UsenetDataSourceException
import kotlinx.coroutines.flow.first

/**
 * Why playback stopped, in words, as the player's panel says it ([PlaybackFailedPanel]), from the
 * debrid link or the Usenet failure behind it when there is one, else from the player's error code.
 * [releaseAtFault]: the release itself is broken or unplayable here (articles missing or damaged,
 * a format the device can't decode), not the provider, the network or the device's decoder or
 * sound output, so it shouldn't be chosen again for what it played. Said for any number of
 * providers (S9-14): an article is missing once every provider lacks it, and a login or connection
 * fails on one of them. While [of]'s providersLeftOut says a provider is left out (its login
 * rejected, or out of reach), an article the others lack may be on that one: not the release's fault.
 * [remote]: the same, as a paired phone whose play this is is told it (L37): in the TV's words, from the failure's kind
 * alone, with nothing of the TV's settings to open there.
 */
internal data class PlaybackFailure(val reason: String, val releaseAtFault: Boolean, val remote: String) {
    companion object {
        fun of(usenet: UsenetError?, errorCode: Int, providersLeftOut: Boolean = false): PlaybackFailure = when (usenet) {
            is UsenetError.MissingArticle -> if (providersLeftOut) {
                PlaybackFailure(
                    "Part of this release is missing from the Usenet providers in use. A provider left out because it rejected the login or couldn't be reached may have it (Settings › Usenet providers).",
                    releaseAtFault = false,
                    remote = "Part of this release is missing from the Usenet providers in use on the TV.",
                )
            } else {
                PlaybackFailure(
                    "Part of this release is missing from every Usenet provider you use.", releaseAtFault = true,
                    remote = "Part of this release is missing from every Usenet provider on the TV.",
                )
            }
            is UsenetError.Corrupt -> PlaybackFailure("Part of this release arrived damaged.", releaseAtFault = true, remote = "Part of this release arrived damaged.")
            is UsenetError.AuthFailed -> provider("A Usenet provider rejected the login (Settings › Usenet providers).", "A Usenet provider on the TV rejected the login.")
            is UsenetError.ConnectionFailed -> provider("A Usenet provider can't be reached.", "The TV can't reach a Usenet provider.")
            is UsenetError.Network -> provider("The connection to a Usenet provider was lost.", "The TV lost the connection to a Usenet provider.")
            is UsenetError.Timeout -> provider("A Usenet provider stopped answering.", "A Usenet provider on the TV stopped answering.")
            is UsenetError.Protocol -> provider("A Usenet provider sent an unexpected answer.", "A Usenet provider on the TV sent an unexpected answer.")
            UsenetError.NotConfigured -> provider("No Usenet provider is set up.", "No Usenet provider is set up on the TV.")
            is UsenetError.Unknown -> provider("Reading the release failed.", READING_FAILED_THERE)
            null -> when (errorCode) {
                in PlaybackException.ERROR_CODE_IO_UNSPECIFIED until PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                    provider("Reading the release failed.", READING_FAILED_THERE)
                in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED until PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                    PlaybackFailure(
                        "The file is damaged or in a format the player can't read.", releaseAtFault = true,
                        remote = "The file is damaged or in a format the TV's player can't read.",
                    )
                // The device's own: a decoder that wouldn't start, or that the system took back.
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ->
                    PlaybackFailure("This device's video or audio decoder stopped working.", releaseAtFault = false, remote = "The TV's video or audio decoder stopped working.")
                in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED until PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ->
                    PlaybackFailure("This device can't decode this release's video or audio.", releaseAtFault = true, remote = "The TV can't decode this release's video or audio.")
                // Sound output gone (an AV receiver switched off, the audio device busy), whatever the release.
                in PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED until PlaybackException.ERROR_CODE_DRM_UNSPECIFIED ->
                    PlaybackFailure("This device's sound output stopped working.", releaseAtFault = false, remote = "The TV's sound output stopped working.")
                else -> PlaybackFailure("Something went wrong while playing.", releaseAtFault = false, remote = "Something went wrong while playing on the TV.")
            }
        }

        /**
         * Why the player's [error] stopped playback: a debrid play's file no longer to be had where its causes hold one
         * ([LinkUnavailable]), else the Usenet failure behind it, else its [errorCode].
         */
        fun of(error: Throwable, errorCode: Int, providersLeftOut: Boolean = false): PlaybackFailure {
            val causes = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(16).toList()
            causes.firstNotNullOfOrNull { it as? LinkUnavailable }?.let { return of(it.failure) }
            return of(causes.firstNotNullOfOrNull { it as? UsenetDataSourceException }?.error, errorCode, providersLeftOut)
        }

        /**
         * A debrid play's file no longer to be had ([link], L204, W81), as DbPhoneFailures says it, once its source asked
         * the service for a fresh link and got none it could read: no words for "expired", which the source renews by
         * itself. Only a refused key names the service, the account's own; only a removal is the release's fault.
         */
        fun of(link: LinkFailure): PlaybackFailure = when (link) {
            LinkFailure.NoWorkingLink -> service("No longer available from the service.", "No longer available from the TV's debrid service.")
            LinkFailure.NotCached -> service("This release is no longer cached.", "This release is no longer cached on the TV's debrid service.")
            LinkFailure.Removed -> PlaybackFailure(
                "The debrid service removed this release, so it can’t be played from there.", releaseAtFault = true,
                remote = "The TV's debrid service removed this release.",
            )
            is LinkFailure.Limit -> service(
                "The debrid service’s limit was reached. " + (link.waitMs?.let { "Try again in about ${about(it)}." } ?: "Try again later."),
                "The TV's debrid service's limit was reached.",
            )
            is LinkFailure.KeyRefused -> service("${link.serviceName} refused your API key. Replace it in Settings › Debrid services.", "The TV's debrid service refused its API key.")
            LinkFailure.Down -> service("The debrid service stopped answering. Try again in a few minutes.", "The TV's debrid service stopped answering.")
            is LinkFailure.Refused -> if (link.tooLarge) {
                service("Too large for your debrid plan.", "Too large for the TV's debrid plan.")
            } else {
                service("The debrid service won’t play this release.", "The TV's debrid service won't play this release.")
            }
        }

        /** A service's wait in words: "20 minutes", "2 hours"; a minute at least. */
        private fun about(waitMs: Long): String {
            val minutes = maxOf(1L, (waitMs + 59_999) / 60_000)
            if (minutes < 60) return if (minutes == 1L) "1 minute" else "$minutes minutes"
            val hours = (minutes + 30) / 60
            return if (hours == 1L) "1 hour" else "$hours hours"
        }

        private fun service(reason: String, remote: String) = PlaybackFailure(reason, releaseAtFault = false, remote = remote)

        /**
         * The player selected none of the release's video ([found], named as the play sheet names it): no decoder of this
         * device takes it. The release's fault, as a format the device can't decode is.
         */
        fun noVideo(found: List<String>): PlaybackFailure {
            val named = if (found.isEmpty()) "" else " (${found.joinToString()})"
            return PlaybackFailure("This device can't play this release's video$named.", releaseAtFault = true, remote = "The TV can't play this release's video$named.")
        }

        private fun provider(reason: String, remote: String) = PlaybackFailure(reason, releaseAtFault = false, remote = remote)

        private const val READING_FAILED_THERE = "Reading the release failed on the TV."
    }
}

/**
 * The player's "Playback stopped" panel, where the next-episode card shows and styled like it:
 * why playback stopped, then Close and Try another release (when [canTryAnother]), and File
 * details (W3), which show what the player read of the file before it stopped: why "No video this
 * device can play", say. Try another release (else Close) takes focus as it shows, so a TV
 * remote's centre button acts at once (on a phone, at the first key from a remote or keyboard).
 * [details] is File details' button, which the focus goes back to as they close.
 *
 * The buttons always show whole: when the room the player leaves it is too low for everything (a
 * landscape phone 360 dp tall at a large font size), the words above them scroll instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaybackFailedPanel(
    reason: String,
    canTryAnother: Boolean,
    onTryAnother: () -> Unit,
    onClose: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
    details: FocusRequester = remember { FocusRequester() },
) {
    val first = remember { FocusRequester() }
    val keys = LocalInputModeManager.current
    LaunchedEffect(canTryAnother) {
        // A button takes the focus only in the remote's input mode, which can reach the window after the panel shows
        // (a phone's first key from a remote or keyboard): wait for it, else nothing would have the focus.
        snapshotFlow { keys.inputMode }.first { it == InputMode.Keyboard }
        first.tryFocus()
    }
    Column(
        modifier.width(300.dp).clip(RoundedCornerShape(16.dp)).background(colors.surface.copy(alpha = 0.94f))
            .semantics { liveRegion = LiveRegionMode.Polite; paneTitle = "Playback stopped" }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Weighted, so the buttons are measured first and the words get what is left.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Playback stopped", style = AppType.small.copy(fontWeight = FontWeight.SemiBold), color = colors.warnStrong)
            Text(reason, style = AppType.bodyStrong.copy(fontSize = 14.sp), color = colors.text)
        }
        // As the card's Stop and Play now; one under the other when a large font leaves no room. File details last: beside
        // Close, or under the two as Try another release leaves no room on their line.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canTryAnother) {
                CardButton("Close", onClose, Modifier, filled = false, horizontalPadding = 16.dp)
                CardButton("Try another release", onTryAnother, Modifier.focusRequester(first), filled = true, horizontalPadding = 16.dp)
            } else {
                CardButton("Close", onClose, Modifier.focusRequester(first), filled = true, horizontalPadding = 16.dp)
            }
            CardButton("File details", onDetails, Modifier.focusRequester(details), filled = false, horizontalPadding = 16.dp)
        }
    }
}
