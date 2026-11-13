package io.skjaere.usenet.app.link

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.Playback
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue

/**
 * A TV in the test's process on this device's own address (L14), paired with the app's link key, kept in its link.json and
 * chosen, its link Ready: the app's own links, start and bar reach it as they reach a TV. [play] starts a play there as
 * Play with it chosen does, which the TV takes as [SESSION] and plays, so the app follows it (the Playing on bar);
 * [say] is the TV's word on its link. [close] puts back what the app had: no start, nothing followed, This phone chosen,
 * the TV gone from link.json. The TV and the show are invented.
 */
class PlayingTv(base: String = "Living room TV") : AutoCloseable {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val link = container.data.link
    val tv: FakeTv
    val name: String get() = tv.name

    init {
        val phone = runBlocking { link.identity().identity.key.id }
        tv = FakeTv(context, inventedTvName(base), paired = setOf(phone), advertised = false)
        runBlocking { assertTrue(link.addTv(tv.key.id, tv.name, tv.address.hostAddress!!, tv.port)) }
        container.playOn.choose(tv.key.id)
        waitFor("the TV linked; now ${container.playOn.target.value}") { (container.playOn.target.value as? PlayTarget.Tv)?.state == TvState.Ready }
    }

    /** The TV says [message] on its newest link to this phone. */
    fun say(message: LinkMessage) {
        waitFor("a link to the TV") { tv.links.isNotEmpty() }
        tv.links.last().send(message)
    }

    /** What the TV says of the play: Harbor Lights S01E03 of [show], [state] at [positionMs] of 52 minutes. */
    fun status(state: Playback, positionMs: Long = 1_930_000, show: Int = SHOW) =
        Status(SESSION, state, mine = true, title = "Harbor Lights", subtitle = "S01E03 · Episode 3", watchKey = WatchKey.episode(show, 1, 3), positionMs = positionMs, durationMs = 3_120_000)

    /** A play started on the TV, which takes it as [SESSION], its player [state]: the app follows it, its bar showing. */
    fun play(state: Playback, show: Int = SHOW) {
        val before = tv.received.size
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            container.tvStart.start(tv.key.id, "Harbor Lights", "S01E03 · Episode 3") { TvAsk(episode = LinkMessage.Play.Episode(show, "Harbor Lights", season = 1, episode = 3)) }
        }
        waitFor("the TV asked to play") { tv.received.drop(before).any { it is LinkMessage.Play } }
        val play = tv.received.drop(before).filterIsInstance<LinkMessage.Play>().single()
        say(LinkMessage.Ack(play.seq, SESSION))
        // As its hello names it to a link coming up again.
        tv.session = LinkMessage.Hello.Session(SESSION, mine = true)
        waitFor("the app following the TV's play") { container.tvPlaying.now.value?.session == SESSION }
        say(status(state, show = show))
        waitFor("the bar") { container.tvPlaying.now.value?.bar != null }
    }

    inline fun <reified T : LinkMessage> sent(): List<T> = tv.received.filterIsInstance<T>()

    override fun close() {
        // The app's start and bar are the process's: the next class finds them as they were.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { container.tvStart.close() }
        container.tvPlaying.close()
        container.playOn.choose(null)
        tv.close()
        runBlocking { link.forgetTv(tv.key.id) }
    }

    private fun waitFor(what: String, millis: Long = 30_000, done: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + millis
        while (!done()) {
            assertTrue(what, SystemClock.uptimeMillis() < until)
            Thread.sleep(50)
        }
    }

    companion object {
        const val SESSION = "s-1"

        /** An invented show's TMDB id: nothing real has it. */
        const val SHOW = 991_605
    }
}
