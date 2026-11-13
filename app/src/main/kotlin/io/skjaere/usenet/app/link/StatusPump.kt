package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.play.Launching
import io.skjaere.usenet.app.play.NowPlaying
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.RemotePlay
import io.skjaere.usenet.app.play.SheetNow
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.UsenetSource
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkLimits
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.Playback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** A paired phone's link as the TV tells it of its plays (L33, L34): whose it is, and a way to send on it, never waiting. */
interface PhoneLink {
    val phone: KeyId

    fun send(message: LinkMessage)
}

/**
 * What hears of paired phones' links besides the TV's listener ([TvLinkHost]): what the TV says of its plays (L34) and
 * where a phone's plays stopped (L33). Called on the links' own threads: post what takes time, and don't block.
 */
interface PhoneLinks {
    /** [link] is up. */
    fun linked(link: PhoneLink)

    /** [link] closed: nothing more goes out on it. */
    fun closed(link: PhoneLink)

    /** The play now, as [phone]'s hello names it (L25): its session, [Hello.Session.mine] when [phone]'s; null for none or the TV's own. */
    fun session(phone: KeyId): Hello.Session?

    /** [phone]'s FinalAck: the Finals [ids] names are owed it no more (L33). */
    fun acked(phone: KeyId, ids: List<String>)

    companion object {
        /** Nothing hears of them: a listener with no plays to tell of (the JVM tests'). */
        val None: PhoneLinks = object : PhoneLinks {
            override fun linked(link: PhoneLink) = Unit

            override fun closed(link: PhoneLink) = Unit

            override fun session(phone: KeyId): Hello.Session? = null

            override fun acked(phone: KeyId, ids: List<String>) = Unit
        }
    }
}

/** What the TV's status is made from: the app's ([of]), or flows a test sets, on its own clock. */
class StatusPumpDeps(
    /** What the play sheet shows, and the session of the play it shows it for. */
    val sheet: Flow<SheetNow>,
    /** What the player in front says it plays, each second while started. */
    val player: Flow<NowPlaying?>,
    /** A play whose launch is on its way to a player. */
    val launching: Flow<Launching?>,
    val statusPerSecond: Int = LinkLimits().statusPerSecond,
    /** Monotonic milliseconds, the clock the pace is kept by. */
    val clock: () -> Long,
    /**
     * Where the pump runs: one thread at a time, so what the flows say reaches it in the order it was said (a launch on
     * its way before the sheet that sent it closes).
     */
    val context: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
    /** The rows the sheet lists while a play searches (L223): set as each play starts, before its search shows. */
    val searchSteps: () -> List<PipelineStep> = { UsenetSource.STEPS },
) {
    companion object {
        fun of(c: AppContainer) = StatusPumpDeps(
            sheet = c.playback.sheet, player = c.playerInFront.now, launching = c.playerInFront.launching, clock = android.os.SystemClock::elapsedRealtime,
            searchSteps = { c.playback.searchSteps.value },
        )
    }
}

/**
 * L34: what the TV says of the play it shows, to its paired phones' links. The play is the sheet's while it shows one
 * (starting, or why it didn't), else the player's in front, else one whose launch is on its way to a player (buffering:
 * the sheet has closed, and its player is yet to start). The phone whose session it is hears it titled: the titles, the
 * watch key, where it is, its length, and the sheet mirrored while it starts ([SheetMirror]); every other paired phone
 * hears its session and state alone; and of the TV's own plays no phone hears anything. A session's next play (the next
 * episode, after one that played) says it [Status.continued]. A session that ends is told so before what comes next:
 * [Playback.Replaced] when another play takes its place, the TV's own or another phone's, [Playback.Stopped] when nothing
 * does (the player left, the sheet was closed or cancelled) for [STOP_GRACE_MS]: a player hands over a moment before the
 * next play's sheet shows anything, and the session's own play showing again in that moment goes on as it was.
 *
 * Statuses go out as what they say changes: each second while a play plays, as the player says where it is; to a phone
 * that isn't the owner, only as the state changes. A link takes at most [StatusPumpDeps.statusPerSecond] a second, a
 * little under, so frames a network bunches still reach a phone within its own count (L24): changes in between are
 * folded into the latest of the same session, which always goes out. A phone linking hears the play now at once.
 */
class StatusPump(scope: CoroutineScope, private val deps: StatusPumpDeps) {
    private sealed interface Event {
        class Shows(val sheet: SheetNow, val player: NowPlaying?, val launching: Launching?) : Event

        class Linked(val link: PhoneLink) : Event

        class Closed(val link: PhoneLink) : Event
    }

    /** What the TV shows: the play's session (null: the TV's own), and what its owner is told of it. */
    private class Showing(val remote: RemotePlay?, val titled: Status?)

    /** The session that showed last, as nothing shows now: told it stopped [at], unless a play shows first. */
    private class Ending(val showing: Showing, val at: Long)

    /** One link's pace: what it was last sent and when, and what waits for its turn, oldest first. */
    private class Pace {
        var last: Status? = null
        var sentAt: Long? = null
        val waiting = ArrayDeque<Status>()
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val interval = 1_000L / deps.statusPerSecond + PACE_SLACK_MS

    /** The play's session now, as a hello names it; read on the links' threads. */
    @Volatile private var now: RemotePlay? = null

    // The rest is the pump's own coroutine's.
    private val links = LinkedHashMap<PhoneLink, Pace>()
    private var showing: Showing? = null
    private var ending: Ending? = null

    /** Sessions whose play reached a player, the newest last: what plays next in one of them continues it. */
    private val played = LinkedHashSet<String>()

    init {
        scope.launch(deps.context) { combine(deps.sheet, deps.player, deps.launching, Event::Shows).collect { events.send(it) } }
        scope.launch(deps.context) { pump() }
    }

    /** [link] is up: it hears the play now, and what comes. */
    fun linked(link: PhoneLink) {
        events.trySend(Event.Linked(link))
    }

    fun closed(link: PhoneLink) {
        events.trySend(Event.Closed(link))
    }

    fun session(phone: KeyId): Hello.Session? = now?.let { Hello.Session(it.session, mine = it.phone == phone.hex) }

    private suspend fun pump() {
        while (true) {
            val sends = links.values.mapNotNull { pace -> if (pace.waiting.isEmpty()) null else (pace.sentAt ?: Long.MIN_VALUE / 2) + interval }
            val due = (sends + listOfNotNull(ending?.at)).minOrNull()
            val event = if (due == null) events.receive() else events.receiveWithin((due - deps.clock()).coerceAtLeast(0))
            when (event) {
                is Event.Shows -> show(showing(event))
                is Event.Linked -> links.getOrPut(event.link) { Pace() }.let { pace -> showing?.let { queue(event.link, pace, it) } }
                is Event.Closed -> links.remove(event.link)
                null -> Unit
            }
            ending?.takeIf { deps.clock() >= it.at }?.let {
                ending = null
                end(it.showing, Playback.Stopped)
            }
            flush()
        }
    }

    /** What the TV shows, from what the sheet, the player and a launch on its way say. */
    private fun showing(e: Event.Shows): Showing? {
        val sheet = e.sheet
        val player = e.player
        val launching = e.launching
        return when {
            sheet.state != PlayState.Idle -> Showing(sheet.remote, sheet.remote?.let { starting(it, sheet.state) })
            player != null -> Showing(player.remote, player.remote?.let { r -> playing(r, player) })
            launching != null -> Showing(launching.remote, launching.remote?.let(::buffering))
            else -> null
        }
    }

    private fun starting(remote: RemotePlay, state: PlayState): Status {
        val (title, subtitle) = titles(state)
        return Status(
            remote.session, Playback.Starting, mine = true, continued = remote.session in played, title = title, subtitle = subtitle,
            sheet = SheetMirror.of(state, deps.searchSteps()),
        )
    }

    private fun playing(remote: RemotePlay, now: NowPlaying): Status {
        played -= remote.session
        played += remote.session
        while (played.size > PLAYED_KEPT) played.remove(played.first())
        return Status(
            remote.session, now.state, mine = true, title = now.title, subtitle = now.subtitle, watchKey = now.watchKey, positionMs = now.positionMs,
            durationMs = now.durationMs, failure = now.failure,
        )
    }

    /** Its launch on its way: titled as it last was, its player yet to start. */
    private fun buffering(remote: RemotePlay): Status {
        val last = showing?.titled?.takeIf { it.session == remote.session }
        return Status(remote.session, Playback.Buffering, mine = true, continued = remote.session in played, title = last?.title, subtitle = last?.subtitle)
    }

    /**
     * The TV shows [next] now. Nothing showing starts the last session's ending; a play showing replaces the last session
     * unless it is that session's own, and is then told what it is.
     */
    private fun show(next: Showing?) {
        val before = showing
        showing = next
        now = next?.remote
        if (next == null) {
            if (before?.remote != null) ending = Ending(before, deps.clock() + STOP_GRACE_MS)
            return
        }
        val last = before?.takeIf { it.remote != null } ?: ending?.showing
        ending = null
        if (last != null && last.remote?.session != next.remote?.session) end(last, Playback.Replaced)
        if (next.remote != null) links.forEach { (link, pace) -> queue(link, pace, next) }
    }

    /** [ended]'s session is over, as [state] says: each link is told so, its owner as it was last told, titled. */
    private fun end(ended: Showing, state: Playback) {
        val remote = ended.remote ?: return
        val titled = ended.titled?.copy(state = state, continued = false, failure = null, sheet = null) ?: Status(remote.session, state, mine = true)
        links.forEach { (link, pace) -> queue(link, pace, Showing(remote, titled)) }
    }

    /** What [link] is told of [what]: titled for its owner, the state alone for another phone; nothing of the TV's own. */
    private fun queue(link: PhoneLink, pace: Pace, what: Showing) {
        val remote = what.remote ?: return
        val titled = what.titled ?: return
        val status = if (link.phone.hex == remote.phone) titled else Status(titled.session, titled.state)
        val waiting = pace.waiting
        // A change of the same session takes the place of one still waiting: the latest goes out. Another session's waits
        // its turn, after the end of the one before.
        if (waiting.lastOrNull()?.session == status.session) waiting.removeLast()
        if (waiting.isEmpty() && status == pace.last) return
        waiting.addLast(status)
    }

    /** Each link's next status, once its pace allows. */
    private fun flush() {
        val at = deps.clock()
        links.forEach { (link, pace) ->
            val next = pace.waiting.firstOrNull() ?: return@forEach
            val sentAt = pace.sentAt
            if (sentAt != null && at - sentAt < interval) return@forEach
            pace.waiting.removeFirst()
            pace.last = next
            pace.sentAt = at
            link.send(next)
        }
    }

    companion object {
        /**
         * How long nothing shows before a session is told it stopped: a player hands over a moment before the next play's
         * sheet shows anything (a movie's start says nothing before its first release).
         */
        const val STOP_GRACE_MS = 1_500L

        /** A little more than the limit's own spacing, so frames a network bunches still count within it at the phone. */
        private const val PACE_SLACK_MS = 50L

        /** Sessions remembered as having played. */
        private const val PLAYED_KEPT = 32

        /** A sheet's titles. */
        private fun titles(state: PlayState): Pair<String?, String?> = when (state) {
            PlayState.Idle -> null to null
            is PlayState.Searching -> state.title to state.subtitle
            is PlayState.Running -> state.title to state.subtitle
            is PlayState.ChoosingFile -> state.title to state.subtitle
            is PlayState.Prechecking -> state.title to state.subtitle
            is PlayState.NoRelease -> state.title to state.subtitle
            is PlayState.Failed -> state.title to state.subtitle
        }
    }
}
