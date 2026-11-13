package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.casting.STOP_CASTING
import io.skjaere.usenet.app.home.UpNextPlay
import io.skjaere.usenet.app.casting.stopCastingTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

/**
 * A play on another device, as the Playing on bar, the remote and the Show page follow it (L91): one this phone started on
 * a TV ([TvPlaying]), or one it casts to a Cast device (CastPlaying). What it is now ([now]), where it is at this moment
 * ([position]: moved on between the device's updates while it plays), and the buttons: pause, play, Stop (Stop casting
 * for a cast), the remote's seeks, and × putting the bar away.
 */
interface RemotePlaying {
    /** The play followed now; null for none. */
    val now: StateFlow<RemoteNow?>

    /**
     * What the phone says of its plays there once they end, for a moment, over the page wherever it is (item 27, L93;
     * PtPhoneStopped, CsPhoneStopped): "Stopped on Kitchen display at 32:10"; null while it says nothing.
     */
    val said: StateFlow<RemoteSaid?>

    /** Where [now]'s play is at this moment. */
    fun position(now: RemoteNow): Long

    fun pause()

    fun resume()

    fun stop()

    /** One seek to [positionMs]: the time bar let go. */
    fun seekTo(positionMs: Long)

    /** One seek [deltaMs] on (back when negative): a burst of ±10 s taps. */
    fun seekBy(deltaMs: Long)

    /** ×: the bar is put away. */
    fun dismiss()

    /** The button of a line said ([said]'s [RemoteSaid.action]) used: the line goes, and its play starts (L125). */
    fun act(said: RemoteSaid) = Unit

    /** Another screen opened under the line [said]: one held with no timeout (TalkBack exploring, L125) goes. */
    fun left(said: RemoteSaid) = Unit

    /**
     * The play [now] is, whose buttons are its own: this one, or, where the TV's and the cast's are followed together
     * ([RemotePlays]), the one [now] is of. What waits a moment before it goes (the remote's burst of ±10 s taps) goes
     * there, whichever play is shown by then.
     */
    fun playOf(now: RemoteNow): RemotePlaying = this
}

/** Which kind of device plays it: a TV with NZStreamer (Play on TV), or a Google Cast device. */
enum class RemoteKind { Tv, Cast }

/**
 * A line the phone says of its plays on another device ([text]), the [id]th of its [kind]'s: one said again is a new one.
 * With an [action], one button under it (L125).
 */
data class RemoteSaid(val text: String, val id: Long, val kind: RemoteKind = RemoteKind.Tv, val action: RemoteSaidAction? = null)

/**
 * A line's one button (L125; CsPhoneWatchedNext): its [words], "Play S02E05 on Kitchen display", and what it plays, Up
 * next's next episode of the show ([play]).
 */
data class RemoteSaidAction(val words: String, val play: UpNextPlay.NextEpisode)

/** What the Playing on bar says (PtPhoneBar, PtPhoneLost; CsPhoneBar): the device's state, or where its link is. */
enum class RemoteBar {
    Playing,
    Paused,

    /** A TV's player buffers. */
    Buffering,

    /** The session's next play is starting on the TV: the next episode, after one that played (L34's `continued`). */
    NextEpisode,

    /** The TV's link is lost: the buttons wait and the line stands still, while the phone tries again. */
    Lost,

    /** Lost for [PlayOnTimings.cantReachMillis] in front: "Can't reach <TV>", with × to put it away (W15). */
    CantReach,

    /** The player there shows "Playback stopped", or a TV session's next play couldn't start: Stop alone is left. */
    Stopped,

    /** A Cast device loads or buffers what the phone serves it: after a seek, or while it waits for data (L91). */
    Loading,

    /** The Cast session is suspended, the phone's Wi-Fi gone a moment: the buttons wait and the line stands still (L91). */
    Reconnecting,
}

/**
 * A play on another device as the bar, the remote and the Show page show it (L91), whichever kind of device ([kind]) it
 * is on, named [device]: its titles and watch key, what the bar says ([shows]; [bar] while it shows at all), where it was
 * ([positionMs], heard at [heardAt] on the phone's monotonic clock) and its length, and this phone's own picture of it.
 */
interface RemoteNow {
    val kind: RemoteKind
    val device: String
    val title: String
    val subtitle: String?
    val watchKey: String?

    /** What the bar says, put away or not. */
    val shows: RemoteBar

    /** The bar, while it shows; null for none. */
    val bar: RemoteBar?
    val positionMs: Long
    val durationMs: Long?
    val heardAt: Long
    val art: TvArt?

    /** Whether the device plays it, paused or not, its link up or not: what the Show page's line says plays there (W14). */
    val live: Boolean

    /** Whether its position moves on between the device's updates: it plays, its link up. */
    val moving: Boolean

    /** Where it is at [clock] (the phone's monotonic clock): moved on since it was heard while [moving], never past its end. */
    fun positionAt(clock: Long): Long {
        if (!moving) return positionMs
        val moved = positionMs + (clock - heardAt).coerceAtLeast(0)
        return durationMs?.let { moved.coerceAtMost(it) } ?: moved
    }

    /** The episode its watch key names: show, season and episode; null for a movie, or before the device says. */
    val episode: Triple<Int, Int, Int>?
        get() = watchKey?.let(EPISODE_KEY::matchEntire)?.destructured?.let { (show, season, episode) ->
            Triple(show.toIntOrNull() ?: return null, season.toIntOrNull() ?: return null, episode.toIntOrNull() ?: return null)
        }

    /** "S02E04": from the watch key, else from the subtitle while the next episode starts; null for a movie. */
    val episodeLabel: String?
        get() = episode?.let { (_, season, number) -> "S%02dE%02d".format(season, number) }
            ?: subtitle?.substringBefore(" · ")?.takeIf(EPISODE_LABEL::matches)

    /** The bar's title: "Harbor Lights · S02E04", or a movie's title. */
    val label: String get() = listOfNotNull(title, episodeLabel).joinToString(" · ")

    /** The title as TalkBack reads it: "Harbor Lights, season 2, episode 4". */
    val spokenTitle: String get() = episode?.let { (_, season, number) -> "$title, season $season, episode $number" } ?: title

    /** Whether its buttons may pause, play and seek it: the device's player has it there, its link up. */
    val controls: Boolean get() = shows in CONTROLLED

    /** Whether its buttons wait, Stop too: the device's link lost while the phone tries again. */
    val waits: Boolean get() = shows in WAITING
}

private val EPISODE_LABEL = Regex("S\\d+E\\d+")
private val CONTROLLED = setOf(RemoteBar.Playing, RemoteBar.Paused, RemoteBar.Buffering, RemoteBar.Loading)
private val WAITING = setOf(RemoteBar.Lost, RemoteBar.CantReach, RemoteBar.Reconnecting)

/**
 * What the bar, the remote and the Show page follow (L91): the play cast to a device while one lives ([cast]), else the one
 * this phone started on a TV ([tv]). Each button goes to the play shown. What is said over the page is the last line
 * either said (L93, item 27).
 */
class RemotePlays(scope: CoroutineScope, private val tv: RemotePlaying, private val cast: RemotePlaying?) : RemotePlaying {
    override val now: StateFlow<RemoteNow?> =
        if (cast == null) tv.now
        else combine(cast.now, tv.now) { casting, onTv -> casting ?: onTv }.stateIn(scope, SharingStarted.Eagerly, cast.now.value ?: tv.now.value)

    /** The line either said last, by its device's mark, until that one's own goes. */
    override val said: StateFlow<RemoteSaid?> =
        if (cast == null) tv.said
        else merge(tv.said.map { RemoteKind.Tv to it }, cast.said.map { RemoteKind.Cast to it })
            .runningFold(cast.said.value ?: tv.said.value) { shown, (kind, line) -> line ?: shown?.takeIf { it.kind != kind } }
            .stateIn(scope, SharingStarted.Eagerly, cast.said.value ?: tv.said.value)

    /** The play [now] is: a cast's, or the TV's. */
    override fun playOf(now: RemoteNow): RemotePlaying = if (now.kind == RemoteKind.Cast) cast ?: tv else tv

    private fun shown(): RemotePlaying? = now.value?.let(::playOf)

    override fun position(now: RemoteNow): Long = playOf(now).position(now)

    override fun pause() {
        shown()?.pause()
    }

    override fun resume() {
        shown()?.resume()
    }

    override fun stop() {
        shown()?.stop()
    }

    override fun seekTo(positionMs: Long) {
        shown()?.seekTo(positionMs)
    }

    override fun seekBy(deltaMs: Long) {
        shown()?.seekBy(deltaMs)
    }

    override fun dismiss() {
        shown()?.dismiss()
    }

    /** To the play whose line it is, by its device's mark. */
    override fun act(said: RemoteSaid) = saidBy(said).act(said)

    override fun left(said: RemoteSaid) = saidBy(said).left(said)

    private fun saidBy(said: RemoteSaid): RemotePlaying = if (said.kind == RemoteKind.Cast) cast ?: tv else tv
}

/** Nothing on another device, ever: a TV's, which plays its own. */
object NoRemote : RemotePlaying {
    override val now: StateFlow<RemoteNow?> = MutableStateFlow<RemoteNow?>(null).asStateFlow()

    override val said: StateFlow<RemoteSaid?> = MutableStateFlow<RemoteSaid?>(null).asStateFlow()

    override fun position(now: RemoteNow): Long = now.positionMs

    override fun pause() = Unit

    override fun resume() = Unit

    override fun stop() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun seekBy(deltaMs: Long) = Unit

    override fun dismiss() = Unit
}

/** The bar's second line (PtPhoneBar, PtPhoneLost; CsPhoneBar), and the remote's state line but where its link is lost. */
fun barLine(now: RemoteNow): String = when (now.shows) {
    RemoteBar.Playing -> "Playing on ${now.device}"
    RemoteBar.Paused -> "Paused on ${now.device}"
    RemoteBar.Buffering -> BUFFERING_ON_TV
    RemoteBar.NextEpisode -> "Next episode starting on ${now.device}"
    RemoteBar.Lost -> LOST_RECONNECTING
    RemoteBar.CantReach -> cantReach(now.device)
    RemoteBar.Stopped -> playbackStoppedOn(now.device)
    RemoteBar.Loading -> loadingOn(now.device)
    RemoteBar.Reconnecting -> reconnectingTo(now.device)
}

/**
 * What TalkBack reads of the bar, one stop (PtPhoneTalkBack 10, CsPhoneTalkBack 9): "Harbor Lights, season 2, episode 4.
 * Playing on Living room TV, 32 minutes 10 seconds of 52 minutes"; no time while the next episode starts.
 */
fun barSpoken(now: RemoteNow, positionMs: Long): String {
    val length = now.durationMs?.takeIf { now.shows != RemoteBar.NextEpisode }
    val time = length?.let { ", ${spokenTime(positionMs)} of ${spokenTime(it)}" }.orEmpty()
    return "${now.spokenTitle}. ${barLine(now).replace(" · ", ", ")}$time"
}

/** Stop's name, to TalkBack: "Stop playing on Living room TV", "Stop casting to Kitchen display" (CsPhoneTalkBack 8, 11). */
fun stopOn(now: RemoteNow): String = when (now.kind) {
    RemoteKind.Tv -> stopPlayingOn(now.device)
    RemoteKind.Cast -> stopCastingTo(now.device)
}

/** The remote's Stop, as it reads: "Stop" for a TV, "Stop casting" for a Cast device (CsPhoneRemote). */
fun stopWords(now: RemoteNow): String = when (now.kind) {
    RemoteKind.Tv -> "Stop"
    RemoteKind.Cast -> STOP_CASTING
}

/** The remote's time bar, to TalkBack: "Position on the TV", "Position on Kitchen display" (CsPhoneTalkBack 4). */
fun positionOn(now: RemoteNow): String = when (now.kind) {
    RemoteKind.Tv -> POSITION_ON_TV
    RemoteKind.Cast -> "Position on ${now.device}"
}

/** A Cast device loading or buffering (CsPhoneBar, CsPhoneRemote): "Loading on Kitchen display…". */
fun loadingOn(name: String) = "Loading on $name…"

/** The Cast session suspended (CsPhoneBar): "Reconnecting to Kitchen display…". */
fun reconnectingTo(name: String) = "Reconnecting to $name…"

/** The remote's lost box for a cast (CsPhoneRemote): what the device does meanwhile. */
const val CAST_PLAYS_ON = "It plays on there from what it has loaded."

/** What a watch key names ([io.skjaere.usenet.data.WatchKey]): an episode by its show, season and number, or a film. */
internal val EPISODE_KEY = Regex("episode:tmdb:(\\d+):s(\\d+):e(\\d+)")
internal val MOVIE_KEY = Regex("movie:tmdb:(\\d+)")
