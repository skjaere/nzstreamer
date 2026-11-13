package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.LinkStore
import io.skjaere.usenet.data.PairedTv
import io.skjaere.usenet.data.RemoteProgress
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Final
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/** What the phone's side of Finals runs on: the app's ([of]), or fakes, so it runs in a JVM test. */
class PhoneFinalsDeps(
    /** What the TVs say on their links. */
    val heard: Flow<TvHeard>,
    /** Sends a message that isn't a command on the TV's link, without waiting: the FinalAck. Whether it went. */
    val send: (KeyId, LinkMessage) -> Boolean,
    /** The TV kept under that key: the name this phone keeps it by, and the sessions this phone started there (L33). */
    val tv: suspend (KeyId) -> PairedTv?,
    /** Saves a Final as this phone's own progress ([LibraryRepository.recordRemoteProgress]); whether it wrote. */
    val record: suspend (Final) -> Boolean,
    /** A Final was saved, from the TV kept under that key and name: what the phone says of it ([TvPlaying.stopped]). */
    val applied: (KeyId, String, Final) -> Unit,
    /** Whether one of the app's activities is in front (L31). */
    val inFront: Flow<Boolean> = flowOf(false),
    /** The paired TVs, as link.json keeps them, with the sessions this phone started on each. */
    val tvs: Flow<List<PairedTv>> = flowOf(emptyList()),
    /** Holds a TV's link while the app is in front (L36). */
    val hold: (KeyId) -> AutoCloseable = { AutoCloseable {} },
    /** Wall-clock milliseconds, the clock a session's start was kept by. */
    val now: () -> Long = System::currentTimeMillis,
    val timings: () -> PlayOnTimings = ::playOnTimings,
) {
    companion object {
        fun of(c: AppContainer) = PhoneFinalsDeps(
            heard = c.tvLinks.heard,
            send = c.tvLinks::send,
            tv = { key -> c.data.link.tvs.first().firstOrNull { it.key == key } },
            record = c.library::recordRemoteProgress,
            applied = c.tvPlaying::stopped,
            inFront = c.inFront.value,
            tvs = c.data.link.tvs,
            hold = c.tvLinks::holdTv,
        )
    }
}

/**
 * The phone's side of where its plays on TVs stopped (W5, L11, L33): each Final a TV sends, on whatever link it is up
 * then, is saved as this phone's own progress, so Resume, the episode's row, Up next and the collection agree. Only for
 * one of this phone's own sessions on that TV and the same show or movie, its watch key worked out again from its ids
 * ([RemoteProgress.key]); any other is dropped. The TV is told after the write that it may let the Final go (FinalAck),
 * and for one dropped too; a write that fails isn't acked, so the TV sends it again on a later link. One sent twice (at
 * once, and again as a link comes up) is saved and said once. Logged: what came of each, never a title (L13).
 */
class PhoneFinals(private val scope: CoroutineScope, private val deps: PhoneFinalsDeps) {
    private val started = AtomicBoolean()

    /** The Finals saved lately, by id: one sent again is acked again, and nothing more. */
    private val saved = LinkedHashSet<String>()

    /**
     * Hears the TVs from now, for the app's life. As the app comes to the front, the TVs this phone started plays on in
     * the last [LinkStore.SESSION_DAYS] days are linked for [PlayOnTimings.sessionLinkMillis] (L36: a TV with a session):
     * a TV sends what it owes on each link up, so after a restart nothing waits for the Play on sheet or Settings › TVs,
     * and a play there still in one of this phone's sessions is followed again (TvPlaying). Then they are let go: the TV
     * chosen, the sheet and a play followed hold their own.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Listening before this returns: what a TV says is for whoever listens then, and nothing is kept for later.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { deps.heard.collect { heard -> (heard.message as? Final)?.let { apply(heard.tv, it) } } }
        scope.launch {
            deps.inFront.distinctUntilChanged().collectLatest { front ->
                if (!front) return@collectLatest
                val since = deps.now() - SESSION_MS
                val tvs = try {
                    deps.tvs.first()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Play on TV: couldn't read the TVs this phone played on", e)
                    emptyList()
                }
                val held = tvs.filter { tv -> tv.sessions.any { it.startedAt >= since } }.mapNotNull { it.key }.map(deps.hold)
                try {
                    delay(deps.timings().sessionLinkMillis)
                } finally {
                    held.forEach { it.close() }
                }
            }
        }
    }

    private suspend fun apply(tv: KeyId, final: Final) {
        val row = deps.tv(tv)
        if (row == null || RemoteProgress.key(final, row.sessions) == null) {
            log.info("Play on TV: a TV's Final isn't of this phone's plays there; dropped")
            ack(tv, final)
            return
        }
        if (final.id in saved) return ack(tv, final)
        val wrote = try {
            deps.record(final)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not acked: the TV keeps it, and sends it again on a later link.
            log.warn("Play on TV: couldn't save where a play on a TV stopped", e)
            return
        }
        saved += final.id
        while (saved.size > SAVED_KEPT) saved.remove(saved.first())
        ack(tv, final)
        log.info("Play on TV: where a play on a TV stopped is saved here{}", if (wrote) "" else " (a newer place was kept)")
        if (wrote) deps.applied(tv, row.name, final)
    }

    private fun ack(tv: KeyId, final: Final) {
        if (!deps.send(tv, LinkMessage.FinalAck(listOf(final.id)))) log.info("Play on TV: a TV's Final wasn't acked, its link gone; it comes again")
    }

    private companion object {
        val log = LoggerFactory.getLogger(PhoneFinals::class.java)

        /** Finals remembered as saved: a TV sends one twice only in the moment its link comes up. */
        const val SAVED_KEPT = 64

        /** How long a session counts as one a TV may still owe for: as long as the phone keeps it, and the TV its Finals (L33). */
        const val SESSION_MS = LinkStore.SESSION_DAYS * 24 * 60 * 60 * 1_000L
    }
}
