package io.skjaere.usenet.app.download

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first half of the DownloadKill pair (L367, L389): one download of [PairFixture.HARBOR_LIGHTS] seeded as
 * [PairSeed] says, the user-initiated job its host; then it returns. The pairs' runner then opens the app as a user
 * does, waits for the transfer past a quarter, kills the app's process (`kill -9`), waits for a host start after it and
 * done, and runs [CheckDownloadKill].
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class StartDownloadKill {
    /** Only where its run names it, on a phone ([PairRun]). */
    @get:Rule
    val pair = PairRun()

    @Test
    fun seedsOneQueuedDownload() = runBlocking<Unit> {
        PairSeed.seed(PairSeed.Plan())
    }
}
