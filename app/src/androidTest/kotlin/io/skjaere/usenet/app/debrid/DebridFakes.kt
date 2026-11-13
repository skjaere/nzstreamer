package io.skjaere.usenet.app.debrid

import io.skjaere.usenet.debrid.ServiceKind
import org.junit.Assume.assumeTrue
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * The debrid device classes' fakes (L189), host processes the run's lead starts on the emulator host: the Torznab fake
 * (mock-seed torznab, 8096) and the debrid fake (mock-seed debrid, 8097), at 10.0.2.2 from the emulator. Their invented
 * keys and accounts are the fakes' own defaults. A class that needs one skips with a named reason when it isn't there.
 */
object DebridFakes {
    const val HOST = "10.0.2.2"
    const val TORZNAB_PORT = 8096
    const val DEBRID_PORT = 8097

    const val TORBOX_KEY = "fake-torbox-key"
    const val PREMIUMIZE_KEY = "fake-premiumize-key"
    const val TORZNAB_KEY = "fake-torznab-key"

    /** Whose accounts the debrid fake's keys open, as it names them. */
    const val TORBOX_ACCOUNT = "quay.viewer@example.org"
    const val PREMIUMIZE_ACCOUNT = "customer 900000042"

    /** The Torznab fake's Prowlarr-shaped feed. */
    const val PROWLARR_URL = "http://$HOST:$TORZNAB_PORT/prowlarr/1"

    fun reachable(port: Int): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress(HOST, port), 2_000) } }.isSuccess

    fun assumeTorznab() = assumeTrue("the Torznab fake isn't reachable at $HOST:$TORZNAB_PORT (mock-seed torznab --port $TORZNAB_PORT)", reachable(TORZNAB_PORT))

    fun assumeDebrid() = assumeTrue("the debrid fake isn't reachable at $HOST:$DEBRID_PORT (mock-seed debrid --port $DEBRID_PORT)", reachable(DEBRID_PORT))

    /** The debrid fake's address for [kind]'s API, as the app's clients take it. */
    fun endpoint(kind: ServiceKind): String = when (kind) {
        ServiceKind.TorBox -> "http://$HOST:$DEBRID_PORT/torbox/v1/api"
        ServiceKind.Premiumize -> "http://$HOST:$DEBRID_PORT/premiumize/api"
    }

    /** Points the app's clients at the debrid fake; the returned block puts back where they pointed before (the runner's closed address). */
    fun pointServicesAtTheFake(): () -> Unit {
        val before = ServiceEndpoints.override
        ServiceEndpoints.override = ::endpoint
        return { ServiceEndpoints.override = before }
    }

    /** One of the debrid fake's controls (`reset`, `refuse-keys?on=true`, `counts`, …): its answer. */
    fun control(path: String): String {
        val (status, body) = ask(path)
        check(status == 200) { "the debrid fake's control $path answered $status" }
        return body
    }

    /**
     * Holds the debrid fake for this test, waiting while another's holds it: its switches and counts are one set for every
     * emulator reaching it, so a run sharded over two emulators takes turns. A test takes it before its first control and
     * gives it back after its last, by the block returned; the fake lets go by itself of one held five minutes.
     */
    fun holdTheFake(): () -> Unit {
        val by = "test-${java.util.UUID.randomUUID()}"
        val until = System.nanoTime() + HOLD_WAIT_MS * 1_000_000
        while (true) {
            val (status, _) = ask("take?by=$by")
            if (status == 200) break
            check(status == 409 && System.nanoTime() < until) { "the debrid fake was held by another test for ${HOLD_WAIT_MS / 1000} s (or answered $status)" }
            Thread.sleep(250)
        }
        return { control("give-back?by=$by") }
    }

    /** How long a test waits for another's turn at the fake: a long debrid class's run, and more. */
    private const val HOLD_WAIT_MS = 10 * 60_000L

    private fun ask(path: String): Pair<Int, String> {
        val connection = URL("http://$HOST:$DEBRID_PORT/control/$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        try {
            val status = connection.responseCode
            return status to ((if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() } ?: "")
        } finally {
            connection.disconnect()
        }
    }
}
