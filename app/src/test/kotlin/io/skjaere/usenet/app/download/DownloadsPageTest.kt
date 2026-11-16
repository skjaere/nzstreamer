package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.NetworkNow
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.search.MovieFind
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.MovieWatch
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.EpisodeRequest
import io.skjaere.usenet.search.FindResult
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.FoundRelease
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.SearchStage
import io.skjaere.usenet.data.PlaybackPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Downloads page (item 19; L372, L379-L381), from rows as the runner keeps them: its sections and their order (the
 * row the queue is at first, then the queue, Failed under it, then one row per show and one per film); a show's
 * episodes grouped; the space strip's numbers, the running one counted whole and those removed counted gone at once;
 * each row's words in each state; what select mode's Delete gives back; the Undo bar's words; and Choose release, which
 * looks an episode's show up on TMDB first and is greyed offline or where TMDB failed. Invented titles and releases.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsPageTest {
    private val saved = Locale.getDefault()

    // formatSize follows the default locale; pin one so the decimal separator is predictable.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    @Test
    fun `the queue's current row comes first, then the queue in order, Failed under it, then shows and films, the latest finished first`() {
        val page = DownloadsPage.of(
            listOf(
                item(episode("q5", 5, DownloadState.Queued, position = 1)),
                item(episode("run", 4, DownloadState.Running, position = 2, size = 3_100_000_000, file = true, written = 1_302_000_000)),
                item(episode("q6", 6, DownloadState.Queued, position = 3)),
                item(episode("gone", 7, DownloadState.Deleting, position = 4)),
                item(episode("failed", 3, DownloadState.Failed, show = QM, title = "Quiet Meridian", position = 5)),
                item(episode("hl1", 1, DownloadState.Done, season = 1, completedAt = 10)),
                item(film("cc", DownloadState.Done, completedAt = 30)),
                item(episode("qm4", 4, DownloadState.Done, show = QM, title = "Quiet Meridian", season = 1, completedAt = 20)),
                item(episode("hl2", 2, DownloadState.Done, season = 1, completedAt = 5)),
            ),
        )
        assertEquals(listOf("run", "q5", "q6"), page.downloading.map { it.key })
        assertEquals(listOf(true, false, false), page.downloading.map { it.current })
        assertEquals(listOf("failed"), page.failed.map { it.key })
        assertEquals(listOf("cc", "show-$QM", "show-$HL"), page.downloaded.map { it.key }, "by when each last finished")
        assertEquals(7, page.rows.size, "what Select all counts")
        assertFalse(page.empty)
        assertTrue(DownloadsPage.of(listOf(item(episode("gone", 7, DownloadState.Deleting)))).empty, "a removed one is hidden at once")

        // Move up and Move down where a queued neighbour is.
        assertEquals(listOf(false to false, false to true, true to false), page.downloading.map { it.up to it.down })

        // With nothing running or held, the queue's head is a queued row like the rest.
        val queued = DownloadsPage.of(listOf(item(episode("q5", 5, DownloadState.Queued)), item(episode("q6", 6, DownloadState.Queued, position = 1))))
        assertEquals(listOf(false, false), queued.downloading.map { it.current })
        // One held is the queue's current row.
        val held = DownloadsPage.of(listOf(item(episode("q5", 5, DownloadState.Queued)), item(episode("p", 4, DownloadState.Paused, position = 1))))
        assertEquals(listOf("p" to true, "q5" to false), held.downloading.map { it.key to it.current })
    }

    @Test
    fun `a show's finished episodes are one row, with what they hold and what plays next, and a film is a row of its own`() {
        val states = mapOf(
            (1 to 1) to watched(1, 1), (1 to 2) to watched(1, 2), (1 to 3) to watched(1, 3),
            (2 to 1) to begun(2, 1, positionMs = 20 * 60_000L, durationMs = 52 * 60_000L),
        )
        val episodes = (1..3).map { episode("s1e$it", it, DownloadState.Done, season = 1, size = 1_700_000_000) } +
            listOf(2_100_000_000L, 2_000_000_000L, 2_000_000_000L).mapIndexed { i, size -> episode("s2e${i + 1}", i + 1, DownloadState.Done, season = 2, size = size) }
        val page = DownloadsPage.of(
            (episodes + film("cc", DownloadState.Done, gaps = 2)).map(::item) + item(episode("queued", 4, DownloadState.Queued, season = 2)),
            episodeStates = mapOf(HL to states),
            films = mapOf(FILM to MovieWatch(FILM, 0, 0, watched = true, lastPlayedAt = 1)),
        )
        val show = assertIs<DownloadsRow.Show>(page.downloaded.single { it.key == "show-$HL" })
        assertEquals("Harbor Lights", show.title)
        assertEquals(6, show.episodes.size, "the queued episode stays under Downloading")
        assertEquals("6 episodes · 11.2 GB", DownloadsPage.showSub(show.episodes))
        assertEquals("Resume S02E01", DownloadsPage.nextLine(show.next!!))
        assertEquals(11_200_000_000, show.held)

        val film = assertIs<DownloadsRow.Film>(page.downloaded.single { it.key == "cc" })
        assertEquals("2022 · 1 h 52 min · 5.5 GB", DownloadsPage.filmSub(film.row))
        assertEquals("2 parts missing", DownloadsPage.missing(film.row))
        assertTrue(film.watched)
        assertNull(DownloadsPage.missing(film.row.copy(gaps = emptyList())))
        assertEquals("1 part missing", DownloadsPage.missing(film.row.copy(gaps = listOf(DownloadGap(0, 9)))))

        // Nothing begun: the next after the furthest watched.
        val next = DownloadsPage.of(episodes.map(::item), episodeStates = mapOf(HL to states - (2 to 1)))
        assertEquals("Next: S02E01", DownloadsPage.nextLine(assertIs<DownloadsRow.Show>(next.downloaded.single()).next!!))
    }

    @Test
    fun `the strip says what downloads hold, the running one whole, and counts those removed free at once`() {
        val rows = listOf(
            episode("run", 4, DownloadState.Running, size = 3_100_000_000, file = true, written = 1_302_000_000),
            episode("q5", 5, DownloadState.Queued, indexerSize = 1_200_000_000),
            episode("done", 1, DownloadState.Done, season = 1, size = 11_200_000_000),
            film("cc", DownloadState.Done),
            episode("qm", 4, DownloadState.Done, show = QM, title = "Quiet Meridian", season = 1, size = 3_600_000_000),
        )
        val space = DownloadSpace.of(allocatable = 43_200_000_000, usable = 43_200_000_000, rows = rows)
        val strip = DownloadsPage.strip(space)
        // 3.1 + 11.2 + 5.5 + 3.6: the running one at its whole size, set aside before its first byte.
        assertEquals(23_400_000_000, strip.used)
        // Less the 1.0 GB kept free and the 1.2 GB the queued one still needs.
        assertEquals(41 * GB, strip.free)
        assertEquals(23.4f / 64.4f, strip.fraction, 0.001f)

        // Harbor Lights' 11.2 GB and Copper Crown's 5.5 GB removed: gone from the strip at once, though Android counts
        // their files until the Undo bar has gone.
        val removed = rows.map { if (it.id == "done" || it.id == "cc") it.copy(state = DownloadState.Deleting) else it }
        val after = DownloadsPage.strip(DownloadSpace.of(43_200_000_000, 43_200_000_000, removed))
        assertEquals(6_700_000_000, after.used)
        assertEquals(57_700_000_000, after.free)
    }

    @Test
    fun `each state says itself in the page's words, the current row with its button`() {
        val running = episode("run", 4, DownloadState.Running, size = 3_100_000_000, file = true, written = 1_302_000_000)
        val words = DownloadsPage.active(DownloadRunner.Item(running, 1_302_000_000, bytesPerSecond = 8_400_000), current = true)
        assertEquals(listOf(PageLine("Downloading · 42%", Tone.Accent)), words.lines)
        assertEquals("1.3 of 3.1 GB · 8.4 MB/s · about 4 min left", words.bytes)
        assertEquals(PageBar.Part(0.42f, Tone.Accent), words.bar)
        assertEquals(RowButton.Pause, words.button)
        assertEquals("S02E04 · Low Water", DownloadsPage.sub(running))
        assertEquals("2022", DownloadsPage.sub(film("cc", DownloadState.Queued)))

        fun lines(row: Download, current: Boolean = true) = DownloadsPage.active(item(row), current)
        val trying = lines(running.copy(state = DownloadState.Resolving, reason = "Trying release 2 of 3 · Checking availability", relativePath = null, safeOffset = 0))
        assertEquals(listOf(PageLine("Trying release 2 of 3", Tone.Accent), PageLine("Checking availability", Tone.Muted)), trying.lines)
        assertEquals(PageBar.Finding, trying.bar)
        assertNull(trying.bytes)
        assertEquals(listOf(PageLine("Fetching NZB", Tone.Accent)), lines(running.copy(state = DownloadState.Resolving, reason = "Fetching NZB")).lines)
        assertEquals(listOf(PageLine("Finding releases…", Tone.Accent)), lines(running.copy(state = DownloadState.Resolving, reason = null)).lines)

        val paused = lines(running.copy(state = DownloadState.Paused))
        assertEquals(listOf(PageLine("Paused · 42%", Tone.Muted)), paused.lines)
        assertEquals("1.3 of 3.1 GB", paused.bytes)
        assertEquals(PageBar.Part(0.42f, Tone.Muted), paused.bar)
        assertEquals(RowButton.Resume, paused.button)

        val wifi = lines(running.copy(state = DownloadState.Waiting, reason = DownloadHostRules.WAITING_FOR_WIFI))
        assertEquals(listOf(PageLine("Waiting for Wi-Fi · 42%", Tone.Secondary)), wifi.lines)
        assertEquals(RowButton.Pause, wifi.button)
        assertEquals("Waiting for a network · 42%", lines(running.copy(state = DownloadState.Waiting, reason = DownloadHostRules.WAITING_FOR_NETWORK)).lines.single().text)
        assertEquals("Waiting to charge · 42%", lines(running.copy(state = DownloadState.Waiting, reason = DownloadHostRules.WAITING_TO_CHARGE)).lines.single().text)

        val stopped = lines(running.copy(state = DownloadState.Stopped))
        assertEquals(listOf(PageLine("Stopped by Android · 42%", Tone.WarnStrong)), stopped.lines)
        assertEquals(RowButton.Resume, stopped.button)

        // The waits that end the host (L388): their words alone, with Resume.
        val provider = lines(running.copy(state = DownloadState.Waiting, reason = DownloadHostRules.CHECK_PROVIDER))
        assertEquals(listOf(PageLine("Check your Usenet provider", Tone.Warn)), provider.lines)
        assertEquals(RowButton.Resume, provider.button)
        assertEquals("Storage isn't available", lines(running.copy(state = DownloadState.Waiting, reason = DownloadHostRules.STORAGE_UNAVAILABLE)).lines.single().text)
        // The writer backing off (L382), beside how far it is.
        assertEquals(
            listOf(PageLine("Can't reach your Usenet provider · 42%", Tone.Warn)),
            lines(running.copy(reason = DownloadHostRules.CANT_REACH_PROVIDER)).lines,
        )

        // A queued one: the queue's words, no bar, no button; nor any button on a row the queue isn't at.
        val queued = lines(episode("q5", 5, DownloadState.Queued), current = false)
        assertEquals(listOf(PageLine("Queued · searched when its turn comes", Tone.Muted)), queued.lines)
        assertNull(queued.bar)
        assertNull(queued.button)
        assertNull(lines(running.copy(state = DownloadState.Paused), current = false).button)
        // Paused before its file was made: no share of it to say.
        assertEquals(listOf(PageLine("Paused", Tone.Muted)), lines(episode("p", 5, DownloadState.Paused)).lines)

        // Failed: the reason in technical words, and how many releases were tried.
        val failed = episode("f", 3, DownloadState.Failed, reason = DownloadResolver.INCOMPLETE, tried = listOf("a", "b", "c"))
        assertEquals(listOf(PageLine("✕ Your provider is missing parts of this release", Tone.Warn), PageLine("3 releases tried", Tone.Muted)), DownloadsPage.failure(failed))
        assertEquals(listOf(PageLine("✕ Storage is full", Tone.Warn), PageLine("1 release tried", Tone.Muted)), DownloadsPage.failure(failed.copy(reason = "Storage is full", tried = listOf("a"))))
        assertEquals(listOf(PageLine("✕ Won't fit", Tone.Warn)), DownloadsPage.failure(failed.copy(reason = DownloadResolver.WONT_FIT, tried = emptyList())))
    }

    @Test
    fun `Delete gives back what the selected rows hold, a queued one only what it set aside`() {
        val show = SIZES.mapIndexed { i, size -> episode("hl${i + 1}", i + 1, DownloadState.Done, season = 1, size = size) }
        val page = DownloadsPage.of(
            (show + film("cc", DownloadState.Done)).map(::item) + listOf(
                item(episode("run", 4, DownloadState.Running, season = 2, size = 3_100_000_000, file = true)),
                item(episode("q5", 5, DownloadState.Queued, season = 2, indexerSize = 1_200_000_000)),
                item(episode("f", 3, DownloadState.Failed, show = QM, title = "Quiet Meridian")),
            ),
        )
        val hl = page.rows.single { it.key == "show-$HL" }
        val cc = page.rows.single { it.key == "cc" }
        assertEquals(16_700_000_000, hl.held + cc.held)
        assertEquals("Delete · 16.7 GB", DownloadsPage.delete(hl.held + cc.held))
        assertEquals("2 selected", DownloadsPage.selected(2))
        assertEquals(5, page.rows.size)
        assertEquals("Select all (5)", DownloadsPage.selectAll(page.rows.size))
        // The running one gives back its whole size, set aside before its first byte; a queued one, nothing yet.
        assertEquals(3_100_000_000, page.rows.single { it.key == "run" }.held)
        assertEquals(0, page.rows.single { it.key == "q5" }.held)
        assertEquals(0, page.rows.single { it.key == "f" }.held)
        assertEquals("Delete", DownloadsPage.delete(0))
    }

    @Test
    fun `the Undo bar says what was deleted, a show's episodes together, and the space it gives back`() {
        val show = SIZES.mapIndexed { i, size -> episode("hl${i + 1}", i + 1, DownloadState.Done, season = 1, size = size) }
        val cc = film("cc", DownloadState.Done)
        assertEquals("Deleted Harbor Lights (6 episodes) and Copper Crown · 16.7 GB", DownloadsPage.deleted(show + cc))
        assertEquals("Deleted Harbor Lights S02E05", DownloadsPage.deleted(listOf(episode("q5", 5, DownloadState.Queued))))
        assertEquals("Deleted Harbor Lights (3 episodes) · 5.1 GB", DownloadsPage.deleted(show.take(3)))
        val qm = episode("qm", 4, DownloadState.Done, show = QM, title = "Quiet Meridian", season = 1, size = 1_800_000_000)
        assertEquals(
            "Deleted Harbor Lights (6 episodes), Quiet Meridian S01E04 and Copper Crown · 18.5 GB",
            DownloadsPage.deleted(show + qm + cc),
        )
        val more = film("other", DownloadState.Done, film = FILM + 1, title = "Lantern Bay")
        assertEquals("Deleted 9 downloads · 24.0 GB", DownloadsPage.deleted(show + qm + cc + more))
    }

    @Test
    fun `Choose release looks an episode's show up on TMDB first, and a pick replaces its releases`() = runTest {
        val fakes = Fakes()
        val choice = fakes.choice(this)
        val row = episode("run", 4, DownloadState.Running, size = 3_100_000_000, file = true)
        choice.open(row)
        runCurrent()
        assertEquals(listOf("lookUp $HL 2 4", "find"), fakes.calls, "TMDB first, then the search")
        val request = fakes.requests.single()
        assertEquals(TvIds(tvdb = 7_001, tmdb = HL), request.show.ids)
        assertEquals(8, request.seasonEpisodes)
        val choosing = assertIs<DownloadChoice.State.Choosing>(choice.state.value)
        assertEquals(row, choosing.row)
        val pick = assertIs<DownloadSheetState.Pick>(choosing.model.state.value)
        // The room it checks against is its own counted free, as a replace gives it back.
        assertEquals(listOf<String?>("run"), fakes.spaceFor)

        pick.mode.onDownload(pick.group.playable.last())
        advanceUntilIdle()
        // The choice's own coroutine is a background one, which advanceUntilIdle leaves once the foreground is idle.
        runCurrent()
        assertEquals("run" to listOf(ATMOS, KESTREL), fakes.replaced.single().let { (id, candidates) -> id to candidates.map { it.title } })
        assertEquals(listOf("lookUp $HL 2 4", "find", "replace", "schedule"), fakes.calls)
        assertEquals(DownloadChoice.State.None, choice.state.value, "the list closes once it is replaced")
    }

    @Test
    fun `a film's choice searches it as its row names it, with no lookup`() = runTest {
        val fakes = Fakes()
        val choice = fakes.choice(this)
        choice.open(film("cc", DownloadState.Failed))
        runCurrent()
        assertEquals(listOf("film"), fakes.calls)
        assertEquals(MovieTarget(FILM, "Copper Crown", 2022, imdb = null, runtimeMinutes = 112), fakes.films.single())
        assertIs<DownloadChoice.State.Choosing>(choice.state.value)
        choice.close()
        assertEquals(DownloadChoice.State.None, choice.state.value)
    }

    @Test
    fun `Choose release is greyed offline, and where TMDB failed until the network comes back`() = runTest {
        val fakes = Fakes()
        val choice = fakes.choice(this)
        val row = episode("q5", 5, DownloadState.Queued)
        fakes.online.value = false
        runCurrent()
        assertFalse(choice.can(row, fakes.online.value, choice.needsConnection.value))
        choice.open(row)
        runCurrent()
        assertEquals(emptyList(), fakes.calls, "nothing asked offline")
        assertEquals(DownloadChoice.State.None, choice.state.value)

        fakes.online.value = true
        fakes.tmdbFails = true
        runCurrent()
        assertTrue(choice.can(row, true, choice.needsConnection.value))
        choice.open(row)
        runCurrent()
        assertEquals(listOf("lookUp $HL 2 5"), fakes.calls, "no search without TMDB's answer")
        assertEquals(DownloadChoice.State.None, choice.state.value)
        assertEquals(setOf("q5"), choice.needsConnection.value)
        assertFalse(choice.can(row, true, choice.needsConnection.value))
        // Another download's choice still opens.
        assertTrue(choice.can(episode("q6", 6, DownloadState.Queued), true, choice.needsConnection.value))

        // The network gone and back: TMDB is asked again.
        fakes.online.value = false
        runCurrent()
        fakes.online.value = true
        runCurrent()
        assertEquals(emptySet(), choice.needsConnection.value)
    }

    /** What a choice reads and asks, faked; what it asked recorded. */
    private class Fakes {
        val online = MutableStateFlow(true)
        var tmdbFails = false
        val calls = mutableListOf<String>()
        val requests = mutableListOf<EpisodeRequest>()
        val films = mutableListOf<MovieTarget>()
        val spaceFor = mutableListOf<String?>()
        val replaced = mutableListOf<Pair<String, List<DownloadCandidate>>>()

        fun choice(scope: TestScope) = DownloadChoice(scope.backgroundScope, online, { show, season, episode ->
            calls += "lookUp $show $season $episode"
            if (tmdbFails) error("TMDB didn't answer")
            EpisodePlay(
                showTmdbId = show, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tvdb = 7_001, tmdb = show), season = season,
                episode = episode, episodeName = "Low Water", airDate = LocalDate.of(2025, 3, 25), runtimeMinutes = 52, seasonEpisodes = 8,
            )
        }) { row -> sheet(row, scope) }

        private fun sheet(row: Download, scope: CoroutineScope) = DownloadSheetDeps(
            findEpisode = { request, _ ->
                calls += "find"
                requests += request
                flowOf(FinderEvent.Finished(FindResult(request, listOf(ranked(KESTREL, 1_200_000_000, 2.0), ranked(ATMOS, 3_400_000_000, 1.0)), emptyList(), emptyList())))
            },
            findMovie = { movie, context ->
                calls += "film"
                films += movie
                flowOf(MovieFind(null, emptyList(), context))
            },
            context = { DOWNLOADING },
            space = {
                spaceFor += row.id
                DownloadSpace(available = 20 * GB, used = 0, queued = 0)
            },
            rows = { emptyList() },
            prefs = { DownloadPrefs() },
            network = { NetworkNow(usable = true, unmetered = true, wifi = true) },
            askDue = { false },
            enqueue = { _, candidates ->
                calls += "replace"
                replaced += row.id to candidates
                row
            },
            beforeDownload = {},
            schedule = { calls += "schedule" },
            outlive = scope,
        )
    }

    private companion object {
        const val GB = 1_000_000_000L
        const val HL = 990_517
        const val QM = 990_518
        const val FILM = 990_519
        const val KESTREL = "Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.H.265-KESTREL"
        const val ATMOS = "Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.Atmos.H.264-TIDEPOOL"

        /** Harbor Lights' six episodes: 11.2 GB. */
        val SIZES = listOf(1_700_000_000L, 1_700_000_000, 1_700_000_000, 2_100_000_000, 2_000_000_000, 2_000_000_000)
        val DOWNLOADING: RankingContext = DownloadRanking.context(StaticDeviceCapabilities(displayHeight = 1080), PlaybackPrefs(), DownloadPrefs(), "en")

        fun item(row: Download) = DownloadRunner.Item(row, row.safeOffset, null)

        fun episode(
            id: String, episode: Int, state: DownloadState, show: Int = HL, title: String = "Harbor Lights", season: Int = 2, position: Long = 0,
            size: Long? = null, indexerSize: Long = 1_000_000_000, file: Boolean = state == DownloadState.Done, written: Long = 0, reason: String? = null,
            tried: List<String> = emptyList(), completedAt: Long? = null,
        ) = Download(
            id = id, watchKey = WatchKey.episode(show, season, episode), kind = DownloadKind.Episode, title = title, showTmdbId = show, season = season,
            episode = episode, episodeName = if (episode == 4 && season == 2) "Low Water" else null, state = state, reason = reason, position = position,
            tried = tried, size = size, indexerSize = indexerSize, safeOffset = written, relativePath = if (file) "downloads/$id/v.mkv" else null,
            createdAt = 1, completedAt = completedAt, updatedAt = 1,
        )

        fun film(id: String, state: DownloadState, gaps: Int = 0, completedAt: Long? = null, film: Int = FILM, title: String = "Copper Crown") = Download(
            id = id, watchKey = WatchKey.movie(film), kind = DownloadKind.Movie, title = title, movieTmdbId = film, year = 2022, runtimeMinutes = 112,
            state = state, position = 9, size = 5_500_000_000, indexerSize = 5_500_000_000, gaps = (0 until gaps).map { DownloadGap(it * 100L, it * 100L + 9) },
            relativePath = if (state == DownloadState.Done) "downloads/$id/v.mkv" else null, createdAt = 1, completedAt = completedAt, updatedAt = 1,
        )

        fun watched(season: Int, episode: Int) = WatchState(
            key = WatchKey.episode(HL, season, episode), kind = WatchKind.Episode, showTmdbId = HL, season = season, episode = episode, title = "Harbor Lights",
            watched = true, lastPlayedAt = 1, updatedAt = 1,
        )

        fun begun(season: Int, episode: Int, positionMs: Long, durationMs: Long, lastPlayedAt: Long = 2) = WatchState(
            key = WatchKey.episode(HL, season, episode), kind = WatchKind.Episode, showTmdbId = HL, season = season, episode = episode, title = "Harbor Lights",
            positionMs = positionMs, durationMs = durationMs, lastPlayedAt = lastPlayedAt, updatedAt = 1,
        )

        fun ranked(title: String, size: Long, score: Double): FoundRelease {
            val item = NewznabItem(
                indexerId = "idx-a", guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null, categories = emptyList(), grabs = null,
                passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null, season = null, episode = null, coverUrl = null,
                mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
            )
            val ranked = RankedRelease(
                ReleaseFacts(item.key, title, size, source = ReleaseFacts.USENET), ReleaseParser.parse(title), playable = true, score = score, reasons = emptyList(),
                estimatedBitrate = null,
            )
            return FoundRelease(item, ranked, SearchStage.Episode)
        }
    }
}
