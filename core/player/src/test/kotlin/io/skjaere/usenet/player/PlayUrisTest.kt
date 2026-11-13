package io.skjaere.usenet.player

import io.skjaere.usenet.core.UsenetUris
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * What a play's URI says ([PlayUris]): the scheme the player's data source routes it by, and the file it names. A Usenet
 * URI reads as [UsenetUris] reads it; a debrid service's names only a listing the app keeps and the file's place in it,
 * never a name, as the URI reaches the media session. Names and ids are invented.
 */
class PlayUrisTest {
    @Test
    fun aUsenetUriParsesAsBefore() {
        val name = "Harbor Lights S02E05 & 100% more+.mkv"
        val target = PlayUris.parse(UsenetUris.build("abc123", 4, name, 1_000_005_000))
        assertEquals(PlayUris.Target.Usenet(UsenetUris.Target("abc123", 4, name, 1_000_005_000)), target)
        assertEquals(PlayUris.USENET, target?.scheme)
        assertEquals(4, target?.fileIndex)
        assertEquals(name, target?.name)
        assertEquals(1_000_005_000, target?.size)
        // One built before names were kept: its position alone.
        assertEquals(PlayUris.Target.Usenet(UsenetUris.Target("abc", 0)), PlayUris.parse("usenet://nzb/abc/file/0"))
        // What UsenetUris doesn't read, neither does this.
        assertNull(PlayUris.parse("usenet://nzb/not-hex/file/0"))
        assertNull(PlayUris.parse("usenet://nzb/abc/file/0?name=%E0%A4%A"))
    }

    @Test
    fun aServiceUriGivesItsListingAndIndexAndNoName() {
        val uri = PlayUris.service("l7f3a9", 2)
        assertEquals("debrid://l7f3a9/file/2", uri)
        val target = PlayUris.parse(uri)
        assertEquals(PlayUris.Target.Service("l7f3a9", 2), target)
        assertEquals(PlayUris.SERVICE, target?.scheme)
        assertEquals(2, target?.fileIndex)
        assertNull(target?.name)
        assertNull(target?.size)
        // Nothing more: a name in a query, another path, no listing or no index is no service URI.
        assertNull(PlayUris.parse("debrid://l7f3a9/file/2?name=Harbor.Lights.S02E05.mkv"))
        assertNull(PlayUris.parse("debrid://l7f3a9/2"))
        assertNull(PlayUris.parse("debrid:///file/2"))
        assertNull(PlayUris.parse("debrid://l7f3a9/file/"))
        assertNull(PlayUris.parse("debrid://l7f3a9/file/99999999999"))
        // A listing id that would need escaping is never made into one.
        assertFailsWith<IllegalArgumentException> { PlayUris.service("Harbor Lights/S02", 0) }
        assertFailsWith<IllegalArgumentException> { PlayUris.service("", 0) }
        assertFailsWith<IllegalArgumentException> { PlayUris.service("l7f3a9", -1) }
    }

    @Test
    fun aUriRoutesByItsScheme() {
        assertEquals("usenet", PlayUris.scheme(UsenetUris.build("abc", 0, "Harbor.Lights.S02E05.mkv", 1)))
        assertEquals("debrid", PlayUris.scheme(PlayUris.service("l7f3a9", 0)))
        assertEquals("https", PlayUris.scheme("https://cdn.example.org/clip.mkv"))
        assertEquals("content", PlayUris.scheme("content://media/external/downloads/7"))
        assertNull(PlayUris.scheme("/sdcard/clip.mkv"))
        assertNull(PlayUris.scheme("clip.mkv"))
        assertNull(PlayUris.scheme(""))
        // A scheme no source has names no file.
        assertNull(PlayUris.parse("https://cdn.example.org/clip.mkv"))
        assertNull(PlayUris.parse("/sdcard/clip.mkv"))
    }
}
