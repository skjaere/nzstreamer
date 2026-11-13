package io.skjaere.usenet.app.home

import io.skjaere.usenet.app.collection.model.ShowRows
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.collection.model.aggregateOf
import io.skjaere.usenet.app.collection.model.toSummary
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeRef
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpNextTest {
    private val today = LocalDate.of(2026, 10, 14)
    private val release = RememberedRelease("fake", "guid", "Harbor.Lights.S02E05.1080p.WEB.h264-GRP", null, 0, "nzb")

    private fun show(id: Int, name: String, addedAt: Long = 0) = CollectedShow(CollectedShow.idFor(id), id, name = name, posterPath = "/p$id.jpg", addedAt = addedAt, updatedAt = 0)

    /** Two seasons of ten episodes, all aired; S02E10 aired [lastAired]. */
    private fun details(id: Int, name: String, lastAired: LocalDate = LocalDate.of(2025, 3, 1)) = ShowDetails(
        id, name, null, 2021, null, "/d$id.jpg", null, "Returning Series", true, 45,
        listOf(SeasonSummary(1, null, 10, null, null), SeasonSummary(2, null, 10, null, null)),
        lastEpisode = EpisodeSummary(2, 10, "Finale $id", lastAired), nextEpisode = null, ids = ExternalIds(),
    )

    private fun season(id: Int, number: Int, aired: LocalDate = LocalDate.of(2025, 1, 1)) = Season(
        id, number, null, null, (1..10).map { Episode(number, it, "Episode $number.$it", null, aired, 45, null) },
    )

    /** [season], each episode with a still of its own: "/s<show>-<season>-<episode>.jpg". */
    private fun withStills(season: Season) = season.copy(episodes = season.episodes.map { it.copy(stillPath = "/s${season.showTmdbId}-${it.season}-${it.episode}.jpg") })

    private fun episode(show: Int, name: String, s: Int, e: Int, positionMs: Long = 0, durationMs: Long = 2_700_000, watched: Boolean = false, playedAt: Long = 0) =
        WatchState(
            WatchKey.episode(show, s, e), WatchKind.Episode, showTmdbId = show, season = s, episode = e, title = WatchTitle.episode(name, s, e),
            positionMs = positionMs, durationMs = durationMs, watched = watched, lastPlayedAt = playedAt, release = release, updatedAt = playedAt,
        )

    private fun movie(id: Int, title: String, positionMs: Long, playedAt: Long) = WatchState(
        WatchKey.movie(id), WatchKind.Movie, movieTmdbId = id, title = title, positionMs = positionMs, durationMs = 6_000_000,
        lastPlayedAt = playedAt, release = release, updatedAt = playedAt,
    )

    private fun releaseOnly(title: String, positionMs: Long, playedAt: Long) = WatchState(
        WatchKey.release("n-$title", 0), WatchKind.Release, title = title, positionMs = positionMs, durationMs = 3_000_000,
        lastPlayedAt = playedAt, release = release, updatedAt = playedAt,
    )

    /**
     * Up next as Home builds it: the collected shows as the store's rows (TMDB's answers for them as
     * stored facts, their states as the library's aggregate), what is in progress from the recent list
     * and from the collected shows' states; [hidden], the keys of the rows taken out of it.
     */
    private fun rows(
        inProgress: List<WatchState> = emptyList(),
        shows: List<CollectedShow> = emptyList(),
        states: Map<Int, List<WatchState>> = emptyMap(),
        tmdb: TmdbAnswers = TmdbAnswers(),
        hasKey: Boolean = true,
        hidden: Set<String> = emptySet(),
    ): List<UpNextRow> {
        val collected = ShowRows.build(
            shows,
            shows.mapNotNull { tmdb.shows[it.tmdbId]?.toSummary() }.associateBy { it.tmdbId },
            shows.mapNotNull { aggregateOf(it.tmdbId, states[it.tmdbId].orEmpty()) }.associateBy { it.showTmdbId },
            emptyMap(), today, hasKey,
        )
        val inProgressOfCollected = states.values.flatten().filter { !it.watched && it.positionMs >= LibraryRepository.MIN_RESUME_MS }
        return UpNext.rows((inProgress + inProgressOfCollected).distinctBy { it.key }, collected, tmdb, hasKey, today, hidden)
    }

    @Test
    fun `rows in progress come first, most recently played first, then the next episodes of collected shows`() {
        val harbor = show(1, "Harbor Lights")
        val orbit = show(2, "The Quiet Orbit")
        val inProgress = listOf(
            episode(1, "Harbor Lights", 2, 5, positionMs = 900_000, playedAt = 50),
            movie(10, "Copper Crown", positionMs = 600_000, playedAt = 80),
        )
        val tmdb = TmdbAnswers(shows = mapOf(1 to details(1, "Harbor Lights"), 2 to details(2, "The Quiet Orbit")))
        val pilot = episode(2, "The Quiet Orbit", 1, 1, watched = true, playedAt = 5)
        val rows = rows(inProgress, listOf(harbor, orbit), mapOf(1 to inProgress.take(1), 2 to listOf(pilot)), tmdb)
        assertEquals(listOf("Copper Crown", "Harbor Lights", "The Quiet Orbit"), rows.map { it.title })
        assertEquals(listOf(UpNextPlay.Resume(inProgress[1]), UpNextPlay.Resume(inProgress[0]), UpNextPlay.NextEpisode(orbit, EpisodeRef(1, 2))), rows.map { it.play })
        assertEquals(listOf("Resume Copper Crown", "Resume Harbor Lights S02E05", "Play The Quiet Orbit S01E02"), rows.map { it.playLabel })
    }

    @Test
    fun `a show appears once, its episode in progress ahead of its next episode`() {
        val harbor = show(1, "Harbor Lights")
        val older = episode(1, "Harbor Lights", 1, 3, positionMs = 900_000, playedAt = 10)
        val newer = episode(1, "Harbor Lights", 2, 5, positionMs = 900_000, playedAt = 20)
        val watched = episode(1, "Harbor Lights", 1, 2, watched = true, playedAt = 5)
        val rows = rows(listOf(newer, older), listOf(harbor), mapOf(1 to listOf(watched, older, newer)), TmdbAnswers(shows = mapOf(1 to details(1, "Harbor Lights"))))
        assertEquals(listOf(UpNextPlay.Resume(newer)), rows.map { it.play })
    }

    @Test
    fun `an episode in progress the recent list missed still counts for its show`() {
        val harbor = show(1, "Harbor Lights")
        val started = episode(1, "Harbor Lights", 1, 4, positionMs = 900_000, playedAt = 10)
        val rows = rows(emptyList(), listOf(harbor), mapOf(1 to listOf(started)), TmdbAnswers(shows = mapOf(1 to details(1, "Harbor Lights"))))
        assertEquals(listOf(UpNextPlay.Resume(started)), rows.map { it.play })
    }

    /** Marks don't say when the show was watched, so shows only marked come last, the one added most recently first. */
    @Test
    fun `next episodes follow the most recently watched show first`() {
        val shows = listOf(
            show(1, "Marked, added lately", addedAt = 300), show(2, "Watched long ago", addedAt = 100), show(3, "Watched lately", addedAt = 50),
            show(4, "Marked, added long ago", addedAt = 200),
        )
        val states = mapOf(
            1 to listOf(episode(1, "Marked, added lately", 1, 1, watched = true)),
            2 to listOf(episode(2, "Watched long ago", 1, 1, watched = true, playedAt = 10)),
            3 to listOf(episode(3, "Watched lately", 1, 1, watched = true, playedAt = 90), episode(3, "Watched lately", 1, 2, watched = true, playedAt = 95)),
            4 to listOf(episode(4, "Marked, added long ago", 1, 1, watched = true)),
        )
        val tmdb = TmdbAnswers(shows = shows.associate { it.tmdbId to details(it.tmdbId, it.name) })
        val rows = rows(shows = shows, states = states, tmdb = tmdb)
        assertEquals(listOf("Watched lately", "Watched long ago", "Marked, added lately", "Marked, added long ago"), rows.map { it.title })
        assertEquals(listOf(EpisodeRef(1, 3), EpisodeRef(1, 2), EpisodeRef(1, 2), EpisodeRef(1, 2)), rows.map { (it.play as UpNextPlay.NextEpisode).episode })
        assertEquals(listOf(null, null, null, null), rows.map { it.progress })
    }

    @Test
    fun `a show that is up to date or not loaded from TMDB has no next episode`() {
        val upToDate = show(1, "Up to date")
        val loading = show(2, "Loading")
        val failed = show(3, "Failed")
        val all = (1..10).flatMap { listOf(episode(1, "Up to date", 1, it, watched = true, playedAt = 5), episode(1, "Up to date", 2, it, watched = true, playedAt = 6)) }
        val rows = rows(shows = listOf(upToDate, loading, failed), states = mapOf(1 to all), tmdb = TmdbAnswers(shows = mapOf(1 to details(1, "Up to date"), 3 to null)))
        assertEquals(emptyList(), rows)
    }

    @Test
    fun `at most ten rows`() {
        val inProgress = (1..8).map { movie(100 + it, "Film $it", positionMs = 600_000, playedAt = it.toLong()) }
        val shows = (1..5).map { show(it, "Show $it") }
        val states = shows.associate { it.tmdbId to listOf(episode(it.tmdbId, it.name, 1, 1, watched = true)) }
        val rows = rows(inProgress, shows, states, TmdbAnswers(shows = shows.associate { it.tmdbId to details(it.tmdbId, it.name) }))
        assertEquals(10, rows.size)
        assertEquals((8 downTo 1).map { "Film $it" } + listOf("Show 1", "Show 2"), rows.map { it.title })
    }

    /** A show taken out of Up next goes whole: its episode in progress, or its next one; collected or not. */
    @Test
    fun `a show taken out has no row, neither its episode in progress nor its next one`() {
        val harbor = show(1, "Harbor Lights")
        val orbit = show(2, "The Quiet Orbit")
        val glasswater = show(3, "Glasswater Bay")
        val resumed = episode(1, "Harbor Lights", 2, 5, positionMs = 900_000, playedAt = 30)
        val notCollected = episode(7, "Salt & Signal", 1, 2, positionMs = 900_000, playedAt = 20)
        val states = mapOf(
            1 to listOf(episode(1, "Harbor Lights", 2, 4, watched = true, playedAt = 10), resumed),
            2 to listOf(episode(2, "The Quiet Orbit", 1, 1, watched = true, playedAt = 5)),
            3 to listOf(episode(3, "Glasswater Bay", 1, 1, watched = true, playedAt = 4)),
        )
        val tmdb = TmdbAnswers(shows = listOf(harbor, orbit, glasswater).associate { it.tmdbId to details(it.tmdbId, it.name) })
        val all = rows(listOf(resumed, notCollected), listOf(harbor, orbit, glasswater), states, tmdb)
        assertEquals(listOf("show:1", "show:7", "show:2", "show:3"), all.map { it.key })
        val rows = rows(listOf(resumed, notCollected), listOf(harbor, orbit, glasswater), states, tmdb, hidden = setOf("show:1", "show:2", "show:7"))
        assertEquals(listOf(UpNextPlay.NextEpisode(glasswater, EpisodeRef(1, 2))), rows.map { it.play })
    }

    @Test
    fun `a film or a release taken out has no row`() {
        val film = movie(10, "Copper Crown", positionMs = 600_000, playedAt = 30)
        val other = movie(11, "Tide Clock", positionMs = 600_000, playedAt = 25)
        val release = releaseOnly("Northbound.2019.1080p.BluRay.x264-GRP", positionMs = 600_000, playedAt = 20)
        val rows = rows(listOf(film, other, release), hidden = setOf(film.key, release.key))
        assertEquals(listOf("Tide Clock"), rows.map { it.title })
    }

    /** Ten rows still: those taken out make room for the next ones. */
    @Test
    fun `rows taken out don't count toward the ten`() {
        val inProgress = (1..12).map { movie(100 + it, "Film $it", positionMs = 600_000, playedAt = it.toLong()) }
        val rows = rows(inProgress, hidden = setOf(WatchKey.movie(112), WatchKey.movie(109)))
        assertEquals(listOf(11, 10, 8, 7, 6, 5, 4, 3, 2, 1).map { "Film $it" }, rows.map { it.title })
    }

    @Test
    fun `watched, deleted and barely started plays are not in progress`() {
        val rows = rows(
            listOf(
                movie(10, "Watched", positionMs = 600_000, playedAt = 3).copy(watched = true),
                movie(11, "Deleted", positionMs = 600_000, playedAt = 2).copy(deleted = true),
                movie(12, "Barely started", positionMs = 59_000, playedAt = 1),
                movie(13, "A minute in", positionMs = 60_000, playedAt = 0),
            ),
        )
        assertEquals(listOf("A minute in"), rows.map { it.title })
    }

    @Test
    fun `films and release-only plays are rows of their own`() {
        val film = movie(10, "Copper Crown", positionMs = 1_200_000, playedAt = 20)
        val other = releaseOnly("Northbound.2019.1080p.BluRay.x264-GRP", positionMs = 1_200_000, playedAt = 10)
        val tmdb = TmdbAnswers(movies = mapOf(10 to MovieDetails(10, "Copper Crown", null, 2019, null, "/cc.jpg", null, 100, ExternalIds())))
        val rows = rows(listOf(film, other), tmdb = tmdb)
        assertEquals(listOf("Copper Crown", "Northbound"), rows.map { it.title })
        assertEquals(listOf("2019 · 1 h 20 min left", "2019 · 30 min left"), rows.map { it.line })
        assertEquals(listOf("/cc.jpg", null), rows.map { it.posterPath })
        assertEquals(listOf(0.2f, 0.4f), rows.map { it.progress })
    }

    /** Home keys its rows by it, so a TV remote's focus stays on the show it played when the rows reorder. */
    @Test
    fun `a show's row keeps its key once its next episode starts, and every row's key is its own`() {
        val harbor = show(1, "Harbor Lights")
        val orbit = show(2, "The Quiet Orbit")
        val tmdb = TmdbAnswers(shows = mapOf(1 to details(1, "Harbor Lights"), 2 to details(2, "The Quiet Orbit")))
        val resumed = episode(1, "Harbor Lights", 2, 5, positionMs = 900_000, playedAt = 10)
        val pilot = episode(2, "The Quiet Orbit", 1, 1, watched = true, playedAt = 5)
        val before = rows(listOf(resumed), listOf(harbor, orbit), mapOf(1 to listOf(resumed), 2 to listOf(pilot)), tmdb)
        val started = episode(2, "The Quiet Orbit", 1, 2, positionMs = 900_000, playedAt = 20)
        val after = rows(listOf(started, resumed), listOf(harbor, orbit), mapOf(1 to listOf(resumed), 2 to listOf(pilot, started)), tmdb)
        assertEquals(listOf(UpNextPlay.Resume(resumed), UpNextPlay.NextEpisode(orbit, EpisodeRef(1, 2))), before.map { it.play })
        assertEquals(listOf(UpNextPlay.Resume(started), UpNextPlay.Resume(resumed)), after.map { it.play })
        assertEquals(before.map { it.key }.reversed(), after.map { it.key })
        val others = rows(
            listOf(resumed, movie(10, "Copper Crown", 600_000, 30), releaseOnly("Northbound.2019.1080p.BluRay.x264-GRP", 600_000, 40)),
            listOf(harbor, orbit), mapOf(1 to listOf(resumed), 2 to listOf(pilot)), tmdb,
        )
        assertEquals(4, others.map { it.key }.distinct().size)
    }

    @Test
    fun `without a TMDB key only rows in progress show`() {
        val harbor = show(1, "Harbor Lights")
        val orbit = show(2, "The Quiet Orbit")
        val started = episode(1, "Harbor Lights", 2, 5, positionMs = 900_000, playedAt = 10)
        val rows = rows(listOf(started), listOf(harbor, orbit), mapOf(1 to listOf(started)), TmdbAnswers(shows = mapOf(2 to details(2, "The Quiet Orbit"))), hasKey = false)
        assertEquals(listOf(UpNextPlay.Resume(started)), rows.map { it.play })
    }

    @Test
    fun `a row in progress shows before TMDB answers, named from what was saved`() {
        val started = episode(7, "Salt & Signal", 2, 5, positionMs = 900_000, durationMs = 2_700_000, playedAt = 10)
        val row = rows(listOf(started)).single()
        assertEquals("Salt & Signal", row.title)
        assertEquals("S02E05 · 30 min left", row.line)
        assertEquals(1f / 3, row.progress)
        assertNull(row.posterPath)
        assertEquals(false, row.new)
    }

    @Test
    fun `the line names the episode and its time left, and New marks one aired this past week`() {
        val harbor = show(1, "Harbor Lights")
        val orbit = show(2, "The Quiet Orbit")
        val started = episode(1, "Harbor Lights", 2, 5, positionMs = 900_000, durationMs = 2_700_000, playedAt = 10)
        val orbitStates = (1..10).map { episode(2, "The Quiet Orbit", 1, it, watched = true, playedAt = 5) } +
            (1..2).map { episode(2, "The Quiet Orbit", 2, it, watched = true, playedAt = 6) }
        val tmdb = TmdbAnswers(
            shows = mapOf(1 to details(1, "Harbor Lights"), 2 to details(2, "The Quiet Orbit", lastAired = today.minusDays(2))),
            seasons = mapOf(SeasonKey(1, 2) to season(1, 2, aired = today.minusDays(6)), SeasonKey(2, 2) to season(2, 2, aired = today.minusDays(9))),
        )
        val rows = rows(listOf(started), listOf(harbor, orbit), mapOf(1 to listOf(started), 2 to orbitStates), tmdb)
        assertEquals(listOf("S02E05 · Episode 2.5 · 30 min left", "S02E03 · Episode 2.3 · 8 unwatched"), rows.map { it.line })
        assertEquals(listOf(true, false), rows.map { it.new })
        assertEquals(listOf("/p1.jpg", "/p2.jpg"), rows.map { it.posterPath })
        assertEquals(setOf(SeasonKey(1, 2), SeasonKey(2, 2)), UpNext.seasons(rows))
    }

    @Test
    fun `a next episode aired this past week is New`() {
        val orbit = show(2, "The Quiet Orbit")
        val second = details(2, "The Quiet Orbit").copy(
            seasons = listOf(SeasonSummary(1, null, 2, null, null)), lastEpisode = EpisodeSummary(1, 2, "Second Light", today.minusDays(1)),
        )
        val pilot = episode(2, "The Quiet Orbit", 1, 1, watched = true, playedAt = 5)
        val row = rows(shows = listOf(orbit), states = mapOf(2 to listOf(pilot)), tmdb = TmdbAnswers(shows = mapOf(2 to second))).single()
        assertEquals("S01E02 · Second Light", row.line)
        assertEquals(true, row.new)
    }

    /** Up next is for what was begun: a show just collected is found under Collection, not here. */
    @Test
    fun `a collected show never started has no row`() {
        val orbit = show(2, "The Quiet Orbit")
        val rows = rows(shows = listOf(orbit), tmdb = TmdbAnswers(shows = mapOf(2 to details(2, "The Quiet Orbit"))))
        assertEquals(emptyList(), rows)
    }

    /**
     * Started as the collection counts it: a regular episode watched (played to the end or marked) or a minute in. A play
     * under a minute, only specials and every episode marked unwatched again don't start a show; one marked watched does.
     */
    @Test
    fun `a play under a minute, only specials or every episode marked unwatched don't start a show, one marked watched does`() {
        val barely = show(1, "Barely played")
        val specials = show(2, "Only its specials")
        val unmarked = show(3, "Marked unwatched")
        val marked = show(4, "Marked watched")
        val shows = listOf(barely, specials, unmarked, marked)
        val states = mapOf(
            1 to listOf(episode(1, "Barely played", 1, 1, positionMs = 59_000, playedAt = 40)),
            2 to listOf(episode(2, "Only its specials", 0, 1, watched = true, playedAt = 30), episode(2, "Only its specials", 0, 2, watched = true, playedAt = 31)),
            3 to (1..3).map { episode(3, "Marked unwatched", 1, it, playedAt = 20) },
            4 to listOf(episode(4, "Marked watched", 1, 1, watched = true)),
        )
        val rows = rows(shows = shows, states = states, tmdb = TmdbAnswers(shows = shows.associate { it.tmdbId to details(it.tmdbId, it.name) }))
        assertEquals(listOf(UpNextPlay.NextEpisode(marked, EpisodeRef(1, 2))), rows.map { it.play })
    }

    /**
     * A TV's Up next card shows a landscape picture: the episode's still; else a movie's backdrop; else the backdrop of a
     * show that isn't collected (a collected show's stored facts have none); else nothing (the card shows the poster).
     * The still comes first even for a show not collected, whose backdrop TMDB has too; a collected show's TMDB backdrop
     * isn't used, its season not loaded or not.
     */
    @Test
    fun `a card's picture is the episode's still, else the movie's backdrop, else that of a show not collected`() {
        val harbor = show(1, "Harbor Lights")
        val orbit = show(2, "The Quiet Orbit")
        val glasswater = show(3, "Glasswater Bay")
        val withStill = episode(1, "Harbor Lights", 2, 5, positionMs = 900_000, playedAt = 60)
        val noSeasonYet = episode(3, "Glasswater Bay", 1, 4, positionMs = 900_000, playedAt = 50)
        val notCollected = episode(7, "Salt & Signal", 1, 2, positionMs = 900_000, playedAt = 40)
        val notCollectedNoSeason = episode(8, "Tide Clock", 1, 3, positionMs = 900_000, playedAt = 35)
        val film = movie(10, "Copper Crown", positionMs = 600_000, playedAt = 30)
        val release = releaseOnly("Northbound.2019.1080p.BluRay.x264-GRP", positionMs = 600_000, playedAt = 20)
        val tmdb = TmdbAnswers(
            shows = mapOf(
                1 to details(1, "Harbor Lights"), 2 to details(2, "The Quiet Orbit"), 3 to details(3, "Glasswater Bay").copy(backdropPath = "/b3.jpg"),
                7 to details(7, "Salt & Signal").copy(backdropPath = "/b7.jpg"), 8 to details(8, "Tide Clock").copy(backdropPath = "/b8.jpg"),
            ),
            seasons = mapOf(SeasonKey(1, 2) to withStills(season(1, 2)), SeasonKey(2, 1) to withStills(season(2, 1)), SeasonKey(7, 1) to withStills(season(7, 1))),
            movies = mapOf(10 to MovieDetails(10, "Copper Crown", null, 2019, null, "/cc.jpg", "/b10.jpg", 100, ExternalIds())),
        )
        val rows = rows(
            listOf(withStill, noSeasonYet, notCollected, notCollectedNoSeason, film, release), listOf(harbor, orbit, glasswater),
            mapOf(1 to listOf(withStill), 2 to listOf(episode(2, "The Quiet Orbit", 1, 1, watched = true)), 3 to listOf(noSeasonYet)), tmdb,
        )
        assertEquals(listOf("Harbor Lights", "Glasswater Bay", "Salt & Signal", "Tide Clock", "Copper Crown", "Northbound", "The Quiet Orbit"), rows.map { it.title })
        assertEquals(listOf("/s1-2-5.jpg", null, "/s7-1-2.jpg", "/b8.jpg", "/b10.jpg", null, "/s2-1-2.jpg"), rows.map { it.landscapePath })
    }

    /** What a TV's Up next card opens: its show's or movie's page; a release played without TMDB has none (its card plays). */
    @Test
    fun `a row's title is its show or movie, and a release played without TMDB has none`() {
        val harbor = show(1, "Harbor Lights")
        val orbit = show(2, "The Quiet Orbit")
        val started = episode(1, "Harbor Lights", 2, 5, positionMs = 900_000, playedAt = 60)
        val notCollected = episode(7, "Salt & Signal", 1, 2, positionMs = 900_000, playedAt = 40)
        val film = movie(10, "Copper Crown", positionMs = 600_000, playedAt = 30)
        val release = releaseOnly("Northbound.2019.1080p.BluRay.x264-GRP", positionMs = 600_000, playedAt = 20)
        val tmdb = TmdbAnswers(shows = mapOf(1 to details(1, "Harbor Lights"), 2 to details(2, "The Quiet Orbit")))
        val pilot = episode(2, "The Quiet Orbit", 1, 1, watched = true)
        val rows = rows(listOf(started, notCollected, film, release), listOf(harbor, orbit), mapOf(1 to listOf(started), 2 to listOf(pilot)), tmdb)
        assertEquals(listOf(TitleKey.show(1), TitleKey.show(7), TitleKey.movie(10), null, TitleKey.show(2)), rows.map { it.titleKey })
    }

    /**
     * A special in progress has no page that plays it: the Show page's Resume and Play are for the show's regular
     * episodes, and it opens on a regular season. Its card plays it, as a release's does.
     */
    @Test
    fun `a special in progress opens no page, so its card plays it`() {
        val harbor = show(1, "Harbor Lights")
        val special = episode(1, "Harbor Lights", 0, 2, positionMs = 900_000, playedAt = 60)
        val rows = rows(listOf(special), listOf(harbor), mapOf(1 to listOf(special)), TmdbAnswers(shows = mapOf(1 to details(1, "Harbor Lights"))))
        assertEquals(listOf(UpNextPlay.Resume(special)), rows.map { it.play })
        assertNull(rows.single().titleKey)
    }

    /**
     * A TV's card has one short line under its title: one in progress says what is left (not the episode's name); a next
     * episode says its name, else how far the show is.
     */
    @Test
    fun `a card's line says what is left of one in progress, else the next episode's name, else how far the show is`() {
        val harbor = show(1, "Harbor Lights")
        val orbit = show(2, "The Quiet Orbit")
        val glasswater = show(3, "Glasswater Bay")
        val started = episode(1, "Harbor Lights", 2, 5, positionMs = 900_000, durationMs = 2_700_000, playedAt = 60)
        val film = movie(10, "Copper Crown", positionMs = 3_480_000, playedAt = 50)
        val release = releaseOnly("Northbound.2019.1080p.BluRay.x264-GRP", positionMs = 1_200_000, playedAt = 40)
        val second = details(2, "The Quiet Orbit").copy(seasons = listOf(SeasonSummary(1, null, 2, null, null)), lastEpisode = EpisodeSummary(1, 2, "Second Light", today.minusDays(1)))
        val tmdb = TmdbAnswers(
            shows = mapOf(1 to details(1, "Harbor Lights"), 2 to second, 3 to details(3, "Glasswater Bay")),
            seasons = mapOf(SeasonKey(1, 2) to season(1, 2)),
            movies = mapOf(10 to MovieDetails(10, "Copper Crown", null, 2019, null, null, null, 100, ExternalIds())),
        )
        val states = mapOf(
            1 to listOf(started), 2 to listOf(episode(2, "The Quiet Orbit", 1, 1, watched = true, playedAt = 5)),
            3 to listOf(episode(3, "Glasswater Bay", 1, 1, watched = true, playedAt = 4)),
        )
        val rows = rows(listOf(started, film, release), listOf(harbor, orbit, glasswater), states, tmdb)
        assertEquals(listOf("Harbor Lights", "Copper Crown", "Northbound", "The Quiet Orbit", "Glasswater Bay"), rows.map { it.title })
        assertEquals(listOf("S02E05 · 30 min left", "2019 · 42 min left", "2019 · 30 min left", "S01E02 · Second Light", "S01E02 · 19 unwatched"), rows.map { it.cardLine })
        // The phone's rows keep the whole line.
        assertEquals("S02E05 · Episode 2.5 · 30 min left", rows.first().line)
    }
}
