package io.skjaere.usenet.app.plugins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.ItemPlay
import io.skjaere.usenet.app.play.PlayOutcome
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.search.ChosenPlays
import io.skjaere.usenet.app.search.ReleaseList
import io.skjaere.usenet.app.search.alternativesTo
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * The host's Choose release for a plugin's item (L628; SpPhoneChoose, SpTvChoose): [token]'s list ([PluginReleases]) in the
 * app's release list, the item's title in its bar over its subtitle and the count, the releases the plugin accepted, then
 * its possible matches with their reasons, and its line of what it left out. A tap plays that release alone, chosen, as
 * the item's Play would ([ItemPlay.chosen]), where Play goes; one that fails is marked as the play sheet says, one that
 * launches closes the page ([onClose]), as a film's Choose release closes. A token no longer kept (a process death) closes
 * it at once. [onBack]: its bar's back arrow.
 */
@Composable
fun PluginReleasesScreen(token: String, onBack: () -> Unit, onClose: () -> Unit) {
    val container = LocalContext.current.container
    val vm: PluginReleasesViewModel = viewModel(key = token) { PluginReleasesViewModel(container.pluginReleases, token, container.playRouter::chosen) }
    val close by rememberUpdatedState(onClose)
    val list = vm.list
    if (list == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val failed by vm.failed.collectAsStateWithLifecycle()
    // Not on the screen's lifecycle: the player is in front as it launches, and the page goes under it.
    LaunchedEffect(vm) {
        vm.launched.first { it }
        close()
    }
    ReleaseList(
        list.accepted, list.target.subtitle.orEmpty(), onBack = onBack, failed = failed, possible = list.possible, heading = list.target.title,
        onPlay = vm::play,
    )
}

/**
 * A plugin's Choose release page's state: [list], its token's, null where none is kept; its plays ([ChosenPlays]) through
 * [play], each release chosen as its item's ([ItemPlay.chosen]), the list's other releases Play would pick kept for the
 * pre-check; [launched] once one did. The token's list is let go as the page is left.
 */
class PluginReleasesViewModel(
    private val lists: PluginReleases,
    private val token: String,
    private val play: (PlayRequest) -> Deferred<PlayOutcome>,
) : ViewModel() {
    val list: PluginReleaseList? = lists[token]
    private val plays = ChosenPlays(viewModelScope)

    /** Why each release chosen here that failed to start didn't, by its key, in the play sheet's words. */
    val failed: StateFlow<Map<String, String>> = plays.failed

    private val _launched = MutableStateFlow(false)

    /** A release chosen here launched its player: the page closes. */
    val launched: StateFlow<Boolean> = _launched.asStateFlow()

    fun play(item: RankedItem) {
        val list = list ?: return
        plays.play(item, { play(ItemPlay.chosen(list.target, item, list.accepted.alternativesTo(item))) }, onLaunched = { _launched.value = true })
    }

    override fun onCleared() {
        lists.close(token)
    }
}
