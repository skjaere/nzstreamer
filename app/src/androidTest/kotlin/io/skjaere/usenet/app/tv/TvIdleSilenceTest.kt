package io.skjaere.usenet.app.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.IdleSilenceDeviceTest
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import org.junit.runner.RunWith

/** [IdleSilenceDeviceTest] on a real TV (tv36), whose player follows the audio route and plays as a TV box's does. */
@TvDevice
@RunWith(AndroidJUnit4::class)
class TvIdleSilenceTest : IdleSilenceDeviceTest() {
    override fun assumeDevice() = assumeTv()
}
