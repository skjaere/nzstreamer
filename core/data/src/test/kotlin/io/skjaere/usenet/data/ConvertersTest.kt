package io.skjaere.usenet.data

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The kinds as Room stores them: by name, read back whatever a newer build wrote, so a row never crashes a page. */
class ConvertersTest {
    private val converters = Converters()

    @Test
    fun `every watch kind round trips by its name`() {
        WatchKind.entries.forEach { kind ->
            assertEquals(kind.name, converters.fromKind(kind))
            assertEquals(kind, converters.toKind(converters.fromKind(kind)))
        }
        assertEquals(listOf("Episode", "Movie", "Release", "Item"), WatchKind.entries.map { converters.fromKind(it) })
    }

    @Test
    fun `every download kind round trips by its name`() {
        DownloadKind.entries.forEach { kind ->
            assertEquals(kind.name, converters.fromDownloadKind(kind))
            assertEquals(kind, converters.toDownloadKind(converters.fromDownloadKind(kind)))
        }
    }

    /** A kind a newer build wrote is kept and shown by its stored title, as a plugin's item is. */
    @Test
    fun `a watch kind no build knows reads as an item`() {
        assertEquals(WatchKind.Item, converters.toKind("Hologram"))
        assertEquals(WatchKind.Item, converters.toKind(""))
        // Names are matched as written: Room only ever stores them so.
        assertEquals(WatchKind.Item, converters.toKind("episode"))
    }

    /** Its row then fails with a release's own words rather than crash the Downloads page. */
    @Test
    fun `a download kind no build knows reads as a release`() {
        assertEquals(DownloadKind.Release, converters.toDownloadKind("Hologram"))
        assertEquals(DownloadKind.Release, converters.toDownloadKind(""))
        assertEquals(DownloadKind.Release, converters.toDownloadKind("movie"))
    }
}
