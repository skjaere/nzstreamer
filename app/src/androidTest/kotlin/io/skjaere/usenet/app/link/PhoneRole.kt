package io.skjaere.usenet.app.link

import android.os.SystemClock
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.data.LinkStore
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkKey
import io.skjaere.usenet.link.LinkMessage.Final
import io.skjaere.usenet.link.LinkMessage.FinalAck
import io.skjaere.usenet.link.LinkMessage.Play
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone's side of Play on TV in the test's process (L14), as the app makes it, in front: its links to the TVs kept in
 * [store], under its own [key] (so it never pairs with this device's own), the start a Play makes, the bar that follows it
 * and its Finals. Each Final it saves goes through [record] (the app's recordRemoteProgress, or nothing) and is kept in
 * [recorded], the recorder seam (L14): where both roles share one database, a TV role's own save writes the same row.
 * [finder] finds TVs' records; none, by default, so a TV is reached at the address it is kept with. The names invented.
 */
class PhoneRole(
    val key: LinkKey = softwareKey(),
    val store: LinkStore = memoryLinkStore(),
    finder: TvFinder = TvFinder { flowOf(null) },
    name: String = "Test phone",
    private val record: suspend (Final) -> Boolean = { true },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val links = TvLinks(scope, TvLinksDeps(store = store, identity = { key }, finder = finder, inFront = MutableStateFlow(true), name = name, app = "0.1.0"))
    private val linked: (KeyId) -> Flow<Boolean> = { tv -> links.states.map { it[tv]?.linked == true }.distinctUntilChanged() }

    /** The names the TVs played on are kept by here, as the app keeps a TV's. */
    private val names = ConcurrentHashMap<KeyId, String>()

    /** The play followed on a TV: the bar's. */
    val playing = TvPlaying(scope, TvPlayingDeps(links::command, links.heard, linked, links::holdTv, MutableStateFlow(true), SystemClock::elapsedRealtime))

    /** A Play's start, its sheet mirrored; the bar follows it once the TV takes it. */
    val start = TvStart(
        scope,
        TvStartDeps(
            command = links::command, heard = links.heard, linked = linked, hold = links::holdTv,
            name = { tv -> names[tv] ?: "the TV" },
            keep = store::addSession, startingHere = emptyFlow(), dismissHere = {}, now = System::currentTimeMillis, clock = SystemClock::elapsedRealtime,
            took = { now, session -> playing.follow(now.tv, now.tvName, session, now.title, now.subtitle) },
        ),
    )

    /** The Finals saved, in order, and the ids this phone acked. */
    val recorded = CopyOnWriteArrayList<Final>()
    val acked = CopyOnWriteArrayList<String>()

    private val finals = PhoneFinals(
        scope,
        PhoneFinalsDeps(
            heard = links.heard, tv = { tv -> store.tvs.first().firstOrNull { it.key == tv } },
            send = { tv, message -> links.send(tv, message).also { sent -> if (sent && message is FinalAck) acked += message.ids } },
            record = { final -> recorded += final; record(final) }, applied = playing::stopped,
        ),
    )

    init {
        links.start()
        finals.start()
    }

    /** The TVs chosen: their links held, as Play on holds the chosen TV's (L36). */
    private val chosen = CopyOnWriteArrayList<AutoCloseable>()

    /** [tv] chosen, as on the Play on sheet: its link is held from now. Its state, once its link is up. */
    suspend fun choose(tv: KeyId): TvNow {
        chosen += links.holdTv(tv)
        return withTimeout(30_000) { links.states.first { it[tv]?.linked == true } }.getValue(tv)
    }

    /** Play [film] on the TV [tv] (kept as [tvName]), as the Movie page's Play does with that TV chosen. */
    fun play(tv: KeyId, tvName: String, film: Play.Movie) {
        names[tv] = tvName
        onMainThread { start.start(tv, film.title, null) { TvAsk(movie = film) } }
    }

    override fun close() {
        chosen.forEach { it.close() }
        playing.close()
        links.close()
        scope.cancel()
    }
}
