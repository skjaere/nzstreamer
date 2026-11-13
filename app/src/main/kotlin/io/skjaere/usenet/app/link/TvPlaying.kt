package io.skjaere.usenet.app.link

import android.os.SystemClock
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.app.collection.mmss
import io.skjaere.usenet.data.PairedTv
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.Playback
import io.skjaere.usenet.link.SheetKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A play this phone started on the TV [tv] (named [tvName] as this phone keeps it), in [session], as the bar follows it:
 * its titles and watch key as the TV last said them (L34), its [state], where it was ([positionMs], heard at [heardAt] on
 * the phone's monotonic clock) and its length. [linked]: the TV's link is up; [cantReach]: it has been down for
 * [PlayOnTimings.cantReachMillis] in front; [hidden]: × put the bar away. [played]: its player had it, so the bar shows
 * (the start's sheet has it before); [failedStart]: the TV's sheet says its start failed. [art]: this phone's own picture
 * of it, none until it is looked up. It prints without its titles.
 */
data class TvPlayNow(
    val tv: KeyId,
    val tvName: String,
    val session: String,
    override val title: String,
    override val subtitle: String?,
    override val watchKey: String?,
    val state: Playback,
    override val positionMs: Long,
    override val durationMs: Long?,
    override val heardAt: Long,
    val linked: Boolean = true,
    val cantReach: Boolean = false,
    val hidden: Boolean = false,
    val played: Boolean = false,
    val failedStart: Boolean = false,
    override val art: TvArt? = null,
) : RemoteNow {
    override val kind: RemoteKind get() = RemoteKind.Tv

    override val device: String get() = tvName

    override val shows: RemoteBar
        get() = when {
            !linked -> if (cantReach) RemoteBar.CantReach else RemoteBar.Lost
            failedStart || state == Playback.Failed -> RemoteBar.Stopped
            state == Playback.Starting -> RemoteBar.NextEpisode
            state == Playback.Paused -> RemoteBar.Paused
            state == Playback.Buffering -> RemoteBar.Buffering
            else -> RemoteBar.Playing
        }

    /** The bar, once the TV's player has the play and while it isn't put away; null for none. */
    override val bar: RemoteBar? get() = shows.takeIf { played && !hidden }

    override val live: Boolean get() = state == Playback.Playing || state == Playback.Paused || state == Playback.Buffering

    override val moving: Boolean get() = state == Playback.Playing && linked

    override fun toString() = "TvPlayNow(${tv.short}, $state, played $played, linked $linked)"
}

/** The picture the remote shows of a play: a TMDB image [path], an episode's still or a film's ([poster]) poster. */
data class TvArt(val path: String, val poster: Boolean = false)

/** What following a play on a TV runs on: the app's ([of]), or fakes, so it runs in a JVM test. */
class TvPlayingDeps(
    /** Sends a command on the TV's link with a number of its own; null when the link isn't up. */
    val command: (KeyId, (Int) -> LinkMessage) -> Int?,
    /** What the TVs say on their links. */
    val heard: Flow<TvHeard>,
    /** Whether the TV's link is up. */
    val linked: (KeyId) -> Flow<Boolean>,
    /** Holds the TV's link while the phone follows its play (L36). */
    val hold: (KeyId) -> AutoCloseable,
    /** Whether one of the app's activities is in front (L31). */
    val inFront: Flow<Boolean>,
    /** Monotonic milliseconds, for where the play is between the TV's updates and how long the link has been lost. */
    val clock: () -> Long,
    val timings: () -> PlayOnTimings = ::playOnTimings,
    /** The picture this phone has for a watch key (L34: no art crosses); null for none. */
    val art: suspend (String) -> TvArt? = { null },
    /** Whether the TV is gone for this phone: forgotten here, or it removed this phone or was set up again (L30). */
    val gone: (KeyId) -> Flow<Boolean> = { flowOf(false) },
    /** The TV kept under that key: the name this phone keeps it by, and the sessions this phone started there (L33). */
    val tv: suspend (KeyId) -> PairedTv? = { null },
) {
    companion object {
        fun of(c: AppContainer) = TvPlayingDeps(
            command = c.tvLinks::command,
            heard = c.tvLinks.heard,
            linked = { key -> c.tvLinks.states.map { it[key]?.linked == true }.distinctUntilChanged() },
            hold = c.tvLinks::holdTv,
            inFront = c.inFront.value,
            clock = SystemClock::elapsedRealtime,
            art = { key -> art(c, key) },
            gone = { key ->
                combine(c.data.link.tvs.map { tvs -> tvs.none { it.key == key } }, c.tvLinks.states.map { it[key]?.state }) { forgotten, state ->
                    forgotten || state == TvState.RemovedHere || state == TvState.SetUpAgain
                }.distinctUntilChanged()
            },
            tv = { key -> c.data.link.tvs.first().firstOrNull { it.key == key } },
        )

        private val log = LoggerFactory.getLogger(TvPlaying::class.java)

        /**
         * What this phone has for [key] (L34), with its own TMDB key and the artwork setting: an episode's still, else its
         * show's poster; a film's poster. Null for none, the setting off, or a lookup that failed. A Cast device is sent
         * the same (L62).
         */
        internal suspend fun art(c: AppContainer, key: String): TvArt? {
            if (!c.settings.metadata.first().showArtwork) return null
            return try {
                EPISODE_KEY.matchEntire(key)?.destructured?.let { (show, season, episode) ->
                    val still = TmdbLookup.season(c, show.toInt(), season.toInt()).episodes.firstOrNull { it.episode == episode.toInt() }?.stillPath
                    still?.let { TvArt(it) } ?: TmdbLookup.show(c, show.toInt()).posterPath?.let { TvArt(it, poster = true) }
                } ?: MOVIE_KEY.matchEntire(key)?.destructured?.let { (movie) -> TmdbLookup.movie(c, movie.toInt()).posterPath?.let { TvArt(it, poster = true) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("Play on TV: no picture for the remote ({})", e::class.simpleName)
                null
            }
        }
    }
}

/**
 * A play this phone started on a TV, as the Playing on bar and the Show page follow it (L38, W14, W15; PtPhoneBar,
 * PtPhoneLost): one session at a time, from the moment the TV took the play ([follow]: its Ack), or, with nothing
 * followed, from the moment a TV says it plays one of this phone's sessions there ([start]: after a restart), so a play
 * on another TV moves the bar there and the first plays on, unfollowed. The TV's link is held meanwhile (L36). The bar
 * shows once the TV's player has the play, and says what the TV says of it: playing, paused, buffering, the next
 * episode starting, or Playback stopped; its position moves on between the TV's updates while it plays ([position]).
 * Its buttons send Pause, Resume and Stop for the session, and the remote's seeks, showing where it sent the TV until
 * the TV says. A lost link reads lost; after [PlayOnTimings.cantReachMillis] in front, "Can't reach <TV>" with × to put
 * it away ([dismiss]); a new link brings it back as the TV says its play. It ends on the TV's stopped status, another
 * play in its place, a TV that names another play (or none) as it links again, or a TV gone for this phone: forgotten
 * here, or removing this phone or set up again there (L30).
 *
 * It also says where a play this phone started on a TV stopped ([said], item 27, PtPhoneStopped, PtPhoneLost 4), for
 * [PlayOnTimings.noticeMillis], once the Final is saved ([stopped]): "Stopped on Living room TV at 32:10", a late one
 * "Living room TV stopped at 41:02", "Watched on Living room TV". For the play the bar follows, the line waits for the
 * session's next word: none as the bar follows on to the next episode, else as it stops (or after [LINE_WAIT_MS] with
 * nothing more). A start the TV stopped before its player, with no failure to show, is "Cancelled on Living room TV".
 * Logged: what came of it, never a title (L13).
 */
class TvPlaying(private val scope: CoroutineScope, private val deps: TvPlayingDeps) : RemotePlaying {
    private val _now = MutableStateFlow<TvPlayNow?>(null)

    /** The play followed now; null for none. */
    override val now: StateFlow<TvPlayNow?> = _now.asStateFlow()

    /** The session followed: whose the state is while it is [run], and where its loop hears a Final saved ([events]). */
    private class Run(val tv: KeyId, val session: String) {
        var job: Job? = null
        @Volatile var events: Channel<Event>? = null
    }

    @Volatile private var run: Run? = null

    private val started = AtomicBoolean()

    /**
     * Hears the TVs from now, for the app's life: with nothing followed, a play the TV says it plays for this phone (its
     * titled status, which a link coming up hears at once, L34) in one of the sessions this phone started there (L33) is
     * followed again, after the process started afresh or an Ack lost with its link.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Listening before this returns: what a TV says is for whoever listens then.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { deps.heard.collect { takeUp(it) } }
    }

    private suspend fun takeUp(heard: TvHeard) {
        val status = heard.message as? Status ?: return
        val title = status.title
        if (run != null || !status.mine || title == null || status.state !in LIVE) return
        val row = try {
            deps.tv(heard.tv)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Play on TV: couldn't read the sessions this phone started on a TV", e)
            null
        }
        if (row == null || row.sessions.none { it.id == status.session } || run != null) return
        log.info("Play on TV: following again a play this phone started on a TV")
        follow(heard.tv, row.name, status.session, title, status.subtitle, heard = status)
    }

    /**
     * Follows [session] on [tv], the play the TV took for this phone, in place of any followed before; [heard], what the
     * TV said of it last, if it said anything yet.
     */
    fun follow(tv: KeyId, tvName: String, session: String, title: String, subtitle: String?, heard: Status? = null) {
        letGo()
        val mine = Run(tv, session)
        run = mine
        _now.value = TvPlayNow(tv, tvName, session, title, subtitle, watchKey = null, state = Playback.Starting, positionMs = 0, durationMs = null, heardAt = deps.clock())
        log.info("Play on TV: following a play on a TV")
        // Undispatched, so its listening starts before this returns, and the TV's next word is heard.
        mine.job = scope.launch(start = CoroutineStart.UNDISPATCHED) { follow(mine) }
        heard?.let { mine.events?.trySend(Event.Heard(it)) }
    }

    private val _said = MutableStateFlow<RemoteSaid?>(null)
    private val lines = AtomicLong()

    /** The line said now, for a moment; null for none. */
    override val said: StateFlow<RemoteSaid?> = _said.asStateFlow()

    /**
     * [final], from the TV [tv] kept as [tvName], is saved here: where it stopped is said, at once, or for the session the
     * bar follows once the TV's next word says the session doesn't go on to its next episode.
     */
    fun stopped(tv: KeyId, tvName: String, final: LinkMessage.Final) {
        val line = when {
            final.watched -> watchedOn(tvName)
            final.ageMs >= LATE_MS -> stoppedAt(tvName, final.positionMs)
            else -> stoppedOnAt(tvName, final.positionMs)
        }
        val mine = run
        val heard = mine?.takeIf { it.tv == tv && it.session == final.session }?.events?.trySend(Event.Saved(line, final.watchKey))?.isSuccess == true
        if (!heard) say(line)
    }

    /** Says [text] for the notice's time, in place of what was said. */
    private fun say(text: String) {
        val line = RemoteSaid(text, lines.incrementAndGet(), RemoteKind.Tv)
        _said.value = line
        scope.launch {
            delay(deps.timings().noticeMillis)
            _said.compareAndSet(line, null)
        }
    }

    /** Where [now]'s play is at this moment, moved on since the TV said it while it plays. */
    override fun position(now: RemoteNow): Long = now.positionAt(deps.clock())

    override fun pause() {
        send { seq, session -> LinkMessage.Pause(seq, session) }
    }

    override fun resume() {
        send { seq, session -> LinkMessage.Resume(seq, session) }
    }

    /**
     * Stop: the TV leaves its player; the bar goes on its stopped status. While the session's next play starts there is no
     * player to leave (the TV would say not-current): the start is cancelled.
     */
    override fun stop() {
        val starting = _now.value?.state == Playback.Starting
        send { seq, session -> if (starting) LinkMessage.Cancel(seq, session) else LinkMessage.Stop(seq, session) }
    }

    /** One seek to [positionMs]: the time bar let go. Shown there until the TV says where it is. */
    override fun seekTo(positionMs: Long) {
        if (send { seq, session -> LinkMessage.SeekTo(seq, session, positionMs) }) moved { positionMs }
    }

    /** One seek [deltaMs] on (back when negative), applied by the TV to its own exact position: a burst of ±10 s taps. */
    override fun seekBy(deltaMs: Long) {
        if (send { seq, session -> LinkMessage.SeekBy(seq, session, deltaMs) }) moved { it + deltaMs }
    }

    /** ×: the bar is put away; the phone still tries the TV, and its link coming up brings the bar back. */
    override fun dismiss() {
        _now.update { it?.copy(hidden = true) }
    }

    /** Follows nothing from now: the TV's play goes on there (a test puts the app's back as it found it). */
    fun close() = letGo()

    private fun letGo() {
        run?.job?.cancel()
        run = null
        _now.value = null
    }

    /** Sends the command [build] makes for the session followed; whether it went. */
    private fun send(build: (Int, String) -> LinkMessage): Boolean {
        val now = _now.value ?: return false
        return deps.command(now.tv) { seq -> build(seq, now.session) } != null
    }

    /** Where the play is said to be [to] (from where it is now), as a seek sent there. */
    private fun moved(to: (Long) -> Long) = _now.update { now ->
        now?.copy(positionMs = to(now.positionAt(deps.clock())).coerceIn(0, now.durationMs ?: Long.MAX_VALUE), heardAt = deps.clock())
    }

    /** [change] on the state while [mine] is the session followed. */
    private fun show(mine: Run, change: (TvPlayNow) -> TvPlayNow) = _now.update { now -> if (run === mine && now != null) change(now) else now }

    private sealed interface Event {
        class Heard(val message: LinkMessage) : Event

        class Link(val up: Boolean) : Event

        class Front(val inFront: Boolean) : Event

        /** The TV is gone for this phone: forgotten, or it removed this phone or was set up again. */
        data object Gone : Event

        /** A Final of the session followed was saved: its [line], said unless the session goes on past [watchKey]. */
        class Saved(val line: String, val watchKey: String) : Event
    }

    private suspend fun follow(mine: Run) {
        val held = deps.hold(mine.tv)
        try {
            coroutineScope {
                val events = Channel<Event>(Channel.UNLIMITED)
                mine.events = events
                val listening = listOf(
                    launch(start = CoroutineStart.UNDISPATCHED) { deps.heard.collect { if (it.tv == mine.tv) events.send(Event.Heard(it.message)) } },
                    launch(start = CoroutineStart.UNDISPATCHED) { deps.linked(mine.tv).collect { events.send(Event.Link(it)) } },
                    launch(start = CoroutineStart.UNDISPATCHED) { deps.inFront.collect { events.send(Event.Front(it)) } },
                    launch(start = CoroutineStart.UNDISPATCHED) { deps.gone(mine.tv).collect { if (it) events.send(Event.Gone) } },
                )
                try {
                    Following(mine, events, this).follow()
                } finally {
                    mine.events = null
                    listening.forEach { it.cancel() }
                }
            }
        } finally {
            held.close()
        }
    }

    /**
     * One session followed: its link, the front, when its link was lost (in front), its picture, looked up in [scope], and
     * the line of a Final saved, waiting for the session's next word.
     */
    private inner class Following(private val mine: Run, private val events: Channel<Event>, private val scope: CoroutineScope) {
        private val timings = deps.timings()
        private var front = true
        private var lostAt: Long? = null
        private var ended = false

        /** A Final's line, its watch key and when it came: said unless the session goes on to its next play. */
        private var waiting: Triple<String, String, Long>? = null

        /** The watch key whose picture is looked up, or was. */
        private var pictured: String? = null
        private var looking: Job? = null

        suspend fun follow() {
            while (!ended) {
                val unreached = lostAt?.takeIf { _now.value?.cantReach == false }?.plus(timings.cantReachMillis)
                val due = listOfNotNull(unreached, waiting?.third?.plus(LINE_WAIT_MS)).minOrNull()
                val wait = due?.let { (it - deps.clock()).coerceAtLeast(0) }
                val event = if (wait == null) events.receive() else events.receiveWithin(wait)
                when (event) {
                    null -> timeUp()
                    is Event.Heard -> heard(event.message)
                    is Event.Link -> link(event.up)
                    is Event.Front -> front(event.inFront)
                    is Event.Saved -> saved(event)
                    // Never reached again: no bar, remote or Show page line for it.
                    Event.Gone -> end("the TV is gone for this phone")
                }
            }
        }

        private fun timeUp() {
            val now = deps.clock()
            waiting?.takeIf { now - it.third >= LINE_WAIT_MS }?.let {
                waiting = null
                say(it.first)
            }
            val lost = lostAt ?: return
            if (now - lost >= timings.cantReachMillis && _now.value?.cantReach == false) {
                log.info("Play on TV: a TV playing this phone's play can't be reached")
                show(mine) { it.copy(cantReach = true) }
            }
        }

        /** A Final of this session was saved: said, unless the bar follows the session on to its next play (no line then). */
        private fun saved(saved: Event.Saved) {
            val now = _now.value?.takeIf { run === mine }
            if (now != null && now.played && now.state == Playback.Starting) return
            waiting = Triple(saved.line, saved.watchKey, deps.clock())
        }

        /** The link up again clears what its loss said; lost, the time counts from now while in front. */
        private fun link(up: Boolean) {
            if (up) {
                lostAt = null
                show(mine) { it.copy(linked = true, cantReach = false, hidden = false) }
            } else {
                lostAt = if (front) deps.clock() else null
                show(mine) { it.copy(linked = false) }
            }
        }

        /** Out of the front the phone doesn't try the TV, so the time waits; back in front it counts again from then. */
        private fun front(inFront: Boolean) {
            front = inFront
            if (!inFront) {
                lostAt = null
            } else if (_now.value?.takeIf { run === mine }?.linked == false) {
                lostAt = deps.clock()
                show(mine) { it.copy(cantReach = false) }
            }
        }

        private fun heard(message: LinkMessage) {
            when (message) {
                is Status -> if (message.session == mine.session) status(message)
                // The TV names the play now as its link comes up: another, or none, and this one is over there.
                is Hello -> if (message.session?.id != mine.session) end("the TV plays something else now")
                else -> Unit
            }
        }

        private fun status(status: Status) {
            val at = deps.clock()
            when (status.state) {
                Playback.Starting -> {
                    // The session goes on to its next play: the bar follows it, and says nothing of the one before.
                    waiting = null
                    show(mine) {
                        it.copy(
                            state = Playback.Starting, title = status.title ?: it.title, subtitle = status.subtitle ?: it.subtitle, watchKey = null,
                            positionMs = 0, durationMs = null, heardAt = at, failedStart = status.sheet?.kind in FAILED_SHEETS, art = null,
                        )
                    }
                }
                Playback.Playing, Playback.Paused, Playback.Buffering, Playback.Failed -> show(mine) {
                    it.copy(
                        state = status.state, played = it.played || status.state != Playback.Buffering, title = status.title ?: it.title,
                        subtitle = status.subtitle ?: it.subtitle, watchKey = status.watchKey ?: it.watchKey, positionMs = status.positionMs ?: it.positionMs,
                        durationMs = status.durationMs ?: it.durationMs, heardAt = at, failedStart = false,
                    )
                }
                Playback.Stopped -> end("stopped on the TV", stopped = true)
                Playback.Replaced -> end("another play took its place")
                // A state from a newer TV: the last one known stands (L26).
                Playback.Unknown -> Unit
            }
            // Its next play's player already has it: the one before isn't said.
            if (status.watchKey != null && waiting?.second?.let { it != status.watchKey } == true) waiting = null
            picture()
        }

        /** The picture of the watch key now, looked up once a key: the phone's own (L34), none until it comes. */
        private fun picture() {
            val key = _now.value?.takeIf { run === mine }?.watchKey
            if (key == pictured) return
            pictured = key
            looking?.cancel()
            looking = key?.let {
                scope.launch {
                    val art = deps.art(key)
                    show(mine) { now -> if (now.watchKey == key) now.copy(art = art) else now }
                }
            }
        }

        /**
         * The session is over ([why]): a Final's line waiting is said; a start [stopped] there before its player, with no
         * failure on its sheet to say why, was cancelled.
         */
        private fun end(why: String, stopped: Boolean = false) {
            log.info("Play on TV: a play on a TV ended ({})", why)
            ended = true
            val last = _now.value?.takeIf { run === mine }
            if (run === mine) {
                run = null
                _now.value = null
            }
            waiting?.let { say(it.first) }
            waiting = null
            if (stopped && last != null && !last.played && !last.failedStart) say(cancelledOn(last.tvName))
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(TvPlaying::class.java)

        /** The TV's sheet says its start didn't start. */
        val FAILED_SHEETS = setOf(SheetKind.NoRelease, SheetKind.Failed, SheetKind.NoProvider)

        /** A session's states while it goes on there, which a play this phone started is followed again in. */
        val LIVE = setOf(Playback.Starting, Playback.Buffering, Playback.Playing, Playback.Paused, Playback.Failed)

        /**
         * How long a Final's line waits for the session's next word: the TV says a session stopped 1.5 s after its player
         * left (StatusPump.STOP_GRACE_MS), its next play starting before that.
         */
        const val LINE_WAIT_MS = 3_000L

        /** A Final made this long before it came is told as late: "Living room TV stopped at 41:02". */
        const val LATE_MS = 30_000L
    }
}

/** Where a play on a TV stopped, told at once (PtPhoneStopped): "Stopped on Living room TV at 32:10". */
fun stoppedOnAt(name: String, positionMs: Long) = "Stopped on $name at ${mmss(positionMs)}"

/** Where it stopped, told late, once the phone reached the TV again (PtPhoneLost 4): "Living room TV stopped at 41:02". */
fun stoppedAt(name: String, positionMs: Long) = "$name stopped at ${mmss(positionMs)}"

/** L41's words for a play that ended watched there. */
fun watchedOn(name: String) = "Watched on $name"

/** L41's words for a start stopped there before its player. */
fun cancelledOn(name: String) = "Cancelled on $name"

const val BUFFERING_ON_TV = "Buffering on the TV"
const val LOST_RECONNECTING = "Lost the connection · reconnecting…"

/** × on the bar that can't reach its TV (W15): words not drawn. */
const val PUT_AWAY = "Put away"

fun cantReach(name: String) = "Can't reach $name"

/** The bar's and the remote's words for the player's "Playback stopped" there (L41). */
fun playbackStoppedOn(name: String) = "Playback stopped on $name"

fun pauseOn(name: String) = "Pause on $name"

/** The play button while the TV is paused: "Play on Living room TV". */
fun playOnTv(name: String) = "Play on $name"

fun stopPlayingOn(name: String) = "Stop playing on $name"

/** The Show page's button while the TV or a Cast device plays one of its episodes (W14, L91): "Playing S02E04 · 32:10". */
fun playingEpisode(now: RemoteNow, positionMs: Long) = "Playing ${now.episodeLabel.orEmpty()} · ${mmss(positionMs)}"

/** That episode's row (W14, L91): "On Living room TV · 32:10", "On Kitchen display · 32:10". */
fun onTvAt(name: String, positionMs: Long) = "On $name · ${mmss(positionMs)}"

/** A time as TalkBack reads it, as the app's player does: "32 minutes 10 seconds", the parts that aren't nought. */
fun spokenTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    fun part(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"}"
    val parts = listOf(seconds / 3_600 to "hour", seconds / 60 % 60 to "minute", seconds % 60 to "second").filter { (n, _) -> n > 0 }
    return parts.joinToString(" ") { (n, unit) -> part(n, unit) }.ifEmpty { part(0, "second") }
}
