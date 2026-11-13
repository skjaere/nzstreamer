package io.skjaere.usenet.app

import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/**
 * A test for a real TV (the tv36 emulator): what a phone emulator showing the TV's layouts can't
 * show, such as Android 16's Back or the TV launcher. Each such test calls [assumeTv] first, so a
 * phone's run skips it.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TvDevice

/** Skips the test unless the device is a TV (declares Leanback). */
fun assumeTv() {
    val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
    assumeTrue("not a TV (no Leanback)", pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
}
