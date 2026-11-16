package io.skjaere.usenet.app.home

import io.skjaere.usenet.app.download.DownloadHostRules
import io.skjaere.usenet.app.download.DownloadRunner
import io.skjaere.usenet.app.download.Tone
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Home's Downloads section (L384, DlPhoneHome's online section): shown only while something is queued or downloaded; at
 * most three rows, the one the queue is at first, then the latest; a show's finished episodes as one row; each row opening
 * what it names (the page, a show's downloads, a film played); the header saying what downloads hold. Invented titles.
 */
class HomeDownloadsTest {
    private val saved = Locale.getDefault()

    // Sizes are said with the device's decimal mark: these read as written here.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    private fun episode(
        id: String,
        show: Int,
        title: String,
        season: Int,
        number: Int,
        state: DownloadState,
        size: Long = 1_800_000_000,
        at: Long = 1,
        reason: String? = null,
        file: Boolean = state == DownloadState.Done,
        written: Long = if (state == DownloadState.Done) size else 0,
    ) = Download(
        id = id, watchKey = WatchKey.episode(show, season, number), kind = DownloadKind.Episode, title = title, showTmdbId = show, season = season,
        episode = number, state = state, reason = reason, position = at, size = size.takeIf { file }, indexerSize = size, safeOffset = written,
        relativePath = if (file) "downloads/$id/v.mkv" else null, createdAt = at, completedAt = at.takeIf { state == DownloadState.Done }, updatedAt = at,
    )

    private fun film(id: String, title: String, state: DownloadState, size: Long = 5_500_000_000, at: Long = 1, gaps: Int = 0) = Download(
        id = id, watchKey = WatchKey.movie(id.hashCode()), kind = DownloadKind.Movie, title = title, movieTmdbId = id.hashCode(), year = 2022,
        state = state, position = at, size = size.takeIf { state == DownloadState.Done }, indexerSize = size, safeOffset = if (state == DownloadState.Done) size else 0,
        relativePath = if (state == DownloadState.Done) "downloads/$id/v.mkv" else null, gaps = (0 until gaps).map { DownloadGap(it * 10L, it * 10L + 1) },
        createdAt = at, completedAt = at.takeIf { state == DownloadState.Done }, updatedAt = at,
    )

    private fun item(row: Download, written: Long = row.safeOffset) = DownloadRunner.Item(row, written, null)

    private val running = episode("hl-4", HL, "Harbor Lights", 2, 4, DownloadState.Running, size = 3_100_000_000, file = true, written = 1_302_000_000, at = 5)
    private val crown = film("cc", "Copper Crown", DownloadState.Done, at = 50)
    private val meridian = listOf(
        episode("qm-1", QM, "Quiet Meridian", 1, 1, DownloadState.Done, at = 30),
        episode("qm-2", QM, "Quiet Meridian", 1, 2, DownloadState.Done, at = 40),
    )
    private val older = film("lf", "Lantern Field", DownloadState.Done, size = 2_000_000_000, at = 10)

    @Test
    fun `none while nothing is queued or downloaded`() {
        assertNull(HomeDownloads.of(emptyList()))
        // One removed is hidden at once (L379): none either.
        assertNull(HomeDownloads.of(listOf(item(crown.copy(state = DownloadState.Deleting)))))
    }

    @Test
    fun `the one the queue is at first, then the latest, three at most`() {
        val home = assertNotNull(HomeDownloads.of((listOf(older, crown) + meridian + running).map(::item)))
        assertEquals(listOf("Harbor Lights", "Copper Crown", "Quiet Meridian"), home.rows.map { it.title })
        // Without one under way, the latest alone; a show's episodes are one row, as late as its latest.
        val idle = assertNotNull(HomeDownloads.of((listOf(older, crown) + meridian).map(::item)))
        assertEquals(listOf("Copper Crown", "Quiet Meridian", "Lantern Field"), idle.rows.map { it.title })
        // One held by a pause is where the queue is: first, though older than the rest.
        val paused = episode("hl-4", HL, "Harbor Lights", 2, 4, DownloadState.Paused, at = 1)
        assertEquals("Harbor Lights", HomeDownloads.of((listOf(crown, paused) + meridian).map(::item))?.rows?.first()?.title)
        // Queued ones are rows of their own, by when they were added.
        val queued = episode("hl-5", HL, "Harbor Lights", 2, 5, DownloadState.Queued, at = 60)
        assertEquals(listOf("hl-4", "hl-5", "cc"), HomeDownloads.of(listOf(running, crown, queued).map(::item))?.rows?.map { it.key })
    }

    @Test
    fun `each row says what it is, as drawn, and opens what it names`() {
        val home = assertNotNull(HomeDownloads.of((listOf(crown) + meridian + running).map(::item)))
        val (hl, cc, qm) = home.rows
        assertEquals(HomeDownloadRow("hl-4", "Harbor Lights", "S02E04 · downloading 42%", Tone.Accent, 0.42f, HomeDownloadOpen.Page), hl)
        assertEquals(HomeDownloadRow("cc", "Copper Crown", "Downloaded · 5.5 GB", Tone.Muted, null, HomeDownloadOpen.Play(crown)), cc)
        assertEquals(HomeDownloadRow("show-$QM", "Quiet Meridian", "2 episodes · 3.6 GB", Tone.Muted, null, HomeDownloadOpen.Show(QM)), qm)
        // Not drawn: the other states, each by its words on the Show page's row, the page for each.
        fun status(row: Download) = HomeDownloads.of(listOf(item(row)))?.rows?.single()?.let { it.status to it.tone }
        assertEquals("S02E05 · queued for download" to Tone.Muted, status(episode("q", HL, "Harbor Lights", 2, 5, DownloadState.Queued)))
        assertEquals(
            "S02E04 · paused 18%" to Tone.Muted,
            status(episode("p", HL, "Harbor Lights", 2, 4, DownloadState.Paused, size = 1_000_000_000, file = true, written = 180_000_000)),
        )
        assertEquals(
            "S02E04 · waiting for a network" to Tone.Muted,
            status(episode("w", HL, "Harbor Lights", 2, 4, DownloadState.Waiting, reason = DownloadHostRules.WAITING_FOR_NETWORK)),
        )
        assertEquals("S02E04 · finding releases…" to Tone.Muted, status(episode("f", HL, "Harbor Lights", 2, 4, DownloadState.Resolving)))
        assertEquals("S02E04 · download failed" to Tone.Warn, status(episode("x", HL, "Harbor Lights", 2, 4, DownloadState.Failed)))
        assertEquals("S02E04 · stopped by Android" to Tone.WarnStrong, status(episode("s", HL, "Harbor Lights", 2, 4, DownloadState.Stopped)))
        assertEquals("1 episode · 1.8 GB" to Tone.Muted, status(meridian.first()))
        // A film says its state with no code before it.
        assertEquals("Queued for download" to Tone.Muted, status(film("q2", "Copper Crown", DownloadState.Queued)))
        assertEquals(HomeDownloadOpen.Page, HomeDownloads.of(listOf(item(film("q2", "Copper Crown", DownloadState.Queued))))?.rows?.single()?.opens)
    }

    @Test
    fun `the header says what downloads hold on the device, the running one whole and a queued one nothing yet`() {
        val queued = episode("hl-5", HL, "Harbor Lights", 2, 5, DownloadState.Queued, size = 2_000_000_000)
        val home = assertNotNull(HomeDownloads.of((listOf(crown, older, queued) + meridian + running).map(::item)))
        // 3.1 + 5.5 + 2.0 + 1.8 + 1.8: every row's file, the three shown or not.
        assertEquals("Downloads · 14.2 GB", home.header)
    }

    private companion object {
        const val HL = 990_717
        const val QM = 990_718
    }
}
