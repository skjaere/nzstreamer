package io.skjaere.usenet.app.debrid

import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.debrid.premiumize.PremiumizeService
import io.skjaere.usenet.debrid.torbox.TorBoxService

/**
 * Where the clients find each service (L189): the services' own addresses. A test seam, as the player's data sources are:
 * the device tests' runner points it at a closed address (L182), so a device run never reaches a real service unless
 * asked, and a debrid class points it at the debrid fake. No build property or setting can point a build elsewhere.
 */
object ServiceEndpoints {
    /** Test seam: each service's address in place of its own, while set. */
    @VisibleForTesting
    @Volatile
    internal var override: ((ServiceKind) -> String)? = null

    fun of(kind: ServiceKind): String = override?.invoke(kind) ?: when (kind) {
        ServiceKind.Premiumize -> PremiumizeService.BASE_URL
        ServiceKind.TorBox -> TorBoxService.BASE_URL
    }

    /** An address nothing answers at (the discard port on this device): a call there fails at once, as a service down. */
    @VisibleForTesting
    internal const val CLOSED = "http://127.0.0.1:9"
}
