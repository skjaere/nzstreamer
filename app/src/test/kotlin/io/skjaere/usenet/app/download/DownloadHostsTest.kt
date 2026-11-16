package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.data.Clock
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.WatchKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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

    /** What holds the job from running now, as its constraints judge the device; and what the next download was said to wait for. */
    private var need: ServicePlan.Need = ServicePlan.Need.Met
    private val said = mutableListOf<String>()

    /** What happens once the ask has read whether a walk is under way or the job waits, after the first of the two reads. */
    private var afterRead: (() -> Unit)? = null

    private fun read() {
        afterRead?.also { afterRead = null }?.invoke()
    }

    private fun hosts(
        sdk: Int = 34,
        television: Boolean = false,
        debug: DownloadDebug = this.debug,
        runnable: suspend () -> List<Download> = { rows },
        scheduleJob: suspend (ScheduleCause) -> Boolean = { jobTaken },
    ) = DownloadHosts(
        DownloadHostsDeps(
            television = television, sdk = sdk, debug = debug, runnable = runnable, walking = { walking.also { read() } }, visible = { visible },
            standing = { standing },
            startService = {
                asked += "service"
                if (serviceRefused) throw IllegalStateException("Not allowed to start service Intent: app is in background")
            },
            pending = { pending.also { read() } },
            scheduleJob = { cause, _ ->
                asked += "job ${cause.name}"
                scheduleJob(cause)
            },
            need = { need },
            waits = { words -> said += words },
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

    /**
     * A job Android has started whose walk hasn't taken the queue up yet reads as waiting to run (DownloadJobService's
     * pending): a schedule now would stop it as it starts (C5). The walk can take the queue up between the ask's two reads,
     * walking from then on and no longer waiting: read the other way round, the ask would find neither and schedule.
     */
    @Test
    fun `a job started but not yet at the queue holds the ask back, its walk taking the queue up as the ask reads`() = runBlocking<Unit> {
        pending = true
        walking = false
        afterRead = {
            walking = true
            pending = false
        }
        assertFalse(hosts().schedule(ScheduleCause.AppStart))
        assertEquals(emptyList(), asked)
        assertEquals(listOf("$NOW schedule cause=AppStart result=skipped"), traced())
    }

    /** The app's start and the notification's Resume as the app comes to the front: the second finds the first's job waiting. */
    @Test
    fun `two asks at once go one at a time, so the job is scheduled once`() = runBlocking<Unit> {
        val scheduling = CompletableDeferred<Unit>()
        val taken = CompletableDeferred<Unit>()
        val hosts = hosts(
            scheduleJob = {
                scheduling.complete(Unit)
                taken.await()
                // Android has it now: waiting to run, or started and not yet at the queue.
                pending = true
                true
            },
        )
        val first = async { hosts.schedule(ScheduleCause.Resume) }
        scheduling.await()
        val second = async { hosts.schedule(ScheduleCause.AppStart) }
        repeat(3) { yield() }
        taken.complete(Unit)
        assertTrue(first.await())
        assertFalse(second.await())
        assertEquals(listOf("job Resume"), asked)
        assertEquals(listOf("$NOW schedule cause=Resume result=scheduled", "$NOW schedule cause=AppStart result=skipped"), traced())
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

    /**
     * Android 14 on, the job waits under its constraints, never started, where the service would wait in place: the download
     * it would run next says what for, as the service's does (DlPhoneMovie's "Queued for download" over "Waiting for Wi-Fi").
     */
    @Test
    fun `a job that waits for its network or the charger says so on the download it would run next`() = runBlocking<Unit> {
        need = ServicePlan.Need.Network(DownloadHostRules.WAITING_FOR_WIFI)
        assertTrue(hosts().schedule(ScheduleCause.Download))
        assertEquals(listOf(DownloadHostRules.WAITING_FOR_WIFI), said)
        // A job already waiting: still said, of whichever download is next now.
        pending = true
        need = ServicePlan.Need.Charger
        assertFalse(hosts().schedule(ScheduleCause.AppStart))
        assertEquals(listOf(DownloadHostRules.WAITING_FOR_WIFI, DownloadHostRules.WAITING_TO_CHARGE), said)
        pending = false
        // Its needs met: it runs, and says nothing.
        need = ServicePlan.Need.Met
        assertTrue(hosts().schedule(ScheduleCause.Resume))
        // A walk under way, from the background, nothing to run, a job Android refused: nothing said.
        need = ServicePlan.Need.Network(DownloadHostRules.WAITING_FOR_NETWORK)
        walking = true
        hosts().schedule(ScheduleCause.Resume)
        walking = false
        visible = false
        hosts().schedule(ScheduleCause.Resume)
        visible = true
        rows = emptyList()
        hosts().schedule(ScheduleCause.Resume)
        rows = listOf(row())
        jobTaken = false
        hosts().schedule(ScheduleCause.Resume)
        jobTaken = true
        // The service waits in place and says it itself (item 10).
        hosts(sdk = 33).schedule(ScheduleCause.Resume)
        assertEquals(listOf(DownloadHostRules.WAITING_FOR_WIFI, DownloadHostRules.WAITING_TO_CHARGE), said)
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
