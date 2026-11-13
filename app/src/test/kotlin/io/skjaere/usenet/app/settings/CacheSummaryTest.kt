package io.skjaere.usenet.app.settings

import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.core.VideoCacheUsage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What Settings › Cache and its row on the settings hub say about the video cache and the data stored. */
class CacheSummaryTest {
    private val saved = Locale.getDefault()

    // formatSize follows the default locale; pin one so the decimal separator is predictable.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    private fun usage(used: Long, limit: Long = 2 * GB, chosen: Long = 2 * GB, free: Long = 50 * GB, on: Boolean = true) =
        VideoCacheUsage(on, used, limit, chosen, free)

    @Test
    fun `the hub row says how much of the limit is used, or that it is off`() {
        assertEquals("1.2 GB of 2 GB used", cacheSummary(usage(used = 1_200_000_000)))
        assertEquals("0 KB of 10 GB used", cacheSummary(usage(used = 0, limit = 10 * GB, chosen = 10 * GB)))
        assertEquals("350 MB of 1 GB used", cacheSummary(usage(used = 350_000_000, limit = GB, chosen = GB)))
        // Held to what fits on the device: that is its limit.
        assertEquals("1.2 GB of 1.4 GB used", cacheSummary(usage(used = 1_200_000_000, limit = 1_400_000_000, chosen = 5 * GB)))
        assertEquals("No room on this device", cacheSummary(usage(used = 0, limit = 0)))
        assertEquals("Off", cacheSummary(usage(used = 0, limit = 0, on = false)))
        // Before the settings are read.
        assertEquals("", cacheSummary(null))
    }

    /** A debrid play isn't kept (L187): while a service is on, the switch says what it keeps. */
    @Test
    fun `with a debrid service on, the switch says it keeps Usenet plays only`() {
        assertEquals("Seeking back, playing again and resuming read it from here, not from your provider. Off deletes it.", keepVideoDetail(serviceOn = false))
        assertEquals(
            "Seeking back, playing again and resuming read it from here, not from your provider. Off deletes it. Keeps Usenet plays only.",
            keepVideoDetail(serviceOn = true),
        )
    }

    @Test
    fun `the screen says why the cache holds less than the limit chosen`() {
        assertNull(roomNote(usage(used = GB)))
        assertNull(roomNote(usage(used = 0, limit = 0, on = false)))
        assertEquals(
            "Only 1.4 GB fits, leaving 1 GB of this device's storage free.",
            roomNote(usage(used = 0, limit = 1_400_000_000, chosen = 5 * GB, free = 2_400_000_000)),
        )
        assertEquals(
            "No room: nothing is kept while this device has under 1.1 GB free.",
            roomNote(usage(used = 0, limit = 0, free = 900_000_000)),
        )
    }

    @Test
    fun `limits read as whole gigabytes when they are`() {
        assertEquals("2 GB", formatLimit(2 * GB))
        assertEquals("10 GB", formatLimit(10 * GB))
        assertEquals("1.4 GB", formatLimit(1_400_000_000))
        assertEquals("500 MB", formatLimit(500_000_000))
    }

    @Test
    fun `the NZB files kept for resume are counted`() {
        assertEquals("None", nzbFilesLine(PreparedNzbStore.StoredFiles(0, 0)))
        assertEquals("1 file · 52 KB", nzbFilesLine(PreparedNzbStore.StoredFiles(1, 52_000)))
        assertEquals("10 files · 1.2 MB", nzbFilesLine(PreparedNzbStore.StoredFiles(10, 1_200_000)))
    }

    @Test
    fun `the releases found incomplete are counted, or none`() {
        assertEquals("None", triedReleasesLine(0))
        assertEquals("1 release", triedReleasesLine(1))
        assertEquals("1,204 releases", triedReleasesLine(1_204))
    }

    private companion object {
        const val GB = 1_000_000_000L
    }
}
