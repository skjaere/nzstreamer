package io.skjaere.usenet.app

import kotlinx.coroutines.CancellationException

/**
 * A teardown's [steps], each run whatever the ones before it threw, then the first failure thrown with the rest
 * suppressed on it: a call to a host fake that fails (the fake stopped meanwhile), or a check of what the test left,
 * can't keep the device's settings, keys and seams from being put back, so the classes after this one don't find them
 * as it left them. A cancellation goes at once.
 */
suspend fun everyStep(vararg steps: suspend () -> Unit) {
    var first: Throwable? = null
    for (step in steps) {
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val earlier = first
            if (earlier == null) first = e else earlier.addSuppressed(e)
        }
    }
    first?.let { throw it }
}
