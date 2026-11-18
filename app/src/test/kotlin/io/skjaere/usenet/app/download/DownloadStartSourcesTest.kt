package io.skjaere.usenet.app.download

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L421, L422: what the app's start does for the downloads, in its order, which no other test reaches whole, as each step's
 * own test calls it alone. The downloads reconciled; in a build that hides debrid, the torrent downloads a shown build left
 * failed with no call; every torrent download still to finish holding its add by its lease, the sweeps of what plays and
 * downloads added then let go to sweep however that went (in a finally); then Delete once watched, which never returns.
 * And the container's DebridAdds is made with its start's holds still to come, so its first sweep waits for them. The
 * app's sources are read as text.
 */
class DownloadStartSourcesTest {
    private val sources: Map<String, String> = File(APP).walkTopDown()
        .filter { it.isFile && it.extension == "kt" }.associate { it.relativeTo(File(APP)).path to it.readText() }

    @Test
    fun `the start reconciles, fails a hidden build's torrents, holds the adds and lets the sweeps go, then deletes what was watched`() {
        val lines = sources.getValue(CONTAINER).lines().map { it.trim() }
        val first = lines.indexOf(START.first())
        assertTrue(first >= 0, "the start's reconcile")
        assertEquals(START, lines.subList(first, minOf(first + START.size, lines.size)))
        // Each step there alone: nothing else holds the adds, fails the torrents or lets the sweeps go (but DebridAdds' own
        // wait, once it is over).
        listOf("holdListings()", "failHiddenTorrents()", "startHolds.complete(").forEach { call ->
            val callers = sources.filterKeys { it != ADDS }.flatMap { (name, text) -> text.lines().filter { call in it && "fun " !in it }.map { "$name: ${it.trim()}" } }
            assertEquals(listOf("$CONTAINER: ${START.first { call in it }}"), callers, call)
        }
    }

    @Test
    fun `the container's sweeps of what plays and downloads added wait for the start's holds`() {
        val made = sources.getValue(CONTAINER).substringAfter("val debridAdds: DebridAdds = DebridAdds(", "").substringBefore("\n    )\n")
        assertTrue("startHolds = CompletableDeferred()," in made, made)
    }

    private companion object {
        const val APP = "src/main/kotlin/io/skjaere/usenet/app"
        const val CONTAINER = "UsenetApplication.kt"
        const val ADDS = "debrid/DebridAdds.kt"

        /** The start's steps for the downloads, line by line, as the app's onCreate launches them. */
        val START = listOf(
            """saveOrLog("reconcile the downloads") { container.downloads.reconcile() }""",
            """saveOrLog("fail the torrent downloads while debrid is hidden") { container.downloads.failHiddenTorrents() }""",
            "try {",
            """saveOrLog("hold the debrid downloads' adds") { container.downloads.holdListings() }""",
            "} finally {",
            "container.debridAdds.startHolds.complete(Unit)",
            "}",
            """saveOrLog("delete the downloads watched a day ago") { container.downloads.autoDelete() }""",
        )
    }
}
