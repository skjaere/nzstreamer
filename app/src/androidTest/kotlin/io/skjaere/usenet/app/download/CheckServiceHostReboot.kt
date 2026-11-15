package io.skjaere.usenet.app.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The second half of the ServiceHostReboot pair (L334, L336, L375): after [StartServiceHostReboot], the app's open and
 * the runner's reboot of the emulator as the service downloaded under the debug override, the boot receiver, switched
 * on while the download could run and not exported, got the system's BOOT_COMPLETED and started the service: a host
 * start by it after the boot; done; the file whole. Android 14 has no ban on starting a dataSync service from the boot.
 * The boot's time is this process's reading of it.
 */
@RunWith(AndroidJUnit4::class)
class CheckServiceHostReboot {
    /** Only where its run names it, on a phone ([PairRun]). */
    @get:Rule
    val pair = PairRun()

    private val check = PairCheck()

    @After
    fun putBack() = runBlocking<Unit> { check.putBack() }

    @Test
    fun theBootReceiverStartsTheServiceAfterTheBootAndItIsDone() = runBlocking<Unit> {
        PairRecord.record("check")
        val row = check.rows(1).single()
        val booted = PairRecord.bootTime()

        val start = check.starts(booted).firstOrNull() ?: throw AssertionError("no host start after the boot")
        assertEquals("the boot receiver's start of the service: ${start.fields}", listOf("service", "boot"), listOf(start["host"], start["cause"]))
        check.done(row.id, start.at)
        check.assertWhole(listOf(row.id))
    }
}
