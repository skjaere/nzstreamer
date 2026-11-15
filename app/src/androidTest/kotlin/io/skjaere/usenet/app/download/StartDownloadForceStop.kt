package io.skjaere.usenet.app.download

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first half of the DownloadForceStop pair (L367, L389): two downloads of [PairFixture.HARBOR_LIGHTS] seeded as
 * [PairSeed] says, the user-initiated job their host; then it returns. The pairs' runner then opens the app as a user
 * does, waits for the transfer past a quarter, force-stops the app (`am force-stop`), waits for reconcile's line
 * holding the first, opening the app again if none comes in a minute, and runs [CheckDownloadForceStop].
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class StartDownloadForceStop {
    @Test
    fun seedsTwoQueuedDownloads() = runBlocking<Unit> {
        PairSeed.seed(PairSeed.Plan(rows = 2))
    }
}
