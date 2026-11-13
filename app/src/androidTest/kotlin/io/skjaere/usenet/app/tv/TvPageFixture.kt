package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.data.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/*
 * What the TV page tests (TvShowScreenTest, TvMovieScreenTest) share: a page in the TV shell as a TV shows it, the app's
 * indexers swapped for a test's own and put back, an indexer that never answers, and an OK held down.
 */

@Serializable
data object PageTestRoute

/** [page] as a TV shows a page, forced on a phone emulator: in the TV shell (no rail beside it; its whole column), at [fontScale]. */
fun ComposeContentTestRule.setTvPage(fontScale: () -> Float = { 1f }, page: @Composable () -> Unit) {
    val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    setTvContent(fontScale) {
        val nav = rememberNavController()
        TvShell(nav, tabsFor(FormFactor.Tv), container.playback) {
            NavHost(nav, startDestination = PageTestRoute) { composable<PageTestRoute> { page() } }
        }
    }
}

/** Whether the mock-seed's fake indexer (10.0.2.2:8089) answers: it has nothing for the invented titles. */
fun fakeIndexerReachable(): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 8089), 2_000) } }.isSuccess

/** The app's indexers switched off for a test, and one of its own on as [id]; [restore] puts them back as they were. */
class PageIndexers(private val id: String) {
    private val settings = InstrumentationRegistry.getInstrumentation().targetContext.container.settings
    private var saved: AppSettings? = null

    fun use(name: String, url: String) = runBlocking {
        if (saved == null) saved = settings.settings.first()
        settings.indexers.first().filter { it.enabled && it.id != id }.forEach { settings.setIndexerEnabled(it.id, false) }
        settings.saveIndexer(id, name, url, "fake-indexer-key")
        Unit
    }

    fun restore() = runBlocking {
        val before = saved ?: return@runBlocking
        // Its key goes with it (restore only puts back the settings document).
        if (before.indexers.none { it.id == id } && settings.indexer(id) != null) settings.deleteIndexer(id)
        settings.restore(before)
        saved = null
    }
}

/** An indexer on this device that takes every connection and never answers, until closed: a search of it waits. */
class SilentIndexer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val connections = CopyOnWriteArrayList<Socket>()
    val url = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true, name = "silent-indexer") {
            while (!server.isClosed) runCatching { connections += server.accept() }
        }
    }

    override fun close() {
        server.close()
        connections.forEach { it.close() }
    }
}

/**
 * OK held on what has the focus, as a long press on a remote: [advance] moves the screen's clock past the long press's
 * time; then, the key still down, [held] (what the long press opened shows), the key's repeats a remote sends while it is
 * held, every 50 ms, and its up. The repeats and the up go to whatever window has the focus by then: a menu opened over
 * the screen gets them.
 */
fun longOk(advance: (Long) -> Unit, held: () -> Unit = {}) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val down = SystemClock.uptimeMillis()
    val press = KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0)
    instrumentation.sendKeySync(press)
    advance(1_500)
    held()
    repeat(OK_REPEATS) { i ->
        instrumentation.sendKeySync(KeyEvent.changeTimeRepeat(press, SystemClock.uptimeMillis(), i + 1))
        Thread.sleep(50)
    }
    instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
}

private const val OK_REPEATS = 4

/** The second press of a double OK on what opened a page [opened] (uptime) a moment before. */
fun earlyOk(opened: Long) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.sendKeySync(KeyEvent(opened + 100, opened + 100, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
    instrumentation.sendKeySync(KeyEvent(opened + 100, opened + 160, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
}
