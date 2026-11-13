package io.skjaere.usenet.app.debrid

import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.app.BuildConfig

/**
 * Whether this build shows debrid (L192): a build made with -PhideDebrid=true, or a beta made without -PwithDebrid=true
 * (L193), hides it ([BuildConfig.HIDE_DEBRID]). Hidden, it offers no way in: no Settings rows, no way through First run, no
 * cards on the setup page, no searches of torrent indexers and no seeding, while what a build that showed it saved stays
 * saved. Every entry point asks [shown], never the build's flag.
 */
object DebridGate {
    /** Test seam: what [shown] answers in place of the build's flag while a test closes or opens the gate ([showing]). */
    @VisibleForTesting
    @Volatile
    internal var shownForTests: Boolean? = null

    val shown: Boolean get() = shownForTests ?: !BuildConfig.HIDE_DEBRID

    /** Tests: [shown] answers [value] while [block] runs, then what it answered before, however [block] ends. */
    @VisibleForTesting
    internal inline fun <T> showing(value: Boolean, block: () -> T): T {
        val before = shownForTests
        shownForTests = value
        try {
            return block()
        } finally {
            shownForTests = before
        }
    }
}
