package io.skjaere.usenet.app.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.DeviceClock
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.app.collection.model.MovieRow
import io.skjaere.usenet.app.collection.model.MovieRows
import io.skjaere.usenet.app.collection.model.MovieState
import io.skjaere.usenet.app.collection.model.toSummary
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.movieNoReleaseNote
import io.skjaere.usenet.app.play.OkGuard
import io.skjaere.usenet.app.play.pageOkGuard
import io.skjaere.usenet.app.play.rememberPageOkGuard
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.app.search.MovieFind
import io.skjaere.usenet.app.search.Poster
import io.skjaere.usenet.app.search.ReleaseList
import io.skjaere.usenet.app.search.alternativesTo
import io.skjaere.usenet.app.search.findMovieReleases
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.link.CHOSEN_PLAYS_HERE
import io.skjaere.usenet.app.link.CastButton
import io.skjaere.usenet.app.link.PlayOnPrimary
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.ReturnFocus
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.rememberReturnFocus
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.data.CollectedMovie
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.MovieSummary
import io.skjaere.usenet.data.MovieWatch
import io.skjaere.usenet.data.Removal
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.metadata.Freshness
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * A movie's page: what the collection's row says of it (from the library at once, TMDB's answer when
 * it comes), Play, Choose release, Mark watched or unwatched, and the collection toggle, which writes
 * and stays here. Progress is the movie's watch key's, so history from before it was collected shows.
 */
class MovieViewModel(private val container: AppContainer, private val tmdbId: Int, private val clock: Clock = DeviceClock) : ViewModel() {
    private val library = container.library
    private val answer = PageAnswer(viewModelScope, clock, { TmdbLookup.movieAged(container, tmdbId) }, Freshness::answer) { aged, _ ->
        // The collection's row and this page then agree; the refresher takes it for collected movies only.
        container.refresher.offer(aged.value, aged.age)
    }
    val details: StateFlow<MovieDetails?> = answer.value
    val error: StateFlow<String?> = answer.error
    val collected: StateFlow<Boolean> = library.isMovieCollected(tmdbId).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val state: StateFlow<WatchState?> = library.state(WatchKey.movie(tmdbId)).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val showArtwork = container.settings.metadata.map { it.showArtwork }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    /** The device's date, which moves on at midnight and on a return to the front, as the header's. */
    val today: StateFlow<LocalDate> = container.today.stateIn(viewModelScope, SharingStarted.Eagerly, LocalDate.now(clock))
    // Removed included: "In collection ✓" tapped while TMDB can't answer leaves the page, its toggle and Play up.
    private val collectedMovie = library.movie(tmdbId).distinctUntilChanged()

    val header: StateFlow<MovieRow?> = combine(collectedMovie, library.movieSummary(tmdbId), details, state, today) { movie, stored, d, watch, today ->
        movieHeader(movie, stored, d, watch, today)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val chooser = ReleaseChooser<MovieTarget>(viewModelScope, container.listRankings)
    val choose: StateFlow<ChooseState<MovieTarget>> = chooser.state
    val chosenFailed: StateFlow<Map<String, String>> = chooser.failed

    /** What "In collection ✓" removed on this visit: tapped again, the movie comes back as it was (when it was added included). */
    private var removal: Removal? = null

    init {
        // TMDB's language too: TmdbClient keeps each language's answers apart, so a page kept across a change asks in the new one.
        answer.follow(library.movieSummary(tmdbId), container.settings.metadata.map { it.language }) { it.toSummary() }
    }

    /** The page is in front again (back from the player, the app or another tab): an answer past its time is asked again. */
    fun shown() {
        answer.shown()
    }

    private fun target(): MovieTarget? = header.value?.let { movieTarget(it, details.value) }

    /** Plays the movie where Play on says (L7): this phone, or the TV chosen. */
    fun play() {
        container.playRouter.playMovie(target() ?: return)
    }

    /**
     * Searches as Play does, but shows the ranked releases to pick from: once every indexer has answered, and again as
     * torrents join once checked (W71).
     */
    fun chooseRelease() {
        val target = target() ?: return
        // Nothing found shows an empty list (none to pick), not the search forever.
        val none = ResultGroup("movie:${target.tmdbId}", GroupKind.Movie, null, emptyList(), null, target.year, emptyList(), emptyList())
        chooser.chooseAsFound(target) {
            findMovieReleases(container, target)
                .map<MovieFind, MovieFind?> { it }
                .catch { e ->
                    log.warn("Finding releases of {} failed", target.title, e)
                    emit(null)
                }
                .map { find ->
                    // Ranked for whom Play goes to, and again for each device chosen while the list shows (L127).
                    find?.context?.let { context -> rankedBy(context) { find.rankedBy(it).group ?: none } }
                    find?.group ?: none
                }
        }
    }

    /** Plays [item], picked from the list, which stays until it plays ([ReleaseChooser.play]). */
    fun playChosen(item: RankedItem) {
        val target = target() ?: return
        val others = (chooser.state.value as? ChooseState.Ready)?.group?.alternativesTo(item).orEmpty()
        chooser.play(item) { container.playRouter.chosen(chosenRequest(target, item, others)) }
    }

    fun closeChooser() {
        chooser.close()
    }

    fun mark(watched: Boolean) = viewModelScope.launch {
        val title = header.value?.movie?.title ?: return@launch
        saveOrLog("mark $title ${if (watched) "watched" else "unwatched"}") { library.markMovies(listOf(MovieMark(tmdbId, title)), watched) }
    }

    /** The toggle writes and stays here: no navigation. */
    fun toggleCollection() = viewModelScope.launch {
        val row = header.value ?: return@launch
        saveOrLog("change the collection") {
            if (collected.value) {
                removal = library.removeTitles(movies = listOf(tmdbId))
            } else {
                val undone = removal?.let { library.undoRemove(it) } ?: 0
                removal = null
                if (undone == 0) {
                    val now = System.currentTimeMillis()
                    val d = details.value
                    library.addMovie(CollectedMovie(CollectedMovie.idFor(tmdbId), tmdbId, d?.ids?.imdb ?: row.imdbId, row.movie.title, row.movie.year, row.movie.posterPath, now, now))
                }
                // As old as the answer is by now: the page may have been open a while.
                details.value?.let { container.refresher.offer(it, Duration.between(answer.answeredAt, clock.instant()).coerceAtLeast(Duration.ZERO)) }
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(MovieViewModel::class.java)
    }
}

@Composable
fun MovieScreen(tmdbId: Int, onBack: () -> Unit) {
    val container = LocalContext.current.container
    val vm: MovieViewModel = viewModel(key = "movie-$tmdbId") { MovieViewModel(container, tmdbId) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.shown() }
    val header by vm.header.collectAsStateWithLifecycle()
    val details by vm.details.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val collected by vm.collected.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val artwork by vm.showArtwork.collectAsStateWithLifecycle()
    val choose by vm.choose.collectAsStateWithLifecycle()
    val chosenFailed by vm.chosenFailed.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val playOn by container.playOn.target.collectAsStateWithLifecycle()
    val okGuard = rememberPageOkGuard()
    val tv = LocalFormFactor.current == FormFactor.Tv
    // A remote's focus back on Choose release once its list closes, which takes the page's place while it shows.
    val focus = rememberReturnFocus()

    when (val c = choose) {
        is ChooseState.Ready -> {
            BackHandler { vm.closeChooser() }
            ReleaseList(c.group, c.target.title, onBack = vm::closeChooser, failed = chosenFailed, rankedFor = c.rankedFor, onPlay = vm::playChosen)
            return
        }
        is ChooseState.Searching -> {
            BackHandler { vm.closeChooser() }
            Column(Modifier.fillMaxSize()) {
                TopBar(c.target.title, vm::closeChooser, subtitle = "Finding releases…")
                CircularProgressIndicator(Modifier.padding(24.dp), color = colors.accent)
                if (tv) ChooserCancel(vm::closeChooser)
            }
            return
        }
        ChooseState.None -> Unit
    }

    // A TV has no Play on button: it plays itself (L36).
    val ui = MoviePageUi(header, details?.overview, error, collected, artwork, state, today, playOn.takeUnless { container.television })
    val actions = MoviePageActions(
        play = vm::play,
        chooseRelease = {
            focus.leaving(MOVIE_CHOOSE)
            vm.chooseRelease()
        },
        mark = { vm.mark(it) },
        toggleCollection = { vm.toggleCollection() },
        retry = vm::shown,
        playOn = container.playOn::openSheet,
    )
    if (tv) TvMovieContent(ui, actions, focus, okGuard) else MovieContent(ui, actions, focus, okGuard, onBack)
}

/** What the Movie page shows: its [row] (null while loading, or when nothing names the movie), TMDB's [overview] and why it couldn't answer ([error]). */
@Immutable
internal data class MoviePageUi(
    val row: MovieRow?,
    val overview: String?,
    val error: String?,
    val collected: Boolean,
    val artwork: Boolean,
    val watch: WatchState?,
    val today: LocalDate,
    /** Where Play goes (W11), for the Cast button; null on a TV, which has none. */
    val playOn: PlayTarget? = null,
) {
    val text: MoviePageText? = row?.let { moviePageText(it, today) }
    val watched: Boolean get() = watch?.takeIf { !it.deleted }?.watched == true
}

/** What the Movie page's buttons do. */
internal class MoviePageActions(
    val play: () -> Unit,
    val chooseRelease: () -> Unit,
    val mark: (watched: Boolean) -> Unit,
    val toggleCollection: () -> Unit,
    val retry: () -> Unit,
    /** The Cast button: the Play on sheet. */
    val playOn: () -> Unit = {},
)

/** A phone's Movie page: one list under its bar. A remote starts on Play, and comes back from Choose release's list on it. */
@Composable
internal fun MovieContent(ui: MoviePageUi, actions: MoviePageActions, focus: ReturnFocus, okGuard: OkGuard, onBack: () -> Unit) {
    val dpad = rememberDpad()
    val row = ui.row
    LaunchedEffect(row != null) {
        if (dpad && row != null && !focus.restore()) runCatchingFocus { focus.requester(MOVIE_PLAY).requestFocus() }
    }
    Column(Modifier.fillMaxSize().pageOkGuard(okGuard)) {
        TopBar("", onBack) {
            ui.playOn?.let { CastButton(it, actions.playOn) }
            if (row != null) TextButton(onClick = actions.toggleCollection, modifier = Modifier.focusOutline(RoundedCornerShape(20.dp), fill = false)) {
                Text(if (ui.collected) "In collection ✓" else "+ Collection", style = AppType.secondary, color = if (ui.collected) colors.muted else colors.accent)
            }
        }
        if (row == null) {
            ui.error?.let { PageLoadFailure(MOVIE_LOAD_FAILED + it, actions.retry) } ?: CircularProgressIndicator(Modifier.padding(24.dp), color = colors.accent)
            return@Column
        }
        val text = ui.text ?: return@Column
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Poster(row.posterPath, ui.artwork, 96, 144, imageWidth = 342)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.text.name, style = AppType.hero.copy(letterSpacing = (-0.4).sp), color = colors.text)
                        if (row.meta.isNotEmpty()) Text(row.meta, style = AppType.body.copy(fontSize = 14.sp), color = colors.muted)
                        Text(text.line, style = AppType.body.copy(fontSize = 14.sp), color = row.statusColor())
                        row.progressFraction?.let { fraction ->
                            LinearProgressIndicator(
                                progress = { fraction }, modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = colors.accent, trackColor = colors.chip, drawStopIndicator = {},
                            )
                        }
                        PlayOnPrimary(
                            text.play, ui.playOn ?: PlayTarget.Phone, actions.play,
                            Modifier.padding(top = 6.dp).focusRequester(focus.requester(MOVIE_PLAY)).focusOutline(RoundedCornerShape(24.dp), color = colors.text, fill = false),
                        )
                        SecondaryButton("Choose release", actions.chooseRelease, Modifier.focusRequester(focus.requester(MOVIE_CHOOSE)).focusOutline(RoundedCornerShape(24.dp), fill = false))
                        // The TV takes no release chosen by hand yet (W11).
                        // A TV only: a release chosen by hand casts with a Cast device chosen (L80), so nothing to say then.
                        if (ui.playOn is PlayTarget.Tv) Text(CHOSEN_PLAYS_HERE, style = AppType.small, color = colors.muted)
                        text.note?.let { Text(it, style = AppType.small, color = colors.muted) }
                    }
                }
            }
            ui.overview?.let { overview -> item { Overview(overview) } }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { actions.mark(!ui.watched) }, modifier = Modifier.focusOutline(RoundedCornerShape(20.dp), fill = false)) {
                        Text(if (ui.watched) "Mark unwatched" else "Mark watched", style = AppType.secondary.copy(fontSize = 14.sp), color = colors.accent)
                    }
                }
            }
            ui.error?.let { item { Footnote(DETAILS_LOAD_FAILED + it) } }
            item { Footnote(MOVIE_FOOTNOTE) }
        }
    }
}

/** The status line's colour: the accent while in progress, good once watched, muted otherwise. */
@Composable
internal fun MovieRow.statusColor() = when (state) {
    MovieState.InProgress -> colors.accent
    MovieState.Watched -> colors.good
    else -> colors.muted
}

/** The overview, four lines until More opens it whole. */
@Composable
private fun Overview(text: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    var cut by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            text, style = AppType.secondary, color = colors.textSecondary, maxLines = if (open) Int.MAX_VALUE else 4,
            onTextLayout = { if (!open) cut = it.hasVisualOverflow },
        )
        if (cut || open) {
            TextButton(onClick = { open = !open }, modifier = Modifier.focusOutline(RoundedCornerShape(20.dp), fill = false)) {
                Text(if (open) "Less" else "More", style = AppType.secondary, color = colors.accent)
            }
        }
    }
}

/**
 * The page's header, as the collection's row says it: the movie as collected (else TMDB's answer, for
 * one opened from Search or since removed; else its removed row, so that removed while TMDB can't
 * answer the page stays up to put it back), TMDB's facts ([details] when it has answered, else those
 * stored), and how far it was watched. Null while there is nothing to name it by.
 */
internal fun movieHeader(collected: CollectedMovie?, stored: MovieSummary?, details: MovieDetails?, watch: WatchState?, today: LocalDate): MovieRow? {
    val movie = collected?.takeIf { !it.deleted } ?: details?.let { d ->
        CollectedMovie(CollectedMovie.idFor(d.tmdbId), d.tmdbId, d.ids.imdb, d.title, d.year, d.posterPath, addedAt = 0, updatedAt = 0)
    } ?: collected ?: return null
    val facts = details?.toSummary() ?: stored
    val watched = watch?.takeIf { !it.deleted }?.let { MovieWatch(movie.tmdbId, it.positionMs, it.durationMs, it.watched, it.lastPlayedAt) }
    return MovieRows.build(
        listOf(movie), listOfNotNull(facts).associateBy { it.tmdbId }, listOfNotNull(watched).associateBy { it.movieTmdbId },
        failures = emptyMap(), today = today, hasKey = true,
    ).single()
}

/** What Play and Choose release look for, from what the page shows ([row]) and TMDB's answer to it, once in. */
internal fun movieTarget(row: MovieRow, details: MovieDetails?): MovieTarget = MovieTarget(
    row.movie.tmdbId, row.movie.title, row.movie.year, details?.ids?.imdb ?: row.imdbId, row.runtimeMinutes, row.releaseDate,
    released = row.state != MovieState.Upcoming, originalLanguage = details?.originalLanguage ?: row.originalLanguage,
)

/**
 * Choose release's Play of [item] for [target]: chosen by hand, so tried alone, whatever is known of it (found missing
 * on the providers in use before included, which its row says). [alternatives]: the list's others, for the pre-check (L152).
 */
internal fun chosenRequest(target: MovieTarget, item: RankedItem, alternatives: List<RankedItem> = emptyList()) =
    PlayRequest(target.title, target.subtitle, listOf(item), FilePicker.Largest, target.watch, rememberedFirst = false, chosen = true, alternatives = alternatives)

/** What the page says under the movie's meta, Play's label, and a note under it for a movie still to come. */
internal data class MoviePageText(val line: String, val play: String, val note: String?)

/** A movie still to come plays all the same: releases often appear before TMDB's date. */
internal fun moviePageText(row: MovieRow, today: LocalDate): MoviePageText = when (row.state) {
    MovieState.InProgress -> MoviePageText(row.status, "Resume", null)
    // From the start: a watched state keeps no position.
    MovieState.Watched -> MoviePageText(row.status, "Play again", null)
    MovieState.Upcoming -> MoviePageText(row.status, "Play", movieNoReleaseNote(row.releaseDate, released = false, today))
    // Unknown: TMDB hasn't said yet (or can't); it was never played either way.
    MovieState.NotWatched, MovieState.Unknown -> MoviePageText("Not watched", "Play", null)
}

/** A TV's Play (its mockup): Resume says where it goes on from, as the Show page's does; the other labels are the phone's. */
internal fun tvMoviePlayLabel(row: MovieRow, text: MoviePageText, watch: WatchState?): String =
    if (row.state == MovieState.InProgress && watch != null) "${text.play} · ${mmss(watch.positionMs)}" else text.play

/** The Movie page's footnote, on phones and TVs: it says nothing of how to press. */
const val MOVIE_FOOTNOTE = "Play finds the best release on your indexers; the one that played before goes first. Details from TMDB."

/** The page's elements the focus comes back to ([ReturnFocus] keys). */
internal const val MOVIE_PLAY = "play"
internal const val MOVIE_CHOOSE = "choose"
