package io.skjaere.usenet.app.plugins

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.skjaere.usenet.app.play.ItemTarget
import io.skjaere.usenet.app.play.PlayOutcome
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.search.PossibleMatches
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.plugin.FileInfo
import io.skjaere.usenet.plugin.Offered
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.PluginFilePicker
import io.skjaere.usenet.plugin.ReleaseHandle
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L628: the host's Choose release for a plugin's item. A plugin's openReleases (and its Play with only possible matches,
 * L627) leaves its releases with the host under a token: the ones it accepted, playable here or hidden as the searches
 * ranked them, then the ones it offered under "Possible matches", each with its reason, and its line of what it left
 * out; a handle the host no longer keeps, or another plugin's, is left out. The lines over the possible matches name
 * the item by its noun, "the right game" on the board, or "the right release" with none. A tap plays that release alone,
 * chosen, as the item's Play would; one that fails is marked, one that launches closes the page; the token goes as the
 * page is left, and a token no longer kept has no list. The plugin, its items and releases are invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginReleasesTest {
    @BeforeEach
    fun main() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun reset() {
        Dispatchers.resetMain()
    }

    private fun ranked(indexer: String, guid: String, title: String, playable: Boolean = true) = RankedItem(
        NewznabItem(
            indexerId = indexer, guid = guid, title = title, sizeBytes = 3_000_000_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(
            ReleaseFacts("$indexer/$guid", title, 3_000_000_000), ReleaseParser.parse(title), playable = playable, score = 1.0,
            reasons = if (playable) emptyList() else listOf(ReleaseReason.DiscImage), estimatedBitrate = null,
        ),
    )

    private val kestrel = ranked("a", "lbf-ccu-kestrel", "KCL.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.1080p50.WEB.h264-KESTREL")
    private val wren = ranked("b", "lbf-ccu-wren", "Lantern.Bay.vs.Copper.Crown.2026.11.21.720p50.WEB.h264-WREN")
    private val disc = ranked("a", "lbf-ccu-disc", "KCL.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.COMPLETE.BLURAY-QUAY", playable = false)
    private val driftwood = ranked("c", "lbf-ccu-driftwood", "Lantern.Bay.FC.vs.Copper.Crown.United.1080p.WEB.h264-DRIFTWOOD")
    private val codes = ranked("b", "lbf-ccu-codes", "KCL.2026.11.21.LBF.vs.CCU.720p.WEB.h264-WREN")
    private val iso = ranked("c", "lbf-ccu-iso", "Lantern.Bay.FC.vs.Copper.Crown.United.ISO-TIDE", playable = false)

    // The plugin's handles and the host's table for them: handle 6 is one the table dropped.
    private val handles = List(8) { i -> object : ReleaseHandle { override fun toString() = "handle $i" } }
    private val table = mapOf(
        handles[0] to kestrel, handles[1] to wren, handles[2] to disc, handles[3] to driftwood, handles[4] to codes, handles[5] to iso,
    )
    private val asked = mutableListOf<String>()
    private val failures = mutableListOf<String>()
    private val calls = PluginCalls { v, what, e -> failures += "${v.id} ${v.versionCode} $what ${e.javaClass.simpleName}" }
    private val lists = PluginReleases()
    private val service = PluginPlayService({ id, given -> asked += id; given.mapNotNull(table::get) }, lists) { _, _ -> error("nothing plays here") }
    private val version = PluginVersion("kestrel", 3, key = 11)

    private val game = PlayItem("ev-000731:main", "Lantern Bay FC vs. Copper Crown United", "KCL", 105, noun = "game")
    private val leftOut = "6 left out: highlights, a name with a score, other games. Their names aren’t shown."

    private fun open(
        item: PlayItem = game,
        accepted: List<ReleaseHandle> = listOf(handles[1], handles[0], handles[2], handles[6]),
        offered: List<Offered> = listOf(Offered(handles[3], "No date in the name"), Offered(handles[4], "Team codes only"), Offered(handles[5], "A disc image")),
        leftOut: String? = this.leftOut,
    ): String = assertNotNull(service.chooser(version, calls).open(item, accepted, offered, leftOut))

    @Test
    fun `the two groups come from the plugin's handles, playable and hidden as the searches ranked them, a dropped one left out`() {
        val list = assertNotNull(lists[open()])
        assertEquals(listOf(wren, kestrel), list.accepted.playable, "in the plugin's order, a dropped handle left out")
        assertEquals(listOf(disc), list.accepted.hidden)
        assertEquals(listOf(driftwood, codes), list.possible.group.playable)
        assertEquals(listOf(iso), list.possible.group.hidden)
        assertEquals(
            mapOf(driftwood.key to "No date in the name", codes.key to "Team codes only", iso.key to "A disc image"),
            list.possible.reasons,
            "each offered one's reason, by the release",
        )
        assertEquals(leftOut, list.possible.leftOut)
        assertFalse(list.onlyPossible)
        assertTrue(asked.isNotEmpty() && asked.all { it == "kestrel" }, "only its own table: $asked")
        assertEquals(emptyList(), failures)
    }

    /** The item as its Play plays it: its key, title and subtitle, its noun, and its picker made the host's, guarded. */
    @Test
    fun `the list keeps the item as its Play has it, its picker the plugin's`() {
        val picker = object : PluginFilePicker {
            override fun pick(files: List<FileInfo>): Int? = 0
            override val noMatch = "No file for the game"
            override fun takesWhole(releaseName: String): Boolean = throw IllegalStateException("the plugin's")
        }
        val list = assertNotNull(lists[open(PlayItem("ev-000731:main", game.title, game.subtitle, 105, noun = "game", picker = picker))])
        val target = list.target
        assertEquals(ItemTarget("kestrel", "ev-000731:main", game.title, game.subtitle, target.picker, noun = "game"), target)
        assertEquals(WatchTarget(WatchKind.Item, game.title, itemKey = "item:kestrel:ev-000731:main"), target.watch)
        assertNotEquals<FilePicker>(FilePicker.Largest, target.picker)
        assertEquals("No file for the game", target.picker.noMatch.text)
        assertFalse(target.picker.takesWhole(kestrel.item.title), "a throwing takesWhole answers no")
        assertEquals(listOf("kestrel 3 takesWhole IllegalStateException"), failures, "and turns its version off, guarded")
    }

    /** A release the plugin both accepted and offered shows once, among the accepted; one offered twice, once. */
    @Test
    fun `a release listed twice shows once, where it was first accepted`() {
        val list = assertNotNull(lists[open(accepted = listOf(handles[0]), offered = listOf(Offered(handles[0], "No date in the name"), Offered(handles[3], "Team codes only"), Offered(handles[3], "Again")))])
        assertEquals(listOf(kestrel), list.accepted.playable)
        assertEquals(listOf(driftwood), list.possible.group.playable)
        assertEquals(mapOf(driftwood.key to "Team codes only"), list.possible.reasons)
    }

    @Test
    fun `the lines over the possible matches name the item by its noun`() {
        val list = assertNotNull(lists[open()])
        assertEquals("Play never picks these. Tap one if it's the right game.", list.possible.line(only = false, tv = false))
        assertEquals("Nothing names this game for sure yet. Play never picks these; tap one if it's the right game.", list.possible.line(only = true, tv = false))
        assertEquals("Play never picks these. Press OK on one if it's the right game.", list.possible.line(only = false, tv = true))
        assertEquals("Nothing names this game for sure yet. Play never picks these; press OK on one if it's the right game.", list.possible.line(only = true, tv = true))
        val session = assertNotNull(lists[open(PlayItem("gp-kestrel:race", "Race", "Grand Prix of Kestrel", 120, noun = "session"))])
        assertEquals("Play never picks these. Press OK on one if it's the right session.", session.possible.line(only = false, tv = true))
    }

    /** An item with no noun, or one outside its rule (which counts as none): the general words, this spec's. */
    @Test
    fun `with no noun the lines say the right release`() {
        for (noun in listOf(null, "Game!", "")) {
            val list = assertNotNull(lists[open(PlayItem("ev-000731:main", game.title, game.subtitle, 105, noun = noun))])
            assertNull(list.target.noun, "noun $noun")
            assertEquals("Play never picks these. Tap one if it's the right release.", list.possible.line(only = false, tv = false))
            assertEquals("Nothing names it for sure yet. Play never picks these; tap one if it's the right release.", list.possible.line(only = true, tv = false))
            assertEquals("Play never picks these. Press OK on one if it's the right release.", list.possible.line(only = false, tv = true))
            assertEquals("Nothing names it for sure yet. Play never picks these; press OK on one if it's the right release.", list.possible.line(only = true, tv = true))
        }
        assertEquals("Possible matches", PossibleMatches.HEADING)
        assertEquals("Possible", PossibleMatches.RANK)
    }

    /** Nothing accepted: the possible matches alone, under their own line; every accepted handle dropped reads the same. */
    @Test
    fun `an empty accepted group shows the possible matches alone`() {
        for (accepted in listOf(emptyList(), listOf(handles[6]))) {
            val list = assertNotNull(lists[open(accepted = accepted, leftOut = null)])
            assertEquals(0, list.accepted.size)
            assertTrue(list.onlyPossible)
            assertEquals(listOf(driftwood, codes), list.possible.group.playable)
            assertNull(list.possible.leftOut)
        }
    }

    /** Kept in memory under a token each page opens with; gone as the page is left, the oldest past the cap, never shared. */
    @Test
    fun `a token's life`() {
        val first = open()
        val second = open()
        assertNotEquals(first, second, "a token for each page")
        assertNotNull(lists[first])
        assertNotNull(lists[second])
        lists.close(first)
        assertNull(lists[first], "gone as its page is left")
        assertNotNull(lists[second])
        assertNull(lists["0"], "a token no page was given")
        // Pages left without closing (a stack the user never went back to): the oldest goes past the cap.
        val more = List(PluginReleases.MAX) { open() }
        assertNull(lists[second], "the oldest let go past ${PluginReleases.MAX}")
        assertTrue(more.all { lists[it] != null })
        // Found from Play (only possible matches): kept the same way.
        val target = ItemTarget("kestrel", "ev-000731:main", game.title, game.subtitle, noun = "game")
        val fromPlay = lists.open(target, io.skjaere.usenet.app.play.ItemFound(emptyList(), offered = listOf(driftwood to "No date in the name")))
        assertEquals(target, lists[fromPlay]?.target)
        assertTrue(lists[fromPlay]!!.onlyPossible)
    }

    /** The page's plays: each release alone, chosen, as the item's Play would; a failure marked; a launch closing the page. */
    @Test
    fun `a tap plays that release alone as the item's, a failure is marked, a launch closes, and the token goes with the page`() {
        val token = open()
        val list = assertNotNull(lists[token])
        val requests = mutableListOf<PlayRequest>()
        val outcomes = ArrayDeque<CompletableDeferred<PlayOutcome>>()
        val play: (PlayRequest) -> Deferred<PlayOutcome> = { request -> requests += request; CompletableDeferred<PlayOutcome>().also(outcomes::addLast) }
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, viewModelFactory { initializer { PluginReleasesViewModel(lists, token, play) } })[PluginReleasesViewModel::class.java]
        assertEquals(list, vm.list)

        vm.play(codes)
        val chosen = requests.single()
        assertEquals(listOf(codes), chosen.candidates)
        assertTrue(chosen.chosen)
        assertFalse(chosen.rememberedFirst)
        assertEquals(list.target.watch, chosen.watch)
        assertEquals(list.target.picker, chosen.picker)
        assertEquals(list.target.title to list.target.subtitle, chosen.title to chosen.subtitle)
        assertEquals(listOf(wren, kestrel), chosen.alternatives, "the list's releases Play would pick, for the pre-check")
        val failure = PlayState.Failed(list.target.title, list.target.subtitle, listOf(codes.item.title to "Release incomplete"), notTried = 0)
        outcomes.removeFirst().complete(PlayOutcome.Failed(failure))
        assertEquals(mapOf(codes.key to "Release incomplete"), vm.failed.value)
        assertFalse(vm.launched.value)

        vm.play(wren)
        assertEquals(listOf(kestrel), requests.last().alternatives)
        outcomes.removeFirst().complete(PlayOutcome.Launched)
        assertTrue(vm.launched.value, "the page closes as the player launches")

        store.clear()
        assertNull(lists[token], "the token goes as the page is left")
    }

    /** A token no longer kept (the process died since): no list, and nothing plays. */
    @Test
    fun `a gone token has no list, and its page plays nothing`() {
        val requests = mutableListOf<PlayRequest>()
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, viewModelFactory { initializer { PluginReleasesViewModel(lists, "gone", { requests += it; CompletableDeferred() }) } })[PluginReleasesViewModel::class.java]
        assertNull(vm.list)
        vm.play(kestrel)
        assertEquals(emptyList(), requests)
        store.clear()
    }
}
