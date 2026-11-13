package io.skjaere.usenet.app.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.DeviceClock
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.CastButton
import io.skjaere.usenet.app.link.PlayOnPrimary
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.PlayingOnButton
import io.skjaere.usenet.app.link.RemoteNow
import io.skjaere.usenet.app.link.playingEpisodeOf
import io.skjaere.usenet.app.link.rememberRemotePosition
import io.skjaere.usenet.app.link.rememberRemoteEpisodeLine
import io.skjaere.usenet.app.play.ChooseFile
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.play.pageOkGuard
import io.skjaere.usenet.app.play.rememberPageOkGuard
import io.skjaere.usenet.app.search.Poster
import io.skjaere.usenet.app.search.ReleaseList
import io.skjaere.usenet.app.search.alternativesTo
import io.skjaere.usenet.app.search.forEpisode
import io.skjaere.usenet.app.search.rankedAgain
import io.skjaere.usenet.app.tv.LocalRailFocus
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.rememberReturnFocus
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.Removal
import io.skjaere.usenet.data.EpisodeRef as DataEpisodeRef
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.app.collection.model.toSummary
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeRef
import io.skjaere.usenet.metadata.Freshness
import io.skjaere.usenet.metadata.NextUp
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.ShowProgress
import io.skjaere.usenet.metadata.hasAired
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * The Show page: TMDB's answer about the show, which follows the stored facts its collection row shows
 * ([PageAnswer]), its seasons ([ShowSeasons]), what was watched, and the plays.
 */
class ShowViewModel(private val container: AppContainer, private val tmdbId: Int, private val clock: Clock = DeviceClock) : ViewModel() {
    private val answer = PageAnswer(viewModelScope, clock, { TmdbLookup.showAged(container, tmdbId) }, Freshness::answer) { aged, first ->
        // The collection's row and this page then agree; the refresher takes it for collected shows only.
        container.refresher.offer(aged.value, aged.age)
        // Open the season of the next episode to watch, once the library has answered.
        if (first) {
            val watches = container.library.episodeStates(tmdbId).map { it.values.toList().toEpisodeWatches() }
            selectSeason(ShowProgress.openingSeason(aged.value, watches, LocalDate.now(clock)))
        } else {
            // A newer answer while the page stays in front: the selected season past its time follows it.
            seasons.shown()
        }
    }
    val details: StateFlow<ShowDetails?> = answer.value
    val error: StateFlow<String?> = answer.error
    private val seasons = ShowSeasons(viewModelScope, clock) { TmdbLookup.seasonAged(container, tmdbId, it) }
    val selectedSeason: StateFlow<Int?> = seasons.selected
    val season: StateFlow<SeasonState> = seasons.state
    val nextUpError: StateFlow<String?> = seasons.nextUpError
    private val chooser = ReleaseChooser<Episode>(viewModelScope, container.listRankings)
    val choose: StateFlow<ChooseState<Episode>> = chooser.state
    val chosenFailed: StateFlow<Map<String, String>> = chooser.failed
    val states: StateFlow<Map<Pair<Int, Int>, WatchState>> = container.library.episodeStates(tmdbId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val collected = container.library.isCollected(tmdbId).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val showArtwork = container.settings.metadata.map { it.showArtwork }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    /** The device's date, which moves on at midnight and on a return to the front: what has aired and what is next. */
    val today: StateFlow<LocalDate> = container.today.stateIn(viewModelScope, SharingStarted.Eagerly, LocalDate.now(clock))
    /** What "In collection ✓" removed on this visit: tapped again, the show comes back as it was (when it was added included). */
    private var removal: Removal? = null

    init {
        // TMDB's language too: TmdbClient keeps each language's answers apart, so a page kept across a change asks in the new one.
        val language = container.settings.metadata.map { it.language }
        answer.follow(container.library.showSummary(tmdbId), language) { it.toSummary() }
        seasons.follow(language)
    }

    /** The page is in front again (back from the player, the app or another tab): what is past its time is asked again. */
    fun shown() {
        answer.shown()
        seasons.shown()
    }

    fun selectSeason(number: Int) {
        seasons.select(number)
    }

    /** A TV's season chips: the season the focus settles on is selected a moment later; OK selects at once. */
    private val dwell = SeasonDwell(viewModelScope, { seasons.selected.value }) { seasons.select(it) }

    fun seasonFocused(number: Int) = dwell.focused(number)

    fun seasonsLeft() = dwell.left()

    fun seasonPressed(number: Int) = dwell.pressed(number)

    private fun episodePlay(d: ShowDetails, e: Episode) = EpisodePlay.of(d, e)

    /** Plays [e] where Play on says (L7): this phone, or the TV chosen. */
    fun play(e: Episode) {
        val d = details.value ?: return
        container.playRouter.playEpisode(episodePlay(d, e))
    }

    /** Plays the next-up episode, fetching its season if needed. */
    fun playNext(ref: io.skjaere.usenet.metadata.EpisodeRef) {
        seasons.playNext(ref, ::play)
    }

    /** Choose release for the next-up episode (a TV page's left pane), its season fetched if needed, as [playNext]. */
    fun chooseNext(ref: io.skjaere.usenet.metadata.EpisodeRef) {
        seasons.playNext(ref, ::chooseRelease)
    }

    /**
     * Long-press: search like Play does, but show the ranked releases to pick from: once every indexer has answered, and
     * again as torrents join once checked (W71).
     */
    fun chooseRelease(e: Episode) {
        val d = details.value ?: return
        val ep = episodePlay(d, e)
        chooser.chooseAsFound(e) {
            flow {
                val context = container.rankingContext()
                container.finder.find(ep.request, container.indexerConfigs(), context).collect { event ->
                    val r = (event as? FinderEvent.Finished)?.result ?: (event as? FinderEvent.Joined)?.result ?: return@collect
                    val found = ResultGroup(
                        key = ep.label, kind = GroupKind.Episode, season = e.season, episodes = listOf(e.episode), airDate = null, year = null,
                        playable = r.playable.map { RankedItem(it.item, it.ranked) }, hidden = r.hidden.map { RankedItem(it.item, it.ranked) },
                    )
                    // Ranked for whom Play goes to, and again for each device chosen while the list shows (L127).
                    rankedBy(context) { found.rankedAgain(it.forEpisode(ep.request)) }
                    emit(found)
                }
            }
        }
    }

    /** Plays [item], picked from [e]'s list, which stays until it plays ([ReleaseChooser.play]). */
    fun playChosen(e: Episode, item: RankedItem) {
        val d = details.value ?: return
        chooser.play(item) { container.playRouter.chosen(chosenRequest(episodePlay(d, e), item, alternativesTo(item))) }
    }

    /** Choose file on [item]'s row in [e]'s list: plays it as [playChosen] does, asking which of its videos is [e]. */
    fun chooseFile(e: Episode, item: RankedItem) {
        val d = details.value ?: return
        chooser.play(item) { container.playRouter.chosen(chooseFileRequest(episodePlay(d, e), item, alternativesTo(item))) }
    }

    /** The list's other releases than [item], as it shows them, for the pre-check (L152). */
    private fun alternativesTo(item: RankedItem) = (chooser.state.value as? ChooseState.Ready)?.group?.alternativesTo(item).orEmpty()

    fun closeChooser() {
        chooser.close()
    }

    fun mark(episodes: List<Episode>, watched: Boolean) = viewModelScope.launch {
        val d = details.value ?: return@launch
        container.library.markEpisodes(episodes.map { DataEpisodeRef(tmdbId, it.season, it.episode, WatchTitle.episode(d.name, it.season, it.episode)) }, watched)
    }

    /** The toggle writes and stays here: no navigation. */
    fun toggleCollection() = viewModelScope.launch {
        val d = details.value ?: return@launch
        if (collected.value) {
            removal = container.library.removeTitles(shows = listOf(tmdbId))
        } else {
            val undone = removal?.let { container.library.undoRemove(it) } ?: 0
            removal = null
            if (undone == 0) {
                val now = System.currentTimeMillis()
                container.library.addShow(CollectedShow(CollectedShow.idFor(d.tmdbId), d.tmdbId, d.ids.tvdb, d.ids.imdb, d.ids.tvmaze, d.name, d.year, d.posterPath, now, now))
            }
            // As old as the answer is by now: the page may have been open a while.
            container.refresher.offer(d, Duration.between(answer.answeredAt, clock.instant()).coerceAtLeast(Duration.ZERO))
        }
    }
}

/**
 * A show's page. While the TV plays one of its episodes for this phone (W14), its button reads "Playing S02E04 · 32:10 on
 * Living room TV" and that episode's row "On Living room TV · 32:10": either opens the remote ([onRemote]).
 */
@Composable
fun ShowScreen(tmdbId: Int, onBack: () -> Unit, onRemote: () -> Unit = {}) {
    val container = LocalContext.current.container
    val vm: ShowViewModel = viewModel(key = "show-$tmdbId") { ShowViewModel(container, tmdbId) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.shown() }
    val details by vm.details.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val selected by vm.selectedSeason.collectAsStateWithLifecycle()
    val season by vm.season.collectAsStateWithLifecycle()
    val nextUpError by vm.nextUpError.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val collected by vm.collected.collectAsStateWithLifecycle()
    val artwork by vm.showArtwork.collectAsStateWithLifecycle()
    val choose by vm.choose.collectAsStateWithLifecycle()
    val chosenFailed by vm.chosenFailed.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    // Where Play goes (W11); a TV has no choice to make.
    val playOn by container.playOn.target.collectAsStateWithLifecycle()
    // The play this phone follows on a TV or a Cast device (W14, L91): a TV plays its own, and follows none.
    val remotePlay by remember(container) { container.remotePlaying.now }.collectAsStateWithLifecycle()
    val onTv = remotePlay?.takeIf { it.playingEpisodeOf(tmdbId) != null }
    // The details from TMDB may come after the page is up (a cold cache), with nothing to focus before: on a TV the
    // shell then puts the focus on the page, as it does when the screen changes.
    val rail = LocalRailFocus.current
    LaunchedEffect(details != null) { if (details != null) rail?.contentChanged() }
    val okGuard = rememberPageOkGuard()
    val tv = LocalFormFactor.current == FormFactor.Tv
    // A TV's: the element that opened the chooser, and the episodes' place, kept while the chooser takes the page's place.
    val focus = rememberReturnFocus()
    val tvMemory = remember { TvShowMemory() }

    when (val c = choose) {
        is ChooseState.Ready -> {
            BackHandler { vm.closeChooser() }
            ReleaseList(
                c.group, details?.name.orEmpty(), onBack = vm::closeChooser, failed = chosenFailed, onChooseFile = { vm.chooseFile(c.target, it) },
                rankedFor = c.rankedFor,
            ) {
                vm.playChosen(c.target, it)
            }
            return
        }
        is ChooseState.Searching -> {
            BackHandler { vm.closeChooser() }
            Column(Modifier.fillMaxSize()) {
                TopBar("S%02dE%02d".format(c.target.season, c.target.episode), vm::closeChooser, subtitle = "Finding releases…")
                CircularProgressIndicator(Modifier.padding(24.dp), color = colors.accent)
                if (tv) ChooserCancel(vm::closeChooser)
            }
            return
        }
        ChooseState.None -> Unit
    }

    val d = details
    if (tv && d != null) {
        TvShowContent(ShowPageUi(d, selected, season, states, collected, artwork, today, nextUpError), vm, focus, tvMemory, okGuard)
        return
    }
    Column(Modifier.fillMaxSize().pageOkGuard(okGuard)) {
        // A TV's page has no bar: its title is its heading, and the remote's Back goes back.
        if (!tv) {
            TopBar(d?.name ?: "", onBack) {
                // The Cast button, before the collection's toggle, on phones and tablets alone (L36, L115).
                if (!container.television) CastButton(playOn, container.playOn::openSheet)
                if (d != null) TextButton(onClick = { vm.toggleCollection() }) {
                    Text(if (collected) "In collection ✓" else "+ Collection", style = AppType.secondary, color = if (collected) colors.muted else colors.accent)
                }
            }
        }
        if (d == null) {
            error?.let { PageLoadFailure(SHOW_LOAD_FAILED + it, vm::shown) } ?: CircularProgressIndicator(Modifier.padding(24.dp), color = colors.accent)
            return@Column
        }
        val progress = ShowProgress.of(d, states.values.toList().toEpisodeWatches(), today, d.recentlyAired())
        LazyColumn(Modifier.fillMaxSize()) {
            item { ShowHeader(d, progress, artwork, nextUpError, playOn, onTv, onRemote) { ref -> vm.playNext(ref) } }
            item {
                SeasonChips(
                    d.seasons, selected, { n -> states.count { (key, st) -> key.first == n && st.watched } }, vm::selectSeason,
                    Modifier.padding(vertical = 12.dp),
                )
            }
            val s = (season as? SeasonState.Loaded)?.season
            val failed = season as? SeasonState.Failed
            if (failed != null) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(SEASON_LOAD_FAILED + failed.message, style = AppType.secondary, color = colors.textSecondary)
                        SecondaryButton("Try again", { selected?.let(vm::selectSeason) })
                    }
                }
            } else if (s == null) {
                item { CircularProgressIndicator(Modifier.padding(24.dp), color = colors.accent) }
            } else {
                val mark = seasonMark(s.episodes, { d.hasAired(it, today) }, { states[it.season to it.episode]?.watched == true })
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.mark(mark.episodes, mark.watched) }, enabled = mark.enabled) {
                            Text(mark.label, style = AppType.secondary, color = colors.accent)
                        }
                    }
                }
                item {
                    GroupCard {
                        s.episodes.forEachIndexed { i, e ->
                            if (i > 0) InsetDivider()
                            // The episode the TV or the Cast device plays says so, and opens the remote rather than starting it over (W14, L91).
                            val playing = onTv?.takeIf { it.playingEpisodeOf(tmdbId) == e.season to e.episode }
                            EpisodeRow(
                                e, states[e.season to e.episode], d.hasAired(e, today), onPlay = if (playing != null) onRemote else { { vm.play(e) } },
                                onChoose = { vm.chooseRelease(e) }, onMark = { w -> vm.mark(listOf(e), w) },
                                still = e.stillPath, showArtwork = artwork, current = progress.nextUp.episodeRef() == EpisodeRef(e.season, e.episode),
                                onTv = playing?.let { rememberRemoteEpisodeLine(container.remotePlaying, it) },
                            )
                        }
                    }
                }
                item { Footnote(SHOW_FOOTNOTE_PHONE) }
            }
        }
    }
}

@Composable
private fun ShowHeader(
    d: ShowDetails,
    p: ShowProgress,
    artwork: Boolean,
    nextUpError: String?,
    playOn: PlayTarget,
    onTv: RemoteNow?,
    onRemote: () -> Unit,
    onPlayNext: (io.skjaere.usenet.metadata.EpisodeRef) -> Unit,
) {
    val container = LocalContext.current.container
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Poster(d.posterPath, artwork, 92, 138)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val seasons = d.seasons.count { !it.isSpecials }
                Text(listOfNotNull(d.year?.toString(), "$seasons season${if (seasons == 1) "" else "s"}", d.status).joinToString(" · "), style = AppType.secondary, color = colors.muted)
                if (p.aired > 0) {
                    Text("${p.watched} of ${p.aired} watched", style = AppType.secondary, color = colors.textSecondary)
                    LinearProgressIndicator(
                        progress = { p.watched.toFloat() / p.aired }, modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = colors.accent, trackColor = colors.divider, drawStopIndicator = {},
                    )
                }
                Box(Modifier.padding(top = 6.dp)) {
                    if (onTv != null) {
                        PlayingOnButton(onTv, rememberRemotePosition(container.remotePlaying, onTv), onRemote)
                        return@Box
                    }
                    when (val next = p.nextUp) {
                        is NextUp.Resume -> PlayOnPrimary("Resume ${next.episode.label} · ${mmss(next.positionMs)}", playOn, { onPlayNext(next.episode) })
                        is NextUp.Play -> PlayOnPrimary("Play ${next.episode.label}", playOn, { onPlayNext(next.episode) })
                        is NextUp.UpToDate -> Text(next.returns?.let { "Up to date · next episode ${shortDate(it)}" } ?: "Up to date", style = AppType.secondary, color = colors.muted)
                    }
                }
                nextUpError?.let { Text(it, style = AppType.small, color = colors.warn) }
            }
        }
        d.overview?.let { Text(it, style = AppType.secondary, color = colors.textSecondary, maxLines = 4) }
    }
}

/**
 * Choose release's Play of [item] for [ep]: chosen by hand, so tried alone, whatever is known of it (found missing on
 * the providers in use before included, which its row says). [alternatives]: the list's others, for the pre-check (L152).
 */
internal fun chosenRequest(ep: EpisodePlay, item: RankedItem, alternatives: List<RankedItem> = emptyList()) = PlayRequest(
    ep.showName, ep.subtitle, listOf(item), FilePicker.Episode(ep.season, ep.episode),
    WatchTarget(WatchKind.Episode, WatchTitle.episode(ep.showName, ep.season, ep.episode), showTmdbId = ep.showTmdbId, season = ep.season, episode = ep.episode),
    rememberedFirst = false,
    chosen = true,
    episodeName = ep.episodeName,
    alternatives = alternatives,
)

/** The season's mark button: [label], whether it's [enabled], and the [episodes] it marks [watched]. */
internal data class SeasonMark(val label: String, val enabled: Boolean, val episodes: List<Episode>, val watched: Boolean)

/**
 * A season counts as watched once its aired episodes are, and marking it watched marks those.
 * Marking it unwatched also takes an episode watched before TMDB's date (releases often come
 * early), so the season starts over rather than staying up to date.
 */
internal fun seasonMark(episodes: List<Episode>, aired: (Episode) -> Boolean, watched: (Episode) -> Boolean): SeasonMark {
    val airedOnes = episodes.filter(aired)
    val allWatched = airedOnes.isNotEmpty() && airedOnes.all(watched)
    return if (allWatched) SeasonMark("Mark season unwatched", true, episodes.filter(watched), watched = false)
    else SeasonMark("Mark season watched", airedOnes.isNotEmpty(), airedOnes, watched = true)
}

/** Choose release's "Choose file" on [item]'s row: its Play for [ep], asking which of its videos is the episode. */
internal fun chooseFileRequest(ep: EpisodePlay, item: RankedItem, alternatives: List<RankedItem> = emptyList()) =
    chosenRequest(ep, item, alternatives).copy(picker = ChooseFile(FilePicker.Episode(ep.season, ep.episode)))

/** The Show page's footnote on a phone, and on a TV, whose remote has no tap. */
const val SHOW_FOOTNOTE_PHONE = "Tap an episode to play the best release, or ⋮ to choose one yourself."
const val SHOW_FOOTNOTE_TV = "Press OK on an episode to play the best release, or ⋮ to choose one yourself."
