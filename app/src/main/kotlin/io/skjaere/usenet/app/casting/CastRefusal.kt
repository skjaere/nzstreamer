package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.debrid.ServicePlayFailure
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.player.LinkUnavailable

/**
 * A debrid cast whose head, opened ahead of the LOAD, the service refused (L218, C1): a cut of the device's first answer
 * is fatal on the Shield (core:serve's ServeLimits), so the load fails there, before anything is sent to the device, in
 * the refusal's own words (D2PhoneCastWords), and is remembered against the account as a play's failure is. A head that
 * failed otherwise sends the LOAD as for Usenet: the device's first request opens its own.
 */
object CastRefusal {
    /**
     * What the head's [error] says of [launch], a debrid play's whose listing the account [account] prepared: its failure,
     * by the account, as a play's failures are remembered ([ServicePlayFailure]), when a [LinkUnavailable] is among its
     * causes; null for any other failure, and for a launch that isn't a debrid play's.
     */
    fun of(launch: PlayerLaunch, error: Throwable, account: String): ServicePlayFailure? {
        val refused = generateSequence(error) { it.cause }.take(MAX_CAUSES).filterIsInstance<LinkUnavailable>().firstOrNull() ?: return null
        return ServicePlayFailure.of(launch, refused.failure) { account }
    }

    /**
     * The card for [refused]: its kind's words after ✕, which name no service but a refused key's; the release's fault, so
     * Try another release leaves it out, as for Usenet (D2PhoneCastWords 3).
     */
    fun card(refused: ServicePlayFailure): CastLoadFailure = CastLoadFailure(CastFailKind.CouldNotPlay, refused.failure.text, providers = true)

    /** How deep the causes are looked through: Media3's wraps one or two. */
    private const val MAX_CAUSES = 16
}
