package io.skjaere.usenet.app.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first half of the ServiceHostReboot pair (L367, L389): one download of [PairFixture.HARBOR_LIGHTS] seeded as
 * [PairSeed] says, the dataSync service its host by the debug override (L336); then it returns. The pairs' runner then
 * opens the app as a user does, waits for the transfer past a quarter, reboots the emulator, waits for a host start
 * after the boot and done, and runs [CheckServiceHostReboot].
 */
@RunWith(AndroidJUnit4::class)
class StartServiceHostReboot {
    @Test
    fun seedsOneQueuedDownload() = runBlocking<Unit> {
        PairSeed.seed(PairSeed.Plan(serviceHost = true))
    }
}
