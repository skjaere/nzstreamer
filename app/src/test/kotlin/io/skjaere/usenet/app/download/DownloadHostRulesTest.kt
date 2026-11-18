package io.skjaere.usenet.app.download

import android.app.job.JobParameters
import io.skjaere.usenet.app.casting.CastWakeLock
import io.skjaere.usenet.app.download.DownloadHostRules.JobStop
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.debrid.ServiceKind
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The downloads' hosts as their rules decide them (L334-L336, L358, L369, L375, L380, L388): which host for the API level,
 * what a download needs, what a stop of the job means, which rows can run, when the boot receiver is on, the service's wait
 * cap, when the app schedules, the wake lock, and the notification's words. Nothing of it on a TV. The show, the film and
 * the release are invented.
 */
class DownloadHostRulesTest {
    @Test
    fun `the job from API 34, the service from 26 to 33, and the service on any level with the override`() {
        (26..33).forEach { assertEquals(DownloadHost.Service, DownloadHostRules.hostFor(it, override = false), "API $it") }
        (34..36).forEach { assertEquals(DownloadHost.Job, DownloadHostRules.hostFor(it, override = false), "API $it") }
        // The debug override (L336): the service host runs on phone34 and tv36's levels too.
        listOf(26, 33, 34, 36).forEach { assertEquals(DownloadHost.Service, DownloadHostRules.hostFor(it, override = true), "API $it") }
    }

    @Test
    fun `nothing runs on a TV`() {
        assertTrue(DownloadHostRules.runs(television = false))
        assertFalse(DownloadHostRules.runs(television = true))
    }

    @Test
    fun `a download needs an unmetered network or any, and the charger or not, as the settings say`() {
        // Only on Wi-Fi or Ethernet on, while charging off: a new install's (W110, L365).
        assertEquals(DownloadNeeds(unmetered = true, charging = false), DownloadHostRules.needs(DownloadPrefs()))
        assertEquals(DownloadNeeds(unmetered = false, charging = true), DownloadHostRules.needs(DownloadPrefs(unmeteredOnly = false, whileCharging = true)))
    }

    @Test
    fun `a stop for the network waits for Wi-Fi, or for a network with Wi-Fi only off, and the job runs again`() {
        val wifiOnly = DownloadHostRules.onStop(JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY, DownloadNeeds(unmetered = true, charging = false))
        assertEquals(JobStop.Wait("Waiting for Wi-Fi"), wifiOnly)
        assertTrue(wifiOnly.reschedule)
        val anyNetwork = DownloadHostRules.onStop(JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY, DownloadNeeds(unmetered = false, charging = false))
        assertEquals(JobStop.Wait("Waiting for a network"), anyNetwork)
        assertTrue(anyNetwork.reschedule)
    }

    @Test
    fun `a stop for the charger waits to charge, and the job runs again`() {
        val stop = DownloadHostRules.onStop(JobParameters.STOP_REASON_CONSTRAINT_CHARGING, DownloadNeeds(unmetered = true, charging = true))
        assertEquals(JobStop.Wait("Waiting to charge"), stop)
        assertTrue(stop.reschedule)
    }

    @Test
    fun `a timeout and the system's own reasons run the job again as it is`() {
        val systems = listOf(
            JobParameters.STOP_REASON_UNDEFINED, JobParameters.STOP_REASON_PREEMPT, JobParameters.STOP_REASON_TIMEOUT,
            JobParameters.STOP_REASON_DEVICE_STATE, JobParameters.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW,
            JobParameters.STOP_REASON_CONSTRAINT_DEVICE_IDLE, JobParameters.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW,
            JobParameters.STOP_REASON_QUOTA, JobParameters.STOP_REASON_BACKGROUND_RESTRICTION, JobParameters.STOP_REASON_APP_STANDBY,
            JobParameters.STOP_REASON_SYSTEM_PROCESSING, JobParameters.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED,
            // Android 16's STOP_REASON_TIMEOUT_ABANDONED, and one a later Android may add.
            16, 99,
        )
        systems.forEach { reason ->
            val stop = DownloadHostRules.onStop(reason, DownloadNeeds(unmetered = true, charging = false))
            assertEquals(JobStop.Again, stop, "reason $reason")
            assertTrue(stop.reschedule, "reason $reason")
        }
    }

    @Test
    fun `the user's stop is Stopped by Android, and the app's own cancel ends it, neither run again`() {
        val user = DownloadHostRules.onStop(JobParameters.STOP_REASON_USER, DownloadNeeds(unmetered = true, charging = false))
        assertEquals(JobStop.Stopped, user)
        assertFalse(user.reschedule)
        // Cancel or Pause in the app: the rows are as the app left them.
        val app = DownloadHostRules.onStop(JobParameters.STOP_REASON_CANCELLED_BY_APP, DownloadNeeds(unmetered = true, charging = false))
        assertEquals(JobStop.Cancelled, app)
        assertFalse(app.reschedule)
    }

    @Test
    fun `queued rows, waits for a network or the charger, and rows a kill left running can run, in the queue's order`() {
        val rows = listOf(
            row("a", DownloadState.Done),
            row("b", DownloadState.Running),
            row("c", DownloadState.Waiting, reason = "Waiting for Wi-Fi"),
            row("d", DownloadState.Resolving),
            row("e", DownloadState.Waiting, reason = "Waiting for a network"),
            row("f", DownloadState.Failed, reason = "Storage is full"),
            row("g", DownloadState.Waiting, reason = "Waiting to charge"),
            row("h", DownloadState.Queued),
            row("i", DownloadState.Deleting),
        )
        assertEquals(listOf("b", "c", "d", "e", "g", "h"), DownloadHostRules.runnable(rows).map { it.id })
    }

    @Test
    fun `waits for the provider or for storage end the host, until a storage wait's folder is back at the app's start`() {
        val provider = listOf(
            row("a", DownloadState.Waiting, reason = "Can't reach your Usenet provider"),
            row("b", DownloadState.Waiting, reason = "Check your Usenet provider"),
            row("j", DownloadState.Waiting, reason = "Can't reach your indexer"),
        )
        assertEquals(emptyList(), DownloadHostRules.runnable(provider))
        assertEquals(emptyList(), DownloadHostRules.runnable(provider, storageBack = true))
        val storage = listOf(row("c", DownloadState.Waiting, reason = "Storage isn't available"))
        assertEquals(emptyList(), DownloadHostRules.runnable(storage))
        assertEquals(listOf("c"), DownloadHostRules.runnable(storage, storageBack = true).map { it.id })
        // The storage's own words, as DownloadStorage says them; the waits' words as the rows keep them.
        assertEquals(DownloadStorage.Unavailable().message, DownloadHostRules.STORAGE_UNAVAILABLE)
        assertEquals(listOf("Can't reach your Usenet provider", "Check your Usenet provider", "Can't reach your indexer"), listOf(
            DownloadHostRules.CANT_REACH_PROVIDER, DownloadHostRules.CHECK_PROVIDER, DownloadHostRules.CANT_REACH_INDEXER,
        ))
    }

    /**
     * L474: the debrid downloads' waits and failures, which the debrid writer brings in, each as the Usenet one it mirrors
     * in words that name no service, as the user's account may be any.
     */
    @Test
    fun `the debrid waits and failures are worded as the Usenet ones, naming no service`() {
        val words = listOf(
            DownloadHostRules.CANT_REACH_SERVICE, DownloadHostRules.CHECK_SERVICE, DownloadHostRules.SERVICE_LIMIT, DownloadHostRules.SERVICE_KEPT_FAILING,
            DownloadHostRules.CANT_RESUME, DownloadHostRules.NOT_IN_THIS_VERSION, DownloadHostRules.WAITS_FOR_STREAM,
        )
        assertEquals(
            listOf(
                "Can't reach your debrid service", "Check your debrid service", "Your debrid service's limit is reached", "Your debrid service kept failing",
                "Its server can't resume this file", "Not available in this version", "Waits while something streams",
            ),
            words,
        )
        assertEquals(emptyList(), words.filter { word -> ServiceKind.entries.any { it.displayName in word } })
    }

    /**
     * L461, L474, C14: a debrid account's waits. Its key refused and its service out of reach for an hour end the walk,
     * and its host, as the provider's do; its limit holds its download alone, the walk going on, as an indexer's does.
     * Resume queues each of them again, a services save the account's three alone, a provider saved none of them. None
     * is a need's wait, nor runs; the failures and the stream's wait are no wait that ends anything.
     */
    @Test
    fun `the debrid waits end the host but for the limit, and Resume and a services save queue them again`() {
        val service = listOf(DownloadHostRules.CHECK_SERVICE, DownloadHostRules.CANT_REACH_SERVICE, DownloadHostRules.SERVICE_LIMIT)
        assertEquals(service.toSet(), DownloadHostRules.SERVICE_WAITS)
        assertTrue(DownloadHostRules.endsWalk(DownloadHostRules.CHECK_SERVICE))
        assertTrue(DownloadHostRules.endsWalk(DownloadHostRules.CANT_REACH_SERVICE))
        assertFalse(DownloadHostRules.endsWalk(DownloadHostRules.SERVICE_LIMIT))
        // As the Usenet ones are.
        listOf(DownloadHostRules.CHECK_PROVIDER, DownloadHostRules.CANT_REACH_PROVIDER, DownloadHostRules.CANT_REACH_INDEXER, DownloadHostRules.STORAGE_UNAVAILABLE)
            .forEach { assertTrue(DownloadHostRules.endsWalk(it), it) }
        assertFalse(DownloadHostRules.endsWalk(DownloadHostRules.LIMIT_REACHED))
        service.forEach { reason ->
            assertTrue(reason in DownloadHostRules.RESUMED_WAITS, reason)
            assertFalse(reason in DownloadHostRules.PROVIDER_WAITS, reason)
            assertFalse(DownloadHostRules.waitsForNeed(reason), reason)
        }
        assertEquals(emptyList(), DownloadHostRules.runnable(service.map { row(it, DownloadState.Waiting, reason = it) }, storageBack = true))
        // The Usenet ones Resume queues again, still; a provider saved queues none of the account's.
        listOf(DownloadHostRules.CHECK_PROVIDER, DownloadHostRules.CANT_REACH_PROVIDER, DownloadHostRules.CANT_REACH_INDEXER).forEach {
            assertTrue(it in DownloadHostRules.PROVIDER_WAITS, it)
            assertTrue(it in DownloadHostRules.RESUMED_WAITS, it)
            assertFalse(it in DownloadHostRules.SERVICE_WAITS, it)
        }
        assertTrue(DownloadHostRules.STORAGE_UNAVAILABLE in DownloadHostRules.RESUMED_WAITS)
        assertTrue(DownloadHostRules.LIMIT_REACHED in DownloadHostRules.RESUMED_WAITS)
        listOf(DownloadHostRules.SERVICE_KEPT_FAILING, DownloadHostRules.CANT_RESUME, DownloadHostRules.NOT_IN_THIS_VERSION, DownloadHostRules.WAITS_FOR_STREAM)
            .forEach { assertFalse(it in DownloadHostRules.RESUMED_WAITS, it) }
        // A save schedules the host from the visible app as a provider's does, once.
        assertTrue(schedules(ScheduleCause.ServiceSaved))
        assertFalse(schedules(ScheduleCause.ServiceSaved, walking = true))
        assertFalse(schedules(ScheduleCause.ServiceSaved, pending = WAITING))
        assertFalse(schedules(ScheduleCause.ServiceSaved, visible = false))
    }

    /**
     * L474: the services' watch hears the user's saves alone: the accounts on (switched on, not signed out, the key not
     * refused) by their ids and when their keys were saved, a save being that set gaining one. A key saved, an account
     * added or switched on, a refused key taken again by Test: yes. The app's own mark of a refused key, a plan's refresh,
     * Remove after playing switched, an account switched off or signed out: no. The settings' first read is no save.
     */
    @Test
    fun `a services save is the user's own, never the app's mark of a refused key, a refresh, a switch-off or a sign-out`() = runBlocking<Unit> {
        val torBox = ServiceAccount("acc-a", ServiceKind.TorBox.name, keySavedAt = 10)
        val premiumize = ServiceAccount("acc-b", ServiceKind.Premiumize.name, keySavedAt = 20)
        fun saved(before: List<ServiceAccount>, after: List<ServiceAccount>) = DownloadHostRules.servicesSaved(before, after)
        assertTrue(saved(listOf(torBox), listOf(torBox.copy(keySavedAt = 11))), "a new key")
        assertTrue(saved(listOf(torBox), listOf(torBox, premiumize)), "an account added")
        assertTrue(saved(emptyList(), listOf(torBox)), "the first account")
        assertTrue(saved(listOf(torBox.copy(enabled = false)), listOf(torBox)), "switched on")
        assertTrue(saved(listOf(torBox.copy(keyRefused = true)), listOf(torBox)), "a refused key taken again")
        assertTrue(saved(listOf(torBox), listOf(premiumize)), "one signed out and another added")
        assertFalse(saved(listOf(torBox), listOf(torBox.copy(keyRefused = true))), "the app's mark of a refused key")
        assertFalse(saved(listOf(torBox), listOf(torBox.copy(plan = "Pro", planUntil = 5, accountName = "quay.viewer@example.org", updatedAt = 3))), "a plan refreshed")
        assertFalse(saved(listOf(torBox), listOf(torBox.copy(removeAfterPlay = false))), "Remove after playing switched")
        assertFalse(saved(listOf(torBox, premiumize), listOf(torBox.copy(enabled = false), premiumize)), "switched off")
        assertFalse(saved(listOf(torBox, premiumize), listOf(premiumize)), "signed out")
        assertFalse(saved(listOf(torBox), listOf(torBox.copy(deleted = true))), "signed out, kept as removed")
        assertFalse(saved(listOf(torBox.copy(keyRefused = true)), listOf(torBox.copy(keyRefused = true, keySavedAt = 11))), "a new key, refused already")
        assertFalse(saved(listOf(torBox), listOf(torBox)), "nothing changed")

        // As the settings move: their first read is none, then each save once.
        val moves = listOf(
            listOf(torBox), listOf(torBox.copy(keyRefused = true)), listOf(torBox.copy(keyRefused = true, plan = "Pro")), listOf(torBox), listOf(torBox),
            listOf(torBox, premiumize),
        )
        assertEquals(2, DownloadHostRules.servicesSaves(flowOf(*moves.toTypedArray())).count())
    }

    /**
     * L399, L399b: an indexer's limit with nothing else found is a wait of its download alone, and isn't a need's: not
     * runnable, the rest of the queue runnable beside it, the job's constraints and the service's callbacks never wait for
     * it, only Resume or the app's start after its end queue it again; its notification says its words alone, with no bar.
     */
    @Test
    fun `an indexer's limit holds its download alone, a wait that isn't a need's`() {
        val limited = listOf(row("a", DownloadState.Waiting, reason = DownloadHostRules.LIMIT_REACHED))
        assertEquals(emptyList(), DownloadHostRules.runnable(limited))
        assertEquals(emptyList(), DownloadHostRules.runnable(limited, storageBack = true))
        assertEquals(listOf("b"), DownloadHostRules.runnable(limited + row("b", DownloadState.Queued)).map { it.id })
        assertFalse(DownloadHostRules.waitsForNeed(DownloadHostRules.LIMIT_REACHED))
        assertEquals("Your indexer's limit is reached", DownloadHostRules.LIMIT_REACHED)
        assertEquals("Your indexer's limit is reached", text(episode(DownloadState.Waiting, reason = DownloadHostRules.LIMIT_REACHED)))
    }

    @Test
    fun `a paused or stopped row holds the whole queue`() {
        val queue = listOf(row("a", DownloadState.Queued), row("b", DownloadState.Waiting, reason = "Waiting for Wi-Fi"))
        assertEquals(2, DownloadHostRules.runnable(queue).size)
        // Pause keeps the running item's place, Paused; nothing else starts until Resume (L380). Stopped by Android the same.
        assertEquals(emptyList(), DownloadHostRules.runnable(queue + row("c", DownloadState.Paused)))
        assertEquals(emptyList(), DownloadHostRules.runnable(listOf(row("c", DownloadState.Stopped)) + queue))
    }

    @Test
    fun `the boot receiver is on while the host is the service and something can run, never on a TV`() {
        val runnable = listOf(row("a", DownloadState.Queued))
        val held = listOf(row("a", DownloadState.Queued), row("b", DownloadState.Paused))
        // API 33: the service.
        assertTrue(DownloadHostRules.receiverOn(television = false, host = DownloadHostRules.hostFor(33, override = false), rows = runnable))
        assertFalse(DownloadHostRules.receiverOn(television = false, host = DownloadHostRules.hostFor(33, override = false), rows = held))
        assertFalse(DownloadHostRules.receiverOn(television = false, host = DownloadHostRules.hostFor(33, override = false), rows = emptyList()))
        // API 34: the job, which JobScheduler persists, needs none; with the override, the service does.
        assertFalse(DownloadHostRules.receiverOn(television = false, host = DownloadHostRules.hostFor(34, override = false), rows = runnable))
        assertTrue(DownloadHostRules.receiverOn(television = false, host = DownloadHostRules.hostFor(34, override = true), rows = runnable))
        assertFalse(DownloadHostRules.receiverOn(television = true, host = DownloadHostRules.hostFor(30, override = false), rows = runnable))
    }

    @Test
    fun `the service waits for its need an hour, then says it is paused with no connection, a tap resuming it`() {
        assertTrue(DownloadHostRules.serviceWaits(waitedMillis = 0))
        assertTrue(DownloadHostRules.serviceWaits(waitedMillis = 60 * 60_000L - 1))
        assertFalse(DownloadHostRules.serviceWaits(waitedMillis = 60 * 60_000L))
        val words = DownloadHostRules.tapToResume(episode(DownloadState.Waiting, reason = "Waiting for Wi-Fi"))
        assertEquals(NotificationWords("Harbor Lights · S02E04", "Paused: no connection · Tap to resume", null), words)
    }

    private fun schedules(
        cause: ScheduleCause,
        visible: Boolean = true,
        runnable: Boolean = true,
        walking: Boolean = false,
        pending: DownloadHostRules.JobPending = DownloadHostRules.JobPending(),
        television: Boolean = false,
    ) = DownloadHostRules.schedules(cause, television = television, visible = visible, runnable = runnable, walking = walking, pending = pending)

    @Test
    fun `the app schedules only while visible, with something to run, and with no walk to take it up nor a job pending, but for a changed switch`() {
        ScheduleCause.entries.forEach { cause ->
            assertTrue(schedules(cause), "$cause")
            // From the background, Android refuses a user-initiated job: never asked there (L335, C4).
            assertFalse(schedules(cause, visible = false), "$cause")
            assertFalse(schedules(cause, runnable = false), "$cause")
            assertFalse(schedules(cause, television = true), "$cause")
        }
        // A schedule stops a running job of the same id (C5): once only, as the job runs or waits for its needs.
        listOf(ScheduleCause.Download, ScheduleCause.Resume, ScheduleCause.ProviderSaved, ScheduleCause.AppStart).forEach { cause ->
            assertFalse(schedules(cause, walking = true), "$cause")
            assertFalse(schedules(cause, pending = AHEAD), "$cause")
        }
        listOf(ScheduleCause.Download, ScheduleCause.Resume, ScheduleCause.ProviderSaved).forEach { cause ->
            assertFalse(schedules(cause, pending = WAITING), "$cause")
        }
        // A changed Wi-Fi or charging switch: a pending job takes the new needs, a running item stops and goes on under them.
        assertTrue(schedules(ScheduleCause.SwitchChanged, walking = true))
        assertTrue(schedules(ScheduleCause.SwitchChanged, pending = WAITING))
        assertTrue(schedules(ScheduleCause.SwitchChanged, pending = AHEAD))
    }

    /**
     * L404: the app's start schedules again a job that waits to run and isn't executing (under its network or the charger,
     * or its backoff after a stop), as a schedule from the visible app undoes Android's demotion of a user-initiated job
     * after 24 hours of its runtime, and a waiting job has no walk to cut. One executing, its walk under way, or ahead of its
     * walk is left alone, as a schedule would stop it (C5). Still only from the visible app, with something to run, never on
     * a TV.
     */
    @Test
    fun `the app's start schedules a job that waits to run again, never one executing or ahead of its walk`() {
        assertTrue(schedules(ScheduleCause.AppStart, pending = WAITING))
        assertFalse(schedules(ScheduleCause.AppStart, pending = AHEAD))
        assertFalse(schedules(ScheduleCause.AppStart, walking = true))
        assertFalse(schedules(ScheduleCause.AppStart, walking = true, pending = WAITING))
        assertFalse(schedules(ScheduleCause.AppStart, pending = WAITING, visible = false))
        assertFalse(schedules(ScheduleCause.AppStart, pending = WAITING, runnable = false))
        assertFalse(schedules(ScheduleCause.AppStart, pending = WAITING, television = true))
    }

    @Test
    fun `the service holds its own wake lock only while bytes flow, as the cast service does, the job's being the system's`() {
        assertTrue(DownloadHostRules.ownWakeLock(DownloadHost.Service))
        assertFalse(DownloadHostRules.ownWakeLock(DownloadHost.Job))
        val lock = DownloadHostRules.wakeLock()
        assertNull(lock.tick(now = 0, sent = 0))
        assertEquals(CastWakeLock.Act.Acquire, lock.tick(now = 1_000, sent = 64 * 1024))
        assertNull(lock.tick(now = 5_000, sent = 128 * 1024))
        // Taken again at half its timeout while the bytes go on, so no missed path holds it unbounded.
        assertEquals(CastWakeLock.Act.Acquire, lock.tick(now = 1_000 + DownloadHostRules.WAKE_TIMEOUT_MILLIS / 2, sent = 256 * 1024))
        // Let go once no byte has come for 10 s: waiting for a network, or between items, holds none.
        val last = 1_000 + DownloadHostRules.WAKE_TIMEOUT_MILLIS / 2
        assertNull(lock.tick(now = last + 9_999, sent = 256 * 1024))
        assertEquals(CastWakeLock.Act.Release, lock.tick(now = last + 10_000, sent = 256 * 1024))
    }

    @Test
    fun `the notification says the show and episode, or the film, and how far each state is`() {
        val rate = 5_833_334L // 700 MB to go: 2 min.
        assertEquals(
            NotificationWords("Harbor Lights · S02E04", "Downloading · 0.5 of 1.2 GB · 2 min left", "2 more queued"),
            DownloadHostRules.words(episode(DownloadState.Running), written = HALF, bytesPerSecond = rate, more = 2),
        )
        assertEquals("Downloading · 0.5 of 1.2 GB", text(episode(DownloadState.Running), bytesPerSecond = null))
        assertEquals("Paused · 0.5 of 1.2 GB", text(episode(DownloadState.Paused)))
        assertEquals("Waiting for Wi-Fi · 0.5 of 1.2 GB", text(episode(DownloadState.Waiting, reason = "Waiting for Wi-Fi")))
        assertEquals("Waiting for a network · 0.5 of 1.2 GB", text(episode(DownloadState.Waiting, reason = "Waiting for a network")))
        assertEquals("Waiting to charge · 0.5 of 1.2 GB", text(episode(DownloadState.Waiting, reason = "Waiting to charge")))
        // The waits a host ends on say what is wrong alone.
        assertEquals("Can't reach your Usenet provider", text(episode(DownloadState.Waiting, reason = "Can't reach your Usenet provider")))
        assertEquals("Check your Usenet provider", text(episode(DownloadState.Waiting, reason = "Check your Usenet provider")))
        assertEquals("Can't reach your indexer", text(episode(DownloadState.Waiting, reason = "Can't reach your indexer")))
        assertEquals("Storage isn't available", text(episode(DownloadState.Waiting, reason = "Storage isn't available")))
        // While the writer backs off, its row says why beside how far it is.
        assertEquals("Can't reach your Usenet provider · 0.5 of 1.2 GB", text(episode(DownloadState.Running, reason = "Can't reach your Usenet provider")))
        assertEquals("Stopped by Android", text(episode(DownloadState.Stopped)))
        assertEquals("Queued", text(episode(DownloadState.Queued)))
        assertEquals("Finding releases…", text(episode(DownloadState.Resolving)))
        assertEquals("Trying release 2 of 3 · Checking availability", text(episode(DownloadState.Resolving, reason = "Trying release 2 of 3 · Checking availability")))
        assertEquals("Downloaded · 1.2 GB", text(episode(DownloadState.Done)))
        assertEquals("Downloaded · 1 part missing · 1.2 GB", text(episode(DownloadState.Done, gaps = 1)))
        assertEquals("Downloaded · 2 parts missing · 1.2 GB", text(episode(DownloadState.Done, gaps = 2)))
        assertEquals(
            "Couldn't download it. Your provider is missing parts of all 3 releases tried.",
            text(episode(DownloadState.Failed, reason = "Your provider is missing parts of all 3 releases tried")),
        )
        assertEquals("Couldn't download it.", text(episode(DownloadState.Failed)))
        // A removed download is hidden at once (L379): no notification.
        assertNull(DownloadHostRules.words(episode(DownloadState.Deleting)))

        val film = episode(DownloadState.Paused).copy(kind = DownloadKind.Movie, title = "Copper Crown", season = null, episode = null, watchKey = WatchKey.movie(900003))
        assertEquals(NotificationWords("Copper Crown", "Paused · 0.5 of 1.2 GB", null), DownloadHostRules.words(film))
    }

    @Test
    fun `sizes read in the whole size's unit, and time left in minutes and hours`() {
        val small = episode(DownloadState.Running).copy(size = 700_000_000)
        assertEquals("Downloading · 350 of 700 MB · 1 min left", text(small, written = 350_000_000, bytesPerSecond = 10_000_000))
        val tiny = episode(DownloadState.Paused).copy(size = 8_100_000)
        assertEquals("Paused · 2.3 of 8.1 MB", text(tiny, written = 2_340_000))
        // An hour and a bit at 100 KB/s for 390 MB.
        val slow = episode(DownloadState.Running).copy(size = 400_000_000)
        assertEquals("Downloading · 10 of 400 MB · 1 h 5 min left", text(slow, written = 10_000_000, bytesPerSecond = 100_000))
        // Before Resolve, the indexer's size; with none, how far it is goes unsaid.
        val unresolved = episode(DownloadState.Waiting, reason = "Waiting for Wi-Fi").copy(size = null, indexerSize = 1_200_000_000)
        assertEquals("Waiting for Wi-Fi · 0.0 of 1.2 GB", text(unresolved, written = 0))
        assertEquals("Waiting for Wi-Fi", text(unresolved.copy(indexerSize = 0), written = 0))
        // Nothing more queued: no line for it.
        assertNull(DownloadHostRules.words(episode(DownloadState.Running), more = 0)!!.subText)
        assertEquals("1 more queued", DownloadHostRules.words(episode(DownloadState.Running), more = 1)!!.subText)
    }

    @Test
    fun `a release's attempt after the first says which of how many`() {
        assertEquals("Checking availability", DownloadHostRules.resolving("Checking availability", attempt = 1, of = 3))
        assertEquals("Trying release 2 of 3 · Checking availability", DownloadHostRules.resolving("Checking availability", attempt = 2, of = 3))
    }

    @Test
    fun `no notification names the release`() {
        DownloadState.entries.forEach { state ->
            val words = DownloadHostRules.words(episode(state, reason = null), bytesPerSecond = 1_000_000, more = 1) ?: return@forEach
            listOf(words.title, words.text, words.subText.orEmpty()).forEach { line ->
                listOf("KESTREL", "1080p", "Harbor.Lights", "v.mkv").forEach { assertFalse(it in line, "$state: \"$line\" holds \"$it\"") }
            }
        }
    }

    private fun text(row: Download, written: Long = HALF, bytesPerSecond: Long? = null): String =
        DownloadHostRules.words(row, written = written, bytesPerSecond = bytesPerSecond)!!.text

    /** Harbor Lights S02E04, 1.2 GB, half way: its release named as a release is, which no notification says. */
    private fun episode(state: DownloadState, reason: String? = null, gaps: Int = 0) = Download(
        id = "c0ffee00-0000-4000-8000-000000000001", watchKey = WatchKey.episode(900002, 2, 4), kind = DownloadKind.Episode,
        title = "Harbor Lights", subtitle = "S02E04 · Low Water", showTmdbId = 900002, season = 2, episode = 4, episodeName = "Low Water",
        state = state, reason = reason, position = 0, candidates = listOf(DownloadCandidate("idx", "g1", RELEASE, size = 1_200_000_000)),
        release = RememberedRelease("idx", "g1", RELEASE, fileIndex = 0, fileName = "$RELEASE.mkv", fileSize = 1_200_000_000),
        fileName = "$RELEASE.mkv", size = 1_200_000_000, indexerSize = 1_190_000_000, safeOffset = HALF,
        gaps = (0 until gaps).map { DownloadGap(it * 10_000_000L, it * 10_000_000L + 749_999) }, relativePath = "downloads/c0ffee00-0000-4000-8000-000000000001/v.mkv",
        createdAt = 1, updatedAt = 1,
    )

    private fun row(id: String, state: DownloadState, reason: String? = null) = Download(
        id = id, watchKey = WatchKey.episode(900002, 2, 4), kind = DownloadKind.Episode, title = "Harbor Lights", state = state, reason = reason,
        position = 0, createdAt = 1, updatedAt = 1,
    )

    private companion object {
        /** A job scheduled and waiting to run, not executing; one ahead of its walk. */
        val WAITING = DownloadHostRules.JobPending(waiting = true)
        val AHEAD = DownloadHostRules.JobPending(ahead = true)

        const val RELEASE = "Harbor.Lights.S02E04.1080p.WEB-DL.H.264-KESTREL"
        const val HALF = 500_000_000L
    }
}
