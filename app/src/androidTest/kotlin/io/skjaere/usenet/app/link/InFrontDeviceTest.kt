package io.skjaere.usenet.app.link

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.pressHome
import io.skjaere.usenet.core.UsenetUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * InFront in the app (L31): in front with MainActivity, and all the way through a player's start and finish over it,
 * since the player starts before the screen it covers stops and that screen starts again before the player stops; out
 * of front about a second after Home. The player plays nothing (its stream fails at once): it only has to be started.
 */
@RunWith(AndroidJUnit4::class)
class InFrontDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val inFront = context.container.inFront.value

    private fun until(what: String, timeoutMs: Long = 10_000, done: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!done()) {
            check(SystemClock.uptimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    private inline fun <reified A : Activity> live(stage: Stage): List<A> =
        onMainThread { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).filterIsInstance<A>() }

    @Test
    fun inFrontThroughAPlayerAndOutOfFrontAfterHome() = runBlocking<Unit> {
        val seen = CopyOnWriteArrayList<Boolean>()
        val watch = launch(Dispatchers.Default) { inFront.collect { seen += it } }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { main ->
            until("the app's screens") { main.state.isAtLeast(Lifecycle.State.RESUMED) }
            assertTrue("in front with MainActivity", inFront.value)
            seen.clear()

            main.onActivity { it.startActivity(Intent(it, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URI, UsenetUris.build("0", 0))) }
            until("the player in front") { live<PlayerActivity>(Stage.RESUMED).isNotEmpty() }
            until("MainActivity stopped under it") { live<MainActivity>(Stage.STOPPED).isNotEmpty() }
            assertTrue("in front with the player", inFront.value)
            live<PlayerActivity>(Stage.RESUMED).forEach { player -> onMainThread { player.finish() } }
            until("MainActivity back") { live<MainActivity>(Stage.RESUMED).isNotEmpty() }
            until("the player gone") { live<PlayerActivity>(Stage.STOPPED).isEmpty() && live<PlayerActivity>(Stage.RESUMED).isEmpty() }
            // Past the grace: a moment out of front on the way would have shown by now.
            Thread.sleep(InFront.GRACE_MS * 2)
            assertTrue("never out of front through the player: $seen", seen.none { !it })
            assertTrue(inFront.value)

            pressHome()
            until("MainActivity stopped by Home") { live<MainActivity>(Stage.STOPPED).isNotEmpty() }
            val stopped = SystemClock.uptimeMillis()
            assertTrue("still in front just after Home, within the grace", inFront.value)
            until("out of front", timeoutMs = 5_000) { !inFront.value }
            val after = SystemClock.uptimeMillis() - stopped
            assertTrue("out of front only after the grace: $after ms", after >= InFront.GRACE_MS - 200)
            assertEquals("out of front once", listOf(false), seen.filter { !it })
        }
        watch.cancelAndJoin()
    }
}
