package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.settings.NoUsableProvider
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.link.Playback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A play a paired phone asks the TV for (L6, L8): an episode, played as the TV's own Show page plays it, or a movie, as
 * its Movie page does. [label] is how the TV's words name it ("Harbor Lights S02E04", "Harbor Bell"); [watchKey] the key
 * the TV keeps its place under.
 */
sealed interface AskedPlay {
    val title: String
    val subtitle: String?
    val label: String
    val watchKey: String

    data class Episode(val episode: EpisodePlay) : AskedPlay {
        override val title: String get() = episode.showName
        override val subtitle: String get() = episode.subtitle
        override val label: String get() = episode.watch.title
        override val watchKey: String get() = episode.key
    }

    data class Movie(val movie: MovieTarget) : AskedPlay {
        override val title: String get() = movie.title
        override val subtitle: String? get() = movie.subtitle
        override val label: String get() = movie.title
        override val watchKey: String get() = WatchKey.movie(movie.tmdbId)
    }
}

/**
 * The player in front, as Play on TV's commands reach it (L8): PlayerActivity's while it is started, called on the main
 * thread. Pause, Resume and SeekTo go through its media session's player, as a headset's do; SeekBy joins the controls'
 * burst of seeks; Stop leaves the player, as the remote's Stop does. [handOver] closes it for a play a phone asked for,
 * as the next episode's hand-over does, whatever it shows. [remote] is the phone's session it plays in, if any.
 */
interface PlayerHandle {
    val remote: RemotePlay?

    /** The file's length, once the player knows it. */
    val durationMs: Long?

    fun pause()

    fun resume()

    fun seekTo(positionMs: Long)

    fun seekBy(deltaMs: Long)

    fun stop()

    fun handOver(play: AskedPlay, origin: PlayOrigin)
}

/**
 * What Play on TV's commands ask of a player that isn't made yet (L8): the handle is up as the player starts, before its
 * setup has read its settings (and, on a TV, before the screen has switched to the file's frame rate, which can take
 * seconds). Pause and Resume, the last one asked; a SeekTo, and the SeekBys since. The player takes them as it is made,
 * so a command acked meanwhile isn't lost. On the main thread, as the handle is called.
 */
class HeldCommands {
    private var playing: Boolean? = null
    private var to: Long? = null
    private var by = 0L

    fun pause() {
        playing = false
    }

    fun resume() {
        playing = true
    }

    fun seekTo(positionMs: Long) {
        to = positionMs
        by = 0
    }

    fun seekBy(deltaMs: Long) {
        by += deltaMs
    }

    /** Whether the player plays as it is made: as the last Pause or Resume asked, else as it would. */
    fun playWhenReady(otherwise: Boolean = true): Boolean = playing ?: otherwise

    /** Where the player starts, made to start at [start]: a SeekTo's place, else [start], moved by the SeekBys since; never before 0. */
    fun startAt(start: Long): Long = ((to ?: start) + by).coerceAtLeast(0)

    /** Taken by the player made: the next one starts afresh. */
    fun clear() {
        playing = null
        to = null
        by = 0
    }
}

/**
 * What the player in front plays, as it says each second while started (L34), for Play on TV's status: its [state],
 * where it is ([positionMs], where a burst of seeks goes) and its length, its titles, the watch key (none for a file
 * opened in Import), the "Playback stopped" panel's words as a phone is told them ([failure], L37), and the phone's
 * session. No art crosses. It prints as its state alone: a title reaches no log by a slip (L13).
 */
data class NowPlaying(
    val state: Playback,
    val positionMs: Long,
    val durationMs: Long?,
    val title: String,
    val subtitle: String?,
    val watchKey: String?,
    val failure: String?,
    val remote: RemotePlay?,
) {
    override fun toString() = "NowPlaying($state)"

    companion object {
        /**
         * The player's state as a phone is told it: [failed] while "Playback stopped" shows; buffering until the player is
         * [made]; then playing, paused, or buffering while it waits to play. At the end of its file ([ended]) it is paused
         * there: the next-episode card may wait at it for Play now, or the next episode is about to start in the same
         * session, so the session goes on. Stopped is the session's end alone (L33, L34), told once the player has left.
         */
        fun state(failed: Boolean, made: Boolean, ended: Boolean, isPlaying: Boolean, playWhenReady: Boolean): Playback = when {
            failed -> Playback.Failed
            !made -> Playback.Buffering
            ended -> Playback.Paused
            isPlaying -> Playback.Playing
            !playWhenReady -> Playback.Paused
            else -> Playback.Buffering
        }
    }
}

/**
 * A play the sheet sent its launch for, its player yet to start (L34): in the phone's session [remote], or the TV's own
 * (null). Until the player says what it plays, the play is on its way, not over.
 */
data class Launching(val remote: RemotePlay?)

/**
 * The player in front (L8, L34): its handle while it is started, and what it last said it plays; nothing once it stops.
 * And the play whose launch is on its way to a player ([launching]), from the sheet's launch until a player takes it.
 */
class PlayerInFront {
    private val _handle = MutableStateFlow<PlayerHandle?>(null)
    val handle: StateFlow<PlayerHandle?> = _handle.asStateFlow()

    private val _now = MutableStateFlow<NowPlaying?>(null)
    val now: StateFlow<NowPlaying?> = _now.asStateFlow()

    private val _launching = MutableStateFlow<Launching?>(null)
    val launching: StateFlow<Launching?> = _launching.asStateFlow()

    /** The sheet sent the launch of a play in [remote]'s session (null: the TV's own): its player is to come. */
    fun launched(remote: RemotePlay?) {
        _launching.value = Launching(remote)
    }

    /** No launch is on its way: another play started, or the one sent was dropped unseen. */
    fun notLaunching() {
        _launching.value = null
    }

    /** [handle]'s player has started: it is the one in front. A launch it took is on its way until it says what it plays. */
    fun started(handle: PlayerHandle) {
        _handle.value = handle
        _now.value = null
    }

    /**
     * What [handle]'s player plays now, while it is the one in front. Once it says the play of the launch on its way, that
     * launch has arrived: as the player that took it starts, or as one already started takes it (the window's player).
     */
    fun said(handle: PlayerHandle, now: NowPlaying) {
        if (_handle.value !== handle) return
        _now.value = now
        if (_launching.value?.remote == now.remote) _launching.value = null
    }

    /** [handle]'s player stopped or leaves; another started since is left alone. */
    fun stopped(handle: PlayerHandle) {
        if (_handle.value !== handle) return
        _handle.value = null
        _now.value = null
    }
}

/**
 * A paired phone asked this TV to play [asked] before it can (L32, PtTvNotReady, D2TvNotReady): the phone's [phone] name,
 * and what the TV lacks first (L224). The sheet offers "Set up with your phone" and the page for what is [missing] (for a
 * service that is off, that page first, L248); the TV doesn't keep the request.
 */
data class NotReady(val phone: String, val asked: String, val missing: Missing) {
    /** Usenet's two parts, or the debrid way's three, when only that way is under way on the TV. */
    sealed interface Missing {
        data object Provider : Missing

        data object Indexer : Missing

        data object Service : Missing

        /**
         * A service saved, none on: switched off, its key refused or unreadable. [refusedBy] names the service whose key
         * was refused, for this sheet alone: the phone is never told it (L248).
         */
        data class ServiceOff(val refusedBy: String? = null) : Missing

        data object TorrentIndexer : Missing
    }

    val text: String
        get() = "$phone asked to play $asked. " + when (missing) {
            Missing.Provider -> "Set up a Usenet provider first."
            Missing.Indexer -> "Add an indexer first: this TV searches its own."
            Missing.Service -> "Set up a debrid service first."
            is Missing.ServiceOff -> missing.refusedBy?.let { "$it refused this TV's API key: replace it in Settings › Debrid services." }
                ?: "Your debrid service is off: switch it on in Settings › Debrid services."
            Missing.TorrentIndexer -> "Add a torrent indexer first: this TV searches its own."
        }

    /** The button that opens the page for what is missing, beside "Set up with your phone". */
    val add: String
        get() = when (missing) {
            Missing.Provider -> "Add a Usenet provider"
            Missing.Indexer -> "Add an indexer"
            Missing.Service -> "Add a debrid service"
            is Missing.ServiceOff -> NoUsableProvider.SERVICES_ACTION
            Missing.TorrentIndexer -> "Add a torrent indexer"
        }
}
