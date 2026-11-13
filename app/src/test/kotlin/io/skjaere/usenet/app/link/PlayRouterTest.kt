package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.casting.CastDeviceFacts
import io.skjaere.usenet.app.casting.CastDeviceKind
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastLinkNow
import io.skjaere.usenet.app.home.UpNextPlay
import io.skjaere.usenet.app.home.UpNextRow
import io.skjaere.usenet.app.home.playsOn
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.EpisodeTarget
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.play.PlayOutcome
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.LinkFile
import io.skjaere.usenet.data.LinkStore
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage.Play
import io.skjaere.usenet.metadata.EpisodeRef
import io.skjaere.usenet.newznab.TvIds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L7, L6, L80: Play, Resume and Up next go where Play on says (W11). With This phone the coordinator plays as today; with a
 * TV a `Play` goes there and nothing runs here: no coordinator call, no NZB grabbed. Up next's rows are worked out with
 * this phone's TMDB key first, and a release played without TMDB plays here. Choose release plays here with a TV chosen.
 * With a Cast device connected, each runs here with the cast origin, a release played without TMDB and Choose release
 * too: the phone runs the play and the device shows it; while it connects nothing plays and the sheet opens. `from` is
 * the phone's own place for the watch key, with how long ago it was saved. With a Cast speaker or speaker group connected,
 * a Play asks first (W50, L122): Cast anyway plays it there, and the session isn't asked again; Play on this phone
 * chooses This phone and plays it here; Cancel plays nothing. The titles and devices are invented.
 */
class PlayRouterTest {
    private val now = 1_800_000_000_000L
    private val living = KeyId.of(Random.nextBytes(91))
    private var target: PlayTarget = PlayTarget.Phone
    private val here = mutableListOf<String>()
    private val onTv = mutableListOf<Triple<KeyId, String, String?>>()
    private val asks = mutableListOf<TvAsk>()
    private val rows = mutableMapOf<String, WatchState>()
    private val lookups = mutableListOf<String>()
    private val grabs = AtomicInteger()
    private val asked = mutableListOf<PlayOnAsk>()
    private var sheets = 0
    private val realGrab = PlaybackCoordinator.fetchNzb

    private val kitchen = CastTo("route:kitchen", "Kitchen display")
    private val hallway = CastTo("route:hallway", "Hallway speaker", device = "device:hallway", model = "Google Nest Mini")
    private val speaker = CastDeviceFacts("device:hallway", "Google Nest Mini", null, CastDeviceKind.Speaker, fixedVolume = false)

    /** The Cast sessions told Cast anyway, as Play on keeps them; and how many times This phone was chosen for a Play. */
    private val told = mutableSetOf<String>()
    private var phoneChosen = 0

    /** Where each play was asked from: nothing for this device's own, the Cast device's name for a cast. */
    private fun from(origin: PlayOrigin) = when (origin) {
        PlayOrigin.Here -> ""
        PlayOrigin(cast = kitchen) -> " cast to ${kitchen.name}"
        PlayOrigin(cast = hallway) -> " cast to ${hallway.name}"
        else -> " from $origin"
    }

    private val coordinator = object : io.skjaere.usenet.app.link.PlayHere {
        override fun playEpisode(ep: EpisodePlay, origin: PlayOrigin) { here += "episode ${ep.label}${from(origin)}" }
        override fun playEpisode(target: EpisodeTarget, origin: PlayOrigin) { here += "episode target ${target.label}${from(origin)}" }
        override fun playMovie(target: MovieTarget, origin: PlayOrigin) { here += "movie ${target.title}${from(origin)}" }
        override fun resume(state: WatchState, origin: PlayOrigin) { here += "resume ${state.key}${from(origin)}" }
        override fun play(request: PlayRequest, origin: PlayOrigin): Deferred<PlayOutcome> {
            here += "release ${request.title}${from(origin)}"
            return CompletableDeferred(PlayOutcome.Launched)
        }
        override fun castFile(nzbId: String, file: FileCandidate, origin: PlayOrigin) { here += "file ${file.name} of $nzbId${from(origin)}" }
    }

    private val ep = EpisodePlay(
        showTmdbId = 4_101, showName = "Harbor Lights", showYear = 2021, aliases = listOf("Harbour Lights"),
        ids = TvIds(tvdb = 5_202, tmdb = 4_101, imdb = "tt7000123", tvmaze = 6_303), season = 2, episode = 5, episodeName = "The Long Tide",
        airDate = LocalDate.of(2025, 10, 2), runtimeMinutes = 43, stillPath = "/harbor-205.jpg", aired = true, originalLanguage = "en", seasonEpisodes = 10,
    )

    private val movie = MovieTarget(7_707, "North Quay", 2023, "tt9000456", 118, LocalDate.of(2023, 3, 9), released = true, originalLanguage = "en")

    private val router = PlayRouter(
        PlayRouterDeps(
            target = { target },
            here = coordinator,
            tv = PlayOnTv { tv, title, subtitle, ask ->
                onTv += Triple(tv, title, subtitle)
                asks += runBlocking { ask() }
            },
            watch = { key -> rows[key] },
            episode = { t ->
                lookups += "episode ${t.showTmdbId} ${t.label}"
                if (t.showTmdbId == ep.showTmdbId && t.season == 2 && t.episode == 5) ep else error("TMDB doesn't list it")
            },
            movie = { id, _ ->
                lookups += "movie $id"
                if (id == movie.tmdbId) movie else error("TMDB doesn't list it")
            },
            now = { now },
            ask = { asked += it },
            notOpen = { sheets++ },
            castAnywayTold = { it in told },
            castAnyway = { told += it },
            choosePhone = {
                phoneChosen++
                target = PlayTarget.Phone
            },
        ),
    )

    private fun tvChosen(state: TvState? = TvState.Ready) {
        target = PlayTarget.Tv(living, "Living room TV", state)
    }

    private fun castChosen(link: CastLink = CastLink.Connected) {
        target = PlayTarget.Cast(kitchen.route, kitchen.name, link)
    }

    /** Hallway speaker connected, a speaker or a speaker group ([kind]), as its session's route says. */
    private fun speakerChosen(kind: CastDeviceKind = CastDeviceKind.Speaker) {
        target = PlayTarget.Cast(hallway.route, hallway.name, CastLink.Connected, speaker.copy(kind = kind))
    }

    /** The speaker question the last Play asked. */
    private fun soundOnly() = asked.last() as PlayOnAsk.SoundOnly

    private val handPicked = PlayRequest("Harbor Lights", "S02E05", emptyList(), watch = WatchTarget(WatchKind.Episode, "Harbor Lights S02E05"), chosen = true)

    init {
        PlaybackCoordinator.fetchNzb = { _, _, _ -> grabs.incrementAndGet(); error("never grabbed") }
    }

    @AfterEach
    fun restore() {
        PlaybackCoordinator.fetchNzb = realGrab
    }

    private fun episodeRow(positionMs: Long, watched: Boolean, savedAgo: Long, deleted: Boolean = false) = WatchState(
        ep.key, WatchKind.Episode, showTmdbId = ep.showTmdbId, season = 2, episode = 5, title = "Harbor Lights S02E05",
        positionMs = positionMs, durationMs = 2_580_000, watched = watched, lastPlayedAt = now - savedAgo, updatedAt = now - savedAgo, deleted = deleted,
    )

    private fun movieRow(positionMs: Long, watched: Boolean, savedAgo: Long) = WatchState(
        WatchKey.movie(movie.tmdbId), WatchKind.Movie, movieTmdbId = movie.tmdbId, title = "North Quay",
        positionMs = positionMs, durationMs = 7_080_000, watched = watched, lastPlayedAt = now - savedAgo, updatedAt = now - savedAgo,
    )

    @Test
    fun `with This phone each Play goes to the coordinator as today`() {
        val resumed = movieRow(4_320_000, watched = false, savedAgo = 60_000)
        router.playEpisode(ep)
        router.playMovie(movie)
        router.upNext(UpNextPlay.Resume(resumed))
        router.upNext(UpNextPlay.NextEpisode(CollectedShow(CollectedShow.idFor(4_101), 4_101, name = "Harbor Lights", addedAt = 0, updatedAt = 0), EpisodeRef(2, 6)))
        assertEquals(listOf("episode S02E05", "movie North Quay", "resume ${resumed.key}", "episode target S02E06"), here)
        assertTrue(onTv.isEmpty())
        assertTrue(lookups.isEmpty(), "nothing looked up for this phone's own plays: the coordinator does that")
    }

    @Test
    fun `a TV target makes no coordinator call and no grab, and the Show page's Play goes there with every field a search needs`() {
        tvChosen()
        router.playEpisode(ep)
        assertTrue(here.isEmpty(), "no coordinator call: $here")
        assertEquals(0, grabs.get(), "no NZB grabbed")
        assertEquals(listOf<Triple<KeyId, String, String?>>(Triple(living, "Harbor Lights", "S02E05 · The Long Tide")), onTv)
        val sent = asks.single()
        assertNull(sent.movie)
        assertEquals(
            Play.Episode(
                showTmdbId = 4_101, showName = "Harbor Lights", showYear = 2021, aliases = listOf("Harbour Lights"), tvdb = 5_202, tmdb = 4_101,
                imdb = "tt7000123", tvmaze = 6_303, season = 2, episode = 5, episodeName = "The Long Tide", airDate = "2025-10-02", runtimeMinutes = 43,
                stillPath = "/harbor-205.jpg", aired = true, originalLanguage = "en", seasonEpisodes = 10,
            ),
            sent.episode,
        )
        assertNull(sent.from, "no place here for it")
    }

    @Test
    fun `a movie goes to the TV as its page built it`() {
        tvChosen()
        router.playMovie(movie)
        assertTrue(here.isEmpty())
        assertEquals(listOf<Triple<KeyId, String, String?>>(Triple(living, "North Quay", "2023")), onTv)
        assertEquals(
            Play.Movie(7_707, "North Quay", 2023, "tt9000456", 118, "2023-03-09", released = true, originalLanguage = "en"),
            asks.single().movie,
        )
    }

    @Test
    fun `Up next's next episode is looked up with this phone's TMDB key and goes to the TV`() {
        tvChosen()
        router.upNext(UpNextPlay.NextEpisode(CollectedShow(CollectedShow.idFor(4_101), 4_101, name = "Harbor Lights", addedAt = 0, updatedAt = 0), EpisodeRef(2, 5)))
        assertTrue(here.isEmpty())
        assertEquals(listOf("episode 4101 S02E05"), lookups)
        assertEquals(listOf<Triple<KeyId, String, String?>>(Triple(living, "Harbor Lights", "S02E05")), onTv)
        assertEquals(5, asks.single().episode?.episode)
    }

    @Test
    fun `Up next's Resume of an episode or a movie is looked up and goes to the TV from where it stopped`() {
        tvChosen()
        val episode = episodeRow(1_930_000, watched = false, savedAgo = 90_000)
        val film = movieRow(4_320_000, watched = false, savedAgo = 3_600_000)
        router.upNext(UpNextPlay.Resume(episode))
        router.upNext(UpNextPlay.Resume(film))
        assertTrue(here.isEmpty())
        assertEquals(listOf("episode 4101 S02E05", "movie 7707"), lookups)
        assertEquals(listOf<Triple<KeyId, String, String?>>(Triple(living, "Harbor Lights", "S02E05"), Triple(living, "North Quay", null)), onTv)
        assertEquals(Play.From(1_930_000, watched = false, ageMs = 90_000), asks[0].from)
        assertEquals(Play.From(4_320_000, watched = false, ageMs = 3_600_000), asks[1].from)
        assertEquals(movie.tmdbId, asks[1].movie?.tmdbId)
    }

    @Test
    fun `a release played without TMDB plays on this phone, a TV chosen or not`() {
        tvChosen()
        val release = WatchState(WatchKey.release("abc", 0), WatchKind.Release, title = "Some.Home.Video.2019", positionMs = 60_000, updatedAt = now)
        router.upNext(UpNextPlay.Resume(release))
        assertEquals(listOf("resume ${release.key}"), here)
        assertTrue(onTv.isEmpty())
    }

    @Test
    fun `Up next's play button says and greys for the TV only where its row goes there`() {
        val notOpen = PlayTarget.Tv(living, "Living room TV", TvState.NotOpen)
        val release = WatchState(WatchKey.release("abc", 0), WatchKind.Release, title = "Some.Home.Video.2019", positionMs = 60_000, updatedAt = now)
        fun row(play: UpNextPlay) = UpNextRow("Some.Home.Video.2019", "30 min left", 0.4f, new = false, posterPath = null, play = play, playLabel = "Resume Some.Home.Video.2019")
        // Played without TMDB, it plays on this phone whatever is chosen (L6): its button says so, and never greys.
        assertEquals(null, row(UpNextPlay.Resume(release)).playsOn(notOpen))
        // An episode, a movie, a next episode: on the TV, greyed while it isn't open.
        val episode = WatchState(WatchKey.episode(4_101, 2, 5), WatchKind.Episode, showTmdbId = 4_101, season = 2, episode = 5, title = "Harbor Lights S02E05", positionMs = 60_000, updatedAt = now)
        val film = WatchState(WatchKey.movie(movie.tmdbId), WatchKind.Movie, movieTmdbId = movie.tmdbId, title = "North Quay", positionMs = 60_000, updatedAt = now)
        assertEquals(notOpen, row(UpNextPlay.Resume(episode)).playsOn(notOpen))
        assertEquals(notOpen, row(UpNextPlay.Resume(film)).playsOn(notOpen))
        assertEquals(null, row(UpNextPlay.Resume(episode)).playsOn(PlayTarget.Phone))
        assertEquals(true, row(UpNextPlay.Resume(episode)).playsOn(notOpen)?.greyed)
    }

    @Test
    fun `Up next's play button says a Cast device for every row, and greys while it connects`() {
        val connected = PlayTarget.Cast("route:kitchen", "Kitchen display", CastLink.Connected)
        val release = WatchState(WatchKey.release("abc", 0), WatchKind.Release, title = "Some.Home.Video.2019", positionMs = 60_000, updatedAt = now)
        val episode = WatchState(WatchKey.episode(4_101, 2, 5), WatchKind.Episode, showTmdbId = 4_101, season = 2, episode = 5, title = "Harbor Lights S02E05", positionMs = 60_000, updatedAt = now)
        fun row(play: UpNextPlay) = UpNextRow("Harbor Lights", "S02E04 · Low Water", null, new = false, posterPath = null, play = play, playLabel = "Play Harbor Lights S02E04")
        val next = UpNextPlay.NextEpisode(CollectedShow(CollectedShow.idFor(4_101), 4_101, name = "Harbor Lights", addedAt = 0, updatedAt = 0), EpisodeRef(2, 4))
        // The phone has the release, so a release played without TMDB casts too (L80), as an episode and a next episode do.
        assertEquals(connected, row(UpNextPlay.Resume(release)).playsOn(connected))
        assertEquals(connected, row(UpNextPlay.Resume(episode)).playsOn(connected))
        assertEquals(connected, row(next).playsOn(connected))
        assertEquals(false, row(next).playsOn(connected)?.greyed)
        for (waiting in listOf(CastLink.Connecting, CastLink.Reconnecting)) {
            assertEquals(true, row(UpNextPlay.Resume(release)).playsOn(connected.copy(link = waiting))?.greyed, "$waiting")
        }
    }

    @Test
    fun `Choose release plays on this phone with a TV chosen`() = runBlocking<Unit> {
        tvChosen()
        val chosen = PlayRequest("Harbor Lights", "S02E05", emptyList(), watch = WatchTarget(WatchKind.Episode, "Harbor Lights S02E05"), chosen = true)
        assertEquals(PlayOutcome.Launched, router.chosen(chosen).await())
        assertEquals(listOf("release Harbor Lights"), here)
        assertTrue(onTv.isEmpty())
    }

    @Test
    fun `from is this phone's place for Play, Resume and Play again, with how long ago it was saved`() {
        tvChosen()
        // Play: an episode begun 2 minutes ago.
        rows[ep.key] = episodeRow(1_930_000, watched = false, savedAgo = 120_000)
        router.playEpisode(ep)
        assertEquals(Play.From(1_930_000, watched = false, ageMs = 120_000), asks.last().from)
        // Resume on the Movie page.
        rows[WatchKey.movie(movie.tmdbId)] = movieRow(4_320_000, watched = false, savedAgo = 86_400_000)
        router.playMovie(movie)
        assertEquals(Play.From(4_320_000, watched = false, ageMs = 86_400_000), asks.last().from)
        // Play again: watched, so from the start unless the TV's own place is newer (L39).
        rows[WatchKey.movie(movie.tmdbId)] = movieRow(0, watched = true, savedAgo = 5_000)
        router.playMovie(movie)
        assertEquals(Play.From(0, watched = true, ageMs = 5_000), asks.last().from)
        // Marked unwatched here: a place of nothing, as new as the mark.
        rows[ep.key] = episodeRow(1_930_000, watched = false, savedAgo = 30_000, deleted = true)
        router.playEpisode(ep)
        assertEquals(Play.From(0, watched = false, ageMs = 30_000), asks.last().from)
        // A row saved by a clock ahead of this one is as new as now.
        rows[ep.key] = episodeRow(1_930_000, watched = false, savedAgo = -60_000)
        router.playEpisode(ep)
        assertEquals(Play.From(1_930_000, watched = false, ageMs = 0), asks.last().from)
    }

    @Test
    fun `what the TV would refuse isn't sent, aliases to 10, words to 200, a still or IMDb id of another shape left out`() {
        tvChosen()
        val long = "x".repeat(250)
        router.playEpisode(
            ep.copy(
                aliases = (1..14).map { "Alias $it" }, episodeName = long, stillPath = "/odd path.webp",
                ids = ep.ids.copy(imdb = "nm0000001", tvdb = 0),
            ),
        )
        val sent = asks.single().episode!!
        assertEquals((1..10).map { "Alias $it" }, sent.aliases)
        assertEquals(200, sent.episodeName?.length)
        assertNull(sent.stillPath)
        assertNull(sent.imdb)
        assertNull(sent.tvdb, "an id of 0 is none")
    }

    @Test
    fun `a lookup that fails fails the TV's start, and nothing plays here`() {
        tvChosen()
        val unknown = WatchState(WatchKey.episode(9_999, 1, 1), WatchKind.Episode, showTmdbId = 9_999, season = 1, episode = 1, title = "Copper Crown S01E01", updatedAt = now)
        val failed = mutableListOf<Throwable>()
        val router = PlayRouter(
            PlayRouterDeps(
                target = { target }, here = coordinator,
                tv = PlayOnTv { _, _, _, ask -> failed += assertFailsWith<IllegalStateException> { runBlocking { ask() } } },
                watch = { null }, episode = { error("TMDB doesn't list it") }, movie = { _, _ -> error("TMDB doesn't list it") }, now = { now },
            ),
        )
        router.upNext(UpNextPlay.Resume(unknown))
        assertEquals(1, failed.size)
        assertTrue(here.isEmpty())
    }

    @Test
    fun `a TV that answers is asked, one that needs setting up too, as it says what it lacks`() {
        for (state in listOf(TvState.Ready, TvState.NeedsProvider, TvState.NeedsIndexer, TvState.NeedsService, TvState.ServiceOff, TvState.NeedsTorrentIndexer)) {
            tvChosen(state)
            router.playEpisode(ep)
        }
        assertEquals(6, asks.size)
        assertTrue(asked.isEmpty() && sheets == 0)
    }

    @Test
    fun `a TV set up again, or that removed this phone, asks to pair again, and nothing is sent`() {
        tvChosen(TvState.SetUpAgain)
        router.playEpisode(ep)
        tvChosen(TvState.RemovedHere)
        router.playMovie(movie)
        tvChosen(TvState.UpdateTv)
        router.upNext(UpNextPlay.NextEpisode(CollectedShow(CollectedShow.idFor(4_101), 4_101, name = "Harbor Lights", addedAt = 0, updatedAt = 0), EpisodeRef(2, 5)))
        tvChosen(TvState.UpdatePhone)
        router.playEpisode(ep)
        assertEquals(
            listOf(
                PlayOnAsk.SetUpAgain(living, "Living room TV"), PlayOnAsk.Removed(living, "Living room TV"),
                PlayOnAsk.Update(tv = true, name = "Living room TV"), PlayOnAsk.Update(tv = false, name = "Living room TV"),
            ),
            asked,
        )
        assertTrue(onTv.isEmpty() && here.isEmpty() && lookups.isEmpty())
    }

    @Test
    fun `a TV whose NZStreamer isn't open, or not reached yet, sends nothing and opens the Play on sheet`() {
        tvChosen(TvState.NotOpen)
        router.playEpisode(ep)
        tvChosen(null)
        router.upNext(UpNextPlay.Resume(movieRow(4_320_000, watched = false, savedAgo = 60_000)))
        assertEquals(2, sheets)
        assertTrue(onTv.isEmpty() && here.isEmpty() && asked.isEmpty())
    }

    @Test
    fun `with a Cast device connected each Play runs here with the cast origin, Up next's rows included`() {
        castChosen()
        val resumed = movieRow(4_320_000, watched = false, savedAgo = 60_000)
        val release = WatchState(WatchKey.release("abc", 0), WatchKind.Release, title = "Some.Home.Video.2019", positionMs = 60_000, updatedAt = now)
        router.playEpisode(ep)
        router.playMovie(movie)
        router.upNext(UpNextPlay.Resume(resumed))
        router.upNext(UpNextPlay.NextEpisode(CollectedShow(CollectedShow.idFor(4_101), 4_101, name = "Harbor Lights", addedAt = 0, updatedAt = 0), EpisodeRef(2, 6)))
        // Played without TMDB: the phone has the release, so the device plays it too (L80).
        router.upNext(UpNextPlay.Resume(release))
        assertEquals(
            listOf(
                "episode S02E05 cast to Kitchen display", "movie North Quay cast to Kitchen display", "resume ${resumed.key} cast to Kitchen display",
                "episode target S02E06 cast to Kitchen display", "resume ${release.key} cast to Kitchen display",
            ),
            here,
        )
        assertTrue(onTv.isEmpty())
        assertTrue(lookups.isEmpty(), "the coordinator looks up, as for a play here")
        assertTrue(asked.isEmpty() && sheets == 0)
    }

    /**
     * L125 (CsPhoneWatchedNext 3): the Watched line's button plays Up next's next episode as its row does, on the device
     * still connected and chosen, with the cast origin, so the same ranking and pre-check apply.
     */
    @Test
    fun `the Watched line's button plays the next episode on the connected device`() {
        castChosen()
        val next = UpNextPlay.NextEpisode(CollectedShow(CollectedShow.idFor(4_101), 4_101, name = "Harbor Lights", addedAt = 0, updatedAt = 0), EpisodeRef(2, 5))
        val line = RemoteSaid("Watched on Kitchen display", 1, RemoteKind.Cast, RemoteSaidAction("Play S02E05 on Kitchen display", next))
        router.upNext(line.action!!.play)
        assertEquals(listOf("episode target S02E05 cast to Kitchen display"), here)
        assertTrue(onTv.isEmpty() && sheets == 0)
    }

    /** L153: the router's CastTo, the only one made, carries the device's Cast id and model, so the play's job finds its profile. */
    @Test
    fun `the router's CastTo has the device's id and model`() = runBlocking<Unit> {
        val facts = CastDeviceFacts("device-kitchen", "Chromecast Ultra", "192.168.1.31", CastDeviceKind.Video, fixedVolume = false)
        target = PlayTarget.Cast(kitchen.route, kitchen.name, CastLink.Connected, facts)
        val origins = mutableListOf<PlayOrigin>()
        val router = PlayRouter(
            PlayRouterDeps(
                target = { target },
                here = object : io.skjaere.usenet.app.link.PlayHere {
                    override fun playEpisode(ep: EpisodePlay, origin: PlayOrigin) { origins += origin }
                    override fun playEpisode(target: EpisodeTarget, origin: PlayOrigin) { origins += origin }
                    override fun playMovie(target: MovieTarget, origin: PlayOrigin) { origins += origin }
                    override fun resume(state: WatchState, origin: PlayOrigin) { origins += origin }
                    override fun play(request: PlayRequest, origin: PlayOrigin): Deferred<PlayOutcome> = CompletableDeferred(PlayOutcome.Launched).also { origins += origin }
                    override fun castFile(nzbId: String, file: FileCandidate, origin: PlayOrigin) { origins += origin }
                },
                tv = PlayOnTv { _, _, _, _ -> error("never a TV") },
                watch = { null }, episode = { error("not looked up") }, movie = { _, _ -> error("not looked up") }, now = { now },
            ),
        )
        router.playMovie(movie)
        router.chosen(PlayRequest("Harbor Lights", "S02E05", emptyList(), watch = WatchTarget(WatchKind.Episode, "Harbor Lights S02E05"), chosen = true)).await()
        val known = CastTo(kitchen.route, kitchen.name, device = "device-kitchen", model = "Chromecast Ultra")
        assertEquals(listOf(PlayOrigin(cast = known), PlayOrigin(cast = known)), origins)
        // A device whose route said nothing of it (none read yet): the play goes there all the same, its profile unknown.
        target = PlayTarget.Cast(kitchen.route, kitchen.name, CastLink.Connected)
        router.playMovie(movie)
        assertEquals(PlayOrigin(cast = kitchen), origins.last())
    }

    @Test
    fun `a release chosen by hand casts with a Cast device chosen`() = runBlocking<Unit> {
        castChosen()
        val chosen = PlayRequest("Harbor Lights", "S02E05", emptyList(), watch = WatchTarget(WatchKind.Episode, "Harbor Lights S02E05"), chosen = true)
        assertEquals(PlayOutcome.Launched, router.chosen(chosen).await())
        assertEquals(listOf("release Harbor Lights cast to Kitchen display"), here)
    }

    /** A file Import opened (L147): its NZB prepared and stored, a video in it. */
    private val imported = FileCandidate(0, StreamableFile("Copper.Crown.2026.1080p.mkv", 1_400_000_000, 0, 0, 0, 0))

    /** W44 (i), L147: Import's file plays here, as before, with This phone or a TV chosen: a TV can't play an NZB of this phone's. */
    @Test
    fun `Import's file plays here with This phone or a TV chosen`() {
        var played = 0
        router.playFile("nzb-copper", imported) { played++ }
        tvChosen()
        router.playFile("nzb-copper", imported) { played++ }
        tvChosen(TvState.NotOpen)
        router.playFile("nzb-copper", imported) { played++ }
        assertEquals(3, played)
        assertTrue(here.isEmpty() && onTv.isEmpty() && asked.isEmpty())
        assertEquals(0, sheets)
    }

    /** L147: with a Cast device connected, Import's file casts, the coordinator's short play from the cast origin. */
    @Test
    fun `Import's file casts to the connected Cast device`() {
        castChosen()
        router.playFile("nzb-copper", imported) { throw AssertionError("never here") }
        assertEquals(listOf("file Copper.Crown.2026.1080p.mkv of nzb-copper cast to Kitchen display"), here)
    }

    /** While the device connects or reconnects, Import's file plays nowhere and the list opens, where it says so. */
    @Test
    fun `Import's file plays nowhere while the Cast device connects, and the list opens`() {
        for (link in listOf(CastLink.Connecting, CastLink.Reconnecting)) {
            castChosen(link)
            router.playFile("nzb-copper", imported) { throw AssertionError("never here") }
        }
        assertEquals(2, sheets)
        assertTrue(here.isEmpty())
    }

    @Test
    fun `a Cast device still connecting plays nothing and opens the Play on sheet`() = runBlocking<Unit> {
        val release = WatchState(WatchKey.release("abc", 0), WatchKind.Release, title = "Some.Home.Video.2019", positionMs = 60_000, updatedAt = now)
        val chosen = PlayRequest("Harbor Lights", "S02E05", emptyList(), watch = WatchTarget(WatchKind.Episode, "Harbor Lights S02E05"), chosen = true)
        for (link in listOf(CastLink.Connecting, CastLink.Reconnecting)) {
            castChosen(link)
            router.playEpisode(ep)
            router.playMovie(movie)
            router.upNext(UpNextPlay.Resume(release))
            assertEquals(PlayOutcome.Stopped, router.chosen(chosen).await(), "nothing played: the list marks nothing")
        }
        assertEquals(8, sheets)
        assertTrue(here.isEmpty() && onTv.isEmpty() && asked.isEmpty())
    }

    /** W50, L122: with a Cast speaker or speaker group connected, every Play asks first, at the tap; nothing plays or is looked up. */
    @Test
    fun `a Play with a speaker or a speaker group connected asks first and plays nothing`() = runBlocking<Unit> {
        for (kind in listOf(CastDeviceKind.Speaker, CastDeviceKind.Group)) {
            speakerChosen(kind)
            router.playEpisode(ep)
            assertEquals("Hallway speaker", soundOnly().name)
            assertEquals(hallway.route, soundOnly().route)
        }
        router.playMovie(movie)
        router.upNext(UpNextPlay.NextEpisode(CollectedShow(CollectedShow.idFor(4_101), 4_101, name = "Harbor Lights", addedAt = 0, updatedAt = 0), EpisodeRef(2, 6)))
        router.playFile("nzb-copper", imported) { throw AssertionError("never here before the answer") }
        val outcome = router.chosen(handPicked)
        assertEquals(6, asked.size)
        assertTrue(asked.all { it is PlayOnAsk.SoundOnly })
        assertTrue(here.isEmpty() && onTv.isEmpty() && lookups.isEmpty() && sheets == 0)
        assertFalse(outcome.isCompleted, "the list's outcome waits for the answer")
        // Its words (L149), and none of them printed.
        assertEquals("Hallway speaker plays only the sound", soundOnlyTitle("Hallway speaker"))
        assertEquals("It still gets the whole video from this phone, picture included.", SOUND_ONLY_TEXT)
        assertEquals(listOf("Cast anyway", "Play on this phone", "Cancel"), PlayOnAsk.SoundOnly.Answer.entries.map { it.words })
        assertFalse("Hallway" in soundOnly().toString())
        assertNull(soundOnly().key)
        // Cancel: nothing plays, and the list's outcome is that nothing did.
        soundOnly().answered(PlayOnAsk.SoundOnly.Answer.Cancel)
        assertEquals(PlayOutcome.Stopped, outcome.await())
        assertTrue(here.isEmpty() && told.isEmpty() && phoneChosen == 0)
    }

    /** L122: Cast anyway runs the same Play on the speaker, and is remembered: the session isn't asked again. */
    @Test
    fun `Cast anyway plays there and the session isn't asked again`() = runBlocking<Unit> {
        speakerChosen()
        val outcome = router.chosen(handPicked)
        soundOnly().answered(PlayOnAsk.SoundOnly.Answer.CastAnyway)
        assertEquals(PlayOutcome.Launched, outcome.await())
        assertEquals(setOf(hallway.route), told)
        router.playEpisode(ep)
        router.playFile("nzb-copper", imported) { throw AssertionError("never here") }
        assertEquals(1, asked.size)
        assertEquals(
            listOf("release Harbor Lights cast to Hallway speaker", "episode S02E05 cast to Hallway speaker", "file Copper.Crown.2026.1080p.mkv of nzb-copper cast to Hallway speaker"),
            here,
        )
        // A screen is never asked.
        castChosen()
        router.playMovie(movie)
        assertEquals(1, asked.size)
    }

    /** L122: Play on this phone chooses This phone (ending the session, L85) and runs the same Play here. */
    @Test
    fun `Play on this phone chooses This phone and plays here`() = runBlocking<Unit> {
        speakerChosen()
        val outcome = router.chosen(handPicked)
        soundOnly().answered(PlayOnAsk.SoundOnly.Answer.PlayHere)
        assertEquals(1, phoneChosen)
        assertEquals(PlayOutcome.Launched, outcome.await())
        speakerChosen()
        router.playEpisode(ep)
        soundOnly().answered(PlayOnAsk.SoundOnly.Answer.PlayHere)
        // Import's file plays here as Import plays one.
        speakerChosen()
        var played = 0
        router.playFile("nzb-copper", imported) { played++ }
        soundOnly().answered(PlayOnAsk.SoundOnly.Answer.PlayHere)
        assertEquals(listOf("release Harbor Lights", "episode S02E05"), here)
        assertEquals(1, played)
        assertEquals(3, phoneChosen)
        assertTrue(told.isEmpty())
    }

    /** L122: Cast anyway lasts its session alone: a new session with the speaker asks again (Play on keeps it). */
    @Test
    fun `a new session with the speaker asks again`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = MutableStateFlow<CastLinkNow?>(CastLinkNow(hallway.route, hallway.name, CastLink.Connected, speaker))
            val playOn = PlayOn(scope, LinkStore(MemoryStore(LinkFile())).tvs, MutableStateFlow(emptyMap()), session) { AutoCloseable {} }
            val asks = mutableListOf<PlayOnAsk>()
            val router = PlayRouter(
                PlayRouterDeps(
                    target = { target }, here = coordinator, tv = PlayOnTv { _, _, _, _ -> error("never a TV") }, watch = { null },
                    episode = { error("not looked up") }, movie = { _, _ -> error("not looked up") }, now = { now }, ask = { asks += it },
                    castAnywayTold = playOn::castAnywayTold, castAnyway = playOn::castAnyway,
                ),
            )
            speakerChosen()
            router.playMovie(movie)
            (asks.single() as PlayOnAsk.SoundOnly).answered(PlayOnAsk.SoundOnly.Answer.CastAnyway)
            router.playMovie(movie)
            assertEquals(1, asks.size, "not asked again in that session")
            // The session ends; a new one with the same speaker asks again.
            session.value = null
            eventually("Cast anyway forgotten with its session") { !playOn.castAnywayTold(hallway.route) }
            session.value = CastLinkNow(hallway.route, hallway.name, CastLink.Connected, speaker)
            router.playMovie(movie)
            assertEquals(2, asks.size)
            assertEquals(listOf("movie North Quay cast to Hallway speaker", "movie North Quay cast to Hallway speaker"), here)
        } finally {
            scope.cancel()
        }
    }
}
