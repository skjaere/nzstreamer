package io.skjaere.usenet.app.discover

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.discover.DiscoverFilters
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.discover.DiscoverQuery
import io.skjaere.usenet.discover.DiscoverTitle
import io.skjaere.usenet.discover.Filter
import io.skjaere.usenet.discover.Genre
import io.skjaere.usenet.discover.Language
import io.skjaere.usenet.discover.Period
import io.skjaere.usenet.discover.Service
import io.skjaere.usenet.discover.YearFilter
import io.skjaere.usenet.discover.plusNew
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * A list's See all page: its titles a step at a time (a TMDB page; for Trending filtered here, up to five), the next
 * asked as the user nears the end ([nearEnd]), one step at a time, each title once, up to TMDB's last page or the cap
 * ([io.skjaere.usenet.discover.DiscoverPage.MAX_PAGES]); a step that adds no title goes on to the next by itself. A
 * filter picked stops the step being asked and starts from the first. A step that fails keeps what came before, with Try
 * again ([retry]); TMDB saying to slow down is asked again after a wait. The filters are kept with the screen's state.
 */
class DiscoverListViewModel(private val deps: DiscoverDeps, initial: DiscoverQuery, private val saved: SavedStateHandle) : ViewModel() {
    private sealed interface Step {
        data object Loading : Step
        data object Done : Step
        data class Failed(val error: TmdbError) : Step
    }

    /**
     * The page's state: the titles so far, the last of TMDB's pages read for them ([page]) of [totalPages] (TMDB having
     * more than the cap: [capped]); the first step's state and a further one's ([more], null while none is asked).
     */
    private data class Paged(
        val hasKey: Boolean?,
        val query: DiscoverQuery,
        val titles: List<DiscoverTitle> = emptyList(),
        val page: Int = 0,
        val totalPages: Int = 1,
        val capped: Boolean = false,
        val first: Step = Step.Loading,
        val more: Step? = null,
        val offline: Duration? = null,
        val genres: List<Genre> = emptyList(),
        val services: List<Service> = emptyList(),
    )

    private val state = MutableStateFlow(Paged(deps.knownKey, restored(initial)))
    private val languages: List<Language> = deps.source.languages(deps.locale())
    private var job: Job? = null
    private var choices: Job? = null

    val ui: StateFlow<DiscoverListUi> = combine(state, collectedKeys(deps), deps.showArtwork.onStart { emit(true) }) { s, collected, artwork -> s.ui(collected, artwork) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, state.value.ui(emptySet(), true))

    init {
        viewModelScope.launch {
            deps.hasTmdbKey.collect { key ->
                state.update { it.copy(hasKey = key) }
                if (key) {
                    loadChoices()
                    restart()
                } else {
                    job?.cancel()
                    choices?.cancel()
                }
            }
        }
        viewModelScope.launch {
            deps.language.distinctUntilChanged().drop(1).collect {
                if (state.value.hasKey != true) return@collect
                loadChoices()
                restart()
            }
        }
    }

    /** The user is near the titles' end: the next step, unless one is being asked, the last failed, or there is no more. */
    fun nearEnd() {
        val s = state.value
        if (job?.isActive == true || s.hasKey != true || s.first != Step.Done || s.more != null || s.page >= s.totalPages) return
        step()
    }

    /** Try again: the first step, or the further one that failed (the titles before it kept). */
    fun retry() {
        val s = state.value
        if (s.hasKey != true) return
        when {
            s.first is Step.Failed -> restart()
            s.more is Step.Failed -> {
                job?.cancel()
                state.update { it.copy(more = null) }
                step()
            }
        }
    }

    /** A filter's choice, by its option's id ([FilterOption.id]). */
    fun pick(filter: Filter, id: String) {
        val q = state.value.query
        val f = q.filters
        val next = when (filter) {
            Filter.Period -> f.copy(period = Period.entries.firstOrNull { it.name == id } ?: f.period)
            Filter.Genre -> f.copy(genre = id.toIntOrNull())
            Filter.Language -> f.copy(language = id.ifEmpty { null })
            Filter.Year -> f.copy(year = YearFilter.entries.firstOrNull { it.name == id } ?: f.year)
            Filter.Service -> f.copy(service = id.toIntOrNull())
        }.on(q.type, q.list)
        if (next != f) setFilters(next)
    }

    /** Every filter that narrows the list back to none (Trending keeps its period, which only picks TMDB's list). */
    fun clearFilters() {
        val q = state.value.query
        val cleared = DiscoverFilters(period = q.filters.period).on(q.type, q.list)
        if (cleared != q.filters) setFilters(cleared)
    }

    private fun setFilters(filters: DiscoverFilters) {
        saved[PERIOD] = filters.period.name
        saved[GENRE] = filters.genre
        saved[LANGUAGE] = filters.language
        saved[YEAR] = filters.year.name
        saved[SERVICE] = filters.service
        state.update { it.copy(query = it.query.copy(filters = filters)) }
        restart()
    }

    /** The filters kept with the screen's state, else [initial]'s. */
    private fun restored(initial: DiscoverQuery): DiscoverQuery {
        if (!saved.contains(PERIOD)) return initial
        val filters = DiscoverFilters(
            period = Period.entries.firstOrNull { it.name == saved.get<String>(PERIOD) } ?: Period.Week,
            genre = saved[GENRE], language = saved[LANGUAGE],
            year = YearFilter.entries.firstOrNull { it.name == saved.get<String>(YEAR) } ?: YearFilter.Any,
            service = saved[SERVICE],
        ).on(initial.type, initial.list)
        return initial.copy(filters = filters)
    }

    /** From the first step again, the titles there were gone. */
    private fun restart() {
        job?.cancel()
        state.update { it.copy(titles = emptyList(), page = 0, totalPages = 1, capped = false, first = Step.Loading, more = null, offline = null) }
        if (state.value.hasKey == true) step()
    }

    private fun step() {
        val query = state.value.query
        job = viewModelScope.launch {
            var waits = 0
            while (true) {
                val from = state.value.page + 1
                // No title yet: the page's own loading and failure, not the footer's.
                val first = state.value.first != Step.Done
                if (!first) state.update { it.copy(more = Step.Loading) }
                try {
                    val answer = deps.source.page(query, from)
                    val page = answer.value
                    // A step that adds no title (filtered Trending's pages matching none, or titles already shown) goes on
                    // to the next while there is one: the screens ask as the titles grow, and nothing on them would change.
                    var goOn = false
                    state.update { s ->
                        if (s.query != query) return@update s
                        val titles = s.titles.plusNew(page.items)
                        goOn = titles.size == s.titles.size && page.hasMore
                        s.copy(
                            titles = titles, page = page.page, totalPages = page.totalPages, capped = page.capped,
                            first = if (goOn && first) Step.Loading else Step.Done, more = if (goOn && !first) Step.Loading else null,
                            offline = listOfNotNull(s.offline, answer.age.takeIf { answer.offline }).maxOrNull(),
                        )
                    }
                    if (!goOn) return@launch
                    waits = 0
                } catch (e: TmdbException) {
                    state.update { s ->
                        if (s.query != query) s else if (first) s.copy(first = Step.Failed(e.error)) else s.copy(more = Step.Failed(e.error))
                    }
                    // TMDB said to slow down: asked again after a wait, Try again meanwhile being the user's.
                    if (e.error !is TmdbError.RateLimited || waits >= deps.backoff.size) return@launch
                    delay(deps.backoff[waits++].toMillis())
                }
            }
        }
    }

    /** The genres and services the pills offer, when the list takes them; none when TMDB can't say. */
    private fun loadChoices() {
        choices?.cancel()
        val q = state.value.query
        val offered = q.list.filters(q.type)
        choices = viewModelScope.launch {
            if (Filter.Genre in offered) {
                try {
                    val genres = deps.source.genres(q.type)
                    state.update { it.copy(genres = genres) }
                } catch (e: TmdbException) {
                    // The pill offers All genres alone.
                }
            }
            if (Filter.Service in offered) {
                try {
                    val services = deps.source.services()
                    state.update { it.copy(services = services) }
                } catch (e: TmdbException) {
                    // The pill offers Any service alone.
                }
            }
        }
    }

    private fun Paged.ui(collected: Set<TitleKey>, artwork: Boolean): DiscoverListUi {
        val errors = listOfNotNull((first as? Step.Failed)?.error, (more as? Step.Failed)?.error)
        val key = when {
            hasKey == null -> null
            !hasKey || errors.any { it is TmdbError.NoKey } -> KeyState.Missing
            errors.any { it is TmdbError.InvalidKey } -> KeyState.Refused
            else -> KeyState.Ready
        }
        val cards = cards(titles, collected)
        val view = when (val f = first) {
            Step.Loading -> ListView.Loading
            is Step.Failed -> ListView.Failed(failureText(f.error))
            Step.Done -> if (titles.isEmpty()) ListView.Empty(canClear = query.filters.narrows) else ListView.Shown
        }
        val footer = when (val m = more) {
            is Step.Failed -> Footer.Failed(failureText(m.error))
            Step.Loading -> Footer.Loading
            else -> when {
                first != Step.Done || titles.isEmpty() || page < totalPages || !capped -> Footer.None
                // Trending filtered here shows only the matches among the 500 read.
                query.list == DiscoverList.Trending && query.filters.narrows -> Footer.Searched
                else -> Footer.Capped
            }
        }
        val f = query.filters
        val genre = genres.firstOrNull { it.id == f.genre }?.name
        val language = languages.firstOrNull { it.code == f.language }?.name ?: f.language
        val service = services.firstOrNull { it.id == f.service }?.name
        return DiscoverListUi(
            key, query, listTitle(query), cards, view, footer, pills(genre, language, service),
            summary(query, genre, language, service, cards.count { it.collected }), artwork, offline,
        )
    }

    /** The pills of the filters the list takes, in its order, each with its choices. */
    private fun Paged.pills(genre: String?, language: String?, service: String?): List<FilterPill> {
        val f = query.filters
        return query.list.filters(query.type).map { filter ->
            when (filter) {
                Filter.Period -> FilterPill(filter, f.period.label, Period.entries.map { FilterOption(it.name, it.label, it == f.period) })
                Filter.Genre -> FilterPill(
                    filter, genre ?: if (f.genre == null) ALL_GENRES else GENRE_PILL,
                    listOf(FilterOption("", ALL_GENRES, f.genre == null)) + genres.map { FilterOption("${it.id}", it.name, it.id == f.genre) },
                )
                Filter.Language -> FilterPill(
                    filter, language ?: ANY_LANGUAGE,
                    listOf(FilterOption("", ANY_LANGUAGE, f.language == null)) + languages.map { FilterOption(it.code, it.name, it.code == f.language) },
                )
                Filter.Year -> FilterPill(filter, f.year.label, YearFilter.entries.map { FilterOption(it.name, it.label, it == f.year) })
                Filter.Service -> FilterPill(
                    filter, service ?: ANY_SERVICE,
                    listOf(FilterOption("", ANY_SERVICE, f.service == null)) + services.map { FilterOption("${it.id}", it.name, it.id == f.service) },
                )
            }
        }
    }

    private companion object {
        const val PERIOD = "period"
        const val GENRE = "genre"
        const val LANGUAGE = "language"
        const val YEAR = "year"
        const val SERVICE = "service"
    }
}
