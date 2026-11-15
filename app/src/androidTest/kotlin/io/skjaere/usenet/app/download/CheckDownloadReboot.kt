package io.skjaere.usenet.app.download

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The second half of the DownloadReboot pair (L359, L367): after [StartDownloadReboot], the app's open and the runner's
 * reboot of the emulator as the first of two downloads ran, the persisted user-initiated job ran again after the boot
 * with no activity started (the app scheduled nothing: L391's lines), and both went on to done, their files whole. The
 * boot's time is this process's reading of it.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CheckDownloadReboot {
    /** Only where its run names it, on a phone ([PairRun]). */
    @get:Rule
    val pair = PairRun()

    private val check = PairCheck()

    @After
    fun putBack() = runBlocking<Unit> { check.putBack() }

    @Test
    fun theJobRunsAgainAfterTheBootWithNoActivityAndBothAreDone() = runBlocking<Unit> {
        PairRecord.record("check")
        val rows = check.rows(2)
        val booted = PairRecord.bootTime()

        val start = check.starts(booted).firstOrNull() ?: throw AssertionError("no host start after the boot")
        assertEquals("the job's start: ${start.fields}", listOf("job", "job"), listOf(start["host"], start["cause"]))
        assertEquals("no activity started after the boot: the app scheduled nothing", emptyList<PairRecord.Traced>(), check.schedules(booted))
        rows.forEach { check.done(it.id, start.at) }
        check.assertWhole(rows.map { it.id })
    }
}
