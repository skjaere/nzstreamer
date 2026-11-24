package io.skjaere.usenet.app.play

import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.data.WatchKey
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The play's log lines (the log can be shared from Settings › Logs): a plugin's item named by its plugin's id alone, never
 * by its title or key, the plugin's own words; a picker's no-match by its kind, never the plugin's words for it. The app's
 * own plays by their titles and keys, and failures in their words, as before. The plugin and its words are invented.
 */
class PlayLogTest {
    private val itemKey = WatchKey.item("kestrel", "ev-000731:main-card")

    @Test
    fun `a plugin's item is named by its plugin's id alone`() {
        assertEquals("a plugin's item (kestrel)", PlayLog.title("Kestrel Cup 2026 · Main card (user 4411)", itemKey))
        assertEquals("a plugin's item (kestrel)", PlayLog.key(itemKey))
        listOf(PlayLog.title("Kestrel Cup 2026 · Main card (user 4411)", itemKey), PlayLog.key(itemKey)).forEach { line ->
            assertFalse("4411" in line || "ev-000731" in line || "main-card" in line, line)
        }
    }

    @Test
    fun `the app's own plays are named as before`() {
        assertEquals("Harbor Lights", PlayLog.title("Harbor Lights", WatchKey.movie(900001)))
        assertEquals("Harbor Lights", PlayLog.title("Harbor Lights", null))
        assertEquals(WatchKey.episode(900002, 1, 3), PlayLog.key(WatchKey.episode(900002, 1, 3)))
        assertEquals(WatchKey.release("nzb-harbor", 0), PlayLog.key(WatchKey.release("nzb-harbor", 0)))
    }

    @Test
    fun `a plugin picker's no-match is said by its kind, any other failure in its words`() {
        val noMatch = PlayLog.failure(AttemptFailure.NoMatchingFile("No file for ev-000731 main card (user 4411)"))
        assertFalse("4411" in noMatch || "ev-000731" in noMatch, noMatch)
        assertEquals(noMatch, PlayLog.failure(AttemptFailure.NoMatchingFile("Another plugin's words", pickerFailed = true)))
        assertEquals("No file for S01E03", PlayLog.failure(AttemptFailure.NoMatchingEpisode(1, 3)))
        assertEquals(AttemptFailure.NoVideo.text, PlayLog.failure(AttemptFailure.NoVideo))
    }
}
