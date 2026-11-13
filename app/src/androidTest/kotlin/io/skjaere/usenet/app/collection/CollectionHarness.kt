package io.skjaere.usenet.app.collection

import android.content.Context
import android.os.SystemClock
import io.skjaere.usenet.app.Today
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.refresh.LibrarySummaryStore
import io.skjaere.usenet.app.refresh.RateGate
import io.skjaere.usenet.app.refresh.SummaryRefresher
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.CollectionViewPrefs
import io.skjaere.usenet.data.EpisodeMark
import io.skjaere.usenet.data.LibraryDatabase
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.ShowSummary
import io.skjaere.usenet.data.SummaryRefresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Collections

/**
 * The collection screen's graph over a library of its own ([NAME], deleted before and after), the
 * fixture's TMDB and a fixed clock: the app's container, its library, TMDB and the key are never
 * touched ([hasKey] is what the screen is told). The refresher runs only once [startRefresher] is
 * called; [prioritized] records what the screen handed it. Artwork is off, unless [posters]: each title
 * has a poster then, and artwork is on (a test's image loader answers for TMDB). Close it in @After.
 */
class CollectionHarness(
    private val context: Context,
    shows: Int = 1_000,
    movies: Int = 2_000,
    prefs: CollectionViewPrefs = CollectionViewPrefs(),
    hasKey: Boolean = true,
    posters: Boolean = false,
) : AutoCloseable {
    val clock: Clock = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC)
    val fixture = ScaleFixture(LocalDate.now(clock), clock.millis(), shows, movies, posters)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        context.deleteDatabase(NAME)
    }

    val db: LibraryDatabase = LibraryDatabase.create(context, NAME)
    val library = LibraryRepository(db) { System.currentTimeMillis() }
    private val today = Today(clock, flowOf(true)).dates
    val store = CollectionStore(CollectionInputs.of(library), today, flowOf(true), scope)
    val source = ScaleTitleSource(fixture)
    val refresher = SummaryRefresher(LibrarySummaryStore(library), source, clock, scope, RateGate(minIntervalMs = 50) { SystemClock.elapsedRealtime() })
    val prefs = MutableStateFlow(prefs)
    val prioritized: MutableList<List<TitleKey>> = Collections.synchronizedList(mutableListOf())
    private var refreshing: Job? = null

    val deps = CollectionDeps(
        shows = store.shows, movies = store.movies, writes = LibraryWrites(library),
        prioritize = { keys -> prioritized += keys; refresher.prioritize(keys) }, refreshStatus = refresher.status,
        prefs = this.prefs, savePrefs = { change -> this.prefs.update(change) },
        hasKey = flowOf(hasKey), showArtwork = flowOf(posters), today = today, writeScope = scope, clock = clock,
    )

    fun seed() = runBlocking { fixture.seed(db) }

    /** Home's part: the store's rows read once, so the tab opens warm. */
    fun warm() = runBlocking {
        scope.launch { store.shows.collect {} }
        scope.launch { store.movies.collect {} }
        store.shows.filterNotNull().first()
        store.movies.filterNotNull().first()
    }

    /** A show just collected as Search's "+ Collection" from a suggestion writes it: no facts yet. Ids past the fixture's. */
    fun addShow(n: Int, name: String): TitleKey = runBlocking {
        val id = ScaleFixture.SHOWS + n
        library.addShow(CollectedShow(CollectedShow.idFor(id), id, name = name, year = 2026, addedAt = 0, updatedAt = 0))
        TitleKey.show(id)
    }

    /** TMDB's answer for [show] stored as the refresher's flush writes it: a returning show of eight episodes, the fourth airing on [next]. */
    fun answer(show: TitleKey, next: LocalDate) = runBlocking {
        val today = LocalDate.now(clock)
        val facts = ShowSummary(
            show.tmdbId, status = "Returning Series", seasons = "1:8",
            last = EpisodeMark(1, 3, airDay = today.minusDays(7).toEpochDay()), next = EpisodeMark(1, 4, airDay = next.toEpochDay()),
        )
        library.saveSummaries(listOf(facts), emptyList(), listOf(SummaryRefresh(SummaryRefresh.SHOW, show.tmdbId, fetchedAt = clock.millis(), staleAt = clock.millis() + 3_600_000)))
    }

    fun startRefresher() {
        refreshing = refresher.start(flowOf(true))
    }

    override fun close() {
        refreshing?.cancel()
        scope.cancel()
        db.close()
        context.deleteDatabase(NAME)
    }

    companion object {
        const val NAME = "collection-scale-test.db"
    }
}
