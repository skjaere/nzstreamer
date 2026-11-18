package io.skjaere.usenet.app.debrid

import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.debrid.premiumize.PremiumizeService
import io.skjaere.usenet.debrid.torbox.TorBoxService

/**
 * Where the clients find each service (L189): the services' own addresses. A test seam, as the player's data sources are:
 * the device tests' runner points it at a closed address (L182), so a device run never reaches a real service unless
 * asked, and a debrid class points it at the debrid fake. No build property or setting can point a build elsewhere, but a
 * debug build's test file (L464): the downloads' `.debrid`, whose presence points every client at the debrid fake
 * ([pointAtTheFake]) for the app's own processes a device test's pair starts, which no test runner reaches.
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

    /**
     * Every client pointed at the debrid fake ([fake]) from now on, in this process: asked only by a debug build's
     * `.debrid` file (io.skjaere.usenet.app.download.DownloadDebug.pointServices, L464), before any client is made. Nothing
     * in the file is read, so no address can come from it.
     */
    internal fun pointAtTheFake() {
        override = ::fake
    }

    /**
     * The debrid fake's address for [kind]'s API (mock-seed debrid, on the emulator's host, as an emulator reaches it), at
     * the paths the services' clients add theirs to.
     */
    internal fun fake(kind: ServiceKind): String = when (kind) {
        ServiceKind.Premiumize -> "$FAKE/premiumize/api"
        ServiceKind.TorBox -> "$FAKE/torbox/v1/api"
    }

    /** The debrid fake, fixed: the emulator's host, at the port the device tests run it on. */
    private const val FAKE = "http://10.0.2.2:8097"

    /** An address nothing answers at (the discard port on this device): a call there fails at once, as a service down. */
    @VisibleForTesting
    internal const val CLOSED = "http://127.0.0.1:9"
}
