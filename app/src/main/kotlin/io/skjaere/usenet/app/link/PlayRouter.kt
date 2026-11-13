package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.app.collection.movieHeader
import io.skjaere.usenet.app.casting.CastDeviceKind
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.collection.movieTarget
import io.skjaere.usenet.app.home.UpNextPlay
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.EpisodeTarget
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.play.PlayOutcome
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage.Play
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * The coordinator's entry points the Show and Movie pages, Up next, Choose release, Search and Import play through on this
 * phone (L7), each from an [origin]: this device's, or a cast's, whose device shows what the phone plays (L80).
 */
interface PlayHere {
    fun playEpisode(ep: EpisodePlay, origin: PlayOrigin)

    /** An episode by its TMDB numbers (Up next's next episode), looked up here. */
    fun playEpisode(target: EpisodeTarget, origin: PlayOrigin)

    fun playMovie(target: MovieTarget, origin: PlayOrigin)

    fun resume(state: WatchState, origin: PlayOrigin)

    /** A release chosen by hand (Choose release, Search): this phone's, or a cast's (W11, L80). */
    fun play(request: PlayRequest, origin: PlayOrigin): Deferred<PlayOutcome>

    /** A file Import opened, [file] of the NZB [nzbId], prepared and stored, cast from [origin] (L147). */
    fun castFile(nzbId: String, file: FileCandidate, origin: PlayOrigin)
}

/** What a phone asks a TV to play (L6, L25): an episode or a movie, and this phone's own place for it ([from], L39). */
data class TvAsk(val episode: Play.Episode? = null, val movie: Play.Movie? = null, val from: Play.From? = null) {
    /** The `Play` for it, numbered [seq]. */
    fun message(seq: Int) = Play(seq, episode, movie, from)

    override fun toString() = "TvAsk(${if (episode != null) "episode" else "movie"})"
}

/** Where a Play for a TV goes: the TV's start, which the phone's play sheet mirrors (L37). */
fun interface PlayOnTv {
    /**
     * Starts a play on [tv], named [title] and [subtitle] while what to ask for is worked out ([ask]: TMDB for Up next's
     * rows). An [ask] that throws ends the start with nothing sent.
     */
    fun start(tv: KeyId, title: String, subtitle: String?, ask: suspend () -> TvAsk)
}

/** What the router runs on: the app's ([of]), or fakes, so it runs in a JVM test. */
class PlayRouterDeps(
    /** Where Play goes now (W11). */
    val target: () -> PlayTarget,
    val here: PlayHere,
    val tv: PlayOnTv,
    /** This phone's row for a watch key, for `from` (L39). */
    val watch: suspend (String) -> WatchState?,
    /** An episode by its TMDB numbers, looked up with this phone's TMDB key (L6). */
    val episode: suspend (EpisodeTarget) -> EpisodePlay,
    /** A movie by its TMDB id, looked up and built as its page builds it, with this phone's [WatchState] of it. */
    val movie: suspend (Int, WatchState?) -> MovieTarget,
    /** Wall-clock milliseconds, the clock the rows were saved by: `from`'s age is told by it (L25). */
    val now: () -> Long,
    /** Asks before a TV can be played on (PtPhonePairFail): pair again, or update. */
    val ask: (PlayOnAsk) -> Unit = {},
    /**
     * The chosen TV's NZStreamer isn't open, or it isn't reached yet; or the chosen Cast device connects: the Play on sheet,
     * where it says so.
     */
    val notOpen: () -> Unit = {},
    /** Whether Cast anyway was told for the Cast session at a route (L122): its speaker isn't asked about again. */
    val castAnywayTold: (String) -> Boolean = { false },
    /** Cast anyway told for the Cast session at a route, for as long as it lasts. */
    val castAnyway: (String) -> Unit = {},
    /** This phone chosen, as its row's tap chooses it: a Cast session ends with it (L85). */
    val choosePhone: () -> Unit = {},
) {
    companion object {
        fun of(c: AppContainer) = PlayRouterDeps(
            target = { c.playOn.target.value },
            here = object : PlayHere {
                override fun playEpisode(ep: EpisodePlay, origin: PlayOrigin) = c.playback.playEpisode(ep, origin = origin)
                override fun playEpisode(target: EpisodeTarget, origin: PlayOrigin) = c.playback.playEpisode(target, origin)
                override fun playMovie(target: MovieTarget, origin: PlayOrigin) = c.playback.playMovie(target, origin)
                override fun resume(state: WatchState, origin: PlayOrigin) = c.playback.resume(state, origin)
                override fun play(request: PlayRequest, origin: PlayOrigin) = c.playback.play(request, origin)
                override fun castFile(nzbId: String, file: FileCandidate, origin: PlayOrigin) = c.playback.castFile(nzbId, file, origin)
            },
            // Made on the first play on a TV: a TV's own pages route through here too, and never play on one.
            tv = PlayOnTv { tv, title, subtitle, ask -> c.tvStart.start(tv, title, subtitle, ask) },
            watch = { key -> c.library.state(key).first() },
            episode = { target ->
                val (show, season) = looked { TmdbLookup.show(c, target.showTmdbId) to TmdbLookup.season(c, target.showTmdbId, target.season) }
                val episode = season.episodes.firstOrNull { it.episode == target.episode } ?: throw TvAskFailed("TMDB doesn't list ${target.label}")
                EpisodePlay.of(show, episode)
            },
            movie = { id, state ->
                val details = looked { TmdbLookup.movie(c, id) }
                val row = movieHeader(null, null, details, state, LocalDate.now()) ?: throw TvAskFailed("TMDB doesn't name it")
                movieTarget(row, details)
            },
            now = System::currentTimeMillis,
            ask = c.playOn::ask,
            notOpen = c.playOn::openSheet,
            castAnywayTold = c.playOn::castAnywayTold,
            castAnyway = c.playOn::castAnyway,
            choosePhone = { c.playOn.choose(PlayChoice.Phone) },
        )
    }
}

/**
 * In front of the coordinator's entry points (L7), for the Show and Movie pages, Home's Up next, Choose release and Search:
 * with This phone chosen they play here as before; with a TV, a `Play` goes there and nothing runs here, so no NZB is
 * grabbed on the phone (C4). Up next's rows are worked out with this phone's TMDB key first, as the coordinator would
 * (L6); a release played without TMDB has nothing a TV could search by, and plays here. Each Play carries this phone's
 * own place for its watch key, with how long ago it was saved (L39). Choose release and Search never reach a TV
 * ([chosen]), nor does Import, which plays here with no launch ([playFile]).
 *
 * With a Cast device connected, each plays here as for This phone, with the cast origin (L80): the phone runs the whole
 * play, Choose release, Search, Import's file and a release played without TMDB too, and the device shows it. While it
 * connects or reconnects nothing plays and the Play on sheet opens, where it says so. A Cast speaker or speaker group
 * plays only the sound, so a Play there asks first, at its tap (W50, L122): Cast anyway runs the same Play there, and the
 * session isn't asked again; Play on this phone chooses This phone and runs it here; Cancel runs nothing.
 *
 * The chosen TV is asked only while it answers (a TV that can't play yet says what it lacks): one set up again, or that
 * removed this phone, asks to pair again first, and an update on either end says so (PtPhonePairFail); one whose
 * NZStreamer isn't open, or not reached yet, sends nothing, and the Play on sheet opens, where it says so and This
 * phone is a tap away.
 */
class PlayRouter(private val deps: PlayRouterDeps) {
    /** The Show page's Play, Resume and episode rows. */
    fun playEpisode(ep: EpisodePlay) = routed { to ->
        when (to) {
            Where.Here -> deps.here.playEpisode(ep, PlayOrigin.Here)
            is Where.Cast -> deps.here.playEpisode(ep, to.origin)
            is Where.Tv -> deps.tv.start(to.key, ep.showName, ep.subtitle) { askEpisode(ep) }
            Where.Nowhere -> Unit
        }
    }

    /** The Movie page's Play, Resume and Play again. */
    fun playMovie(target: MovieTarget) = routed { to ->
        when (to) {
            Where.Here -> deps.here.playMovie(target, PlayOrigin.Here)
            is Where.Cast -> deps.here.playMovie(target, to.origin)
            is Where.Tv -> deps.tv.start(to.key, target.title, target.subtitle) { askMovie(target) }
            Where.Nowhere -> Unit
        }
    }

    /** A row of Up next: what is in progress resumed, or a show's next episode. */
    fun upNext(play: UpNextPlay) {
        when (play) {
            is UpNextPlay.NextEpisode -> {
                val target = EpisodeTarget(play.show.tmdbId, play.show.name, play.episode.season, play.episode.episode)
                routed { to ->
                    when (to) {
                        Where.Here -> deps.here.playEpisode(target, PlayOrigin.Here)
                        is Where.Cast -> deps.here.playEpisode(target, to.origin)
                        is Where.Tv -> deps.tv.start(to.key, target.showName, target.label) { askEpisode(deps.episode(target)) }
                        Where.Nowhere -> Unit
                    }
                }
            }
            is UpNextPlay.Resume -> {
                val state = play.state
                val episode = state.episodeTarget
                val movie = state.movieTmdbId?.takeIf { state.kind == WatchKind.Movie }
                // Played without TMDB: nothing a TV could search by, so with a TV chosen it plays here; a Cast device plays
                // it, as the phone has the release (L80).
                if (!play.goesToTv && deps.target() is PlayTarget.Tv) return deps.here.resume(state, PlayOrigin.Here)
                routed { to ->
                    when (to) {
                        Where.Here -> deps.here.resume(state, PlayOrigin.Here)
                        is Where.Cast -> deps.here.resume(state, to.origin)
                        is Where.Tv -> when {
                            episode != null -> deps.tv.start(to.key, episode.showName, episode.label) { askEpisode(deps.episode(episode), state) }
                            movie != null -> deps.tv.start(to.key, state.title, null) { askMovie(deps.movie(movie, state), state) }
                        }
                        Where.Nowhere -> Unit
                    }
                }
            }
        }
    }

    /**
     * A release chosen by hand (Choose release, Search): on this phone with a TV chosen (W11), on the Cast device with one
     * chosen (L80), the phone having the release. While the device connects nothing plays: Stopped, so its list marks
     * nothing. To a speaker, it comes out as the answer to its question says.
     */
    fun chosen(request: PlayRequest): Deferred<PlayOutcome> = when (deps.target()) {
        PlayTarget.Phone, is PlayTarget.Tv -> deps.here.play(request, PlayOrigin.Here)
        // A Cast device's where is the device, nowhere while it connects (the sheet opened), or where a speaker's answer says.
        is PlayTarget.Cast -> CompletableDeferred<PlayOutcome>().also { outcome ->
            routed { to ->
                when (to) {
                    Where.Here -> outcome.follow(deps.here.play(request, PlayOrigin.Here))
                    is Where.Cast -> outcome.follow(deps.here.play(request, to.origin))
                    is Where.Tv, Where.Nowhere -> outcome.complete(PlayOutcome.Stopped)
                }
            }
        }
    }

    /**
     * Import's Play of [file] in the NZB [nzbId], which Import prepared and stored (L147, W44 (i)): with This phone or a
     * TV chosen it plays [here], as Import always has (a TV can't play this phone's NZB); on the connected Cast device
     * from the cast origin, the coordinator's short play of the file. While the device connects nothing plays and the
     * sheet opens; to a speaker, as the answer to its question says.
     */
    fun playFile(nzbId: String, file: FileCandidate, here: () -> Unit) {
        when (deps.target()) {
            PlayTarget.Phone, is PlayTarget.Tv -> here()
            is PlayTarget.Cast -> routed { to ->
                when (to) {
                    Where.Here -> here()
                    is Where.Cast -> deps.here.castFile(nzbId, file, to.origin)
                    is Where.Tv, Where.Nowhere -> Unit
                }
            }
        }
    }

    /** [play] where a Play goes now; for a Cast speaker, where the answer to its question says, once it is answered. */
    private fun routed(play: (Where) -> Unit) {
        where(play)?.let(play)
    }

    /** Where a Play goes now. */
    private sealed interface Where {
        data object Here : Where

        data class Tv(val key: KeyId) : Where

        /** The connected Cast device [to]: this phone runs the play from the cast [origin], and the device shows it (L80). */
        data class Cast(val to: CastTo) : Where {
            val origin: PlayOrigin get() = PlayOrigin(cast = to)
        }

        /** The chosen TV asked first, or isn't open, or the chosen Cast device connects: nothing plays. */
        data object Nowhere : Where
    }

    /**
     * This phone; the chosen TV while it answers; the connected Cast device, for which this phone plays (L80). Otherwise a
     * TV asks first, or the sheet opens, and nothing plays. A Cast speaker or speaker group asks first, unless its session
     * was told Cast anyway (L122): none for now, and the answer runs the Play where it says ([then]).
     */
    private fun where(then: (Where) -> Unit): Where? = when (val target = deps.target()) {
        PlayTarget.Phone -> Where.Here
        is PlayTarget.Tv -> when (target.state) {
            // One that can't play yet is asked all the same, and says what it lacks (D2PhoneTvStartFail).
            TvState.Ready, TvState.NeedsProvider, TvState.NeedsIndexer, TvState.NeedsService, TvState.ServiceOff, TvState.NeedsTorrentIndexer -> Where.Tv(target.key)
            TvState.SetUpAgain -> Where.Nowhere.also { deps.ask(PlayOnAsk.SetUpAgain(target.key, target.name)) }
            TvState.RemovedHere -> Where.Nowhere.also { deps.ask(PlayOnAsk.Removed(target.key, target.name)) }
            TvState.UpdateTv -> Where.Nowhere.also { deps.ask(PlayOnAsk.Update(tv = true, name = target.name)) }
            TvState.UpdatePhone -> Where.Nowhere.also { deps.ask(PlayOnAsk.Update(tv = false, name = target.name)) }
            null, TvState.NotOpen -> Where.Nowhere.also { deps.notOpen() }
        }
        is PlayTarget.Cast -> when (target.link) {
            CastLink.Connected -> {
                // The device's Cast id and model go with the play, for its profile (L153).
                val there = Where.Cast(CastTo(target.route, target.name, target.device?.id, target.device?.model))
                if (target.soundOnly && !deps.castAnywayTold(target.route)) null.also { deps.ask(soundOnly(target, there, then)) } else there
            }
            CastLink.Connecting, CastLink.Reconnecting -> Where.Nowhere.also { deps.notOpen() }
        }
    }

    /**
     * The question for a Play to the speaker [target] (L122): Cast anyway remembers it for the session and plays [there];
     * Play on this phone chooses This phone and plays here; Cancel plays nowhere. Each answer runs [then].
     */
    private fun soundOnly(target: PlayTarget.Cast, there: Where.Cast, then: (Where) -> Unit) = PlayOnAsk.SoundOnly(
        target.route, target.name,
        castAnyway = {
            deps.castAnyway(target.route)
            then(there)
        },
        playHere = {
            deps.choosePhone()
            then(Where.Here)
        },
        cancel = { then(Where.Nowhere) },
    )

    /** [ep] as the TV is asked for it, from this phone's row for it, else [shown]'s (the row Up next read). */
    private suspend fun askEpisode(ep: EpisodePlay, shown: WatchState? = null) = TvAsk(episode = PlayWire.episode(ep), from = from(ep.key, shown))

    private suspend fun askMovie(target: MovieTarget, shown: WatchState? = null) = TvAsk(movie = PlayWire.movie(target), from = from(WatchKey.movie(target.tmdbId), shown))

    private suspend fun from(key: String, shown: WatchState?): Play.From? = PlayWire.from(deps.watch(key) ?: shown, deps.now())
}

/**
 * What a phone sends for a play (L6, L25), within what the TV takes (L32): its search's fields, words cut to 200
 * characters at a character's edge, at most 10 aliases, and an id, IMDb id or still path the TV would refuse left out,
 * so a Play is never refused for a field a search can do without.
 */
internal object PlayWire {
    fun episode(ep: EpisodePlay) = Play.Episode(
        showTmdbId = ep.showTmdbId, showName = cut(ep.showName), showYear = ep.showYear, aliases = ep.aliases.take(MAX_ALIASES).map(::cut),
        tvdb = id(ep.ids.tvdb), tmdb = id(ep.ids.tmdb), imdb = imdb(ep.ids.imdb), tvmaze = id(ep.ids.tvmaze), season = ep.season,
        episode = ep.episode, episodeName = ep.episodeName?.let(::cut), airDate = ep.airDate?.toString(), runtimeMinutes = ep.runtimeMinutes,
        stillPath = ep.stillPath?.takeIf(STILL::matches), aired = ep.aired, originalLanguage = ep.originalLanguage?.let(::cut),
        seasonEpisodes = ep.seasonEpisodes,
    )

    fun movie(t: MovieTarget) = Play.Movie(
        t.tmdbId, cut(t.title), t.year, imdb(t.imdb), t.runtimeMinutes, t.releaseDate?.toString(), t.released, t.originalLanguage?.let(::cut),
    )

    /**
     * This phone's place in [state], saved how long before [now]: a row marked unwatched since (a tombstone) is a place of
     * nothing, as new as the mark; a row saved by a clock ahead of [now] is as new as now. Null without a row.
     */
    fun from(state: WatchState?, now: Long): Play.From? = state?.let {
        Play.From(if (it.deleted) 0 else it.positionMs, watched = !it.deleted && it.watched, ageMs = (now - it.updatedAt).coerceAtLeast(0))
    }

    private fun id(id: Int?) = id?.takeIf { it > 0 }

    private fun imdb(id: String?) = id?.takeIf(IMDB::matches)

    /** [s] in at most [MAX_TEXT] characters, never half of a pair. */
    private fun cut(s: String): String {
        if (s.length <= MAX_TEXT) return s
        val end = if (Character.isHighSurrogate(s[MAX_TEXT - 1])) MAX_TEXT - 1 else MAX_TEXT
        return s.substring(0, end)
    }

    private const val MAX_TEXT = 200
    private const val MAX_ALIASES = 10
    private val IMDB = Regex("tt[0-9]{7,9}")
    private val STILL = Regex("/[A-Za-z0-9_-]+\\.(jpg|png)")
}

/**
 * Whether a row of Up next goes to a TV chosen in Play on (L6): a next episode, or a Resume of an episode or a movie, which
 * a TV can search for by their ids. A release played without TMDB plays on this phone, whatever is chosen. Up next's play
 * button asks it too, so it never says a TV for a play that stays here.
 */
val UpNextPlay.goesToTv: Boolean
    get() = when (this) {
        is UpNextPlay.NextEpisode -> true
        is UpNextPlay.Resume -> state.episodeTarget != null || (state.kind == WatchKind.Movie && state.movieTmdbId != null)
    }

/** A Cast speaker or speaker group, as its session's route says (W50): it plays only the sound. */
private val PlayTarget.Cast.soundOnly: Boolean get() = device?.kind == CastDeviceKind.Speaker || device?.kind == CastDeviceKind.Group

/** [this], as [play] comes out: the coordinator's outcome, which completes, never fails. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun CompletableDeferred<PlayOutcome>.follow(play: Deferred<PlayOutcome>) {
    play.invokeOnCompletion { cause -> complete(if (cause == null) play.getCompleted() else PlayOutcome.Stopped) }
}

/** [this] state's episode, by its show's TMDB numbers; null for a movie or a release. */
private val WatchState.episodeTarget get() = WatchTarget(kind, title, showTmdbId, season, episode, movieTmdbId).episodeTarget

/** What to ask a TV for couldn't be worked out, for a reason in the app's own [words] (TMDB doesn't list the episode). */
class TvAskFailed(val words: String) : Exception(words)

/** [lookup] on TMDB, its failure in the coordinator's words for one (lookUpAndPlay's): "Couldn't look it up on TMDB: …". */
private suspend fun <T> looked(lookup: suspend () -> T): T = try {
    lookup()
} catch (e: CancellationException) {
    throw e
} catch (e: TmdbException) {
    throw TvAskFailed("Couldn't look it up on TMDB: ${e.error.message}")
}
