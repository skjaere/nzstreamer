package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.plugins.FakeSecrets
import io.skjaere.usenet.app.plugins.PluginCalls
import io.skjaere.usenet.app.plugins.PluginHosts
import io.skjaere.usenet.app.plugins.PluginPlayService
import io.skjaere.usenet.app.plugins.PluginStore
import io.skjaere.usenet.app.plugins.PluginVersion
import io.skjaere.usenet.app.searchRoute
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.plugin.IndexerNote
import io.skjaere.usenet.plugin.ItemReleases
import io.skjaere.usenet.plugin.Offered
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.ReleaseHandle
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L627: Play on a plugin's item, as a film's Play. The release that played the item before goes alone while its NZB is
 * stored; else, or once it fails, the plugin's releases(), the ones it accepted in its order. Nothing accepted: the host's
 * no-release sheet with the plugin's lines and note, never "Show the n found". A releases() that throws is nothing found,
 * and turns its version off; NZStreamer goes on. What plays is kept under item:<plugin>:<key>. The plugin, its items and
 * releases are invented.
 */
class ItemPlayTest {
    private val race = ItemTarget("kestrel", "gp-kestrel:race", "Grand Prix of Kestrel · Race", "Round 20")
    private val watch = WatchTarget(WatchKind.Item, "Grand Prix of Kestrel · Race", itemKey = "item:kestrel:gp-kestrel:race")
    private val remembered = RememberedRelease(
        "a", "gp-race-good", "Kestrel.Grand.Prix.2026.Race.1080p.WEB.h264-KESTREL", null, 0, "nzb-gp",
        "Kestrel.Grand.Prix.2026.Race.1080p.WEB.h264-KESTREL.mkv", 3_000_000_000,
    )

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

    private val good = ranked("a", "gp-race-good", "Kestrel.Grand.Prix.2026.Race.1080p.WEB.h264-KESTREL")
    private val other = ranked("b", "gp-race-web", "Kestrel.Grand.Prix.2026.Race.720p.WEB.h264-TIDE")
    private val third = ranked("a", "gp-race-hdtv", "Kestrel.Grand.Prix.2026.Race.HDTV.x264-QUAY")
    private val locked = ranked("b", "gp-race-locked", "Kestrel.Grand.Prix.2026.Race.2160p.WEB.h265-LOCK", playable = false)

    /** What one run asked for: the requests played (with their retries), the plugin's releases() asked, the sheet's lines. */
    private class Run {
        val plays = mutableListOf<Pair<PlayRequest, Retry?>>()
        var asked = 0
        val lines = mutableListOf<String>()
    }

    private fun run(
        stored: Boolean,
        remembered: RememberedRelease? = this.remembered,
        found: ItemFound = ItemFound(listOf(other, good, third)),
        outcomes: List<PlayState.Failed?> = listOf(null),
        target: ItemTarget = race,
    ): Pair<Run, PlayState?> {
        val r = Run()
        val left = outcomes.toMutableList()
        val end = runBlocking {
            ItemPlay.run(
                target, remembered, stored,
                play = { request, retry -> r.plays += request to retry; left.removeAt(0) },
                searching = { r.lines += it },
                releases = { r.asked++; found },
            )
        }
        return r to end
    }

    private fun failed(what: String) = PlayState.Failed(race.title, race.subtitle, listOf(what to "Release incomplete"), notTried = 0)

    @Test
    fun `an item is watched as an item, under item, the plugin's id and its key`() {
        assertEquals("item:kestrel:gp-kestrel:race", race.watchKey)
        assertEquals(WatchKey.item("kestrel", "gp-kestrel:race"), race.watchKey)
        assertEquals(watch, race.watch)
        assertEquals("item:kestrel:gp-kestrel:race", race.watch.rememberedKey)
        // Its progress goes under its own key whichever release plays, never under the release's.
        assertEquals("item:kestrel:gp-kestrel:race", race.watch.key("nzb-gp", 3))
        assertEquals("item:kestrel:gp-kestrel:race", Replay.watchKey(race.watch, remembered))
        // The player saves its progress, and the play its release, under the launch's key: the item's.
        val request = PlayRequest(race.title, race.subtitle, emptyList(), watch = race.watch)
        val launch = launchOf(request, SavedRelease("b", "gp-race-web", "Kestrel.Grand.Prix.2026.Race.720p.WEB.h264-TIDE", null, 2, "nzb-web"), "usenet://nzb-web/2", "play-1", retry = null)
        assertEquals("item:kestrel:gp-kestrel:race", launch.watchKey)
        assertEquals(watch, launch.watch)
    }

    @Test
    fun `the release that played it, still stored, plays alone without asking the plugin`() {
        val (r, end) = run(stored = true)
        assertNull(end)
        assertEquals(0, r.asked)
        assertEquals(emptyList(), r.lines)
        val (request, retry) = r.plays.single()
        assertNull(retry)
        assertEquals(listOf("a/gp-race-good"), request.candidates.map { it.key })
        assertEquals(remembered, request.remembered)
        assertEquals(watch, request.watch)
        assertEquals(Replay.file(remembered, FilePicker.Largest, watch), request.picker)
        assertEquals(race.title to race.subtitle, request.title to request.subtitle)
    }

    @Test
    fun `without its NZB stored the plugin's releases play, in its order, the playable ones alone`() {
        val (r, end) = run(stored = false, found = ItemFound(listOf(other, locked, good, third)))
        assertNull(end)
        assertEquals(1, r.asked)
        assertEquals(listOf("Searching your indexers…"), r.lines)
        val (request, retry) = r.plays.single()
        assertNull(retry)
        assertEquals(listOf(other, good, third), request.candidates, "the plugin's order, not the remembered one first")
        assertEquals(false, request.rememberedFirst)
        assertEquals(setOf(locked.key), request.hidden)
        assertEquals(watch, request.watch)
        assertEquals(FilePicker.Largest, request.picker)
        val picked = race.copy(picker = FilePicker.Episode(1, 1))
        assertEquals(FilePicker.Episode(1, 1), run(stored = false, target = picked).first.plays.single().first.picker, "the plugin's picker")
    }

    @Test
    fun `when the remembered release fails, the plugin's releases play without it`() {
        val (r, end) = run(stored = true, outcomes = listOf(failed(remembered.title), null))
        assertNull(end)
        assertEquals(1, r.asked)
        assertEquals(listOf("The release that played it failed · Searching your indexers…"), r.lines)
        val (request, retry) = r.plays[1]
        assertEquals(Retry(setOf("a/gp-race-good"), startPositionMs = null, midStream = false), retry)
        assertEquals(listOf(other, third), request.candidates)
    }

    @Test
    fun `when the remembered release fails and the plugin has nothing else, its failure shows, with no search`() {
        val failure = failed(remembered.title)
        val (_, end) = run(stored = true, found = ItemFound(listOf(good)), outcomes = listOf(failure))
        assertEquals(failure, end)
        assertNull(assertIs<PlayState.Failed>(end).search)
    }

    @Test
    fun `nothing accepted ends on the no-release sheet with the plugin's lines and note, and nothing hidden to show`() {
        val nothing = ItemFound(
            accepted = listOf(locked),
            lines = listOf("Indexer A" to "6 results, none for this race", "Indexer B" to "No results"),
            note = "Releases often appear a few hours after a race ends. Try Play again later.",
        )
        val (r, end) = run(stored = false, remembered = null, found = nothing)
        assertEquals(emptyList(), r.plays)
        val none = assertIs<PlayState.NoRelease>(end)
        assertEquals(race.title to race.subtitle, none.title to none.subtitle)
        assertEquals(listOf("Indexer A" to "6 results, none for this race", "Indexer B" to "No results"), none.stages)
        assertEquals("Releases often appear a few hours after a race ends. Try Play again later.", none.note)
        assertEquals(emptyList(), none.hidden, "never Show the n found: their names can carry what the plugin hides")
        assertNull(none.nameQuery, "no Search on the sheet yet")
        assertNull(none.searchRoute())
        assertEquals(emptyList(), none.remoteStages)
    }

    @Test
    fun `releases only offered read as nothing found, until Choose release opens with them`() {
        val offered = ItemFound(emptyList(), offered = listOf(other to "No date in the name"), leftOut = "2 left out: a name with a score")
        val (r, end) = run(stored = false, remembered = null, found = offered)
        assertEquals(emptyList(), r.plays)
        assertIs<PlayState.NoRelease>(end)
    }

    /** L619: a releases() that throws counts as nothing found, and turns the version off; NZStreamer goes on. */
    @Test
    fun `a releases() that throws is nothing found, its version off, and the host going on`() {
        val failures = mutableListOf<String>()
        val calls = PluginCalls { version, what, e -> failures += "${version.id} ${version.versionCode} $what ${e.javaClass.simpleName}" }
        val routed = mutableListOf<Pair<ItemTarget, suspend () -> ItemFound>>()
        val service = PluginPlayService(ranked = { _, _ -> error("nothing to map") }) { target, releases -> routed += target to releases }
        val play = service.of(PluginVersion("kestrel", 3, key = 11), calls)
        play.play(PlayItem("gp-kestrel:race", "Grand Prix of Kestrel · Race", "Round 20", 95)) { throw IllegalStateException("the plugin's own words: key=hunter2") }
        val (target, releases) = routed.single()
        assertEquals(race, target)
        val found = runBlocking { releases() }
        assertEquals(ItemFound.NOTHING, found)
        assertEquals(listOf("kestrel 3 releases IllegalStateException"), failures)

        val (_, end) = run(stored = false, remembered = null, found = found)
        val none = assertIs<PlayState.NoRelease>(end)
        assertEquals(emptyList(), none.stages)
        assertNull(none.note)
    }

    /** The plugin's answer in the host's terms: its handles mapped back in its order, one the table dropped left out. */
    @Test
    fun `the plugin's handles are mapped back to its releases, in its order, and its lines and note kept`() {
        val handles = List(5) { i -> object : ReleaseHandle { override fun toString() = "handle $i" } }
        val table = mapOf(handles[0] to good, handles[1] to other, handles[3] to third, handles[4] to locked)
        val asked = mutableListOf<String>()
        val routed = mutableListOf<Pair<ItemTarget, suspend () -> ItemFound>>()
        val service = PluginPlayService(ranked = { id, given -> asked += id; given.mapNotNull(table::get) }) { target, releases -> routed += target to releases }
        val play = service.of(PluginVersion("kestrel", 3, key = 11), PluginCalls { _, _, e -> throw AssertionError("no error", e) })
        play.play(PlayItem("gp-kestrel:race", "Grand Prix of Kestrel · Race", "Round 20", 95)) {
            ItemReleases(
                accepted = listOf(handles[1], handles[2], handles[0]),
                offered = listOf(Offered(handles[3], "No date in the name"), Offered(handles[2], "Team codes only")),
                leftOut = "4 left out: a score in the name",
                lines = listOf(IndexerNote("Indexer A", "6 results, none for this race")),
                note = "Try Play again later.",
            )
        }
        val found = runBlocking { routed.single().second() }
        assertEquals(listOf(other, good), found.accepted, "a handle the table dropped is left out")
        assertEquals(listOf(third to "No date in the name"), found.offered)
        assertEquals("4 left out: a score in the name", found.leftOut)
        assertEquals(listOf("Indexer A" to "6 results, none for this race"), found.lines)
        assertEquals("Try Play again later.", found.note)
        assertTrue(asked.all { it == "kestrel" }, "only its own table: $asked")
    }

    /** A host made with the app's play service plays its items through it, its errors charged to its own version. */
    @Test
    fun `a host made with the play service plays through it, and a throwing releases() turns that version off`(@TempDir dir: File) {
        val routed = mutableListOf<Pair<ItemTarget, suspend () -> ItemFound>>()
        val service = PluginPlayService(ranked = { _, _ -> emptyList() }) { target, releases -> routed += target to releases }
        val store = PluginStore(File(dir, "files"), File(dir, "cache"), FakeSecrets())
        val failures = mutableListOf<String>()
        val host = PluginHosts(store, FakeSecrets(), main = Dispatchers.Unconfined, play = service)
            .create(PluginVersion("kestrel", 3, key = 11), "Kestrel") { what, e -> failures += "$what ${e.javaClass.simpleName}" }
        host.play.play(PlayItem("gp-kestrel:race", "Grand Prix of Kestrel · Race", "Round 20", 95)) { error("the plugin's") }
        assertEquals(race, routed.single().first)
        assertEquals(ItemFound.NOTHING, runBlocking { routed.single().second() })
        assertEquals(listOf("releases IllegalStateException"), failures)
    }
}
