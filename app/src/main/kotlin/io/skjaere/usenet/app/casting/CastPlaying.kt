package io.skjaere.usenet.app.casting

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.CastExtras
import io.skjaere.usenet.app.home.UpNextPlay
import io.skjaere.usenet.app.link.RemoteBar
import io.skjaere.usenet.app.link.RemoteKind
import io.skjaere.usenet.app.link.RemoteNow
import io.skjaere.usenet.app.link.RemotePlaying
import io.skjaere.usenet.app.link.RemoteSaid
import io.skjaere.usenet.app.link.RemoteSaidAction
import io.skjaere.usenet.app.link.TvArt
import io.skjaere.usenet.app.link.playOnTimings
import io.skjaere.usenet.app.play.CastSlowStream
import io.skjaere.usenet.app.play.MissingParts
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SlowStream
import io.skjaere.usenet.data.PlaybackPrefs
import io.skjaere.usenet.player.PlayUris
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * A cast play on its device as the bar, the remote and the Show page show it (L91; CsPhoneBar, CsPhoneRemote): the
 * device [device] at [route], the play's own [load] (its LOAD's content id), its titles and watch key, what the bar says,
 * where it was ([positionMs], heard at [heardAt] on the phone's monotonic clock), its length and TMDB's picture. The bar
 * always shows while it lives: a cast has no × (L91). It prints without its words.
 */
data class CastNow(
    override val device: String,
    val route: String,
    val load: String,
    override val title: String,
    override val subtitle: String?,
    override val watchKey: String?,
    override val shows: RemoteBar,
    override val positionMs: Long,
    override val durationMs: Long?,
    override val heardAt: Long,
    override val art: TvArt? = null,
) : RemoteNow {
    override val kind: RemoteKind get() = RemoteKind.Cast

    override val bar: RemoteBar get() = shows

    override val live: Boolean get() = shows != RemoteBar.Stopped

    override val moving: Boolean get() = shows == RemoteBar.Playing

    override fun toString() = "CastNow($shows)"
}

/** The slow-stream banner during a cast as it shows (L134; CsPhoneSlowCast): its words, and whether Try a smaller release is offered. */
internal data class CastSlowBanner(val shown: SlowStream.Shown, val trySmaller: Boolean)

/**
 * What following a cast play runs on: castExtras' feed, session and the device's player, whether the app is in front, and
 * the coordinator's Try another release for missing parts ([of]), or fakes, so it runs in a JVM test.
 */
class CastPlayingDeps(
    /** The play on the device as the phone follows it (L91): from its LOAD, and after its end until the next LOAD. */
    val feed: StateFlow<CastFeed?>,
    /** The Cast session now: its device, and whether it is connected or suspended. */
    val link: StateFlow<CastLinkNow?>,
    /** The device's player's pause, play and seek (L48): the buttons' on the main thread, the gap watch's from any. */
    val pause: () -> Unit,
    val resume: () -> Unit,
    val seekTo: (Long) -> Unit,
    /** Stop casting (L85): the cast ends, the session with it. */
    val stop: () -> Unit,
    /** Monotonic milliseconds: where the play is between the feed's updates. */
    val clock: () -> Long,
    /** Whether one of the app's activities is in front (L31): a line made while none is waits for it (L93). */
    val inFront: StateFlow<Boolean> = MutableStateFlow(true),
    /** How long a line is said (L93: the Undo bar's 4 s, PlayOnTimings.noticeMillis). */
    val noticeMillis: () -> Long = { playOnTimings().noticeMillis },
    /**
     * Try another release from the missing-parts prompt (L94): the launch playing there, still playing, from where it is;
     * the coordinator's tryAnotherForMissingParts, on the same device. On the main thread.
     */
    val tryAnother: (PlayerLaunch, Long) -> Unit = { _, _ -> },
    /** Whether the launch has another release to try: else the prompt is the count alone. */
    val canTryAnother: (PlayerLaunch) -> Boolean = { false },
    /** The cast's timings: the gap watch's and the notice's (L98). */
    val timings: () -> CastTimings = ::castTimings,
    /**
     * Up next's next episode of the show the play was of, as the collection says it once the play's watch is saved
     * (L125); null for none.
     */
    val nextEpisode: suspend (PlayerLaunch) -> UpNextPlay.NextEpisode? = { null },
    /** Plays Up next's next episode as its row does, where Play on says (PlayRouter.upNext). On the main thread. */
    val play: (UpNextPlay.NextEpisode) -> Unit = {},
    /** How long a line with a button is said (W65: PlayOnTimings.nextMillis). */
    val nextMillis: () -> Long = { playOnTimings().nextMillis },
    /** Whether TalkBack explores by touch: a line with a button then has no timeout (W65). */
    val touchExploration: () -> Boolean = { false },
    /** A play starting, as the coordinator says it (PlaybackCoordinator.starting): a line with a button goes. */
    val starting: Flow<Unit> = emptyFlow(),
    /** The playback settings now: the slow-stream banner's hint reads the two maximum bitrates (W52, W64). */
    val playbackPrefs: suspend () -> PlaybackPrefs = { PlaybackPrefs() },
    /**
     * Whether Try a smaller release has one to try for the launch, read at the rate (bits per second) where the file
     * needs its bitrate (W2): the coordinator's canTrySmaller.
     */
    val canTrySmaller: suspend (PlayerLaunch, Long, Long) -> Boolean = { _, _, _ -> false },
    /**
     * Try a smaller release (L134): the launch playing there, from where it is, read at the rate where the file needs its
     * bitrate; the coordinator's trySmaller, on the same device. On the main thread.
     */
    val trySmaller: (PlayerLaunch, Long, Long, Long) -> Unit = { _, _, _, _ -> },
) {
    companion object {
        fun of(c: AppContainer, extras: CastExtras, context: Context) = CastPlayingDeps(
            feed = extras.feed,
            link = extras.now,
            pause = { extras.player?.pause() },
            resume = { extras.player?.play() },
            seekTo = { at -> onMain { extras.player?.seekTo(at) } },
            stop = extras::stop,
            clock = SystemClock::elapsedRealtime,
            inFront = c.inFront.value,
            tryAnother = { launch, at -> c.playback.tryAnotherForMissingParts(launch, at, c.engine.inUse) },
            canTryAnother = { c.playback.canTryAnother(it) },
            nextEpisode = { upNextAfter(c, it) },
            play = { c.playRouter.upNext(it) },
            touchExploration = { context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true },
            starting = c.playback.starting,
            playbackPrefs = { c.settings.playback.first() },
            canTrySmaller = { launch, measured, playing -> c.playback.canTrySmaller(launch, measured, playing) },
            trySmaller = { launch, at, measured, playing -> c.playback.trySmaller(launch, at, measured, playing) },
        )

        /** [block] on the main thread, the device's player's: at once there, else posted to it. */
        private fun onMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post(block)
        }
    }
}

/**
 * The cast play the bar, the remote and the Show page follow (L91, L92; CsPhoneBar, CsPhoneRemote), from the feed and the
 * Cast session (castExtras'), not the cast's service, so a play that stopped there with an error keeps "Playback stopped on
 * Kitchen display" and its Stop casting after the service has stopped (L85, L88). Shown from its LOAD: playing, paused, or
 * loading (the device fetching or buffering), its position standing still but while it plays, when it moves on between the
 * feed's updates, about once a second ([position]); reconnecting while the session is suspended, its buttons waiting. It
 * goes as the play ends there (the bar says nothing more of it; L93 is its own), as its session ends or is another
 * device's, or as × puts it away. Its buttons go to the device's player: pause, play and the remote's seeks, a burst of
 * ±10 s taps as one seek to where it is plus their sum (the device's player has no seek by), and Stop casting.
 *
 * Once a play that played there ends, it says how, once, for a moment ([said], L93; CsPhoneStopped): "Stopped on Kitchen
 * display at 32:10", "Watched on Kitchen display", "Kitchen display closed after a long pause. Stopped at 32:10."; nothing
 * for an error, which the bar says, or a play the next one's LOAD replaced. A line made while the app isn't in front (the
 * long pause's, mostly: the phone is locked by then) waits until it is.
 *
 * Nothing plays by itself on a cast (W54 (a)): watched there, with the session still alive as the line is said and Up
 * next holding the show's next episode, the line gains one button, "Play S02E05 on Kitchen display" (L125;
 * CsPhoneWatchedNext), which plays it as Up next's row does ([act]). That line stays 10 s; with TalkBack exploring by touch
 * it stays until its button is used, another screen opens ([left]) or a play starts. The button lives while the play's
 * session does, connected: once it ends, is suspended or is another device's, the line stays alone for the notice's
 * time, and its button plays nothing, as it would no longer play there.
 *
 * While a play lives there, it counts the missing parts the phone's server sent the device, as the player counts its own
 * ([MissingParts], each placed by its share of the file, L94), and runs the gap watch ([CastGapWatch], L104), every
 * [PARTS_TICK_MS]: the prompt ([missing]; W37, CsPhoneMissingParts) asks as the player's does, its time waiting while the
 * app isn't in front, and at once, saying the device is stuck, when the gap watch's seeks didn't get it past one; one
 * part is said for a moment as the play gets to it ([notice]). Keep watching and Try another release answer it, the
 * latter from where it plays, on the same device.
 *
 * And it judges whether the stream keeps up there ([CastDip], from what Casting counts each second; W52, L134): falling
 * behind, the slow-stream banner ([slow]; CsPhoneSlowCast) says what reached the device against what the film needs,
 * which part is slow, the Wi-Fi to it or the providers, and what Maximum bitrate when casting could do (W64), with Try a
 * smaller release where the play has one ([trySmaller]). It waits while the missing-parts prompt has the slot (W37), and
 * hides as the stream keeps up; never in the notification. The dips each play had are counted for its end's log line
 * ([dips], L136).
 */
class CastPlaying(private val scope: CoroutineScope, private val deps: CastPlayingDeps) : RemotePlaying {
    private val _now = MutableStateFlow<CastNow?>(null)

    override val now: StateFlow<CastNow?> = _now.asStateFlow()

    private val _said = MutableStateFlow<RemoteSaid?>(null)
    private val lines = AtomicLong()

    override val said: StateFlow<RemoteSaid?> = _said.asStateFlow()

    private val _missing = MutableStateFlow<CastPrompt?>(null)

    /** The missing-parts prompt for the play there, as the remote and the app's other screens show it; null for none. */
    val missing: StateFlow<CastPrompt?> = _missing.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    private val notices = AtomicLong()

    /** "A missing part: Kitchen display may glitch", for a moment, as the play gets to one; null otherwise. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _slow = MutableStateFlow<CastSlowBanner?>(null)

    /**
     * The slow-stream banner for the play there (L134; CsPhoneSlowCast), as the remote and the app's other screens show
     * it; null for none, and while the missing-parts prompt has its slot (W37).
     */
    internal val slow: StateFlow<CastSlowBanner?> = _slow.asStateFlow()

    /**
     * One LOAD's missing parts, gap watch and slow-stream judge, and the stretches of zeros counted so far; [stuck]: the gap
     * watch asked. [slowFor]: the dip whose banner is being made or shown, and [banner] once it is made.
     */
    private class Parts(val load: String, val missing: MissingParts, val watch: CastGapWatch, val dip: CastDip) {
        val counted = HashSet<LongRange>()
        var stuck = false
        var slowFor: CastDip.Slow? = null
        var banner: CastSlowBanner? = null
    }

    /** Guards [parts]: the ticks and the prompt's buttons (on the main thread) share it. */
    private val lock = Any()
    private var parts: Parts? = null

    /** The play watched before [parts]' (its load) and the dips it had: the next LOAD's took its place before it ended. */
    private var before: Pair<String, Int>? = null

    /** The ticks while a play lives there: its missing parts and its stream. The feed's collector's alone. */
    private var ticking: Job? = null

    /** The load whose session went, or that × put away: never shown again. The feed's collector's alone. */
    @Volatile private var gone: String? = null

    /** Guards [ended] and [waiting]: the end's collector's, and a test's [forget]. */
    private val saying = Any()

    /** The load whose end was taken: each play's end is said once. */
    private var ended: String? = null

    /** A play's end made while the app wasn't in front, said as it comes to the front. */
    private var waiting: Ended? = null

    /** A play's end there to say: its [feed] as it ended, and the line for it ([text]). */
    private class Ended(val feed: CastFeed, val text: String)

    /** The line with a button said with no timeout (TalkBack exploring), which another screen puts away. */
    @Volatile private var held: RemoteSaid? = null

    /** The line said with the next episode's button, and the play whose session the button lives with (L125). */
    @Volatile private var next: Pair<RemoteSaid, CastFeed>? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { combine(deps.feed, deps.link, ::Pair).collect { (feed, link) -> heard(feed, link) } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { combine(deps.feed, deps.inFront, ::Pair).collect { (feed, front) -> after(feed, front) } }
        // A play starting: the next episode's button is no longer what to do next.
        scope.launch { deps.starting.collect { _said.value?.takeIf { it.action != null }?.let(::putAway) } }
    }

    /** The feed or the front changed: a play's end there is said once, at once in front, else as the app comes to it. */
    private fun after(feed: CastFeed?, front: Boolean) {
        synchronized(saying) {
            val end = feed?.end
            if (feed != null && end != null && feed.load != ended) {
                ended = feed.load
                waiting = feed.launch.cast?.name?.let(end::said)?.let { Ended(feed, it) }
            }
            if (!front) return
            waiting?.let(::sayEnd)
            waiting = null
        }
    }

    /**
     * Says how [ended]'s play ended: watched there, with Up next's next episode's button while its session still lives
     * (L125), else the line alone.
     */
    private fun sayEnd(ended: Ended) {
        val feed = ended.feed
        val name = feed.launch.cast?.name
        if (feed.end?.how != CastEnd.How.Watched || name == null || !lives(feed)) return say(ended.text)
        scope.launch {
            // The session may have gone while Up next was asked.
            val action = deps.nextEpisode(feed.launch)?.takeIf { lives(feed) }?.let { RemoteSaidAction(playNextOn(it.episode, name), it) }
            say(ended.text, action, feed)
        }
    }

    /** Whether [feed]'s play's session lives, its device still connected: what the next episode's button needs (L125). */
    private fun lives(feed: CastFeed): Boolean = deps.link.value?.let { it.route == feed.launch.cast?.route && it.link == CastLink.Connected } == true

    /**
     * Says [text] for the notice's time, in place of what was said; with its button ([action], for [feed]'s play) for 10 s,
     * and with TalkBack exploring until it is used, another screen opens or a play starts (W65).
     */
    private fun say(text: String, action: RemoteSaidAction? = null, feed: CastFeed? = null) {
        val line = RemoteSaid(text, lines.incrementAndGet(), RemoteKind.Cast, action)
        _said.value = line
        held = null
        next = feed?.takeIf { action != null }?.let { line to it }
        if (action != null && deps.touchExploration()) {
            held = line
            return
        }
        goesAfter(line, if (action != null) deps.nextMillis() else deps.noticeMillis())
    }

    /** [line] goes [millis] from now, if it is still what is said then. */
    private fun goesAfter(line: RemoteSaid, millis: Long) {
        scope.launch {
            delay(millis)
            _said.compareAndSet(line, null)
        }
    }

    /** [line] goes, if it is still what is said. */
    private fun putAway(line: RemoteSaid): Boolean {
        if (held == line) held = null
        if (next?.first == line) next = null
        return _said.compareAndSet(line, null)
    }

    /**
     * The next episode's button gone with its play's session (L125): the line, still said, stays alone, for the notice's
     * time from now as a line alone is said, TalkBack's too.
     */
    private fun buttonGoesWithItsSession() {
        val (line, feed) = next ?: return
        if (lives(feed)) return
        next = null
        if (held == line) held = null
        val alone = line.copy(action = null)
        if (_said.compareAndSet(line, alone)) goesAfter(alone, deps.noticeMillis())
    }

    /**
     * The next episode's button (L125): the line goes and the episode plays, where Play on says, as Up next's row does;
     * nothing once its play's session is gone, as it would no longer play on the device the button names.
     */
    override fun act(said: RemoteSaid) {
        val action = said.action ?: return
        buttonGoesWithItsSession()
        if (next?.first != said) return
        if (putAway(said)) deps.play(action.play)
    }

    /** Another screen opened: a line held with no timeout goes (W65); one with its time to run stays. */
    override fun left(said: RemoteSaid) {
        if (held == said) putAway(said)
    }

    /** The feed or the session changed: what the bar says of the play now. */
    private fun heard(feed: CastFeed?, link: CastLinkNow?) {
        buttonGoesWithItsSession()
        // Its session went, or is another device's: this play is over here, and isn't shown again in a later session.
        if (feed != null && (link == null || link.route != feed.launch.cast?.route)) gone = feed.load
        // No play followed: none to keep away.
        if (feed == null) gone = null
        // Its missing parts are watched while it lives there.
        if (feed != null && feed.end == null && feed.load != gone) {
            if (ticking == null) ticking = scope.launch { ticks() }
        } else {
            ticking?.cancel()
            ticking = null
            synchronized(lock) {
                _missing.value = null
                _notice.value = null
                _slow.value = null
                // A banner still being made is for a play no longer watched.
                parts?.let {
                    it.slowFor = null
                    it.banner = null
                }
            }
        }
        val at = deps.clock()
        _now.update { before ->
            // Read in the update, so × putting it away meanwhile (on the main thread) isn't undone.
            val play = feed?.takeIf { it.load != gone } ?: return@update null
            val shows = shows(play, link) ?: return@update null
            // A new word of the feed says where the device is as it comes; the session's alone says nothing of where.
            val same = before?.takeIf { it.load == play.load && it.positionMs == play.positionMs && it.shows == shows }
            CastNow(
                device = link?.name ?: play.launch.cast?.name.orEmpty(),
                route = play.launch.cast?.route.orEmpty(),
                load = play.load,
                title = play.launch.title,
                subtitle = play.launch.subtitle,
                watchKey = play.launch.watchKey,
                shows = shows,
                positionMs = play.positionMs,
                durationMs = play.durationMs ?: play.launch.video?.durationMs,
                heardAt = same?.heardAt ?: at,
                art = play.art,
            )
        }
    }

    override fun position(now: RemoteNow): Long = now.positionAt(deps.clock())

    /**
     * Forgets the line said and the one waiting for the app in front (a device test's reset): the tests' plays share one
     * process, and a line one test's play left waiting would be said in the next test's activity.
     */
    @VisibleForTesting
    fun forget() {
        synchronized(saying) { waiting = null }
        held = null
        next = null
        _said.value = null
    }

    /** Keep watching (W37): the prompt hides, and asks again at the next count, as the player's does. */
    fun keepWatching() {
        synchronized(lock) {
            parts?.let {
                it.missing.keepWatching()
                it.stuck = false
            }
            _missing.value = null
        }
    }

    /**
     * Try another release (L94): the next release starts on the same device from where this one plays, which goes on there
     * until that one loads (L84); the prompt hides, and asks again at the next count if none plays.
     */
    fun tryAnother() {
        val feed = deps.feed.value?.takeIf { it.end == null } ?: return
        val at = _now.value?.takeIf { it.load == feed.load }?.let(::position) ?: feed.positionMs
        keepWatching()
        deps.tryAnother(feed.launch, at)
    }

    /**
     * Try a smaller release (L134): the play's smaller releases start on the same device from where this one plays, read
     * at the dip's rate (the coordinator's trySmaller), and this one goes on there until one loads (L84, L135); the banner
     * goes, and that dip shows no more.
     */
    fun trySmaller() {
        val feed = deps.feed.value?.takeIf { it.end == null } ?: return
        val at = _now.value?.takeIf { it.load == feed.load }?.let(::position) ?: feed.positionMs
        val dip = synchronized(lock) {
            val p = parts?.takeIf { it.load == feed.load } ?: return
            val slow = p.slowFor?.takeIf { p.banner?.trySmaller == true } ?: return
            p.dip.answered()
            p.slowFor = null
            p.banner = null
            _slow.value = null
            slow.dip
        }
        deps.trySmaller(feed.launch, at, dip.rateBps, dip.bitrateBps)
    }

    /** How many slow-stream dips [load]'s play had (L136): the one watched, or the one before it, which the next LOAD took over. */
    fun dips(load: String): Int = synchronized(lock) {
        parts?.takeIf { it.load == load }?.dip?.found ?: before?.takeIf { it.first == load }?.second ?: 0
    }

    /** Watches the play's missing parts, and whether its stream keeps up, each [PARTS_TICK_MS] while it lives there. */
    private suspend fun ticks() {
        while (true) {
            watchParts()
            delay(PARTS_TICK_MS)
        }
    }

    /**
     * One tick: the stretches of zeros sent since the last counted, each by its place in the film; the gap watch while the
     * session is up, told while it is down (its statuses don't come meanwhile, and the time away isn't the device standing
     * stuck); then what the prompt and the notice say, at where the play is; then whether the stream keeps up there, as
     * Casting last counted it ([CastDip]).
     */
    private fun watchParts() {
        val feed = deps.feed.value?.takeIf { it.end == null } ?: return
        val link = deps.link.value
        val connected = link?.link == CastLink.Connected && link.route == feed.launch.cast?.route
        val name = link?.name ?: feed.launch.cast?.name.orEmpty()
        val position = _now.value?.takeIf { it.load == feed.load }?.let(::position) ?: feed.positionMs
        val another = deps.canTryAnother(feed.launch)
        val front = deps.inFront.value
        var seek: Long? = null
        var notice = false
        var slowNew: Pair<Parts, CastDip.Slow>? = null
        synchronized(lock) {
            // Ended meanwhile: nothing more to say of it.
            if (deps.feed.value?.let { it.load == feed.load && it.end == null } != true) return
            val p = parts?.takeIf { it.load == feed.load }
                ?: Parts(feed.load, MissingParts(deps.clock), CastGapWatch(deps.timings()), CastDip(deps.timings())).also { new ->
                    parts?.let { before = it.load to it.dip.found }
                    parts = new
                }
            count(p, feed)
            if (connected) {
                when (val act = p.watch.heard(feed, deps.clock())) {
                    is CastGapWatch.Act.Seek -> seek = act.toMs
                    CastGapWatch.Act.Prompt -> {
                        p.missing.askNow(another)
                        p.stuck = true
                    }
                    null -> Unit
                }
            } else {
                p.watch.away(feed, deps.clock())
            }
            val shown = p.missing.update(position, waiting = !front, tryAnother = another)
            if (shown.prompt == null) p.stuck = false
            notice = shown.notice
            _missing.value = shown.prompt?.let { CastPrompt(it.count, it.tryAnother, p.stuck, name) }
            // The slow-stream banner (L134): judged under the prompt too, and shown once the slot is its (W37).
            val slow = p.dip.heard(feed, connected, covered = shown.prompt != null || !front)
            if (slow !== p.slowFor) {
                p.slowFor = slow
                p.banner = null
                if (slow != null) slowNew = p to slow
            }
            _slow.value = p.banner?.takeIf { shown.prompt == null }
        }
        seek?.let(::sendPast)
        if (notice) noticed(aMissingPart(name))
        slowNew?.let { (p, slow) -> banner(p, slow, feed.launch, name) }
    }

    /**
     * Makes [slow]'s banner for [p]'s play on [name]: the hint by the maximums set (W52, W64), the slow source the debrid
     * service for a play from one (L220), and Try a smaller release where the play has one (W2); shown while it is still
     * the dip there and the slot is its.
     */
    private fun banner(p: Parts, slow: CastDip.Slow, launch: PlayerLaunch, name: String) {
        scope.launch {
            val prefs = deps.playbackPrefs()
            val smaller = deps.canTrySmaller(launch, slow.dip.rateBps, slow.dip.bitrateBps)
            val fromService = PlayUris.parse(launch.uri) is PlayUris.Target.Service
            val made = CastSlowBanner(CastSlowStream.shown(slow, name, prefs.maxBitrate.mbps(), prefs.castMaxBitrate.mbps(), fromService = fromService), smaller)
            synchronized(lock) {
                if (parts !== p || p.slowFor !== slow) return@synchronized
                p.banner = made
                if (_missing.value == null) _slow.value = made
            }
        }
    }

    /** The stretches of zeros [feed] says were sent and [p] hasn't counted, each at its place by the file's share (L94). */
    private fun count(p: Parts, feed: CastFeed) {
        val length = feed.durationMs ?: feed.launch.video?.durationMs ?: return
        val size = feed.size?.takeIf { it > 0 } ?: return
        for (gap in feed.gaps) {
            if (!p.counted.add(gap)) continue
            val at = CastGapWatch.placed(gap, size, length).first
            // A spot of its own, not one it grew from: said as the play gets there.
            if (p.missing.found(gap, at)) p.missing.jumped(gap, at)
        }
    }

    /** The gap watch's seek (L104): to the device's player, shown there until the device says where it is. */
    private fun sendPast(toMs: Long) {
        deps.seekTo(toMs)
        _now.update { it?.copy(positionMs = toMs, heardAt = deps.clock()) }
    }

    /** Says [text] in the remote's state line for the notice's time. */
    private fun noticed(text: String) {
        val mine = notices.incrementAndGet()
        _notice.value = text
        scope.launch {
            delay(deps.timings().partNoticeMillis)
            if (notices.get() == mine) _notice.compareAndSet(text, null)
        }
    }

    override fun pause() {
        if (_now.value?.controls == true) deps.pause()
    }

    override fun resume() {
        if (_now.value?.controls == true) deps.resume()
    }

    /** Stop casting (L85): the cast ends and the session with it; the phone keeps the place, as for a play here. */
    override fun stop() {
        val now = _now.value ?: return
        if (!now.waits) deps.stop()
    }

    /** One seek to [positionMs]: shown there until the device says where it is. */
    override fun seekTo(positionMs: Long) {
        val now = _now.value?.takeIf { it.controls } ?: return
        val to = positionMs.coerceIn(0, now.durationMs ?: Long.MAX_VALUE)
        deps.seekTo(to)
        _now.update { it?.takeIf { it.load == now.load }?.copy(positionMs = to, heardAt = deps.clock()) ?: it }
    }

    /** One seek [deltaMs] from where it is now: the device's player seeks only to a place (L91). */
    override fun seekBy(deltaMs: Long) {
        val now = _now.value ?: return
        seekTo(position(now) + deltaMs)
    }

    /** ×: put away until the next play's LOAD. A cast's bar has none (L91); a test's or a later caller's. */
    override fun dismiss() {
        gone = _now.value?.load ?: return
        _now.value = null
    }

    private companion object {
        /** How often the missing parts are watched while a play lives there, as the player ticks its own. */
        const val PARTS_TICK_MS = 500L

        /** A maximum bitrate set, in bits per second, as the banner's hint names it: whole Mbps. */
        fun Long?.mbps(): Int? = this?.let { (it / 1_000_000).toInt() }

        /**
         * What the bar says of [feed] in the session [link]: Playback stopped after an error there (L88); nothing once it
         * ended otherwise; reconnecting while the session is suspended; else the device's state, loading for any but
         * playing or paused.
         */
        fun shows(feed: CastFeed, link: CastLinkNow?): RemoteBar? {
            feed.end?.let { end -> return if (end.how == CastEnd.How.Failed) RemoteBar.Stopped else null }
            if (link?.link != CastLink.Connected) return RemoteBar.Reconnecting
            return when (feed.state) {
                CastPlayerState.Playing -> RemoteBar.Playing
                CastPlayerState.Paused -> RemoteBar.Paused
                CastPlayerState.Loading, CastPlayerState.Buffering, CastPlayerState.Idle, CastPlayerState.Unknown -> RemoteBar.Loading
            }
        }
    }
}
