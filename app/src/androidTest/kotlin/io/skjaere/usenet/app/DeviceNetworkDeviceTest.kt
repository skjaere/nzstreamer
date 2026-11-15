package io.skjaere.usenet.app

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.download.DownloadRunnerDeps
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The device's default network as the app follows it (L381), on a phone: the container's one default network callback,
 * whose answers the downloads' runner and writer and their service read.
 * - Wi-Fi off with mobile data off too: no network, so neither answer is validated; Wi-Fi back on, both say validated,
 *   unmetered Wi-Fi again. Each answer comes from the callback's words: the first read can't give either change.
 * - The runner's deps, as the app builds them, follow the container's flow, so the writer's backoff ends on its return.
 *
 * Put back after each, whatever happened: Wi-Fi on, mobile data as it was, and the validated Wi-Fi waited for.
 */
@RunWith(AndroidJUnit4::class)
class DeviceNetworkDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    // What the test changed of the device, so its end puts back exactly that.
    private var wifiOff = false
    private var dataWas: String? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        assertFalse("a phone", container.television)
        waitFor("the device on validated Wi-Fi") { validatedWifi() }
        waitFor("the container's default network: validated Wi-Fi, unmetered", ANSWER_MILLIS) { if (container.defaultNetwork.now.value == WIFI && container.validatedNetwork.value) Unit else null }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        val failures = mutableListOf<Throwable>()
        fun back(what: () -> Unit) {
            try {
                what()
            } catch (e: Throwable) {
                failures += e
            }
        }
        if (wifiOff) back { shell("svc wifi enable") }
        dataWas?.let { was -> back { shell(if (was == "1") "svc data enable" else "svc data disable") } }
        if (wifiOff) back { runBlocking { waitFor("Wi-Fi back for the next test", BACK_MILLIS) { validatedWifi() } } }
        failures.firstOrNull()?.let { first -> failures.drop(1).forEach(first::addSuppressed); throw first }
    }

    @Test
    fun withNoNetworkNeitherAnswerIsValidatedAndBackOnWifiBothAre() = runBlocking<Unit> {
        dataWas = shell("settings get global mobile_data").trim()
        shell("svc data disable")
        wifiOff = true
        shell("svc wifi disable")
        waitFor("no validated network, nothing offered", ANSWER_MILLIS) {
            if (!container.validatedNetwork.value && container.defaultNetwork.now.value == NetworkNow.NONE) Unit else null
        }

        shell("svc wifi enable")
        wifiOff = false
        waitFor("validated Wi-Fi again, unmetered", BACK_MILLIS) {
            if (container.validatedNetwork.value && container.defaultNetwork.now.value == WIFI) Unit else null
        }
    }

    @Test
    fun theRunnersDepsAsTheAppBuildsThemFollowTheContainersFlow() {
        // The writer is made inside the deps from this one flow (L381): another here would leave its backoff unwoken.
        assertSame(container.validatedNetwork, DownloadRunnerDeps.of(container, context).network)
    }

    private fun validatedWifi(): Unit? = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }?.let { }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    private companion object {
        val WIFI = NetworkNow(usable = true, unmetered = true, wifi = true)

        /** How long the callback takes to say a change. */
        const val ANSWER_MILLIS = 30_000L

        /** Wi-Fi's own time to connect and be validated again. */
        const val BACK_MILLIS = 90_000L
    }
}
