package io.skjaere.usenet.app.collection

import io.skjaere.usenet.app.play.PlayOutcome
import io.skjaere.usenet.app.search.ChosenPlays
import io.skjaere.usenet.app.search.rankedFor
import io.skjaere.usenet.app.search.ranksAs
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Choose release" for a [T] (an episode, a movie): nothing chosen, its search running, or what it found. */
sealed interface ChooseState<out T> {
    data object None : ChooseState<Nothing>
    data class Searching<T>(val target: T) : ChooseState<T>

    /** What the search found for [target], ranked for the Cast device [rankedFor] names (L127), or for this device (null). */
    data class Ready<T>(val target: T, val group: ResultGroup, val rankedFor: String? = null) : ChooseState<T>
}

/**
 * A list's search, which says how it ranked the releases it found ([rankedBy]), so that they rank again as another device
 * is chosen in Play on (L127).
 */
class ListSearch internal constructor() {
    internal var ranked: RankedBy? = null
        private set

    /** The releases found are ranked by [context]; [again] ranks the same releases by another, with no search. */
    fun rankedBy(context: RankingContext, again: (RankingContext) -> ResultGroup) {
        ranked = RankedBy(context, again)
    }
}

/** How a list's releases are ranked ([context]), and how the same releases rank by another ([again]). */
internal class RankedBy(val context: RankingContext, val again: (RankingContext) -> ResultGroup)

/**
 * "Choose release": searches for an episode's or a movie's releases and shows them to pick from. One
 * search at a time: choosing again or closing stops the running one, which spends indexer calls.
 * The list stays while a release picked from it starts (under the play sheet): one that fails is
 * marked with why ([failed]), so another can be picked; once one plays, the list closes, so the
 * player's Back leads to the page.
 *
 * A list whose search said how it ranked its releases ([ListSearch.rankedBy]) ranks them again, with no search, as a
 * choice is made in Play on ([rankings], L127): for the Cast device chosen, its "Ranked for" line saying so, or for this
 * device; what it marked stays. This phone coming back by itself says nothing there, so the list keeps its order until
 * Choose release opens it again. The ranking runs on [dispatcher], off the main thread.
 */
class ReleaseChooser<T : Any>(
    private val scope: CoroutineScope,
    private val rankings: Flow<RankingContext> = emptyFlow(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow<ChooseState<T>>(ChooseState.None)
    val state: StateFlow<ChooseState<T>> = _state.asStateFlow()
    private var job: Job? = null
    private val plays = ChosenPlays(scope)
    val failed: StateFlow<Map<String, String>> = plays.failed

    /** Searches with [search] and shows what it found for [target]. */
    fun choose(target: T, search: suspend ListSearch.() -> ResultGroup?) {
        chooseAsFound(target) { flow { search()?.let { emit(it) } } }
    }

    /**
     * Searches with [search] and shows what it found for [target] at its first list, then each list it gives after, as
     * more join it (W71: torrents once checked), each ranked as the list is ranked by then, what it marked kept.
     */
    fun chooseAsFound(target: T, search: ListSearch.() -> Flow<ResultGroup>) {
        job?.cancel()
        plays.clear()
        _state.value = ChooseState.Searching(target)
        job = scope.launch {
            val list = ListSearch()
            // Each list found, with how its search ranked it; and each choice made in Play on since the search began.
            val found = list.search().map { it to list.ranked }
            val chosen = flow<RankingContext?> {
                emit(null)
                emitAll(rankings)
            }
            combine(found, chosen) { group, by -> group to by }.collectLatest { (foundRanked, by) ->
                val (group, ranked) = foundRanked
                // Ranked for another than the choice made: ranked again for it, with no search.
                val again = ranked?.takeIf { by != null && !it.context.ranksAs(by) }
                val shown = if (again != null && by != null) withContext(dispatcher) { again.again(by) } else group
                // Only while the user still waits for this one, or looks at its list: a dropped search mustn't pop its list up.
                val now = _state.value
                if (now != ChooseState.Searching(target) && (now as? ChooseState.Ready)?.target != target) return@collectLatest
                _state.value = ChooseState.Ready(target, shown, (if (again != null) by else ranked?.context)?.rankedFor)
            }
        }
    }

    /** Plays [item], picked from the list, with [start], which says what came of it. */
    fun play(item: RankedItem, start: () -> Deferred<PlayOutcome>) {
        if (_state.value !is ChooseState.Ready) return
        plays.play(item, start, onLaunched = ::close)
    }

    fun close() {
        job?.cancel()
        job = null
        plays.clear()
        _state.value = ChooseState.None
    }
}
