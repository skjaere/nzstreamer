package io.skjaere.usenet.app.download

import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The downloads' job as a plain plan (item 9, L335, L376, C5): one user-initiated job for the whole queue, in its own
 * namespace, persisted, needing the network and the charger as the settings say, with no storage constraint, and the
 * bytes it expects and has fetched, counted without a work item. The show is invented.
 */
class JobPlanTest {
    @Test
    fun `a new install's job is user-initiated, persisted, on Wi-Fi or Ethernet, at any charge, with no storage constraint`() {
        val plan = JobPlan.of(DownloadPrefs(), listOf(row("q", DownloadState.Queued, indexerSize = GB)))
        assertEquals("downloads", plan.namespace)
        assertEquals(1, plan.id)
        assertTrue(plan.userInitiated)
        assertTrue(plan.persisted, "a reboot keeps it")
        assertTrue(plan.unmetered, "Only on Wi-Fi or Ethernet is on by default (W110)")
        assertFalse(plan.charging, "Only while charging is off by default (L365)")
        assertFalse(plan.storageNotLow, "the app's own reserve decides (L376)")
        assertEquals(GB, plan.downloadBytes)
        assertEquals(0, plan.uploadBytes)
    }

    @Test
    fun `the network and the charger as the switches say`() {
        val rows = listOf(row("q", DownloadState.Queued, indexerSize = GB))
        val anyNetwork = JobPlan.of(DownloadPrefs(unmeteredOnly = false, whileCharging = true), rows)
        assertFalse(anyNetwork.unmetered)
        assertTrue(anyNetwork.charging)
        // The rest stays as it is, whatever the switches.
        assertEquals(JobPlan.of(DownloadPrefs(), rows).copy(unmetered = false, charging = true), anyNetwork)
    }

    @Test
    fun `its estimate is what is left to fetch of the downloads that can run`() {
        val rows = listOf(
            // Not resolved yet: the indexer's size.
            row("queued", DownloadState.Queued, indexerSize = 1_200_000_000),
            // Resolved: its exact size, less what is written.
            row("running", DownloadState.Running, size = 2_000_000_000, indexerSize = 2_100_000_000, safeOffset = 500_000_000),
            row("wifi", DownloadState.Waiting, size = GB, safeOffset = 250_000_000, reason = DownloadHostRules.WAITING_FOR_WIFI),
            // None of these is the job's: finished, failed, or waiting for what ends the host (L388).
            row("done", DownloadState.Done, size = GB, safeOffset = GB),
            row("failed", DownloadState.Failed, size = GB, safeOffset = 100),
            row("provider", DownloadState.Waiting, size = GB, reason = DownloadHostRules.CHECK_PROVIDER),
        )
        assertEquals(1_200_000_000L + 1_500_000_000L + 750_000_000L, JobPlan.of(DownloadPrefs(), rows).downloadBytes)
        // As the job goes: the live write's bytes, not the record's.
        val items = rows.map { DownloadRunner.Item(it, if (it.id == "running") 1_500_000_000 else it.safeOffset, null) }
        assertEquals(1_200_000_000L + 500_000_000L + 750_000_000L, JobPlan.remaining(items))
    }

    @Test
    fun `a held queue has nothing to fetch, and sizes not known yet say so`() {
        val held = listOf(row("paused", DownloadState.Paused, size = GB, safeOffset = 10), row("queued", DownloadState.Queued, indexerSize = GB))
        assertEquals(0, JobPlan.of(DownloadPrefs(), held).downloadBytes)
        // An indexer that gave no size: Android's "unknown", not 0 bytes.
        val unknown = listOf(row("queued", DownloadState.Queued, indexerSize = 0))
        assertEquals(JobPlan.UNKNOWN_BYTES, JobPlan.of(DownloadPrefs(), unknown).downloadBytes)
    }

    @Test
    fun `what the job fetched counts what was written since it started, and its estimate adds what is left`() {
        val bytes = JobBytes()
        val running = row("running", DownloadState.Running, size = GB, safeOffset = 400)
        val queued = row("queued", DownloadState.Queued, indexerSize = 2 * GB)
        // What was written before the job started isn't the job's.
        bytes.seen(listOf(item(running, 400), item(queued, 0)))
        assertEquals(0, bytes.transferred)
        assertEquals(GB - 400 + 2 * GB, bytes.estimated)

        bytes.seen(listOf(item(running, 1_400), item(queued, 0)))
        assertEquals(1_000, bytes.transferred)
        // What it fetched and what is left: the same estimate while the bytes only move from one to the other.
        assertEquals(1_000 + GB - 1_400 + 2 * GB, bytes.estimated)
        assertEquals(GB - 400 + 2 * GB, bytes.estimated)

        // A release given up starts its file again from 0: what it fetches then counts too, never less than before.
        bytes.seen(listOf(item(running, 0), item(queued, 0)))
        bytes.seen(listOf(item(running, 40), item(queued, 0)))
        assertEquals(1_040, bytes.transferred)

        // A download queued while the job runs counts from its first byte; one gone counts what it had.
        val added = row("added", DownloadState.Queued, indexerSize = 500)
        bytes.seen(listOf(item(queued, 0), item(added, 0)))
        bytes.seen(listOf(item(queued, 0), item(added.copy(state = DownloadState.Running), 300)))
        assertEquals(1_340, bytes.transferred)
        assertEquals(1_340 + 2 * GB + 200, bytes.estimated)
    }

    @Test
    fun `an estimate with no size known is Android's unknown`() {
        val bytes = JobBytes()
        bytes.seen(listOf(item(row("queued", DownloadState.Queued, indexerSize = 0), 0)))
        assertEquals(JobPlan.UNKNOWN_BYTES, bytes.estimated)
    }

    private fun item(row: Download, written: Long) = DownloadRunner.Item(row, written, null)

    private fun row(id: String, state: DownloadState, size: Long? = null, indexerSize: Long = 0, safeOffset: Long = 0, reason: String? = null) = Download(
        id = id, watchKey = WatchKey.episode(SHOW, 2, 4), kind = DownloadKind.Episode, title = "Harbor Lights", showTmdbId = SHOW, season = 2, episode = 4,
        state = state, reason = reason, position = 0, size = size, indexerSize = indexerSize, safeOffset = safeOffset, createdAt = 0, updatedAt = 0,
    )

    private companion object {
        const val SHOW = 900_781
        const val GB = 1_000_000_000L
    }
}
