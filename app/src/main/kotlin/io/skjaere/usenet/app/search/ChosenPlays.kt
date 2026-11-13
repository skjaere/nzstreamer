package io.skjaere.usenet.app.search

import io.skjaere.usenet.app.play.PlayOutcome
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A release list's plays of the releases chosen on it, the last one only: one that fails before playback starts is
 * marked ([failed]), so another can be picked from the list, which stays under the play sheet meanwhile; [play]'s
 * onLaunched runs once the player launches. Stopped first (Cancel, another play), it leaves the list as it was.
 */
class ChosenPlays(private val scope: CoroutineScope) {
    private val _failed = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Why each release chosen on the list that failed to start didn't, by [RankedItem.key], in the play sheet's words. */
    val failed: StateFlow<Map<String, String>> = _failed.asStateFlow()
    private var job: Job? = null

    /** Plays [item] with [start], which says what came of it. */
    fun play(item: RankedItem, start: () -> Deferred<PlayOutcome>, onLaunched: () -> Unit = {}) {
        job?.cancel()
        val outcome = start()
        job = scope.launch {
            when (val o = outcome.await()) {
                PlayOutcome.Launched -> onLaunched()
                is PlayOutcome.Failed -> _failed.update { it + (item.key to o.reason(item.item.title)) }
                PlayOutcome.Stopped -> Unit
            }
        }
    }

    /** The list closed: what it marked goes with it, and a play still running marks nothing. */
    fun clear() {
        job?.cancel()
        job = null
        _failed.value = emptyMap()
    }
}
