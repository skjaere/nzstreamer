package io.skjaere.usenet.app.setup

import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether "Set up with your phone" is started (L32): from its page's start to its stop, whatever it shows (waiting, the
 * review, its tests, the save), not only while TV-8's server is up, which closes as a form arrives. A phone's Play is
 * refused `busy-setup` meanwhile.
 */
class SetupPages {
    private val started = AtomicInteger()

    val open: Boolean get() = started.get() > 0

    fun started() {
        started.incrementAndGet()
    }

    fun stopped() {
        started.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
}
