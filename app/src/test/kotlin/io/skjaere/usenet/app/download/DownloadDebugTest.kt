package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.app.download.DownloadTrace.Decision
import io.skjaere.usenet.app.download.DownloadTrace.ScheduleResult
import io.skjaere.usenet.app.download.DownloadTrace.StartCause
import io.skjaere.usenet.data.Clock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The debug build's seams for the downloads' device tests (L336, L367): the host override (`.host`), the writer's throttle
 * (`.throttle`) and the trace the runner and the hosts append to (`.trace`), in filesDir's `downloads/`. A release, bench
 * or beta build reads and writes none of them; the debug flag is passed in, so a test stands for either. The trace's lines
 * name ids, offsets and causes, never a title, a release or a host.
 */
class DownloadDebugTest {
    @TempDir
    lateinit var filesDir: File

    private val folder get() = File(filesDir, "downloads")
    private val clock = Clock { NOW }

    @Test
    fun `a release build reads and writes none of the three`() {
        folder.mkdirs()
        File(folder, ".host").writeText("service")
        File(folder, ".throttle").writeText("1000000")
        File(folder, ".trace").writeText("1763164700000 start host=job cause=job\n")
        val release = DownloadDebug.of(filesDir, debug = false, clock = clock)
        assertFalse(release.serviceHost)
        assertNull(release.throttle)
        release.trace(DownloadTrace.HostStart(DownloadHost.Service, StartCause.BootReceiver))
        release.trace(DownloadTrace.Completed(ID, 48_000_000))
        assertEquals("1763164700000 start host=job cause=job\n", File(folder, ".trace").readText())
    }

    @Test
    fun `a release build makes no folder or file of its own`() {
        val release = DownloadDebug.of(filesDir, debug = false, clock = clock)
        release.trace(DownloadTrace.Reopen(ID, 1_048_576))
        assertEquals(emptyList(), filesDir.listFiles().orEmpty().toList())
    }

    @Test
    fun `a debug build takes the service host while the override's file is there, whatever it holds`() {
        val debug = DownloadDebug.of(filesDir, debug = true, clock = clock)
        assertFalse(debug.serviceHost)
        folder.mkdirs()
        File(folder, ".host").writeText("")
        assertTrue(debug.serviceHost)
        File(folder, ".host").delete()
        assertFalse(debug.serviceHost)
    }

    @Test
    fun `a debug build's throttle is the file's bytes a second, and none for anything else`() {
        val debug = DownloadDebug.of(filesDir, debug = true, clock = clock)
        assertNull(debug.throttle)
        folder.mkdirs()
        File(folder, ".throttle").writeText("1000000\n")
        assertEquals(1_000_000, debug.throttle)
        listOf("0", "-1", "fast", "", "1.5").forEach { written ->
            File(folder, ".throttle").writeText(written)
            assertNull(debug.throttle, "\"$written\"")
        }
    }

    @Test
    fun `the trace's lines say each host start and its cause, reconcile's decision, each reopen and completion, each at its time`() {
        val debug = DownloadDebug.of(filesDir, debug = true, clock = clock)
        debug.trace(DownloadTrace.HostStart(DownloadHost.Job, StartCause.Job))
        debug.trace(DownloadTrace.HostStart(DownloadHost.Service, StartCause.BootReceiver))
        debug.trace(DownloadTrace.HostStart(DownloadHost.Service, StartCause.StickyRestart))
        debug.trace(DownloadTrace.HostStart(DownloadHost.Service, StartCause.Watchdog))
        debug.trace(DownloadTrace.HostStart(DownloadHost.Service, StartCause.Activity))
        debug.trace(DownloadTrace.Reconciled(ID, Decision.Stopped, exitReason = 10, exitAt = 1_763_164_790_000))
        debug.trace(DownloadTrace.Reconciled(ID, Decision.LeftForHost, exitReason = null, exitAt = null))
        debug.trace(DownloadTrace.Progress(ID, offset = 12_000_000, size = 48_000_000))
        debug.trace(DownloadTrace.Reopen(ID, offset = 12_582_912))
        debug.trace(DownloadTrace.Completed(ID, size = 48_000_000))
        val lines = File(folder, ".trace").readLines()
        assertEquals(
            listOf(
                "1763164800000 start host=job cause=job",
                "1763164800000 start host=service cause=boot",
                "1763164800000 start host=service cause=sticky",
                "1763164800000 start host=service cause=watchdog",
                "1763164800000 start host=service cause=activity",
                "1763164800000 reconcile id=$ID decision=stopped exit=10 exit-at=1763164790000",
                "1763164800000 reconcile id=$ID decision=left exit=none",
                "1763164800000 progress id=$ID offset=12000000 size=48000000",
                "1763164800000 reopen id=$ID offset=12582912",
                "1763164800000 done id=$ID size=48000000",
            ),
            lines,
        )
        // Ids, numbers and words of the trace's own: nothing a title, a release or a host could be in.
        lines.forEach { assertTrue(LINE.matches(it), it) }
    }

    @Test
    fun `the app's own schedules are traced by the cause's name and how each went`() {
        val debug = DownloadDebug.of(filesDir, debug = true, clock = clock)
        ScheduleCause.entries.forEach { debug.trace(DownloadTrace.Schedule(it, ScheduleResult.Scheduled)) }
        ScheduleResult.entries.forEach { debug.trace(DownloadTrace.Schedule(ScheduleCause.AppStart, it)) }
        val lines = File(folder, ".trace").readLines()
        assertEquals(
            listOf(
                "1763164800000 schedule cause=Download result=scheduled",
                "1763164800000 schedule cause=Resume result=scheduled",
                "1763164800000 schedule cause=ProviderSaved result=scheduled",
                "1763164800000 schedule cause=SwitchChanged result=scheduled",
                "1763164800000 schedule cause=AppStart result=scheduled",
                "1763164800000 schedule cause=AppStart result=scheduled",
                "1763164800000 schedule cause=AppStart result=skipped",
                "1763164800000 schedule cause=AppStart result=service",
                "1763164800000 schedule cause=AppStart result=refused",
            ),
            lines,
        )
        lines.forEach { assertTrue(LINE.matches(it), it) }
    }

    @Test
    fun `progress is traced at each whole percent, not at every record`() {
        val debug = DownloadDebug.of(filesDir, debug = true, clock = clock)
        listOf(0L, 5, 10, 19, 250, 251, 1_000).forEach { debug.trace(DownloadTrace.Progress(ID, offset = it, size = 1_000)) }
        // Another download's own percents.
        debug.trace(DownloadTrace.Progress(OTHER, offset = 5, size = 1_000))
        assertEquals(
            listOf("offset=0", "offset=10", "offset=250", "offset=1000", "id=$OTHER offset=5"),
            File(folder, ".trace").readLines().map { it.substringAfter("progress ").removePrefix("id=$ID ").substringBefore(" size") },
        )
    }

    @Test
    fun `the trace starts again once past its cap, so a debug build's never grows unbounded`() {
        val debug = DownloadDebug.On(folder, clock, cap = 200)
        repeat(10) { debug.trace(DownloadTrace.Reopen(ID, offset = it.toLong())) }
        val trace = File(folder, ".trace")
        assertTrue(trace.length() <= 200 + 80, "${trace.length()} bytes")
        assertEquals("1763164800000 reopen id=$ID offset=9", trace.readLines().last())
    }

    private companion object {
        const val NOW = 1_763_164_800_000
        const val ID = "c0ffee00-0000-4000-8000-000000000001"
        const val OTHER = "c0ffee00-0000-4000-8000-000000000002"
        val LINE = Regex("""\d+ [a-z]+( [a-z-]+=[A-Za-z0-9._~-]+)*""")
    }
}
