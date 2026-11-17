package io.skjaere.usenet.app.download

import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchedDownload
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Delete once watched's clock (W111; L412, L415, L416, L444): a finished download is due a day after the later of its
 * title's watched mark and the time the setting was switched on; none while it is off; one watched before it finished (a
 * title downloaded again after its mark) never; one held by a play left; the next due time ahead; and "Deleting tomorrow"
 * for those that will go and aren't due yet, none once due. Invented ids.
 */
class AutoDeleteTest {
    @Test
    fun `a download is due a day after the later of its watched mark and the switch's time`() {
        val early = watched("a", completedAt = NOW - 5 * DAY, watchedAt = NOW - 3 * DAY)
        val late = watched("b", completedAt = NOW - 5 * DAY, watchedAt = NOW - HOUR)
        val since = NOW - 2 * DAY
        // Watched before the switch: its day starts at the switch, so nothing goes on the day it is switched on.
        assertEquals(since + DAY, AutoDelete.dueAt(early, since))
        assertEquals(NOW - HOUR + DAY, AutoDelete.dueAt(late, since))
        assertEquals(listOf("a"), AutoDelete.due(listOf(early, late), since, NOW))
        // Exactly at its time it is due; a moment before, not.
        assertEquals(listOf("a"), AutoDelete.due(listOf(early), since, since + DAY))
        assertEquals(emptyList(), AutoDelete.due(listOf(early), since, since + DAY - 1))
        // Switched on just now: neither is due for a day.
        assertEquals(emptyList(), AutoDelete.due(listOf(early, late), NOW, NOW + DAY - 1))
        assertEquals(listOf("a", "b"), AutoDelete.due(listOf(early, late), NOW, NOW + DAY))
    }

    /** L472, W111: a file downloaded from Import, watched to its end, is due a day after as any download is. */
    @Test
    fun `a file downloaded from Import is due as any download`() {
        val file = WatchedDownload("file", WatchKey.release("nzb-qm", 1), completedAt = NOW - 5 * DAY, watchedAt = NOW - 2 * DAY)
        assertEquals(NOW - DAY, AutoDelete.dueAt(file, NOW - 10 * DAY))
        assertEquals(listOf("file"), AutoDelete.due(listOf(file), NOW - 10 * DAY, NOW))
    }

    /** L412: a title marked watched before its download finished (downloaded again after it was watched) isn't due. */
    @Test
    fun `a download finished after its title was marked watched is never due`() {
        val again = watched("again", completedAt = NOW - DAY, watchedAt = NOW - 3 * DAY)
        val same = watched("same", completedAt = NOW - 3 * DAY, watchedAt = NOW - 3 * DAY)
        assertNull(AutoDelete.dueAt(again, NOW - 10 * DAY))
        assertNull(AutoDelete.dueAt(same, NOW - 10 * DAY))
        assertEquals(emptyList(), AutoDelete.due(listOf(again, same), NOW - 10 * DAY, NOW + 10 * DAY))
        assertEquals(emptySet(), AutoDelete.tomorrow(listOf(again, same), NOW - 10 * DAY, NOW))
        assertNull(AutoDelete.nextAt(listOf(again, same), NOW - 10 * DAY, NOW))
    }

    /** W111: off by default; off, nothing is due, ahead or said. On with no time kept reads as off, which keeps. */
    @Test
    fun `none while the setting is off`() {
        assertNull(AutoDelete.since(DownloadPrefs()))
        assertNull(AutoDelete.since(DownloadPrefs(deleteOnceWatched = false, deleteOnceWatchedSince = NOW)))
        assertNull(AutoDelete.since(DownloadPrefs(deleteOnceWatched = true, deleteOnceWatchedSince = null)))
        assertEquals(NOW - DAY, AutoDelete.since(DownloadPrefs(deleteOnceWatched = true, deleteOnceWatchedSince = NOW - DAY)))
        val rows = listOf(watched("a", completedAt = NOW - 5 * DAY, watchedAt = NOW - 3 * DAY))
        assertNull(AutoDelete.dueAt(rows.single(), null))
        assertEquals(emptyList(), AutoDelete.due(rows, null, NOW + 10 * DAY))
        assertNull(AutoDelete.nextAt(rows, null, NOW))
        assertEquals(emptySet(), AutoDelete.tomorrow(rows, null, NOW))
    }

    /** L413: a download a play holds is left, though due: it goes once its hold ends. */
    @Test
    fun `a held download is left, though due`() {
        val rows = listOf(
            watched("a", completedAt = NOW - 5 * DAY, watchedAt = NOW - 3 * DAY),
            watched("b", completedAt = NOW - 5 * DAY, watchedAt = NOW - 2 * DAY),
        )
        assertEquals(listOf("b"), AutoDelete.due(rows, NOW - 4 * DAY, NOW) { it == "a" })
        assertEquals(listOf("a", "b"), AutoDelete.due(rows, NOW - 4 * DAY, NOW))
    }

    /** L414: the next due time still ahead, which the runner waits for; none once every one is due, or with none watched. */
    @Test
    fun `the next due time is the earliest still ahead`() {
        val rows = listOf(
            watched("due", completedAt = NOW - 5 * DAY, watchedAt = NOW - 3 * DAY),
            watched("later", completedAt = NOW - 5 * DAY, watchedAt = NOW - 2 * HOUR),
            watched("soon", completedAt = NOW - 5 * DAY, watchedAt = NOW - 20 * HOUR),
        )
        val since = NOW - 4 * DAY
        assertEquals(NOW + 4 * HOUR, AutoDelete.nextAt(rows, since, NOW))
        assertEquals(NOW + 22 * HOUR, AutoDelete.nextAt(rows, since, NOW + 4 * HOUR))
        assertNull(AutoDelete.nextAt(rows, since, NOW + 22 * HOUR))
        assertNull(AutoDelete.nextAt(emptyList(), since, NOW))
    }

    /**
     * L415, L444: "Deleting tomorrow" for the downloads that will go and aren't due yet; one already due (in the start's two
     * minutes, or held by a play) says nothing more, held or not.
     */
    @Test
    fun `Deleting tomorrow is said of those still ahead, none once due`() {
        assertEquals("Deleting tomorrow", AutoDelete.DELETING_TOMORROW)
        val rows = listOf(
            watched("due", completedAt = NOW - 5 * DAY, watchedAt = NOW - 3 * DAY),
            watched("ahead", completedAt = NOW - 5 * DAY, watchedAt = NOW - 2 * HOUR),
        )
        val since = NOW - 4 * DAY
        assertEquals(setOf("ahead"), AutoDelete.tomorrow(rows, since, NOW))
        assertEquals(emptySet(), AutoDelete.tomorrow(rows, since, NOW - 2 * HOUR + DAY))
        // Switched on just now: every watched one goes tomorrow.
        assertEquals(setOf("due", "ahead"), AutoDelete.tomorrow(rows, NOW, NOW))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 60 * 60_000L
        const val DAY = 24 * HOUR
        const val SHOW = 990_741

        fun watched(id: String, completedAt: Long, watchedAt: Long) = WatchedDownload(id, WatchKey.episode(SHOW, 1, id.length), completedAt, watchedAt)
    }
}
