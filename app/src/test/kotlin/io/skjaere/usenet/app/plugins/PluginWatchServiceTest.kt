package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.data.ItemMark
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.plugin.WatchInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * L594, L626, L613: what the user watched of a plugin's items, as the host keeps it. A plugin's keys are its own, stored
 * under item:<plugin id>:<key>, so another plugin's same key is another item; a key outside the API's rule is refused with
 * an IllegalArgumentException naming the rule, never the key. observe gives each key with a state its position, length,
 * watched and when it last played, and a new map as one changes; mark stores the items with their titles, as Mark
 * watched does a film. The plugins, items and titles are invented.
 */
class PluginWatchServiceTest {
    /** The library's rows, as the service reads and marks them; what it was asked for, kept. */
    private class Library {
        val rows = MutableStateFlow<List<WatchState>>(emptyList())
        val observed = mutableListOf<List<String>>()
        val marked = mutableListOf<Pair<List<ItemMark>, Boolean>>()

        fun observe(keys: List<String>): Flow<List<WatchState>> {
            observed += keys
            return rows.map { all -> all.filter { it.key in keys && !it.deleted } }
        }

        suspend fun mark(items: List<ItemMark>, watched: Boolean) {
            marked += items to watched
        }
    }

    private val library = Library()
    private val service = PluginWatchService(library::observe, library::mark)

    private fun row(plugin: String, key: String, positionMs: Long, watched: Boolean = false, playedAt: Long = 1_700_000_000_000, deleted: Boolean = false) = WatchState(
        WatchKey.item(plugin, key), WatchKind.Item, title = "Grand Prix of Kestrel · $key", positionMs = positionMs, durationMs = 6_000_000,
        watched = watched, lastPlayedAt = playedAt, updatedAt = playedAt, deleted = deleted,
        release = RememberedRelease("fake", "gp-$key", "Kestrel.Grand.Prix.2026.Race.1080p.WEB.h264-KESTREL", null, 0, "nzb-$key"),
    )

    private fun Map<String, WatchInfo>.said() = mapValues { (_, info) -> listOf(info.positionMs, info.durationMs, info.watched, info.lastPlayedAt) }

    @Test
    fun `observe gives the plugin's own keys their states, and leaves out a key with none`() = runTest {
        library.rows.value = listOf(
            row("kestrel", "gp-kestrel:race", positionMs = 1_680_000),
            row("kestrel", "gp-kestrel:qualifying", positionMs = 0, watched = true),
            // Marked only, never played: no time it played.
            row("kestrel", "gp-kestrel:practice", positionMs = 0, watched = true, playedAt = 0),
            row("kestrel", "gp-kestrel:sprint", positionMs = 600_000, deleted = true),
            // Another plugin's item of the same key is another item.
            row("quill", "gp-kestrel:race", positionMs = 4_000_000),
        )
        val watch = service.of("kestrel")
        val seen = watch.observe(listOf("gp-kestrel:race", "gp-kestrel:qualifying", "gp-kestrel:practice", "gp-kestrel:sprint", "gp-kestrel:pit-lane")).first()
        assertEquals(
            mapOf(
                "gp-kestrel:race" to listOf(1_680_000L, 6_000_000L, false, Instant.ofEpochMilli(1_700_000_000_000)),
                "gp-kestrel:qualifying" to listOf(0L, 6_000_000L, true, Instant.ofEpochMilli(1_700_000_000_000)),
                "gp-kestrel:practice" to listOf(0L, 6_000_000L, true, null),
            ),
            seen.said(),
        )
        assertEquals(
            listOf("race", "qualifying", "practice", "sprint", "pit-lane").map { "item:kestrel:gp-kestrel:$it" },
            library.observed.single(),
            "asked under the plugin's own id",
        )
        assertEquals(emptyMap(), watch.observe(emptyList()).first())
    }

    @Test
    fun `a change to one of the keys gives a new map, a change to another item none`() = runTest {
        library.rows.value = listOf(row("kestrel", "gp-kestrel:race", positionMs = 1_680_000))
        val maps = mutableListOf<Map<String, WatchInfo>>()
        val collecting = launch(Dispatchers.Unconfined) { service.of("kestrel").observe(listOf("gp-kestrel:race")).take(2).toList(maps) }
        // Another item, the other plugin's: nothing new for this one.
        library.rows.value = library.rows.value + row("quill", "gp-kestrel:race", positionMs = 60_000)
        library.rows.value = library.rows.value.map { if (it.key == WatchKey.item("kestrel", "gp-kestrel:race")) it.copy(positionMs = 2_400_000) else it }
        yield()
        collecting.join()
        assertEquals(listOf(1_680_000L, 2_400_000L), maps.map { it.getValue("gp-kestrel:race").positionMs })
    }

    @Test
    fun `mark stores the plugin's items under its own id, with the titles it gives`() = runTest {
        service.of("kestrel").mark(listOf("gp-kestrel:race" to "Grand Prix of Kestrel · Race", "gp-kestrel:sprint" to "Grand Prix of Kestrel · Sprint"), watched = true)
        service.of("quill").mark(listOf("gp-kestrel:race" to "Quill Cup · Final"), watched = false)
        service.of("kestrel").mark(emptyList(), watched = true)
        assertEquals(
            listOf(
                listOf(ItemMark("item:kestrel:gp-kestrel:race", "Grand Prix of Kestrel · Race"), ItemMark("item:kestrel:gp-kestrel:sprint", "Grand Prix of Kestrel · Sprint")) to true,
                listOf(ItemMark("item:quill:gp-kestrel:race", "Quill Cup · Final")) to false,
            ),
            library.marked,
        )
    }

    /** L613: a key outside the rule is refused before anything is read or written, and the message names the rule alone. */
    @Test
    fun `a key outside the rule is refused, naming the rule and never the key`() = runTest {
        val watch = service.of("kestrel")
        listOf("", "gp kestrel race", "gp/kestrel", "a".repeat(201), "gp-kestrel:räce").forEach { key ->
            val observed = assertFailsWith<IllegalArgumentException>(key) { watch.observe(listOf("gp-kestrel:race", key)) }
            val marked = assertFailsWith<IllegalArgumentException>(key) { watch.mark(listOf(key to "Grand Prix of Kestrel · Race"), watched = true) }
            listOf(observed, marked).forEach { refused ->
                assertEquals("An item's key is 1 to 200 letters, digits, dots, underscores, colons and dashes", refused.message)
                assertFalse(key.isNotEmpty() && key in refused.message.orEmpty())
            }
        }
        assertEquals(emptyList(), library.observed)
        assertEquals(emptyList(), library.marked)
    }

    /** A host made with the app's watch service watches through it, under its plugin's id. */
    @Test
    fun `a host made with the watch service watches through it`(@TempDir dir: File) = runTest {
        library.rows.value = listOf(row("kestrel", "gp-kestrel:race", positionMs = 1_680_000))
        val store = PluginStore(File(dir, "files"), File(dir, "cache"), FakeSecrets())
        val host = PluginHosts(store, FakeSecrets(), main = Dispatchers.Unconfined, watch = service).create(PluginVersion("kestrel", 3, key = 11), "Kestrel") { _, _ -> }
        assertEquals(1_680_000L, host.watch.observe(listOf("gp-kestrel:race")).first().getValue("gp-kestrel:race").positionMs)
        host.watch.mark(listOf("gp-kestrel:race" to "Grand Prix of Kestrel · Race"), watched = true)
        assertEquals(listOf(ItemMark("item:kestrel:gp-kestrel:race", "Grand Prix of Kestrel · Race")) to true, library.marked.single())
        assertNull(host.watch.observe(listOf("gp-kestrel:sprint")).first()["gp-kestrel:sprint"])
    }

    /**
     * The host's own database failing (a full disk, a corrupt file) is the host's, never the plugin's: a mark returns, an
     * observation ends, both logged by the plugin's id and the failure's kind, and nothing reaches the plugin's scope, whose
     * handler would turn it off after an error. A key outside the rule is still the plugin's own error.
     */
    @Test
    fun `a failure of the host's own database is never thrown at the plugin`(@TempDir dir: File) = runTest {
        val failing = PluginWatchService(
            states = { flow { throw java.io.IOException("disk full at /data/canary-db") } },
            marking = { _, _ -> throw java.io.IOException("disk full at /data/canary-db") },
        )
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        val failures = mutableListOf<String>()
        val store = PluginStore(File(dir, "files"), File(dir, "cache"), FakeSecrets())
        val host = PluginHosts(store, FakeSecrets(), main = Dispatchers.Unconfined, watch = failing)
            .create(PluginVersion("kestrel", 3, key = 11), "Kestrel") { what, e -> failures += "$what ${e.javaClass.simpleName}" }
        host.scope.launch { host.watch.mark(listOf("gp-kestrel:race" to "Grand Prix of Kestrel · Race"), watched = true) }.join()
        assertEquals(emptyList(), host.watch.observe(listOf("gp-kestrel:race")).toList(), "the observation ends")
        assertEquals(emptyList(), failures, "nothing charged to the plugin")
        val logged = LogBuffer.snapshot().filter { it.seq > from }.map { it.message }.filter { "kestrel" in it }
        assertEquals(2, logged.size, "$logged")
        assertFalse(logged.any { "canary" in it }, "$logged")
        assertFailsWith<IllegalArgumentException> { host.watch.mark(listOf("gp kestrel" to "Grand Prix of Kestrel · Race"), watched = true) }
        host.cancel()
    }
}
