package io.skjaere.usenet.app

import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner
import io.skjaere.usenet.app.cast.CastFakes
import io.skjaere.usenet.app.cast.Pocket
import io.skjaere.usenet.app.debrid.DevServices
import io.skjaere.usenet.app.debrid.ServiceEndpoints
import io.skjaere.usenet.app.discover.DiscoverLookup
import io.skjaere.usenet.app.discover.FakeDiscover

/**
 * The app's device tests' runner: before any test, Discover's lists come from invented titles ([FakeDiscover.DEFAULT]),
 * so no test that passes the Discover tab (a walk of every tab, the whole app's) asks TMDB, whatever key the device has.
 * A test's own FakeDiscover swaps in and puts this back.
 *
 * And for the whole run the app is refused picture-in-picture, as a user who turned it off in Android's settings has it
 * ([PictureInPictureAppOp]): a test that leaves a playing player, by Home or as ActivityScenario stops one (an activity of
 * its own in a task of its own, which Android takes for the user leaving), stops it as before, rather than wait out
 * ActivityScenario's 45 s for a stop the window never brings. The window's own tests let it in ([PictureInPictureAllowed]).
 * The device's setting is put back as the run ends; a run that dies first leaves the app refused until it is installed
 * again, as each Gradle and sharded run does.
 *
 * And Google's Cast software never starts (L73): Play services answers "missing" to Cast's gate, so the Play on sheet's
 * Cast group says Cast isn't available, with no Google call, and a fake session's player is a fake too, never Media3's.
 * A test that needs the group fakes Cast started over its own fake devices ([CastFakes]); CastStartRecordDeviceTest alone
 * puts Google's own back, in a run of its own.
 *
 * And a phone a test's process left in a pocket as it died ([Pocket]: Doze forced, battery saver, the restricted bucket,
 * the screen off, unplugged) is put back before the first test, so the tests after it, in a runner's next process, find
 * the phone as they expect it; so is a screen left off or locked ([wakeLeft]), which would leave their activities stopped
 * behind the lock screen.
 *
 * And a run never reaches the real debrid accounts a debug build is set up with unless asked (L182): before the app
 * starts, debug's debrid seeding is turned off ([DevServices.seeding]) and the services' clients point at an address that
 * answers nothing ([ServiceEndpoints.CLOSED]), so the debug build installed for the run saves no account or torrent
 * indexer from local.properties and sends no key anywhere. The instrumentation argument `debridLive=true` (the lead's
 * live classes alone) leaves both as the app has them. A class that needs the debrid fake points the clients there itself.
 */
class AppTestRunner : AndroidJUnitRunner() {
    /** The app's picture-in-picture mode as the run found it, put back as it ends; null once put back, or never read. */
    private var pictureInPicture: String? = null

    override fun onCreate(arguments: Bundle?) {
        DiscoverLookup.source = { FakeDiscover.DEFAULT }
        if (arguments?.getString("debridLive") != "true") {
            DevServices.seeding = false
            ServiceEndpoints.override = { ServiceEndpoints.CLOSED }
        }
        CastFakes.beforeTheApp()
        super.onCreate(arguments)
    }

    override fun onStart() {
        try {
            if (Pocket.putBackLeft(shellOnly(), targetContext.packageName)) Log.i(TAG, "A pocket a test left as its process died was put back")
        } catch (e: Exception) {
            // The run goes on: a test that needs the phone as it was says so.
            Log.e(TAG, "A pocket a test left not put back", e)
        }
        try {
            if (wakeLeft(shellOnly())) Log.i(TAG, "The screen a test left off or locked was woken and unlocked")
        } catch (e: Exception) {
            // The run goes on: a test that needs the screen on says so.
            Log.e(TAG, "The screen left off or locked", e)
        } catch (e: AssertionError) {
            Log.e(TAG, "The screen left off or locked", e)
        }
        try {
            val automation = shellOnly()
            pictureInPicture = PictureInPictureAppOp.mode(automation)
            PictureInPictureAppOp.set(PictureInPictureAppOp.IGNORE, automation)
        } catch (e: Exception) {
            // The run goes on: PictureInPictureFootingTest says the app is let in.
            Log.e(TAG, "Picture-in-picture not refused for the run", e)
        }
        super.onStart()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        pictureInPicture?.let { found ->
            pictureInPicture = null
            try {
                PictureInPictureAppOp.set(found, shellOnly())
            } catch (e: Exception) {
                Log.e(TAG, "Picture-in-picture not put back to $found", e)
            }
        }
        super.finish(resultCode, results)
    }

    /**
     * UiAutomation for the shell's commands alone, outside accessibility where Android allows it (13+). Connected as an
     * accessibility service, as a test's uiAutomation is, it would have the app see accessibility on in every test from
     * the first, where today only those after one that uses it do.
     */
    private fun shellOnly(): UiAutomation =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY) else uiAutomation

    private companion object {
        const val TAG = "AppTestRunner"
    }
}
