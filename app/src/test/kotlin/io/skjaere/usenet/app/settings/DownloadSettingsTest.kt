package io.skjaere.usenet.app.settings

import io.skjaere.usenet.app.download.DownloadHostRules
import io.skjaere.usenet.app.download.DownloadRanking
import io.skjaere.usenet.app.download.DownloadSpace
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.PlaybackPrefs
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.releases.DownloadSize
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings › Downloads (item 20; W110, L365, L371): the hub row's line, from the downloads' rows and their settings; the
 * page's words; Download size's cells, read out in full, and GB an hour as the ranker reads it (bits a second, through
 * core:releases' DownloadSize), its explanation's example worked out; the SD card's cell, shown only while a removable
 * volume is mounted; On this phone's lines and Delete all's confirm; Notifications' line. Then the page's model: a changed
 * switch is saved, the app's own listener asking for the host (L335's exception), the page never; Delete all goes through
 * the runner, from the app's scope; Android's answers are read again as the page comes to the front. Invented titles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSettingsTest {
    private val saved = Locale.getDefault()

    // formatSize follows the default locale; pin one so the decimal separator is predictable.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    @Test
    fun `the hub row's line says what downloads hold, whether one runs, and when they run`() {
        val wifi = DownloadPrefs()
        assertEquals("None yet · Wi-Fi or Ethernet only", DownloadSettings.hubLine(emptyList(), wifi))
        val held = listOf(done("a", 7_400_000_000), done("b", 5_000_000_000, movie = true))
        assertEquals("12.4 GB · Wi-Fi or Ethernet only", DownloadSettings.hubLine(held, wifi))
        assertEquals("12.4 GB · Wi-Fi or Ethernet, while charging", DownloadSettings.hubLine(held, wifi.copy(whileCharging = true)))
        assertEquals("12.4 GB · any network", DownloadSettings.hubLine(held, wifi.copy(unmeteredOnly = false)))
        assertEquals("12.4 GB · any network, while charging", DownloadSettings.hubLine(held, DownloadPrefs(unmeteredOnly = false, whileCharging = true)))
        // One under way: the running one's file counts whole, as it is allocated whole (C8); the rest of the queue counted.
        val running = row("r", DownloadState.Running, size = 2_000_000_000, file = true)
        val queued = listOf(row("q1", DownloadState.Queued), row("q2", DownloadState.Waiting, reason = DownloadHostRules.WAITING_FOR_WIFI))
        assertEquals(
            "Downloading · 2 queued · 12.4 GB",
            DownloadSettings.hubLine(listOf(done("a", 7_400_000_000), done("b", 3_000_000_000, movie = true), running) + queued, wifi),
        )
        assertEquals("Downloading · 2.0 GB", DownloadSettings.hubLine(listOf(running), wifi))
        // Being found: no file yet, nothing held.
        assertEquals("Downloading · 1 queued", DownloadSettings.hubLine(listOf(row("f", DownloadState.Resolving), queued.first()), wifi))
        // Waiting for its network, with nothing running: what holds them, and that they wait.
        assertEquals("2 queued · Wi-Fi or Ethernet only", DownloadSettings.hubLine(queued, wifi))
        assertEquals("12.4 GB · 2 queued · Wi-Fi or Ethernet only", DownloadSettings.hubLine(held + queued, wifi))
        // A paused queue is still to go; a failed one holds what it wrote and isn't queued; one being deleted counts gone.
        val paused = row("p", DownloadState.Paused, size = 1_000_000_000, file = true)
        val failed = row("x", DownloadState.Failed, size = 1_000_000_000, file = true)
        val deleting = row("d", DownloadState.Deleting, size = 9_000_000_000, file = true)
        assertEquals("2.0 GB · 1 queued · Wi-Fi or Ethernet only", DownloadSettings.hubLine(listOf(paused, failed, deleting), wifi))
    }

    @Test
    fun `the page's words are the drawn ones`() {
        assertEquals("Downloads", HubRow.Downloads.title)
        assertEquals("Downloads", DownloadSettings.TITLE)
        assertEquals("Only on Wi-Fi or Ethernet", DownloadSettings.UNMETERED)
        assertEquals("On mobile data or a metered hotspot, downloads wait.", DownloadSettings.UNMETERED_DETAIL)
        assertEquals("Only while charging", DownloadSettings.CHARGING)
        assertEquals("Downloads wait until this phone is plugged in.", DownloadSettings.CHARGING_DETAIL)
        assertEquals(
            "One download at a time, in the order you added them. While something plays, downloads go on more slowly.",
            DownloadSettings.ONE_AT_A_TIME,
        )
        assertEquals("Download size", DownloadSettings.SIZE)
        assertEquals(
            listOf(
                "Downloads rank releases as plays do, by your quality and languages, but of the same quality the smaller go first. " +
                    "Releases above this size are tried last.",
                "At 2 GB an hour, a 45-minute episode is about 1.5 GB.",
            ),
            DownloadSettings.SIZE_EXPLANATION,
        )
        assertEquals("Save downloads to", DownloadSettings.SAVE_TO)
        assertEquals("This phone", DownloadSettings.THIS_PHONE)
        assertEquals("SD card", DownloadSettings.SD_CARD)
        assertEquals("Later", DownloadSettings.LATER)
        assertEquals(
            "Downloads stay on this device, in NZStreamer’s own storage. They aren’t backed up, and uninstalling NZStreamer deletes them.",
            DownloadSettings.STAYS,
        )
        assertEquals("On this phone", DownloadSettings.ON_THIS_PHONE)
        assertEquals("Delete all", DownloadSettings.DELETE_ALL)
        assertEquals("Delete all downloads?", DownloadSettings.CONFIRM_TITLE)
        assertEquals("Notifications", DownloadSettings.NOTIFICATIONS)
    }

    @Test
    fun `Download size offers Not set and 1 to 12 GB an hour, each read out in full`() {
        assertEquals(listOf(null, 1, 2, 3, 4, 6, 8, 12), DownloadSettings.CELLS)
        assertEquals(listOf<Int?>(null) + DownloadPrefs.DOWNLOAD_SIZES_GB_PER_HOUR, DownloadSettings.CELLS)
        assertEquals("GB an hour", DownloadSettings.UNIT)
        assertEquals("No download size", DownloadSettings.label(null))
        assertEquals("Download size 2 GB an hour", DownloadSettings.label(2))
        assertEquals("Download size 12 GB an hour", DownloadSettings.label(12))
        // Two full rows of four, as Maximum bitrate's grid lays them out.
        assertEquals(listOf(listOf(null, 1, 2, 3), listOf(4, 6, 8, 12)), DownloadSettings.CELLS.chunked(4))
    }

    @Test
    fun `GB an hour is what the ranker reads in bits a second, and the explanation's example works out`() {
        assertEquals(2_222_222L, DownloadSize.bitsPerSecond(1))
        assertEquals(4_444_444L, DownloadSize.bitsPerSecond(2))
        assertEquals(26_666_666L, DownloadSize.bitsPerSecond(12))
        DownloadSettings.CELLS.filterNotNull().forEach { gb ->
            assertEquals(gb, DownloadSize.gbPerHour(DownloadSize.bitsPerSecond(gb)), "$gb GB an hour back from bits a second")
            // A cell picked is the cap the download ranking reads.
            val context = DownloadRanking.context(StaticDeviceCapabilities(displayHeight = 1080), PlaybackPrefs(), DownloadPrefs(sizeCapGbPerHour = gb), "en")
            assertEquals(DownloadSize.bitsPerSecond(gb), context.prefs.downloadCapBitsPerSecond, "$gb GB an hour")
        }
        assertEquals(null, DownloadRanking.context(StaticDeviceCapabilities(displayHeight = 1080), PlaybackPrefs(), DownloadPrefs(), "en").prefs.downloadCapBitsPerSecond)
        // 2 GB an hour for 45 minutes: 4,444,444 bits a second for 2,700 s, about 1.5 GB.
        assertEquals(1_499_999_850L, DownloadSettings.bytes(gbPerHour = 2, minutes = 45))
        assertEquals("At 2 GB an hour, a 45-minute episode is about 1.5 GB.", DownloadSettings.example(gbPerHour = 2, minutes = 45))
        assertEquals("At 4 GB an hour, a 45-minute episode is about 3.0 GB.", DownloadSettings.example(gbPerHour = 4, minutes = 45))
        assertEquals("At 1 GB an hour, a 90-minute episode is about 1.5 GB.", DownloadSettings.example(gbPerHour = 1, minutes = 90))
        assertEquals(DownloadSettings.example(gbPerHour = 2, minutes = 45), DownloadSettings.SIZE_EXPLANATION.last())
    }

    @Test
    fun `the SD card shows only while a removable volume other than the primary one is mounted`() {
        val phone = DownloadSettings.Volume(primary = true, removable = false, mounted = true)
        val card = DownloadSettings.Volume(primary = false, removable = true, mounted = true)
        assertFalse(DownloadSettings.sdCardShown(listOf(phone)))
        assertTrue(DownloadSettings.sdCardShown(listOf(phone, card)))
        assertFalse(DownloadSettings.sdCardShown(listOf(phone, card.copy(mounted = false))), "a card taken out, or not mounted yet")
        // A USB stick is a removable volume too: still Later.
        assertTrue(DownloadSettings.sdCardShown(listOf(phone, card, card)))
        // An old phone whose primary volume is its card has nothing else to offer.
        assertFalse(DownloadSettings.sdCardShown(listOf(DownloadSettings.Volume(primary = true, removable = true, mounted = true))))
        assertFalse(DownloadSettings.sdCardShown(emptyList()))
    }

    @Test
    fun `On this phone says what downloads hold, how many there are, and what is free`() {
        val space = DownloadSpace(available = 51_000_000_000, used = 12_400_000_000, queued = 3_000_000_000, deleting = 1_000_000_000)
        val rows = (1..6).map { done("e$it", 1_400_000_000) } + done("cc", 4_000_000_000, movie = true)
        // What is free as the Downloads page's strip says it: those removed counted gone at once (L379).
        assertEquals(
            DownloadSettings.Usage("12.4 GB in downloads", "6 episodes and 1 film · 48.0 GB free", deletable = true),
            DownloadSettings.usage(rows, space),
        )
        assertEquals("48.0 GB free", DownloadSettings.free(space))
        assertEquals(DownloadHostRules.STORAGE_UNAVAILABLE, DownloadSettings.free(null))
        assertEquals(
            DownloadSettings.Usage("1.4 GB in downloads", "1 episode · Storage isn't available", deletable = true),
            DownloadSettings.usage(rows.take(1), null),
        )
        assertEquals(
            DownloadSettings.Usage("8.0 GB in downloads", "2 films · 48.0 GB free", deletable = true),
            DownloadSettings.usage(listOf(done("a", 4_000_000_000, movie = true), done("b", 4_000_000_000, movie = true)), space),
        )
        // Only queued: nothing held yet, and they can still be deleted.
        assertEquals(
            DownloadSettings.Usage("Nothing downloaded yet", "2 episodes · 48.0 GB free", deletable = true),
            DownloadSettings.usage(listOf(row("q1", DownloadState.Queued), row("q2", DownloadState.Queued)), space),
        )
        // After Delete all, or before the first.
        assertEquals(DownloadSettings.Usage("No downloads", "48.0 GB free", deletable = false), DownloadSettings.usage(emptyList(), space))
        // Those removed and waiting for their Undo's end count gone.
        assertEquals(
            DownloadSettings.Usage("No downloads", "48.0 GB free", deletable = false),
            DownloadSettings.usage(listOf(row("d", DownloadState.Deleting, size = 1_000_000_000, file = true)), space),
        )
    }

    @Test
    fun `Delete all's confirm says what goes, and that a download under way stops`() {
        val rows = (1..6).map { done("e$it", 1_400_000_000) } + done("cc", 4_000_000_000, movie = true)
        assertEquals(
            "Deletes the 12.4 GB of downloads on this phone: 6 episodes and 1 film. A download running now stops.",
            DownloadSettings.confirm(rows),
        )
        assertEquals(
            "Deletes the downloads on this phone: 2 episodes. A download running now stops.",
            DownloadSettings.confirm(listOf(row("q1", DownloadState.Queued), row("q2", DownloadState.Resolving))),
        )
    }

    @Test
    fun `Notifications says whether Android shows them, and where to allow them`() {
        assertEquals("On: progress, Pause and Cancel", DownloadSettings.notificationsLine(on = true))
        assertEquals("Off: allow them in Android’s settings", DownloadSettings.notificationsLine(on = false))
    }

    /** The page's model over seams: the settings in a flow, the saves and Delete all recorded, Android's answers set. */
    private inner class Seams(work: CoroutineScope) {
        val prefs = MutableStateFlow(DownloadPrefs())
        val rows = MutableStateFlow(listOf(done("a", 7_400_000_000)))
        val events = mutableListOf<String>()
        var notifications = false
        var sdCard = false
        var spaceReads = 0

        /** What Delete all waits for before the runner has done it: the running download stopping. */
        var deleting: suspend () -> Unit = {}

        val deps = DownloadSettingsDeps(
            rows = rows,
            prefs = prefs,
            space = {
                spaceReads++
                DownloadSpace(available = 51_000_000_000, used = 7_400_000_000, queued = 0)
            },
            setUnmetered = { on ->
                events += "unmetered $on"
                prefs.value = prefs.value.copy(unmeteredOnly = on)
            },
            setCharging = { on ->
                events += "charging $on"
                prefs.value = prefs.value.copy(whileCharging = on)
            },
            setSizeCap = { gb ->
                events += "size $gb"
                prefs.value = prefs.value.copy(sizeCapGbPerHour = gb)
            },
            deleteAll = {
                deleting()
                events += "delete all"
                rows.value = emptyList()
            },
            notificationsOn = { notifications },
            sdCard = { sdCard },
            work = work,
        )
    }

    /**
     * Runs [body] with the seams and a scope of the test's own for the model, so its virtual time runs the model's work (the
     * background's tasks are left out of advanceUntilIdle), cancelled at the test's end with the flows it collects.
     */
    private fun test(body: suspend TestScope.(Seams, CoroutineScope) -> Unit) = runTest {
        val job = Job(coroutineContext[Job])
        try {
            val work = CoroutineScope(coroutineContext + job)
            body(Seams(work), work)
        } finally {
            job.cancel()
        }
    }

    /**
     * The app's own listener asks for the host as a switch's needs change, from the app in front (L335's exception): the
     * page only saves, as a second ask would stop a running download a second time.
     */
    @Test
    fun `a changed switch is saved and the page asks for no host itself, and Download size is saved alone`() = test { seams, work ->
        val model = DownloadSettingsModel(seams.deps, work)
        model.setUnmetered(false)
        advanceUntilIdle()
        model.setCharging(true)
        advanceUntilIdle()
        model.setSizeCap(4)
        advanceUntilIdle()
        model.setSizeCap(null)
        advanceUntilIdle()
        assertEquals(listOf("unmetered false", "charging true", "size 4", "size null"), seams.events)
        assertEquals(DownloadPrefs(unmeteredOnly = false, whileCharging = true), model.prefs.first { it != null })
    }

    @Test
    fun `Delete all goes through the runner and the space is read again, and Android's answers as the page comes back`() = test { seams, work ->
        val model = DownloadSettingsModel(seams.deps, work)
        work.launchCollecting(model)
        advanceUntilIdle()
        assertEquals(1, seams.spaceReads)
        assertEquals(listOf(done("a", 7_400_000_000)), model.rows.value)
        assertFalse(model.notificationsOn.value)
        assertFalse(model.sdCard.value)

        model.deleteAll()
        advanceUntilIdle()
        assertEquals(listOf("delete all"), seams.events)
        assertEquals(emptyList(), model.rows.value)
        assertEquals(2, seams.spaceReads, "the space read again once the rows are gone")

        // Allowed in Android's settings, a card put in, meanwhile: read again as the page is in front again.
        seams.notifications = true
        seams.sdCard = true
        assertFalse(model.notificationsOn.value)
        model.shown()
        assertTrue(model.notificationsOn.value)
        assertTrue(model.sdCard.value)
    }

    @Test
    fun `Delete all asked as the page goes is still made, the runner's call being the app's own work`() = test { seams, _ ->
        val stopped = CompletableDeferred<Unit>()
        seams.deleting = { stopped.await() }
        val page = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val model = DownloadSettingsModel(seams.deps, page)
        model.deleteAll()
        runCurrent()
        // Back once confirmed: the page's view model is cleared while the runner stops the download under way.
        page.cancel()
        stopped.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("delete all"), seams.events, "every download deleted, as the confirm said")
    }

    /** Keeps [model]'s flows collected, as the page does while it shows. */
    private fun CoroutineScope.launchCollecting(model: DownloadSettingsModel) {
        listOf(model.prefs, model.rows, model.space).forEach { flow -> launch { flow.collect {} } }
    }

    private companion object {
        const val SHOW = 990_717
        const val FILM = 990_719

        /** An episode's row in [state], of [size] bytes, its file made where [file] (allocated whole, C8). */
        fun row(id: String, state: DownloadState, size: Long = 1_500_000_000, file: Boolean = false, reason: String? = null, movie: Boolean = false): Download =
            Download(
                id = id, watchKey = if (movie) WatchKey.movie(FILM) else WatchKey.episode(SHOW, 2, id.hashCode() and 0xff),
                kind = if (movie) DownloadKind.Movie else DownloadKind.Episode, title = if (movie) "Copper Crown" else "Harbor Lights",
                showTmdbId = SHOW.takeIf { !movie }, season = 2.takeIf { !movie }, episode = (id.hashCode() and 0xff).takeIf { !movie },
                movieTmdbId = FILM.takeIf { movie }, state = state, reason = reason, position = 0, size = size.takeIf { file }, indexerSize = size,
                relativePath = if (file) "downloads/$id/v.mkv" else null, createdAt = 1, updatedAt = 1,
            )

        /** A finished download of [size] bytes: an episode, or a film. */
        fun done(id: String, size: Long, movie: Boolean = false): Download = row(id, DownloadState.Done, size, file = true, movie = movie)
    }
}
