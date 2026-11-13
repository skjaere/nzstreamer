package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.LinkStore
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.link.FinalKind
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage.Final
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * W5, L33: where a play a phone started stopped on the TV, as a [Final] the phone applies to its own progress. One is made
 * each time a phone's play's player saves as it ends ([ended]: as it closes, and at the end of the file), in the phone's
 * session, watched by the 90 % rule, and owed to that phone in link.json ([LinkStore.queueFinal]): sent at once on its
 * link, else on its next one ([linked], a few at a time) with its age by then, until its FinalAck lets it go ([acked]).
 * The TV's own plays make none, and a phone no longer paired is owed nothing; Remove and Unpair take what it was owed
 * (L30). The TV's own progress is saved as it always is.
 */
class TvFinals(
    private val scope: CoroutineScope,
    private val store: LinkStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val context: CoroutineContext = Dispatchers.Default,
) {
    /** The paired phones' links up, by phone, the newest last: a Final goes out on the newest. */
    private val links = HashMap<KeyId, MutableList<PhoneLink>>()

    /** [launch]'s player saved where it ended, [positionMs] of [durationMs]: a phone's play is owed to that phone. */
    fun ended(launch: PlayerLaunch, positionMs: Long, durationMs: Long) {
        val phone = launch.remote?.phone?.let(KeyId::parse) ?: return
        val made = final(launch, positionMs, durationMs, newId()) ?: return
        scope.launch(context) {
            saveOrLog("keep where a phone's play stopped") {
                store.queueFinal(phone, made)
                // Sent at once, as owed now (none for a phone no longer paired), on its newest link.
                store.owed(phone).filter { it.id == made.id }.forEach { owed -> newest(phone)?.send(owed) }
            }
        }
    }

    /**
     * [link] is up: what its phone is owed goes out on it, each with its age as it goes, oldest first, [PER_TURN] at a
     * time every [TURN_MS] while it stays open. All at once, 50 could pass the frames a phone takes in a second (L24),
     * and it drops those past them, the newest; at this pace the TV's statuses and Pongs still fit beside them.
     */
    fun linked(link: PhoneLink) {
        synchronized(links) { links.getOrPut(link.phone) { mutableListOf() } += link }
        scope.launch(context) {
            saveOrLog("send a phone what it is owed") {
                val sent = HashSet<String>()
                while (open(link)) {
                    val turn = store.owed(link.phone).filter { it.id !in sent }.take(PER_TURN)
                    if (turn.isEmpty()) break
                    turn.forEach { owed ->
                        sent += owed.id
                        link.send(owed)
                    }
                    delay(TURN_MS)
                }
            }
        }
    }

    fun closed(link: PhoneLink) {
        synchronized(links) {
            links[link.phone]?.let { open ->
                open -= link
                if (open.isEmpty()) links -= link.phone
            }
        }
    }

    /** [phone]'s FinalAck: the Finals [ids] names are owed it no more, applied or dropped; another phone's stay. */
    fun acked(phone: KeyId, ids: List<String>) {
        scope.launch(context) { saveOrLog("let go of the Finals a phone acked") { store.ackFinals(phone, ids) } }
    }

    private fun newest(phone: KeyId): PhoneLink? = synchronized(links) { links[phone]?.lastOrNull() }

    private fun open(link: PhoneLink): Boolean = synchronized(links) { links[link.phone]?.contains(link) == true }

    companion object {
        /** Finals sent on a link coming up at a time, and how often: 10 a second, half a phone's frames (L24). */
        const val PER_TURN = 5
        const val TURN_MS = 500L

        fun of(c: AppContainer) = TvFinals(c.appScope, c.data.link)

        /**
         * [launch]'s end as its phone is told it: [positionMs] of [durationMs], watched past [LibraryRepository.WATCHED_FRACTION]
         * of it, and what it was (an episode or a movie, by their ids) for the phone to work its own watch key out again.
         * Null for the TV's own play, a play with no length, or one played without TMDB.
         */
        fun final(launch: PlayerLaunch, positionMs: Long, durationMs: Long, id: String): Final? {
            val remote = launch.remote ?: return null
            if (durationMs <= 0) return null
            val watch = launch.watch
            val kind = when (watch.kind) {
                WatchKind.Episode -> FinalKind.Episode
                WatchKind.Movie -> FinalKind.Movie
                WatchKind.Release -> return null
            }
            return Final(
                id, remote.session, launch.watchKey, kind, watch.title, showTmdbId = watch.showTmdbId, season = watch.season, episode = watch.episode,
                movieTmdbId = watch.movieTmdbId, positionMs = positionMs, durationMs = durationMs,
                watched = positionMs >= durationMs * LibraryRepository.WATCHED_FRACTION, ageMs = 0,
            )
        }
    }
}
