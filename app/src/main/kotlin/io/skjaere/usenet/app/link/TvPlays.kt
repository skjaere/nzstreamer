package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.play.AskedPlay
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.Launching
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.play.NotReady
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerHandle
import io.skjaere.usenet.app.play.RemotePlay
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Ack
import io.skjaere.usenet.link.LinkMessage.Play
import io.skjaere.usenet.link.LinkMessage.Rejected
import io.skjaere.usenet.link.Rejection
import io.skjaere.usenet.newznab.TvIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** A paired phone asking over its link: its key, and the name the TV keeps it under (Settings › Phones). */
data class AskingPhone(val key: KeyId, val name: String) {
    override fun toString() = "AskingPhone(${key.short})"
}

/** What the TV does with a paired phone's commands (L8, L32), each answered over its link by [answer]. */
fun interface TvCommands {
    fun received(phone: AskingPhone, command: LinkMessage.Command, answer: (LinkMessage) -> Unit)

    companion object {
        /** Each command told the TV doesn't do it: a TV's listener with nothing to play through (the JVM tests'). */
        val Unsupported = TvCommands { _, command, answer -> answer(Rejected(command.seq, Rejection.Unsupported)) }
    }
}

/** The coordinator as Play on TV's commands start and stop plays through it, on the main thread. */
interface TvStarts {
    /** Whether the sheet is starting a play now: searching, trying releases, or asking which file plays. */
    val starting: Boolean

    /** The paired phone's session of the play on the sheet, starting or showing why it didn't; null for none, or the TV's own. */
    val session: RemotePlay?

    /** Starts [play] as from the TV's own Show or Movie page, the sheet's play before it stopped. */
    fun play(play: AskedPlay, origin: PlayOrigin)

    fun cancel()

    /** Shows [failure] on the sheet: a phone's Play the TV can't start yet. */
    fun tell(failure: PlayState.Failed)
}

/** What the TV's plays for phones run on: the app's ([of]), or fakes, so they run in a JVM test. */
class TvPlaysDeps(
    val starts: TvStarts,
    /** The player in front, while one is started. */
    val player: () -> PlayerHandle?,
    /** "Set up with your phone" is started, or a pairing question shows (L32). */
    val busy: () -> Boolean,
    /** Whether the TV can play a phone's play now, by Usenet or by its debrid service, else what it lacks first (L224). */
    val ready: suspend () -> TvReadiness,
    /** The TV's own row for a watch key, for where a phone's play starts (L39). */
    val tvRow: suspend (String) -> WatchState?,
    /** Wall-clock milliseconds, the clock the TV's rows were saved by. */
    val now: () -> Long,
    /** Monotonic milliseconds, the clock the Play limits count by. */
    val clock: () -> Long,
    val newSession: () -> String = { UUID.randomUUID().toString() },
    /** Where the commands are worked through, one after another: the main thread, as the coordinator and the player are. */
    val context: CoroutineContext = Dispatchers.Main,
    /** A play whose launch the sheet sent, its player yet to start (L34); null for none. */
    val launching: () -> Launching? = { null },
    /** The player that starts for [launching]'s launch, once it is in front; null if none does in a few seconds. */
    val arriving: suspend () -> PlayerHandle? = { null },
) {
    companion object {
        /** How long a command waits for the player a launch on its way starts: it is made a moment after the sheet closes. */
        const val ARRIVING_MS = 3_000L

        fun of(c: AppContainer) = TvPlaysDeps(
            starts = object : TvStarts {
                override val starting: Boolean get() = c.playback.startingPlay
                override val session: RemotePlay? get() = c.playback.sheetSession

                override fun play(play: AskedPlay, origin: PlayOrigin) = when (play) {
                    is AskedPlay.Episode -> c.playback.playEpisode(play.episode, origin = origin)
                    is AskedPlay.Movie -> c.playback.playMovie(play.movie, origin)
                }

                override fun cancel() = c.playback.cancel()

                override fun tell(failure: PlayState.Failed) = c.playback.tell(failure)
            },
            player = { c.playerInFront.handle.value },
            launching = { c.playerInFront.launching.value },
            arriving = { withTimeoutOrNull(ARRIVING_MS) { c.playerInFront.handle.filterNotNull().first() } },
            busy = { c.setupPages.open || c.tvLink.asking },
            ready = { TvReadiness.of(TvSetup.read(c.settings), DebridGate.shown) },
            tvRow = { key -> c.library.state(key).first() },
            now = System::currentTimeMillis,
            clock = android.os.SystemClock::elapsedRealtime,
        )
    }
}

/**
 * The TV's side of a phone's commands (L8, L18, L32). A Play is checked first (anything outside [PlayChecks] is
 * `unsupported`), held to its own limit per phone (one every 3 s, 10 in 10 minutes: each is a search on the TV's
 * indexers), refused `busy-setup` while "Set up with your phone" is started or a pairing question shows, and refused for
 * what the TV lacks first while it can play neither by Usenet nor by its debrid service ([TvReadiness], L224), which
 * the TV's sheet then says (PtTvNotReady, D2TvNotReady) unless a play starts or plays here. Otherwise it starts a session
 * of its own: handed over to the player in front, whatever it shows, or to the one a launch on its way starts, else
 * started as from the TV's own Show or Movie page, replacing what the sheet was starting. The last to ask wins. It starts
 * where the phone's place says, when that is newer than the TV's own (L39).
 *
 * Cancel and the transport commands act only on the session now, and only for the phone whose session it is (any other
 * gets `not-current`): the player's, else the one the sheet starts; while its launch is on its way, the player it starts.
 * SeekTo is clamped to the play's length and SeekBy to six hours either way. Each answered command is acked with its
 * session.
 */
class TvPlays(private val deps: TvPlaysDeps, scope: CoroutineScope? = null) : TvCommands {
    private class Incoming(val phone: AskingPhone, val command: LinkMessage.Command, val answer: (LinkMessage) -> Unit)

    private val incoming = Channel<Incoming>(QUEUE)

    /** Each phone's recent Plays that were taken, by the monotonic clock, oldest first. */
    private val plays = HashMap<KeyId, ArrayDeque<Long>>()

    init {
        // One after another, on the main thread: a Cancel never overtakes the Play it cancels.
        scope?.launch(deps.context) {
            for (each in incoming) {
                try {
                    each.answer(answer(each.phone, each.command))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Unanswered: the phone gives up on it in its own time. Never the command's words (L13).
                    log.warn("Play on TV: a phone's {} failed", each.command::class.simpleName, e)
                }
            }
        }
    }

    /** From a link's thread: worked through in turn; a phone that floods the queue loses what doesn't fit. */
    override fun received(phone: AskingPhone, command: LinkMessage.Command, answer: (LinkMessage) -> Unit) {
        incoming.trySend(Incoming(phone, command, answer))
    }

    /** What [phone]'s [command] does here, and the answer to send it. */
    suspend fun answer(phone: AskingPhone, command: LinkMessage.Command): LinkMessage = when (command) {
        is Play -> play(phone, command)
        is LinkMessage.Cancel -> cancel(phone, command.seq, command.session)
        is LinkMessage.Pause -> transport(phone, command.seq, command.session) { it.pause() }
        is LinkMessage.Resume -> transport(phone, command.seq, command.session) { it.resume() }
        is LinkMessage.SeekTo -> transport(phone, command.seq, command.session) { it.seekTo(command.positionMs.coerceIn(0, it.durationMs ?: Long.MAX_VALUE)) }
        is LinkMessage.SeekBy -> transport(phone, command.seq, command.session) { it.seekBy(command.deltaMs.coerceIn(-MAX_SEEK_BY_MS, MAX_SEEK_BY_MS)) }
        is LinkMessage.Stop -> transport(phone, command.seq, command.session) { it.stop() }
        else -> Rejected(command.seq, Rejection.Unsupported)
    }

    private suspend fun play(phone: AskingPhone, command: Play): LinkMessage {
        val asked = PlayChecks.asked(command) ?: return Rejected(command.seq, Rejection.Unsupported)
        if (!take(phone.key)) return Rejected(command.seq, Rejection.TooFast)
        if (deps.busy()) return Rejected(command.seq, Rejection.BusySetup)
        val ready = deps.ready()
        val missing = missing(ready)
        val rejection = ready.rejection
        if (missing != null && rejection != null) {
            // Over a play starting or playing here, the TV's sheet would take its place: that play goes on. One whose
            // launch is on its way would open its player over the sheet.
            if (deps.player() == null && !deps.starts.starting && deps.launching() == null) {
                deps.starts.tell(PlayState.Failed(asked.title, asked.subtitle, emptyList(), notTried = 0, notReady = NotReady(phone.name, asked.label, missing)))
            }
            return Rejected(command.seq, rejection)
        }
        val start = PlayFrom.start(command.from, command.from?.let { tvRow(asked.watchKey) }, deps.now())
        val session = deps.newSession()
        val origin = PlayOrigin(start = start, remote = RemotePlay(session, phone.key.hex, phone.name))
        // A launch on its way opens its player whatever the sheet starts meanwhile: the Play waits for it and takes its place.
        val player = deps.player() ?: deps.launching()?.let { deps.arriving() }
        if (player != null) player.handOver(asked, origin) else deps.starts.play(asked, origin)
        log.info("Play on TV: a phone's play started, {}", if (player != null) "handed over by the player" else "on the sheet")
        return Ack(command.seq, session)
    }

    /** What the TV's sheet says it lacks for a phone's play (D2TvNotReady), a refused key's service named there alone (L248). */
    private fun missing(ready: TvReadiness): NotReady.Missing? = when (ready.lacks) {
        TvReadiness.Lack.Provider -> NotReady.Missing.Provider
        TvReadiness.Lack.Indexer -> NotReady.Missing.Indexer
        TvReadiness.Lack.Service -> NotReady.Missing.Service
        TvReadiness.Lack.ServiceOff -> NotReady.Missing.ServiceOff(ready.refusedBy)
        TvReadiness.Lack.TorrentIndexer -> NotReady.Missing.TorrentIndexer
        null -> null
    }

    /** The TV's own row for [key]; none when it can't be read, so the phone's place counts, and the play still starts. */
    private suspend fun tvRow(key: String): WatchState? = try {
        deps.tvRow(key)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Play on TV: couldn't read this TV's place for a phone's play", e)
        null
    }

    /** Cancel: the session's play stops, its player leaving or the sheet's start cancelled. */
    private suspend fun cancel(phone: AskingPhone, seq: Int, session: String): LinkMessage {
        val player = player(phone, session)
        when {
            player != null -> if (owns(phone, player.remote, session)) player.stop() else return Rejected(seq, Rejection.NotCurrent)
            owns(phone, deps.starts.session, session) -> deps.starts.cancel()
            else -> return Rejected(seq, Rejection.NotCurrent)
        }
        return Ack(seq, session)
    }

    /** A transport command, for the session's player alone. */
    private suspend fun transport(phone: AskingPhone, seq: Int, session: String, act: (PlayerHandle) -> Unit): LinkMessage {
        val player = player(phone, session)?.takeIf { owns(phone, it.remote, session) } ?: return Rejected(seq, Rejection.NotCurrent)
        act(player)
        return Ack(seq, session)
    }

    /**
     * The player in front; else, while [phone]'s [session] has its launch on its way (the sheet has closed, and its
     * player is yet to start), that player once it starts, so a Pause or Stop in that moment isn't lost. Null for none.
     */
    private suspend fun player(phone: AskingPhone, session: String): PlayerHandle? =
        deps.player() ?: deps.launching()?.takeIf { owns(phone, it.remote, session) }?.let { deps.arriving() }

    /**
     * Whether [play] is [session], [phone]'s own. Every paired phone hears a session's id (L34: its state, untitled; the
     * hello names it), so the id alone proves nothing: only the phone that asked for the play acts on it.
     */
    private fun owns(phone: AskingPhone, play: RemotePlay?, session: String): Boolean = play?.session == session && play.phone == phone.key.hex

    /** Whether [phone] may start another play now (L24); one that may is counted. */
    private fun take(phone: KeyId): Boolean {
        val now = deps.clock()
        val times = plays.getOrPut(phone) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() >= PLAY_WINDOW_MS) times.removeFirst()
        if (times.isNotEmpty() && now - times.last() < PLAY_GAP_MS) return false
        if (times.size >= PLAYS_PER_WINDOW) return false
        times.addLast(now)
        return true
    }

    private companion object {
        val log = LoggerFactory.getLogger(TvPlays::class.java)

        /** Commands waiting to be worked through: a phone sends 20 a second at most (L24). */
        const val QUEUE = 64

        /** One Play every 3 s, 10 in 10 minutes, per phone (L24). */
        const val PLAY_GAP_MS = 3_000L
        const val PLAY_WINDOW_MS = 600_000L
        const val PLAYS_PER_WINDOW = 10

        /** SeekBy's reach either way (L32). */
        const val MAX_SEEK_BY_MS = 6 * 60 * 60 * 1_000L
    }
}

/**
 * L32's checks of a phone's Play, before anything is done with it: an episode or a movie, not both; TMDB, TVDB and TVmaze
 * ids above 0; an IMDb id of "tt" and 7 to 9 digits; season 0-999 and episode 1-9999; strings of at most 200 characters
 * and at most 10 aliases; a still path of "/", then letters, digits, "_" or "-", then ".jpg" or ".png"; dates in ISO
 * 8601. What passes is what the TV plays, as its own pages would ask for it; anything else is null.
 */
internal object PlayChecks {
    fun asked(play: Play): AskedPlay? {
        val episode = play.episode
        val movie = play.movie
        return when {
            episode != null && movie == null -> episode(episode)?.let(AskedPlay::Episode)
            movie != null && episode == null -> movie(movie)?.let(AskedPlay::Movie)
            else -> null
        }
    }

    private fun episode(e: Play.Episode): EpisodePlay? {
        val fine = e.showTmdbId > 0 && id(e.tvdb) && id(e.tmdb) && id(e.tvmaze) && imdb(e.imdb) && e.season in 0..999 && e.episode in 1..9_999 &&
            text(e.showName) && text(e.episodeName) && text(e.airDate) && text(e.originalLanguage) && e.aliases.size <= MAX_ALIASES &&
            e.aliases.all(::text) && e.stillPath.let { it == null || STILL.matches(it) }
        if (!fine) return null
        val aired = e.airDate?.let { date(it) ?: return null }
        return EpisodePlay(
            showTmdbId = e.showTmdbId, showName = e.showName, showYear = e.showYear, aliases = e.aliases,
            ids = TvIds(tvdb = e.tvdb, tmdb = e.tmdb, imdb = e.imdb, tvmaze = e.tvmaze), season = e.season, episode = e.episode,
            episodeName = e.episodeName, airDate = aired, runtimeMinutes = e.runtimeMinutes, stillPath = e.stillPath, aired = e.aired,
            originalLanguage = e.originalLanguage, seasonEpisodes = e.seasonEpisodes,
        )
    }

    private fun movie(m: Play.Movie): MovieTarget? {
        if (m.tmdbId <= 0 || !imdb(m.imdb) || !text(m.title) || !text(m.releaseDate) || !text(m.originalLanguage)) return null
        val date = m.releaseDate?.let { date(it) ?: return null }
        return MovieTarget(m.tmdbId, m.title, m.year, m.imdb, m.runtimeMinutes, date, m.released, m.originalLanguage)
    }

    private fun id(id: Int?) = id == null || id > 0

    private fun imdb(id: String?) = id == null || IMDB.matches(id)

    private fun text(s: String?) = s == null || s.length <= MAX_TEXT

    private fun date(s: String): LocalDate? = try {
        LocalDate.parse(s)
    } catch (_: DateTimeParseException) {
        null
    }

    private const val MAX_TEXT = 200
    private const val MAX_ALIASES = 10
    private val IMDB = Regex("tt[0-9]{7,9}")
    private val STILL = Regex("/[A-Za-z0-9_-]+\\.(jpg|png)")
}
