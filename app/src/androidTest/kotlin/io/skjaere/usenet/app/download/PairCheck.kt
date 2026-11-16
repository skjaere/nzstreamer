package io.skjaere.usenet.app.download

import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/**
 * The pairs' second half (L367, L389, L390, item 25): what a Check class reads of the step the pairs' runner took
 * between the phases, and asserts. Only what lasts: the trace, Room's rows and the files, never live state. The step's
 * evidence is the trace's lines from before this process started ([since]): the instrumentation's own start ended the
 * process before, and this one's start reconciles, and may run a job, after the step.
 *
 * Its end ([putBack]) leaves nothing of the pair running or kept: the downloads' jobs cancelled and the service
 * stopped, the rows and their files deleted, the season packs' copies with them, the debug seams' files (`.host`,
 * `.throttle`) removed. The trace stays, for the runner's logs; the settings stay as Start left them, as the runner
 * installs the app afresh for each pair.
 */
class PairCheck(private val fixture: PairFixture = PairFixture.HARBOR_LIGHTS) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val dao = container.data.downloads
    private val folder = File(context.filesDir, DownloadStorage.DIR)

    /** When this process started, as the trace times its lines: those before it are the step's evidence. */
    val since: Long = PairRecord.processStart()

    /** The trace's lines before [since], read back in the order the app wrote them. */
    private val evidence: List<PairRecord.Traced> by lazy { traced().filter { it.at < since } }

    /**
     * Room's [count] rows, in the queue's order, as the step left them (this process's start changes no row Done,
     * Stopped or Queued); and the trace as a whole before them: every line of a shape this reads, and the app's open by
     * the runner seen scheduling its host (L391) before any byte came, so what follows is the host the app's open asked
     * for. And no debrid account or torrent indexer saved by the app's processes between the phases (L182).
     */
    suspend fun rows(count: Int): List<Download> {
        val unread = PairRecord.traceLines(context).filter { PairRecord.parse(it) == null }.map { "${it.substringBefore(' ')} (${it.length} chars)" }
        assertEquals("every trace line of a shape the pairs read (DownloadTrace's)", emptyList<String>(), unread)
        val firstByte = evidence.firstOrNull { it.event == "progress" } ?: throw AssertionError("no transfer under way in the trace")
        val opened = evidence.filter { it.event == "schedule" && it.at < firstByte.at && it["cause"] == "AppStart" }
        assertTrue(
            "the app's open scheduled its host before the transfer: ${opened.map { it.fields }}",
            opened.any { it["result"] == DownloadTrace.ScheduleResult.Scheduled.word || it["result"] == DownloadTrace.ScheduleResult.Service.word },
        )
        // The app's own processes between the phases (the runner's open, JobScheduler's runs) saved none of the debug
        // build's debrid accounts or torrent indexers (L182): counted, never named.
        val saved = container.settings.settings.first()
        assertEquals(
            "debrid accounts and torrent indexers saved between the phases", 0 to 0, saved.activeServices.size to saved.activeTorrentIndexers.size,
        )
        val rows = dao.all().first()
        assertEquals("the downloads Start seeded", count, rows.size)
        return rows
    }

    /**
     * The step's reconcile line for download [id] (L389): the first the trace has for it, a process's start after the
     * step judging the row the step left under way. The row was Queued before the app's open, so no earlier start
     * judged it; its exit came after the transfer was under way, so it is the step's.
     */
    fun reconciled(id: String): PairRecord.Traced {
        val line = evidence.firstOrNull { it.event == "reconcile" && it["id"] == id } ?: throw AssertionError("no reconcile line for $id before Check's own process")
        val firstByte = evidence.firstOrNull { it.event == "progress" && it["id"] == id } ?: throw AssertionError("$id never under way before Check's own process")
        assertTrue("the reconcile line names its exit's time: ${line.fields}", "exit-at" in line.fields)
        assertTrue("the exit it names came after the transfer was under way: ${line.fields}", exitAt(line) > firstByte.at)
        return line
    }

    /** When the exit [reconciled] names came: the step's own time. */
    fun exitAt(reconciled: PairRecord.Traced): Long = reconciled["exit-at"].toLong()

    /** Download [id]'s safe offset as last recorded before [at] (its progress line's), past a quarter of the file. */
    fun recorded(id: String, at: Long): Long {
        val offset = evidence.lastOrNull { it.event == "progress" && it["id"] == id && it.at < at }?.get("offset")?.toLong()
            ?: throw AssertionError("no progress recorded for $id before $at")
        assertTrue("the transfer was past a quarter at $offset", offset * 4 >= fixture.size)
        return offset
    }

    /** The hosts' starts at [from] or later, before this process. */
    fun starts(from: Long): List<PairRecord.Traced> = evidence.filter { it.event == "start" && it.at >= from }

    /**
     * The app's own schedules of its host (L391) at [from] or later, before this process: the visible app's alone, so
     * none means the app wasn't opened again (its open's line may come just after a host's start, as a job runs once
     * the process is up, before its activity).
     */
    fun schedules(from: Long): List<PairRecord.Traced> = evidence.filter { it.event == "schedule" && it.at >= from }

    /** The reopens of download [id], or of any, at [from] or later, before this process. */
    fun reopens(from: Long, id: String? = null): List<PairRecord.Traced> =
        evidence.filter { it.event == "reopen" && it.at >= from && (id == null || it["id"] == id) }

    /** Every line naming download [id] before this process. */
    fun linesOf(id: String): List<PairRecord.Traced> = evidence.filter { it.fields["id"] == id }

    /** Download [id]'s done line at [from] or later, before this process; fails without one. */
    fun done(id: String, from: Long): PairRecord.Traced =
        evidence.firstOrNull { it.event == "done" && it["id"] == id && it.at >= from } ?: throw AssertionError("$id not done after $from, before Check's own process")

    /** The reopens of download [id] this process wrote: after the Resume it took. */
    fun ownReopens(id: String): List<PairRecord.Traced> = traced().filter { it.at >= since && it.event == "reopen" && it["id"] == id }

    /** Every one of [ids] Done in Room, its whole file there: [fixture]'s size and SHA-256, the pair's own by default. */
    suspend fun assertWhole(ids: List<String>, fixture: PairFixture = this.fixture) {
        val storage = container.downloadStorage
        ids.forEach { id ->
            val row = dao.get(id) ?: throw AssertionError("$id is gone")
            assertEquals("$id done", DownloadState.Done, row.state)
            assertEquals("$id's safe offset at its end", fixture.size, row.safeOffset)
            assertEquals("$id's gaps", 0, row.gaps.size)
            val file = storage.file(row) ?: throw AssertionError("$id names no file")
            assertEquals("$id's file's size", fixture.size, file.length())
            assertEquals("$id's file's SHA-256", fixture.sha256, DownloadFixtures.sha256(file))
        }
    }

    /**
     * What a held pair's step left (StopApp, ForceStop: L359, L389): the first row "Stopped by Android" from
     * reconcile's line after the step, with the user's exit (REASON_USER_REQUESTED), its file kept as far as it went;
     * no reopen after the step; and the queue held, the second row never started.
     */
    suspend fun held(): Held {
        val (first, second) = rows(2)
        val reconciled = reconciled(first.id)
        assertEquals("reconcile held the first: ${reconciled.fields}", DownloadTrace.Decision.Stopped.word, reconciled["decision"])
        assertEquals("by the user's exit", "${DownloadExit.USER_REQUESTED}", reconciled["exit"])
        val stopped = exitAt(reconciled)
        assertTrue("reconcile's line after the step's exit", reconciled.at >= stopped)
        assertEquals("no reopen after the step", emptyList<PairRecord.Traced>(), reopens(stopped))
        assertEquals("the first Stopped", DownloadState.Stopped, first.state)
        assertEquals(DownloadHostRules.STOPPED_BY_ANDROID, first.reason)
        assertTrue("the first kept where it was, past a quarter: ${first.safeOffset}", first.safeOffset * 4 >= fixture.size && first.safeOffset < fixture.size)
        assertEquals("the second waits Queued", DownloadState.Queued, second.state)
        assertEquals("the second never started: ${linesOf(second.id)}", emptyList<PairRecord.Traced>(), linesOf(second.id))
        assertTrue("the second wrote nothing", second.safeOffset == 0L && second.relativePath == null)
        assertEquals("nothing can run while the first holds the queue", emptyList<Download>(), container.downloads.runnable())
        return Held(first, second, stopped)
    }

    /** A held pair's rows ([first] Stopped, [second] Queued) and when the step stopped them ([at]). */
    data class Held(val first: Download, val second: Download, val at: Long)

    /**
     * Lets the held queue go as a user does (L380, C4): the notification's Resume, the app's own intent with its token,
     * opens the app in front, which resumes there and schedules the host from the visible app; then every one of [ids]
     * goes on to done, in this process. The writer's throttle goes first: what is checked is that they go on, not how
     * fast.
     */
    suspend fun resume(ids: List<String>) {
        File(folder, DownloadDebug.THROTTLE).delete()
        context.startActivity(MainActivity.openDownloads(context, resume = true))
        val opened = waitFor("the app opened by Resume") { resumedMain() }
        try {
            waitFor("every download done after Resume", DONE_MILLIS) {
                val rows = runBlocking { dao.all().first() }
                rows.takeIf { ids.all { id -> rows.any { it.id == id && it.state == DownloadState.Done } } }
            }
        } finally {
            instrumentation.runOnMainSync { opened.finish() }
        }
    }

    /** Says [what] the platform did, beside the phase's result (the record says which, L389). */
    fun outcome(what: String) = PairRecord.say("outcome $what")

    /** Puts back what the pair left, whatever the Check got to (the class's KDoc). */
    suspend fun putBack() {
        val failures = mutableListOf<Throwable>()
        suspend fun back(what: suspend () -> Unit) {
            try {
                what()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures += e
            }
        }
        back { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) DownloadJobService.scheduler(context).cancelAll() }
        back { context.getSystemService(JobScheduler::class.java).cancel(ServicePlan.WATCHDOG_ID) }
        back { context.stopService(Intent(context, DownloadService::class.java)) }
        back {
            waitFor("no host under way", HOST_GONE_MILLIS) {
                val job = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && DownloadJobService.starts.value > 0
                if (!job && DownloadService.phase.value == DownloadService.Phase.None && !container.downloads.walking.value) Unit else null
            }
        }
        back { dao.all().first().forEach { container.downloadStorage.delete(it.id) } }
        back { File(context.filesDir, DownloadNzbs.PATH).deleteRecursively() }
        back {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.cancel(DownloadNotifications.ONGOING_ID)
            manager.cancel(DownloadNotifications.STATUS_ID)
        }
        back { listOf(DownloadDebug.THROTTLE, DownloadDebug.HOST).forEach { File(folder, it).delete() } }
        failures.firstOrNull()?.let { first -> failures.drop(1).forEach(first::addSuppressed); throw first }
    }

    /** The trace as it is now, read back. */
    private fun traced(): List<PairRecord.Traced> = PairRecord.traceLines(context).mapNotNull(PairRecord::parse)

    private fun resumedMain(): MainActivity? {
        var found: MainActivity? = null
        instrumentation.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
        }
        return found
    }

    private companion object {
        /** Both 48 MB files after Resume, unthrottled, from the mock. */
        const val DONE_MILLIS = 180_000L

        const val HOST_GONE_MILLIS = 30_000L
    }
}
