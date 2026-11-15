package io.skjaere.usenet.app.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first half of the ServiceHostKill pair (L367, L389): one download of [PairFixture.HARBOR_LIGHTS] seeded as
 * [PairSeed] says, the dataSync service its host by the debug override (L336); then it returns. The pairs' runner then
 * opens the app as a user does, waits for the transfer past a quarter, kills the app's process (`kill -9`), waits for a
 * host start after it and done, and runs [CheckServiceHostKill].
 */
@RunWith(AndroidJUnit4::class)
class StartServiceHostKill {
    @Test
    fun seedsOneQueuedDownload() = runBlocking<Unit> {
        PairSeed.seed(PairSeed.Plan(serviceHost = true))
    }
}
