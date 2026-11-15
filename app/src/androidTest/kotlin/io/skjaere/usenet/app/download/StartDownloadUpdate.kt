package io.skjaere.usenet.app.download

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first half of the DownloadUpdate pair (L367, L389): one download of [PairFixture.HARBOR_LIGHTS] seeded as
 * [PairSeed] says, the user-initiated job its host; then it returns. The pairs' runner then opens the app as a user
 * does, waits for the transfer past a quarter, installs the app over itself (`install -r`), waits a minute for a host
 * start, else opens the app, then for done or reconcile's line, and runs [CheckDownloadUpdate].
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class StartDownloadUpdate {
    @Test
    fun seedsOneQueuedDownload() = runBlocking<Unit> {
        PairSeed.seed(PairSeed.Plan())
    }
}
