package io.skjaere.usenet.app.link

import java.io.IOException

/**
 * The port the TV's listener takes (L12): 28462 unless another was taken before, so a phone finds the TV again at the
 * address and port it remembers, mDNS or not. 28462 isn't in /etc/services and lies below Android's ephemeral range
 * (32768-60999). When the port is busy, any free one is taken, and saved to be taken first next time.
 */
object PortChoice {
    const val DEFAULT = 28_462

    /** What [bind] opened: [value] on [port]; [save] when that port isn't the one remembered, which it then becomes. */
    class Bound<T>(val value: T, val port: Int, val save: Boolean)

    /**
     * Opens on the [remembered] port, else [default], and on any free one when that one is busy. [open] binds on the port
     * it is given (0: any) and returns what it opened and the port it is on, or throws an IOException. Throws the last
     * one when no port could be bound (the address went, say).
     */
    fun <T> bind(remembered: Int?, default: Int = DEFAULT, open: (Int) -> Pair<T, Int>): Bound<T> {
        val first = remembered?.takeIf { it in 1..MAX_PORT } ?: default
        var failure: IOException? = null
        for (port in listOf(first, ANY).distinct()) {
            val (value, bound) = try {
                open(port)
            } catch (e: IOException) {
                failure = e
                continue
            }
            return Bound(value, bound, save = bound != remembered)
        }
        throw failure ?: IOException("no port to bind")
    }

    private const val ANY = 0
    private const val MAX_PORT = 65_535
}
