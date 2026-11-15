package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.data.Clock
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.WatchKey
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app's own schedule of the downloads' host (L335, L388, L391), over fakes of the runner, the service and the job: each
 * ask traced in the debug build as "schedule cause=<the cause's name> result=<how it went>", so a two-phase device test
 * tells the app's schedule from JobScheduler's run of a job that was waiting, or Android's start of the service again.
 * Every path's result: the job scheduled, the service started, skipped (from the background, nothing to run, a host under
 * way or waiting), and refused (Android's refusal, or the ask failing).
 */
class DownloadHostsTest {
    @TempDir
    lateinit var filesDir: File

    private val debug by lazy { DownloadDebug.of(filesDir, debug = true, clock = Clock { NOW }) }

    // The device and the app as each case has them.
    private var visible = true
    private var rows = listOf(row())
    private var walking = false
    private var standing = false
    private var pending = false
    private var jobTaken = true
    private var serviceRefused = false

    /** What was asked of Android, in turn: the service's start and the job's schedule, each with its cause. */
    private val asked = mutableListOf<String>()

    private fun hosts(
        sdk: Int = 34,
        television: Boolean = false,
        debug: DownloadDebug = this.debug,
        runnable: suspend () -> List<Download> = { rows },
    ) = DownloadHosts(
        DownloadHostsDeps(
            television = television, sdk = sdk, debug = debug, runnable = runnable, walking = { walking }, visible = { visible },
            standing = { standing },
            startService = {
                asked += "service"
                if (serviceRefused) throw IllegalStateException("Not allowed to start service Intent: app is in background")
            },
            pending = { pending },
            scheduleJob = { cause, _ ->
                asked += "job ${cause.name}"
                jobTaken
            },
        ),
    )

    private fun traced(): List<String> = File(filesDir, "downloads/${DownloadDebug.TRACE}").takeIf { it.isFile }?.readLines().orEmpty()

    @Test
    fun `the job scheduled from the visible app is traced as scheduled, by its cause's name`() = runBlocking<Unit> {
        assertTrue(hosts().schedule(ScheduleCause.AppStart))
        assertTrue(hosts().schedule(ScheduleCause.Download))
        assertEquals(listOf("job AppStart", "job Download"), asked)
        assertEquals(listOf("$NOW schedule cause=AppStart result=scheduled", "$NOW schedule cause=Download result=scheduled"), traced())
    }

    @Test
    fun `an ask the rules hold back is traced as skipped, and nothing is asked of Android`() = runBlocking<Unit> {
        val hosts = hosts()
        // From the background.
        visible = false
        assertFalse(hosts.schedule(ScheduleCause.ProviderSaved))
        visible = true
        // Nothing to run.
        rows = emptyList()
        assertFalse(hosts.schedule(ScheduleCause.AppStart))
        rows = listOf(row())
        // A walk under way will take it up; a job waiting to run will too.
        walking = true
        assertFalse(hosts.schedule(ScheduleCause.Resume))
        walking = false
        pending = true
        assertFalse(hosts.schedule(ScheduleCause.AppStart))
        assertEquals(emptyList(), asked)
        assertEquals(
            listOf("ProviderSaved", "AppStart", "Resume", "AppStart").map { "$NOW schedule cause=$it result=skipped" },
            traced(),
        )
        // A changed switch schedules all the same, the waiting job taking the new needs (L335).
        assertTrue(hosts.schedule(ScheduleCause.SwitchChanged))
        assertEquals("$NOW schedule cause=SwitchChanged result=scheduled", traced().last())
    }

    @Test
    fun `a job Android doesn't take is traced as refused`() = runBlocking<Unit> {
        jobTaken = false
        assertFalse(hosts().schedule(ScheduleCause.Resume))
        assertEquals(listOf("job Resume"), asked)
        assertEquals(listOf("$NOW schedule cause=Resume result=refused"), traced())
    }

    @Test
    fun `the service started below Android 14, or with the override, is traced as service`() = runBlocking<Unit> {
        assertTrue(hosts(sdk = 33).schedule(ScheduleCause.AppStart))
        // Android 14 with the debug build's override (L336).
        File(filesDir, "downloads").mkdirs()
        File(filesDir, "downloads/${DownloadDebug.HOST}").writeText("service")
        assertTrue(hosts(sdk = 34).schedule(ScheduleCause.Download))
        assertEquals(listOf("service", "service"), asked)
        assertEquals(listOf("$NOW schedule cause=AppStart result=service", "$NOW schedule cause=Download result=service"), traced())
    }

    @Test
    fun `the service held back is skipped, and refused from the background by Android is refused`() = runBlocking<Unit> {
        // One that stands ready, started and not yet walking, will take it up.
        standing = true
        assertFalse(hosts(sdk = 30).schedule(ScheduleCause.AppStart))
        assertEquals(emptyList(), asked)
        standing = false
        // Android 12 on refuses a foreground service to an app that has just left the front.
        serviceRefused = true
        assertFalse(hosts(sdk = 31).schedule(ScheduleCause.Resume))
        assertEquals(listOf("service"), asked)
        assertEquals(listOf("$NOW schedule cause=AppStart result=skipped", "$NOW schedule cause=Resume result=refused"), traced())
    }

    @Test
    fun `an ask that fails is traced as refused`() = runBlocking<Unit> {
        assertFalse(hosts(runnable = { throw IOException("disk I/O error") }).schedule(ScheduleCause.AppStart))
        assertEquals(emptyList(), asked)
        assertEquals(listOf("$NOW schedule cause=AppStart result=refused"), traced())
    }

    @Test
    fun `a TV schedules nothing and traces nothing, and a release build traces nothing`() = runBlocking<Unit> {
        assertFalse(hosts(television = true).schedule(ScheduleCause.AppStart))
        assertTrue(hosts(debug = DownloadDebug.Off).schedule(ScheduleCause.AppStart))
        assertEquals(listOf("job AppStart"), asked)
        assertEquals(emptyList(), traced())
    }

    /** A download that can run: Harbor Lights' episode, queued. */
    private fun row() = Download(
        id = ID, watchKey = WatchKey.episode(900002, 2, 4), kind = DownloadKind.Episode, title = "Harbor Lights", position = 0, createdAt = 1,
        updatedAt = 1,
    )

    private companion object {
        const val NOW = 1_763_164_800_000
        const val ID = "c0ffee00-0000-4000-8000-000000000001"
    }
}
