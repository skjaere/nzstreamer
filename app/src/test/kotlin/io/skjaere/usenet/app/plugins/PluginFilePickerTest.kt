package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.FileListing
import io.skjaere.usenet.core.ListedFile
import io.skjaere.usenet.plugin.FileInfo
import io.skjaere.usenet.plugin.PluginFilePicker
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * L627, L619: a plugin's file picker as the host's. It is handed a release's files in their listed order, each its path,
 * size and whether the host takes it for a video, and its answer, an index into that list, picks the listed file at that
 * place; one past the end, or none, picks nothing, and the play says the plugin's no-match. A pick or takesWhole that
 * throws is caught: the version is turned off as an error, the attempt finds nothing and fails with the no-match, and
 * the play goes on. The files are invented.
 */
class PluginFilePickerTest {
    private data class Listed(override val index: Int, override val path: String, override val size: Long) : ListedFile

    private class Listing(override val files: List<Listed>) : FileListing<Listed> {
        override val id = "nzb-weekend"
        override val title = "Kestrel.Grand.Prix.2026.Weekend.1080p.WEB.h264-KESTREL"
    }

    // Listed out of index order, as a source may list them after one left out: the plugin's index is a place in the list.
    private val weekend = Listing(
        listOf(
            Listed(4, "Kestrel.Grand.Prix.2026.Qualifying.mkv", 2_000_000_000),
            Listed(0, "Kestrel.Grand.Prix.2026.Race.mkv", 4_000_000_000),
            Listed(7, "Extras/Kestrel.Grand.Prix.2026.Race.nfo", 2_000),
        ),
    )

    private val version = PluginVersion("kestrel", 3, key = 11)

    /** A picker of the plugin's, choosing by [chosen] from what it is handed, which it keeps. */
    private class Picker(
        val chosen: (List<FileInfo>) -> Int?,
        val whole: (String) -> Boolean = { true },
        override val noMatch: String = "No file for the race",
    ) : PluginFilePicker {
        val handed = mutableListOf<List<FileInfo>>()

        override fun pick(files: List<FileInfo>): Int? {
            handed += files
            return chosen(files)
        }

        override fun takesWhole(releaseName: String): Boolean = whole(releaseName)
    }

    private val failures = mutableListOf<String>()
    private val calls = PluginCalls { v, what, e -> failures += "${v.id} ${v.versionCode} $what ${e.javaClass.simpleName}" }

    @Test
    fun `the plugin is handed the files in their listed order, and its index picks the file at that place`() {
        val picker = Picker({ files -> files.indexOfFirst { it.path.endsWith("Race.mkv") } })
        val adapted = PluginFilePickers.of(picker, version, calls)
        assertSame(weekend.files[1], adapted.pick(weekend))
        val handed = picker.handed.single()
        assertEquals(weekend.files.map { it.path }, handed.map { it.path })
        assertEquals(listOf(2_000_000_000L, 4_000_000_000L, 2_000L), handed.map { it.size })
        assertEquals(listOf(true, true, false), handed.map { it.isVideo })
        assertEquals(AttemptFailure.NoMatchingFile("No file for the race"), adapted.noMatch)
        assertEquals("No file for the race", adapted.noMatch.text)
        assertTrue(adapted.takesWhole("Kestrel.Grand.Prix.2026.Race.1080p.WEB.h264-KESTREL"))
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `none, or an index outside the list, picks nothing and the play says the plugin's no-match`() {
        listOf<Int?>(null, 3, -1, Int.MAX_VALUE).forEach { index ->
            val adapted = PluginFilePickers.of(Picker({ index }), version, calls)
            assertNull(adapted.pick(weekend), "$index")
            assertEquals(AttemptFailure.NoMatchingFile("No file for the race"), adapted.noMatchIn(weekend))
        }
        assertEquals(emptyList(), failures, "no file isn't an error")
    }

    @Test
    fun `a pick that throws finds nothing, fails with the no-match, and turns the version off`() {
        var asked = 0
        val adapted = PluginFilePickers.of(Picker({ asked++; throw IllegalStateException("the plugin's own words") }), version, calls)
        assertNull(adapted.pick(weekend))
        assertEquals(listOf("kestrel 3 pick IllegalStateException"), failures)
        // Its words, but it looked for nothing: no release is at fault for it (Replay keeps the remembered one).
        assertEquals(AttemptFailure.NoMatchingFile("No file for the race", pickerFailed = true), adapted.noMatchIn(weekend))
        assertEquals("No file for the race", adapted.noMatch.text)
        // Its code isn't asked again for this play: every later release finds nothing, and isn't checked whole.
        assertNull(adapted.pick(weekend))
        assertFalse(adapted.takesWhole("Kestrel.Grand.Prix.2026.Race.1080p.WEB.h264-KESTREL"))
        assertEquals(1, asked)
        assertEquals(1, failures.size)
    }

    @Test
    fun `a takesWhole that throws is not whole, turns the version off, and the attempt then finds nothing`() {
        val picker = Picker({ 1 }, whole = { throw NotImplementedError() })
        val adapted = PluginFilePickers.of(picker, version, calls)
        assertFalse(adapted.takesWhole("Kestrel.Grand.Prix.2026.Race.1080p.WEB.h264-KESTREL"))
        assertEquals(listOf("kestrel 3 takesWhole NotImplementedError"), failures)
        assertNull(adapted.pick(weekend), "the attempt fails with the no-match")
        assertTrue(picker.handed.isEmpty(), "its pick isn't asked")
        assertEquals(AttemptFailure.NoMatchingFile("No file for the race", pickerFailed = true), adapted.noMatchIn(weekend))
    }

    @Test
    fun `a no-match that throws or says nothing is said in the host's words, and one that throws turns the version off`() {
        val throwing = object : PluginFilePicker {
            override fun pick(files: List<FileInfo>): Int? = 1
            override val noMatch: String get() = throw IllegalStateException("no words")
            override fun takesWhole(releaseName: String): Boolean = true
        }
        val adapted = PluginFilePickers.of(throwing, version, calls)
        // Its code threw: it looks for nothing from now on, so no release is at fault.
        assertEquals(AttemptFailure.NoMatchingFile(PluginFilePickers.NO_MATCH, pickerFailed = true), adapted.noMatch)
        assertEquals(listOf("kestrel 3 noMatch IllegalStateException"), failures)
        assertNull(adapted.pick(weekend), "a picker that threw isn't asked again")
        assertEquals(AttemptFailure.NoMatchingFile(PluginFilePickers.NO_MATCH), PluginFilePickers.of(Picker({ 1 }, noMatch = "  "), version, calls).noMatch)
        assertEquals("No matching file", PluginFilePickers.NO_MATCH)
    }

    /** Errors of the process itself aren't the plugin's: they go on up, as from every guarded call. */
    @Test
    fun `out of memory in a pick goes on up`() {
        val adapted = PluginFilePickers.of(Picker({ throw OutOfMemoryError("no room") }), version, calls)
        assertTrue(runCatching { adapted.pick(weekend) }.exceptionOrNull() is OutOfMemoryError)
        assertEquals(emptyList(), failures)
    }
}
