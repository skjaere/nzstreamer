package io.skjaere.usenet.app.link

/**
 * Below API 34, the services found waiting to be resolved, one at a time, as NsdService allows a client
 * ([LinkNsdFinder]): which to resolve next, and whether a resolve's answer is still wanted. A resolve there can't be
 * stopped, so a service lost while its resolve runs, or after it was passed over as stuck, is dropped when its answer
 * comes; found again, it is wanted again. Not thread-safe: its owner calls it under its own lock, and keeps the timers.
 */
internal class ResolveQueue<S>(private val nameOf: (S) -> String) {
    private val waiting = ArrayDeque<S>()

    /** The service resolving now; null while none is. */
    var resolving: String? = null
        private set

    /** Services whose resolve started and hasn't answered: the one resolving, and any passed over. */
    private val pending = HashSet<String>()

    /** Of those, the ones lost since: their answers are dropped. */
    private val gone = HashSet<String>()

    /** Services whose resolve met another still running: they wait to be tried again, unless lost meanwhile. */
    private val again = HashSet<String>()

    /** [service] found: it waits its turn, once; returns the service to resolve now, if none is resolving. */
    fun found(service: S): S? {
        val name = nameOf(service)
        gone -= name
        if (waiting.none { nameOf(it) == name }) waiting.addLast(service)
        return next()
    }

    /** [name] lost: it waits no more, an answer still to come for it is dropped, and it isn't tried again. */
    fun lost(name: String) {
        waiting.removeAll { nameOf(it) == name }
        again -= name
        if (name in pending) gone += name
    }

    /** [name]'s resolve answered: whether its answer is still wanted, and the service to resolve next, if any. */
    fun answered(name: String): Pair<Boolean, S?> {
        pending -= name
        val wanted = !gone.remove(name)
        return wanted to finished(name)
    }

    /**
     * [name]'s resolve failed: the service to resolve next, if any. [busy]: another resolve still runs (one passed over),
     * so none starts now, and this one is to be tried again ([retry]) a moment later.
     */
    fun failed(name: String, busy: Boolean): S? {
        pending -= name
        gone -= name
        if (!busy) return finished(name)
        again += name
        if (resolving == name) resolving = null
        return null
    }

    /** [service]'s turn again after [failed] said busy, unless it was lost since; returns the service to resolve now. */
    fun retry(service: S): S? {
        if (!again.remove(nameOf(service))) return next()
        return found(service)
    }

    /** [name]'s resolve hasn't answered in its time: the next one's turn; its answer, if it comes, is taken as any. */
    fun passOver(name: String): S? = finished(name)

    private fun finished(name: String): S? {
        if (resolving != name) return null
        resolving = null
        return next()
    }

    private fun next(): S? {
        if (resolving != null) return null
        val service = waiting.removeFirstOrNull() ?: return null
        val name = nameOf(service)
        resolving = name
        pending += name
        return service
    }
}
