package io.skjaere.usenet.app.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.NavRow
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.tvFocusMemory
import io.skjaere.usenet.plugin.ItemReleases
import io.skjaere.usenet.plugin.NzPlugin
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.PluginHost
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.PluginTab
import io.skjaere.usenet.plugin.ReleaseInfo
import io.skjaere.usenet.plugin.WatchInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * An in-process plugin of the device tests' own (L595, L622), put in a slot of a registry through its test seam by
 * [FakePluginRule]. As a plugin, through what its host gives it: its tab, whose [label] it gives each time a version of it
 * loads, so a test changes it before a reload; its own page, whose rows open its second page (with an argument, and Back)
 * and its settings, play its one item ([item], with what [releases] hands the host) and mark it watched, the item's state
 * as the host's watch says it; its settings page; a search of the user's indexers ([search]); and work in its host's scope
 * that throws ([failIn]). On a TV each page takes its first focus as the skeleton's do, and keeps the row that had the
 * focus for the remote's way back from the rail (the kit's tvFocusMemory), as a plugin's scrolling page must: its scroll
 * is a focus group of its own, which the shell's content would otherwise enter at its first row. Its classes are the test
 * APK's, outside any plugin's package; its words are invented.
 */
class FakePlugin(val id: String = FAKE, label: String = "Fake") : NzPlugin {
    /** The label its next version's tab() gives. */
    @Volatile
    var label: String = label

    /** What its item's Play hands the host: nothing accepted, unless a test sets what its search found. */
    @Volatile
    var releases: suspend () -> ItemReleases = { ItemReleases(emptyList()) }

    /** How many of its instances were let go. */
    val closed = AtomicInteger()

    /** Its pages composed now, and how many were as each close() was called: none, by NzPlugin.close's promise. */
    private val composed = AtomicInteger()
    val composedAtClose: MutableList<Int> = java.util.concurrent.CopyOnWriteArrayList()

    /** Its one item: a game of an invented cup, its key stored as item:<id>:cc-2019:final. */
    val item = PlayItem(ITEM_KEY, ITEM_TITLE, ITEM_SUBTITLE, runtimeMinutes = 118, noun = "game")

    override fun tab(): PluginTab = PluginTab(label, Icons.Filled.Favorite)

    @Composable
    override fun Page(page: String, arg: String?, host: PluginHost, nav: PluginNav) {
        Counted()
        when (page) {
            "" -> OwnPage(host, nav)
            else -> SecondPage(arg, nav)
        }
    }

    @Composable
    override fun Settings(host: PluginHost, nav: PluginNav) {
        Counted()
        val first = rememberFirstFocus()
        Column(Modifier.fillMaxSize().tvFocusMemory().verticalScroll(rememberScrollState())) {
            TopBar(settingsTitle(label), onBack = { nav.back() })
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Nothing to set up for the $label plugin.", style = AppType.body, color = colors.text)
                PrimaryButton(DONE, onClick = { nav.back() }, modifier = Modifier.focusRequester(first))
            }
        }
    }

    override fun close() {
        composedAtClose += composed.get()
        closed.incrementAndGet()
    }

    /** Counts a page of it composed, from its composition until it leaves it. */
    @Composable
    private fun Counted() {
        DisposableEffect(Unit) {
            composed.incrementAndGet()
            onDispose { composed.decrementAndGet() }
        }
    }

    /** Work of its own in [host]'s scope, as a list's refresh would be, that fails as it starts. */
    fun failIn(host: PluginHost): Job = host.scope.launch { throw IllegalStateException("The $label plugin's refresh didn't read") }

    /** What its search of the user's indexers for [words] found once every indexer answered, through [host], as a plugin searches. */
    suspend fun search(host: PluginHost, words: String): List<ReleaseInfo> =
        withTimeoutOrNull(SEARCH_MS) { host.search.search(words, item.runtimeMinutes).toList().lastOrNull()?.releases }
            ?: throw AssertionError("the $label plugin's search for $words found nothing in ${SEARCH_MS / 1000} s")

    /** Its own page: its rows, the first taking a TV's first focus, and its item's state under the last. */
    @Composable
    private fun OwnPage(host: PluginHost, nav: PluginNav) {
        val states by remember(host) { host.watch.observe(listOf(ITEM_KEY)) }.collectAsState(emptyMap())
        val first = rememberFirstFocus()
        Column(Modifier.fillMaxSize().tvFocusMemory().verticalScroll(rememberScrollState())) {
            TopBar(ownPage(label), onBack = null)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NavRow(SECOND_PAGE, modifier = Modifier.focusRequester(first)) { nav.open(SECOND, secondArg(label)) }
                NavRow(SETTINGS) { nav.openSettings() }
                NavRow(PLAY) { host.play.play(item) { releases() } }
                NavRow(MARK, status = watchLine(states[ITEM_KEY])) {
                    host.scope.launch { host.watch.mark(listOf(ITEM_KEY to ITEM_TITLE), watched = true) }
                }
            }
        }
    }

    /** Its second page: the argument it was opened with, and a button that goes back as Back does. */
    @Composable
    private fun SecondPage(arg: String?, nav: PluginNav) {
        val first = rememberFirstFocus()
        Column(Modifier.fillMaxSize().tvFocusMemory().verticalScroll(rememberScrollState())) {
            TopBar(SECOND_PAGE, onBack = { nav.back() })
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(arg ?: "Opened with nothing", style = AppType.body, color = colors.text)
                PrimaryButton(GO_BACK, onClick = { nav.back() }, modifier = Modifier.focusRequester(first))
            }
        }
    }

    companion object {
        /** The fake's id, its item stored as item:fake:<key>; a second fake is another id ([KESTREL]). */
        const val FAKE = "fake"
        const val KESTREL = "kestrel"

        const val ITEM_KEY = "cc-2019:final"
        const val ITEM_TITLE = "Copper Crown · Final"
        const val ITEM_SUBTITLE = "KCL"

        /** Its rows' and buttons' words. */
        const val SECOND = "second"
        const val SECOND_PAGE = "Second page"
        const val SETTINGS = "Settings"
        const val PLAY = "Play $ITEM_TITLE"
        const val MARK = "Mark $ITEM_TITLE watched"
        const val GO_BACK = "Go back"
        const val DONE = "Done"

        /** Its item's state as its own page says it. */
        const val NOT_WATCHED = "Not watched"
        const val IN_PROGRESS = "In progress"
        const val WATCHED = "Watched"

        /** How long its search may take: the indexers' own time limits and then some. */
        private const val SEARCH_MS = 60_000L

        /** Its own page's title, by the label it had as it was drawn. */
        fun ownPage(label: String) = "The $label tab's own page"

        fun settingsTitle(label: String) = "$label settings"

        /** What its own page opens its second page with. */
        fun secondArg(label: String) = "Opened from the $label tab"

        /** Its item's [state], as its own page says it. */
        fun watchLine(state: WatchInfo?): String = when {
            state == null -> NOT_WATCHED
            state.watched -> WATCHED
            else -> IN_PROGRESS
        }
    }
}

/** On a TV, a page's first focus once the rail lets it take one, as the skeleton's pages take theirs; a phone's take none. */
@Composable
private fun rememberFirstFocus(): FocusRequester {
    val first = remember { FocusRequester() }
    val initial = rememberInitialFocus()
    val tv = LocalFormFactor.current == FormFactor.Tv
    LaunchedEffect(tv) { if (tv) initial.request(first) }
    return first
}
