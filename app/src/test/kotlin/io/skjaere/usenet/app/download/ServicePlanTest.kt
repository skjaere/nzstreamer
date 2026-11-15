package io.skjaere.usenet.app.download

import android.content.Intent
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.app.download.ServicePlan.Need
import io.skjaere.usenet.app.download.ServicePlan.Step
import io.skjaere.usenet.app.download.ServicePlan.Wake
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The downloads' service as its rules decide it (item 10, L334-L336, L375, L388): where a start of it runs the queue, when
 * the visible app starts it, what it does as its needs come and go (walk, wait in place up to an hour, give the wait up,
 * stop), the boot receiver's rule by host, the watchdog's job on every API level and what its run does there, and the
 * Wi-Fi lock. Nothing of it on a TV. The show is invented.
 */
class ServicePlanTest {
    @Test
    fun `a start runs the queue where the service is the host, API 26 to 33, or any level with the override`() {
        (26..33).forEach { assertTrue(ServicePlan.runsHere(television = false, sdk = it, override = false), "API $it") }
        // The job's levels: a start there finds the job the host and stops at once.
        (34..36).forEach { assertFalse(ServicePlan.runsHere(television = false, sdk = it, override = false), "API $it") }
        // The debug override (L336): phone34 and tv36's levels run the service.
        listOf(26, 33, 34, 36).forEach { assertTrue(ServicePlan.runsHere(television = false, sdk = it, override = true), "API $it with the override") }
    }

    @Test
    fun `the visible app starts it with something to run, no walk under way and none standing, and for a changed switch whatever`() {
        fun starts(
            cause: ScheduleCause = ScheduleCause.AppStart, sdk: Int = 30, override: Boolean = false, visible: Boolean = true, runnable: Boolean = true,
            walking: Boolean = false, standing: Boolean = false,
        ) = ServicePlan.starts(cause, television = false, sdk = sdk, override = override, visible = visible, runnable = runnable, walking = walking, standing = standing)

        ScheduleCause.entries.forEach { assertTrue(starts(cause = it), "$it") }
        assertTrue(starts(sdk = 34, override = true), "API 34 with the override")
        // Android 14 on is the job's: the service isn't started there.
        assertFalse(starts(sdk = 34))
        // From the background, or with nothing to run, never (L335, L388).
        assertFalse(starts(visible = false))
        assertFalse(starts(runnable = false))
        // A walk under way takes up what is queued, and so does a service standing before its walk or waiting in place.
        assertFalse(starts(walking = true))
        assertFalse(starts(standing = true))
        // A changed Wi-Fi or charging switch starts it all the same: a service under way takes the new needs as it reads them.
        assertTrue(starts(cause = ScheduleCause.SwitchChanged, walking = true, standing = true))
        assertFalse(starts(cause = ScheduleCause.SwitchChanged, visible = false))
    }

    @Test
    fun `a download needs its network first, then the charger while charging is asked for`() {
        val wifiOnly = DownloadNeeds(unmetered = true, charging = false)
        assertEquals(Need.Met, ServicePlan.need(wifiOnly, network = true, charging = false))
        assertEquals(Need.Network("Waiting for Wi-Fi"), ServicePlan.need(wifiOnly, network = false, charging = true))
        val anyNetwork = DownloadNeeds(unmetered = false, charging = true)
        assertEquals(Need.Network("Waiting for a network"), ServicePlan.need(anyNetwork, network = false, charging = false))
        assertEquals(Need.Charger, ServicePlan.need(anyNetwork, network = true, charging = false))
        assertEquals(Need.Met, ServicePlan.need(anyNetwork, network = true, charging = true))
    }

    @Test
    fun `it walks while its need is met, waits in place up to an hour, gives the wait up after, and stops with nothing to run`() {
        val wifi = Need.Network("Waiting for Wi-Fi")
        assertEquals(Step.Walk, ServicePlan.step(runnable = true, need = Need.Met, waitedMillis = 0))
        // Waiting, for what is left of the hour, with its words on the row and the watchdog scheduled for the network.
        val waiting = ServicePlan.step(runnable = true, need = wifi, waitedMillis = 0)
        assertEquals(Step.Wait(wifi, HOUR), waiting)
        assertEquals("Waiting for Wi-Fi", (waiting as Step.Wait).words)
        assertTrue(waiting.watchdog)
        assertEquals(Step.Wait(wifi, 1), ServicePlan.step(runnable = true, need = wifi, waitedMillis = HOUR - 1))
        // The charger's wait: its own words, and no watchdog, whose run says "no connection".
        val charger = ServicePlan.step(runnable = true, need = Need.Charger, waitedMillis = 10 * MINUTE) as Step.Wait
        assertEquals(HOUR - 10 * MINUTE, charger.forMillis)
        assertEquals("Waiting to charge", charger.words)
        assertFalse(charger.watchdog)
        // The hour up: the service stops, saying "Paused: no connection · Tap to resume" after the network's wait alone.
        assertEquals(Step.GiveUp(tapToResume = true), ServicePlan.step(runnable = true, need = wifi, waitedMillis = HOUR))
        assertEquals(Step.GiveUp(tapToResume = false), ServicePlan.step(runnable = true, need = Need.Charger, waitedMillis = 2 * HOUR))
        // Its need back after a wait: on in place.
        assertEquals(Step.Walk, ServicePlan.step(runnable = true, need = Need.Met, waitedMillis = 2 * HOUR))
        // Nothing to run (done, the queue held, a wait that ends the host, L388): it stops, whatever its need.
        listOf(Need.Met, wifi, Need.Charger).forEach { assertEquals(Step.Stop, ServicePlan.step(runnable = false, need = it, waitedMillis = 0), "$it") }
        assertEquals(Step.Stop, ServicePlan.step(runnable = false, need = wifi, waitedMillis = 2 * HOUR))
    }

    @Test
    fun `the boot receiver is on while the host is the service and something can run, API 34 with the override reading as the service`() {
        val queued = listOf(row(DownloadState.Queued))
        (26..33).forEach { assertTrue(ServicePlan.receiverOn(television = false, sdk = it, override = false, rows = queued), "API $it") }
        assertFalse(ServicePlan.receiverOn(television = false, sdk = 34, override = false, rows = queued), "the job's host: JobScheduler keeps it")
        assertTrue(ServicePlan.receiverOn(television = false, sdk = 34, override = true, rows = queued), "API 34 with the override")
        // Nothing to run: done, or a held queue.
        assertFalse(ServicePlan.receiverOn(television = false, sdk = 30, override = false, rows = listOf(row(DownloadState.Done))))
        assertFalse(ServicePlan.receiverOn(television = false, sdk = 30, override = false, rows = queued + row(DownloadState.Paused)))
        assertFalse(ServicePlan.receiverOn(television = false, sdk = 30, override = false, rows = emptyList()))
    }

    @Test
    fun `boot and the app's update start the service where it is the host and something can run`() {
        listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED).forEach { action ->
            assertTrue(ServicePlan.bootStarts(action, television = false, sdk = 30, override = false, runnable = true), action)
            assertTrue(ServicePlan.bootStarts(action, television = false, sdk = 34, override = true, runnable = true), "$action, API 34 with the override")
            assertFalse(ServicePlan.bootStarts(action, television = false, sdk = 34, override = false, runnable = true), "$action, the job's host")
            assertFalse(ServicePlan.bootStarts(action, television = false, sdk = 30, override = false, runnable = false), "$action, nothing to run")
        }
        // Any other broadcast starts nothing.
        assertFalse(ServicePlan.bootStarts(Intent.ACTION_LOCKED_BOOT_COMPLETED, television = false, sdk = 30, override = false, runnable = true))
        assertFalse(ServicePlan.bootStarts(null, television = false, sdk = 30, override = false, runnable = true))
    }

    @Test
    fun `the watchdog is job 2 on the default scheduler on every level, regular, persisted, with the network need`() {
        (26..36).forEach { sdk ->
            val watchdog = ServicePlan.watchdog(sdk, DownloadNeeds(unmetered = true, charging = false))
            assertEquals(2, watchdog.id, "API $sdk")
            // No namespace: namespaces are Android 14's, and the downloads' own job is in its own one there.
            assertNull(watchdog.namespace, "API $sdk")
            assertFalse(watchdog.userInitiated, "API $sdk: a regular job")
            assertTrue(watchdog.persisted, "API $sdk")
            assertTrue(watchdog.unmetered, "API $sdk: Wi-Fi only")
        }
        assertFalse(ServicePlan.watchdog(30, DownloadNeeds(unmetered = false, charging = true)).unmetered, "any network with Wi-Fi only off")
        assertEquals(ServicePlan.WATCHDOG_ID, ServicePlan.watchdog(30, DownloadNeeds(unmetered = true, charging = false)).id)
    }

    @Test
    fun `the watchdog starts the service again on 26 to 30 and on 34 on with the override, and says tap to resume on 31 to 33`() {
        (26..30).forEach { assertEquals(Wake.StartService, ServicePlan.onWatchdog(television = false, sdk = it, override = false, up = false, runnable = true), "API $it") }
        // Android 12 and 13 refuse a foreground service started from a job: a notification's tap is a start they allow.
        (31..33).forEach { sdk ->
            listOf(false, true).forEach { override ->
                assertEquals(Wake.TapToResume, ServicePlan.onWatchdog(television = false, sdk = sdk, override = override, up = false, runnable = true), "API $sdk")
            }
        }
        (34..36).forEach { assertEquals(Wake.StartService, ServicePlan.onWatchdog(television = false, sdk = it, override = true, up = false, runnable = true), "API $it, override") }
        // The job's host has no watchdog to run.
        assertEquals(Wake.Nothing, ServicePlan.onWatchdog(television = false, sdk = 34, override = false, up = false, runnable = true))
        // The service still up (waiting in place, or walking) goes on by itself; nothing left to run needs nothing.
        assertEquals(Wake.Nothing, ServicePlan.onWatchdog(television = false, sdk = 30, override = false, up = true, runnable = true))
        assertEquals(Wake.Nothing, ServicePlan.onWatchdog(television = false, sdk = 32, override = false, up = true, runnable = true))
        assertEquals(Wake.Nothing, ServicePlan.onWatchdog(television = false, sdk = 30, override = false, up = false, runnable = false))
    }

    @Test
    fun `the Wi-Fi lock is held only while bytes flow on Wi-Fi`() {
        assertTrue(ServicePlan.wifiLock(writing = true, onWifi = true))
        assertFalse(ServicePlan.wifiLock(writing = true, onWifi = false))
        assertFalse(ServicePlan.wifiLock(writing = false, onWifi = true))
        assertFalse(ServicePlan.wifiLock(writing = false, onWifi = false))
    }

    @Test
    fun `nothing of the service on a TV`() {
        listOf(30, 34).forEach { sdk ->
            listOf(false, true).forEach { override ->
                assertFalse(ServicePlan.runsHere(television = true, sdk = sdk, override = override))
                assertFalse(ServicePlan.starts(ScheduleCause.Download, television = true, sdk = sdk, override = override, visible = true, runnable = true, walking = false, standing = false))
                assertFalse(ServicePlan.receiverOn(television = true, sdk = sdk, override = override, rows = listOf(row(DownloadState.Queued))))
                assertFalse(ServicePlan.bootStarts(Intent.ACTION_BOOT_COMPLETED, television = true, sdk = sdk, override = override, runnable = true))
                assertEquals(Wake.Nothing, ServicePlan.onWatchdog(television = true, sdk = sdk, override = override, up = false, runnable = true))
            }
        }
    }

    private fun row(state: DownloadState) = Download(
        id = "dl-${state.name.lowercase()}", watchKey = WatchKey.episode(SHOW, 2, 4), kind = DownloadKind.Episode, title = "Harbor Lights", showTmdbId = SHOW,
        season = 2, episode = 4, state = state, position = 0, indexerSize = 1_000_000_000, createdAt = 0, updatedAt = 0,
    )

    private companion object {
        const val SHOW = 900_782
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
