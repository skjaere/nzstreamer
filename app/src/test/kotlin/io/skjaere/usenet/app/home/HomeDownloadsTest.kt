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
 * most three rows, the one the queue is at first, then the latest; a show's finished episodes as one row, and a show with
 * episodes still to finish one row too, with how many are in its queue (L436); each row opening what it names (the page,
 * a show's downloads, a film played); the header saying what downloads hold. Invented titles.
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
        // A queued film is a row of its own, by when it was added; a show's queued episodes are its one row (L436).
        val queued = film("lf-2", "Lantern Field", DownloadState.Queued, at = 60)
        val next = episode("hl-5", HL, "Harbor Lights", 2, 5, DownloadState.Queued, at = 70)
        assertEquals(listOf("show-$HL", "lf-2", "cc"), HomeDownloads.of(listOf(running, crown, queued, next).map(::item))?.rows?.map { it.key })
    }

    @Test
    fun `each row says what it is, as drawn, and opens what it names`() {
        val home = assertNotNull(HomeDownloads.of((listOf(crown) + meridian + running).map(::item)))
        val (hl, cc, qm) = home.rows
        // A show with one episode still to finish: its row says that episode alone, as before (L436).
        assertEquals(HomeDownloadRow("show-$HL", "Harbor Lights", "S02E04 · downloading 42%", Tone.Accent, 0.42f, HomeDownloadOpen.Page), hl)
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
        // Nothing with a file yet (queued, or failed): the section's name alone, never "0 KB".
        val failed = episode("hl-6", HL, "Harbor Lights", 2, 6, DownloadState.Failed)
        assertEquals("Downloads", assertNotNull(HomeDownloads.of(listOf(queued, failed).map(::item))).header)
    }

    /** Harbor Lights' season 2 queued (L436): S02E01 being written, 42 % of it, and four more queued after it. */
    private val season = listOf(episode("hl-1", HL, "Harbor Lights", 2, 1, DownloadState.Running, size = 3_100_000_000, file = true, written = 1_302_000_000, at = 5)) +
        (5..8).map { episode("hl-$it", HL, "Harbor Lights", 2, it, DownloadState.Queued, at = 5) }

    @Test
    fun `a show's episodes still to finish are one row, how many with the one written, then the first as its own row said it`() {
        val home = assertNotNull(HomeDownloads.of((season + crown + meridian).map(::item)))
        assertEquals(
            listOf(
                HomeDownloadRow("show-$HL", "Harbor Lights", "S02E01 · downloading 42%", Tone.Accent, 0.42f, HomeDownloadOpen.Page, queue = "5 in the queue"),
                HomeDownloadRow("cc", "Copper Crown", "Downloaded · 5.5 GB", Tone.Muted, null, HomeDownloadOpen.Play(crown)),
                HomeDownloadRow("show-$QM", "Quiet Meridian", "2 episodes · 3.6 GB", Tone.Muted, null, HomeDownloadOpen.Show(QM)),
            ),
            home.rows,
        )
        // Two shows queued: a row each, the one the queue is at first.
        val other = listOf(3, 4).map { episode("qm-$it", QM, "Quiet Meridian", 1, it, DownloadState.Queued, at = 9) }
        val both = assertNotNull(HomeDownloads.of((season + other).map(::item)))
        assertEquals(listOf("show-$HL" to "5 in the queue", "show-$QM" to "2 in the queue"), both.rows.map { it.key to it.queue })
        assertEquals("S01E03 · queued for download", both.rows[1].status)
    }

    @Test
    fun `while another title is written, a show's row says its first episode queued`() {
        val lantern = film("lf", "Lantern Field", DownloadState.Running, size = 2_000_000_000, at = 50).copy(size = 2_000_000_000, relativePath = "downloads/lf/v.mkv")
        val queued = (5..6).map { episode("hl-$it", HL, "Harbor Lights", 2, it, DownloadState.Queued, at = 5) }
        val home = assertNotNull(HomeDownloads.of((listOf(lantern) + queued).map(::item)))
        assertEquals(listOf("lf", "show-$HL"), home.rows.map { it.key })
        assertEquals(
            HomeDownloadRow("show-$HL", "Harbor Lights", "S02E05 · queued for download", Tone.Muted, null, HomeDownloadOpen.Page, queue = "2 in the queue"),
            home.rows[1],
        )
    }

    @Test
    fun `a show's finished episodes are in its row while its queue lasts, and its own row as before once it is empty`() {
        val finished = listOf(1, 2).map { episode("hl-$it", HL, "Harbor Lights", 2, it, DownloadState.Done, at = 30 + it.toLong()) }
        val left = listOf(
            episode("hl-3", HL, "Harbor Lights", 2, 3, DownloadState.Running, size = 3_100_000_000, file = true, written = 1_302_000_000, at = 5),
            episode("hl-4", HL, "Harbor Lights", 2, 4, DownloadState.Queued, at = 5),
        )
        val lasting = assertNotNull(HomeDownloads.of((finished + left + crown).map(::item)))
        assertEquals(listOf("show-$HL", "cc"), lasting.rows.map { it.key })
        assertEquals("2 in the queue", lasting.rows.first().queue)
        assertEquals(HomeDownloadOpen.Page, lasting.rows.first().opens)
        // Its last one written: the row is as it was, its episodes and their size, opening the show's downloads.
        val done = finished + left.map { it.copy(state = DownloadState.Done, safeOffset = it.indexerSize, size = it.indexerSize, relativePath = "downloads/${it.id}/v.mkv", completedAt = 60) }
        val empty = assertNotNull(HomeDownloads.of((done + crown).map(::item)))
        assertEquals(
            HomeDownloadRow("show-$HL", "Harbor Lights", "4 episodes · 8.5 GB", Tone.Muted, null, HomeDownloadOpen.Show(HL)),
            empty.rows.first(),
        )
    }

    @Test
    fun `a failed episode keeps its own row beside its show's, and three rows at most`() {
        val left = listOf(
            episode("hl-7", HL, "Harbor Lights", 2, 7, DownloadState.Running, size = 1_400_000_000, file = true, written = 168_000_000, at = 5),
            episode("hl-8", HL, "Harbor Lights", 2, 8, DownloadState.Queued, at = 5),
        )
        val failed = episode("hl-6", HL, "Harbor Lights", 2, 6, DownloadState.Failed, at = 60)
        val home = assertNotNull(HomeDownloads.of((left + failed + crown + older).map(::item)))
        assertEquals(
            listOf(
                HomeDownloadRow("show-$HL", "Harbor Lights", "S02E07 · downloading 12%", Tone.Accent, 0.12f, HomeDownloadOpen.Page, queue = "2 in the queue"),
                HomeDownloadRow("hl-6", "Harbor Lights", "S02E06 · download failed", Tone.Warn, null, HomeDownloadOpen.Page),
                HomeDownloadRow("cc", "Copper Crown", "Downloaded · 5.5 GB", Tone.Muted, null, HomeDownloadOpen.Play(crown)),
            ),
            home.rows,
        )
        // Its queue empty, the failed one still its own row, the finished ones the show's.
        val after = assertNotNull(HomeDownloads.of((left.map { it.copy(state = DownloadState.Done, completedAt = 70) } + failed).map(::item)))
        assertEquals(listOf("show-$HL" to null, "hl-6" to null), after.rows.map { it.key to it.queue })
        assertEquals(HomeDownloadOpen.Show(HL), after.rows.first().opens)
    }

    private companion object {
        const val HL = 990_717
        const val QM = 990_718
    }
}
